-- V1: the claims aggregate.
--
-- Enum-like columns are TEXT + CHECK rather than native Postgres ENUM types.
-- Postgres enums are painful to alter (adding a value is fine, removing or
-- reordering is not), and JPA maps them awkwardly. TEXT + CHECK gives the same
-- guarantee, migrates cleanly, and reads well in psql.

CREATE TABLE claims (
    id                UUID           PRIMARY KEY,
    policy_number     TEXT           NOT NULL,
    claimant_name     TEXT           NOT NULL,
    claim_type        TEXT           NOT NULL,
    claimed_amount    NUMERIC(12, 2) NOT NULL,
    approved_amount   NUMERIC(12, 2),
    status            TEXT           NOT NULL,
    incident_date     DATE           NOT NULL,
    submitted_at      TIMESTAMPTZ    NOT NULL,
    decided_at        TIMESTAMPTZ,
    paid_at           TIMESTAMPTZ,
    rejection_reason  TEXT,
    version           BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT claims_claim_type_valid
        CHECK (claim_type IN ('MEDICAL', 'DENTAL', 'VISION', 'DISABILITY')),

    CONSTRAINT claims_status_valid
        CHECK (status IN ('SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'PAID')),

    -- Money rules. The application enforces these too; the database is the
    -- last line of defence and is not bypassable by a buggy code path.
    CONSTRAINT claims_claimed_amount_positive
        CHECK (claimed_amount > 0),

    CONSTRAINT claims_approved_amount_non_negative
        CHECK (approved_amount IS NULL OR approved_amount >= 0),

    CONSTRAINT claims_approved_within_claimed
        CHECK (approved_amount IS NULL OR approved_amount <= claimed_amount),

    -- A rejected claim must say why. Auditors ask.
    CONSTRAINT claims_rejection_reason_required
        CHECK (status <> 'REJECTED' OR rejection_reason IS NOT NULL),

    -- Timestamps cannot run backwards.
    CONSTRAINT claims_decided_after_submitted
        CHECK (decided_at IS NULL OR decided_at >= submitted_at),

    CONSTRAINT claims_paid_after_decided
        CHECK (paid_at IS NULL OR (decided_at IS NOT NULL AND paid_at >= decided_at))
);

-- Indexed for the queries we actually run (see the list endpoint), not
-- speculatively on every column.
CREATE INDEX idx_claims_policy_number ON claims (policy_number);
CREATE INDEX idx_claims_status_submitted_at ON claims (status, submitted_at DESC);
