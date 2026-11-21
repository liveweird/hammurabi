// Notifications (0.6.0): the bell shows what another person's write made, a click opens the ADR and marks the notice seen, the digest
// reaches the recipient's Mailpit, and the preferences page switches a kind off for the bell. The server's rules (who is told what, never
// the actor) are `NotificationsTest`; this asserts what a person SEES and receives.
// The digest test needs a stack whose digest is fast (compose passes NOTIFICATIONS_EMAIL_DELAY_SECONDS=0,
// NOTIFICATIONS_EMAIL_MIN_INTERVAL_SECONDS=0 and NOTIFICATIONS_SWEEP_INTERVAL_SECONDS=10 on; e2e/README.md "Run"); on the default
// 5-minute digest it fails with the message saying so.
// Owns: its throwaway users (unique `e2e-*` names) and the ADR they share, deleted through the API in afterEach. Notices are per recipient.
import type { APIRequestContext } from "@playwright/test";
import { apiAs, apiAsAdmin, createAdrViaApi, createUserViaApi, deleteViaApi, expect, login, mailText, test, transitionViaApi, uniqueText } from "./helpers";

type Person = Awaited<ReturnType<typeof createUserViaApi>>;

test.describe("Notifications", () => {
  let admin: APIRequestContext;
  let driver: Person;
  let recipient: Person;
  let driverApi: APIRequestContext;
  let adrId: number;
  let adrKey: string;
  let adrName: string;
  let cleanup: string[];

  test.beforeEach(async () => {
    ({ api: admin } = await apiAsAdmin());
    driver = await createUserViaApi(admin, "E2E Notifier");
    recipient = await createUserViaApi(admin, "E2E Recipient");
    ({ api: driverApi } = await apiAs(driver.email, driver.password));
    adrName = uniqueText("e2e-adr");
    // The Driver raises a complete ADR and names the recipient its Approver: that is the first notice.
    const adr = await createAdrViaApi(driverApi, {
      name: adrName,
      problem: "Which broker do we use?",
      context: "Several teams publish events.",
      decision: "Use the managed broker.",
      consequences: "A vendor dependency.",
      participants: [{ userId: recipient.id, role: "APPROVER" }],
    });
    adrId = adr.id;
    adrKey = adr.key;
    cleanup = [`/api/v1/adrs/${adrId}`, `/api/v1/users/${recipient.id}`, `/api/v1/users/${driver.id}`];
  });

  test.afterEach(async () => {
    await driverApi.dispose();
    await admin.dispose();
    await deleteViaApi(cleanup);
  });

  const bell = (page: import("@playwright/test").Page, unseen: number | string) =>
    page.getByRole("button", { name: `Notifications (${unseen} unseen)` });

  test("the bell shows a role another user gave you, and its notice opens the ADR and is marked seen", async ({ page }) => {
    await login(page, recipient.email, recipient.password);
    await expect(bell(page, 1)).toBeVisible();
    await bell(page, 1).click();
    const drawer = page.getByRole("dialog", { name: "Notifications" });
    const notice = drawer.getByRole("link", { name: new RegExp(`${driver.name} gave you a role on ${adrKey}: Approver`) });
    await expect(notice).toBeVisible();
    await expect(notice).toContainText(adrName);
    await notice.click();
    await expect(page).toHaveURL(new RegExp(`/adrs/${adrId}$`));
    await expect(page.getByRole("heading", { level: 2, name: adrName })).toBeVisible();
    await expect(bell(page, 0)).toBeVisible();
  });

  test("a transition of your ADR shows in the bell, and Mark all as seen clears the count", async ({ page }) => {
    await transitionViaApi(driverApi, adrId, "IN_REVIEW");
    await login(page, recipient.email, recipient.password);
    await expect(bell(page, 2)).toBeVisible();
    await bell(page, 2).click();
    const drawer = page.getByRole("dialog", { name: "Notifications" });
    await expect(drawer.getByRole("link", { name: new RegExp(`${driver.name} changed the status of ${adrKey} to In review`) })).toBeVisible();
    await expect(drawer.getByRole("link", { name: new RegExp(`gave you a role on ${adrKey}`) })).toBeVisible();
    await drawer.getByRole("button", { name: "Mark all as seen" }).click();
    await expect(drawer.getByRole("button", { name: "Mark all as seen" })).toHaveCount(0);
    await drawer.getByRole("button", { name: "Close" }).click();
    await expect(bell(page, 0)).toBeVisible();
  });

  test("a notice that stays unseen is mailed to the recipient in one digest", async () => {
    let text: string | undefined;
    await expect
      .poll(
        async () => {
          text = await mailText(recipient.email, (subject) => subject.startsWith("Hammurabi: "));
          return text;
        },
        {
          timeout: 60_000,
          intervals: [2_000],
          message: "no digest in Mailpit: start the stack with NOTIFICATIONS_EMAIL_DELAY_SECONDS=0, ...MIN_INTERVAL_SECONDS=0 and NOTIFICATIONS_SWEEP_INTERVAL_SECONDS=10",
        },
      )
      .toBeTruthy();
    expect(text).toContain(`${driver.name} made you Approver of ${adrKey}`);
    expect(text).toContain(`/adrs/${adrId}`);
    // The mail names the ADR by its key only: never its name or any of its text.
    expect(text).not.toContain(adrName);
    expect(text).not.toContain("Which broker");
  });

  test("switching a kind off on the preferences page stops its notices for the bell", async ({ page }) => {
    await login(page, recipient.email, recipient.password);
    await page.goto("/notification-preferences");
    await expect(page.getByRole("heading", { level: 2, name: "Notification preferences" })).toBeVisible();
    const statusSwitch = page.getByRole("switch", { name: "Bell: An ADR changes status" });
    await expect(statusSwitch).toBeChecked();
    // The Mantine switch hides its input behind a track: force the click on the input, as the feature-flag specs do.
    await statusSwitch.click({ force: true });
    await expect(statusSwitch).not.toBeChecked();
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(page.getByText("Notification preferences saved")).toBeVisible();
    await page.reload();
    await expect(page.getByRole("switch", { name: "Bell: An ADR changes status" })).not.toBeChecked();

    await transitionViaApi(driverApi, adrId, "IN_REVIEW");
    await page.goto("/");
    await expect(bell(page, 1)).toBeVisible();
    await bell(page, 1).click();
    const drawer = page.getByRole("dialog", { name: "Notifications" });
    await expect(drawer.getByRole("link", { name: new RegExp(`gave you a role on ${adrKey}`) })).toBeVisible();
    await expect(drawer.getByRole("link", { name: /changed the status/ })).toHaveCount(0);
  });
});
