package com.ledger.transfer;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A committed transfer as stored in the {@code transfers} table. Amount is in minor units. */
public record Transfer(
        UUID id,
        String idempotencyKey,
        long fromAccountId,
        long toAccountId,
        long amount,
        OffsetDateTime createdAt) {

    /** Whether a retried request asks for exactly this transfer. */
    boolean hasSameParameters(long fromAccountId, long toAccountId, long amount) {
        return this.fromAccountId == fromAccountId
                && this.toAccountId == toAccountId
                && this.amount == amount;
    }
}
