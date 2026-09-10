# Enabling SonarQube Cloud and Snyk

The CI workflow already contains both steps. They **skip silently** until the
corresponding token exists, so the build stays green on a fresh clone or a fork
rather than failing on missing credentials.

Enabling each is a one-time setup on the repository.

> **Naming:** Sonar renamed the products. What was *SonarCloud* is now
> **SonarQube Cloud**; the self-hosted server is **SonarQube Server**. Same
> analysis engine, same rules, same Maven goal — only the host URL and how the
> token is issued differ. Older documentation and the `sonarcloud.io` domain still
> use the previous name.

---

## SonarQube Cloud — static analysis and quality gates

Free for public repositories, with unlimited lines of code.

### 1. Install the GitHub App

Sign in at <https://sonarcloud.io> with GitHub.

When it asks which repositories to authorise, choose **Only select repositories**
and pick this one. The app requests *read and write access to checks, commit
statuses, pull requests, and security events* — legitimate, since it posts the
quality gate onto pull requests, but "All repositories" also covers every repo you
create in future. You can add more at any time.

### 2. Create the organization

Take the defaults, with one change: **untick "Automatically import new GitHub
repositories."** Private projects consume the 50k private-lines allowance, and
auto-import means a future private repo starts consuming it without you deciding
to.

The **Key** on this screen is your `SONAR_ORGANIZATION`. For this project:
`hineshpatel-ds`.

Choose the **Free** plan. Public repositories get unlimited lines of code, so
nothing here needs paying for.

### 3. Import the project

Select `claims-adjudication-service`, then **Set Up**. It is created as a *public*
project, which means the dashboard is linkable without an account — useful when
someone asks to see it.

The resulting project key is `hineshpatel-ds_claims-adjudication-service`, and it
also appears in the dashboard URL as `?id=...`.

### 4. Turn Automatic Analysis OFF — this one matters

**Administration → Analysis method → Automatic Analysis: off.**

Automatic Analysis is often enabled by default, and it will produce a working-looking
analysis that is quietly wrong. It runs on Sonar's servers by cloning the
repository, so it never executes the build and never sees
`target/site/jacoco/jacoco.xml`.

The symptoms:

- Coverage reports **0%**, with the message *"A few extra steps are needed for
  SonarQube Cloud to analyze your code coverage"*
- The default *Sonar way* gate requires 80% coverage on new code, so the gate
  fails on every push — despite actual coverage being 90%

The failure is confusing because nothing is wrong with the code, and the usual
reaction is to disable the quality gate, which discards the useful part.

This project's workflow runs the scanner **after** `./mvnw verify`, so the JaCoCo
report already exists when Sonar reads it.

### 5. Generate the token

**Analysis method → With GitHub Actions** walks through this and generates one, or
go directly to your avatar → **My Account → Security → Generate Tokens**
(<https://sonarcloud.io/account/security>).

Choose a **Project Analysis Token** scoped to this project rather than a global
User Token — if it leaks, the blast radius is one public repository instead of the
whole account.

**Copy it immediately.** The value is shown once; if you navigate away, delete it
and generate another.

### 6. Add the three values to GitHub

These live in the **repository's** settings, not your account settings — both are
called "Settings", which catches people out:

```
https://github.com/hineshpatel-ds/claims-adjudication-service/settings/secrets/actions
```

| Tab | Name | Value |
|---|---|---|
| **Secrets** | `SONAR_TOKEN` | the token from step 5 |
| **Variables** | `SONAR_PROJECT_KEY` | `hineshpatel-ds_claims-adjudication-service` |
| **Variables** | `SONAR_ORGANIZATION` | `hineshpatel-ds` |

Secrets are encrypted, masked in logs, and unreadable after saving. Variables are
plain text and visible in logs — which is where a project key is actually useful
when a run misbehaves.

Push any commit and the analysis runs with coverage attached.

### The quality gate

The default *Sonar way* gate applies to **new code only**. That is the right
default: it stops new problems entering without demanding you fix everything that
already exists before you can merge anything.

"Quality gate: Not computed" on a first analysis is normal — there is no previous
analysis to diff against, and gates are skipped when new code is under 20 lines.

---

## Snyk — dependency vulnerability scanning

1. Sign in at <https://snyk.io> with GitHub
2. **Account settings → Auth Token → click to show**
3. Add it as a repository **secret** named `SNYK_TOKEN`, same page as above

The `Dependency scan` job starts running on the next push.

### What it actually finds

This is the check that catches **the code you did not write**. A transitive
dependency four levels down with a published CVE is invisible to every test in
this repository — the code is correct, the library is not.

The scan uses `--severity-threshold=high`, so low-severity findings in test-only
dependencies do not block the build. That threshold is a judgement rather than a
rule: set it too low and people learn to ignore a permanently red build, which is
worse than not scanning at all.

---

## Describing this in an interview

> "SonarQube Cloud runs on every push with the quality gate scoped to new code, and
> Snyk scans the dependency tree for known CVEs. Coverage comes from JaCoCo and is
> reported rather than gated — a coverage threshold mostly produces tests that
> execute code without asserting on it."

That describes a pipeline you configured and can defend.

**If asked about SonarQube Server versus Cloud:** same engine, same rules, same
Maven goal; Server is self-hosted and Cloud is managed. Most banks and insurers
run Server on-premises. Saying that plainly is better than implying you have
operated the self-hosted server when you have not.
