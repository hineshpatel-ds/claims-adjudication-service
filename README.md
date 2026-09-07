# Claims Adjudication & Payout Service

A Spring Boot REST service that accepts insurance claims, moves them through a
controlled lifecycle, and pays approved amounts out through an immutable ledger.

Built to exercise the engineering concerns that regulated industries actually
care about — exactly-once money movement, auditability, and concurrency control —
rather than another CRUD demo.

> **Status: in progress.** Schema and project skeleton are in place; the domain
> model and endpoints are being built. See [docs/SPEC.md](docs/SPEC.md).

---

## Stack

Java 21 · Spring Boot 4.1 · PostgreSQL 16 · Flyway · Spring Data JPA ·
Testcontainers · Docker · GitHub Actions

## Running locally

```bash
docker compose up -d          # starts PostgreSQL 16
./mvnw spring-boot:run        # starts the service on :8080
```

Health check:

```bash
curl http://localhost:8080/actuator/health
```

## Tests

```bash
./mvnw test
```

Integration tests run against a **real PostgreSQL instance** in Docker via
Testcontainers — not H2, not a mock. Docker must be running.

---

## Design notes

**Flyway owns the schema, Hibernate only validates it.** `ddl-auto` is set to
`validate`, so Hibernate compares entity mappings against the real tables at
startup and refuses to boot on a mismatch. Migrations run first, so a drift
between a migration and an entity is caught at startup rather than as a
`column does not exist` error on a rarely-hit query in production.

**Constraints live in the database, not only in Java.** Amounts must be
positive, a rejected claim must carry a reason, `paid_at` cannot precede
`decided_at`. Application code enforces these too, but a buggy code path can
bypass application logic — it cannot bypass the database.

**Enum-like columns are `TEXT` + `CHECK`, not native Postgres enums.** Same
guarantee, but Postgres enums are awkward to alter and map poorly through JPA.

**Indexes match the queries we actually run** — `policy_number` and
`(status, submitted_at DESC)` — rather than speculatively indexing every column.

**`open-in-view` is disabled.** The default holds a database connection open for
the entire HTTP request, which hides lazy-loading bugs and exhausts the pool
under load.

---

## Learning notes

This repo doubles as a study record. [docs/learning/](docs/learning/) contains
worked explanations of each concept as it was introduced, with self-test
questions and answers.
