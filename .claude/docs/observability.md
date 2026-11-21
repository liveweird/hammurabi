### Observability

`plugins/OpenTelemetry.kt` installs `KtorServerTelemetry` and obtains the SDK via
`getOpenTelemetry("hammurabi")` from the `core` module. `plugins/Monitoring.kt` separately installs
Dropwizard metrics (dumped to the `metrics` logger by a `Slf4jReporter` every `monitoring.reporterIntervalSeconds`, env
`METRICS_REPORT_INTERVAL_SECONDS`: default **300**, `0` = reporter off with the meters still collected, otherwise 30..3600, anything
else fails startup via `requireConfigInt`; it was a fixed 10 s in Flow, a main source of idle allocation and
log volume, since each dump formats the whole registry into dozens of lines the console logs exporter re-emits; nothing in the repo —
docs, e2e, tests — reads those lines, they are a look-back aid; pinned by `MonitoringReporterConfigTest`; the registry is a `TelemetryRegistry` — timers on a true sliding window of `telemetry.sampleIntervalSeconds`, capped at 300 s (the reservoir keeps ~16 bytes per timer update until it ages out of the window, so the cap bounds the memory cost per request) — published as `MetricRegistryKey`, and Ktor's JVM metric sets are OFF (`registerJvmMetricSets = false`: its thread-states gauge dumped every thread on each reporter run; the telemetry sampler reads the MXBeans itself), see "Telemetry") and the `CallId` plugin using `X-Request-Id`
(client-supplied ids are honored only when ≤64 URL-safe chars — they reach logs and OTel
attributes, so junk is ignored rather than propagated). The SDK is also wired for **logs**: a
Logback `OpenTelemetryAppender` (`server/src/main/resources/logback.xml`, the sole root appender
— re-adding STDOUT would double every line, since the interim `console` logs exporter already
prints to System.out) bridges every SLF4J log into the OTel logs SDK, and
`configureOpenTelemetry` (`plugins/OpenTelemetry.kt`) installs the appender (flushing the
pre-install boot-log buffer) while `getOpenTelemetry` (`core/.../OpenTelemetry.kt`) sets the
interim exporter to `console`. Defaults are set via `addPropertiesSupplier` (the lowest-precedence
config tier) **on purpose**, so the sink can be redirected to a collector by env alone with no
code change — set `OTEL_LOGS_EXPORTER=otlp` + `OTEL_EXPORTER_OTLP_ENDPOINT` (the
`opentelemetry-exporter-otlp` dep is already on the classpath); `OTEL_TRACES_EXPORTER` likewise.
Metrics and traces exporters default to `none` (`otel.metrics.exporter`/`otel.traces.exporter`).
**Shutdown ordering (the SDK closes LAST).** JVM shutdown hooks run concurrently and unordered. The
OpenTelemetry autoconfigure SDK registers its own hook (flush + close the logs pipeline) and Ktor's
`start(wait = true)` registers another (it fires `ApplicationStopping`, where a component may audit a final event); with both live, the SDK often closed first and every audit line
emitted during Ktor's stop was silently dropped (the DB release still happened — only the log line
vanished). So `getOpenTelemetry` (`core`) calls `disableShutdownHook()` and `configureOpenTelemetry`
registers `TelemetryShutdown`'s hook instead: it waits (bounded, 20 s) for `ApplicationStopped` — the end of
Ktor's stop — and only then closes the SDK. Ktor raises an event's handlers in subscription order, so the
latch handler is subscribed from inside the `ApplicationStarted` handler (after every module has subscribed its
own `ApplicationStopped` work — the pool dispose) and therefore runs last. The 20 s sits
in a budget: Ktor grace + timeout ≈ 11 s by default, and 20 s + the SDK's ≤10 s `close()`
is exactly the Kubernetes default `terminationGracePeriodSeconds` (30 s), so `k8s/app-deployment.yaml` sets 45 s
(`JvmMemoryFlagsTest` asserts it is at least `STOP_WAIT_SECONDS` + 10 s + 15 s) — raising Ktor's shutdown timeout or
lowering the pod's grace must revisit `STOP_WAIT_SECONDS`; if the application never finished starting (a boot failure)
there is no Ktor stop to wait for and it flushes at once, so the failure's own lines still export. Anything
that must log during a stop belongs in an `ApplicationStopping`/`ApplicationStopped` handler, never in its
own JVM hook (it would race the SDK again). Pinned by `ShutdownAuditTest`: a forked child JVM (a real Netty
server + the real `configureOpenTelemetry`) gets `kill -TERM` and its stdout must hold the line its
`ApplicationStopping` handler audited (not `Process.destroy()`, which closes the child's pipes first).

**Convention for non-fatal "this should never happen" events:** emit a WARN with the
`SHOULD_NEVER_HAPPEN` marker (`MarkerFactory.getMarker`) plus key/value attributes — they flow
through the appender to OTel. Reserve it for branches that are genuinely unreachable by ordinary
requests (no current usage — Lettuce's history shows how easily an "unreachable" branch turns out
to be a routine denial path).

**The client-address fallback (WARN log, no audit event; `security.md` "The client address is NUMERIC").** Logger `ch.nokillswit.infra.ClientAddress`: WARN `the trusted X-Forwarded-For hop is not an IP address (N call(s) in this window): ...` at most once per ten minutes per process, N the calls folded since the last line, no client text (it is attacker-chosen).

**The 500 line (`plugins/ErrorHandling.kt`, `plugins/ScrubbedFailure.kt`).** The catch-all logs ERROR `Unhandled exception while processing <METHOD> <path>` (no query string) with the failure as its throwable. When the cause chain holds a database exception (`R2dbcException`, which Exposed's `ExposedR2dbcException` extends, or `java.sql.SQLException`), OR any link has suppressed exceptions, OR the chain is deeper than the walk's 32-link bound (fail closed: a database exception could hide in either), the throwable is replaced by `ScrubbedFailure`s mirroring the chain: each link is `<exception class name> [SQLSTATE <state>]` with the original stack frames and NO message and no suppressed exceptions, because a driver or Exposed message can carry bound values (Exposed expands the statement's arguments into the message when the transaction's `debug` flag is on; Postgres echoes values into some messages), and a message body, a title or a search term is such a value. Any other failure is logged unchanged. Two one-line sites that render a failure without a stack use `scrubbedSummary()` (the links joined by ` <- `): the telemetry collector WARN/DEBUG and the readiness probe's DEBUG. `LogHygieneTest` pins it (the echo test proves the leak is closed; the 57014 test only pins that the line still names the driver class and the SQLSTATE); `ScrubbedFailureTest` pins the rendering. **No logger below INFO prints a failure's message:** DEBUG statement logging of the driver stays off, and `logback.xml` pins the two other loggers that would print a cause below INFO, `Exposed` (its DEBUG `Transaction attempt #N failed: ${cause.message}`) and `io.ktor.server.plugins.statuspages` (its TRACE line with the URI and `cause.toString()`), to INFO.

**Spans carry an exception's type only.** The Ktor instrumentation records the RAW throwable of a failed request (`Span.recordException`: `exception.message` and `exception.stacktrace` with every "Caused by" message). `core`'s `getOpenTelemetry` wraps whichever exporter `OTEL_TRACES_EXPORTER` selects in `ScrubbedSpanExporter` (`addSpanExporterCustomizer`), which drops those two attributes from every span event, empties a span status description and drops the request's query string (`url.query`, with the full-URL spellings `url.full`, `http.url`, `http.target`: it holds what a user typed into a search or a filter); `exception.type` and every other attribute stay. Pinned by `ScrubbedSpanExporterTest` (the wrapper, and the real `getOpenTelemetry` wiring through a test-only exporter SPI). The OTel logs appender needs no wrapper: the 500 catch-all hands it the scrubbed throwable.

**Database connection guards (WARN logs, no audit event; `persistence.md` "Connection pool").** Logger
`ch.nokillswit.infra.db.LeakedSocketGuard`: `closed the socket of a broken database connection the driver had left open (backend pid
<pid>; its transaction rolls back)` each time the guard disposes a socket r2dbc-postgresql gave up on without closing (the r2dbc-postgresql
1.1.3 driver bug; it follows the driver's own `Connection Error` ERROR, e.g. "Response queue is full"); `leaked-socket guard disabled:
r2dbc-postgresql internals not found` once at boot (pool setup), if a driver upgrade renamed the internals it reads. A pooled session
the server ends under `idle_in_transaction_session_timeout` surfaces in Hammurabi only as a generic connection-closed error on its next
statement (in its own request or job); the `FATAL 25P03` naming the idle timeout is in the PostgreSQL log alone.

**Audit trail.** Security-relevant events are structured INFO logs on the dedicated
`ch.nokillswit.audit` logger with the `AUDIT` marker, emitted via `audit("area.event", "key" to value, …)`
(`audit/Audit.kt`) — they ride the same Logback→OTel pipeline, so shipping them to a
collector/SIEM is env-only. Fields travel as SLF4J key/values, not in the message text (assert
with `hasKeyValue` from `TestEnvironment.kt`). Emitted today:

- `login.success` / `login.failure` (with `reason`: unknown_email/wrong_password) /
  `login.lockout` (the throttle tripping) / `login.rejected_locked` (an attempt against a locked
  account) / `login.capacity_rejected` (an unseen identity cannot be admitted into the full
  throttle); login audits use `email` for ordinary identities and a fixed-size `emailDigest` for
  oversized submissions,
- `login.mfa_challenge` (email/userId — correct credentials, second factor pending) /
  `login.mfa_success` / `login.mfa_failure` (with `reason`:
  unknown_challenge/expired/wrong_code/too_many_attempts/user_gone/credential_changed) /
  `login.mfa_unavailable` (MFA enabled on a mail-less deployment → 503) /
  `login.mfa_capacity_rejected` (the pending challenge store is full → 429, no code sent) /
  `login.mfa_send_failed` (email/errorType),
- `logout`,
- `password_reset.requested` (email) / `password_reset.unknown_email` (email) / `password_reset.throttled` (email) /
  `password_reset.capacity_rejected` (email - new identity at the full cooldown store, no async task started) /
  `password_reset.link_sent` (userId/email) / `password_reset.capped` (userId - the account already holds the maximum of 5
  unexpired links: nothing minted, nothing sent, the request still answered 202) / `password_reset.stale_account` (userId - the
  account changed between the async task's lookup and the grant insert: nothing minted, nothing sent) / `password_reset.send_failed` (email/errorType - the link never left; its grant is
  revoked) / `password_reset.rejected` (no fields - a confirm against an invalid, expired, used or stale grant, uniform) /
  `password_reset.completed` (userId - the confirm) / `password_reset.notification_failed` (userId/errorType - the post-reset
  notice; the reset stands) - the self-service reset trail, none of it carrying a token or a password; the async task's events
  double as test barriers,
- `refresh.rejected` (with `reason`: invalid_or_expired/wrong_token_type/revoked/malformed/
  user_gone/credential_changed/replayed — `replayed` is a rotated refresh token presented again after the replay grace,
  `revoked` a logout-revoked one or a rotation this instance no longer remembers),
- `access.rejected` (with `reason`: user_gone/revoked/credential_changed/roles_changed/malformed, `userId` - the
  token's claim, null when absent OR out of range - and `suppressed`; `plugins/Security.kt`) - the per-request access check (`TokenBlocklistService.accessVerdict`)
  refused an otherwise valid signed access token: the account was deleted, the token was logged out, the password was
  changed, the role was changed, or the token lacks a claim the check needs (`roles`, `exp`, an invalid `userId`/`credentialRevision`: a pre-deploy token or a hand-signed one). **Coalesced** like `rate_limit.exceeded` (its own `RateLimitAuditThrottle`): at most ONE event per user and reason per 60 s, `suppressed`
  counting the rejections folded into it since the previous one (0 on the first), so a stale token replayed on every request writes a line a minute;
  the keys are bounded by the accounts that ever held a signed token. Junk, expired, wrong-audience/`typ` and jti-less tokens never reach the check and emit nothing. Never the token,
- `refresh.replay_grace` (userId — a rotated refresh token presented again inside the grace
  (`security.refresh.replayGraceSeconds`) and answered with a fresh pair, at most twice per token; a lost response or a racing tab, or a replay),
- `password.changed` (targetUserId/byUserId/selfChange) / `password.change_denied` (wrong or
  missing current password),
- `user.features_changed` (byUserId/targetUserId/featuresFrom/featuresTo — only on an actual
  change),
- `user.notification_preferences_changed` (byUserId/targetUserId/`from`/`to` as sorted `TYPE:CHANNEL` lists; only on an actual change; the bell and digest switches, V17; `NotificationRoutesTest`),
- `notification.email_sent` (userId/count - one digest left; the count is the notices it lists) and `notification.email_failed` (userId/errorType - the class name only, never the provider's message; the notices stay claimed and are not retried): emitted by `notifications/NotificationDigest.kt` per recipient, never a subject, body or address,
- `adr.expiry_reminded` (adrId/number/expiresOn/recipients - NO `byUserId`: the system; the reminder pass made the Driver's and Approver's notices for that expiry date, once per date; `ExpiryRemindersTest`),
- `user.language_changed` (the per-user language — byUserId/targetUserId + `from`/`to` codes;
  emitted only on an actual change; `user.created` additionally carries `language` when
  non-default),
- `user.created` (byUserId/newUserId/email/roles — roles as STORED, i.e. the folded
  additional-roles set) / `user.updated` (name/email deltas, only when changed) /
  `user.roles_changed` (rolesFrom/rolesTo, both as stored) / `user.deleted`
  (byUserId/targetUserId) — the user events (`/api/v1/users`),
- `team.created` (byUserId/teamId/name/managerUserId, nullable) / `team.updated`
  (byUserId/teamId/name) / `team.manager_changed` (byUserId/teamId/managerFrom/managerTo, nullable ids as STORED —
  only when a PUT actually changes the manager; a manager assignment can grant team-scoped rights, so it is its own
  trail line, the `user.roles_changed` precedent) / `team.deleted` (byUserId/teamId) — every team mutation; a rejected save
  emits nothing,
- `adr.created` (byUserId/adrId/number/participants - the count of initial participants) / `adr.updated` (byUserId/adrId/number/revision/fieldsChanged -
  comma-joined field names, never a value; only when the document PUT changed something, a no-op audits nothing) / `adr.transitioned`
  (byUserId/adrId/number/revision/from/to/reasoned - whether the move carried a reason, never the text) / `adr.participants_changed` (byUserId/adrId/number/revision/added/removed/changed - `userId:ROLE` lists, `userId:FROM>TO` for a
  role change, so a Driver or Approver handover is on the trail; only on an actual change) / `adr.deleted` (byUserId/adrId; ADMIN soft delete) /
  `adr.expired` (adrId/number/expiresOn - NO `byUserId`: the system, the expiry sweep) / `adr.tags_changed` (byUserId/adrId/number/revision/added/removed - comma-joined tag names, never content; only on a real change) / `adr.successor_changed` (byUserId/adrId/number/revision/supersedesFrom/supersedesTo - predecessor ids, null for none; only on a real change) / `adr.successor_detached` (byUserId/adrId/number/successorId/successorNumber/successorRevision - the predecessor's owner removed its pending successor; adrId is the PREDECESSOR, successorRevision the successor's bumped revision) / `adr.superseded` (byUserId/adrId/number/revision/successorId/successorNumber - the predecessor an activation moved to SUPERSEDED, after `adr.transitioned` of the successor; byUserId is the activator) / `tag_category.created` (byUserId/categoryId/name/tags - the count) / `tag_category.updated` (the same) / `tag_category.deleted` (byUserId/categoryId) - a stale-revision `409` on either emits none / `adr.discussion_posted` (byUserId/adrId/number/entryId/kind) /
  `adr.discussion_edited` (the same fields; only when the text changed) / `adr.discussion_deleted` (the same plus `byAdmin` - a moderation delete is visible) / `adr.discussion_state_changed` (the same plus `from`/`to`; only when the state really changed, or - `from == to` - when a RESOLVED entry's note did) - the discussion's four, never the
  text; every ADR mutation after its commit; a refused or conflicting
  write (403/409/400) emits none, a 403 is the generic `authz.denied`; content (the name and the Markdown fields) is never a field,
- `adr.exported` (byUserId/adrId/number) / `adr.exported_bulk` (byUserId/count/adrIds - the ids as a comma-joined list, up to 500) - the Markdown export (0.8.0), emitted when the export is accepted, just before the first byte; NEVER a name, a filter, the search term or content; a `404`, a refused filter or a `429` emits none. **`adr.exported_bulk` is audited on acceptance, before the stream: an export aborted mid-stream may have sent fewer ADRs than `count` says** (the client got a failed download, not a short archive). `export.busy` (byUserId) - a bulk export refused by the process cap (`adrs.export.maxConcurrent`); `HEAD` on either route is `405` and audits nothing,
- **`GET /adrs/overview` (0.9.0) emits no audit event**: a read of what every signed-in user can already read, and it exports nothing.
- `lens.created` (byUserId/lensId/visibility) / `lens.updated` (the same) / `lens.deleted` (byUserId/lensId/byAdmin - true when an ADMIN moderated a PUBLIC lens of someone else) - the saved ADR-list filters (`/api/v1/lenses`, V16); NEVER the name or the filters (a search term is user content),
- `search.timeout` (byUserId) / `search.busy` (byUserId) - a full-text search (`GET /adrs?text=`) outran `search.timeoutSeconds` (the cancelled statement or a deadline spent between two statements) or met the `search.maxConcurrent` cap: the typed `429`s, ids only, the throwable neither logged nor attached (`adrs/AdrListService.kt`, `security.md` "Search"),
- `bootstrap.admin_password_rotated` (userId/email — the fixed seed-admin email, never the password or
  its hash; `infra/db/Bootstrap.kt`, only when `ADMIN_INITIAL_PASSWORD` actually replaced the seed
  hash, so an idempotent re-boot emits nothing) — the boot-time credential event, so an admin-password reset leaves a trail beyond a log line
  (Flow's `crypto.reencrypted` boot-backfill event comes back with `EncryptedAtRest`/`Reencrypt`, `security.md` "Encryption at rest"),
- `authz.denied` (every 403, from the `ForbiddenException` handler in `plugins/ErrorHandling.kt`,
  with method/path/byUserId/detail — `path` is the request path only, never the query string),
- `rate_limit.exceeded` (bucket/method/path/suppressed, plus `byUserId` on the per-USER `telemetry` and `directory` buckets — a token bucket (`plugins/RateLimits.kt`: per-IP login, refresh,
  password-reset, password-reset-confirm, mfa, ready; per-user telemetry and directory) rejected the request with a 429, emitted from the 429 status handler in
  `plugins/ErrorHandling.kt`; `bucket` is stamped by the bucket's own `requestKey`. **Coalesced**
  (`RateLimitAuditThrottle`): at most ONE event per bucket per 60 s, `suppressed` counting the rejections folded into it
  since the previous event (0 on the first), so an unauthenticated flood writes a line a minute per bucket instead of one
  per request; keyed by bucket only (per-IP buckets), so memory is bounded whoever floods. The per-USER `telemetry` and `directory` buckets are keyed by
  `telemetry:<userId>` / `directory:<userId>` instead and carries `byUserId` -- the server-minted claim of a verified token, never client text; only a signed-in
  account reaches that key, so the key space is the account count. The client address is deliberately NOT a field
  (it is `X-Forwarded-For`-derived behind a proxy, i.e. attacker-chosen text; the ingress log has it), and neither is the
  query string. The per-account lockout's 429s are their own `login.lockout`/`login.rejected_locked` events and do not
  emit this).

Field-naming convention: the acting caller is `byUserId` everywhere except the auth lifecycle
events (`login.*`, `logout`, `refresh.rejected`, `access.rejected`, `password_reset.*`), where `userId` identifies the account being
authenticated.

Never log secrets (passwords, tokens); emails/ids are fine. When adding a security-relevant
mutation or denial path, emit an `audit(...)` event alongside it and extend this list in the same
change (Toadie's one sanctioned exception was a per-user view-state PUT written on every drag —
pure view state may stay unaudited, with a documented justification) — the catalog grows with every ADR mutation
(adr, participant, transition, discussion entry, tag, successor link: each gets its `area.event` names here in the commit that adds the route). Tested in
`AuditTest` via a Logback `ListAppender` on the audit logger (the shared `LogCapture` helper in
`TestEnvironment.kt`).

**Notices themselves are not audited (0.6.0).** A notice is a CONSEQUENCE of an audited ADR write (`adr.transitioned`, `adr.participants_changed`, `adr.discussion_posted`, `adr.expired`, `adr.superseded`, the successor events), made in that write's transaction from its history event; the trail already says who did what, so a line per recipient would only multiply it. `POST /notifications/{id}/seen`, `.../seen-all` and the retention are view state / housekeeping: no audit line (the bound is the `read-state` bucket, `security.md`). The sweep's logger is `ch.nokillswit.notifications.NotificationSweep`: INFO `notification retention: N notice(s) deleted` when a pass deleted any, WARN `notification retention failed, the rows stay until the next pass: <class names and SQLSTATE>`, WARN `expiry reminders failed: ...` and `notification email digest failed: ...` (all scrubbed, `scrubbedSummary`); the digest (`...NotificationDigest`) logs INFO `notification email digest: N notice(s) claimed, N email(s) sent, N failed, N released` per non-empty run and once `notification email digest is off` without a mailer; the reminder pass (`...ExpiryReminders`) WARNs `Could not remind about the expiry of ADR <id>: <exception class>`. No ADR name, text or email address reaches any of them.

**The ADR expiry sweep (WARN logs, `adrs/AdrExpirySweep.kt`, logger `ch.nokillswit.adrs.AdrExpirySweep`).** `Could not expire ADR <id>: <exception class>` when one ADR's own transaction fails (the rest still run; the class name only, a driver message can carry values) and `The ADR expiry sweep failed: <exception class>` when a whole pass fails (retried at the next interval). The sweep audits `adr.expired` per ADR it moves (above); a pass that moves nothing logs nothing. Config `adrs.expiry.enabled` (env `ADRS_EXPIRY_ENABLED`, default true) and `adrs.expiry.intervalSeconds` (`ADRS_EXPIRY_INTERVAL_SECONDS`, default 3600, 1..86400), validated on every boot, enabled or not; the loop runs on `backgroundScope("hammurabi-adr-expiry")`. Tests: `AdrExpirySweepTest`.

**Health probes** (`plugins/Health.kt`): `GET /api/v1/health` answers `{status: ok}` whenever the
process serves requests (the image's `HEALTHCHECK`, the compose healthcheck and the k8s liveness
probe); `GET /api/v1/ready` additionally round-trips the database (one `SELECT` on `users`, 3 s
budget) and answers a `503` problem while it fails (the k8s readiness probe and the e2e global
setup wait on it). Both are public and unaudited — they are called every few seconds and disclose
nothing. `/ready` alone rides a per-IP bucket (`ready`, 60/min, security.md; the kubelet needs 12/min), so an over-limit caller gets a `429` (the generic `rate_limit.exceeded` audit, bucket `ready`); `/health` does no I/O and is unbucketed. Tests: `HealthTest` (the `ReadinessProbeKey` seam swaps the database probe for a failing
one).

### Telemetry

`telemetry/` (the `ops` schema, `persistence.md` "Telemetry (V9)") is how Hammurabi measures itself: **the process samples itself into
Postgres, and the ADMIN read API ("Read API" below) and the panel read the rows.** Hammurabi is ONE process (the process `role` is the constant `app`), but the
design is multi-process-safe (two replicas, a rolling deploy) and kept that way on purpose. `configureTelemetry` runs after the database and monitoring seams (`application.yaml`); `telemetry.enabled` (env
`TELEMETRY_ENABLED`, default true) switches the sampler off, the suite sets it false (`TestEnvironment.configureApp`). The
`telemetry.*` block is validated on every boot, enabled or not: `sampleIntervalSeconds` 60..1800 and a divisor of 3600 (default 300; it is the stored raw `resolution`; validated by
`plugins/Monitoring.kt`, which needs it for the timers' window) and `storageIntervalSeconds` 900..86400 (3600), the cadence of the storage collector below; env `TELEMETRY_*` (README).

- **Per process, every tick** (`TelemetrySampler`, a `SupervisorJob` scope on `Dispatchers.IO`; the first tick 5 s after
  `ApplicationStarted`, then aligned to the interval; cancelled on `ApplicationStopping`, before the pool is disposed): `JvmProbe` —
  heap used/committed, the old generation's peak since the previous tick (`MemoryPoolMXBean` peak, reset after each read), non-heap, direct
  buffers, process CPU as a fraction of the available processors (CPU-time delta over wall delta), `ThreadMXBean.threadCount` (never a
  thread dump), GC count and time per collector as deltas; `CgroupProbe` — `/proc/self/status` `VmRSS`, and the memory of the process's OWN cgroup (from `/proc/self/cgroup`, joined under
  the mount root, falling back to the root itself; v2 `memory.current`, `memory.stat` `anon` and `inactive_file`, `memory.peak` — a LIFETIME peak, unlike the per-interval `mem.old_gen_peak` — `memory.max`; v1 fallback; "working set" =
  current − inactive_file, the figure the kubelet evicts on); the connection pool (`PoolStatsKey`: acquired, idle, pending, allocated); and `LatencyCollector` (below). A missing
  file (macOS, no memory controller, an old kernel without `memory.peak`) is a null figure and no row, never an error. The metric
  registry (names ≤ 48 chars, kind GAUGE|DELTA|PERCENTILE, unit, group) is `TelemetryMetrics.kt` — a new metric needs no migration.
- **Cluster section, one process per interval.** After its own samples a tick tries `pg_try_advisory_xact_lock(TELEMETRY_LOCK_KEY)`
  (`persistence.md`); the holder runs the cluster collectors in ONE transaction, each in a savepoint (a
  failing collector is undone alone and the rest still run), and commits their readings with a `telemetry.cluster_ms` marker row. A process that
  loses the try-lock, or finds the marker already at this interval's bucket, skips — so two replicas do the work
  once. The cluster collectors (`ClusterCollector`; per-process ones are `ProcessCollector`): **retention** (the hourly rollup, below, and the prune); the storage collector is the heavy one.
- **Requests (`LatencyCollector.kt`, `LatencyCollectorTest`).** Every tick it reads Ktor's Dropwizard TIMERS and status METERS from the registry (`MetricRegistryKey`;
  never its gauges) and writes, per normalised route (label `/api/v1/users/{id}`) and for `*` (every `/api/` route except `/api/v1/telemetry` and the `/api/v1/health` / `/api/v1/ready` probes),
  `http.count` (delta of the timer count against the value kept in memory; the first tick counts since process start), `http.p50`/`http.p95` (ms; the windowed snapshots of the
  timers that saw a request, merged into one set of observations per label, so `*` is a real percentile) and `http.5xx`/`http.429` (deltas of the per-status meters). A label has
  rows only in an interval it saw something (a 5xx/429 row only when non-zero). Series names are `ktor.calls.<route>.timer|meter|<status>` with `<route>` the whole selector chain
  (`/(authenticate "default")/api/v1/users/{id}/(method:GET)`); `normaliseRoute` strips every parenthesised selector (pinned by a test against the
  names Ktor 3.6 writes). Only ROUTED series are read: a `.<status>` meter counts when its route also has a `.timer` (the timer is stopped in the same hook that marks the
  status meter; the all-status `.meter` is marked earlier, when the routed call starts, and is not used), and a name containing `?` (Ktor's fallback name for a
  response it did not route: the URI, query included) is ignored. **Ktor marks a response each time the response pipeline runs**, and `ErrorHandling`'s `status(429)`
  handler answers the RateLimit plugin's bodiless 429 a second time: on a route with wrapping selectors that second mark lands under the URI-named fallback (ignored), on
  a bare route it lands twice in the `429` meter, so the surplus of a route's status meters over its timer delta is taken off its 429 count (a thrown exception is
  counted once; a 5xx needs no correction). `previous` keeps only the names seen in the latest tick.
- **Heavy collector: storage (cluster, outside the lock transaction).** The cluster transaction does only the cheap work
  (retention) and DECIDES whether the heavy collector is due (its marker, `telemetry.storage_ms`, is at least its interval older than the
  current bucket); a due one is CLAIMED by a marker row of value 0 committed with the cheap readings, so a heavy run is attempted at most once per interval whatever its
  outcome (a failure is not retried before the next interval, a rollback of the light phase claims nothing). After that commit the heavy phase runs under
  its own budget (`TelemetryConfig.heavyBudgetMillis` = 2/5 of the interval, so light + heavy fit in an interval), every statement in a SHORT transaction of its own
  (`HeavyReader`; `queryTimeout` 30 s, at most 3/4 of the heavy budget - 18 s at a 60 s interval), the readings inserted in one more transaction, and the marker set to the run's
  duration in milliseconds (at least 1; a marker still at 0 is a claim that did not complete). A failure is one WARN per collector then "recovered"; the heavy phase
  overrunning its budget is the collector `heavy`. No lock or long transaction spans the scans, so a deploy's `ALTER` is never queued behind them.
  **Storage** (every `storageIntervalSeconds`): `db.size` (`pg_database_size`) and `db.schema.size` per `public`/`ops` (and every schema a later migration adds): the sum of
  `pg_total_relation_size` (heap + TOAST + indexes) over relations of kind r/m. Catalog reads only.

- **Resolution and retention.** A raw sample (`resolution` = the sample interval in seconds, so any value below 3600) is `(ts = the interval bucket, process, metric, label, n = 1, avg = max =
  value)`, written with `ON CONFLICT DO NOTHING` (a repeated tick in a bucket adds nothing). Once an hour ended at least `max(10 minutes, interval / 2)` ago, the
  next cluster section folds every complete hour after the newest hourly row into hourly rows (`resolution` 3600: `n` = sum of the samples,
  `avg` = their n-weighted mean, `max` = the largest max, so a 5-minute peak survives; for a DELTA metric `avg * n` is the hour's total) — one idempotent
  `INSERT … SELECT … GROUP BY … ON CONFLICT DO NOTHING`. Raw rows are deleted after 7 days, hourly rows after 90, and a process unseen for 90 days
  with its samples (`ON DELETE CASCADE`). The prune runs only after a rollup that INSERTED rows (about once an hour); a rollup that fails throws before it, so raw rows are never pruned while the rollup keeps failing.
- **Failure isolation (it must never be the reason something else is slow or down).** Every collector runs in `catchingFailures` and is
  independent of the others; the whole tick runs under `withTimeoutOrNull(interval / 2)`; every statement under the transaction's
  `queryTimeout` (a quarter of the interval, 15..60 s; the only way a statement timeout may be set). Per collector, the FIRST failure is one WARN
  (`Telemetry collector <name> failed: <exception toString>`, no stack; a database failure renders as class names and SQLSTATE only, `scrubbedSummary()`), repeats are DEBUG, and the first success afterwards one INFO
  `Telemetry collector <name> recovered` (logger `ch.nokillswit.telemetry.TelemetrySampler`; the tick budget is the collector `tick`). It touches neither
  `/ready` nor any other probe, and no audit event: it records no user action. Pinned by `TelemetrySamplerTest`.
- **Never recorded:** JVM arguments, environment variables, system properties, request paths with identifiers, any user or ADR data. A process
  row holds the role, the instance (`HOSTNAME`, else the resolver's host name, ≤ 100 chars), the JVM start, the build version and commit
  (the image's `HAMMURABI_BUILD_VERSION`/`HAMMURABI_BUILD_COMMIT`, stamped with `--build-arg`; null outside the image), the available processors,
  the heap maximum and the container memory limit. The `ops` tables hold only numbers and these labels.
- **Seams** (lower layers publish, `telemetry/` reads; nothing imports it — `PackageBoundaryTest`): `plugins/Monitoring.kt`'s `MetricRegistryKey`
  and `infra/db/Database.kt`'s `PoolStatsKey` (pinned present for r2dbc-pool 1.0.2 by `PoolStatsTest`); the test seams are
  `TelemetryOverridesKey` (clock, probes, identity, first-tick delay) and `TelemetrySamplerKey` (the running sampler).
- **Read API (ADMIN; `TelemetryRoutes.kt`, `TelemetryReads.kt`, `TelemetryCurrent.kt`, `TelemetryHistory.kt`, `TelemetryWire.kt`; `authorization.md`).** Two GETs, registered with the feature
  routes, every statement under a 5 s `queryTimeout`. `GET /api/v1/telemetry/current` answers `{generatedAt, sampleIntervalSeconds, processes[], storage, requests}`:
  `processes` = every process seen in the last 24 h that is live, every stale one seen within the last hour (a wedged replica, a just-replaced pod), plus per role the newest older stale one when none is alive (`stale` = no tick, or no sample, for more than 3 sample intervals: `last_seen` or the newest sample's bucket that old, or no sample at all for a process older than that), each with the figures of ITS newest raw
  bucket (a figure that bucket did not write is null, never carried over): the `memory` block (heap used/committed/max, old-gen peak, non-heap, direct, rss, cgroup current/anon/working set/peak,
  `containerLimitBytes`, `percentOfLimit` = working set / limit in percent, one decimal, null without a limit) and the `jvm` block (cpu ratio, threads, GC count/time summed over collectors, the four pool figures);
  `storage` = the newest `db.size` / `db.schema.size` measurement; `requests` = the newest bucket of the live processes (counts and errors add up, p50 weighted by the requests of the processes that wrote one, p95 the largest): the `*` total and the 15 busiest routes; `windowSeconds` is the counts' window (the sample interval), `percentileWindowSeconds` the p50/p95 window (the interval capped at 300 s, the timers' reservoir). `GET /api/v1/telemetry/history?group=memory|storage|requests|jvm&range=24h|7d|90d`
  (both required, a `400` otherwise) answers `{group, range, bucketSeconds, from, to, historyFrom, truncated, series[{key, metric, label, role, connectionId, unit, kind, points[{t, avg, max}]}]}`.
  Buckets: 24h = the sample interval (widened to a whole multiple of it only if 24 h would exceed 400 points, i.e. an interval under 216 s), 7d = 3600 s (hourly rows PLUS the raw rows after the newest hourly row, so the
  current hours are never missing and nothing is counted twice), 90d = 21600 s (hourly rows grouped to six hours in SQL). Per-process groups (memory, jvm, requests) merge the processes of one `role` into one series
  (`key` = `<role>:<metric>[:<label>]`) so a trend survives a restart; `requests` series merge ALL serving roles per route (`key` = `<metric>:<route>`, `role` null); storage is cluster-wide (`role` null). A GAUGE/PERCENTILE point is the n-weighted mean and the largest sample; a DELTA point's `avg` is the bucket's
  TOTAL and `max` the largest single interval. `requests` keeps `*` (all five metrics) and the ten busiest routes of the range (count, p50, p95 only: the series cap).
  At most 400 points per series and 40 series (`truncated` when more matched, in registry then label order). `historyFrom` = the oldest instant the range can hold, clipped to the range: the first process start. Both reads ride the per-user `telemetry` bucket (30/min, security.md). Pinned by `TelemetryRoutesTest` (clock pinned through `TelemetryOverridesKey`).
- **Registry bound:** the `TelemetryRegistry` never registers a `ktor.calls.` name containing `?` and registers at most 2,000 `ktor.calls.` names (security.md "Telemetry"); the Slf4jReporter filters `?` names.
- **Tests:** `CgroupProbeTest`, `JvmProbeTest`, `TelemetryConfigTest`, `TelemetryStoreTest` (insert, rollup, prune boundaries, cascade, the lock),
  `LatencyCollectorTest`, `TelemetrySamplerTest` (the storage collector too), `TelemetryRoutesTest` (the read API), `PoolStatsTest`, `TelemetryRegistryTest` (the sliding window, no JVM gauge sets).

### Not yet ported

Flow's sync-job, data-source, metrics, capacity and outbound-guard audit events (`data_source.*`, `sync_job.*`, `outbound.blocked`, `team.capacity_*`, `public_holiday.*`,
`crypto.reencrypted`) were dropped with their features. The ADR events are added above in the commit that adds the route. Flow's "code-read refusals" paragraph (a row-budget refusal
that is neither an audit event nor a log line of its own) came with its report features and is not ported.
