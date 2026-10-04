package com.ledger.transfer;

import static com.ledger.transfer.TransferRejectedException.Reason.ACCOUNT_NOT_FOUND;
import static com.ledger.transfer.TransferRejectedException.Reason.IDEMPOTENCY_KEY_REUSED;
import static com.ledger.transfer.TransferRejectedException.Reason.INSUFFICIENT_FUNDS;
import static com.ledger.transfer.TransferRejectedException.Reason.INVALID_REQUEST;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransferService {

    // SQLSTATEs are standard and need no driver classes, which keeps the
    // Postgres driver at runtime scope.
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private static final RowMapper<Transfer> TRANSFER_ROW = (rs, rowNum) -> new Transfer(
            rs.getObject("id", UUID.class),
            rs.getString("idempotency_key"),
            rs.getLong("from_account_id"),
            rs.getLong("to_account_id"),
            rs.getLong("amount"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public TransferService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * Moves {@code amount} minor units between accounts, exactly once per
     * idempotency key.
     *
     * <p>The transaction is managed programmatically, not with
     * {@code @Transactional}, because the duplicate-key path must run
     * <em>after</em> rollback. In Postgres, any error aborts the current
     * transaction, so the original transfer can't be read inside it.
     */
    public TransferResult create(String idempotencyKey, long fromAccountId, long toAccountId, long amount) {
        validate(idempotencyKey, fromAccountId, toAccountId, amount);
        try {
            Transfer created = tx.execute(status -> execute(idempotencyKey, fromAccountId, toAccountId, amount));
            return new TransferResult(created, true);
        } catch (DuplicateIdempotencyKey e) {
            // A unique violation is only raised once the conflicting row has
            // committed (an in-flight one makes our INSERT wait instead), so
            // the original is visible to this fresh statement.
            Transfer original = jdbc.queryForObject(
                    "SELECT * FROM transfers WHERE idempotency_key = ?", TRANSFER_ROW, idempotencyKey);
            if (!original.hasSameParameters(fromAccountId, toAccountId, amount)) {
                throw new TransferRejectedException(IDEMPOTENCY_KEY_REUSED,
                        "Idempotency key was already used for a transfer with different parameters");
            }
            return new TransferResult(original, false);
        }
    }

    private Transfer execute(String idempotencyKey, long fromAccountId, long toAccountId, long amount) {
        // Insert first: the unique constraint, not a prior SELECT, decides
        // whether this key is new. If anything below fails, the whole
        // transaction rolls back, including this row, so a client may retry
        // a rejected transfer (e.g. after topping up) with the same key.
        Transfer transfer = insertTransfer(idempotencyKey, fromAccountId, toAccountId, amount);

        // Update balances in ascending account-ID order. Each UPDATE takes the
        // row lock, so every transfer locks accounts in the same global order
        // and A->B racing B->A can't deadlock. (The FK checks on the INSERT
        // above take FOR KEY SHARE locks, which don't conflict with the
        // FOR NO KEY UPDATE lock a balance-only UPDATE takes.)
        long first = Math.min(fromAccountId, toAccountId);
        long second = Math.max(fromAccountId, toAccountId);
        applyBalanceChange(first, first == fromAccountId ? -amount : amount);
        applyBalanceChange(second, second == fromAccountId ? -amount : amount);

        // The two legs sum to zero; the deferred trigger from V2 verifies it at commit.
        insertEntry(transfer.id(), fromAccountId, -amount);
        insertEntry(transfer.id(), toAccountId, amount);
        return transfer;
    }

    private Transfer insertTransfer(String idempotencyKey, long fromAccountId, long toAccountId, long amount) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO transfers (idempotency_key, from_account_id, to_account_id, amount)
                    VALUES (?, ?, ?, ?)
                    RETURNING *
                    """, TRANSFER_ROW, idempotencyKey, fromAccountId, toAccountId, amount);
        } catch (DataIntegrityViolationException e) {
            String state = sqlState(e);
            // The only unique constraints on transfers are the PK (a
            // DB-generated UUID) and the idempotency key, so a unique
            // violation here means the key has been seen before.
            if (UNIQUE_VIOLATION.equals(state)) {
                throw new DuplicateIdempotencyKey();
            }
            if (FOREIGN_KEY_VIOLATION.equals(state)) {
                throw new TransferRejectedException(ACCOUNT_NOT_FOUND, "Account not found");
            }
            throw e;
        }
    }

    private void applyBalanceChange(long accountId, long delta) {
        try {
            jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", delta, accountId);
        } catch (DataIntegrityViolationException e) {
            // Non-negativity is enforced by the accounts_balance_non_negative
            // CHECK, not by reading the balance first, which would race.
            if (CHECK_VIOLATION.equals(sqlState(e))) {
                throw new TransferRejectedException(INSUFFICIENT_FUNDS, "Insufficient funds");
            }
            throw e;
        }
    }

    private void insertEntry(UUID transferId, long accountId, long amount) {
        jdbc.update("INSERT INTO entries (account_id, transfer_id, amount) VALUES (?, ?, ?)",
                accountId, transferId, amount);
    }

    // Mirrors the DB constraints so bad input gets a clear 400 instead of a
    // constraint error; the CHECKs in V3 remain the backstop.
    private static void validate(String idempotencyKey, long fromAccountId, long toAccountId, long amount) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new TransferRejectedException(INVALID_REQUEST,
                    "Idempotency key must be 1-" + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        if (amount <= 0) {
            throw new TransferRejectedException(INVALID_REQUEST, "Amount must be positive");
        }
        if (fromAccountId == toAccountId) {
            throw new TransferRejectedException(INVALID_REQUEST, "Cannot transfer to the same account");
        }
    }

    private static String sqlState(DataAccessException e) {
        return e.getMostSpecificCause() instanceof SQLException sql ? sql.getSQLState() : null;
    }

    /** Internal signal: rolls back the transaction, then triggers the replay lookup. */
    private static final class DuplicateIdempotencyKey extends RuntimeException {
        DuplicateIdempotencyKey() {
            // Control flow only; skip the stack trace.
            super(null, null, false, false);
        }
    }
}
