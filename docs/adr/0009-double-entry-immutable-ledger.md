# ADR-0009 — Double-entry, immutable payout ledger

**Status:** Accepted · 2026-09-08

## Context

Paying a claim moves money, and money has requirements ordinary data does not: it
must not appear from nowhere, must not be paid twice, and must not be editable
after the fact.

The shortcut is a `paid_amount` column on `claims` and a `paid_at` timestamp.
That records *that* a payment happened but not the movement itself, gives no
account structure to reconcile against, and can be edited by any UPDATE that
touches the row.

Options for representing the movement:

**A single payment row per claim.** Simple. But money only ever leaves — nothing
records where it came from, so the books cannot be balanced and an error is
indistinguishable from a legitimate payment.

**Double-entry.** Every movement produces balanced entries summing to zero.
Paying a claim debits claims expense and credits cash. An imbalance is definitive
evidence of a bug.

## Decision

Double-entry, in `ledger_entries`, with four properties:

**Entries are immutable.** A trigger rejects UPDATE and DELETE. Corrections are
reversing entries, never edits — the history of what was believed at the time is
part of the record.

**Amounts are always positive; the sign lives in `direction`.** Negative amounts
make every query ambiguous: is `-50` a credit of 50, or a debit entered wrongly?

**Balance is enforced by a deferred constraint trigger.** Each `transaction_id`
must sum to zero, checked at COMMIT rather than per statement — entries are
inserted one at a time and the pair is legitimately unbalanced in between, so a
non-deferred check would reject every valid payout.

**Balances are derived, never stored.** No `balance` column anywhere. A stored
balance drifts the moment one update is missed, and then two sources of truth
disagree with no way to tell which is right.

Additionally: a unique index on `(claim_id, entry_type, account)` means a claim
can be paid exactly once at the database level, independent of the state machine
and optimistic locking above it. The payout amount is read from the stored claim,
never accepted from the request.

Claim status and ledger entries are written in one transaction, so no state exists
where a claim reads PAID with no money recorded, or the reverse.

## Consequences

- Money cannot appear from nowhere: an unbalanced transaction cannot commit
- A claim cannot be paid twice, guarded at three independent levels — state
  machine, optimistic locking, unique index
- Recorded money cannot be altered by application code, a script, or a migration
- Balances are always correct because they are computed, never maintained
- A caller cannot pay an amount nobody approved
- Reporting must aggregate rather than read a column. Fine at this scale; a
  high-volume system would add periodic balance snapshots as a cache, with the
  entries remaining the source of truth
- Single currency, hard-coded to CAD. Multi-currency needs a currency on the
  claim, rates with effective dates, and per-currency rounding — a feature, not a
  column
- Two rows per payment instead of one. That is the cost of being able to prove
  the books balance
