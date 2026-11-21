### Persistence

PostgreSQL is the only database. Connection settings come from the `postgres:` block in
`application.yaml` (env-overridable via `POSTGRES_JDBC_URL`, `POSTGRES_R2DBC_URL`,
`POSTGRES_USER`, `POSTGRES_PASSWORD`); defaults match the `docker compose up postgres` service
(host port **5440** — the sibling repos occupy 5432–5438 on the same machine, table in `ci.md`;
in-network consumers use `postgres:5432`). There is one persistence stack:

- **Flyway** (`infra/db/Flyway.kt`) — runs schema migrations from
  `server/src/main/resources/db/migration/` at startup via the Java API, opening a short-lived
  JDBC connection. Migrations are the single source of truth for schema; do not call
  `SchemaUtils.create` anywhere. **An applied migration's bytes are immutable — comments
  included**: Flyway validates stored checksums at startup, so any edit to an existing `V*.sql`
  makes every long-lived database refuse to boot (a failure no fresh-container CI run can see).
  `MigrationChecksumTest` pins every file's checksum; a new migration adds one manifest line, and
  a red pin means REVERT the edit (clarifications go into this doc), never update the pinned
  value. The range today is **V1–V21**: V1 `users`, V2 `revoked_tokens`, V3 the seed admin, V4 the
  `unaccent` extension, V5 `user_disabled_features` (V1–V5 are Flow's), V6 `teams` (with the optional
  manager), V7 `users.credential_revision`, V8 `password_reset_tokens`, V9 the `ops` telemetry
  schema, V10 `adrs` + `adr_participants`, V11 `adr_events`, V12 `adr_discussion`, V13 `tag_categories` + `tags` + `adr_tags` (and the seed), V14 `adrs.supersedes_id`, V15 `adrs.search` (the generated `tsvector`, the `hammurabi_search` configuration, the GIN index and the filter indexes), V16 `lenses`, V17 `notifications` + `user_notification_preferences`, V18 `adr_expiry_reminders`, V19 `adrs.supersedes_accepted_id`, V20 the lens filter names, V21 the per-actor mail budget (`notification_email_budget`). (The bootstrap renumbered the donor's history; from V10 on, migrations are append-only like any other.)
- **Exposed + R2DBC** (`infra/db/Database.kt` + the feature services) — runtime DB access.
  `Database.kt` connects the `R2dbcDatabase` and is the composition root: it constructs the
  services and publishes them into `Application.attributes` (today: `UserServiceKey`, `TeamServiceKey`, `NotificationServiceKey`, `AdrEventServiceKey`, `AdrDiscussionServiceKey`, `TagCategoryServiceKey`, `AdrServiceKey`, `AdrListServiceKey`, `AdrExportServiceKey`, `AdrOverviewServiceKey`, `LensServiceKey`, `TokenBlocklistServiceKey`, `PasswordResetServiceKey`; `notifications/NotificationPreferenceRoutes.kt` and `telemetry/TelemetryRoutes.kt` build their own, `conventions.md` "Deliberate exceptions"); each service itself lives next to the feature it serves
  (`users/UserService.kt`, `teams/TeamService.kt`, `adrs/AdrService.kt`, `auth/TokenBlocklistService.kt`, ...). The Exposed
  table `object`s (e.g. `UserService.Users`, nested inside their service) are used for queries
  only, not DDL. Ids are `UIntIdTable` — unsigned end-to-end (the spec declares `minimum: 0`, and
  `ErrorHandling.kt` 400s negative path segments before kotlinx's `UInt` decoding can silently
  wrap them); one wrinkle inherited from Toadie: V1 creates `users.id` as `BIGSERIAL` (64-bit in
  SQL, 32-bit everywhere above it) while `teams` uses `SERIAL`/`INTEGER` — harmless at this scale,
  documented so nobody "fixes" one to match the other without a migration (an `INTEGER` FK column
  may reference the `BIGINT` id; Postgres compares them fine).

The `org.postgresql:postgresql` JDBC driver is on the classpath solely for Flyway; runtime queries
go through R2DBC.

### Connection pool

- **Applies when:** touching `infra/db/Database.kt`'s connect call, the `postgres.pool.*`
  configuration, or reasoning about how many PostgreSQL backends one Hammurabi instance can hold.
- **Requirement:** Exposed connects through ONE bounded `io.r2dbc:r2dbc-pool` `ConnectionPool`
  (ported from Lettuce via Flow) wrapping the plain PostgreSQL R2DBC factory, built by the one shared
  `connectPooledDatabase` (the test harness's direct `sharedDatabaseForTests()` uses it too, with a
  small pool of its own) — never a raw `r2dbc:postgresql://` connect, which opens one backend per `suspendTransaction` with nothing
  capping concurrency (measured in Lettuce: 120 parallel requests took ALL 100 backends of PostgreSQL's default `max_connections`;
  the JWT validation's blocklist read failed and surfaced as an invalid token — so a throwing blocklist lookup answers the
  catch-all's 500, never 401, see "JWT model" in `.claude/docs/security.md`).
  Bounds come from `application.yaml`'s `postgres.pool` block, each boot-validated (startup fails
  outside the range, via the shared `infra/config/` `requireConfigInt`/`requireConfigLong` helpers): `maxSize`
  (`POSTGRES_POOL_MAX_SIZE`, default 20, 1..1000), `initialSize` (`POSTGRES_POOL_INITIAL_SIZE`,
  default 2, 0..maxSize — the floor the pool fills up to on its FIRST acquire, not at
  construction: r2dbc-pool warms up lazily and `warmup()` is deliberately not called),
  `maxAcquireTimeSeconds` (`POSTGRES_POOL_MAX_ACQUIRE_SECONDS`, default 10,
  1..600) and `maxIdleTimeSeconds` (`POSTGRES_POOL_MAX_IDLE_SECONDS`, default 600, 1..86400). Every
  pooled connection carries `application_name = hammurabi` (`postgres.pool.applicationName`, test-only
  override), so operators count this instance's backends with
  `SELECT count(*) FROM pg_stat_activity WHERE application_name = 'hammurabi'`. The driver's per-connection
  prepared-statement cache is bounded (`PREPARED_STATEMENT_CACHE_QUERIES` = 256 in `Database.kt`, applied in
  `connectPooledDatabase`, so the test pool has it too; r2dbc-postgresql's default is unbounded and every cached text stays a
  named server-side statement for the connection's life) — it matters because `MultiRowInsert.kt`'s SQL text varies with the row count
  (quantized to a handful of sizes per table). **A multi-row statement is bounded in payload as well as in bind parameters**:
  `MultiRowInsert.kt` also splits a batch so one statement binds at most `MAX_PAYLOAD_CHARS` (1 MiB) of `String` values (a lone
  heavier row goes alone, never refused there). `insertRowsIgnoringConflicts` builds each row (run the caller's body)
  lazily, only while the statement that carries it is assembled, so the rows in flight are one statement's cap, not the call's, and
  each body runs exactly once. Exposed lists every
  statement a transaction ran until it ends (parsed SQL and encoded parameters included): `insertRowsIgnoringConflicts` clears the list after each statement.
- **Statement timeouts: use the transaction's `queryTimeout`, nothing else.** Exposed's R2DBC executor *requests* the
  transaction's `queryTimeout` (whole seconds, default 0, never null) from the driver before EVERY statement. The telemetry reads
  use it (`queryTimeout` 5 s, "Telemetry (V9)"). **The per-connection cache (`infra/db/StatementTimeoutCache.kt`).**
  r2dbc-postgresql answers each request with its own `SET STATEMENT_TIMEOUT` simple query that Exposed awaits — a large share of all
  round trips. `connectPooledDatabase` therefore wraps the RAW connection factory (below the pool) in
  `StatementTimeoutCachingConnectionFactory`: each physical connection remembers the value the server last confirmed, and the
  driver call is *sent* only when the requested value differs from it. No reset on release is needed — Exposed requests its
  value before every statement, so the borrower after a 30 s budget sees `0 != 30` and sends it, which is also why a budget
  never leaks to the next borrower. **The hazard it handles:** PostgreSQL reverts a session-level
  `SET` issued inside a transaction when that transaction rolls back, and a statement cancelled by the timeout aborts its
  transaction — a naive cache would then believe the budget is still set and run the next query unbudgeted. The remembered value
  is therefore forgotten on every rollback (also to a savepoint), on a failed commit and on `setAutoCommit(true)`, forgotten
  before a SET is sent and remembered only once it completed under an unchanged generation, and kept across a successful commit.
  **Rule: ONLY the transaction's `queryTimeout` may set the statement timeout.** A `SET LOCAL statement_timeout` may stay in
  force for the rest of its transaction (the cache no longer sends a request that matches its remembered value, so nothing
  overwrites it), and a session-level `SET`, `set_config(…, false)`, `RESET` or `DISCARD` on a pooled connection desyncs the cache
  from the backend. Deployment assumption: one client connection is one server session — a transaction-mode PgBouncer in front of
  Postgres would break the cache (none today). `StatementTimeoutCacheTest` (own one-connection pool) pins convergence, the
  rolled-back SET, the aborted-transaction canary, a budget enforced again after a cancelled statement, and the skip with its limit.
- **Sizing.** Size it as `maxSize × replicas + 1` (Flyway's short-lived JDBC connection, `infra/db/Flyway.kt`) well under
  the server's `max_connections`. `maxAcquireTimeSeconds` is a per-attempt deadline, and the effective
  bound is about 2× the setting. r2dbc-pool 1.0.2's `ConnectionPool` builds `create()` as
  `Mono.defer { acquire.flatMap(…).timeout(maxAcquireTime) }.retry(acquireRetry)` and the builder default is
  `acquireRetry = 1`: the timeout is an ordinary error to `retry`, so a timed-out waiter is
  re-queued at the BACK of the acquire queue for a second full deadline before the failure surfaces. Hammurabi keeps the
  default on purpose: the same retry is what transparently replaces a connection that fails `LOCAL` validation
  (`prepareConnection` invalidates it and the outer retry acquires another), so a connection closed from the driver
  side — a Postgres or pod restart, a FIN/RST on an idle socket — costs no request, whereas with
  `acquireRetry(0)` each such stale connection would fail one. After a Postgres restart the pool therefore heals itself as long as
  at most one stale connection is handed out per acquire; size `maxAcquireTimeSeconds` knowing a saturated pool fails after twice it
  (`ConnectionPoolTest` pins the ~2×). A caller that waits past the acquire deadline fails with the pool's timeout
  exception, which `plugins/ErrorHandling.kt`'s catch-all renders as a logged `500` — deliberately NOT a new declared status, since the
  OpenAPI conformance gate would need it on every operation. The pool is disposed on `ApplicationStopped`, so every `testApplication` the
  suite boots releases its connections. Every background loop (today the telemetry sampler and the ADR expiry sweep) therefore
  lives in an `Application.backgroundScope(...)` (`infra/BackgroundScope.kt`), which cancels AND joins it (2 s) on `ApplicationStopping`, earlier. Exposed's `R2dbcDatabase.connect(connectionFactory, databaseConfig)` derives the dialect from
  `databaseConfig.connectionFactoryOptions` alone, so the parsed options (still `driver=postgresql`) are threaded into the config
  unchanged while traffic goes through the pool. The same config pins `defaultMaxAttempts = 1`: Exposed would otherwise retry ANY
  `R2dbcException` three times, and the pool's acquire timeout is one — a saturated pool would cost 3 × the acquire budget per request.
  **Dependency alignment:** r2dbc-pool declares an older reactor-pool than the reactor-core Hammurabi resolves, so
  `server/build.gradle.kts` imports the Reactor BOM (`reactor-bom` in `gradle/libs.versions.toml`) as a platform — it pins
  reactor-core, reactor-pool and reactor-netty as one release train, and `:server:checkDependencyAlignment` fails when any
  reactor module resolves to a version other than the BOM's. Move the `reactor-bom` catalog line together with the `netty` pin.
  Covered by `ConnectionPoolTest` (bounded concurrency, a saturated pool's acquire timeout, its ~2× deadline, disposal on
  `ApplicationStopped` and on a later module's failed startup, and the config range-check cases) and `checkDependencyAlignment`'s own Reactor guard.
- **A backend left holding its transaction.** Two defences, both on every pooled connection
  (built in `connectPooledDatabase`, so the test pool has them too). (1) `infra/db/LeakedSocketGuard.kt`: r2dbc-postgresql 1.1.3
  never closes the socket after a protocol-level connection error ("Response queue is full"). The backend then keeps its transaction
  and locks for the life of the process — `idle in transaction`, or `active` in `ClientWrite`, which no server timeout ends. A decorator
  on the RAW factory (below the pool, beside the statement-timeout cache) disposes the netty channel after the driver's close when the
  client is closed but the channel is open; the pool's release validation (`LOCAL`) fails such a connection at once and destroys it. It
  reads two private driver fields by reflection; `LeakedSocketGuardTest` pins them AND the driver bug on an unguarded connection (red
  after an upgrade = the upstream fix landed, delete the guard). (2) `idle_in_transaction_session_timeout`, sent as a startup parameter
  (`postgres.pool.idleInTransactionTimeoutSeconds`, `POSTGRES_POOL_IDLE_IN_TRANSACTION_TIMEOUT_SECONDS`, default 300, 0..86400, 0 = off;
  an operator's own `options=` key in the URL wins): the server ends any pooled session idle inside a transaction for longer — the
  backstop for a client that stopped talking with a transaction open (a coroutine stuck mid-transaction). No transaction may span an
  outbound call or a mail send. It is a different setting from the statement timeout, so the "only `queryTimeout`" rule is untouched.
  `LeakedSocketGuardTest` pins both.
- **A pooled connection must not pin the frame that created it.** reactor-netty keeps the subscriber
  chain of the `create()` that opened a channel on the channel (`ChannelOperations.listener`) for the channel's whole life
  (`maxIdleTimeSeconds` of idleness, never while the connection is reused). Through r2dbc-pool's allocator, the pool's borrower and
  Exposed's `awaitOne` that chain reached the continuation of the coroutine that happened to trigger the creation, so a finished
  request's frame stayed reachable from the netty selector for minutes. `infra/db/DetachingConnectionFactory.kt` ends the chain at a
  bridge: it subscribes to the real `create()` with a `DetachingBridge`, which forwards the one connection / error / completion to the
  pool and then DROPS the pool's subscriber. It offers the driver an EMPTY Reactor context (reactor-netty copies the subscriber context
  into the observer at connect time and keeps the copy for the channel's life). It is the OUTERMOST decorator of the raw factory in
  `connectPooledDatabase` so that a connection it closes is the decorated one (the close reaches the socket guard). It honours demand,
  delivers exactly once and CLOSES a connection nobody owns (it arrived after a cancel): never a leaked socket.
  `PooledConnectionPinTest` proves it with weak references on a fresh pool (a 16 MiB array held across the query, the function returned,
  the connection idle, `System.gc()` must clear it); its callers run on `Dispatchers.IO` (a test that runs the query directly on the
  `runBlocking` thread keeps a stale reference on that thread's own stack and is red either way). `DetachingConnectionFactoryTest`
  pins the contract without a database.
- **Exception:** the pool runs r2dbc-pool's defaults for liveness (`ValidationDepth.LOCAL`, no
  `maxLifeTime`): a connection killed server-side between uses is handed out once and fails that
  request with a 500 — accepted until a deployment introduces an idle killer or proxy.

### Cross-feature table reads AND writes (the service-layer rule, inherited from Lettuce)

A feature service MAY query — or WRITE — another feature's Exposed table objects directly when the
operation must run **inside its own transaction** (SQL joins, atomic snapshots, a single soft-delete that must also close a
dependent row elsewhere) — the transaction boundary must be explicit rather than relying on an unrelated
service to preserve it. Exposed reuses an enclosing transaction for nested `suspendTransaction`
calls on the same database. Route handlers never touch tables (services only). The reads/writes in
place:

- `TeamService` reads `UserService.Users` for the manager: the active manager's display name (one
  batched lookup per page), and the manager id check on create AND update. `teams.manager_user_id` has no `ON DELETE` rule: users are only ever soft-deleted;
  a future hard purge of users must clear it first, and a future user RESTORE must clear or re-audit the
  manager grants the user held (a soft-deleted manager's id stays in the column, inert only while the user
  is deleted).
- `auth/TokenBlocklistService.accessVerdict` (the per-request access-token check, `plugins/Security.kt`) READS
  `users/UserService.Users` (`role`, `credential_revision`, `marked_as_deleted`, by primary key) and its own
  `revoked_tokens` (an `EXISTS` on the `jti`) in ONE uncached statement per authenticated request; it writes nothing.
- `auth/PasswordResetService` (V8) READS `users/UserService.Users` (the `active()` / `credential_revision` / `email` predicate
  of a grant's liveness, and `FOR UPDATE` on the user row) and WRITES `users.password_hash` / `password_changed_at` / `credential_revision`
  through `users`' `updatePasswordInTransaction` inside its own transaction (the grant is consumed in the same transaction).
- `adrs/AdrParticipantService` (`adr_participants`) READS `users/UserService.Users` in the ADR write's own transaction: the display name of each participant (joined, a soft-deleted user keeps the name) and the `active()` check of every user a set adds or re-roles (a client-supplied FK is a `400`, never a constraint `500`); `adrs/AdrEventService`'s `EventLog` LEFT-joins `Users` for the actor's name and deletion flag (`infra/db/EventLog.kt`: the feature passes the actor table in, `infra/` names no feature). `adrs/AdrDiscussionService` (`adr_discussion`, V12) READS `users/UserService.Users` (the names and deletion flags of an entry's author and resolver, one batched `id IN (...)` per page, a soft-deleted user keeps the name), READS `adrs` (the status and number, locked `FOR NO KEY UPDATE` on a write) and `adr_participants` (the caller's role, through `AdrParticipantService.roleOf`), and WRITES `adr_discussion` plus `adr_events` (through `AdrEventService.record`, same transaction); `AdrService` READS `adr_discussion` for the counts (`AdrDiscussionService.countsOf`, which runs in the read's or list page's own transaction and spells the index predicate as literals so a prepared plan can use the partial index). Both write only `adr_*`. `AdrService` writes `adrs.created_by` / `adr_participants.added_by` / `adr_events.user_id` (FKs to `users`, no `ON DELETE` rule: users soft-delete only); `AdrDiscussionService` writes `adr_discussion.author_id` / `state_changed_by` likewise.
- `adrs/AdrTagService` (`adr_tags`, V13) READS `tags/TagCategoryService.Tags` (`tags`: the live-name lookup of a tag write, and the name and retired flag joined into every ADR read and list page, in the ADR write's or read's own transaction) and WRITES `adr_tags` plus `adr_events` (through `AdrEventService.record`); `adr_tags.added_by` is an FK to `users`. The list's `tag` filter reads `adr_tags` ⋈ `tags` as a subquery of the `adrs` predicate (`AdrListQuery.kt`). `adrs/AdrSuccessorService` READS and WRITES `adrs` (`supersedes_id`, and the status moves of an activation, `AdrService.transition`) and `adr_events`, all inside the ADR write's transaction. `tags/` itself touches only `tag_categories` and `tags`.
- `plugins/Health.kt` (`GET /api/v1/ready`, the readiness probe) READS `users/UserService.Users` (`id`, `LIMIT 1`), a bounded round trip
  that proves the database answers; it writes nothing and returns no row.
- `telemetry/StorageCollectors.kt` (the storage panel) reads, OUTSIDE the telemetry cluster transaction (the heavy phase: each statement is a short
  transaction of its own, `HeavyCollectors.kt`): the catalogs (`pg_class`, `pg_namespace`, `pg_attribute`, `pg_database_size`, `pg_total_relation_size`) for
  sizes — row COUNTS and sizes only, never a payload; it writes nothing outside `ops`.
- `telemetry/TelemetryRoutes.kt` (the ADMIN read API: `TelemetryReads.kt`, `TelemetryCurrent.kt`, `TelemetryHistory.kt`) READS `ops.telemetry_processes` /
  `ops.telemetry_samples` (its own schema; one SELECT per transaction under `queryTimeout` 5 s, the history grouped per role/metric/label/bucket in SQL).
- `adrs/AdrListService` (the list and the search) READS `adrs`, `adr_participants` (the `participantId` filter and the Driver and Approver of the page: `AdrParticipantService.leadsOf`), `adr_tags` and `tags/TagCategoryService.Tags` (the `tag` filter, the page's tags), `adr_discussion` (the `hasOpenIssues` filter and the page's counts) and the generated `adrs.search`; it writes nothing.
- `adrs/AdrExportService` (0.8.0, the Markdown export) READS `adrs`, `adr_participants` (with the users' names, `AdrParticipantService.forAdrs`), `adr_tags` with `tags` and `tag_categories` (`AdrTagService.categorizedForAdrs`: the category name), `adr_events` (the first `TRANSITIONED` into `ACTIVE`, the decision date: `AdrEventService.firstActivations`) and the successor links (`AdrSuccessorService.refOf`/`successorOf`), one transaction per batch of 25 ADRs; it writes nothing. `AdrListService.exportPlan` reads `adrs` (id, number, name) under the list's predicate.
- `adrs/AdrOverviewService` (0.9.0, the decision log) READS `adrs` (the status counts, `status_changed_at`, `expires_on`), `adr_tags` with `tags/TagCategoryService.Tags` and `TagCategories` (the in-force groups: one windowed raw statement over `adr_tags` ⋈ `adrs` ⋈ `tags` ⋈ `tag_categories`, live rows only, and a `NOT IN` over the live `adr_tags` for the untagged), `adr_participants` (the awaiting page's Approvers through `AdrParticipantService.leadsOf`, and the caller's own Approver count) and `users` (`activeAmong`: whether an Approver is an `active()` account); one read-only REPEATABLE READ transaction, writes nothing, no migration.
- `lenses/LensService` READS `users/UserService.Users` (the creator's name and deleted flag, joined in the lens query's own transaction) and WRITES only `lenses`.
- `notifications/NotificationService` (V17) WRITES `notifications` in the ADR write's own transaction (through `AdrNotices`, called by `adrs/AdrEventService.record` right after the event row) and READS `adrs/AdrParticipantService.AdrParticipants` (the holders of the roles a notice names, as the write left them), `adrs/AdrDiscussionService.AdrDiscussion` (the author of an answered or resolved entry), `adrs` (the list joins it for the ADR's number, name and `active()`, and the keys of a second ADR) and `users` (names for a page; the insert's own `EXISTS` keeps only ACTIVE accounts) and `user_notification_preferences` (the in-app switch, inside the insert's predicate). `notifications/NotificationDigest` READS `users` (name, email, language, `active()`), `adrs` (number, `active()`) and `user_notification_preferences` (the email switch) and WRITES `notifications.emailed_at` (the claim, the settle, the release). `notifications/ExpiryReminders` (V18) READS `adrs` (locked `FOR NO KEY UPDATE`) and WRITES `adr_expiry_reminders` plus, through `NotificationService.make`, `notifications`, in one transaction per ADR. `NotificationPreferenceService` READS `users` (`active()`, locked `FOR NO KEY UPDATE`) and WRITES only `user_notification_preferences`. `notifications/NotificationEmailBudget` (V21) READS and WRITES only its own `notification_email_budget` (ensure the account's row of the day, lock it `FOR UPDATE`, save the counter), inside the writing transaction (first thing in it, `AdrNotices.reserve`, and again when the notices are made), and deletes the old days from the retention pass; it references `users` by foreign key and nothing else. `NotificationService` additionally READS `adr_participants` joined to `users` (`active()`) for the Approver a system-made notice is charged to.

List each new cross-feature read/write here as it lands — the list IS the permission.

### Schemas

Hammurabi's PostgreSQL database is split across schemas:

- **`public`** — the foundation tables (`users`, `teams`, `revoked_tokens`, `user_disabled_features`, `password_reset_tokens`), the
  ADR tables (`adrs`, `adr_participants`, `adr_events`, `adr_discussion`, `adr_tags`, `lenses` today; `adr-model.md`), the tag registry (`tag_categories`, `tags`) and the notifications (`notifications`, `user_notification_preferences`, `adr_expiry_reminders`, V17-V18). Flyway's own history table also stays here — `flyway.schemas` is left at its default.
- **`ops`** — Hammurabi's own operational telemetry (V9, "Telemetry (V9)" below): the process registry and the metric samples each
  process writes about itself. Numbers and labels only, never user or ADR data.

**How Exposed addresses a schema-qualified table.** No Exposed `Schema` object and no
`search_path` override are involved: a table object simply passes the dotted, schema-qualified name straight to the `Table(...)`
constructor — e.g. `object Samples : Table("ops.telemetry_samples")` — and Exposed/R2DBC resolve it as-is, including a cross-schema
FK reference. `CREATE SCHEMA IF NOT EXISTS <schema>` runs in the first migration that needs the schema (V9 for `ops`) — never a
standalone "create schemas" migration.

### Credential revision (V7)

`users.credential_revision` is an internal nonnegative BIGINT generation, initially zero. Every
password update/reset and conditional bootstrap rotation increments it atomically in SQL with the
hash update. It is carried in signed access and refresh tokens and pending MFA challenges; the per-request access
check (`TokenBlocklistService.accessVerdict`), refresh and MFA acceptance compare it with the current user row.
Soft delete and role changes do NOT bump it (the access check compares the stored role separately; a delete
is `user_gone`). It is not exposed in user DTOs. A password-reset grant (V8) captures it and goes stale when it moves.
`password_changed_at` remains a timestamp, not an authorization boundary.

### Password reset tokens (V8)

`password_reset_tokens`: single-use grants, only the SHA-256 digest of the emailed token is stored (`token_hash` PK), with the
account snapshot (`email`, `credential_revision`) the link was issued against and `expires_at`; `user_id` references `users` with
`ON DELETE CASCADE`. A hard-delete table (expiring credentials, not history — see "Soft delete (convention)"); expired rows are
pruned opportunistically.

### Telemetry (V9)

`ops.telemetry_processes` and `ops.telemetry_samples` (V9; `telemetry/TelemetryStore.kt`, `.claude/docs/observability.md` "Telemetry")
are the tables of the `ops` schema (created by this migration, the "no standalone create-schemas migration" rule). A process row is one
JVM run (`role`, `instance`, `started_at`, `last_seen`, `build_version`/`build_commit`, `processors`, `heap_max_bytes`, nullable
`container_limit_bytes`; unique on `(role, instance, started_at)`; `role` is the constant `"app"` — Hammurabi is one process; the column stays for a future split);
`telemetry_samples` is ONE narrow table for every metric —
`(resolution, metric, ts, process_id, label)` is the primary key (`resolution` = the sample width in seconds: a raw row stores the sampler's real interval, 60..1800 and a divisor of 3600, an hourly rollup 3600 - the tier is `resolution < 3600`; the CHECK enforces it), `n`/`avg`/`max` the figures, so a metric is added in
`TelemetryMetrics.kt` with no migration. Epoch-millis `BIGINT` timestamps, `SERIAL` ids, the cluster section's rows (its marker today) attributed to
the process that took the lock. Writes go through `infra/db/MultiRowInsert.kt`'s `insertRowsIgnoringConflicts`; every statement runs under
the transaction's `queryTimeout`.

**The telemetry prune is a hard-delete exception**: samples are regenerated, high-volume observations with no reader once old, so they
are deleted, not soft-deleted — raw rows older than 7 days, hourly rows older than 90, processes unseen for 90 days (their samples
cascade, `ON DELETE CASCADE`; an index on `process_id` keeps that cascade off a table scan). The prune runs inside the telemetry cluster
transaction under `TELEMETRY_LOCK_KEY`, and a row exactly at its cutoff stays. The tests own the schema:
`TelemetryStoreTest`/`TelemetrySamplerTest` empty `ops` before each test (the suite starts no sampler).

### Soft delete (convention)

`users`, `teams`, `adrs`, `lenses`, `tag_categories`, `tags` (a retired tag) and `adr_discussion` (a deleted entry is a tombstone) are **soft-deleted** — rows are flagged, never physically removed; every
business entity follows the same convention. Only join/token tables (today: `revoked_tokens`, a
pure token registry, `password_reset_tokens` (V8, expiring single-use credentials, not history), `user_disabled_features`, a pure flag join
whose PUT is a wholesale replace, `adr_participants` and `adr_tags`, current-fact joins replaced as a diff whose every change is an `adr_events` row, `notifications` (pointers, deleted with their ADR or user and by the retention window; V17), `user_notification_preferences` (a disabled-pair flag join, replaced wholesale), `adr_expiry_reminders` (a sent marker, V18), `notification_email_budget` (a counter of the day, V21; the notice retention pass deletes the days before its cutoff's) and the
`ops.telemetry_*` samples (the prune above)) hard-delete; `adr_events` is APPEND-ONLY (no update, no delete: history outlives its record's changes) — a new
hard-delete table needs a documented justification, exactly like Lettuce's exceptions list. Pure operational history with no reader once
old (job queues, samples) may be pruned by a fixed documented retention window, the same shape as the JWT blocklist's opportunistic
pruning of expired `revoked_tokens` rows. To add soft-delete to a new entity, follow the established pattern
(reference implementation: `users/UserService.kt`):

1. **Migration** — `marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE` in the CREATE (a retrofit
   adds the column plus `CREATE INDEX idx_<t>_marked_as_deleted ON <t>(marked_as_deleted);`).
2. **Exposed table** — declare the table `: UIntIdTable("…"), SoftDeletable` with
   `override val markedAsDeleted = bool("marked_as_deleted").default(false)`; the ONE `active()`
   predicate, `nowMillis()` and `lockActiveForWrite` come from `infra/db/SoftDelete.kt` (no
   private copies).
3. **Filter every read** — `read`, `list`, `count`, and any lookup (e.g. `findWithIdByEmail`) get
   `… and active()`. Apply it in the shared list predicate so the `count()` (total) and the row
   select stay consistent.
4. **`delete` flips the flag** — `update({ (id eq id) and (markedAsDeleted eq false) }) { it[markedAsDeleted] = true }`,
   returning the affected-row `Int`; guard `update` mutations the same way. The route maps
   `0 → 404` (the `orNotFound` helper), so a missing-or-already-deleted row is `404` (not `204`)
   and delete stays idempotent in effect — `UserService.deleteGuarded` shows the shape, adding the
   last-admin check inside the same transaction.
5. **Routes need no special-casing** — they key `404`/`204`/`NoContent` off the row-count and the
   `active()`-filtered `read`.

**Freeing a unique business field on delete.** To let a value be reused once its holder is
soft-deleted, use a **partial unique index** over active rows instead of a global `UNIQUE`:
`CREATE UNIQUE INDEX uq_<t>_<col>_active ON <t>(<col>) WHERE NOT marked_as_deleted;`. Skip the
Exposed `.uniqueIndex()` on that column (Exposed defs are query-only — the DB enforces it). A
clash with an **active** row still raises `23505 → 409` (mapped centrally in
`plugins/ErrorHandling.kt`, which names WHAT clashed per constraint — extend
`UNIQUE_CONSTRAINT_DETAILS` when adding a partial unique index). In place today: `users.email`
(`uq_users_email_active`, `V1`), the team name (`uq_teams_name_active`, `V6` — an expression
index over `LOWER(name)`) and the ADR name (`uq_adrs_name_active`, `V10`, "ADRs (V10)" below).

**`infra/db/Sql.kt`** (ported from Lettuce with the first list endpoint): `containsNormalized` —
the case- AND accent-insensitive substring filter over `public.unaccent` (V4), its LIKE metacharacters escaped in SQL after the fold; every per-column
substring filter MUST use it. Also `orVanished` (post-commit read-back guard → 500, for every
create that reads its row back).

### Row locks vs. FK locks

PostgreSQL's foreign-key check takes `FOR KEY SHARE` on the REFERENCED row for every child insert and keeps it until the
inserting transaction ends. A long transaction whose inserts reference a parent row therefore holds key-share locks on every parent it
touched for its whole run; `SELECT … FOR UPDATE` on that parent conflicts with `FOR KEY SHARE` and would wait behind it (Flow hit this
with its long-running derive job against team rows; the ADR writes — discussion entries and participants referencing an ADR — will be the same shape).

The rule: **lock a row `FOR NO KEY UPDATE` unless the write changes its key.** That mode conflicts with itself and with
`UPDATE`/`DELETE` (writers of the row, e.g. a soft delete racing a child insert, still serialize) but
NOT with `FOR KEY SHARE`, so FK locks never stall it. `lockActiveForWrite` (`infra/db/SoftDelete.kt`,
`FOR NO KEY UPDATE`), `TeamService.update` and `PasswordResetService.lockUser` (the active `users` row) follow it; none of them touches
`id`, and the unique indexes on these tables are partial or expression indexes (`uq_teams_name_active`, `uq_users_email_active`), which
PostgreSQL does not count as key columns — a rename is still a non-key update. Plain `FOR UPDATE` is for a row nothing references from a
long transaction (`users` is referenced only by columns no long transaction inserts — `updated_by_*`, `teams.manager_user_id`,
`user_disabled_features.user_id`, `password_reset_tokens.user_id` (short inserts only): `UserService.lockedActiveAdminCount`), the actor's `notification_email_budget` row (V21, referenced by nothing: `NotificationEmailBudget`), or for
`SKIP LOCKED` claims. A new `FOR UPDATE` on an FK-referenced row needs that justification in a comment. Flow pinned both halves with `RowLockVersusDeriveTest` (a writer completes while another transaction holds `FOR KEY SHARE` on the row, and a second `FOR NO KEY UPDATE` writer still waits); Hammurabi's is `AdrRowLockTest`: every ADR write (document PUT, transition, participants, delete) locks the `adrs` row `FOR NO KEY UPDATE`, because `adr_participants` and `adr_events` inserts reference it.

### JSON in stored columns

There is no `jsonb` column type and no canonical-JSON helper: `infra/db/Jsonb.kt` and `infra/json/CanonicalJson.kt` (Flow's, for stored payloads with a content hash) were deleted in the 1.0.0 checkup (C-SMELL-1) because nothing here stored such a payload. JSON lives in `TEXT` columns: `adr_events.params` (a string map, `infra/json/JsonParams.kt`) and `lenses.filters` (the list's filter document, `LensService`). A stored payload that needs a hash or a `jsonb` column ports the donor's implementation (Flow `infra/db/Jsonb.kt`, `infra/json/CanonicalJson.kt`) with it, including its U+0000 rule: PostgreSQL `jsonb` and `text` cannot store a NUL (SQLSTATE `22P05` / `22021`), so a free-text value bound for either must have it replaced (with U+FFFD, never dropped) before it is stored.

### Persisted enums

A Kotlin enum is stored by NAME in a `VARCHAR` with no CHECK (the V5 rule: the enum is the whitelist, so adding a value is code only). Renaming or removing a value is a DATA migration in the same commit: rewrite every place that holds the old name - the enum's own column (`adrs.status`, `adr_participants.role`, `adr_events.event_type` (an event type the history renders; an old row keeps its name for good), `notifications.notification_type`/`detail` and `user_notification_preferences.notification_type`/`channel` (a stored name this build does not know is invisible to its readers, never fatal), `adr_discussion.kind`/`status` (the wire calls the second `state`; the enum is `DiscussionState`, renamed in 1.0.0 with its values unchanged, so no data moved), `lenses.visibility`), the JSON of `adr_events.params`, and **`lenses.filters`**, whose `status` and `participantRole` keys hold the names inside a TEXT document no column type protects (rewrite it with a JSON-aware statement, and test it). A lens that slips through is read leniently (`LensService.decodeStoredFilters`: an unknown key is ignored, an unknown `status`/`participantRole` value is dropped, so the lens widens on that key until it is saved again) instead of failing the list with a `500`; every other column fails on an unknown name, so the rewrite is not optional.

**Rollback note (1.2.0).** `adr_events.event_type` gains `PREDECESSOR_ENDED` and `notifications.notification_type` gains `PREDECESSOR_ENDED` (additions, no migration). The notice side is harmless to an older build (an unknown notification type is invisible), but 1.1.x answers `500` on an ADR's history that holds an event type it does not know (`AdrEventType.valueOf`), so a rollback to 1.1.x first runs `DELETE FROM adr_events WHERE event_type = 'PREDECESSOR_ENDED'` (and may run `DELETE FROM notifications WHERE notification_type = 'PREDECESSOR_ENDED'` to tidy the bell). The successor links themselves are untouched by the feature, so there is nothing else to undo.

### ADRs (V10, V11, V12)

`adrs` (`adrs/AdrService.kt`), `adr_participants` (`AdrParticipantService.kt`), `adr_events` (`AdrEventService.kt`, V11) and `adr_discussion` (`AdrDiscussionService.kt`, V12); the domain is `adr-model.md`.

- **Ids.** `adrs.id` is `SERIAL` like `teams` (the `UIntIdTable` the whole feature template, `EventLog` and `lockActiveForWrite` share); the human-readable id is `"number"`, `ADR-` + the number zero-padded to four digits (`adrKey`). `adr-model.md`'s first sketch said UUID row keys; the foundation's every helper is `UInt`-keyed, so the row id stays an integer and the number is what people see.
- **The number counter and its lock.** ONE global counter: `MAX("number") + 1` over EVERY row (a soft-deleted ADR keeps its number; none is reused) taken under `pg_advisory_xact_lock(ADR_NUMBER_LOCK_KEY)` (the single-`bigint` form, the ASCII bytes of "ADRNUMBR"), the first statement of a create after its cheap reads. Transaction-scoped: released at commit or rollback, so a failed create never leaves the counter stuck. A row lock cannot serialise it (a create has no row yet, and `MAX` is no `FOR UPDATE`); a lock row in a one-row table would need a migration and the same wait. Writers of the counter queue only for one `INSERT` plus its participants and event. `uq_adrs_number` (unique over all rows) is the backstop: a bypass fails an insert instead of duplicating a number. Pinned by `AdrTest` (twelve parallel creates: distinct, gapless) and "never reused".
- **The name.** Unique among ACTIVE rows, case- and accent-insensitively: `uq_adrs_name_active` over `public.adr_name_key(name)` (`lower(public.unaccent('public.unaccent'::regdictionary, name))`) `WHERE NOT marked_as_deleted`. `unaccent` is only STABLE and an expression index needs IMMUTABLE, so the wrapper is declared `IMMUTABLE` BY PROMISE, the way Adder's search configuration is: a PostgreSQL major upgrade that changes the unaccent rules needs a `REINDEX` of that index. A clash is the central `23505` to `409` ("An ADR with this name already exists", `UNIQUE_CONSTRAINT_DETAILS`).
- **Dates.** `expires_on` is `VARCHAR(10)` holding a canonical ISO date (Covenant's date shape; `CHECK ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'`): it sorts chronologically, so the sweep compares text, and no date-time module joins the build. The Kotlin side parses it (`parseExpiresOn`: a real calendar date).
- **Roles.** `adr_participants (adr_id, user_id, role, added_at, added_by)`, primary key `(adr_id, user_id)` (one role per user), two PARTIAL unique indexes pin at most one `DRIVER` and one `APPROVER` per ADR; "exactly one Driver" is the service's (`validateParticipantSet`). A replace is a diff written delete-then-insert for the removed and the changed users (a changed row keeps its `added_at`/`added_by`), so a Driver handover or a Driver/Approver swap never trips the unique indexes half-done. `idx_adr_participants_user (user_id, role)` serves the list's `participant` filter.
- **History.** `adr_events (adr_id, user_id NULLABLE, created_at, event_type, params)`: the actor is NULL for a system event (the expiry sweep). `(adr_id, created_at, id)` serves the newest-first page. No CHECK on `event_type` or on `adrs.status` / `adr_participants.role` (the V5 rule: the Kotlin enums are the whitelists).
- **Revision.** `adrs.revision` guards the document PUT, the transitions, the participants PUT and (0.4.0) the tags and supersedes PUTs (`X-Expected-Revision`); every real change of any of them bumps it (a no-op set or link does not); an activation also bumps the superseded predecessor, and a detach bumps the (former) successor. The expiry sweep bumps it (a stale reader's next write conflicts).
- **Sweep index.** `idx_adrs_expiry (expires_on) WHERE status = 'ACTIVE' AND expires_on IS NOT NULL AND NOT marked_as_deleted` serves the candidate scan.
- **Discussion (V12).** `adr_discussion (adr_id, kind, status NULLABLE, body, resolution, author_id, created_at, edited_at, state_changed_by/at, marked_as_deleted, deleted_at)`; no CHECK on `kind`/`status` (the V5 rule), CHECKs on the
  body/resolution lengths and on a non-blank body unless deleted. A soft delete WIPES `body` and `resolution` and nulls `status`, `edited_at` and `state_changed_by/at` (`ck_adr_discussion_tombstone` pins it, with `deleted_at` stamped) (a tombstone is in no state, so no status filter and no count matches it; the text is gone from the store, not merely hidden).
  `idx_adr_discussion_adr_created (adr_id, created_at, id)` serves the oldest-first page; the PARTIAL `idx_adr_discussion_open (adr_id, kind) WHERE status = 'OPEN' AND NOT marked_as_deleted` serves the counts the ADR rows carry
  (`openIssueCount`, `unansweredQuestionCount`): one `GROUP BY adr_id, kind` per read or list page, whose predicate repeats the index's (`status = 'OPEN'` and `active()`) so the planner can use it. Every discussion write locks the `adrs` row
  `FOR NO KEY UPDATE` (the window is judged under it) and never bumps `adrs.revision`; `AdrRowLockTest` pins both halves.
- **Locking.** Every ADR write: `FOR NO KEY UPDATE` on the `adrs` row ("Row locks vs. FK locks"), the caller's role re-read under it, then state, revision, requirements, writes and events in ONE transaction.

### Tag categories and tags (V13)

`tag_categories` (`tags/TagCategoryService.kt`: name unique among ACTIVE rows case-insensitively, optional description, `revision BIGINT NOT NULL DEFAULT 1` - the `X-Expected-Revision` token of the category's PUT and DELETE, checked under the registry lock and bumped by every PUT and by the delete - `SoftDeletable`), `tags` (`category_id`, a normalised lower-case `name`, `marked_as_deleted`) and `adr_tags` (`adr_id` ON DELETE CASCADE, `tag_id` with no ON DELETE rule, `added_at/by`; primary key `(adr_id, tag_id)`; `idx_adr_tags_tag` serves the list's `tag` filter). The domain is `adr-model.md` "Tags".

- **A tag is a row, retired not erased.** `uq_tags_name` is unique over EVERY row, deleted ones included: removing a tag from a category (or deleting the category) sets `marked_as_deleted`, the ADRs keep their `adr_tags` row and read the tag as retired, and adding the name to a category again REVIVES the row (moving it to the new category) instead of minting a twin. "Each tag in exactly one active category" is therefore one row plus the unique name.
- **The registry lock.** Every registry write is `lockingTransaction` (`infra/db/Locking.kt`, Toadie's): READ COMMITTED after `LOCK TABLE tag_categories IN SHARE ROW EXCLUSIVE MODE`, which also covers an empty registry (a row lock would not) while plain SELECTs stay available. Registry writers queue on it, so a claim check (`applyTags`) and its writes are atomic; `uq_tag_categories_name_active` and `uq_tags_name` are the backstops (`23505` to `409`). An ADR tag write takes no registry lock: it reads the live names inside the ADR row lock, and a tag retired a moment later simply reads as retired - the same state a retirement leaves anyway.
- **Seed.** V13 inserts the Domain and Scope categories and their eight tags with the migration's own timestamp. Tests never mutate them (they name their own categories and tags).

### Successor links (V14)

`adrs.supersedes_id` (a self-reference, nullable, no ON DELETE rule: ADRs soft-delete; `ck_adrs_supersedes_self` refuses `= id`) on the SUCCESSOR. `uq_adrs_supersedes_open (supersedes_id) WHERE supersedes_id IS NOT NULL AND NOT marked_as_deleted AND status NOT IN (CANCELLED, WITHDRAWN, EXPIRED, SUPERSEDED)` allows one successor in play per predecessor (the terminal names are spelled in the predicate; they are the frozen `AdrStatus` values); `idx_adrs_supersedes` serves the reverse lookup ("who replaces this one?"). Two locks, in this order and never the other way (after the actor's mail-budget row, which is the FIRST lock of every write that can make notices): the `ADRLINKS` advisory lock (`ADR_LINK_LOCK_KEY`, `pg_advisory_xact_lock`, the first statement of a link write, because a cycle spans rows) and then the ADR row lock `FOR NO KEY UPDATE`. An activation takes only row locks: the successor's and the predecessor's TOGETHER in one `ORDER BY id` statement, so two writers touching the same pair cannot deadlock (two activations along a chain, B replaces A and C replaces B, share only B's row, always taken in id order - `AdrSuccessorDetachTest` runs them at once, with the chain in both id orders); it peeks the predecessor id without a lock first (a link change bumps the successor's revision, so a stale peek is a revision `409`). An **ending that replaces nothing** (1.2.0: a transition to `CANCELLED` or `WITHDRAWN`, and the expiry sweep's move to `EXPIRED`; `AdrStatus.endedUnreplaced`) takes the same three in the same order: the budget row (`reserve`, for the sweep the ended ADR's Approver), the link lock (`AdrSuccessorService.lockLinkWrites`), then the ADR's row and its pending successor's TOGETHER in one `ORDER BY id ... FOR NO KEY UPDATE` (`lockWithPendingSuccessor`; the pending successor is a plain read of the link, safe because every link write holds the link lock, and `recordPredecessorEnded` re-reads it under the locks before it writes `PREDECESSOR_ENDED` on it). So a link written concurrently either is seen by the ending move or finds a terminal predecessor (`400`), and an activation (row locks only, no link lock) takes the same pair in the same id order, so there is no cycle. The sweep now takes the link lock for every ADR it expires; it holds it for one short transaction. A DETACH (`DELETE /adrs/{id}/successor`, the predecessor's owner) takes the link lock first and then both rows in the same id order, clears the successor's `supersedes_id` and bumps the successor's revision. The cycle check walks UP the `supersedes_id` chain from the requested predecessor, one primary-key lookup per step (O(depth)), skipping a link whose successor is deleted, `CANCELLED` or `WITHDRAWN`.

### Successor acceptance (V19, 1.0.0)

`adrs.supersedes_accepted_id` (INTEGER, nullable, FK to `adrs`) on the SUCCESSOR beside `supersedes_id`: the predecessor the acceptance of its Approver (or ADMIN) was given FOR; the link is accepted when the two are equal, and `ck_adrs_supersedes_accepted CHECK (supersedes_accepted_id IS NULL OR supersedes_accepted_id IS NOT DISTINCT FROM supersedes_id)` refuses every other row (`IS NOT DISTINCT FROM`, because `=` against a cleared link is NULL and a CHECK passes NULL). So an acceptance belongs to ONE link: `AdrLinkWrites.writeLink` is the only writer of `supersedes_id` and always clears the marker in the same UPDATE (a re-point, an unlink and a detach each void it; the same link again writes nothing), `writeAcceptance` the only other writer (pinned by a source scan in `AdrSuccessorAcceptTest`, tests and fixtures included). **Upgrade rule:** the migration backfills `supersedes_accepted_id = supersedes_id` for the links already in effect (the successor holds its `SUCCESSOR_ACTIVATED` event; soft-deleted rows included) and leaves every pending link unaccepted, so after the upgrade each pending successor needs its predecessor's Approver before it can activate (release note); `SuccessorAcceptanceMigrationTest` pins it on a populated scratch database (V18 to V19: backfilled, pending, cancelled, unlinked, soft-deleted, an event with no link; no other column moves). **Rollback to 0.9.x:** safe, 0.9.x ignores the column (its reads name their columns and its writes never touch it); the cost is that 0.9.x activates unaccepted links again and, when it re-points, unlinks or detaches a link whose marker is set, trips `ck_adrs_supersedes_accepted` (a 500), so before rolling back clear the PENDING markers only: `UPDATE adrs SET supersedes_accepted_id = NULL WHERE NOT EXISTS (SELECT 1 FROM adr_events e WHERE e.adr_id = adrs.id AND e.event_type = 'SUCCESSOR_ACTIVATED')`. Clearing every marker would also erase the backfill of the links already in effect (an in-effect link keeps its marker: 0.9.x never touches it, since the successor is already active and its link frozen). Locking is unchanged: the accept takes the link advisory lock and then both rows in id order, like the detach and the activation (the activation reads the marker from its own locked row).

### Lens filter names (V20, 1.0.0)

A lens stores the list's own filter document, so renaming four list filters (`participant` -> `participantId`, `role` -> `participantRole`, `supersedes` -> `hasPredecessor`, `supersededBy` -> `hasSuccessor`) is a data migration of `lenses.filters` (TEXT): `V20__lens_filter_names.sql` rewrites every row that holds one of the four keys, soft-deleted rows included, through `jsonb_each` / `jsonb_object_agg` (values keep their JSON types, every other key stays), and leaves every document without an old key untouched (the WHERE), so those stay byte-identical; a rewritten document is re-spelled by Postgres (`{"a": 1}` spacing, key order) and decoded by the app all the same. The reader has no alias for the old keys. `LensFilterNamesMigrationTest` pins it on a populated scratch database (V19 to V20: all four keys, one key, none, a soft-deleted lens, a lens already in the new spelling; the rewritten document decodes as `AdrFilterDocument`). **A document holding both spellings** keeps the NEW key's value (`jsonb_object_agg` lets the last duplicate win, so the old-spelled entries are ordered first; `LensFilterNamesMigrationTest` pins it). **Rollback to 0.9.x:** STOP 1.0.0 first, then run the same statement reversed (the old and new names swapped; it is in the migration's header comment) BEFORE the old release starts, otherwise a 0.9.x reader ignores the new keys (it reads an unknown key leniently), and the lens lists but filters on fewer keys until saved again. **Rolling forward again:** Flyway does not re-run V20, so BEFORE starting 1.0.0 again run V20's forward statement by hand (it is idempotent: its WHERE skips every document already in the new spelling); lenses saved or reversed under 0.9.x would otherwise silently widen. Nothing else changed shape: the discussion's column `adr_discussion.status`, `idx_adr_discussion_open` and every audit and event-params key keep their names.

### Search and filters (V15)

Adder's V16 search column, ported to ONE table (`infra/db/Fts.kt`, `adr-model.md` "Search"). `CREATE TEXT SEARCH CONFIGURATION public.hammurabi_search (COPY = simple)` with `unaccent` (V4) as a filtering dictionary in front of `simple` for the six token types that can hold non-ASCII letters (`to_tsvector(regconfig, text)` is IMMUTABLE, so a generated column can use it where the STABLE `public.unaccent()` could not; no stemming and no stop words: ADR text mixes English and Polish). Then `adrs.search tsvector GENERATED ALWAYS AS (setweight(name, 'A') || setweight(problem || decision, 'B') || setweight(context || alternatives || consequences, 'C')) STORED` and a plain GIN index `idx_adrs_search` (a search matches the whole table, so unlike Adder's per-thread probe the index IS what serves it; `AdrSearchSchemaTest` pins `attgenerated = 's'` and the index). **`STORED` must be written**: on PostgreSQL 18 a bare `GENERATED ALWAYS AS (...)` is VIRTUAL and cannot be indexed. The `ALTER TABLE` is the backfill (a rewrite of `adrs`: an ADR log is thousands of rows, milliseconds). A word of 2,047 BYTES or more is skipped with a NOTICE; the fields are capped at 20,000 characters, far below the 1 MB limit of one tsvector.

- **Never in an Exposed table object.** `selectAll()` would read the vector on every ADR read and the driver has no `tsvector` codec: `Fts.kt` names the configuration and refers to the column as the free expression `adrs.search`; a plain Exposed `insert` answers `RETURNING *`, which would send the vector (about as large as the text) back on every create, so `AdrService.create` uses `insertReturning(listOf(Adrs.id))` (`RETURNING adrs.id`; `AdrSearchTest` pins it through the driver's query log).
- **Recompute runbook.** The configuration is immutable BY PROMISE. After it changes, or after a PostgreSQL major upgrade that changes `unaccent.rules`, the stored vectors AND the GIN index are stale until `ALTER TABLE adrs ALTER COLUMN search SET EXPRESSION AS (<the V15 expression>)` rewrites them (no `STORED`: the column already is; the index is rebuilt by it). A migration that alters the configuration runs that statement in the SAME file. `uq_adrs_name_active`'s `adr_name_key` wrapper has the same promise (a `REINDEX`).
- **The filter indexes** (same file): `idx_adrs_created_at (created_at)`, `idx_adrs_updated_at (updated_at)` (also the `updatedAt` sort) and `idx_adrs_expires_on (expires_on) WHERE expires_on IS NOT NULL`, all `AND NOT marked_as_deleted`. `hasOpenIssues` reads `adr_discussion` through the existing partial `idx_adr_discussion_open` (its predicate spelled as literals), `hasPredecessor` / `hasSuccessor` through `idx_adrs_supersedes`, `participantId` through `idx_adr_participants_user` and `tag` through `idx_adr_tags_tag`. The presence filters are `IN (sub-select)` / `NOT IN (sub-select)` over sub-selects that never hold a NULL.
- **Statement timeout.** A search arms `queryTimeout` per statement with the time left of ONE request deadline (`infra/db/SearchBudget.kt`; the only way a statement timeout may be set, "Statement timeouts"); the participants, counts and tags of the page are read in the same transaction after one arm.

### Lenses (V16)

`lenses` (`lenses/LensService.kt`, V16, Toadie's lenses ported): `name` (1-100), `visibility` PRIVATE/PUBLIC (no CHECK: the V5 rule), `filters` TEXT (the `AdrFilterDocument` as JSON, at most 20,000 characters, replaced whole on every save, validated by the list's own rules BEFORE it is stored), `created_by` (FK to `users`), `created_at/updated_at`, `marked_as_deleted`. The 50-lens cap is a count, so a create takes `pg_advisory_xact_lock(LENS_CAP_LOCK_KEY, userId)` (the two-`int` form, disjoint from the single-`bigint` ADR locks; per user, transaction-scoped) before counting - the row lock of an update cannot serialise a row that does not exist yet (`LensTest` races two creates at the 49th lens). `uq_lenses_owner_name_active (created_by, LOWER(name)) WHERE NOT marked_as_deleted` makes a name unique per owner and a soft-deleted lens frees it; `idx_lenses_public` serves "everyone's PUBLIC ones". Updates and deletes lock the row `FOR NO KEY UPDATE`. A filter document is plain TEXT, not `jsonb`: it is never queried, only round-tripped. A lens references a participant by user id and tags by name, with no FK: a lens outlives a deleted user or a retired tag and simply matches nothing.

### Notifications (V17, V18, V21)

`notifications` (`notifications/NotificationService.kt`): `recipient_id` (FK `users`, cascade), `notification_type` (NO CHECK: the Kotlin enum is the whitelist, the V5 rule; a name this build does not know is invisible), `actor_id` (nullable = the system), `adr_id` (FK `adrs`, cascade), `other_adr_id` (nullable; the successor of a successor notice), `detail` (one short enum-valued string: a role, a status, an entry kind or a date), `created_at`, `seen_at`, `emailed_at` (the digest's CLAIM, null until claimed; "settled, never mailed" is the settling run's `now` - the stamp the claim writes; a notice WITHHELD by the per-actor mail budget (V21) is stamped `created_at` at insert, the same "never mailed" meaning, as is the test fixture `settleNoticeBacklog()`'s). A notice is a POINTER, never text. **Delete class: HARD** - it goes with its ADR or user (cascade; both soft-delete today, so a soft-deleted ADR's notices are hidden by the list and removed by retention) and by retention (`notifications.retentionDays`, batches of 1,000 `FOR UPDATE SKIP LOCKED` off `idx_notifications_created`). Indexes: `idx_notifications_recipient (recipient_id, id)` (the list), `idx_notifications_unseen` (partial, the badge), `idx_notifications_created`, `idx_notifications_email_due` (partial: unseen and unclaimed, the digest's claim). `user_notification_preferences (user_id, notification_type, channel)`: the V5 disabled-set model (a row is a DISABLED pair; `channel` `IN_APP` or `EMAIL`, no CHECK; replaced wholesale; hard delete with the user). `adr_expiry_reminders (adr_id PK, expires_on, sent_at)` (V18): the reminder's sent marker, one row per ADR holding the date it was last reminded for; the upsert's `WHERE expires_on <> EXCLUDED.expires_on` decides, so a changed date re-arms it; hard delete with the ADR.

- **Made inside the write.** `AdrEventService.record` / `recordAll` offer the events of one write to `AdrNotices` after the event rows, in the same transaction, so a notice commits or rolls back with its change (`NotificationsTest`). The map from event to notice is the pure `noticeFor` (`NotificationPlan.kt`); the actor is taken out of the audience; the insert keeps only ACTIVE accounts that have not switched the kind's in-app channel off. **One statement per write**: the events of one write on one ADR (the creation's participants, a participants PUT's diff, a successor re-link) arrive together through `recordAll` and `NotificationService.makeAll` inserts all their notices with ONE `INSERT ... SELECT FROM unnest(...)` (parallel arrays; a missing second ADR is carried as 0 and a missing detail as `''`, so no array holds a null) `RETURNING recipient_id, id` (the id is what the mail budget stamps; the recipient is also how the reminder's audit counts the rows that really went in). The creation holds the numbering advisory lock, so its notices are one statement under it, not one per participant.
- **The per-actor mail budget (V21, 1.2.0, additive).** `notification_email_budget (actor_id BIGINT -> users ON DELETE CASCADE, day INTEGER, admitted INTEGER, exceeded_at BIGINT, PRIMARY KEY (actor_id, day))` plus `idx_notification_email_budget_day (day)`: one row per charged account and UTC epoch day holding how many notices were made eligible for the digest (at most `notifications.email.maxNoticesPerActorPerDay`); `exceeded_at` is the first withholding's instant (informational: the audit line is per withholding write). `NotificationService.makeAll` runs it INSIDE the write, after the notices' own `INSERT ... RETURNING recipient_id, id`: the first N notices of the day (ids ascending, i.e. plan order; a role audience is read Driver, Approver, Contributors, Informed, then user id) stay admitted and the rest are withheld by `UPDATE notifications SET emailed_at = created_at WHERE id = ANY(...)` (the "settled, never mailed" stamp; no new column on `notifications`, `seen_at` untouched). **Whose row:** the actor's; for a write with no actor (the expiry sweep, the reminders) the CURRENT Approver of the ADR (`adr_participants` joined to an active `users` row, read without a lock; `PREDECESSOR_ENDED`, written on the successor, is made in the transaction that ended the predecessor, which reserved for the ENDED predecessor's Approver, so `makeAll` charges that reserved account); no active Approver = exempt. **The row lock is a plain `FOR UPDATE` on purpose** ("Row locks vs. FK locks"): nothing references the row, so no foreign-key check can wait behind it. **Lock order: it is the FIRST lock of every write that can make notices** (`AdrNotices.reserve`, called by `AdrService.create` when it has participants, `update`, `transition` and `replaceParticipants`, the three link writes before the link advisory lock, the discussion post and state change, and the expiry sweep and the reminders for their Approver), taken BEFORE the ADR rows and the numbering (`ADR_NUMBER_LOCK_KEY`) and link (`ADR_LINK_LOCK_KEY`) advisory locks, and `makeAll` finds it already held. **The reservation is transaction-scoped** (`AdrNotices.reserve` records the charged account, or "exempt", and the UTC day in the transaction's own state, Exposed's per-transaction user data): `makeAll` charges exactly that account for exactly that day, so an Approver changed after the reservation (the sweep reserved A1, a participants PUT made it A2) is still charged as A1 and a write that straddles 00:00 UTC still charges the day it reserved; neither asks for another row late. A transaction that made notices without reserving (a path that forgot) falls back to a fresh look and logs a WARN (`NotificationEmailBudgetTest` drives every notice-making route and the two system passes and asserts none does). `reserve` also runs on writes that end up making no notice (a refused or no-op write; a refused one rolls the row's creation back), which the `adr-write` bucket bounds. Every notice-making transaction therefore takes the same order (budget row, then advisory lock, then ADR rows in id order, then history rows), so it sits in no cycle, and a write queued behind the account's other writes waits for this row alone, never while holding a global lock (`NotificationEmailBudgetTest`: a creation blocked on the row leaves the numbering lock free). The wait is **unbounded** (no `lock_timeout`): one account's notice-making writes serialise on their day's row, the worst case is that account's own in-flight writes (a few milliseconds each, holding a pooled connection while they wait; the `adr-write` bucket, 120 a minute, bounds the queue), and a timeout would turn a harmless queue of one person's own writes into failed writes. A writer that reserves and then fails (403, 409) rolls the row's creation back with it. Constant statement texts, every parameter cast. **Delete class: HARD** (a counter of the day, not a record): the notice retention pass (`purgeOlderThan`) ends by deleting the days before its cutoff's (a failure of that last step is logged and never fails the pass), and an account's rows go with the user row. It is deliberately NOT tied to the notices or the participants, so a removal or a re-add cannot reset a budget, and a rolled-back write rolls its spending back. Cross-feature: `notifications/` owns the table and touches no other feature's. `MigrationChecksumTest` pins the file.
- **The digest's claim** is Adder's (settle, claim per person `FOR UPDATE SKIP LOCKED`, stamp before send, release the unattempted), unchanged but for the columns; its statements run under a 10 s `queryTimeout` (the only way a timeout is set).

### Backup and restore

A manual runbook (checkup C-ARCH-2, decided 2026-11-21: no scheduled job; `BACKLOG.md` files running the drill nightly). The unit is `pg_dump -Fc`
of the `hammurabi` database, taken INSIDE the Postgres container/pod and streamed out, so the host needs no Postgres client and no password
(the image's local socket trusts the in-container user). `scripts/db-backup.sh [OUT.dump]` and `scripts/db-restore.sh DUMP.dump [--replace]`
(Adder's, ported) wrap it; both take the command that reaches the container from `HAMMURABI_PG_EXEC` (also `HAMMURABI_DB_USER`/`HAMMURABI_DB_NAME`,
default `hammurabi`/`hammurabi`; the value is word-split on purpose):

| Where | `HAMMURABI_PG_EXEC` |
|---|---|
| compose, default project | unset (default `docker exec -i hammurabi-postgres`) |
| compose, another project | `docker compose -p NAME exec -T postgres` (a container name changed by an override: `docker exec -i THAT-NAME`) |
| Kubernetes (`hammurabi` namespace) | `kubectl -n hammurabi exec -i deploy/postgres --` |

- **Which database.** Both scripts print their target command (`via: ...`) on stderr before they touch anything. A `HAMMURABI_PG_EXEC` that is
  set but EMPTY is refused (never read as unset, so a computed command that came out blank cannot fall back to the local default
  container and its real data; a deliberate departure from Adder's `:-`). The restore waits up to 30 s for a real query to answer (a fresh volume
  can pass the container healthcheck while the entrypoint's temporary init server is still up) before it looks at the database.
- **Backup** (live, the app may keep running: one transaction, a consistent snapshot): `scripts/db-backup.sh`, default name `hammurabi-<UTC>.dump`
  in the current directory (the ignore files cover `*.dump` and `*.dump.partial.*` only, so keep that suffix on a name you choose). The file is written `0600` (it holds every user, ADR and password hash; treat it
  like the database), never over an existing file, and only kept if `pg_restore --list` reads it and finds table data. The bare equivalent is
  `(umask 077; docker exec hammurabi-postgres pg_dump -U hammurabi -d hammurabi -Fc > hammurabi.dump)`.
- **Restore into an EMPTY database** (a new machine, a lost volume): bring up ONLY Postgres, restore, then start the app.
  Compose: `docker compose up -d --wait postgres && scripts/db-restore.sh hammurabi.dump && docker compose up -d app`.
  Kubernetes: scale the app to zero (`kubectl -n hammurabi scale deploy/app --replicas=0`), make sure the `postgres` pod runs on a fresh
  `postgres-data` claim (or restore with `--replace`), restore, scale the app back to 1. The app must NOT have booted against the empty database
  first: Flyway and the seed admin would have made it non-empty, and the script refuses (see below). On start the app finds the schema current
  (`Flyway applied 0 migration(s); schema at version 21`). The restore is one transaction (`--single-transaction --exit-on-error`): all or
  nothing, and `--no-owner --no-acl` leaves everything owned by the connecting role.
- **Safe by default:** the restore refuses unless the target has NO table, view or sequence outside the system schemas, no extension besides `plpgsql`, no schema besides `public` AND no other client is
  connected (`pg_stat_activity`: the app still running; `--force-connected` overrides, for a client you know is idle), and changes nothing when
  it refuses. `--replace` is the explicit way over a non-empty database and DESTROYS what is there: in ONE explicit transaction it drops every
  extension (bar `plpgsql`) and every non-system schema (`public`, `ops`; V4 puts `unaccent` in `public`, V9 creates `ops`) with `CASCADE`,
  recreates an empty `public`, and replays the dump rendered by `pg_restore -f -`, so the result is exactly the dump even when the live database
  is AHEAD of it (a newer table with a foreign key into `adrs`, a higher Flyway history row; `pg_restore --clean` would trip over the first and
  leave the second). The script is fed to `psql` without `-1` and ends in `COMMIT;`, so a cut stream rolls back instead of committing half a
  restore (the rendered SQL goes through a `0600` temp file first, so a failed `pg_restore` never reaches the database). A future migration that
  creates another schema or an extension needs no change here: the drop is by catalog, not by name. **Take a fresh `db-backup.sh` of the CURRENT state first** (a `--replace` cannot be undone). Stop the app first (compose
  `docker compose stop app`; Kubernetes scale to 0). To restore beside the data instead, make a scratch database
  (`HAMMURABI_DB_NAME=hammurabi_restored` after `createdb`) and compare.
- **Flyway:** the dump carries `flyway_schema_history`, so a restore brings the schema AND its version back. A dump restores into the SAME or a
  NEWER app version (Flyway migrates forward on boot: a 1.1.0 dump under a 1.2.0 app logs `Flyway applied 1 migration(s); schema at version 21`).
  An OLDER app against a newer database is not refused by Flyway (its default ignores `*:future` migrations; the config is unchanged), so never
  roll the app back past the schema without restoring a dump of that older schema (a history holding a value the older build does not know
  answers 500, "Persisted enums"). Restore into the same Postgres major (18): a dump from a newer `pg_dump` may not load in an older server.
- **Not in the dump** (keep each with the dump, STORED APART from it): `JWT_SECRET`, `DATA_ENCRYPTION_KEY` and `DATA_ENCRYPTION_KEY_PREVIOUS`
  (the Secret / compose env; no column is encrypted yet, but the day one is, a restored database is unreadable without the key that wrote it;
  `security.md` "Encryption at rest"), `POSTGRES_PASSWORD` (the role's password is a cluster property, not in a logical dump: the restored
  database takes the new cluster's role) and `ADMIN_INITIAL_PASSWORD`. In Kubernetes back up the `hammurabi-secrets` Secret separately
  (`kubectl -n hammurabi get secret hammurabi-secrets -o yaml`; it is as sensitive as the dump).
- **The seed admin.** A dump of a demo stack still holds the V3 seed hash (`admin@hammurabi.local` / `changeme`). Production refuses to start on
  it until `ADMIN_INITIAL_PASSWORD` rotates it at boot (`security.md` "Seed passwords"); set it for the restored deployment.
- **A restore turns back time.** Password changes and revocations after the dump are undone (`revoked_tokens`, `users.credential_revision`
  come back as they were), so a token revoked after the dump is valid again until it expires and a changed password reverts: after an incident
  rotate `JWT_SECRET` (every outstanding token dies) and have affected users reset their passwords. Notices the digest mailed after the dump are
  unclaimed again (`emailed_at` reverts) and are mailed a second time if still unseen; the per-actor mail budget's counters revert with them.
  In-memory state (lockouts, MFA codes, refresh rotations, rate buckets, export slots) starts empty.
- **Drill.** `node scripts/db-drill.mjs [--image TAG] [--keep]` proves all of the above on a throwaway compose project
  (`hammurabi-backup-drill`, own containers and volume, ports 8592 / 5943 / 8536, the pattern of `scripts/cds-classlist.mjs`; it never touches
  the default project: one `compose()` helper hard-codes `-p`, and each script call asserts the drill's `HAMMURABI_PG_EXEC` and the container's
  compose label first). It seeds through the API (users, a team, a tag category, an accented-name ADR taken to `ACTIVE` with participants, a
  comment, a lens), fingerprints the database (row counts per table, the Flyway maximum, md5 of the ordered `users`, `adrs`, `adr_events`),
  backs up (mode `0600`, a second run to the same path refuses), proves the restore refuses while the app runs and over a non-empty database,
  drops the volume, restores into a fresh Postgres (the fingerprint is equal, taken before the app starts because telemetry writes at boot),
  starts the app (`Flyway applied 0 migration(s)`, both logins, `GET /adrs?text=zolw` finds the accented ADR, a duplicate name is `409`, a new
  ADR takes the next number), runs `--replace` over a database AHEAD of the dump (an extra table with a foreign key into `adrs`, an extra ADR, a fake
  higher Flyway row: afterwards exactly the dump), and a forward migration (V21 undone by hand, i.e. a 1.1.0-shaped database; the boot logs
  `applied 1 migration(s); schema at version 21` and a notice-making write works). It exits non-zero at the first failed assertion and takes
  the project down (`down -v`; an image the drill built itself goes with it, one passed with `--image` is yours and stays), also on Ctrl-C or SIGTERM. In a git worktree build the image from a scratch clone and pass `--image`. Repeat the
  drill after a migration that adds a schema or an extension, and repoint its forward step (`FORWARD` in the script) at the newest migration.
- **Drilled** 2026-11-21, on the 1.2.0 tree (V1-V21, image built from a scratch clone of the commit before this one): `node scripts/db-drill.mjs` exited 0 with 32
  assertions (the final scripts; an earlier run of the first version also passed), steps 1 to 9 above, the compose path only. The Kubernetes path was NOT run end to end
  (no `hammurabi` namespace exists on the local cluster, and the manifests, the scale-down and the claim swap were not exercised); only the `kubectl exec` transport was
  exercised, with the final scripts against a bare `postgres` Deployment in a throwaway namespace (backup `0600`, restore into an empty database, the refusal over a
  leftover extension, `--replace`; the namespace is gone). Append a dated entry on every repeat; a failing drill is a failing backup.
