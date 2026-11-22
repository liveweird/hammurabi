# Product backlog

Updated 2026-11-21. This file tracks **outstanding work only**. Implemented behaviour and release history belong elsewhere:
- [README.md](README.md);
- the [application changelog](web/src/changelog/entries.ts);
- the topic guides under `.claude/docs/` (the roadmap: `.claude/docs/product.md`).

Entries are proposals, not delivery commitments.

**Status tags.** Every top-level item under a `##` section opens with one tag:
- `[next]`: what comes next;
- `[todo]`: confirmed, to do;
- `[new]`: proposed, not yet confirmed. That includes the decisions only the user can take. Claude adds items as
  `[new]`, and only the user promotes them;
- `[parked]`: deliberately set aside until a trigger;
- `[blocked]`: waiting on someone or something else.

The `backlog` mod (`liveweird/claude-mods`) summarises the tags on the status line and in `/backlog`.

## Foundation follow-ups

- [new] **External systems writing ADRs.** Port Flow's `integration/` service tokens when an external system should create or update ADRs.

## 1.0.0 checkup follow-ups

Source: `.claude/docs/audit-status.md` (the findings table has the evidence and the proposed fix of every id).

- [new] **Run the backup drill nightly in `e2e.yml`.** `node scripts/db-drill.mjs` (backup, restore, `--replace`, the forward migration) was run once at 1.2.0 and is recorded in `persistence.md` "Backup and restore"; a scheduled run would catch a migration that a dump cannot round-trip (a new schema or extension) before a restore does. Needs a built image in the job and the throwaway ports free (user decision D4, 2026-11-21).
- [new] **Charge the `adr-write` bucket by fan-out.** The bucket counts requests, not the people a request reaches: a participants PUT that swaps two disjoint sets removes 99 and adds 99, so one account at 120 requests a minute can still write about 24,000 bell notices and as many history rows a minute (email stays capped by the 1.2.0 budget). Weigh a request by its diff (Ktor's `requestWeight`) or add a per-actor notices-per-minute counter beside the budget row (security review of C-SEC-4, 2026-11-21).
- [new] **Tell the actor when a write went over the mail budget.** Over the daily budget the email is withheld silently: the actor does not learn that the people a write affected got a bell notice only. Surface it in the write's response or in the UI (security review of C-SEC-4, 2026-11-21).
- [new] **Checkup tier 2.** The findings that can follow 1.0.0: C-SEC-8 (hardening lows), C-SEC-9 (export slots, search shares, the successor chain walk); C-ARCH-3 (regenerate the AppCDS list with a workload that calls the ADR routes, and assert every main package has a listed class), C-ARCH-4 (direct-memory cap, `preStop`); C-GATE-5 (the Dependabot round; alerts are the owner's call), C-GATE-7 (re-measure `server/test-weights.tsv`); C-TEST-2 (a migration test on a populated database), C-TEST-3 (two concurrent sweeps), C-TEST-4, C-TEST-5 (e2e journeys); C-UI-3 (axe states), C-UI-4 (heading outline on the ADR pages); C-DRY-1 (one guarded-write block), C-DRY-2 (`catchingFailures` everywhere), C-DRY-3 (`ExpiringIndex`, `optionalBoundedString`), C-DRY-4 (`useAdrWrite`, one word module); C-API-4 (declare the 400s, type the four 409s); C-CONV-1 (publish the preference service).
- [new] **C-GATE-6: guardrail gaps.** The 400-line cap covers server main only; no feature-to-feature import rule beyond the no-cycle check of the domain packages (1.2.1); no `noUncheckedIndexedAccess`; no lock-drift check locally.

## 1.3.x review follow-ups

- [new] **Check the stored lenses' tag filters.** Since 1.2.2 a `tag` filter must be a valid tag name, but no stored lens was checked: a lens saved through the API earlier with, say, `#frontend` answers `400` when opened (release-noted in 1.3.1). A one-off read of `lenses.filters` for tag values that fail the grammar (and a fix-up or a note to their owners) would close it (review of 1.2.2–1.3.0, 2026-11-22).
- [new] **Measure My work on a large participation set.** `GET /adrs/my-work` runs per-ADR lateral lookups for every ADR the caller is on (terminal ones included in the history lookup) and is the landing page after sign-in; 1.3.1 caps it at 120 calls a minute per user. Time it with a user on a few thousand ADRs before deciding whether it needs an index or a narrower scan (`adr-model.md` "My work") (same review).
- [new] **Small server tidy-ups.** `successorId=0` answers `404` although the spec says `minimum: 1` (a `400`); `ReadSnapshotTest` only greps source text, so it cannot tell which transaction a string sits in; `Database.kt` re-checks `adrs.expiry.reminderDays` against the same bounds as `NotificationSweep` (one check, one place); a repeated and a malformed `X-Expected-Revision` share one message, so their tests cannot tell the two paths apart (same review).
- [new] **Fold `AccountForm.module.css` into the theme.** 1.3.1 moved the shared styling into `theme.module.css` and listed every remaining module in `web/CLAUDE.md`; the account forms' padding and hairlines could use the new `surface`/`ruleAbove` classes, but those pages were not in the before/after screenshot set, so it waits for a visual check (same review).
- [new] **Harden the successor-picker e2e step.** `adr-successor.spec.ts:57` flaked once in CI on the 1.3.1 release commit (run 45036976716, attempt 1; green on retry and on the re-run): the picker sits at the bottom of the viewport, its option list re-rendered while the search answered, and once Playwright scrolled the option into view the dropdown had detached. Scroll the Successor section into view and wait for the search response before picking (same review).
