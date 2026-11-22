# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Product

Hammurabi creates and manages **Architecture Decision Records** (ADRs) across their full lifecycle: a problem is raised, a solution is drafted,
reviewed and decided; while the decision is in force people report how it works; eventually it expires or a successor replaces it. Status runs
`PROBLEM_RAISED` → `SOLUTION_DRAFT` → `IN_REVIEW` → `ACTIVE`, with `CANCELLED`, `WITHDRAWN`, `EXPIRED` and `SUPERSEDED` as the other endings.
Responsibility follows **DACI**: one Driver, one Approver, any number of Contributors and Informed. Every change is an event in the ADR's history;
ADRs are tagged, searchable by several criteria, discussed before they are active (comments/questions) and challenged after (issues/escalations).
My work (`/my-work`) is the signed-in user's personal ADR workspace.

**Roadmap:** 0.1.0 = the foundation, Flow trimmed to its generic core: auth + email MFA, password reset, users, feature flags, teams,
EN/PL, light/dark, changelog, the ADMIN telemetry panel, the lapis brand and the "Stele" logo. 0.2.0 = the ADR core
(ids, lifecycle, DACI, history, expiry), 0.3.0 = discussion, 0.4.0 = tags and successors, 0.5.0 = find, 0.6.0 = notify, 0.7.0 = send back and withdraw, 0.8.0 = Markdown export, 0.9.0 = Decision log, 1.0.0 = checkup, 1.1.0 = visual editor and Lifecycle tab, 1.2.x = safety net. **1.3.0 (current release) = My work**, with a personal ADR queue, activity and DACI role filtering.
Per-version scope, the user's decisions and the assumptions: `.claude/docs/product.md`; the domain model: `.claude/docs/adr-model.md`. Open items: `BACKLOG.md`.

Brand: lapis lazuli indigo (the `hammurabi` colour tuple in `web/src/theme.ts`, `primaryShade: { light: 7, dark: 8 }`, dark accent ink shade 3); the logo
"Stele" is the basalt stele of the Code of Hammurabi on a lapis disc — the sun disc of Shamash above, rows of cuneiform wedges below (`web/public/logo-*.svg`).

## Donors

**Flow is the primary donor**: this repository started as an exact copy of Flow (`~/Sources/flow` @ `4e43e480`, Flow 1.57.1), trimmed in the shape
Astrolabe's bootstrap set (`~/Sources/astrolabe`, 2026-10-31); Covenant, Lettuce and Toadie came in indirectly through Flow. **Port, don't reinvent**:
when Hammurabi needs a capability a sibling already has, port its implementation. What each donated and what to port next: `.claude/docs/product.md` "Donors".

## Commands

Gradle wrapper is at `./gradlew` (use `gradlew.bat` on Windows). JDK 21 toolchain is required
(auto-provisioned via foojay-resolver; the local dev JDK is pinned in `mise.toml` —
`temurin-21.0.13+9.0.LTS`, Node 24.22.1). Run `mise install` once, then `mise exec -- <command>`
or activate mise in your shell. If `./gradlew` resolves the wrong JDK, point it explicitly: `JAVA_HOME=$(mise where java) ./gradlew build`.

- All local gates, timed like CI: `scripts/gates.sh [server|web|e2e|all]` (stops at the first failure; `.claude/docs/build-times.md`)
- Build everything: `./gradlew build`; run the server (Ktor + Netty, port 8090): `./gradlew :server:run`; all tests: `./gradlew test`
- Run server tests only: `./gradlew :server:test` (Docker for Testcontainers; OrbStack: testing.md)
- Run a single test: `./gradlew :server:test --tests "ch.nokillswit.ServerTest.security headers are set on responses"`
- Static analysis (detekt, both Kotlin modules): `./gradlew detekt` — rides `check`, zero findings, no baseline file; rule
  tuning lives in `config/detekt/detekt.yml` ONLY; never add an uncommented `@Suppress`.
- Dependency alignment: `./gradlew :server:checkDependencyAlignment` — one version per aligned family
  (Netty, OpenTelemetry, kotlin stdlib/reflect, Jackson 2, Reactor) on the runtime classpath; rides `check`.
- Package the server: `./gradlew :server:installDist`. **Never use `:server:buildFatJar`** — the fat JAR breaks Flyway's
  `ServiceLoader` discovery.
- JVM memory flags are pre-tuned in `server/build.gradle.kts` (`applicationDefaultJvmArgs`; rationale in place).
- AppCDS class list (`server/cds/classlist.txt`, dumped into the Docker image): `node scripts/cds-classlist.mjs` regenerates it from a real run
  (in a git worktree pass `--image <tag>`); `CdsClassListTest` and CI's `scripts/check-cds-archive.sh` guard drift.
- Backup and restore: `scripts/db-backup.sh`, `scripts/db-restore.sh`, the drill `node scripts/db-drill.mjs` (`.claude/docs/persistence.md` "Backup and restore").
- **Run the whole stack with one command: `docker compose up --build`** (only Docker required). See "Running the full stack" below.
- Frontend: `cd web && npm install --legacy-peer-deps`, then
  `npm run dev|build|lint|test|test:coverage|knip|gen:api` (details in `web/CLAUDE.md`). The
  API-contract gate is `npm run lint:api` (Spectral lint of the OpenAPI spec + the conformant
  fixture against `api-guidelines/`) and `npm run check:api` (in-memory spec → `schema.ts` diff).
- E2E: start a throwaway stack and name it (`E2E_BASE_URL`, `E2E_MAILPIT_URL`; no default, the suite refuses without them), then
  `cd e2e && npm ci && npx playwright install chromium && npm test` (plus `lint`, `knip`, `typecheck`, `check:scenarios`).
- CI: `.github/workflows/ci.yml` re-runs every gate above on push/PR; the blackbox Playwright suite (`e2e.yml`) runs
  nightly and on demand; Dependabot checks every workspace weekly. The job list, the Markdown-only skip, Trivy and
  grouping: `.claude/docs/ci.md` (dependency rules: `dependencies.md`, `dependency-reproducibility.md`).

## Running the full stack

`docker compose up --build` serves everything at `http://localhost:8090` (sign in as `admin@hammurabi.local`; the README
has the initial password). Local dev: `docker compose up postgres mailpit` (Postgres on host port
**5440**, Mailpit UI **8031**) + `./gradlew :server:run` (API on **8090**) + `cd web && npm run dev` (Vite on **5181**,
proxying `/api`), each in its own terminal; the ports avoid the sibling repos' and bind to 127.0.0.1 only. The sibling port table, Mailpit and the
Kubernetes (OrbStack) `hammurabi` namespace: `.claude/docs/ci.md` "Running the full stack".

## Architecture

Hammurabi is Flow's generic foundation under the ADR domain (`adrs/`: the core of 0.2.0 and the discussion of 0.3.0). The foundation: **JWT auth**
with a sliding refresh pair and a server-side revocation blocklist, opt-in **email MFA**, self-service password reset,
per-account lockout and per-IP rate limits, **ADMIN-managed users** (`/api/v1/users`, `UserRole`) with per-user **feature flags**
and a synced UI/email **language** (EN/PL), flat **teams** with an optional manager, the ADMIN
**telemetry** panel, and the React shell (nav model, palette, theme, changelog). Every feature follows the feature template below.

Multi-module Gradle build (Kotlin DSL) defined in `settings.gradle.kts` with two Kotlin modules
plus two standalone npm workspaces (Gradle never touches them):

- **`core`** — Kotlin Multiplatform (JVM target only currently). Shared code consumed by `server`.
  Holds the OpenTelemetry SDK bootstrap (`getOpenTelemetry(serviceName)`).
- **`server`** — Kotlin/JVM. The Ktor application. Depends on `core`. One process: there is no worker and no role switch.
- **`web/`** — Vite + React + TypeScript SPA that consumes the server's HTTP API.
- **`e2e/`** — Playwright blackbox suite against a named stack.

Group is `ch.nokillswit`, version `1.0.0-SNAPSHOT` (inherited from the donor scaffold; renaming is out of scope). Dependency
versions are centralized in `gradle/libs.versions.toml` (every pin carries its rationale; Ktor comes from the separate `ktorLibs`
catalog). Resolved Gradle dependencies use strict locking and SHA-256 verification; intentional updates follow
`.claude/docs/dependency-reproducibility.md`.

### Server bootstrap model

`server/src/main/kotlin/main.kt` just delegates to `io.ktor.server.netty.EngineMain`. The
application is wired declaratively in `server/src/main/resources/application.yaml` under
`ktor.application.modules` — each entry is a fully-qualified extension function on `Application`
(e.g. `ch.nokillswit.plugins.HttpKt.configureHttp`). **Module order is load-bearing**: base plugins
(Http → SecurityHeaders → Monitoring → Serialization → Security → RateLimits → ErrorHandling → OpenTelemetry → AutoHeadResponse → Resources) →
infra (Mail → Crypto → Flyway → Database → Bootstrap → Health) → Telemetry (the sampler) → feature route modules (the login throttle `configureLoginThrottle` first, then users, teams, tags, adrs + its expiry sweep, lenses, notifications + its sweep, auth, password
reset, telemetry reads) → `RoutingKt.configureRouting` strictly last (the SPA catch-all). `configureDatabase` publishes the services into
`Application.attributes` via `AttributeKey`s. To add a cross-cutting concern, create a `configureXxx()` extension under `plugins/` and register it
in `application.yaml`; do not call it from `main.kt`. There is no DI framework — services travel via `attributes`.

### Package layout

Source files sit flat under `server/src/main/kotlin/<area>/` but declare `package ch.nokillswit.<area>`
(no `ch/nokillswit` directory nesting — a deliberate idiom, the `InvalidPackageDeclaration` detekt
override). One line per package; `ls` gives the files. **Layering is one-way (`PackageBoundaryTest`):** `infra/` imports no feature package
except the composition root (`infra/db/Database.kt`, `Bootstrap.kt`); `telemetry/` is imported by nothing.

- `plugins/` — cross-cutting Ktor wiring (`configureXxx` that only `install` plugins; Security = JWT, ErrorHandling = RFC 7807). `Routing` (the SPA catch-all) is registered strictly last; `Health` (`/api/v1/health`, `/api/v1/ready`) after Database; `RateLimits` holds every per-IP bucket (and the per-user `telemetry`, `directory`, `read-state`, `export`, `password-change` and `adr-write` ones), keyed by `infra/ClientAddress.kt`'s `clientAddressKey()`.
- `infra/mail/` — outbound email (Lettuce's, ported): `MAIL_TRANSPORT` log/smtp/disabled; the `log` transport is refused in production (fail-closed); a null mailer = email features answer 503.
- `infra/crypto/` — `PasswordHashing.kt` (bcrypt) and encryption at rest (Lettuce's, ported): `FieldCipher` (AES-256-GCM `enc:v1:` envelopes, current + rotation key), `configureCrypto` (`DATA_ENCRYPTION_KEY`; a burned key fails startup closed). No cipher consumer yet; Flow's `EncryptedAtRest` registry and `Reencrypt` come back with the first encrypted column.
- `infra/db/` — Flyway bootstrap, the R2DBC composition root, the seed bootstrap (admin rotation, prod fail-closed), `EventLog.kt` + `EventChanges.kt` (the append-only per-record history, Adder's, with a nullable system actor; the field-diff vocabulary), `SoftDelete.kt` (`SoftDeletable`: ONE `active()` predicate, `nowMillis()`, `lockActiveForWrite`), `Sql.kt` (`containsNormalized` — the only substring filter), `MultiRowInsert.kt` (`insertRowsIgnoringConflicts`), `DatabasePassword.kt` (production refuses the default database password), `StatementTimeoutCache.kt` (only `queryTimeout` may set `STATEMENT_TIMEOUT`), `Fts.kt` + `SearchBudget.kt` (the full-text kernel and the one search deadline, Adder's), `LeakedSocketGuard.kt`, `DetachingConnectionFactory.kt` (pool hygiene, `persistence.md`).
- `infra/paging/` — the shared list-endpoint machinery (Lettuce's, ported verbatim; `list-endpoints.md`).
- `infra/validation/` — cross-feature input helpers (`sanitizeSingleLine`: trim + control/hidden-direction-char 400; `sanitizeMultiLine`: free text, `\n`/`\t` kept; `requireNoDuplicates`).
- `infra/json/` — `Sha256.kt` (`sha256Hex`) and `JsonParams.kt` (the string-map event `params` as JSON text).
- `infra/revision/` — `X-Expected-Revision` for whole-document PUTs (Toadie's, header REQUIRED): `expectedRevision()` (400), `requireRevision` (typed 409 `urn:hammurabi:problem:revision-conflict`); the ADR document PUT and the transitions use it.
- `infra/BackgroundScope.kt` — `Application.backgroundScope(name)`: the ONE way to own a background loop (cancel + bounded join on `ApplicationStopping`, before the pool is disposed; a source guard keeps hand-built scopes out).
- `infra/time/` — `Millis.kt`, `MILLIS_PER_DAY`, the one fixed-24h-day constant.
- `infra/config/` — `requireConfigInt`/`requireConfigLong`, boot-validated numeric config (Lettuce's).
- `infra/ProblemTypes.kt` — the ONE registry of the typed `urn:hammurabi:problem:*` URIs (`ProblemTypesTest` diffs it against the spec and the SPA).
- `infra/ExpiringIndex.kt` — the TTL-bounded in-memory index behind the throttles. `infra/ClientAddress.kt` — the ONE per-IP key (IPv4 whole, IPv6 by /64, junk counts under the TCP peer). `infra/Failures.kt` — `catchingFailures` (keeps the failure without swallowing cancellation).
- `audit/` — the security audit trail: `audit(event, fields…)` → AUDIT-marked structured logs.
- `authz/` — `CallerPrincipal`, the guards (`requireAdmin`, `requireSelfOrAdmin`) and the typed HTTP exceptions (401/403/404/409/429).
- `auth/` — login (+ the email-MFA second step), refresh, logout, self-service password reset, the self password change (`PasswordChangeRoutes.kt`; `users/` never imports `auth/`), token minting, the revoked-token blocklist, `AccountLockout.kt` (the progressive lockout, `configureLoginThrottle`) and `AuditDigest.kt` (unknown identities audited as a keyed digest); in-memory auth state is capacity-bounded with audited 429 saturation.
- `users/` — Hammurabi's login accounts (REST `/api/v1/users`): ADMIN-only CRUD (self-delete 403, last-admin 409) plus the any-signed-in-user picker lookup `GET /users/directory` (`{id, name, email}`, own rate bucket), per-user feature flags (`Feature` enum, the V5 disabled-set model; MFA is the inverted-default login-scoped flag), the ONE synced per-user UI+email language (`Languages.kt` = `SUPPORTED_LANGUAGES`).
- `tags/` — the ADMIN-curated tag registry (0.4.0, V13, REST `/api/v1/tag-categories`; `adr-model.md` "Tags"): categories of tags, retire-not-erase, writes in `lockingTransaction` (`infra/db/Locking.kt`).
- `lenses/` — saved ADR-list filters (0.5.0, V16, REST `/api/v1/lenses`; `adr-model.md` "Lenses"): Toadie's lenses, the payload is the list's own filter document.
- `notifications/` — the bell, per-user switches, email digest, expiry reminders and retention (0.6.0, V17–V18, V21, REST `/api/v1/notifications`; `adr-model.md` "Notifications"): Adder's, every notice derives from an `adr_events` row in its own transaction (`AdrNotices`).
- `teams/` — flat teams (V6) with an optional manager: GET any authenticated, POST/PUT/DELETE ADMIN only. **The feature template** (below).
- `adrs/` — the ADR core (0.2.0, REST `/api/v1/adrs`; `adr-model.md`): the record + DACI participants (V10) and history (V11), the lifecycle map (`AdrLifecycle`, typed 409s), the role verdicts (`AdrAccess`), numbering under an advisory lock, the `X-Expected-Revision` document PUT, the participants diff, the events read, and the expiry sweep (`AdrExpirySweep`, `adrs.expiry.*`). 0.3.0 adds the discussion (V12 `adr_discussion`, `AdrDiscussionService`/`Routes`/`Access`, `openIssueCount` on the ADR rows); 0.4.0 adds `adr_tags` (V13, `AdrTagService`) and the successor link (V14, `SuccessorGraph`, `AdrSuccessorService`); 0.5.0 the search vector (V15, `AdrListService`, `AdrListFilter`); 0.8.0 the Markdown export (`AdrMarkdown`, `AdrExportService`, `AdrExportRoutes`: one file, or a ZIP over the list's filters); 0.9.0 the decision log (`AdrOverviewService`, `GET /adrs/overview`).
- `telemetry/` — per-process JVM/cgroup/request/storage samples in `ops.*` (V9), 90 days, hourly, ADMIN read API (`observability.md`).

**Feature template — copy `teams/`** (a small ADMIN-curated registry; the only shape the foundation ships). Before adding a feature, route,
service, migration or page, read `.claude/docs/conventions.md` in full.

### The OpenAPI contract

`server/src/main/resources/openapi/documentation.yaml` is hand-maintained and authoritative: every endpoint change edits it in
the same commit. The server suite validates every test-client `/api/` interaction against it (`OpenApiConformance.kt`,
`-Dopenapi.conformance=fail`); the frontend derives its types from it (`npm run gen:api` → committed `web/src/api/schema.ts`,
regenerated in the same commit). The file says `openapi: 3.1.0` but must use only 3.0-compatible constructs
(`OpenApiSpecTest`). `AnonymousAccessTest` sweeps every operation without `security: []` for a 401 — the "401 sweep" — so a new
route is covered the moment its spec entry lands. `info.version` equals `APP_VERSION` (`app-releases.md`).

### Cross-cutting conventions

Two docs apply to nearly every change and are always loaded:

@.claude/docs/testing.md
@.claude/docs/list-endpoints.md

Every other convention doc is **read on demand, not imported** (together they exceed the instruction-size budget) but binding:
**before touching an area, read its doc in full** (and name it in any implementer/reviewer brief). When a doc and this file
disagree, the doc wins. The always-loaded set (this file plus the two imports) stays under 36,000 bytes (`AlwaysLoadedDocsBudgetTest`).

| Doc | Read before you touch |
|---|---|
| `.claude/docs/conventions.md` | adding a feature, route, service, migration or page: the `teams/` feature template (DTO + validator, routes, service, migration + checksum pin, spec paths, `gen:api`, lazy page + nav entry, e2e spec + scenario) |
| `.claude/docs/persistence.md` | any migration (`db/migration/V*.sql`, V1–V22 today — applied bytes are immutable, `MigrationChecksumTest`), an Exposed table/`*Service.kt`, soft delete, a cross-feature table read/write (the list there IS the permission), the connection pool, the `ops` schema |
| `.claude/docs/security.md` | auth/JWT/MFA/password reset, rate limits, headers/CSP, secrets/encryption at rest, production fail-closed startup checks, payload validation |
| `.claude/docs/authorization.md` | any route: its guard (ADMIN vs any authenticated), 403-before-400/404 ordering, existence disclosure, the error/`ProblemDetail` mapping |
| `.claude/docs/observability.md` | any mutation or denial path (it needs an `audit(...)` event, listed there), logging, OpenTelemetry, health probes, the telemetry panel's server side |
| `.claude/docs/ci.md` | a CI job (ids and list), Dependabot rule or static-analysis config; the full-stack/Kubernetes notes and the sibling port table |
| `.claude/docs/product.md` | the per-version roadmap, the user's decisions, assumptions; what each donor repo contributed and what to port next |
| `.claude/docs/adr-model.md` | the ADR domain (`adrs/`, ADR pages): the record, lifecycle map, DACI roles and verdicts, history, expiry, discussion, tags, successors, search |
| `.claude/docs/test-fixtures.md` | any server test fixture, the harness, fork warm-up, OpenAPI conformance internals, e2e scenario files, Schemathesis |
| `.claude/docs/web-internals.md` | the `web/` transport/session layer, user/feature-flag pages, language switcher, colour tokens/logo, changelog wiring |
| `.claude/docs/dependencies.md` | any dependency, image or runtime-pin change |
| `.claude/docs/dependency-reproducibility.md` | lockfiles or `gradle/verification-metadata.xml` (the empty-`GRADLE_USER_HOME` rule) |
| `.claude/docs/app-releases.md` | a version bump, changelog entry, tag or GitHub release |
| `.claude/docs/build-times.md` | any CI/build/test-time change; when a gate gets slower (`scripts/timings/`, budgets — PROVISIONAL until re-baselined) |
| `.claude/docs/audit-status.md` | the checkup record (audited, fixed, parked); start the 1.0.0 checkup here |

A new doc under `.claude/docs/` gets a row here, not an `@` import.

### Frontend (`web/`)

See `web/CLAUDE.md` for the frontend conventions (flat directories, co-located tests, typed i18n
with EN/PL parity, the transport layer, theming — the lapis `hammurabi` brand is the interactive accent
only; red = blocking, orange = warning, gray = neutral, cyan = in progress, grape = waiting, teal = success).
