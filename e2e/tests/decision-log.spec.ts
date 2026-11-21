// The decision log in the browser (0.9.0): the read-only overview page. A decision that became active shows under its tag and in "Recently
// decided", one that expires in five days in "Expiring soon", and an ADR in review shows as the Approver's own (highlighted, counted and
// linked to their review queue) while the Driver sees it plain. The caps, the horizon, the grouping rules and the ordering are the server's
// (`AdrOverviewTest`, `AdrOverviewCapsTest`) and the page's (`DecisionLog.test.tsx`); this asserts what a person SEES. Owns: its two
// throwaway users (a Driver and an Approver), its ADRs (unique `e2e-adr-*` names) and its throwaway tag category (a unique `E2E Log
// Category` with an `e2e-tag-log-*` tag; the registry is shared, append-only state and the seeded categories are never touched) - all
// deleted through the API in afterEach (the ADRs, then the category, then the users).
import type { APIRequestContext } from "@playwright/test";
import { apiAs, apiAsAdmin, createAdrViaApi, createUserViaApi, deleteViaApi, expect, login, test, transitionViaApi, uniqueText } from "./helpers";

type Person = Awaited<ReturnType<typeof createUserViaApi>>;

/** A UTC calendar day `days` from now, `YYYY-MM-DD` (the day the server judges expiry by). */
function utcDayFromNow(days: number): string {
  return new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);
}

test.describe("the decision log", () => {
  let admin: APIRequestContext;
  let driver: Person;
  let approver: Person;
  let driverApi: APIRequestContext;
  let approverApi: APIRequestContext;
  let cleanup: string[];

  test.beforeEach(async () => {
    ({ api: admin } = await apiAsAdmin());
    driver = await createUserViaApi(admin, "E2E Log Driver");
    approver = await createUserViaApi(admin, "E2E Log Approver");
    ({ api: driverApi } = await apiAs(driver.email, driver.password));
    ({ api: approverApi } = await apiAs(approver.email, approver.password));
    cleanup = [`/api/v1/users/${approver.id}`, `/api/v1/users/${driver.id}`];
  });

  test.afterEach(async () => {
    await driverApi.dispose();
    await approverApi.dispose();
    await admin.dispose();
    await deleteViaApi(cleanup);
  });

  /** Raises a complete ADR (Approver named) as the Driver and returns it; `fields` lays over the body. */
  async function raise(fields: Record<string, unknown> = {}) {
    const name = uniqueText("e2e-adr-log");
    const adr = await createAdrViaApi(driverApi, {
      name,
      problem: "What do we decide?",
      context: "The context.",
      decision: "The decision.",
      consequences: "The consequences.",
      participants: [{ userId: approver.id, role: "APPROVER" }],
      ...fields,
    });
    cleanup.unshift(`/api/v1/adrs/${adr.id}`);
    return adr;
  }

  test("an activated tagged ADR shows under its tag and in recently decided, and one expiring in five days shows in expiring soon", async ({ page }) => {
    const category = uniqueText("E2E Log Category");
    const tag = uniqueText("e2e-tag-log");
    const created = await admin.post("/api/v1/tag-categories", { data: { name: category, description: null, tags: [tag] } });
    expect(created.status(), await created.text()).toBe(201);
    cleanup.unshift(`/api/v1/tag-categories/${((await created.json()) as { id: number }).id}`);

    const tagged = await raise();
    const tagSet = await driverApi.put(`/api/v1/adrs/${tagged.id}/tags`, { data: { tags: [tag] }, headers: { "X-Expected-Revision": String(tagged.revision) } });
    expect(tagSet.status(), await tagSet.text()).toBe(204);
    const expiring = await raise({ expiresOn: utcDayFromNow(5) });
    for (const adr of [tagged, expiring]) {
      await transitionViaApi(driverApi, adr.id, "IN_REVIEW");
      await transitionViaApi(approverApi, adr.id, "ACTIVE");
    }

    await login(page, driver.email, driver.password);
    await page.getByRole("link", { name: "Decision log", exact: true }).click();
    await expect(page).toHaveURL(/\/decision-log$/);
    await expect(page.getByRole("heading", { level: 2, name: "Decision log" })).toBeVisible();

    // In force by tag: the ADR sits under its category and tag.
    const inForce = page.getByRole("region", { name: "In force by tag" });
    await expect(inForce.getByRole("heading", { level: 4, name: category })).toBeVisible();
    await expect(inForce.getByRole("heading", { level: 5, name: tag })).toBeVisible();
    const taggedList = inForce.getByRole("list", { name: `${category}: ${tag}` });
    await expect(taggedList.getByRole("link", { name: new RegExp(`${tagged.key}\\s*${tagged.name}`) })).toBeVisible();

    // Recently decided: both activations, newest first (the expiring one was activated last).
    const recent = page.getByRole("region", { name: "Recently decided" });
    await expect(recent.getByRole("link", { name: new RegExp(`${tagged.name}`) })).toBeVisible();
    await expect(recent.getByRole("link", { name: new RegExp(`${expiring.name}`) })).toBeVisible();

    // Expiring soon: the ADR dated in five days, with its last day, not marked overdue.
    const soon = page.getByRole("region", { name: "Expiring soon" });
    const row = soon.getByRole("listitem").filter({ hasText: expiring.name });
    await expect(row).toBeVisible();
    await expect(row.getByText(`last day ${utcDayFromNow(5)}`)).toBeVisible();
    await expect(row.getByText("Overdue")).toHaveCount(0);
    await expect(soon.getByRole("link", { name: new RegExp(tagged.name) })).toHaveCount(0);

    // A row opens its ADR.
    await recent.getByRole("link", { name: new RegExp(`${tagged.name}`) }).click();
    await expect(page).toHaveURL(new RegExp(`/adrs/${tagged.id}$`));
    await expect(page.getByRole("heading", { level: 2, name: tagged.name })).toBeVisible();
  });

  test("an ADR in review is the Approver's own - highlighted, counted and linked to their queue - and plain to the Driver", async ({ page }) => {
    const review = await raise();
    await transitionViaApi(driverApi, review.id, "IN_REVIEW");

    await login(page, approver.email, approver.password);
    await page.goto("/decision-log");
    const awaiting = page.getByRole("region", { name: "Awaiting decision" });
    const row = awaiting.getByRole("listitem").filter({ hasText: review.name });
    await expect(row).toBeVisible();
    await expect(row.getByText("Needs your decision")).toBeVisible();
    await expect(row.getByText(`Approver: ${approver.name}`)).toBeVisible();
    await expect(awaiting.getByRole("link", { name: "1 ADR is waiting for your decision" })).toBeVisible();

    // The count leads to the Approver's own review queue in the ADR list.
    await awaiting.getByRole("link", { name: "1 ADR is waiting for your decision" }).click();
    await expect(page).toHaveURL(/\/adrs\?status=IN_REVIEW&mine=1&role=APPROVER$/);
    await expect(page.getByRole("link", { name: `Open ${review.key}: ${review.name}` })).toBeVisible();

    // The Driver sees the same ADR waiting, but not as theirs to decide.
    await login(page, driver.email, driver.password);
    await page.goto("/decision-log");
    const driversRow = page.getByRole("region", { name: "Awaiting decision" }).getByRole("listitem").filter({ hasText: review.name });
    await expect(driversRow).toBeVisible();
    await expect(driversRow.getByText("Needs your decision")).toHaveCount(0);
    await expect(page.getByRole("link", { name: /waiting for your decision/ })).toHaveCount(0);
  });
});
