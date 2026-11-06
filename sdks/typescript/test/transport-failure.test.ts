/**
 * What a caller meets when the exchange never completes.
 *
 * A caller catching FlexemarketsError is told that is every failure the SDK
 * throws. fetch's bare TypeError escaped past it, from every call: a robot
 * guarding its order loop that way ended on the first connection reset. Mirrors
 * the Java SDK's TransportFailureTest and the Python test_transport_failure.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";

import { ApiError, Flexemarkets } from "../src/client.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

type Step = "hang-up" | "cut-off" | "unreadable" | "place";

/** Answers sign-in, and each other request by the next step: "place" when they run out. */
async function serving(plan: Step[]) {
  const seen: string[] = [];
  const server = http.createServer((req, res) => {
    let raw = "";
    req.on("data", (chunk) => (raw += chunk));
    req.on("end", () => {
      if ((req.url ?? "").startsWith("/api/v1/tokens")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ token: TOKEN, person: { id: 7, accountId: 1 }, account: { id: 1, name: "dev" } }));
        return;
      }
      seen.push(raw);
      const step = plan.shift() ?? "place";
      if (step === "hang-up") {
        req.socket.destroy();
      } else if (step === "cut-off") {
        // The headers arrive, so fetch has returned: it is reading the body that fails.
        res.writeHead(200, { "Content-Type": "application/json", "Content-Length": "100" });
        res.write("[", () => req.socket.destroy());
      } else if (step === "unreadable") {
        res.writeHead(200, { "Content-Type": "text/html" });
        res.end("<html>not json</html>");
      } else {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ id: 42, marketplaceId: 1, marketId: 11, type: "LIMIT" }));
      }
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`;
  const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "transport-failure-test");
  const done = async () => {
    await fm.close();
    server.closeAllConnections();
    server.close();
  };
  return { fm, seen, done };
}

const CALLS: Record<string, (fm: Flexemarkets) => Promise<unknown>> = {
  submitLimit: (fm) => fm.submitLimit(1, 11, "BUY", 1, 100),
  submitCancel: (fm) => fm.submitCancel(1, 11, 99),
  markets: (fm) => fm.markets(1),
  activeOrders: (fm) => fm.activeOrders(1),
  recentTrades: (fm) => fm.recentTrades(1),
};

const lost = (e: Error) => e instanceof ApiError && e.cause instanceof TypeError && /HTTP request failed/.test(e.message);

for (const [name, call] of Object.entries(CALLS)) {
  for (const step of ["hang-up", "cut-off"] as const) {
    test(`${name}: a ${step} is an ApiError carrying what happened`, async () => {
      // Twice, for the call that sends again.
      const { fm, done } = await serving([step, step]);
      try {
        await assert.rejects(call(fm), lost);
      } finally {
        await done();
      }
    });
  }
}

test("a sign-in that cannot reach the server is an ApiError too", async () => {
  const closed = http.createServer();
  await new Promise<void>((resolve) => closed.listen(0, "127.0.0.1", () => resolve()));
  const port = (closed.address() as AddressInfo).port;
  await new Promise((resolve) => closed.close(resolve));

  await assert.rejects(Flexemarkets.connect(TOKEN, `http://127.0.0.1:${port}/api/marketplaces/1`, "t"), lost);
});

for (const step of ["hang-up", "cut-off"] as const) {
  test(`an order is sent once more when its first answer was a ${step}`, async () => {
    const { fm, seen, done } = await serving([step]);
    try {
      assert.equal((await fm.submitLimit(1, 11, "BUY", 1, 100)).id, 42);
    } finally {
      await done();
    }
    assert.equal(seen.length, 2);
    assert.equal(seen[0], seen[1]);
  });
}

test("an unreadable answer is not sent again", async () => {
  // Also an ApiError, but the server did answer, so the order is placed.
  const { fm, seen, done } = await serving(["unreadable"]);
  try {
    await assert.rejects(fm.submitLimit(1, 11, "BUY", 1, 100), ApiError);
  } finally {
    await done();
  }
  assert.equal(seen.length, 1);
});

// -- valid JSON of the wrong shape ------------------------------------------

/** A client whose every request, sign-in aside, is answered 200 with `body`. */
async function answeringWith(body: string, signIn?: string) {
  const server = http.createServer((req, res) => {
    req.resume();
    res.writeHead(200, { "Content-Type": "application/json" });
    if ((req.url ?? "").startsWith("/api/v1/tokens")) {
      res.end(signIn ?? JSON.stringify({ token: TOKEN, person: { id: 7, accountId: 1 }, account: { id: 1, name: "dev" } }));
      return;
    }
    res.end(body);
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`;
  const done = () => { server.closeAllConnections(); server.close(); };
  return { base, done };
}

const ONE_THING: Record<string, (fm: Flexemarkets) => Promise<unknown>> = {
  submitLimit: (fm) => fm.submitLimit(1, 11, "BUY", 1, 100),
  submitCancel: (fm) => fm.submitCancel(1, 11, 99),
  marketplace: (fm) => fm.marketplace(1),
};
const A_LIST: Record<string, (fm: Flexemarkets) => Promise<unknown>> = {
  markets: (fm) => fm.markets(1),
  accounts: (fm) => fm.accounts(),
  activeOrders: (fm) => fm.activeOrders(1),
  recentTrades: (fm) => fm.recentTrades(1),
  sessions: (fm) => fm.sessions(1),
  identifiers: (fm) => fm.identifiers(1),
};

for (const [name, call] of Object.entries(ONE_THING)) {
  for (const body of ["[]", "1", '"x"', "null"]) {
    test(`${name}: ${body}, when it asked for an object, is an ApiError`, async () => {
      // null was a TypeError past anyone catching FlexemarketsError; the others read as an empty object.
      const { base, done } = await answeringWith(body);
      const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "transport-failure-test");
      try {
        await assert.rejects(call(fm), ApiError);
      } finally { await fm.close(); done(); }
    });
  }
}

for (const [name, call] of Object.entries(A_LIST)) {
  for (const body of ["{}", "1", '"x"', "null", "[1]"]) {
    test(`${name}: ${body}, when it asked for a list, is an ApiError`, async () => {
      const { base, done } = await answeringWith(body);
      const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "transport-failure-test");
      try {
        await assert.rejects(call(fm), ApiError);
      } finally { await fm.close(); done(); }
    });
  }
}

for (const body of ["[]", "1", '"x"', "null"]) {
  test(`a sign-in answered with ${body} is an ApiError`, async () => {
    const { base, done } = await answeringWith("[]", body);
    try {
      await assert.rejects(Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "transport-failure-test"), ApiError);
    } finally { done(); }
  });
}
