/**
 * What a caller meets when the server says no, and when a desk's seed comes
 * back. Mirrors the Java SDK's HttpFailureMappingTest and
 * SignInAndSnapshotFailureTest, and the Python test_failure_messages.
 *
 * The SDKs had drifted. Java reported the server's sentence -- "Authentication
 * failed: Wrong password." -- while this one said "Authentication failed." to
 * every refused sign-in, whatever the reason, and put the whole JSON envelope
 * into every other refusal.
 */

import { test, before, after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  AuthenticationError,
  AuthorizationError,
  ConflictError,
  ConnectionFailedError,
  FlexemarketsError,
  Flexemarkets,
  HttpError,
  InvalidArgumentError,
} from "../src/client.ts";
import { NO_SEQ } from "../src/stomp.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";
const SIGNED_IN = {
  token: TOKEN, person: { id: 7, accountId: 1, email: "dev@dev" }, account: { id: 1, name: "dev" },
};

/** path -> [status, body, x-fm-as-of-seq]; anything else answers `fallback`. */
const answers = new Map<string, [number, unknown, string | null]>();
let fallback: [number, unknown] = [200, { _links: {} }];

let server: Server;
let base: string;

before(async () => {
  server = createServer((req, res) => {
    req.resume();
    req.on("end", () => {
      const path = (req.url ?? "").split("?")[0];
      const [status, payload, seq] = answers.get(path) ?? [...fallback, null];
      const body = typeof payload === "string" ? payload : JSON.stringify(payload);
      const headers: Record<string, string> = { "Content-Type": "application/json" };
      if (seq !== null) headers["x-fm-as-of-seq"] = seq;
      res.writeHead(status, headers);
      res.end(body);
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("no port");
  base = `http://127.0.0.1:${address.port}/api`;
});

after(() => { server.close(); });

beforeEach(() => {
  answers.clear();
  answers.set("/api/tokens", [200, SIGNED_IN, null]);
  answers.set("/api/tokens/refresh", [200, SIGNED_IN, null]);
  fallback = [200, { _links: {} }];
});

const refusal = (error: string, message: string, status: number) =>
  ({ error, message, path: "/api/x", shortDigest: "abc123", status });

const connect = () => Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "failure-test");

async function call(status: number, body: unknown): Promise<unknown> {
  const fm = await connect();
  fallback = [status, body];
  try {
    await fm.activeOrders(1);
    return null;
  } catch (e) {
    return e;
  } finally {
    fm.close();
  }
}

// --- the mapping, as Java's HttpFailureMappingTest ---------------------------

test("a bad request is an invalid argument carrying the server's sentence", async () => {
  const e = await call(400, refusal("ORDER_INVALID", "price off the tick", 400));
  assert.ok(e instanceof InvalidArgumentError);
  assert.equal((e as Error).message, "Invalid request: price off the tick");
});

test("a forbidden call is an authorization failure carrying the server's sentence", async () => {
  const e = await call(403, refusal("NOT_PERMITTED", "not permitted", 403));
  assert.ok(e instanceof AuthorizationError);
  assert.equal((e as Error).message, "Not permitted: not permitted");
});

test("a refusal that is not JSON is reported as it came", async () => {
  const e = await call(400, "<html>edge</html>");
  assert.equal((e as Error).message, "Invalid request: <html>edge</html>");
});

test("a plain 409 is a conflict", async () => {
  assert.ok((await call(409, { status: "CONFLICT", message: "taken" })) instanceof ConflictError);
});

test("a server error is a connection failure", async () => {
  assert.ok((await call(503, "unavailable")) instanceof ConnectionFailedError);
});

test("anything else is a typed HTTP error carrying its status", async () => {
  const e = await call(404, refusal("NOT_FOUND", "no such marketplace", 404));
  assert.ok(e instanceof HttpError);
  assert.ok(e instanceof FlexemarketsError);
  assert.equal((e as HttpError).statusCode, 404);
});

// --- sign-in and snapshots, as Java's SignInAndSnapshotFailureTest ----------

test("a refused password is an authentication failure carrying the server's sentence", async () => {
  answers.set("/api/tokens", [401, refusal("ACCOUNT_INVALID_CREDENTIALS", "Wrong password.", 401), null]);
  const credential = join(mkdtempSync(join(tmpdir(), "fm-sdk-cred-")), "credential");
  writeFileSync(credential, "account=dev\nemail=dev@dev\npassword=nope\n");

  await assert.rejects(Flexemarkets.connect(credential, `${base}/marketplaces/1`, "failure-test"),
    (e: unknown) => e instanceof AuthenticationError && e.message === "Authentication failed: Wrong password.");
});

test("a refused token fails at connect saying why", async () => {
  answers.set("/api/tokens/refresh", [401, refusal("TOKEN_EXPIRED", "Token expired.", 401), null]);

  await assert.rejects(connect(),
    (e: unknown) => e instanceof AuthenticationError && e.message === "Authentication failed: Token expired.");
});

test("a snapshot carries the sequence it was taken at", async () => {
  answers.set("/api/v1/marketplaces/1/orders/active", [200, [], "41"]);
  const fm = await connect();
  try {
    const snapshot = await fm.activeOrders(1);
    assert.equal(snapshot.asOfSeq, 41);
    assert.deepEqual(snapshot.body, []);
  } finally { fm.close(); }
});

test("a snapshot without a sequence says so", async () => {
  answers.set("/api/v1/marketplaces/1/orders/active", [200, [], null]);
  const fm = await connect();
  try {
    assert.equal((await fm.activeOrders(1)).asOfSeq, NO_SEQ);
  } finally { fm.close(); }
});

test("a refused snapshot is the server's refusal", async () => {
  answers.set("/api/v1/marketplaces/1/orders/active", [403, refusal("NOT_PERMITTED", "Not your marketplace.", 403), null]);
  const fm = await connect();
  try {
    await assert.rejects(fm.activeOrders(1),
      (e: unknown) => e instanceof AuthorizationError && e.message === "Not permitted: Not your marketplace.");
  } finally { fm.close(); }
});
