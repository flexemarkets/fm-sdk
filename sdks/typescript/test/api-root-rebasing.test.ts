/**
 * An API root whose links name somewhere other than the host that was dialled.
 *
 * The defect, 2026-08-21: production on `api.adhocmarkets.com` answers
 * `GET /api` with every href spelled `http://`, while the same application on
 * `api.flexemarkets.com` spells them `https://`. The cause was an edge reaching
 * the origin over a plaintext leg, so the server was told the request arrived on
 * HTTP and built its links accordingly. Every call that goes through a link then
 * leaves on plain HTTP and meets the edge's redirect.
 *
 * Following the redirect does not repair it: a 301 on a POST is re-sent as a GET
 * with the body dropped, so an order is never placed and a session never opens.
 * The links have to be pointed back at the host the token came from.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";

import { Flexemarkets, rebaseApiRoot } from "../src/client.ts";
import type { ApiRoot } from "../src/hal.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

function rootNaming(origin: string): ApiRoot {
  return {
    links: {
      marketplaces: `${origin}/api/marketplaces`,
      orders: `${origin}/api/orders`,
    },
  };
}

/** Run `action` with console.warn captured rather than printed. */
function onConsoleWarn(action: () => void): string {
  const original = console.warn;
  const lines: string[] = [];
  console.warn = (...args: unknown[]) => {
    lines.push(args.map(String).join(" "));
  };
  try {
    action();
  } finally {
    console.warn = original;
  }
  return lines.join("\n");
}

test("links are moved to the host that was dialled", () => {
  const rebased = onConsoleWarnReturning(() =>
    rebaseApiRoot(rootNaming("http://api.example.com"), "https://api.example.com/api"),
  );

  assert.deepEqual(rebased.links, {
    marketplaces: "https://api.example.com/api/marketplaces",
    orders: "https://api.example.com/api/orders",
  });
});

test("a port is part of the origin and moves with it", () => {
  const rebased = onConsoleWarnReturning(() =>
    rebaseApiRoot(rootNaming("http://127.0.0.1:9999"), "http://127.0.0.1:8080/api"),
  );

  assert.equal(rebased.links.marketplaces, "http://127.0.0.1:8080/api/marketplaces");
});

test("a URI template survives the rewrite", () => {
  const root: ApiRoot = {
    links: { marketplaces: "http://api.example.com/api/marketplaces{?page,size,sort*}" },
  };

  const rebased = onConsoleWarnReturning(() =>
    rebaseApiRoot(root, "https://api.example.com/api"),
  );

  assert.equal(
    rebased.links.marketplaces,
    "https://api.example.com/api/marketplaces{?page,size,sort*}",
  );
});

test("a relative href is left alone", () => {
  const root: ApiRoot = { links: { marketplaces: "/api/marketplaces" } };

  const rebased = rebaseApiRoot(root, "https://api.example.com/api");

  assert.equal(rebased.links.marketplaces, "/api/marketplaces");
});

test("a rewrite says so and names both origins", () => {
  const reported = onConsoleWarn(() => {
    rebaseApiRoot(rootNaming("http://api.example.com"), "https://api.example.com/api");
  });

  assert.match(reported, /http:\/\/api\.example\.com/);
  assert.match(reported, /https:\/\/api\.example\.com/);
  assert.match(reported, /forwarding the request scheme/);
});

test("a root that already agrees is silent", () => {
  const reported = onConsoleWarn(() => {
    rebaseApiRoot(rootNaming("https://api.example.com"), "https://api.example.com/api");
  });

  assert.equal(reported, "");
});

// ---------------------------------------------------------------------------
// End to end, against a real loopback server: the unit tests above would all
// still pass if the rewrite were never wired into the connection.
// ---------------------------------------------------------------------------

test("a read through a link stays on the host that was dialled", async () => {
  const originRequests: string[] = [];
  const decoyRequests: string[] = [];

  const decoy = http.createServer((req, res) => {
    decoyRequests.push(`${req.method} ${req.url}`);
    res.writeHead(401, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "not this host" }));
  });
  await listen(decoy);
  const decoyBase = `http://127.0.0.1:${(decoy.address() as AddressInfo).port}`;

  const origin = http.createServer((req, res) => {
    originRequests.push(`${req.method} ${req.url}`);
    res.writeHead(200, { "Content-Type": "application/json" });

    if (req.method === "POST") {
      res.end(JSON.stringify({
        token: TOKEN,
        person: { id: 7, accountId: 1, email: "dev@dev" },
        account: { id: 1, name: "dev" },
      }));
    } else if (req.url === "/api") {
      // Every link names the decoy, which is what a server behind a
      // misconfigured edge does.
      res.end(JSON.stringify({
        _links: {
          marketplaces: { href: `${decoyBase}/api/marketplaces{?page,size,sort*}` },
        },
      }));
    } else if (req.url?.startsWith("/api/marketplaces/1/markets")) {
      res.end(JSON.stringify([{ id: 11, symbol: "STK", name: "Stock" }]));
    } else {
      res.end("[]");
    }
  });
  await listen(origin);
  const base = `http://127.0.0.1:${(origin.address() as AddressInfo).port}/api`;

  try {
    const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "rebase-test");
    try {
      const markets = await fm.markets(1);
      assert.deepEqual(markets.map((m) => m.symbol), ["STK"]);
    } finally {
      await fm.close();
    }

    assert.ok(originRequests.some((r) => r.startsWith("GET /api/marketplaces/1/markets")));
    assert.deepEqual(decoyRequests, [], "nothing was sent to the host the links named");
  } finally {
    origin.close();
    decoy.close();
  }
});

function listen(server: http.Server): Promise<void> {
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
}

/** The rewrite warns by design; these cases assert the result, not the noise. */
function onConsoleWarnReturning<T>(action: () => T): T {
  let result!: T;
  onConsoleWarn(() => {
    result = action();
  });
  return result;
}
