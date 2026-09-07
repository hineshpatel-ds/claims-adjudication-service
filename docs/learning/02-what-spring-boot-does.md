# Lesson 2 — What Spring Boot Actually Does

> Lesson 1 covered the container: Spring builds your objects. But our generated
> project has no database code, no server code, and no configuration — yet it
> will start a web server and connect to Postgres. This lesson is where that
> comes from, so the generated project stops being a black box.

Spring Boot does three separable things. Most people blur them together, which is
why "Spring Boot magic" feels unknowable.

---

## Thing 1 — The parent POM manages versions

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1.RELEASE</version>
</parent>
```

Then every dependency below it is declared with **no `<version>`**:

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

The parent carries a *bill of materials* — a long list of library versions that
the Spring team tested together. You inherit that list.

**The problem this solves.** Hibernate wants Jackson 2.15. Your JSON library wants
Jackson 2.12. Maven picks one by nearest-wins, and you get a `NoSuchMethodError`
at run time in production, not at compile time. This is classic dependency hell,
and it used to consume entire days.

By inheriting the parent you get one coherent, pre-tested set. You *can* override
a single version, but you should know why you're doing it.

**Interview-relevant:** if asked "what does the Spring Boot parent give you," the
answer is dependency version management first — not magic.

---

## Thing 2 — Starters are curated bundles

A starter contains **almost no code**. It's a POM that depends on other things.

`spring-boot-starter-webmvc` pulls in Spring MVC, Jackson for JSON, validation
plumbing, and an embedded Tomcat. One line instead of fifteen, with versions
already agreed.

Our seven:

| Starter | What it drags in |
|---|---|
| `spring-boot-starter-webmvc` | Spring MVC, Jackson, embedded Tomcat |
| `spring-boot-starter-data-jpa` | Hibernate, Spring Data, HikariCP pool, transactions |
| `spring-boot-starter-flyway` | Flyway migration engine |
| `spring-boot-starter-validation` | Jakarta Bean Validation (Hibernate Validator) |
| `spring-boot-starter-actuator` | health, metrics, info endpoints |
| `postgresql` | the JDBC driver (not a starter — a plain driver) |
| `testcontainers-*` | real Docker containers in tests |

Note the embedded Tomcat. There is no server to install and no WAR to deploy —
the server is a library inside your JAR. That is why a Spring Boot app
containerises so cleanly: `java -jar app.jar` and you have an HTTP service.

### A Boot 4 change worth knowing

Boot 3 had one catch-all `spring-boot-starter-test`. Boot 4 splits it per module —
`spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test`, and so on.
You only pull in the test machinery for the parts you actually use. If you follow
an older tutorial that references `spring-boot-starter-test`, that is why it
isn't in our POM.

---

## Thing 3 — Auto-configuration (the actual magic)

This is the part worth understanding properly.

```java
@SpringBootApplication
public class ClaimsServiceApplication { ... }
```

That single annotation is three annotations stacked:

| Part | Job |
|---|---|
| `@Configuration` | this class may itself define beans |
| `@ComponentScan` | find `@Service`/`@Repository`/`@RestController` **from this package down** |
| `@EnableAutoConfiguration` | the interesting one |

`@EnableAutoConfiguration` tells Boot: look at what's on the classpath and
configure sensible defaults for it.

Boot ships hundreds of auto-configuration classes. Each is guarded by conditions:

```java
@AutoConfiguration
@ConditionalOnClass(DataSource.class)          // only if JDBC is on the classpath
@ConditionalOnMissingBean(DataSource.class)    // and only if I didn't define one
public class DataSourceAutoConfiguration { ... }
```

Read those conditions as a sentence:

> *"If a JDBC driver is on the classpath, and the user hasn't defined their own
> `DataSource` bean, then create a connection pool for them."*

That is the whole model. **Nothing is hidden — it's a giant pile of `if`
statements evaluated at startup.**

Two consequences that explain most beginner confusion:

1. **Adding a dependency changes behaviour.** Drop in the Postgres driver and
   Boot starts trying to configure a datasource. Startup failures right after
   adding a dependency are usually this.
2. **Defining your own bean silently switches the default off.** That's
   `@ConditionalOnMissingBean`. Your definition always wins — you never have to
   fight the framework, you just define the bean yourself.

### The command that makes it visible

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--debug
```

This prints the **auto-configuration report**: every candidate, split into
positive matches (applied, and why) and negative matches (skipped, and which
condition failed). When something isn't configuring the way you expect, this
report tells you which condition was not met.

Very few juniors know this flag exists. Knowing it is a genuine signal that you
understand the framework rather than copying from it.

---

## The startup sequence

`SpringApplication.run()` in order:

1. Work out the application type (web / reactive / plain) from the classpath
2. Load `application.properties` / `application.yml`, env vars, CLI args
3. Create the application context
4. Component-scan for your `@Service`, `@Repository`, `@RestController`
5. Evaluate every auto-configuration condition
6. Instantiate all beans, resolving the dependency graph in order
7. Run Flyway migrations (before JPA validates the schema)
8. Start the embedded Tomcat and bind the port

Step 6 is why misconfiguration fails **at startup**, not on first request.
Spring builds the whole graph eagerly. A missing bean or an ambiguous one takes
the app down in development instead of at 3am under load. That eager check is a
deliberate design decision, and it is a good answer to "what do you like about
Spring."

Step 7's ordering matters for us: migrations run **before** Hibernate checks the
schema, which is what makes Flyway-managed schemas work with JPA validation.

---

## The Maven wrapper

`mvnw` / `mvnw.cmd` and `.mvn/wrapper/` pin the Maven version per project.
Anyone cloning the repo — including CI — runs `./mvnw` and gets the exact Maven
this project expects, with no local install. Always use `./mvnw`, never `mvn`,
so your machine and CI genuinely match.

---

## Check yourself

1. You add a dependency and the app now fails at startup. What's the most likely
   category of cause, and which flag would you use to investigate?
2. Boot auto-configures a `DataSource`. You define your own `DataSource` bean.
   Which wins, and which annotation decides that?
3. Why does a Spring Boot app not need Tomcat installed on the server?
4. Flyway runs before Hibernate validates the schema. Why does that ordering matter?

Answers in `02-answers.md`.
