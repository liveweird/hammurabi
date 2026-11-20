// The ADR history: every change is an event, rendered newest first as localized text with who did it, ten to a page. The changes are
// made through the API (the subject is the rendering); the reader is the Driver in the browser. Owns: its throwaway users (unique
// `e2e-*` names) and the ADRs they hold — deleted through the API in afterEach.
import type { APIRequestContext } from "@playwright/test";
import { adrRevision, apiAs, apiAsAdmin, createAdrViaApi, createUserViaApi, deleteViaApi, expect, login, test, transitionViaApi, uniqueText } from "./helpers";

type Person = Awaited<ReturnType<typeof createUserViaApi>>;

test.describe("the ADR history", () => {
  let admin: APIRequestContext;
  let driver: Person;
  let contributor: Person;
  let driverApi: APIRequestContext;
  let cleanup: string[];

  test.beforeEach(async () => {
    ({ api: admin } = await apiAsAdmin());
    driver = await createUserViaApi(admin, "E2E Driver");
    contributor = await createUserViaApi(admin, "E2E Contributor");
    ({ api: driverApi } = await apiAs(driver.email, driver.password));
    cleanup = [`/api/v1/users/${contributor.id}`, `/api/v1/users/${driver.id}`];
  });

  test.afterEach(async () => {
    await driverApi.dispose();
    await admin.dispose();
    await deleteViaApi(cleanup);
  });

  test("the history lists each change newest first, in words, with who made it", async ({ page }) => {
    const name = uniqueText("e2e-adr");
    const adr = await createAdrViaApi(driverApi, { name, problem: "Where do we log?" });
    cleanup.unshift(`/api/v1/adrs/${adr.id}`);
    const edited = await driverApi.put(`/api/v1/adrs/${adr.id}`, {
      data: { name, problem: "Where do we log?", context: "Ops wants one place.", expiresOn: null },
      headers: { "X-Expected-Revision": String(await adrRevision(driverApi, adr.id)) },
    });
    expect(edited.status(), await edited.text()).toBe(204);
    const replaced = await driverApi.put(`/api/v1/adrs/${adr.id}/participants`, {
      data: { participants: [{ userId: driver.id, role: "DRIVER" }, { userId: contributor.id, role: "CONTRIBUTOR" }] },
      headers: { "X-Expected-Revision": String(await adrRevision(driverApi, adr.id)) },
    });
    expect(replaced.status(), await replaced.text()).toBe(204);
    await transitionViaApi(driverApi, adr.id, "SOLUTION_DRAFT");

    await login(page, driver.email, driver.password);
    await page.goto(`/adrs/${adr.id}`);
    const history = page.getByRole("region", { name: "History" });
    await expect(history.getByText("ADR raised")).toBeVisible();

    // Newest first: the move, the participant, the edit, the creation.
    const sentences = history.getByText(/^(ADR raised|Edited|Status changed from .*|.* added as .*)$/);
    await expect(sentences).toHaveText([
      "Status changed from Problem raised to Solution draft",
      `${contributor.name} added as Contributor`,
      "Edited",
      "ADR raised",
    ]);
    await expect(history.getByText("Context changed")).toBeVisible();
    await expect(history.getByText(`${adr.key} "${name}"`)).toBeVisible();
    await expect(history.getByText(new RegExp(`^${driver.name} · \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$`))).toHaveCount(4);
  });

  test("the history pages ten events at a time", async ({ page }) => {
    const name = uniqueText("e2e-adr");
    const adr = await createAdrViaApi(driverApi, { name, problem: "Edit 0" });
    cleanup.unshift(`/api/v1/adrs/${adr.id}`);
    // Eleven real edits plus the creation: twelve events, so a second page of two.
    for (let n = 1; n <= 11; n++) {
      const edit = await driverApi.put(`/api/v1/adrs/${adr.id}`, {
        data: { name, problem: `Edit ${n}`, expiresOn: null },
        headers: { "X-Expected-Revision": String(await adrRevision(driverApi, adr.id)) },
      });
      expect(edit.status(), await edit.text()).toBe(204);
    }

    await login(page, driver.email, driver.password);
    await page.goto(`/adrs/${adr.id}`);
    const history = page.getByRole("region", { name: "History" });
    await expect(history.getByText("Edited")).toHaveCount(10);
    await expect(history.getByText("ADR raised")).toHaveCount(0);
    await history.getByRole("button", { name: "Next page" }).click();
    await expect(history.getByText("ADR raised")).toBeVisible();
    await expect(history.getByText("Edited")).toHaveCount(1);
  });
});
