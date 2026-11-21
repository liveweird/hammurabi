#!/usr/bin/env node
// Loads a demo data set into a LOCAL Hammurabi stack (`docker compose up --build`), so the app can be assessed with content:
// 10 sample users (two ADMINs, two Polish-language), 7 teams, 1 new tag category (plus the seeded two, extended), 16 ADRs covering every status (ACTIVE,
// IN_REVIEW, drafts, sent back, CANCELLED, WITHDRAWN twice, EXPIRED, SUPERSEDED, a pending successor), discussion entries,
// issues and escalations, and 4 saved lenses.
//
// Everything goes through the public API as the acting user, so every rule, history event and notice is the real one. Two
// steps cannot be done through the API and touch the database directly (`docker exec hammurabi-postgres psql`):
//   1. one ADR's expiry date is moved into the past and the app is restarted, so the expiry sweep expires it the real way;
//   2. at the end every timestamp written by this run is spread over the past year along the story's timeline (one
//      monotonic mapping for all tables, so the order of events and status_changed_at == its TRANSITIONED event still hold).
//
// Usage: node scripts/sample-data.mjs            (refuses unless the stack is on localhost and has no ADRs yet)
// Env:   HAMMURABI_URL (default http://localhost:8090), ADMIN_PASSWORD (default changeme),
//        PG_CONTAINER (default hammurabi-postgres), APP_CONTAINER (default hammurabi-app). Both containers must belong to the Compose
//        project whose app service publishes HAMMURABI_URL; this is checked before the first write.
//
// Every sample account signs in with the password below (a local demo value, never a real one).
import { execFileSync } from 'node:child_process';
import { inspectSampleDataTarget } from './sample-data-target.mjs';

const BASE = process.env.HAMMURABI_URL ?? 'http://localhost:8090';
const ADMIN_EMAIL = 'admin@hammurabi.local';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD ?? 'changeme';
const SAMPLE_PASSWORD = 'hammurabi-sample';
const PG = process.env.PG_CONTAINER ?? 'hammurabi-postgres';
const APP = process.env.APP_CONTAINER ?? 'hammurabi-app';
const DAY_MS = 86_400_000;

if (!/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(BASE)) {
  throw new Error(`Refusing to seed ${BASE}: sample data is for a local stack only`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const isoDate = (daysFromToday) => new Date(Date.now() + daysFromToday * DAY_MS).toISOString().slice(0, 10);

// ---------- HTTP ----------
const passwords = new Map([[ADMIN_EMAIL, ADMIN_PASSWORD]]);
const tokens = new Map();
const userIds = new Map();

async function login(email) {
  const res = await fetch(`${BASE}/api/v1/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email, password: passwords.get(email) }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok || !body.token) throw new Error(`login ${email}: ${res.status} ${JSON.stringify(body)}`);
  tokens.set(email, body.token);
  userIds.set(email, body.userId);
  return body.token;
}

async function api(email, method, path, body, revision) {
  const token = tokens.get(email) ?? (await login(email));
  const headers = { authorization: `Bearer ${token}` };
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (revision !== undefined) headers['x-expected-revision'] = String(revision);
  const res = await fetch(`${BASE}/api/v1${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} as ${email}: ${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

// ---------- the story clock ----------
// at(daysAgo) pins "now" in the story; the remap at the end maps real time between two pins linearly onto story time.
const marks = [];
async function at(daysAgo) {
  await sleep(3);
  if (marks.length && daysAgo > marks.at(-1)[1]) throw new Error(`story goes backwards at ${daysAgo}`);
  marks.push([Date.now(), daysAgo]);
  await sleep(3);
}

// ---------- domain helpers ----------
const U = {}; // key -> { id, email, name }

const get = (as, adr) => api(U[as].email, 'GET', `/adrs/${adr.id}`);

async function create(as, doc, people = {}, tags = []) {
  const participants = Object.entries(people).flatMap(([role, keys]) =>
    [keys].flat().map((k) => ({ userId: U[k].id, role })),
  );
  const adr = await api(U[as].email, 'POST', '/adrs', { ...doc, participants });
  if (tags.length) await api(U[as].email, 'PUT', `/adrs/${adr.id}/tags`, { tags }, adr.revision);
  return adr;
}

async function move(as, adr, to, reason) {
  const fresh = await get(as, adr);
  return api(U[as].email, 'POST', `/adrs/${adr.id}/transitions`, reason ? { to, reason } : { to }, fresh.revision);
}

async function edit(as, adr, changes) {
  const f = await get(as, adr);
  const doc = { name: f.name, problem: f.problem };
  for (const k of ['context', 'decision', 'alternatives', 'consequences', 'expiresOn']) if (f[k] != null) doc[k] = f[k];
  return api(U[as].email, 'PUT', `/adrs/${adr.id}`, { ...doc, ...changes }, f.revision);
}

async function addPeople(as, adr, role, keys) {
  const f = await get(as, adr);
  const set = f.participants.map(({ userId, role: r }) => ({ userId, role: r }));
  for (const k of [keys].flat()) set.push({ userId: U[k].id, role });
  return api(U[as].email, 'PUT', `/adrs/${adr.id}/participants`, { participants: set }, f.revision);
}

const post = (as, adr, kind, body) => api(U[as].email, 'POST', `/adrs/${adr.id}/discussion`, { kind, body });
const settle = (as, adr, entry, state, resolution) =>
  api(U[as].email, 'PUT', `/adrs/${adr.id}/discussion/${entry.id}/state`, resolution ? { state, resolution } : { state });

async function linkPredecessor(as, successor, predecessor) {
  const f = await get(as, successor);
  return api(U[as].email, 'PUT', `/adrs/${successor.id}/supersedes`, { supersedesId: predecessor.id }, f.revision);
}
const acceptSuccessor = (as, predecessor, successor) =>
  api(U[as].email, 'POST', `/adrs/${predecessor.id}/successor/accept`, { successorId: successor.id });

// ---------- database (two steps only) ----------
function psql(sql) {
  return execFileSync('docker', ['exec', '-i', PG, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'hammurabi', '-d', 'hammurabi', '-Atq'], {
    input: sql,
    encoding: 'utf8',
  });
}

async function waitHealthy() {
  for (let i = 0; i < 120; i++) {
    try {
      const res = await fetch(`${BASE}/api/v1/ready`);
      if (res.ok) return;
    } catch {
      /* still starting */
    }
    await sleep(1000);
  }
  throw new Error('the app did not come back after the restart');
}

// ---------- the data ----------
const PEOPLE = [
  ['alice', 'Alice Novak', 'en', true], // Principal Architect
  ['oliver', 'Oliver Brandt', 'en', true], // CTO
  ['bartosz', 'Bartosz Wiśniewski', 'pl', false], // Platform lead
  ['chioma', 'Chioma Okafor', 'en', false], // Payments engineering manager
  ['daniel', 'Daniel Reyes', 'en', false], // Staff engineer, backend
  ['eva', 'Eva Lindqvist', 'en', false], // Security lead
  ['farid', 'Farid Haddad', 'en', false], // Data engineer
  ['grace', 'Grace Kim', 'en', false], // Web lead
  ['hiroshi', 'Hiroshi Tanaka', 'en', false], // SRE
  ['magdalena', 'Magdalena Zielińska', 'pl', false], // Mobile lead
];

const TEAMS = [
  ['Architecture', 'The architecture guild: cross-team standards and the decision log.', 'alice'],
  ['Platform', 'Kubernetes, CI/CD, observability and the paved road.', 'bartosz'],
  ['Payments', 'Checkout, billing and the payment service providers.', 'chioma'],
  ['Security', 'Application security, identity and compliance.', 'eva'],
  ['Data', 'The warehouse, pipelines and event streaming.', 'farid'],
  ['Web', 'The customer web apps and the design system.', 'grace'],
  ['Mobile', 'The iOS and Android apps.', 'magdalena'],
];

const DOMAIN_TAGS = ['integration', 'frontend', 'infrastructure', 'security', 'data', 'backend', 'mobile', 'on-premises'];

async function main() {
  // The API creates ids later used in direct SQL, and APP is restarted for the real expiry sweep. Prove all three targets are one stack
  // before the first API mutation, including when the caller selected an alternate local Compose project.
  inspectSampleDataTarget(BASE, PG, APP);
  await login(ADMIN_EMAIL);
  const existing = await api(ADMIN_EMAIL, 'GET', '/adrs?pageSize=1');
  if (existing.total > 0) throw new Error(`The stack already has ${existing.total} ADR(s); sample data goes into an empty one only`);
  U.admin = { id: userIds.get(ADMIN_EMAIL), email: ADMIN_EMAIL, name: 'admin' };

  // ---- the organisation (a year ago) ----
  await at(360);
  for (const [key, name, language, isAdmin] of PEOPLE) {
    const email = `${key}@hammurabi.local`;
    const user = await api(ADMIN_EMAIL, 'POST', '/users', {
      name,
      email,
      password: SAMPLE_PASSWORD,
      roles: isAdmin ? ['ADMIN'] : [],
      language,
    });
    U[key] = { id: user.id, email, name };
    passwords.set(email, SAMPLE_PASSWORD);
  }
  for (const [name, description, manager] of TEAMS) {
    await api(ADMIN_EMAIL, 'POST', '/teams', { name, description, managerId: U[manager].id });
  }
  // The V13 seed ships "Domain" and "Scope"; Domain gains the areas the story needs, and one new category is added.
  const seeded = (await api(ADMIN_EMAIL, 'GET', '/tag-categories')).items;
  const domain = seeded.find((c) => c.name === 'Domain');
  if (!domain || !seeded.some((c) => c.name === 'Scope')) throw new Error('expected the seeded Domain and Scope tag categories');
  await api(
    ADMIN_EMAIL,
    'PUT',
    `/tag-categories/${domain.id}`,
    { name: domain.name, description: domain.description ?? null, tags: DOMAIN_TAGS },
    domain.revision,
  );
  await api(ADMIN_EMAIL, 'POST', '/tag-categories', {
    name: 'Quality attribute',
    description: 'The quality the decision trades for or protects.',
    tags: ['performance', 'reliability', 'cost', 'maintainability', 'compliance', 'usability'],
  });

  // ---- ADR-0001 PostgreSQL (ACTIVE since ~10 months) ----
  await at(330);
  const pg = await create(
    'daniel',
    {
      name: 'Use PostgreSQL as the primary relational datastore',
      problem:
        'Every new service picks its own database. We run MySQL 5.7, two PostgreSQL versions and a MongoDB cluster, and nobody can be on call for all of them.',
    },
    { APPROVER: 'alice', CONTRIBUTOR: 'bartosz', INFORMED: 'farid' },
    ['backend', 'data', 'organisation', 'reliability'],
  );
  await at(328);
  await move('daniel', pg, 'SOLUTION_DRAFT');
  await edit('daniel', pg, {
    context:
      'Four database engines in production, one DBA, and three incidents last quarter caused by unfamiliar failover behaviour.\n\n- MySQL 5.7 reaches end of life this year.\n- The data team needs logical replication into the warehouse.\n- The platform team can offer **one** managed engine with backups and point-in-time recovery.',
    decision:
      'New services use **PostgreSQL** (the managed offering, currently 16). Existing services migrate when they next need a schema change of size, not before.\n\nDocument stores are allowed only with an ADR of their own.',
    alternatives:
      '- **MySQL 8**: the upgrade path is shorter, but the JSON and full-text support is weaker.\n- **Keep the status quo**: no migration cost, but the on-call load stays.\n- **CockroachDB**: attractive multi-region story, licence and cost unclear.',
    consequences:
      '- One backup, restore and upgrade runbook.\n- Teams on MySQL carry a migration in their roadmap.\n- We lose nothing we rely on today; JSONB covers the document use-cases we have.',
  });
  await at(325);
  await post('farid', pg, 'COMMENT', 'Logical replication is exactly what the warehouse ingestion needs. Strong +1 from Data.');
  const pgQ = await post(
    'hiroshi',
    pg,
    'QUESTION',
    'Who owns the point-in-time recovery drills? Today nobody has restored a MongoDB backup in a year.',
  );
  await at(323);
  await settle('daniel', pg, pgQ, 'ANSWERED');
  await post('daniel', pg, 'COMMENT', '@Hiroshi Platform runs a quarterly restore drill per cluster; added to the consequences.');
  await at(320);
  await move('daniel', pg, 'IN_REVIEW');
  await at(316);
  await move('alice', pg, 'ACTIVE');

  // ---- ADR-0002 Kotlin (ACTIVE; later a successor is proposed) ----
  await at(305);
  const kotlin = await create(
    'daniel',
    {
      name: 'Kotlin for new backend services',
      problem: 'Which language do new JVM backend services start in?',
      context:
        'Half the backend is Java 11, a quarter Kotlin, the rest Scala from an acquisition. Hiring for Scala is hard and the Java code is full of null checks.',
      decision: 'New backend services are written in **Kotlin** on the JVM 21 runtime. Java stays allowed in existing services.',
      alternatives: '- **Java 21**: records and pattern matching close part of the gap.\n- **Go**: different runtime, different tooling, retraining.',
      consequences:
        '- A shared Gradle convention plugin and a detekt configuration.\n- Interop with the Java libraries stays seamless.\n- Scala services are frozen and rewritten only when they need larger changes.',
    },
    { APPROVER: 'oliver', CONTRIBUTOR: 'alice', INFORMED: ['bartosz', 'chioma'] },
    ['backend', 'organisation', 'maintainability'],
  );
  await at(304);
  await move('daniel', kotlin, 'IN_REVIEW');
  await post('grace', kotlin, 'COMMENT', 'Does this include the BFF services the web team owns? We would rather keep those in TypeScript.');
  await at(302);
  await post(
    'daniel',
    kotlin,
    'COMMENT',
    'Good point: the BFFs are out of scope, this is about the JVM services. I will make that explicit.',
  );
  await edit('daniel', kotlin, {
    decision:
      'New **JVM** backend services are written in **Kotlin** on the JVM 21 runtime. Java stays allowed in existing services. The TypeScript BFFs are out of scope.',
  });
  await at(298);
  await move('oliver', kotlin, 'ACTIVE');

  // ---- ADR-0003 REST + OpenAPI (ACTIVE, an open issue and a resolved escalation) ----
  await at(285);
  const rest = await create(
    'alice',
    {
      name: 'REST with a hand-written OpenAPI contract for service APIs',
      problem: 'Service-to-service and client APIs are described in five different ways, and two of them only in code.',
      expiresOn: isoDate(300),
    },
    { APPROVER: 'oliver', CONTRIBUTOR: ['daniel', 'grace'], INFORMED: 'magdalena' },
    ['backend', 'frontend', 'mobile', 'organisation', 'maintainability'],
  );
  await at(283);
  await move('alice', rest, 'SOLUTION_DRAFT');
  await edit('alice', rest, {
    context:
      'Clients break on undocumented changes; generated docs drift from the code; gRPC is used by two teams, and the mobile apps cannot use it directly.',
    decision:
      'Every service API is **REST/JSON** described by a **hand-maintained OpenAPI 3** document in the repository, linted in CI against the API guidelines. Clients generate their types from it.',
    alternatives: '- **gRPC everywhere**, with a gateway for the browsers and the apps.\n- **Code-first** generated specs.',
    consequences:
      '- The spec is reviewed with the code; a contract change is visible in the diff.\n- Teams on gRPC keep it internally and expose REST at the edge.\n- We need a guideline document and a linter ruleset.',
  });
  await at(280);
  await move('alice', rest, 'IN_REVIEW');
  await post('daniel', rest, 'COMMENT', 'Spectral has a ruleset format we can version next to the guidelines. I can set it up.');
  await at(276);
  await move('oliver', rest, 'ACTIVE');

  // ---- ADR-0004 Elasticsearch logs (ACTIVE, superseded later) ----
  await at(262);
  const elk = await create(
    'hiroshi',
    {
      name: 'Centralise logs in a self-hosted Elasticsearch cluster',
      problem: 'Logs live on each node and vanish with the pod. Incident analysis means SSH and grep.',
    },
    { APPROVER: 'bartosz', CONTRIBUTOR: 'daniel' },
    ['infrastructure', 'on-premises', 'reliability', 'organisation'],
  );
  await at(260);
  await move('hiroshi', elk, 'SOLUTION_DRAFT');
  await edit('hiroshi', elk, {
    context: 'We run in our own data centre; sending logs to a SaaS needs a data-processing agreement we do not have yet.',
    decision: 'Ship container logs with Fluent Bit to a three-node **Elasticsearch** cluster with Kibana, 14 days retention.',
    consequences: '- Platform runs and upgrades the cluster.\n- Disk sizing is our problem; a noisy service can fill it.',
  });
  await at(257);
  await move('hiroshi', elk, 'IN_REVIEW');
  await at(252);
  await move('bartosz', elk, 'ACTIVE');

  // ---- ADR-0005 config-file feature flags (ACTIVE, later WITHDRAWN) ----
  await at(242);
  const flags = await create(
    'grace',
    {
      name: 'Feature flags as configuration files in the repository',
      problem: 'How do teams switch unfinished features off in production?',
      context: 'We have no flag service; teams comment code out or keep long-lived branches.',
      decision: 'Flags are YAML files next to the service configuration, read at start-up.',
      consequences: '- No new infrastructure.\n- **A flip needs a deploy.**',
    },
    { APPROVER: 'alice', INFORMED: ['magdalena', 'chioma'] },
    ['frontend', 'backend', 'organisation'],
  );
  await at(240);
  await move('grace', flags, 'IN_REVIEW');
  await at(236);
  await move('alice', flags, 'ACTIVE');

  // ---- ADR-0006 web monorepo (ACTIVE with an expiry; expires later) ----
  await at(222);
  const mono = await create(
    'grace',
    {
      name: 'One monorepo for the web applications',
      problem: 'The four web apps share a design system through published packages, and every change takes three releases.',
      context: 'A trial: the design system and the apps move into one repository with Nx, for two quarters, then we decide again.',
      decision: 'Move the four web apps and the design system into **one monorepo** for a trial period ending with this ADR.',
      alternatives: '- Keep the polyrepo and automate the package bumps with Renovate.',
      consequences: '- One CI pipeline with affected-project builds.\n- Ownership by CODEOWNERS, not by repository.',
      expiresOn: isoDate(60),
    },
    { APPROVER: 'alice', CONTRIBUTOR: 'magdalena' },
    ['frontend', 'team', 'maintainability'],
  );
  await at(220);
  await move('grace', mono, 'IN_REVIEW');
  await at(214);
  await move('alice', mono, 'ACTIVE');

  // ---- ADR-0007 GraphQL gateway (drafted, then CANCELLED) ----
  await at(205);
  const gql = await create(
    'magdalena',
    {
      name: 'A GraphQL gateway for the mobile apps',
      problem: 'The apps make six to nine REST calls to render the home screen, and on a bad network that takes seconds.',
    },
    { APPROVER: 'alice', CONTRIBUTOR: 'grace' },
    ['mobile', 'backend', 'performance'],
  );
  await at(203);
  await move('magdalena', gql, 'SOLUTION_DRAFT');
  await edit('magdalena', gql, {
    decision: 'Introduce an Apollo-based GraphQL gateway in front of the REST services for the apps.',
  });
  await at(200);
  const gqlQ = await post(
    'daniel',
    gql,
    'QUESTION',
    'How does this fit with ADR-0003? A gateway means a second contract to keep in sync with the OpenAPI documents.',
  );
  await post('grace', gql, 'COMMENT', 'The web BFF solved the same problem with two aggregated REST endpoints. Might be enough here too.');
  await at(192);
  await settle('magdalena', gql, gqlQ, 'ANSWERED');
  await move('magdalena', gql, 'CANCELLED', 'Two aggregated BFF endpoints in REST cover the home screen; no second contract. See ADR-0003.');

  // ---- ADR-0008 self-hosted CI runners (IN_REVIEW, WITHDRAWN by the Approver) ----
  await at(175);
  const runners = await create(
    'bartosz',
    {
      name: 'Self-hosted CI runners on our Kubernetes cluster',
      problem: 'Hosted CI minutes cost more every quarter and the builds queue at peak hours.',
      context: 'The bill grew 40% in a year. Our cluster has spare capacity at night, not during the day.',
      decision: 'Run the CI jobs on autoscaled self-hosted runners in the platform cluster.',
      consequences: '- Platform owns the runner images and their security updates.\n- Hosted minutes drop to the release jobs.',
    },
    { APPROVER: 'oliver', CONTRIBUTOR: 'hiroshi', INFORMED: 'eva' },
    ['infrastructure', 'cost', 'organisation'],
  );
  await at(173);
  await move('bartosz', runners, 'IN_REVIEW');
  await post(
    'eva',
    runners,
    'QUESTION',
    'Pull requests from forks would run on our cluster. How are the runners isolated from production workloads?',
  );
  await at(166);
  await move(
    'oliver',
    runners,
    'WITHDRAWN',
    'The cost model does not hold once the daytime capacity and the isolation work are priced in. We keep hosted runners and revisit next year.',
  );

  // ---- ADR-0009 OpenTelemetry + Loki, the successor of ADR-0004 ----
  await at(152);
  const otel = await create(
    'hiroshi',
    {
      name: 'Ship logs and traces through OpenTelemetry to Grafana Loki and Tempo',
      problem: 'The Elasticsearch cluster from ADR-0004 needs a full-time keeper, and we still have no traces.',
    },
    { APPROVER: 'bartosz', CONTRIBUTOR: ['daniel', 'farid'], INFORMED: 'alice' },
    ['infrastructure', 'reliability', 'cost', 'organisation'],
  );
  await at(150);
  await move('hiroshi', otel, 'SOLUTION_DRAFT');
  await linkPredecessor('hiroshi', otel, elk);
  await edit('hiroshi', otel, {
    context:
      'The data-processing agreement with our cloud provider is signed, so managed storage is allowed now. Elasticsearch took 30% of Platform\'s on-call pages last quarter.',
    decision:
      'Services emit logs and traces through the **OpenTelemetry** SDK and Collector; logs go to **Loki**, traces to **Tempo**, both on object storage, 30 days.',
    alternatives: '- Upgrade and keep Elasticsearch.\n- A SaaS observability vendor (cost scales with volume).',
    consequences:
      '- The Elasticsearch cluster is shut down once the migration is done.\n- Log queries move from KQL to LogQL; a cheat sheet is needed.\n- Traces become available for every service on the paved road.',
  });
  await at(146);
  await post('daniel', otel, 'COMMENT', 'The Kotlin services already have the OTel agent from the template, so this is mostly config for us.');
  const otelQ = await post('farid', otel, 'QUESTION', 'Can the data pipelines keep a 90-day retention for their job logs?');
  await at(144);
  await settle('hiroshi', otel, otelQ, 'ANSWERED');
  await post('hiroshi', otel, 'COMMENT', 'Yes: retention is per tenant in Loki; the data tenant gets 90 days.');
  await at(141);
  await move('hiroshi', otel, 'IN_REVIEW');
  await at(138);
  await acceptSuccessor('bartosz', elk, otel);
  await at(136);
  await move('bartosz', otel, 'ACTIVE');
  await at(128);
  // The on-premises tag is retired (removed from the category; ADR-0004 keeps it, shown as retired).
  const domainNow = (await api(ADMIN_EMAIL, 'GET', '/tag-categories')).items.find((c) => c.id === domain.id);
  await api(
    ADMIN_EMAIL,
    'PUT',
    `/tag-categories/${domain.id}`,
    { name: domainNow.name, description: domainNow.description ?? null, tags: DOMAIN_TAGS.filter((t) => t !== 'on-premises') },
    domainNow.revision,
  );

  // ---- issues on the decisions in force ----
  await at(118);
  await post(
    'magdalena',
    rest,
    'ISSUE',
    'The apps need batched reads (several resources per call). The guidelines have no pattern for it, so every team invents one.',
  );
  await at(104);
  const flagIssue = await post(
    'chioma',
    flags,
    'ISSUE',
    'During INC-2291 we needed to switch the new 3-D Secure flow off and it took a full deploy (47 minutes). Flags must be switchable at runtime.',
  );
  await at(102);
  await post(
    'hiroshi',
    flags,
    'ESCALATION',
    'Escalating: this is the second incident in a month made longer by config-file flags.',
  );

  // ---- ADR-0010 freeze API v1 (ACTIVE, expires in ~10 days) ----
  await at(98);
  const v1 = await create(
    'chioma',
    {
      name: 'Freeze the public payments API v1 until its shutdown',
      problem: 'Partners keep asking for features on v1 while v2 has been available for a year.',
      context: '87% of the traffic is on v2. The remaining partners have a contractual shutdown date.',
      decision: 'API v1 receives security fixes only until the shutdown date, which is this ADR\'s expiry date.',
      consequences: '- New features are built on v2 only.\n- Partner management communicates the freeze.',
      expiresOn: isoDate(10),
    },
    { APPROVER: 'oliver', INFORMED: ['alice', 'daniel'] },
    ['backend', 'organisation', 'maintainability'],
  );
  await at(96);
  await move('chioma', v1, 'IN_REVIEW');
  await at(93);
  await move('oliver', v1, 'ACTIVE');

  // ---- ADR-0005 is withdrawn; its issue is resolved ----
  await at(88);
  await move(
    'alice',
    flags,
    'WITHDRAWN',
    'Config-file flags made two incidents longer (INC-2291, INC-2310). Flags move to the runtime flag service; a new ADR follows.',
  );
  await settle('grace', flags, flagIssue, 'RESOLVED', 'The decision was withdrawn; the runtime flag service is being set up by Platform.');

  // ---- ADR-0011 passkeys (reviewed, SENT BACK, drafting again) ----
  await at(80);
  const passkeys = await create(
    'eva',
    {
      name: 'Passkeys for customer sign-in',
      problem: 'Password reuse drives most account takeovers we see, and SMS codes are expensive and phishable.',
    },
    { APPROVER: 'oliver', CONTRIBUTOR: 'chioma', INFORMED: ['magdalena', 'grace'] },
    ['security', 'mobile', 'frontend', 'compliance'],
  );
  await at(78);
  await move('eva', passkeys, 'SOLUTION_DRAFT');
  await edit('eva', passkeys, {
    context:
      'Account takeover cases doubled this year. Strong customer authentication rules apply to payments; passkeys qualify as a possession factor bound to the device.',
    decision: 'Offer **passkeys** (WebAuthn) as the primary sign-in for customers on web and in the apps; passwords stay as a fallback for now.',
    consequences: '- Fewer takeovers and fewer SMS codes.\n- Support needs a flow for customers who lost every device.',
  });
  await at(72);
  await move('eva', passkeys, 'IN_REVIEW');
  const pkQ = await post(
    'magdalena',
    passkeys,
    'QUESTION',
    'Older Android devices (about 9% of our users) have no passkey support. Do they keep SMS codes?',
  );
  await at(66);
  await move(
    'oliver',
    passkeys,
    'SOLUTION_DRAFT',
    'Needs the account-recovery path written down and a rollout plan for devices without passkey support before I can approve it.',
  );
  await at(61);
  await edit('eva', passkeys, {
    alternatives:
      '- **TOTP apps**: cheaper than SMS, still phishable.\n- **Keep SMS** and add risk-based checks.',
    consequences:
      '- Fewer takeovers and fewer SMS codes.\n- Recovery: a verified email plus an identity check at support; documented in the runbook.\n- Devices without passkey support keep SMS codes until their share drops under 2%.',
  });
  await settle('eva', passkeys, pkQ, 'ANSWERED');

  // ---- ADR-0012 event streaming (IN_REVIEW, waiting for Alice) ----
  await at(52);
  const kafka = await create(
    'farid',
    {
      name: 'A managed Kafka platform for domain events',
      problem: 'Services integrate through nightly database exports and polling. The data team needs changes within minutes, not a day later.',
    },
    { APPROVER: 'alice', CONTRIBUTOR: ['daniel', 'chioma'], INFORMED: 'hiroshi' },
    ['data', 'backend', 'organisation', 'reliability'],
  );
  await at(48);
  await move('farid', kafka, 'SOLUTION_DRAFT');
  await edit('farid', kafka, {
    context:
      '- 14 nightly export jobs, three of them failing weekly.\n- Payments wants an outbox so the ledger and the notifications agree.\n- Nobody wants to run ZooKeeper again.',
    decision:
      'Adopt a **managed Kafka** service for domain events. Services publish through a transactional outbox; schemas live in a schema registry with compatibility checks in CI.',
    alternatives: '- **RabbitMQ**: simpler, but no replay.\n- **Postgres logical replication + Debezium** only: no shared event contracts.',
    consequences:
      '- A new paid managed service (budget approved for a pilot).\n- Event schemas become a contract like the OpenAPI documents (ADR-0003).\n- Exports are retired team by team.',
  });
  await at(40);
  await post('daniel', kafka, 'COMMENT', 'The outbox library we use for Payments can be extracted into the service template.');
  await post('chioma', kafka, 'COMMENT', 'Payments is happy to be the pilot. We need exactly-once into the ledger, the outbox gives us that.');
  await at(33);
  await move('farid', kafka, 'IN_REVIEW');
  await post('hiroshi', kafka, 'QUESTION', 'Who is on call for the brokers: Platform, Data, or the vendor only?');
  await at(31);
  const kafkaQ2 = await post('alice', kafka, 'QUESTION', 'What is the plan for the event schema ownership: per producing team, or a central registry team?');
  await at(29);
  await post('farid', kafka, 'COMMENT', 'Per producing team, with the compatibility check as the guard rail. I will add a paragraph.');
  await settle('farid', kafka, kafkaQ2, 'ANSWERED');

  // ---- ADR-0006 expires (the real sweep, after its expiry date is moved into the past) ----
  await at(22);
  psql(`UPDATE adrs SET expires_on = '${isoDate(-23)}' WHERE id = ${mono.id};`);
  console.log('Restarting the app so the expiry sweep runs ...');
  execFileSync('docker', ['restart', APP], { stdio: 'ignore' });
  await waitHealthy();
  tokens.clear();
  for (let i = 0; i < 30 && (await get('alice', mono)).status !== 'EXPIRED'; i++) await sleep(1000);
  if ((await get('alice', mono)).status !== 'EXPIRED') throw new Error('the expiry sweep did not expire the monorepo ADR');

  // ---- ADR-0013 Postgres operator (IN_REVIEW, waiting for the seed admin) ----
  await at(20);
  const operator = await create(
    'bartosz',
    {
      name: 'Run PostgreSQL in Kubernetes with the CloudNativePG operator',
      problem: 'The managed PostgreSQL from ADR-0001 is not available in the new on-premises region.',
      context: 'The new region hosts customer data that must stay in the country. Only our own Kubernetes clusters run there.',
      decision: 'Run PostgreSQL in that region with the **CloudNativePG** operator: three instances per cluster, WAL archive to object storage.',
      consequences: '- Platform operates databases in that region; a second backup runbook.\n- ADR-0001 still holds everywhere else.',
    },
    { APPROVER: 'admin', CONTRIBUTOR: 'hiroshi', INFORMED: ['daniel', 'eva'] },
    ['infrastructure', 'data', 'compliance', 'reliability'],
  );
  await at(16);
  await move('bartosz', operator, 'IN_REVIEW');
  await post('eva', operator, 'COMMENT', 'Please make sure the WAL archive bucket is encrypted with the regional key, not the global one.');

  // ---- ADR-0014 Kotlin 2 (IN_REVIEW, a successor of ADR-0002 waiting for its acceptance) ----
  await at(12);
  const k2 = await create(
    'daniel',
    {
      name: 'Kotlin 2 and the K2 compiler for all backend services',
      problem: 'ADR-0002 allows Java in existing services; two codebases now mix both and the build times hurt.',
    },
    { APPROVER: 'oliver', CONTRIBUTOR: 'alice', INFORMED: ['bartosz', 'chioma', 'farid'] },
    ['backend', 'organisation', 'maintainability', 'performance'],
  );
  await at(11);
  await move('daniel', k2, 'SOLUTION_DRAFT');
  await linkPredecessor('daniel', k2, kotlin);
  await edit('daniel', k2, {
    context: 'The K2 compiler halves our clean build times in the pilot. Mixed Java/Kotlin modules block the switch.',
    decision:
      'All backend services move to **Kotlin 2** with the K2 compiler. New Java code is no longer accepted; existing Java is converted when a module is touched.',
    alternatives: '- Keep ADR-0002 as it is and accept the slower builds.',
    consequences: '- Faster builds and one language on the JVM.\n- Conversion work in the two mixed codebases (estimated four weeks).',
  });
  await at(8);
  await move('daniel', k2, 'IN_REVIEW');
  await post('bartosz', k2, 'COMMENT', 'The CI images already ship the Kotlin 2 toolchain; nothing needed from Platform.');

  // ---- ADR-0015 audit log retention (PROBLEM_RAISED) ----
  await at(5);
  const retention = await create(
    'eva',
    {
      name: 'Retention period for the security audit log',
      problem:
        'The security audit log has no retention period. It is 1.4 TB and growing, and the auditors asked how long we keep it and why.',
    },
    { CONTRIBUTOR: 'farid', INFORMED: 'oliver' },
    ['security', 'compliance', 'cost'],
  );
  await at(4);
  await post('farid', retention, 'QUESTION', 'Do the payment-related entries fall under the 10-year bookkeeping rule, or only the ledger itself?');

  // ---- ADR-0016 search (PROBLEM_RAISED) ----
  await at(3);
  const search = await create(
    'farid',
    {
      name: 'Product search on PostgreSQL full-text instead of Elasticsearch',
      problem: 'With logs gone from Elasticsearch (ADR-0009), product search is the last user of a cluster we no longer want to run.',
    },
    { CONTRIBUTOR: 'daniel', INFORMED: ['grace', 'hiroshi'] },
    ['data', 'backend', 'cost'],
  );
  await at(2);
  await post('hiroshi', search, 'COMMENT', 'Happy to help measure: the search cluster is at 12% CPU on average.');
  await addPeople('farid', search, 'INFORMED', 'magdalena');

  // ---- saved lenses ----
  await at(1);
  await api(U.alice.email, 'POST', '/lenses', {
    name: 'Waiting for my approval',
    visibility: 'PRIVATE',
    filters: { status: ['IN_REVIEW'], participantId: U.alice.id, participantRole: 'APPROVER' },
  });
  await api(U.eva.email, 'POST', '/lenses', { name: 'Security decisions', visibility: 'PUBLIC', filters: { tag: ['security'] } });
  await api(U.oliver.email, 'POST', '/lenses', { name: 'Decisions in force', visibility: 'PUBLIC', filters: { status: ['ACTIVE'] } });
  await api(U.hiroshi.email, 'POST', '/lenses', {
    name: 'Infrastructure with open issues',
    visibility: 'PUBLIC',
    filters: { tag: ['infrastructure'], hasOpenIssues: true },
  });
  await at(0.02);

  remapTimestamps();
  console.log(`Loaded 10 users, 7 teams, 1 new tag category (plus the seeded two, extended), 16 ADRs and 4 lenses into ${BASE}.`);
  console.log(`Sample accounts: alice, oliver (ADMINs), bartosz, chioma, daniel, eva, farid, grace, hiroshi, magdalena @hammurabi.local.`);
}

// Spread every timestamp this run wrote over the story's timeline: real time between two pins maps linearly onto the
// story days between them; anything before the first pin (the seed admin, earlier rows) is left alone.
function remapTimestamps() {
  const now = Date.now();
  const [r0] = marks[0];
  const story = (d) => `${now} - (${d} * ${DAY_MS})`;
  let expr = `CASE WHEN ts IS NULL OR ts < ${r0} THEN ts`;
  for (let i = 0; i + 1 < marks.length; i++) {
    const [ra, da] = marks[i];
    const [rb, db] = marks[i + 1];
    expr += ` WHEN ts < ${rb} THEN round(${story(`(${da} + (ts - ${ra})::numeric / ${rb - ra} * (${db} - ${da}))`)})::bigint`;
  }
  expr += ` ELSE round(${story(marks.at(-1)[1])})::bigint END`;
  const columns = {
    adrs: ['created_at', 'updated_at', 'status_changed_at'],
    adr_events: ['created_at'],
    adr_participants: ['added_at'],
    adr_tags: ['added_at'],
    adr_discussion: ['created_at', 'edited_at', 'state_changed_at', 'deleted_at'],
    adr_expiry_reminders: ['sent_at'],
    lenses: ['created_at', 'updated_at'],
    notifications: ['created_at', 'emailed_at', 'seen_at'],
    tag_categories: ['created_at', 'updated_at'],
    teams: ['created_at', 'updated_at'],
  };
  let sql = `BEGIN;\nCREATE FUNCTION pg_temp.remap(ts bigint) RETURNS bigint LANGUAGE sql IMMUTABLE AS $$ SELECT ${expr} $$;\n`;
  for (const [table, cols] of Object.entries(columns)) {
    sql += `UPDATE ${table} SET ${cols.map((c) => `${c} = pg_temp.remap(${c})`).join(', ')};\n`;
  }
  sql += 'COMMIT;\n';
  psql(sql);
}

main().catch((e) => {
  console.error(e.message ?? e);
  process.exit(1);
});
