# ADR-0001 — Record architecture decisions

**Status:** Accepted · 2026-09-07

## Context

This service will be changed by people who were not present when it was written.
Code shows *what* was decided but rarely *why*, and the "why" is what determines
whether a future change is safe.

The specific failure this prevents: a constraint that looks arbitrary gets
removed, and the problem it was quietly preventing comes back — usually in
production, usually months later.

## Decision

Record significant decisions as ADRs in `docs/adr/`, numbered sequentially.
Each states Context, Decision, and Consequences — including the costs.

An ADR is warranted when a decision is expensive to reverse, constrains future
work, or would look wrong without its context. Routine choices are not recorded.

ADRs are immutable. A changed decision becomes a new ADR that supersedes the old.

## Consequences

- A reviewer can see the reasoning without reading the whole codebase
- Future changes start from the original constraints rather than from guesswork
- Small ongoing cost: a decision made without an ADR is effectively undocumented
