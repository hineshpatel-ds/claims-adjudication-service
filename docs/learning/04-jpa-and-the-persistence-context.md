# Lesson 4 — JPA, Hibernate, and the Persistence Context

> This is the biggest conceptual jump in Spring, and the one people skip. Most
> serious production incidents in Spring apps — connection pool exhaustion,
> N+1 query storms, mystery updates, `LazyInitializationException` — come from
> not understanding what is in this lesson.
>
> Read it before writing the entity, not after.

---

## Who is who

| Name | What it is |
|---|---|
| **JPA** | A *specification*. Interfaces and annotations. `jakarta.persistence.*`. Ships no working code. |
| **Hibernate** | The *implementation* that actually runs. Spring Boot's default. |
| **Spring Data JPA** | A convenience layer that generates repository implementations for you. |

Three layers, often conflated. When someone says "JPA is slow," they nearly
always mean "I used Hibernate badly."

You write against JPA. Hibernate does the work. Spring Data removes the
boilerplate of calling Hibernate.

---

## The core idea: the persistence context

This is the concept everything else hangs off.

A **persistence context** is a workspace that Hibernate keeps for the duration of
a transaction. It holds every entity it has loaded or saved, keyed by primary key.

Two properties matter:

**1. It is an identity map.** Load the same row twice in one transaction and you
get *the same Java object*, not two equal copies:

```java
Claim a = repo.findById(id).orElseThrow();
Claim b = repo.findById(id).orElseThrow();

a == b;   // true — the second call never hits the database
```

That second call is served from the persistence context. This is the
**first-level cache**, and it is always on. You cannot disable it.

**2. It tracks changes.** When an entity is loaded, Hibernate keeps a snapshot of
its original field values. Before committing, it compares current values against
that snapshot and issues `UPDATE` statements for whatever differs.

That is **dirty checking**, and it surprises everyone the first time.

---

## Dirty checking — the behaviour that catches people out

```java
@Transactional
public void approveClaim(UUID id, BigDecimal amount) {
    Claim claim = repo.findById(id).orElseThrow();
    claim.setApprovedAmount(amount);
    claim.setStatus(ClaimStatus.APPROVED);
    // no repo.save(claim) anywhere
}
```

**The database is updated anyway.** The entity is *managed*, so at commit
Hibernate notices the changed fields and writes an `UPDATE`.

Two consequences worth internalising:

- `save()` is often redundant on an already-loaded entity. Harmless, but noise.
- **An accidental setter call inside a transaction becomes a database write.**
  This is a real source of production bugs — a helper method "normalises" a field
  for display, and silently rewrites the row.

The trade for that surprise is a good one: Hibernate batches the writes and
issues them at flush time, rather than a round trip per setter.

---

## Entity lifecycle states

An object is in exactly one of four states. Nearly every JPA confusion is a state
confusion.

| State | Meaning | Do changes persist? |
|---|---|---|
| **Transient** | `new Claim()`, never saved, no id | No |
| **Managed** | loaded or saved inside an open persistence context | **Yes — automatically** |
| **Detached** | context closed; object still in memory | No |
| **Removed** | marked for deletion, `DELETE` pending | n/a |

```java
Claim c = new Claim();          // TRANSIENT
repo.save(c);                   // MANAGED    — inside @Transactional
c.setStatus(APPROVED);          // tracked, will be written at commit
// method returns, transaction commits, context closes
c.setStatus(REJECTED);          // DETACHED   — changes nothing in the database
```

That last line is the classic bug. The code looks identical to the line above it
and behaves completely differently, because the transaction boundary moved
underneath it.

---

## `@Transactional` — what it actually does

```java
@Service
public class ClaimService {

    @Transactional
    public Claim submit(SubmitClaimCommand cmd) { ... }
}
```

Spring wraps your bean in a **proxy**. Calling `submit()` goes to the proxy
first, which opens a transaction and a persistence context, calls your real
method, then commits (or rolls back on a `RuntimeException`) and closes the
context.

Three rules that follow directly from it being a proxy:

**1. Self-invocation does not work.**

```java
public void outer() {
    this.inner();     // calls the real object, NOT the proxy
}                     // -> @Transactional on inner() is ignored entirely
```

The proxy is only involved when the call arrives from outside. An internal `this.`
call bypasses it. This silently produces no transaction, and it is one of the most
common Spring bugs there is.

**2. Only `public` methods are proxied.** `@Transactional` on a private or
package-private method does nothing.

**3. Rollback is on unchecked exceptions only, by default.** A `RuntimeException`
rolls back; a checked `Exception` **commits**. Use
`@Transactional(rollbackFor = Exception.class)` if you need otherwise.

### Read-only transactions

```java
@Transactional(readOnly = true)
public Claim findById(UUID id) { ... }
```

Hibernate skips dirty-check snapshots, so reads use less memory and do less work,
and the driver can route to a replica. Use it on every read path.

---

## Lazy loading and why `open-in-view` is off

Relationships can load eagerly (immediately) or lazily (on first access). Lazy is
usually right — you rarely need every association.

But lazy loading requires an **open persistence context**. Touch a lazy field
after the transaction closed and you get:

```
LazyInitializationException: could not initialize proxy - no Session
```

Spring Boot's default `spring.jpa.open-in-view=true` "fixes" this by holding the
persistence context — and a database connection — open for the entire HTTP
request, including while serialising JSON.

That is a bad trade:

- A connection is held during work that does not need one; under load the pool
  is exhausted and requests queue behind it
- Lazy loads fire during JSON serialisation, so queries are triggered by your
  view layer, invisibly
- The bug is hidden in development and appears as a latency cliff in production

**We set it to `false`.** You will occasionally hit
`LazyInitializationException` — that is the point. It surfaces at the moment you
write the bug, and the fix is to load what you need inside the service, which is
where that decision belongs.

Knowing why `open-in-view` should be disabled is a genuine senior-level signal,
and most Spring developers have never thought about it.

---

## Say it in an interview

> "Hibernate keeps a persistence context per transaction that acts as an identity
> map and dirty-checks managed entities, so changes to a loaded entity are written
> at commit without an explicit save. I disable `open-in-view` so a database
> connection isn't held for the whole request, which means loading decisions stay
> in the service layer instead of being triggered during JSON serialisation."

---

## Check yourself

1. You load a claim, change a field, and never call `save()`. Is the database
   updated? Under exactly what condition?
2. `outer()` calls `this.inner()`, and `inner()` is `@Transactional`. Is a
   transaction started? Why?
3. A service method throws a checked `Exception` after writing. Does the write
   roll back?
4. Why does `open-in-view=true` risk exhausting the connection pool?
5. You call `findById(id)` twice in one transaction. How many `SELECT`s run?

Answers in `04-answers.md`.
