package com.ledger.transfer;

/**
 * A transfer the ledger refused. Carries a domain reason rather than an HTTP
 * status so the service stays transport-agnostic; the web layer maps it.
 */
public class TransferRejectedException extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST,
        ACCOUNT_NOT_FOUND,
        INSUFFICIENT_FUNDS,
        IDEMPOTENCY_KEY_REUSED
    }

    private final Reason reason;

    public TransferRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
