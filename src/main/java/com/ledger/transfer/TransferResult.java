package com.ledger.transfer;

/**
 * Outcome of a create call: the stored transfer, and whether this call created
 * it ({@code true}) or replayed an earlier one with the same idempotency key.
 */
public record TransferResult(Transfer transfer, boolean created) {
}
