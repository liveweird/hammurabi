import AxeBuilder from "@axe-core/playwright";
import { type APIRequestContext, expect, type Page, request as playwrightRequest, test as base } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { inflateRawSync } from "node:zlib";
import { BASE_URL, MAILPIT_URL } from "../playwright.config";

export { expect };

/**
 * Console messages a test may leave behind: the ALLOWLIST of the console-error guard below. Anything else the page logs at
 * `error` level, and every uncaught exception, fails the test that caused it. Keep an entry only for noise the app cannot avoid, with
 * the reason.
 */
const ALLOWED_CONSOLE_ERRORS: { pattern: RegExp; apiOnly: boolean; reason: string }[] = [
  {
    // The browser itself logs every 4xx subresource answer as an error. Specs that exercise a refusal on purpose (a 401 that triggers
    // the silent refresh, a 403, a 404 page) cause them; the status is the server's, asserted where it matters. Only an answer of the
    // app's own API qualifies: a 4xx for a static asset (a logo, a font, a chunk) is a real fault and still fails the test.
    pattern: /^Failed to load resource: the server responded with a status of 4\d\d\b/,
    apiOnly: true,
    reason: "an HTTP 4xx answer of /api/ that the app handles",
  },
];

/** Is `url` a request to the app's own API (same origin as the page under test, path under /api/)? */
function isOwnApi(url: string, pageUrl: string): boolean {
  try {
    const target = new URL(url);
    return target.origin === new URL(pageUrl).origin && target.pathname.startsWith("/api/");
  } catch {
    return false;
  }
}

/**
 * The suite's `test`: the plain Playwright one plus an automatic console-error guard. Every page of the test's browser context is
 * watched; a `console.error` outside ALLOWED_CONSOLE_ERRORS or an uncaught page exception (React crash, failed chunk, CSP
 * violation) fails the test at its end, listing each message. (Contexts a spec opens by hand are not watched.)
 */
export const test = base.extend<{ consoleGuard: void }>({
  consoleGuard: [
    async ({ context }, use) => {
      const problems: string[] = [];
      context.on("console", (message) => {
        if (message.type() !== "error") return;
        const text = message.text();
        const source = message.location().url;
        const pageUrl = message.page()?.url() ?? "";
        if (ALLOWED_CONSOLE_ERRORS.some(({ pattern, apiOnly }) => pattern.test(text) && (!apiOnly || isOwnApi(source, pageUrl)))) return;
        problems.push(`console.error: ${text.slice(0, 300)}`);
      });
      context.on("weberror", (webError) => {
        problems.push(`uncaught: ${webError.error().message.slice(0, 300)}`);
      });
      await use();
      expect(problems, "the page logged errors nobody allowed (ALLOWED_CONSOLE_ERRORS in tests/helpers.ts)").toEqual([]);
    },
    { auto: true },
  ],
});

/** The seeded bootstrap admin (V3) — the compose demo leaves its password unrotated. */
export const ADMIN = "admin@hammurabi.local";
const PASSWORD = "changeme";

/**
 * Navigate to a usable sign-in form. Any leftover session has to go first: while one exists
 * the app's RedirectIfAuthed bounces /login to the home page, so the form never renders and
 * a fill() waits out the whole test timeout.
 */
async function gotoSignInForm(page: Page): Promise<void> {
  if (page.url().startsWith("http")) {
    await page.evaluate(() => localStorage.clear());
    await page.goto("/login");
  } else {
    // A fresh page (about:blank) is a fresh browser context: there is no session to clear, so ONE
    // load of the SPA is enough — the clear-and-reload branch above cost a second one in every
    // login (~125 per run, ~95 of them on the axe sweeps).
    await page.goto("/login");
  }
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
}

/**
 * Sign in through the real login form. Use it where the credential or the form IS the subject (the first sign-in with a
 * generated password, a reset password, the language riding the login response); everything else takes `login`.
 */
export async function loginViaForm(page: Page, email = ADMIN, password = PASSWORD): Promise<void> {
  await gotoSignInForm(page);
  // Target by textbox role: getByLabel("Password") also matches the visibility-toggle button.
  await page.getByRole("textbox", { name: "Email" }).fill(email);
  await page.getByRole("textbox", { name: "Password" }).fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(accountMenu(page)).toBeVisible({ timeout: 15_000 });
}

/** The `/login` response fields the SPA persists (`storeSession` in `web/src/api/session.ts`). */
interface LoginBody {
  token: string;
  refreshToken: string;
  roles: string[];
  userId: number;
  disabledFeatures?: string[];
}

/**
 * The `localStorage` of a signed-in SPA: the keys `storeSession` writes, from the tokens the server just issued. The session identity
 * is left out on purpose: the SPA gives a session without one a fresh identity on first use, exactly as for an older session.
 */
function sessionEntries(body: LoginBody): [string, string][] {
  return [
    ["hammurabi.auth.token", body.token],
    ["hammurabi.auth.refreshToken", body.refreshToken],
    ["hammurabi.auth.roles", JSON.stringify(body.roles)],
    ["hammurabi.auth.userId", String(body.userId)],
    ["hammurabi.auth.disabledFeatures", JSON.stringify(body.disabledFeatures ?? [])],
  ];
}

/**
 * Sign in WITHOUT the form: the real `POST /api/v1/login` through the page's request context (so the tokens, the revocation
 * blocklist and any credential-revision check are the server's own), then the session written to `localStorage` exactly as the SPA
 * would persist it, then ONE load of `/`. The form costs a full SPA boot on `/login`, two fills, a click and a client-side redirect
 * per sign-in, and the suite signs in ~190 times. The session is minted per call: `signOut` revokes the pair
 * server-side, so one reused across tests would be a revoked one. A refused login (wrong password, an MFA challenge, a rate limit) or a
 * session the shell does not accept falls back to the real form, which then reports what is wrong. Pass credentials as a regular
 * user to read as one; the specs whose subject is a credential call `loginViaForm`.
 */
export async function login(page: Page, email = ADMIN, password = PASSWORD): Promise<void> {
  const minted = await page.request.post("/api/v1/login", { data: { email, password } });
  const body = minted.ok() ? ((await minted.json()) as Partial<LoginBody>) : undefined;
  if (body?.token === undefined || body.refreshToken === undefined || body.roles === undefined || body.userId === undefined) {
    return loginViaForm(page, email, password);
  }
  // `localStorage` needs a document on the app's origin; a fresh page is about:blank, and a static asset is the cheapest document
  // the origin serves (the SPA is not booted for it).
  if (!page.url().startsWith("http")) await page.goto("/favicon.svg");
  await page.evaluate((entries) => {
    localStorage.clear();
    for (const [key, value] of entries) localStorage.setItem(key, value);
  }, sessionEntries(body as LoginBody));
  await page.goto("/");
  try {
    await expect(accountMenu(page)).toBeVisible({ timeout: 15_000 });
  } catch {
    // Drift in the persisted keys costs one slow sign-in here, never a red spec that is not about signing in.
    await loginViaForm(page, email, password);
  }
}

/** The header account-menu trigger — visible only inside the authenticated shell (v1.19.0). */
export function accountMenu(page: Page) {
  return page.getByRole("button", { name: "Account menu" });
}

/** Sign out through the account menu (the former header Logout button). */
export async function signOut(page: Page): Promise<void> {
  await accountMenu(page).click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
}

/**
 * Delete a user from the Users list through its row menu (v1.19.0: the row actions sit under
 * an "Operations for <name>" kebab) — the cleanup step every throwaway-user spec ends with.
 * Filters the list to the name first, then waits for the DELETE to land.
 */
export async function deleteUserRow(page: Page, name: string): Promise<void> {
  await page.goto("/users");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, `Delete ${name}`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
}

/**
 * Create a throwaway user through the real UI (an admin must be signed in) and capture the
 * generated password from the one-time reveal modal. Never mutate seeded accounts — use this.
 * The id comes from the POST response; the password from the dialog after "Show password".
 */
export async function createUserViaUi(
  page: Page,
  namePrefix = "E2E User",
): Promise<{ id: number; name: string; email: string; password: string }> {
  const name = uniqueText(namePrefix);
  const email = emailFor(name);
  await page.goto("/users/new");
  await page.getByRole("textbox", { name: "Name" }).fill(name);
  await page.getByRole("textbox", { name: "Email" }).fill(email);
  const [created] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/users") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  const id: number = (await created.json()).id;
  const dialog = page.getByRole("dialog");
  // Masked as "*" until revealed — click the eye toggle first.
  await dialog.getByRole("button", { name: "Show password" }).click();
  await expect(dialog.getByRole("button", { name: "Hide password" })).toHaveAttribute("aria-pressed", "true");
  const password = (await dialog.locator("code").textContent()) ?? "";
  // Keep the credential out of assertion output: validate only the captured value's shape.
  expect(password.length > 0 && !/^\*+$/.test(password), "generated password is revealed").toBe(true);
  // Mantine renders both a header X and the footer button named Close.
  await dialog.getByRole("button", { name: "Close", exact: true }).last().click();
  await expect(page).toHaveURL(/\/users$/);
  return { id, name, email, password };
}

/** Collision-free text so specs never depend on absolute counts or clean state. */
export function uniqueText(prefix: string): string {
  return `${prefix}-${randomUUID().slice(0, 8)}`;
}

/** The login email a throwaway account named `name` gets: lower-cased, anything but letters, digits and hyphens folded to a hyphen. */
function emailFor(name: string): string {
  return `${name.toLowerCase().replace(/[^a-z0-9-]/g, "-")}@hammurabi.local`;
}

/** A unique, well-formed email nobody owns (for the specs that ask the server about an address, e.g. a password-reset request). */
export function uniqueEmail(prefix: string): string {
  return emailFor(uniqueText(prefix));
}

/** A UTC calendar day `days` from now, `YYYY-MM-DD` (the day the server judges an expiry by): a date that stays in the future however long the suite lives. */
export function utcDayFromNow(days: number): string {
  return new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);
}

/**
 * Ensure a list view's filter panel is expanded. Idempotent on purpose: the open/collapsed
 * state persists per view in localStorage (hammurabi.viewSettings.*), so within one test a
 * revisited page restores the panel open — a blind toggle click would close it again.
 */
export async function openFilters(page: Page): Promise<void> {
  // Anchored: with a filter on, the list also offers "Clear all filters" (and the toggle's name gains its count).
  const toggle = page.getByRole("button", { name: /^Filters/ });
  await expect(toggle).toBeVisible();
  if ((await toggle.getAttribute("aria-expanded")) !== "true") {
    await toggle.click();
  }
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
}

/**
 * Drive a list row's action through its "Operations for <name>" kebab menu (the Users rows
 * today; list rows carry interpolated item names, hence the open `string` union member).
 */
export async function rowOperation(
  page: Page,
  name: string,
  operation: "Edit" | "Delete" | (string & {}),
): Promise<void> {
  const trigger = page.getByRole("button", { name: `Operations for ${name}` });
  // A missing row fails HERE with its name, not as an anonymous click timeout at the end of the test budget.
  await expect(trigger).toBeVisible();
  // Ensure THIS row's menu actually opened: a previous row's still-fading dropdown treats
  // the first click as its outside-click and swallows it, leaving the WRONG menu mounted —
  // an unscoped menuitem click would then drive the other row's operation. Bounded: an
  // unbounded toPass() retries until the TEST budget is gone and reports the wrong step.
  await expect(async () => {
    if ((await trigger.getAttribute("aria-expanded")) !== "true") await trigger.click();
    expect(await trigger.getAttribute("aria-expanded")).toBe("true");
  }).toPass({ timeout: 10_000 });
  const dropdownId = await trigger.getAttribute("aria-controls");
  await page.locator(`[id="${dropdownId}"]`).getByRole("menuitem", { name: operation }).click();
}

/**
 * API-side seeding for specs whose subject is a PAGE, not the journey that creates its data (the accessibility sweep, the ADR specs):
 * one request context authenticated as `email` against the same stack. Every id it creates is the caller's to delete directly through
 * the same API — the Owns rule applies unchanged.
 */
export async function apiAs(email: string, password: string): Promise<{ api: APIRequestContext; userId: number }> {
  const anonymous = await playwrightRequest.newContext({ baseURL: BASE_URL });
  const login = await anonymous.post("/api/v1/login", { data: { email, password } });
  expect(login.ok(), await login.text()).toBeTruthy();
  const { token, userId } = (await login.json()) as { token: string; userId: number };
  await anonymous.dispose();
  const api = await playwrightRequest.newContext({ baseURL: BASE_URL, extraHTTPHeaders: { Authorization: `Bearer ${token}` } });
  return { api, userId };
}

/** `apiAs` the seeded admin. */
export async function apiAsAdmin(): Promise<{ api: APIRequestContext; userId: number }> {
  return apiAs(ADMIN, PASSWORD);
}

/**
 * Create a throwaway regular user through the admin API (no UI journey, no one-time reveal): the cast of the ADR specs. The name and
 * the email carry `e2e`; the caller deletes the user (`/api/v1/users/<id>`) in its teardown.
 */
export async function createUserViaApi(admin: APIRequestContext, namePrefix: string): Promise<{ id: number; name: string; email: string; password: string }> {
  const name = uniqueText(namePrefix);
  const email = emailFor(name);
  const password = `pw-${randomUUID()}`;
  const created = await admin.post("/api/v1/users", { data: { name, email, password, roles: [], language: "en" } });
  expect(created.status(), await created.text()).toBe(201);
  return { id: ((await created.json()) as { id: number }).id, name, email, password };
}

/** An ADR as the specs read it back from the API. */
export interface AdrSeed {
  id: number;
  key: string;
  name: string;
  revision: number;
}

/** Raise an ADR through the API as the signed-in `api` user (who becomes its Driver). Callers delete it (`/api/v1/adrs/<id>`) as the admin. */
export async function createAdrViaApi(api: APIRequestContext, body: Record<string, unknown>): Promise<AdrSeed> {
  const created = await api.post("/api/v1/adrs", { data: body });
  expect(created.status(), await created.text()).toBe(201);
  return (await created.json()) as AdrSeed;
}

/**
 * The body of a COMPLETE ADR (all four sections written, `approverId` as its Approver), ready to go to review and be activated; `fields`
 * lays over it (a `name`, a different `problem`, more `participants`, an `expiresOn`). The text is filler no spec asserts on.
 */
export function completeAdrBody(approverId: number, fields: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    problem: "Which queue do we run?",
    context: "The team already runs PostgreSQL.",
    decision: "Use a PostgreSQL-backed queue.",
    consequences: "No new infrastructure to run.",
    participants: [{ userId: approverId, role: "APPROVER" }],
    ...fields,
  };
}

/** The ADR's current revision, for the next guarded write (`X-Expected-Revision`). */
export async function adrRevision(api: APIRequestContext, id: number): Promise<number> {
  const response = await api.get(`/api/v1/adrs/${id}`);
  expect(response.ok(), await response.text()).toBeTruthy();
  return ((await response.json()) as { revision: number }).revision;
}

/** One lifecycle move through the API under the ADR's current revision. */
export async function transitionViaApi(api: APIRequestContext, id: number, to: string): Promise<void> {
  const moved = await api.post(`/api/v1/adrs/${id}/transitions`, { data: { to }, headers: { "X-Expected-Revision": String(await adrRevision(api, id)) } });
  expect(moved.status(), await moved.text()).toBe(200);
}

/**
 * Teardown through the API for the rows a test created, for a spec's `test.afterEach` — so a failing step leaves nothing
 * behind. `paths` are deleted IN THE GIVEN ORDER
 * (children first) through a FRESH admin session. EVERY path is
 * attempted; 204 and 404 pass, since the test body may already have deleted a row through the UI — so callers must register
 * only paths built from a create response's id (a mistyped path would also answer 404 and go unnoticed). Any other status is
 * collected and thrown once at the end, listing all of them. A tag category's DELETE is revision-guarded (`X-Expected-Revision`), so the
 * helper reads the category's current revision first (an already-deleted one sends 1 and answers 404, which passes).
 */
export async function deleteViaApi(paths: string[]): Promise<void> {
  if (paths.length === 0) return;
  const { api } = await apiAsAdmin();
  const failures: string[] = [];
  try {
    for (const path of paths) {
      const status = (await api.delete(path, { headers: await revisionGuard(api, path) })).status();
      if (status !== 204 && status !== 404) failures.push(`${path} -> ${status}`);
    }
  } finally {
    await api.dispose();
  }
  expect(failures, `teardown DELETEs that answered neither 204 nor 404`).toEqual([]);
}

/** The `X-Expected-Revision` header a revision-guarded teardown DELETE needs (only the tag categories' today), else none. */
async function revisionGuard(api: APIRequestContext, path: string): Promise<Record<string, string> | undefined> {
  const category = /^\/api\/v1\/tag-categories\/(\d+)$/.exec(path);
  if (!category) return undefined;
  const registry = (await (await api.get("/api/v1/tag-categories")).json()) as { items: { id: number; revision: number }[] };
  const revision = registry.items.find((item) => item.id === Number(category[1]))?.revision ?? 1;
  return { "X-Expected-Revision": String(revision) };
}

const AXE_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];

/**
 * An axe WCAG 2.0/2.1 A+AA scan of the open page (or of `include`, a selector), as one readable
 * line per violation with the offending nodes — `[]` when clean. No waivers, `color-contrast`
 * included: the theme's tokens are AA-tested in `web/src/theme.test.ts`, so a finding is fixed at
 * the token level, never by patching single elements.
 */
export async function axeViolations(page: Page, include?: string): Promise<{ id: string; impact: string | null | undefined; help: string; nodes: string[] }[]> {
  const builder = new AxeBuilder({ page }).withTags(AXE_TAGS);
  const results = await (include ? builder.include(include) : builder).analyze();
  return results.violations.map((v) => ({
    id: v.id,
    impact: v.impact,
    help: v.help,
    nodes: v.nodes.map((n) => n.target.join(" ")),
  }));
}

export type Scheme = "light" | "dark";

/** The scheme the page must actually render in — Mantine stamps it on <html> (the `auto` default follows Playwright's emulated `colorScheme`). */
export async function expectScheme(page: Page, scheme: Scheme): Promise<void> {
  await expect(page.locator("html")).toHaveAttribute("data-mantine-color-scheme", scheme);
}

/**
 * The plain text of the newest message to `to` whose subject `subject` accepts, or undefined while none has arrived. It asks Mailpit to
 * SEARCH by recipient (`/api/v1/search`) instead of reading the newest page of the whole catcher, so a busy stack's other mail (a long
 * nightly run, a shared catcher) can never push the awaited message out of view. Poll it with `expect.poll`.
 */
export async function mailText(to: string, subject: (subject: string) => boolean): Promise<string | undefined> {
  const query = new URLSearchParams({ query: `to:"${to}"`, limit: "50" });
  const found = await fetch(`${MAILPIT_URL}/api/v1/search?${query}`).then((r) => r.json());
  const msg = found.messages?.find(
    (m: { Subject?: string; To?: { Address: string }[] }) => m.To?.some((t) => t.Address === to) && subject(m.Subject ?? ""),
  );
  if (!msg) return undefined;
  return (await fetch(`${MAILPIT_URL}/api/v1/message/${msg.ID}`).then((r) => r.json())).Text as string;
}

/**
 * The text of every file in a ZIP, by name, with no dependency: the central directory (found from the end-of-central-directory record)
 * says where each entry sits and how big it is, which a streamed archive (the server's, written with data descriptors) only knows there.
 * Handles stored and deflated entries, which is all `java.util.zip` writes.
 */
export function unzipText(zip: Buffer): Map<string, string> {
  const END_OF_DIRECTORY = 0x06054b50;
  const CENTRAL_HEADER = 0x02014b50;
  const LOCAL_HEADER = 0x04034b50;
  let end = zip.length - 22;
  while (end >= 0 && zip.readUInt32LE(end) !== END_OF_DIRECTORY) end--;
  expect(end, "the archive has an end-of-central-directory record").toBeGreaterThanOrEqual(0);
  const count = zip.readUInt16LE(end + 10);
  let at = zip.readUInt32LE(end + 16);
  const files = new Map<string, string>();
  for (let n = 0; n < count; n++) {
    expect(zip.readUInt32LE(at)).toBe(CENTRAL_HEADER);
    const method = zip.readUInt16LE(at + 10);
    const size = zip.readUInt32LE(at + 20);
    const nameLength = zip.readUInt16LE(at + 28);
    const extraLength = zip.readUInt16LE(at + 30);
    const commentLength = zip.readUInt16LE(at + 32);
    const local = zip.readUInt32LE(at + 42);
    const name = zip.toString("utf8", at + 46, at + 46 + nameLength);
    expect(zip.readUInt32LE(local)).toBe(LOCAL_HEADER);
    const start = local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28);
    const data = zip.subarray(start, start + size);
    files.set(name, (method === 8 ? inflateRawSync(data) : data).toString("utf8"));
    at += 46 + nameLength + extraLength + commentLength;
  }
  return files;
}

/** The bytes of a finished Playwright download. */
export async function downloadBytes(download: { path(): Promise<string> }): Promise<Buffer> {
  return readFile(await download.path());
}
