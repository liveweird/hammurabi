// Markdown export in the browser (0.8.0): the ADR page's Export Markdown button downloads the ADR as an adr-tools document under an ASCII
// file name, and the list's Export (ZIP) button downloads exactly the ADRs the current filters select plus an index.md. The document's
// layout, the slug rules, the cap, the audit and the rate bucket are the server's (`AdrMarkdownTest`, `AdrExportTest`); this asserts what a
// person GETS. Owns: its throwaway user (a unique `e2e-*` email) and the ADRs it raises (unique `e2e-adr-*` names) - deleted through the
// API in afterEach (the ADRs first, then the user).
import type { APIRequestContext } from "@playwright/test";
import { apiAs, apiAsAdmin, createAdrViaApi, createUserViaApi, deleteViaApi, downloadBytes, expect, login, test, transitionViaApi, uniqueText, unzipText } from "./helpers";

type Person = Awaited<ReturnType<typeof createUserViaApi>>;

test.describe("ADR export", () => {
  let admin: APIRequestContext;
  let driver: Person;
  let driverApi: APIRequestContext;
  let cleanup: string[];

  test.beforeEach(async () => {
    ({ api: admin } = await apiAsAdmin());
    driver = await createUserViaApi(admin, "E2E Exporter");
    ({ api: driverApi } = await apiAs(driver.email, driver.password));
    cleanup = [`/api/v1/users/${driver.id}`];
  });

  test.afterEach(async () => {
    await driverApi.dispose();
    await admin.dispose();
    await deleteViaApi(cleanup);
  });

  async function raise(body: Record<string, unknown>): Promise<{ id: number; key: string }> {
    const adr = await createAdrViaApi(driverApi, { problem: "Which option do we take?", ...body });
    cleanup.unshift(`/api/v1/adrs/${adr.id}`);
    return adr;
  }

  test("the ADR page downloads the ADR as a Markdown file with an ASCII name, its content and its Driver", async ({ page }) => {
    const name = uniqueText("e2e-adr-Żółw");
    const adr = await raise({ name, decision: "We keep the turtle approach.", consequences: "It is slow and steady." });

    await login(page, driver.email, driver.password);
    await page.goto(`/adrs/${adr.id}`);
    await expect(page.getByRole("heading", { level: 2, name })).toBeVisible();
    const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "Export Markdown" }).click()]);

    // The name is the key and the slug, folded to ASCII (Ż, ó, ł became z, o, l).
    expect(download.suggestedFilename()).toMatch(new RegExp(`^${adr.key}-e2e-adr-zolw-[a-z0-9-]+\\.md$`));
    const text = (await downloadBytes(download)).toString("utf8");
    expect(text).toContain(`# ${Number(adr.key.slice(4))}. ${name}`);
    expect(text).toContain("## Status\n\nProblem raised");
    expect(text).toContain("## Decision\n\nWe keep the turtle approach.");
    expect(text).toContain("## Consequences\n\nIt is slow and steady.");
    expect(text).toContain(`- **Driver:** ${driver.name}`);
    expect(text).toMatch(/<!-- Exported from Hammurabi on \d{4}-\d{2}-\d{2} -->/);
  });

  test("the list downloads a ZIP of exactly the filtered ADRs, with an index in number order", async ({ page }) => {
    const marker = uniqueText("e2e-adr-zip");
    const first = await raise({ name: `${marker} first`, decision: "First." });
    const second = await raise({ name: `${marker} second`, decision: "Second." });
    const cancelled = await raise({ name: `${marker} cancelled`, decision: "Third." });
    await transitionViaApi(driverApi, cancelled.id, "CANCELLED");

    await login(page, driver.email, driver.password);
    await page.goto(`/adrs?q=${marker}&status=PROBLEM_RAISED`);
    await expect(page.getByRole("link", { name: `Open ${first.key}: ${marker} first` })).toBeVisible();
    await expect(page.getByRole("link", { name: `Open ${second.key}: ${marker} second` })).toBeVisible();
    await expect(page.getByRole("link", { name: new RegExp(`${marker} cancelled`) })).toHaveCount(0);
    const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "Export (ZIP)" }).click()]);

    expect(download.suggestedFilename()).toMatch(/^adr-export-\d{4}-\d{2}-\d{2}\.zip$/);
    const files = unzipText(await downloadBytes(download));
    const firstFile = `${first.key}-${marker}-first.md`;
    const secondFile = `${second.key}-${marker}-second.md`;
    // The cancelled ADR does not match the status filter, so it is not in the archive.
    expect([...files.keys()].sort()).toEqual([firstFile, "index.md", secondFile].sort());
    expect(files.get(firstFile)).toContain(`## Decision\n\nFirst.`);
    expect(files.get(secondFile)).toContain(`## Decision\n\nSecond.`);
    const index = files.get("index.md") ?? "";
    expect(index).toContain(`- [${first.key}: ${marker} first](${firstFile}) (Problem raised)`);
    expect(index.indexOf(first.key)).toBeLessThan(index.indexOf(second.key));
    expect(index).not.toContain("cancelled");
  });
});
