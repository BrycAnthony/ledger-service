package com.ledger.transfer;

import static com.ledger.transfer.TransferRejectedException.Reason.INVALID_REQUEST;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

    /**
     * Boxed so a missing field is detectable. With primitives, Jackson
     * would silently default it to 0.
     */
    public record CreateTransferRequest(Long fromAccountId, Long toAccountId, Long amount) {
    }

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    /**
     * The idempotency key travels in the {@code Idempotency-Key} header (the
     * common convention) rather than the body, so it stays separate from the
     * parameters it guards.
     *
     * <p>201 for a new transfer, 200 for a replay. The body is the stored
     * transfer either way, so a retrying client gets an identical representation.
     */
    @PostMapping("/transfers")
    public ResponseEntity<Transfer> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateTransferRequest request) {
        if (request.fromAccountId() == null || request.toAccountId() == null || request.amount() == null) {
            throw new TransferRejectedException(INVALID_REQUEST,
                    "fromAccountId, toAccountId and amount are required");
        }
        TransferResult result = transfers.create(
                idempotencyKey, request.fromAccountId(), request.toAccountId(), request.amount());
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.transfer());
    }

    @ExceptionHandler(TransferRejectedException.class)
    ProblemDetail rejected(TransferRejectedException e) {
        HttpStatus status = switch (e.reason()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            // 422 rather than 404: /transfers exists; the request body
            // refers to something that doesn't.
            case ACCOUNT_NOT_FOUND, INSUFFICIENT_FUNDS -> HttpStatus.UNPROCESSABLE_ENTITY;
            case IDEMPOTENCY_KEY_REUSED -> HttpStatus.CONFLICT;
        };
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }
}
