### Security posture: development vs production mode

`ktor.development` is env-overridable (`$KTOR_DEVELOPMENT:true`): local `:server:run`/tests
default to development mode; **the Docker image ships `KTOR_DEVELOPMENT=false`** (production
mode), and `docker-compose.yaml` explicitly sets it back to `true` because it is the local
plain-HTTP demo. Production mode activates HSTS + HTTPS redirect and **fail-closed startup
checks**:

- **JWT secret** (`plugins/Security.kt`): production requires a private 64-character hex key
  generated with `openssl rand -hex 32`. Blank, malformed, repeated-character and publicly
  committed values (including the compose demo key and Kubernetes template placeholder) refuse
  startup. Development warns and retains its demo behavior. The format check cannot establish
  randomness; generate and store the key privately.
- **Seed passwords** (`infra/db/Bootstrap.kt`, module `configureBootstrap`, runs after
  `configureDatabase`): in production mode, if any active account still carries the well-known
  `changeme` bcrypt hash the app **refuses to start**. The seed admin is also checked against
  known plaintexts so a freshly salted hash of an unsafe initial password from an older build
  cannot pass that hash-equality check. Setting `ADMIN_INITIAL_PASSWORD` (config
  `bootstrap.adminInitialPassword`) rotates the V3 seed admin's password at startup — idempotent:
  only applied while the admin still has the seed hash, so an admin-chosen password is never
  overwritten. Before rotating in production, bootstrap rejects the known `changeme`/`CHANGE-ME`
  values and applies the normal account password length and bcrypt byte limits. Covered by
  `BootstrapTest`; seed-mutating tests restore state via `TestSeedState.restoreSeedAccounts()`
  (`TestEnvironment.kt`).
- **Mail transport** (`infra/mail/Mail.kt`): `mail.transport=log` writes outbound email (including
  password-reset links and MFA codes) into the application log — permitted with a warning in development,
  **refuse to start** in production; `smtp` with a blank host refuses to start in **any** mode.
  A non-blank `mail.appUrl` that is not the deployment's `https` origin (`http` only in development; no
  path, query, fragment or userinfo) refuses to start in **any** mode (blank is allowed: the reset answers 503
  and boot warns). See "Outbound email" below.
- **Data-encryption key** (`infra/crypto/Crypto.kt`, module `configureCrypto`, right after
  `configureMail` and BEFORE `configureDatabase`): blank or the burned repo-committed dev default
  → warn in development, **refuse to start** in production; a malformed key (not 64 hex chars —
  the k8s template placeholder included) refuses in ANY mode. See "Encryption at rest" below.
  Covered by `CryptoBootTest`; every production-mode boot test overrides
  `security.encryption.key` with `strongEncryptionKey()`.
- **Postgres password** (`infra/db/DatabasePassword.kt`, called at the head of `configureFlyway`, the first module to open a
  connection, so nothing ever connects with a public password; checkup C-ARCH-1): a blank password, the shipped default `hammurabi`
  (the `postgres.password` default in `application.yaml` and the compose demo database) or the k8s template placeholder
  `CHANGE-ME` (also `changeme`; trimmed, case-insensitive) → warn in development, **refuse to start** in production. The
  message names `POSTGRES_PASSWORD`, never the value. Compose is unaffected: it runs development mode (`KTOR_DEVELOPMENT=true`) on the
  `hammurabi` demo password, so `docker compose up --build` still works untouched; a real deployment creates the database with a
  private `POSTGRES_PASSWORD` (k8s: the `hammurabi-secrets` Secret, `openssl rand -hex 16`) and an existing database whose role
  still has `hammurabi` needs `ALTER ROLE hammurabi PASSWORD '…'` first. The test container's password is deliberately NOT `hammurabi`
  (`PostgresTestSupport`), so production-mode boot tests reach the later checks; `DatabasePasswordBootTest` pins this one.

**JWT model** (`auth/Tokens.kt` + `plugins/Security.kt`): login issues an access/refresh **pair**,
both carrying `email`, `userId`, a `roles` array, a `typ` claim (`"access"`/`"refresh"`), a
`credentialRevision` and a random `jti`. The `jwt {}` verifier requires audience (`hammurabi-api`), issuer
(`http://0.0.0.0:8090/` by default), `typ == "access"`, and a PRESENT `jti` (a jti-less token could never be
revoked, so both the verifier and `/refresh` reject it outright). **Every authenticated request then runs ONE
uncached database statement** (`TokenBlocklistService.accessVerdict`, ported from Lettuce's `isSessionLive` and
extended; `revoked_tokens` is written by `/logout` and by the refresh rotation, the revoke path prunes expired
rows): it reads the token's user row (`role`, `credential_revision`, `marked_as_deleted`) and whether the `jti` is
blocklisted, and the token is accepted only when the account exists and is not deleted, the `jti` is not
blocklisted, the token's `credentialRevision` equals the stored one and its `roles` equal the stored role. Anything else is a `401`
audited `access.rejected` with a `reason` (`user_gone`, `revoked`, `credential_changed`, `roles_changed`, and
`malformed` for a signed access token lacking `roles` or `exp` (the verifier alone would accept an `exp`-less token that never expires; `accessPrincipal` is the one place that refuses it, for every bearer route), or whose `userId` (1..4294967295) or `credentialRevision` (non-negative) is not an integral JSON
number - the same `strictLongClaim` rule as `/refresh`, so `1.0`, `1e0` and a numeric string are rejected on both paths -
a pre-deploy token, which the SPA silently refreshes), all with ONE response text ("Access token is no longer
valid - refresh or sign in again"; the audit event is coalesced to one per user and reason per minute with a `suppressed` count, like
`rate_limit.exceeded`, so a client replaying a stale token writes a line a minute, not a line per request). Structural rejections (junk, expired, wrong audience/`typ`, no `jti`) stay
unaudited and never reach the database. Nothing is cached: a logout, an admin delete, a demotion/promotion or a
password change/reset ends the token on its very next request, on any replica (the old 30-second verdict cache,
`REVOCATION_CACHE_TTL_MS`, is gone); the price is one primary-key read per request (about 1 ms). A role change does NOT bump the
credential revision, so the SPA's `401` -> `/refresh` succeeds with the new roles. Not compared: the MFA flag and
other feature flags (they ride the token until the next refresh), lockout, name/email/language. Pinned by
`StaleAccessTokenTest`. **A lookup that THROWS inside the JWT `validate` block
(database unreachable, pool acquire timeout) is an outage, not a bad token:** ported from
Lettuce, `validate` runs the lookup through `catchingFailures` (`infra/Failures.kt`)
and, on failure, stashes the cause on the call (`BlocklistFailureKey` in `plugins/Security.kt`);
Ktor's JWT provider turns any throw out of `validate` into a plain challenge, so the challenge
answers the catch-all's logged `500` (`respondInternalError`, `plugins/ErrorHandling.kt`) instead
of `401` — the SPA reads a `401` as session expiry and would sign the user out over a transient
outage. A malformed/expired/wrong-audience/wrong-`typ` token never reaches the lookup and stays
the uniform `401`. Pinned by `BlocklistOutageTest`; the OpenAPI spec already declares `500` on
every operation and the coverage gate ignores it (`CROSS_CUTTING_STATUSES` in
`OpenApiConformance.kt`), so no spec change is needed. Full token/refresh/logout semantics: see
"Authorization model" in `.claude/docs/authorization.md`.

**Token storage & response headers (the XSS posture).** The SPA keeps BOTH tokens in
`localStorage` under `hammurabi.auth.*` (`web/src/api/session.ts` — access, refresh, roles, userId,
disabledFeatures and a random session identity; no password is ever persisted; everything else in
localStorage is view state). A deliberate trade-off, documented rather than hidden: any script
running on the origin can read the REFRESH token, i.e. a renewable session unbound to device/IP — so the
real control is keeping foreign script out. **The refresh token's lifecycle (rotation with a short replay grace):**
`POST /api/v1/refresh` verifies the presented refresh token (signature, issuer, audience, a PRESENT `exp`, `typ`, a
present `jti`, not blocklisted, the user still active, `credentialRevision` current) and then CLAIMS it: an atomic
`INSERT .. ON CONFLICT DO NOTHING` of its `jti` into `revoked_tokens` (until the token's own expiry;
`TokenBlocklistService.revokeIfAbsent`, pinned directly by `TokenBlocklistRotationTest`), after which a fresh
access/refresh pair is minted. Only the claim is atomic; delivery of the response is not, so of any number of
concurrent calls with one token exactly one inserts the row (`CLAIMED`) and the rest are decided by the grace below.
**Replay grace** (`security.refresh.replayGraceSeconds`, `$REFRESH_REPLAY_GRACE_SECONDS`, default 60, 0 = strict
one-time use): a `/refresh` response can be lost after the claim committed (the SPA's 30 s deadline, a dropped VPN, a
reload mid-flight), leaving the client holding the already-retired token. So a replay of a token THIS instance rotated
less than the grace ago is answered with another fresh pair (audit `refresh.replay_grace`, `userId`) instead of a `401`
that would sign the user out; the same grace answers the loser of two tabs refreshing at once (the rotation is
recorded inside the claim's transaction, before its commit, so a racing loser, which only resumes after that commit,
always finds it). One token gets at most 2 such re-mints (`MAX_GRACE_REUSES`; a lost response or a racing tab needs one);
after that, or after the window, a replay is rejected. **A logout that REACHES the route body ends the grace**: it
drops every rotation record of the access principal's user and of the presented refresh token's own user
(`TokenBlocklistService.forgetRotations`), so a copy of a recently rotated token cannot mint after sign-out and its replay
is `reason=revoked` (it also ends the grace of that user's other devices, whose next lost-response retry then signs out:
strict, never lax). `/logout` sits behind `authenticate {}`, so it needs a valid ACCESS token: the SPA's `logout()`
(`web/src/api/auth.ts`) therefore, on a `401` (the stored access token expired after idle or a lost refresh response),
exchanges the captured refresh token for a new pair without touching the already-cleared local session
(`refreshPairDetached`) and calls `/logout` again with the NEW pair, so the live refresh token is the one revoked; if that
refresh fails the sign-out stays local only and the refresh token lives until its own expiry (<= 1 h). A direct API caller
that logs out with an expired access token gets the plain `401` and revokes nothing. Known limitation: a refresh and a
logout racing within the same few milliseconds can leave the freshly minted pair unrevoked, bounded by the refresh TTL. The rotation records (`auth/RefreshRotations.kt`, `RefreshRotationsTest`) are in memory
and per instance like the lockout/MFA state (single replica) and capacity-bounded at 10,000: when full, records whose
token has expired or whose grace has passed are evicted first and, if it is still full, the NEW record is dropped. A
restart, or a dropped record, loses them and the behaviour falls back to strict. Outside the grace, a replay of a rotated token the instance still remembers is a `401`
audited as `refresh.rejected reason=replayed`; a token blocklisted by logout (or whose rotation is not remembered) is
`reason=revoked`, so the trail separates theft/replay from logout. The accepted cost of the grace: a stolen token replayed
within the grace of its legitimate rotation also gets a pair, at most twice (the window is short, the replay is audited and a logout ends it). There is
deliberately NO family-wide revocation and no "sign out everywhere": a replay outside the grace does not end the live
chain, and a stolen token that refreshes FIRST makes the victim's next refresh the rejected replay (the victim is signed
out and the trail shows `replayed`). The access token a rotation supersedes is not revoked: it stays valid until its
own expiry (<= 15 min; only a logout blocklists an access token, and the per-request check above ends it on any
delete, role change or password change). `POST /api/v1/logout` revokes the access token it is
called with and the one refresh token the client sends in the body (best-effort; both jtis go to the blocklist); it does
NOT end other pairs of the same user. What does end every outstanding refresh token of a user is a password
change/reset/bootstrap rotation (the `credential_revision` bump) or an admin deleting the account (`/refresh` re-reads
the user), and the same events end the ACCESS token at its next request (the per-request check above); an idle session
also ends at the refresh TTL. The cost is one `revoked_tokens` row per refresh (about one per
15 minutes per active session, pruned at the next revoke once expired). The SPA copes with two tabs sharing one session
(`web/src/api/http.ts`, `web-internals.md`). The keep-script-out control is `plugins/SecurityHeaders.kt`, installed unconditionally and pinned by
`ServerTest`: a strict CSP (`script-src 'self'`, `style-src 'self' 'unsafe-inline'`,
`object-src 'none'`, `base-uri 'self'`, `frame-ancestors 'none'` — the whole string is pinned
verbatim in `ServerTest`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer`, `Permissions-Policy: geolocation=(), camera=(), microphone=(), payment=()`
(also pinned verbatim), and — production mode only, `plugins/Http.kt` — `Strict-Transport-Security`
(`max-age=31536000; includeSubDomains`, pinned by `ServerTest`'s production-mode boot behind
`http.behindProxy` with `X-Forwarded-Proto: https`; absent in development). Every `/api/` JSON and problem+json answer additionally carries
`Cache-Control: no-store` (`plugins/Http.kt`'s `CachingHeaders`, pinned by `ServerTest` together
with the unchanged caching of the SPA's static assets), so per-user data never lands in a browser
or proxy cache ("Caching" below). `text/html` (index.html and every SPA deep link answered with it) carries `Cache-Control: no-cache`
(revalidate every time, so a deploy's new hashed asset names are never masked by a stale shell), while the content-hashed
CSS/JS keep `max-age=86400`. The `Server` header is the fixed generic `hammurabi` (`install(DefaultHeaders) { header(Server, …) }`
in `Http.kt`) — Ktor's default `Server: Ktor/<version>` disclosed the framework and its exact version; pinned in `ServerTest`
(no `Ktor` in it).

**Caching -- a settled decision (the owner chose `no-store`).** Every `/api/` answer (JSON and `problem+json`) is `Cache-Control: no-store`, so nothing lands in a browser or proxy cache. No route answers through cache validators: Flow's report GETs were the donor's only consumer, and its `ETag` seam (`ResponseValidators`) was removed unused (Adder's checkup C-SMELL-2, ported); the first view GET that wants one ports it back. The rejected alternative was `private, no-cache` + `Vary: Authorization` + a logout `Clear-Site-Data: "cache"`, which would leave response bodies on disk between logins on the same profile (best-effort clearing only). Cross-user safety of any future `304` rests on two rules: authentication runs BEFORE any validator logic (an unauthenticated request is `401` with no validator, so a `304` is never a way to probe for data), and a validator never covers content that depends on the caller unless the caller's verdict is folded into the `ETag`.


`style-src` carries `'unsafe-inline'` deliberately: Mantine's style props/CSS variables render
inline `style` attributes and runtime `<style>` tags, and the app uses `style={{}}` itself — a
STYLES-only concession; it does not reopen script injection.

The Swagger UI is the ONLY CSP exemption (it bootstraps with inline script/style), and it applies
only when `http.exposeOpenApi` is on (`Application.exposesOpenApi()`, the same definition that
mounts the UI: the property when set, else development mode) AND the path is exactly `/openapi` or
starts with `/openapi/`. With it off — production by default — `/openapi`, `/openapi/x` and
look-alikes such as `/openapiX` are ordinary paths (the SPA catch-all answers them with
`index.html`) and carry the full CSP. The exempt paths still get the non-CSP hardening headers.
All of it is pinned in `ServerTest`.

Consequences: never weaken `script-src` (no CDN scripts, no `unsafe-inline` for scripts — Vite
bundles everything same-origin, fonts included), and the SPA must stay free of
`dangerouslySetInnerHTML`/`eval` sinks (currently zero). Moving tokens to httpOnly cookies would
trade this for CSRF machinery — revisit only with that full picture.

**Login timing equalizer** (`auth/AuthRoutes.kt` + `TIMING_EQUALIZER_HASH` in
`auth/Passwords.kt`): an unknown email pays a full, discarded bcrypt verify against a fixed
cost-12 hash so its 401 takes as long as a wrong password's — without it, response timing is an
account-enumeration oracle (the reset path equalizes via async processing; login equalizes
in-line).

**Per-account login lockout** (`auth/LoginThrottle.kt`, built by `configureLoginThrottle` in `auth/AccountLockout.kt`, published as
`LoginThrottleKey`; the module loads BEFORE the user routes, which share it): after
`security.lockout.threshold` (default 5, `$LOGIN_LOCKOUT_THRESHOLD`) consecutive failures for one
submitted email, `/login` answers `429` for `security.lockout.durationSeconds` (default 900,
`$LOGIN_LOCKOUT_DURATION_SECONDS`; longer when wrong emailed codes tripped it, see "progressive for MFA codes ONLY" below) — even with the correct password, and regardless of whether the
account exists (no enumeration signal). A success resets the counter. The in-memory, per-instance (so the app runs ONE replica — `ci.yml` `k8s-static` pins it)
store has a hard `security.lockout.maxTracked` limit (default 10,000,
`$LOGIN_LOCKOUT_MAX_TRACKED`): expired state is reclaimed through an expiry index (`infra/ExpiringIndex.kt`, the one TTL index the reset cooldown and the MFA challenges share), but fresh
counters and active locks are never evicted to admit a new identity. At capacity, an untracked
identity receives an audited `429` before password verification, so saturation cannot bypass the
account throttle; tracked identities can still finish their normal flow. A fixed-size digest of
the canonical submitted identity bounds retained key size even for malformed login input;
oversized identities are also digested in audit fields instead of logged in full. The deployment
is single-replica; a restart resets the throttle. This complements the per-IP `RateLimit` bucket,
which rotating hosts sidestep. The 429 is **thrown** (`TooManyRequestsException`), never
`respondProblem`ed directly — StatusPages' generic 429 status handler rewrites any non-StatusPages
429, so only the exception path keeps the specific detail. Tests: `LoginThrottleTest` (unit,
injected clock) + `LoginLockoutTest` (route). The SPA shows a neutral temporary sign-in limit for
either 429 cause.

**ONE counter for every guess at an account's credentials (C-SEC-5).** A wrong password at `/login`, a wrong emailed code at
`/login/mfa` and a wrong `currentPassword` on the self password change (`PUT /users/{id}/password`) all feed the same
`LoginThrottle` counter, so N wrong guesses across the three lock the account exactly as N failed logins would
(`AccountGuess` in `auth/AccountLockout.kt`). What each answers: the locking failure answers what
that route always answers (401 at login and the MFA step, 403 on the change); every LATER attempt answers the lockout's specific
`429` (thrown, see above), the change BEFORE any bcrypt (preflight), and an unseen identity at a full store the audited capacity
`429` - **but only where the account is not yet known (Adder's 1.0.0 security audit, M1, ported).** The MFA step (the challenge's subject) and the
self password change (the signed-in caller) act on an account that EXISTS, so their failures are counted even at a full store
(`AccountGuess.knownAccount`, `LoginThrottle.preflight/recordFailure(knownAccount = true)`): otherwise a password holder could fill
the store with junk logins (about 670 a minute) and then guess emailed codes without ever locking. **The memory bound stays:** the
key space of those extra entries is the user table (one digest per EMAIL an account held during a failure window, each expiring after the lock window; an ADMIN email change adds a key, so the bound is per email, not strictly per account), so a full store can
hold at most `maxTracked` unknown identities plus one entry per such email. The capacity refusal stays for an UNKNOWN identity, and at
`/login` it stays for every untracked email: the refusal comes BEFORE any password is compared and answers the same `429` for a known
and an unknown email, so it leaks nothing and lets no guess through, whereas counting a wrong password for a known account there
would have to answer differently from an unknown one (an existence oracle). **The accepted cost:** the above-cap entries can only be made for REAL accounts, so while an attacker holds the store full, a `/login` probe of an email flips from the capacity `429` to `401` once that account's owner mistypes an MFA code or their current password. That proves the account exists, and the probe then counts a failure against it. It needs the owner's own mistake inside the lock window plus a sustained fill that already denies sign-in to everyone untracked, so it is accepted. A correct password (non-MFA), a correct code or a correct current password clears the counter; a MISSING `currentPassword`
is a plain 403 that does not count (nothing was guessed). An admin resetting ANOTHER user's password verifies nothing, so it neither
counts nor is refused by the target's lock; the cost is that a locked owner with a live session cannot change their own
password until the window ends. The ways out: the emailed reset link (`POST /password-reset` is not behind the lock and a confirmed reset
changes the password without `currentPassword`), or an admin resetting the password; with `MAIL_TRANSPORT=disabled` (no reset mail) only
an admin can. For an MFA account the password step does NOT clear the counter (it is only the first factor: a
caller holding the password could otherwise log in afresh after every few wrong codes and never lock); an accepted code does.
**Pending MFA challenges and a lock:** a lock tripped by the MFA step (or met by a wrong code there) drops the account's pending
challenges; a lock tripped by wrong passwords at `/login` or on the change does NOT, but no pending code can finish a sign-in
during it (a correct code answers the lockout's `429`, `AccountGuess.recordSuccess`) and the challenges expire on their own TTL.
**The lock is progressive for MFA codes ONLY (the owner's 2026-11-20 decision in Adder's checkup, ported; `LoginThrottle`).** Someone who HOLDS the
password and guesses emailed codes used to get `threshold` (5) guesses per flat 15-minute window indefinitely (about 175,000 guesses
a year against a 6-digit code, roughly a 16 % chance per year). Now a lock whose failure window held ANY wrong emailed code
(`AccountGuess.mfaCode`, set by the MFA step alone; `State.codeFailed`) doubles per consecutive such lock: the n-th lasts `durationSeconds x 2^(n-1)`
(900 s, 1800, 3600, ... ), capped at `security.lockout.mfaMaxDurationSeconds` (`$LOGIN_LOCKOUT_MFA_MAX_DURATION_SECONDS`, default
86,400 = 24 h, never below `durationSeconds`, 1..30 days boot-validated). **The bound that results (with the pacing
counter of rule (6) and the one-step decay of rule (2)):** the climb to the top costs 7 locks (15 min ... 16 h, about 32 h in all,
35 guesses); after that every lock is the 24 h cap, and a guess round is either a lock (5 guesses, then 24 h) or a paced period (4
guesses, no lock, 24 h): the best mix is 5 + 4 guesses per 48 h, about 4.5 a day, about 1,650 a year against a 6-digit code (10^-6
each), roughly a 0.17 % chance per year, down from the roughly 16 % of the flat window (35,000 windows x 5 guesses). Waiting does
not buy the cheap bottom of the ladder back: the level loses one step per quiet day, so a guesser who waits a day and climbs again is
back at the top. Each guess round also costs a
fresh login and a sign-in code email to the owner and a `login.lockout` line with a rising `escalationLevel`. **A lock whose window held wrong passwords ONLY (`/login`, the password change) is ALWAYS the
flat `durationSeconds`**: a username alone must never buy a longer lock-out of the real owner, because a wrong code needs a live
challenge, which needs the password. **Rules:** (1) the level is the count of consecutive code-escalated locks and lives in
its OWN map with its own `ExpiringIndex` (`escalations`/`escalationExpiries`), because the lock entry is pruned the instant the lock
ends; (2) it **decays ONE STEP per full `security.lockout.mfaDecaySeconds`** (`$LOGIN_LOCKOUT_MFA_DECAY_SECONDS`, default 86,400 = 24 h,
1..30 days) without an escalated lock, counted from the END of the last one (computed lazily from that instant; the expiry slot is
`end + level x decay`, where the level reaches 0 and the entry goes), not straight to 0: dropping to 0 let a guesser wait one period and
climb the cheap bottom of the ladder again, about 22 guesses a day, roughly 0.8 % a year. It is dropped at once by a
confirmed password reset (below) or a success that clears the counter: the accepted code (the full login), and also a correct password for a non-MFA account or a
correct current password on the change, both of which prove a full sign-in or a live session; a success REFUSED by an active lock
clears nothing; (3) a password-only window neither escalates NOR resets the level (decided: no reset, no escalation: otherwise the
holder of an MFA-escalated account could reset the ladder with five wrong passwords), and a failure that meets an active lock
(`ALREADY_LOCKED`) changes nothing; (4) **the window decides, not the tripping failure**: the counter remembers whether ANY of its
failures was a wrong code (`State.codeFailed`, dropped with the counter: when it trips, expires or a success clears it), and a
window with one escalates whatever failure then trips it. Otherwise a password holder would send four wrong codes and trip the lock
with a wrong password at `/login`, staying on the flat 15 minutes forever. This is safe for the owner: a wrong code needs a live
challenge, so someone who knows only the username cannot START an escalating window. They can CONTINUE one the owner left open with a
single code typo, but only inside that typo's 15-minute window and for at most one level per owner typo; (5) **memory**: the escalation map and the code
counter of rule (6) are written only by `recordFailure(mfaCode = true)`, which requires a `knownAccount` (the MFA challenge's
subject), so their key space is the user table (one digest per email an account held during an escalated lock or a code failure, each
entry gone `lock + decay` / `decay` later through its own `ExpiringIndex`, like the above-cap entries), and neither counts against
`maxTracked`: the capacity rule for unknown identities is unchanged; (6) **pacing**: the shared counter's window is only the flat
15 minutes, so a guesser who stays one short of the threshold and waits would never trip a lock. A SEPARATE per-account counter of
wrong CODES only (`codeGuesses`) closes that: it lives `mfaDecaySeconds` from the FIRST code failure of its period (a fixed window,
deliberately not sliding: a sliding one would let two typos a day apart keep it alive for ever), and at `threshold` it trips an
escalated lock whatever the shared counter says, then starts over. So pacing buys at most `threshold - 1` guesses per decay period
before an escalating lock. A success, a confirmed reset (`clear`) and an escalated lock drop it. The shared counter is NOT given a
longer life: with that, an owner's single typo would let a username-only attacker escalate them with wrong passwords for 24 h. The owner
cost: five mistyped codes within 24 h (not within 15 minutes) earn the first, 15-minute escalated lock; (7) **stale challenges**: a
challenge minted before a password change or a confirmed reset is dead ("invalidates any pending MFA challenge"): its wrong
codes are NOT counted (`MfaRoutes.staleChallengeReason`, one primary-key read per wrong code; audited `login.mfa_failure`
`reason=credential_changed`, the challenge dropped), so an old challenge cannot re-lock the owner right after a reset lifted the lock. The level counter is clamped (32) and the doubling is step-wise against the cap,
so nothing overflows. **Nothing new is disclosed:** the 429 is the same `accountLockedException` for every cause and carries no
`Retry-After` today either; only the audit line carries the length and level. **Cost, accepted:** a real owner who mistypes codes
repeatedly can sit locked out for up to 24 h; the ways out are the lock's end, a restart of the single replica and **a confirmed
password reset (the owner's 2026-11-20 decision in Adder's checkup, ported: it lifts the lock AND the level)**: the reset link goes to the same mailbox as the
sign-in codes, so completing it proves the very factor a code guesser lacks (`LoginThrottle.clear`, keyed on the account's email,
called by the confirm route after `PasswordResetService.complete` succeeded, keyed on the email `complete` verified under the user-row
lock, `PasswordResetService.Completion`; the audit says so with `lockoutCleared`, below). Only
the CONFIRM does this: an unconfirmed `POST /password-reset` mails a link and clears nothing, so asking for mail cannot lift a lock;
`login.lockout` with `escalationLevel >= 2` is the operator's signal that a password
holder is guessing codes.
Audit: `login.lockout` (now with `lockSeconds` and `escalationLevel`, below)/`login.rejected_locked` (with `userId` and `source` for the MFA and change
routes; on `/login` the identity is the `email` of an account that exists and the keyed `emailDigest` (HMAC under a key derived from the JWT secret, `observability.md`) of a guess at none, C-SEC-7), `login.capacity_rejected` (`/login` only), `password.change_rejected_locked` (`targetUserId`/`byUserId`). Tests: `AccountGuessTest` (the race branches), `PasswordChangeTest`, `MfaLockoutTest`, `LoginThrottleProgressiveTest` (the ladder, the flat password lock, reset, decay, capacity, overflow on an injected clock).


**Self-service password reset by confirm link** (`auth/PasswordResetRoutes.kt`, `auth/PasswordResetService.kt`, V8; SPA:
"Forgot password?" on `/login` → `/reset-password`, the emailed link opens `/reset-password/confirm`; Toadie's design, 2D3).
The public `POST /api/v1/password-reset` takes `{email}` and answers a uniform `202` for any well-formed request - the
lookup, grant issuance and email delivery run **asynchronously** after the response (uniform latency, no existence oracle),
and the request **changes nothing**: no password, no session. An unknown or soft-deleted account (`findWithIdByEmail`
filters active rows) sends nothing. Each link carries a 256-bit `SecureRandom` token (43-char base64url); only its SHA-256
digest is persisted in `password_reset_tokens` with the user id, the **account snapshot** (`credential_revision` AND
`email`) and the expiry. Lookup is SQL equality on the digest, so no constant-time compare is needed (OWASP Forgot-Password
cheat sheet). The lifetime is `security.passwordReset.tokenTtlSeconds` (`$PASSWORD_RESET_TOKEN_TTL_SECONDS`, default 900,
boot-validated 1..3600). **Several links may coexist** - a newer request never revokes a usable one, or anyone typing an
address into the form could kill the owner's link; a failed delivery revokes only the grant it just minted
(`password_reset.send_failed`); expired rows are pruned on every issue. **An account holds at most 5 unexpired grants** (a constant in
`PasswordResetService`, counted under the user-row lock so concurrent requests cannot slip past it): at the cap `issue` mints
nothing, the async task sends no email and audits `password_reset.capped` (userId only), and the request route still answers `202`
(no oracle). Earlier links stay valid. This bounds the remaining nuisance of the unauthenticated form - anyone who knows an
address can still make the owner receive reset mail, but at most 5 live links per lifetime (15 min by default) per account,
on top of the one-request-per-minute throttle. The async task that finds the account moved between its lookup and the grant insert
audits `password_reset.stale_account` (userId) and sends nothing. A grant dies on **use** (confirming deletes every grant
of that user), a **password change** (the captured `credential_revision` no longer matches), an **email change** (the captured
`email` no longer matches - a Flow deviation from Toadie, whose `auth_version` moves on email edits; the credential revision moves
only on password changes and must stay that way), an account **soft delete** and **expiry**; a role change does not
invalidate it. The email renders through `LocalizedText` in the RECIPIENT'S stored language (V1 `users.language`, EN fallback);
no email carries a password.

The link is `<mail.appUrl>/reset-password/confirm#token=…`. The token sits in the URL **fragment**, which never reaches the
server, its access logs or `Referer`; the SPA strips it from history and keeps it in component memory, and loading the page
consumes nothing (there is deliberately no token pre-check endpoint - an invalid token is learned at submit). The origin is
`mail.appUrl` (`$MAIL_APP_URL`), **configured, never derived from the request `Host`**, and validated at boot by
`configureMail` in any mode: a non-blank value must be an `https` origin (`http` only in development), with a host and no
path beyond `/`, userinfo, query or fragment, at most 2048 characters - otherwise startup is refused (a wrong origin would mail
reset tokens to another host). A **blank** value is allowed: the request route then answers `503` ("set MAIL_APP_URL") and boot
logs ONE `warn` when the transport is not `disabled`. `503` also when the deployment has no outbound email
(`MAIL_TRANSPORT=disabled`).

Public `POST /api/v1/password-reset/confirm` takes `{token, password}`. The grant is checked FIRST (`isUsable`, before any
bcrypt: a junk token never costs a hash; for the PASSWORD rules the 401 wins over a 400 - a body that fails to decode is a
400 first), then `validatePassword` (a refused password is a `400`
that does **not** consume the grant), then `PasswordResetService.complete` does consumption, the password write
(`updatePasswordInTransaction`, conditional on the captured revision) and the deletion of every sibling grant in **one
transaction**, under a `FOR NO KEY UPDATE` lock of the user row (persistence.md "Row locks vs. FK locks"), so competing confirmations (across instances too) have exactly one
winner. An unknown, malformed, expired, used or stale grant is one uniform `401` (`password_reset.rejected`). Success is `204`
with **no auto-login**: the `credential_revision` bump signs every other session out through the per-request access check and
kills pending refresh tokens and MFA challenges, while MFA stays as it was, so the next login still asks for the code. A
best-effort localized notice ("your password was reset, sessions signed out"; no token, no password) is emailed after the
response; its failure (`password_reset.notification_failed`) cannot undo the reset. A confirmed reset **clears the
per-account lockout** (the counter, an active lock of ANY cause and the MFA escalation level; the owner's 2026-11-20 decision in
Adder's checkup, ported; "Per-account login lockout" above; the `password_reset.completed` line carries `lockoutCleared`). A lost 204 can make a retry answer `401`: sign in with the
chosen password or request another link.

Throttling: per submitted email - one request per `security.passwordReset.minIntervalSeconds` (default 60,
`$PASSWORD_RESET_MIN_INTERVAL_SECONDS`) - uniformly for existing and unknown addresses (`auth/PasswordResetThrottle.kt`,
in-memory/per-instance like the lockout), with the hard `security.passwordReset.maxTracked` limit (default 10,000,
`$PASSWORD_RESET_MAX_TRACKED`: elapsed cooldowns are reclaimed, a fresh one never evicted; saturation rejects a new identity with
an audited `429` before any task starts); the per-IP `password-reset` bucket (`security.rateLimit.passwordResetPerMinute`,
`$PASSWORD_RESET_RATE_LIMIT_PER_MINUTE` - blank follows the mode: 5/min production, 100/min development); and the confirm
step's own per-IP `password-reset-confirm` bucket, 10/min in every mode (not configurable - the MFA idiom). `Cache-Control:
no-store` already rides every `/api/` JSON and problem+json answer (`plugins/Http.kt`, StatusPages' 429s included); the bodiless
`204` has nothing to cache. The development `log` mail transport prints the link: it must stay inaccessible outside a local
demo (production refuses it). Tests: `PasswordResetServiceTest` (injected-clock expiry, digest storage, the single-use race,
revision/email/delete staleness, the 5-grant cap, the revision-conditional write that lets only one of two competing writers win, and
a completed reset followed by an admin password write: both succeed, the admin's password is the final one), `PasswordResetConfirmationTest` (the confirm route, its own
bucket, the byte ceiling, MFA, notification failure, the `mail.appUrl` boot contract), `PasswordResetTest` (the request route and
the full link roundtrip incl. the session revocation), `PasswordResetThrottleTest`, `LocalizedEmailTest`.


**Per-IP login bucket** (`security.rateLimit.loginPerMinute`, `$LOGIN_RATE_LIMIT_PER_MINUTE`;
every bucket is installed by `plugins/RateLimits.kt`, which also owns the bucket names — today
`login`, `refresh`, `password-reset`, `password-reset-confirm`, `mfa`, `ready`, and the per-USER `telemetry`, `directory`, `read-state`, `export`, `password-change` and `adr-write` below): blank **follows the mode** — 10/min in production,
1000/min in development — and an explicit number pins it in either mode (the `http.exposeOpenApi`
idiom). Development is lifted because the e2e suite drives its logins from one host and would
otherwise sleep out the bucket; **the per-account lockout above is the actual brute-force defence
and is identical in both modes**. The sibling `refresh` bucket defaults to 30/min in both modes and
is pinnable via `security.rateLimit.refreshPerMinute` (`$REFRESH_RATE_LIMIT_PER_MINUTE`). The anonymous
`GET /api/v1/ready` (one pooled DB round trip per call) rides the `ready` bucket, 60/min in both modes, pinnable via
`security.rateLimit.readyPerMinute` (`$READY_RATE_LIMIT_PER_MINUTE`): the kubelet probes it every 5 s (12/min) and the
e2e global setup every 2 s (30/min), so probers stay far under it; `/api/v1/health` does no I/O and has no bucket.

`RateLimitResponseTest` and `LoginTest` pin the value to `10` explicitly, since tests run in
development mode.

**Per-USER telemetry bucket** (`telemetry`, a fixed 30/min in every mode, `RateLimits.TELEMETRY`) -- keyed by the verified user id, not the client IP, so a NAT'd office is not one bucket -- for
`GET /api/v1/telemetry/current` and `/history` (ADMIN only): `configureTelemetryRoutes` mounts `rateLimit(RateLimitName(RateLimits.TELEMETRY))`
once INSIDE `authenticate {}` around both reads, so the key is the verified user id (`TelemetryRoutesTest`). The panel polls `current` once a
minute and fetches a history per expanded section, a handful a minute; each call is a few `ops` aggregations, so the
bound is for a runaway client. The rejection is the generic `429` problem and the coalesced `rate_limit.exceeded` audit event (bucket `telemetry`, `byUserId`).

**Per-USER read-state bucket** (`read-state`, a fixed 240/min in every mode, `RateLimits.READ_STATE`, 0.6.0; Adder's) for every `/api/v1/notifications` route (the list, `POST .../{id}/seen`, `POST .../seen-all`) and `GET`/`PUT /api/v1/users/{id}/notification-preferences`: the calls that read or move only the caller's OWN view. `NotificationRoutes.kt` and `NotificationPreferenceRoutes.kt` mount `rateLimit(RateLimitName(RateLimits.READ_STATE))` INSIDE `authenticate {}` and BEFORE every guard (an unknown id or a forbidden target spends a token), keyed by the verified user id. The SPA polls the unseen count once a minute and marks a notice on a click; the bound is for a runaway client or a script sweeping ids through the `404`. The rejection is the generic `429` problem plus the coalesced `rate_limit.exceeded` event (bucket `read-state`, `byUserId`). Unlike Adder the LIST rides it too (Hammurabi has no live channel, so the bell polls). `NotificationRoutesTest`.

**Per-USER export bucket** (`export`, a fixed 20/min in every mode, `RateLimits.EXPORT`, 0.8.0) for `GET /api/v1/adrs/{id}/export` and `GET /api/v1/adrs/export` (one bucket for both): `configureAdrExportRoutes` mounts `rateLimit(RateLimitName(RateLimits.EXPORT))` INSIDE `authenticate {}`, keyed by the verified user id. A bulk export reads up to 500 ADRs (25 per transaction, streamed) and every ADR is readable by every signed-in user, so the bound is for a runaway script, not a person (one click is one token); the response is the generic `429` problem plus the coalesced `rate_limit.exceeded` event (bucket `export`, `byUserId`). A bulk export with `text` also rides the search's deadline and process cap (`search-timeout`, `search-busy`). `Compression` skips `application/zip`. **Bulk concurrency cap** (`adrs.export.maxConcurrent`, env `ADRS_EXPORT_MAX_CONCURRENT`, default 2, boot-validated 1..16): a slow reader of a download parks a blocking IO thread for as long as it reads, so one process streams at most that many bulk exports; the next is refused AT ONCE (a `tryAcquire` before the plan, never a wait) with the typed `429` `urn:hammurabi:problem:export-busy` + `Retry-After: 5`, audited `export.busy` (the search-busy pattern). The slot is released by the stream's end or failure (`ExportPermit`), never by the handler returning, which happens while the body is still being written. **A failed stream aborts** (the channel is cancelled with the cause, the zip never finished), so a client never receives a valid-looking short archive. `HEAD` on either export is `405` (the auto-HEAD would otherwise run the plan and the audit).

**No bucket on the decision log** (`GET /api/v1/adrs/overview`, 0.9.0): like `GET /adrs` it rides `authenticate {}` alone. Every section is capped (`list-endpoints.md`), the answer is a constant number of indexed statements in one transaction with no user text in any of them, and the SPA reads it once per page load; a per-user bucket would add a 429 path for a read that costs about what one list page does.

**`password-change`, 5/min (`RateLimits.PASSWORD_CHANGE`, C-SEC-5),** per signed-in user, fixed in every mode (the telemetry idiom, no config key): `PUT /api/v1/users/{id}/password`, mounted INSIDE `authenticate {}` by `configureUserRoutes`. The self change runs a bcrypt verify of `currentPassword` per request, so the bucket stops a stolen session guessing at dispatcher speed and any user occupying the small default dispatcher; the per-account lockout above is the actual guessing bound. The bucket sits BEFORE the guards (a refused or invalid change spends a token; it is also an admin's budget for resetting others, a few a minute at the very most). Rejection: the generic `429` problem plus the coalesced `rate_limit.exceeded` (bucket `password-change`, `byUserId`). Pinned by `PasswordChangeTest`.

**`adr-write`, 120/min (`RateLimits.ADR_WRITE`, C-SEC-4, 1.2.0),** per signed-in user, fixed in every mode (the `directory` idiom, no config key): ONE bucket for the 13 writes under `/api/v1/adrs` — `POST /adrs`, the document `PUT` and the ADMIN `DELETE`, the transitions, the participants, tags and successor-link `PUT`s, the successor `DELETE` and its accept, and the four discussion writes (post, edit, delete, state) — mounted INSIDE `authenticate {}` and BEFORE every guard by `configureAdrRoutes` and `configureAdrDiscussionRoutes` around the write groups only (the reads on the same paths stay outside). Every one takes the ADR's row lock and a pooled connection and makes history rows and notices, so before this bucket one account's script could queue unlimited writers and flood the notice table and the mail digest. 120 is the `directory` figure: a person drafts, moves and discusses a few times a minute, 2 a second is a script. A refused or invalid write spends a token too (the bucket is checked before the ADR is looked up, so a write now answers 401, then 429, then 404/403/400/409). Lenses and tag categories stay unbucketed (guard-gated, one row each). The rejection is the generic `429` problem with `Retry-After` plus the coalesced `rate_limit.exceeded` event (bucket `adr-write`, `byUserId`). Pinned by `AdrWriteLimitsTest` (120 across the 13, then `429` on each, the audit line, another account and every read untouched).

**Per-USER directory bucket** (`directory`, a fixed 120/min in every mode, `RateLimits.DIRECTORY`) for `GET /api/v1/users/directory`, the ONE read that lets
any signed-in user (not just an ADMIN) list accounts. `configureUserRoutes` mounts `rateLimit(RateLimitName(RateLimits.DIRECTORY))` INSIDE
`authenticate {}` (the telemetry idiom), keyed by the verified user id: a NAT'd office is not one bucket and changing IP buys nothing. A user picker
makes a debounced lookup per keystroke pause (a handful a minute). The route adds two bounds of its own: `q` is REQUIRED and at least 2 code points after
trimming (max 100; code points, `codePointCount`, never UTF-16 units), and a page holds at most 20 rows (`400` beyond). **Honest scope: these slow and audit a
bulk harvest, they do not prevent one.** A signed-in insider can still walk the directory with two-letter queries inside the bucket (up to 120 pages of 20 rows, i.e. 2,400
rows, a minute), and every rejection is the generic `429` problem plus the coalesced `rate_limit.exceeded` audit event (bucket `directory`, `byUserId`, one per user per
minute). `UserDirectoryTest` pins the bounds, 120 then `429`, per user, and the audit event.

**What the user directory discloses, and to whom** (a deliberate widening of the ADMIN-only user list; Adder's decision D2, carried over as a Hammurabi assumption, `product.md`): to every signed-in user, the `id`, `name` and `email` of every ACTIVE account, searchable by name or email
(case- and accent-insensitive). Never roles (who is an ADMIN), feature flags, language, MFA state, timestamps or the password hash: `UserDirectoryEntry` is a
three-field projection built in the service, not the `User` row filtered afterwards, and `UserDirectoryTest` pins the exact key set. A soft-deleted account is
absent (its email is freed for reuse). `%` and `_` in `q` are literal (`containsNormalized` escapes them AFTER the unaccent fold, in SQL: unaccent maps the fullwidth `％ ＿ ＼` to ASCII, so an earlier escape would be undone; `ContainsPatternTest`). **Residual risk:** the email is the login identifier, so
a signed-in colleague who harvests emails can (a) aim failed sign-ins at a victim's account to trigger the per-account lockout, bounded by the per-IP `login` bucket
(10/min in production) and the lockout's own window, and (b) request password-reset mail for a victim, bounded by the per-IP `password-reset` bucket and the
per-email reset throttle (`PasswordResetThrottleTest`). Neither reveals or changes a credential, and the reset link goes only to the account's own mailbox. Collapse
the projection to `{id, name}` if that trade changes.

**Email MFA (opt-in)** (`auth/MfaChallenges.kt` + `auth/MfaEmail.kt`, wired in
`configureAuthRoutes`): a per-user second factor behind the `MFA` feature flag — **the one
inverted-default flag**: `V5` seeds every existing user as MFA-disabled and `UserService.create`
inserts the disabled row for every new user (admin create and test seeds both funnel through it),
so MFA is OFF until an admin removes the row via the features PUT (the per-user editor or the
`/feature-flags` bulk screen; mind the wholesale-replace semantics — a PUT whose disabled set
omits `MFA` ENABLES it, including the "empty array re-enables everything" idiom). With MFA
enabled, `POST /login` with correct credentials normally answers
`200 MfaChallengeResponse {mfaRequired, challengeId, expiresAt}` instead of tokens (the branch runs
AFTER `verifyPassword`, so nothing about enumeration changes; the lockout counter is checked but NOT cleared there, see "ONE counter"
above; `login.success` is deliberately NOT emitted); a 6-digit code (`generateMfaCode`, `auth/Passwords.kt`) is held in the
in-memory challenge store (config `security.mfa.codeTtlSeconds` default 300
`$MFA_CODE_TTL_SECONDS`, `security.mfa.maxAttempts` default 5 `$MFA_MAX_ATTEMPTS`; per-instance
like the throttles — a restart just means signing in again) and emailed in the user's stored
language (V1 `users.language`, EN fallback). Its hard `security.mfa.maxTracked` limit (default
10,000, `$MFA_MAX_TRACKED`) first drops expired challenges, then rejects new issuance with an
audited `429` at capacity; existing codes remain verifiable and no rejected code is emailed. A
failed or cancelled delivery discards its challenge so a retry can use that slot. **An account holds at most 3 live
challenges** (`MfaChallenges.DEFAULT_MAX_PER_USER`, a constant, no config key): a further login REPLACES the oldest (refusing would
let anyone holding the password stop the owner signing in; the replacement needs no spare capacity). **The accepted cost:** a password holder can therefore evict the owner's pending code with three logins of their own; the owner signs in again (a fresh code), and the password reset ends the holder's access. Eviction is no guess: the per-account lockout still bounds every guess at a code ("ONE counter"). The per-challenge attempt cap bounds
one code; **every wrong code also counts into the per-account lockout** (`auth/MfaRoutes.kt`, "ONE counter"), so the guesses across
challenges add up and a fresh login never resets them. The failure that locks the account, and every later wrong code, also drops the account's pending
challenges (`discardUser`), and a correct code arriving while the account is locked is refused with the lockout's `429`
(the counter restarts when a lock expires: the long-run bound is stated in "ONE counter").
Consequence: with the default threshold 5, five wrong codes (the default per-challenge cap) lock the account for 15 minutes.
`POST /api/v1/login/mfa` `{challengeId, code}` (own per-IP bucket `"mfa"`, hardcoded 10/min in
every mode) exchanges it for the ordinary `LoginResponse` — one fresh `userService.read` first
(the `/refresh` precedent: current roles/flags; a user deleted in between → the uniform 401
`user_gone`); every failure mode (unknown/expired/wrong code/attempt cap) is a **uniform 401**,
reasons live only in the `login.mfa_failure` audit event (which names the account by `userId` whenever the challenge was found); challenges are single-use and bound to
the credential revision verified at the password step; a subsequent password change/reset
invalidates the pending challenge. **Mail-less deployments fail closed**: on
`MAIL_TRANSPORT=disabled` an MFA-enabled login answers `503` (`login.mfa_unavailable`) — don't
enable MFA flags on the k8s default `disabled` transport; the features PUT keeps working, so an
admin can always flip the flag back. SPA: the Login card's second step (`PinInput`, `auth.mfa*`
keys). Tests: `MfaChallengesTest` (pure store) + `MfaLoginTest` (route matrix) + `MfaLockoutTest` (the lockout, the per-user cap, the audit `userId`); e2e `mfa.spec.ts`
drives the emailed code through Mailpit.

**Swagger/OpenAPI gate** (`plugins/Routing.kt`): `/openapi` (UI + spec) is served only in development
mode, or when `http.exposeOpenApi` (`$HTTP_EXPOSE_OPENAPI`) is explicitly `"true"` — blank follows
the mode, `"false"` hides it even in dev (the mount lives in `configureRouting`; the CSP exemption follows the same condition). Bearer auth cannot protect a browser-loaded UI (page
loads carry no `Authorization` header), hence a gate rather than `authenticate {}`.

**Accounts are admin-managed.** There is no self-signup: administrators create accounts at
`/users/new`. The initial password is generated CLIENT-side (`web/src/utils/password.ts`, 96
bits) and shown to the admin exactly once in the reveal modal — the server stores only the bcrypt
hash and no response ever carries plaintext; users rotate it via the Change password page (the
existing `PUT /users/{id}/password` rules). Deletion is the soft-delete convention plus two
guards: no self-delete (403) and the last active administrator can be neither deleted nor demoted
(409).

**Canonical email identity.** Emails are folded to `trim().lowercase()` (`canonicalEmail` in
`users/Validation.kt`) at EVERY entry point — login and `findWithIdByEmail` itself (defense-in-
depth) today, and every future create/update/import — so one mailbox is one account and a
padded/case-variant login matches (and shares one lockout bucket). The V1 partial unique index
stays byte-wise (all writes are canonical).

**Request body ceiling.** `RequestBodyLimit` (installed in `plugins/Http.kt`,
the limit is chosen per call by `requestBodyLimit`) rejects oversized bodies with a 413 problem (mapped in
`plugins/ErrorHandling.kt`) before any receive/validation work — a memory-DoS backstop, not a
business rule (the body is buffered before it decodes, so a few concurrent big bodies would otherwise pin real heap); field-level
`maxLength` validation rejects oversized values far earlier on ordinary payloads. **`MAX_REQUEST_BODY_BYTES` = 512 KiB** is the global
ceiling (the foundation's bodies are a few hundred bytes; a feature whose documents outgrow it, the ADR body, sizes its own ceiling from
its field maxima in `requestBodyLimit`, the way Adder sized its thread and message writes), and **`MAX_SMALL_REQUEST_BODY_BYTES` = 16 KiB**
applies to `POST` on the anonymous auth routes (`/login`, `/login/mfa`, `/refresh`, `/password-reset`, `/password-reset/confirm`): an
unauthenticated caller reaches them, and their bodies are a handful of short fields, and to the ADR transition `POST` and the ADR participants `PUT`
(`/adrs/{id}/transitions`, `/adrs/{id}/participants`: one status name and an optional reason of at most 1000 code points - 12 000 bytes even when every one is a `\u`-escaped surrogate pair - or at most 100 `{userId, role}` entries). **`MAX_ADR_REQUEST_BODY_BYTES` = 384 KiB** sizes the
ADR document writes (`POST /adrs`, `PUT /adrs/{id}`): five Markdown fields of at most 20,000 UTF-16 units, 3 bytes each as raw UTF-8, is about 300 KB
plus the name and at most 99 participants (the arithmetic is on the constant); a client that `\u`-escapes every non-ASCII character of five maximal fields
(~600 KB) is not covered on purpose. `AdrTest` posts the maximal document and the oversize bodies of all four routes. The ceilings match the path as ROUTING sees it
(`routedPath` in `Http.kt`: segments percent-decoded, empty segments dropped), so `/api/v1/%6Cogin` or `/api/v1//login/` meets the same
ceiling as its plain spelling instead of slipping to the global one (`PayloadValidationTest`). **Request bodies are never decompressed** (checkup C-SEC-1, Adder's, ported): `Compression` is installed in `CompressResponse` mode, because its default also inflates a `Content-Encoding: gzip` request body after the ceiling counted only the wire bytes (8 KiB of gzip inflated to megabytes); and a request that carries any `Content-Encoding` other than `identity` is refused up front with a `415` problem (a `Setup`-phase interceptor in `plugins/Http.kt`, before any ceiling or parser), so a compressed or merely labelled body is never decoded and never parsed (`PayloadValidationTest`). Declared in the spec on every body-taking operation (13 today; the spec is the source of the count) and covered by the one
`PayloadValidationTest` case.

**Request payload validation (convention — API-SEC-003/API-ERR-005).** Mutating routes validate
payloads up-front and throw `BadRequestException` (→ `400` + `ProblemDetail`) instead of letting
oversized/blank values die in the DB as `500`s. One cross-cutting example: password ≥
`MIN_PASSWORD_LENGTH` (10) chars AND ≤ 71 UTF-8 bytes (`validatePassword` in `users/UserRoutes.kt`
+ `MAX_PASSWORD_BYTES` in `auth/Passwords.kt` — **the bcrypt ceiling**: longer input makes bcrypt
throw, and the 500-vs-401 split would be an account-enumeration oracle, so login's
`verifyPassword` guards it too). Declare limits as `maxLength` in the OpenAPI spec. Keep new
validators feature-local and enforce them **after** the authz guard (403 wins over 400). Covered
by `PayloadValidationTest`. **Hidden formatting characters** (`isHiddenFormatting`, `infra/validation/Text.kt`: the bidirectional embeddings, overrides and isolates U+202A..U+202E and U+2066..U+2069, and the line and paragraph separators U+2028/U+2029, the "Trojan Source" family) are refused wherever a control character is: `sanitizeSingleLine` (names, titles) and `sanitizeMultiLine` (free text that wraps, `\n`/`\t` kept; `SanitizeMultiLineTest`). The zero-width joiner of an emoji sequence stays allowed.

**Outbound HTTP calls (SSRF posture): not ported.** Hammurabi makes no server-initiated HTTP call (SMTP aside), so Flow's `infra/outbound/` guard (the host allow-list, `GuardedDns`, the
blocked-address ranges, `outbound.blocked`) was left in Flow on purpose (`product.md` "Donors"). Port it, with its tests, in the commit that adds the first outbound call, and add its
section and audit event here.

**CORS is off by default** (`plugins/Http.kt`): the plugin is installed only when
`http.corsHosts` (`$CORS_ALLOWED_HOSTS`, comma-separated hosts) is non-empty. Production is
single-origin (Ktor serves the SPA) and dev goes through the Vite proxy, so no cross-origin caller
exists by default — no `anyHost()`. **Reverse proxy**: set `HTTP_BEHIND_PROXY=true` (config
`http.behindProxy`) when TLS terminates at an ingress/proxy — it installs `XForwardedHeaders`,
configured to honour ONLY the canonical `X-Forwarded-Host`/`X-Forwarded-Proto` (Ktor's defaults
also read `X-Forwarded-Server`/`X-Forwarded-Protocol`/`X-Forwarded-SSL`/`Front-End-Https`, which a
proxy that sets just the canonical ones passes through from the client untouched — Lettuce's
v3.6.2 finding, ported) and never `X-Forwarded-Port` (a client-supplied non-numeric value used to
throw a 500 out of `CallSetup` before any route ran; nothing reads it, since the scheme-derived
default port is all the HTTPS redirect needs), so rate-limit buckets key on the real client IP and
the HTTPS redirect sees the real scheme; the proxy must set (and strip client-supplied)
`X-Forwarded-For`/`X-Forwarded-Proto`. **`X-Forwarded-For` is folded before Ktor ever reads it**:
Ktor's `XForwardedHeaders` resolves a for-header via `Headers.get(name)` — the FIRST header LINE
only — so a proxy that APPENDS a fresh `X-Forwarded-For` line instead of merging into one the
client already sent (HAProxy's `option forwardedfor`) would leave the client-supplied first line as
the trusted value. `resolveForwardedForOrigin` (`plugins/Http.kt`) runs at the `Setup` phase —
strictly before `XForwardedHeaders`' own `Plugins`-phase `onCall` — reads every
`X-Forwarded-For` header line via `getAll`, folds them into one hop-ordered list (RFC 2616: same-
name header fields may be combined by joining their values with a comma, in the order received),
and resolves the trusted hop itself directly into the call's `MutableOriginConnectionPoint`;
`XForwardedHeaders` is then configured with an empty `forHeaders` so it never overwrites that
result with its own unfolded, first-line-only read. The hop selection itself is unchanged: read
from the END of the combined list, keyed on `HTTP_PROXY_HOPS` (config `http.proxyHops`,
boot-validated ≥ 1 via `requireConfigInt` — a config error, not a runtime concern): 1 (the default,
one TLS-terminating proxy that APPENDS) trusts the value the last proxy wrote; N skips the N-1
addresses trusted proxies appended after the client's — never the first value, which is
client-supplied and spoofable. A hop count in excess of what the request actually carries falls
back to the last available value (the same fallback Ktor's own `useLastProxy()`/
`skipLastProxies()` apply) rather than throwing. Off by default because honoring these headers
from direct clients lets them spoof both. Covered by `ForwardedHeadersTest`; the multi-line fold
itself needs a real Netty engine to pin (`RawForwardedForLinesTest` writes the raw HTTP/1.1
request by hand — ktor-client's own request writer folds repeated `header()` calls into one wire
line before send, so `testApplication`'s client cannot reproduce two genuinely distinct lines).

**The client address is NUMERIC, with or without a proxy** (Adder's 0.4.0 audit, ported). Every per-IP bucket (the `login`, `refresh`, `mfa`, `password-reset`, `password-reset-confirm` and `ready` rate limits; the login LOCKOUT is per account, not per IP) keys by `clientAddressKey()` (`infra/ClientAddress.kt`, checkup C-SEC-4): `call.request.origin.remoteHost` folded to an IPv4 address whole (a `:port` suffix dropped, the form Azure Application Gateway and IIS ARR write), an IPv6 one by its /64, an IPv4-mapped one as IPv4; a value that is NOT an address literal (junk forwarded text, a hostname) is never a key of its own, it counts under the TCP peer (`call.request.local.remoteAddress`), so rotating `X-Forwarded-For` text mints no buckets (`ClientAddressKeyTest` pins the precedence, `ForwardedHeadersTest`). The fallback is logged: one WARN per ten minutes (logger `ch.nokillswit.infra.ClientAddress`, the number of calls folded, never client text) when the trusted hop is no address. `origin.remoteHost` itself defaults on Netty to `InetSocketAddress.getHostName()`, a REVERSE-DNS lookup: a client with forward-confirmed PTR records for many addresses would get one key per address (and an IPv6 /64 would not group), and one whose PTR is shared or slow would share a key. So `configureHttp` registers a `Setup`-phase interceptor FIRST, before anything reads `remoteHost` (the JDK caches the resolved name), that copies `call.request.local.remoteAddress` (`getHostString()`, the literal) into the origin's `remoteHost`; behind a proxy, `resolveForwardedForOrigin` (registered after it) overrides that with the trusted hop, and a request with no `X-Forwarded-For` keeps the proxy's numeric address. The helper is `numericClientHost` (`NumericClientHostTest`, played by hand because a test host cannot make Netty resolve a name; the proxy-trust tests `ForwardedHeadersTest` and `RawForwardedForLinesTest`, the latter on the real Netty engine, still hold).

**Trust the address or the buckets degrade.** The per-IP buckets are only as good as `remoteHost`. Behind a plain proxy with `http.behindProxy=false`, or through an SNAT load balancer (`externalTrafficPolicy: Cluster`), every client arrives as the proxy's or the node's address and they all share ONE bucket per limit; with `behindProxy=true` and no proxy that strips client-supplied `X-Forwarded-For` (a LoadBalancer or NodePort straight to the pod: the shipped manifests no longer have one, `k8s/ingress.yaml`), a client can still claim any REAL address literal (a fresh IPv4 address per request, or an IPv6 /64 of its own) and so pick its own key; junk text cannot. Fix the trust (a proxy that sets the header and `behindProxy=true`, or `externalTrafficPolicy: Local`). **Behind a proxy the peer IS the proxy**: clients whose trusted hop is not an address (a header format `clientAddressKey()` does not read, or `http.proxyHops` pointing at a hop that is not the client) are all counted under the proxy's address and share ONE bucket per limit, a global lockout; the WARN above says so, so check the proxy's `X-Forwarded-For` format.

CSRF install is gated behind `security.csrf.enabled` (default **`false`** in `application.yaml`,
env-overridable via `SECURITY_CSRF_ENABLED`); the configured `originMatchesHost()` +
`allowOrigin("http://localhost:8090")` + `checkHeader("X-CSRF-Token")` combo is unsatisfiable from
both the Ktor test client and the dev SPA on `:5181`, and CSRF protection is anyway moot for this
app's bearer-JWT auth model — browsers do not auto-attach `Authorization` headers, so cross-site
forms cannot forge an authenticated request. Re-enable only if you move to cookie-based session
auth and fix the allow-list accordingly.

**Default admin.** Migration `V3__seed_admin.sql` inserts a single bootstrap administrator on
first boot: `admin@hammurabi.local` / `changeme` (role `ADMIN`), idempotent via `ON CONFLICT DO
NOTHING`. The migration is kept **unchanged** (dev + e2e depend on it; checksums must not change)
— production neutralizes it at startup via the bootstrap above. There are no demo seed users.
**Kubernetes secrets** live in the `hammurabi-secrets` Secret in the `hammurabi` namespace
(`k8s/secret.yaml` is a placeholder template — create the real one out-of-band with the
`kubectl create secret generic` command in its header; the `app` Deployment consumes it via
`secretKeyRef`).

**Probes and the plain-HTTP crash-loop** (`k8s/app-deployment.yaml`, ported from Toadie): every
probe (`startupProbe`/`readinessProbe`/`livenessProbe`) sends `X-Forwarded-Proto: https` — the
header the TLS-terminating ingress sets, which `HTTP_BEHIND_PROXY`/`HTTP_PROXY_HOPS` above makes
the app trust. Without it, production mode answers the kubelet's plain-HTTP probe request with a
301 to `https://<pod-ip>/`, which the kubelet FOLLOWS (same host) into a refused `:443` — every
probe fails and the pod crash-loops (Lettuce's v3.6.1 incident, reproduced in production).
`startupProbe` (30 x 5s = ~150s) covers JVM boot + Flyway migrations before the liveness clock
starts, so a slow first boot cannot trigger a restart.

**Kubernetes pod and network hardening** (`k8s/*.yaml`, validated by ci.yml's `k8s-static`
job — `kubeconform -strict` over the raw manifests and over what `k8s/apply-local.sh` renders):

- **Pods.** Both (`app`, `postgres`) set `automountServiceAccountToken: false` — nothing
  calls the Kubernetes API, so no token is mounted. `app`: uid 10001, `runAsNonRoot`, seccomp
  `RuntimeDefault`, read-only root filesystem (only `/tmp`; the AppCDS archive `/app/cds/hammurabi.jsa` is a root-owned 0444 file baked into the image and only ever read), no privilege escalation, `drop: [ALL]`.
  `postgres` runs the same way minus the read-only root filesystem: `runAsNonRoot` as the alpine image's
  own `postgres` user (**uid/gid 70** — 999 in the Debian image, so change `runAsUser`/`runAsGroup`/
  `fsGroup` together with the image variant), `fsGroup: 70` with `fsGroupChangePolicy: OnRootMismatch`,
  seccomp `RuntimeDefault`, `allowPrivilegeEscalation: false`, `drop: [ALL]` (a non-root start needs no
  capability: the entrypoint's chown/gosu step only runs as root). `readOnlyRootFilesystem` stays off for
  postgres — it writes its socket/lock files under `/var/run/postgresql` and temp files under `/tmp`.
- **`k8s/network-policies.yaml`.** `default-deny-all` (ingress AND egress for every pod in `hammurabi`), then:
  `postgres` accepts 5432 only from `app`; `app` accepts 8090 only from the ingress-nginx controller's pods (the front-door bullet below);
  `app` may egress to `postgres:5432`, to cluster DNS (kube-system `k8s-app=kube-dns`, 53
  UDP/TCP) and to public addresses on 443, 587/465/25 (SMTP) — RFC 1918,
  link-local (cloud metadata `169.254.169.254`) and CGNAT ranges excluded, so a compromised pod cannot
  reach other namespaces or cluster services. Things that need their own extra policy:
  an in-cluster/private SMTP relay, an OTLP collector (`OTEL_*_EXPORTER=otlp`; the default is console),
  any private upstream. **Cluster assumptions:** DNS is
  matched as pods labelled `k8s-app: kube-dns` in the `kube-system` namespace (stock CoreDNS); a
  NodeLocal DNSCache (link-local resolver address) or OpenShift (`openshift-dns` namespace, different
  labels) needs the DNS rule adjusted, or every lookup times out; SMTP submission on port 2525 and any
  IPv6 destination are not covered (the `ipBlock` is IPv4 only) and need their own rule. Enforcement needs a CNI that
  implements NetworkPolicy (OrbStack's local cluster does). A CNI that does not enforce leaves the objects inert, which is harmless. New
  pods take a few seconds to be programmed, so a very fast first connection can fail once — the
  `startupProbe` window absorbs it.
- **The front door: an ingress-nginx Ingress** (`k8s/ingress.yaml`; ported from Adder's `b15e01e`, 2026-11-20, C-SEC-6 of this repository's 1.0.0
  checkup; Adder verified the contract live on OrbStack, **this repository's copy has not been re-run on a cluster**). The `app` Service is
  `ClusterIP` (no LoadBalancer/NodePort path around the proxy) and `allow-app-ingress` admits `:8090` only from the controller's pods
  (namespace `kubernetes.io/metadata.name: ingress-nginx` AND pod labels `app.kubernetes.io/name: ingress-nginx`,
  `app.kubernetes.io/component: controller`, ONE `from` item); the kubelet's probes need no rule. The two halves belong
  together: `HTTP_PROXY_HOPS=1` makes the app trust the LAST `X-Forwarded-For` entry, which is only the controller's
  when nothing else can reach the pod; the policy is what turns "trust one hop" into "trust only the ingress"
  (the app has no CIDR allow-list; the CNI's policy is that list). CI's `k8s-static` fails on any `LoadBalancer`/`NodePort`
  Service, `hostPort` or `hostNetwork`, requires the app Service to be `ClusterIP` and the policy to name the controller's namespace.
  **Which entry is taken:** ingress-nginx's defaults (`use-forwarded-headers: false`) REPLACE `X-Forwarded-For` with the address of the
  connection nginx accepted (the client's own value moves to `X-Original-Forwarded-For`), so the app sees one entry and
  `resolveForwardedForOrigin` takes it; a client-sent `X-Forwarded-For: 1.2.3.4` never reaches the app as such. With
  `use-forwarded-headers: true` + `compute-full-forwarded-for: true` the controller APPENDS (`client-sent..., peer`) and the last
  entry is still the peer the controller saw; trust more hops only when a real proxy sits in front of the controller (a cloud LB that
  appends: `HTTP_PROXY_HOPS=2`), and never raise the count without that proxy, or a client-written entry becomes the key.
  `use-forwarded-headers: true` is safe only with `proxy-real-ip-cidr` narrowed to the trusted front proxy and together with
  `compute-full-forwarded-for`; it also passes the client's `X-Forwarded-Proto`/`-Host` through, which skips the ssl-redirect. F5's
  NGINX Ingress Controller ignores the `nginx.ingress.kubernetes.io/*` annotations and carries other labels, so with it the policy
  admits nothing and the app answers 502/504 (fail closed). **What "the peer the controller saw" is** depends on the cluster: with an
  SNAT in front of the controller it is the node's or gateway's address, not the browser's, and every client shares one per-IP bucket
  (Adder measured exactly that on OrbStack: the pod-network gateway for every request, `externalTrafficPolicy: Local` or not; no
  audit line carries an address, so read the controller's access log). TLS ends at the controller (`ssl-redirect` 308, HSTS overwritten
  by the controller's own policy); the host, the certificate SAN and `MAIL_APP_URL` move together; `http.corsHosts` stays empty (single
  origin). Body ceiling: the largest app ceiling is 512 KiB (`MAX_REQUEST_BODY_BYTES`), the Ingress pins `proxy-body-size: 1m` so the
  app's own problem+json 413 answers. Community ingress-nginx was retired in March 2026: a production cluster re-verifies this
  paragraph against whichever controller it runs (the contract, not the name, is what the app needs: replace XFF, set Proto/Host,
  60 s+ timeouts). `kubectl port-forward svc/app 8090` and a node-local process still reach the pod, as in any cluster.

**Log hygiene.** Every data class that carries a password, token, MFA code, password hash or signing key
(`LoginRequest`, `LoginResponse`, `MfaVerifyRequest`, `RefreshRequest`, `LogoutRequest`, `User`,
`UserCreateRequest`, `PasswordUpdateRequest`, `PasswordResetConfirmRequest`, `JwtConfig`, the MFA challenge types, `IssuedToken`)
overrides `toString()` and prints each secret
through `maskedSecret` (`infra/Secrets.kt`: `***`, or `null` when absent), so templating one into a log line or an
exception message never reveals it; a new secret-bearing class does the same. Pinned by `SecretToStringTest`. `infra/db/Flyway.kt`'s startup log line renders the operator-supplied JDBC URL
through `jdbcUrlForLogging()` — `host:port/db` only, no userinfo or query string — so a
`jdbc:postgresql://host/db?user=...&password=...` form never lands a credential in the log
(`.claude/docs/observability.md`, "never log secrets"); covered by `FlywayLogTest`. `POST
/api/v1/logout`'s malformed-body debug line (`auth/AuthRoutes.kt`) logs only the parse failure's
exception CLASS NAME, never the throwable — the cause chain can embed a body excerpt (kotlinx's
decode-error message), the same `errorType`-only rule as `login.mfa_send_failed`/
`password_reset.send_failed`; covered by `LogoutTest`. The delivery-failure ERROR lines (`auth/MfaEmail.kt`, both of `auth/PasswordResetEmail.kt`'s) log the class name (the MFA one also the user id), never the address or the throwable (an SMTP refusal can echo the recipient and server detail); `MfaLoginTest` pins the MFA line against a server that does exactly that. **A failed database statement never logs its bound values**: a database exception's MESSAGE is unsafe (Exposed's `ExposedR2dbcException.getMessage()` appends the failed statements with their arguments expanded whenever the transaction's `debug` flag is on, and Postgres itself echoes values into some messages: an input-syntax failure, a `RAISE`, a malformed tsquery), so the 500 catch-all logs a chain holding one (or any suppressed exception, or a chain past the walk's bound: fail closed) as class names, the SQLSTATE and stack frames only (`plugins/ScrubbedFailure.kt`, `observability.md` "The 500 line"); any new log site for a throwable that may come from the database does the same (`scrubbedForLog()` for a throwable, `scrubbedSummary()` for one line), or logs `javaClass.simpleName`; the telemetry collector failure line and the readiness probe's DEBUG line already do. Two loggers that print a cause below INFO (`Exposed`, `io.ktor.server.plugins.statuspages`) are pinned to INFO in `logback.xml`, and so are the R2DBC driver (`io.r2dbc.postgresql`: its QUERY and PARAM loggers print every statement with its bound values at DEBUG) and Ktor's authentication and rate-limit loggers (they trace `request.uri`, the query string included). **Spans too:** `core`'s `ScrubbedSpanExporter` wraps every configured trace exporter so a recorded exception leaves as `exception.type` only, never `exception.message`/`exception.stacktrace` (`ScrubbedSpanExporterTest`); the exporter drops the request's query string (`url.query` and its full-URL spellings) too, since it holds what a user typed into a search or a filter. `LogHygieneTest`'s echo test makes a team INSERT fail with a database message that carries the name and asserts it in no ROOT-logger event (it fails on the unscrubbed catch-all); its cancelled-statement (57014) test is only a shape pin that the line still names the driver class and the SQLSTATE, not a leak proof.

**Outbound email** (`infra/mail/`, ported from Lettuce): `configureMail` (registered at the top of
the infrastructure group, before Flyway) publishes `MailerKey` holding a `Mailer` or null.
`mail.transport` (`$MAIL_TRANSPORT`) selects `log` (dev default — the full message, **including
reset links and MFA codes**, goes to the `ch.nokillswit.mail` logger; **production mode refuses to
start on it**), `smtp` (Jakarta/Angus Mail over `mail.smtp.*` / `$SMTP_HOST` etc.; a blank host
refuses startup in any mode), or `disabled` (the Docker image default via
`ENV MAIL_TRANSPORT=disabled` — email features answer 503 through `respondMailUnavailable`). The
compose demo wires `smtp` → the bundled Mailpit (`http://localhost:8031` — 8025–8029 belong
to sibling stacks); k8s ships `disabled` with `SMTP_USER`/`SMTP_PASSWORD` read (optional) from
`hammurabi-secrets`. Consumers: self-service password reset and email MFA. The recipient-language
content layer (`LocalizedText`) lives beside the transports; `mail.appUrl` is validated here too (above). Tests:
`MailTransportTest` (the transport matrix + both refusals + LogMailer delivery via the
`ch.nokillswit.mail` LogCapture); production-mode boot tests must override `mail.transport` with
`"disabled"`, since the dev-default `log` transport is refused in production.

**Encryption at rest** (`infra/crypto/`, Lettuce's, ported; no column stores an encrypted value yet (the field cipher has no consumer; its production boot check is live) — the first consumer will be a stored credential, e.g. a bot token). Sensitive columns are encrypted application-side with AES-256-GCM (`FieldCipher`: a
fresh 12-byte nonce per value, 128-bit tag, envelope `enc:v1:<base64(nonce||ciphertext)>`), so a
database-level attacker — SQL access, `pg_dump`, a stolen volume or backup — sees ciphertext; the
key lives with the app (`DATA_ENCRYPTION_KEY`, 64 hex chars from `openssl rand -hex 32`; compose
ships a burned demo key, k8s reads `hammurabi-secrets`), never in the database. **Losing the key loses
every encrypted value** — back it up apart from the DB. Rotation: set the OLD key as
`DATA_ENCRYPTION_KEY_PREVIOUS` (decrypt-only fallback — a wrong-key attempt fails cleanly on GCM's
tag, then the previous key is tried) and the new one as `DATA_ENCRYPTION_KEY`, then re-encrypt every row under the
current key and remove the previous key. **Not ported yet:** Flow's `EncryptedAtRest` registry (a service owning encrypted columns registers
in `infra/db/Bootstrap.kt`; never remove a registration once one lands — a rotation would strand that feature's rows) and `Reencrypt.kt` (`reencryptRows`,
the boot backfill/rotation body, selecting through the ONE sanctioned SQL predicate over an encrypted column: `notLike "enc:v1:%"`) have no consumer here and
come back with the first encrypted column. **Never filter or sort on an encrypted column in SQL** — ciphertext carries no order or equality (every value has its own nonce).
A credential column is write-only end to end: accepted on create/update, never returned, never in an audit line (the response carries a `has…` boolean instead).
Tests: `FieldCipherTest` (roundtrip, fresh nonces, legacy passthrough, tamper, wrong key, rotation, malformed keys), `CryptoBootTest`.

**Trusted PostgreSQL extensions.** `unaccent` (V4) is a contrib extension trusted since PG13: `CREATE EXTENSION IF NOT EXISTS` needs only `CREATE` on the
database, no superuser. Any further contrib extension a migration needs is checked for the same trust before it is relied on.

### Search (`GET /api/v1/adrs?text=`, V15, 0.5.0)

The list's full-text `text` is the surface that reads ADR text in bulk (Adder's search hardening, ported and trimmed to one table: every ADR is readable by every signed-in user, so there is no scope to leak, only cost to bound). Bounded three ways. (1) **ONE deadline per request** (`search.timeoutSeconds`, `$SEARCH_TIMEOUT_SECONDS`, default 5, boot-validated 1..30; `infra/db/SearchBudget.kt`): each statement's `queryTimeout` (the only way a statement timeout may be set, `persistence.md`) is the whole seconds LEFT of the request's budget, so the sum of the statements is bounded; a cancelled statement (`57014`) or a deadline already spent between two statements is the typed `429` `urn:hammurabi:problem:search-timeout` with `Retry-After: 2`, audited `search.timeout` (`byUserId`; ids only), and the throwable is not logged. (2) **A process-wide concurrency cap** (`search.maxConcurrent`, `$SEARCH_MAX_CONCURRENT`, default 4, boot-validated 1..16; a PRODUCTION boot refuses a value that reaches `postgres.pool.maxSize`): a `Semaphore` `tryAcquire` BEFORE a pool connection is asked for; a search past it is refused at once, never queued, as `429` `urn:hammurabi:problem:search-busy` with `Retry-After: 1`, audited `search.busy`. A plain list (no `text`) takes no permit. (3) **Page size 20 and a 100-character term** (2 to 100 code points, a letter or digit, no control character: `400`). There is deliberately NO per-user token bucket on `GET /adrs` (the SPA lists on every filter change; the cap and the deadline bound the cost, and the whole table is one GIN-indexed `tsvector` match, not Adder's per-thread probe). **No input is a query error or an injection**: the tsquery is built by the database from `to_tsvector`'s own lexemes (`infra/db/Fts.kt`), the text is only ever a bound argument, and `&`, `|`, `!`, quotes and `:*` are punctuation. **Snippets are typed segments `{text, hit}`, never markup**: `ts_headline` returns its input untouched, so the server headlines with the control markers U+0002/U+0003 (unforgeable: every name and Markdown field refuses control characters), splits on them and the SPA renders `text` as text. **A search term is user content**: it travels in the query string and reaches no log line, no audit line and no problem body beyond the requester's own `400` (which never echoes it; `AdrSearchTest` plants a marker in every path). A saved lens holds the term in its stored filters and never in an audit line. **No span carries the query string**: Ktor's server spans record it as `url.query`, so `core`'s `ScrubbedSpanExporter` drops `url.query` (and `url.full`, `http.url`, `http.target`) from every span before any exporter sees it (`ScrubbedSpanExporterTest`); the path and route stay. Tests: `AdrSearchTest`, `SearchBudgetTest`, `FtsTest`, `AdrSearchSchemaTest`.

### Notices (`/api/v1/notifications`, V17, 0.6.0)

Adder's notices, reshaped from chat to ADRs (`adr-model.md` "Notifications"). The surface is the caller's OWN rows, so it needs no guard and has nothing to disclose (every ADR is readable by every signed-in user anyway). (1) **A notice is ids and one short detail**: the actor, the ADR (and the second ADR of a successor notice) and a role, status (the send back is its own word, `SENT_BACK`), entry kind or date; no name, ADR text, discussion body, transition reason or search term is stored, so none can leak, be scrubbed or reach a log; the actor's name and the ADR's key and name are read when a page is read. (2) **Flooding is bounded by what already bounds the write** (the `adr-write` bucket, 120 writes a minute per user, since 1.2.0, and (5) below for the mail): a notice is made inside an ADR write (a participants PUT reaches at most 198 people: it can remove 99 and add 99 in one request, and each is a history row and a bell notice), as ONE multi-row `INSERT ... SELECT FROM unnest(...)` of constant text for ALL the events of that write (`AdrEventService.recordAll` hands them over together and `NotificationService.makeAll` inserts them in a single statement - a creation or a participants PUT with 100 people is one insert, not 100, which matters most for the creation that holds the numbering advisory lock; the recipient audiences are resolved first, by one short read per role-audience plan and none for a named person; the 1.0.0 `EXPIRY_CHANGED` notice of an `ACTIVE` ADR's changed date is one more role-audience plan, its status condition an `EXISTS` inside that same read, so it adds no statement and a draft's date makes none; only the Approver or ADMIN can change a decision in force's date, so a Driver cannot make it at all); **accepted residual (the bucket counts requests, not fan-out)**: 120 writes a minute at 198 people each still allows up to 23,760 `adr_events` rows and as many bell notices a minute from one account (about 34 million a day; history is append-only, notices go with the 90-day retention), and the mail budget below spends at most 200 of it; charging the bucket by fan-out is a filed backlog item; **a recipient's rows are not capped, so their READS are**: the list's count reads at most 1,001 rows and the API answers `total` capped at 1000, a page starting at row 1000 or later is empty without the OFFSET, and `seen-all` works in chunks of 1,000 rows, each its own short transaction. (3) Rows are deleted with their ADR or user (foreign keys) and `notifications.retentionDays` (90, 1..3650) after they were made, by the sweep's hourly housekeeping in batches of 1,000 (`FOR UPDATE SKIP LOCKED`, off `idx_notifications_created`), never inside an ADR write. A notice of a soft-deleted ADR is hidden by the list. (4) The kind is a persisted enum name with no CHECK: a name this build does not know is invisible, never fatal. (5) **The per-actor mail budget (V21, C-SEC-4, 1.2.0; Adder's V24 with a counter of notices).** The 0.6.0 reasoning behind leaving Adder's budget out was right about CONTENT: no ADR name or free text reaches a mail, so a Driver cannot make the digest say anything of their choosing. It missed VOLUME through the shared mailer: a Driver re-adding 98 participants every digest interval mails each of them up to 96 times a day, naming the actor, and the provider's quota is what MFA codes and password resets share. So `NotificationEmailBudget` (`notifications.email.maxNoticesPerActorPerDay`, `$NOTIFICATIONS_EMAIL_MAX_NOTICES_PER_ACTOR_PER_DAY`, default 200, boot-validated 1..10000) counts the notices an account's writes make in a UTC day: the first N stay eligible for the email, every later one is made for the bell (never dropped) and its email is withheld for good (`emailed_at` stamped with `created_at` at insert; not delayed, no "+N more" summary). The unit is the NOTICE, not Adder's distinct recipient (an ADR's audience is up to 99 people, so a count of people neither fits honest use nor closes the lever); EVERY notice made counts, even a second one to the same person and a kind its recipient switched email off for (that switch is read at send time, so it cannot be known at insert). An ADMIN actor is NOT exempt. **System-made notices are charged too** (the expiry sweep's `ADR_EXPIRED` to every role, the reminders; no actor): a Driver and an Approver (or one ADMIN) could otherwise create an ADR with 99 participants and an expiry date of today, activate it and have the sweep mail all of them unbudgeted at midnight, or toggle the date inside the 14-day window to re-arm a reminder. They are charged to the ADR's CURRENT Approver (the person who decides when a decision in force ends: activation and, once `ACTIVE`, the expiry date are the Approver's or ADMIN's), the event's actor staying null (history and "never notify the actor" unchanged; only the charged account differs, and the audit line's `byUserId` names it); an ADR with no active Approver (none, or soft-deleted) is the one exemption. A reminder is also re-armed at most once per ADR per UTC day (`adr-model.md`). A role audience is admitted Driver, Approver, Contributors, Informed, then user id, so the leads are the ones mailed when the budget runs out inside one audience. The row is `notification_email_budget (actor_id, day)` (`persistence.md` "Notifications"), locked `FOR UPDATE` as the FIRST lock of every write that can make notices (before the ADR rows and before the numbering and link advisory locks, so a write queued behind the account's other writes never holds a global lock while it waits, and the order is the same everywhere: no cycle; the reservation is transaction-scoped and fixes the account and the UTC day, so a later change of the Approver or the passing of midnight cannot ask for another row late), so concurrent writes charged to one account never overshoot; removing and re-adding participants cannot reset it; a rolled-back write spends nothing. **Every write that withholds something is audited** (`notification.email_budget_exceeded`, `observability.md`: `byUserId`, `adrId`, `budget`, `withheld`; at most 120 writes a minute per account by the `adr-write` bucket), so a run that silences a recipient leaves a line per write, not one per day. What else bounds mail: one digest per person per `notifications.email.minIntervalSeconds`, at most `maxPerRun` mails a tick, the per-kind email switch, and the `adr-write` bucket above on the writes that make notices. `NotificationEmailBudgetTest`.

**The notices' email digest** (`notifications/NotificationDigest.kt` + `NotificationEmail.kt`, the third `Mailer` consumer after MFA and the reset). The user's decision (Adder's): a digest, on by default; a notice still unseen after `notifications.email.delaySeconds` (300; 0..86400) is mailed, at most one email per person per `notifications.email.minIntervalSeconds` (900; 0..86400), both boot-validated on every boot and refused together when the larger plus `notifications.sweepIntervalSeconds` reaches 86400 (nothing older than a day is mailed). (1) **What it may say**: who (the actor's name, or "Hammurabi" for the system), what, the ADR's KEY and a link, in the recipient's stored language; **never an ADR name or any ADR or discussion text**: `DigestEntry` has no field that could carry one. Names pass `mailSafe` (Adder's, verbatim: format characters dropped, NFC, control characters to a space, `@`, `://`, backslashes and every continuing full stop made unclickable), the subject is a count with the Polish plural rule and holds no name. (2) **Links** are built from `mail.appUrl` and the ADR id only (`/adrs/{id}`, `/notification-preferences`); with it blank the mail has none. (3) **Who is mailed**: re-checked at SEND time in one short read: the account is active, the notice still exists unseen, the ADR still exists and the kind is not switched off for email at that moment; what fails is skipped silently and stays claimed. (4) **Claim, then send** (Adder's, unchanged): one statement stamps `emailed_at` per PERSON (`FOR UPDATE SKIP LOCKED`) BEFORE any send, so a notice is mailed at most once and a failed send is audited (`notification.email_failed`, the class name only) and not retried; three failures in a row end the run and release the unattempted claims; at most `notifications.email.maxPerRun` (100, 1..1000) mails a tick protect the MFA and reset mails that share the `Mailer`. (5) **A null mailer** (`mail.transport=disabled`) makes the digest do nothing and say so once; the bell is unaffected and startup is never refused for it. `NotificationDigestTest`, `NotificationEmailTest`.

### Telemetry

`telemetry/` (`observability.md` "Telemetry") records Hammurabi's own vital signs into `ops.*` and serves them to ADMINs only (`GET /api/v1/telemetry/current`
and `/history`, `authorization.md`; per-user bucket `telemetry`, 30/min). What it discloses, and to whom — every ADMIN, nobody else (a USER gets the
uniform `403`):

- **Process identity:** the role (the constant `app`), the pod / host name (`HOSTNAME`, up to 100 control-free characters), the JVM start, the image's build version and
  commit, the processor count, the heap maximum and the container memory limit - and the live memory, CPU, thread, GC and connection-pool figures.
  NEVER JVM arguments, environment variables, system properties, request paths with identifiers or any user or ADR data (pinned: the `ops` tables hold
  numbers and these labels only).
- **Route usage:** the normalised API routes (`/api/v1/users/{id}`, path parameters as written, never an identifier) with their request counts,
  latencies and 5xx / 429 counts. The telemetry routes themselves and the probes are excluded.

**Metric-name cardinality is bounded.** Ktor's `DropwizardMetrics` (3.6) names a meter after `request.uri` - query string included - for every response it
could not route and for every second response of a call (the StatusPages re-answer of the RateLimit plugin's bodiless `429`, routing's `405`), and a
registered Dropwizard instrument is never evicted: an unauthenticated client (`POST /api/v1/login?x=<random>` past the login bucket, random unrouted
paths or methods) could mint one meter per distinct URI until the heap is gone, and the periodic `Slf4jReporter` would print each of them, query
strings included. So `plugins/Monitoring.kt`'s `TelemetryRegistry` registers NO `ktor.calls.` name containing a `?` (the caller gets a detached,
unregistered instrument), caps the whole `ktor.calls.` family at 2,000 registered names (past it: detached instruments and one WARN), and the reporter
filters out any name with a `?` as defence in depth. Pinned by `TelemetryRegistryTest` (a flood of unrouted, 405 and rate-limited requests with varying
query strings registers none). The sampler reads the registry's timers and meters only, so detached instruments cost it nothing.

### Not yet ported

Flow's outbound-call guard (above) and its `EncryptedAtRest` registry / `Reencrypt` boot backfill ("Encryption at rest") have no consumer here and come back with the first feature that needs
them; new subsystems arrive with their own section in this file.
