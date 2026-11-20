# The ADR domain model

Owns: the ADR record, its lifecycle map, the DACI roles and their permission verdicts, history, discussion (comments and issues), tags,
successors and expiry — the sketch, refined per version as each part is built. Not here: the user's decisions and the roadmap
(`product.md`), list conventions (`list-endpoints.md`), migration and locking rules (`persistence.md`), guard ordering (`authorization.md`).

Read before touching `adrs/` (server) or any ADR page (web).

## The record (`adrs`, 0.2.0)

| Field | Wire | Rule |
|---|---|---|
| id | `id` (UUID) | the row key; URLs use it |
| number | `number` → shown as `ADR-0042` (`key`) | one global gapless counter, `MAX(number)+1` under the `adrs` advisory lock (Adder's `ThreadService` thread number); never reused, a cancelled or soft-deleted ADR keeps its number |
| name | `name` | 1–120 chars, single line; unique among non-deleted ADRs, case- and accent-insensitive; defaults to the problem's first line truncated |
| problem/question | `problem` | required, Markdown, ≤ 20 000 chars |
| context | `context` | Markdown, ≤ 20 000; required to enter `IN_REVIEW` |
| decision | `decision` | Markdown, ≤ 20 000; required to enter `IN_REVIEW` |
| alternatives considered | `alternatives` | Markdown, ≤ 20 000; optional |
| accepted consequences / side effects | `consequences` | Markdown, ≤ 20 000; required to enter `IN_REVIEW` |
| status | `status` | the lifecycle below |
| expiry date | `expiresOn` (`YYYY-MM-DD`, nullable) | when set while `ACTIVE`, must not be in the past; the sweep expires the ADR on that date |
| revision | `revision` + `ETag`-style `X-Expected-Revision` | optimistic concurrency on every write (Adder/Toadie `infra/revision`) |
| audit | `createdAt`, `createdBy`, `updatedAt`, `statusChangedAt` | epoch millis like every sibling |

## Lifecycle (`AdrLifecycle.kt`, Covenant's `contracts/Lifecycle.kt` shape)

```
PROBLEM_RAISED ──► SOLUTION_DRAFT ──► IN_REVIEW ──► ACTIVE ──► EXPIRED     (sweep, expiresOn passed)
      │                  │               │            └──────► SUPERSEDED  (automatic, successor activated)
      └──────────────────┴───────────────┼─► CANCELLED
                                         └─► WITHDRAWN
```

`PROBLEM_RAISED → IN_REVIEW` is allowed too (a problem that arrives with its solution skips the draft). An illegal move is `409`
(`ILLEGAL_TRANSITION`); a move whose target requirements fail (missing decision, no Approver) is `400` naming the missing fields.
`EXPIRED` and `SUPERSEDED` are never requested by a user: the system moves into them. Terminal: `CANCELLED`, `WITHDRAWN`, `EXPIRED`,
`SUPERSEDED`.

## DACI roles and verdicts (`AdrAccess.kt`)

Participants live in `adr_participants` (adr, user, role, added at/by). Exactly one `DRIVER` (always present — the creator at first) and at
most one `APPROVER` (required to enter `IN_REVIEW`); any number of `CONTRIBUTOR` and `INFORMED`; one role per user per ADR.

| Action | Who |
|---|---|
| read, list, comment (pre-active), raise an issue (active) | any authenticated user |
| create | any authenticated user (becomes the Driver) |
| edit content (pre-active states) | Driver, Contributors, ADMIN |
| `→ SOLUTION_DRAFT`, `→ IN_REVIEW`, `→ CANCELLED` | Driver, ADMIN |
| `→ ACTIVE`, `→ WITHDRAWN` | Approver, ADMIN |
| change participants, expiry date, tags, successor | Driver, Approver, ADMIN (the Driver role can be handed over, never left empty) |
| resolve an issue | Driver, Approver, ADMIN |
| soft-delete | ADMIN only |

Guards run before validation: a forbidden write is `403` even when its body is also invalid (`authorization.md`). A missing ADR is `404`.

## History (`adr_events`, 0.2.0)

Lettuce's/Adder's `EventLog` machinery: one immutable structural event per mutation, committed in the same transaction as the write.
Types: `CREATED`, `UPDATED` (field-level `changed` list; Markdown fields recorded as the bare fact that they changed, short fields with
`<field>.from/.to`), `TRANSITIONED` (`from`, `to`), `PARTICIPANT_ADDED/REMOVED/CHANGED`, `EXPIRED` and `SUPERSEDED` (system actor),
later `TAGGED`, `SUCCESSOR_LINKED`, `COMMENTED`, `ISSUE_RAISED`, `ISSUE_RESOLVED`. A no-op save records nothing. Read through
`GET /api/v1/adrs/{id}/events` (paged) and rendered in the viewer's language.

## Expiry (`AdrExpirySweep.kt`, 0.2.0)

A periodic loop on the background scope (Covenant's `ReleaseLineReminders` shape, `adrs.expiry.*` config: enabled, interval) moves every
`ACTIVE` ADR whose `expires_on` is before today (UTC) to `EXPIRED`, each in its own transaction with a system-actor event. Idempotent and
safe on several replicas (the row lock + status predicate).

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
