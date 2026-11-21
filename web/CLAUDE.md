# Frontend (`web/`)

Vite + React 19 + TypeScript SPA: the shell + auth, user/feature management, MFA, password reset,
the changelog, the flat-teams registry and the ADMIN telemetry panel — the foundation, trimmed from Flow
(`~/Sources/flow` @ 4e43e480, 1.57.1, the donor: port building blocks from there, don't reinvent; Astrolabe's trim of the same
shell is the precedent) — plus the ADR pages (the list, create and detail pages, the discussion, find, the notifications, the decision log: "The ADR pages" below) behind a Home that welcomes
and leads into them. Routes are lazy.

- Dev server: `cd web && npm run dev` (port **5181** — not Vite's 5173 default, so Hammurabi can run
  beside the sibling stacks' 5173–5178 and 5180; the port table is in `.claude/docs/ci.md`). All backend routes live under the
  `/api/` namespace and Vite proxies the single `/api` subtree → `http://localhost:8090`. Any
  other path is served as `index.html` so React Router owns the SPA URL space and browser reloads
  don't collide with API routes.
- Production build: `cd web && npm run build` → static files in `web/dist`. In the Docker image
  these are baked in and served by the Ktor server itself (via `WEB_STATIC_DIR`; see
  `plugins/Routing.kt`), so production is single-origin and there is no Vite proxy — the SPA and
  `/api` share `http://localhost:8090`.
- Regenerate API types: `cd web && npm run gen:api`. Reads
  `server/src/main/resources/openapi/documentation.yaml` directly (no server needed) and writes
  `web/src/api/schema.ts`. Run this after editing the OpenAPI spec; commit the regenerated
  `schema.ts` in the same change.
- **Quality gates (the frontend counterpart of the backend's detekt)**: `npm run lint` carries
  `eslint-plugin-sonarjs` (recommended) plus core size/complexity backstops — zero-findings gate;
  rule tuning lives in `eslint.config.js` ONLY, one commented override per deliberate idiom, and
  any inline `eslint-disable` needs a justifying comment. `npm run lint` runs with `--cache --cache-strategy content`
  (`web/.eslintcache`, gitignored; a warm run is ~0.6 s against ~12 s, CI restores the file between runs); the config is not type-aware, so a per-file cache is sound, and a config change or an
  ESLint/node upgrade re-lints everything on its own. `npm run knip` is the dead-code gate
  (unused files/exports/dependencies; `knip.json` ignores the generated `schema.ts` and the
  `ajv` devDependency pinned only for `@stoplight/spectral-cli`'s resolution) — keep exports that
  only the declaring file uses un-exported, and delete what knip flags rather than ignoring it.
  **The API-contract gate**: `npm run lint:api` Spectral-lints the OpenAPI spec and
  `api-guidelines/examples/conformant.yaml` with `--fail-severity=warn`, through Hammurabi's local ruleset
  `web/spectral.hammurabi.yaml` (it `extends` the shared `api-guidelines/api-guidelines.spectral.yaml`, which is the other
  team's area and never edited; it switches off only the two registered-gap rules `x-sla`/`termsOfService`, each with
  its reason). A finding is fixed in the spec; a new local `off` needs an inapplicability comment, never a fixable finding;
  `npm run check:api` (`scripts/check-api.mjs`) regenerates the API types in memory and diffs
  them against the committed `src/api/schema.ts` — it must never overwrite that file, so
  `gen:api` stays the one command developers run to update it.
- **Build version stamp**: `vite.config.ts` injects `__APP_COMMIT__` (short sha, `+dirty` when the
  worktree has uncommitted changes) and `__APP_COMMIT_TIME__` (commit ISO timestamp) via `define`,
  declared in `src/vite-env.d.ts`. Env vars `GIT_SHA`/`GIT_COMMIT_TIME` override the local-git
  lookup — the Dockerfile's SPA stage sets them explicitly (its worktree never matches the index,
  so the dirty check would false-positive), and CI can do the same when building without `.git`.
  `src/components/VersionStamp.tsx` renders `v<APP_VERSION> · <sha> · <time>` at the bottom of the
  navbar (`App.tsx`) and under the login card (`components/AuthCard.tsx`).

## Layout conventions

- **Flat directories**: `pages/`, `components/`, `hooks/`, `utils/`, `api/`, `changelog/`,
  `locales/{en,pl}/`, `test/` — no deeper nesting, no per-feature folders (a feature contributes
  files into these).
- **Default exports for components/pages**, named exports for everything else; **no path
  aliases** — relative imports only.
- **Co-located tests**: `Foo.test.tsx` sits beside `Foo.tsx`; shared test scaffolding lives in
  `src/test/` (`setup.ts` forces `en`, `render.tsx` is the provider wrapper — it and every
  file-local `MantineProvider` must pass `env="test"`, or Select/Popover interaction silently
  fails under happy-dom; `http.ts` holds the fetch stubs).
- **CSS modules only where the theme can't express it**: styling belongs in `src/theme.ts`
  (Mantine `createTheme` — component `extend`s, defaultProps) with `src/theme.module.css` for the
  class-level parts; a per-component `*.module.css` is the exception, not the pattern.
- Pages are **lazy** (`React.lazy` in `App.tsx`); new routes register above the `path="*"`
  NotFound catch-all (LAST child, never feature-gated). **The nav model lives in
  `utils/navigation.ts`**, shared by the sidebar and the command palette: `NAV_SECTIONS` —
  labelled, always-open sections (currently Overview: Home, Decision log, ADRs; and Administration: Teams, Users, Feature flags) of `NavLeaf`s (the
  `label` is a typed i18n key; `adminOnly?` gates admin leaves, `keywords?` adds raw palette search terms for a renamed page's old name, and `visibleSections(isAdmin())`
  drops an emptied section) — plus `ACCOUNT_NAV` (Change password, Notification preferences, Changelog), which the header
  `UserMenu` and the palette render and the sidebar never does. Sections are static captions
  (`role="group"` + `aria-label`), never toggles: every leaf is always in the DOM, so tests and
  e2e address the links directly; leaf clicks close the mobile drawer; `activeNavPath` is the
  longest-prefix matcher over the leaves. **The icon rail (Lettuce's):** a header `ActionIcon`
  beside the Burger (`appShell.toggleNav`, `data-expanded` while expanded) narrows the desktop
  navbar from 240 to 64px — `rail = navCollapsed && !opened`, remembered under
  `hammurabi.viewSettings.appShell.navCollapsed`; in rail mode each leaf is an icon-only `NavLink`
  (`classes.railLink`) whose `aria-label` IS the label (so every by-name locator keeps working)
  inside a right-hand `Tooltip`, section captions become hairline `Divider`s. The mobile overlay
  always shows full labels.
- **The shell header** is 48px: brand, the `CommandPalette` trigger, the notifications bell (`NotificationsButton`, "Notifications (0.6.0)" below), and the `UserMenu` (avatar →
  identity from `GET /users/{id}` under the `["user", id]` key, language, theme as a three-state
  SegmentedControl, Change password, Notification preferences, Changelog with the what's-new badge, for an ADMIN a "Telemetry panel" toggle, Sign out). The version
  stamp stays in the sidebar footer. **`components/CommandPalette.tsx`** wraps
  `@mantine/spotlight` (pinned to the core version — keep every `@mantine/*` on one version) over
  a module-level store (`utils/commandPalette.ts`): the session's pages, plus the "Raise an ADR" action (everyone) and an ADMIN-only show/hide action for the telemetry panel. No
  `highlightQuery` — it splits labels into `<mark>` fragments that role/name locators cannot
  match.
- **Page chrome**: every page is a `Stack gap="md"` on the canvas starting with
  `components/PageHeader.tsx` (`title` → the `order={2}` heading tests locate, `description?`,
  `backTo?`, right-aligned `actions?` — the ONE place a page's primary "New …" button lives — and
  a `toolbar?` row). `PageHeader` also titles the browser tab (`hooks/useDocumentTitle.ts`: "{title} · Hammurabi", `documentTitle?` when the tab needs more than the heading, e.g. an ADR's key; `AuthCard` does the same for the sign-in pages), so a new page gets its tab title from its header and never calls the hook itself.
  No `Container` and no page-wrapping `Paper`; widths come from
  `utils/layout.ts`: full-bleed lists, `Box maw={CONTENT_MAX_WIDTH}` for registry tables and the
  changelog, `Paper withBorder p="xl" maw={FORM_MAX_WIDTH}` (left-aligned) for the simple field
  forms. The one spinner is `components/LoadingBlock.tsx` (`TableLoadingRow` wraps it);
  `components/EditPageLoadState.tsx` is the edit pages' shared load-failure triage (centered
  loader, else a back-to-list alert); `EmptyState` takes the Tabler icon COMPONENT and sizes it
  itself (an optional `hint` is the next step under the label; `compact` is the same body for a card, a side panel or a drawer: the decision log's sections, the history, a discussion thread) and a sub-collection's pager is `components/CompactPagination.tsx` (icon-only controls named, nothing for one page) while a list's footer is `PaginationBar`. **One heading outline everywhere**: the `PageHeader` title is the h2, a page's cards/sections are h3
  (`<Title order={3} size="h4">` keeps the look), their inner blocks h4; and every `Table` has an accessible
  name (`aria-label` — the words of the heading above it, or a `<area>.tableAria` key). Page tests pin the
  outline with `test/headings.ts`'s `headingOutline()`.

## The typed API layer (`src/api/`)

The OpenAPI spec at `server/src/main/resources/openapi/documentation.yaml` is the contract between
backend and frontend — hand-maintained, not auto-generated from routes. Swagger UI is at
`http://localhost:8090/openapi` (dev). The layer is small hand-written modules:

- **`http.ts`** (transport): `authedFetch()` with the single-flighted silent refresh on a 401. A DEFINITIVE rejection signs the session out, a TRANSIENT failure keeps it — **never collapse the two**; stale requests and refresh completions must not publish credentials, clear a newer login or retry across session identities. `ApiError` (read RFC 7807 fields via the `detail`/`instance`/`type` getters, never hand-cast `err.body`); `jsonRequest<T>()`/`voidRequest()` for every ordinary endpoint wrapper; `buildQuery()` for list wrappers (`false` and `0` ARE sent). Every transport fetch carries `timeoutSignal()` — new `fetch` call sites in `api/` must attach it. Refresh/wrapper internals: `.claude/docs/web-internals.md`.
- **`session.ts`**: token/roles/userId under the `hammurabi.auth.*` localStorage keys (`persistSession`, `persistRefreshedSession`, the `isAdmin()`/`getUserId()` accessors); ADMIN-dependent components use `useAdmin()` from `auth.tsx`. Details: `.claude/docs/web-internals.md`.
- **Session boundary** (`components/SessionBoundary.tsx` + `utils/sessionQueryCache.ts`): binds the root `QueryClient` to logical session identity — a different sign-in, sign-out or cross-tab change must synchronously remove caller-scoped query data before the next account renders, a token rotation within one identity must retain it, and late requests from the previous identity must not repopulate the new account's UI or cache. Keep this at the session boundary rather than clearing individual page keys. Mechanics: `.claude/docs/web-internals.md`.
- **`auth.ts`**: login + the MFA exchange, self-service password reset, and logout (`logout()` is
  best-effort — local credentials are cleared immediately, before awaiting the revoke POST for the
  captured token pair; a delayed completion never clears a later login, and the function never
  throws).
- The types come from the generated `schema.ts`; a new endpoint's wrapper goes into its feature's
  module (a new feature area gets a new module — never a catch-all file). Avoid heavyweight client
  generators (Orval/Kiota) — the lightweight pairing of `openapi-typescript` (types only) +
  hand-written fetch is intentional.

Explicit sign-out opens `/login` without a saved return destination (a self password change signs out the same way, with its own notice: `flagSignedOut("passwordChanged")`); anonymous access to a protected link preserves its path. Reading the signed-out notice during render must be side-effect free (acknowledge it after the login page commits). Test logout navigation with the real route guards from a non-home page. Details: `.claude/docs/web-internals.md`.

`openapi-typescript` is installed with `--legacy-peer-deps` because its declared peer is TS `^5`
while the scaffold uses TS 6; the generated output is compatible. If you re-`npm install` from
scratch, use `npm install --legacy-peer-deps`.

## Error handling

- **ErrorBoundary** (`components/ErrorBoundary.tsx`): a page render crash must never white-screen
  the app. `RouteErrorBoundary` wraps the `<Outlet />` inside `AppShell.Main` (header/nav survive;
  keyed by `location.pathname`, so navigating anywhere recovers), and a plain `ErrorBoundary` in
  `main.tsx` is the last resort for shell crashes. Don't add per-page boundaries — the two mounts
  are the model.
- **Catch-all 404**: `pages/NotFound.tsx` is the LAST `path="*"` child of the Shell route. New
  routes go above it.
- **Chunk-load recovery**: `main.tsx` listens for `vite:preloadError` (a redeploy 404s the old
  hashed chunks) and reloads once, rate-limited via sessionStorage (`hammurabi.chunkReloadedAt`, max
  one reload/minute) so a genuinely missing chunk falls through to the ErrorBoundary instead of
  looping.
- **Query retry policy**: the `QueryClient` in `main.tsx` uses `shouldRetryQuery` (`api/http.ts`)
  — NEVER retry a 4xx (the answer won't change; retrying only delays the error UI), at most two
  retries for transient failures.
- **Messages**: never render `error.message` (it's the internal `API <status>` / the browser's
  "Failed to fetch") — map statuses to i18n keys via the shared mappers in `utils/saveError.ts`:
  `saveErrorMessage(err, t, keys)` for mutations (per-status keys + a `failed` fallback) and
  `loadErrorMessage(err, t)` for list loads. Errors render inline as `color="red" variant="light"`
  Alerts — never as toasts. A failed QUERY renders through `components/ErrorAlert.tsx`
  (`error`, optional `title`), never an inline `<Alert>{loadErrorMessage(…)}</Alert>`; a form/action failure
  (an already-mapped string) renders through `components/FormErrorAlert.tsx`, never a hand-written red `<Alert>`.
- The `@mantine/notifications` host is mounted in `main.tsx` (top-center, autoClose 2500, limit 3
  — deliberately not in App, so unit tests never mount it). **Success toasts only, with fixed
  vocabulary only** (`showSuccessToast(t("<area>.toast.*"))` in `utils/toast.tsx` — teal, never
  user-entered values; errors stay inline).

## Shared list-page building blocks (the Users.tsx template)

Every list page composes the same ported Lettuce blocks — copy `pages/Users.tsx`, don't re-derive:

- **State**: filters in `useStoredState` (persisted under
  `hammurabi.viewSettings.<viewKey>.filter.*`, text filters debounced 300 ms — the DEBOUNCED value
  goes into the query key, and any action reading the filters reads the DEBOUNCED value too, so a
  click never operates on a slice the table isn't showing), sort/page/pageSize from
  `usePagedSort(initialSort, filterDeps, { key, sortFields })` with `SORT_FIELDS ... as const`.
  Every query derived from one area keys under that area's prefix (`["users", …]`, `["teams", …]`),
  so refreshing an area prefix after a mutation refreshes all its active queries and invalidates
  inactive cached data.
- **Query**: `useQuery({ queryKey: ["<area>", page, pageSize, sortParam, ...filters], queryFn: list<Area>(...), placeholderData: keepPreviousData })`.
- **Chrome**: `FilterPanel` (collapsed by default, persisted, active-count badge) +
  `ClearableTextInput` for freetext filters (debounced); `SortHeader` renders its OWN `Table.Th`
  (so the sort state lands as `aria-sort` on the header cell — don't wrap it in another Th);
  `TableLoadingRow` (`isLoading && !data`) / rows / `EmptyState` (`!isError`) triage in the tbody;
  a `color="red" variant="light"` Alert with `loadErrorMessage` ABOVE the table on error;
  `PaginationBar` below.
- **The colour vocabulary is app-wide, not per-page**: the lapis `hammurabi` brand = the interactive accent only (primary CTA,
  active nav item, focus); red = hard/blocking (validation errors, destructive confirm buttons, a failed row, a violated
  constraint); orange = warning / waived (a soft finding, drift); gray = neutral state; cyan = in progress; grape = waiting;
  teal = success (success toasts and notices) — never stock blue, indigo or violet for a status, which sit beside the lapis. A new status colour goes through that vocabulary, not a page-local pick. The lapis has one deliberate use beyond CTAs, nav and focus: the lifecycle diagram's two interactive meanings - the heavy outline of where this ADR is, and the heavier arrows of the moves the caller may make now ("The ADR pages").
- **Delete**: `useDeleteConfirm` + `ConfirmDeleteModal` — the hook owns modal state and the
  success toast, the page owns cache refresh. For mutations while a list remains mounted, use
  `refreshQueriesAfterMutation(queryClient, ["area"], ...)` from `utils/queryRefresh.ts` after
  success. It cancels all affected query groups before invalidating them: a newly keyed initial
  fetch has no cached data even when `keepPreviousData` displays rows, so default invalidation
  alone may reuse a pre-mutation response. Keep refresh out of failed mutation paths.
  **`ConfirmActionModal`** is the non-destructive sibling (Lettuce's, ported with the
  feature-flags bulk actions) — a message plus a neutral cancel and a confirm button whose
  `loading` blocks cancel/close while the action runs; labels arrive already translated.
- **An entity's NAME is the way into its detail page** — a `RouterLink` `Anchor` with an
  interpolated accessible name (`common.action.editAria` = "Edit {{name}}", or an area-specific
  `openAria`), which is what tests and e2e locate. A real link, not an onClick: cmd/middle-click
  opens a tab and it takes keyboard focus. Route families are spelled out ONCE in
  `utils/<area>Links.ts` — never hand-assemble a URL.
- Row action buttons carry interpolated aria-labels (`<area>.editAria` etc.) — unit tests and e2e
  locate by them; table tests query cells by **text**, not `cell` role names. **Row actions**: a
  row with more than two actions bundles them under `components/RowActionsMenu.tsx` — an
  icon-only kebab whose accessible name is `common.table.operationsAria` ("Operations for
  {{name}}"; items are plain `menuitem`s, only one row's menu is open at a time); a per-row
  `Switch` (Feature flags) is state, not an action.

## The ADR pages (0.2.0, discussion 0.3.0)

`pages/Adrs.tsx` (`/adrs`, the primary nav entry), `pages/CreateAdr.tsx` (`/adrs/new`) and `pages/AdrDetails.tsx` (`/adrs/:id`); routes in `utils/adrLinks.ts`, wrappers in `api/adrs.ts`, copy in `locales/*/adrs.json`.

- **The decision log (0.9.0).** `pages/DecisionLog.tsx` (`/decision-log`, Overview between Home and ADRs; the Home page's "Open the decision log" button) is the read-only overview: ONE query `["adrs", "overview"]` (so any write that refreshes the `["adrs"]` prefix refreshes it) from `getAdrOverview()`, laid out as the count strip (`DecisionLogCounts`: each status's `AdrStatusBadge` and count, a link to the list in that status), three `DecisionLogCard`s (Astrolabe's `HomeCard` layout: Paper, icon, h3, one dimmed sentence) - Awaiting decision (the caller's rows `mine` tinted grape, badged "Needs your decision", `mineTotal` linked to `?status=IN_REVIEW&mine=1&participantRole=APPROVER`), Expiring soon (the last day, a red "Overdue" badge for a past date, "+N more" to `status=ACTIVE&expiresBefore=<today+days+1>`, "today" being the response's `generatedAt` day, not the browser clock), Recently decided - and `DecisionLogInForce` (category h4, tag h5, `LogList` of `AdrLogRow`s, "+N more" to `status=ACTIVE&tag=<name>`; "Untagged", whose link says "All active ADRs" because the list has no untagged filter; the hidden-tags sentence). A "+N more" link's visible text is "+N more" and a `VisuallyHidden` suffix says more of what (WCAG 2.5.3: never an `aria-label` that replaces the visible words). Lists are real `<ul>`s named for assistive technology; each row is ONE link (`key name`); empty sections say so through the shared compact `EmptyState`. Links to the list are built by `adrListPath(patch)` (`utils/adrListParams.ts`), never by hand. Copy: `locales/*/decisionLog.json`; the PL title is "Rejestr decyzji".

- **Export (0.8.0).** `components/AdrExportButton.tsx` (a `data-print="hide"` action; the failure is an inline red `role="alert"` line, `utils/adrExport.ts`) sits in both pages' `PageHeader` actions: **Export Markdown** on the detail page (everyone who can read the ADR) calls `exportAdr(id)`, **Export (ZIP)** on the list calls `exportAdrs(filters)` with the SAME `filters` object the list query uses (no paging or sort; disabled while a date range is inverted; the server's `400` over 500 matches reads "narrow the filters"). The download goes through `api/download.ts` (`downloadRequest`: `authedFetch`, so the bearer token and the silent refresh apply, the body as a Blob, the file name from `Content-Disposition` accepted only when it is a plain slug) and `utils/saveFile.ts` (an object URL on a throwaway `<a download>`); page tests mock `../utils/saveFile`.

- **The list** is the Users/Teams table (`RegistryListTable`, `usePagedSort` with a descending initial direction: newest first) with its FILTERS in the URL
  (`hooks/useAdrListParams.ts` over `utils/adrListParams.ts`), written by `replace`; page, page size and sort stay `usePagedSort`'s.
- **Find (0.5.0).** The URL keys ARE the list endpoint's parameter names: `q` (name or key), `text` (full text), repeated `status`/`tag`, `createdFrom`/`createdTo`/`updatedFrom`/`updatedTo`/`expiresBefore` (`YYYY-MM-DD`, native `<input type=date>`), `hasOpenIssues`/`hasPredecessor`/`hasSuccessor` (`true`|`false`, absent = any), `participantRole`, and `mine=1` (the signed-in user's own `participantId`; a bare `participantId=<id>` only arrives with another user's shared lens and shows as a removable chip). **1.0.0 renamed four keys** (`participant`, `role`, `supersedes`, `supersededBy` -> `participantId`, `participantRole`, `hasPredecessor`, `hasSuccessor`): the old ones are still READ (a 0.9.x bookmark filters; `LEGACY_KEYS` in `utils/adrListParams.ts`, the current spelling wins), never written, and the first write of the filters drops them. Any filter change resets the page. The **Search** box sits above the Filters panel (outside the badge count): it follows every keystroke, commits the trimmed term to `text` 300 ms later and only when empty or 2+ characters (a lone character shows a hint; a hand-edited one is read as no search). While `text` is set the request carries no sort until a column is picked (the server ranks by relevance; the headers read unsorted and a hint says so), and the page size is capped at 20 in the request and the size control (`SEARCH_MAX_PAGE_SIZE`). An inverted date range is flagged on the field and not requested. A row with `match` renders the name and, under it, the snippet through `AdrMatchText`: the server's plain-text segments as React text, hits in Mantine's yellow `Mark` (never `dangerouslySetInnerHTML`); the link's `aria-label` stays the plain name. The typed 429s (`search-timeout`, `search-busy`) are `loadErrorMessage` sentences ("narrow it" / "try again"), inline like any load failure.
- **Lenses (0.5.0).** `components/LensPicker.tsx` (Toadie's, ported) sits in the Filters panel header: a grouped combo of "My lenses" (`mine=true`) and "Shared lenses" (`mine=false`, creator named) over `hooks/useLenses.ts` (`["lenses", "mine"|"shared"]`, page size 100), plus a **Lens actions** menu. A lens is the `AdrFilterDocument` (the list's parameter names; `utils/lensFilters.ts` maps it to and from the URL filters): applying REPLACES every filter (what the lens lacks is cleared; sort and page size are not part of a lens; its `participantId` equal to the signed-in user is `mine`), saving stores the current URL filters, and the lens the filters equal is highlighted (a picked one they have left reads "Modified"). Save-as, rename/visibility (`LensEditorModal`, one whole-lens PUT that keeps the stored filters), overwrite with the current filters, and delete (`ConfirmDeleteModal`) are each gated by the lens's own `canEdit` / `canDelete`; failures speak through `utils/lensErrors.ts` (403, 404, 409 = name taken or 50 lenses).
- **The detail page**: the main pane is the five Markdown sections (`AdrContentSections`, user Markdown in the `message` variant so its headings never reshape the outline) or the inline editor (`AdrEditForm`,
  `MarkdownField`, the visual Markdown editor: "The Markdown field" below); the side panel is Status (`AdrStatusControl`: one button per `allowedTransitions`; `moveOf(from, to)` names the two moves a target alone cannot - "Send back to draft" (`IN_REVIEW` → `SOLUTION_DRAFT`) and "Withdraw decision" (`ACTIVE` → `WITHDRAWN`); cancelling, withdrawing and sending back confirm in `AdrTransitionModal` with an optional reason of at most 1,000 code points, no control or hidden direction characters (`hasForbiddenReasonCharacter` mirrors `sanitizeMultiLine`; the button stays off), that the History shows as a "Reason:" line; a `400` to a confirmed move can only be the reason, so the dialog stays open with the reason and says it was refused; the bell says "sent back to draft" for the `SENT_BACK` detail), DACI
  (`AdrParticipantsPanel`/`Editor` over `UserDirectorySelect` and `UserMultiPicker`; the Approver picker is disabled when `canChangeApprover` is false and never offers the caller unless an ADMIN: nobody else makes themselves Approver), Tags and Successor (below), Details (the expiry date has its own editor, `AdrExpiryEditor`, offered when `canEditExpiry`) and History (`AdrHistory` over the ported
  `EventTimeline`; `utils/adrEvents.ts` renders every event type and its params as localized text). All sections are h3 under the page's h2.
- **The discussion (0.3.0)**: the detail page's main pane is `components/AdrMainPane.tsx`, four tabs - Document (the sections or their editor), Discussion (comments and questions), Issues (issues and escalations) and Lifecycle (below) - each panel is mounted when first opened and then kept, and the
  Discussion/Issues tabs carry the ADR's `unansweredQuestionCount` / `openIssueCount` as badges (so a tab's accessible name reads "Issues open: 2"). Each tab is `AdrDiscussion` (`variant` `discussion` | `issues`, kinds from
  `utils/adrDiscussion.ts`): an oldest-first thread of `DiscussionEntryCard`s (kind and state badges, author, date, `MarkdownView` body in the `message` variant, the tombstone placeholder of a deleted entry, the resolution note; Edit
  inline through `DiscussionEntryEditor`, Delete behind `ConfirmActionModal`, answer / resolve / reopen - resolving asks for an optional note in `DiscussionResolveModal`) and a `DiscussionComposer` (a kind switch, `MarkdownField`, one
  submit named for the kind). The server's `canComment` / `canRaiseIssue` controls posting. If the window closes with a draft open,
  the composer keeps it available with submission disabled. Every action is gated by the entry's own server verdicts (`canEdit`, `canDelete`,
  `canChangeState`; the state change has no window, so an old issue can still be resolved). Wrappers: `api/adrDiscussion.ts` (the entry's `state` and the list's `state` filter, named `status` until 0.9.x; the person fields are flat `authorId`/`authorName`/`authorDeleted` and `stateChangedById`/`stateChangedByName`/`stateChangedByDeleted`; the badge is `DiscussionStateBadge`); query keys `["adrs", "discussion", id, variant, page, onlyOpen]`, so every write refreshing `["adrs"]` also refreshes the counts, the list column and the history. The typed 409 `discussion-closed`
  renders through `AdrWriteError` (`kind="discussion"`) as the orange reload notice. Kind colours (`DISCUSSION_KIND_COLOR`): escalation red, issue orange, question grape, comment gray; settled states teal. The list has an Open issues column (an orange badge).
- **Unsaved ADR drafts.** The authenticated shell's `DraftGuardProvider` confirms leaving a dirty create, document, expiry or discussion form. An explicit cancel confirms the affected draft. Discussion paging and filtering protect entry drafts. See `.claude/docs/web-internals.md` for the shared router blocker and unload guard.
- **The lifecycle diagram (1.1.0).** The **Lifecycle** tab (`AdrMainPane`, lazy like the others, the tab state is the page's and is not in the URL) draws the workflow: `components/AdrLifecycleDiagram.tsx` (geometry as data, every word from `adrs.lifecycle.*` and the existing `adrs.status.*` / `adrs.transition.*` / `adrs.role.*`) over `components/LifecycleDiagram.tsx` (Lettuce's shared SVG primitive, ported and extended: an edge is a line or any SVG `d` path, two-line captions, `emphasised` edges, a node colour tint, a `minWidth` inside the focusable `ScrollRegion` so a phone scrolls the picture sideways instead of shrinking its type; `data-node` / `data-edge` / `data-current` / `data-emphasised` are the test and e2e hooks). The moves and who takes them are `utils/adrLifecycle.ts` (`ADR_LIFECYCLE_MOVES`, a hand-kept description of the server's machine: its vitest checks the table's internal consistency and the server suite's `AdrLifecycleMirrorTest` parses the literal and pins its edges to `ALLOWED_TRANSITIONS` (the automatic ones to `SYSTEM_STATUSES`) and each actor to what `allowedTransitions` gives a Driver and an Approver - keep the one-object-per-line literal shape; the server still judges every move). Layout: the draft pipeline down the middle, CANCELLED and WITHDRAWN on the right fed by orthogonal buses, the automatic endings (EXPIRED, SUPERSEDED) below ACTIVE, the skip arc and the send back on the left. Each node is tinted with its `ADR_STATUS_COLOR`; terminal nodes other than the current one dim; **where the ADR is = the heavy accent outline + bold**, **the moves the caller may make now = the accent, heavier arrows** (`allowedTransitions` of the ADR response, never re-derived), and under the picture the same facts as text (`Moves from <status>`: each move, who takes it, "You can do this now") because the SVG is one `role="img"` to assistive technology.
- **Tags and successors (0.4.0).** Tags are neutral pills (`AdrTagPills`: gray light badges, never a status colour and never blue, indigo or violet; a RETIRED tag - the registry dropped it - is an outlined, struck-through pill whose accessible name says "retired") on the list (a Tags column) and in the side panel's Tags section (`AdrTagsPanel`; whoever `canEditTags` says gets
  `AdrTagsEditor`: a searchable `MultiSelect` GROUPED by registry category over `useTagCategories`, `["tagCategories"]`, one `PUT .../tags` under the held revision; it starts from the live tags only and says retired ones are dropped on save, since the server refuses a write that keeps one). The list's tag filter is the same grouped picker, written into the URL (repeated `tag`). The Successor section (`AdrSuccessorPanel`) shows both ends of the link
  as links with their status badges; whoever `canEditSuccessor` says gets `SearchPicker` over the list endpoint ("This ADR supersedes…", by key or name, only ACTIVE or pre-active ADRs, never itself) plus **Link**, and **Remove the link** once linked; its picker is `lazy` (no search until the dropdown opens or the user types) and capped at the server's 100-character term; a 409 (a loop, a rival) is `AdrWriteError` `kind="supersedes"` in words. `AdrSupersededBanner` tops the page: orange "This ADR is superseded" with a link once `SUPERSEDED`, a cyan "A successor is on its way" while the successor is only linked AND this ADR is still `ACTIVE` or pre-active (a cancelled, withdrawn or expired ADR shows nothing, and since 1.2.0 the server no longer serves it a `supersededBy` either); whoever `canDetachSuccessor` says (the PREDECESSOR's Driver, Approver, ADMIN) gets **Detach** in it, behind a confirmation, one header-less `DELETE .../successor` (`AdrWriteError` `kind="detach"`).
  The ADMIN registry page is `pages/Tags.tsx` (`/tags`, nav entry and route ADMIN-only; Toadie's Tags page without the entity kinds): one unpaged table of categories with tag pills, `TagCategoryEditorModal` (name, description, a `TagsInput` that folds each tag to its normal form as it is added), a confirmed delete that says ADRs keep the tags as retired.
- **Successor acceptance (1.0.0).** `AdrSupersededBanner` reads a pending successor as a PROPOSAL (grape "A successor is proposed") until `supersededByAccepted`, with **Accept** (a confirmation, `acceptAdrSuccessor`) for whoever `canAcceptSuccessor` says, then cyan "on its way"; `AdrSuccessorPanel` tells under "This ADR supersedes" whether the predecessor's Approver accepted (teal / grape / gray; nothing once the predecessor ended, whose orange "no longer in force" note stands alone, an acceptance it gave while in force included), `AdrStatusControl` shows a waiting hint beside the "missing" one when the move to ACTIVE would be refused, and the typed `successor-not-accepted` 409 is `adrConflict` "successorNotAccepted" (`adrWriteError` kind `accept` has its own 404 and 409).
- **What the user may do comes from the server's verdicts** (`allowedTransitions`, `canEditContent`, `canEditExpiry`, `canManageParticipants`); the Edit button follows `canEditContent` and the expiry editor `canEditExpiry` (the Approver's or ADMIN's
  once the ADR is `ACTIVE`), so the SPA derives no role verdict (`utils/adrAccess.ts` keeps only `missingForReview`). The server re-judges every write.
- **Revision-protected writes send the revision the user acted on.** Open document, expiry, participant and tag editors retain their initial revision. A lifecycle confirmation retains the move and revision shown when it opens. Discussion writes use the entry revision, and successor detach sends the successor ID captured with the confirmation. A refetch can advance the page while an action is open, so the captured revision or ID lets the server refuse the stale action. ADR writes refresh the `["adrs"]` prefix. The same holds for the Tags page: the category object captured when its editor or delete confirmation opened carries the `revision` the PUT/DELETE sends. A typed 409 (`revision-conflict`,
  `adr-frozen`, `illegal-transition`; `utils/adrErrors.ts`) renders through `AdrWriteError` as an orange notice with a reload button; every other failure is a red alert in fixed words.
- **One status colour map** (`utils/adrStatus.ts`: gray, grape, cyan, teal, orange, red) feeds `AdrStatusBadge` everywhere; a new status colour goes through the vocabulary above.

## The Markdown field

Every user-written Markdown text (the five ADR sections, a discussion entry and its edit) is edited in `components/MarkdownField.tsx`: a **visual (WYSIWYG) editor by default** and a **Markdown source** view (a plain Textarea) behind a Visual/Markdown `SegmentedControl` whose choice is remembered (`hammurabi.viewSettings.markdownField.mode`). The value is ALWAYS the Markdown string; the saved text is shown by `MarkdownView` (the editor's job is to write what that renders). The visual editor is Lettuce's MDXEditor wrapper, ported: `components/MarkdownRichEditor.tsx` (+ `MarkdownCodeBlock.tsx`, `MarkdownRichEditor.module.css`) over `@mdxeditor/editor` (Lexical), **lazy-loaded** (`React.lazy` + `Suspense` with `LoadingBlock`) and split into its own `MarkdownRichEditor` + `lexical` chunks (`vite.config.ts`), so no page pays for it until a field mounts. Rules a change must keep (mechanics: `.claude/docs/web-internals.md` "The Markdown field"):

- **An unchanged field saves its original text.** MDXEditor re-serialises what it loads, and the server records an "Edited" history event per changed field, so `onChange` is emitted only for a USER's edit: never for the editor's own initial normalisation, never before the field was touched, never when the text equals what the parent holds. A new `value` from outside re-mounts the editor. `MarkdownRichEditor.test.tsx` runs the REAL editor (happy-dom) over every construct `MarkdownView` renders and asserts nothing is emitted. **The first real edit does rewrite the whole field in the editor's style** (it renders the same, the History says "Edited"): `*`/`+` bullets to `-`, `***` rules to `---`, loose lists to tight, `1)` to `1.`, setext headings to ATX, indented code to fenced, `<url>` and bare URLs to `[url](url)`, a hard break to a backslash and a newline, `<3` to `\<3`, runs of blank lines collapsed. Hard breaks are kept as hard breaks (`hardBreakPlugin`); anything the editor cannot represent never gets here (it opens as source).
- **Nothing is dropped, nothing is raw HTML.** HTML processing is off (`suppressHtmlProcessing`) and underline/sub/superscript are not offered; raw HTML, reference links, images and anything else the editor cannot represent fail to parse, and the field opens in the Markdown source view with a note (the stored preference is untouched). The text is never rewritten by opening it.
- **The editable region is the field's textbox**: Lexical's `role="textbox"` div carries `aria-labelledby` (the label), `aria-multiline`, `aria-required`, `aria-invalid` and `aria-describedby` (set from outside by a MutationObserver), so `getByRole("textbox", { name: /^Context/ })` finds it in either view. Playwright `fill()` works on it (it inserts text verbatim; the Markdown shortcuts, e.g. `**bold**` becoming bold, fire only for typed keys: `click()` + `pressSequentially()`).
- **`maxLength` never cuts the text**: the counter (`charCountDescription`) shows near the limit and the form's own validation reports a too-long text.
- **Light/dark and brand**: MDXEditor's `dark-theme` class follows `useComputedColorScheme`; its accent variables are mapped onto the lapis tuple and Mantine tokens in `MarkdownRichEditor.module.css`. The editor's own tooltips and dialogs speak the UI language (`locales/*/markdownEditor.json`, passed as MDXEditor's `translation`; a language switch re-mounts it).
- **Tests of anything that merely uses a `MarkdownField`** install the stand-in: `vi.mock("../components/MarkdownRichEditor", () => import("../test/markdownRichEditorStub"))` (a plain textarea with the same name and state), then `await screen.findByRole("textbox", ...)` (the editor is lazy).

## Notifications (0.6.0)

Adder's bell, ported without its live channel, rooms and threads: **there is no push, the bell polls.** `components/NotificationsButton.tsx` sits in the shell header between the palette trigger and the `UserMenu`: a labelled `ActionIcon` ("Notifications (N unseen)", "Notifications (999+ unseen)" once the server's capped `total` reaches `NOTIFICATION_TOTAL_CAP` = 1000) with a brand-accent count `Indicator` (the same lapis as the account menu's what's-new dot: red is blocking, never "unread") and a right-hand `Drawer` that lists the caller's notices newest first, 20 a page (`components/CompactPagination.tsx`, the sub-collection pager the discussion threads and the history share), read only while it is open. The count is `hooks/useUnseenNotifications.ts`: `GET /notifications?seen=false&page=1&pageSize=1`, only `total` read, `refetchInterval` 60 s (not in a background tab), enabled only with a token. Every key sits under the `["notifications"]` prefix (`utils/notificationKeys.ts`), so a write refreshes badge and list together via `refreshQueriesAfterMutation`; the session boundary needs nothing extra (`queryClient.clear()` on an identity change drops them, and the bell closes its drawer and stops reading when the token goes). A row is a `RouterLink` to `/adrs/{adrId}` (`adrPath`) with a lapis unseen dot plus a visually hidden "Unseen"; a plain click marks it seen (fire-and-forget POST, re-read on settle) and closes the drawer, a modifier-click opens a new tab and changes nothing but the seen mark. "Mark all as seen" shows while anything is unseen. **The sentences** live in `locales/*/notifications.json` (`notifications.event.*`) and are chosen by `utils/notificationText.ts` (`describeNotification`): one per type, the actor's name or "the system" (null actor), the ADR key in the sentence and the ADR name + date/time in the subline; the role, status and entry-kind WORDS come from `adrs.role.*`, `adrs.status.*` and `adrs.discussion.kind.*` and are never copied (the PL sentences put them after a colon or in quotes, since the locale holds only the nominative); an unknown type shows its raw name and an unknown detail shows as sent. **Preferences:** `pages/NotificationPreferences.tsx` (`/notification-preferences`, lazy; the `UserMenu` item and the palette entry via `ACCOUNT_NAV`) is one table of every notice kind (thirteen today) with a Bell and an Email `Switch` per row (`aria-label`s "Bell: <kind>" / "Email: <kind>"); Save PUTs every kind the GET listed, `{type, inApp, email}` each (the GET's own shape); email needs the bell (a row whose bell is off has its email switch off and disabled), every email switch is disabled with a gray note when `emailAvailable` is false, and a kind this build does not know is hidden but sent back with its read switches (the email choice of a bell-off row is stored as it was). Wrappers: `api/notifications.ts`.

## Registries (the Teams.tsx template)

The small ADMIN-curated registries (Teams and, since 0.4.0, the tag categories on `/tags`) compose
`useRegistryListControls` for their persisted name filter, debounce and paged sort, and
`RegistryListTable` for the common load/error/empty/pagination states. Each page owns its query
key and parameters, extra filters, columns, row actions and mutation refresh prefixes. Their
editors (e.g. `components/TeamEditorModal.tsx`) share the `RegistryMetadataFields` (name +
description, with `utils/charCount.tsx`'s "123 / 4000" counter) and `RegistryEditorActions`
(save-error alert + cancel/submit footer) components; form rules
(`utils/formRules.ts`'s shared `nameRule`/`descriptionRule`), submit/conflict handling and any
parent/target fields stay local. Keep each registry's field limits explicit and mirrored from the
server's own validation (`infra/validation/Text.kt`'s `requireNameAndDescription`).

**Searchable pickers** (Astrolabe's via Adder, ported): `components/SearchPicker.tsx` owns the search-box mechanics (a debounced server-side term, the caller holds the pick so its label survives later searches); thin wrappers own the query and the texts — `UserDirectorySelect` (`GET /users/directory`, open to every signed-in user; the server REQUIRES a term, so the picker searches only from two characters, page size 20, and shows a hint below that — `minTermLength`/`minTermHint`). `TeamManagerSelect` stays on the ADMIN-only users list. The DACI pickers (Driver, Approver, Contributors) are `UserDirectorySelect`s; a team picker joins as a wrapper when an ADR needs one.

## Forms (the CreateUser/EditUser template)

- Shared vocabulary in `utils/<area>Form.ts`: the `<Area>FormValues` type, length constants
  mirroring the server's, a `<area>FormValidation(t)` factory (rules identical to the server's —
  keep them in sync), and `toRequest`/`fromResponse` mappers. The field block lives in
  `components/<Area>FormFields.tsx`; the pages own submit/error/navigation.
- Edit prefills via **`form.initialize(...)` guarded by `!form.initialized` during render** —
  never a `useEffect`. Submit is a plain async fn with local `submitting`/`error` state wrapped by
  `form.onSubmit`; success → `invalidateQueries` (list + detail) → `showSuccessToast` →
  `navigate(list, { replace: true })`; failure → `saveErrorMessage` into an inline Alert.
- **Widths**: `Paper withBorder p="xl" maw={FORM_MAX_WIDTH}` under a `PageHeader` (with `backTo`
  to the list) for simple field forms — left-aligned, never a centred `Container`.

## User management and feature flags

Passwords are generated client-side and revealed exactly once (`components/OneTimePasswordModal.tsx` sets `closeOnClickOutside={false} closeOnEscape={false}`; closing drops the plaintext for good). The management routes sit under ONE `<Route element={<RequireAdmin />}>` in `App.tsx` — no page redirects itself, their queries stay `enabled: isAdmin()`; UX only, the server's `requireAdmin` is the rule. Users live under `/users` (list, `/new`, `/:id/edit`, `/:id/features`; `utils/userLinks.ts` spells the family out — never hand-assemble a URL); feature flags (`/users/:id/features`, ADMIN `/feature-flags`): mind the wholesale-replace semantics — a PUT whose disabled set omits `MFA` ENABLES it. Page narratives (`pages/Users|CreateUser|EditUser|ChangePassword|FeatureFlags|UserFeatures.tsx`): `.claude/docs/web-internals.md`.

## Per-feature rules (every page)

The ADMIN telemetry panel (`components/Telemetry*.tsx`, the shell's right-hand aside: memory, storage, requests, JVM) is the only chart surface. Rules that apply to every page:

- **Charts** (any page): charts live only in lazy chunks, each chart component imports `@mantine/charts/styles.css` itself, a legend for two or more series, one axis (never dual), every chart has its numbers in a table beside it, colour is never the only carrier of identity, and colours come from the existing vocabulary in `utils/chartColors.ts` (no new hue for meaningful series; series keyed by arbitrary names — statuses, columns, people — draw from its `CATEGORY_PALETTE`; every mark clears WCAG 1.4.11, ≥ 3:1). Tests mock `@mantine/charts`. 
- **Dates** render deterministically (`YYYY-MM-DD` / `YYYY-MM-DD HH:mm` via `utils/formatDate.ts`), never `toLocaleString()`. **A timestamp is read in its reader's zone**: every ADR-page timestamp (updated, history, discussion, notices) passes `browserTimeZone()` as the `timeZone` of `formatDate`/`formatDateTime` (UTC is only the default); a date-only value (`expiresOn`, the `YYYY-MM-DD` filters) is a calendar day and is never shifted, and the telemetry panel stays UTC (the server's sampling clock). Tests that print a timestamp pin the zone through `test/timeZone.ts`.
- **Failures render inline, never as a toast** (see "Error handling"); success toasts use the fixed vocabulary only.

## Internationalization (i18n)

The SPA is **N-language by architecture** via react-i18next (`src/i18n.ts`); the shipped bundles
are English (THE default and fallback everywhere) and Polish. All user-facing strings go through
`const { t } = useTranslation()` / `<Trans>` — **no hardcoded UI text**. Conventions:

- **Resources** live in `src/locales/{en,pl}/<area>.json`, one file per area (`adrs`, `appShell`, `auth`,
  `changelog`, `common`, `home`, `lenses`, `markdownEditor` (the visual editor's own tooltips and dialogs), `notifications`, `tags`, `teams`, `telemetry`, `users`); `i18n.ts` merges them into a single
  `translation` namespace, so keys read `area.key` (e.g. `t("auth.signIn")`). Only EN is
  statically imported — its typed `en` tree is the key canon AND the runtime fallback; every other
  language is auto-discovered from `locales/<lang>/` via `import.meta.glob`. Bundles are eager on
  purpose; move non-EN to lazy loading when a 3rd language ships or a bundle grows large.
- **Keys are typed**: `src/i18next.d.ts` augments i18next's `CustomTypeOptions` with `typeof en`
  from `i18n.ts` (the `@public`-tagged export — knip can't trace the d.ts consumer), so every
  `t()` call and `i18nKey` is compile-checked against the EN tree — a typo or removed key fails
  `tsc`/`npm run build`. Fields holding a key are typed `ParseKeys` (from `i18next`), never
  `string` (the `NavLeaf` `label` pattern in `utils/navigation.ts`); functions taking a translator
  take `TFunction`, never a hand-written `(key: string) => string`.
- **`common.*` is the shared source**: actions, field labels, shared vocabulary. Reuse it instead
  of duplicating; build Mantine `Select` option labels from `t()` at render so they translate.
- **Keep key parity vs EN for every shipped language** — a shipped bundle is all-or-nothing: every
  English key must exist in each `locales/<lang>/` (language-specific plural variants are
  expected), enforced by `locales/parity.test.ts` (auto-discovers language folders; also pins
  folders == `SUPPORTED_LANGUAGES`). Use i18next interpolation (`{{name}}`) and plural keys rather
  than string concatenation.
- **Adding a language** = a complete `locales/<lang>/` folder (parity-gated) + one code in `SUPPORTED_LANGUAGES` (`i18n.ts`) + one `NATIVE_LANGUAGE_NAMES` line; the server-side steps are in `.claude/docs/web-internals.md`.
- **Polish voice convention** (inherited from Lettuce): inclusive slash forms, active/direct
  voice, same tense and meaning as the English; spell out irregular feminines in full. No
  impersonal/passive dodges.
- The language switcher is the Language section of `components/UserMenu.tsx`; the user language is ONE synced property (the switcher also saves it server-side — it drives every email). Details: `.claude/docs/web-internals.md`.
- **Tests render English**: `src/test/setup.ts` imports `../i18n` and forces `en`, so text-based
  assertions match the EN resources.

## Theming

**The design language is theme-owned** (Lettuce's "clean enterprise SaaS" posture) —
`src/theme.ts` + `src/themeVariables.ts` + `src/theme.module.css` are the single source, and
`src/theme.test.ts` guards every contrast ratio:

- The brand is the 10-stop **`hammurabi` lapis lazuli indigo tuple** (`#eef1fb #dce2f6 #b8c3ec #93a4e2 #7d90de #5a70d2 #4459c8 #3a4cb0 #2f3f94 #263378`,
  hue ≈ 228°) with `primaryShade: { light: 7, dark: 8 }` — the shades that carry white text sit at its deep end (white on shade 7 and on shade 8
  clear 4.5:1, pinned in `theme.test.ts`) — and `autoContrast: true`.
  **Restrained** — in UI chrome and status badges (chart series colours are
  `utils/chartColors.ts`'s): lapis marks only primary CTAs, the active nav item (a light tint + a
  3px accent bar), focus and the lifecycle diagram's "you are here" / "moves you can make now" marks — it is the interactive accent and nothing else (see the colour vocabulary). Everything else
  is neutral BY THEME DEFAULT — `Anchor` is text-coloured with a
  hover underline, `Chip` is `variant="light"`, `Badge` is `variant="light" color="gray"` (a colourless badge is neutral, never the brand), `ActionIcon` is
  `variant="subtle" color="gray"` (destructive ones pass `color="red"`), `Menu` is bottom-end in a
  portal, tables are `verticalSpacing="xs"` (≈40px rows). Pages never pass those props back. Teal
  is the SUCCESS colour, red the BLOCKING one, orange the warning one (soft findings, waived); the ADMIN role badge is
  `variant="outline" color="gray"`. **Never use stock blue, indigo or violet for a status** (in progress is cyan) — they sit next to the lapis brand. The light
  canvas carries a faint lapis cast (`#f4f5fa`), pinned equal to `index.css`'s first-paint hex.
- **Colour tokens are AA-tested** (`themeVariables.ts`: `LIGHT_TOKENS`/`DARK_TOKENS`/`LIGHT_VARIANT_INKS`, wired in through `cssVariablesResolver` in `main.tsx`). Never hand a shade-suffixed colour (`"orange.8"`) to a light Badge/Alert — the tint comes from the hue, the ink from that map. Internals (`--hammurabi-accent-ink`, the first-paint hexes): `.claude/docs/web-internals.md`.
- `theme.module.css` is scheme-aware **via `light-dark()`** throughout (tables card-framed on a
  quiet tinted canvas, white header/navbar surfaces, hairline borders) — new surface styling
  follows that pattern, not `[data-mantine-color-scheme]` selectors. Every `Table` inherits the
  card frame + hoverable rows + neutral compact header from the theme's `Table.extend` — don't add
  per-table frames.
- Soft diffuse `shadows` scale; tightened heading sizes (pages title themselves with `order={2}`).
- Inter is bundled (`@fontsource-variable/inter`, imported in `main.tsx`) so it loads same-origin
  and satisfies the CSP `font-src 'self'`; the system stack is the fallback.
- **Overlay accessibility rules (pinned by the e2e axe sweep over dialogs):** every `Modal` and
  `Drawer` passes `closeButtonProps={{ "aria-label": t("common.action.close") }}` — Mantine's
  close X has no default name — or hides the X with `withCloseButton={false}` when a footer
  button is the one deliberate exit (`OneTimePasswordModal`).
- **A scroller that can overflow is a focusable, labelled region.** A table wider than its box or
  capped by `maxHeight` goes in `components/ScrollRegion.tsx` (native scroller, `tabIndex={0}`,
  `role="region"`, the table's own i18n name as `aria-label`, the `tableScroll` focus ring) — never a
  bare `Table.ScrollContainer`; axe's `scrollable-region-focusable` fails any that overflows without it.
- The logo SVGs are computed geometry — change them in a vector editor, never by hand-editing the path data; restyle rule: keep aria-labels, roles and real semantic elements stable. Details: `.claude/docs/web-internals.md`.

## Changelog & app versioning

The user-facing changelog is a **build-time artifact** — no DB, no API, changes only with a
deploy. `src/changelog/entries.ts` holds `ChangelogEntry` rows (`version`, `date` `YYYY-MM-DD`,
`en`/`pl` **markdown** bodies), newest first; the app's only human-readable version is
`APP_VERSION` in **`src/changelog/version.ts`** — its own tiny module so the shell's eager imports
(VersionStamp, the what's-new dot) never pull the bilingual entries into the main bundle (the
entries ride the lazy Changelog chunk only). **A release = the new entry at the top of
entries.ts + the bump of that one literal** (the Gradle `1.0.0-SNAPSHOT` is unrelated);
`entries.test.ts` pins `CHANGELOG[0].version === APP_VERSION`, so forgetting either half fails the
suite. Release convention:

- Write both language bodies by hand (or LLM) — never derive them from commit messages. Bodies
  are *content*, so they live in the data file, not `locales/`; the PL body follows the Polish
  voice conventions (inclusive slash forms, active voice).
- Keep dates descending (same-day releases are fine — newest stays on top), versions unique, and
  both bodies non-empty — pinned by `src/changelog/entries.test.ts`.
- Complete publication with the annotated version tag and bilingual GitHub release described in
  [the application release process](../.claude/docs/app-releases.md). A version is a roadmap
  milestone: its release commit bumps the version with its one entry (a later fix is folded in before the tag, or ships as a patch).
  Documentation/test-only changes keep the version; published tags never move.
- Keep phrases that tests assert on in plain text runs: markdown formatting splits text nodes,
  and testing-library's `getByText` matches direct text nodes only.

Rendering & UI wiring (details: `.claude/docs/web-internals.md`): `pages/Changelog.tsx` renders the entries through `MarkdownView` and calls `markChangelogSeen()`; bodies are authored EN+PL only, by policy. The "what's new" dot is driven by `hooks/useChangelogSeen.ts` via `useSyncExternalStore` — do **not** replace that with a plain localStorage read in state.

## Not yet ported from Toadie / Lettuce (port, don't reinvent)

When a feature needs a Lettuce or Covenant `web/` building block (`safeBackParam`, `StatusPill`/`MetaStrip`/`FormFooter`/`ListToolbar`, `useDiscardGuard` / the `beforeunload` guard (Flow's `hooks/useBeforeUnloadGuard.ts`, dropped with the Capacity page), the catalog filter-set/facets, the CodeMirror editor), port the sibling's `web/` implementation and its `web/CLAUDE.md` section wholesale — the list is in `.claude/docs/web-internals.md`. (`hooks/useRovingGrid.ts`, kept as prior art, was deleted in the 1.0.0 checkup, C-SMELL-1: no grid view consumed it; Flow's implementation is the donor.)
