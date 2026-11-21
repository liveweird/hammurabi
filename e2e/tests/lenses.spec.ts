// Lenses in the browser: a Driver saves the list's current filters as a lens, applies it from a clean list and renames and deletes it; a
// PUBLIC lens is offered to another user (with its creator) and a PRIVATE one is not, and that user can neither rename nor delete it - in
// the menu and at the API. The verdict order, the cap and the validation are the server's (`LensTest`). Owns: its throwaway users (unique
// `e2e-*` emails), the lenses they save (deleted through their own API sessions: a PRIVATE lens is invisible to the admin) and the ADRs
// the Driver raises (unique `e2e-adr-*` names) - all removed in afterEach.
import type { APIRequestContext } from "@playwright/test";
import { apiAs, apiAsAdmin, createAdrViaApi, createUserViaApi, deleteViaApi, expect, login, test, transitionViaApi, uniqueText } from "./helpers";

type Person = Awaited<ReturnType<typeof createUserViaApi>>;

test.describe("Lenses", () => {
  let admin: APIRequestContext;
  let owner: Person;
  let other: Person;
  let ownerApi: APIRequestContext;
  let otherApi: APIRequestContext;
  let cleanup: string[];

  test.beforeEach(async () => {
    ({ api: admin } = await apiAsAdmin());
    owner = await createUserViaApi(admin, "E2E Lens Owner");
    other = await createUserViaApi(admin, "E2E Lens Other");
    ({ api: ownerApi } = await apiAs(owner.email, owner.password));
    ({ api: otherApi } = await apiAs(other.email, other.password));
    cleanup = [`/api/v1/users/${other.id}`, `/api/v1/users/${owner.id}`];
  });

  test.afterEach(async () => {
    // Every lens either user left is theirs to delete (the admin cannot see a PRIVATE one), and must go before the users.
    for (const api of [ownerApi, otherApi]) {
      const mine = (await (await api.get("/api/v1/lenses?mine=true&pageSize=100")).json()) as { items: { id: number }[] };
      for (const lens of mine.items) await api.delete(`/api/v1/lenses/${lens.id}`);
      await api.dispose();
    }
    await admin.dispose();
    await deleteViaApi(cleanup);
  });

  async function raise(name: string, draft: boolean): Promise<void> {
    const adr = await createAdrViaApi(ownerApi, { name, problem: "Which option do we take?" });
    cleanup.unshift(`/api/v1/adrs/${adr.id}`);
    if (draft) await transitionViaApi(ownerApi, adr.id, "SOLUTION_DRAFT");
  }

  async function saveLens(page: import("@playwright/test").Page, name: string, shared: boolean): Promise<void> {
    await page.getByRole("button", { name: "Lens actions" }).click();
    await page.getByRole("menuitem", { name: "Save current filters as lens…" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("textbox", { name: "Name" }).fill(name);
    if (shared) await dialog.getByRole("radio", { name: "Shared" }).check();
    await dialog.getByRole("button", { name: "Save" }).click();
    await expect(dialog).toBeHidden();
  }

  test("a Driver saves the current filters as a lens, applies it from a clean list, renames it and deletes it", async ({ page }) => {
    const marker = uniqueText("e2e-adr-lens");
    const draftName = `${marker} draft`;
    const raisedName = `${marker} raised`;
    await raise(draftName, true);
    await raise(raisedName, false);
    const lensName = uniqueText("e2e-lens");

    await login(page, owner.email, owner.password);
    await page.goto(`/adrs?q=${marker}&status=SOLUTION_DRAFT`);
    await expect(page.getByRole("row").filter({ hasText: draftName })).toBeVisible();
    await expect(page.getByRole("row").filter({ hasText: raisedName })).toHaveCount(0);
    await saveLens(page, lensName, false);
    await expect(page.getByRole("combobox", { name: "Lens" })).toHaveValue(lensName);

    // From a clean list (both rows), the lens brings the filters back into the URL and the list narrows to the draft.
    await page.goto(`/adrs?q=${marker}`);
    await expect(page.getByRole("row").filter({ hasText: raisedName })).toBeVisible();
    await page.getByRole("combobox", { name: "Lens" }).click();
    await page.getByRole("option", { name: lensName }).click();
    await expect(page).toHaveURL(/status=SOLUTION_DRAFT/);
    await expect(page.getByRole("row").filter({ hasText: raisedName })).toHaveCount(0);
    await expect(page.getByRole("row").filter({ hasText: draftName })).toBeVisible();

    // Rename it and share it, then delete it after a confirmation.
    const renamed = `${lensName}-renamed`;
    await page.getByRole("button", { name: "Lens actions" }).click();
    await page.getByRole("menuitem", { name: "Rename / change visibility…" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("textbox", { name: "Name" }).fill(renamed);
    await dialog.getByRole("radio", { name: "Shared" }).check();
    await dialog.getByRole("button", { name: "Save" }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByRole("combobox", { name: "Lens" })).toHaveValue(renamed);
    await page.getByRole("button", { name: "Lens actions" }).click();
    await page.getByRole("menuitem", { name: "Delete lens…" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Delete" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
    const left = (await (await ownerApi.get("/api/v1/lenses?mine=true")).json()) as { total: number };
    expect(left.total).toBe(0);
  });

  test("a PUBLIC lens is offered to another user and a PRIVATE one is not, and the other user cannot change it", async ({ page }) => {
    const publicName = uniqueText("e2e-lens-public");
    const privateName = uniqueText("e2e-lens-private");
    const filters = { status: ["SOLUTION_DRAFT"] };
    const created = await ownerApi.post("/api/v1/lenses", { data: { name: publicName, visibility: "PUBLIC", filters } });
    expect(created.status(), await created.text()).toBe(201);
    const publicLens = (await created.json()) as { id: number };
    const hidden = await ownerApi.post("/api/v1/lenses", { data: { name: privateName, visibility: "PRIVATE", filters } });
    expect(hidden.status(), await hidden.text()).toBe(201);
    const privateLens = (await hidden.json()) as { id: number };

    await login(page, other.email, other.password);
    await page.goto("/adrs");
    await page.getByRole("combobox", { name: "Lens" }).click();
    await expect(page.getByRole("option", { name: `${publicName} — ${owner.name}` })).toBeVisible();
    await expect(page.getByRole("option", { name: new RegExp(privateName) })).toHaveCount(0);
    await page.getByRole("option", { name: `${publicName} — ${owner.name}` }).click();
    await expect(page).toHaveURL(/status=SOLUTION_DRAFT/);

    // Applying it works, changing it does not: no rename, no delete, no overwrite in the menu.
    await page.getByRole("button", { name: "Lens actions" }).click();
    await expect(page.getByRole("menuitem", { name: "Save current filters as lens…" })).toBeVisible();
    await expect(page.getByRole("menuitem", { name: "Rename / change visibility…" })).toHaveCount(0);
    await expect(page.getByRole("menuitem", { name: "Delete lens…" })).toHaveCount(0);
    await page.keyboard.press("Escape");

    // The API agrees: the public lens is 403 to change, the private one does not exist for the other user.
    const body = { name: "hijacked", visibility: "PUBLIC", filters };
    expect((await otherApi.put(`/api/v1/lenses/${publicLens.id}`, { data: body })).status()).toBe(403);
    expect((await otherApi.delete(`/api/v1/lenses/${publicLens.id}`)).status()).toBe(403);
    expect((await otherApi.put(`/api/v1/lenses/${privateLens.id}`, { data: body })).status()).toBe(404);
    expect((await otherApi.get(`/api/v1/lenses/${privateLens.id}`)).status()).toBe(404);
  });
});
