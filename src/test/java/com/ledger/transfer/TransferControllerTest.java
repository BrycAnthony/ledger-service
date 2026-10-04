package com.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end tests for POST /transfers against real Postgres.
 *
 * <p>Each test uses fresh accounts and idempotency keys, so tests stay
 * independent without cleanup (entries are append-only and can't be deleted).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TransferControllerTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Test
    void createsTransferAndMovesBalances() throws Exception {
        long from = createAccount(1_000);
        long to = createAccount(0);
        String key = newKey();

        ResponseEntity<String> response = post(key, from, to, 300);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Transfer transfer = json.readValue(response.getBody(), Transfer.class);
        assertThat(transfer.idempotencyKey()).isEqualTo(key);
        assertThat(transfer.amount()).isEqualTo(300);
        assertThat(balanceOf(from)).isEqualTo(700);
        assertThat(balanceOf(to)).isEqualTo(300);
        assertThat(entryAmounts(transfer.id())).containsExactlyInAnyOrder(-300L, 300L);
    }

    // --- idempotency: same key, same parameters ---------------------------

    @Test
    void replayWithSameParametersReturnsOriginalTransfer() throws Exception {
        long from = createAccount(1_000);
        long to = createAccount(0);
        String key = newKey();

        ResponseEntity<String> first = post(key, from, to, 300);
        ResponseEntity<String> replay = post(key, from, to, 300);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The replay returns the stored original (same id, same timestamp),
        // not a newly built object that merely looks like it.
        assertThat(json.readValue(replay.getBody(), Transfer.class))
                .isEqualTo(json.readValue(first.getBody(), Transfer.class));
        // Money moved exactly once.
        assertThat(transferCount(key)).isEqualTo(1);
        assertThat(balanceOf(from)).isEqualTo(700);
        assertThat(balanceOf(to)).isEqualTo(300);
    }

    @Test
    void concurrentRequestsWithSameKeyMoveMoneyOnce() throws Exception {
        // The race that insert-first exists for. SELECT-then-INSERT would let
        // several of these requests see "not found" and each move money.
        long from = createAccount(1_000);
        long to = createAccount(0);
        String key = newKey();
        int requests = 8;

        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            Callable<ResponseEntity<String>> call = () -> {
                start.await();
                return post(key, from, to, 300);
            };
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(call));
            }
            start.countDown();

            List<HttpStatus> statuses = new ArrayList<>();
            List<UUID> ids = new ArrayList<>();
            for (Future<ResponseEntity<String>> f : futures) {
                ResponseEntity<String> r = f.get();
                statuses.add(HttpStatus.valueOf(r.getStatusCode().value()));
                ids.add(json.readValue(r.getBody(), Transfer.class).id());
            }

            assertThat(statuses).containsOnlyOnce(HttpStatus.CREATED);
            assertThat(statuses).filteredOn(s -> s == HttpStatus.OK).hasSize(requests - 1);
            assertThat(ids).containsOnly(ids.get(0));
        } finally {
            pool.shutdownNow();
        }

        assertThat(transferCount(key)).isEqualTo(1);
        assertThat(balanceOf(from)).isEqualTo(700);
        assertThat(balanceOf(to)).isEqualTo(300);
    }

    // --- idempotency: same key, different parameters ----------------------

    @Test
    void sameKeyWithDifferentParametersReturns409() {
        long from = createAccount(1_000);
        long to = createAccount(0);
        long other = createAccount(0);
        String key = newKey();
        assertThat(post(key, from, to, 300).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> differentAmount = post(key, from, to, 999);
        ResponseEntity<String> differentDestination = post(key, from, other, 300);
        ResponseEntity<String> reversedDirection = post(key, to, from, 300);

        assertThat(differentAmount.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(differentDestination.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reversedDirection.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Nothing beyond the original transfer happened.
        assertThat(transferCount(key)).isEqualTo(1);
        assertThat(balanceOf(from)).isEqualTo(700);
        assertThat(balanceOf(to)).isEqualTo(300);
        assertThat(balanceOf(other)).isZero();
    }

    // --- rejections -------------------------------------------------------

    @Test
    void insufficientFundsIsRejectedAndNothingIsRecorded() {
        long from = createAccount(100);
        long to = createAccount(0);
        String key = newKey();

        ResponseEntity<String> response = post(key, from, to, 101);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // The transfer row rolled back with the failed debit, so the key is
        // free for a retry.
        assertThat(transferCount(key)).isZero();
        assertThat(balanceOf(from)).isEqualTo(100);
        assertThat(balanceOf(to)).isZero();
    }

    @Test
    void fractionalAmountIsRejected() {
        long from = createAccount(1_000);
        long to = createAccount(0);

        ResponseEntity<String> response = exchange(newKey(),
                "{\"fromAccountId\": %d, \"toAccountId\": %d, \"amount\": 10.99}".formatted(from, to));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(balanceOf(from)).isEqualTo(1_000);
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        long from = createAccount(1_000);
        long to = createAccount(0);

        ResponseEntity<String> response = exchange(null,
                "{\"fromAccountId\": %d, \"toAccountId\": %d, \"amount\": 300}".formatted(from, to));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(balanceOf(from)).isEqualTo(1_000);
    }

    // --- helpers ----------------------------------------------------------

    private ResponseEntity<String> post(String key, long from, long to, long amount) {
        try {
            return exchange(key, json.writeValueAsString(
                    Map.of("fromAccountId", from, "toAccountId", to, "amount", amount)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<String> exchange(String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return rest.exchange("/transfers", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    private long createAccount(long balance) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (name, balance) VALUES (?, ?) RETURNING id",
                Long.class, "acct-" + UUID.randomUUID(), balance);
    }

    private long balanceOf(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private int transferCount(String key) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM transfers WHERE idempotency_key = ?", Integer.class, key);
    }

    private List<Long> entryAmounts(UUID transferId) {
        return jdbc.queryForList("SELECT amount FROM entries WHERE transfer_id = ?", Long.class, transferId);
    }
}
