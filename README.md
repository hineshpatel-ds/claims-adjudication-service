# Claims Adjudication & Payout Service

[![CI](https://github.com/hineshpatel-ds/claims-adjudication-service/actions/workflows/ci.yml/badge.svg)](https://github.com/hineshpatel-ds/claims-adjudication-service/actions/workflows/ci.yml)

A Spring Boot REST service that accepts insurance claims, moves them through a
controlled lifecycle, and pays approved amounts out through an immutable
double-entry ledger.

**121 tests · 90% instruction coverage · integration tests against real PostgreSQL**

---

## Why this exists

Most portfolio services are CRUD with a lifecycle bolted on. This one is built
around the four concerns that regulated industries actually fail on, and each is
demonstrated by a test that would fail if the mechanism were removed.

| Concern | Mechanism | Proven by |
|---|---|---|
| **Lost updates** | `@Version` optimistic locking | 8 threads adjudicate one claim; exactly 1 wins, 7 are told |
| **Duplicate submissions** | `Idempotency-Key` + primary key | 6 concurrent requests, 1 key, exactly 1 claim |
| **Impossible states** | state machine with one choke point | illegal transitions are unrepresentable, not merely rejected |
| **Unauditable history** | append-only trail + DB trigger | raw `UPDATE`/`DELETE` on audit rows is refused by PostgreSQL |

Each is enforced at the database level as well as in code, because application
code can be changed, bypassed, or simply wrong.

---

## Running it

```bash
docker compose up -d --build
```

Starts PostgreSQL 16 and the service. Compose waits for `pg_isready` — not merely
for the container to exist — before starting the app.

```bash
curl http://localhost:8080/actuator/health
```

## A full claim lifecycle

**1. Submit.** The `Idempotency-Key` is optional but makes a retry after a
timeout safe.

```bash
curl -i -X POST http://localhost:8080/api/claims \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "policyNumber":  "POL-778901",
    "claimantName":  "Jane Doe",
    "claimType":     "DENTAL",
    "claimedAmount": 1450.75,
    "incidentDate":  "2026-08-21"
  }'
```

`201 Created`, with a `Location` header and `"version": 0`. Repeating the same
request with the same key returns the original claim rather than creating a
second one.

**2. Move it through review.** Every state-changing call carries the `version`
you last read. If someone else changed the claim meanwhile, you get `409` with
both versions rather than silently overwriting their decision.

```bash
CLAIM=<id from step 1>

curl -X POST http://localhost:8080/api/claims/$CLAIM/review \
  -H 'Content-Type: application/json' -d '{"expectedVersion":0}'

curl -X POST http://localhost:8080/api/claims/$CLAIM/approve \
  -H 'Content-Type: application/json' \
  -d '{"expectedVersion":1,"approvedAmount":650.00}'

curl -X POST http://localhost:8080/api/claims/$CLAIM/payout \
  -H 'Content-Type: application/json' -d '{"expectedVersion":2}'
```

Partial approval is normal — approving less than claimed is allowed, more is not.
The payout request carries **no amount**: it is read from the stored claim, so a
caller cannot pay a figure nobody adjudicated.

**3. Read the money and the history.**

```bash
curl http://localhost:8080/api/claims/$CLAIM/ledger
curl http://localhost:8080/api/claims/$CLAIM/events
```

The ledger returns both halves of the movement, sharing one `transactionId`:

```json
[ { "account": "CLAIMS_EXPENSE", "direction": "DEBIT",  "amount": 650.00 },
  { "account": "CASH",           "direction": "CREDIT", "amount": 650.00 } ]
```

The audit trail reconstructs the whole story without consulting the claim:

```
SUBMITTED       →  SUBMITTED      Claimed 1450.75 for DENTAL
REVIEW_STARTED  SUBMITTED    → UNDER_REVIEW
APPROVED        UNDER_REVIEW → APPROVED    Approved for 650.00 of 1450.75 claimed
PAID            APPROVED     → PAID        Paid 650.00
```

**4. Search.** Filters compose; paging is capped at 100 and defaults to newest
first.

```bash
curl "http://localhost:8080/api/claims?status=UNDER_REVIEW&size=20"
curl "http://localhost:8080/api/claims?policyNumber=POL-778901"
curl "http://localhost:8080/api/claims?from=2026-08-01T00:00:00Z&to=2026-09-01T00:00:00Z"
```

## Endpoints

| Method | Path | |
|---|---|---|
| `POST` | `/api/claims` | submit (optional `Idempotency-Key`) |
| `GET` | `/api/claims/{id}` | fetch one |
| `GET` | `/api/claims` | list, filtered and paged |
| `POST` | `/api/claims/{id}/review` | `SUBMITTED → UNDER_REVIEW` |
| `POST` | `/api/claims/{id}/approve` | `UNDER_REVIEW → APPROVED` |
| `POST` | `/api/claims/{id}/reject` | `UNDER_REVIEW → REJECTED` |
| `POST` | `/api/claims/{id}/payout` | `APPROVED → PAID`, writes the ledger |
| `GET` | `/api/claims/{id}/events` | audit trail |
| `GET` | `/api/claims/{id}/ledger` | ledger entries |
| `GET` | `/actuator/health` | liveness, including a real database check |

Errors are RFC 9457 problem details, produced in one place so every failure has
the same shape:

```json
{
  "type":   "https://claims.hines.dev/problems/stale-version",
  "title":  "Stale claim version",
  "status": 409,
  "detail": "This claim has changed since you loaded it. Re-read it and retry.",
  "expectedVersion": 1,
  "actualVersion":   3
}
```

---

## Design notes

Decisions are recorded as [ADRs](docs/adr/) — what was decided, why, and what it
cost. The ones worth reading first:

**[Flyway owns the schema](docs/adr/0002-flyway-owns-the-schema.md).** `ddl-auto`
is `validate`, so Hibernate verifies entities against the real tables and refuses
to start on a mismatch. A drift between a migration and an entity fails at
startup rather than as a `column does not exist` error on a rare query.

**[Entities never cross the web boundary](docs/adr/0006-never-expose-entities-over-http.md).**
Controllers speak DTOs only. A client POSTing `{"status": "PAID"}` cannot set it
— not because it is filtered, but because the field does not exist on the type
the endpoint accepts.

**[The aggregate records its own history](docs/adr/0008-append-only-audit-trail.md).**
Every status change funnels through one private method, which both validates the
transition and appends the audit event. A future transition cannot forget to log
itself, because the only way to change status is through that method.

**[Double-entry, immutable ledger](docs/adr/0009-double-entry-immutable-ledger.md).**
Balances are derived by summing, never stored — a stored balance drifts the moment
one update is missed. A deferred constraint trigger checks every transaction sums
to zero at commit.

**[Offset pagination, with a known expiry](docs/adr/0010-offset-pagination-with-a-size-cap.md).**
Correct at this scale, and the ADR names the conditions under which to switch to
keyset.

**[`open-in-view` is disabled](docs/adr/0004-disable-open-in-view.md).** The
default holds a database connection for the whole HTTP request, which hides
lazy-loading bugs and exhausts the pool under load.

## Architecture

Packaged by feature, not by layer, so a change to claim handling stays in one
directory and coupling between features is visible in review.

```
com.hines.claims
├── claim/          controller, service, repository, aggregate, DTOs
├── audit/          append-only claim events
├── ledger/         immutable double-entry entries
├── idempotency/    duplicate-submission suppression
└── common/         error handling, paging, cross-cutting config
```

Business rules live on the aggregate rather than in services. `Claim` has **no
public setters**: `approve()` is the only way to reach `APPROVED`, so its
guarantees hold for every caller — including a future batch job or queue consumer
that never goes near the controller.

## Testing

```bash
./mvnw verify
```

| | Count | |
|---|---|---|
| Domain unit tests | 50 | no Spring, no database, ~0.3s total |
| Web layer (MockMvc) | 30 | routing, validation, status codes |
| Integration (Testcontainers) | 41 | real PostgreSQL 16 |

Integration tests run against **real PostgreSQL, not H2**, and the reason is
specific rather than aspirational: the concurrency tests verify the atomicity of
a conditional `UPDATE ... WHERE version = ?` across genuinely concurrent
transactions. A mock has no transactions and H2 has different concurrency
behaviour — the tests would pass while proving nothing.

Two defects were found this way that no single-threaded test could have caught,
both documented in the ADRs they relate to.

## Stack

Java 21 · Spring Boot 4.1 · PostgreSQL 16 · Flyway · Spring Data JPA ·
Testcontainers · JaCoCo · Docker (multi-stage, non-root) · GitHub Actions ·
SonarCloud · Snyk

SonarCloud and Snyk steps are in the pipeline and skip until their tokens are
configured, so a fresh clone still builds green. See
[docs/quality-gates-setup.md](docs/quality-gates-setup.md).

---

## What this deliberately does not do

Stated because the gaps are choices, not oversights:

- **No authentication.** Audit rows record `actor: "system"`. A client-supplied
  identity header was rejected — an unverifiable identity in an audit trail is
  worse than none, because it looks authoritative. Adding Spring Security changes
  one constant.
- **Single currency.** Multi-currency needs rates with effective dates and
  per-currency rounding — a feature, not a column.
- **No idempotency-key expiry.** Keys accumulate; production needs a retention
  sweep. The index for it exists, the job does not.
- **One policy, one claimant, four benefit types.** A real insurer has hundreds
  of benefit codes; modelling that adds volume without adding anything to
  demonstrate.
