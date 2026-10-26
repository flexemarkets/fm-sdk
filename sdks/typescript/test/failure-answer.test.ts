/**
 * A refusal carries the server's answer, not only a sentence about it.
 *
 * The message is for people. A caller deciding what to do -- an order refused
 * because the session closed, against one refused for its price -- branches on
 * the body's `error`, and a transport handing the refusal on (FMClientLibrary's
 * order_rejected receives the body and the status) needs both as sent.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";

import {
  AuthenticationError, ConflictError, ConnectionFailedError, Flexemarkets, FlexemarketsError, HttpError,
  InvalidArgumentError,
} from "../src/client.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";
const REFUSED = JSON.stringify({ error: "SESSION_CLOSED", message: "Closed session.", path: "/api/orders",
                                 status: "BAD_REQUEST" });

/** A client whose next order is answered with `status` and `body`. */
async function refusedWith(status: number, body: string): Promise<FlexemarketsError> {
  const server = http.createServer((req, res) => {
    req.resume();
    req.on("end", () => {
      if ((req.url ?? "").startsWith("/api/tokens")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ token: TOKEN, person: { id: 7, accountId: 1 }, account: { id: 1, name: "dev" } }));
        return;
      }
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(body);
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api`;
  const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "failure-answer-test");
  try {
    await fm.submitLimit(1, 11, "BUY", 1, 100);
    assert.fail("the order was not refused");
  } catch (e) {
    assert.ok(e instanceof FlexemarketsError, String(e));
    return e;
  } finally {
    await fm.close();
    server.close();
  }
}

for (const [status, kind] of [[400, InvalidArgumentError], [409, ConflictError], [418, HttpError],
                              [503, ConnectionFailedError]] as const) {
  test(`a ${status} carries its status, reason and body`, async () => {
    const error = await refusedWith(status, REFUSED);
    assert.ok(error instanceof kind);
    assert.equal(error.statusCode, status);
    assert.equal(error.reason, "SESSION_CLOSED");
    assert.equal(error.body, REFUSED);
  });
}

test("a body that names no error leaves the reason unknown", async () => {
  for (const body of ["", "<html>bad gateway</html>", JSON.stringify({ message: "no code" })]) {
    const error = await refusedWith(400, body);
    assert.equal(error.reason, null);
    assert.equal(error.body, body);
  }
});

test("an error the SDK raised itself carries no answer", () => {
  const error = new InvalidArgumentError("raised locally");
  assert.deepEqual([error.statusCode, error.reason, error.body], [null, null, null]);
});

test("a 401 that is not a renewable expiry carries its answer too", async () => {
  const error = await refusedWith(401, JSON.stringify({ error: "ACCOUNT_INVALID_CREDENTIALS", message: "no" }));
  assert.ok(error instanceof AuthenticationError);
  assert.equal(error.reason, "ACCOUNT_INVALID_CREDENTIALS");
});
