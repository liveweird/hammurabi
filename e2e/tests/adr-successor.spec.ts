// Successor links in the browser: a Driver links a draft successor to an ACTIVE ADR from the picker, the Approver activates the
// successor, and the predecessor reads SUPERSEDED with the banner and the history line; a link that would close a loop is refused in
// words. The rules (cycles, rival successors, the activation's one transaction, the locks) are the server's (`AdrSuccessorTest`,
// `SuccessorGraphTest`); this asserts what a person SEES. The predecessor's Driver also detaches a pending successor somebody else parked on it. Owns: its throwaway users (unique `e2e-*` names) and the ADRs they raise
// (unique `e2e-adr-*` names) - deleted through the API in afterEach (the ADRs first, then the users).
import type { APIRequestContext } from "@playwright/test";
import { adrRevision, apiAs, apiAsAdmin, createAdrViaApi, createUserViaApi, deleteViaApi, expect, login, signOut, test, transitionViaApi, uniqueText } from "./helpers";

type Person = Awaited<ReturnType<typeof createUserViaApi>>;

test.describe("ADR successors", () => {
  let admin: APIRequestContext;
  let driver: Person;
  let approver: Person;
  let driverApi: APIRequestContext;
  let approverApi: APIRequestContext;
  let cleanup: string[];

  test.beforeEach(async () => {
    ({ api: admin } = await apiAsAdmin());
    driver = await createUserViaApi(admin, "E2E Driver");
    approver = await createUserViaApi(admin, "E2E Approver");
    ({ api: driverApi } = await apiAs(driver.email, driver.password));
    ({ api: approverApi } = await apiAs(approver.email, approver.password));
    cleanup = [`/api/v1/users/${approver.id}`, `/api/v1/users/${driver.id}`];
  });

  test.afterEach(async () => {
    await approverApi.dispose();
    await driverApi.dispose();
    await admin.dispose();
    await deleteViaApi(cleanup);
  });

  /** An ADR the Driver raises with an Approver and complete content, so it can go to review and be activated. */
  async function raise(name: string): Promise<{ id: number; key: string }> {
    const adr = await createAdrViaApi(driverApi, {
      name,
      problem: "Which queue do we run?",
      context: "The team already runs PostgreSQL.",
      decision: "Use a PostgreSQL-backed queue.",
      consequences: "No new infrastructure to run.",
      participants: [{ userId: approver.id, role: "APPROVER" }],
    });
    cleanup.unshift(`/api/v1/adrs/${adr.id}`);
    return adr;
  }

  test("a draft successor is linked to an ACTIVE ADR and activated, and the predecessor shows SUPERSEDED with the banner", async ({ page }) => {
    const oldName = uniqueText("e2e-adr-old");
    const nextName = uniqueText("e2e-adr-next");
    const old = await raise(oldName);
    await transitionViaApi(driverApi, old.id, "IN_REVIEW");
    await transitionViaApi(approverApi, old.id, "ACTIVE");
    const next = await raise(nextName);

    // The Driver links the draft to the ACTIVE one from the picker (searching by name) and sends it to review.
    await login(page, driver.email, driver.password);
    await page.goto(`/adrs/${next.id}`);
    const successor = page.getByRole("region", { name: "Successor" });
    await expect(successor.getByText("Replaces no ADR.")).toBeVisible();
    await expect(successor.getByRole("button", { name: "Link" })).toBeDisabled();
    await successor.getByRole("combobox", { name: "This ADR supersedes…" }).fill(oldName);
    await page.getByRole("option", { name: `${old.key} ${oldName}` }).click();
    await successor.getByRole("button", { name: "Link" }).click();
    await expect(successor.getByRole("link", { name: `${old.key} ${oldName}` })).toBeVisible();
    await expect(successor.getByRole("button", { name: "Remove the link" })).toBeVisible();
    await expect(page.getByRole("region", { name: "History" }).getByText(`Set to replace ${old.key}`)).toBeVisible();
    // The predecessor is still in force, with a quiet note about the successor on its way.
    await page.goto(`/adrs/${old.id}`);
    await expect(page.getByText("A successor is on its way")).toBeVisible();
    await expect(page.getByRole("region", { name: "Status" }).getByText("Active")).toBeVisible();
    await page.goto(`/adrs/${next.id}`);
    await page.getByRole("region", { name: "Status" }).getByRole("button", { name: "Send to review" }).click();
    await expect(page.getByRole("region", { name: "Status" }).getByText("In review")).toBeVisible();
    await signOut(page);

    // The Approver activates the successor: the predecessor is superseded in the same step.
    await login(page, approver.email, approver.password);
    await page.goto(`/adrs/${next.id}`);
    await page.getByRole("region", { name: "Status" }).getByRole("button", { name: "Activate" }).click();
    await expect(page.getByRole("region", { name: "Status" }).getByText("Active")).toBeVisible();
    await expect(page.getByRole("region", { name: "History" }).getByText(`Activated and superseded ${old.key}`)).toBeVisible();

    await page.goto(`/adrs/${old.id}`);
    await expect(page.getByRole("heading", { level: 2, name: oldName })).toBeVisible();
    const banner = page.getByText("This ADR is superseded").locator("xpath=ancestor::div[@role='status']");
    await expect(banner.getByRole("link", { name: `${next.key} ${nextName}` })).toBeVisible();
    await expect(page.getByRole("region", { name: "Status" }).getByText("Superseded")).toBeVisible();
    await expect(page.getByRole("region", { name: "Status" }).getByText("This ADR is closed: it is read-only.")).toBeVisible();
    await expect(page.getByRole("region", { name: "History" }).getByText(`Superseded by ${next.key}`)).toBeVisible();
    await expect(page.getByRole("button", { name: "Edit tags" })).toHaveCount(0);
    // The banner's link leads to the successor.
    await banner.getByRole("link", { name: `${next.key} ${nextName}` }).click();
    await expect(page).toHaveURL(new RegExp(`/adrs/${next.id}$`));
    await expect(page.getByRole("region", { name: "Successor" }).getByRole("link", { name: `${old.key} ${oldName}` })).toBeVisible();
  });

  test("the predecessor's Driver detaches a successor somebody else parked on it, and both histories say so", async ({ page }) => {
    const oldName = uniqueText("e2e-adr-old");
    const old = await raise(oldName);
    await transitionViaApi(driverApi, old.id, "IN_REVIEW");
    await transitionViaApi(approverApi, old.id, "ACTIVE");
    // A stranger to the predecessor links their own draft to it: linking is the successor's write, whoever the predecessor belongs to.
    const squatter = await createUserViaApi(admin, "E2E Squatter");
    cleanup.push(`/api/v1/users/${squatter.id}`);
    const { api: squatterApi } = await apiAs(squatter.email, squatter.password);
    const parkedName = uniqueText("e2e-adr-parked");
    let parked: { id: number; key: string };
    try {
      parked = await createAdrViaApi(squatterApi, { name: parkedName, problem: "Parking a link." });
      cleanup.unshift(`/api/v1/adrs/${parked.id}`);
      const linked = await squatterApi.put(`/api/v1/adrs/${parked.id}/supersedes`, {
        data: { supersedesId: old.id },
        headers: { "X-Expected-Revision": String(await adrRevision(squatterApi, parked.id)) },
      });
      expect(linked.status()).toBe(204);
    } finally {
      await squatterApi.dispose();
    }

    await login(page, driver.email, driver.password);
    await page.goto(`/adrs/${old.id}`);
    const banner = page.getByText("A successor is on its way").locator("xpath=ancestor::div[@role='status']");
    await expect(banner.getByRole("link", { name: `${parked.key} ${parkedName}` })).toBeVisible();
    // Detach asks first; keeping it changes nothing.
    await banner.getByRole("button", { name: "Detach" }).click();
    const dialog = page.getByRole("dialog", { name: `Detach ${parked.key}?` });
    await dialog.getByRole("button", { name: "Keep it" }).click();
    await expect(dialog).toHaveCount(0);
    await expect(banner).toBeVisible();
    await banner.getByRole("button", { name: "Detach" }).click();
    await page.getByRole("dialog", { name: `Detach ${parked.key}?` }).getByRole("button", { name: "Detach" }).click();
    await expect(page.getByText("A successor is on its way")).toHaveCount(0);
    await expect(page.getByRole("region", { name: "History" }).getByText(`Pending successor ${parked.key} detached`)).toBeVisible();
    await expect(page.getByRole("region", { name: "Status" }).getByText("Active")).toBeVisible();
    // The parked draft is free of the link, and its own history records the unlink.
    await page.goto(`/adrs/${parked.id}`);
    await expect(page.getByRole("region", { name: "Successor" }).getByText("Replaces no ADR.")).toBeVisible();
    await expect(page.getByRole("region", { name: "History" }).getByText(`No longer replaces ${old.key}`)).toBeVisible();
  });

  test("a link that would close a loop of successors is refused in words and changes nothing", async ({ page }) => {
    const first = await raise(uniqueText("e2e-adr-a"));
    const second = await raise(uniqueText("e2e-adr-b"));
    // The second replaces the first (through the API); making the first replace the second would close the loop.
    const revision = ((await (await driverApi.get(`/api/v1/adrs/${second.id}`)).json()) as { revision: number }).revision;
    const linked = await driverApi.put(`/api/v1/adrs/${second.id}/supersedes`, {
      data: { supersedesId: first.id },
      headers: { "X-Expected-Revision": String(revision) },
    });
    expect(linked.status()).toBe(204);

    await login(page, driver.email, driver.password);
    await page.goto(`/adrs/${first.id}`);
    const successor = page.getByRole("region", { name: "Successor" });
    await expect(successor.getByText("Set to replace this ADR")).toBeVisible();
    await successor.getByRole("combobox", { name: "This ADR supersedes…" }).click();
    await page.getByRole("option", { name: new RegExp(`^${second.key} `) }).click();
    await successor.getByRole("button", { name: "Link" }).click();
    await expect(successor.getByRole("alert")).toContainText("close a loop of successors");
    await expect(successor.getByText("Replaces no ADR.")).toBeVisible();
  });
});
