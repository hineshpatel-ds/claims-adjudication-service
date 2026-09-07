# Architecture Decision Records

An ADR records **one significant decision**: the context it was made in, what was
decided, and what that costs.

The point is not documentation for its own sake. It is that in six months
somebody — including you — will look at a constraint and think "this is
pointless, let's remove it." The ADR tells them what problem it was solving, so
they can make a real judgement instead of guessing.

A decision that is easy to reverse does not need an ADR. One that is expensive to
reverse, or that a reasonable person would question, does.

Records are immutable. When a decision changes, write a new ADR and mark the old
one `Superseded by ADR-NNNN` rather than editing it — the history is the value.

## Index

| # | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-flyway-owns-the-schema.md) | Flyway owns the schema; Hibernate validates | Accepted |
| [0003](0003-text-and-check-over-native-enums.md) | `TEXT` + `CHECK` over native Postgres enums | Accepted |
| [0004](0004-disable-open-in-view.md) | Disable `open-in-view` | Accepted |
| [0005](0005-package-by-feature.md) | Package by feature, not by layer | Accepted |
| [0006](0006-never-expose-entities-over-http.md) | Never expose entities over HTTP | Accepted |
