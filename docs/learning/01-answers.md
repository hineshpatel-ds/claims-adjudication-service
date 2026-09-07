# Lesson 1 — Answers

**1. Why can't a field-injected dependency be `final`?**

A `final` field must be assigned during construction. Field injection works by
reflection *after* the object is already constructed, so at construction time
there is nothing to assign — the compiler rejects it. That ordering is also the
real problem: there is a window where the object exists but is not fully wired.
Constructor injection has no such window; the object is never observable in a
half-built state.

**2. `@Service` in `com.other.pkg`, main class in `com.app` — startup fails. Why?**

`@SpringBootApplication` scans its own package and everything below it. `com.other.pkg`
is not below `com.app`, so the class is never found, never becomes a bean, and
anything asking for it fails with `NoSuchBeanDefinitionException`.

Fixes, best first:
- Move the class under `com.app` — package layout should reflect the app anyway
- `@SpringBootApplication(scanBasePackages = {"com.app", "com.other.pkg"})`
- Import it explicitly with `@Import`

This is the most common "why won't my app start" cause for beginners. Read the
message literally: *no qualifying bean of type X* means Spring never saw the class.

**3. Two `@Component` classes implement `ClaimRepository`. What happens?**

Startup fails with `NoUniqueBeanDefinitionException` — Spring matches by type,
finds two candidates, and refuses to guess.

Note that it fails *at startup*, not on the first request. Spring resolves the
whole graph eagerly so misconfiguration surfaces immediately rather than at 3am
under load. That eager check is a deliberate design choice worth knowing.

Ways out:
- `@Primary` on the default one — the fallback winner
- `@Qualifier("postgresClaimRepository")` at the injection point to name the one you want
- `@Profile("test")` / `@Profile("prod")` so only one is active per environment
- Ask for `List<ClaimRepository>` if you genuinely want all of them
