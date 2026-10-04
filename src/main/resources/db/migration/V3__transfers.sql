-- Transfers: one row per money movement, carrying the client's idempotency key.
--
-- The UNIQUE constraint on idempotency_key is what makes POST /transfers
-- idempotent. The service INSERTs first and treats a unique violation as
-- "already seen", never SELECT-then-INSERT, which would let two concurrent
-- requests with the same key both see "not found" and both move money.
-- With insert-first, the second INSERT blocks on the first's uncommitted
-- index entry and only fails once the first commits, so the race is decided
-- by Postgres, not by application timing.

CREATE TABLE transfers (
    -- gen_random_uuid() is built in since Postgres 13; no extension needed.
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key TEXT        NOT NULL,
    from_account_id BIGINT      NOT NULL REFERENCES accounts (id),
    to_account_id   BIGINT      NOT NULL REFERENCES accounts (id),
    -- Minor units, always positive: direction is given by from/to, and the
    -- signed legs live in entries.
    amount          BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT transfers_idempotency_key_unique UNIQUE (idempotency_key),
    -- Bound the key so clients can't bloat the unique index with huge values.
    CONSTRAINT transfers_idempotency_key_length CHECK (char_length(idempotency_key) BETWEEN 1 AND 255),
    CONSTRAINT transfers_amount_positive CHECK (amount > 0),
    CONSTRAINT transfers_distinct_accounts CHECK (from_account_id <> to_account_id)
);

-- V2 left entries.transfer_id unconstrained until this table existed.
-- Adding the FK validates existing rows, so any orphaned entries make this
-- migration fail loudly rather than being silently carried forward.
-- entries_transfer_id_idx (V2) already covers the referencing column.
ALTER TABLE entries
    ADD CONSTRAINT entries_transfer_id_fkey
    FOREIGN KEY (transfer_id) REFERENCES transfers (id);
