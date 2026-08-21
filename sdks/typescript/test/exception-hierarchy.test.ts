/**
 * Conflicts, which this SDK did not have.
 *
 * The Java and Python SDKs have raised a typed 409 since the admin surface
 * landed: a general `ConflictError`, plus `AccountNameConflictError` carrying
 * the server's proposed alternative and `PersonHasMarketplaceDataError` naming
 * the user who could not be deleted. TypeScript had none of the three, so a
 * taken account name arrived as a bare `FlexemarketsError` reading
 * `HTTP 409: {...}` and the suggestion had to be parsed back out of the
 * message by hand.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";

import {
  AccountNameConflictError,
  ConflictError,
  Flexemarkets,
  FlexemarketsError,
  PersonHasMarketplaceDataError,
} from "../src/client.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

/** A server that answers every write with a 409 carrying a suggestion. */
function conflictingServer(): http.Server {
  return http.createServer((req, res) => {
    if (req.method === "POST" && req.url === "/api/tokens") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        token: TOKEN,
        person: { id: 7, accountId: 1, email: "dev@dev" },
        account: { id: 1, name: "dev" },
      }));
      return;
    }
    if (req.method === "GET" && req.url === "/api/tokens/refresh") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        token: TOKEN,
        person: { id: 7, accountId: 1, email: "dev@dev" },
        account: { id: 1, name: "dev" },
      }));
      return;
    }
    if (req.method === "GET" && req.url === "/api") {
      const base = `http://127.0.0.1:${(req.socket.localPort ?? 0)}/api`;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        _links: {
          accounts: { href: `${base}/accounts` },
          users: { href: `${base}/users` },
        },
      }));
      return;
    }
    res.writeHead(409, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ status: "409", suggestedName: "acme-2" }));
  });
}

async function withClient(run: (fm: Flexemarkets) => Promise<void>): Promise<void> {
  const server = conflictingServer();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`;
  try {
    const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "conflict-test");
    try {
      await run(fm);
    } finally {
      await fm.close();
    }
  } finally {
    server.close();
  }
}

test("a taken account name carries the server's suggestion", async () => {
  await withClient(async (fm) => {
    await assert.rejects(
      () => fm.signup("acme", "owner@new", "s3cret"),
      (e: unknown) => {
        assert.ok(e instanceof AccountNameConflictError);
        assert.equal(e.requestedName, "acme");
        assert.equal(e.suggestedName, "acme-2");
        return true;
      },
    );
  });
});

test("a taken account name is catchable as a conflict", async () => {
  await withClient(async (fm) => {
    await assert.rejects(
      () => fm.signup("acme", "owner@new", "s3cret"),
      (e: unknown) => e instanceof ConflictError,
    );
  });
});

test("deleting a user who owns data is refused, and says whose", async () => {
  await withClient(async (fm) => {
    await assert.rejects(
      () => fm.deleteUser(7),
      (e: unknown) => {
        assert.ok(e instanceof PersonHasMarketplaceDataError);
        assert.equal(e.userId, 7);
        return true;
      },
    );
  });
});

test("conflicts remain catchable as the base error", async () => {
  await withClient(async (fm) => {
    await assert.rejects(
      () => fm.deleteUser(7),
      (e: unknown) => e instanceof FlexemarketsError,
    );
  });
});
