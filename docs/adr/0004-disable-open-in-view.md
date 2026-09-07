# ADR-0004 — Disable `open-in-view`

**Status:** Accepted · 2026-09-07

## Context

Spring Boot defaults `spring.jpa.open-in-view` to `true`, keeping the JPA
persistence context — and its database connection — open for the whole HTTP
request, including JSON serialisation.

It exists to prevent `LazyInitializationException` when a lazy association is
touched after the service returns. It does so by making a performance problem
invisible instead of making a correctness problem visible.

Consequences of leaving it on:

- A connection is held during work that does not need one. Held 200ms where 20ms
  was required, effective pool capacity drops roughly tenfold, and under load
  requests queue while the database itself looks idle.
- Queries fire during serialisation, so the view layer silently triggers database
  work.
- The problem is invisible in development and appears as a latency cliff in
  production.

## Decision

Set `spring.jpa.open-in-view: false`. Services load exactly what they need
inside their transaction and return DTOs (see ADR-0006).

## Consequences

- Connections are held only for the transaction that needs them
- Lazy-loading mistakes surface as `LazyInitializationException` during
  development, at the point the mistake was made
- Loading decisions become explicit in the service layer, where they belong
- Slightly more work per endpoint: what to fetch must be stated, not inferred
