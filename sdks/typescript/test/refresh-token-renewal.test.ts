/**
 * A client given a refresh token signs in with it, and renews with each one it
 * is handed after.
 *
 * A robot fm-server launches used to run on the participant's access token,
 * and stopped when that expired: it held no password to sign in again with.
 * Once access tokens live two hours rather than a day, that is every robot
 * left running through a class. Now it is handed a refresh token of its own.
 * Against a loopback server that, as fm-server does, takes each refresh token
 * once, answers with the next, and refuses one presented again.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import type { AddressInfo } from "node:net";

import { AuthenticationError, Flexemarkets } from "../src/client.ts";

const OLD = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJvbGQifQ.b2xk";
const NEW = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJuZXcifQ.bmV3";

interface State { live: Map<string, [string, string]>; renewals: string[]; presented: string[] }

async function withServer(run: (base: string, state: State) => Promise<void>) {
  const state: State = {
    // Each live refresh token, and the access token and next refresh token it is traded for.
    live: new Map([["rt-1", [OLD, "rt-2"]], ["rt-2", [NEW, "rt-3"]]]),
    renewals: [],
    presented: [],
  };
  const server = http.createServer((req, res) => {
    const send = (status: number, payload: unknown) => {
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(JSON.stringify(payload));
    };
    if (req.url === "/api/v1/tokens/renew") {
      let body = "";
      req.on("data", (chunk) => (body += chunk));
      req.on("end", () => {
        const presented = String(JSON.parse(body || "{}").refreshToken ?? "");
        state.renewals.push(presented);
        const traded = state.live.get(presented);
        state.live.delete(presented);
        if (!traded) {
          send(401, { error: "REFRESH_TOKEN_INVALID", message: "The refresh token is not a live one" });
          return;
        }
        send(200, {
          token: traded[0], refreshToken: traded[1],
          person: { id: 7, accountId: 1, email: "dev@dev" }, account: { id: 1, name: "dev" },
        });
      });
      return;
    }
    state.presented.push(String(req.headers.authorization));
    if (req.headers.authorization !== `Bearer ${NEW}`) {
      send(401, { error: "ACCOUNT_TOKEN_EXPIRED", message: "JWT expired for dev@dev" });
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

test("it signs in with the refresh token and renews with the next when the access token expires", async () => {
  await withServer(async (base, state) => {
    const fm = await Flexemarkets.connect("refresh:rt-1", `${base}/marketplaces/1`, "refresh-test");
    try {
      assert.equal(fm.token().token, OLD, "the access token the first renewal handed out");
      assert.deepEqual(await fm.markets(1), []);
      assert.equal(fm.token().token, NEW);
    } finally {
      await fm.close();
    }
    assert.deepEqual(state.renewals, ["rt-1", "rt-2"],
      "each refresh token presented once, the second the one the first renewal handed back");
    assert.deepEqual(state.presented, [`Bearer ${OLD}`, `Bearer ${NEW}`]);
  });
});

test("a credential file carries the refresh token too", async () => {
  await withServer(async (base, state) => {
    const file = join(mkdtempSync(join(tmpdir(), "fm-refresh-")), "credential");
    writeFileSync(file, "refresh-token=rt-2\n");
    const fm = await Flexemarkets.connect(file, `${base}/marketplaces/1`, "refresh-test");
    try {
      await fm.markets(1);
    } finally {
      await fm.close();
    }
    assert.deepEqual(state.renewals, ["rt-2"]);
  });
});

function defaultCredential(text: string) {
  const dir = join(homedir(), ".fm");
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, "credential"), text);
}

// The default file's refresh token outlived a credential passed in, and every
// client on the machine signed in with it (#8).
test("a credential file passed replaces the default credential whole", async () => {
  await withServer(async (base, state) => {
    defaultCredential("refresh-token=rt-1\n");
    const file = join(mkdtempSync(join(tmpdir(), "fm-refresh-")), "credential");
    writeFileSync(file, `token=${OLD}\n`);
    await assert.rejects(Flexemarkets.connect(file, `${base}/marketplaces/1`, "refresh-test"));
    assert.deepEqual(state.renewals, [], "the default file's refresh token was presented");
  });
});

test("a token passed does not sign in with the default credential's refresh token", async () => {
  await withServer(async (base, state) => {
    defaultCredential("refresh-token=rt-1\n");
    await assert.rejects(Flexemarkets.connect(OLD, `${base}/marketplaces/1`, "refresh-test"));
    assert.deepEqual(state.renewals, [], "the default file's refresh token was presented");
  });
});

test("a refresh token the server will not take is refused at connect", async () => {
  await withServer(async (base) => {
    await assert.rejects(Flexemarkets.connect("refresh:rt-unknown", `${base}/marketplaces/1`, "refresh-test"),
      AuthenticationError);
  });
});

test("an expired token whose renewal is refused answers with the refusal it had", async () => {
  await withServer(async (base, state) => {
    state.live.delete("rt-2");
    const fm = await Flexemarkets.connect("refresh:rt-1", `${base}/marketplaces/1`, "refresh-test");
    try {
      await assert.rejects(fm.markets(1), (e: unknown) => e instanceof AuthenticationError && /JWT expired/.test(e.message));
    } finally {
      await fm.close();
    }
    assert.deepEqual(state.renewals, ["rt-1", "rt-2"]);
  });
});

test("a token another caller already renewed is not renewed again", async () => {
  // A second renewal of the same token would present a used refresh token, and end the family.
  await withServer(async (base, state) => {
    const fm = await Flexemarkets.connect("refresh:rt-1", `${base}/marketplaces/1`, "refresh-test");
    try {
      const renew = (fm as unknown as { _renewToken(presented: string): Promise<boolean> })._renewToken.bind(fm);
      assert.equal(await renew("Bearer something-older"), true);
    } finally {
      await fm.close();
    }
    assert.deepEqual(state.renewals, ["rt-1"]);
  });
});
