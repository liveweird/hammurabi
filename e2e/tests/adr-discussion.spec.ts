// The ADR discussion in the browser: comments and questions on a draft (a question answered by the Driver), the composer gone once the
// ADR is active (the API refuses too), and an issue raised on an active ADR, counted on the list and resolved by the Approver. The
// permission matrix and the window are the server's (`AdrDiscussionAccessTest`, `AdrDiscussionTest`); this asserts what a person SEES.
// Owns: its throwaway users (unique `e2e-*` names) and the ADRs they share (unique `e2e-adr-*` names) — deleted through the API in
// afterEach (the ADRs first, then the users).
import type { APIRequestContext } from "@playwright/test";
import { apiAs, apiAsAdmin, axeViolations, completeAdrBody, createAdrViaApi, createUserViaApi, deleteViaApi, expect, login, signOut, test, transitionViaApi, uniqueText } from "./helpers";

type Person = Awaited<ReturnType<typeof createUserViaApi>>;

test.describe("the ADR discussion", () => {
  let admin: APIRequestContext;
  let driver: Person;
  let approver: Person;
  let asker: Person;
  let driverApi: APIRequestContext;
  let cleanup: string[];

  test.beforeEach(async () => {
    ({ api: admin } = await apiAsAdmin());
    driver = await createUserViaApi(admin, "E2E Driver");
    approver = await createUserViaApi(admin, "E2E Approver");
    asker = await createUserViaApi(admin, "E2E Asker");
    ({ api: driverApi } = await apiAs(driver.email, driver.password));
    cleanup = [`/api/v1/users/${asker.id}`, `/api/v1/users/${approver.id}`, `/api/v1/users/${driver.id}`];
  });

  test.afterEach(async () => {
    await driverApi.dispose();
    await admin.dispose();
    await deleteViaApi(cleanup);
  });

  /** An ADR the Driver raises with an Approver and complete content (so it can go to review and be activated). */
  async function raise(name: string): Promise<{ id: number }> {
    const adr = await createAdrViaApi(driverApi, completeAdrBody(approver.id, { name }));
    cleanup.unshift(`/api/v1/adrs/${adr.id}`);
    return adr;
  }

  /** Review then activation through the API: the Driver sends it to review, the Approver activates it. */
  async function activate(id: number): Promise<void> {
    await transitionViaApi(driverApi, id, "IN_REVIEW");
    const { api: approverApi } = await apiAs(approver.email, approver.password);
    await transitionViaApi(approverApi, id, "ACTIVE");
    await approverApi.dispose();
  }

  test("a comment and a question are posted on a draft and the Driver marks the question answered", async ({ page }) => {
    const adr = await raise(uniqueText("e2e-adr"));
    await login(page, asker.email, asker.password);
    await page.goto(`/adrs/${adr.id}`);
    await page.getByRole("tab", { name: "Discussion" }).click();
    const discussion = page.getByRole("region", { name: "Comments and questions" });
    await expect(discussion.getByText("Nothing has been said yet.")).toBeVisible();

    // Anyone signed in may post: a comment first, then a question (the same composer, the kind switched).
    const composer = discussion.getByRole("textbox", { name: /Your comment or question/ });
    // The composer is the visual editor (a contenteditable): `fill` inserts text verbatim, but a person's keystrokes trigger the Markdown
    // shortcuts, so `**as long as**` typed key by key becomes bold and is stored as Markdown.
    await composer.click();
    await composer.pressSequentially("Fine by me, **as long as** it stays boring.");
    await discussion.getByRole("button", { name: "Post comment" }).click();
    const comment = discussion.getByRole("article", { name: `Comment by ${asker.name}` });
    await expect(comment).toBeVisible();
    await expect(comment.getByText("as long as", { exact: true })).toBeVisible();
    // The composer is a contenteditable (no value): a posted comment leaves it empty.
    await expect(composer).toHaveText("");
    // The kind switch is a Mantine SegmentedControl: its radio inputs are visually hidden, the label is what a person clicks.
    await discussion.getByRole("radiogroup", { name: "Kind of entry" }).getByText("Question", { exact: true }).click();
    await composer.fill("Why not a dedicated broker?");
    await discussion.getByRole("button", { name: "Ask question" }).click();
    const question = discussion.getByRole("article", { name: `Question by ${asker.name}` });
    await expect(question.getByText("Why not a dedicated broker?")).toBeVisible();
    await expect(question.getByText("Open", { exact: true })).toBeVisible();
    // The tab counts what is unanswered; the author of the comment may edit and delete it (two icon buttons), a comment has no state.
    await expect(page.getByRole("tab", { name: "Discussion unanswered: 1" })).toBeVisible();
    await expect(comment.getByRole("button")).toHaveCount(2);
    // The success toast fades in and out, and axe measures contrast mid-transition: scan once it has left.
    await expect(page.getByText("Posted", { exact: true })).toHaveCount(0);
    expect(await axeViolations(page)).toEqual([]);
    await signOut(page);

    // The Driver reads it and answers the question.
    await login(page, driver.email, driver.password);
    await page.goto(`/adrs/${adr.id}`);
    await page.getByRole("tab", { name: /^Discussion/ }).click();
    const thread = page.getByRole("region", { name: "Comments and questions" });
    await expect(thread.getByRole("article")).toHaveCount(2);
    await thread.getByRole("article", { name: `Question by ${asker.name}` }).getByRole("button", { name: "Mark answered" }).click();
    const answered = thread.getByRole("article", { name: `Question by ${asker.name}` });
    await expect(answered.getByText("Answered", { exact: true })).toBeVisible();
    await expect(answered.getByText(new RegExp(`^Answered by ${driver.name} on \\d{4}-\\d{2}-\\d{2}$`))).toBeVisible();
    await expect(page.getByRole("tab", { name: "Discussion", exact: true })).toBeVisible();

    // The history records all three, newest first.
    const history = page.getByRole("region", { name: "History" });
    await expect(history.getByText(/^(Comment added|Question asked|Question answered)$/)).toHaveText(["Question answered", "Question asked", "Comment added"]);
  });

  test("once the ADR is active the discussion takes no new comments: the composer is gone and the API refuses", async ({ page }) => {
    const adr = await raise(uniqueText("e2e-adr"));
    // A comment made while it was a draft stays readable forever.
    const { api: askerApi } = await apiAs(asker.email, asker.password);
    const said = await askerApi.post(`/api/v1/adrs/${adr.id}/discussion`, { data: { kind: "COMMENT", body: "Said while it was a draft." } });
    expect(said.status(), await said.text()).toBe(201);
    await activate(adr.id);

    await login(page, asker.email, asker.password);
    await page.goto(`/adrs/${adr.id}`);
    await page.getByRole("tab", { name: "Discussion" }).click();
    const discussion = page.getByRole("region", { name: "Comments and questions" });
    await expect(discussion.getByText("Said while it was a draft.")).toBeVisible();
    await expect(discussion.getByText("Comments and questions are taken until the ADR is active. What was said stays readable.")).toBeVisible();
    await expect(discussion.getByRole("textbox")).toHaveCount(0);
    await expect(discussion.getByRole("button", { name: "Post comment" })).toHaveCount(0);

    // The window is the server's: a comment or a question is a typed 409 now, an issue is accepted.
    const late = await askerApi.post(`/api/v1/adrs/${adr.id}/discussion`, { data: { kind: "COMMENT", body: "Too late." } });
    expect(late.status()).toBe(409);
    expect(((await late.json()) as { type: string }).type).toBe("urn:hammurabi:problem:discussion-closed");
    const issue = await askerApi.post(`/api/v1/adrs/${adr.id}/discussion`, { data: { kind: "ISSUE", body: "Raised once active." } });
    expect(issue.status(), await issue.text()).toBe(201);
    await askerApi.dispose();
  });

  test("an issue is raised on an active ADR, counted on the list, and resolved by the Approver", async ({ page }) => {
    const name = uniqueText("e2e-adr");
    const adr = await raise(name);
    await activate(adr.id);

    await login(page, asker.email, asker.password);
    await page.goto(`/adrs/${adr.id}`);
    await page.getByRole("tab", { name: "Issues" }).click();
    const issues = page.getByRole("region", { name: "Issues and escalations" });
    await expect(issues.getByText("No issues have been raised.")).toBeVisible();
    await issues.getByRole("textbox", { name: /Describe the issue/ }).fill("The queue stalls under load.");
    await issues.getByRole("button", { name: "Raise issue" }).click();
    const raised = issues.getByRole("article", { name: `Issue by ${asker.name}` });
    await expect(raised.getByText("The queue stalls under load.")).toBeVisible();
    await expect(raised.getByText("Open", { exact: true })).toBeVisible();
    // Raising one is open to everyone; resolving it is not - the author sees no Resolve button.
    await expect(raised.getByRole("button", { name: "Resolve" })).toHaveCount(0);
    await expect(page.getByRole("tab", { name: "Issues open: 1" })).toBeVisible();
    await expect(page.getByText("Posted", { exact: true })).toHaveCount(0);
    expect(await axeViolations(page)).toEqual([]);

    // The list counts it.
    await page.goto("/adrs");
    const row = page.getByRole("row").filter({ hasText: name });
    await expect(row.getByText("Open issues: 1")).toHaveCount(1);
    await signOut(page);

    // The Approver resolves it with a note.
    await login(page, approver.email, approver.password);
    await page.goto(`/adrs/${adr.id}`);
    await page.getByRole("tab", { name: /^Issues/ }).click();
    const thread = page.getByRole("region", { name: "Issues and escalations" });
    await thread.getByRole("button", { name: "Resolve" }).click();
    const dialog = page.getByRole("dialog", { name: "Resolve this entry" });
    await dialog.getByRole("textbox", { name: /Resolution note/ }).fill("Fixed in the connection pool.");
    await dialog.getByRole("button", { name: "Resolve", exact: true }).click();
    const resolved = thread.getByRole("article", { name: `Issue by ${asker.name}` });
    await expect(resolved.getByText("Resolved", { exact: true })).toBeVisible();
    await expect(resolved.getByText("Fixed in the connection pool.")).toBeVisible();
    await expect(resolved.getByText(new RegExp(`^Resolved by ${approver.name} on \\d{4}-\\d{2}-\\d{2}$`))).toBeVisible();
    await expect(page.getByRole("tab", { name: "Issues", exact: true })).toBeVisible();
    await expect(page.getByRole("region", { name: "History" }).getByText("Issue resolved")).toBeVisible();

    // And the list no longer counts it.
    await page.goto("/adrs");
    await expect(page.getByRole("row").filter({ hasText: name })).toBeVisible();
    await expect(page.getByRole("row").filter({ hasText: name }).getByText(/^Open issues:/)).toHaveCount(0);
  });
});
