/**
 * Route fixtures: where each client call goes.
 *
 * See `sdks/fixtures/routes/README.md`. Each case names a call, its arguments,
 * and the requests it must send, in order. The call runs against a loopback
 * server that answers `/api/tokens*` and, for the rest, exactly the requests
 * the case lists -- anything else fails the case, `GET /api` above all: 0.4
 * reads no HAL root, and this is what keeps it from drifting back.
 *
 * The wire fixtures pin what a payload means; these pin where a call goes, the
 * half that broke every SDK at once when fm-server 4.5.6 dropped three links
 * from its root.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

import { Flexemarkets } from "../src/client.ts";
import type { Holding } from "../src/types.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

const ROUTES = join(
  fileURLToPath(new URL(".", import.meta.url)), "..", "..", "fixtures", "routes", "routes.json",
);

interface ExpectedRequest {
  method: string;
  path: string;
  query?: Record<string, string>;
  contentType?: string;
  accept?: string;
  body?: unknown;
  response: { status: number; body?: unknown; contentType?: string };
}

interface Case {
  call: string;
  args: Record<string, unknown>;
  why: string;
  requests: ExpectedRequest[];
  returns?: unknown;
}

const CASES = (JSON.parse(readFileSync(ROUTES, "utf8")) as { cases: Case[] }).cases;

// --- named arguments onto this SDK's signatures --------------------------------

type Args = Record<string, any>;

/** A `csv` argument is file content; the SDK takes a path. */
function csvFile(content: string): string {
  const dir = mkdtempSync(join(tmpdir(), "fm-sdk-routes-"));
  const path = join(dir, "upload.csv");
  writeFileSync(path, content);
  return path;
}

/** A fixture holding is the fields that matter; the rest take their opening values. */
function holding(marketplaceId: number, h: Args): Holding {
  return {
    marketplaceId,
    sessionId: 0,
    allocationId: 0,
    ownerId: h.ownerId,
    name: h.name,
    cash: h.cash,
    availableCash: h.cash,
    securities: (h.securities ?? []).map((s: Args) => ({
      marketId: s.marketId,
      units: s.units,
      availableUnits: s.units,
      shortUnits: 0,
      canBuy: true,
      canSell: true,
    })),
  };
}

const RUNNERS: Record<string, (fm: Flexemarkets, a: Args) => Promise<unknown>> = {
  marketplaces: (fm) => fm.marketplaces(),
  marketplace: (fm, a) => fm.marketplace(a.marketplaceId),
  markets: (fm, a) => fm.markets(a.marketplaceId),
  symbols: (fm, a) => fm.symbols(a.marketplaceId),
  sessions: (fm, a) => fm.sessions(a.marketplaceId),
  session: (fm, a) => fm.session(a.marketplaceId),
  orders: (fm, a) =>
    a.symbol !== undefined || a.sessionIds !== undefined
      ? fm.orders(a.marketplaceId, { symbol: a.symbol, sessionIds: a.sessionIds })
      : fm.orders(a.marketplaceId),
  trades: (fm, a) => fm.trades(a.marketplaceId, a.symbol),
  activeOrders: (fm, a) => fm.activeOrders(a.marketplaceId),
  recentTrades: (fm, a) => fm.recentTrades(a.marketplaceId, a.size),
  holdings: (fm, a) => fm.holdings(a.marketplaceId, a.sessionIds),
  holding: (fm, a) => fm.holding(a.marketplaceId),
  connections: (fm, a) => fm.connections(a.marketplaceId),
  identifiers: (fm, a) => fm.identifiers(a.marketplaceId),
  users: (fm) => fm.users(),
  userById: (fm, a) => fm.userById(a.userId),
  accountById: (fm, a) => fm.accountById(a.accountId),
  accounts: (fm) => fm.accounts(),
  allotments: (fm, a) => fm.allotments(a.marketplaceId, a.allocationId),
  downloadHoldings: (fm, a) => fm.downloadHoldings(a.marketplaceId, a.sessionIds),
  submitLimit: (fm, a) => fm.submitLimit(a.marketplaceId, a.marketId, a.side, a.units, a.price),
  submitCancel: (fm, a) => fm.submitCancel(a.marketplaceId, a.marketId, a.originalId),
  openSession: (fm, a) => fm.openSession(a.marketplaceId),
  pauseSession: (fm, a) => fm.pauseSession(a.marketplaceId),
  closeSession: (fm, a) => fm.closeSession(a.marketplaceId),
  createMarketplaceFromJson: (fm, a) => fm.createMarketplaceFromJson(a.json),
  deleteMarketplace: (fm, a) => fm.deleteMarketplace(a.marketplaceId),
  createMarket: (fm, a) =>
    fm.createMarket(a.marketplaceId, a.symbol, a.name, a.price, a.units, a.privateMarket),
  allocate: (fm, a) =>
    fm.allocate(a.marketplaceId, a.holdings.map((h: Args) => holding(a.marketplaceId, h))),
  uploadHoldings: (fm, a) => fm.uploadHoldings(a.marketplaceId, csvFile(a.csv)),
  uploadState: (fm, a) => fm.uploadState(a.marketplaceId, csvFile(a.csv)),
  removeWidget: (fm, a) => fm.removeWidget(a.marketplaceId, a.key, a.userId),
  allWidgets: (fm, a) => fm.allWidgets(a.marketplaceId),
  signup: (fm, a) => fm.signup(a.accountName, a.email, a.password),
  approveAccount: (fm, a) => fm.approveAccount(a.accountName),
  deleteMyAccount: (fm) => fm.deleteMyAccount(),
  deleteAccount: (fm, a) => fm.deleteAccount(a.accountId),
  createUser: (fm, a) => fm.createUser(a.email, a.password, a.firstName, a.lastName, a.roles),
  deleteUser: (fm, a) => fm.deleteUser(a.userId),
  managerOtpBundle: (fm, a) => fm.managerOtpBundle(a.userIds),
};

// --- comparing a request with what the case lists ------------------------------

/**
 * The query as the server reads it: split on the raw `&` and `=`, then each
 * name and value decoded -- as Java and Python compare it. A raw comparison
 * would fail a symbol the client rightly encodes (`S%26P`), and a decoded one
 * still fails an unencoded `&`, which splits the value before decoding.
 */
function rawQuery(url: string): Record<string, string> {
  const at = url.indexOf("?");
  const query: Record<string, string> = {};
  if (at < 0) return query;
  for (const pair of url.substring(at + 1).split("&")) {
    if (!pair) continue;
    const eq = pair.indexOf("=");
    const decode = (part: string) => decodeURIComponent(part.replace(/\+/g, " "));
    query[decode(eq < 0 ? pair : pair.substring(0, eq))] = eq < 0 ? "" : decode(pair.substring(eq + 1));
  }
  return query;
}

/** Why this request is not the one expected, or null if it is. */
function mismatch(
  req: http.IncomingMessage, body: string, expected: ExpectedRequest,
): string | null {
  const url = req.url ?? "";
  const path = url.split("?")[0];
  const problems: string[] = [];

  if (req.method !== expected.method) problems.push(`method ${req.method}, expected ${expected.method}`);
  if (path !== expected.path) problems.push(`path ${path}, expected ${expected.path}`);
  try {
    assert.deepEqual(rawQuery(url), expected.query ?? {});
  } catch {
    problems.push(`query ${JSON.stringify(rawQuery(url))}, expected ${JSON.stringify(expected.query ?? {})}`);
  }
  if (expected.contentType !== undefined) {
    const sent = (req.headers["content-type"] ?? "").split(";")[0].trim();
    if (sent !== expected.contentType) {
      problems.push(`content type ${sent || "(none)"}, expected ${expected.contentType}`);
    }
  }
  if (expected.accept !== undefined) {
    const sent = req.headers.accept ?? "";
    if (!sent.split(",").some((t) => t.split(";")[0].trim() === expected.accept)) {
      problems.push(`accept ${sent || "(none)"} does not name ${expected.accept}`);
    }
  }
  if (expected.body !== undefined) {
    const want = expected.body;
    try {
      if (typeof want === "string") {
        assert.equal(body, want);
      } else if (want !== null && typeof want === "object" && !Array.isArray(want)) {
        // An object is compared on the fields named: a client may send more.
        const sent = JSON.parse(body) as Record<string, unknown>;
        for (const [field, value] of Object.entries(want)) {
          assert.deepEqual(sent[field], value, `field ${field}`);
        }
      } else {
        assert.deepEqual(JSON.parse(body), want);
      }
    } catch (e) {
      problems.push(`body ${body} does not match ${JSON.stringify(want)} (${(e as Error).message})`);
    }
  }
  return problems.length === 0 ? null : problems.join("; ");
}

function answer(res: http.ServerResponse, response: ExpectedRequest["response"]): void {
  if (response.body === undefined) {
    res.writeHead(response.status);
    res.end();
    return;
  }
  const json = response.contentType === undefined;
  res.writeHead(response.status, { "Content-Type": response.contentType ?? "application/json" });
  res.end(json ? JSON.stringify(response.body) : String(response.body));
}

// --- one test per case ---------------------------------------------------------

async function run(c: Case): Promise<void> {
  const runner = RUNNERS[c.call];
  assert.ok(runner, `no runner for '${c.call}': add it to RUNNERS`);

  const failures: string[] = [];
  let next = 0;

  const server = http.createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (chunk) => chunks.push(chunk as Buffer));
    req.on("end", () => {
      const url = req.url ?? "";
      if (url.startsWith("/api/tokens")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({
          token: TOKEN,
          person: { id: 7, accountId: 1, email: "dev@dev", roles: ["ROLE_ADMIN"] },
          account: { id: 1, name: "dev" },
        }));
        return;
      }

      const expected = c.requests[next];
      const problem = expected === undefined
        ? "not listed"
        : mismatch(req, Buffer.concat(chunks).toString("utf8"), expected);
      if (problem !== null) {
        failures.push(`#${next + 1} ${req.method} ${url}: ${problem}`);
        res.writeHead(599, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ message: `unexpected request: ${problem}` }));
        return;
      }
      next++;
      answer(res, expected.response);
    });
  });

  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`;
  try {
    const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "route-fixtures");
    let result: unknown;
    let thrown: unknown = null;
    try {
      result = await runner(fm, c.args);
    } catch (e) {
      thrown = e;
    } finally {
      fm.close();
    }

    assert.deepEqual(failures, [], `requests the case does not list`);
    if (thrown !== null) throw thrown;
    assert.equal(next, c.requests.length,
      `sent ${next} of the ${c.requests.length} requests listed`);
    if ("returns" in c) assert.deepEqual(result, c.returns);
  } finally {
    server.close();
  }
}

for (const c of CASES) {
  test(`${c.call} ${JSON.stringify(c.args)}`, () => run(c));
}

test("there are route cases to run", () => {
  assert.ok(CASES.length >= 40, `only found ${CASES.length} cases`);
});
