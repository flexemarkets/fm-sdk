/**
 * The EventListener against a real WebSocket, with `ws`'s own server.
 *
 * Mirrors Java's EventsSocketTest and Python's test_events_socket.
 * stream-reconnect.test.ts stubs start(), so before this the receive callback
 * -- where frames are decoded and handed on -- ran in no test.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import { createServer, type IncomingMessage } from "node:http";
import { WebSocketServer, type WebSocket } from "ws";

import { EventListener } from "../src/stomp.ts";
import type { FmEvent, OrdersUpdate } from "../src/stomp.ts";
import { Flexemarkets, parseHolding, parseOrder } from "../src/client.ts";
import type { Holding } from "../src/types.ts";

const MP = 7;

/** The tag of a tagged event; Version, Session and Holding arrive as bare payloads. */
const kind = (e: FmEvent): string | undefined => (e as { kind?: string }).kind;
const isOrdersUpdate = (e: FmEvent): e is OrdersUpdate => kind(e) === "orders-update";

function message(type: string, body: string, extraHeader?: string): string {
  return `MESSAGE\ndestination:/user/queue/marketplaces/7\nmessage-type:${type}\n`
    + (extraHeader ? extraHeader + "\n" : "") + "\n" + body + "\0";
}

/** Answers CONNECT with CONNECTED and records what each client sent. */
function server() {
  const wss = new WebSocketServer({ host: "127.0.0.1", port: 0 });
  const sockets: WebSocket[] = [];
  const received: string[][] = [];
  wss.on("connection", (socket) => {
    const index = sockets.push(socket) - 1;
    received.push([]);
    socket.on("message", (raw) => {
      const text = raw.toString();
      if (!text.trim()) return; // a heartbeat
      received[index].push(text);
      if (text.startsWith("CONNECT\n")) socket.send("CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0");
    });
  });
  const url = () => `ws://127.0.0.1:${(wss.address() as AddressInfo).port}/api/events`;
  return { wss, sockets, received, url };
}

async function until(what: string, condition: () => boolean): Promise<void> {
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    if (condition()) return;
    await new Promise((r) => setTimeout(r, 10));
  }
  throw new Error(`timed out waiting for: ${what}`);
}

async function listening() {
  const s = server();
  await new Promise<void>((resolve) => s.wss.on("listening", () => resolve()));
  const events: FmEvent[] = [];
  const listener = new EventListener(s.url(), "Bearer the-token", MP, (e) => void events.push(e),
                                     "fm-sdk-test", parseHolding as never, parseOrder as never);
  await listener.start();
  const done = async () => {
    listener.close();
    for (const socket of s.sockets) socket.terminate();
    await new Promise<void>((resolve) => s.wss.close(() => resolve()));
  };
  return { s, events, listener, done };
}

test("connecting subscribes to the marketplace", async () => {
  const { s, done } = await listening();
  try {
    await until("CONNECT and three SUBSCRIBEs", () => s.received[0]?.length >= 4);
    const [connect, ...subscribes] = s.received[0];
    assert.match(connect, /^CONNECT\n/);
    assert.match(connect, /marketplace-id:7/);
    assert.match(subscribes[0], /destination:\/user\/queue\/marketplaces\/7/);
    assert.match(subscribes[1], /destination:\/topic\/marketplaces\/7/);
    assert.match(subscribes[2], /destination:\/app\/v1\/marketplaces\/7/);
  } finally { await done(); }
});

test("an orders update arrives with its sequence number", async () => {
  const { s, events, done } = await listening();
  try {
    s.sockets[0].send(message("ORDERS-UPDATE", '[{"id":5,"type":"LIMIT","side":"BUY","units":2,"price":300}]', "seq:41"));
    await until("the update", () => events.length >= 1);
    const update = events[0];
    assert.ok(isOrdersUpdate(update));
    assert.equal(update.seq, 41);
    assert.deepEqual(update.orders.map((o) => o.id), [5]);
  } finally { await done(); }
});

test("a frame that cannot be read is reported and the stream carries on", async () => {
  // Reconnecting cannot fix a malformed frame. Java reports it and reads the
  // next one; here a non-JSON body vanished without a word, and one whose
  // orders could not be parsed threw out of the message callback.
  const { s, events, done } = await listening();
  try {
    s.sockets[0].send(message("HOLDING-UPDATE", "{not json"));
    s.sockets[0].send(message("ORDERS-UPDATE", "[null]", "seq:2"));
    s.sockets[0].send(message("ORDERS-UPDATE", "[]", "seq:3"));
    await until("three events", () => events.length >= 3);
    assert.deepEqual(events.slice(0, 3).map(kind), ["frame-unreadable", "frame-unreadable", "orders-update"]);
    assert.equal(s.sockets.length, 1, "no reconnect for a bad frame");
  } finally { await done(); }
});

test("frames arrive in the order they were sent", async () => {
  const { s, events, done } = await listening();
  try {
    for (let seq = 1; seq <= 300; seq++) s.sockets[0].send(message("ORDERS-UPDATE", "[]", `seq:${seq}`));
    await until("300 updates", () => events.length >= 300);
    const seqs = events.map((e) => (isOrdersUpdate(e) ? e.seq : -1));
    assert.deepEqual(seqs, [...seqs].sort((a, b) => a - b));
  } finally { await done(); }
});

test("a server close is a drop that reconnects and says so", async () => {
  const { s, events, done } = await listening();
  try {
    s.sockets[0].close();
    await until("dropped and reconnected", () => events.some((e) => kind(e) === "reconnected"));
    assert.equal(kind(events[0]), "stream-dropped");
    await until("the new socket subscribes again", () => s.received[1]?.length >= 4);
  } finally { await done(); }
});

test("a connection dials its endpoint's events socket with its own token", async () => {
  // The socket's address is derived from the endpoint, and the handshake is
  // what carries the token: a wrong scheme, path or header and nothing ever
  // arrives. Until this, every test that opened a stream stubbed the dial.
  const token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";
  const http = createServer((req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ token, person: { id: 7 }, account: { id: 1, name: "dev" } }));
  });
  const wss = new WebSocketServer({ server: http });
  const upgrades: IncomingMessage[] = [];
  const sockets: WebSocket[] = [];
  wss.on("connection", (socket, request) => {
    upgrades.push(request);
    sockets.push(socket);
    socket.on("message", (raw) => {
      if (raw.toString().startsWith("CONNECT\n")) socket.send("CONNECTED\nversion:1.2\n\n\0");
    });
  });
  await new Promise<void>((resolve) => http.listen(0, "127.0.0.1", () => resolve()));
  const base = `http://127.0.0.1:${(http.address() as AddressInfo).port}/api`;

  const fm = await Flexemarkets.connect(token, `${base}/marketplaces/${MP}`, "fm-sdk-test");
  try {
    const events: FmEvent[] = [];
    await fm.reconnect();   // nothing to reconnect yet
    assert.equal(upgrades.length, 0);

    await fm.listen(MP, (e) => void events.push(e));
    assert.equal(upgrades[0]!.url, "/api/events");
    assert.equal(upgrades[0]!.headers.authorization, `Bearer ${token}`);

    // Sent as "assets", which only the SDK's holding parser reads as securities.
    sockets[0]!.send(message("HOLDING-UPDATE", '{"marketplaceId":7,"cash":2500,"assets":[{"marketId":3,"units":4}]}'));
    await until("the holding", () => events.length >= 1);
    assert.equal((events[0] as Holding).cash, 2500);
    assert.equal((events[0] as Holding).securities[0]?.units, 4);

    await fm.reconnect();
    assert.equal(upgrades.length, 2, "reconnect dials again");
  } finally {
    fm.close();
    for (const socket of sockets) socket.terminate();
    await new Promise<void>((resolve) => wss.close(() => resolve()));
    await new Promise<void>((resolve) => http.close(() => resolve()));
  }
});
