// Find in the browser: the list's full-text search finds an ADR by a word in its decision, folding accents ("zolw" finds "Żółw") and
// highlighting the hit; the filters combine with the search and the whole view lives in the URL, so a reload and a fresh visit of the
// link show the same rows. The ranking, the weights, the validation, the deadline and every filter's boundary are the server's
// (`AdrSearchTest`, `AdrFiltersTest`); this asserts what a person SEES. Owns: its throwaway user (a unique `e2e-*` email) and the ADRs
// it raises (unique `e2e-adr-*` names) - deleted through the API in afterEach (the ADRs first, then the user).
import type { APIRequestContext } from "@playwright/test";
import { apiAs, apiAsAdmin, createAdrViaApi, createUserViaApi, deleteViaApi, expect, login, openFilters, test, transitionViaApi, uniqueText } from "./helpers";

type Person = Awaited<ReturnType<typeof createUserViaApi>>;

test.describe("ADR search", () => {
  let admin: APIRequestContext;
  let driver: Person;
  let driverApi: APIRequestContext;
  let cleanup: string[];

  test.beforeEach(async () => {
    ({ api: admin } = await apiAsAdmin());
    driver = await createUserViaApi(admin, "E2E Searcher");
    ({ api: driverApi } = await apiAs(driver.email, driver.password));
    cleanup = [`/api/v1/users/${driver.id}`];
  });

  test.afterEach(async () => {
    await driverApi.dispose();
    await admin.dispose();
    await deleteViaApi(cleanup);
  });

  /** A letters-and-digits word nobody else wrote (the search tokenises on punctuation, so no hyphen). */
  function uniqueWord(prefix: string): string {
    return `${prefix}${uniqueText("w").slice(2).replace(/-/g, "")}`;
  }

  async function raise(body: Record<string, unknown>): Promise<{ id: number; key: string }> {
    const adr = await createAdrViaApi(driverApi, { problem: "Which option do we take?", ...body });
    cleanup.unshift(`/api/v1/adrs/${adr.id}`);
    return adr;
  }

  test("full-text search finds an ADR by a word in its decision, ignoring accents, and highlights the hit", async ({ page }) => {
    const word = uniqueWord("Żółwik");
    const asciiWord = word.replace("Żółwik", "zolwik");
    const hitName = uniqueText("e2e-adr-hit");
    const missName = uniqueText("e2e-adr-miss");
    const hit = await raise({ name: hitName, decision: `We keep the ${word} approach because it is slow and steady.` });
    await raise({ name: missName, decision: "Something else entirely." });

    await login(page, driver.email, driver.password);
    await page.goto("/adrs");
    await page.getByRole("textbox", { name: "Search" }).fill(asciiWord);

    // The accent-folded query finds the row; the hit is marked in the snippet in the stored spelling; the unrelated ADR is gone.
    const row = page.getByRole("row").filter({ has: page.getByRole("link", { name: `Open ${hit.key}: ${hitName}` }) });
    await expect(row).toBeVisible();
    await expect(row.locator("mark", { hasText: word })).toBeVisible();
    await expect(page.getByRole("row").filter({ hasText: missName })).toHaveCount(0);
    await expect(page).toHaveURL(new RegExp(`text=${asciiWord}`));
    await expect(page.getByText("Sorted by relevance")).toBeVisible();

    // The URL is the state: a reload restores the box and the result.
    await page.reload();
    await expect(page.getByRole("textbox", { name: "Search" })).toHaveValue(asciiWord);
    await expect(row).toBeVisible();

    // A word nobody wrote finds nothing, and clearing the box brings the plain list back (the text leaves the URL).
    await page.getByRole("textbox", { name: "Search" }).fill(uniqueWord("nothing"));
    await expect(page.getByText("No ADRs match these filters")).toBeVisible();
    await page.getByRole("button", { name: "Clear search" }).click();
    await expect(page).not.toHaveURL(/text=/);
  });

  test("the filters combine with the search and the whole view is a link that round-trips", async ({ page }) => {
    const word = uniqueWord("combo");
    const draftName = uniqueText("e2e-adr-draft");
    const raisedName = uniqueText("e2e-adr-raised");
    const undatedName = uniqueText("e2e-adr-undated");
    const draft = await raise({ name: draftName, decision: `About ${word}`, expiresOn: "2031-01-01" });
    await transitionViaApi(driverApi, draft.id, "SOLUTION_DRAFT");
    await raise({ name: raisedName, decision: `About ${word}`, expiresOn: "2031-01-01" });
    await raise({ name: undatedName, decision: `About ${word}` });

    await login(page, driver.email, driver.password);
    await page.goto(`/adrs?text=${word}`);
    for (const name of [draftName, raisedName, undatedName]) await expect(page.getByRole("row").filter({ hasText: name })).toBeVisible();

    // Expires before drops the ADR with no date; the status filter then narrows to the draft.
    await openFilters(page);
    await page.getByLabel("Expires before").fill("2032-01-01");
    await expect(page.getByRole("row").filter({ hasText: undatedName })).toHaveCount(0);
    await expect(page.getByRole("row").filter({ hasText: raisedName })).toBeVisible();
    await page.getByRole("combobox", { name: "Status" }).click();
    await page.getByRole("option", { name: "Solution draft" }).click();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("row").filter({ hasText: raisedName })).toHaveCount(0);
    await expect(page.getByRole("row").filter({ hasText: draftName })).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`text=${word}`));
    await expect(page).toHaveURL(/expiresBefore=2032-01-01/);
    await expect(page).toHaveURL(/status=SOLUTION_DRAFT/);

    // A creation range wholly in the past empties the list; the filters panel counts its three active filters (the search box is its own).
    await page.getByLabel("Created to").fill("2000-01-01");
    await expect(page.getByText("No ADRs match these filters")).toBeVisible();
    await expect(page.getByRole("button", { name: /^Filters/ })).toContainText("3");

    // The link is the view: a fresh visit of the same URL shows the same filters and rows (and the cleared range shows the row again).
    const link = page.url();
    await page.goto("/adrs");
    await page.goto(link);
    await expect(page.getByRole("textbox", { name: "Search" })).toHaveValue(word);
    await expect(page.getByLabel("Created to")).toHaveValue("2000-01-01");
    await page.getByLabel("Created to").fill("");
    await expect(page.getByRole("row").filter({ hasText: draftName })).toBeVisible();
    await expect(page).not.toHaveURL(/createdTo=/);
  });
});
