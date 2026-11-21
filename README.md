# Hammurabi

Hammurabi creates and manages **Architecture Decision Records** (ADRs) across their full lifecycle: a problem is raised, a solution is drafted, reviewed and decided
(`PROBLEM_RAISED` → `SOLUTION_DRAFT` → `IN_REVIEW` → `ACTIVE`); while the decision is in force people report how it works; eventually it expires or a successor replaces it.
Responsibility on every ADR follows **DACI**: one Driver, one Approver, any number of Contributors and Informed. Each ADR has a unique readable id, a name, the problem, its context,
the decision, the alternatives considered and the accepted consequences; every change lands in its dated history; ADRs are tagged, searchable by several criteria, discussed before
they are active (comments and questions) and challenged after (issues and escalations). The name is Hammurabi of Babylon, whose stele carried one of the oldest written codes:
decisions written down so that they bind and can be looked up.

## What's here today (1.1.0 — write and see)

Hammurabi's repository started as an exact copy of [Flow](https://github.com/liveweird/flow) (1.57.1, `4e43e480`), trimmed to its generic foundation (0.1.0); 0.2.0 built the ADR core on it, 0.3.0 added the discussion, 0.4.0 the tags and successors, 0.5.0 the search, 0.6.0 the notifications, 0.7.0 the send back and the withdrawal of a decision, 0.8.0 the Markdown export, 0.9.0 the decision log page, 1.0.0 the checkup (the hardening, the frozen API names, a decision in force ends only by its Approver) and 1.1.0 the visual Markdown editor and the Lifecycle diagram:

- **ADRs** (`/api/v1/adrs`; the list, create and detail pages): a gapless `ADR-0001` key, a name, the problem, context, decision, alternatives and consequences in Markdown, an expiry date, and the
  lifecycle `PROBLEM_RAISED` → `SOLUTION_DRAFT` → `IN_REVIEW` → `ACTIVE` (or `CANCELLED`, `WITHDRAWN`, and `EXPIRED` by a daily sweep). **DACI** decides who may do what — one Driver, one
  Approver, Contributors and Informed — and the server serves each caller's allowed moves, including **sending an ADR back** from review to the draft (the Approver or the Driver) and **withdrawing a decision** in force (the Approver), each with an optional reason the history shows. Content freezes once an ADR is active; concurrent edits are guarded by `X-Expected-Revision`; every
  change is an event in a paged, localized **history**. The list filters by name or key, status and the ADRs you take part in, with the filters in the URL.
- **Discussion** (`/api/v1/adrs/{id}/discussion`; the Discussion and Issues tabs of the ADR page): while an ADR is being drafted and reviewed anyone signed in can post a **comment** or ask a
  **question** (a question is marked answered by its author, the Driver, the Approver or an ADMIN); once it is active anyone can raise an **issue** or an **escalation**, which the Driver, the
  Approver or an ADMIN resolves (with an optional note) and reopens. Markdown bodies, authors edit their own, an author or an ADMIN deletes (a tombstone stays), everything stays readable in every
  status, and the ADR list counts the open issues.
- **Find** (`GET /api/v1/adrs`, `/api/v1/lenses`; the search box, the Filters panel and the lens picker of the ADR list): **full-text search** over the name, problem, context, decision, alternatives and
  consequences (accent- and case-insensitive, ranked by where the words occur, with highlighted snippets), next to filters for status, tags, participants and roles, created and updated date
  ranges, expiry, open issues and successor links; the whole filtered view lives in the URL, so it is a shareable link. **Lenses** save a combination of filters under a name, private or shared with everyone.
- **Notify** (`/api/v1/notifications`; the bell in the header and the Notification preferences page): being given or losing a DACI role, a status change, a comment, question, issue or escalation, an
  answer or resolution, an expiry or a supersede, a successor linked or detached, and an **expiry reminder** (`ADRS_EXPIRY_REMINDER_DAYS`, 14 by default, `0` off) reach the people it concerns, never
  the person who caused it. Every notice is made in the same transaction as the change; an **email digest** (`NOTIFICATIONS_EMAIL_*`) in the recipient's language follows what stays unseen, through
  the configured mailer, and each kind can be switched off for the bell and for email.

- **Export** (`GET /api/v1/adrs/{id}/export`, `GET /api/v1/adrs/export`; the Export Markdown button of the ADR page and the Export (ZIP) button of the list): any signed-in user downloads an ADR as one
  Markdown file in the [adr-tools](https://github.com/npryce/adr-tools) layout (status, context, decision, consequences, plus the alternatives, the DACI roles and the tags), named
  `ADR-0042-use-postgresql.md`, or a ZIP of every ADR the list's current search and filters select (at most 500) with an `index.md` in number order.

- **Decision log** (`GET /api/v1/adrs/overview`; the Decision log page, between Home and the ADRs): a read-only overview for every signed-in user: the number of ADRs per status, **what waits for a
  decision** (ADRs in review, longest wait first, with the ones waiting for you highlighted), **what expires soon** (`ADRS_OVERVIEW_EXPIRING_SOON_DAYS`, 30 by default), **what was decided recently**
  and **what is in force by tag category and tag**; every row opens its ADR and every "+N more" opens the ADR list filtered to what the section covers.

- **Sign-in** with a JWT access/refresh pair, a server-side revocation blocklist, per-account lockout and per-IP rate limits; opt-in **email MFA**; **self-service password reset** by an
  emailed single-use link.
- **Users** (ADMIN-managed, `/api/v1/users`) with per-user **feature flags** and a synced UI/email **language** (English and Polish).
- **Teams** — a flat registry with an optional manager.
- The React **shell**: navigation model, command palette, light/dark theme in the lapis lazuli Hammurabi brand with the "Stele" logo, a changelog, and the ADMIN **telemetry panel** (memory, JVM,
  requests and storage of the running process, 90 days of history).
- The quality gates, the Docker/Compose/Kubernetes deployment and a Playwright e2e suite, all inherited from Flow.

## Roadmap

0.2.0 ADR core (ids, lifecycle, DACI, history, expiry) · 0.3.0 discussion (comments, questions, issues, escalations) · 0.4.0 tags and successors · 0.5.0 find (full-text search, the remaining filters, URL-synced filters, saved lenses) · 0.6.0 notify (the bell, email digest, preferences, expiry reminders) · 0.7.0 send back and withdraw · 0.8.0 Markdown export · 0.9.0 the decision log page · 1.0.0 checkup · 1.1.0 write and see (the visual editor, the Lifecycle tab).
The scope of each version and the decisions behind it: `.claude/docs/product.md`; the domain model: `.claude/docs/adr-model.md`; open items: `BACKLOG.md`. `CLAUDE.md`'s "Product" and
"Donors" sections say which sibling project each capability ports from.

## The stack

- **Backend**: Kotlin + [Ktor](https://ktor.io) (Netty), JWT auth with refresh tokens and a
  server-side revocation blocklist, PostgreSQL with [Flyway](https://flywaydb.org) migrations and
  [Exposed](https://github.com/JetBrains/Exposed) (R2DBC), OpenTelemetry, RFC 7807 problem-detail
  errors, Swagger UI at `/openapi` (development mode). Application-level encryption at rest
  (`infra/crypto/FieldCipher`, AES-256-GCM) is in place for the first stored credential. One process serves everything.
- **Frontend**: [Vite](https://vite.dev) + React 19 + TypeScript + [Mantine](https://mantine.dev),
  react-i18next (English + Polish), a typed API client generated from the OpenAPI contract.
- **Quality gates**: detekt (zero findings), Kover coverage floors, dependency-family alignment,
  ESLint + sonarjs, knip, Vitest coverage floors, runtime OpenAPI conformance in the server test
  suite, and Playwright e2e with axe accessibility scans. Pushes to `master` and pull requests run
  the server/web gates and E2E static/setup checks; the full browser suite runs nightly and on
  demand.

## Running the whole stack (one command)

```bash
docker compose up --build
```

Then open <http://localhost:8090> and sign in as `admin@hammurabi.local` / `changeme`. This compose
file runs in development mode (`KTOR_DEVELOPMENT=true`), which tolerates the seed admin's
well-known `changeme` password (the V3 seed migration); any real deployment ships `KTOR_DEVELOPMENT=false` (the Docker
image's default) and **refuses to start** while any active account still carries that password —
set `ADMIN_INITIAL_PASSWORD` to rotate it at first boot instead (see "Configuration" below and
`.claude/docs/security.md`). Swagger UI: <http://localhost:8090/openapi>. Mailpit (reset and MFA
email): <http://localhost:8031>.

**Sample data.** `node scripts/sample-data.mjs` fills a fresh local stack with a year of demo content to assess the app with: 10 users
(`alice` and `oliver` are ADMINs; the shared password is `SAMPLE_PASSWORD` at the top of the script), 7 teams, tags, 16 ADRs in every
status with their discussion and history, and saved lenses. It goes through the API as each user (so every rule, event and notice is
real), restarts the app container once so the expiry sweep expires one ADR, and finally spreads the run's timestamps over the past year
in the database. It refuses anything but `localhost` and a stack that already has ADRs; start over with `docker compose down -v`.

Ports are chosen to coexist with the sibling stacks on the same machine (the full table is in
`.claude/docs/ci.md`): the app is on **8090**, Postgres is host-mapped to **5440**, the Vite dev server uses **5181**,
Mailpit **8031**. All host ports bind to 127.0.0.1.

On a cold start the app waits for Postgres rather than exiting: Flyway retries its boot connection
(`POSTGRES_CONNECT_RETRIES`, default 10 — about a minute of backing-off attempts; see "Configuration"),
so `docker compose up` and Kubernetes restarts need no manual ordering.

## Running on Kubernetes (local)

Two Deployments, each a single replica with `Recreate` updates (the old pod stops before the
new one starts, so an upgrade briefly interrupts it): `postgres` and `app` (the HTTP API and the SPA; one process). The application pod migrates on boot and runs in production mode
(`KTOR_DEVELOPMENT=false`), in the `hammurabi` namespace.

With a local cluster that shares the Docker image store (e.g. OrbStack):

```bash
HAMMURABI_IMAGE_TAG="dev-$(git rev-parse --short=12 HEAD)-$(date +%s)"
docker build -t "hammurabi-app:${HAMMURABI_IMAGE_TAG}" .
kubectl create namespace hammurabi
# create the hammurabi-secrets Secret — see the header comment in k8s/secret.yaml
./k8s/apply-local.sh "$HAMMURABI_IMAGE_TAG"
```

The helper renders the deployment image with the selected build tag and excludes the Secret
template. Use a new tag for each rebuild; do not apply `k8s/` directly, since the checked-in image
name is a placeholder. An existing installation already has the namespace and Secret.

The manifests are hardened: no ServiceAccount token in any pod, non-root/seccomp/`drop: [ALL]` on the
app and postgres containers, and `k8s/network-policies.yaml` — default-deny plus only
app → postgres:5432, DNS and public egress on 443 / 587, 465, 25 (SMTP), and 8090 into
the app from the ingress-nginx controller only. NetworkPolicy is enforced only by a CNI that implements it (OrbStack's cluster does). Details
in `.claude/docs/security.md` ("Kubernetes pod and network hardening"). CI validates every manifest and
the `apply-local.sh` render with `kubeconform` (`k8s-static` job).

**The front door is an ingress-nginx Ingress** (`k8s/ingress.yaml`). The `app` Service is `ClusterIP`, so
no Service path reaches the pod except the Ingress, and `allow-app-ingress` admits only the ingress-nginx
controller's pods (a node-local or `hostNetwork` process and `kubectl port-forward` can still reach it, as in any cluster). The cluster needs the ingress-nginx controller in its `ingress-nginx` namespace (not part of
`k8s/`; community ingress-nginx was retired in March 2026, so this is a local reference, not a production
recipe). Then open <https://hammurabi.k8s.orb.local> (OrbStack resolves `*.k8s.orb.local` ingress hosts). With no
`secretName` under `tls:` the controller serves its self-signed fake certificate, so the browser warns once;
plain HTTP is answered with a `308` to https.

- **Install the controller once per cluster** (OrbStack: enable Kubernetes first, `orbctl config set k8s.enable true`, then restart
  OrbStack once). Pinned to the last community release; the manifest pins the controller image by digest:
  `kubectl --context orbstack apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.15.1/deploy/static/provider/cloud/deploy.yaml`, then
  `kubectl --context orbstack -n ingress-nginx wait --for=condition=Ready pod -l app.kubernetes.io/component=controller --timeout=240s`.
  Undo: the same command with `delete -f` (removes the `ingress-nginx` namespace, its cluster RBAC, the `nginx` IngressClass
  and the admission webhook). The cloud-provider manifest creates a `LoadBalancer` Service with `externalTrafficPolicy: Local`;
  OrbStack gives it an address of its own and resolves `*.k8s.orb.local` ingress hosts to it.
- The app trusts exactly one `X-Forwarded-For` hop (`HTTP_PROXY_HOPS=1`): ingress-nginx's defaults replace
  the header with the address it saw, and the NetworkPolicy keeps every other pod away from the app, so a
  client-sent `X-Forwarded-For` never becomes the rate-limit key (`.claude/docs/security.md`).
- **OrbStack shows every client as one address.** The controller sees the pod-network gateway as the peer of every
  request, even with `externalTrafficPolicy: Local`, so the app's per-IP rate-limit buckets are shared by everyone on the
  cluster. Fine for one local user; there is no OrbStack-side fix (the audit trail carries no client address by design,
  so check the controller's access log, whose first field is that peer). A real cluster keeps the browser's address when
  its load balancer preserves it.
- A real host needs three edits in step: the host in `ingress.yaml` (twice), a certificate Secret named under
  `tls: secretName` whose SAN has that host, and `MAIL_APP_URL` in `app-deployment.yaml` when mail is on. CORS
  stays empty (single origin).
- `kubectl -n hammurabi port-forward svc/app 8090` bypasses the Ingress for debugging: production mode answers
  plain HTTP with its `301` to https there. For throwaway plain-HTTP testing, edit `k8s/app-deployment.yaml`
  yourself — `apply-local.sh` only substitutes the image tag — setting `KTOR_DEVELOPMENT` to `"true"` (and
  `HTTP_BEHIND_PROXY` to `"false"`), and never commit or apply that in a shared or production
  cluster. Development mode is a different posture, not just "HTTP allowed": it also tolerates
  the burned demo credentials, exposes `/openapi` and the Swagger UI, and lifts the login per-IP
  bucket from 10/min to 1000/min when it is left blank.

## Local development

Run each long-lived process in a separate terminal from the repository root:

```bash
docker compose up postgres mailpit   # database on :5440 and local mail on :8031
./gradlew :server:run                # API on :8090
(cd web && npm install --legacy-peer-deps && npm run dev)    # SPA on :5181
```

The local JDK and Node runtime are pinned in `mise.toml` (Temurin 21 and Node 24 LTS). Run
`mise install` and use `mise exec -- <command>` or activate mise in your shell. If `./gradlew`
resolves the wrong JDK (a system JRE ahead of the mise shim on `PATH`), point it explicitly:
`JAVA_HOME=$(mise where java) ./gradlew build`. If server tests need a Docker daemon and you use
OrbStack without `/var/run/docker.sock`, export `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`
first. See [dependency maintenance](.claude/docs/dependencies.md) for compatibility pins,
automated updates, and runtime verification. The gates, timed the way CI runs them: `scripts/gates.sh [server|web|e2e|all]`.

## Configuration (environment variables)

`server/src/main/resources/application.yaml` is the authoritative reference — every setting there
is a `$VAR:default` pair, and the table below lists them (defaults as shipped; the
Docker image additionally sets `KTOR_DEVELOPMENT=false`, `MAIL_TRANSPORT=disabled` and
`WEB_STATIC_DIR=/app/web`). Production mode refuses the burned demo keys and the `log` mail
transport at startup (`.claude/docs/security.md`).

JVM options are not in the table: the image bakes its heap/GC flags (`server/build.gradle.kts`), so size a deployment with
`JAVA_OPTS` (the baked heap is `-Xmx256m`). Leave `SERVER_OPTS` unset — the image uses it to switch on its
class-data-sharing (AppCDS) archive, and setting it drops the archive. Build-time identity for the telemetry panel: the image build args `HAMMURABI_BUILD_VERSION` and `HAMMURABI_BUILD_COMMIT`.

| Variable | Default | Purpose |
|---|---|---|
| `KTOR_DEVELOPMENT` | `true` | Development mode (`true`) vs production mode — HSTS/HTTPS redirect and the fail-closed startup checks. The image ships `false`. |
| `KTOR_STARTUP_TIMEOUT_MILLIS` | `140000` | How long Ktor waits for every module (Flyway included) to start. A database whose migrations outrun it cannot boot: raise it together with the k8s startupProbe's `failureThreshold` (`k8s/app-deployment.yaml`), never one alone. |
| `LOGIN_LOCKOUT_THRESHOLD` | `5` | Consecutive failures per account before `/login` answers 429. |
| `LOGIN_LOCKOUT_DURATION_SECONDS` | `900` | How long a locked account stays locked. |
| `LOGIN_LOCKOUT_MFA_MAX_DURATION_SECONDS` | `86400` | Ceiling of the progressive lock: a lock tripped by wrong emailed codes doubles per consecutive such lock (15 min, 30, 60, ...) up to this; wrong passwords keep the flat duration. |
| `LOGIN_LOCKOUT_MFA_DECAY_SECONDS` | `86400` | How long after the last escalated lock ends its level is forgotten, and the period of the per-account wrong-code counter (a fixed window from its first failure: threshold wrong codes inside it trip an escalated lock even when paced). A successful sign-in or a confirmed reset forgets both at once. |
| `LOGIN_LOCKOUT_MAX_TRACKED` | `10000` | Maximum in-memory login identities; new identities receive 429 at capacity while active counters and locks remain. |
| `LOGIN_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP login bucket; blank follows the mode (10 prod / 1000 dev). |
| `REFRESH_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP refresh bucket (blank = 30). |
| `READY_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP bucket on `GET /api/v1/ready` (blank = 60; the kubelet probes 12/min). |
| `PASSWORD_RESET_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP reset bucket; blank follows the mode (5 prod / 100 dev). |
| `REFRESH_REPLAY_GRACE_SECONDS` | `60` | How long a rotated refresh token may be replayed (a lost `/refresh` response, two racing tabs) before the replay is a 401; `0` = strict one-time use. In-memory, per instance (`.claude/docs/security.md` "Token storage"). |
| `PASSWORD_RESET_MIN_INTERVAL_SECONDS` | `60` | One reset request per submitted email per interval. |
| `PASSWORD_RESET_MAX_TRACKED` | `10000` | Maximum in-memory reset cooldowns; new identities receive 429 at capacity. |
| `PASSWORD_RESET_TOKEN_TTL_SECONDS` | `900` | Lifetime of the emailed single-use reset link, 1–3600 seconds. |
| `MFA_CODE_TTL_SECONDS` | `300` | Lifetime of an emailed MFA code. |
| `MFA_MAX_ATTEMPTS` | `5` | Wrong-code attempts before a challenge dies. |
| `MFA_MAX_TRACKED` | `10000` | Maximum pending email-MFA challenges; new issuance receives 429 at capacity. |
| `DATA_ENCRYPTION_KEY` | *(dev key, burned)* | AES-256-GCM key (64 hex) for stored credentials (none stored yet; reserved for the first stored credential) — the dev default is burned, production refuses it. Back it up apart from the database. |
| `DATA_ENCRYPTION_KEY_PREVIOUS` | *(blank)* | Decrypt-only fallback during a key rotation (boot once, then remove). |
| `SECURITY_CSRF_ENABLED` | `false` | CSRF plugin gate — off (bearer JWT, no cookies). |
| `JWT_SECRET` | `secret` | HMAC key for the access/refresh pair — production requires a private 64-hex key (`openssl rand -hex 32`); the placeholders and compose demo key are burned. |
| `JWT_ISSUER` | `http://0.0.0.0:8090/` | The `iss` claim the verifier requires. |
| `JWT_AUDIENCE` | `hammurabi-api` | The `aud` claim the verifier requires. |
| `JWT_REALM` | `hammurabi-api` | The `WWW-Authenticate` realm. |
| `JWT_ACCESS_EXPIRES_IN_SECONDS` | `900` | Access-token lifetime (the API bearer). |
| `JWT_REFRESH_EXPIRES_IN_SECONDS` | `3600` | Refresh-token lifetime — the idle-session window. |
| `ADMIN_INITIAL_PASSWORD` | *(blank)* | Rotates the V3 seed admin's `changeme` at startup while it still carries the seed hash; production rejects known placeholders and passwords outside the ordinary account limits. |
| `HTTP_BEHIND_PROXY` | `false` | Honour `X-Forwarded-*` from a TLS-terminating proxy (rate-limit keys, HTTPS redirect). |
| `HTTP_PROXY_HOPS` | `1` | Trusted proxies in front of the app when `HTTP_BEHIND_PROXY=true`; `X-Forwarded-For` is trusted from the END of the list (1 = the last proxy's value). |
| `CORS_ALLOWED_HOSTS` | *(blank)* | Comma-separated cross-origin hosts; blank = CORS not installed. |
| `HTTP_EXPOSE_OPENAPI` | *(blank)* | Serve Swagger UI + the spec at `/openapi`; blank follows the mode. |
| `METRICS_REPORT_INTERVAL_SECONDS` | `300` | How often the Dropwizard metrics registry is dumped to the `metrics` logger: `0` = off, otherwise 30..3600 seconds (`.claude/docs/observability.md`). |
| `TELEMETRY_ENABLED` | `true` | The per-process telemetry sampler (JVM, container memory and connection-pool figures into the `ops` schema, 7 days raw and 90 days hourly): `false` starts none (`.claude/docs/observability.md` "Telemetry"). |
| `TELEMETRY_SAMPLE_INTERVAL_SECONDS` | `300` | How often a process samples itself, 60..1800 and a divisor of 3600. |
| `TELEMETRY_STORAGE_INTERVAL_SECONDS` | `3600` | How often database/schema sizes are measured, 900..86400. |
| `ADRS_EXPIRY_ENABLED` | `true` | The ADR expiry sweep: moves every `ACTIVE` ADR whose expiry date is before today (UTC) to `EXPIRED` (`.claude/docs/adr-model.md` "Expiry"); `false` starts no loop. |
| `ADRS_EXPIRY_INTERVAL_SECONDS` | `3600` | How often the sweep runs, 1..86400 (validated on every boot). |
| `ADRS_EXPORT_MAX_ADRS` | `500` | The most ADRs one bulk export (`GET /api/v1/adrs/export`) holds; over it the export answers `400` before the first byte (`.claude/docs/adr-model.md` "Export"). |
| `ADRS_EXPORT_MAX_CONCURRENT` | `2` | The most exports one process streams at once; past it the export answers a typed `429`. |
| `NOTIFICATIONS_RETENTION_DAYS` | `90` | How long a notice is kept before the sweep deletes it, 1..3650. |
| `NOTIFICATIONS_SWEEP_INTERVAL_SECONDS` | `60` | How often the notification sweep wakes (reminders, the digest, the purge), 10..3600. |
| `NOTIFICATIONS_SWEEP_ENABLED` | `true` | `false` starts no notification sweep (the test harness); the keys are validated either way. |
| `NOTIFICATIONS_EMAIL_DELAY_SECONDS` | `300` | A notice still unseen after this many seconds is mailed in the digest, 0..86400 (0 = at the next tick); with `MAIL_TRANSPORT=disabled` no digest is sent and the bell is unaffected. |
| `NOTIFICATIONS_EMAIL_MIN_INTERVAL_SECONDS` | `900` | At most one digest email per person per this many seconds, per process, 0..86400 (0 = no spacing). |
| `NOTIFICATIONS_EMAIL_MAX_PER_RUN` | `100` | The most digest emails one sweep tick sends (the mailer is shared with MFA codes and password resets), 1..1000. |
| `SEARCH_TIMEOUT_SECONDS` | `5` | The deadline of one full-text search request, 1..30; past it the search answers the typed `429` `search-timeout` (`.claude/docs/security.md` "Search"). |
| `SEARCH_MAX_CONCURRENT` | `4` | The most full-text searches one process runs at once, 1..16 (production boot refuses a value that reaches the pool size); past it the typed `429` `search-busy`. |
| `WEB_STATIC_DIR` | *(blank)* | Directory of the built SPA to serve; blank in local dev (Vite serves it). The image sets `/app/web`. |
| `MAIL_TRANSPORT` | `log` | `log` (dev only — production refuses it), `smtp`, or `disabled` (email features answer 503; the image default). |
| `SMTP_HOST` | *(blank)* | SMTP server (required for `smtp`). |
| `SMTP_PORT` | `587` | SMTP port. |
| `SMTP_USER` | *(blank)* | SMTP user (optional). |
| `SMTP_PASSWORD` | *(blank)* | SMTP password (optional). |
| `SMTP_STARTTLS` | `true` | STARTTLS on the SMTP connection. |
| `MAIL_FROM` | `hammurabi@localhost` | Sender address of every outbound email. |
| `MAIL_APP_URL` | *(blank)* | The deployment's public **origin** (`https://host[:port]` in production — `http` only in development; no path, query or credentials) — the self-service password reset emails a link built from it. A malformed value refuses startup; blank = the reset answers 503. |
| `POSTGRES_JDBC_URL` | `jdbc:postgresql://localhost:5440/hammurabi` | Flyway's JDBC URL. |
| `POSTGRES_R2DBC_URL` | `r2dbc:postgresql://localhost:5440/hammurabi` | The runtime R2DBC URL. |
| `POSTGRES_USER` | `hammurabi` | Database user. |
| `POSTGRES_PASSWORD` | `hammurabi` | Database password. Production mode (`KTOR_DEVELOPMENT=false`) **refuses to start** on this default, on a blank value and on the k8s `CHANGE-ME` placeholder: set a private one on the database and the app (`openssl rand -hex 16`). The compose demo runs in development mode and keeps `hammurabi`. |
| `POSTGRES_CONNECT_RETRIES` | `10` | How many times Flyway retries its boot connection (doubling waits capped at 8 s, ~63 s at 10) so a cold start waits for Postgres instead of crash-looping; `0` fails fast (0..15 — 15 is ~2 min, inside the k8s startup-probe budget). |
| `POSTGRES_POOL_MAX_SIZE` | `20` | Ceiling on concurrent pooled R2DBC connections for this instance (1..1000). |
| `POSTGRES_POOL_INITIAL_SIZE` | `2` | Connections the pool fills up to on its first acquire (0..maxSize). |
| `POSTGRES_POOL_MAX_ACQUIRE_SECONDS` | `10` | How long a caller waits for a free pooled connection before failing (1..600). |
| `POSTGRES_POOL_MAX_IDLE_SECONDS` | `600` | How long an idle pooled connection may sit before recycling (1..86400). |
| `POSTGRES_POOL_IDLE_IN_TRANSACTION_TIMEOUT_SECONDS` | `300` | PostgreSQL's `idle_in_transaction_session_timeout` on every pooled connection: a session left idle inside an open transaction is ended and its locks released (0 = off, else up to 86400; `.claude/docs/persistence.md` "Connection pool"). |

## Useful Gradle tasks

| Task | What it does |
| ---- | ------------ |
| `./gradlew build` | Compiles everything and runs every gate: detekt, tests (Testcontainers), Kover verify, dependency alignment |
| `./gradlew :server:run` | Runs the API against the compose Postgres |
| `./gradlew :server:test` | Server test suite (needs a Docker daemon for Testcontainers) |
| `./gradlew detekt` | Static analysis only |
| `./gradlew :server:checkDependencyAlignment` | One version per aligned dependency family on the runtime classpath |
| `./gradlew :server:installDist` | Builds the runnable distribution (used by the Docker image; never `buildFatJar`) |

Frontend: `cd web && npm run build | lint | test | test:coverage | knip | gen:api`.
E2E: `cd e2e && npm ci && npx playwright install chromium && npm test`.

## License

MIT — see [LICENSE](LICENSE).
