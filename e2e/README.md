# E2E (Playwright, blackbox)

Owns: how to run the suite against a named stack, the parallel state-ownership rulebook and the coverage map. Not here: each spec's actors, owned state and steps (`e2e/scenarios/<spec>.md`; the format is `e2e/scenarios/README.md`); the CI jobs (`ci.md`); gate times (`build-times.md`).

## Rules

- The tests treat the app as a **blackbox**: a real Chromium against the whole stack (SPA + server + Flyway + Postgres) served single-origin, **a stack you started and named** (see "Run"). The package is fully isolated from `web/`: it imports none of the app source and only speaks HTTP/DOM.
- **Playwright runs only against a stack somebody named.** The suite starts, builds, stops and tears down nothing, and never removes a service or a volume. Tests clean up only the records they own; existing data stays intact.
- **A new or behaviorally changed test lands with its scenario file and its line in "What's covered" in the same commit.** The scenario file is the design, the line is the coverage map. `npm run check:scenarios` enforces the parity mechanically (both directions, orphan files included); `accessibility.spec.ts` is its registered skip.
- **Every spec file owns its server-side state exclusively.** The serial unit is the spec file; different files run concurrently. Each spec's scenario file declares its **Owns** line ("nothing — read-only" when applicable); a new or edited spec satisfies the rulebook in "Parallel execution".
- Artifacts are unique-named, and list asserts are filter- or sort-anchored, never bare page-1 assumptions.
- Every e2e-created entity's name and every e2e-created user's email contains `e2e`, and nothing that must SURVIVE runs is ever named that way. Each spec deletes its own state, so the rule is satisfied by self-cleanup; add a `sweep-residue` global-setup pass if aborted runs start leaving residue that self-cleanup misses.
- **Seeded accounts are never mutated.** The seed admin (`admin@hammurabi.local`) is a shared read-mostly actor: specs sign in as it but must not change its password, roles, language or state; a spec that needs a mutated account creates a throwaway. Seeded accounts must stay English: every login applies the stored language to that session's UI, so a Polish seed admin would flip parallel specs mid-run. `mfa` never touches the seed admin's MFA flag (enabling it would make every spec's login demand a code).
- **The tag registry is shared, append-only state too** (a tag name is unique for good, retired ones included): the `tags` spec creates and deletes only its own uniquely named category and tags, and never edits the seeded Domain and Scope categories.
- **The Teams registry is shared, append-only state.** Several specs create teams concurrently (`teams`, `accessibility`, `list-mutation-refresh`), so a spec only ever appends and removes its OWN uniquely named `e2e-*` rows, never edits or deletes another's.
- A future spec minting globally-visible state (banners, org-wide notifications) runs in its own dependent project phase, not in the parallel pool.
- Specs log in with the seeded admin or a throwaway through `helpers.login()`; `helpers.loginViaForm()` only where the form, the credential or the language riding the login response IS the subject ("Logging in").

## Run

**Playwright runs only against a stack somebody named.** The suite writes rows through the API of the stack it is given (users, teams), so it never guesses a stack: the repository's default compose project (the app on host port 8090) is a working stack with your data, and nothing here falls back to it. Start a throwaway stack yourself (its own compose project name and host ports, torn down by you afterwards), then name its two addresses:

```bash
cd e2e
npm install
npm run install:browsers      # one-time: download Chromium
export E2E_BASE_URL=http://localhost:<app> E2E_MAILPIT_URL=http://localhost:<mailpit>
npm test                      # checks the named stack answers, runs the specs, leaves the stack intact
```

- Both variables are required, each an absolute `http(s)` URL (`target.ts` is the one reader of them; no other file under `e2e/` holds a host URL). With one missing, blank or malformed, `global-setup.ts` refuses to run before it makes a single request and names each variable at fault. When the app named by `E2E_BASE_URL` does not answer `/api/v1/ready`, it fails with "not ready: start that stack first"; when the Mailpit named by `E2E_MAILPIT_URL` does not answer `/api/v1/messages` (10 s timeout), it fails with "is not reachable" and names the variable: **Mailpit is REQUIRED**, so the two email specs never skip themselves and a stack without a catcher is a red run, not a silently green one. **The suite starts, builds, stops and tears down nothing** (no `docker` command lives in `stack.ts` or `global-setup.ts`), and it never removes a service or a volume. Tests clean up only the records they own; existing data stays intact.
- **A flaky test fails the run.** CI retries a failed test twice (`retries` in `playwright.config.ts`; 0 locally), and `failOnFlakyTests: true` makes the run exit non-zero when a test passed only on a retry, so a nightly that was green only thanks to a retry is red, uploads its report and opens the "Nightly e2e is failing" issue (`.claude/docs/ci.md` "Failure alerting"). Fix the race; do not raise the retries or turn the option off. `npm run test:setup` pins both the option and the absence of any `test.skip(` in a spec.
- The nightly `e2e.yml` builds the image and starts the stack in their own workflow steps (so the time is attributable per step, `.claude/docs/build-times.md`) and sets the two variables to that stack's published ports (8090 and 8031).
- `E2E_MAILPIT_URL` (the stack's Mailpit; the compose default is host port 8031) is the catcher the two email specs (`mfa.spec.ts`, `password-reset.spec.ts`) read; name the Mailpit of the same stack as `E2E_BASE_URL` (an isolated compose project has its own Mailpit port). Reading another stack's catcher makes those two specs fail.

Docker-free gates ride every spec change — run them before merging, like the web package's lint/knip:

```bash
npm run typecheck             # tsc --noEmit — Playwright only TRANSPILES TS, it never checks it
npm run check:scenarios       # spec ↔ scenario parity: files exist, test() titles == headings
npm run test:setup            # the refusal without a named stack, the readiness check, the source pins
```

`check:scenarios` enforces the same-commit rule mechanically (both directions, orphan files included); `accessibility.spec.ts` is its registered skip — the parameterized-title carve-out in [`scenarios/README.md`](scenarios/README.md).

Reports/artifacts land in `playwright-report/` and `test-results/` (git-ignored).

## Parallel execution

The suite runs on **4 workers by default** (`E2E_WORKERS` overrides; the `e2e.yml` `workflow_dispatch` form has a `workers` input for A/B runs; `E2E_WORKERS=1` restores fully-serial behavior). The serial unit is the **spec file** (`fullyParallel: false` — a file's tests may be order-dependent); different files run concurrently. That is only sound because **every spec file owns its server-side state exclusively** — the standing rulebook that any new or edited spec must satisfy:

- Each spec's scenario file declares its **Owns** line. Today: `auth`, `changelog` and `shell` (device-local localStorage only) are read-only; `accessibility` owns one API-seeded fixture team (`e2e-axe-team-*`) and one fixture ADR (`e2e-axe-adr-*`); `users` owns its throwaway accounts; `teams` owns its throwaway teams (unique `e2e-team-*` names) and users; `adr-lifecycle`, `adr-daci`, `adr-history`, `adr-discussion`, `adr-successor`, `adr-search` and `lenses` own their throwaway users (created through the admin API) and the ADRs those users raise (unique `e2e-adr-*` names, deleted through the API); `tags` owns its throwaway user, ADR and tag category (unique names, deleted through the API; it never touches the seeded Domain and Scope categories); `i18n` owns its throwaway Polish-language account (never the seed admin's language); `password-reset` owns its throwaway account (its reset requests use unique per-run emails against the in-memory per-email throttle, and both tests together stay under the per-IP 5/min request bucket and the 10/min confirm bucket); `mfa` owns its throwaway accounts and toggles ONLY their MFA flags; `list-mutation-refresh` owns its own uniquely named users/teams, created and deleted through the real API; `telemetry` owns one throwaway user (the panel's figures are read-only).
- **Locks.** No spec holds a Playwright lock today. A spec that owns state the others read, or edits global settings, takes one (`test.describe.configure({ lock })`) rather than running at another time; the lock is held by the spec's whole run, and this section lists it.
- **Rate buckets.** Development stacks lift the per-IP login bucket (`security.rateLimit.loginPerMinute`, 1000/min in development vs 10/min in production), so the ~100 sign-ins of a run are not throttled; the two telemetry reads share one per-user bucket of 30 a minute (`telemetry.spec.ts` polls within it).

## What's covered

One line a spec: what it proves. The design (actors, owned state, numbered steps) is the spec's scenario file under [`scenarios/`](scenarios/README.md).

- [`accessibility.spec.ts`](scenarios/accessibility.md) — axe WCAG A/AA smoke (`color-contrast` included): the login screen and the authenticated list/form pages (`/`, `/adrs`, `/adrs/new`, `/teams`, `/tags`, `/users`, `/users/new`, `/feature-flags`, `/change-password`, `/changelog`), the detail pages of an API-seeded fixture team and ADR (the team page, the ADR page, the admin's own edit-user and user-features pages), `/reset-password` and its confirm page, the not-found page and a registry editor modal scoped to its dialog; the app shell at a mobile width (390x844, the burger present); the page sets and the login screen again in the DARK scheme (`colorScheme: "dark"`, which the `auto` default follows).
- [`auth.spec.ts`](scenarios/auth.md) — login / logout / invalid credentials / guarded deep link with query and hash; explicit sign-out from a non-home protected page returns the next sign-in to Home, including while server revocation is delayed.
- [`shell.spec.ts`](scenarios/shell.md) — the app shell chrome: the icon rail collapses/expands the navbar and remembers the choice across reload; the account menu's theme control switches the color scheme and persists it.
- [`telemetry.spec.ts`](scenarios/telemetry.md) — the ADMIN telemetry panel (Hammurabi's own vital signs): after the sampler's first sample (awaited through the API) the admin opens it from the account menu and reads Memory (the compose app's 512 MB container limit and the share of it) and Storage (database size); the open panel and its expanded sections survive a reload and close with their own button; a regular user has no menu item and a hand-stored open flag shows nothing; an axe scan of the panel with every section open.
- [`changelog.spec.ts`](scenarios/changelog.md) — the what's-new dot on a fresh device leads to the changelog via the version stamp and clears once read (no language switching — it runs as the seed admin; see `i18n.spec.ts`).
- [`i18n.spec.ts`](scenarios/i18n.md) — the synced per-user language: a throwaway user switches to Polish, the choice survives a reload and a wiped-device re-login, and the admin's English flips it back.
- [`mfa.spec.ts`](scenarios/mfa.md) — the `/feature-flags` row switch and the per-user editor round-trip a throwaway user's MFA flag; an MFA-enabled account signs in with the revealed generated password and the emailed 6-digit code via Mailpit (Mailpit is required by global setup).
- [`password-reset.spec.ts`](scenarios/password-reset.md) — the forgot-password flow: neutral confirmation and per-email throttle; the emailed single-use link keeps the old password until the new one is set, then ends the old session and works once (Mailpit is required by global setup).
- [`users.spec.ts`](scenarios/users.md) — the account lifecycle: create via the one-time password reveal, the new user's limited view and self password change, promotion, deletion, the dead login; own-row protections; an admin's demotion and deletion reach a signed-in user on their next request.
- [`tags.spec.ts`](scenarios/tags.md) — the tag registry and the ADR tags: an admin creates a category with two tags (folded to lower case), a Driver tags an ADR from the grouped picker (the History says so), the list's tag filter finds it (in the URL, with the pill in the row), the API refuses an unregistered tag with a 400; a regular user reads the registry through the API but has no Tags link or page and no writes.
- [`adr-successor.spec.ts`](scenarios/adr-successor.md) — successor links: a Driver links a draft to an ACTIVE ADR from the picker, the Approver activates it and the predecessor reads SUPERSEDED with the banner, the history lines and the way back; a link that would close a loop is refused in words; the predecessor's Driver detaches a successor a stranger parked on it, and both histories say so.
- [`adr-search.spec.ts`](scenarios/adr-search.md) — Find: the list's full-text search finds an ADR by a word in its decision, folding accents ("zolwik" finds "Żółwik") and marking the hit in the snippet; the search combines with the Expires-before, status and created-range filters, and the URL carries the whole view, so a reload and a fresh visit of the link show the same rows.
- [`lenses.spec.ts`](scenarios/lenses.md) — saved filters: a user saves the current filters as a lens, applies it from a clean list, renames and shares it, then deletes it; another user is offered a PUBLIC lens (with its creator) but not a PRIVATE one, and can neither rename nor delete it, in the menu and at the API.
- [`teams.spec.ts`](scenarios/teams.md) — the flat-teams registry: create, rename, assign a manager, delete; a regular user's read-only list and team page.
- [`adr-lifecycle.spec.ts`](scenarios/adr-lifecycle.md) — the ADR lifecycle in the browser: a Driver raises an ADR with an Approver, drafts it (too early for review is refused in words), writes the content inline and sends it to review; the Approver cannot edit but activates it, after which the content is frozen; a Driver cancels one after a confirmation; the Approver withdraws one in review.
- [`adr-daci.spec.ts`](scenarios/adr-daci.md) — what each DACI role sees: a user with no role reads an ADR but is offered no controls; a Contributor edits the content but has no moves, no participant editor and no expiry editor.
- [`adr-history.spec.ts`](scenarios/adr-history.md) — the ADR history: edits, participant changes and moves listed newest first as localized sentences with who and when, ten events to a page.
- [`adr-discussion.spec.ts`](scenarios/adr-discussion.md) — the ADR discussion: a comment and a question posted on a draft and the question answered by the Driver; the composer gone (and the API's typed 409) once the ADR is active; an issue raised on an active ADR, counted on the list and resolved by the Approver with a note.
- [`list-mutation-refresh.spec.ts`](scenarios/list-mutation-refresh.md) — a held first filter-response for Users or Teams cannot restore a deleted row; a held Feature Flags response cannot undo a successful toggle.

### Logging in

`createUserViaUi()` waits for the one-time password reveal to render before reading the password. A click on Show password alone is not evidence that the mask has been replaced. Every journey that creates a user through this shared helper inherits that synchronization.

`helpers.login()` signs in **without the form** (Flow's build-times.md WHY 37): the real `POST /api/v1/login` through the page's request context, the session written to `localStorage` under the `hammurabi.auth.*` keys the SPA itself persists (`storeSession` in `web/src/api/session.ts`), then one load of `/` — the tokens, the revocation blocklist and any per-request credential check are the server's own, nothing is forged or reused across tests (`signOut` revokes the pair, so a session is minted per call). It takes the seed admin by default or any account's credentials (a throwaway regular user reads the app as one), and falls back to the real form when the login is refused, answers a challenge (MFA) or the shell does not accept the session. `helpers.loginViaForm()` is the real form (clearing any leftover `hammurabi.auth.*` localStorage session first — while one exists, `RedirectIfAuthed` bounces `/login` away and the form never renders): use it where the credential, the form or the language riding the login response IS the subject — `users` (the first sign-in with the revealed password), `password-reset` (the new password), `i18n` (the Polish throwaway and the English admin after it), `auth` and `mfa` (which drive the form inline). Development stacks lift the per-IP login bucket (`security.rateLimit.loginPerMinute`, 1000/min in development vs 10/min in production), so neither path is throttled.

## Deliberately not covered

- **Login lockout (429)** — five failed logins would lock the seed admin for 15 minutes in the shared database and poison the rest of the run. Covered by `LoginLockoutTest` (server).
- **Token refresh / expiry (clock-driven)** — real expiry needs clock control; covered by server tests and the `web/src/api/api.test.ts` unit tests.
- **The authz matrix** — covered by the server tests (`GuardsTest`, `AnonymousAccessTest`); E2E asserts only user-visible consequences.
- **Dark-mode rendering** — the palette is theme-owned (`web/src/theme.ts`); `shell.spec.ts` asserts the color-scheme attribute switches and persists, and `accessibility.spec.ts` scans the page sets in the dark scheme (contrast), but no e2e asserts rendered colors, and there is no visual-regression suite.
- **Responsive / cross-browser / visual automation** — the suite deliberately runs a single **Desktop Chrome (chromium) project** only, with no mobile project or screenshot comparison; layout relies on Mantine semantics plus the role/label-based locators every spec uses. Accessibility gets the `accessibility.spec.ts` axe smoke.
