# Audit status

The checkup record: what was audited, what was fixed, what was parked. The first checkup is the **1.0.0 checkup** (2026-11-21, below).

Flow's three checkups (2026-09-30, 2026-10-06, 2026-10-23) audited the donor code this repository was trimmed from; their findings and fixes are Flow's record
(`~/Sources/flow/.claude/docs/audit-status.md`) and were not carried over, because most of what they covered (the data-pipeline and reporting features) is not here.
Adder's 1.0.0 checkup (2026-11-20, `~/Sources/adder/.claude/docs/audit-status.md`) fixed defects in the shared foundation after Hammurabi's tree was cut; the ones not
ported are findings below.

## Method

One `checkup-lead` plans the run and adjudicates it: one `auditor` (and a `security-auditor` for authn/authz) per dimension — docs and specs, DRY/SRP/SOLID, unnecessary
complexity, conventions, tests, UI consistency, authn/authz, guardrails, architecture and infrastructure, API spec, smells and dead code — in parallel, after a `gate-runner`
baseline. Every high/medium claim is re-read against the code before it is kept. Each finding keeps an id, a severity, `path:line` evidence and a proposed fix; the findings become
`BACKLOG.md` items, and the fixing commits are listed here under "Fixed so far".

## 1.0.0 checkup (2026-11-21, master @ `f1b6c20`, tag `v0.9.0` @ `f1b6c20`)

**Run.** 17 sub-agents: two `gate-runner`s (baseline, stability probes), fourteen `auditor`s (docs split three ways, API spec, DRY server, DRY web, conventions, UI,
guardrails, infra, leftovers, Adder-fix parity, server tests, web and e2e tests), one `security-auditor`. Read-only. 182 findings returned, 92 high or medium, 88 re-read
against the code: 1 dropped, 1 partly dropped, 1 raised, about thirty downgraded, duplicates merged into the 55 rows below (1 high, 35 medium, 19 low).

**Not audited.** The Playwright suite (no stack was started, and `e2e.yml` had no run; e2e judged from the source only); no browser was opened; the attack sequences were
read through every hop, not executed, and the heap figures of C-SEC-2 and C-SEC-3 are arithmetic; the security pass did not read `users/UserRoutes.kt` beyond its wiring and
the directory, `teams/`, `telemetry/`, `tags/TagCategoryService.kt` in full, `web/src/api/http.ts` or Ktor and Netty internals; `dependencies.md` version numbers, the 82
Spectral hints and the Polish wording were not checked.

**Gate baseline.** `scripts/gates.sh all` green: server-build 60 s, web-gates 13 s, e2e-static 3 s. Server 134 classes, 890 tests, 0 skipped; Kover line 98.47 % (floor 97),
branch 86.55 % (floor 81); detekt 0. Web 124 files, 922 tests; coverage 98.58 / 96.13 / 98.65 / 99.57 (statements, branches, functions, lines) against 98 / 94 / 98 / 99.
Stability probes: vitest shuffled with three more seeds, server `-Pforks=1`, `3` and `2` again, all green, no hung fork. The push run on `f1b6c20` was green and `v0.9.0`
was tagged on it; `e2e.yml` had 0 runs.

**Verdict per dimension.** Conventions: *yes*. Every other dimension: *mostly* sound. Domain docs match the code both ways (69/69 audit events, 10/10 rate buckets, 12/12
lifecycle edges); code and spec agree 58 to 58; 21 of 21 body reads come after the guard; 18 of 18 migrations pinned and never edited; no IDOR, injection, stored XSS or
guard bypass found, and the Approver-seat rule held against every sequence tried; EN/PL parity complete; 0 skipped tests.

Paths: `S/` = `server/src/main/kotlin/`, `T/` = `server/src/test/kotlin/`, `W/` = `web/src/`, `D/` = `.claude/docs/`, `spec` = `server/src/main/resources/openapi/documentation.yaml`,
`yaml` = `server/src/main/resources/application.yaml`. "Adder `<hash>`" = the fixing commit in `~/Sources/adder`.

### Findings

| id | dimension | sev | verified | evidence | problem | proposed fix |
|---|---|---|---|---|---|---|
| C-SEC-1 | authz | high | yes (read, not executed) | `S/adrs/AdrAccess.kt:83-84`, `S/adrs/AdrService.kt:231-236,286-297`, `S/adrs/AdrLinkWrites.kt:28-38`, `S/adrs/AdrSuccessorService.kt:153-159`, `S/notifications/NotificationPlan.kt:47-71` vs `D/product.md:74` | A decision in force can be ended without its Approver. The Driver alone sets `expiresOn` to today on an ACTIVE ADR; the sweep expires it the next day and no notice is made. The Approver of any ADR in review, or any two users, links it to any ACTIVE ADR and activates it: the predecessor is `SUPERSEDED` in the same transaction, with no role or consent on it and no window for the detach veto. `D/adr-model.md:78` and `D/authorization.md:169` permit both. | Expiry of an ACTIVE ADR by the Approver or ADMIN only, with a notice; a successor activates over an ACTIVE predecessor only once its Approver or ADMIN accepted the link; serve `canEditExpiry` so the SPA stops deriving it (`W/components/AdrDetailsSection.tsx:41`, `W/utils/adrAccess.ts:16`). |
| C-SEC-2 | authz | medium | yes (heap not measured) | `S/adrs/AdrListService.kt:101-103`, `S/infra/paging/Paging.kt:10`, `S/adrs/Adr.kt:20`, `server/build.gradle.kts:87` | `GET /adrs` selects every column, the five Markdown bodies included, for up to 100 rows, with no permit or deadline unless `text` is set: about 20 MB a request at the field maximum against a 256 MB heap that exits on OOM. | Select the list columns only, as `plan()` (`:133`) does; pin it in `AdrListTest`. |
| C-SEC-3 | authz | medium | yes | `S/adrs/AdrEventService.kt:235-239,245-253`, `S/adrs/AdrService.kt:141-145,152,333` | "Ever in review" and "first activation" read every `TRANSITIONED` row and filter in memory; the send back / send to review loop is unbounded and each move carries up to 1000 code points, so every read of that ADR pays for all of it. | One bounded probe or a stored marker; pin in `AdrHistoryTest`. |
| C-SEC-4 | authz | medium | yes (absence) | `S/notifications/NotificationPlan.kt:48-66`, `S/notifications/NotificationService.kt:123-151`, `yaml:246-253`, the eleven `rateLimit(` mounts vs `D/security.md:526` | No ADR-domain write rides a rate bucket and notices have no per-actor bound; any active user can be enrolled and cannot leave. One user re-adding 98 participants every 15 minutes mails each of them 96 times a day through the mailer MFA and reset share. | Adder's per-actor digest budget plus one per-user write bucket. |
| C-SEC-5 | authz | medium | yes (`AuthRoutes.kt` detail by the auditor) | `S/users/UserRoutes.kt:311-321`, `S/plugins/RateLimits.kt:68-104`, `S/auth/MfaChallenges.kt:61-93`, `S/auth/LoginThrottle.kt`; Adder `43e3557`, `4fed5b9`, `8cb7eaf` | Adder's auth hardening is not ported: a wrong `currentPassword` costs a bcrypt verify with no bucket and no lockout; wrong MFA codes are bounded per challenge only, live challenges per account are uncapped, the lockout is flat. | Port the three commits with their tests; spec 429. |
| C-SEC-6 | authz | medium | yes | `k8s/app-service.yaml:12`, `k8s/app-deployment.yaml:88-91`, `k8s/network-policies.yaml:54-62`, `README.md:116`; Adder `b15e01e` | The reference manifests trust forwarded headers behind a bare LoadBalancer, so a client picks its own per-IP key. A documented open gap. | Port Adder's Ingress and its `k8s-static` check. |
| C-SEC-7 | authz | low | yes | `S/auth/AuthRoutes.kt:138-139`, `S/auth/PasswordResetRoutes.kt:68,72,76`, `S/auth/PasswordResetEmail.kt:66` | Unknown login and reset identities are audited as typed; Adder's keyed digest is not ported. | Port `AuditDigest` (Adder `fc29297`, `8cb7eaf`). |
| C-SEC-8 | authz | low | yes, except the `+N` id spelling | `S/infra/mail/Mail.kt:107`, `S/plugins/SecurityHeaders.kt`, `S/plugins/Http.kt:66-75,131-153,170-173,191`, `S/adrs/AdrExportRoutes.kt:125,159`, `k8s/network-policies.yaml:119` | Hardening lows: no production refusal of `SMTP_STARTTLS=false`, no COOP/CORP, CORS allows `http` in production, the early 415 has no security headers, exports carry no `no-store`, egress 443 open with no outbound client, `+N` ids may dodge the small body ceilings (suspected). | One hardening commit (Adder `79c1f89`, `fc29297`). |
| C-SEC-9 | authz | low | no | `S/adrs/AdrExportRoutes.kt:97,140`, `S/adrs/AdrListService.kt:48`, `S/adrs/SuccessorGraph.kt:18-27`, `S/adrs/AdrService.kt:274-286` | One user can hold both export slots with no stream deadline; search permits have no per-user share; the successor chain walk is unbounded under the link lock; an activation may supersede a predecessor it did not lock (suspected). | One slot per user, a deadline, a depth cap, a re-check after the lock. |
| C-SEC-10 | authz | low | yes | `T/AnonymousAccessTest.kt:102-109` | The 401 sweep's floor is 11 with the donor's comment; 51 operations are secured. | Raise to about 46. |
| C-ARCH-1 | infra | medium | yes | `yaml:297`, `S/infra/db/Flyway.kt:49`, `S/infra/db/Database.kt:120`; Adder `50851b9` | Production boots with the committed default database password. | Port `DatabasePassword.kt` and its boot test. |
| C-ARCH-2 | infra | medium | yes | `scripts/`, `k8s/postgres-data-persistentvolumeclaim.yaml:13` | No backup or restore procedure. | Port `db-backup.sh`, `db-restore.sh` and the runbook (Adder `50851b9`). |
| C-ARCH-3 | infra | low | yes | `server/cds/classlist.txt` (`573dc1b`), `T/CdsClassListTest.kt:47-60` | The AppCDS list predates every ADR feature: 0 classes from `adrs`, `notifications`, `lenses`, `tags`. The guard only checks that listed classes still exist. Startup cost only. | Regenerate; assert every main package has a listed class. |
| C-ARCH-4 | infra | low | yes | `server/build.gradle.kts:85-96`, `k8s/app-deployment.yaml:27` | No direct-memory cap, no `preStop`. | Adder `79c1f89`. |
| C-ARCH-5 | infra | low | no | `S/adrs/AdrExportRoutes.kt:97`, `S/adrs/AdrListService.kt:48`, `S/auth/RefreshRotations.kt:20-30`, `S/plugins/RateLimits.kt:37` | The per-process state behind "one replica" is listed incompletely. | One list in `security.md`. |
| C-GATE-1 | gates | medium | yes | `.github/workflows/e2e.yml:8-9`; 0 runs at 2026-11-21T03:48Z | The Playwright suite (23 specs, 64 tests) has no CI run on record and was not run in the checkup. | A green run on the release candidate is a condition of the tag. |
| C-GATE-2 | gates | medium | yes | `server/build.gradle.kts:122,126`, `web/vite.config.ts:92-97` | Floors below the actuals: Kover 97 / 81 against 98.47 / 86.55; vitest branches 94 against 96.13. | 98 / 86 and 96, after the deletions and new tests. |
| C-GATE-3 | gates | medium | yes | run 44833044813 on `ddc1b87`, issue #10, `D/app-releases.md:5-12,30-32` | v0.1.0 to v0.3.0 were tagged while the only master run was red; the release doc has no "green run on the exact commit" step; master is unprotected. v0.4.0 to v0.9.0 were tagged on green runs. | Adder `a11ebf4` text; close issue #10. |
| C-GATE-4 | gates | low | yes | `scripts/gradle-retry.sh:38` | The retry does not recognise "Plugin [...] was not found in any of the following sources", the failure of that red run. | Add the signature. |
| C-GATE-5 | gates | medium | states and settings yes, causes by the auditors | PRs #1 to #14 (13 open, 8 red); `vulnerability-alerts` 404 | Gradle bumps fail by construction (lock state, verification metadata), partial Postgres and Node bumps fail the pin tests; Dependabot alerts are off on a public repository. | A manual round; alerts on (a repository setting: the owner's call). |
| C-GATE-6 | gates | low | cap and boundary yes | `T/SourceFileSizeTest.kt:27,52`, `T/PackageBoundaryTest.kt:36-45`, `web/tsconfig.app.json:22-27`, `scripts/gates.sh` | The 400-line cap covers server main only (`AuthRoutes.kt` 396); no feature-to-feature import rule; no `noUncheckedIndexedAccess`; no lock-drift check locally. | Parked. |
| C-GATE-7 | gates | low | yes | `server/test-weights.tsv` | The donor's pruned weights: 54 of 138 classes missing, 3 rows without a class. | Re-measure. |
| C-TEST-1 | tests | medium | yes | `S/authz/Principal.kt:20,22,24,26`, `T/ConnectionPoolTest.kt:137`; Adder `d12c665` | Adder's foundation tests are not ported (`CallerPrincipalTest`, `ErrorMappingTest`, `RefreshClaimBoundsTest`): the 401 branches of `caller()` never run, `password_reset.throttled` is unasserted, the pool test keeps the 4 s bound. | Port. |
| C-TEST-2 | tests | medium | yes (absence) | `T/MigrationChecksumTest.kt` | No migration runs against a populated database. | Adder `e14c591` shape. |
| C-TEST-3 | tests | medium | yes | `S/adrs/AdrExpirySweep.kt:73,89`, `S/notifications/ExpiryReminders.kt:104,114,125` | The re-check paths that make the sweep and the reminder safe on several replicas never execute. | Two concurrent passes, one event. |
| C-TEST-4 | tests | medium | no | `T/AdrApproverSeatTest.kt`, `T/AdrExportStreamTest.kt:74`, `T/LensTest.kt:217,334` | Unpinned: a seat swap racing the move into review, a Contributor promoted to Approver after review, the export permit after a refusal, an ADMIN never reading a private lens. | Add. |
| C-TEST-5 | tests | medium | partly | `e2e/tests/adr-daci.spec.ts:50,68`, `e2e/tests/adr-send-back.spec.ts:160` | No end-to-end journey for editing participants, a stale-revision conflict, expiry by the sweep, the expiry date, an ADMIN delete. | Participants and the stale conflict first. |
| C-TEST-6 | tests | low | partly | `T/AdrOverviewTest.kt:196-217`, `T/AdrSearchTest.kt:263,296`, `e2e/tests/decision-log.spec.ts:86-88`, `e2e/tests/adr-search.spec.ts:78`, `e2e/scripts/check-scenarios.mjs:13`, `web/vite.config.ts:79` | Assertions on globally capped sections, wall-clock bounds, twelve fixed-delay checks, a 2031 date, the scenario check skipping the axe spec, no machine-readable web coverage, no console-error guard. No probe failed. | Parked. |
| C-UI-1 | UI | medium | yes | `web/index.html:12`; no `document.title` in `W/` | Every route is titled "Hammurabi" (WCAG 2.4.2). | Port `useDocumentTitle` (Adder `27514b0`). |
| C-UI-2 | UI | medium | yes | `W/utils/formatDate.ts:11,20`, `W/pages/Adrs.tsx:298`, `W/components/AdrDetailsSection.tsx:33-35`, `W/components/EventTimeline.tsx:106`, `W/pages/DecisionLog.tsx:71,130` | Every timestamp is rendered in UTC with no label. | The browser zone (Adder's `browserTimeZone()`). |
| C-UI-3 | UI | medium | yes | `e2e/tests/accessibility.spec.ts:26-39,119-131` | The axe sweep visits every route in one state: no discussion entries, notification drawer, filter panel, lens editor, tag modal, transition modal, participants editor, dark detail pages. | Add the states. |
| C-UI-4 | UI | low | yes | `W/pages/headingOutline.test.tsx:26-34` | The heading-outline guard answers only users and teams, so ADR pages are checked in their error shape. | Feed fixtures. |
| C-UI-5 | UI | low | yes | `W/theme.ts:109`, `W/components/AdrParticipantsPanel.tsx:40`, `W/pages/Users.tsx:160`, `W/components/AuthCard.tsx:29` | A colourless Badge takes the brand colour; the dark login gradient uses stock blue. | `color: "gray"`; a brand token. |
| C-UI-6 | UI | low | pagination yes | `W/components/EventTimeline.tsx:63`, `W/components/AdrDiscussion.tsx:85`, `W/components/NotificationsButton.tsx:102,153`, `W/utils/saveError.ts:44`, `W/components/UserMenu.tsx:64-70` | Three bare paginations, four hand-rolled empty lines, a red unread cue, an English-text match on a server detail, a swallowed identity error, one untranslated label. | Parked. |
| C-DOC-1 | docs | medium | yes | `CLAUDE.md:15` vs `W/changelog/version.ts:6` | "0.5.0 (current release)"; the app is 0.9.0. | Rewrite within the byte budget (1,204 left). |
| C-DOC-2 | docs | medium | yes | `README.md:156-225` vs `yaml` | The environment table lacks 9 of 67 variables. | Add the rows. |
| C-DOC-3 | docs | medium | yes | `D/app-releases.md:5-12` | The doc says a bump per user-visible PR; practice is one version per milestone. | With C-GATE-3. |
| C-DOC-4 | docs | medium | yes | `D/build-times.md:9-10,81`, `scripts/timings/budgets.json:2`, `BACKLOG.md:20,29` | "There is no remote yet"; every budget PROVISIONAL; the backlog still asks for the remote. | Re-baseline; drop the item. |
| C-DOC-5 | docs | medium | yes | `D/conventions.md:21`, `D/list-endpoints.md:49`, `S/auth/AuthRoutes.kt:148,233,279,335`, `S/notifications/NotificationPreferenceRoutes.kt:40`, `S/telemetry/TelemetryRoutes.kt:38` | "Deliberate exceptions: none in v0.1.0" while string-routed handlers, two route-built services and the telemetry documents go unrecorded. | Write the list. |
| C-DOC-6 | docs | medium | yes | `D/adr-model.md:57-58` vs `S/adrs/AdrRoutes.kt:262-263,281-282`, `S/adrs/AdrService.kt:325,348-349` | The documented order puts every 400 after the 409s; the shape checks of participants and tags run first. | State it, or move the checks. |
| C-DOC-7 | docs | medium | yes | `D/observability.md:136-141`, `D/authorization.md:215-216`, `D/security.md:247`, `S/plugins/ErrorHandling.kt:189,198` | The per-user bucket list omits `read-state` and `export` in four places. | Name all four. |
| C-DOC-8 | docs | medium | yes | `D/product.md:110-111,116`, `D/persistence.md:23-24,319`, `D/authorization.md:159`, `web/CLAUDE.md:4,268`, `D/web-internals.md:188` | "next:" ports already done; 4 of 15 published services named; the persisted-enum list omits `adr_events.event_type` and the notification columns; an ETag sentence for a removed seam; v0.1.0 tense. | One sweep. |
| C-DOC-9 | docs | low | no | `CLAUDE.md` "Package layout", `D/persistence.md:160,267-269`, `D/adr-model.md:122,235`, `yaml:223-232`, `D/security.md:367,502` | Two infra files without a bullet, partial lists, a misplaced comment, five undocumented config keys. | Same sweep. |
| C-API-1 | API | medium | yes | `spec:2348,2580,2929,2969`; `spec:2007,2508,2591,2912`; `spec:355,1953,1402,1935` | Names that freeze at 1.0.0: seven styles for a person; `supersedes` is an id, an object and a stored boolean filter; `role` and `status` mean different things on two routes. | Unify before the tag (Adder `43b2fb4`). |
| C-API-2 | API | medium | yes | `spec:3001-3017`, `S/lenses/LensRoutes.kt:82`, `S/teams/TeamRoutes.kt:73` | Notification preferences read as `kinds[]` and write as `disabled[]`; the lens and team PUTs are last-write-wins, unstated. | One shape; say so in the spec at least. |
| C-API-3 | API | medium | residue yes, the rest no | `spec:42,643,509`; `spec:2063-2071,2578,2946-2955` | Donor prose in the public spec ("chat features", "the user's catalog files") and three drifted descriptions. | Reword; regenerate `schema.ts`. |
| C-API-4 | API | low | 409s yes | `spec:1292,1456,1501,1620,1660`, `S/adrs/AdrSuccessorService.kt:163,169` | Five path-id operations declare no 400; four conflicts are untyped 409s. | Declare; type them if a client must tell them apart. |
| C-DRY-1 | DRY | medium | yes | `S/adrs/AdrService.kt:219-222,240-250,277-279,300,327-331,351-354`, `S/adrs/AdrLinkWrites.kt:30-33`, `S/adrs/AdrExpirySweep.kt:86-97`, `S/adrs/AdrDiscussionService.kt:87-89` | The ADR write preamble is copied in five writes, the revision bump exists in three forms, the row lock in four. No divergence yet. | One guarded-write block; `bumpRevision` for the sweep. |
| C-DRY-2 | DRY | medium | yes | `S/adrs/AdrExpirySweep.kt:67-75,113`, `S/notifications/ExpiryReminders.kt:98-106`, `S/notifications/NotificationSweep.kt:112`, `S/telemetry/TelemetryConfig.kt:54` | Nine hand-rolled cancellation-safe catches beside `catchingFailures`; the sweep and reminder copies log the class name without the SQLSTATE. The boolean config parser is written three times. | `catchingFailures` everywhere; `requireConfigBoolean`. |
| C-DRY-3 | DRY | medium | yes | `S/auth/LoginThrottle.kt:47,139`, `S/auth/MfaChallenges.kt:58`, `S/teams/TeamRoutes.kt:48`, `S/users/UserRoutes.kt:138-139`, `S/adrs/AdrExportRoutes.kt:192` | Adder's cleanups not ported (`218157a`): a TTL index per throttle, unbounded `name` and `email` filter needles, SHA-256 hex twice, bare `System.currentTimeMillis()`. | Port `ExpiringIndex`, `optionalBoundedString`. |
| C-DRY-4 | DRY | medium | yes (word mappers by the auditor) | `W/utils/adrEvents.ts:12-18,30-31`, `W/utils/notificationText.ts:13-23`, `W/utils/adrStatus.ts:74-75`, `W/pages/Users.tsx:46-47` | The mutate, refresh, toast sequence is copied at eleven ADR sites; status, role and kind words are mapped twice; the two special moves are spelled twice; the debounce is a literal at six sites. | `useAdrWrite`, one word module, `moveOf`, the constant. |
| C-DRY-5 | DRY | low | no | `S/users/UserRoutes.kt:4-7`, `S/adrs/AdrMarkdown.kt:81-89`, `S/notifications/NotificationEmail.kt:43-52`, `S/adrs/AdrExportRoutes.kt:136-196`, `W/components/AdrParticipantsEditor.tsx:26` | A `users` and `auth` import cycle, status words in two files, ZIP streaming in a routes file, a non-reactive `isAdmin()`, repeated e2e helpers. | Parked. |
| C-SMELL-1 | dead code | medium | yes | `S/infra/db/MultiRowInsert.kt:122`, `S/infra/db/Jsonb.kt`, `S/infra/json/CanonicalJson.kt:40-53`, `S/infra/db/Fts.kt:93`, `S/infra/paging/QueryParams.kt:51`, `S/authz/Guards.kt:22`, `S/teams/TeamService.kt:131`, `S/infra/crypto/`, `W/hooks/useRovingGrid.ts`, `W/utils/isoDate.ts:25,54,66,83,92`, `W/api/session.ts:101`, `W/components/SearchPicker.tsx:29` | About 1,000 lines only their own tests call, counted in both coverage figures: `insertRows`, the `jsonb` column, `canonicalJson`, `ftsMatchSql`, `repeatedLongs`, the team-manager guard, the field cipher; in the SPA a hook, five date functions, `hasFeature`, three picker props. | Delete with the tests; keep the field cipher (its production boot check is live) and record it. |
| C-SMELL-2 | dead code | low | sampled | `S/infra/paging/PageResponse.kt:7`, `S/infra/time/Millis.kt:6`, `W/api/http.ts:256,273-274`, `W/utils/formatDate.ts:5-9`, `W/components/TeamEditorModal.tsx:24-25`, `.gitignore:47-48`, `web/package.json:5` | Donor comments citing files that do not exist here, 35 "WHY n" citations into Flow's file, stale ignore entries, package versions 0.0.0 and 1.0.0. | Parked. |
| C-CONV-1 | conventions | low | yes | `S/notifications/NotificationPreferenceRoutes.kt:40`, `S/adrs/AdrDiscussionService.kt:191`, `S/adrs/AdrListQuery.kt:94`, `S/users/UserRoutes.kt:49` | A route module builds its own service; two literal soft-delete predicates (commented); a validator in a routes file. | Publish the service; record the rest. |

### Remediation tiers

- **Before tagging 1.0.0:** C-SEC-1, C-SEC-2, C-SEC-3, C-SEC-5, C-SEC-6, C-ARCH-1; C-API-1, C-API-2, C-API-3; C-UI-1, C-UI-2; C-SMELL-1, then C-GATE-2; C-TEST-1;
  C-DOC-1 to 8 with C-GATE-3; one green e2e run on the candidate commit (C-GATE-1). Riding along: C-SEC-7, C-SEC-10, C-GATE-4, C-UI-5, C-DOC-9.
- **Can follow:** C-SEC-4, C-SEC-8, C-SEC-9; C-ARCH-2, 3, 4; C-GATE-5, C-GATE-7; C-TEST-2 to 5; C-UI-3, C-UI-4; C-DRY-1 to 4; C-API-4; C-CONV-1.
- **Parked (`BACKLOG.md`):** C-GATE-6, C-TEST-6, C-UI-6, C-DRY-5, C-SMELL-2, C-ARCH-5.

**Decisions** (taken by the main session on 2026-11-21 while the owner was away, each recorded in `product.md` "Assumptions" and changeable on request): a decision in
force ends without its Approver only by ADMIN — the expiry date of an ACTIVE ADR is the Approver's or ADMIN's, and a successor supersedes an ACTIVE predecessor only after that
predecessor's Approver or ADMIN accepted the link (C-SEC-1); the API names are unified before the freeze (C-API-1, C-API-2); timestamps render in the browser's zone (C-UI-2);
the notice-volume bound follows after 1.0.0 (C-SEC-4); the team-manager guard is deleted and the field cipher kept (C-SMELL-1). Open for the owner: Dependabot alerts and branch
protection on `master` (repository settings), and closing issue #10 (C-GATE-3, C-GATE-5).

## Fixed so far

| Finding | Fixed in | What |
|---|---|---|
| C-SMELL-1 (the SPA half: `useRovingGrid`, five date functions, `hasFeature`, three picker props) | `0ec0d2f` (batch C) | Deleted with their tests. |
| C-UI-1 | `0ec0d2f` | `useDocumentTitle` on every route (Adder `27514b0`), pinned per route. |
| C-UI-2 | `0ec0d2f` | `browserTimeZone()` for every ADR timestamp; date-only values untouched. |
| C-UI-5 | `0ec0d2f` | A colourless Badge is gray; the dark login gradient uses the brand. |
| C-SEC-2 | `0fa1782` (batch A) | The list reads headline columns only; pinned in `AdrListTest`. |
| C-SEC-3 | `0fa1782` | Bounded history probes. |
| C-SEC-10 | `0fa1782` | The 401 sweep's floor raised. |
| C-SMELL-1 (the server half: `insertRows`, the `jsonb` column, `canonicalJson`, `ftsMatchSql`, `repeatedLongs`, the team-manager guard) | `0fa1782` | Deleted with their tests; the field cipher is kept and recorded. |
| C-SEC-5, C-SEC-6, C-SEC-7, C-ARCH-1, C-TEST-1, C-GATE-4 | `029da12` (batch B) | Adder's auth hardening, the Ingress and its `k8s-static` check, `AuditDigest`, the default database password refused in production, the foundation tests, the retry signature. |
| C-SEC-1 | `b923e35` | The Approver's say over a decision in force (expiry of an `ACTIVE` ADR, the successor acceptance, V19). Review fixes in this commit: the rollback runbook (V19 and V20, `persistence.md`, `product.md`), the "not in force yet" line, the successor-not-accepted title, the source-pin regex, V20's both-spellings rule and its test. |
| C-API-1, C-API-2 | `1f891d9` | One naming rule before the freeze; V20 migrates the stored lens filters. |
| C-GATE-2 | this commit (batch D) | Kover floors 98 / 87 (measured 98.85 / 87.31); the vitest branch floor was raised to 96 in `0ec0d2f`. |
| C-GATE-3, C-DOC-3 | this commit | `app-releases.md`: one version per milestone, a patch for a later fix, CI green on the exact commit before a tag (Adder `a11ebf4`), the three exceptions recorded. |
| C-DOC-1 | this commit | `CLAUDE.md` roadmap. |
| C-DOC-2 | this commit | The README environment table covers all 69 variables of `application.yaml` (checked mechanically). |
| C-DOC-4 | this commit | `budgets.json` and `build-times.md` re-baselined from the measured CI and local series; "no remote yet" and the backlog item gone. |
| C-DOC-5 | this commit | `conventions.md` "Deliberate exceptions" written. |
| C-DOC-6 | this commit | `adr-model.md` "Order inside a write" states where the shape checks (400) run. |
| C-DOC-7 | this commit | Every per-user bucket named (spec `TooManyRequests`, `RateLimits.kt`, `CLAUDE.md`). |
| C-DOC-8, C-DOC-9 | this commit | The stale sweeps (donor "next:" ports, the service list, the persisted-enum list, the deleted seams, v0.1.0 tense, the package layout, V1-V20, the `application.yaml` comment order). |
| C-API-3 | this commit | Donor prose out of the spec; `schema.ts` regenerated. |

**CI.** The `e2e` workflow ran green on `029da12` (run 44865747177, after one transient Maven Central miss on the first attempt). C-GATE-1 stays a condition of the tag: the same green run is needed on the 1.0.0 release commit.

**Not fixed in this round.** C-ARCH-3 (the AppCDS list): `scripts/cds-classlist.mjs` trains the image on a workload that never calls the ADR, notification, lens or tag routes, so a regenerated list would still hold no class of them; it needs the workload extended first, and is in the tier-2 backlog item. Everything else of the tiers is in `BACKLOG.md`.
