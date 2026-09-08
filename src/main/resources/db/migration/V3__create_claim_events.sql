-- V3: the audit trail.
--
-- Every status change appends a row here. Nothing updates or deletes one.
--
-- In insurance and banking "who changed this, when, and from what to what" is a
-- legal requirement, not a nice-to-have. A regulator asking why a claim paid a
-- particular amount needs an answer that does not depend on anyone's memory.

CREATE TABLE claim_events (
    id           UUID        PRIMARY KEY,
    claim_id     UUID        NOT NULL REFERENCES claims (id),

    event_type   TEXT        NOT NULL,

    -- NULL only for the submission event: nothing precedes a claim's creation.
    from_status  TEXT,
    to_status    TEXT        NOT NULL,

    -- Who caused it. Currently always 'system' - this service has no
    -- authentication (deliberately out of scope). With auth this comes from the
    -- authenticated principal. The column exists now so adding auth later does
    -- not require rewriting history.
    actor        TEXT        NOT NULL,

    -- What made this transition distinctive: the approved amount, the rejection
    -- reason. Free text rather than jsonb: the structured facts already have their
    -- own columns, and this is for the part a human reads.
    detail       TEXT,

    occurred_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT claim_events_type_valid
        CHECK (event_type IN ('SUBMITTED', 'REVIEW_STARTED', 'APPROVED', 'REJECTED', 'PAID')),

    CONSTRAINT claim_events_status_valid
        CHECK (to_status IN ('SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'PAID')),

    CONSTRAINT claim_events_from_status_valid
        CHECK (from_status IS NULL
               OR from_status IN ('SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'PAID'))
);

-- The only query this table serves: one claim's history, oldest first.
CREATE INDEX idx_claim_events_claim_id_occurred_at ON claim_events (claim_id, occurred_at);


-- Append-only, enforced by the database rather than by convention.
--
-- Application code can be changed, bypassed, or simply wrong. An audit trail that
-- is only append-only because nobody wrote an UPDATE yet is not an audit trail -
-- its value comes from being unable to lie, and that has to be enforced somewhere
-- an application bug cannot reach.
--
-- This also stops the accident: a well-meaning cleanup script, an ORM cascade, a
-- migration that "tidies" old rows.
CREATE OR REPLACE FUNCTION claim_events_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'claim_events is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER claim_events_immutable
    BEFORE UPDATE OR DELETE ON claim_events
    FOR EACH ROW EXECUTE FUNCTION claim_events_reject_mutation();
