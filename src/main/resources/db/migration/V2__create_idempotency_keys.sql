-- V2: duplicate-submission suppression.
--
-- A client whose request times out cannot tell whether it succeeded. Retrying is
-- the only reasonable thing for them to do, so the server has to make it safe.
--
-- The client sends an Idempotency-Key header. The first request for a key does
-- the work and records the outcome here; a repeat returns the original result
-- instead of creating a second claim.

CREATE TABLE idempotency_keys (
    -- Client-supplied, so TEXT rather than UUID: the spec says opaque string,
    -- and rejecting a caller's perfectly good ULID or nanoid would be unhelpful.
    idempotency_key  TEXT        PRIMARY KEY,

    -- Scoped per endpoint. The same key on a different operation is a different
    -- request, not a duplicate of this one.
    endpoint         TEXT        NOT NULL,

    -- Fingerprint of the request body. A repeat with the same key but a DIFFERENT
    -- body is a client bug - usually a key generated once and reused in a loop -
    -- and returning the first result would silently discard the second request.
    -- We reject it instead.
    request_hash     TEXT        NOT NULL,

    -- What the original request produced.
    claim_id         UUID        NOT NULL REFERENCES claims (id),

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The PRIMARY KEY above is what actually makes this safe under concurrency:
-- two simultaneous requests carrying the same key cannot both insert. One wins,
-- the other's transaction fails and rolls back, so no duplicate claim is left
-- behind. The guarantee is the database's, not the application's.

-- Supports expiry sweeps. Keys are not useful forever - holding them for all time
-- turns a retry-safety table into an unbounded log - so a scheduled job would
-- delete rows past the retention window. Not implemented yet; the index is here
-- so adding it later does not require a second migration.
CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);
