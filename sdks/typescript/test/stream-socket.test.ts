/**
 * The EventListener against a real WebSocket, with `ws`'s own server.
 *
 * Mirrors Java's EventsSocketTest and Python's test_events_socket.
 * stream-reconnect.test.ts stubs start(), so before this the receive callback
 * -- where frames are decoded and handed on -- ran in no test.
 */

import { test, mock } from "node:test";
import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import { createServer, type IncomingMessage } from "node:http";
import { WebSocketServer, type WebSocket } from "ws";

import { EventListener } from "../src/stomp.ts";
import type { FmEvent, OrdersUpdate } from "../src/stomp.ts";
import { Flexemarkets, parseHolding, parseOrder } from "../src/client.ts";
import type { Holding, Session, Version } from "../src/types.ts";
import type { FrameUnreadable, StreamDropped } from "../src/stomp.ts";
import { HEARTBEAT_INTERVAL_MS } from "../src/stomp.ts";

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
  const heartbeats: number[] = [];
  wss.on("connection", (socket) => {
    const index = sockets.push(socket) - 1;
    received.push([]);
    heartbeats.push(0);
    socket.on("message", (raw) => {
      const text = raw.toString();
      if (!text.trim()) { heartbeats[index]++; return; }
      received[index].push(text);
      if (text.startsWith("CONNECT\n")) socket.send("CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0");
    });
  });
  const url = () => `ws://127.0.0.1:${(wss.address() as AddressInfo).port}/api/events`;
  return { wss, sockets, received, heartbeats, url };
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

test("version and session-list frames arrive parsed", async () => {
  const { s, events, done } = await listening();
  try {
    s.sockets[0].send(message("VERSION", '{"version":3}'));
    s.sockets[0].send(message("VERSION", "4"));
    s.sockets[0].send(message("SESSION-LIST", '[{"id":300,"marketplaceId":7,"state":"OPEN"}]'));
    s.sockets[0].send(message("SESSION-LIST", "{}"));
    await until("four events", () => events.length >= 4);
    assert.deepEqual(events.slice(0, 2), [{ version: 3 }, { version: 4 }] satisfies Version[]);
    assert.deepEqual((events[2] as Session[]).map((x) => [x.id, x.state, x.name]), [[300, "OPEN", null]],
                     "each session parsed, a missing name read as null");
    assert.deepEqual(events[3], [], "a session list that is not a list is an empty one");
  } finally { await done(); }
});

test("a STOMP ERROR frame is reported with the server's message", async () => {
  const { s, events, done } = await listening();
  try {
    s.sockets[0].send("ERROR\nmessage:no such destination\n\nwhat went wrong\0");
    s.sockets[0].send("ERROR\n\n\0");
    await until("two reports", () => events.length >= 2);
    const [named, bare] = events as FrameUnreadable[];
    assert.equal(named!.kind, "frame-unreadable");
    assert.equal(named!.exception.message, "no such destination");
    assert.equal(named!.body, "what went wrong");
    assert.equal(bare!.exception.message, "STOMP ERROR");
    assert.equal(s.sockets.length, 1, "no reconnect for an ERROR frame");
  } finally { await done(); }
});

test("a broken socket is a drop that says what broke", async () => {
  // Bytes no WebSocket frame can begin with: the client's socket errors.
  const { s, events, done } = await listening();
  try {
    (s.sockets[0] as unknown as { _socket: { write(b: Buffer): void } })._socket
      .write(Buffer.from([0x8f, 0x00]));
    await until("the drop", () => events.some((e) => kind(e) === "stream-dropped"));
    const dropped = events.filter((e) => kind(e) === "stream-dropped") as StreamDropped[];
    assert.match(dropped[0]!.exception.message, /opcode/i);
  } finally { await done(); }
});

test("an idle connection sends a heartbeat every interval", async () => {
  mock.timers.enable({ apis: ["setInterval"] });
  try {
    const { s, done } = await listening();
    try {
      await until("subscribed", () => s.received[0]?.length >= 4);
      mock.timers.tick(HEARTBEAT_INTERVAL_MS - 1);
      await new Promise((r) => setTimeout(r, 50));
      assert.equal(s.heartbeats[0], 0, "not before the interval");

      mock.timers.tick(1);
      await until("a heartbeat", () => s.heartbeats[0] === 1);
      mock.timers.tick(HEARTBEAT_INTERVAL_MS);
      await until("another", () => s.heartbeats[0] === 2);
    } finally { await done(); }
  } finally {
    mock.timers.reset();
  }
});

/** A ws server that refuses every upgrade with `status`. */
async function refusing(status: number) {
  let attempts = 0;
  const wss = new WebSocketServer({
    host: "127.0.0.1", port: 0,
    verifyClient: (_info, cb) => { attempts++; cb(false, status); },
  });
  await new Promise<void>((resolve) => wss.on("listening", () => resolve()));
  const url = `ws://127.0.0.1:${(wss.address() as AddressInfo).port}/api/events`;
  return { url, attempts: () => attempts, close: () => new Promise<void>((r) => wss.close(() => r())) };
}

for (const status of [401, 403]) {
  test(`a ${status} on reconnect ends the stream and says the token was rejected`, async () => {
    // Retrying a refused token is a client hammering a server that has given
    // its final answer -- 11,918 handshake 401s in two hours from one gateway.
    const server = await refusing(status);
    const events: FmEvent[] = [];
    const listener = new EventListener(server.url, "Bearer expired", MP, (e) => void events.push(e),
                                       "fm-sdk-test", parseHolding as never, parseOrder as never);
    try {
      const started = Date.now();
      await listener.reconnect();

      assert.ok(Date.now() - started < 1500, "ended at once, not after a retry's wait");
      assert.equal(server.attempts(), 1, "no retry");
      assert.equal(events.length, 1);
      const dropped = events[0] as StreamDropped;
      assert.equal(dropped.kind, "stream-dropped");
      assert.match(dropped.exception.message, /token was rejected/);
      assert.match(String((dropped.exception.cause as Error).message), new RegExp(String(status)));
    } finally {
      listener.close();
      await server.close();
    }
  });
}

test("starting against a server that refuses fails the start", async () => {
  const server = await refusing(503);
  const listener = new EventListener(server.url, "Bearer t", MP, () => {},
                                     "fm-sdk-test", parseHolding as never, parseOrder as never);
  try {
    await assert.rejects(listener.start(), /503/);
  } finally {
    listener.close();
    await server.close();
  }
});
