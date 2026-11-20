# The ADR domain model

Owns: the ADR record, its lifecycle map, the DACI roles and their permission verdicts, history, discussion (comments and issues), tags,
successors and expiry — the sketch, refined per version as each part is built. Not here: the user's decisions and the roadmap
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
| expiry date | `expiresOn` (`YYYY-MM-DD`, nullable) | the LAST day the decision is in force: the sweep expires an `ACTIVE` ADR whose date is before today (UTC), i.e. the day after. A CHANGED date must not be in the past (any non-terminal status); entering `ACTIVE` with a date already past is `400` (the sweep would expire it at once) |
| revision | `revision` + `X-Expected-Revision` | optimistic concurrency on the document PUT, the transitions and the participants PUT (Adder/Toadie `infra/revision`, header required on all three); starts at 1, every real change and every sweep move bumps it, a no-op bumps nothing |
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
`IN_REVIEW`; once `ACTIVE` a content change is `409 adr-frozen`, while the expiry date and the participants stay editable (tags and the successor link
join them in 0.4.0).

**Order inside a write** (`authorization.md`), under the row lock:
- document PUT: the coarse role (403), a terminal ADR (409 `adr-frozen`), the **stale revision (409 `revision-conflict`)**, then per field the content freeze (409 `adr-frozen`) or the role (403) and a past expiry date (400) — the revision comes before the per-field judgements, so a stale save is never a false 403 (and never writes a false `authz.denied`) or 400;
- transition: the coarse role (403), the per-target role (403), the lifecycle map (409 `illegal-transition` — also a move out of a terminal status, and a request for `EXPIRED`/`SUPERSEDED`), the stale revision (409), then the target's requirements (400);
- participants: the coarse role (403), a terminal ADR (409 `adr-frozen`), the stale revision (409), then the users (400).

A save that changes nothing is `204`, bumps nothing and records nothing.

## DACI roles and verdicts (`AdrAccess.kt`)

Participants live in `adr_participants` (adr, user, role, added at/by). Exactly one `DRIVER` (always present — the creator at first) and at
most one `APPROVER` (required to enter `IN_REVIEW`); any number of `CONTRIBUTOR` and `INFORMED`; one role per user per ADR.

| Action | Who |
|---|---|
| read, list, history, comment (pre-active), raise an issue (active) | any authenticated user |
| create | any authenticated user (becomes the Driver) |
| edit content (pre-active states) | Driver, Contributors, ADMIN |
| `→ SOLUTION_DRAFT`, `→ IN_REVIEW`, `→ CANCELLED` | Driver, ADMIN |
| `→ ACTIVE`, `→ WITHDRAWN` | Approver, ADMIN |
| change participants, expiry date, tags, successor | Driver, Approver, ADMIN (the Driver role can be handed over, never left empty) |
| resolve an issue | Driver, Approver, ADMIN |
| soft-delete | ADMIN only |

Guards run before validation: a forbidden write is `403` even when its body is also invalid (`authorization.md`). A missing ADR is `404`.
The verdict is also computed FOR THE CALLER and served on the ADR: `allowedTransitions` (the map minus the system's moves, filtered by the table
above), `canEdit` (content in a pre-active status, or the expiry date in a non-terminal one) and `canManageParticipants`, so the SPA never re-derives it.
The coarse guard before the body is "may touch this ADR at all" (edit: Driver, Contributor, Approver, ADMIN; transition and participants: Driver,
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
`PARTICIPANT_CHANGED` `{userId, name, from, to}`, `EXPIRED` `{expiresOn}` (system actor), `SUPERSEDED` (system actor; reserved, written from 0.4.0),
later `TAGGED`, `SUCCESSOR_LINKED`, `COMMENTED`, `ISSUE_RAISED`, `ISSUE_RESOLVED`. A no-op save records nothing, and so does a refused write. The
actor is the acting user (`userId`, `userName`, `userDeleted`), or NULL for a system event. Read through `GET /api/v1/adrs/{id}/events` (paged,
newest first by default, sortable by `id` and `createdAt`; any authenticated user). A soft delete is audit-only (the ADR is gone from every read).

## Expiry (`AdrExpirySweep.kt`, 0.2.0)

A periodic loop on the background scope (Covenant's `ReleaseLineReminders` shape; `adrs.expiry.enabled` / `adrs.expiry.intervalSeconds`, env
`ADRS_EXPIRY_*`, boot-validated) sweeps at start and then every interval: it moves every `ACTIVE` ADR whose `expires_on` is before today (UTC; the
date is the last day in force) to `EXPIRED`, each in its own transaction (the revision bumps) with a system-actor event and the `adr.expired` audit.
Idempotent and safe on several replicas (the candidate scan is only a filter; each ADR is re-read under its row lock with the status and date
predicate). "Today" is an injected `Clock`, so tests expire deterministically (`AdrExpirySweepTest`); the suite itself runs no loop.

## Endpoints (0.2.0)

`GET /adrs` (paged; filters `status` any-of, `q` name-or-key, `participant` + `role`; sort `id`, `number` (default, newest first), `name`, `updatedAt`,
`status` in lifecycle order), `POST /adrs`, `GET /adrs/{id}`, `PUT /adrs/{id}` (document, `X-Expected-Revision`), `POST /adrs/{id}/transitions`
(`{to}`, `X-Expected-Revision`, answers the ADR), `PUT /adrs/{id}/participants`, `GET /adrs/{id}/events`, `DELETE /adrs/{id}` (ADMIN). The contract is
`openapi/documentation.yaml`; the permission order `authorization.md`; the audit events `observability.md`.

## Discussion (0.3.0)

`adr_discussion` (Covenant's `version_reviews`, ported): `kind` `COMMENT`/`QUESTION` (accepted while the ADR is pre-active; a question can be
marked answered) or `ISSUE`/`ESCALATION` (accepted while `ACTIVE`; `OPEN`/`RESOLVED`). Markdown body ≤ 10 000 chars; author edits own;
soft-deleted with a tombstone.

## Tags (0.4.0)

Toadie's ADMIN-curated tag categories (`tags/`): a category = name + its tags, each tag in exactly one category. `adr_tags` joins ADRs to
tags; every write is strict (an unknown tag is `400`).

## Successors (0.4.0)

`adrs.supersedes_id` on the successor (at most one predecessor; a predecessor has at most one non-terminal successor). A link that would close
a cycle is refused (Astrolabe's `DependencyGraph.cycleClosedBy`). When the successor becomes `ACTIVE` and the predecessor is `ACTIVE`, the
predecessor moves to `SUPERSEDED` in the same transaction; both get an event.

## Search (0.4.0)

Filters: status (multi), tag (multi), participant (+ role), created/updated ranges, expires-before, free text (Adder's generated `tsvector`
with `unaccent` over name, problem, context, decision). Saved as Toadie lenses (PRIVATE/PUBLIC).
