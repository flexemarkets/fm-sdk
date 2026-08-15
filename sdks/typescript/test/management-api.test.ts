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
          person: { id: 7, accountId: 1, email: "dev@dev" },
          account: { id: 1, name: "dev" },
        });
      } else if (url === "/api") {
        send({
          _links: {
            marketplaces: { href: `${api()}/marketplaces` },
            usersJson: { href: `${api()}/usersJson` },
          },
        });
      } else if (url === "/api/v1/marketplaces" && req.method === "POST") {
        send({ id: 77, name: "simple-dividend", markets: [] });
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
    securities: [{ marketId: 10, units: 50, availableUnits: 50, canBuy: true, canSell: true }],
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
