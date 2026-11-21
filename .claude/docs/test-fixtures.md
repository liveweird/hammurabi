### Test fixtures and harness internals

The binding one-line rules are in `testing.md`; this file is the reference behind them. v0.1.0 has the harness, the OpenAPI conformance layer, the fork warm-up and the
watchdogs, the weight-balanced fork order and the fixture-tripwire machinery — Flow's domain fixtures (the synced and derived stub fixtures, the normalized-layer sweep)
were not ported. When an ADR increment adds a shared fixture, its rules (warm in a `@BeforeClass`, read-only vs clone, the guard test, the tripwire) are added here in the same commit.

**Runtime OpenAPI conformance.** Every `/api/` interaction the server test suite produces is
validated against `documentation.yaml` by the `OpenApiConformance` Ktor client plugin
(`server/src/test/kotlin/OpenApiConformance.kt`), installed via the shared test-client defaults —
so `jsonClient()`/`authedClient()` traffic is checked automatically and drift (undeclared
endpoint/method/status, response-schema or content-type mismatch) fails the exercising test with
the validation report. Response side is fully validated; request-side validation is ignored except
unknown-path/method (tests deliberately send malformed payloads and missing tokens). The spec is
fed to the validator relabeled 3.0.3 in memory (swagger-request-validator's 3.1 support is
unreliable); `OpenApiSpecTest` pins that the document stays 3.0-compatible (use `nullable:`, never
`type: [..., "null"]`) plus static invariants (unique operationIds, 401/500 declared, paths under
`/api/v1/`). `-Dopenapi.conformance=warn|off` relaxes enforcement for drift triage only (default
`fail`). A coverage report (exercised vs. declared operation/status pairs) is written to
`server/build/reports/openapi-conformance/coverage.md` after each test run, and it is a GATE: the
sibling `gaps.txt` lists every declared (operation, status) pair the suite never produced — minus
the cross-cutting statuses a shared plugin answers for every route alike and one test pins each
(`400` malformed id/body, `401`, `413`, `415`, `429`) and `500`/`default`, which the public API
offers no honest way to force — and `server/build.gradle.kts` fails the `test` task on a
non-empty file whenever the WHOLE suite ran (a `--tests` filter skips the gate). The gate is
**fork-safe**: each test JVM writes its own `exercised-<pid>-<uuid>.txt` and re-merges every fork's file
under a file lock into the ONE `coverage.md` + `gaps.txt` (`OpenApiCoverageMerge`, pinned by
`OpenApiCoverageMergeTest` — a pair exercised by ANY fork is covered, so the last fork to exit
leaves the complete union), and the `test` task clears the directory first so stale per-fork files
never leak in; a whole-suite run that leaves NO `gaps.txt` fails the gate too. A new operation
therefore lands with a test per declared status, or with its status list trimmed to what the
route can actually answer (`CoverageGapsTest` pins the cross-cutting statuses). `413` is declared on EVERY operation that takes a request body
(`OpenApiSpecTest` pins it) but is never a per-operation test — the one `PayloadValidationTest` case covers the shared
limit. Tests that use
`testApplication`'s default `client` bypass the plugin — prefer `jsonClient()`.

**Schemathesis (optional manual fuzz pass, not in CI).** Property-based fuzzing of the running
stack from the spec: `docker compose up --build` (compose ships dev mode, so `/openapi` is
exposed), grab a token —
`TOKEN=$(curl -s -X POST localhost:8090/api/v1/login -H 'Content-Type: application/json' -d '{"email":"admin@hammurabi.local","password":"changeme"}' | jq -r .token)`
— then
`uvx schemathesis run -c all -H "Authorization: Bearer $TOKEN" --exclude-path /api/v1/logout http://localhost:8090/openapi/documentation.yaml --url http://localhost:8090`.
The `/logout` exclusion is load-bearing: fuzzing it **revokes the bearer token** (everything after
401s). Login fuzzing also trips the per-account lockout for `admin@hammurabi.local` (the spec's
example email) — in-memory, so `docker compose restart app` clears it. Expect residual noise from
stateful invariants the spec cannot express (rate-limit 429s, TRACE probes); a **`Server error`
count above zero is the real signal**. It complements, not replaces, the suite-piggybacked
conformance layer above; fuzz junk lives only in the compose volume (`docker compose down -v`
resets). Needs `uv` (or `pipx`); no Python dependency lives in the repo.
**E2E scenarios (design artifacts).** The Playwright suite in `e2e/` is governed by
`e2e/README.md` (run recipes, the parallel state-ownership rulebook, the coverage map). Every spec
has a **natural-language scenario file** in `e2e/scenarios/` — versioned, deliberately
non-executable design artifacts (actors, owned state, numbered user-level steps, expected
outcomes; `## Scenario:` headings equal the `test()` titles verbatim). `e2e/scenarios/README.md`
holds the format and the **compiler contract** — the house rules any human/agent/tool must
satisfy when turning a scenario into spec code. Same-commit rule: a new or behaviorally changed
test lands with its scenario file and its one-line entry in the e2e README's coverage map;
`cd e2e && npm run check:scenarios` enforces the parity mechanically (both directions, orphan
files included), and `npm run typecheck` covers what Playwright's transpile-only TS handling
never checks. CI also runs `cd e2e && npm run test:setup`; the setup tests cover reuse of a
healthy stack, startup of the unavailable default stack, and the invariant that services and
volumes remain intact. E2E global setup reuses a healthy stack, starts the default stack when
unavailable, and leaves services and volumes intact. (Flow's shared read-only synced fixture, exported to the specs as `E2E_SHARED_*`, is not ported; the ADR increments add a deterministic dataset and its own fixture rules here — `e2e/README.md` "Parallel execution".)

**Balanced fork order (`-Pforks>=2`).** Gradle's `MaxNParallelTestDefinitionProcessor` deals candidate classes to
the forks round-robin in visit order (position `p` goes to fork `p % forks`), all up front, with no work stealing;
the scanner visits each directory in sorted order. `server/build.gradle.kts` therefore replaces `testClassesDirs`
with a composite of one file tree per candidate class (a composite is visited in declaration order and keeps
package-relative paths) plus a per-directory catch-all for everything that is not `**/*Test.class`, so the candidate
set equals the plain tree's. The order comes from `balancedCandidateOrder`: classes sorted by weight, descending
(ties by name), dealt greedily to the least loaded fork that still has a free slot (each fork's slot count is capped
at its round-robin share, so the interleaving reproduces the plan), then position `p` takes fork `p % forks`'s next
class. The weights are `server/test-weights.tsv` (`<FQN><TAB><seconds>`, suite time minus the fixture-cost markers,
written by `local-times.mjs test-weights` from a full run; the committed file is the donor's, pruned to Hammurabi's classes — re-measure it once the suite has run on
this repository). Degradation: a missing or unparseable file warns once and keeps the alphabetical order; a class not in the file weighs the average; a deleted class's row is
ignored. `--tests` filters work unchanged. The file is not a task input (only the class trees are). A red local rerun is reordered by Gradle's `RunPreviousFailedFirst` (failed
classes go first), so the balance is off for that rerun. `slow-tests --forks N --weights server/test-weights.tsv` prints the fork-balance block (span vs sum / N, drift of measured
against recorded weights; the CI `server` job and `scripts/gates.sh` run it).

**The pinned head: fork warm-up (every `-Pforks`).** The first class of a JVM pays the container, Flyway and the first
Ktor boot, and when it runs in `testApplication` that is inside the hard 60 s limit. `ForkWarmup.kt` moves it into a
`@BeforeClass` (JUnit 4 does not time it): `ForkWarmup.warm()` migrates the container (`ensureMigrated()`), boots the app
once via `TestApplication { configureApp() }` and throws it away (marker `app-boot`, beside the `postgres` marker in `TEST-ch.nokillswit.ForkWarmup<k>Test.xml`); `ForkWarmup.assertWarm()` is the
one test of each `ForkWarmup<k>Test`. There are eight such classes (`ForkWarmup1Test`..`ForkWarmup8Test`, one per
`-Pforks` value 1..8) because Gradle deals one class FILE to one fork and collapses a repeated class name, while
`TestClassNamingTest` allows one concrete `*Test` class per file and forbids an abstract `*Test` base. The `testClassesDirs`
Callable puts `ForkWarmup1Test`..`ForkWarmup<forks>Test` at positions `0..forks-1` (one file tree each), so the round-robin
deal gives fork k its own `ForkWarmup<k>Test` as the first class it runs. `balancedCandidateOrder` (via `isForkWarmup`) never
plans a warm-up class; the surplus `ForkWarmup<forks+1..8>Test` stay out of the run through the per-dir
`exclude(**/*Test.class)` catch-all in the weights path and through `exclude(FORK_WARMUP_GLOB)` in the plain path (`-Pforks=1`
or no weights file). The Callable never throws: before `compileTestKotlin` ran, or while the compiled tree is stale
and lacks a needed `ForkWarmup<k>Test.class`, it returns the plain dirs (so `--dry-run` works) and is evaluated again at execution; the test task's `doFirst`, after the
compile, then fails a run whose class is still missing (naming the file; "run clean" if the source exists). Not covered: a `--tests` run drops every
warm-up class (no test in it matches), and a failed-first rerun (`RunPreviousFailedFirst`) may open a fork with another
class; both still open cold, which is fine for a focused run. The **tripwire** (`ForkWarmupLedger`, next to the stall
watchdog in `server/build.gradle.kts`) records each JVM's first class and the result of every `ForkWarmup*Test`; a FULL run
(the OpenAPI gate's condition: no `--tests`) fails unless exactly `ForkWarmup1..forksTest` ran SUCCESSFULLY in `forks`
distinct JVMs, and only warns about a JVM whose first class was not its warm-up. If the previous run's warm-up class failed, Gradle runs failed classes first and can deal two
warm-ups to one fork, so the distinct-JVM check can fire once on that rerun: rerun. It guards the Gradle-internals assumptions (round-robin dealing, in-order forwarding,
class-name dedupe). The throwaway boot runs `Bootstrap` on a fresh database before any test: no test may assume it is the fork's first boot.

**Fixture tripwires.** `FixtureTripwire.kt` (pinned by `FixtureTripwireTest`) is the machinery for a shared, read-only fixture connection: the fixture arms it with the baseline it captures
when its own init completes, and every later `connectionId()` call re-snapshots the connection and compares, so a consumer that mutates the shared state fails the NEXT test that asks
for it, whatever class order the fork runs in (`perCallerClass` re-checks an expensive snapshot only when the calling test class changes; after a failure the baseline is re-captured,
so one mutation fails ONE test). No fixture uses it in v0.1.0 (Flow's synced and derived stub fixtures did); the first shared ADR fixture arms it and adds its explicit tripwire test here.

**Coverage gates.** Backend Kover enforces line- and branch-coverage floors in
`server/build.gradle.kts` (the `minBound` line/branch floors, wired into `check` via
`koverVerify`). The measurement EXCLUDES classes annotated `@kotlinx.serialization.Serializable`
(`kover.reports.filters`): kotlinx-serialization synthesizes one branch per optional property in a
data class's generated constructor and serializer — noise no test can exercise; behavior never
lives in a wire shape (services, validators and a DTO's companion object stay measured). The
floors sit just below current actuals — the convention is to **re-measure and raise** them as
coverage improves, never to lower them for new code: `check` runs only `koverVerify`, so run
`./gradlew :server:koverXmlReport` for fresh actuals. Every fork's Kover agent writes into the one shared `test.ic` (if CI ever flakes on Kover, fall back to `-Pforks=1`).
Kover costs roughly a tenth of the test wall and stays on everywhere `check` runs — the floors are a gate. There is **no
Gradle property to switch it off**; for a timing run or a tight dev loop over `:server:test` alone, temporarily add
`kover { currentProject { instrumentation { disabledForTestTasks.add("test") } } }` to `server/build.gradle.kts` and never commit
it (no `test.ic` is written, so `koverVerify` would have nothing to check). The floors (lines 97, branches 81) were re-baselined once after the trim and are to be raised as the ADR features add tests (`BACKLOG.md`). Frontend vitest enforces thresholds in
`web/vite.config.ts` (`test.coverage.thresholds`, same re-measure convention — the current
actuals are noted in a comment beside them); run `cd web && npm run test:coverage`.

**Server test setup and forks.**

Backend tests live flat in `server/src/test/kotlin/` (kotlin.test + `io.ktor.server.testing.testApplication`)
and override the `postgres.*` config keys via `MapApplicationConfig` to point at a Testcontainers
`PostgreSQLContainer("postgres:18.6-alpine@sha256:10a5c69a…")` (the same digest `docker-compose.yaml` and
`k8s/postgres-deployment.yaml` pin — `PostgresImagePinTest`) started lazily by `PostgresTestSupport` and **shared
across the whole suite within a JVM fork** (one container per fork; test-side direct database access, `sharedDatabaseForTests()`, goes through a small
r2dbc-pool built by production's own `connectPooledDatabase` — an unpooled connect paid ~4 ms of backend
setup per transaction). Running tests requires a working Docker daemon (Docker Desktop,
OrbStack, etc. — with OrbStack and no `/var/run/docker.sock`, export
`DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`). The container runs **all** Flyway
migrations, so the V3 seed admin (`admin@hammurabi.local`) is present — tests scope their assertions
with unique prefixes/filters (`uniqueEmail("marker")`) rather than asserting absolute counts.

**Parallel forks (`-Pforks=N`).** `server/build.gradle.kts` sets `maxParallelForks` from the `forks` Gradle
property (an integer 1..8, anything else fails any build that runs the `test` task; default 1 = one JVM; CI and `scripts/gates.sh` run `-Pforks=2`; `forkEvery` is deliberately unset). Each fork is its own
JVM, so it starts its OWN Testcontainers Postgres (`PostgresTestSupport` is a per-JVM `object`) and holds its own
fixture singletons — every "shared suite" rule in this doc
(`withSoloAdmins`, `restoreSeedAccounts`, the seed admin) therefore holds per fork, never across forks; a test must never assume another class ran before it
on the same JVM, or that a class ran at all in this one. Gradle hands whole CLASSES to forks in whatever order it
likes, so **class order is not a contract**: any class may be the first thing a fork runs after the fork's pinned
`ForkWarmup<k>Test` (a `--tests` run or a failed-first rerun has no such pin and opens cold). The container is migrated by `PostgresTestSupport` itself the moment it starts (a `Flyway.migrate()`
in its lazy init, the same call as `infra/db/Flyway.kt`), so a fixture or raw-JDBC helper never needs its own
"has Flyway run yet?" guard (`PostgresTestSupport.ensureMigrated()` is the explicit spelling). The OpenAPI gate above
merges the forks' coverage.

**Stall watchdog (`-PtestStallMinutes=N`).** Why: on 2026-10-17 (CI run 39062260538) a hung test left the `server` job silent until the
25-minute timeout cancelled it, and a cancelled job uploads no `server-reports` artifact, so there was no evidence. `TestStallWatchdog` (top of the `test`
task's section in `server/build.gradle.kts`) is a Test-task listener that tracks, per test JVM (the "Gradle Test Executor N" suite in a descriptor's parent chain),
the time of the last suite/test start or finish. An executor that has started, not finished and been quiet for the threshold gets (1) `jcmd <pid> Thread.print -l`
(the `jcmd` of the test task's `javaLauncher`; the test JVM's pid is found among Gradle's descendants by its command line, which ends in `'Gradle Test Executor N'`)
written to `server/build/reports/hung-tests/Gradle-Test-Executor-N-<yyyyMMdd-HHmmss>.txt` and logged at ERROR with the class/test in flight, then (2) its
JVM is destroyed, so the task fails ("Process 'Gradle Test Executor N' finished with non-zero exit value 137") once the other forks have run their
queues, instead of at the CI job timeout (`jcmd` itself is bounded at 30 s, so a JVM too wedged to attach to is still killed). The
directory is cleared at the start of each run; CI uploads `server/build/reports` with the `server-reports` artifact when the job is not cancelled, and the
dump is in the log either way. `-PtestStallMinutes` is an integer 1..60 (default `DEFAULT_TEST_STALL_MINUTES` = 5; the measurement behind it - carried over from Flow - is in the comment on the constant); every executor also logs `test watchdog: … longest quiet gap N s (in flight: …)` when it finishes, which
is the number to re-measure the default from. The quiet gap is a single test's run time or a class's `@BeforeClass` fixture warm-up, nothing else.
The matching source-side bound: every raw JDBC connection a test opens comes from `PostgresTestSupport.connect()` (pgjdbc `connectTimeout`/`loginTimeout` 30 s,
`socketTimeout` 180 s, `tcpKeepAlive`), never a bare `DriverManager.getConnection`: a call parked in a socket read cannot be cancelled, and the 180 s bound is
above any legitimate statement (a lock wait behind another transaction) and below the watchdog, so one half-dead connection fails ITS test with a stack trace.
**The `TestEnvironment.kt` harness** — use it instead of hand-rolling setup:

- `configureApp(vararg overrides)` (on `TestApplicationBuilder`, so `ForkWarmup.warm()` can use it outside `testApplication`) points the app at the shared container (with CSRF off)
  WITHOUT starting it — tests that assert startup behavior (the fail-closed checks) add their
  overrides and call `startApplication()` themselves; `usePostgresTestcontainer()` is the
  configure-and-start shorthand. It sets `mail.appUrl` to `https://localhost:8090` (the origin the emailed reset link is built
  from, validated at boot in every mode - so `https`, which the production-mode boot tests, reusing this config, also accept); a test
  that wants the blank-origin 503 or a malformed origin overrides it.
  It sets `telemetry.enabled` to `false` (the sampler would write a process row and samples into the shared database from every booted app):
  the telemetry tests (`TelemetrySamplerTest`, `TelemetryStoreTest`) build the sampler directly over `TelemetryFixtures.kt`'s helpers and empty `ops`
  before each test (`wipeTelemetry`; nothing else writes there); a test that wants the real sampler opts in with `"telemetry.enabled" to "true"` plus
  `TelemetryOverridesKey` (a fixed identity, an immediate first tick).
- `ForkWarmup.warm()` / `ForkWarmup.assertWarm()` (`ForkWarmup.kt`): the fork's untimed Postgres + first-boot warm-up, called only by the `ForkWarmup<k>Test` classes; no other test calls it.
- Password-reset fixtures: `newPasswordResetService(ttlMillis, clock)` builds a `PasswordResetService` over the shared database (an
  injected clock for expiry), `issueToken(userId, revision, email)` unwraps its `IssueOutcome` to the emailed token (null when stale
  or capped), and `resetTokenHashes(userId)` lists the stored digests (the table never holds a raw token).
- `jsonClient()` / `authedClient(email, password)` — the standard HTTP clients; both go through
  the shared test-client defaults (JSON + `application/problem+json` negotiation + the
  `OpenApiConformance` plugin), and `authedClient` logs in and attaches the bearer on every
  request.
- `LogCapture(loggerName)` + `hasKeyValue` — a Logback `ListAppender` for asserting the audit
  trail (`ch.nokillswit.audit`); `awaitEvent` polls for asynchronously produced events. Detach in
  a finally.
- `TestUsers.seed(email, password, name, role)` (bcrypt cost 4 for speed; defaults to ADMIN — pass
  `UserRole.USER` for a non-privileged caller) — every seeded user funnels through
  `UserService.create`, so they carry the inverted-default MFA-disabled row and log in
  single-step and `TestUsers.softDelete(id)` (direct table update, bypassing the delete endpoint's
  guards) and `TestUsers.withSoloAdmins(ids) { }` (temporarily parks every other active admin —
  the last-admin-protection pin). `seededClient(prefix, role)` is the one-line seed+login
  fixture.
- `postJson`/`putJson` (the JSON body ceremony) + `HttpClient.login` (the raw login POST),
  `withAuditCapture { }` (attach/detach on the audit logger), `withSeedRestored { }` and
  `assertStartupFails(part) { }` for bootstrap/fail-closed tests — use these instead of re-rolling
  the blocks they replaced.
- **Many failed logins → seeded accounts.** A login for an UNKNOWN email pays a discarded cost-12 bcrypt
  verify (the timing equalizer, ~225 ms locally); a test that needs a dozen failures (rate limit,
  proxy trust) logs in as freshly seeded accounts (`TestUsers.seed`, cost 4) with a wrong password —
  the same 401 path at ~1 ms; soft-delete them afterwards.
- Tag and successor fixtures (`TagFixtures.kt`, 0.4.0): `uniqueTag()` / `categoryName()` (the registry is SHARED state and a tag name is unique for good, retired ones included - never reuse a literal, and never mutate the seeded Domain / Scope categories), `HttpClient.createCategory` / `postCategory` / `saveCategory` / `deleteCategory` (both send the category's current revision unless given one; `categoryRevision`) / `setTags` / `setSupersedes` / `detachSuccessor` / `registryTags` and `rawSupersedesId`. The parallel link, cycle and activation tests (`AdrSuccessorTest`) and the registry race (`TagCategoryTest`) use `coroutineScope { async(Dispatchers.IO) }` like `AdrTest`'s counter test.
- ADR fixtures (`AdrFixtures.kt`, no shared state): `adrActor(prefix, role)` seeds a user and signs them in (`AdrActor(id, client)`), `adrCast()` the six people of one ADR
  (ADMIN, Driver, Approver, Contributor, Informed, a stranger; `cast.raise()` makes an ADR with the other three roles attached), `HttpClient.createAdr` / `getAdr` / `saveAdr` /
  `moveAdr` / `moveAdrNow` / `setParticipants` / `adrEvents` and `forceAdrStatus` / `forceAdrExpiry` / `rawAdrStatus` (direct table access: a state the API refuses to reach, e.g.
  a past expiry). ADRs are SHARED suite state like teams: every name is unique (`adrName()`), a list assertion is scoped by a unique marker or participant, and the
  suite runs no expiry loop (`configureApp` sets `adrs.expiry.enabled` to false; `AdrExpirySweepTest` builds `AdrExpirySweep` with a pinned `Clock` or opts in).
- `TestTeams.seed(name)` — a fresh team fixture; beside it, raw-row readers for what
  the API hides (`TestTeams.rawRows`, `TestUsers.stampPasswordChangedAt`). Shared
  suite state (the seed admin) is never mutated destructively — tests mint UNIQUE rows and remove
  their own.
- `TestSeedState.restoreSeedAccounts()` — bootstrap/production-mode tests rotate the seed admin's
  password in the SHARED container; call this afterwards so later tests (and re-runs) see the
  pristine V3 state. Production-mode boots must also override `"mail.transport" to "disabled"` —
  the dev-default `log` transport is refused in production (`MailTransportTest`) — and
  `"security.encryption.key" to strongEncryptionKey()` — the dev-default data-encryption key is
  burned (`CryptoBootTest`); the checks fire in module order (JWT → mail → crypto → seed
  passwords), so a test asserting a later check must satisfy every earlier one.

**Frontend test internals** (moved from `testing.md`; the binding rules stay there).

**Frontend tests.** Vitest + happy-dom + Testing Library, **co-located** next to the source
(`Foo.test.tsx` beside `Foo.tsx`). `src/test/setup.ts` imports `../i18n` and forces `en`, so text
assertions match the English resources; `src/test/render.tsx` is the shared wrapper and — like
every file-local `MantineProvider` — must pass **`env="test"`** (since Mantine 9.4 the
Popover/Combobox dropdown is `display: none` until Floating UI sees a real bounding box, which
never happens in happy-dom, so Select-option clicks silently fail without it). `src/test/http.ts`
holds the fetch-stubbing helpers. The shared setup also forces the reduced-motion media query and
makes every Mantine test provider honor it: `Transition` invokes its animation hook even with
`env="test"`, so synchronous reduced-motion transitions prevent callbacks from outliving happy-dom
teardown. Other media queries retain their normal behavior; the application theme is unchanged.
`setup.ts` also raises testing-library's `asyncUtilTimeout` to 4 s (and `vite.config.ts` the `testTimeout` to 10 s): the
module registry is cleared per file, so the first test of a shuffled file to touch a lazy chunk pays its cold
import, which exceeds the 1 s `findBy`/`waitFor` default on a contended machine. A lazy read is still always awaited
(`findBy…`/`waitFor`) — a synchronous `getBy…` after the page's own controls appear is the bug, not the timeout. To
reproduce shuffle flakes run several `npx vitest run --sequence.shuffle --sequence.seed=N src/pages src/components`
in parallel (a quiet single run hides them).

**The suite runs with `isolate: false`** (`web/vite.config.ts` — a vitest process reuses its module registry and
globals across files). Vitest already scopes `vi.mock`
registrations per test file; what carries over is the EVALUATED `src/` modules, so `setup.ts` calls
`vi.resetModules()` before every file (npm packages stay cached) and each file's own mocks apply. Tests must not assume anything an earlier test left
behind — await lazy chunks (`findBy…`/`waitFor`, never a synchronous `getBy…` right after the first
data assertion), reset module-level state in `afterEach`, and unstub globals/timers a test installed. The
proof is `cd web && npx vitest run --sequence.shuffle` (run it a few times after adding a test); a test
that only passes in file order is the bug, not the config.

### Notifications (0.6.0)

`NotificationFixtures.kt`: `HttpClient.notices(query)` / `noticesOf(type, adrId)` read the caller's page, `markSeen` / `markAllSeen` drive the writes, `rawNotices(userId)` reads the stored rows (the columns the list hides, `emailed_at` included), `insertNotices(...)` mints a backlog in one `generate_series` statement (the cap and chunk tests), `execRaw(sql)` runs one statement on the shared database, and `RecordingMailer` (records, counts `attempts`, refuses chosen addresses or everything), `mailRecipient(prefix, language)` and `digestOf(mailer, ...)` (the digest over the shared database with the test knobs) serve `NotificationDigestTest` (the content and the rules) and `NotificationDigestRunTest` (Adder's concurrency, release and bound tests ported: a row another process holds locked is skipped not waited for - the test that fails without `SKIP LOCKED`; a run really cancelled before and during a send - the tests that fail without the `NonCancellable` release; the statement budget; the 120-notice flood; the batch, scan-cap and margin bounds), and `newNotificationService()` builds the service for a sweep or history service made by hand (`AdrEventService(db, newNotificationService())`; the one built by `AdrEventService(db)` makes no notice). **The digest tests call `settleNoticeBacklog()` first**: the claim takes the OLDEST waiting people, and the shared database holds every earlier test's unmailed notices, so each digest test settles them (`emailed_at = created_at`; the digest itself settles with the run's `now`) and then asserts only on its own throwaway users through a recording `Mailer`. The suite boots with `notifications.sweepEnabled=false` (no loop mails or purges the shared database); `ExpiryRemindersTest` pins a clock in 2100 so no other test's expiry date is in its window. The 1000-row cap test (`NotificationRoutesTest`) and the `read-state` bucket test each take a moment (a thousand-row insert, 260 requests) and own throwaway users.
