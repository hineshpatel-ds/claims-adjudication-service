# Lesson 1 — Dependency Injection and the Spring Container

> The one concept everything else in Spring is built on. If this is fuzzy,
> every annotation later feels like magic. Once it clicks, most of Spring
> becomes obvious.

---

## The problem, in plain Java

Say a service needs a repository to fetch claims. The naive way:

```java
public class ClaimService {
    private final ClaimRepository repo = new PostgresClaimRepository();

    public Claim find(UUID id) {
        return repo.findById(id);
    }
}
```

This compiles and works. It is also close to unmaintainable, for three reasons.

**1. You cannot test it.**
`ClaimService` hard-wires a real Postgres repository. To unit test `find()`, you
now need a running database. There is no seam to slip a fake into.

**2. It knows too much.**
`ClaimService` should care about *claim rules*. Instead it knows Postgres exists.
Swap to a different store and you edit the service — which has nothing to do with
storage.

**3. Construction spreads.**
If `PostgresClaimRepository`'s constructor later needs a connection pool, every
class that wrote `new PostgresClaimRepository()` breaks at once.

---

## The fix: don't build your dependencies, ask for them

```java
public class ClaimService {
    private final ClaimRepository repo;

    public ClaimService(ClaimRepository repo) {   // handed in, not built
        this.repo = repo;
    }
}
```

`ClaimService` now depends on the **interface** `ClaimRepository`. It has no idea
whether the real thing is Postgres, in-memory, or a mock. In a test you pass a
fake. In production you pass the real one.

This is **Dependency Injection**. That is the whole idea — it is a design
practice, not a Spring feature. You can do it in any language.

## So what does Spring actually do?

DI creates a new problem: *somebody* has to assemble the object graph.

```java
var pool     = new ConnectionPool(url, user, password);
var repo     = new PostgresClaimRepository(pool);
var ledger   = new LedgerService(repo);
var service  = new ClaimService(repo, ledger, auditWriter, clock);
var controller = new ClaimController(service);
// ...times eighty classes
```

That wiring code is real, tedious, and error-prone. **Spring is that wiring code,
automated.**

At startup Spring:

1. **Scans** your packages for classes marked as managed components
2. **Instantiates** one of each — an instance Spring manages is called a **bean**
3. **Wires** them by matching constructor parameter types to available beans
4. **Holds** them in the *application context* (the container) for the app's life

You declare *what exists*. Spring works out *the order to build it in*.

---

## Inversion of Control

Normally your code calls the library. With a container, the framework constructs
your objects and calls into your code. Control is inverted — hence **IoC container**,
which is just another name for the application context.

---

## The annotations that mark a bean

All four do the same core job — "Spring, manage this class." The different names
document intent and add behaviour.

| Annotation | Use it on | Extra behaviour |
|---|---|---|
| `@Component` | anything | none — the generic one |
| `@Service` | business logic | none technically; signals intent |
| `@Repository` | data access | translates DB exceptions into Spring's exception types |
| `@RestController` | HTTP endpoints | `@Controller` + `@ResponseBody`; returns become JSON |

`@SpringBootApplication` on your main class turns on the scan, starting from that
class's package and everything beneath it. **This is why package layout matters**
— a class outside that tree is invisible to Spring, and the failure looks like
"no qualifying bean" at startup.

---

## Constructor injection, and why not the alternatives

```java
@Service
public class ClaimService {
    private final ClaimRepository repo;

    public ClaimService(ClaimRepository repo) {   // no @Autowired needed
        this.repo = repo;
    }
}
```

With exactly one constructor, Spring uses it automatically — `@Autowired` is
optional and modern code omits it.

You will see field injection in older tutorials:

```java
@Service
public class ClaimService {
    @Autowired private ClaimRepository repo;   // don't
}
```

Avoid it:

- The field cannot be `final`, so the object is mutable after construction
- A plain `new ClaimService()` in a unit test compiles but leaves `repo` null —
  the failure is an NPE at run time instead of a compile error
- Dependencies become invisible; a class can quietly accumulate ten of them,
  whereas an eight-argument constructor *looks* wrong and pushes you to split it

Constructor injection makes bad design visible. That is a feature.

---

## What this buys you in tests

```java
// Pure unit test — no Spring, no database, milliseconds
var fakeRepo = new InMemoryClaimRepository();
var service  = new ClaimService(fakeRepo);

assertThat(service.find(id)).isNotNull();
```

No container, no Postgres. This is why we design for injection: **testability is
a consequence of it**, not a separate task.

---

## Say it in an interview

> "Constructor injection, so dependencies are explicit and final. The service
> depends on the repository interface rather than the implementation, which means
> I can unit test the business rules with an in-memory fake and save the real
> database for integration tests against Testcontainers."

That answers *what you did*, *why*, and *what it enabled* — in three sentences.

---

## Check yourself

1. Why can't a field-injected dependency be `final`?
2. Your `@Service` sits in `com.other.pkg`, your main class in `com.app`.
   Startup fails. Why?
3. Two classes implement `ClaimRepository` and both are `@Component`. What
   happens when a service asks for one, and what would you do about it?

Answers in `01-answers.md` — try them first.
