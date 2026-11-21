---
name: run-stack
description: How to run, package, and deploy the Hammurabi stack — docker compose, local dev (gradle + vite), local Kubernetes (OrbStack), installDist packaging, and JVM memory tuning.
---

# Running, packaging, and deploying the Hammurabi stack

## Packaging

Package the server for deployment with `./gradlew :server:installDist` (output under
`server/build/install/server/`, launcher `bin/server`). **Do not use `:server:buildFatJar`** —
the shadow plugin collapses the duplicate
`META-INF/services/org.flywaydb.core.extensibility.Plugin` descriptors and the fat JAR NPEs at
startup inside Flyway's plugin registry. `installDist` keeps each dependency JAR separate, so
Flyway's `ServiceLoader` discovery works exactly as under `:server:run`.

## JVM footprint tuning

JVM footprint tuning is baked into the `application {}` block in `server/build.gradle.kts` via
`applicationDefaultJvmArgs` = `-XX:+UseSerialGC -Xmx256m -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError`
plus the heap/thread sizing `-Xmn64m -XX:SurvivorRatio=2 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30
-XX:-ShrinkHeapInSteps -XX:ActiveProcessorCount=2`, so it flows
into both `bin/server` (-> Docker image) and `:server:run` (the Gradle `test` task is unaffected). The image
also sets `MALLOC_ARENA_MAX=2` (Dockerfile `ENV`; compose and k8s inherit it). These flags were measured in the
donor project; re-measure when Hammurabi carries real data. SerialGC removes G1's per-heap overhead (~75 MiB);
`-Xmx256m` caps a heap that holds no large caches; C1-only (`TieredStopAtLevel=1`) trims code-cache + C2-compiler
memory (~50 MiB) at the cost of peak CPU-bound throughput (**remove that flag if the service ever runs hot**);
`-XX:+ExitOnOutOfMemoryError` makes a heap OOM end the process (a half-dead process that compose/k8s never restarted
is worse than a restart; `restart: unless-stopped`/k8s restarts it); `-Xmn64m -XX:SurvivorRatio=2` fix the young
generation; the `Min/MaxHeapFreeRatio` 10/30 and `-ShrinkHeapInSteps` let a full collection shrink the heap back
(**never set `-XX:+DisableExplicitGC`**); `-XX:ActiveProcessorCount=2` stops the JVM sizing Netty/reactor/ForkJoin
pools from every core of the node. **AppCDS:** the Docker image also carries a class-data-sharing
archive (`/app/cds/hammurabi.jsa`, dumped at image build from the committed class list `server/cds/classlist.txt`) that the
image turns on through `ENV SERVER_OPTS=-XX:SharedArchiveFile=/app/cds/hammurabi.jsa`. It is image-only: `:server:run` and
the Gradle `test` task run without it (a missing archive file would switch CDS off altogether, so the flag is NOT in
`applicationDefaultJvmArgs`), and a mismatching archive (another JVM, a changed jar) degrades to a normal start with a
`[warning][cds]` line. `SERVER_OPTS=` (empty) turns it off. Regenerate the class list (a stale one only costs startup and
metaspace; `CdsClassListTest` trips on gross drift) with `node scripts/cds-classlist.mjs` (builds its own image from the
tree under its own compose project, tears itself and that image down; it writes the list only from a healthy run; in a git
worktree, whose `.git` file breaks `COPY .git`, build the image from a scratch copy and pass `--image <tag>`).
`scripts/check-cds-archive.sh <image>` proves an image's archive fits its runtime (CI's `images` job runs it; any change of
the baked JVM flags that alters pointer layout, e.g. `-XX:-UseCompressedOops`, fails it).
Override per-deploy with `JAVA_OPTS` (`SERVER_OPTS` is the image's AppCDS hook; setting it replaces that flag) — the
Gradle-generated launcher script (`bin/server`) assembles the JVM command line as
`$DEFAULT_JVM_OPTS $JAVA_OPTS $SERVER_OPTS`, so a `JAVA_OPTS`-supplied flag comes AFTER the baked
`applicationDefaultJvmArgs` on the same `java` invocation and the JVM takes the LAST `-Xmx` it sees. Container ceilings:
`resources.limits.memory: 512Mi` (request `320Mi`) in `k8s/app-deployment.yaml`, and `mem_limit: 512m` for the `app` service
in `docker-compose.yaml` (one process). **`JvmMemoryFlagsTest` pins all of this** (a file-parsing test, no
Docker): every compose service and k8s container has a memory limit; the effective `-Xmx` plus 256 MiB fits the limit;
`-XX:+ExitOnOutOfMemoryError` is on and `-XX:+DisableExplicitGC` set nowhere; the Dockerfile has `MALLOC_ARENA_MAX=2` and the
only `SERVER_OPTS`; `terminationGracePeriodSeconds` (45 s) outlasts the shutdown budget. So "raise `-Xmx` and the limit
together" fails the build otherwise.

## One process

Hammurabi v0.1.0 runs a single application process: the HTTP API and the SPA. There is no `ROLE` switch and no
second Deployment — `k8s/app-deployment.yaml` is the only application workload and sits behind `k8s/app-service.yaml`.
A role split returns only with the first feature that needs a separate process.

## Running the full stack

Two ways to run, sharing the same `docker-compose.yaml`. Hammurabi's ports (server **8090**, Postgres **5440**, Mailpit UI
**8031**, Vite **5181**) deliberately avoid the other local stacks' (the table in `.claude/docs/ci.md`), so several stacks can run side by
side on one machine; host ports bind to 127.0.0.1:

- **One command (clone & run / demo):** `docker compose up --build` builds the SPA and the
  server, starts PostgreSQL, runs Flyway on boot, and serves everything at
  `http://localhost:8090` (Swagger at `/openapi`; sign in as `admin@hammurabi.local` / `changeme`;
  Mailpit at `http://localhost:8031`). Tear down with `docker compose down` (add `-v` to drop the
  DB volume).
- **Local development (hot reload):** in separate terminals, run
  `docker compose up postgres mailpit` (Postgres host port **5440** -> in-container 5432),
  `./gradlew :server:run`, and `cd web && npm run dev` (Vite on **5181**, proxying `/api` ->
  **8090**).

- **Local Kubernetes (OrbStack):** give each local build a unique tag, for example
  `HAMMURABI_IMAGE_TAG="dev-$(git rev-parse --short=12 HEAD)-$(date +%s)"`, then run
  `docker build -t "hammurabi-app:${HAMMURABI_IMAGE_TAG}" .`. Create the `hammurabi` namespace and
  `hammurabi-secrets` Secret (command in `k8s/secret.yaml`), then run
  `./k8s/apply-local.sh "$HAMMURABI_IMAGE_TAG"`. The script renders the Deployment
  (`k8s/app-deployment.yaml`) with that tag *before* applying any
  resource and deliberately excludes the Secret template. The checked-in `:local-build` name is a
  render placeholder; never apply the directory directly. A unique tag makes
  `imagePullPolicy: IfNotPresent` deterministic while OrbStack's cluster shares Docker's image
  store, so no push is needed. Everything lives in the dedicated namespace so it coexists with
  other local stacks. PostgreSQL uses an exact version and multi-platform manifest
  digest, its argv-list exec probe also serves as the readiness gate. The app runs in production mode
  (`KTOR_DEVELOPMENT=false`, `HTTP_BEHIND_PROXY=true` — it expects the ingress-nginx Ingress, `k8s/ingress.yaml`, to set
  `X-Forwarded-For`/`-Proto`) behind `k8s/app-service.yaml` (a `ClusterIP`: the Ingress is the only way in, the NetworkPolicy admits only the
  controller's pods; `kubectl -n hammurabi port-forward svc/app 8090` bypasses both and answers plain HTTP with the 301 to https unless the
  request sets `X-Forwarded-Proto: https`, so it is for debugging, never for a browser session), one replica (the login lockout, MFA and reset throttles are
  per-instance in-memory state; CI pins `replicas: 1`). `k8s/network-policies.yaml` (default-deny + the few allowed flows)
  is applied with the rest and OrbStack's cluster enforces it: an in-cluster SMTP relay is blocked until you add an
  egress policy for it (`.claude/docs/security.md`, "Kubernetes pod and network hardening"). For throwaway plain-HTTP
  local-cluster testing, flip `KTOR_DEVELOPMENT` to `"true"` in the file. **Teardown:** retain database data by scaling the
  deployments to zero, or delete the deployments, services, network policies and PVC-holding resources explicitly.
  **Secrets:** the deployment consumes the `hammurabi-secrets` Secret via `secretKeyRef` (`JWT_SECRET`,
  `POSTGRES_USER`/`POSTGRES_PASSWORD`, `DATA_ENCRYPTION_KEY`, `ADMIN_INITIAL_PASSWORD`, optional
  `SMTP_USER`/`SMTP_PASSWORD`); `k8s/secret.yaml` is a placeholder **template** — applying it
  verbatim fail-closes at startup (the placeholder JWT value is on the burned list in
  `plugins/Security.kt`).

- **Backup and restore:** `scripts/db-backup.sh [OUT.dump]` and `scripts/db-restore.sh DUMP.dump [--replace]` (dump taken inside the
  Postgres container; the restore refuses a non-empty database without `--replace`). Default target is the compose container
  `hammurabi-postgres`; point `HAMMURABI_PG_EXEC` elsewhere (`docker compose -p NAME exec -T postgres`, or
  `kubectl -n hammurabi exec -i deploy/postgres --`). Restore into a stack where ONLY Postgres is up, then start the app. The drill is
  `node scripts/db-drill.mjs [--image TAG] [--keep]`: a throwaway compose project `hammurabi-backup-drill` (ports 8592 / 5943 / 8536,
  the pattern of `scripts/cds-classlist.mjs`), never the default project; in a git worktree build the image from a scratch clone and
  pass `--image`. Never run these scripts against the default stack to "try them": it holds real data. Runbook:
  `.claude/docs/persistence.md` "Backup and restore".

The root `Dockerfile` is a 3-stage build: (1) Node 24.22.1 builds `web/dist` (`npm ci
--legacy-peer-deps`), (2) Temurin 21.0.13+9 JDK on Ubuntu 24.04 Noble runs `:server:installDist`
with the committed Gradle project and plugin locks, (3) the matching Temurin JRE bundles the
install image **and** the built SPA, sets `WEB_STATIC_DIR=/app/web` and `KTOR_DEVELOPMENT=false`,
and runs `bin/server` as an unprivileged uid. The runtime stage upgrades libexpat1, libsqlite3-0,
perl-base, libssl3t64, libbrotli1, apt and libapt-pkg6.0t64 from Noble's signed repositories and enforces fixed-version floors until the
Temurin image includes them. Every external base uses an exact tag and reviewed multi-platform
manifest digest. The `app` Compose service points the `POSTGRES_*` env vars at the `postgres`
service host (`postgres:5432` in-network) and waits on its healthcheck. Compose and
Testcontainers share PostgreSQL 18.6's reviewed digest; Mailpit is pinned to a reviewed
image digest. `.dockerignore` keeps build outputs, every `node_modules`, `.claude/` (in a primary
checkout that includes each git worktree under `.claude/worktrees/`: the context is ~18 MB instead of ~3.8 GB),
coverage/Playwright output and `.kotlin/` out of the build context; `.git` is **included** on purpose — the SPA build stage reads the commit sha/timestamp
from it for the version stamp (see "Build version stamp" in `web/CLAUDE.md`). Note the volume
mount: postgres 18+ images keep data in a versioned subdir of `/var/lib/postgresql`, so the
compose volume mounts that path (the pre-18 `/var/lib/postgresql/data` path makes the container
unhealthy).
