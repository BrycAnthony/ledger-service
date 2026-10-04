package com.ledger.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.UUID;

import com.ledger.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the V2 schema enforces ledger invariants in Postgres itself,
 * independent of any application code.
 *
 * <p>Entries are append-only (even TRUNCATE is blocked), so tests cannot clean
 * up between runs. Instead each test creates its own accounts and transfer IDs
 * and asserts only on those, keeping tests independent of one another.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class LedgerSchemaTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String INTEGRITY_CONSTRAINT_VIOLATION = "23000";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    // --- accounts.balance >= 0 -------------------------------------------

    @Test
    void insertWithNegativeBalanceIsRejected() {
        Throwable thrown = catchThrowable(() -> jdbc.update(
                "INSERT INTO accounts (name, balance) VALUES (?, ?)", "overdrawn", -1L));

        assertConstraintViolation(thrown, CHECK_VIOLATION, "accounts_balance_non_negative");
    }

    @Test
    void updateToNegativeBalanceIsRejected() {
        // The relative update is the path a real debit takes, so prove the
        // CHECK catches it rather than relying on an app-side balance read.
        long id = createAccount(100);

        Throwable thrown = catchThrowable(() -> jdbc.update(
                "UPDATE accounts SET balance = balance - ? WHERE id = ?", 101L, id));

        assertConstraintViolation(thrown, CHECK_VIOLATION, "accounts_balance_non_negative");
        assertThat(balanceOf(id)).isEqualTo(100);
    }

    @Test
    void zeroBalanceIsAllowed() {
        long id = createAccount(100);

        jdbc.update("UPDATE accounts SET balance = balance - ? WHERE id = ?", 100L, id);

        assertThat(balanceOf(id)).isZero();
    }

    // --- entries per transfer sum to zero ---------------------------------

    @Test
    void balancedTransferCommits() {
        long from = createAccount(1_000);
        long to = createAccount(0);
        UUID transfer = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            insertEntry(from, transfer, -500);
            insertEntry(to, transfer, 500);
        });

        assertThat(entryCount(transfer)).isEqualTo(2);
    }

    @Test
    void unbalancedTransferIsRejectedAtCommit() {
        long from = createAccount(1_000);
        long to = createAccount(0);
        UUID transfer = UUID.randomUUID();

        // Both INSERTs succeed individually; the deferred trigger fires at
        // COMMIT and rolls back the whole transaction.
        Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(status -> {
            insertEntry(from, transfer, -400);
            insertEntry(to, transfer, 500);
        }));

        assertConstraintViolation(thrown, CHECK_VIOLATION, "entries_transfer_balanced");
        assertThat(entryCount(transfer)).isZero();
    }

    @Test
    void singleLegTransferIsRejected() {
        long id = createAccount(0);
        UUID transfer = UUID.randomUUID();

        // Autocommit: the statement's implicit commit runs the deferred check.
        Throwable thrown = catchThrowable(() -> insertEntry(id, transfer, 500));

        assertConstraintViolation(thrown, CHECK_VIOLATION, "entries_transfer_balanced");
        assertThat(entryCount(transfer)).isZero();
    }

    @Test
    void zeroAmountEntryIsRejected() {
        long id = createAccount(0);

        Throwable thrown = catchThrowable(() -> insertEntry(id, UUID.randomUUID(), 0));

        assertConstraintViolation(thrown, CHECK_VIOLATION, "entries_amount_non_zero");
    }

    // --- entries are append-only -----------------------------------------

    @Test
    void entriesCannotBeUpdatedOrDeleted() {
        long from = createAccount(1_000);
        long to = createAccount(0);
        UUID transfer = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            insertEntry(from, transfer, -500);
            insertEntry(to, transfer, 500);
        });

        Throwable update = catchThrowable(() -> jdbc.update(
                "UPDATE entries SET amount = 1 WHERE transfer_id = ?", transfer));
        Throwable delete = catchThrowable(() -> jdbc.update(
                "DELETE FROM entries WHERE transfer_id = ?", transfer));
        Throwable truncate = catchThrowable(() -> jdbc.execute("TRUNCATE entries"));

        assertConstraintViolation(update, INTEGRITY_CONSTRAINT_VIOLATION, "entries_append_only");
        assertConstraintViolation(delete, INTEGRITY_CONSTRAINT_VIOLATION, "entries_append_only");
        assertConstraintViolation(truncate, INTEGRITY_CONSTRAINT_VIOLATION, "entries_append_only");
        assertThat(entryCount(transfer)).isEqualTo(2);
    }

    // --- helpers ----------------------------------------------------------

    private long createAccount(long balance) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (name, balance) VALUES (?, ?) RETURNING id",
                Long.class, "acct-" + UUID.randomUUID(), balance);
    }

    private long balanceOf(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private void insertEntry(long accountId, UUID transferId, long amount) {
        jdbc.update("INSERT INTO entries (account_id, transfer_id, amount) VALUES (?, ?, ?)",
                accountId, transferId, amount);
    }

    private int entryCount(UUID transferId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM entries WHERE transfer_id = ?", Integer.class, transferId);
    }

    /**
     * Asserts on the Postgres error itself, both SQLSTATE and constraint name,
     * so a test only passes if <em>this</em> rule fired, not merely "some
     * write failed". Spring's exception wrapping differs between statement
     * and commit failures, so walk the cause chain to the driver exception.
     */
    private static void assertConstraintViolation(Throwable thrown, String sqlState, String constraint) {
        assertThat(thrown).as("expected a database rejection").isNotNull();

        PSQLException psql = null;
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof PSQLException p) {
                psql = p;
                break;
            }
        }

        // Cast: SQLException implements Iterable, making assertThat ambiguous.
        assertThat((Object) psql).as("no PSQLException in cause chain of %s", thrown).isNotNull();
        assertThat(psql.getSQLState()).isEqualTo(sqlState);
        String actualConstraint = psql.getServerErrorMessage() == null
                ? null
                : psql.getServerErrorMessage().getConstraint();
        assertThat(actualConstraint).isEqualTo(constraint);
    }
}
