# ADR-0003 — `TEXT` + `CHECK` over native Postgres enums

**Status:** Accepted · 2026-09-07

## Context

`claim_type` and `status` have a fixed set of legal values. Postgres offers a
native `ENUM` type; the alternative is `TEXT` with a `CHECK` constraint.

Native enums add a value cleanly, but removing or reordering values requires
recreating the type and rewriting every dependent column. They also map awkwardly
through JPA, typically needing a custom type or a converter.

Claim statuses will change — new lifecycle states are among the most likely
future requirements in this domain.

## Decision

Store as `TEXT` with a `CHECK` constraint listing legal values. Map to a Java
`enum` with `@Enumerated(EnumType.STRING)`.

## Consequences

- Identical integrity guarantee: the database rejects an invalid value
- Changing the legal set is an ordinary `ALTER ... DROP/ADD CONSTRAINT` migration
- Values are human-readable in `psql` without joins or casts
- JPA mapping needs no custom converter
- Slightly larger storage than a native enum's integer representation —
  irrelevant at this scale
