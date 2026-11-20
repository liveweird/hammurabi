# Audit status

No checkup has run on Hammurabi yet. The first one is the **1.0.0 checkup** (`product.md` "Roadmap"); it starts this file.

Flow's three checkups (2026-09-30, 2026-10-06, 2026-10-23) audited the donor code this repository was trimmed from; their findings and fixes are Flow's record
(`~/Sources/flow/.claude/docs/audit-status.md`) and were not carried over, because most of what they covered (the data-pipeline and reporting features) is not here.

## Method (when it runs)

One `checkup-lead` plans the run and adjudicates it: one `auditor` (and a `security-auditor` for authn/authz) per dimension — docs and specs, DRY/SRP/SOLID, unnecessary
complexity, conventions, tests, UI consistency, authn/authz, guardrails, architecture and infrastructure, API spec, smells and dead code — in parallel, after a `gate-runner`
baseline. Every high/medium claim is re-read against the code before it is kept. Each finding keeps an id, a severity, `path:line` evidence and a proposed fix; the findings become
`BACKLOG.md` items, and the fixing PRs are listed here under "Fixed so far".

## Fixed so far

Nothing yet.
