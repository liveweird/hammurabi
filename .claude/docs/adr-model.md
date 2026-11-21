# The ADR domain model

Owns: the ADR record, its lifecycle map, the DACI roles and their permission verdicts, history, discussion (comments and issues), tags,
successors, search, saved filters (lenses) and expiry — the sketch, refined per version as each part is built. Not here: the user's decisions and the roadmap
(`product.md`), list conventions (`list-endpoints.md`), migration and locking rules (`persistence.md`), guard ordering (`authorization.md`).

Read before touching `adrs/` (server) or any ADR page (web).

## The record (`adrs`, 0.2.0 — built)

| Field | Wire | Rule |
|---|---|---|
| id | `id` (int32) | the row key (`SERIAL`, like every table of the foundation: `EventLog`, `lockActiveForWrite` and the typed routes are `UInt`-keyed; the first sketch said UUID); URLs use it |
| number | `number` → shown as `ADR-0042` (`key`) | one global gapless counter, `MAX(number)+1` over every row under `pg_advisory_xact_lock` (Adder's `ThreadService` thread number, made global; `persistence.md` "ADRs (V10)"); never reused, a cancelled or soft-deleted ADR keeps its number; the key pads to four digits and grows beyond 9999 |
| name | `name` | 1–120 chars, single line; unique among non-deleted ADRs, case- and accent-insensitive (`uq_adrs_name_active`, a clash is `409`); omitted or blank at create = the problem's first non-blank line cut to 120 (a clash of the defaulted name is `409` too, the creator names it); a PUT needs a name |
| problem/question | `problem` | required, Markdown, ≤ 20 000 chars |
| context | `context` | Markdown, ≤ 20 000; required to enter `IN_REVIEW` |
| decision | `decision` | Markdown, ≤ 20 000; required to enter `IN_REVIEW` |
| alternatives considered | `alternatives` | Markdown, ≤ 20 000; optional |
| accepted consequences / side effects | `consequences` | Markdown, ≤ 20 000; required to enter `IN_REVIEW` |
| status | `status` | the lifecycle below |
| tags | `tags` `[{name, retired}]` | the ADR's tags from the registry ("Tags" below), alphabetical; set by `PUT /adrs/{id}/tags`, never by the document PUT |
| predecessor / successor | `supersedes` / `supersededBy` `{id, key, name, status}` | the successor link ("Successors" below); `supersedes_id` lives on the successor and is set by `PUT /adrs/{id}/supersedes` |
| expiry date | `expiresOn` (`YYYY-MM-DD`, nullable) | the LAST day the decision is in force: the sweep expires an `ACTIVE` ADR whose date is before today (UTC), i.e. the day after. A CHANGED date must not be in the past (any non-terminal status); entering `ACTIVE` with a date already past is `400` (the sweep would expire it at once) |
| revision | `revision` + `X-Expected-Revision` | optimistic concurrency on the document PUT, the transitions, the participants PUT and (0.4.0) the tags and supersedes PUTs (Adder/Toadie `infra/revision`, header required on all five); starts at 1, every real change and every sweep move bumps it, a no-op bumps nothing |
| audit | `createdAt`, `createdBy`, `updatedAt`, `statusChangedAt` | epoch millis like every sibling |

The text fields are stored as sanitised (`sanitizeSingleLine` for the name, `sanitizeMultiLine` for the Markdown: CRLF to LF, control and hidden direction characters `400`, leading blank lines and trailing whitespace dropped); the optional ones are empty strings, never null. `problem` is required at create and on every PUT; the others default to empty. A create may carry all the content fields at once (a problem that arrives with its solution).

## Lifecycle (`AdrLifecycle.kt`, Covenant's `contracts/Lifecycle.kt` shape)

```
PROBLEM_RAISED ──► SOLUTION_DRAFT ──► IN_REVIEW ──► ACTIVE ──► EXPIRED     (sweep, expiresOn passed)
      │                  │               │            └──────► SUPERSEDED  (automatic, successor activated)
      └──────────────────┴───────────────┼─► CANCELLED
                                         └─► WITHDRAWN
```

`PROBLEM_RAISED → IN_REVIEW` is allowed too (a problem that arrives with its solution skips the draft). An illegal move is `409` of the
typed problem `urn:hammurabi:problem:illegal-transition`; a move whose target requirements fail is `400` naming the missing fields.
`EXPIRED` and `SUPERSEDED` are never requested by a user: the system moves into them, and a user asking for either gets the same `409`
(ADMIN included). Terminal: `CANCELLED`, `WITHDRAWN`, `EXPIRED`, `SUPERSEDED` — a terminal ADR is read-only (every write is the typed `409`
`urn:hammurabi:problem:adr-frozen`; ADMIN may still soft-delete it).

**Requirements** (`missingForTransition`): entering `IN_REVIEW` needs `context`, `decision`, `consequences` and an Approver (the name and the
problem exist from creation, alternatives stay optional); entering `ACTIVE` re-checks the same (content and participants stay editable in review, so
an Approver may have left) plus a date already set not in the past. The `400` names every missing field in its detail.

**Content freeze** (`requireContentEditable`): name and the five Markdown fields change only in `PROBLEM_RAISED`, `SOLUTION_DRAFT` and
`IN_REVIEW`; once `ACTIVE` a content change is `409 adr-frozen`, while the expiry date, the participants and the tags stay editable. The successor link is the
opposite: it is set only while the ADR is pre-active (a link on an `ACTIVE` ADR is `409 adr-frozen`), because it takes effect at activation.

**Order inside a write** (`authorization.md`), under the row lock:
- document PUT: the coarse role (403), a terminal ADR (409 `adr-frozen`), the **stale revision (409 `revision-conflict`)**, then per field the content freeze (409 `adr-frozen`) or the role (403) and a past expiry date (400) — the revision comes before the per-field judgements, so a stale save is never a false 403 (and never writes a false `authz.denied`) or 400;
- transition: the coarse role (403), the per-target role (403), the lifecycle map (409 `illegal-transition` — also a move out of a terminal status, and a request for `EXPIRED`/`SUPERSEDED`), the stale revision (409), then the target's requirements (400);
- participants: the coarse role (403), a terminal ADR (409 `adr-frozen`), the stale revision (409), then the users (400);
- tags (0.4.0): the coarse role (403), a terminal ADR (409 `adr-frozen`), the stale revision (409), then the names (400: unknown or retired, duplicate, over 20);
- supersedes (0.4.0): the coarse role (403), a terminal ADR (409 `adr-frozen`), the stale revision (409), an `ACTIVE` ADR (409 `adr-frozen`), then the predecessor (400: itself, unknown or deleted, terminal; 409: closes a cycle, already has a successor in play);
- detach successor (0.4.0, `DELETE /adrs/{id}/successor`, no header or body): judged on the PREDECESSOR - the coarse role (403), a terminal predecessor (409 `adr-frozen`), then no pending successor (404);
- the activation also needs the ADR it replaces to be `ACTIVE` (below), judged with the other requirements (400).

A save that changes nothing is `204`, bumps nothing and records nothing.

## DACI roles and verdicts (`AdrAccess.kt`)

Participants live in `adr_participants` (adr, user, role, added at/by). Exactly one `DRIVER` (always present — the creator at first) and at
most one `APPROVER` (required to enter `IN_REVIEW`); any number of `CONTRIBUTOR` and `INFORMED`; one role per user per ADR.

| Action | Who |
|---|---|
| read, list, history, discussion; comment or ask (pre-active), raise an issue or an escalation (active) | any authenticated user |
| create | any authenticated user (becomes the Driver) |
| edit content (pre-active states) | Driver, Contributors, ADMIN |
| `→ SOLUTION_DRAFT`, `→ IN_REVIEW`, `→ CANCELLED` | Driver, ADMIN |
| `→ ACTIVE`, `→ WITHDRAWN` | Approver, ADMIN |
| change participants, expiry date, tags, successor | Driver, Approver, ADMIN (the Driver role can be handed over, never left empty); the successor link is the SUCCESSOR's (the predecessor's roles do not matter) and only while it is pre-active |
| detach a pending successor | Driver, Approver, ADMIN of the PREDECESSOR, while it is non-terminal and the successor is still pre-active (the answer to a link parked by a stranger; below) |
| curate the tag registry (categories and their tags) | ADMIN only; any authenticated user reads it |
| resolve or reopen an issue / escalation | Driver, Approver, ADMIN |
| answer or reopen a question | its author, Driver, Approver, ADMIN |
| edit a discussion entry | its author only |
| delete a discussion entry (a tombstone) | its author, ADMIN |
| soft-delete | ADMIN only |

Guards run before validation: a forbidden write is `403` even when its body is also invalid (`authorization.md`). A missing ADR is `404`.
The verdict is also computed FOR THE CALLER and served on the ADR: `allowedTransitions` (the map minus the system's moves, filtered by the table
above), `canEdit` (content in a pre-active status, or the expiry date in a non-terminal one), `canManageParticipants`, `canEditTags` (a non-terminal ADR), `canEditSuccessor` (a pre-active one) and `canDetachSuccessor` (this ADR is the PREDECESSOR of a pending successor and the caller holds one of the three roles on it), so the SPA never re-derives it.
The coarse guard before the body is "may touch this ADR at all" (edit: Driver, Contributor, Approver, ADMIN; transition, participants, tags and supersedes: Driver,
Approver, ADMIN); the per-target and per-field rows above are re-judged under the row lock (`403`).

**Participants** (`PUT /adrs/{id}/participants`, a wholesale replace applied as a diff, under `X-Expected-Revision` — REQUIRED like the document PUT, so a stale editor cannot silently drop people another one added; a real change bumps the ADR revision, a no-op bumps nothing): exactly one Driver in the set (the creator at first),
at most one Approver, each user once, at most 100, every user NEW to the set (or taking a new role) an active account. One event per added, removed
and changed participant; a set that changes nothing is `204` and records nothing. Creation takes optional extra participants (never a Driver, never
the caller; one `PARTICIPANT_ADDED` each).

## History (`adr_events`, 0.2.0)

Lettuce's/Adder's `EventLog` machinery: one immutable structural event per mutation, committed in the same transaction as the write.
Types and their string `params` (the SPA renders them in the viewer's language; no rendered text is stored): `CREATED` `{key, name}` (the actor is the
Driver), `UPDATED` `{changed: "name,context,…", <field>.from?, <field>.to?}` (field-level: the Markdown fields — problem, context, decision,
alternatives, consequences — by NAME only, the short ones `name` and `expiresOn` with their sides, an absent side meaning unset; Toadie's
`FieldChange` vocabulary, one event per save), `TRANSITIONED` `{from, to}`, `PARTICIPANT_ADDED` / `PARTICIPANT_REMOVED` `{userId, name, role}`,
`PARTICIPANT_CHANGED` `{userId, name, from, to}`, `EXPIRED` `{expiresOn}` (system actor), and from 0.4.0 `TAGS_CHANGED` `{added?, removed?}` (comma-joined tag names, each key only when non-empty), `SUCCESSOR_LINKED` / `SUCCESSOR_UNLINKED` `{predecessorId, key, name}` (on the successor; a detach also writes `SUCCESSOR_UNLINKED` `{successorId, key, name}` on the PREDECESSOR), `SUPERSEDED` `{successorId, key, name}` (on the superseded predecessor, actor = the user who activated the successor) and `SUCCESSOR_ACTIVATED` `{predecessorId, key, name}` (on the successor, same transaction),
the discussion's eight (0.3.0, below: `COMMENTED`, `QUESTION_ASKED`, `QUESTION_ANSWERED`, `QUESTION_REOPENED`, `ISSUE_RAISED`, `ESCALATED`, `ISSUE_RESOLVED`, `ISSUE_REOPENED`). A no-op save records nothing, and so does a refused write. The
actor is the acting user (`userId`, `userName`, `userDeleted`), or NULL for a system event. Read through `GET /api/v1/adrs/{id}/events` (paged,
newest first by default, sortable by `id` and `createdAt`; any authenticated user). A soft delete is audit-only (the ADR is gone from every read).

## Expiry (`AdrExpirySweep.kt`, 0.2.0)

A periodic loop on the background scope (Covenant's `ReleaseLineReminders` shape; `adrs.expiry.enabled` / `adrs.expiry.intervalSeconds`, env
`ADRS_EXPIRY_*`, boot-validated) sweeps at start and then every interval: it moves every `ACTIVE` ADR whose `expires_on` is before today (UTC; the
date is the last day in force) to `EXPIRED`, each in its own transaction (the revision bumps) with a system-actor event and the `adr.expired` audit.
Idempotent and safe on several replicas (the candidate scan is only a filter; each ADR is re-read under its row lock with the status and date
predicate). "Today" is an injected `Clock`, so tests expire deterministically (`AdrExpirySweepTest`); the suite itself runs no loop.

## Endpoints (0.2.0, discussion 0.3.0)

`GET /adrs` (paged; filters `status` any-of, `tag` any-of, `q` name-or-key, `text` full-text, `participant` + `role`, the created/updated day ranges, `expiresBefore`, `hasOpenIssues`, `supersedes`, `supersededBy` ("Search"); sort `id`, `number` (default, newest first), `name`, `updatedAt`,
`status` in lifecycle order, `relevance` with `text`), `POST /adrs`, `GET /adrs/{id}`, `PUT /adrs/{id}` (document, `X-Expected-Revision`), `POST /adrs/{id}/transitions`
(`{to}`, `X-Expected-Revision`, answers the ADR), `PUT /adrs/{id}/participants`, `PUT /adrs/{id}/tags` and `PUT /adrs/{id}/supersedes` (both under `X-Expected-Revision`, 0.4.0), `DELETE /adrs/{id}/successor` (0.4.0), `GET /adrs/{id}/events`, `DELETE /adrs/{id}` (ADMIN), `/lenses` (0.5.0, "Lenses"), the discussion routes below and the registry `GET|POST /tag-categories`, `PUT|DELETE /tag-categories/{id}` (both under `X-Expected-Revision`). The contract is
`openapi/documentation.yaml`; the permission order `authorization.md`; the audit events `observability.md`.

## Discussion (`adr_discussion`, 0.3.0 — built)

`adrs/AdrDiscussion*.kt`, V12; Covenant's `version_reviews` ported and reshaped (its open/outdated/closed review and approvals are gone: an ADR has ONE open thread, an entry has a kind and a state). One table, one service
(`AdrDiscussionService`), the pure verdicts in `AdrDiscussionAccess.kt`. An open decision log: **any authenticated user reads and posts**.

| Kind | Accepted while the ADR is | State | Settled by |
|---|---|---|---|
| `COMMENT` | `PROBLEM_RAISED`, `SOLUTION_DRAFT`, `IN_REVIEW` | none | - |
| `QUESTION` | the same | `OPEN` → `ANSWERED` (and back) | its author, the Driver, the Approver, ADMIN |
| `ISSUE`, `ESCALATION` | `ACTIVE` | `OPEN` → `RESOLVED` (and back), an optional `resolution` note (≤ 2 000) | the Driver, the Approver, ADMIN |

- **The window** (`DiscussionKind.acceptedIn`) is judged under the ADR row lock (`FOR NO KEY UPDATE`, like every ADR write), so a post can never slip in after a lifecycle move; outside it a post or an edit is the typed `409` `urn:hammurabi:problem:discussion-closed`. The
  **state change** (answer, resolve, reopen) and the **delete** are open in EVERY status: an issue on an ADR that later expired or was superseded can still be settled, a question answered after activation, and the moderation path is never closed. Reads are open in every status: **entries stay readable forever**.
  A discussion write never bumps the ADR `revision` (the discussion is not part of the document), so it never conflicts with a document edit.
- **The entry.** Markdown `body` 1–10 000 characters (`sanitizeMultiLine`; blank, too long or a control character is `400`), the author, `createdAt`, `editedAt` (the author's edit; the same text writes nothing), `status`, `resolution`,
  `stateChangedBy/At`. A **soft delete leaves a tombstone**: `deleted` true, `body`, `status`, `resolution`, `editedAt`, `stateChangedBy` and `stateChangedAt` null on the wire AND wiped in the table (`ck_adr_discussion_tombstone` pins that shape), kind/author/`createdAt` kept; no status filter and no count sees it; a write to a deleted
  entry is `404`. Each entry carries the caller's verdicts (`canEdit`: the author, window open; `canDelete`: the author or ADMIN, not yet deleted; `canChangeState`: a live entry with a state and the role above, in any ADR status) so the SPA never re-derives them. The ADR
  carries `canComment` / `canRaiseIssue` (the window as a flag: the composers) and the counts `openIssueCount` (open issues + escalations) and `unansweredQuestionCount` (open questions), tombstones excluded; the list rows carry `openIssueCount`.
  The counts are ONE aggregate per read or list page (`WHERE adr_id IN (...) AND status = 'OPEN' AND NOT marked_as_deleted GROUP BY adr_id, kind`) over the partial index `idx_adr_discussion_open`.
- **Routes.** `GET /adrs/{id}/discussion` (paged; `kind` any-of, `status`; sort `id`, `createdAt`; default **oldest first**), `POST` (201 + `Location`, answers the entry), `GET .../{entryId}`, `PUT .../{entryId}` (edit the body, author only),
  `DELETE .../{entryId}` (204), `PUT .../{entryId}/state` (`{status, resolution?}`: set the target state, idempotent - answered/resolved/reopened; answers the entry). Order of one write (`authorization.md`): the ADR / entry (`404`; an entry of another ADR is
  `404`) → the coarse guard (`403` before the body) → the body (`400`) → under the lock the guard again, then (post and edit only) the window (`409`), then the state against the kind (`400`: a comment has no state, a question is `OPEN`/`ANSWERED`, an issue `OPEN`/`RESOLVED`, a note only with `RESOLVED`).
- **History.** Every post and every REAL state change is an `adr_events` row in the same transaction, params `{entryId, kind}` (never the text; the SPA reads the entry through the discussion endpoint): `COMMENTED`, `QUESTION_ASKED`,
  `ISSUE_RAISED`, `ESCALATED` on a post; `QUESTION_ANSWERED` / `QUESTION_REOPENED`, `ISSUE_RESOLVED` / `ISSUE_REOPENED` (the `kind` param says issue or escalation) on a state change; setting the state an entry already has records nothing. The one exception to "nothing": a RESOLVED entry given a DIFFERENT note keeps its state and has the note replaced (an empty one clears it; who/when follow the editor), still with no history event and audited as `adr.discussion_state_changed` with `from == to`; the same note is a plain no-op.
  **Edits and deletes are NOT events** (decision): the entry itself shows `editedAt` / the tombstone, and the audit trail has `adr.discussion_edited` / `adr.discussion_deleted`; an event per edit would drown the ADR's history in typing.
- **Audit** (`observability.md`): `adr.discussion_posted`, `adr.discussion_edited`, `adr.discussion_deleted` (with `byAdmin`), `adr.discussion_state_changed` (`from`/`to`) after the commit, only on a change; a `403` is the generic `authz.denied`.
- **Body ceilings** (`plugins/Http.kt`): an entry post or edit 64 KiB (`MAX_DISCUSSION_REQUEST_BODY_BYTES`, sized so even a body whose every unit is `\u`-escaped, 60 000 bytes, fits), the state PUT the 16 KiB small ceiling.
- **Web.** The ADR page's Discussion tab (comments and questions) and Issues tab (issues and escalations), `web/CLAUDE.md` "The ADR pages".

## Tags (0.4.0 - built)

Toadie's ADMIN-curated tag categories (`tags/`, V13), trimmed of everything Backstage: a **category** = a name (1-60, single line, unique among active categories
case-insensitively) + an optional description (at most 500) + its **tags** (1-100); a **tag** is in exactly ONE category. `GET /tag-categories` is open to any authenticated user (the
editor's picker, the list filter), every write is ADMIN-only with the guard before the header, the body and the id (`403` even for an unknown id), and the registry is capped at 100 categories. A category carries a `revision` (V13, starts at 1): its **PUT and DELETE take `X-Expected-Revision`** (required: `400` missing; order: guard `403` → header `400` → body `400` → existence `404` → stale revision `409` `revision-conflict` → the claims), judged under the registry lock, so an admin editing a stale copy gets a `409` instead of silently overwriting another admin's tag list; every PUT bumps it (the registry has no no-op rule), a delete bumps it too. The SPA sends the revision the category had when its editor or confirmation OPENED.

- **A tag name** is normalised at every entry (trimmed, control/hidden characters `400`, whitespace runs collapsed, lower-cased), 1-40 characters of letters and digits with spaces and `. _ / + # : -` inside (no comma: events join names with one). It is unique over EVERY row, retired ones included (`uq_tags_name`).
- **Storage.** `tag_categories`, `tags` (one row per tag, `category_id`), `adr_tags` (ADR <-> tag, `persistence.md` "Tag categories (V13)"). Registry writes run in a `lockingTransaction` (a table lock, Toadie's protocol), so two admins editing at once queue; a cross-category claim is `409` naming the owner.
- **Strict.** An ADR write carrying a name that is not a LIVE registry tag is `400` (unknown or retired), a duplicate is `400`, an ADR carries at most 20 (the count is judged BEFORE any name is normalised, so an oversized list is refused without sanitising it).
- **Retired, not erased (the decision).** Removing a tag from a category, or deleting the category (a soft delete that frees its name), RETIRES the tag rows (`marked_as_deleted`). Toadie keeps stored references and reports them as findings; here an ADR keeps its `adr_tags` row and the API serves the tag as `retired: true` (the SPA draws it struck through and says "retired" to assistive technology) until the next tag write
  drops it - and a tag write that still carries it is `400`, so the editor starts from the live tags and says the retired ones are dropped on save. Adding the name to a category again REVIVES the same row, so those ADRs show it live again; moving a tag between categories is two saves (drop, then add). The list's `tag` filter still matches a retired tag on the ADRs that kept it.
- **Seed.** V13 inserts two small categories an ADMIN can rename, extend or delete: **Domain** (`data`, `security`, `infrastructure`, `frontend`, `integration`) and **Scope** (`team`, `system`, `organisation`).
- **Setting them.** `PUT /adrs/{id}/tags` `{tags: [names]}` under `X-Expected-Revision` (a dedicated route, not a field of the document PUT: the document PUT is a content write for Contributors too and tags are the Driver's, Approver's and ADMIN's, in every non-terminal status - `ACTIVE` included, they are outside the content freeze). A wholesale replace applied as a diff; a real change bumps the revision and records one `TAGS_CHANGED` event; the same set is `204` and records nothing.
- **Reading them.** The ADR and every list row carry `tags`; the list takes `tag` (repeatable, any-of, at most 20, normalised, each at most 40 characters).

## Successors (0.4.0 - built)

`adrs.supersedes_id` (V14) on the SUCCESSOR names the ADR it replaces: one predecessor at most, set by `PUT /adrs/{id}/supersedes` `{supersedesId}` (omitted or null removes it) under `X-Expected-Revision`, by the successor's Driver, Approver or ADMIN, **only while the successor is pre-active** (it takes effect at activation; the product assumption that the link also changes once `ACTIVE` was dropped for this reason).

- **Squatting and the detach (decision, `product.md` "Assumptions").** Linking needs no role on the PREDECESSOR, and a predecessor has one successor in play, so a stranger could park a draft on any ADR and block everyone else. The PREDECESSOR's Driver, Approver or ADMIN may therefore **detach** its pending successor: `DELETE /adrs/{id}/successor` (no header, no body; the route names the one pending link, and the service judges it under the locks). Under the `ADRLINKS` lock and BOTH rows (id order, the activation's pair order) it clears the successor's `supersedes_id`, **bumps the successor's revision** (its open editors and a racing activation conflict; the predecessor's own revision does not move) and writes `SUCCESSOR_UNLINKED` on both ADRs (audit `adr.successor_detached`). `204`; `403` for anyone else (judged on the predecessor); `409` `adr-frozen` for a terminal predecessor (an activated successor superseded it); `404` when no successor is pending (also one that was cancelled or withdrawn). The successor's Driver may link again - detaching is the owner's veto of THIS link, not a ban.

- **The predecessor** must be another live ADR (`400`), `ACTIVE` or pre-active (`400` for a terminal one: nothing left to replace), must not close a cycle (`409`, `SuccessorGraph.cycleClosedBy`, Astrolabe's kernel reduced to a walk UP the stored `supersedes_id` chain from the requested predecessor: O(depth) one-row lookups, never the whole table, and a link whose successor is `CANCELLED`/`WITHDRAWN`/deleted no longer counts; the walk runs under the `ADRLINKS` advisory lock, taken before the row lock, because a cycle spans rows) and must have no other successor in play (`409`; `uq_adrs_supersedes_open` is the partial-unique backstop over non-terminal, non-deleted successors, so a cancelled or withdrawn attempt frees the predecessor for another).
- **Activation.** When the successor becomes `ACTIVE`, the predecessor moves `ACTIVE -> SUPERSEDED` in the SAME transaction: both rows are locked together in id order (the predecessor id is peeked first; every link change bumps the successor's revision, so a link that moved in between is a stale-revision `409` before anything else), the predecessor's revision bumps, and it gets `SUPERSEDED` (actor = the activator) while the successor gets `TRANSITIONED` and `SUCCESSOR_ACTIVATED`. Audit: `adr.transitioned` then `adr.superseded`.
- **The predecessor is not `ACTIVE` at that moment** (it was only a draft when linked, or it expired or was cancelled since): the activation is refused with `400` naming it (a requirement of the move, like the date), and the successor's Driver, Approver or ADMIN removes the link or waits for it to be activated. A predecessor soft-deleted meanwhile reads as no link and the activation goes through, leaving it alone. A pre-active predecessor may be linked precisely so the two can be prepared together; only an `ACTIVE` one is ever superseded.
- **Both ends on the wire.** `supersedes` (the ADR this one replaces) and `supersededBy` (its pending successor, or once `SUPERSEDED` the one that replaced it; never a cancelled or withdrawn one), each `{id, key, name, status}`. History: `SUCCESSOR_LINKED` / `SUCCESSOR_UNLINKED` on the successor (a re-link is both; the old end is resolved even when that ADR was soft-deleted meanwhile, so the unlink is always recorded), and on the predecessor a detach's `SUCCESSOR_UNLINKED`, `SUPERSEDED` and `SUCCESSOR_ACTIVATED` at activation. The detail page shows both links and the "Superseded by ADR-00xx" banner (`web/CLAUDE.md`).

## Search (0.5.0 - built)

`GET /adrs` is the one search surface: the older filters (`status`, `tag`, `participant` + `role`, `q`) and the 0.5.0 ones, all AND-combined, on ONE predicate for the count and the rows (`AdrListQuery.kt`, `AdrListService.kt`; `list-endpoints.md`).

- **Full-text `text` (decision: a separate parameter from `q`).** `q` stays the cheap name-or-key substring (the key `ADR-0042`, a name fragment); `text` is Adder's full-text search (V15, `persistence.md` "Search and filters (V15)"; `infra/db/Fts.kt`, `SearchBudget.kt`) over ONE stored generated `tsvector` with the accent-folding `hammurabi_search` configuration: the name weighs A, the problem and the decision B, the context, the alternatives and the consequences C. Case- and accent-insensitive ("zolw" finds "Żółw"), no stemming, every word must match (AND, any order), a word of three characters or more matches as a prefix, and no input is a query error: the tsquery is built from `to_tsvector`'s own lexemes (Adder's approach, chosen over `websearch_to_tsquery` so documents and queries share one parser and the operators are plain punctuation). `text` is 2 to 100 code points after trimming with a letter or digit (`400`; blank = no search). The SPA's search box sends `text`; `q` stays in the Filters panel as "Name or key".
- **Ranking.** With `text` the default sort is `-relevance,-number` (`ts_rank_cd`, the weights above, newest among equals); `relevance` is a sortable name only with `text` (`400` otherwise) and any explicit `sort` wins. A search page is at most 20 rows.
- **Snippets, never markup.** With `text` each row carries `match: {name, snippet}` of typed segments `{text, hit}`: the whole name with its hits marked and one or two fragments of the body (decision, problem, context, alternatives, consequences, in that order; empty when only the name matched). `ts_headline` is asked for the page's ids only and returns its input untouched, so it is requested with the control characters U+0002/U+0003 as markers (unforgeable: every name and Markdown field refuses control characters) and split server-side (`splitHeadline`); the SPA renders `text` as React text and wraps the hits in a `<mark>`. Without `text`, `match` is null.
- **The deadline and the cap** (`security.md` "Search"): one `search.timeoutSeconds` deadline for all the statements of a search and at most `search.maxConcurrent` searches at once; the typed `429`s `search-timeout` and `search-busy` carry `Retry-After`. The search term is user content and reaches no log, audit line or span.
- **The other filters.** `createdFrom`/`createdTo` and `updatedFrom`/`updatedTo` are inclusive UTC days (`YYYY-MM-DD`; from after to is `400`); `expiresBefore` keeps the ADRs whose expiry date (the LAST day in force) is strictly earlier, any status, an ADR without a date never matches; `hasOpenIssues` is `openIssueCount > 0` (open issues and escalations) or its complement; `supersedes` is "has a live predecessor", `supersededBy` "has a successor in play or in effect, never a cancelled or withdrawn one" (the wire's `supersedes` / `supersededBy` rule), each `true` or `false`.
- **Not built (assumptions, change on request):** the lens filter has no "me" placeholder (a `participant` is a user id), a filter has no OR across criteria, and the search does not read the discussion text or the history.

## Lenses (0.5.0 - built)

Saved filters, Toadie's lenses (`lenses/`, V16) ported: a named, per-user snapshot of the list's filters. The payload is the list's OWN filter document (`AdrFilterDocument`: the query parameter names, `status` and `tag` as arrays, the dates as `YYYY-MM-DD`), validated by the SAME rules as the query string (`normalized()`) and stored canonical; it carries no sort and no page (a lens is a filter, not a view). A user keeps at most 50; the name is unique per owner (case-insensitively, `409`). **PRIVATE** = visible to the creator only; **PUBLIC** = visible to every signed-in user (the creator's name beside it), changed by its creator only; an ADMIN may delete a PUBLIC lens (moderation) and sees no PRIVATE one. Existence disclosure and ordering follow Toadie's `mutationVerdict` (`authorization.md`): foreign PRIVATE or unknown = `404`, foreign PUBLIC = `403`, both before the body's `400`. Routes: `GET|POST /lenses`, `GET|PUT|DELETE /lenses/{id}` (PUT is the whole replace: rename, the visibility flip and overwriting the filters; no revision header, last write wins like Toadie's). A lens holding a participant or a tag that no longer exists is not an error: it matches nothing until edited. A `status` or `role` value this release no longer knows (a later release removed it) is dropped when the lens is read, an unknown key is ignored, and the lens is listable all the same (it matches on its remaining keys until it is saved again; `persistence.md` "Persisted enums"). The 50-lens cap is counted under a per-user advisory lock, so racing creates cannot overshoot it. Applying a lens is the ordinary list request with its filters; the SPA's picker copies them into the URL (`web/CLAUDE.md`).
