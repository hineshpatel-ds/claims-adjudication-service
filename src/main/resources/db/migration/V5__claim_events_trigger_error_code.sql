-- V5: give the claim_events immutability trigger a real SQLSTATE.
--
-- A new migration rather than an edit to V3, because V3 has been released.
-- Flyway checksums an applied migration; changing one makes validation fail for
-- anyone who already ran it, and the fix is worse than the problem (ADR-0002).
-- Corrections go forward, the same principle as the ledger and the audit trail.
--
-- The defect: PL/pgSQL's RAISE defaults to SQLSTATE P0001, which no JDBC driver
-- recognises as anything in particular. Spring's exception translator falls back
-- to BadSqlGrammarException, so an attempt to rewrite audit history surfaced to
-- the caller as "internal server error, bad SQL grammar" - misleading to a client
-- and, worse, it bypasses our DataIntegrityViolationException handler and so
-- reports 500 instead of 409.
--
-- 'restrict_violation' (23001) is a real integrity-constraint code, which Spring
-- maps to DataIntegrityViolationException.

CREATE OR REPLACE FUNCTION claim_events_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'claim_events is append-only; % is not permitted', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

-- The trigger itself is unchanged and still points at this function; replacing
-- the function body is enough.
