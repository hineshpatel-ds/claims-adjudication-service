# ADR-0007 — Idempotency via client-supplied keys

**Status:** Accepted · 2026-09-08

## Context

A client POSTs a claim and the response is lost — a timeout, a dropped
connection, a pod restarted mid-request. The client cannot tell whether the claim
was created. Retrying is the only reasonable thing it can do, and without
protection that produces two claims and, downstream, two payouts.

This is not an edge case. Every HTTP client that handles failure retries, and any
mobile client on a flaky network retries often.

Options considered:

**Server-side deduplication on content.** Treat two identical bodies within a
time window as one request. Rejected: two members can legitimately submit
identical claims (same policy, same amount, same day), and the server cannot tell
that apart from a retry.

**Client-supplied idempotency key.** The client generates a unique key per logical
request and sends it with every attempt. The server records the key and what it
produced; a repeat returns the original result.

## Decision

An optional `Idempotency-Key` header on `POST /api/claims`.

- First request for a key: create the claim, record key → claim id, both in one
  transaction
- Repeat with the same key and the same body: return the original claim
- Repeat with the same key and a **different** body: `409`, no work done
- No key: proceed normally, with no duplicate protection

The key is scoped per endpoint, and the body is fingerprinted with SHA-256 rather
than stored, since it may contain personal data and a fixed-width hash keeps the
table small.

**Concurrency is resolved by the primary key on `idempotency_keys`, not by
application logic.** Two simultaneous requests with one key both find no prior
record and both proceed; exactly one insert survives, and the loser's transaction
rolls back including its claim. Its retry then replays the winner's result.

Optional rather than mandatory so the endpoint stays usable from a plain curl.

### Implementation note: `IdempotencyRecord` must implement `Persistable`

This is load-bearing, not stylistic. Spring Data chooses between INSERT and
UPDATE by asking whether the entity is new, and its default answer is "the id is
null". Our id is the client's key and is therefore never null, so Spring Data
called `merge()` — which SELECTs first and, finding a row another transaction had
just committed, issued an UPDATE instead of a colliding INSERT.

The primary key never fired. Six simultaneous requests with one key produced six
claims: the schema was correct, the constraint was correct, and the guarantee was
silently not running.

Implementing `Persistable` with an `@Transient boolean isNew` (cleared by
`@PostPersist`/`@PostLoad`) forces `persist()`, so a duplicate key is a real
INSERT that the database rejects.

**Do not remove it.** Nothing in a single-threaded test will fail if you do —
`merge()` and `persist()` are indistinguishable without concurrency — and the
duplicate protection will be gone.

## Consequences

- A retry after a timeout is safe, which is the entire point
- No duplicate can survive a concurrent race — the guarantee is the database's
- A client reusing one key for different requests is told, rather than silently
  handed an unrelated claim
- Callers that omit the header get no protection. Accepted deliberately; a
  payment-moving API would likely make it mandatory, and that change is a
  one-line validation away
- Keys accumulate. A retention sweep is needed before this reaches production —
  the index on `created_at` exists for it, but the job is not written
- The loser of a concurrent race sees a 409 rather than the original claim, and
  must retry once more. Resolving it on the first attempt would need the
  reservation in a separate transaction, which is more machinery than the
  frequency of the case justifies
