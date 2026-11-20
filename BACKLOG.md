# Product backlog

Updated 2026-11-20. This file tracks **outstanding work only**. Implemented behaviour and release history belong elsewhere:
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

## ADR MVP (0.2–0.5)

- [new] **0.4.0 Organise and find.** Tag categories, successor links + `SUPERSEDED`, multi-criteria filters, full-text search, URL-synced filters, saved lenses.
- [new] **0.5.0 Notify.** The bell, email digest and preferences for DACI participants; expiry reminders.
- [new] **Markdown export of an ADR.** Render an ADR in the adr-tools format.
- [new] **"Send back" and withdraw an active ADR.** `IN_REVIEW` → `SOLUTION_DRAFT` and `ACTIVE` → `WITHDRAWN` (`product.md` "Not built").

## Foundation follow-ups

- [new] **Raise the coverage floors back.** The trim re-baselined them to lines 97 / branches 81 (Flow had 98 / 85 with its cut features' tests);
  raise them as the ADR features add tests.
- [new] **External systems writing ADRs.** Port Flow's `integration/` service tokens when an external system should create or update ADRs.
- [new] **GitHub remote.** Create `liveweird/hammurabi` and push; CI only runs once it exists.
