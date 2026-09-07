# Claims Adjudication & Payout Service

A REST service that accepts insurance claims, moves them through a controlled
lifecycle, and pays out approved amounts through an immutable ledger.

Built to target **banking, insurance, and regulated enterprise** roles. The domain
is insurance; the engineering concerns (exactly-once money movement, auditability,
concurrency control) are identical in banking.

---

## Scope discipline

One flow, done properly, beats five flows done shallowly.

**In scope:** claim submission, adjudication lifecycle, payout, audit trail.
**Out of scope:** users, authentication, roles, policy management, document upload,
notifications, a UI. If we want auth later it is a separate, deliberate decision.

---

## The domain

A **claim** is a request for money against a policy.

```
                  ┌──────────────┐
                  │  SUBMITTED   │  member filed it
                  └──────┬───────┘
                         │ POST /review
                  ┌──────▼───────┐
                  │ UNDER_REVIEW │  an adjuster picked it up
                  └──┬────────┬──┘
         POST /decision        POST /decision
                     │        │
            ┌────────▼──┐  ┌──▼────────┐
            │ APPROVED  │  │ REJECTED  │  terminal
            └─────┬─────┘  └───────────┘
                  │ POST /payout
            ┌─────▼─────┐
            │   PAID    │  terminal, ledger entry written
            └───────────┘
```

Every other transition is illegal and must be rejected with `409 Conflict`.
You cannot pay a rejected claim. You cannot decide a claim twice. You cannot
re-open a paid claim.

---

## Data model

Four tables. Each one exists for a specific engineering reason.

### `claims` — the mutable aggregate

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | primary key |
| `policy_number` | text | not null, indexed |
| `claimant_name` | text | not null |
| `claim_type` | enum | `MEDICAL`, `DENTAL`, `VISION`, `DISABILITY` |
| `claimed_amount` | numeric(12,2) | not null, must be > 0 |
| `approved_amount` | numeric(12,2) | nullable until adjudicated |
| `status` | enum | the state machine above |
| `incident_date` | date | not null, cannot be in the future |
| `submitted_at` | timestamptz | not null |
| `decided_at` | timestamptz | nullable |
| `paid_at` | timestamptz | nullable |
| `rejection_reason` | text | nullable, required when REJECTED |
| `version` | bigint | **optimistic locking** — see below |

Indexes on `policy_number` and `(status, submitted_at)`, matching the list query.

### `claim_events` — the audit trail (append-only)

Every state change writes a row here. Rows are never updated or deleted.

`id`, `claim_id` (FK), `event_type`, `from_status`, `to_status`, `actor`,
`payload` (jsonb), `occurred_at`.

*Why:* in insurance and banking, "who changed this and when" is a legal
requirement, not a nice-to-have. Regulators audit this.

### `ledger_entries` — money (append-only, immutable)

`id`, `claim_id` (FK), `amount` numeric(12,2), `direction` (`DEBIT`/`CREDIT`),
`currency`, `entry_type`, `created_at`.

*Why:* money records are never mutated. You correct a mistake by writing a
reversing entry, never by editing history. Balances are derived by summing, not
stored in a column that can drift.

### `idempotency_keys` — duplicate suppression

`key` (PK), `endpoint`, `request_hash`, `claim_id`, `response_status`, `created_at`.

*Why:* see below.

---

## The four concerns this project exists to teach

### 1. Idempotency
A client POSTs a claim. The response times out. The client retries. Without
protection you now have two claims and potentially two payouts.

Solution: client sends an `Idempotency-Key` header. We record it. A repeat of the
same key returns the original result instead of creating a second claim.

### 2. Optimistic locking
Two adjusters open claim X. Both approve, one for $500, one for $2000. Last write
wins and the first decision vanishes silently.

Solution: a `version` column. Each update asserts the version it read. If it
changed underneath you, the write fails and you return `409`.

### 3. State machine
Nothing may reach an impossible state. Transitions are validated in one place,
not scattered through controllers.

### 4. Audit trail
Every transition appends an immutable event. No state change happens without one.

---

## Endpoints

```
POST   /api/claims                    submit           (Idempotency-Key header)
GET    /api/claims/{id}               fetch one
GET    /api/claims                    list, paged, ?status= &policyNumber= &from= &to=
POST   /api/claims/{id}/review        SUBMITTED    -> UNDER_REVIEW
POST   /api/claims/{id}/decision      UNDER_REVIEW -> APPROVED | REJECTED
POST   /api/claims/{id}/payout        APPROVED     -> PAID, writes ledger entry
GET    /api/claims/{id}/events        the audit trail
GET    /actuator/health               liveness
```

---

## Stack

| Piece | Choice |
|---|---|
| Language | Java 21 (LTS) |
| Framework | Spring Boot 4.1.x |
| Web | Spring Web MVC |
| Persistence | Spring Data JPA / Hibernate |
| Database | PostgreSQL 16 |
| Migrations | Flyway |
| Validation | Jakarta Bean Validation |
| Health | Spring Boot Actuator |
| Build | Maven |
| Tests | JUnit 5, MockMvc, Testcontainers, AssertJ |
| Container | Docker, multi-stage build |
| Local stack | Docker Compose |
| CI | GitHub Actions |
| Quality | SonarCloud, Snyk |

---

## Test strategy

- **Unit** — state machine legality, money rules, validation. No Spring context, fast.
- **Slice (MockMvc)** — status codes per endpoint, including 400, 404, 409.
- **Integration (Testcontainers)** — real Postgres. Filters, pagination, and the
  concurrency test that proves optimistic locking actually works.

The concurrency test is the one worth talking about in an interview: two threads
adjudicating the same claim, exactly one succeeds.

---

## Build order

1. Project skeleton, Compose, Postgres connection
2. Flyway migration + entity + repository
3. Submit and fetch endpoints, with validation
4. State machine and the transition endpoints
5. Optimistic locking
6. Idempotency
7. Audit trail
8. Ledger and payout
9. List, filtering, pagination
10. Testcontainers integration suite
11. Dockerfile and Compose for the whole stack
12. GitHub Actions, then SonarCloud and Snyk
