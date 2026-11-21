### Authorization model

Layered RBAC. Implemented in the `server/src/main/kotlin/authz/` package.

- **Global roles are an additive set**: every user is implicitly a regular user (USER is the
  baseline — never transmitted), and additional roles — currently only `ADMIN` (`UserRole` in
  `users/User.kt`; a future role is just a new enum value) — only ever ADD privileges. The API
  carries them as a `roles` array (`[]` = regular user); `isAdmin()` (`authz/Guards.kt`) =
  `ADMIN ∈ roles`. Storage is a single `users.role` column with a CHECK (V1) — the **wire shape is
  already the set** (`roles` claim + `LoginResponse.roles`), so moving to a Lettuce-style
  `user_roles` join table when a second additional role arrives never breaks issued tokens or
  clients. **ADMIN is the management role**: it owns the whole user-management surface (the
  `/users` CRUD below) and may reset any user's password (`requireSelfOrAdmin`'s admin branch);
  the management surfaces attach to it (users and flat-team CRUD).
- **Login** issues a **pair** of JWTs, both carrying `email`, `userId`, a `roles` array claim, a
  `credentialRevision` and a `typ` claim (`"access"` / `"refresh"`), minted by `auth/Tokens.kt`
  (`JwtConfig.issueAccessToken` / `issueRefreshToken`). The short-lived **access** token
  (`jwt.accessExpiresInSeconds`, default 900) is the API bearer; the longer-lived **refresh**
  token (`jwt.refreshExpiresInSeconds`, default 3600) is exchanged at `POST /api/v1/refresh` for a
  fresh pair. `LoginResponse` (also the `/refresh` response) exposes `token`/`expiresAt`,
  `refreshToken`/`refreshExpiresAt`, `userId`, and `roles`. The `jwt {}` verifier in
  `plugins/Security.kt` additionally requires `typ == "access"` and runs the per-request account check
  (`TokenBlocklistService.accessVerdict`: one uncached statement - non-revoked `jti`, user exists and not deleted,
  `credentialRevision` and `roles` equal the stored ones; `security.md` "JWT model"), so a refresh token cannot
  authenticate an API call and a stale access token is a `401` on its very next request.
- **Session auto-extension (pure sliding).** `/api/v1/refresh` (rate-limited, no `authenticate` —
  the access token may be expired) verifies the refresh token's signature/issuer/audience/`typ`/
  non-revocation, then does **one** `userService.read(userId)` to confirm the user is still active
  (soft-delete-filtered) and pick up their current roles/email, **requires the signed
  refresh-token `credentialRevision` to equal `users.credential_revision`**. Every password
  change/reset/bootstrap rotation increments that revision atomically with the hash update, so
  even changes in the same millisecond invalidate earlier credentials. Missing or malformed
  revision claims are rejected. The refresh token is **rotated**: after those checks pass, its `jti` is claimed
  with one atomic insert into the blocklist (`TokenBlocklistService.claimRefresh`, until the token's own expiry) and a fresh
  pair is minted - only the claim is atomic, not the delivery of the response. So it works once; a replay inside the
  60-second grace (`security.refresh.replayGraceSeconds`; covers a lost response and the loser of two racing tabs) is
  answered with another pair (at most twice per token, and a logout that reaches the route ends the grace of that user's rotations) and audited
  `refresh.replay_grace`, a replay after it is the uniform `401` with
  `refresh.rejected reason=replayed` (`revoked` stays for logout-revoked tokens; the grace/records are in memory,
  per instance, and a restart falls back to strict - `security.md` "Token storage"). There is no family-wide revocation; the superseded access token lives until its own expiry; an idle session (no refresh
  within the refresh TTL) ends, as does one hit by a password change/reset or account delete (`security.md` "Token storage"
  states the consequence). Every rejection is audited (`refresh.rejected` with `reason`). Frontend:
  `web/src/api/http.ts` `authedFetch` silently refreshes (single-flighted within the current
  session, and serialised across tabs by a Web Lock where the browser has one) and retries once on a `401`; a refresh rejected because another tab already
  rotated the pair adopts the pair in storage instead of signing out; on a definitive refresh rejection it clears only the
  session that initiated the request. Responses from an obsolete session cannot publish
  credentials, clear a newer session, or retry an operation as the newer account. Logout clears
  local credentials immediately and revokes its captured token pair best-effort; when its access token has expired
  (`/logout` needs one) it renews the pair detached from the cleared session and revokes the NEW pair.
- **The public surface** is exactly seven operations, each declaring `security: []` in the spec:
  `POST /login`, `/login/mfa`, `/refresh`, `/password-reset`, `/password-reset/confirm`, and the two probes
  `GET /api/v1/health` (liveness — the process answers) and `GET /api/v1/ready` (readiness — a
  database round trip within a short budget, else `503`; `plugins/Health.kt`). Probes disclose
  nothing but `{status: ok}`; everything else sits inside `authenticate {}` (`AnonymousAccessTest`
  sweeps it from the spec — the "401 sweep": every operation without a declared `security: []` is
  probed token-less, so a new route is covered the moment its spec entry lands).
- **A reset token authorizes exactly one thing**: replacing that one account's password through
  `POST /password-reset/confirm`, never a bearer-authenticated call. The confirm checks the grant before validating the
  password (a uniform `401` for an unknown, expired, used or stale grant, so `401` wins over `400`), then rechecks expiry,
  the credential revision and the email under a user-row lock while it consumes the grant and writes the password in one
  transaction. Confirmation invalidates every session of the account and any pending MFA challenge; normal sign-in,
  including enabled MFA, still follows. The authenticated ADMIN password `PUT` is unchanged. Details: `security.md`
  "Self-service password reset by confirm link".
- **Guards** in `authz/Guards.kt` are plain `suspend`-friendly function calls used at the top of
  each route handler — there is no Ktor plugin or DSL. Each route reads `call.caller()` (which
  parses the JWT claims into a `CallerPrincipal{userId, email, roles}` — missing/unknown claims
  throw `UnauthorizedException`) and then invokes the relevant guard: `isAdmin(caller)`,
  `requireAdmin(caller)`, `requireSelfOrAdmin(caller, targetUserId)`; new guards join
  this file so the whole policy stays in one place. (Flow's `requireTeamManagerOrAdmin` and `TeamService.isActiveManager` were
  deleted in the 1.0.0 checkup, C-SMELL-1: no route ever consumed them; a team-scoped right ports them back with its first consumer.)
- **Resource rules**:
  - `/api/v1/users` (list/create) and `PUT`/`DELETE /api/v1/users/{id}` → **ADMIN only** — the
    whole management surface is admin-gated (the list included; regular users keep only the self
    password change, and the narrow user-picker directory below). `GET /users/{id}` → target user or ADMIN, **guard-before-read** (an
    unauthorized caller 403s uniformly whether or not the id exists). Two protections with a
    deliberate 403/409 split: **self-delete is a `ForbiddenException` (403)** — a
    permissions-shaped rule about WHO acts — while **deleting or demoting the LAST active
    administrator is a `ConflictException` (409)** — a state-shaped rule about what the system
    can afford to lose. Initial passwords are client-generated (the SPA) and stored only as
    bcrypt hashes; no response carries plaintext.
  - `GET /api/v1/users/directory?q=&page=&pageSize=&sort=` → **any authenticated user, no guard** (so no 403; the 401 is `authenticate`'s): the
    user-picker lookup (the DACI pickers: anyone may be named Driver, Approver, Contributor or Informed, and `GET /users` is ADMIN-only). It is a
    deliberate, narrow exception to the "management surface is admin-gated" rule above, and it is NOT the ADMIN list opened up: a separate route
    (the literal `directory` segment wins over `{id}`), a separate service method (`UserService.directory`) and a separate projection,
    `{id, name, email}` of ACTIVE users only (`UserDirectoryEntry`; roles, feature flags, language and the password hash never leave the service).
    `q` matches name OR email (`containsNormalized`); `q` is REQUIRED and 2 to 100 code points after trimming, a page holds at most 20 rows, `400`
    otherwise; sortable `name` (default) and `id`. Because it lets a regular user list accounts it rides the per-USER `directory` bucket (120/min,
    `security.md`; `429` beyond). Those bounds slow and audit a bulk harvest; they do not prevent an insider enumerating (the name+email disclosure
    to any signed-in user is accepted, `product.md` "Assumptions"). A read: no audit event (a 429 is the generic `rate_limit.exceeded`). The ADMIN
    `GET /users` is unchanged. Tests: `UserDirectoryTest`.
  - `/api/v1/teams` (V6, flat teams) → `GET` list and `GET /{id}` **any authenticated user**
    (the Teams page reads them); `POST` and `PUT`/`DELETE /{id}` **ADMIN only**, with `requireAdmin` BEFORE the body decodes and the
    id lookup (guard-before-read: a non-admin probe gets a uniform 403 whether or not the id exists, and a
    non-admin's malformed body stays 403). A
    case-insensitive name clash with an active team `409` (the V6 partial index). The optional
    `managerId` (create and the full-replace PUT — omitted = no manager; named `managerUserId` until 0.9.x) must be an ACTIVE user,
    else `400` ("Unknown or deleted manager ids"); `TeamResponse`/`TeamListItem` carry `managerId` +
    `managerName`, both null for a soft-deleted manager. Being a team's manager grants NO write on the team
    record itself (still ADMIN only) — a team-scoped right a manager might get later would need a new guard in `authz/Guards.kt`. The PUT has no revision: **the last write wins**, stated in the spec (the team is one short record an ADMIN edits). Mutations audit `team.created/updated/deleted` and `team.manager_changed`
    (only on an actual change). Tests: `TeamTest`, `GuardsTest`.
  - `PUT /api/v1/users/{id}/features` → ADMIN only; **self allowed** (an admin adjusting their own
    flags is a feature, and the users routes are never gated, so a fully self-disabled admin can
    always get back); unknown/soft-deleted target → 404; unknown feature name → 400 (enum
    decode); wholesale replace (idempotent — same-set re-PUT is 204, not 409); audited
    `user.features_changed` (featuresFrom/featuresTo) only on an actual change. **Per-user feature
    flags (V5, Lettuce's model)**: `Feature { MFA }` stored as the DISABLED set
    (`user_disabled_features`, no row = enabled). **`MFA` is inverted-default and login-scoped** —
    every user starts with the MFA row present (the V5 seed + the `UserService.create`
    chokepoint), so email MFA is opt-in; it gates the LOGIN FLOW only, and no route is gated on a
    feature yet (the first gated area adds a `requireFeatureEnabled(caller, feature)` guard to `Guards.kt`, run as the FIRST
    guard — before any read, so a disabled caller gets a uniform 403 even for a missing id). Mind the wholesale-replace PUT: a disabled set omitting MFA — including the
    empty "re-enable everything" array — ENABLES the second factor. The set rides both JWTs as
    the `disabledFeatures` claim (missing claim = empty — outstanding tokens survive the deploy;
    unknown value = 401) and `LoginResponse`/`UserResponse.disabledFeatures`, so a change takes
    effect at the target's next refresh (≤15 min) or login. Users list:
    `feature=<F>&featureEnabled=true|false` filter pair (must come together, 400 otherwise). SPA:
    the session's `disabledFeatures` (localStorage `hammurabi.auth.disabledFeatures`), the per-user
    editor `/users/:id/features` (the Users table's Features button) and the admin per-feature
    screen `/feature-flags` (state filter + bulk enable/disable over every row matching the
    current filters, behind a count-stating confirm — a client-side loop of the same per-user
    wholesale PUTs). Tests: `FeatureFlagsTest` + the GuardsTest/AuditTest cases.
  - `PUT /api/v1/users/{id}/language` → **target user or ADMIN** (`requireSelfOrAdmin`, the V1
    `language` column — Lettuce's model): the ONE synced per-user language, set at create
    (`UserCreateRequest.language`, default `"en"`), applied to the SPA at sign-in (rides
    `LoginResponse`, applied by `persistSession` on login/MFA/refresh) and used for every email
    sent to the user (read fresh at send time). The header language switcher is the self-service
    writer (fire-and-forget save on switch); the whole-user PUT deliberately never touches it.
    Unsupported code → 400 (after the guard — 403 wins); idempotent; audited
    `user.language_changed` on an actual change. Tests: `UserLanguageTest`.
  - `PUT /api/v1/users/{id}/password` → target user or ADMIN (`requireSelfOrAdmin`). New password
    must be ≥ 10 chars (`MIN_PASSWORD_LENGTH` in `users/UserRoutes.kt`) and ≤ 71 UTF-8 bytes
    (`MAX_PASSWORD_BYTES`, `auth/Passwords.kt` — the bcrypt ceiling), else `400`. A **self-change**
    (even by an admin) additionally requires a correct `currentPassword` in the body (else `403`,
    audited `password.change_denied`) — checked BEFORE the length validation, so 403 wins over
    400 (the convention everywhere); an admin resetting **another** user's password does not. The route rides the
    per-user `password-change` bucket (5 a minute, before the guards) and a wrong `currentPassword` counts into the
    account's login lockout (`security.md` "Per-account login lockout"): a locked account's self change answers the
    lockout's `429`, after the guard and BEFORE any password check; the admin-resets-another path neither counts nor is
    refused by the target's lock. Tests: `PasswordChangeTest`. A
    successful change stamps `users.password_changed_at` and atomically increments
    `users.credential_revision`, which invalidates all outstanding refresh tokens and pending MFA
    challenges (see "Session auto-extension") and, through the per-request revision check, every outstanding
    ACCESS token too - the token a self-change was made with is a `401` on the next request (the SPA signs out
    explicitly). Covered by `PasswordChangeTest` and `StaleAccessTokenTest`.
  - **A delete, a demotion/promotion or a password change takes effect on the account's very next request.** An
    access token's `jwt {}` validation (`plugins/Security.kt`) runs ONE uncached statement over the user row
    (`TokenBlocklistService.accessVerdict`): a soft-deleted user is `401` (`access.rejected reason=user_gone`), a token
    whose `roles` differ from the stored role is `401` `roles_changed` (the SPA's `401` -> `/refresh` then succeeds
    with the NEW roles and re-renders; a delete or password change makes the refresh fail too, which signs the user
    out), a changed `credentialRevision` is `401` `credential_changed`, a logged-out `jti` is `401` `revoked`. The
    `ADMIN` guards therefore never run on a stale role, `caller()` still reads `roles`/`disabledFeatures` off the (now
    verified) claims, and `disabledFeatures` alone still lags to the next refresh (`FeatureFlagsTest`). Over HTTP the
    caller of `DELETE /users/{id}` is therefore always an active admin, so the last-admin `409` is reachable only
    as a race (the caller deleted between the access check and the locked admin count; pinned with a lock-wait test in
    `UserRoutesTest`) - the self-delete `403` is the HTTP answer.
  - **`/api/v1/adrs` — the ADR/DACI matrix** (`adrs/AdrAccess.kt`, `AdrRoutes.kt`, `AdrService.kt`; `product.md` "Decisions", `adr-model.md` "DACI roles and verdicts"): "the Driver drives, the Approver decides", ADMIN overrides every verdict. Every authenticated user **reads** every ADR (`GET /adrs`, `/{id}`, `/{id}/events`: an open decision log, so a missing ADR is a plain `404`, nothing to disclose) and **creates** one, becoming its Driver. The role a caller holds on an ADR (`adr_participants`) is read per request, in the database, inside the write's transaction — never from the JWT, so a participant change takes effect on the next request. **Read-before-guard**: a write first resolves the ADR (`404`), then the coarse guard, then the header and body; the same guards run AGAIN under the row lock on the fresh role.
    - **Order of one write** (a `403` always beats a `400`): standing (404) → coarse role guard (403) → `X-Expected-Revision` header (400; all three writes require it) → body decode and validation (400) → under the lock: role re-read, coarse guard again (403), then per write — document PUT: terminal (409 `adr-frozen`) → **stale revision (409)** → per-field verdict (403) / content freeze (409) / past date (400); transition: per-move verdict (403) → lifecycle map (409 `illegal-transition`) → stale revision (409) → the optional `reason` (400) → requirements (400); participants: no self-approval (403) → terminal (409) → stale revision (409) → the Approver-seat verdict (403, against the locked participants) → users (400) — then write and events. The stale revision comes before the per-field verdict so a stale save is a `409`, never a false `403` (which would also write a false `authz.denied`). The per-target verdict needs the body (`to`) and the per-field one the diff, so they cannot run before it; `to` is the only body field the verdict needs, so a malformed one is `400` for a caller who passed the coarse guard; the optional `reason` is judged under the lock after the revision, so a wrong role is `403` and a stale save `409` whatever it holds.
    - **`PUT /adrs/{id}`** (document): coarse guard = Driver, Contributor, Approver or ADMIN (Informed and strangers `403` before the body). Under the lock: a terminal ADR → `409 adr-frozen`; a stale revision → `409 revision-conflict`; then a CONTENT change (name, problem, context, decision, alternatives, consequences) → `409 adr-frozen` once `ACTIVE`, else `403` unless Driver, Contributor or ADMIN; an expiry-date change → `403` unless Driver, Approver or ADMIN, and **once the ADR is `ACTIVE` unless Approver or ADMIN** (1.0.0: the date of a decision in force is the Approver's; `canEditExpiry` is the ONE verdict behind both the `403` and the served flag; `400` for a past date). A no-op writes and audits nothing.
    - **`POST /adrs/{id}/transitions`**: coarse = Driver, Approver or ADMIN; per move: the Driver (or ADMIN) → `SOLUTION_DRAFT` (from `PROBLEM_RAISED`), `IN_REVIEW`, `CANCELLED`; the Approver (or ADMIN) → `ACTIVE`, `WITHDRAWN` (from `IN_REVIEW` or, since 0.7.0, `ACTIVE`); the SEND BACK (`IN_REVIEW` → `SOLUTION_DRAFT`) is the Driver's or the Approver's (or ADMIN's), the one verdict that also reads the source status (`rolesToRequest(from, target)`); then the lifecycle map (`409 illegal-transition`, which is also what a user asking for `EXPIRED`/`SUPERSEDED` gets, ADMIN included, and what a move out of a terminal status gets), the stale revision, the requirements (`400` naming the missing fields; an Approver counts only while the account is active). The response's `allowedTransitions` is this same verdict for the caller, so the SPA never re-derives it.
    - **`PUT /adrs/{id}/participants`**: Driver, Approver or ADMIN (coarse, before the header and body), `X-Expected-Revision` REQUIRED (a stale editor gets `409 revision-conflict` instead of silently dropping people another one added); **the Approver seat has two more `403`s (0.7.0)**, under the row lock: a non-ADMIN may never name THEMSELVES Approver (unless they already are; judged first, before the terminal `409`, the revision and the set's shape), and once the ADR has ever been in review (`IN_REVIEW`, `ACTIVE`, a draft again after a send back; read from its `TRANSITIONED` events) only the current Approver or ADMIN may add, remove or replace the Approver (a PUT that leaves the seat as it is is not judged: a Driver handover works). This second comparison runs against the locked participants AFTER the terminal `409` and the stale revision, so a stale editor that did not touch the seat is a `409`, never a false `403`. They close the two-step bypass (put yourself in the seat, then approve or withdraw your own ADR) and the send-back one (send back, swap the Approver in the draft, resubmit); `canChangeApprover` serves the second rule, and a denial is the generic `authz.denied`. A terminal ADR is `409 adr-frozen` (before the revision); a real change bumps the ADR revision. The Driver role is handed over, never left empty (`400` unless the set holds exactly one Driver).
    - **`/adrs/{id}/export` and `/adrs/export`** (0.8.0, `adrs/AdrExportRoutes.kt`; `adr-model.md` "Export"): reads, **any authenticated user, no guard** (an open decision log, so no `403`; a missing or soft-deleted ADR is a plain `404`; the 401 is `authenticate`'s), behind the per-user `export` bucket. The bulk export judges its filters, the search deadline and the 500-ADR cap (`400`) before the first byte.
    - **`/adrs/overview`** (0.9.0, `adrs/AdrOverviewRoutes.kt`; `adr-model.md` "Overview"): a read, **any authenticated user, no guard** (an open decision log, so no `403`; the 401 is `authenticate`'s). It takes no id or body, so no `404`/`400` exists; the caller's id only decides the `mine` flag and `mineTotal`. No rate bucket (`security.md`).
    - **`/adrs/{id}/discussion`** (`adrs/AdrDiscussionAccess.kt`, `AdrDiscussionRoutes.kt`; `adr-model.md` "Discussion"): reads and posts are any authenticated (an open decision log; a missing ADR or entry is a plain `404`, nothing to disclose). Order of one entry write: the ADR and the live entry (`404`; an entry of another ADR, or a deleted one, is `404`) → the coarse guard (`403` before the body is decoded) → the body (`400`) → under the ADR row lock the guard again, then, for a post or an edit only, the **window** (`409` `urn:hammurabi:problem:discussion-closed`: comments and questions only while pre-active, issues and escalations only while `ACTIVE`; the state change and the delete have no window, in any ADR status) → the state against the kind (`400`; the body's field is `state`, named `status` until 0.9.x). Who: **edit** = the author only (not even ADMIN); **delete** = the author or ADMIN, in every ADR status; **answer a question** = its author, the Driver, the Approver, ADMIN; **resolve or reopen an issue/escalation** = the Driver, the Approver, ADMIN. A post has no role guard, so its order is `404` → `400` → `409`. The entry carries the caller's verdicts (`canEdit`, `canDelete`, `canChangeState`), the ADR `canComment`/`canRaiseIssue`. Tests: `AdrDiscussionAccessTest` (every role row, 403-before-400/409), `AdrDiscussionTest`, `AdrDiscussionAuditTest`.
    - **`PUT /adrs/{id}/tags`** (0.4.0): the participants' shape - Driver, Approver or ADMIN (coarse, before the header and body), `X-Expected-Revision` REQUIRED, a terminal ADR `409 adr-frozen` (before the revision), then the names (`400`: unknown or retired, duplicate, more than 20). Tags are outside the content freeze: an `ACTIVE` ADR takes them. **`PUT /adrs/{id}/supersedes`**: the same coarse guard, judged on the SUCCESSOR (the predecessor's roles are irrelevant; the link is only a proposal until the predecessor's Approver accepts it, and any change of it voids an acceptance); under the lock a terminal ADR `409 adr-frozen`, the stale revision `409`, an `ACTIVE` ADR `409 adr-frozen` (the link is set before the decision is in force), then the predecessor (`400` itself / unknown / deleted / terminal, `409` cycle / a successor already in play); activating a successor needs its predecessor `ACTIVE` (`400` otherwise, with the other requirements, after the revision) and, over an `ACTIVE` one, that predecessor's Approver's acceptance (`409 successor-not-accepted`, before the requirements).
    - **`DELETE /adrs/{id}/successor`** (0.4.0): the PREDECESSOR's Driver, Approver or ADMIN (standing `404` → coarse guard `403`, no header or body); under the link lock and both rows a terminal predecessor is `409 adr-frozen`, then no pending successor `404`. The caller's role on the SUCCESSOR is irrelevant (linking is the successor's, detaching the predecessor's: the answer to a link parked by a stranger). Audit `adr.successor_detached`; `canDetachSuccessor` serves the verdict.
    - **`POST /adrs/{id}/successor/accept`** (1.0.0, `adrs/AdrLinkWrites.kt`; `adr-model.md` "Successors"): the PREDECESSOR's Approver or ADMIN accepts its pending successor, the consent the successor's activation needs while the predecessor is `ACTIVE`. Order: standing (`404`) → coarse guard `requireMayAcceptSuccessor` (`403`: Approver or ADMIN only; the Driver, a Contributor, the Informed, a stranger and the successor's own Approver are refused) → body `{successorId}` (`400`) → under the link lock and both rows (id order): the guard again (`403`), a terminal predecessor `409 adr-frozen`, a predecessor not yet `ACTIVE` plain `409`, a pending successor that is not `successorId` `404` (so a re-pointed link is never accepted blind), already accepted `204` writing nothing. No header, no revision bump. Audit `adr.successor_accepted`; `canAcceptSuccessor` serves the verdict. **The activation side:** a successor over an `ACTIVE` predecessor is `409 successor-not-accepted` until accepted, ADMIN included (no bypass), judged right after the stale revision (so a stale save is `409 revision-conflict` first) and before the `reason` and the requirements (`400`), from the row locked in that transaction.
    - **`/api/v1/tag-categories`** (0.4.0, `tags/TagCategoryRoutes.kt`): `GET` any authenticated user (the picker and the list filter read it); `POST`, `PUT /{id}`, `DELETE /{id}` ADMIN only, **guard-before-read** (a non-admin gets a uniform `403` before the header, the body or the id is looked at, whether or not the category exists), like teams. `PUT` and `DELETE` take a REQUIRED `X-Expected-Revision` (the category's `revision`): guard `403` → header `400` → body `400` → existence `404` → stale revision `409` `revision-conflict` (under the registry lock) → the tag claims `409`. Writes audit `tag_category.*`.
    - **`DELETE /adrs/{id}`**: ADMIN only, **guard-before-read** (a non-admin gets a uniform `403` whether or not the id exists), soft delete, a second delete `404`.
    - The response carries what THIS caller may do (`allowedTransitions`, `canEditContent`, `canEditExpiry`, `canManageParticipants`, `canChangeApprover`, `canEditTags`, `canEditSuccessor`, `canDetachSuccessor`, `canAcceptSuccessor`). Mutations audit `adr.*` (`observability.md`); a `403` is the generic `authz.denied`. Tests: `AdrAccessTest` (every verdict row, 403-before-400), `AdrApproverSeatTest` (the Approver seat), `AdrLifecycleTest`, `AdrTest`, `AdrParticipantsTest`, `AdrAuditTest`; 0.4.0: `TagCategoryTest`, `AdrTagsTest`, `AdrSuccessorTest`, `AdrSuccessorDetachTest`, `SuccessorGraphTest`; 1.0.0: `AdrSuccessorAcceptTest`.
  - **`/api/v1/notifications` - the caller's own notices** (0.6.0, `notifications/NotificationRoutes.kt`): `GET` any authenticated caller with NO guard and no id in the request, so there is no `404` to probe and nothing to disclose: the predicate starts at `recipient_id = <caller>` and keeps the rows of ADRs that still exist. `POST /notifications/{id}/seen` is scoped to the caller's own row: another person's notice and an unknown id are the SAME `404` (never a `403` to probe with); seen again is `204`. `POST /notifications/seen-all` touches only the caller's rows. All three ride the per-user `read-state` bucket before any other check.
  - **`GET`/`PUT /users/{id}/notification-preferences`** (0.6.0, `NotificationPreferenceRoutes.kt`) → **target user or ADMIN** (`requireSelfOrAdmin` FIRST: `403` before anything is read, then the body `400` - an unknown type fails to decode, a kind listed twice (the body is the GET's own `kinds`, a kind left out is on, the last write wins) - then the target `404` for an unknown or soft-deleted user), after the `read-state` bucket.
  - **`/api/v1/lenses` — saved ADR-list filters** (0.5.0, `lenses/LensRoutes.kt`, `LensService.kt`; Toadie's lenses, ported): ALL routes **any authenticated user** - lenses are per-user CONTENT. A lens = a name + `visibility` PRIVATE/PUBLIC + the list's own filter document (`AdrFilterDocument`, validated by the list's rules, `adr-model.md` "Lenses"). `GET` lists the caller's OWN lenses (both visibilities) plus everyone's PUBLIC ones, paged (`mine`, `visibility` filters); `GET {id}` reads one the caller may see; `POST` creates with the caller as creator (name unique per owner among active lenses, case-insensitively -> `409`; at most 50 per user -> `409`, counted under a per-user advisory lock so two racing creates cannot both take the last place). `PUT`/`DELETE {id}` follow **Toadie's hybrid disclosure** (`LensService.mutationVerdict`, decided under a row lock inside the mutation's transaction): a missing, soft-deleted or foreign PRIVATE lens -> uniform `404` (a private lens's existence is itself the secret, an ADMIN's too), a foreign PUBLIC lens -> the honest `403` (audited `authz.denied`). **`PUT` is creator-only** (rename, the visibility flip and the filters are the one whole-lens replace). **`DELETE` is the creator's, or an ADMIN's for a PUBLIC lens** (moderation, the one departure from Toadie's "ADMIN gets no special content access"; audited `lens.deleted` with `byAdmin`). The verdict runs BEFORE the body is decoded, sanitised or validated, so `403`/`404` win over `400` even for a body that does not decode (an unknown enum, a wrong type, a missing field): the PUT route asks `LensService.verdictForUpdate` (an unlocked read) first, then decodes, and `update` judges the verdict AGAIN under the row lock. The PUT has no revision: **the last write wins** (stated in the spec). The rows carry the caller's verdicts `canEdit` / `canDelete` and name their creator `createdById`/`createdByName`/`createdByDeleted`. Applying a lens is the ordinary `GET /adrs` with its filters - nothing new to authorize. Tests: `LensTest`.
- **Existence disclosure (403 vs 404) — deliberate policy (API-ERR-006)**: two sanctioned idioms,
  and every new resource picks one consciously rather than mixing them. **Guard-before-read**
  (`GET /users/{id}`): a forbidden caller gets a uniform 403 whether or not the id exists — use it
  where existence itself is sensitive. **Read-before-guard**: missing → 404, existing-but-
  forbidden → 403 — acceptable where ids are sequential and existence is no secret. The password
  PUT effectively guards first (`requireSelfOrAdmin` needs no read), then maps a zero-row update
  to 404 — safe because the only callers past the guard are the target and admins.
- **Audit on denial**: every 403 emits `authz.denied` (method/path/byUserId/detail) from the
  `ForbiddenException` handler in `plugins/ErrorHandling.kt` — denials are part of the security
  trail, and route code gets it for free by **throwing**, never hand-rolling a 403 response. See
  "Audit trail" in `.claude/docs/observability.md`.
  - `GET /api/v1/telemetry/current` and `GET /api/v1/telemetry/history?group=&range=` (`telemetry/TelemetryRoutes.kt`) → **ADMIN only, read-only**
    (operational data: process sizes, container limits, request figures). `requireAdmin(call.caller())` is the first
    statement, before the query is read, so a non-admin gets the uniform `403` for a bad or missing `group`/`range` too (`403` outranks `400`);
    only then is an unknown, missing, blank or repeated `group` / `range` a `400`. No mutation, so no audit event of its own (a denial is the
    generic `authz.denied`); both ride the per-user `telemetry` rate bucket (30/min, mounted inside `authenticate`; `security.md` "Telemetry").
    See `.claude/docs/observability.md` "Telemetry" for the shapes. Tests: `TelemetryRoutesTest`.
- **Exceptions**: `UnauthorizedException` (→ 401), `ForbiddenException` (→ 403),
  `NotFoundException` (→ 404), `ConflictException` (→ 409), and `TooManyRequestsException` (→ 429) live
  in `authz/Exceptions.kt` and are mapped in `plugins/ErrorHandling.kt`. **All error bodies are
  RFC 7807 `application/problem+json` (API-ERR-001/002)**
  (`ProblemDetail{type,title,status,detail,instance}`; `instance` defaults to the request path
  without query parameters; a stale `X-Expected-Revision` is the typed 409
  `urn:hammurabi:problem:revision-conflict`, `infra/revision/ExpectedRevision.kt`; every typed URN is registered in `infra/ProblemTypes.kt` and listed on the spec's `ProblemDetail.type`, `ProblemTypesTest`) — emit them via the `ApplicationCall.respondProblem(status, detail)`
  helper (it uses an explicit `TextContent` so ContentNegotiation does not relabel the media type
  as `application/json`). `StatusPages` routes `BadRequestException`→400 (**with fixed vocabulary
  for converter failures**: a `ContentConvertException` anywhere in the cause chain
  (ContentNegotiation's malformed-JSON wrap, whose raw message carries the target FQCN) answers
  "Request body is invalid or does not match the expected schema", and the Resources decode wrap
  "Can't transform call to resource" answers "Path or query parameter is malformed"; our
  validators' own messages, cause-less, pass through), `CannotTransformContentToTypeException`→400
  when Content-Type is absent, otherwise →415 for an unsupported request media type (the abstract
  ContentTransformationException parent is deliberately not caught), the typed exceptions as above
  (**the route 404 convention**: read preambles and zero-row mutation results
  `throw NotFoundException("<Resource> not found")` — never a hand-rolled
  `respondProblem(NotFound, …)` + return), unique-violation `23505`→409 and NUL-byte `22021`→400
  (via the R2DBC/Exposed cause-chain walkers; a check violation `23514` is deliberately NOT mapped: every column's bound is validated in Kotlin first, so one reaching the database is a validation hole, and the logged `500` is the honest answer), and a catch-all `Throwable`→500 (logged) through
  that helper. A `status(TooManyRequests)` handler additionally gives the `RateLimit`
  plugin's bodiless 429 (per-IP buckets, and the per-USER `telemetry`, `directory`, `read-state`, `export` and `password-change` buckets) a generic problem body and emits the `rate_limit.exceeded` audit event
  (bucket/method/path, coalesced to one per bucket per minute with a `suppressed` count — per user, with `byUserId`, for the `telemetry`, `directory`, `read-state`, `export` and `password-change` buckets —
  `observability.md`) — **that handler rewrites any non-StatusPages
  429**, which is why caller-specific 429s (the login lockout) must go through
  `TooManyRequestsException` (handled calls are marked and skipped) — and a sibling
  `status(MethodNotAllowed)` handler gives routing's bodiless wrong-method 405 a problem body too
  (no `Allow` header — Ktor doesn't surface the allowed-method set; test with the default client,
  the conformance plugin would rightly flag the out-of-spec operation). The SPA fallback
  (`plugins/Routing.kt`, only when `web.staticDir` is set) never answers under `/api` (or `/api`
  itself): an interceptor on its route node throws `NotFoundException`, so an unknown API path is
  the standard 404 problem body, never index.html (a wrong method on a declared `/api` path is then a 404 too, not
  405 — the 405 handler answers only when no `staticDir` is served) (`ServerTest` covers it WITH a `staticDir`,
  through the default client — the conformance plugin would flag the undeclared path). A pre-routing intercept in
  `configureErrorHandling` additionally 400s any `/api/` path with a negative-integer segment
  (kotlinx decodes `UInt` via `toInt().toUInt()`, so `/users/-1` would WRAP to 4294967295 and flow
  into the normal lookup instead of failing as the spec's `minimum: 0` promises). The JWT
  `challenge` in `plugins/Security.kt` also calls `respondProblem` (it runs outside
  `StatusPages`). Test HTTP clients must register the `application/problem+json` content type
  (`json(contentType = ContentType.parse("application/problem+json"))`) to decode error bodies
  with `body<ProblemDetail>()` — the shared test-client defaults do.
- **Tests**: `GuardsTest` covers the guard matrix, `AnonymousAccessTest` sweeps the 401 surface
  FROM THE SPEC (plus the forged-secret and jti-less token cases), `UserRoutesTest` the management
  CRUD + protections (with `TestUsers.withSoloAdmins` isolating the last-admin 409s in the shared
  container), `PasswordChangeTest` the self-vs-admin rules, `AuditTest` the denial and mutation
  events. The shared `TestUsers.seed` helper defaults to `role = UserRole.ADMIN` so privileged
  fixtures are terse; pass `role = UserRole.USER` when you need a non-privileged caller.

### Not yet ported from Lettuce

- **The HR read-only auditor role** (audited `hr.read`/`hr.list` cross-user reads) — with it,
  Lettuce's rule that every HR-privileged read is audit-logged.
- **The management-chain rule** (`teams/ManagementChain.kt` — transitive manager rights) —
  **deliberately NOT ported**: Hammurabi's teams are flat (ONE optional direct manager, no
  chain, no succession history). Port only if a hierarchy of teams ever becomes a requirement.