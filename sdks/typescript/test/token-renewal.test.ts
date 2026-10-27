/**
 * A client that signed in with a password renews its token when it expires.
 *
 * Tokens live 24 hours. A robot that ran longer ended the first time it asked
 * for anything after that, or looped on the refusal -- a Utah poller, about 250
 * times a day at 00Z. Against a loopback server that accepts only the second
 * token it hands out, and refuses the first the way fm-server refuses an
 * expired one: 401 with `ACCOUNT_TOKEN_EXPIRED`.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { AddressInfo } from "node:net";

import { AuthenticationError, Flexemarkets } from "../src/client.ts";

const OLD = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJvbGQifQ.b2xk";
const NEW = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJuZXcifQ.bmV3";

interface Options { refusal?: string; plain401?: boolean; signInsBeforeFailing?: number }

async function withServer(options: Options, run: (base: string, state: { signIns: number; presented: string[] }) => Promise<void>) {
  const state = { signIns: 0, presented: [] as string[] };
  const server = http.createServer((req, res) => {
    const send = (status: number, payload: unknown) => {
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(JSON.stringify(payload));
    };
    if (req.url === "/api/v1/tokens" || req.url === "/api/v1/tokens/refresh") {
      req.resume();
      if (state.signIns >= (options.signInsBeforeFailing ?? Infinity)) {
        state.signIns++;
        send(503, { error: "SERVICE_UNAVAILABLE" });
        return;
      }
      const token = ++state.signIns === 1 ? OLD : NEW;
      send(200, { token, person: { id: 7, accountId: 1, email: "dev@dev" }, account: { id: 1, name: "dev" } });
      return;
    }
    state.presented.push(String(req.headers.authorization));
    if (req.headers.authorization !== `Bearer ${NEW}`) {
      if (options.plain401) { res.writeHead(401); res.end("Unauthorized"); return; }
      send(401, { error: options.refusal ?? "ACCOUNT_TOKEN_EXPIRED", message: "JWT expired for dev@dev" });
      return;
    }
    send(200, []);
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  try {
    await run(`http://127.0.0.1:${(server.address() as AddressInfo).port}/api`, state);
  } finally {
    server.close();
  }
}

function credential(): string {
  const file = join(mkdtempSync(join(tmpdir(), "fm-renewal-")), "credential");
  writeFileSync(file, "account=dev\nemail=dev@dev\npassword=secret\n");
  return file;
}

test("an expired token is renewed once and the request repeated", async () => {
  await withServer({}, async (base, state) => {
    const fm = await Flexemarkets.connect(credential(), `${base}/marketplaces/1`, "renewal-test");
    try {
      assert.deepEqual(await fm.markets(1), []);
      assert.equal(fm.token().token, NEW, "the token the client reports is the one it presents");
    } finally {
      await fm.close();
    }
    assert.equal(state.signIns, 2);
    assert.deepEqual(state.presented, [`Bearer ${OLD}`, `Bearer ${NEW}`]);
  });
});

test("a client given a token cannot renew it and says so", async () => {
  await withServer({}, async (base, state) => {
    const fm = await Flexemarkets.connect(OLD, `${base}/marketplaces/1`, "renewal-test");
    try {
      await assert.rejects(fm.markets(1), AuthenticationError);
    } finally {
      await fm.close();
    }
    assert.equal(state.signIns, 1, "the refresh that validated the token at connect, and no more");
  });
});

for (const [name, options] of [
  ["a token refused for another reason", { refusal: "ACCOUNT_INVALID_CREDENTIALS" }],
  ["a 401 that is not JSON", { plain401: true }],
] as const) {
  test(`${name} is not renewed`, async () => {
    await withServer(options, async (base, state) => {
      const fm = await Flexemarkets.connect(credential(), `${base}/marketplaces/1`, "renewal-test");
      try {
        await assert.rejects(fm.markets(1), AuthenticationError);
      } finally {
        await fm.close();
      }
      assert.equal(state.signIns, 1);
    });
  });
}

test("a renewal that fails answers with the refusal it had", async () => {
  await withServer({ signInsBeforeFailing: 1 }, async (base, state) => {
    const fm = await Flexemarkets.connect(credential(), `${base}/marketplaces/1`, "renewal-test");
    try {
      await assert.rejects(fm.markets(1), /JWT expired/);
    } finally {
      await fm.close();
    }
    assert.equal(state.signIns, 2);
  });
});

test("a token another caller already renewed is not renewed again", async () => {
  await withServer({}, async (base, state) => {
    const fm = await Flexemarkets.connect(credential(), `${base}/marketplaces/1`, "renewal-test");
    try {
      const renew = (fm as unknown as { _renewToken(presented: string): Promise<boolean> })._renewToken.bind(fm);
      assert.equal(await renew("Bearer something-older"), true);
    } finally {
      await fm.close();
    }
    assert.equal(state.signIns, 1);
  });
});
