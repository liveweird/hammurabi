// Successor links in the browser: a Driver links a draft successor to an ACTIVE ADR from the picker, the Approver activates the
// successor once the predecessor's Approver accepted the link, and the predecessor reads SUPERSEDED with the banner and the history line;
// a successor's Approver with no role on the predecessor is refused in words until then; a link that would close a loop is refused in words. The rules (cycles, rival successors, the activation's one transaction, the locks) are the server's (`AdrSuccessorTest`,
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
    // The predecessor is still in force, with a note that a successor is only proposed until its Approver accepts it.
    await page.goto(`/adrs/${old.id}`);
    await expect(page.getByText("A successor is proposed")).toBeVisible();
    await expect(page.getByRole("button", { name: "Accept" })).toHaveCount(0);
    await expect(page.getByRole("region", { name: "Status" }).getByText("Active")).toBeVisible();
    await page.goto(`/adrs/${next.id}`);
    await page.getByRole("region", { name: "Status" }).getByRole("button", { name: "Send to review" }).click();
    await expect(page.getByRole("region", { name: "Status" }).getByText("In review")).toBeVisible();
    await signOut(page);

    // The predecessor's Approver accepts the link; only then is it on its way.
    await login(page, approver.email, approver.password);
    await page.goto(`/adrs/${old.id}`);
    await page.getByRole("button", { name: "Accept", exact: true }).click();
    await page.getByRole("dialog", { name: `Accept ${next.key}?` }).getByRole("button", { name: "Accept", exact: true }).click();
    await expect(page.getByText("A successor is on its way")).toBeVisible();
    await expect(page.getByRole("region", { name: "History" }).getByText(`Accepted ${next.key} as its successor`)).toBeVisible();

    // The Approver activates the successor: the predecessor is superseded in the same step.
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
    const banner = page.getByText("A successor is proposed").locator("xpath=ancestor::div[@role='status']");
    await expect(banner.getByRole("link", { name: `${parked.key} ${parkedName}` })).toBeVisible();
    // Detach asks first; keeping it changes nothing.
    await banner.getByRole("button", { name: "Detach" }).click();
    const dialog = page.getByRole("dialog", { name: `Detach ${parked.key}?` });
    await dialog.getByRole("button", { name: "Keep it" }).click();
    await expect(dialog).toHaveCount(0);
    await expect(banner).toBeVisible();
    await banner.getByRole("button", { name: "Detach" }).click();
    await page.getByRole("dialog", { name: `Detach ${parked.key}?` }).getByRole("button", { name: "Detach" }).click();
    await expect(page.getByText("A successor is proposed")).toHaveCount(0);
    await expect(page.getByRole("region", { name: "History" }).getByText(`Pending successor ${parked.key} detached`)).toBeVisible();
    await expect(page.getByRole("region", { name: "Status" }).getByText("Active")).toBeVisible();
    // The parked draft is free of the link, and its own history records the unlink.
    await page.goto(`/adrs/${parked.id}`);
    await expect(page.getByRole("region", { name: "Successor" }).getByText("Replaces no ADR.")).toBeVisible();
    await expect(page.getByRole("region", { name: "History" }).getByText(`No longer replaces ${old.key}`)).toBeVisible();
  });

  test("withdrawing the decision a draft was set to replace tells the draft's Driver and the withdrawn ADR stops listing it", async ({ page }) => {
    const oldName = uniqueText("e2e-adr-old");
    const old = await raise(oldName);
    await transitionViaApi(driverApi, old.id, "IN_REVIEW");
    await transitionViaApi(approverApi, old.id, "ACTIVE");
    // Another team's Driver sets a draft of their own to replace it (linking is the successor's write).
    const otherDriver = await createUserViaApi(admin, "E2E Other Driver");
    cleanup.push(`/api/v1/users/${otherDriver.id}`);
    const { api: otherDriverApi } = await apiAs(otherDriver.email, otherDriver.password);
    const nextName = uniqueText("e2e-adr-next");
    let next: { id: number; key: string };
    try {
      next = await createAdrViaApi(otherDriverApi, { name: nextName, problem: "Which queue do we run?" });
      cleanup.unshift(`/api/v1/adrs/${next.id}`);
      const linked = await otherDriverApi.put(`/api/v1/adrs/${next.id}/supersedes`, {
        data: { supersedesId: old.id },
        headers: { "X-Expected-Revision": String(await adrRevision(otherDriverApi, next.id)) },
      });
      expect(linked.status()).toBe(204);
    } finally {
      await otherDriverApi.dispose();
    }
    // The decision's Approver withdraws it.
    await transitionViaApi(approverApi, old.id, "WITHDRAWN");

    // The draft's Driver finds it in the bell and in the draft's history.
    await login(page, otherDriver.email, otherDriver.password);
    const bell = page.getByRole("button", { name: "Notifications (1 unseen)" });
    await expect(bell).toBeVisible();
    await bell.click();
    const sentence = `${old.key}, which ${next.key} was set to replace, is now Withdrawn`;
    await expect(page.getByRole("dialog", { name: "Notifications" }).getByRole("link", { name: new RegExp(sentence) })).toBeVisible();
    await page.goto(`/adrs/${next.id}`);
    await expect(page.getByRole("region", { name: "History" }).getByText(`${old.key}, which this ADR was set to replace, is now Withdrawn`)).toBeVisible();
    await expect(page.getByRole("region", { name: "Successor" }).getByText(/is no longer in force \(Withdrawn\)/)).toBeVisible();

    // The withdrawn ADR no longer names the draft as its pending successor.
    await page.goto(`/adrs/${old.id}`);
    await expect(page.getByRole("heading", { level: 2, name: oldName })).toBeVisible();
    await expect(page.getByRole("region", { name: "Status" }).getByText("Withdrawn")).toBeVisible();
    await expect(page.getByText("A successor is proposed")).toHaveCount(0);
    const successor = page.getByRole("region", { name: "Successor" });
    await expect(successor.getByText("Set to replace this ADR")).toHaveCount(0);
    await expect(successor.getByRole("link", { name: `${next.key} ${nextName}` })).toHaveCount(0);
  });

  test("the successor's Approver cannot activate over a decision in force until its Approver accepts the link", async ({ page }) => {
    const old = await raise(uniqueText("e2e-adr-old"));
    await transitionViaApi(driverApi, old.id, "IN_REVIEW");
    await transitionViaApi(approverApi, old.id, "ACTIVE");
    // A second team: its Driver and Approver hold no role on the decision in force.
    const otherDriver = await createUserViaApi(admin, "E2E Other Driver");
    const otherApprover = await createUserViaApi(admin, "E2E Other Approver");
    cleanup.push(`/api/v1/users/${otherApprover.id}`, `/api/v1/users/${otherDriver.id}`);
    const { api: otherDriverApi } = await apiAs(otherDriver.email, otherDriver.password);
    const { api: otherApproverApi } = await apiAs(otherApprover.email, otherApprover.password);
    let next: { id: number; key: string };
    try {
      next = await createAdrViaApi(otherDriverApi, {
        name: uniqueText("e2e-adr-next"),
        problem: "Which queue do we run?",
        context: "The team already runs PostgreSQL.",
        decision: "Use a PostgreSQL-backed queue.",
        consequences: "No new infrastructure to run.",
        participants: [{ userId: otherApprover.id, role: "APPROVER" }],
      });
      cleanup.unshift(`/api/v1/adrs/${next.id}`);
      const linked = await otherDriverApi.put(`/api/v1/adrs/${next.id}/supersedes`, {
        data: { supersedesId: old.id },
        headers: { "X-Expected-Revision": String(await adrRevision(otherDriverApi, next.id)) },
      });
      expect(linked.status()).toBe(204);
      await transitionViaApi(otherDriverApi, next.id, "IN_REVIEW");
    } finally {
      await otherDriverApi.dispose();
    }

    // Their Approver is told why it waits, and the activation is refused in words; the decision in force is untouched.
    await login(page, otherApprover.email, otherApprover.password);
    await page.goto(`/adrs/${next.id}`);
    const status = page.getByRole("region", { name: "Status" });
    await expect(status.getByText(`Waiting for ${old.key}'s Approver to accept this ADR as its successor`)).toBeVisible();
    await status.getByRole("button", { name: "Activate" }).click();
    await expect(status.getByRole("alert")).toContainText("has to accept it first");
    await expect(status.getByText("In review")).toBeVisible();

    // The decision's own Approver accepts; the same click then goes through.
    const accepted = await approverApi.post(`/api/v1/adrs/${old.id}/successor/accept`, { data: { successorId: next.id } });
    expect(accepted.status()).toBe(204);
    await page.reload();
    await page.getByRole("region", { name: "Status" }).getByRole("button", { name: "Activate" }).click();
    await expect(page.getByRole("region", { name: "Status" }).getByText("Active")).toBeVisible();
    await otherApproverApi.dispose();
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
