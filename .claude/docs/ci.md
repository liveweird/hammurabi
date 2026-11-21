### CI and static-analysis gate details

Read on demand before changing a CI job, a Dependabot rule or a static-analysis config; the commands a session runs stay in `CLAUDE.md` "Commands" and the binding one-line rules in `.claude/docs/testing.md`.

**CI** (moved from `CLAUDE.md` "Commands").

- CI: `.github/workflows/ci.yml` re-runs every gate above on push/PR (job ids `changes`, `server`, `gradle-vulnerability-scan`, `web`, `e2e-static`, `k8s-static`, `images`, and the failure-only `alert`; the `changes` job lets a PR changing only Markdown skip `server` and `images`, except that a change to `CLAUDE.md`, `.claude/docs/testing.md` or `.claude/docs/list-endpoints.md` (the three files `AlwaysLoadedDocsBudgetTest` reads) still runs `server`, so the 36,000-byte budget cannot be broken by a docs-only PR; `server` — incl. the OpenAPI
  coverage gate, strict Gradle dependency verification (`--dependency-verification strict`) plus a
  lock/verification-metadata drift check, `gradle-vulnerability-scan` (a HIGH/CRITICAL Gradle lockfile vulnerability scan),
  `web` — incl. the API-contract gate (`lint:api` + `check:api`), `npm run lint` over a restored ESLint result cache (`web/.eslintcache` via `actions/cache`, keyed on the lockfile + `eslint.config.js` + the commit with a prefix restore, so a PR re-lints only the files it changed) and the coverage run in shuffled order
  (`npm run test:coverage -- --sequence.shuffle --sequence.seed=<seed>`, which is also the `isolate: false` independence proof; `scripts/gates.sh web` mirrors it with a
  random seed it prints; CI picks the seed itself and writes it, with the replay command, to the job summary before the run, so a shuffle-order failure can be reproduced), `e2e-static` —
  lint/knip/typecheck/scenario parity/setup, the toolchain-pins check, `npm audit` (high+) on both npm workspaces, `k8s-static` (kubeconform over
  `k8s/`, the app deployment pinned to one replica, and "No path around the Ingress": no `LoadBalancer`/`NodePort`, `hostPort` or `hostNetwork`, the `app` Service a `ClusterIP`, the NetworkPolicy naming the `ingress-nginx` controller), and `images`, an image build plus a Trivy scan of it, on `master` pushes and on every pull request that changes more than Markdown — the `changes` job decides it, as it does `server`); the blackbox Playwright
  suite (`e2e.yml`) runs nightly and on demand, and re-scans the image it built with the same Trivy gate (its "Build images" step is a plain `docker compose build`, like `images`). Dependabot (`.github/dependabot.yml`) checks every
  workspace, Actions and container manifests weekly; `.claude/docs/dependencies.md` describes
  grouping, compatibility pins and runtime verification, and
  `.claude/docs/dependency-reproducibility.md` describes the Gradle lock/checksum mechanism itself. The job time budgets are PROVISIONAL until measured (`build-times.md`).

**Concurrency.** `ci.yml` cancels a superseded run only for `pull_request` events (`cancel-in-progress: ${{ github.event_name == 'pull_request' }}`); a push to
`master` runs in a group of its own (the run id), because inside one shared group GitHub keeps a single pending run and drops the older pending one even without
`cancel-in-progress` — every master commit (so every release commit) is fully verified. `e2e.yml` (nightly/on demand) declares no `concurrency`.

**Failure alerting** (checkup 3, D5-1/D8-5; nightly + alerting was chosen over a PR-time e2e subset, to keep PR CI fast).

- **Where and when.** `e2e.yml` job `alert` runs `if: failure() && github.event_name == 'schedule'` (the nightly only; an on-demand `workflow_dispatch` run stays quiet because its author is watching) and `ci.yml` job `alert` runs
  `if: failure() && github.ref == 'refs/heads/master'` (a master push; a pull request never alerts) and `needs` EVERY other job (`changes`, `server`, `gradle-vulnerability-scan`, `web`, `e2e-static`, `k8s-static`, `images`), so any red master job alerts, not only `images` (Adder's 1.0.0 checkup C-GATE-7). Both are skipped (not run) on every green or cancelled run and add nothing to a gate's time. **A new `ci.yml` job joins that `needs` list in the same commit.**
  The nightly also goes red when a test passed only on retry (`failOnFlakyTests`, `e2e/README.md`), which opens the same issue.
- **One reusable piece.** Both call `.github/workflows/ci-alert.yml` (`workflow_call`, input `title`; no third-party action, only the runner's `gh`). The calling job grants `permissions: issues: write` with the built-in `GITHUB_TOKEN`; the workflows' default stays `contents: read` and no secret is involved.
- **Dedupe.** The label `ci-failure` (created with `gh label create --force`) plus an exact title, one issue per workflow: "Nightly e2e is failing" and "master CI is failing". An OPEN issue with that label and title gets a comment (run URL, date, head SHA, trigger); otherwise the issue is opened with the same body. Every context value reaches the script through `env:` (script-injection rule); no commit message, branch name or PR title is used.
- **Closing is manual.** Nothing closes the issue automatically, not even a green run: close it once the cause is understood, and the next red run opens a fresh one. A red `alert` job (e.g. a token problem) is itself a failed run.

**Transient resolution failures** (Adder's 1.0.0 checkup C-GATE-1, ported). A dependency miss from Maven Central or the plugin portal (`Could not find io.ktor:ktor-version-catalog`, a 404/5xx/timeout) failed 3 of 8 of Adder's master pushes with no retry. `scripts/gradle-retry.sh <gradle command>` re-runs the command up to 3 times (`GRADLE_RETRY_ATTEMPTS`, pause 15 s then 30 s, `GRADLE_RETRY_PAUSE_SECONDS`) ONLY when the output carries a network/resolution signature and never on `Dependency verification failed`; a compile error or failing test is final on the first attempt. Between attempts it deletes `caches/modules-2/metadata-*`, because Gradle caches a missing module for 24 h and a bare re-run would read the cached miss; the jars stay and every artifact is still verified, so locking and checksum verification are untouched (no `--offline`, no `--refresh-dependencies`). Use it with `--no-daemon` only. It guards three places: the `Dockerfile` `server` stage (`./gradlew --version` and `:server:installDist`, so `images` and `e2e.yml` both inherit it; the script is copied to `/usr/local/bin/gradle-retry`), the `server` job's "Resolve Gradle dependencies (bounded retry)" step, which runs the root task `resolveAllDependencies` (`build.gradle.kts`: every resolvable configuration of every project and each buildscript classpath, resolved to its files under strict locking and verification, so it throws on a miss and downloads the jars; the `dependencies`/`buildEnvironment` report tasks cannot serve, they print FAILED and exit 0) so the 25-minute build starts on a warm cache; the full `check` build is deliberately NOT wrapped (a test log could match a network signature and re-run it), and `gradle-vulnerability-scan`. What counts as transient: `Could not GET|HEAD|download`, `Could not find <group>:<artifact>:<version>` (a full coordinate), timeouts/resets, HTTP 5xx, DNS; never a bare `Could not find`/`Could not resolve` (a build-script `Could not find method` is final), never `Dependency verification failed` or a strict-lock violation (`not part of the dependency lock state`). The script refuses a `gradlew` command without `--no-daemon`. A genuinely wrong coordinate is retried too (about a minute lost) before it fails. The metadata deletion also drops the artifact index, so jars may be downloaded again on a retry; they are re-verified. The coverage is as good as the signatures: a transient failure that prints none of them fails on the first attempt, as before.

**Local gates vs CI (`scripts/gates.sh`).** The script runs `server-build`, `web-gates` and `e2e-static` (lint, knip, typecheck, scenario parity, `npm run test:setup` and
`node scripts/check-toolchain-pins.mjs`, the same cheap checks as the CI `e2e-static` job). **CI-only scans**, deliberately not in the script (the header comment of
`gates.sh` lists them too): `npm audit` on both workspaces and the HIGH/CRITICAL Gradle lockfile vulnerability scan (they read a live advisory database and the network, and
their result changes overnight without a commit), kubeconform in `k8s-static` (a downloaded checksum-pinned binary), and the Trivy / AppCDS image checks (need a built image).

**Static analysis and dead-code gates** (moved from `testing.md`).

**Static analysis (detekt).** `./gradlew detekt` runs detekt over `core` + `server` (plain rule
sets, no type resolution) and rides `check`, so `build` fails on any finding — the gate is zero
findings with **no baseline file**. Repo tuning lives in `config/detekt/detekt.yml`, layered on
the bundled defaults; every override there carries a one-line comment naming the deliberate idiom
it protects (wildcard Ktor imports, the flat feature-package layout, declarative `*Routes.kt`
registrars, the validation-throw convention, guard-clause returns). Fix new findings in code
first; extend the config only for a genuinely deliberate idiom, and prefer a config override over
`@Suppress` (a per-site `@Suppress` needs a one-line justifying comment). Runs in seconds, no
Docker — safe to run anytime, unlike the test suite.

**Frontend static analysis (sonarjs + knip).** The SPA's counterpart, same
zero-findings/no-baseline policy: `cd web && npm run lint` carries `eslint-plugin-sonarjs`
(recommended set) plus core size/complexity backstops tuned generously for React's
one-function-per-page architecture (`cognitive-complexity` 40, `complexity` 50 — backstops
against future monsters, not targets); every override in `web/eslint.config.js` carries the idiom
comment. `cd web && npm run knip` is the dead-code gate (unused files/exports/dependencies; test
files count as entries, so a flagged export is unused even by tests) — the generated
`src/api/schema.ts` is excluded (type-checked by `tsc`, not style-linted). `web/scripts/*.mjs` (the build-time Node scripts, e.g. `check-api.mjs`) are linted too —
`js.configs.recommended` + sonarjs with Node globals, a separate block in `web/eslint.config.js`.

**Running the full stack** (moved from `CLAUDE.md`; the ports and the one-liners stay there).

`docker compose up --build` serves everything at `http://localhost:8090` (sign in as
`admin@hammurabi.local` — see the README for where the initial password comes from); local dev is
`docker compose up postgres mailpit` (Postgres on host port **5440**) + `./gradlew :server:run`
(API on **8090**) + `cd web && npm run dev` (Vite on **5181**, proxying `/api` to :8090), each in
its own terminal. The compose stack is postgres + mailpit + app only, and bundles **Mailpit** (`http://localhost:8031`) and wires the
app's password-reset and MFA email to it (`MAIL_TRANSPORT=smtp`). Ports deliberately differ across the sibling repos so every stack can run side by side
(host ports bind to 127.0.0.1 only):

| Repo | App | Postgres | Mailpit UI | Vite |
|---|---|---|---|---|
| Lettuce | 8080 | 5432 | 8025 | 5173 |
| Toadie | 8081 | 5433 | 8026 | 5174 |
| Covenant | 8082 | 5434 | 8027 | 5175 |
| Flow | 8084 | 5435 | 8028 | 5176 |
| Grenadiers | 8085 | 5436 | – | 5177 |
| Astrolabe | 8086 | 5437 | 8029 | 5178 |
| Astrid | 8088 | 5438 | – | 5180 |
| **Hammurabi** | **8090** | **5440** | **8031** | **5181** |

Kubernetes
(OrbStack) deployment targets the dedicated `hammurabi` namespace — see `k8s/secret.yaml`'s header for
the secret-creation command; the image is `hammurabi-app` and the cluster runs a single `app-deployment.yaml` (one process, one replica) beside postgres. **The front door is an ingress-nginx Ingress** (`k8s/ingress.yaml`; the `app` Service is a `ClusterIP` and `network-policies.yaml` admits only the controller's pods), because the app trusts the proxy's `X-Forwarded-For` for its per-IP keys: a LoadBalancer in front would let a client pick its own key (checkup C-SEC-6). Reach it at the Ingress host (`hammurabi.k8s.orb.local` on OrbStack, a self-signed certificate); `kubectl port-forward` bypasses the proxy and the policy, so it is for an operator's debugging only.

**AppCDS (class-data sharing).**

- The Docker build dumps `/app/cds/hammurabi.jsa` from the committed `server/cds/classlist.txt` (a few seconds per image build); Trivy scans the same image as before.
- The `images` job then runs `scripts/check-cds-archive.sh hammurabi-app` (the image's launcher with `-XX:+PrintSharedArchiveAndExit`, under a second, no database): it fails when the archive does not fit the runtime
  (a changed jar, `-XX:-UseCompressedOops`/`ObjectAlignmentInBytes` added to `applicationDefaultJvmArgs`, another JDK) or is not wired, because such an image starts silently WITHOUT the archive and slower than before.
- `scripts/cds-classlist.mjs` regenerates the class list from a real run in its own throwaway compose project (own ports, own volume); it builds its own image and refuses to overwrite the list from an unhealthy run. Regenerate it whenever the class set changes materially (`CdsClassListTest` guards drift).
