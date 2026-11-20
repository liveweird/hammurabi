import { readTarget, targetProblems } from "./target.ts";
import { requireReadyTarget } from "./stack.ts";

/** GET `${url}${path}` and say whether it answered 2xx within 10 s; a refused connection, a DNS miss or a hang is `false`. */
async function answers(url: string, path: string): Promise<boolean> {
  try {
    const res = await fetch(`${url}${path}`, { redirect: "manual", signal: AbortSignal.timeout(10_000) });
    return res.ok;
  } catch {
    return false;
  }
}

export default async function globalSetup(): Promise<void> {
  const target = readTarget(process.env);
  await requireReadyTarget(targetProblems(process.env), target, {
    // 200 only once the app answers AND its database round trip works.
    app: (url) => answers(url, "/api/v1/ready"),
    // The list the two email specs read; Mailpit is REQUIRED (their old self-skip hid a missing catcher as a green run).
    mailpit: (url) => answers(url, "/api/v1/messages"),
  });
  console.log(`[e2e] Using app ${target.baseUrl}, Mailpit ${target.mailpitUrl}; nothing is started or stopped, and the stack's data is left intact.`);
}
