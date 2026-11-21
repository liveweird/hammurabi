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

## ADR MVP (0.2–0.6)

- [todo] **ADR log / overview page.** A read-only decision-log page: active ADRs grouped by tag category, recently decided, expiring soon. (promoted by the user 2026-11-21)
- [new] **Tell a pending successor's people when its predecessor stops being ACTIVE.** Notify the successor's Driver and Approver when its predecessor is withdrawn, expires or is cancelled, and stop listing it as pending on the terminal predecessor.

## Foundation follow-ups

- [new] **Raise the coverage floors back.** The trim re-baselined them to lines 97 / branches 81 (Flow had 98 / 85 with its cut features' tests);
  raise them as the ADR features add tests.
- [new] **External systems writing ADRs.** Port Flow's `integration/` service tokens when an external system should create or update ADRs.
- [new] **GitHub remote.** Create `liveweird/hammurabi` and push; CI only runs once it exists.
