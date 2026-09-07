# ADR-0005 — Package by feature, not by layer

**Status:** Accepted · 2026-09-07

## Context

Two conventional layouts:

**By layer** — `controller/`, `service/`, `repository/`, `model/`. Common in
tutorials. One feature is scattered across four packages, so a single change
touches all of them, and nothing prevents any service from reaching into any
other feature's internals.

**By feature** — `claim/`, `ledger/`, `audit/`, `idempotency/`, each holding its
own controller, service, repository, and domain types.

## Decision

Package by feature:

```
com.hines.claims
├── claim/          controller, service, repository, entity, DTOs
├── ledger/         immutable money entries
├── audit/          append-only claim events
├── idempotency/    duplicate-submission suppression
└── common/         error handling, cross-cutting config
```

## Consequences

- A change to claim handling stays in one directory
- Feature boundaries are visible; coupling between features is obvious in review
  rather than hidden behind a shared `service/` package
- Extracting a feature into its own service later is a directory move, not an
  archaeology exercise
- `common/` must be watched — it attracts unrelated code and turns into a dumping
  ground if unpoliced
