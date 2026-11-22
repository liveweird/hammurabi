import {
  apiAs,
  apiAsAdmin,
  axeViolations,
  completeAdrBody,
  createAdrViaApi,
  createUserViaApi,
  deleteViaApi,
  expect,
  login,
  test,
  transitionViaApi,
  uniqueText,
  utcDayFromNow,
} from "./helpers";

const cleanup: string[] = [];

test.afterEach(async () => {
  await deleteViaApi(cleanup.splice(0));
});

async function seedPeople() {
  const { api: admin } = await apiAsAdmin();
  try {
    const driver = await createUserViaApi(admin, "E2E Work Driver");
    cleanup.push(`/api/v1/users/${driver.id}`);
    const reviewer = await createUserViaApi(admin, "E2E Work Reviewer");
    cleanup.push(`/api/v1/users/${reviewer.id}`);
    return { driver, reviewer };
  } finally {
    await admin.dispose();
  }
}

async function seedWork() {
  const { driver, reviewer } = await seedPeople();
  const { api: driverApi } = await apiAs(driver.email, driver.password);
  try {
    const { api: reviewerApi } = await apiAs(reviewer.email, reviewer.password);
    try {
      const review = await createAdrViaApi(driverApi, completeAdrBody(reviewer.id, { name: uniqueText("e2e-adr-review") }));
      cleanup.unshift(`/api/v1/adrs/${review.id}`);
      await transitionViaApi(driverApi, review.id, "IN_REVIEW");
      const question = await driverApi.post(`/api/v1/adrs/${review.id}/discussion`, {
        data: { kind: "QUESTION", body: "Who will own the rollout?" },
      });
      expect(question.status(), await question.text()).toBe(201);

      const expiry = await createAdrViaApi(reviewerApi, completeAdrBody(driver.id, {
        name: uniqueText("e2e-adr-expiry"),
        expiresOn: utcDayFromNow(3),
      }));
      cleanup.unshift(`/api/v1/adrs/${expiry.id}`);
      await transitionViaApi(reviewerApi, expiry.id, "IN_REVIEW");
      await transitionViaApi(driverApi, expiry.id, "ACTIVE");

      const unrelated = await createAdrViaApi(driverApi, { name: uniqueText("e2e-adr-unrelated"), problem: "A separate decision." });
      cleanup.unshift(`/api/v1/adrs/${unrelated.id}`);
      return { driver, reviewer, review, expiry, unrelated };
    } finally {
      await reviewerApi.dispose();
    }
  } finally {
    await driverApi.dispose();
  }
}

test("My work shows my ADR roles and opens the relevant sections", async ({ page }) => {
  const { driver, reviewer, review, expiry, unrelated } = await seedWork();
  await login(page, reviewer.email, reviewer.password);
  await page.goto("/");
  await expect(page).toHaveURL(/\/my-work$/);
  await expect(page.getByRole("link", { name: "My work", exact: true })).toHaveAttribute("aria-current", "page");

  const attention = page.getByRole("region", { name: "Needs your action" });
  const activity = page.getByRole("region", { name: "Recent activity" });
  const cases = page.getByRole("region", { name: "My ADRs" });
  await expect(attention.getByRole("link", { name: review.name, exact: true })).toBeVisible();
  await expect(attention.getByText("Awaiting your decision", { exact: true })).toBeVisible();
  await expect(attention.getByText(`Expires in 3 days · ${driver.name} can extend it`)).toBeVisible();
  await expect(attention.getByRole("link", { name: expiry.name, exact: true })).toHaveAttribute("href", `/adrs/${expiry.id}#validity`);
  await expect(cases.getByRole("link", { name: expiry.name, exact: true })).toBeVisible();
  await expect(page.getByText(unrelated.name, { exact: true })).toHaveCount(0);
  expect(await axeViolations(page)).toEqual([]);

  // The desktop sidebar still takes space at this width. The work columns follow the available content width.
  await page.setViewportSize({ width: 850, height: 900 });
  await expect.poll(async () => {
    const attentionBox = await attention.boundingBox();
    const activityBox = await activity.boundingBox();
    return attentionBox !== null && activityBox !== null && activityBox.y >= attentionBox.y + attentionBox.height;
  }).toBe(true);

  await attention.getByRole("link", { name: expiry.name, exact: true }).click();
  await expect(page.getByRole("region", { name: "Validity" })).toBeFocused();
  await expect(page.getByRole("button", { name: "Extend validity" })).toHaveCount(0);
  await page.goBack();

  await page.getByRole("combobox", { name: "My role" }).click();
  await page.getByRole("option", { name: "Approver", exact: true }).click();
  await expect(page).toHaveURL(/\/my-work\?participantRole=APPROVER$/);
  await expect(cases.getByRole("link", { name: review.name, exact: true })).toBeVisible();
  await expect(page.getByText(expiry.name, { exact: true })).toHaveCount(0);
  await expect(cases.getByRole("link", { name: "View all my ADRs" })).toHaveAttribute("href", /\/adrs\?.*participantRole=APPROVER/);

  await activity.getByRole("link", { name: /Question asked/i }).click();
  await expect(page).toHaveURL(new RegExp(`/adrs/${review.id}#discussion$`));
  await expect(page.getByRole("heading", { name: "Comments and questions" })).toBeFocused();
  await expect(page.getByText("Who will own the rollout?")).toBeVisible();
});
