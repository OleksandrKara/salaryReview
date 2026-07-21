# Backend test coverage: the ratchet gate

How CI enforces that unit-test coverage never regresses, and the plan to climb it toward 100%
without turning every PR into a slog or making `mvn verify` slow.

## The gate

`backend/pom.xml` wires `jacoco-maven-plugin` into the build:

- `prepare-agent` (bound to `test`) instruments the JVM running the existing test suite — no extra
  test run, just an agent attached to the one that already happens. Overhead is a few percent of
  test time, not a new pipeline stage.
- `report` (bound to `test`) writes the HTML/XML report to `target/site/jacoco/`.
- `check` (bound to `verify`) fails the build if whole-codebase coverage drops below the floor in
  two `pom.xml` properties:

  ```xml
  <jacoco.instruction.minimum>0.53</jacoco.instruction.minimum>
  <jacoco.branch.minimum>0.42</jacoco.branch.minimum>
  ```

CI runs `mvnw verify` (not just `test`) for exactly this reason — see `.github/workflows/deploy.yml`.

**Scope: whole codebase, no exclusions.** DTOs, Spring Data repo interfaces, `@Configuration`
classes, and the main app class all count toward the number. Several of these packages are near 0%
today (`repo`, `config`) — that's real, uncovered surface, not noise to filter out, and it's part of
what the ratchet is climbing toward.

## The ratchet, not a cliff

Going from today's real baseline (54% instruction / 43% branch, measured via an ad-hoc JaCoCo run —
see below) to 100% in one PR isn't realistic. Instead:

- The floor is set **just under** the current real number (53% / 42%) so today's suite passes
  cleanly, with a small margin for run-to-run noise (e.g. timing-dependent retry-path tests).
- **The floor only ever moves up.** Any PR that meaningfully raises coverage bumps
  `jacoco.instruction.minimum` / `jacoco.branch.minimum` in the same PR to just under its new real
  number — locking in the gain so a later PR can't silently give it back.
- No PR is required to raise coverage. The gate's only job is to catch *regressions*; closing the
  gap toward 100% happens incrementally, package by package, as normal feature/bugfix work touches
  that code — or as dedicated coverage passes, tracked below.

## Current baseline (as of this doc)

Measured via `mvn org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.12:report`,
366 tests:

| Package | Instruction | Branch |
|---|---|---|
| `commission` | 100% | 100% |
| `sop` | 78% | 64% |
| `marketing` | 73% | 68% |
| `telegram` | 70% | 57% |
| `web.dto` | 66% | — |
| `domain` | 61% | 21% |
| `square` | 60% | 44% |
| `kb` | 56% | 42% |
| `sms` | 52% | 39% |
| `ai` | 48% | 31% |
| `rag` | 41% | 37% |
| `service` | 24% | 100% |
| `web` (controllers) | 26% | 23% |
| `config` | 6% | 3% |
| `repo` | 0% | — |
| **Total** | **54%** | **43%** |

`web` (controllers) is the highest-value near-term target — most controllers are thin but
untested pass-throughs; a `MockMvc` test per controller (see `MarketingAdsReportControllerTest`
for the pattern) closes a lot of gap per test written. `config`/`repo` are Spring wiring and JPA
interfaces — genuinely near-zero logic, but they still count, so closing them means either a
trivial context-load smoke test per config class or accepting they'll be the last, slowest few
points on the way to 100%.

## Regenerating the report locally

```
cd backend
mvn org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.12:report
open target/site/jacoco/index.html   # per-package/per-class breakdown
```

(`mvn verify` also produces this report as a side effect of the gate — the two-step form above is
just handy for iterating without tripping the `check` failure while a package is mid-improvement.)
