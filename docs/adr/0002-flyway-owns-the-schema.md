# ADR-0002 — Flyway owns the schema; Hibernate validates

**Status:** Accepted · 2026-09-07

## Context

Hibernate can generate the database schema from entity classes
(`spring.jpa.hibernate.ddl-auto=update`). It is convenient and it is how most
tutorials start.

It is also unusable in production. Generated DDL is not reviewable before it
runs, `update` never drops or alters anything safely, there is no down path, and
two environments can silently diverge. In a regulated domain, an unreviewed
automatic schema change is not acceptable.

## Decision

Flyway owns the schema. Every change is a numbered, immutable SQL migration in
`src/main/resources/db/migration/`.

`ddl-auto` is set to `validate`: Hibernate compares entity mappings against the
real schema at startup and refuses to boot on a mismatch. Boot ordering runs
Flyway before that validation.

## Consequences

- Schema changes are reviewable SQL in pull requests, like any other change
- A drift between an entity and a migration fails **at startup**, not as a
  `column does not exist` error on a rarely-hit query in production
- Every schema change costs a migration file — deliberate friction
- Applied migrations must never be edited; corrections are new migrations
