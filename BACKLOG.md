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
- [new] **C-GATE-6: guardrail gaps.** The 400-line cap covers server main only; no feature-to-feature import rule; no `noUncheckedIndexedAccess`; no lock-drift check locally.
- [new] **C-TEST-6: brittle test assertions.** Assertions on globally capped sections, wall-clock bounds, twelve fixed-delay e2e checks, a 2031 date, the scenario check skipping the axe spec, no machine-readable web coverage, no console-error guard.
- [new] **C-UI-6: UI consistency leftovers.** Three bare paginations, four hand-rolled empty lines, a red unread cue, an English-text match on a server detail, a swallowed identity error, one untranslated label.
- [new] **C-DRY-5: small duplications.** A `users` and `auth` import cycle, status words in two files, ZIP streaming in a routes file, a non-reactive `isAdmin()`, repeated e2e helpers.
- [new] **C-SMELL-2: donor leftovers.** Comments citing files that do not exist here, 35 "WHY n" citations into Flow's file, stale ignore entries, package versions 0.0.0 and 1.0.0.
- [new] **C-ARCH-5: list the per-process state behind "one replica".** One list in `security.md` (export slots, search permits, refresh rotations, the rate buckets).
