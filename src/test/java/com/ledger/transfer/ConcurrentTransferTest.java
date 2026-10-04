package com.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.ledger.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves account-ordered locking prevents deadlock under concurrent transfers.
 *
 * <p>Transfers run in both directions between the same account pairs. If each
 * transfer locked "from" before "to", A->B and B->A would each hold one row
 * lock while waiting on the other, and Postgres would abort one with a
 * deadlock (SQLSTATE 40P01). Locking in ascending ID order means both lock
 * min(A, B) first, so one simply waits for the other.
 *
 * <p>This calls {@link TransferService} directly rather than POST /transfers so
 * a failure surfaces as the real exception with its SQLSTATE, not an opaque 500.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // One connection per transfer so all 50 hold row locks at the same time.
        // With the default pool of 10, most transfers queue for a connection
        // instead of contending for locks, and a lock-ordering bug can go unnoticed.
        properties = "spring.datasource.hikari.maximum-pool-size=" + ConcurrentTransferTest.TRANSFERS)
@Import(TestcontainersConfiguration.class)
class ConcurrentTransferTest {

    private static final String DEADLOCK_DETECTED = "40P01";

    // Few accounts means many transfers per pair, so opposing transfers collide often.
    private static final int ACCOUNTS = 3;
    static final int TRANSFERS = 50;
    // Large enough that no interleaving can overdraw an account, so every
    // transfer must succeed and any failure is a real concurrency bug.
    private static final long INITIAL_BALANCE = 1_000_000;

    @Autowired
    TransferService transfers;

    @Autowired
    JdbcTemplate jdbc;

    private record Planned(String key, long from, long to, long amount) {
    }

    /**
     * Test-only instrumentation: pause briefly after each balance UPDATE while
     * its row lock is still held. Real transfers are so fast that opposing
     * transactions often don't overlap, so a lock-ordering bug would only fail
     * this test some of the time. The pause makes every opposing pair overlap,
     * so unordered locking deadlocks on every run.
     *
     * <p>Safe to install: the pool-size property gives this class its own
     * Spring context and therefore its own Postgres container, and the trigger
     * is dropped after the test.
     */
    @BeforeEach
    void widenLockWindow() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_hold_account_lock() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    PERFORM pg_sleep(0.02);
                    RETURN NULL;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER test_hold_account_lock
                    AFTER UPDATE ON accounts
                    FOR EACH ROW EXECUTE FUNCTION test_hold_account_lock()
                """);
    }

    @AfterEach
    void restoreLockWindow() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_hold_account_lock ON accounts");
        jdbc.execute("DROP FUNCTION IF EXISTS test_hold_account_lock()");
    }

    @Test
    void opposingConcurrentTransfersDoNotDeadlock() throws Exception {
        List<Long> accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            accounts.add(createAccount(INITIAL_BALANCE));
        }

        // Every pair of accounts, used in both directions. Consecutive
        // transfers on a pair alternate direction, so A->B and B->A are
        // always in flight against each other.
        List<long[]> pairs = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            for (int j = i + 1; j < ACCOUNTS; j++) {
                pairs.add(new long[] {accounts.get(i), accounts.get(j)});
            }
        }
        List<Planned> plan = new ArrayList<>();
        for (int n = 0; n < TRANSFERS; n++) {
            long[] pair = pairs.get(n % pairs.size());
            boolean forward = (n / pairs.size()) % 2 == 0;
            long amount = (n % 7 + 1) * 100L;
            plan.add(forward
                    ? new Planned("deadlock-" + UUID.randomUUID(), pair[0], pair[1], amount)
                    : new Planned("deadlock-" + UUID.randomUUID(), pair[1], pair[0], amount));
        }
        assertThat(plan).as("plan must exercise both directions on each pair")
                .anyMatch(p -> p.from() == pairs.get(0)[0] && p.to() == pairs.get(0)[1])
                .anyMatch(p -> p.from() == pairs.get(0)[1] && p.to() == pairs.get(0)[0]);

        // One thread per transfer, all released at once by the latch.
        List<TransferResult> completed = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(TRANSFERS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<TransferResult>> futures = new ArrayList<>();
            for (Planned p : plan) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return transfers.create(p.key(), p.from(), p.to(), p.amount());
                }));
            }
            start.countDown();

            for (Future<TransferResult> f : futures) {
                try {
                    completed.add(f.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // Deadlocks are checked first so that failure gets the most specific message.
        assertThat(failures).as("transfers aborted by a Postgres deadlock (40P01)")
                .filteredOn(ConcurrentTransferTest::isDeadlock)
                .isEmpty();
        assertThat(failures).as("transfers that failed for any reason").isEmpty();
        assertThat(completed).hasSize(TRANSFERS).allMatch(TransferResult::created);

        // Every planned transfer was recorded once, with two balanced legs.
        for (Planned p : plan) {
            assertThat(jdbc.queryForList(
                    "SELECT e.amount FROM entries e JOIN transfers t ON t.id = e.transfer_id "
                            + "WHERE t.idempotency_key = ?",
                    Long.class, p.key()))
                    .as("entries for %s", p)
                    .containsExactlyInAnyOrder(-p.amount(), p.amount());
        }

        // Balances reconcile both ways: against the plan, and against the
        // ledger. Accounts are seeded directly (no entries), so balance must
        // equal the seed plus the sum of the account's entries.
        Map<Long, Long> expected = new HashMap<>();
        accounts.forEach(a -> expected.put(a, INITIAL_BALANCE));
        for (Planned p : plan) {
            expected.merge(p.from(), -p.amount(), Long::sum);
            expected.merge(p.to(), p.amount(), Long::sum);
        }
        long total = 0;
        for (long account : accounts) {
            long balance = balanceOf(account);
            assertThat(balance).as("balance of account %d vs plan", account)
                    .isEqualTo(expected.get(account));
            assertThat(balance).as("balance of account %d vs its entries", account)
                    .isEqualTo(INITIAL_BALANCE + sumOfEntries(account));
            total += balance;
        }
        // Transfers move money between accounts, never create or destroy it.
        assertThat(total).isEqualTo(ACCOUNTS * INITIAL_BALANCE);
    }

    private static boolean isDeadlock(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && DEADLOCK_DETECTED.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private long createAccount(long balance) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (name, balance) VALUES (?, ?) RETURNING id",
                Long.class, "acct-" + UUID.randomUUID(), balance);
    }

    private long balanceOf(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private long sumOfEntries(long accountId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM entries WHERE account_id = ?", Long.class, accountId);
    }
}
