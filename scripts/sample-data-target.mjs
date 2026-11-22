import { execFileSync } from "node:child_process";

const PROJECT_LABEL = "com.docker.compose.project";
const SERVICE_LABEL = "com.docker.compose.service";

const displayName = (container) => container.Name?.replace(/^\//, "") ?? "unknown container";

function requireComposeService(container, expectedService) {
  const name = displayName(container);
  const labels = container.Config?.Labels ?? {};
  if (labels[SERVICE_LABEL] !== expectedService) {
    throw new Error(`Refusing sample data: container ${name} is not Compose service '${expectedService}'`);
  }
  if (!container.State?.Running) {
    throw new Error(`Refusing sample data: container ${name} is not running`);
  }
  const project = labels[PROJECT_LABEL];
  if (!project) throw new Error(`Refusing sample data: container ${name} has no Compose project label`);
  return project;
}

/**
 * Proves the direct SQL target and restarted app are the same Compose stack whose API [base] names.
 * Run before the first API mutation: the script later uses numeric API ids in SQL, so a split target could alter another stack.
 */
export function assertSampleDataTarget({ base, pgContainer, appContainer, containers }) {
  const byName = new Map(containers.map((container) => [displayName(container), container]));
  const postgres = byName.get(pgContainer);
  const app = byName.get(appContainer);
  if (!postgres || !app) throw new Error("Refusing sample data: Docker did not describe both configured containers");

  const pgProject = requireComposeService(postgres, "postgres");
  const appProject = requireComposeService(app, "app");
  if (pgProject !== appProject) {
    throw new Error(
      `Refusing sample data: ${pgContainer} and ${appContainer} belong to different Compose projects ` +
        `('${pgProject}' and '${appProject}')`,
    );
  }

  const url = new URL(base);
  const apiPort = url.port || (url.protocol === "http:" ? "80" : "443");
  const published = app.NetworkSettings?.Ports?.["8090/tcp"] ?? [];
  const reachableHosts = url.hostname === "localhost" ? ["127.0.0.1", "0.0.0.0", "::1", "::"] : ["127.0.0.1", "0.0.0.0"];
  if (!published.some(({ HostIp, HostPort }) => HostPort === apiPort && reachableHosts.includes(HostIp))) {
    throw new Error(`Refusing sample data: container ${appContainer} does not publish its API on ${url.hostname}:${apiPort}`);
  }
}

export function inspectSampleDataTarget(base, pgContainer, appContainer) {
  let containers;
  try {
    containers = JSON.parse(
      execFileSync("docker", ["container", "inspect", "--", pgContainer, appContainer], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }),
    );
  } catch (error) {
    const detail = error.stderr?.trim() || error.message;
    throw new Error(`Refusing sample data: cannot inspect the configured containers: ${detail}`);
  }
  assertSampleDataTarget({ base, pgContainer, appContainer, containers });
}

/**
 * The sample data goes into an EMPTY stack only. [countOutput] is psql's `-At` answer to `SELECT count(*) FROM adrs`, which counts every
 * row, soft-deleted ones included (the API list hides those, so an API-side check could see an empty list over a used database).
 * Anything that is not a plain non-negative integer is refused too: an unreadable answer is not evidence of an empty table.
 */
export function assertNoAdrRows(countOutput) {
  const text = String(countOutput).trim();
  if (!/^\d+$/.test(text)) throw new Error(`Refusing sample data: could not read the ADR row count (got '${text.slice(0, 40)}')`);
  const rows = Number(text);
  if (rows > 0) {
    throw new Error(`Refusing sample data: the database already has ${rows} ADR row(s) (soft-deleted ones included); sample data goes into an empty one only`);
  }
}
