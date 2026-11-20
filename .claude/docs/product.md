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
   `IN_REVIEW` (manual); `EXPIRED` from `ACTIVE` once its expiry date passes.
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
- **Content freeze:** content is editable only in the three pre-decision states; once `ACTIVE` only the expiry date, tags, participants and
  the successor link change; terminal states are read-only.
- **Required fields:** creating needs only the problem/question (the name defaults to its first line, truncated); entering `IN_REVIEW`
  requires name, context, decision, consequences and an assigned Approver; alternatives stay optional.
- **Comments vs issues:** comments/questions are accepted only before `ACTIVE`; issues/escalations only in `ACTIVE` (OPEN/RESOLVED, resolved
  by the Driver, the Approver or an ADMIN). Both stay readable forever.
- **Not built (one-line map changes if wanted):** `IN_REVIEW → SOLUTION_DRAFT` ("send back") and `ACTIVE → WITHDRAWN`.
- **User directory:** any signed-in user may look up `{id, name, email}` of active users (`GET /users/directory`, Adder's, for the DACI pickers); the
  name+email disclosure to every signed-in user that Adder's owner accepted is carried over (`security.md` "What the user directory discloses"). Collapse it to `{id, name}` if that changes.
- Flow's integration service tokens are not ported: nothing external writes ADRs yet.
- The coverage floors were re-baselined once after the trim (lines 97, branches 81, as Adder's).

## Roadmap

Each increment is built and gated on its own. **Current version: 0.1.0.**

| Ver | Scope |
|---|---|
| **0.1.0 Foundation** | Flow trimmed (Adder's bootstrap tree): auth and email MFA, password reset, users and feature flags, teams with a manager, EN/PL, light/dark, changelog, telemetry panel, the lapis brand and the Stele logo |
| **0.2.0 ADR core** | `adrs/`: ids, fields, the lifecycle map (409 on an illegal move), DACI participants and their permission verdicts, `X-Expected-Revision`, field-level history, the expiry sweep; list, create and detail pages |
| **0.3.0 Discussion** | Comments/questions before `ACTIVE`, issues/escalations in `ACTIVE` (Covenant's version reviews, ported) |
| **0.4.0 Organise and find** | Tag categories (Toadie), successor links + `SUPERSEDED`, multi-criteria filters + full-text search (Adder), URL-synced filters, saved lenses (Toadie) |
| **0.5.0 Notify** | The bell, email digest and preferences (Adder's `notifications/`) for DACI participants; expiry reminders |
| **1.0.0 Checkup** | The family's technical checkup (docs, DRY/SRP, tests, UI consistency, security, guardrails, infra) |

## Donors

Hammurabi's repository started from Adder's bootstrap tree (`~/Sources/adder` @ `defb001`, 2026-11-17), which is itself an exact copy of Flow @
`4e43e480` (`~/Sources/flow`, Flow 1.57.1 — still Flow's HEAD on 2026-11-20) trimmed in the shape Astrolabe's bootstrap (2026-10-31) set. Flow
was copied from Covenant; Lettuce and Toadie came in through it. **Port, don't reinvent**: when Hammurabi needs a capability a sibling has,
port its implementation.

- **Flow** (`~/Sources/flow`, the primary donor): the whole foundation — stack, API design, UI/UX, testing, gates, versioning, deployment, the
  telemetry panel.
- **Adder** (`~/Sources/adder`): the trimmed bootstrap tree; next: `infra/db/EventLog.kt`, `infra/db/Fts.kt` + the search migration,
  `infra/revision/ExpectedRevision.kt`, the user directory and pickers (`UserDirectorySelect`), the thread number allocation, the
  `ThreadPane`/`ThreadSidePanel` detail layout, `EventTimeline`, Markdown building blocks, `notifications/`, the post-bootstrap security fixes.
- **Covenant** (`~/Sources/covenant`): `contracts/Lifecycle.kt` (the 409-on-illegal-move transition map), `ContractAccess.kt` (permission
  verdicts), `VersionReview*` (comments/approvals), `ReleaseLineReminders.kt` (the periodic date sweep).
- **Toadie** (`~/Sources/toadie`): `tags/` tag categories, `lenses/` saved filters, `lockingTransaction`, the `list-endpoints.md` filter shape.
- **Lettuce** (`~/Sources/lettuce`): `infra/mail`, `infra/crypto`, `infra/paging` (through Flow); next: `MarkdownEditor`, sharing pickers.
- **Astrolabe** (`~/Sources/astrolabe`): `planning/DependencyGraph.kt` (`cycleClosedBy`) for the successor chain.

Left in Flow on purpose: `ingest/`, `fetch/`, `jira/`, `gitlab/`, `norm/`, `identities/`, `metrics/`, `reports/`, `integration/` (service tokens),
`infra/outbound/` (the SSRF guard — port it with the first server-initiated call other than SMTP), `plugins/Role.kt`, `EncryptedAtRest`/`Reencrypt`.

## Release history

### v0.1.0 — the foundation (2026-11-20)

Adder's bootstrap tree (Flow 1.57.1 trimmed) renamed to Hammurabi: ports 8090/5181/5440/8031, the `hammurabi` namespace, the lapis brand and
the Stele logo. Migrations V1–V9. No ADR feature yet.
