// The ADR lifecycle: a Driver raises an ADR, drafts it and sends it to review, the Approver activates it; a Driver cancels one after
// confirming; the Approver withdraws one in review. Owns: its throwaway users (unique `e2e-*` names) and the ADRs they raise — all
// deleted through the API in afterEach (the ADRs first, then the users).
import type { APIRequestContext } from "@playwright/test";
import { apiAs, apiAsAdmin, createAdrViaApi, createUserViaApi, deleteViaApi, expect, login, signOut, test, transitionViaApi, uniqueText } from "./helpers";

type Person = Awaited<ReturnType<typeof createUserViaApi>>;

test.describe("the ADR lifecycle", () => {
  let admin: APIRequestContext;
  let driver: Person;
  let approver: Person;
  let cleanup: string[];

  test.beforeEach(async () => {
    ({ api: admin } = await apiAsAdmin());
    driver = await createUserViaApi(admin, "E2E Driver");
    approver = await createUserViaApi(admin, "E2E Approver");
    cleanup = [`/api/v1/users/${approver.id}`, `/api/v1/users/${driver.id}`];
  });

  test.afterEach(async () => {
    await admin.dispose();
    // The ADRs go first (registered at the front), then the users.
    await deleteViaApi(cleanup);
  });

  test("a Driver raises an ADR, drafts it and sends it to review, and the Approver activates it", async ({ page }) => {
    const name = uniqueText("e2e-adr");
    await login(page, driver.email, driver.password);

    // Raise it with the problem and an Approver.
    await page.goto("/adrs/new");
    await expect(page.getByRole("heading", { name: "Raise an ADR" })).toBeVisible();
    await page.getByRole("textbox", { name: "Name" }).fill(name);
    await page.getByRole("textbox", { name: /^Problem/ }).fill("Which queue do we run?");
    await page.getByRole("combobox", { name: "Approver" }).fill(approver.name);
    await page.getByRole("option", { name: `${approver.name} (${approver.email})` }).click();
    const [created] = await Promise.all([
      page.waitForResponse((r) => r.url().endsWith("/api/v1/adrs") && r.request().method() === "POST" && r.status() === 201),
      page.getByRole("button", { name: "Create" }).click(),
    ]);
    const id = ((await created.json()) as { id: number }).id;
    cleanup.unshift(`/api/v1/adrs/${id}`);
    await expect(page).toHaveURL(new RegExp(`/adrs/${id}$`));
    await expect(page.getByRole("heading", { level: 2, name })).toBeVisible();
    const status = page.getByRole("region", { name: "Status" });
    await expect(status.getByText("Problem raised")).toBeVisible();

    // Draft it; entering review still lacks the context, decision and consequences.
    await status.getByRole("button", { name: "Move to draft" }).click();
    await expect(status.getByText("Solution draft")).toBeVisible();
    await expect(status.getByText("Still needed to continue: context, decision, consequences.")).toBeVisible();
    await status.getByRole("button", { name: "Send to review" }).click();
    await expect(status.getByRole("alert")).toContainText("Not ready for that move");
    await expect(status.getByText("Solution draft")).toBeVisible();

    // Write the missing content in the inline editor, then send it to review.
    await page.getByRole("button", { name: "Edit", exact: true }).click();
    await page.getByRole("textbox", { name: /^Context/ }).fill("The team already runs PostgreSQL.");
    await page.getByRole("textbox", { name: /^Decision/ }).fill("Use a PostgreSQL-backed queue.");
    await page.getByRole("textbox", { name: /^Consequences/ }).fill("No new infrastructure to run.");
    await page.getByRole("button", { name: "Save", exact: true }).click();
    // The editor closes once the save landed and the page holds the new revision; a move sent before that would race the save.
    await expect(page.getByRole("heading", { name: "Edit the ADR" })).toHaveCount(0);
    await expect(page.getByText("The team already runs PostgreSQL.")).toBeVisible();
    await status.getByRole("button", { name: "Send to review" }).click();
    await expect(status.getByText("In review")).toBeVisible();
    await signOut(page);

    // The Approver reads it, cannot edit the content, and activates it.
    await login(page, approver.email, approver.password);
    await page.goto(`/adrs/${id}`);
    await expect(page.getByRole("heading", { level: 2, name })).toBeVisible();
    await expect(page.getByRole("button", { name: "Edit", exact: true })).toHaveCount(0);
    await expect(status.getByRole("button", { name: "Send to review" })).toHaveCount(0);
    await status.getByRole("button", { name: "Activate" }).click();
    await expect(status.getByText("Active")).toBeVisible();
    await expect(status.getByText("This ADR is closed")).toHaveCount(0);

    // The decision is in force: its content is frozen for everyone.
    await expect(page.getByRole("button", { name: "Edit", exact: true })).toHaveCount(0);
    await expect(page.getByRole("region", { name: "History" }).getByText("Status changed from In review to Active")).toBeVisible();
  });

  test("a Driver cancels an ADR after confirming", async ({ page }) => {
    const { api } = await apiAs(driver.email, driver.password);
    const adr = await createAdrViaApi(api, { name: uniqueText("e2e-adr"), problem: "Do we need this?" });
    await api.dispose();
    cleanup.unshift(`/api/v1/adrs/${adr.id}`);

    await login(page, driver.email, driver.password);
    await page.goto(`/adrs/${adr.id}`);
    const status = page.getByRole("region", { name: "Status" });
    await status.getByRole("button", { name: "Cancel ADR" }).click();
    const dialog = page.getByRole("dialog", { name: "Cancel this ADR?" });
    await expect(dialog).toBeVisible();
    // Backing out sends nothing.
    await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(dialog).toHaveCount(0);
    await expect(status.getByText("Problem raised")).toBeVisible();

    await status.getByRole("button", { name: "Cancel ADR" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Cancel ADR" }).click();
    await expect(status.getByText("Cancelled")).toBeVisible();
    await expect(status.getByText("This ADR is closed: it is read-only.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Edit", exact: true })).toHaveCount(0);
    await expect(status.getByRole("button")).toHaveCount(0);
  });

  test("the Approver withdraws an ADR in review", async ({ page }) => {
    const { api } = await apiAs(driver.email, driver.password);
    const adr = await createAdrViaApi(api, {
      name: uniqueText("e2e-adr"),
      problem: "Should we adopt X?",
      context: "Context.",
      decision: "Adopt X.",
      consequences: "Some.",
      participants: [{ userId: approver.id, role: "APPROVER" }],
    });
    cleanup.unshift(`/api/v1/adrs/${adr.id}`);
    await transitionViaApi(api, adr.id, "IN_REVIEW");
    await api.dispose();

    await login(page, approver.email, approver.password);
    await page.goto(`/adrs/${adr.id}`);
    const status = page.getByRole("region", { name: "Status" });
    await expect(status.getByText("In review")).toBeVisible();
    await status.getByRole("button", { name: "Withdraw" }).click();
    await page.getByRole("dialog", { name: "Withdraw this ADR?" }).getByRole("button", { name: "Withdraw" }).click();
    await expect(status.getByText("Withdrawn")).toBeVisible();
    await expect(status.getByText("This ADR is closed: it is read-only.")).toBeVisible();
  });
});
