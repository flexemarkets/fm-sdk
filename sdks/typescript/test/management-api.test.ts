/**
 * The management surface: session transitions, the account roster, and staging
 * opening positions.
 *
 * Mirrors the Java SDK's ManagementApiTest and the Python
 * test_management_api.py. Asserted against a real loopback server rather than a
 * stubbed fetch, because what matters is the request that actually goes out --
 * above all the field names in its body -- and a stub would assert only that
 * the client called itself the way this test expected.
 */

import { test, before, after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { Flexemarkets, HttpError, InvalidArgumentError } from "../src/client.ts";
import type { Holding } from "../src/types.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

/** One allotment, spelling the positions the way the server does: "grants". */
const ALLOTMENTS = [
  {
    id: 5,
    allocationId: 42,
    marketplaceId: 1,
    ownerId: 8,
    name: "alice",
    assets: { cash: 10000, grants: [{ marketId: 10, units: 50 }] },
  },
];

/** One stored widget per scope, as fm-server answers a push or a read of all. */
const WIDGETS = [
  {
    createdDate: "2026-10-01T09:00:00", lastModifiedDate: "2026-10-01T09:00:05",
    id: 31, marketplaceId: 1, scope: "MARKETPLACE", key: "score", title: "Score",
    content: { kind: "text", lines: ["round 1"] },
  },
  {
    id: 32, marketplaceId: 1, scope: "USER", userId: 8, key: "values",
    emphasis: "strong", ttlSeconds: 60,
    content: { kind: "kv", items: [{ label: "value", value: 120, format: "price" }] },
  },
];

/**
 * One robot as fm-server's catalogue answers it: a manifest with its parameter
 * groups, and one configured parameter with a control.
 */
const ROBOTS = [
  {
    manifest: {
      id: "fm-maker-mvo", version: "0.11.1", name: "MVO maker",
      parameters: {
        manager: [{ name: "penalty", type: "option", valueType: "decimal",
                    required: null, default: "0.03", min: 0 }],
        user: [],
      },
    },
    enabled: true,
    configuration: [{
      id: 4, marketplaceId: 1, robotId: "fm-maker-mvo", paramName: "penalty",
      value: "0.005", control: { min: 0.001, max: 0.009, step: 0.001 }, exposed: true,
    }],
  },
];

let server: Server;
let port: number;
let requests: string[] = [];
let bodies = new Map<string, string>();
let contentTypes = new Map<string, string>();

function api(): string {
  return `http://127.0.0.1:${port}/api`;
}

before(async () => {
  server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (c) => chunks.push(c as Buffer));
    req.on("end", () => {
      const key = `${req.method} ${req.url}`;
      requests.push(key);
      bodies.set(key, Buffer.concat(chunks).toString("utf-8"));
      contentTypes.set(key, req.headers["content-type"] ?? "");

      const send = (payload: unknown, type = "application/json") => {
        const body = typeof payload === "string" ? payload : JSON.stringify(payload);
        res.writeHead(200, { "Content-Type": type });
        res.end(body);
      };

      const url = req.url ?? "";
      const mp = "/api/v1/marketplaces/1";
      if (url.startsWith("/api/v1/tokens")) {
        send({
          token: TOKEN,
          person: { id: 7, accountId: 1, email: "dev@dev", roles: ["ROLE_MANAGER"] },
          account: { id: 1, name: "dev" },
        });
      } else if (url.startsWith("/api/v1/marketplaces/2/")) {
        // A marketplace that is not there: the 404 carries a failure document.
        res.writeHead(404, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "MARKETPLACE_NOT_FOUND", message: "no marketplace 2" }));
      } else if (req.method === "DELETE" && url.includes("/widgets/")) {
        // 204 for the key that exists, an empty 404 for one that does not.
        res.writeHead(url.endsWith("/score") ? 204 : 404);
        res.end();
      } else if (url === `${mp}/widgets?participant=all`
                 || (url === `${mp}/widgets` && req.method === "POST")) {
        send(WIDGETS);
      } else if (url === `${mp}/robots`) {
        send(ROBOTS);
      } else if (url === `${mp}/robots/fm-maker-mvo/parameters/penalty` && req.method === "PUT") {
        // Answered with what was stored: exposed exactly when the body carried bounds.
        const sent = JSON.parse(bodies.get(key) || "{}") as Record<string, unknown>;
        const exposed = "min" in sent;
        send({
          id: 4, marketplaceId: 1, robotId: "fm-maker-mvo", paramName: "penalty",
          value: sent.value, exposed,
          ...(exposed ? { control: { min: sent.min, max: sent.max, step: sent.step } } : {}),
        });
      } else if (url === "/api/v1/marketplaces" && req.method === "POST") {
        send({ id: 77, name: "simple-dividend", markets: [] });
      } else if (url === `${mp}/sessions/current` && req.method === "PATCH") {
        const state = (JSON.parse(bodies.get(key) || "{}") as { state?: string }).state;
        send({ id: 99, marketplaceId: 1, state });
      } else if (url.startsWith(`${mp}/sessions`)) {
        send([{ id: 300, state: "CLOSED" }]);
      } else if (url.startsWith(`${mp}/connections`)) {
        send([{ id: 9, ownerId: 8, marketplaceId: 1, sessionId: 300 }]);
      } else if (url.startsWith(`${mp}/orders?state=TRADED`)) {
        // The symbol-keyed read answers with the trade id in "original" and
        // no symbol on the order.
        send([{ id: 0, original: 4242, units: 5, price: 950 }]);
      } else if (url === "/api/v1/accounts/2/approvals" && req.method === "POST") {
        res.writeHead(201, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ account: { id: 2, name: "acme", approval: true }, approve: true }));
      } else if (url === "/api/v1/otp/manager" && req.method === "POST") {
        send({
          expiresAt: "2026-08-15T18:00:00Z",
          otps: [{ userId: 1, email: "alice@lab.edu", otp: "123456" }],
        });
      } else if (url === "/api/v1/accounts" && req.method === "POST") {
        send({ token: TOKEN, person: { id: 8 }, account: { id: 2, name: "acme" } });
      } else if (url === "/api/v1/accounts") {
        send([{ id: 1, name: "dev" }, { id: 2, name: "acme" }]);
      } else if (url.startsWith("/api/v1/users/") && req.method === "DELETE") {
        res.writeHead(204);
        res.end();
      } else if (url === "/api/v1/users" && req.method === "POST") {
        send({ id: 42, accountId: 1, email: "alice@lab.edu" });
      } else if (url === "/api/v1/users") {
        send([{ id: 7, email: "dev@dev" }, { id: 8, email: "t1@dev" }]);
      } else if (url.startsWith(`${mp}/holdings`) && req.headers.accept?.includes("text/csv")) {
        send("owner,cash\nalice,10000\n", "text/csv");
      } else if (url.startsWith(`${mp}/holdings`)) {
        send([{ ownerId: 8, name: "alice", cash: 10000 }]);
      } else if (url === `${mp}/markets` && req.method === "POST") {
        send({ id: 10, marketplaceId: 1, symbol: "STK" });
      } else if (url === `${mp}/allocations` || url === `${mp}/allocations/42/allotments`) {
        send(ALLOTMENTS);
      } else {
        res.writeHead(404);
        res.end();
      }
    });
  });

  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  port = (server.address() as { port: number }).port;
});

after(() => server.close());

beforeEach(() => {
  requests = [];
  bodies = new Map();
  contentTypes = new Map();
});

async function connect() {
  return Flexemarkets.connect(TOKEN, `${api()}/marketplaces/1`, "management-test");
}

test("session transitions PATCH the current session's state", async () => {
  const fm = await connect();
  try {
    assert.equal((await fm.openSession(1)).state, "OPEN");
    assert.equal((await fm.pauseSession(1)).state, "PAUSED");
    assert.equal((await fm.closeSession(1)).state, "CLOSED");
  } finally {
    fm.close();
  }

  assert.deepEqual(requests.filter((r) => r.startsWith("PATCH")),
    Array(3).fill("PATCH /api/v1/marketplaces/1/sessions/current"));
  // The stub answers with the state the body asked for, so the states read back
  // above are the bodies that went out.
});

test("users reads the V1 route", async () => {
  const fm = await connect();
  try {
    const users = await fm.users();
    assert.equal(users.length, 2);
    assert.equal(users[1].email, "t1@dev");
  } finally {
    fm.close();
  }

  assert.deepEqual(requests.filter((r) => !r.includes("/tokens")), ["GET /api/v1/users"]);
});

test("allotments are read from the V1 route for one allocation", async () => {
  const fm = await connect();
  try {
    const allotments = await fm.allotments(1, 42);
    assert.equal(allotments.length, 1);
    assert.equal(allotments[0].assets?.securities[0].units, 50);
  } finally {
    fm.close();
  }

  assert.ok(requests.includes("GET /api/v1/marketplaces/1/allocations/42/allotments"));
});

/**
 * The failure this guards is silent. The server reads opening positions from
 * `grants`; send `securities` and it finds none, creates the allocation with
 * the cash and no positions, and answers 200 — an experiment whose
 * participants hold nothing.
 */
test("allocate sends positions as grants", async () => {
  const holding: Holding = {
    marketplaceId: 1, sessionId: 0, allocationId: 0, ownerId: 8, name: "alice",
    cash: 10000, availableCash: 10000,
    securities: [{ marketId: 10, units: 50, availableUnits: 50, shortUnits: 0, canBuy: true, canSell: true }],
  };

  const fm = await connect();
  try {
    await fm.allocate(1, [holding]);
  } finally {
    fm.close();
  }

  const body = bodies.get("POST /api/v1/marketplaces/1/allocations") ?? "";
  assert.ok(body.includes('"grants"'), "the server reads opening positions from 'grants'");
  assert.ok(!body.includes('"securities"'));
  assert.ok(body.includes('"cash":10000'));
});

test("allocate returns what the server created", async () => {
  const holding: Holding = {
    marketplaceId: 1, sessionId: 0, allocationId: 0, ownerId: 8, name: "alice",
    cash: 10000, availableCash: 10000, securities: [],
  };

  const fm = await connect();
  try {
    const created = await fm.allocate(1, [holding]);
    assert.equal(created.length, 1);
    assert.equal(created[0].ownerId, 8);
    assert.equal(created[0].allocationId, 42);
    assert.equal(created[0].cash, 10000);
    // An opening position has committed nothing, and predates the session it
    // will be opened under.
    assert.equal(created[0].availableCash, 10000);
    assert.equal(created[0].sessionId, 0);
    assert.equal(created[0].securities[0].marketId, 10);
  } finally {
    fm.close();
  }
});

/** A CSV, returned as-is. JSON.parse would die on the header row. */
test("downloadHoldings returns the CSV verbatim", async () => {
  const fm = await connect();
  try {
    assert.equal(await fm.downloadHoldings(1), "owner,cash\nalice,10000\n");
  } finally {
    fm.close();
  }
});

test("uploadHoldings posts the file itself as text/csv", async () => {
  const dir = mkdtempSync(join(tmpdir(), "fm-sdk-test-"));
  const csv = join(dir, "holdings.csv");
  writeFileSync(csv, "owner,cash\nalice,10000\n");

  const fm = await connect();
  try {
    const created = await fm.uploadHoldings(1, csv);
    assert.equal(created.length, 1);
  } finally {
    fm.close();
  }

  // Format by header, not a multipart part: the body is the file, whole.
  const key = "POST /api/v1/marketplaces/1/allocations";
  assert.equal(bodies.get(key), "owner,cash\nalice,10000\n");
  assert.equal(contentTypes.get(key), "text/csv");
});

test("a marketplace is created from its JSON definition", async () => {
  const fm = await connect();
  let created;
  try {
    created = await fm.createMarketplaceFromJson(
      '{"name":"simple-dividend","markets":[{"symbol":"STK"}]}');
  } finally {
    fm.close();
  }

  assert.equal(created.id, 77);
  assert.ok(requests.includes("POST /api/v1/marketplaces"));
  assert.ok(
    (bodies.get("POST /api/v1/marketplaces") ?? "").includes('"STK"'),
    "the definition is forwarded, not rebuilt",
  );
});

/**
 * Parsed before it is sent, so a bad definition fails here rather than as a
 * 400 whose message is about a document the caller cannot see.
 */
test("malformed marketplace JSON fails before any request", async () => {
  const fm = await connect();
  try {
    await assert.rejects(
      () => fm.createMarketplaceFromJson("{not json"),
      /not valid JSON/,
    );
  } finally {
    fm.close();
  }

  assert.ok(!requests.includes("POST /api/v1/marketplaces"));
});

/**
 * fm-server's Asset emits initialShortUnits for a live session; the allotments
 * path emits shortUnits. Before this field existed both were dropped in
 * silence, so a participant permitted to short 50 read as one permitted to
 * short nothing.
 */
test("a short allowance is read under either name and sent as shortUnits", async () => {
  const holding: Holding = {
    marketplaceId: 1, sessionId: 0, allocationId: 0, ownerId: 8, name: "alice",
    cash: 10000, availableCash: 10000,
    securities: [{ marketId: 10, units: 5, availableUnits: 55, shortUnits: 50, canBuy: true, canSell: true }],
  };

  const fm = await connect();
  let allotments;
  try {
    await fm.allocate(1, [holding]);
    allotments = await fm.allotments(1, 42);
  } finally {
    fm.close();
  }

  assert.ok(
    (bodies.get("POST /api/v1/marketplaces/1/allocations") ?? "").includes('"shortUnits":50'),
    "requests carry shortUnits",
  );
  // The stub answers with "grants" carrying neither spelling, so absent must
  // read as none rather than undefined — callers do arithmetic on this.
  assert.equal(allotments[0].assets?.securities[0].shortUnits, 0);
});

/**
 * The filter is spelled `sessionIds` here and `sessions` on the holdings
 * download. Getting it wrong is not an error -- it is an unfiltered answer that
 * looks right until someone checks the totals.
 */
/**
 * The SDK no longer pretends these filter.
 *
 * This asserted the opposite: that `sessions(1, ids)` put `?sessionIds=` on the
 * wire. It did — and the server ignored it. `GET /marketplaces/{id}/sessions`
 * and `/connections` accept only `format`, so the answer was the whole history
 * looking like a filtered one. Asserting the request without asserting the
 * response is how a defect becomes a requirement.
 */
test("sessions and connections are never filtered on the wire", async () => {
  const fm = await connect();
  try {
    await fm.sessions(1);
    await fm.connections(1);
  } finally {
    fm.close();
  }

  assert.deepEqual(requests.filter((r) => !r.includes("/tokens")), [
    "GET /api/v1/marketplaces/1/sessions",
    "GET /api/v1/marketplaces/1/connections",
  ]);
});

test("the holdings download filters on sessions", async () => {
  const fm = await connect();
  try {
    await fm.downloadHoldings(1, [300]);
  } finally {
    fm.close();
  }

  assert.ok(requests.includes("GET /api/v1/marketplaces/1/holdings?sessions=300"));
});

/**
 * A connection belongs to a session, and that is how a study works out who was
 * present in a run. The field was absent until 0.0.11, so every connection read
 * as belonging to none.
 */
test("a connection carries its session", async () => {
  const fm = await connect();
  try {
    const connections = await fm.connections(1);
    assert.equal(connections.length, 1);
    assert.equal(connections[0]!.sessionId, 300);
  } finally {
    fm.close();
  }
});

/**
 * Tape come back with the trade id in `original` and no symbol, because the
 * query already fixed it. Both are filled in, so the result is a trade list
 * rather than half-populated orders.
 */
test("trades carry their id and symbol", async () => {
  const fm = await connect();
  try {
    const trades = await fm.trades(1, "STK");
    assert.equal(trades.length, 1);
    assert.equal(trades[0]!.id, 4242, "the trade id, taken from original");
    assert.equal(trades[0]!.symbol, "STK");
  } finally {
    fm.close();
  }

  assert.ok(requests.includes("GET /api/v1/marketplaces/1/orders?state=TRADED&symbol=STK&limit=5000"));
});

/** An empty filter means "now", and asks for no filter at all. */
test("empty filters fall back to the unfiltered routes", async () => {
  const fm = await connect();
  try {
    await fm.downloadHoldings(1, []);
    await fm.holdings(1, []);
  } finally {
    fm.close();
  }

  assert.deepEqual(requests.filter((r) => !r.includes("/tokens")),
    Array(2).fill("GET /api/v1/marketplaces/1/holdings"));
});

/**
 * The owner's credentials go out as ownerEmail/ownerPassword. The plausible
 * guess -- email/password -- creates an account with an owner the server
 * cannot sign in as, and answers 200 while doing it.
 */
test("signup names the owner's credentials the way the server reads them", async () => {
  const fm = await connect();
  try {
    const created = await fm.signup("acme", "owner@new", "s3cret", "Ada", "Lovelace");
    assert.equal(created.account?.name, "acme");
  } finally {
    fm.close();
  }

  const body = bodies.get("POST /api/v1/accounts")!;
  assert.ok(body.includes('"ownerEmail":"owner@new"'));
  assert.ok(body.includes('"ownerPassword":"s3cret"'));
  assert.ok(!body.includes('"email":"owner@new"'));
});

test("accounts are listed and approved", async () => {
  const fm = await connect();
  try {
    assert.equal((await fm.accounts()).length, 2);
    const approved = await fm.approveAccount("acme");
    assert.equal(approved?.name, "acme");
  } finally {
    fm.close();
  }

  // V1 approves by id, so the name is looked up first.
  assert.deepEqual(requests.filter((r) => !r.includes("/tokens")), [
    "GET /api/v1/accounts",
    "GET /api/v1/accounts",
    "POST /api/v1/accounts/2/approvals",
  ]);
  assert.deepEqual(JSON.parse(bodies.get("POST /api/v1/accounts/2/approvals")!), { approve: true });
});

/**
 * V1 approves by id. A name that matches no account must not fall through to
 * approving some other one -- or to a POST at all.
 */
test("approving an account no one holds is an invalid argument", async () => {
  const fm = await connect();
  try {
    await assert.rejects(fm.approveAccount("nobody"),
      (e: unknown) => e instanceof InvalidArgumentError && /nobody/.test((e as Error).message));
  } finally {
    fm.close();
  }
  assert.ok(!requests.some((r) => r.startsWith("POST /api/v1/accounts/")));
});

test("a user is created with the roles given", async () => {
  const fm = await connect();
  try {
    const created = await fm.createUser("alice@lab.edu", "pw", "Alice", "Anderson", ["ROLE_MANAGER"]);
    assert.equal(created.id, 42);
  } finally {
    fm.close();
  }

  const body = bodies.get("POST /api/v1/users")!;
  assert.ok(body.includes('"roles":["ROLE_MANAGER"]'));
});

/**
 * The verb is the whole request for a delete: a POST to the same path would
 * answer 2xx and leave the user standing.
 */
test("deletes use the DELETE verb", async () => {
  const fm = await connect();
  try {
    await fm.deleteUser(42);
  } finally {
    fm.close();
  }

  assert.ok(requests.some((r) => r === "DELETE /api/v1/users/42"), requests.join(", "));
});

/** Omitting the unit grid keeps the old fixed default; a market without unit
 *  bounds rejects every order. */
test("createMarket defaults the unit grid when it is not given", async () => {
  const fm = await connect();
  try {
    await fm.createMarket(1, "STK", "Stock", { minimum: 0, maximum: 10000, tick: 1 });
  } finally {
    fm.close();
  }

  const body = bodies.get("POST /api/v1/marketplaces/1/markets")!;
  assert.ok(body.includes('"unitMinimum":1'));
  assert.ok(body.includes('"unitMaximum":100'));
  assert.ok(body.includes('"unitTick":1'));
});

/**
 * The defect: unit bounds were fixed at 1/100/1 with no way to say otherwise,
 * on a call that set the price grid three arguments earlier. The server
 * enforces the two identically, so a market needing lots of ten could not be
 * made here at all.
 */
test("unit bounds are the caller's too", async () => {
  const fm = await connect();
  try {
    await fm.createMarket(1, "STK", "Stock",
      { minimum: 100, maximum: 200, tick: 25 },
      { minimum: 10, maximum: 500, tick: 10 });
  } finally {
    fm.close();
  }

  const body = bodies.get("POST /api/v1/marketplaces/1/markets")!;
  assert.ok(body.includes('"unitMinimum":10'), body);
  assert.ok(body.includes('"unitMaximum":500'));
  assert.ok(body.includes('"unitTick":10'));
});

test("otp bundles are minted for the users asked", async () => {
  const fm = await connect();
  try {
    const bundle = await fm.managerOtpBundle([1, 2]);
    assert.equal(bundle.expiresAt?.toISOString(), "2026-08-15T18:00:00.000Z");
    assert.equal(bundle.otps.length, 1);
    assert.equal(bundle.otps[0]!.otp, "123456");
  } finally {
    fm.close();
  }

  assert.ok(bodies.get("POST /api/v1/otp/manager")!.includes('"userIds":[1,2]'));
});

test("isAdmin reads the roles on the token", async () => {
  const fm = await connect();
  try {
    assert.equal(fm.isAdmin(), false, "ROLE_MANAGER is not ROLE_ADMIN");
    assert.equal(fm.isManager(), true);
    assert.equal(fm.token().token, TOKEN);
  } finally {
    fm.close();
  }
});

test("pushWidgets posts one array", async () => {
  // A list goes up as one JSON array, whatever its length -- the server's rate
  // limit counts requests, and sixty single pushes at session open is what it
  // is there to refuse. The target travels nested, as the server reads it, and
  // a marketplace target carries no userId.
  const fm = await connect();
  let stored;
  try {
    stored = await fm.pushWidgets(1, [
      { key: "score", title: "Score", target: { scope: "MARKETPLACE" },
        content: { kind: "text", lines: ["round 1"] } },
      { key: "values", emphasis: "strong", ttlSeconds: 60, target: { scope: "USER", userId: 8 },
        content: { kind: "kv", items: [{ label: "value", value: 120 }] } },
    ]);
  } finally {
    fm.close();
  }

  assert.deepEqual(requests.filter((r) => r.startsWith("POST") && !r.includes("/tokens")),
    ["POST /api/v1/marketplaces/1/widgets"]);
  const body = JSON.parse(bodies.get("POST /api/v1/marketplaces/1/widgets") ?? "null");
  assert.ok(Array.isArray(body) && body.length === 2);
  assert.deepEqual(body[0].target, { scope: "MARKETPLACE" });
  assert.deepEqual(body[1].target, { scope: "USER", userId: 8 });
  assert.equal(body[1].ttlSeconds, 60);
  assert.ok(!("title" in body[1]), "an absent title is absent, not null");

  assert.deepEqual(stored.map((w) => w.id), [31, 32]);
  assert.equal(stored[0].lastModifiedDate?.getTime(), Date.UTC(2026, 9, 1, 9, 0, 5));
  assert.equal(stored[1].userId, 8);
  assert.equal(stored[1].ttlSeconds, 60);
});

test("allWidgets reads the manager route", async () => {
  const fm = await connect();
  try {
    const widgets = await fm.allWidgets(1);
    assert.deepEqual(widgets.map((w) => w.key), ["score", "values"]);
    assert.equal(widgets[1].content.kind, "kv");
  } finally {
    fm.close();
  }
  assert.ok(requests.includes("GET /api/v1/marketplaces/1/widgets?participant=all"));
});

test("marketplaceRobots reads the catalogue", async () => {
  // The catalogue reads as the server sends it: the manifest's groups, the
  // default spelled "default", a null "required" as false, a group the
  // manifest leaves out as empty, and the control's decimals as decimals.
  const fm = await connect();
  try {
    const robots = await fm.marketplaceRobots(1);
    assert.equal(robots.length, 1);
    const robot = robots[0];
    assert.equal(robot.enabled, true);
    assert.equal(robot.manifest.id, "fm-maker-mvo");
    const penalty = robot.manifest.parameters.manager[0];
    assert.equal(penalty.defaultValue, "0.03");
    assert.equal(penalty.required, false);
    assert.equal(penalty.max, null);
    assert.deepEqual(robot.manifest.parameters.platform, []);
    const config = robot.configuration[0];
    assert.equal(config.exposed, true);
    assert.equal(config.value, "0.005");
    assert.deepEqual(config.control, { min: 0.001, max: 0.009, step: 0.001 });
  } finally {
    fm.close();
  }
  assert.ok(requests.includes("GET /api/v1/marketplaces/1/robots"));
});

test("configureRobotParameter fixes without a control", async () => {
  // A fixed parameter sends the value alone; the server reads any bound as a
  // control, so sending nulls for them would not be the same request.
  const fm = await connect();
  const key = "PUT /api/v1/marketplaces/1/robots/fm-maker-mvo/parameters/penalty";
  try {
    const stored = await fm.configureRobotParameter(1, "fm-maker-mvo", "penalty", "0.03", null);
    assert.equal(stored.exposed, false);
    assert.equal(stored.control, null);
  } finally {
    fm.close();
  }
  assert.equal(requests.filter((r) => r.startsWith("PUT ")).length, 1);
  assert.deepEqual(JSON.parse(bodies.get(key) ?? ""), { value: "0.03" });
});

test("configureRobotParameter sends the control flat", async () => {
  const fm = await connect();
  const key = "PUT /api/v1/marketplaces/1/robots/fm-maker-mvo/parameters/penalty";
  try {
    const stored = await fm.configureRobotParameter(1, "fm-maker-mvo", "penalty", "0.03",
      { min: 0.01, max: 0.05, step: 0.01 });
    assert.equal(stored.exposed, true);
    assert.deepEqual(stored.control, { min: 0.01, max: 0.05, step: 0.01 });
  } finally {
    fm.close();
  }
  assert.deepEqual(JSON.parse(bodies.get(key) ?? ""), { value: "0.03", min: 0.01, max: 0.05, step: 0.01 });
});

test("removeWidget answers whether anything was there", async () => {
  // 204 is removed and an empty 404 is nothing to remove: both are answers,
  // and a robot clearing a key it may never have pushed should not have to
  // catch an exception to find out which.
  const fm = await connect();
  try {
    assert.equal(await fm.removeWidget(1, "score"), true);
    assert.equal(await fm.removeWidget(1, "absent"), false);
    assert.equal(await fm.removeWidget(1, "score", 8), true);
  } finally {
    fm.close();
  }
  assert.ok(requests.includes("DELETE /api/v1/marketplaces/1/widgets/score"));
  assert.ok(requests.includes("DELETE /api/v1/marketplaces/1/widgets/absent"));
  assert.ok(requests.includes("DELETE /api/v1/marketplaces/1/participants/8/widgets/score"));
});

test("removeWidget throws a 404 that says something else", async () => {
  // A 404 carrying a failure document is not "nothing there" -- it is a
  // marketplace that does not exist, and reading it as false would let a robot
  // pointed at the wrong marketplace believe its clean-up worked.
  const fm = await connect();
  try {
    await assert.rejects(fm.removeWidget(2, "score"),
      (e: unknown) => e instanceof HttpError && e.statusCode === 404);
  } finally {
    fm.close();
  }
});
