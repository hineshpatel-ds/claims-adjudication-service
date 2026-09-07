# Lesson 4 — Answers

**1. Load a claim, change a field, never call `save()`. Is the database updated?**

Yes — **provided the entity is still managed**, which means the change happened
inside the transaction that loaded it. Hibernate dirty-checks managed entities at
flush and issues an `UPDATE` for changed fields.

If the transaction has already closed, the entity is detached and the change goes
nowhere. Same two lines of code, opposite outcomes — the transaction boundary is
what decides.

**2. `outer()` calls `this.inner()`, `inner()` is `@Transactional`. Transaction started?**

No. `@Transactional` works through a proxy that wraps the bean. External callers
reach the proxy; `this.inner()` calls the real object directly and bypasses it
entirely. The annotation is silently ignored.

It fails quietly — no warning, no error, just no transaction — which is what makes
it dangerous. Fixes:

- Move `inner()` into a separate bean and inject it (cleanest, and usually
  reveals that the two methods had different responsibilities)
- Put `@Transactional` on `outer()` instead
- Self-inject the proxy (works, but a design smell)

**3. Service method throws a checked `Exception` after writing. Does it roll back?**

**No — it commits.** Spring rolls back on `RuntimeException` and `Error` by
default, not on checked exceptions. This inherits EJB convention, where checked
exceptions meant "recoverable business outcome."

It surprises nearly everyone and is a genuine source of data-integrity bugs. If
you need rollback on a checked exception:

```java
@Transactional(rollbackFor = Exception.class)
```

**4. Why does `open-in-view=true` risk exhausting the connection pool?**

It keeps the persistence context — and its database connection — open for the
whole HTTP request, including JSON serialisation and any other post-service work.

A connection held for 200ms when it was needed for 20ms is a 10x reduction in
effective pool capacity. Under load, requests queue waiting for a connection, and
latency climbs sharply while the database itself looks idle. That symptom —
slow app, bored database — is the signature of this misconfiguration.

**5. `findById(id)` twice in one transaction. How many `SELECT`s?**

**One.** The first populates the persistence context; the second is served from
it, and returns the same object instance (`a == b` is true).

This is the first-level cache. It is always on, scoped to the transaction, and
cannot be disabled. It also means you cannot "re-read" a row to see another
transaction's change without explicitly calling `refresh()` or clearing the
context.
