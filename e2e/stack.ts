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

export async function requireReadyTarget(
  problems: readonly string[],
  url: string,
  responds: (url: string) => Promise<boolean>,
): Promise<void> {
  if (problems.length > 0) throw new Error(refusal(problems));
  if (!(await responds(url))) throw new Error(`E2E_BASE_URL (${url}) is not ready: start that stack first`);
}
