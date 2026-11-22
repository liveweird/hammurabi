# Web internals (read on demand)

Mechanism and narrative text kept out of `web/CLAUDE.md` so that file stays a lean always-loaded-in-`web/` rule set. The binding rules (every never/must/always) stay in `web/CLAUDE.md` as one-liners; this doc holds the mechanics behind them. **Read the relevant section before changing the transport/session layer, the user/feature-flag pages, the language switcher, the colour tokens or logo, or the changelog wiring.**

### `http.ts` — the transport's refresh internals

- **`http.ts`** (transport): `authedFetch()` with the **single-flighted silent refresh** — on a
  401 it exchanges the stored refresh token once (concurrent 401s from the same session share one
  in-flight `/refresh` call) and retries once; a DEFINITIVE rejection (no refresh token, or
  401/403 from the server) clears the originating session and signs out only if that session is
  still current, while a TRANSIENT failure (network/timeout/5xx/429/malformed body) keeps the
  session so a later retry can succeed — **never collapse the two** (a network blip must not
  erase a valid session). Stale requests and refresh completions must not publish credentials,
  clear a newer login or retry across session identities. **The server rotates the refresh token** (each works once, plus a
  60 s server-side replay grace for a lost response; after that a replay is a 401), so two tabs on one session can race on
  `/refresh`: `doRefresh` serialises them with a Web Lock (`hammurabi.auth.refresh`, where the browser has `navigator.locks`; a lock
  that cannot be acquired falls back to an unserialised refresh) and, inside it and again after a 401/403, adopts a pair already
  stored by another tab (same session identity, different refresh token) instead of replaying or clearing; a different login (new identity) is never adopted. **Why a 401 can now come at any time:** the server checks every access token against the user row per request (`security.md` "JWT model"), so a delete, a demotion/promotion, a password change or a logout elsewhere turns the NEXT request into a 401 (one text). The transport needs no special case: the 401 -> silent refresh -> retry path handles all of them - a role change refreshes successfully into the NEW roles (`persistRefreshedSession` republishes them and `notifyAuthChange` re-renders the nav and `RequireAdmin`, pinned in `http.test.ts`), a delete or password change makes the refresh a DEFINITIVE rejection and signs the user out. `ApiError` (status + parsed body; read
  RFC 7807 fields via the `detail`/`instance`/`type` getters, never hand-cast `err.body`), and the two
  standard wrapper shapes **`jsonRequest<T>()`/`voidRequest()`** every ordinary endpoint wrapper
  uses, plus **`buildQuery()`** — the query-string builder behind the list wrappers (skips
  null/undefined/""; `false` and `0` ARE sent, so an omit-when-false param is passed as
  `value || undefined` at the call site). Reach for the raw `authedFetch`/`safeJson` primitives
  only when a wrapper genuinely inspects the Response. Every transport fetch carries
  `timeoutSignal()` (30 s, feature-detected for happy-dom) — a hung request rejects with a
  `TimeoutError` DOMException instead of pending forever; new `fetch` call sites in `api/` must
  attach it.

- **Downloads** (`api/download.ts`, 0.8.0): a file is fetched with `downloadRequest(path, fallbackName)` - `authedFetch` (a plain `<a href>` cannot send the bearer header), a non-2xx answer an `ApiError` with its problem body, the body a Blob, the name from `Content-Disposition` only when it is a plain `[A-Za-z0-9._-]` slug (else the fallback) - and handed to the browser by `utils/saveFile.ts`. It carries the transport's 30 s `timeoutSignal`.

### `session.ts`

- **`session.ts`**: token/roles/userId storage under the `hammurabi.auth.*` localStorage keys, written
  by `persistSession(LoginResponse)` on login/MFA and conditionally by `persistRefreshedSession`
  on refresh, and the render-time accessors (`isAdmin()`, `getUserId()`, …). Components whose
  controls or queries depend on ADMIN use `useAdmin()` from `auth.tsx`; it subscribes to role
  changes from silent refresh and other tabs. A random stored identity distinguishes sign-ins,
  while credential snapshots guard refresh publication; renewing tokens preserves the logical
  session so staggered 401s can reuse the new token.

### Session boundary

- **Session boundary** (`components/SessionBoundary.tsx` + `utils/sessionQueryCache.ts`): bind the
  root `QueryClient` to logical session identity changes, and key the mounted app subtree by that
  identity. A different sign-in, definitive automatic sign-out, explicit sign-out, or a change
  from another tab must synchronously cancel and remove caller-scoped query data and discard
  mounted form/mutation results before the next account renders; an access/refresh token rotation
  within the same identity must retain them. Keep this at the session boundary rather than
  clearing individual page keys, because user/team/account queries all contain caller-specific
  projections. Late requests from the previous identity must not repopulate the new account's UI
  or cache.

### Sign-out and the return destination

Explicit sign-out opens `/login` without a saved return destination, so the next sign-in opens
My work (`/my-work`) even when a different account signs in. Anonymous access to a protected link still preserves
its path, query and hash. The legacy root redirects to My work. Test logout navigation with the real route guards
from a protected detail or list page. Reading the signed-out notice
during render must be side-effect free. Acknowledge it only after the login page commits, so a
suspended or retried render cannot discard the notice. The flag carries a reason (`SignedOutReason` in `auth.tsx`: `sessionEnded`, the default, or `passwordChanged`; `pendingSignedOut()` reads it, `consumeSignedOut()` clears it) and `pages/Login.tsx` picks the notice from it. **The self password change signs the device out explicitly** (`pages/ChangePassword.tsx`): the PUT bumps the account's credential revision, which ends every access and refresh token including this device's, so after the success toast the page runs `clearSession()`, `flagSignedOut("passwordChanged")` and `navigate("/login", { replace: true })` (the notice reads "Your password was changed. Sign in with your new password."). No `/logout` call - nothing is left to revoke, and the stale access token would only 401; no broadcast, for the same reason as `UserMenu`'s sign-out.

### Password reset (confirm link)

`pages/ResetPassword.tsx` only REQUESTS a reset (a uniform 202: the server emails a single-use link and changes nothing). The link lands on `pages/ConfirmPasswordReset.tsx` at `/reset-password/confirm#token=…`, which is NOT behind `RedirectIfAuthed` (a signed-in device may finish a reset). The token travels in the URL fragment, so it never reaches a server log or a Referer: the page reads it once into component state, strips the fragment and any query from the address bar and history (`navigate(…, { replace: true })`), keeps it in memory only (never storage) and sends it to nothing but `confirmPasswordReset` (a plain `fetch`, never `authedFetch`, so it can never refresh or retry a session). Loading the page consumes nothing; the form shows for any well-formed 43-character token and an unusable one is learned at submit (401: the token is dropped and "Request another reset link" shows; 429, 400 and network failures keep it for a retry). The new-password rule is `newPasswordValidation` (`utils/userForm.ts`), shared with `ChangePassword.tsx`. On success the page runs `clearSession()`, `queryClient.clear()` and `notifyAuthChange()` and shows the teal notice: no `/logout` call (the reset bumped the credential revision, so nothing is left to revoke) and no return destination.

## User management (`pages/Users|CreateUser|EditUser|ChangePassword.tsx`)

Passwords are generated client-side (`utils/password.ts`) and revealed exactly once: the modal
(`components/OneTimePasswordModal.tsx`) sets `closeOnClickOutside={false} closeOnEscape={false}`,
renders `components/RevealablePassword.tsx` (masked by default, eye toggle, copy), and closing
drops the plaintext from state for good — the server never returns it. The admin Reset-password
action (`hooks/useResetPassword.ts`) reuses the same generate→PUT→reveal flow. `NavLeaf.adminOnly`
gates the Users nav item (the Shell filters by `isAdmin()`); the management routes sit under ONE
`<Route element={<RequireAdmin />}>` in `App.tsx` (`auth.tsx`, beside `RequireAuth` — a regular
user is sent to My work), so no page redirects itself; their queries additionally stay
`enabled: isAdmin()` so a redirected caller fires no request. UX only — the server's
`requireAdmin` is the rule. The pages live under `/users` (`utils/userLinks.ts` is the one place the family is spelled out; Flow's `/flow-users` rename and its legacy `/users…` redirect do not exist here).

## Feature flags (`pages/FeatureFlags.tsx`, `pages/UserFeatures.tsx`)

Lettuce's per-user flag surfaces, ported with the MFA feature: the `hammurabi.auth.disabledFeatures` localStorage set
(`api/session.ts`; no area-gating consumer yet, so its reader `hasFeature()` was deleted in the 1.0.0 checkup; MFA gates only the login flow server-side), the per-user editor at
`/users/:id/features` (the Users table's Features button — a checkbox per `Feature`, wholesale-
replace PUT), and the ADMIN per-feature screen at `/feature-flags` (`adminOnly` nav leaf; state
filter + bulk enable/disable over every row matching the current filters behind a count-stating
confirm — `hooks/useBulkFeatureUpdate.ts` loops the same per-user wholesale PUTs client-side via
`ConfirmActionModal`). The page itself is only the query, the table and the single-row toggle; the rest sits in
co-located pieces: `hooks/useFeatureFlagFilters.ts` (the persisted filters), `hooks/useFeatureFlagBulk.ts` (the bulk run
wired to this screen), `components/FeatureFlagFilters|FeatureFlagBulk|FeatureFlagRow.tsx` and `utils/featureFlags.ts`
(`isFeatureEnabled`/`withFeatureEnabled`, the one place a disabled set is rewritten). A second `Feature` value needs no
restructuring: the feature `Select` already lists `FEATURES`. Both queries key under `["users", …]`. Mind the wholesale-replace
semantics: a PUT whose disabled set omits `MFA` ENABLES it. A bulk run with nothing to change is neither
success nor failure: it shows the inline gray `role="note"` Alert (`users.featureFlags.bulkNoChange`), never a toast.

### The Markdown field

`components/MarkdownField.tsx` (the public props: `label`, `value`, `onChange`, `error`, `maxLength`, `required`, `description`, `minRows`) is the one Markdown editor; its callers are `AdrContentFields` (the five ADR sections, create and edit), `DiscussionComposer` and `DiscussionEntryEditor`. Two views over the same string: the **visual editor** (`MarkdownRichEditor`, lazy) and the **source** view (a Mantine autosize Textarea), switched by a Visual/Markdown `SegmentedControl` and remembered through `useStoredState("markdownField.mode")`. The counter sits under either view, and the label, description and error are ONE `Input.Wrapper` with ids, so the field's name and `aria-describedby` are the same in both views.

`MarkdownRichEditor` (Lettuce's `MarkdownEditor` ported and reshaped; MDXEditor is uncontrolled, it reads `markdown` once) enables headings, lists (with task lists), quote, link + link dialog, thematic break, table, code block (a plain monospace textarea, `MarkdownCodeBlock`, the catch-all descriptor), the Markdown shortcuts, and a toolbar of undo/redo, bold/italic, strikethrough, inline code, lists, block type, link, table and code block (a rule is typed as `---`; no underline or sub/superscript: those are raw HTML in Markdown). Serialisation options: hyphen bullets and rules, `listItemIndent: "one"`. The `diffSourcePlugin` is NOT used (CodeMirror is a heavy chunk); the source view is our own Textarea.

**The no-spurious-change rule.** MDXEditor reports `onChange(markdown, initialMarkdownNormalize)`. It re-writes what it loads (`* a` becomes `- a`, `<url>` becomes `[url](url)`) and, a moment after mount, its link plugin turns a BARE URL into a link: a second, unflagged `onChange` with no user action. The wrapper emits only when ALL hold: the call is not flagged as the initial normalisation, the user has touched the field since the editor mounted (capture-phase `keydown`/`beforeinput`/`paste`/`cut`/`drop`/`click` on its container), and the text differs from what the parent holds (the last emission, or the value it was given). So opening an ADR's edit form and saving without typing sends the original text and records no history event. A `value` that differs from what the editor knows (a posted comment clearing its composer) is an external update: the editor is re-mounted (a `key`), a clean slate with its own normalisation to ignore, rather than pushed in with `setMarkdown`. A space that ends a line (the text, a paragraph, a list item) is written by mdast as `&#x20;` until the next character arrives; the form does not trim a Markdown field, so the wrapper strips that entity from every line end outside fenced code, never an escaped one (`\&#x20;`, the literal text typed). **Line breaks:** MDXEditor exports a Lexical line break as the text `\n`, which Markdown reads as a SOFT break, so an edit would turn `a  \nb` into `a b`; `hardBreakPlugin` registers a higher-priority export visitor that writes a real `break` node (a backslash and a newline), and drops a break nothing follows (Shift+Enter at the end, which would be a stray `\`). A soft break (a plain newline) stays soft. **The first edit rewrites the whole field in the editor's style** (the list in `web/CLAUDE.md`).

**Fallback, never loss.** HTML processing is off (`suppressHtmlProcessing`), so raw HTML is an unrepresentable `html` node: like a reference link, a definition or an image (no image plugin), it makes MDXEditor's import throw and call `onError`; `MarkdownField` then shows the Markdown source view with a note, WITHOUT changing the stored preference or the text (the editor emitted nothing). `MarkdownRichEditor.test.tsx` lists the constructs both ways.

**Accessibility.** Lexical renders `<div role="textbox" contenteditable aria-label="Editable text">` itself; a MutationObserver on the wrapper adds `aria-labelledby` (the field's label, which wins over the library's `aria-label`), `aria-multiline`, `aria-required`, `aria-invalid` and `aria-describedby`, again after every re-mount. The toolbar's toggles are `role="radio"` buttons named by `markdownEditor.toolbar.*`. The editor's strings come from `locales/*/markdownEditor.json` (MDXEditor's `translation` callback, keys `area.key` as the library spells them, English defaults from the library for any key not listed); the callback is read once at mount, so a language switch re-mounts the editor.

**Chunking.** `React.lazy(() => import("./MarkdownRichEditor"))` puts the editor (and `@mdxeditor/editor/style.css`) in its own chunk; `vite.config.ts` gives `@lexical`/`lexical` a `lexical` chunk below the `react` group (so React is not pulled into it) to stay under Vite's 500 kB warning. The initial graph carries only the field, `LoadingBlock` and the two small `markdownEditor.json` bundles. **Styling** is `MarkdownRichEditor.module.css`: the box and surface are Mantine tokens, the accent variables MDXEditor paints active toggles with are the lapis tuple (`light-dark()`), and MDXEditor's habit of copying our `className` onto a popup host appended to `<body>` is neutralised there.

### Heading outline guard

`pages/headingOutline.test.tsx` renders every route read from `App.tsx` (via `?raw`, so a new route is covered
the moment it registers) under an ADMIN session with an empty API and asserts the outline starts at h2 and never
skips a level going down. A page whose real title needs loaded data passes a static `title` to
`EditPageLoadState`, so its load/failure state still opens with an h2 (TeamDetails).

### Adding a language

- **Adding a language** = a complete `locales/<lang>/` folder (every area file, parity-gated) +
  one code in `SUPPORTED_LANGUAGES` (`i18n.ts`) + one `NATIVE_LANGUAGE_NAMES` line — the Record
  type and the parity test each fail loudly on a missed step. Server-side, the `LocalizedText(en, pl)`
  constructor arity is the parity gate for email texts (a new language means a new parameter —
  every email text becomes a compile error until translated), and `users/Languages.kt` mirrors
  `SUPPORTED_LANGUAGES`.

### The language switcher and the synced user language

- The language switcher is the Language section of `components/UserMenu.tsx` (the header account
  menu) — `Menu.Item`s of NATIVE language names (`NATIVE_LANGUAGE_NAMES`, deliberate constants:
  readable before switching, and `Intl.DisplayNames` yields lowercase forms); choice persists in
  `localStorage` (`hammurabi.lang`) and updates `<html lang>` (the `languageChanged` hook in
  `i18n.ts`). **The user language is ONE synced property (V1's `language` column)**: the switcher
  also fire-and-forget saves it server-side (`setUserLanguage` — it drives every email sent to the
  user), and `persistSession` applies the login/refresh response's `language` to the UI — signing
  in restores the user's language on any device, and an admin change propagates at the ≤15-min
  silent refresh. Emails render in the RECIPIENT'S stored language (`LocalizedText(en, pl)` in
  `infra/mail/`, EN fallback).

### Colour tokens

- **Colour tokens are AA-tested** (`themeVariables.ts`, ported from Lettuce): `LIGHT_TOKENS`/
  `DARK_TOKENS` (text, dimmed, canvas, surface tint, borders, error inks) and
  `LIGHT_VARIANT_INKS` (the per-hue ink over a `variant="light"` surface — Mantine's stock light-
  scheme ink fails 4.5:1 for several hues) go in through `cssVariablesResolver` in `main.tsx`.
  Never hand a shade-suffixed colour (`"orange.8"`) to a light Badge/Alert — the tint comes from
  the hue, the ink from this map. **The brand is the lapis lazuli `hammurabi` tuple** (`theme.ts`, `primaryShade: { light: 7, dark: 8 }`: `#3a4cb0` light, `#2f3f94` dark,
  white text clears AA on both). **`--hammurabi-accent-ink`** is the brand hue where it must be PERCEIVED against the canvas (the nav marker, the anchor hover
  underline, focus): the light primary `#3a4cb0` in light mode, the 3-shade `#93a4e2` in dark mode; the light-variant ink for the brand hue is `#2f3f94`, and the light canvas
  carries a faint lapis cast (`#f4f5fa`). Success is **teal** (ink `#087255`), never stock blue/indigo/violet for a status, so nothing reads as the accent (pinned by a CIEDE2000 distance test in
  `theme.test.ts`); red = blocking/violation, orange = warning/overload/drift, gray = neutral, cyan = in progress, grape = waiting — the full vocabulary is `web/CLAUDE.md`.
  `utils/chartColors.ts` exports `CATEGORY_PALETTE` only (`hammurabi.5`, `grape.5`, `pink.6`, `violet.5`, `gray.6` — categorical series, never semantic status colours). `index.css` repeats the two canvas hexes for
  the first paint (`public/color-scheme.js` stamps the scheme on `<html>` before the bundle
  loads); the theme test pins them equal to the tokens.

### The logo

- The logo SVGs (`public/logo-{light,dark}.svg` + `favicon.svg`; rendered by `components/BrandLogo.tsx`) are the brand mark **"Stele"**: the basalt stele of the Code of Hammurabi on a 48×48
  lapis disc — a tall, round-topped slab crowned by the sun disc of Shamash with its rays, a band beneath the relief, and rows of cuneiform wedges pressed into the face below (the `<desc>` in each file says so). The light file is the lapis disc with a white stele and Babylonian-gold sun (`#2f3f94` / `#ffffff` / `#d9a52e`), the dark file the pale-lapis disc with a deep-indigo stele (`#93a4e2` / `#1b2459` / `#e2b54a`), and `favicon.svg` is a copy of the light one; check them in both colour schemes. Change the geometry in a vector editor,
  not by hand-editing path data. Restyle rule: keep aria-labels, roles, and real semantic elements stable — e2e and unit tests locate by role/name.

### Changelog rendering & UI wiring

Rendering & UI wiring:

- `pages/Changelog.tsx` (`/changelog`, authenticated, lazy) renders a list of release articles
  with version headings, dates and `MarkdownView` bodies in the viewer's language, and calls `markChangelogSeen()`
  on mount. **Bodies are authored EN+PL only, by policy** — a new shipped UI language does NOT
  add changelog fields; non-authored languages read the English body (`AUTHORED_LANGUAGES` in the
  page). `components/MarkdownView.tsx` (Typography + react-markdown + remark-gfm/gemoji, all
  lazy-chunk-only deps) is the one markdown renderer — reuse it for future markdown content. **Two variants** (`variant`, default `"document"`): `document` is plain react-markdown output
  (the changelog; real headings, plain links), `message` is for discussion bodies (Adder's chat bodies, ported; Hammurabi's comments and issues), where many user-written bodies share one page: headings render as BOLD PARAGRAPHS (50 comments cannot flood the heading
  outline; the page's own h1/h2/h3 outline stays), an image renders as a LINK to its address labelled by its alt text (the CSP is `img-src 'self' data:`, so a remote image would be
  blocked anyway; an unsafe `src` that react-markdown's default `urlTransform` blanks stays as plain alt text), and every link opens with `target="_blank" rel="noopener noreferrer"`. Neither variant
  renders raw HTML (no `rehype-raw`; never add it). The CSP is not changed. `MarkdownView.module.css` carries the `message` styling only. **Not ported: the emoji trio** (`EmojiTextarea`, `EmojiButton`, `EmojiPicker`, Lettuce's via Adder): it needs `emoji-mart`, `@emoji-mart/react` and `@emoji-mart/data` (a `package.json` and lockfile change, `dependencies.md`), so it comes with the first feature that needs an emoji picker; Adder's `dependencies.md` entry explains the no-CDN rule (`data` and `i18n` passed explicitly).
- `components/VersionStamp.tsx` shows `v<APP_VERSION> · <sha> · <time>`; its optional `to` prop
  renders it as a router link — the navbar footer instance links to `/changelog` (no dot on it any
  more), the Login/AuthCard instance deliberately stays plain (the route is behind auth). The
  "Changelog" entry is an `ACCOUNT_NAV` leaf rendered by the `UserMenu` and the command palette,
  not a sidebar item.
- The **"what's new" dot** is a brand-accent (`hammurabi`) Mantine `Indicator` around the header `UserMenu` trigger (it
  carries the `title="What's new"` e2e locates; the Changelog menu item shows a text badge
  instead of a second title, which would trip strict-mode locators while the menu is open), shown
  while localStorage `hammurabi.changelog` (`{seenVersion}`) differs from `APP_VERSION`. It's driven
  by `hooks/useChangelogSeen.ts` via `useSyncExternalStore` over a module-level listener set — do
  **not** replace that with a plain localStorage read in state: the shell renders before the
  page's mark-seen effect, so the dot would only clear on the next navigation. The seen-state is
  device-level (survives logout, like `hammurabi.lang`) and hand-rolls its guarded read/write, not
  `useStoredState` (that hook owns the `hammurabi.viewSettings.*` namespace).
- Tests: `pages/Changelog.test.tsx`, `changelog/entries.test.ts`, the dot/nav cases in
  `App.test.tsx`, the link/prefix cases in `components/VersionStamp.test.tsx`.

## Unsaved ADR drafts

The browser mounts the existing route tree through `createBrowserRouter`. The authenticated shell's `DraftGuardProvider` adapts
Lettuce's `useBlocker` confirmation and Flow's `beforeunload` handler. The provider owns one router blocker because the ADR document
and visited discussion tabs can all hold drafts at once. Create, document, expiry and discussion forms register while dirty. The unload
listener is attached only while the registry has drafts. Saves release their registration before navigating and local cancels confirm
only the changes they would discard. Discussion paging and the open-only filter protect entry edits and resolution notes while keeping
the composer mounted. If a refetch closes editing or posting, changed document and composer drafts stay available to copy or discard.

## Not yet ported (port, don't reinvent)

When a feature needs one of these, port the sibling's `web/` implementation and its
`web/CLAUDE.md` section wholesale:

- **Lettuce's chat-shaped pieces** (`~/Sources/lettuce/web/src/components/`): `EmojiPicker`/`EmojiButton`/`EmojiTextarea`, and
  `safeBackParam` (the open-redirect guard for any future `?back=` param), and `StatusPill`/`MetaStrip`/`FormFooter`/`ListToolbar`.
- **Covenant's catalog surface** (`covenant/web/src/`) — the filter-set/facets pattern
  (`hooks/useContractFilterState.ts` + `components/ContractFilterControls.tsx`) — not ported: the ADR list built its own filters (`hooks/useAdrListParams.ts`, the Filters panel, 0.5.0); the Teams/Users lists stay the
  `useRegistryListControls`/`Users.tsx` templates above.

## Storage keys, Web Locks and telemetry (reference)

- **localStorage:** `hammurabi.auth.*` (session), `hammurabi.viewSettings.*` (`useStoredState`), `hammurabi.lang`, `hammurabi.changelog`, `hammurabi.chunkReloadedAt` (the one-shot reload after a failed lazy chunk, `main.tsx`). **Web Lock:** `hammurabi.auth.refresh`. **CSS variables:** `--hammurabi-*`.
- **Telemetry panel** (`components/Telemetry*.tsx`, `hooks/useTelemetryPanel.ts`, `utils/telemetry*.ts`): the memory, storage, requests and jvm groups only (`GET /api/v1/telemetry/history?group=`), and one process role, `app` (`utils/telemetryRole.ts`). Flow's sync and derive sections are gone.
- **`components/TableDisclosure.tsx`** (Flow's `DailyTableDisclosure`) is the collapsible accessible-table twin of a chart; use it for every chart that carries data a screen-reader user needs.
- The Polish user-facing noun for Users is "Użytkownicy/czki" (EN "Users").
