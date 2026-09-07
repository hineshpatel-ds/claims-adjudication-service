# ADR-0006 — Never expose entities over HTTP

**Status:** Accepted · 2026-09-07

## Context

A JPA entity can be returned straight from a controller; Jackson will serialise
it. It is the shortest path and it is what most tutorial and generated code does.

It also welds the public API contract to the database schema. Consequences:

- Renaming a column silently changes the API and breaks clients
- Every column is exposed by default, so a new internal field leaks the moment
  it is added — including ones that should never be public
- Serialising an entity outside its transaction triggers lazy loads, or throws
- Request binding onto an entity allows a client to set fields it must not
  control, such as `status` or `approvedAmount`

The last one is the serious one: it is a real vulnerability, not a style issue.

## Decision

Controllers accept and return DTOs only. Entities never cross the web boundary
in either direction.

- Inbound: a request DTO carrying only client-settable fields, with Bean
  Validation constraints
- Outbound: a response DTO shaped for the consumer
- Mapping happens in the service layer

## Consequences

- The schema can change without changing the API, and vice versa — the point of
  the decision
- A client cannot set a field the DTO does not expose
- Validation rules live on the DTO, next to the contract they describe
- Costs real boilerplate: two DTOs and a mapping per endpoint. Accepted
  deliberately — Java records keep it small, and the coupling it prevents is
  expensive to undo later
