-- V4: the payout ledger.
--
-- Double-entry: every movement of money produces balanced entries that sum to
-- zero. Paying a claim increases the insurer's claims expense and decreases cash
-- by the same amount. Money is never created or destroyed, only moved between
-- accounts, and an imbalance means something is wrong.
--
-- Entries are immutable. A mistake is corrected by writing a reversing entry,
-- never by editing history - which is how accounting has worked for six hundred
-- years, and for the same reason we made claim_events append-only.

CREATE TABLE ledger_entries (
    id              UUID           PRIMARY KEY,

    -- Groups the entries that make up one movement. The balance check below is
    -- scoped to this: a single row is meaningless, a transaction must balance.
    transaction_id  UUID           NOT NULL,

    claim_id        UUID           NOT NULL REFERENCES claims (id),

    account         TEXT           NOT NULL,
    direction       TEXT           NOT NULL,

    -- Always positive. The sign comes from direction, never from the amount.
    -- Allowing negative amounts would make every query ambiguous: is -50 a credit
    -- of 50, or a debit that was entered wrongly? Accounting avoids the question.
    amount          NUMERIC(12, 2) NOT NULL,

    currency        TEXT           NOT NULL,
    entry_type      TEXT           NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL,

    CONSTRAINT ledger_entries_amount_positive
        CHECK (amount > 0),

    CONSTRAINT ledger_entries_direction_valid
        CHECK (direction IN ('DEBIT', 'CREDIT')),

    CONSTRAINT ledger_entries_account_valid
        CHECK (account IN ('CLAIMS_EXPENSE', 'CASH')),

    CONSTRAINT ledger_entries_type_valid
        CHECK (entry_type IN ('CLAIM_PAYOUT')),

    CONSTRAINT ledger_entries_currency_valid
        CHECK (currency ~ '^[A-Z]{3}$')
);

-- A claim can be paid exactly once. The state machine already refuses a second
-- payout, and optimistic locking refuses a concurrent one - but this is the
-- backstop that does not depend on application code being correct.
--
-- One row per (claim, movement type, account), so a payout's DEBIT and CREDIT
-- both fit while a second payout cannot.
CREATE UNIQUE INDEX uq_ledger_entries_claim_payout
    ON ledger_entries (claim_id, entry_type, account);

CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries (transaction_id);
CREATE INDEX idx_ledger_entries_claim_id ON ledger_entries (claim_id);


-- Every transaction must balance: debits and credits sum to zero.
--
-- DEFERRABLE INITIALLY DEFERRED means this runs at COMMIT, not per statement -
-- which is essential, because the entries are inserted one at a time and the
-- books are legitimately unbalanced between the first insert and the second.
-- A non-deferred check would reject every valid payout.
--
-- Writing a lone entry, or a mismatched pair, fails at commit.
CREATE OR REPLACE FUNCTION ledger_entries_assert_balanced() RETURNS trigger AS $$
DECLARE
    imbalance NUMERIC;
BEGIN
    SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0)
      INTO imbalance
      FROM ledger_entries
     WHERE transaction_id = NEW.transaction_id;

    IF imbalance <> 0 THEN
        -- ERRCODE matters. PL/pgSQL's default for RAISE is P0001, which no JDBC
        -- driver or framework recognises - Spring gives up and reports
        -- BadSqlGrammarException, so a genuine integrity failure surfaces to the
        -- caller as "internal server error, bad SQL grammar". Raising a real
        -- constraint-violation code makes it a DataIntegrityViolationException,
        -- which the application already maps to 409.
        RAISE EXCEPTION 'ledger transaction % does not balance: debits minus credits = %',
            NEW.transaction_id, imbalance
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_assert_balanced();


-- Immutable, for the same reason claim_events is: a ledger that can be edited is
-- not a ledger. Corrections are reversing entries.
CREATE OR REPLACE FUNCTION ledger_entries_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only; % is not permitted. Write a reversing entry instead', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_reject_mutation();
