// Send back and withdraw (0.7.0): the Approver returns an ADR in review to the draft with a reason, the Driver edits and resubmits, the
// Approver activates it and later withdraws the decision with a reason; both reasons are in the History. A Contributor (and the Driver)
// cannot withdraw a decision in force: no button, and the API answers 403. The server's rules (who may, the lifecycle map, notices) are
// `AdrSendBackWithdrawTest`; this asserts what a person SEES and clicks.
// Owns: its throwaway users (unique `e2e-*` names) and the ADRs they raise, deleted through the API in afterEach (the ADRs first).
import type { APIRequestContext } from "@playwright/test";
import { apiAs, apiAsAdmin, completeAdrBody, createAdrViaApi, createUserViaApi, deleteViaApi, expect, login, signOut, test, transitionViaApi, uniqueText } from "./helpers";

type Person = Awaited<ReturnType<typeof createUserViaApi>>;

test.describe("sending an ADR back and withdrawing a decision", () => {
  let admin: APIRequestContext;
  let driver: Person;
  let approver: Person;
  let contributor: Person;
  let cleanup: string[];

  test.beforeEach(async () => {
    ({ api: admin } = await apiAsAdmin());
    driver = await createUserViaApi(admin, "E2E Driver");
    approver = await createUserViaApi(admin, "E2E Approver");
    contributor = await createUserViaApi(admin, "E2E Contributor");
    cleanup = [`/api/v1/users/${contributor.id}`, `/api/v1/users/${approver.id}`, `/api/v1/users/${driver.id}`];
  });

  test.afterEach(async () => {
    await admin.dispose();
    await deleteViaApi(cleanup);
  });

  test("the Approver sends an ADR back with a reason, the Driver resubmits it, and the Approver activates and then withdraws it", async ({ page }) => {
    const { api: driverApi } = await apiAs(driver.email, driver.password);
    const adr = await createAdrViaApi(driverApi, completeAdrBody(approver.id, { name: uniqueText("e2e-adr") }));
    cleanup.unshift(`/api/v1/adrs/${adr.id}`);
    await transitionViaApi(driverApi, adr.id, "IN_REVIEW");
    await driverApi.dispose();

    // The Approver returns it for rework, with a reason.
    await login(page, approver.email, approver.password);
    await page.goto(`/adrs/${adr.id}`);
    const status = page.getByRole("region", { name: "Status" });
    await expect(status.getByText("In review")).toBeVisible();
    await status.getByRole("button", { name: "Send back to draft" }).click();
    const sendBack = page.getByRole("dialog", { name: "Send this ADR back to draft?" });
    await sendBack.getByRole("textbox", { name: "Reason (optional)" }).fill("Add the cost of running it.");
    await sendBack.getByRole("button", { name: "Send back to draft" }).click();
    await expect(status.getByText("Solution draft")).toBeVisible();
    await expect(status.getByRole("button", { name: "Send back to draft" })).toHaveCount(0);
    await signOut(page);

    // The Driver is told, sees the reason in the History, edits the content and sends it to review again.
    await login(page, driver.email, driver.password);
    await page.getByRole("button", { name: "Notifications (1 unseen)" }).click();
    await expect(page.getByRole("dialog", { name: "Notifications" }).getByRole("link", { name: new RegExp(`${approver.name} sent ${adr.key} back to draft`) })).toBeVisible();
    await page.keyboard.press("Escape");
    await page.goto(`/adrs/${adr.id}`);
    const history = page.getByRole("region", { name: "History" });
    await expect(history.getByText("Sent back to draft")).toBeVisible();
    await expect(history.getByText("Reason: Add the cost of running it.")).toBeVisible();
    await page.getByRole("button", { name: "Edit", exact: true }).click();
    await page.getByRole("textbox", { name: /^Consequences/ }).fill("One more thing to operate, about 200 EUR a month.");
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Edit the ADR" })).toHaveCount(0);
    await expect(page.getByText("about 200 EUR a month.")).toBeVisible();
    await status.getByRole("button", { name: "Send to review" }).click();
    await expect(status.getByText("In review")).toBeVisible();
    await signOut(page);

    // The Approver activates it, then revokes the decision with a reason (a red button, a confirmation first).
    await login(page, approver.email, approver.password);
    await page.goto(`/adrs/${adr.id}`);
    await status.getByRole("button", { name: "Activate" }).click();
    await expect(status.getByText("Active", { exact: true })).toBeVisible();
    await status.getByRole("button", { name: "Withdraw decision" }).click();
    const withdraw = page.getByRole("dialog", { name: "Withdraw this decision?" });
    await expect(withdraw).toBeVisible();
    // Backing out sends nothing.
    await withdraw.getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(withdraw).toHaveCount(0);
    await expect(status.getByText("Active", { exact: true })).toBeVisible();
    await status.getByRole("button", { name: "Withdraw decision" }).click();
    await withdraw.getByRole("textbox", { name: "Reason (optional)" }).fill("The platform team now provides the cache.");
    await withdraw.getByRole("button", { name: "Withdraw decision" }).click();
    await expect(status.getByText("Withdrawn")).toBeVisible();
    await expect(status.getByText("This ADR is closed: it is read-only.")).toBeVisible();

    // Both reasons are in the History.
    await expect(history.getByText("Decision withdrawn")).toBeVisible();
    await expect(history.getByText("Reason: The platform team now provides the cache.")).toBeVisible();
    await expect(history.getByText("Reason: Add the cost of running it.")).toBeVisible();
  });

  test("a Contributor cannot withdraw a decision in force: no button, and the API answers 403", async ({ page }) => {
    const { api: driverApi } = await apiAs(driver.email, driver.password);
    const adr = await createAdrViaApi(
      driverApi,
      completeAdrBody(approver.id, {
        name: uniqueText("e2e-adr"),
        participants: [
          { userId: approver.id, role: "APPROVER" },
          { userId: contributor.id, role: "CONTRIBUTOR" },
        ],
      }),
    );
    cleanup.unshift(`/api/v1/adrs/${adr.id}`);
    await transitionViaApi(driverApi, adr.id, "IN_REVIEW");
    const { api: approverApi } = await apiAs(approver.email, approver.password);
    await transitionViaApi(approverApi, adr.id, "ACTIVE");
    await approverApi.dispose();
    const revision = (await (await driverApi.get(`/api/v1/adrs/${adr.id}`)).json()) as { revision: number };

    await login(page, contributor.email, contributor.password);
    await page.goto(`/adrs/${adr.id}`);
    const status = page.getByRole("region", { name: "Status" });
    await expect(status.getByText("Active", { exact: true })).toBeVisible();
    await expect(status.getByRole("button")).toHaveCount(0);
    await expect(status.getByText("No status change is available to you.")).toBeVisible();

    // The server refuses it all the same: the Contributor, and the Driver (who drives, the Approver decides).
    const { api: contributorApi } = await apiAs(contributor.email, contributor.password);
    for (const api of [contributorApi, driverApi]) {
      const refused = await api.post(`/api/v1/adrs/${adr.id}/transitions`, {
        data: { to: "WITHDRAWN", reason: "not mine to withdraw" },
        headers: { "X-Expected-Revision": String(revision.revision) },
      });
      expect(refused.status(), await refused.text()).toBe(403);
    }
    await contributorApi.dispose();
    await driverApi.dispose();
    await page.reload();
    await expect(status.getByText("Active", { exact: true })).toBeVisible();
  });

  test("a Driver cannot take the Approver seat of a decision in force to withdraw it: the picker is locked and the API answers 403", async ({ page }) => {
    const { api: driverApi } = await apiAs(driver.email, driver.password);
    const adr = await createAdrViaApi(driverApi, completeAdrBody(approver.id, { name: uniqueText("e2e-adr") }));
    cleanup.unshift(`/api/v1/adrs/${adr.id}`);
    await transitionViaApi(driverApi, adr.id, "IN_REVIEW");
    const { api: approverApi } = await apiAs(approver.email, approver.password);
    await transitionViaApi(approverApi, adr.id, "ACTIVE");
    await approverApi.dispose();

    await login(page, driver.email, driver.password);
    await page.goto(`/adrs/${adr.id}`);
    await page.getByRole("button", { name: "Edit participants" }).click();
    await expect(page.getByRole("combobox", { name: "Approver" })).toBeDisabled();
    await expect(page.getByText(/only the Approver or an administrator can change this/)).toBeVisible();

    // The server refuses the two-step all the same: the Driver names themselves Approver (handing the Driver seat to the Contributor).
    const current = (await (await driverApi.get(`/api/v1/adrs/${adr.id}`)).json()) as { revision: number };
    const takeSeat = await driverApi.put(`/api/v1/adrs/${adr.id}/participants`, {
      data: { participants: [{ userId: driver.id, role: "APPROVER" }, { userId: contributor.id, role: "DRIVER" }] },
      headers: { "X-Expected-Revision": String(current.revision) },
    });
    expect(takeSeat.status(), await takeSeat.text()).toBe(403);
    await driverApi.dispose();
  });
});
