import { defineConfig, devices } from "@playwright/test";
import { readTarget } from "./target.ts";

// Blackbox E2E: drives a real browser against the full stack (SPA + server + Postgres) served single-origin. The stack is NEVER
// started or assumed here: somebody starts one and names it through the two E2E_* variables of target.ts, and global-setup refuses
// to run without them. Services and data remain intact after the run.
const target = readTarget(process.env);
export const BASE_URL = target.baseUrl;
/** The Mailpit catcher of the stack under test (the three email specs read it). */
export const MAILPIT_URL = target.mailpitUrl;

const desktopChrome = { ...devices["Desktop Chrome"] };

// Specs that READ a section of a global, capped overview (the decision log's "Awaiting decision", "Recently decided" and "Expiring soon"
// list the newest few of EVERY user's ADRs) cannot own what they read: another spec activating or sending to review at the same moment
// can push their rows off the cap. They run in a second phase, after the parallel pool has finished and deleted its ADRs.
const GLOBAL_READERS = /decision-log\.spec\.ts$/;

export default defineConfig({
  testDir: "./tests",
  // The serial unit is the FILE (fullyParallel stays false so a file's tests may be
  // order-dependent). Files run in parallel; every spec file must own its server-side
  // state exclusively — see "Parallel execution" in the README before adding a spec.
  fullyParallel: false,
  workers: Number(process.env.E2E_WORKERS ?? 4),
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  // A retry keeps one network blip from reddening the nightly, but a test that PASSED ONLY ON RETRY is a flake somebody must
  // see: the run exits non-zero (and the nightly's alert issue opens) instead of staying green (1.0.0 checkup C-GATE-8).
  // Unconditional: locally retries are 0, so no test can be flaky there.
  failOnFlakyTests: true,
  reporter: [["list"], ["html", { open: "never" }]],
  timeout: 60_000,
  expect: { timeout: 10_000 },
  globalSetup: "./global-setup.ts",
  use: {
    baseURL: BASE_URL,
    // retain-on-failure, not on-first-retry: retries are 0 locally, so on-first-retry never
    // fires and local failures would produce no trace at all. A trace beats the video it
    // replaces (DOM snapshots, network, console) and costs nothing on a passing run.
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [
    { name: "chromium", use: desktopChrome, testIgnore: GLOBAL_READERS },
    { name: "global-readers", use: desktopChrome, testMatch: GLOBAL_READERS, dependencies: ["chromium"] },
  ],
});
