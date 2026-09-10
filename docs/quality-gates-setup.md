# Enabling SonarCloud and Snyk

The CI workflow already contains both steps. They **skip silently** until the
corresponding token exists, so the build stays green on a fresh clone or a fork
rather than failing on missing credentials.

Enabling each is a one-time setup on the repository.

---

## SonarCloud — static analysis and quality gates

Free for public repositories.

**1. Sign in.** Go to <https://sonarcloud.io> and sign in with GitHub. Authorise
it for this repository only — there is no reason to grant it access to
everything.

**2. Create the project.** *Analyze new project* → pick
`claims-adjudication-service`. Note the two values it shows you:

- **Project key** — usually `hineshpatel-ds_claims-adjudication-service`
- **Organization** — usually `hineshpatel-ds`

**3. Choose the analysis method.** Select **GitHub Actions**, *not* automatic
analysis. Automatic analysis cannot see the JaCoCo coverage report, so coverage
shows as 0% and the quality gate then fails on "coverage on new code" for no real
reason. If you enabled automatic analysis already, turn it off in
*Administration → Analysis Method*.

**4. Add the token.** SonarCloud shows a `SONAR_TOKEN`. In GitHub:

*Settings → Secrets and variables → Actions*

| Type | Name | Value |
|---|---|---|
| Secret | `SONAR_TOKEN` | the token from SonarCloud |
| Variable | `SONAR_PROJECT_KEY` | the project key above |
| Variable | `SONAR_ORGANIZATION` | the organization above |

The token is a **secret** (masked in logs, never readable again). The other two
are **variables** — they are not sensitive, and keeping them out of secrets means
they show up in logs where they are useful for debugging.

**5. Set the quality gate.** The default *Sonar way* gate applies to **new code
only**, which is the right default: it stops new problems entering without
demanding you fix everything that already exists before you can merge anything.

Push a commit, and the analysis appears at
`https://sonarcloud.io/project/overview?id=<your project key>`.

### Adding the badge

Once the first analysis completes, SonarCloud generates badge markdown under
*Project Information → Badges*. Add it next to the CI badge in the README.

---

## Snyk — dependency vulnerability scanning

Free tier covers open-source projects.

**1. Sign in** at <https://snyk.io> with GitHub.

**2. Get the token.** *Account settings → Auth Token → click to show*.

**3. Add it** as a GitHub secret named `SNYK_TOKEN`, the same way as above.

That is all. The `Dependency scan` job starts running on the next push.

### What it actually finds

This is the check that catches **the code you did not write**. A transitive
dependency four levels down with a published CVE is invisible to every test in
this repository — the code is correct, the library is not.

The scan is set to `--severity-threshold=high`, so low-severity findings in
test-only dependencies do not block the build. That threshold is a judgement, not
a rule: too low and people learn to ignore a permanently red build, which is
worse than not scanning at all.

---

## What to say about this in an interview

Worth being precise, because the difference is noticeable:

> "SonarCloud runs on every push with the quality gate scoped to new code, and
> Snyk scans the dependency tree for known CVEs. Coverage comes from JaCoCo and is
> reported rather than gated — a coverage threshold mostly produces tests that
> execute code without asserting on it."

That describes a pipeline you configured and can defend. "I added SonarQube to my
project" describes a checkbox.

### If asked about SonarQube versus SonarCloud

Same analysis engine. SonarQube is the self-hosted server, SonarCloud is the
hosted service. An organisation running SonarQube on-premises — most banks and
insurers do — uses the same rules, the same quality gate concept, and the same
Maven goal; only the `sonar.host.url` and how the token is issued differ.

Saying that plainly is better than implying you have run the self-hosted server
when you have not.
