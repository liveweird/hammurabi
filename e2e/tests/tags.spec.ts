// The tag registry and the ADR tags in the browser: an ADMIN curates a category and its tags, a Driver tags an ADR from the grouped picker,
// the list's tag filter finds it, and the API refuses a tag the registry does not hold; a regular user reads the registry through the API
// but has neither the page nor the writes. The rules (normalisation, one category per tag, retired tags, verdicts) are the server's
// (`TagCategoryTest`, `AdrTagsTest`); this asserts what a person SEES. Owns: its throwaway users and ADR (unique `e2e-*` names) and its
// throwaway category with `e2e-tag-*` tags (the registry is shared, append-only state: it never touches the seeded Domain / Scope
// categories) - all deleted through the API in afterEach (the ADR, then the category, then the users).
import type { APIRequestContext } from "@playwright/test";
import { apiAs, apiAsAdmin, createAdrViaApi, createUserViaApi, deleteViaApi, expect, login, test, uniqueText } from "./helpers";

type Person = Awaited<ReturnType<typeof createUserViaApi>>;

test.describe("the tag registry and the ADR tags", () => {
  let admin: APIRequestContext;
  let driver: Person;
  let cleanup: string[];

  test.beforeEach(async () => {
    ({ api: admin } = await apiAsAdmin());
    driver = await createUserViaApi(admin, "E2E Tagger");
    cleanup = [`/api/v1/users/${driver.id}`];
  });

  test.afterEach(async () => {
    await admin.dispose();
    await deleteViaApi(cleanup);
  });

  test("an admin curates a category, a Driver tags an ADR with it, the list filter finds it and the API refuses an unknown tag", async ({ page }) => {
    const category = uniqueText("E2E Category");
    const tagA = uniqueText("e2e-tag-a");
    const tagB = uniqueText("e2e-tag-b");
    const name = uniqueText("e2e-adr");

    // The admin creates the category with two tags: the tag field takes Enter (or a comma) per tag and shows each folded.
    await login(page);
    await page.goto("/tags");
    await expect(page.getByRole("heading", { level: 2, name: "Tags" })).toBeVisible();
    await page.getByRole("button", { name: "New category" }).click();
    const dialog = page.getByRole("dialog", { name: "New tag category" });
    await dialog.getByLabel("Name").fill(category);
    await dialog.getByLabel("Description").fill("Playwright's own category");
    await dialog.getByRole("combobox", { name: "Tags" }).fill(tagA);
    await page.keyboard.press("Enter");
    await dialog.getByRole("combobox", { name: "Tags" }).fill(tagB.toUpperCase());
    await page.keyboard.press("Enter");
    const [created] = await Promise.all([
      page.waitForResponse((r) => r.url().endsWith("/api/v1/tag-categories") && r.request().method() === "POST" && r.status() === 201),
      dialog.getByRole("button", { name: "Create" }).click(),
    ]);
    cleanup.unshift(`/api/v1/tag-categories/${((await created.json()) as { id: number }).id}`);
    const row = page.getByRole("row").filter({ hasText: category });
    await expect(row.getByText(tagA, { exact: true })).toBeVisible();
    await expect(row.getByText(tagB, { exact: true })).toBeVisible();

    // A Driver raises an ADR and tags it from the picker, which groups the registry by category.
    const { api: driverApi } = await apiAs(driver.email, driver.password);
    const adr = await createAdrViaApi(driverApi, { name, problem: "Which tags do we need?" });
    cleanup.unshift(`/api/v1/adrs/${adr.id}`);
    await login(page, driver.email, driver.password);
    await page.goto(`/adrs/${adr.id}`);
    const tags = page.getByRole("region", { name: "Tags" });
    await expect(tags.getByText("No tags")).toBeVisible();
    await tags.getByRole("button", { name: "Edit tags" }).click();
    await tags.getByRole("combobox", { name: "Tags" }).click();
    await page.getByRole("option", { name: tagA, exact: true }).click();
    // The open list of options covers the buttons below the picker; Escape closes it (a multi-select stays open to pick several).
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Save tags" }).click();
    await expect(tags.getByText(tagA, { exact: true })).toBeVisible();
    await expect(tags.getByRole("button", { name: "Edit tags" })).toBeVisible();
    await expect(page.getByRole("region", { name: "History" }).getByText("Tags changed")).toBeVisible();
    await expect(page.getByRole("region", { name: "History" }).getByText(`Added: ${tagA}`)).toBeVisible();

    // The list's tag filter finds it: the picker writes the tag into the URL and the pill shows in the row.
    await page.goto("/adrs");
    await page.getByRole("button", { name: "Filters" }).click();
    await page.getByRole("combobox", { name: "Tags" }).click();
    await page.getByRole("option", { name: tagA, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`tag=${tagA}`));
    const listed = page.getByRole("row").filter({ hasText: name });
    await expect(listed).toBeVisible();
    await expect(listed.getByText(tagA, { exact: true })).toBeVisible();
    // Asking for the category's OTHER tag lists no row of this ADR.
    await page.goto(`/adrs?tag=${tagB}`);
    await expect(page.getByRole("row").filter({ hasText: name })).toHaveCount(0);

    // The API is strict: a tag the registry does not hold is a 400 and changes nothing.
    const revision = ((await (await driverApi.get(`/api/v1/adrs/${adr.id}`)).json()) as { revision: number }).revision;
    const refused = await driverApi.put(`/api/v1/adrs/${adr.id}/tags`, {
      data: { tags: [tagA, uniqueText("e2e-unregistered")] },
      headers: { "X-Expected-Revision": String(revision) },
    });
    expect(refused.status()).toBe(400);
    const after = (await (await driverApi.get(`/api/v1/adrs/${adr.id}`)).json()) as { revision: number; tags: { name: string }[] };
    expect(after.tags.map((tag) => tag.name)).toEqual([tagA]);
    expect(after.revision).toBe(revision);
    await driverApi.dispose();
  });

  test("a regular user reads the registry through the API but has no Tags page and no writes", async ({ page }) => {
    await login(page, driver.email, driver.password);
    await expect(page.getByRole("link", { name: "Teams" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Tags" })).toHaveCount(0);
    await page.goto("/tags");
    await expect(page).toHaveURL(/\/$/);

    const { api } = await apiAs(driver.email, driver.password);
    const read = await api.get("/api/v1/tag-categories");
    expect(read.status()).toBe(200);
    const registry = (await read.json()) as { items: { name: string }[] };
    expect(registry.items.map((item) => item.name)).toEqual(expect.arrayContaining(["Domain", "Scope"]));
    const write = await api.post("/api/v1/tag-categories", { data: { name: uniqueText("e2e-nope"), tags: [uniqueText("e2e-nope")] } });
    expect(write.status()).toBe(403);
    await api.dispose();
  });
});
