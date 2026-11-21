// The backup/restore drill (checkup C-ARCH-2; runbook: .claude/docs/persistence.md "Backup and restore"). It boots the image against a
// throwaway compose project (own containers, volume and ports), seeds a database through the API, then proves scripts/db-backup.sh and
// scripts/db-restore.sh end to end: every step is an assertion and the first failure exits non-zero.
//
//   node scripts/db-drill.mjs [--image <tag>] [--keep]
//
// Steps: (1) up, seed (users, a team, a tag category, an accented-name ADR taken to ACTIVE with participants, a comment, a lens);
// (2) fingerprint (row counts of every public and ops table, the Flyway maximum, md5 of ordered users/adrs/adr_events);
// (3) backup: mode 0600, a second run to the same path refuses; (4) restore refuses while the app runs, and refuses a non-empty
// database, changing nothing; (5) `down -v`, Postgres only, restore, the fingerprint is equal (taken before the app starts: the app
// writes telemetry at boot); (6) start the app: "Flyway applied 0 migration(s)", both logins work, the accented ADR is found by
// `text=zolw`, a duplicate name is 409, a new ADR takes the next number, a live backup works; (7) `--replace` over a database AHEAD of
// the dump (an extra table with a foreign key into adrs, a fake higher Flyway row, an extra ADR): afterwards exactly the dump;
// (8) the forward migration: a V21-shaped database (V22's discussion revision undone), dump, restore, start: one migration applied;
// (9) tear down.
//
// By default it BUILDS the image from the working tree under its own project (`docker compose -p hammurabi-backup-drill build app`).
// The build needs a plain checkout: `COPY .git` fails in a git worktree (its .git is a file), where you pass `--image <tag>` of an
// image built from a scratch copy (that image is yours: the drill leaves it in place; an image it built itself is removed with the project).
// `--keep` leaves the project and the work directory in place for inspection. Ctrl-C or SIGTERM runs the same cleanup as a normal end.
// Needs Docker, bash, Node >= 24 and free loopback ports 8592 (app) / 5943 (postgres) / 8536 (mailpit).
//
// SAFETY: every compose call goes through `compose()`, which hard-codes the project `hammurabi-backup-drill`; both scripts are always
// called with HAMMURABI_PG_EXEC set to this project's Postgres container, and `target()` asserts that string AND that the container
// carries this project's compose label before EVERY call (the scripts' own default, the `hammurabi-postgres` of the default project,
// is never reachable from here). The default project on :8090 is never touched.
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const args = process.argv.slice(2);
const flag = (name) => args.includes(name);
const option = (name, fallback) => (args.includes(name) ? args[args.indexOf(name) + 1] : fallback);
if (args.includes("--image") && !(args[args.indexOf("--image") + 1] ?? "").trim().match(/^[^-\s]\S*$/)) {
  console.error("db-drill: --image needs an image tag (a non-empty value not starting with '-')");
  process.exit(2);
}
const image = option("--image", null);
const project = "hammurabi-backup-drill";
const pgContainer = `${project}-postgres`;
const appContainer = `${project}-app`;
const PG_EXEC = `docker exec -i ${pgContainer}`;
const base = "http://127.0.0.1:8592";
const work = mkdtempSync(join(tmpdir(), "hammurabi-drill-"));
const override = join(work, "compose.override.yaml");
const PASSWORD = "hammurabi-drill-1";
const ACCENTED_NAME = "Żółw jako wzorzec trwałości";
// The forward-migration step (8) models the newest migration as "not yet applied": it undoes this one by hand. When a newer migration
// lands, repoint the shape and version at it (the step asserts the migration directory ends exactly at this version).
const FORWARD = { version: 22, table: "adr_discussion", column: "revision" };

writeFileSync(
  override,
  `services:
  postgres:
    container_name: ${pgContainer}
    ports: !override
      - "127.0.0.1:5943:5432"
  mailpit:
    container_name: ${project}-mailpit
    ports: !override
      - "127.0.0.1:8536:8025"
  app:${image ? `\n    image: ${image}\n    pull_policy: never` : ""}
    container_name: ${appContainer}
    ports: !override
      - "127.0.0.1:8592:8090"
    environment:
      MAIL_APP_URL: ${base}
      # The digest must not mail the seeded notices mid-drill (a changed row would read as a restore difference).
      NOTIFICATIONS_EMAIL_DELAY_SECONDS: "43200"
`,
);

// The ONE compose entry point: the project is hard-coded, nothing else can be reached through it.
const compose = (...a) => execFileSync("docker", ["compose", "-p", project, "-f", join(root, "docker-compose.yaml"), "-f", override, ...a], { stdio: ["ignore", "inherit", "inherit"] });
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const log = (m) => console.log(`[db-drill] ${m}`);
const results = [];
const check = (cond, what) => {
  if (!cond) throw new Error(`ASSERTION FAILED: ${what}`);
  results.push(what);
  log(`ok: ${what}`);
};

// Asserted before EVERY script or psql call: the exec string is this project's, and the container is labelled as this project's.
const env = { ...process.env, HAMMURABI_PG_EXEC: PG_EXEC, HAMMURABI_DB_USER: "hammurabi", HAMMURABI_DB_NAME: "hammurabi" };
const target = () => {
  if (env.HAMMURABI_PG_EXEC !== `docker exec -i hammurabi-backup-drill-postgres`) throw new Error("HAMMURABI_PG_EXEC is not the drill project's Postgres container");
  const label = execFileSync("docker", ["inspect", "-f", '{{index .Config.Labels "com.docker.compose.project"}}', pgContainer], { encoding: "utf8" }).trim();
  if (label !== project) throw new Error(`container ${pgContainer} belongs to compose project '${label}', not ${project}: refusing`);
};
const script = (name, scriptArgs) => {
  target();
  const r = spawnSync("bash", [join(root, "scripts", name), ...scriptArgs], { env, encoding: "utf8", cwd: work, timeout: 180_000 });
  return { status: r.status, out: r.stdout ?? "", err: r.stderr ?? "" };
};
const psql = (sql) => {
  target();
  const r = spawnSync("docker", ["exec", "-i", pgContainer, "psql", "-U", "hammurabi", "-d", "hammurabi", "-v", "ON_ERROR_STOP=1", "-Atq"], { input: sql, encoding: "utf8", timeout: 60_000 });
  if (r.status !== 0) throw new Error(`psql failed: ${r.stderr}`);
  return r.stdout.trim();
};
const fingerprint = (schemas) =>
  psql(`
SELECT 'flyway=' || max(version::int) FROM flyway_schema_history WHERE success;
SELECT 'count:' || table_schema || '.' || table_name || '=' ||
  (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text
FROM information_schema.tables WHERE table_schema IN (${schemas.map((s) => `'${s}'`).join(",")}) AND table_type = 'BASE TABLE' ORDER BY 1;
SELECT 'md5:users=' || coalesce(md5(string_agg(t::text, E'\\n' ORDER BY id)), '') FROM users t;
SELECT 'md5:adrs=' || coalesce(md5(string_agg(t::text, E'\\n' ORDER BY id)), '') FROM adrs t;
SELECT 'md5:adr_events=' || coalesce(md5(string_agg(t::text, E'\\n' ORDER BY id)), '') FROM adr_events t;
`);

let tokens = new Map();
const call = async (as, method, path, body, revision) => {
  const headers = { "content-type": "application/json", ...(as ? { authorization: `Bearer ${tokens.get(as)}` } : {}) };
  if (revision !== undefined) headers["x-expected-revision"] = String(revision);
  const res = await fetch(`${base}/api/v1${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15_000) });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* empty or HTML */ }
  return { status: res.status, json };
};
const must = (res, what) => {
  if (res.status >= 300) throw new Error(`${what} answered ${res.status}: ${JSON.stringify(res.json)?.slice(0, 300)}`);
  return res.json;
};
const login = async (email, password) => {
  const res = await call(null, "POST", "/login", { email, password });
  if (res.status === 200 && res.json?.token) tokens.set(email, res.json.token);
  return res;
};
const until = async (what, probe, seconds) => {
  for (let i = 0; i < seconds; i++) {
    const v = await probe();
    if (v) return v;
    await sleep(1000);
  }
  throw new Error(`timed out waiting for ${what}`);
};
const waitHealthy = () => until("the app to answer /health", async () => (await fetch(`${base}/api/v1/health`).catch(() => null))?.ok, 120);
// A fresh container each time, so `docker logs` holds exactly this boot.
const startApp = async () => {
  tokens = new Map();
  compose("up", "-d", "mailpit");
  compose("rm", "-sf", "app");
  compose("up", "-d", "--no-deps", "app");
  await waitHealthy();
};
const stopApp = () => compose("stop", "-t", "30", "app");
const appLog = () => {
  const r = spawnSync("docker", ["logs", appContainer], { encoding: "utf8" });
  return `${r.stdout}${r.stderr}`;
};
const mode = (file) => (statSync(file).mode & 0o777).toString(8);

const migrations = readdirSync(join(root, "server", "src", "main", "resources", "db", "migration"))
  .map((f) => /^V(\d+)__/.exec(f)?.[1])
  .filter(Boolean)
  .map(Number);
const latest = Math.max(...migrations);

const adrDoc = (name) => ({
  name,
  problem: "Where do the tortoises live?",
  context: "The reserve is full.",
  decision: "Move them to the north pasture.",
  consequences: "The north pasture needs a fence.",
});
const adr = async (as, id) => must(await call(as, "GET", `/adrs/${id}`), `reading ADR ${id}`);
const move = async (as, id, to) => must(await call(as, "POST", `/adrs/${id}/transitions`, { to }, (await adr(as, id)).revision), `moving ADR ${id} to ${to}`);

// Idempotent: the normal end and a signal both run it. The drill project goes down with its volume, the work directory (0600 dumps) is removed.
let cleaned = false;
const cleanup = () => {
  if (cleaned) return;
  cleaned = true;
  if (flag("--keep")) {
    log(`--keep: project ${project} is still up (app ${base}, work dir ${work}); remove it with: docker compose -p ${project} down -v --rmi local`);
    return;
  }
  try { compose("down", "-v", "--rmi", "local", "--remove-orphans"); } catch { /* best effort: the project is throwaway */ }
  rmSync(work, { recursive: true, force: true });
};
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    log(`${signal}: cleaning up`);
    cleanup();
    process.exit(130);
  });
}

let ok = false;
try {
  log(`project ${project}; ports 8592 / 5943 / 8536; work dir ${work}`);
  compose("down", "-v", "--remove-orphans"); // a leftover of an earlier failed run, THIS project only
  if (image === null) {
    log("building the app image under the drill project (a git worktree needs --image, see the header)");
    compose("build", "app");
  }

  // ---- 1. up, seed through the API
  log("step 1: up and seed");
  compose("up", "-d", "--wait", "postgres", "mailpit");
  compose("up", "-d", "--no-deps", "app");
  await waitHealthy();
  must(await login("admin@hammurabi.local", "changeme"), "admin login");
  const users = {};
  for (const key of ["driver", "approver", "contributor"]) {
    const email = `drill-${key}@hammurabi.local`;
    users[key] = must(await call("admin@hammurabi.local", "POST", "/users", { name: `Drill ${key}`, email, password: PASSWORD, roles: [], language: "en" }), `creating ${key}`);
    users[key].email = email;
    must(await login(email, PASSWORD), `${key} login`);
  }
  must(await call("admin@hammurabi.local", "POST", "/teams", { name: "drill-team", description: "seeded by the drill", managerId: users.driver.id }), "creating the team");
  must(await call("admin@hammurabi.local", "POST", "/tag-categories", { name: "Drill", description: "seeded by the drill", tags: ["drill-tag"] }), "creating the tag category");
  const driver = users.driver.email;
  const first = must(
    await call(driver, "POST", "/adrs", { ...adrDoc(ACCENTED_NAME), participants: [{ userId: users.approver.id, role: "APPROVER" }, { userId: users.contributor.id, role: "CONTRIBUTOR" }] }),
    "creating the accented ADR",
  );
  must(await call(driver, "PUT", `/adrs/${first.id}/tags`, { tags: ["drill-tag"] }, (await adr(driver, first.id)).revision), "tagging the ADR");
  await move(driver, first.id, "SOLUTION_DRAFT");
  await move(driver, first.id, "IN_REVIEW");
  const comment = must(
    await call(users.contributor.email, "POST", `/adrs/${first.id}/discussion`, { kind: "COMMENT", body: "Seeded by the drill." }),
    "posting the comment",
  );
  await move(users.approver.email, first.id, "ACTIVE");
  must(await call(driver, "POST", "/lenses", { name: "drill lens", visibility: "PRIVATE", filters: { status: ["ACTIVE"] } }), "creating the lens");
  check((await adr(driver, first.id)).status === "ACTIVE", `step 1: the seeded ADR ${first.key} is ACTIVE with two participants, a tag, a comment and a lens beside it`);

  // ---- 2. fingerprint (app stopped: nothing writes between the fingerprint and the dump)
  log("step 2: fingerprint");
  stopApp();
  const before = fingerprint(["public", "ops"]);
  const beforePublic = fingerprint(["public"]);
  check(before.includes(`flyway=${latest}`) && /count:public\.adrs=1\b/.test(before) && /count:public\.users=\d+/.test(before), `step 2: fingerprint taken (Flyway ${latest}, ${before.split("\n").length} lines)`);

  // ---- 3. backup
  log("step 3: backup");
  const dump = join(work, "drill.dump");
  const b1 = script("db-backup.sh", [dump]);
  check(b1.status === 0 && /^wrote .*drill\.dump/.test(b1.out), "step 3: db-backup.sh wrote the dump");
  check(mode(dump) === "600", `step 3: the dump is mode 0600 (was ${mode(dump)})`);
  const size = statSync(dump).size;
  const b2 = script("db-backup.sh", [dump]);
  check(b2.status !== 0 && b2.err.includes("refusing to overwrite") && statSync(dump).size === size, "step 3: a second backup to the same path refuses and leaves the dump alone");
  check(readdirSync(work).every((f) => !f.includes(".partial.")), "step 3: no partial file is left behind");
  // A set-but-empty HAMMURABI_PG_EXEC is refused before anything runs (it must never fall back to the default container).
  for (const name of ["db-backup.sh", "db-restore.sh"]) {
    const r = spawnSync("bash", [join(root, "scripts", name), join(work, "empty-exec.dump")], { env: { ...env, HAMMURABI_PG_EXEC: "" }, encoding: "utf8", cwd: work });
    check(r.status === 2 && r.stderr.includes("set but empty"), `step 3: ${name} refuses a set-but-empty HAMMURABI_PG_EXEC`);
  }
  // An output name starting with '-' is a path, not an option.
  const dashed = script("db-backup.sh", ["-dash.dump"]);
  check(dashed.status === 0 && statSync(join(work, "-dash.dump")).size > 0, "step 3: an output name starting with '-' is written as a path");

  // ---- 4. restore refuses while the app runs, and a non-empty database
  log("step 4: refusals");
  await startApp();
  const r1 = script("db-restore.sh", [dump]);
  check(r1.status !== 0 && r1.err.includes("other client(s) are connected"), "step 4: restore refuses while the app is connected");
  stopApp();
  const r2 = script("db-restore.sh", [dump]);
  check(r2.status !== 0 && r2.err.includes("is not empty"), "step 4: restore refuses a non-empty database");
  check(fingerprint(["public"]) === beforePublic, "step 4: the refusals changed nothing (public fingerprint equal)");

  // ---- 5. down -v, Postgres only, restore, fingerprint equal
  log("step 5: lose the volume, restore into a fresh Postgres");
  compose("down", "-v", "--remove-orphans");
  compose("up", "-d", "--wait", "postgres");
  const r3 = script("db-restore.sh", [dump]);
  check(r3.status === 0 && r3.out.includes(`schema at Flyway version ${latest}`), `step 5: db-restore.sh restored into the empty database (Flyway ${latest})`);
  check(fingerprint(["public", "ops"]) === before, "step 5: the restored fingerprint equals the original (counts, Flyway version, md5 of users, adrs, adr_events)");

  // ---- 6. the app on the restored database
  log("step 6: start the app on the restored database");
  await startApp();
  check(appLog().includes("Flyway applied 0 migration(s)"), "step 6: the app log says 'Flyway applied 0 migration(s)'");
  check((await login("admin@hammurabi.local", "changeme")).status === 200, "step 6: the seed admin logs in");
  check((await login(driver, PASSWORD)).status === 200, "step 6: the seeded driver logs in (the password hash came back)");
  const found = must(await call(driver, "GET", "/adrs?text=zolw"), "searching text=zolw");
  check(found.items.some((i) => i.id === first.id), "step 6: GET /adrs?text=zolw finds the accented ADR (the search vector came back)");
  const dup = await call(driver, "POST", "/adrs", adrDoc("ZOLW jako wzorzec trwalosci"));
  check(dup.status === 409, `step 6: a duplicate name (case and accent folded) is 409 (was ${dup.status})`);
  const second = must(await call(driver, "POST", "/adrs", adrDoc("The second ADR after the restore")), "creating a new ADR");
  check(second.number === first.number + 1, `step 6: a new ADR takes the next number (${first.key} then ${second.key})`);
  const live = join(work, "live.dump");
  const b3 = script("db-backup.sh", [live]);
  check(b3.status === 0 && mode(live) === "600", "step 6: a live backup (the app running) works");

  // ---- 7. --replace over a database AHEAD of the dump
  log("step 7: --replace over a database ahead of the dump");
  stopApp();
  psql(`
CREATE TABLE drill_extra (id BIGSERIAL PRIMARY KEY, adr_id BIGINT NOT NULL REFERENCES adrs(id));
INSERT INTO drill_extra (adr_id) SELECT id FROM adrs ORDER BY id LIMIT 1;
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
  SELECT max(installed_rank) + 1, '${latest + 100}', 'drill', 'SQL', 'V${latest + 100}__drill.sql', 0, 'drill', 0, true FROM flyway_schema_history;
`);
  check(fingerprint(["public", "ops"]) !== before, "step 7: the database is now ahead of the dump (an extra table, an extra ADR, a higher Flyway row)");
  const r4 = script("db-restore.sh", [dump, "--replace"]);
  check(r4.status === 0 && r4.out.includes(`schema at Flyway version ${latest}`), "step 7: restore --replace succeeded");
  check(fingerprint(["public", "ops"]) === before, "step 7: afterwards the database is exactly the dump (the extra table, row and Flyway version are gone)");
  check(psql("SELECT to_regclass('public.drill_extra') IS NULL").trim() === "t", "step 7: the extra table with its foreign key into adrs is gone");
  await startApp();
  check(appLog().includes("Flyway applied 0 migration(s)"), "step 7: the app boots on the replaced database, applying 0 migrations");

  // ---- 8. forward migration: a V21-shaped database restored into a newer app
  log(`step 8: forward migration (V${FORWARD.version} undone by hand, then applied on boot)`);
  check(latest === FORWARD.version, `step 8: the newest migration is V${latest}, the one this step models (a newer one: repoint FORWARD at it)`);
  stopApp();
  psql(`ALTER TABLE ${FORWARD.table} DROP COLUMN ${FORWARD.column}; DELETE FROM flyway_schema_history WHERE version = '${FORWARD.version}';`);
  check(
    psql(`SELECT NOT EXISTS (
      SELECT FROM information_schema.columns
      WHERE table_schema = 'public' AND table_name = '${FORWARD.table}' AND column_name = '${FORWARD.column}'
    )`) === "t",
    `step 8: the older shape has no ${FORWARD.table}.${FORWARD.column}`,
  );
  const old = join(work, "old.dump");
  check(script("db-backup.sh", [old]).status === 0, "step 8: dumped the older-shaped database");
  compose("down", "-v", "--remove-orphans");
  compose("up", "-d", "--wait", "postgres");
  const r5 = script("db-restore.sh", [old]);
  check(r5.status === 0 && r5.out.includes(`schema at Flyway version ${FORWARD.version - 1}`), `step 8: restored the older-shaped dump into an empty database (Flyway ${FORWARD.version - 1})`);
  await startApp();
  check(appLog().includes(`Flyway applied 1 migration(s); schema at version ${latest}`), `step 8: the app log says 'Flyway applied 1 migration(s); schema at version ${latest}'`);
  check(
    psql(`SELECT count(*) > 0 AND bool_and(${FORWARD.column} = 1) FROM ${FORWARD.table}`) === "t",
    `step 8: V${FORWARD.version} restored ${FORWARD.table}.${FORWARD.column} and backfilled every existing entry to 1`,
  );
  must(await login(users.contributor.email, PASSWORD), "contributor login after the forward migration");
  const guardedDelete = await call(users.contributor.email, "DELETE", `/adrs/${first.id}/discussion/${comment.id}`, undefined, 1);
  check(
    guardedDelete.status === 204 && psql(`SELECT marked_as_deleted AND ${FORWARD.column} = 2 FROM ${FORWARD.table} WHERE id = ${comment.id}`) === "t",
    `step 8: a discussion write guarded by the backfilled revision succeeds and advances it to 2 (was ${guardedDelete.status})`,
  );
  must(await login(driver, PASSWORD), "driver login after the forward migration");
  const migrated = await call(driver, "POST", "/adrs", { ...adrDoc("After the forward migration"), participants: [{ userId: users.approver.id, role: "APPROVER" }] });
  check(migrated.status === 201, `step 8: a write that makes notices works on the migrated database (was ${migrated.status})`);

  // ---- 9. tear down (in the finally)
  log("step 9: tear down");
  ok = true;
} finally {
  cleanup();
}
if (ok) {
  log(`step 9: the drill project is down (volume removed)`);
  log(`PASSED: ${results.length} assertions`);
}
process.exit(ok ? 0 : 1);
