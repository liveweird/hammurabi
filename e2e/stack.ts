import type { Target } from "./target.ts";

/**
 * The suite starts nothing and stops nothing: it runs against a stack somebody started and named (`target.ts`). The refusal comes
 * BEFORE any request, because the suite writes rows through the API of the stack it is given; the repository's default stack is a
 * working stack, so it is never assumed. Name a throwaway one.
 */
function refusal(problems: readonly string[]): string {
  return [
    "E2E refuses to run: the stack under test was not named.",
    ...problems.map((problem) => `  - ${problem}`),
    "The suite writes rows through the API of the stack it is given.",
    "This repository's default stack is a working stack, so no stack is assumed: start a throwaway one yourself",
    "and set E2E_BASE_URL and E2E_MAILPIT_URL to its app and Mailpit (e2e/README.md \"Run\").",
  ].join("\n");
}

/** The stack's two services, each probed by its own function (global-setup passes the real fetches, the unit test fakes). */
export interface StackProbes {
  /** True once the app answers AND its database round trip works. */
  app: (url: string) => Promise<boolean>;
  /** True once Mailpit answers: the two email specs cannot pass without it, so a stack with no catcher is refused up front. */
  mailpit: (url: string) => Promise<boolean>;
}

export async function requireReadyTarget(problems: readonly string[], target: Target, probes: StackProbes): Promise<void> {
  if (problems.length > 0) throw new Error(refusal(problems));
  if (!(await probes.app(target.baseUrl))) throw new Error(`E2E_BASE_URL (${target.baseUrl}) is not ready: start that stack first`);
  if (!(await probes.mailpit(target.mailpitUrl))) {
    throw new Error(
      `E2E_MAILPIT_URL (${target.mailpitUrl}) is not reachable: the mfa and password-reset specs read the emailed code and link from the ` +
        "stack's Mailpit and cannot run without it. Start the stack's mailpit service, or name the Mailpit of the stack under test.",
    );
  }
}
