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

import { Flexemarkets } from "../src/client.ts";
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

let server: Server;
let port: number;
let requests: string[] = [];
let bodies = new Map<string, string>();

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

      const send = (payload: unknown, type = "application/json") => {
        const body = typeof payload === "string" ? payload : JSON.stringify(payload);
        res.writeHead(200, { "Content-Type": type });
        res.end(body);
      };

      const url = req.url ?? "";
      if (url.startsWith("/api/tokens")) {
        send({
          token: TOKEN,
          person: { id: 7, accountId: 1, email: "dev@dev", roles: ["ROLE_MANAGER"] },
          account: { id: 1, name: "dev" },
        });
      } else if (url === "/api") {
        send({
          _links: {
            marketplaces: { href: `${api()}/marketplaces` },
            accounts: { href: `${api()}/accounts` },
            users: { href: `${api()}/users` },
            symbolTradesJson: { href: `${api()}/symbolTradesJson` },
            usersJson: { href: `${api()}/usersJson` },
          },
        });
      } else if (url === "/api/v1/marketplaces" && req.method === "POST") {
        send({ id: 77, name: "simple-dividend", markets: [] });
      } else if (url.startsWith("/api/v1/marketplaces/1/sessions")
                 || url.startsWith("/api/marketplaces/1/sessions")) {
        send([{ id: 300, state: "CLOSED" }]);
      } else if (url.startsWith("/api/marketplaces/1/connections")) {
        send([{ id: 9, ownerId: 8, marketplaceId: 1, sessionId: 300 }]);
      } else if (url.startsWith("/api/symbolTradesJson")) {
        // The symbol-keyed route answers with the trade id in "original" and
        // no symbol on the order.
        send([{ id: 0, original: 4242, units: 5, price: 950 }]);
      } else if (url === "/api/approvals" && req.method === "POST") {
        send({ account: { id: 2, name: "acme", approval: true }, approve: true });
      } else if (url === "/api/otp/manager" && req.method === "POST") {
        send({
          expiresAt: "2026-08-15T18:00:00Z",
          otps: [{ userId: 1, email: "alice@lab.edu", otp: "123456" }],
        });
      } else if (url === "/api/accounts" && req.method === "POST") {
        send({ token: TOKEN, person: { id: 8 }, account: { id: 2, name: "acme" } });
      } else if (url.startsWith("/api/accounts")) {
        send([{ id: 1, name: "dev" }, { id: 2, name: "acme" }]);
      } else if (url.startsWith("/api/users/") && req.method === "DELETE") {
        res.writeHead(204);
        res.end();
      } else if ((url === "/api/v1/users" || url === "/api/users") && req.method === "POST") {
        send({ id: 42, accountId: 1, email: "alice@lab.edu" });
      } else if (url === "/api/usersJson") {
        send([{ id: 7, email: "dev@dev" }, { id: 8, email: "t1@dev" }]);
      } else if (url.startsWith("/api/marketplaces/1/holdings/downloads")) {
        send("owner,cash\nalice,10000\n", "text/csv");
      } else if (url.endsWith("/open")) {
        send({ id: 99, marketplaceId: 1, state: "OPEN" });
      } else if (url.endsWith("/pause")) {
        send({ id: 99, marketplaceId: 1, state: "PAUSED" });
      } else if (url.endsWith("/close")) {
        send({ id: 99, marketplaceId: 1, state: "CLOSED" });
      } else {
        send(ALLOTMENTS);
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
});

async function connect() {
  return Flexemarkets.connect(TOKEN, `${api()}/marketplaces/1`, "management-test");
}

test("session transitions are PATCHes to their own routes", async () => {
  const fm = await connect();
  try {
    assert.equal((await fm.openSession(1)).state, "OPEN");
    assert.equal((await fm.pauseSession(1)).state, "PAUSED");
    assert.equal((await fm.closeSession(1)).state, "CLOSED");
  } finally {
    fm.close();
  }

  assert.ok(requests.includes("PATCH /api/marketplaces/1/open"));
  assert.ok(requests.includes("PATCH /api/marketplaces/1/pause"));
  assert.ok(requests.includes("PATCH /api/marketplaces/1/close"));
});

test("users reads the JSON route rather than the HAL one", async () => {
  const fm = await connect();
  try {
    const users = await fm.users();
    assert.equal(users.length, 2);
    assert.equal(users[1].email, "t1@dev");
  } finally {
    fm.close();
  }

  assert.ok(requests.includes("GET /api/usersJson"));
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

  assert.ok(requests.includes("GET /api/v1/marketplaces/1/allotments?allocation=42"));
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

  const body = bodies.get("POST /api/marketplaces/1/allocations") ?? "";
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

test("uploadHoldings posts the file as multipart", async () => {
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

  const body = bodies.get("POST /api/marketplaces/1/holdings/uploads") ?? "";
  assert.ok(body.includes('name="file"'), "the part must be named 'file'");
  assert.ok(body.includes('filename="holdings.csv"'), "and carry the file's own name");
  assert.ok(body.includes("owner,cash"));
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
    (bodies.get("POST /api/marketplaces/1/allocations") ?? "").includes('"shortUnits":50'),
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

  assert.ok(!requests.some((r) => r.includes("sessionIds=")));
  // sessions moved to V1, which needs no format= to avoid HAL; connections has
  // no V1 equivalent with these semantics and stays on V0 for now.
  assert.ok(requests.some((r) => r.includes("/api/v1/marketplaces/1/sessions")));
  assert.ok(requests.some((r) => r.includes("/api/marketplaces/1/connections?format=")));
});

test("the holdings download filters on sessions", async () => {
  const fm = await connect();
  try {
    await fm.downloadHoldings(1, [300]);
  } finally {
    fm.close();
  }

  assert.ok(requests.some((r) => r.includes("/holdings/downloads?sessions=300")));
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
 * Trades come back with the trade id in `original` and no symbol, because the
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

  assert.ok(requests.some((r) => r.includes("symbol=STK")));
});

/** An empty filter means "now", and asks for no filter at all. */
test("empty filters fall back to the unfiltered routes", async () => {
  const fm = await connect();
  try {
        await fm.downloadHoldings(1, []);
  } finally {
    fm.close();
  }

  assert.ok(!requests.some((r) => r.includes("sessionIds=")));
  assert.ok(!requests.some((r) => r.includes("?sessions=")));
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

  const body = bodies.get("POST /api/accounts")!;
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

  assert.ok(bodies.get("POST /api/approvals")!.includes('"approval":true'));
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

  assert.ok(requests.some((r) => r === "DELETE /api/users/42"), requests.join(", "));
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

  const body = bodies.get("POST /api/marketplaces/1/markets")!;
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

  const body = bodies.get("POST /api/marketplaces/1/markets")!;
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

  assert.ok(bodies.get("POST /api/otp/manager")!.includes('"userIds":[1,2]'));
});

test("isAdmin reads the roles on the token", async () => {
  const fm = await connect();
  try {
    assert.equal(fm.isAdmin(), false, "ROLE_MANAGER is not ROLE_ADMIN");
    assert.equal(fm.token().token, TOKEN);
  } finally {
    fm.close();
  }
});
