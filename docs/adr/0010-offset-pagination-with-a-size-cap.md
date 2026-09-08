# ADR-0010 — Offset pagination, with a size cap and a known expiry

**Status:** Accepted · 2026-09-08

## Context

Listing claims needs paging. Two approaches:

**Offset** — `LIMIT ? OFFSET ?`, which is what Spring Data's `Pageable` produces.
Simple, gives a total count and page numbers, and lets a caller jump to any page.

Two real weaknesses:

- **Deep offsets are slow.** `OFFSET 100000` makes the database produce and
  discard 100,000 rows before returning any. Cost grows linearly with page number.
- **It drifts.** If a claim is submitted while someone is paging, every row shifts
  by one — so an item can be seen twice, or skipped entirely. Nothing errors; the
  results are quietly wrong.

**Keyset (cursor)** — `WHERE (submitted_at, id) < (?, ?) ORDER BY ... LIMIT ?`.
Constant cost at any depth and immune to drift, because position is defined by the
last row seen rather than by a count.

Its costs: no total count and no page numbers, cannot jump to page 7, and the
sort column must be part of the cursor, so arbitrary client-chosen sorting becomes
much harder.

## Decision

**Offset pagination**, via Spring Data `Pageable`, with two non-negotiable
additions:

**A hard cap of 100 rows per page.** Uncapped, `?size=1000000` loads the table
into memory and serialises it — a denial of service reachable from a URL bar, and
usually not malicious, just a client that assumed it could fetch everything.

**A default sort of `submittedAt DESC`** when the caller supplies none. Without an
`ORDER BY`, PostgreSQL may return rows in any order, and that order can differ
between two identical queries — so page 2 can repeat a row from page 1 for no
visible reason. **Pagination without a total order is not pagination.** The
default also matches `idx_claims_status_submitted_at` from V1, so the common case
of a status-filtered queue is served from the index without a sort step.

Filters are built with Spring Data Specifications rather than one query with
`(:param IS NULL OR column = :param)`. The latter emits identical SQL whatever was
supplied, so the planner cannot use selectivity to choose an index. Specifications
emit only the predicates present.

## When to revisit

This decision has a foreseeable expiry. Move to keyset pagination when any of:

- The claims table passes roughly a million rows **and** callers page deep
- Clients report duplicated or missing rows while paging
- List endpoint latency correlates with page number

Until then, offset is correct: claims are filtered before they are paged, so deep
offsets do not arise, and the UI wants a total count that keyset cannot provide.

## Consequences

- Callers get total counts and page numbers, which UIs need
- Any page is directly addressable, which makes the API easy to script against
- Deep pagination will degrade, and the drift window is real but small at this
  scale
- Page size is bounded, so no single request can exhaust memory
- Ordering is deterministic, so pages compose correctly
- Specifications cost more code than a single annotated query, in exchange for SQL
  that matches the request
