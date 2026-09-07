# Lesson 3 — Reading Spring Failures

> Spring stack traces are long and intimidating, and beginners scroll to the top,
> read something unhelpful, and start guessing. There is a reliable method. This
> lesson uses the two real failures from setting this project up.

---

## First: which kind of failure is it?

| Kind | When | Looks like |
|---|---|---|
| **Build failure** | before any Java runs | `[ERROR]` from Maven, no stack trace, no Spring banner |
| **Startup failure** | context is being built | Spring banner appears, then `APPLICATION FAILED TO START` |
| **Runtime failure** | app is up, a request fails | HTTP 500, stack trace in the access path |

They have different causes and different fixes. Identify which one you have
before reading anything else.

---

## Case 1 — a build failure

```
[ERROR] Non-resolvable parent POM for com.hines:claims-service:0.0.1-SNAPSHOT:
        The following artifacts could not be resolved:
        org.springframework.boot:spring-boot-starter-parent:pom:4.1.1.RELEASE (absent)
```

No Spring banner, no `Caused by` chain. Maven never got as far as compiling.

Read it literally: *this exact coordinate does not exist in the repository.* Not
"the network is down", not "Spring is broken". A `groupId:artifactId:version`
triple was wrong.

**The habit worth building:** when a dependency won't resolve, check what
actually exists rather than guessing. The repository publishes its own index:

```
https://repo.maven.apache.org/maven2/<group path>/<artifact>/maven-metadata.xml
```

For us: `.../org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml`.
That file is authoritative. (The `search.maven.org` web index lags behind it —
during setup it showed 3.5.3 as newest while 4.1.1 had already been published,
which is exactly the sort of thing that sends you down a wrong path.)

The real bug: the version was `4.1.1.RELEASE`; the published artifact is `4.1.1`.

---

## Case 2 — a startup failure, and how to read the chain

The second failure produced roughly 300 lines. Here is the shape:

```
Error creating bean 'entityManagerFactory'
  Failed to initialize dependency 'flyway'
    Error creating bean 'flyway'
      Unsatisfied dependency ... parameter 0
        Error creating bean 'flywayContainerConnectionDetailsForPostgresContainer'
          Error creating bean 'postgresContainer'
            Could not find a valid Docker environment
```

### Rule 1 — read bottom-up

The **last** `Caused by` is the real cause. Everything above it is fallout: bean A
failed *because* bean B failed *because* bean C failed.

Here, the actual problem is the final line — Docker was not running. Every line
above it is noise. Beginners read `entityManagerFactory` at the top, conclude
something is wrong with JPA, and start editing entity mappings. Nothing was wrong
with JPA.

### Rule 2 — the chain is a free diagram of your object graph

Read that chain upward and it tells you how the app is wired:

- `postgresContainer` → Testcontainers supplies the database
- `flywayContainerConnectionDetails` → Flyway is pointed at that container
- `flyway` → migrations run
- `entityManagerFactory` → **only then** does JPA start

That confirms Lesson 2's startup ordering, from your own logs: **Flyway runs
before Hibernate**. You did not have to take that on faith — the failure proved it.

### Rule 3 — read Spring's own analysis first

Above the raw trace, Boot prints a block like:

```
***************************
APPLICATION FAILED TO START
***************************

Description:  <plain-English statement of the problem>
Action:       <what to do about it>
```

That is a **failure analyser** — Boot ships dedicated handlers for common
mistakes (port in use, missing datasource URL, bean not found). When it fires,
it is usually correct and saves you the whole trace. Read it before scrolling.

---

## The failures you will actually hit

| Message | Real meaning |
|---|---|
| `NoSuchBeanDefinitionException` | Spring never saw the class — usually outside the `@SpringBootApplication` package tree |
| `NoUniqueBeanDefinitionException` | two beans of one type; add `@Primary` or `@Qualifier` |
| `Failed to determine a suitable driver class` | JPA is on the classpath, `spring.datasource.url` is not set |
| `Could not find a valid Docker environment` | Testcontainers cannot reach Docker — the daemon is not running |
| `Port 8080 was already in use` | an old run is still alive |
| `Table 'x' doesn't exist` at startup | `ddl-auto: validate` found a schema/entity mismatch — a migration is missing |
| `Circular reference` | two beans require each other in their constructors; extract the shared part |

---

## The lesson underneath

`docker --version` succeeded at the very start of this project. It looked like
Docker was fine. But that command only asks the **CLI binary** for its version —
it never contacts the daemon.

To verify a service is genuinely usable, run something that requires the service
to answer:

```bash
docker ps        # needs the daemon; --version does not
```

The general habit: **check the thing you depend on, not a proxy for it.** This
applies well beyond Docker, and it is the difference between a green check that
means something and one that doesn't.

---

## Check yourself

1. A trace has six `Caused by` lines. Which one names the real problem?
2. Startup fails with `NoSuchBeanDefinitionException` for a class you can see in
   your IDE, spelled correctly. What is the most likely cause?
3. Why is `docker --version` a poor readiness check, and what would you run instead?

Answers in `03-answers.md`.
