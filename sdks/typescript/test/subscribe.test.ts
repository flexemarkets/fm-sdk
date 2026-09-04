/**
 * Independent event subscriptions, which only Java could open.
 *
 * `listen` is one per connection: a second call replaces the first. The
 * mechanism for more than one stream was already here -- `_connectEvents`,
 * package-private, added so two `desk()` calls would not trample each other --
 * but a caller who wanted a second stream of their own had no way to ask.
 *
 * Java has exposed it as `subscribe` since desk sharing landed, and Python has
 * had this test since. This is the TypeScript counterpart: `subscribe` is
 * declared in all three, so it is exactly the surface check-parity compares by
 * name and cannot check the behaviour of.
 *
 * `_connectEvents` is stubbed rather than dialled, the way Python monkeypatches
 * it: what these are about is how many listeners a call opens and which one
 * closing affects, not what happens on a socket.
 */

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";

import { Flexemarkets } from "../src/client.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

let server: Server;
let port: number;

function api(): string {
  return `http://127.0.0.1:${port}/api`;
}

before(async () => {
  server = createServer((req, res) => {
    const send = (payload: unknown) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(payload));
    };
    const url = req.url ?? "";
    if (url.startsWith("/api/tokens")) {
      send({
        token: TOKEN,
        person: { id: 7, accountId: 1, email: "dev@dev", roles: ["ROLE_MANAGER"] },
        account: { id: 1, name: "dev" },
      });
    } else if (url === "/api") {
      send({ _links: { marketplaces: { href: `${api()}/marketplaces` } } });
    } else {
      send({});
    }
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  port = (server.address() as AddressInfo).port;
});

after(() => server.close());

/** A listener that records nothing but whether it was closed. */
class FakeListener {
  closed = false;
  async close(): Promise<void> { this.closed = true; }
}

/** Replaces _connectEvents, collecting every listener it hands out. */
function stubConnectEvents(fm: Flexemarkets): FakeListener[] {
  const opened: FakeListener[] = [];
  (fm as unknown as { _connectEvents: () => Promise<FakeListener> })._connectEvents =
    async () => {
      const listener = new FakeListener();
      opened.push(listener);
      return listener;
    };
  return opened;
}

async function connect(): Promise<Flexemarkets> {
  return Flexemarkets.connect(TOKEN, `${api()}/marketplaces/1`, "subscribe-test");
}

test("subscriptions coexist rather than replacing each other", async () => {
  // Two subscriptions are two listeners; two listens are one.
  const fm = await connect();
  try {
    const opened = stubConnectEvents(fm);

    const first = await fm.subscribe(1, () => {});
    const second = await fm.subscribe(1, () => {});

    assert.equal(opened.length, 2, "each subscribe opens its own stream");
    assert.notEqual(first, second);

    first();
    await new Promise((r) => setTimeout(r, 10));

    assert.equal(opened[0]!.closed, true);
    assert.equal(opened[1]!.closed, false, "closing one leaves the other running");
  } finally {
    fm.close();
  }
});

test("listen remains one per connection", async () => {
  const fm = await connect();
  try {
    const opened = stubConnectEvents(fm);

    await fm.listen(1, () => {});
    await fm.listen(1, () => {});

    const held = (fm as unknown as { _eventListener: FakeListener })._eventListener;
    assert.equal(held, opened[opened.length - 1], "the second listen replaces the first");
    assert.equal(opened.length, 2);
  } finally {
    fm.close();
  }
});
