// DACI permissions in the browser: a user with no role on an ADR reads it but is offered no controls; a Contributor edits the content
// but cannot move the ADR or change who takes part; the expiry date of a decision in force is the Approver's, not the Driver's. The matrix
// itself is the server's (`AdrAccessTest`); this asserts what a person SEES.
// Owns: its throwaway users (unique `e2e-*` names) and the ADR they share — deleted through the API in afterEach.
import type { APIRequestContext } from "@playwright/test";
import {
  apiAs,
  apiAsAdmin,
  completeAdrBody,
  createAdrViaApi,
  createUserViaApi,
  deleteViaApi,
  expect,
  login,
  signOut,
  test,
  transitionViaApi,
  uniqueText,
} from "./helpers";

type Person = Awaited<ReturnType<typeof createUserViaApi>>;

test.describe("ADR roles", () => {
  let admin: APIRequestContext;
  let driver: Person;
  let contributor: Person;
  let outsider: Person;
  let adrId: number;
  let adrName: string;
  let cleanup: string[];

  test.beforeEach(async () => {
    ({ api: admin } = await apiAsAdmin());
    driver = await createUserViaApi(admin, "E2E Driver");
    contributor = await createUserViaApi(admin, "E2E Contributor");
    outsider = await createUserViaApi(admin, "E2E Outsider");
    const { api } = await apiAs(driver.email, driver.password);
    adrName = uniqueText("e2e-adr");
    const adr = await createAdrViaApi(api, {
      name: adrName,
      problem: "Which cache do we use?",
      participants: [{ userId: contributor.id, role: "CONTRIBUTOR" }],
    });
    await api.dispose();
    adrId = adr.id;
    cleanup = [`/api/v1/adrs/${adrId}`, `/api/v1/users/${outsider.id}`, `/api/v1/users/${contributor.id}`, `/api/v1/users/${driver.id}`];
  });

  test.afterEach(async () => {
    await admin.dispose();
    await deleteViaApi(cleanup);
  });

  test("a user who is not a participant reads the ADR but sees no controls", async ({ page }) => {
    await login(page, outsider.email, outsider.password);
    await page.goto(`/adrs/${adrId}`);
    await expect(page.getByRole("heading", { level: 2, name: adrName })).toBeVisible();
    await expect(page.getByText("Which cache do we use?")).toBeVisible();
    // Everyone reads the DACI set and the history.
    await expect(page.getByRole("region", { name: "DACI" }).getByText(driver.name)).toBeVisible();
    await expect(page.getByRole("region", { name: "History" }).getByText("ADR raised")).toBeVisible();

    await expect(page.getByText("No status change is available to you.")).toBeVisible();
    for (const control of ["Move to draft", "Send to review", "Cancel ADR", "Activate", "Withdraw", "Edit", "Edit participants", "Edit the expiry date", "Delete"]) {
      await expect(page.getByRole("button", { name: control, exact: true }), `no "${control}" button`).toHaveCount(0);
    }
  });

  test("a Contributor edits the content but cannot move the ADR or change who takes part", async ({ page }) => {
    await login(page, contributor.email, contributor.password);
    await page.goto(`/adrs/${adrId}`);
    await expect(page.getByRole("heading", { level: 2, name: adrName })).toBeVisible();
    await expect(page.getByRole("region", { name: "DACI" }).getByText("You")).toBeVisible();

    await page.getByRole("button", { name: "Edit", exact: true }).click();
    await page.getByRole("textbox", { name: /^Context/ }).fill("Latency matters more than hit rate.");
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(page.getByText("Latency matters more than hit rate.")).toBeVisible();
    await expect(page.getByRole("region", { name: "History" }).getByText("Context changed")).toBeVisible();

    await expect(page.getByText("No status change is available to you.")).toBeVisible();
    for (const control of ["Move to draft", "Send to review", "Cancel ADR", "Edit participants", "Edit the expiry date"]) {
      await expect(page.getByRole("button", { name: control, exact: true }), `no "${control}" button`).toHaveCount(0);
    }
  });

  test("the expiry date of a decision in force is the Approver's to change, and the Driver is told", async ({ page }) => {
    const approver = await createUserViaApi(admin, "E2E Approver");
    cleanup.push(`/api/v1/users/${approver.id}`);
    const { api: driverApi } = await apiAs(driver.email, driver.password);
    const inForce = await createAdrViaApi(driverApi, completeAdrBody(approver.id, { name: uniqueText("e2e-adr") }));
    cleanup.unshift(`/api/v1/adrs/${inForce.id}`);
    await transitionViaApi(driverApi, inForce.id, "IN_REVIEW");
    await driverApi.dispose();
    const { api: approverApi } = await apiAs(approver.email, approver.password);
    await transitionViaApi(approverApi, inForce.id, "ACTIVE");
    await approverApi.dispose();

    // The Driver reads the decision in force but has no way to date it.
    await login(page, driver.email, driver.password);
    await page.goto(`/adrs/${inForce.id}`);
    await expect(page.getByRole("region", { name: "Status" }).getByText("Active")).toBeVisible();
    await expect(page.getByRole("button", { name: "Edit the expiry date", exact: true })).toHaveCount(0);
    await signOut(page);

    // The Approver sets the date.
    await login(page, approver.email, approver.password);
    await page.goto(`/adrs/${inForce.id}`);
    await page.getByRole("button", { name: "Edit the expiry date", exact: true }).click();
    await page.getByLabel("Expiry date").fill("2030-01-31");
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(page.getByText("2030-01-31").first()).toBeVisible();
    await signOut(page);

    // The Driver's bell says who changed it and to what.
    await login(page, driver.email, driver.password);
    await page.getByRole("button", { name: /^Notifications \(\d+ unseen\)$/ }).click();
    const drawer = page.getByRole("dialog", { name: "Notifications" });
    await expect(
      drawer.getByRole("link", { name: new RegExp(`${approver.name} changed the expiry date of ${inForce.key} to 2030-01-31`) }),
    ).toBeVisible();
  });
});
