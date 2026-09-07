# Lesson 2 — Answers

**1. You add a dependency and the app now fails at startup. Likely cause, and how do you investigate?**

Auto-configuration. A new JAR on the classpath satisfies `@ConditionalOnClass`
conditions that were previously false, so Boot starts configuring something new —
and that new configuration usually needs properties you haven't set. The classic
case is adding a JDBC driver with no `spring.datasource.url`: Boot now tries to
build a `DataSource` and fails.

Investigate with the auto-configuration report:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--debug
```

Look at **positive matches** for what newly switched on. The startup exception is
usually accurate too — Boot's failure analysers print a "Description / Action"
block that names the missing property.

**2. Boot auto-configures a `DataSource`; you define your own. Which wins?**

Yours. The mechanism is `@ConditionalOnMissingBean` on Boot's auto-configuration:
it only creates a `DataSource` if no `DataSource` bean already exists. Your
definitions are processed first, so the auto-configuration backs off.

This is the general principle — **auto-configuration always yields to explicit
configuration**. You never fight the framework to override a default; you just
define the bean and Boot steps aside.

**3. Why doesn't a Spring Boot app need Tomcat installed on the server?**

The server is embedded. `spring-boot-starter-webmvc` brings Tomcat in as an
ordinary library, and the packaged JAR contains it. `java -jar app.jar` starts
the process, which starts Tomcat in-process and binds the port.

This inverts the old model: instead of deploying a WAR into a server someone else
installed and configured, the app owns its server and its config. That's what
makes containerisation clean — the image needs a JRE and your JAR, nothing else.
It's also what makes the app scale horizontally as identical, disposable units.

**4. Flyway runs before Hibernate validates the schema. Why does that matter?**

Because we set `ddl-auto: validate`. Hibernate compares your `@Entity` mappings
against the real tables and refuses to start if they disagree. If validation ran
first, it would check a schema Flyway hadn't created yet and always fail.

The ordering gives you a genuinely useful safety property: Flyway owns the schema,
Hibernate verifies the code matches it, and a mismatch between a migration and an
entity is caught **at startup** rather than as a `column does not exist` error on
some rarely-hit query in production.
