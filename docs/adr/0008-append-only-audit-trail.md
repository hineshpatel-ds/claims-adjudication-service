# ADR-0008 — Append-only audit trail, recorded by the aggregate

**Status:** Accepted · 2026-09-08

## Context

In insurance and banking, "who changed this, when, and from what to what" is a
legal requirement. A regulator asking why a claim paid a particular amount needs
an answer that does not depend on anyone's memory or on reconstructing intent
from the current row.

Three questions had to be settled.

**Where does the record get written?** The obvious answer is "in each service
method". That works until someone adds a transition and forgets, and the gap is
invisible — nothing fails, the history is simply incomplete. An audit trail with
holes is worse than none, because it looks complete.

**In the same transaction as the change, or separately?** A separate transaction
means a crash between the two commits the state change with no record of it.

**What stops history being altered?** If append-only is only a convention, then a
cleanup script, an ORM cascade, or a migration that "tidies" old rows can rewrite
it. The value of an audit trail comes entirely from being unable to lie.

## Decision

**The aggregate records its own transitions.** `Claim.transitionTo` — already the
single choke point every status change passes through (ADR-0005 layering, and the
state machine) — appends a `ClaimTransition` to a `@Transient` list. The service
drains that list and writes the rows.

A new transition method cannot forget to audit itself, because the only way to
change status is through the choke point, and the choke point always records.

**Audit rows are written in the same transaction as the change**, in
`ClaimService.respondWith` — which is on every mutating path and no read path.

**Append-only is enforced by the database.** A trigger on `claim_events` raises an
exception on UPDATE or DELETE (V3). The repository interface exposes no mutation
method either, but that is convenience; the trigger is the guarantee.

**`actor` is `'system'` for now.** This service has no authentication, so there is
no principal to record. A client-supplied header was rejected: an unverifiable
identity in an audit trail is worse than none, because it looks authoritative.

## Consequences

- Every transition is audited by construction, not by discipline
- A crash cannot produce a state change without its record, or the reverse
- History cannot be rewritten by application code, a script, or a migration
- The aggregate stays unaware of how history is stored — it produces
  `ClaimTransition` values; the audit package depends on the claim package and
  not the reverse
- Adding authentication later changes one constant and nothing else
- `claim_events` grows without bound. Retention and archival are unaddressed;
  regulated retention periods are typically measured in years, so this needs a
  policy before production
- The trigger makes test fixtures harder: rows genuinely cannot be cleaned up.
  Accepted, and arguably the point
