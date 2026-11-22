# Product, roadmap and donors

Owns: what Hammurabi is, the scope of every version, the decisions the user took, the defaults taken without asking, what each donor gave, and the
release history (newest first). Not here: the binding one-liners (the current version, "Port, don't reinvent") are in `CLAUDE.md`; the domain
model (lifecycle, DACI, history, discussion, tags, successors) is `adr-model.md`; how an area works now is in its own doc.

Read on demand when you need the product intent, the scope of a version, a decision the user already took or what a donor contributed. A decision
recorded here is the user's unless it is filed under "Assumptions" (taken without asking, change on request).

## What Hammurabi is

Hammurabi creates and manages **Architecture Decision Records** (ADRs — <https://github.com/architecture-decision-record/architecture-decision-record>)
across their full lifecycle: a problem is raised, a solution is drafted, reviewed and decided; while the decision is in force people report how
it works; eventually it expires or a successor replaces it. Responsibility on each ADR follows **DACI**
(<https://learningloop.io/glossary/daci-decision-making-framework>): one **Driver**, one **Approver**, any number of **Contributors** and
**Informed**.

The user's requirements (2026-11-20):

1. **User management** (the foundation's users, teams, feature flags).
2. **The ADR lifecycle** — `PROBLEM_RAISED` → `SOLUTION_DRAFT` → `IN_REVIEW` → `ACTIVE`; `CANCELLED` from the first three; `WITHDRAWN` from
   `IN_REVIEW` (manual); `EXPIRED` from `ACTIVE` once its expiry date passes. (0.7.0 added the send back `IN_REVIEW` → `SOLUTION_DRAFT` and
   `ACTIVE` → `WITHDRAWN`, below.)
3. **ADR event history** — every change with its date.
4. **Tagging** and other ways to organise ADRs.
5. **Search** by multiple criteria.
6. **Comments / questions** — registered before the ADR becomes active (input to the discussion).
7. **Issues / escalations** — registered after it is active (feedback on whether the decision works).
8. **Successors** — one ADR replacing another.

ADR properties: a unique human-readable id, a name (short, unique), the problem/question, the context (why it matters, constraints,
assumptions, who it concerns), the status, an optional expiry date, the decision, the alternatives considered, and the accepted consequences /
side effects.

The name is Hammurabi of Babylon, whose stele carried one of the oldest written codes: decisions written down so that they bind and can be
looked up. **Brand:** lapis lazuli indigo (`hammurabi` tuple, hue ≈ 228°, light primary shade 7 `#3a4cb0`, dark primary shade 8 `#2f3f94`,
dark-scheme accent ink shade 3 `#93a4e2`) — the glazed bricks of Babylon's Ishtar Gate, the free arc between Flow blue 209° and Covenant
violet 264° (Toadie amber 26°, Adder olive 71°, Lettuce green 142°, Astrolabe cyan 189°, Astrid red 351°). The logo **"Stele"**: the basalt
stele of the Code of Hammurabi on a lapis disc — the sun disc of Shamash crowning the relief, rows of cuneiform wedges below (Babylonian gold
`#d9a52e`/`#e2b54a` for the sun). Success is **teal**, in progress is **cyan** (never stock blue/indigo/violet, which sit beside the brand).

## Decisions (the user's, 2026-11-20)

- **Foundation:** Flow, Toadie and Lettuce as the base for stack, conventions, UI approach, app and API design — re-use as much as possible; the UI
  stays coherent with Lettuce, Toadie, Covenant, Flow, Astrolabe, Astrid and Adder; a new accent colour; an icon referring to the name
  (Babylon, the law codex, Mesopotamia).
- **Expiry** applies to `ACTIVE` ADRs: once the expiry date passes, a background job moves the ADR to `EXPIRED`. (The first sketch said "from
  in review"; the user corrected it.)
- **Successors:** a new terminal status **`SUPERSEDED`**. When a successor becomes `ACTIVE`, the ADR it replaces moves from `ACTIVE` to
  `SUPERSEDED` in the same transaction; both are linked both ways.
- **Permissions — "the Driver drives, the Approver decides":** anyone creates an ADR and becomes its Driver. The Driver edits it, moves it
  forward to `IN_REVIEW` and can cancel it; the single Approver activates or withdraws it; Contributors edit content and comment; Informed
  only read and get notified; ADMIN overrides.
- **Tags:** ADMIN-curated tag categories (Toadie's registry), strictly checked on every write.

## Assumptions (taken without asking; change on request)

- **Ids** are `ADR-0001`, one global gapless counter allocated under a lock (Adder's per-room thread number, made global); never reused.
- **Reading:** every authenticated user reads every ADR (an open decision log), and may comment on it or raise an issue.
- **Content freeze:** content is editable only in the three pre-decision states; once `ACTIVE` only the expiry date (the Approver's or ADMIN's from 1.0.0), the tags and the participants
  change (0.4.0: the successor link is set BEFORE activation instead, because it takes effect at activation); terminal states are read-only.
- **Required fields:** creating needs only the problem/question (the name defaults to its first line, truncated); entering `IN_REVIEW`
  requires name, context, decision, consequences and an assigned Approver; alternatives stay optional.
- **Comments vs issues:** comments/questions are accepted only before `ACTIVE`; issues/escalations only in `ACTIVE` (OPEN/RESOLVED, resolved
  by the Driver, the Approver or an ADMIN). Both stay readable forever.
- **Discussion windows (0.3.0):** posting and editing follow the kind's window (comments/questions pre-active, issues/escalations `ACTIVE`); the STATE change (answer, resolve, reopen) and the delete are open in EVERY status, so an issue on an ADR
  that later expired or was superseded can still be resolved and a question can be answered after activation (the open-issue count keeps counting until then). Edits and deletes of an entry are audit-only, not history events. Change on request.
- **Tags and successors (0.4.0; change on request):** the seed registry is two small categories (Domain: data, security, infrastructure, frontend, integration; Scope: team, system, organisation).
  A tag dropped from the registry (or with its category) stays on the ADRs that carry it, shown as retired, until a tag write drops it (a write carrying it is `400`); a tag name is unique for good and re-adding it revives the row. Tags are set by their own `PUT` (Driver, Approver, ADMIN), not by the document PUT. A
  successor link is the SUCCESSOR's (its Driver, Approver or ADMIN, only while it is pre-active); the predecessor may be `ACTIVE` or pre-active, a terminal one is refused, and activating the successor needs the predecessor to be `ACTIVE`
  (else `400`: unlink it or wait) - then it is superseded in the same transaction, the event actor being the activator. **Successor squatting (decided in the 0.4.0 review):** linking needs no role on the PREDECESSOR (anyone who owns a draft may point it at any live ADR), so a stranger could park a pending link on an ADR and block every honest successor (one successor in play per predecessor). To close that, the PREDECESSOR's Driver, Approver or ADMIN may DETACH its pending successor (`DELETE /adrs/{id}/successor`; the successor's Driver may link again, so detaching is a veto of that link, not a ban). The detach bumps the successor's revision and writes `SUCCESSOR_UNLINKED` on both ADRs. Details: `adr-model.md` "Tags", "Successors".
- **Find (0.5.0; change on request):** `text` (full-text) is a separate parameter from `q` (name-or-key substring) and both stay; a search page is at most 20 rows and ranks by relevance unless sorted; `expiresBefore` is strictly before the day, any status; a lens stores the filters only (no sort, no page), keeps at most 50 per user, and an ADMIN may delete (not edit, not read if private) a PUBLIC lens; there is no per-user rate bucket on the list (the deadline and the concurrency cap bound a search). Details: `adr-model.md` "Search", "Lenses".
- **Notify (0.6.0; change on request):** every notice derives from a history event of its own transaction and never reaches its actor; the email digest cadence is the deployment's (Adder's `delaySeconds` 300 / `minIntervalSeconds` 900), not a per-user frequency, and "email off" is the EMAIL switches; a comment reaches the Driver, Approver and Contributors, an issue or escalation the Driver and Approver; the expiry reminder is `adrs.expiry.reminderDays` (14, `0` off) days before the last day in force, once per ADR per expiry date (a changed date re-arms it); mail names the ADR by its KEY only, never its name or text; the bell is polled once a minute (no live channel). Details: `adr-model.md` "Notifications".
- **Send back and withdraw (0.7.0, approved by the user 2026-11-21; the who is assumed, change on request):** `IN_REVIEW → SOLUTION_DRAFT` ("send back") may be requested by the **Approver** (returning it for rework), the **Driver** (pulling it back) or ADMIN; `ACTIVE → WITHDRAWN` by the **Approver** or ADMIN only, since the Approver decides (the Driver cannot revoke a decision). A **reason** is optional on every transition (the UI asks for it on send back, withdraw and cancel): free text up to 1000 code points, kept in the history, **never in a notice or an email** (notices say "sent back" / the new status; mail names the ADR by its key only). A send back clears nothing; the next "Send to review" re-validates the required fields. A withdrawn decision is terminal; a pending successor of it cannot be activated until its Driver removes the link (as for an expired predecessor), and a predecessor a withdrawn successor had already superseded stays superseded. Details: `adr-model.md` "Lifecycle", "DACI roles and verdicts".
- **The Approver seat (0.7.0, decided in the review of the withdraw; change on request):** nobody but ADMIN can make themselves the Approver, at any status (no self-approval), and once the ADR has ever been in review (also after a send back to draft) only the current Approver or ADMIN can add, remove or replace the Approver; the Driver still hands the Driver seat over, sends back to fix the text and edits every other seat. The Driver could otherwise take the seat through the participants PUT and approve or withdraw their own ADR. Until its first review the Driver may still name someone else as Approver; if the Approver's account is deleted, ADMIN fixes the seat. Details: `adr-model.md` "Participants", `authorization.md`.
- **Markdown export (0.8.0, approved by the user 2026-11-21; the details are assumed, change on request):** any signed-in user can export any ADR (every ADR is readable). One ADR is an adr-tools Markdown file (`# 42. Name`, `Date:`, `## Status`, `## Context`, `## Decision`, `## Consequences`) plus Hammurabi's alternatives, DACI roles and tags; `Date` is the day it first became `ACTIVE`, else the day it was created; the Markdown fields go out verbatim; the file is English whatever the exporter's language; the footer names the export date only. The ZIP exports what the list's filters select (full-text `text` included), at most 500 (more is `400`, never a silent truncation), with an `index.md`, one rate bucket (20/min per user) for both exports, two bulk exports streaming per process at most (`429 export-busy`), audited with ids and counts only. Details: `adr-model.md` "Export".
- **Decision log (0.9.0, approved by the user 2026-11-21; the details are assumed, change on request):** any signed-in user reads the overview (`GET /adrs/overview`, no rate bucket: it is capped and one call per page load; not audited). Sections and caps: the count per status, ACTIVE ADRs by tag category and tag (20 per tag, 100 tag groups, an ADR under each of its live tags, a group for ADRs with no live tag), the 10 most recently activated ADRs still in force, the ACTIVE ADRs expiring within 30 days (`ADRS_OVERVIEW_EXPIRING_SOON_DAYS`, overdue ones first), and the 20 longest-waiting ADRs in review with the caller's own highlighted and counted in full. Activation time is the row's `status_changed_at` (the history event's own stamp), not a history scan; a retired tag does not group an ADR; no migration. Find it in the Overview navigation between My work and ADRs. Details: `adr-model.md` "Overview".
- **A decision in force is the Approver's (1.0.0, checkup C-SEC-1; the plan was approved, the details are assumed, change on request):** the Approver decides, so what ends or replaces a decision in force needs the Approver (or ADMIN), never only the Driver. (1) **The expiry date of an `ACTIVE` ADR** is the Approver's or ADMIN's to change (the Driver, who may still date a draft or an ADR in review, could otherwise set it to today and let the sweep end the decision next day); the wire serves `canEditContent` and `canEditExpiry` instead of `canEdit`, so the SPA derives no role verdict; a changed or removed date of an `ACTIVE` ADR is an `EXPIRY_CHANGED` notice to every participant but the actor (a draft's date notifies nobody; the old reminder still re-arms per date). (2) **A successor replaces a decision in force only once that decision's Approver (or ADMIN) accepted the link** (V19, `POST /adrs/{id}/successor/accept`, typed `409 successor-not-accepted` at activation, ADMIN included, no bypass); acceptance is possible only while the predecessor is `ACTIVE`, belongs to one link (a re-point, removal or detach voids it; a hand-over of the Approver seat does not), cannot be taken back (detach instead), and notifies the successor's Driver and Approver. A link is a proposal until then (the web and the Markdown export say so). **Upgrade:** links already in effect are kept as accepted, pending ones need an acceptance after the upgrade (release note); rolling back to 0.9.x ignores the column but first needs the pending markers cleared (`UPDATE adrs SET supersedes_accepted_id = NULL WHERE NOT EXISTS (SELECT 1 FROM adr_events e WHERE e.adr_id = adrs.id AND e.event_type = 'SUCCESSOR_ACTIVATED')`, otherwise 0.9.x trips the CHECK on a re-point, an unlink or a detach; clearing every marker would erase the backfill; `persistence.md`).
- **An ended predecessor tells its pending successor (1.2.0; the user's decision D3, change on request):** when an ADR that a PENDING successor (still pre-active) was set to replace is cancelled, withdrawn or expires, the successor's Driver and Approver get a notice (`PREDECESSOR_ENDED`, bell and email on by default, the actor excepted), the successor's history gets the event, and the ended ADR **stops listing** the successor everywhere it did (`supersededBy`, the Successor panel, the export, `hasSuccessor`). **The dead link stays on the successor** (its Driver removes it; activation stays `400` "unlink it first"): nothing detaches or re-points it for them, and the predecessor's owners could not anyway (it is terminal). Assumed: a soft delete of the predecessor tells nobody (a soft delete records no event); a successor already cancelled or withdrawn is told nothing; a link that predates the release and already points at an ended ADR is not announced after the fact. The notice is charged to the actor's mail budget (the sweep's to the ended ADR's Approver, the "Mail budget" assumption).
- **API names frozen at 1.0.0 (checkup C-API-1, C-API-2; decided in the approved plan, change on request):** a person is `<role>Id` / `<role>Name` / `<role>Deleted` everywhere on the wire (`managerId`, `createdById`, `actorId`, `authorId`, `stateChangedById`, `participantId`; `conventions.md` "Wire names"); the ADR list's `participant`, `role`, `supersedes` and `supersededBy` filters became `participantId`, `participantRole`, `hasPredecessor` and `hasSuccessor` (the presence booleans no longer share a name with the link objects, `role` no longer reads like `GET /users?role=`), and the discussion's `status` became `state` (it is not the ADR's `status`); `GET /users?role=` and `GET /adrs?status=` keep their names. The notification preferences PUT takes the GET's own `kinds` (a kind left out is on). The lens and team PUTs carry no revision and the spec says the last write wins. The SPA's URL keys follow the API names; the four old keys stay READABLE (a 0.9.x bookmark still filters) and are never written. Stored lens documents are migrated (V20), not read through an alias. Release-notes points: the renames above (old to new), the URL keys, V20 and its rollback statement.
- **User directory:** any signed-in user may look up `{id, name, email}` of active users (`GET /users/directory`, Adder's, for the DACI pickers); the
  name+email disclosure to every signed-in user that Adder's owner accepted is carried over (`security.md` "What the user directory discloses"). Collapse it to `{id, name}` if that changes.
- **Login lockout (1.0.0, ported from Adder's 2026-11-20 checkup decisions; change on request):** the lock is progressive for wrong emailed MFA codes ONLY (a lock tripped by wrong passwords keeps the flat duration; the level decays after `LOGIN_LOCKOUT_MFA_DECAY_SECONDS`); a password-only window neither escalates nor resets the level; a confirmed password reset (it proves the mailbox) lifts the lock AND the level; a wrong `currentPassword` on a self password change counts into the same lockout and rides its own per-user bucket; unknown login and reset identities are audited as a keyed digest, never as typed. Details: `security.md` "Per-account login lockout".
- **Mail budget and the ADR write bucket (1.2.0, C-SEC-4; the user's decisions D1 and D2, change on request):** the email digest counts NOTICES per actor per UTC day, not Adder's distinct recipients, because an ADR's audience is up to 99 people: the first 200 (`NOTIFICATIONS_EMAIL_MAX_NOTICES_PER_ACTOR_PER_DAY`, 1..10000) an actor's writes make stay eligible for the email, every later notice still reaches the bell and its email is withheld for good (`emailed_at` stamped at insert; not delayed, no "+N more" summary, the bell is never dropped); an ADMIN actor is not exempt and the system's notices (the expiry sweep, the reminders) are charged to the ADR's current Approver (exempt only without one), so two people cannot arm the sweep into an unbudgeted flood, and a reminder re-arms at most once per ADR per UTC day; every write that withholds is audited (`notification.email_budget_exceeded`). One per-user `adr-write` bucket, 120 a minute and fixed in every mode, covers the 13 writes under `/api/v1/adrs` (the discussion's four included); lenses and tag categories stay unbucketed. Details: `security.md` "Notices" (5) and the `adr-write` paragraph, `persistence.md` "Notifications".
- **Dates (1.0.0, decided while the owner was away, change on request):** every timestamp on an ADR page renders in the browser's own time zone (date-only values such as the expiry are calendar days and never shift); the telemetry panel stays UTC.
- **Dead code (1.0.0, same):** Flow's team-manager guard, `jsonb`/canonical-JSON helpers, `insertRows`, `ftsMatchSql`, `repeatedLongs` and `useRovingGrid` were deleted with their tests because nothing here called them; the field cipher stays (its production boot check is live) and is recorded as having no consumer yet. The notice-volume bound (Adder's per-actor digest budget, C-SEC-4) followed in 1.2.0 (the "Mail budget" assumption below).
- **Backup and restore (1.2.0, C-ARCH-2; the user's decision D4, change on request):** a manual runbook, not a scheduled job: `scripts/db-backup.sh` and `scripts/db-restore.sh` (Adder's, `HAMMURABI_PG_EXEC` for compose or `kubectl`) plus `scripts/db-drill.mjs`, a committed drill on a throwaway compose project that was run once at 1.2.0 and is recorded in `persistence.md` "Backup and restore". It does not run in CI; running it nightly in `e2e.yml` is a backlog item. A dump never holds `JWT_SECRET`, `DATA_ENCRYPTION_KEY` or the Postgres password, and a restore turns back time (revoked tokens, changed passwords).
- Flow's integration service tokens are not ported: nothing external writes ADRs yet.
- The coverage floors were re-baselined at the 1.0.0 checkup to the measured actuals, rounded down (`testing.md`).

## Roadmap

Each increment is built and gated on its own. The current version is **1.3.0** (My work).

| Ver | Scope |
|---|---|
| **0.1.0 Foundation** | Flow trimmed (Adder's bootstrap tree): auth and email MFA, password reset, users and feature flags, teams with a manager, EN/PL, light/dark, changelog, telemetry panel, the lapis brand and the Stele logo |
| **0.2.0 ADR core** | `adrs/`: ids, fields, the lifecycle map (409 on an illegal move), DACI participants and their permission verdicts, `X-Expected-Revision`, field-level history, the expiry sweep; list, create and detail pages |
| **0.3.0 Discussion** | `adr_discussion` (V12): comments/questions before `ACTIVE`, issues/escalations in `ACTIVE` (Covenant's version reviews, ported and reshaped); the Discussion and Issues tabs, `openIssueCount` on the ADR rows |
| **0.4.0 Tags and successors** | Tag categories (Toadie) and successor links + `SUPERSEDED`, released |
| **0.5.0 Find** | The remaining multi-criteria filters, full-text search (Adder), URL-synced filters, saved lenses (Toadie), released |
| **0.6.0 Notify** | The bell, email digest and preferences (Adder's `notifications/`) for DACI participants, notices derived from the history events, expiry reminders (`adrs.expiry.reminderDays`), released |
| **0.7.0 Send back and withdraw** (extra, approved by the user 2026-11-21) | `IN_REVIEW` → `SOLUTION_DRAFT` and `ACTIVE` → `WITHDRAWN` with an optional reason in the history, released |
| **0.8.0 Markdown export** (extra, approved by the user 2026-11-21) | `GET /adrs/{id}/export` (one adr-tools Markdown file) and `GET /adrs/export` (a ZIP over the list's filters, at most 500, with an `index.md`), the buttons on the ADR page and the list, released |
| **0.9.0 Decision log** (extra, approved by the user 2026-11-21) | `GET /adrs/overview` and the read-only Decision log page: status counts, what waits for a decision (the Approver's own highlighted), what expires soon, what was decided recently, what is in force by tag category and tag, every section capped with a "+N more" link, released |
| **1.0.0 Checkup** | The family's technical checkup (docs, DRY/SRP, tests, UI consistency, security, guardrails, infra): fixes in `audit-status.md` "Fixed so far", the follow-ups in `BACKLOG.md`, released |
| **1.1.0 Write and see** (requested by the user 2026-11-21) | The visual Markdown editor for every long text field (Lettuce's `MarkdownEditor`, MDXEditor, with a Markdown source view; a save without typing changes nothing) and the Lifecycle tab on the ADR page (Lettuce's `LifecycleDiagram`: every status and move, who may make it, where the ADR is and the caller's own moves highlighted), released |
| **1.2.0 Safety net** (the user's "1.2.0 = 1–3", 2026-11-21) | C-SEC-4: the per-actor mail budget (V21, 200 notices a UTC day, system notices charged to the Approver) and the `adr-write` bucket (120/min); the `PREDECESSOR_ENDED` event and notice for a pending successor whose predecessor ends unreplaced; C-ARCH-2: `db-backup.sh`/`db-restore.sh` (Adder's), the drill and the runbook, released |
| **1.3.0 My work** | Personal landing page at `/my-work`, one DACI role filter, actionable and watch items, seven days of activity, and the caller's open ADRs. The capped read is `GET /adrs/my-work`. |

## Donors

Hammurabi's repository started from Adder's bootstrap tree (`~/Sources/adder` @ `defb001`, 2026-11-17), which is itself an exact copy of Flow @
`4e43e480` (`~/Sources/flow`, Flow 1.57.1 — still Flow's HEAD on 2026-11-20) trimmed in the shape Astrolabe's bootstrap (2026-10-31) set. Flow
was copied from Covenant; Lettuce and Toadie came in through it. **Port, don't reinvent**: when Hammurabi needs a capability a sibling has,
port its implementation.

- **Flow** (`~/Sources/flow`, the primary donor): the whole foundation — stack, API design, UI/UX, testing, gates, versioning, deployment, the
  telemetry panel.
- **Adder** (`~/Sources/adder`): the trimmed bootstrap tree; ported in 0.2.0 (server): `infra/db/EventLog.kt`, the thread-number allocation (made global,
  an advisory lock), `infra/revision/ExpectedRevision.kt`'s first use; ported in 0.5.0: `infra/db/Fts.kt`, the search column (V15), `SearchBudget` and the typed search `429`s (the per-user `search` bucket was left out, `security.md` "Search"); ported in 0.6.0: `notifications/` (the notices, the bell's routes, the preferences, the email digest with its claim-then-send machinery and the `mailSafe` text hardening, the retention sweep; the per-actor digest budget of V24 was left out here and ported in 1.2.0 as a counter of notices, V21 `notification_email_budget`, `security.md` "Notices" (5), with the chat-write bucket's idea as the `adr-write` bucket; Adder @ `48865f7`), `NotificationsButton` and the preferences page; ported on the way: the pickers (`UserDirectorySelect`, `SearchPicker`), `EventTimeline` and the Markdown view and field (the `ThreadPane`/`ThreadSidePanel` rooms layout was not: there are no rooms); ported in 1.0.0 (checkup, Adder @ `2161ea5`): the progressive login lockout and the MFA wrong-code and live-challenge bounds (`auth/AccountLockout.kt`, `LoginThrottle`, `MfaChallenges`), the password-change bucket and lockout, `AuditDigest`, `ExpiringIndex`, `DatabasePassword`, the ingress-nginx Ingress and its `k8s-static` check, the foundation tests, the naming rule (`43b2fb4`) and the release rule (`a11ebf4`); ported in 1.2.0: `scripts/db-backup.sh`, `scripts/db-restore.sh` and the runbook (`50851b9`, with a drill script of our own, `scripts/db-drill.mjs`).
  (`UserDirectorySelect`), the `ThreadPane`/`ThreadSidePanel` detail layout, `EventTimeline`, Markdown building blocks.
- **Covenant** (`~/Sources/covenant`): `contracts/Lifecycle.kt` (the 409-on-illegal-move transition map), `ContractAccess.kt` (permission
  verdicts) and `ReleaseLineReminders.kt` (the periodic date sweep) are ported (0.2.0, `adrs/`; its reminder-once-per-date shape again in 0.6.0, `notifications/ExpiryReminders.kt`, with the sent marker `adr_expiry_reminders`); `VersionReview*` (the entry table, its routes and `VersionReviews.tsx`) is ported
  and reshaped as the ADR discussion (0.3.0): Covenant's open/outdated/closed review and its approvals are gone, a kind (comment/question/issue/escalation) and a state (open/answered/resolved) take their place.
- **Toadie** (`~/Sources/toadie`): `tags/` tag categories and `infra/db/Locking.kt` (`lockingTransaction`) are ported (0.4.0, without the Backstage entity kinds; the tags became rows instead of a JSON array); `lenses/` saved filters are ported (0.5.0, V16: the typed filter document replaces Toadie's catalog slots, and an ADMIN may delete a PUBLIC lens).
- **Lettuce** (`~/Sources/lettuce`): `infra/mail`, `infra/crypto`, `infra/paging` (through Flow); `MarkdownEditor` (ported as `MarkdownField` + `MarkdownRichEditor`: the visual Markdown editor over `@mdxeditor/editor`, with a Markdown source view and no emoji picker; 1.1.0) and `LifecycleDiagram` (1.1.0, grown for an eight-status graph: path edges, two-line captions, the caller's moves emphasised, a text list of the moves; `AdrLifecycleDiagram`); not ported: the sharing pickers.
- **Astrolabe** (`~/Sources/astrolabe`): `planning/DependencyGraph.kt` (`cycleClosedBy`) is ported for the successor chain (0.4.0, `adrs/SuccessorGraph.kt`, reduced to one kind of node).

Left in Flow on purpose: `ingest/`, `fetch/`, `jira/`, `gitlab/`, `norm/`, `identities/`, `metrics/`, `reports/`, `integration/` (service tokens),
`infra/outbound/` (the SSRF guard — port it with the first server-initiated call other than SMTP), `plugins/Role.kt`, `EncryptedAtRest`/`Reencrypt`.

## My work

The user approved replacing Home with a personal workspace at `/my-work`. It groups the caller's DACI assignments into work that needs their action, developments worth watching, recent ADR activity and a list of open ADRs. The role filter applies to all sections. Proposed defaults adopted for the first version are seven days of recent activity, 14 days without progress in draft and seven days waiting for a review decision. Expiry follows the configured reminder window. See `adr-model.md` for the selection rules.

## Release history

### v1.3.0 — My work (2026-11-22)

My work replaces the welcome page at the root and opens by default after sign-in. It shows pending reviews, expiring or stalled ADRs, returned drafts, and open escalations against the caller's DACI role. It also shows seven days of ADR history and links to the complete filtered ADR list. Activity links open Validity, Discussion, or Issues where appropriate. The additive API uses existing tables and indexes.

### v1.2.4 — workspace refresh and ADR validity (2026-11-22)

Shared page headers, responsive tables, and clearer filter and form layouts bring the ADR and administration views in line with the Decision log. Home groups the main actions, and Changelog presents each release with version and date metadata. Expiring decisions and reminders link to Validity, where an Approver or administrator can extend the last day of an active ADR. The date flows through Lifecycle, History, notifications and the existing expiry filters. The extension reuses the revision-checked ADR write and ships without a database migration.

### v1.2.3 — a clearer Decision log (2026-11-21)

The Decision log has an aligned status grid, a shared panel for pending reviews, upcoming expirations and recent decisions, and a clearer hierarchy for active ADRs by category and tag. The layout adapts to available content width and uses the app's light and dark theme tokens.

### v1.2.2 — safer ADR edits (2026-11-21)

Withdrawal, expiry and successor actions use the revision or target the user saw. V22 gives discussion entries their own revision.
The web app sends the new preconditions, and existing v1 callers may omit them. Draft guards protect ADR and discussion forms.
ADR pages, export plans and notification lists read a consistent snapshot. Tag validation, digest capacity and demo stack targeting
match their documented behavior. The V22 backup and restore drill covers a populated upgrade. See `audit-status.md` for the review fixes.

### v1.2.1 — tidy-up (2026-11-21)

The user's "do the cleanup items": the parked 1.0.0 checkup findings C-DRY-5, C-SMELL-2, C-ARCH-5, C-UI-6 and C-TEST-6, fixed (`audit-status.md` "Fixed so far"). User-visible: one compact pager, one empty state, the unread cues on the brand accent, the identity error in the user menu, the PL working-set labels. API: the last-administrator `409` now carries `urn:hammurabi:problem:last-administrator` (was `about:blank`; the body is unchanged otherwise). Structure: password hashing in `infra/crypto/`, the password change in `auth/`, no import cycle between the domain packages (`PackageBoundaryTest`), one status-word source, the ZIP streaming in `AdrExportArchive.kt`, `security.md` "Per-process state". Tests: the e2e console-error guard, the `global-readers` phase, the axe spec in the scenario check. No migration. Released.

### v1.2.0 — safety net (2026-11-21)

The user's "1.2.0 = 1–3" with decisions D1–D4. C-SEC-4: V21 `notification_email_budget` (200 notices per actor per UTC day, `NOTIFICATIONS_EMAIL_MAX_NOTICES_PER_ACTOR_PER_DAY`; over it the bell notice stays and the email is withheld, leads first; the sweep's and reminders' notices charged to the ADR's Approver; the budget row the first lock of every notice-making write, reserved per transaction; every withholding write audited; reminders re-armed at most once a day) and the per-user `adr-write` bucket (120/min, the 13 ADR and discussion writes, `429`). The stuck successor: ending an ADR unreplaced records `PREDECESSOR_ENDED` on its pending successor and tells its leads; `supersededBy`, `hasSuccessor`, the panel and the export no longer list it (D3: the link stays). C-ARCH-2: `scripts/db-backup.sh`, `db-restore.sh` (Adder's, hardened), `db-drill.mjs` and `persistence.md` "Backup and restore". Upgrade notes: V21 runs on boot; `hasSuccessor=true` and `supersededBy` no longer count a pending successor of an ADR that ended unreplaced; rolling back to 1.1.x needs `DELETE FROM adr_events WHERE event_type = 'PREDECESSOR_ENDED'` first (1.1.x cannot read that event). Released.

### v1.1.0 — write and see (2026-11-21)

The user's two requests after 1.0.0. The visual Markdown editor (`MarkdownField` + the lazy `MarkdownRichEditor` over `@mdxeditor/editor`, ported from Lettuce): a Visual/Markdown toggle remembered per browser, an emitted change only after a real user edit (so an untouched save records no `UPDATED` field change), hard breaks kept as breaks, constructs it cannot show (raw HTML, images, reference links) opened in the Markdown view; its own `lexical` chunk. The Lifecycle tab (`LifecycleDiagram` ported from Lettuce and grown, `AdrLifecycleDiagram`, the move map `utils/adrLifecycle.ts` pinned to `AdrLifecycle.kt` by a server test), highlighting the moves from the ADR's `allowedTransitions`. No API or migration change.

### v1.0.0 — the checkup (2026-11-21)

The family's technical checkup (`audit-status.md`, 55 findings): tier 1 fixed, tier 2 filed in `BACKLOG.md`. The fixes: the decision in force is the Approver's (C-SEC-1: the expiry date of an ACTIVE ADR and the successor acceptance, V19, `successor-not-accepted`), the auth hardening ported from Adder (the progressive MFA lockout, the password-change bucket, the audit digest, the ingress checks), the API names frozen (C-API-1/2: `<role>Id`/`Name`/`Deleted`, the renamed list filters, the discussion `state`, V20 for stored lenses), and the docs sweep (C-DOC-1..9) with the raised coverage floors. Upgrade notes: the renames and V20 in the "API names frozen" and "A decision in force" bullets above; the pending successor links need an acceptance after upgrade. Released.

### v0.9.0 — the decision log (2026-11-21)

Server (`adrs/`; no migration): `GET /api/v1/adrs/overview` (any signed-in user): a purpose-built, fully capped document - the count per status, the ACTIVE ADRs by tag category and tag with an untagged group, the 10 most recently decided, the ACTIVE ADRs expiring within `adrs.overview.expiringSoonDays` (30), and the ADRs in review longest-waiting first with the caller's `mine` flag and `mineTotal` - read in one transaction of a constant number of statements (the per-tag groups in one windowed raw statement). Recorded as the second shape exception in `list-endpoints.md`. Web: the Decision log page (`/decision-log`, nav entry between Home and the ADRs, a button on Home): the status strip, Awaiting decision, Expiring soon, Recently decided and In force by tag, each "+N more" a filtered ADR list link. E2E: `decision-log`.

### v0.8.0 — Markdown export (2026-11-21)

Server (`adrs/`; no migration): `GET /api/v1/adrs/{id}/export` (`text/markdown`, an attachment named `ADR-0042-<ascii-slug>.md`) and `GET /api/v1/adrs/export` (`application/zip`: a file per ADR the list's filters select, plus `index.md`; the list's own filter parsing and validation, `text` included; at most 500, else `400`; streamed 25 ADRs at a time with `java.util.zip`), the pure renderer `AdrMarkdown.kt` (adr-tools layout plus alternatives, DACI roles and tags), the per-user `export` rate bucket (20/min) and the audit events `adr.exported` / `adr.exported_bulk`. The spec's list filters became shared parameter components. Web: Export Markdown on the ADR page and Export (ZIP) on the list (authenticated download through `api/download.ts`). E2E: `adr-export`.

### v0.7.0 — send back and withdraw (2026-11-21)

Server (`adrs/`, `notifications/`; no migration): two new edges in the lifecycle map, `IN_REVIEW → SOLUTION_DRAFT` (the Approver, the Driver or ADMIN) and `ACTIVE → WITHDRAWN` (the Approver or ADMIN), the per-move verdict (`rolesToRequest(from, target)`; `allowedTransitions` serves it), an optional `reason` on every transition (`{to, reason?}`, up to 1000 code points, stored in the `TRANSITIONED` event, audited only as `reasoned`), `STATUS_CHANGED` notices with the `SENT_BACK` word (and its mail sentence), and the successor read that keeps a superseded predecessor pointing at a successor withdrawn later. Web: "Send back to draft" and "Withdraw decision" in the Status section, a confirmation with the optional reason for send back, withdraw and cancel, the reason and the two new sentences in the History, "sent back" in the bell, a warning on a successor whose predecessor is no longer in force. E2E: `adr-send-back`.

### v0.6.0 — notify (2026-11-21)

Server (`notifications/`, `adrs/AdrNotices.kt`, migrations V17-V18): `/api/v1/notifications` (the caller's own notices, newest first, `seen` filter, `total` capped at 1000, `POST .../{id}/seen`, `POST .../seen-all`) and `GET`/`PUT /api/v1/users/{id}/notification-preferences` (a bell and an email switch per kind), on the new per-user `read-state` bucket. Ten kinds of notice (a role given or taken away, a status change, a comment/question/issue/escalation, an answer or resolution, an expiry, a supersede, a successor linked or detached, an expiry reminder), each made INSIDE the ADR write that caused it from its history event, never for the actor. Adder's email digest in each recipient's language through the existing mailer, the retention sweep, and the expiry reminder (`adrs.expiry.reminderDays`, once per ADR per expiry date, V18). Web: the header bell with the unseen count and a drawer, and the Notification preferences page. E2E: `notifications`.

### v0.5.0 — find (2026-11-21)

Server (`adrs/`, `lenses/`, migrations V15–V16): `GET /api/v1/adrs?text=` full-text search (Adder's `Fts.kt` and search column: a stored generated `tsvector` with the accent-folding `hammurabi_search` configuration and a GIN index; the name weighs A, problem and decision B, context, alternatives and consequences C; ranked, typed highlight snippets, one deadline and a concurrency cap with the typed `429`s `search-timeout` and `search-busy`), the remaining filters (`createdFrom/To`, `updatedFrom/To`, `expiresBefore`, `hasOpenIssues`, `supersedes`, `supersededBy`, with their indexes) and `/api/v1/lenses` (Toadie's saved filters, PRIVATE or PUBLIC, creator-only mutable, an ADMIN may delete a PUBLIC one). Web: the search box, the grown Filters panel, snippets under the name, every filter in the URL and the lens picker. E2E: `adr-search` and `lenses`.

### v0.4.0 — tags and successors (2026-11-21)

Server (`adrs/`, migrations V13–V14): the `adr_tags` table and the ADMIN tag registry (`/api/v1/tag-categories`, V13), the successor link (V14, `SuccessorGraph`) and `SUPERSEDED` on activation of the successor, with the loop refused and the successor detachable by the predecessor's Driver, Approver or ADMIN (`DELETE /adrs/{id}/successor`). Web: the Tags page, tags on the ADRs and the list's tag filter, the successor panel on both pages, the new history sentences. E2E: the tag and successor scenarios.

### v0.3.0 — discussion (2026-11-20)

Server (`adrs/`, migration V12): `adr_discussion` and `/api/v1/adrs/{id}/discussion` (list with `kind` any-of and `status` filters, post, read, edit, delete as a tombstone, and `PUT .../state` to answer, resolve and reopen), the window per kind
(comments/questions pre-active, issues/escalations `ACTIVE`, else the typed 409 `discussion-closed`), eight discussion history events, `openIssueCount` / `unansweredQuestionCount` / `canComment` / `canRaiseIssue` on the ADR. Web: the Document /
Discussion / Issues tabs of the ADR page, the composer, entry cards with edit, delete, answer and resolve (with an optional note), the Open issues column of the list, the new history sentences. E2E: `adr-discussion`.

### v0.2.0 — the ADR core (2026-11-20)

Server (`adrs/`, migrations V10–V11): `/api/v1/adrs` with the gapless `ADR-NNNN` key, the lifecycle map (409 on an illegal move), DACI participants and
their permission verdicts, the `X-Expected-Revision` document PUT, transitions and participants replace, field-level history, the expiry sweep. Web: the
ADR list (`/adrs`, filters in the URL), the create page (`/adrs/new`), the detail page (`/adrs/:id`: sections, inline Markdown editor with a preview,
Status with the allowed moves, DACI editor, Details with the expiry date, paged History), an ADMIN-only delete, a real Home, the palette entries; the
status colour map (gray, grape, cyan, teal, orange, red) lives in `web/src/utils/adrStatus.ts`. E2E: `adr-lifecycle`, `adr-daci`, `adr-history` and the
axe sweep over the ADR pages.

### v0.1.0 — the foundation (2026-11-20)

Adder's bootstrap tree (Flow 1.57.1 trimmed) renamed to Hammurabi: ports 8090/5181/5440/8031, the `hammurabi` namespace, the lapis brand and
the Stele logo. Migrations V1–V9. No ADR feature yet.
