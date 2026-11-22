import assert from "node:assert/strict";
import test from "node:test";

import { assertNoAdrRows, assertSampleDataTarget } from "./sample-data-target.mjs";

const container = (name, project, service, ports = {}) => ({
  Name: `/${name}`,
  Config: {
    Labels: {
      "com.docker.compose.project": project,
      "com.docker.compose.service": service,
    },
  },
  State: { Running: true },
  NetworkSettings: { Ports: ports },
});

const appPorts = (hostPort) => ({
  "8090/tcp": [{ HostIp: "127.0.0.1", HostPort: String(hostPort) }],
});

test("accepts an alternate local Compose project when its app publishes the selected URL", () => {
  assert.doesNotThrow(() =>
    assertSampleDataTarget({
      base: "http://127.0.0.1:8592",
      pgContainer: "demo-postgres",
      appContainer: "demo-app",
      containers: [container("demo-postgres", "demo", "postgres"), container("demo-app", "demo", "app", appPorts(8592))],
    }),
  );
});

test("refuses containers from different Compose projects", () => {
  assert.throws(
    () =>
      assertSampleDataTarget({
        base: "http://localhost:8090",
        pgContainer: "other-postgres",
        appContainer: "hammurabi-app",
        containers: [container("other-postgres", "other", "postgres"), container("hammurabi-app", "hammurabi", "app", appPorts(8090))],
      }),
    /different Compose projects/,
  );
});

test("refuses an app container that does not publish the selected API port", () => {
  assert.throws(
    () =>
      assertSampleDataTarget({
        base: "http://localhost:8592",
        pgContainer: "hammurabi-postgres",
        appContainer: "hammurabi-app",
        containers: [container("hammurabi-postgres", "hammurabi", "postgres"), container("hammurabi-app", "hammurabi", "app", appPorts(8090))],
      }),
    /does not publish .*8592/,
  );
});

test("refuses a matching port published on a different loopback address", () => {
  const postgres = container("hammurabi-postgres", "hammurabi", "postgres");
  const app = container("hammurabi-app", "hammurabi", "app", {
    "8090/tcp": [{ HostIp: "127.0.0.2", HostPort: "8090" }],
  });
  assert.throws(
    () => assertSampleDataTarget({ base: "http://127.0.0.1:8090", pgContainer: "hammurabi-postgres", appContainer: "hammurabi-app", containers: [postgres, app] }),
    /does not publish .*127\.0\.0\.1:8090/,
  );
});

test("refuses wrongly labelled services", () => {
  const postgres = container("hammurabi-postgres", "hammurabi", "database");
  const app = container("hammurabi-app", "hammurabi", "app", appPorts(8090));
  assert.throws(
    () =>
      assertSampleDataTarget({
        base: "http://localhost:8090",
        pgContainer: "hammurabi-postgres",
        appContainer: "hammurabi-app",
        containers: [postgres, app],
      }),
    /Compose service 'postgres'/,
  );
});

test("refuses a stopped configured container", () => {
  const postgres = container("hammurabi-postgres", "hammurabi", "postgres");
  const app = container("hammurabi-app", "hammurabi", "app", appPorts(8090));
  app.State.Running = false;
  assert.throws(
    () =>
      assertSampleDataTarget({
        base: "http://localhost:8090",
        pgContainer: "hammurabi-postgres",
        appContainer: "hammurabi-app",
        containers: [postgres, app],
      }),
    /is not running/,
  );
});

test("accepts an empty adrs table, whatever psql's trailing whitespace", () => {
  assert.doesNotThrow(() => assertNoAdrRows("0\n"));
  assert.doesNotThrow(() => assertNoAdrRows(" 0 "));
});

test("refuses any existing adrs row, soft-deleted ones included", () => {
  assert.throws(() => assertNoAdrRows("1\n"), /already has 1 ADR row\(s\) \(soft-deleted ones included\)/);
  assert.throws(() => assertNoAdrRows("16"), /already has 16 ADR row/);
});

test("refuses an unreadable row count instead of treating it as empty", () => {
  for (const output of ["", "\n", "ERROR: relation does not exist", "-1", "1.5", "0 0"]) {
    assert.throws(() => assertNoAdrRows(output), /could not read the ADR row count/, JSON.stringify(output));
  }
});
