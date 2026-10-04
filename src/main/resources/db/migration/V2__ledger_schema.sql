-- Core double-entry ledger schema.
--
-- Money is always BIGINT minor units (cents). Never NUMERIC-as-float, REAL or
-- DOUBLE PRECISION: integer arithmetic is exact and Postgres raises on
-- overflow instead of silently rounding.
--
-- Invariants live in the database, not in app code, so they hold no matter
-- which code path (or ad-hoc psql session) writes the data:
--   1. accounts.balance >= 0                     -> CHECK constraint
--   2. entries for a transfer sum to zero        -> deferred constraint trigger
--   3. entries are immutable once written        -> append-only triggers

CREATE TABLE accounts (
    -- BIGINT identity rather than UUID: IDs are totally ordered, which is
    -- what transfers sort by to lock accounts in a deterministic order.
    -- GENERATED ALWAYS rejects caller-supplied IDs.
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       TEXT        NOT NULL,
    -- Cached running balance in minor units; must equal SUM(entries.amount)
    -- for the account once all balance changes flow through transfers.
    balance    BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Named so callers can identify exactly which rule rejected a write
    -- (e.g. map an overdraft to a 422 rather than a generic 500).
    CONSTRAINT accounts_balance_non_negative CHECK (balance >= 0)
);

CREATE TABLE entries (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  BIGINT      NOT NULL REFERENCES accounts (id),
    -- Groups the legs of one transfer. No FK yet: the transfers table (with
    -- its unique idempotency key) arrives in a later migration, which can add
    -- the FK without reshaping this table.
    transfer_id UUID        NOT NULL,
    -- Signed minor units: negative = debit, positive = credit.
    amount      BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A zero leg carries no meaning. Combined with the sum-to-zero rule this
    -- also guarantees every transfer has at least two entries.
    CONSTRAINT entries_amount_non_zero CHECK (amount <> 0)
);

-- Serves the sum-to-zero trigger and transfer lookups.
CREATE INDEX entries_transfer_id_idx ON entries (transfer_id);
-- Serves account statements / history in time order.
CREATE INDEX entries_account_id_created_at_idx ON entries (account_id, created_at);

-- ---------------------------------------------------------------------------
-- Sum-to-zero per transfer.
--
-- A CHECK constraint only sees a single row, so a cross-row rule needs a
-- trigger. It is a DEFERRABLE INITIALLY DEFERRED constraint trigger so it runs
-- at COMMIT: the debit and credit legs are separate INSERTs, and checking
-- after each statement would reject the first leg every time. If the check
-- fails, the whole transaction (both legs and any balance updates) rolls back.
--
-- Cost: one indexed SUM per inserted row, i.e. ~2 index lookups per transfer.
-- ---------------------------------------------------------------------------
CREATE FUNCTION entries_assert_transfer_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT COALESCE(SUM(amount), 0) FROM entries WHERE transfer_id = NEW.transfer_id) <> 0 THEN
        RAISE EXCEPTION 'entries for transfer % do not sum to zero', NEW.transfer_id
            USING ERRCODE = 'check_violation', CONSTRAINT = 'entries_transfer_balanced';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER entries_transfer_balanced
    AFTER INSERT ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION entries_assert_transfer_balanced();

-- ---------------------------------------------------------------------------
-- Append-only entries.
--
-- The sum-to-zero trigger only fires on INSERT, so an UPDATE or DELETE could
-- silently unbalance an already-committed transfer. A ledger never edits
-- history anyway: mistakes are corrected with a reversing transfer.
-- ---------------------------------------------------------------------------
CREATE FUNCTION entries_reject_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'entries are append-only; % is not allowed', TG_OP
        USING ERRCODE = 'integrity_constraint_violation', CONSTRAINT = 'entries_append_only';
END;
$$;

CREATE TRIGGER entries_append_only
    BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION entries_reject_modification();

-- TRUNCATE bypasses row-level triggers, so it needs its own statement trigger.
CREATE TRIGGER entries_append_only_truncate
    BEFORE TRUNCATE ON entries
    FOR EACH STATEMENT EXECUTE FUNCTION entries_reject_modification();

-- ---------------------------------------------------------------------------
-- Verification (reconciliation) queries -- both should return zero rows:
--
--   -- Any transfer whose legs don't balance:
--   SELECT transfer_id FROM entries GROUP BY transfer_id HAVING SUM(amount) <> 0;
--
--   -- Any account whose cached balance has drifted from its entries:
--   SELECT a.id, a.balance, COALESCE(SUM(e.amount), 0) AS entries_total
--   FROM accounts a LEFT JOIN entries e ON e.account_id = a.id
--   GROUP BY a.id, a.balance
--   HAVING a.balance <> COALESCE(SUM(e.amount), 0);
-- ---------------------------------------------------------------------------
