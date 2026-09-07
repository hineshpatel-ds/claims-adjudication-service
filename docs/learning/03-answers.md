# Lesson 3 — Answers

**1. Six `Caused by` lines. Which names the real problem?**

The last one. Spring wraps each failure as it propagates up the bean graph, so the
chain reads outermost-symptom first, root-cause last. The top line tells you which
bean *noticed*; the bottom line tells you what actually broke.

Our case: the top said `entityManagerFactory` (JPA) and the bottom said Docker was
not running. Editing anything JPA-related would have been wasted work.

**2. `NoSuchBeanDefinitionException` for a class you can see, spelled correctly?**

It is outside the component-scan tree. `@SpringBootApplication` scans its own
package and everything beneath it, so a class in a sibling package is invisible no
matter how correct it is.

Check the package declaration against your main class's package. Ours is
`com.hines.claims`, so everything must live under `com.hines.claims.*`.

Second possibility: the class has no stereotype annotation at all. A plain class
is not a bean — Spring only manages what is marked `@Component`, `@Service`,
`@Repository`, `@RestController`, or produced by an `@Bean` method.

**3. Why is `docker --version` a poor readiness check?**

It only asks the local CLI binary to print its own version string. It never opens
a connection to the daemon, so it succeeds when Docker Desktop is fully stopped —
which is exactly what happened here.

Use a command that requires the daemon to answer:

```bash
docker ps        # or: docker info
```

The general rule: **check the dependency, not a proxy for it.** A version flag
proves a binary is installed; only a real request proves the service is up. The
same reasoning is why a health endpoint that returns a hardcoded `200 OK` is
worthless, while one that actually pings the database tells you something.
