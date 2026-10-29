/**
 * Orders over the socket, several in flight (fm-sdk#7).
 *
 * As Python's test_order_stream. The first tests run against a real
 * WebSocket -- `ws`'s own server -- for what only a socket shows: the frames on
 * the wire, in the order sent, and what a drop and a reconnect do to the orders
 * still unanswered. The rest stub the listener, to reach what a socket cannot
 * be made to do on cue.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { WebSocketServer, type WebSocket } from "ws";

import {
  ConnectionFailedError,
  Flexemarkets,
  InvalidArgumentError,
  OrderOutcomeUnknownError,
  parseHolding,
  parseOrder,
  refused,
} from "../src/client.ts";
import { EventListener, decodeFrame, parseEvent } from "../src/stomp.ts";
import type { EventCallback, FmEvent } from "../src/stomp.ts";
import { OrderStream, PendingOrder, type OrderListener } from "../src/order-stream.ts";
import type { Order } from "../src/types.ts";
import { AuthorizationError } from "../src/client.ts";

const MP = 7;
const DESTINATION = `/app/marketplaces/${MP}/orders`;
const ERRORS = {
  notSent: (m: string) => new ConnectionFailedError(m),
  outcomeUnknown: (m: string, id: string) => new OrderOutcomeUnknownError(m, id),
};

const order = (sid: string, price = 900) =>
  ({ marketplaceId: MP, marketId: 11, type: "LIMIT", side: "SELL", units: 1, price, submissionId: sid });
const row = (sid: string | null, id: number) =>
  ({ id, original: id, supplier: id, type: "LIMIT", side: "SELL", units: 1, price: 900, mine: true, submissionId: sid });

function message(type: string, body: string, extraHeader?: string): string {
  return `MESSAGE\ndestination:/user/queue/marketplaces/7\nmessage-type:${type}\n`
    + (extraHeader ? extraHeader + "\n" : "") + "\n" + body + "\0";
}

async function until(what: string, condition: () => boolean): Promise<void> {
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    if (condition()) return;
    await new Promise((r) => setTimeout(r, 10));
  }
  throw new Error(`timed out waiting for: ${what}`);
}

/** Answers CONNECT with CONNECTED, records what each client sent, refuses upgrades on cue. */
async function server() {
  const http_ = http.createServer();
  const state = { refuse: false };
  const wss = new WebSocketServer({ noServer: true });
  http_.on("upgrade", (req, socket, head) => {
    if (state.refuse) {
      socket.end("HTTP/1.1 503 Service Unavailable\r\n\r\n");
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws));
  });
  const sockets: WebSocket[] = [];
  const received: string[][] = [];
  wss.on("connection", (socket: WebSocket) => {
    const index = sockets.push(socket) - 1;
    received.push([]);
    socket.on("message", (raw) => {
      const text = raw.toString();
      if (!text.trim()) return;
      received[index].push(text);
      if (text.startsWith("CONNECT\n")) socket.send("CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0");
    });
  });
  await new Promise<void>((resolve) => http_.listen(0, "127.0.0.1", () => resolve()));
  const url = `ws://127.0.0.1:${(http_.address() as AddressInfo).port}/api/v1/events`;
  /** The submission ids of the orders sent on one connection, in order. */
  const sent = (i: number): string[] => (received[i] ?? []).filter((t) => t.startsWith("SEND\n")).map((t) => {
    const f = decodeFrame(t);
    assert.equal(f.headers.destination, DESTINATION);
    return JSON.parse(f.body).submissionId as string;
  });
  const close = async () => {
    for (const s of sockets) s.terminate();
    await new Promise<void>((resolve) => wss.close(() => http_.close(() => resolve())));
  };
  return { state, sockets, received, url, sent, close };
}

/** A listener with no pause between reconnect attempts. */
class Listener extends EventListener {
  protected override _sleep(): Promise<void> {
    return new Promise((r) => setTimeout(r, 20));
  }
}

async function onSocket() {
  const s = await server();
  const stream = await OrderStream.open(async (callback: EventCallback) => {
    const listener = new Listener(s.url, "Bearer the-token", MP, callback, "fm-sdk-test",
                                  parseHolding as never, parseOrder as never, async () => false, false, refused);
    await listener.start();
    return listener;
  }, MP, ERRORS);
  return { s, stream, done: async () => { stream.close(); await s.close(); } };
}

test("orders go out back to back and each is answered by its own row", async () => {
  const { s, stream, done } = await onSocket();
  try {
    const [a, b, c] = await Promise.all(["s-1", "s-2", "s-3"].map((sid) => stream.submit(order(sid))));
    await until("three sent before any answer", () => s.sent(0).length === 3);
    assert.deepEqual(s.sent(0), ["s-1", "s-2", "s-3"]);
    assert.ok(!a.done && !b.done && !c.done);

    s.sockets[0].send(message("ORDERS-UPDATE", JSON.stringify(
      [row("s-2", 102), row(null, 99), row("someone-else", 98), row("s-1", 101)])));
    assert.deepEqual([(await a.result()).id, (await b.result()).id], [101, 102]);
    assert.ok(!c.done);
    s.sockets[0].send(message("ORDERS-UPDATE", JSON.stringify([row("s-1", 555), row("s-3", 103)])));
    assert.deepEqual([(await a.result()).id, (await c.result()).id], [101, 103]);
  } finally { await done(); }
});

test("unanswered orders are sent again in order once the socket is back", async () => {
  const { s, stream, done } = await onSocket();
  try {
    const first = await stream.submit(order("s-1"));
    await until("the first sent", () => s.sent(0).length === 1);

    s.state.refuse = true;
    s.sockets[0].terminate();
    await until("the drop noticed", () => !(stream as unknown as { _up: boolean })._up);
    const held = await stream.submit(order("s-2"));
    await new Promise((r) => setTimeout(r, 200));
    assert.deepEqual(s.sent(0), ["s-1"]);
    assert.equal(s.sockets.length, 1);

    s.state.refuse = false;
    await until("both sent again", () => s.sockets.length > 1 && s.sent(1).length === 2);
    assert.deepEqual(s.sent(1), ["s-1", "s-2"]);
    s.sockets[1].send(message("ORDER-ACCEPTED", JSON.stringify(row("s-1", 101)), "submission-id:s-1"));
    s.sockets[1].send(message("ORDERS-UPDATE", JSON.stringify([row("s-2", 102)])));
    assert.deepEqual([(await first.result()).id, (await held.result()).id], [101, 102]);
  } finally { await done(); }
});

test("a refused order rejects with what REST throws", async () => {
  const { s, stream, done } = await onSocket();
  try {
    const pending = await stream.submit(order("s-1"));
    await until("sent", () => s.sent(0).length === 1);
    s.sockets[0].send(message("ORDER-REJECTED",
      '{"status": "BAD_REQUEST", "error": "ORDER_INVALID", "message": "Price is off the tick."}',
      "submission-id:s-1"));
    await assert.rejects(pending.result(), (e: Error) =>
      e instanceof InvalidArgumentError && e.message === "Invalid request: Price is off the tick.");
  } finally { await done(); }
});

test("an order frame goes out as one SEND, its length in bytes", async () => {
  const s = await server();
  const listener = new EventListener(s.url, "Bearer t", MP, () => {}, "fm-sdk-test",
                                     parseHolding as never, parseOrder as never);
  try {
    assert.throws(() => listener.send(DESTINATION, "{}"), /not connected/);
    await listener.start();
    listener.send(DESTINATION, '{"symbol": "é"}');
    await until("the SEND", () => s.received[0].some((t) => t.startsWith("SEND\n")));
    const frame = decodeFrame(s.received[0].find((t) => t.startsWith("SEND\n"))!);
    assert.deepEqual(frame.headers, { destination: DESTINATION, "content-type": "application/json",
                                      "content-length": "16" });
    assert.equal(frame.body, '{"symbol": "é"}');
    assert.equal(listener.closed, false);
    listener.close();
    assert.equal(listener.closed, true);
    assert.throws(() => listener.send(DESTINATION, "{}"), /not connected/);
  } finally { listener.close(); await s.close(); }
});

test("order answers are parsed with their submission id and REST's error", () => {
  const frame = (type: string, body: string, headers: Record<string, string> = {}) =>
    ({ command: "MESSAGE", headers: { "message-type": type, ...headers }, body });
  const refusal = (status: number, body: string) => Object.assign(new Error(body), { status });

  const accepted = parseEvent(frame("ORDER-ACCEPTED", JSON.stringify(row("s-1", 41)), { "submission-id": "s-1" }),
                              parseHolding as never, parseOrder as never, refusal) as FmEvent & { order: Order };
  assert.deepEqual([(accepted as { kind: string }).kind, (accepted as { submissionId: string }).submissionId,
                    accepted.order.id, accepted.order.submissionId], ["order-accepted", "s-1", 41, "s-1"]);

  const status = (body: string, headers: Record<string, string> = {}) => {
    const e = parseEvent(frame("ORDER-REJECTED", body, headers), parseHolding as never, parseOrder as never,
                         refusal) as { kind: string; submissionId: string | null; error: Error & { status: number } };
    assert.equal(e.kind, "order-rejected");
    return [e.submissionId, e.error.status];
  };
  assert.deepEqual(status('{"status": "FORBIDDEN"}', { "submission-id": "s-2" }), ["s-2", 403]);
  assert.deepEqual(status('{"status": "INTERNAL_SERVER_ERROR"}'), [null, 500]);
  assert.deepEqual(status('{"status": "NOT_A_STATUS"}'), [null, 400]);
  assert.deepEqual(status('{"message": "no status"}'), [null, 400]);
  assert.deepEqual(status('"a bare string"'), [null, 400]);
  // Without the client's mapping, the body is the message.
  const bare = parseEvent(frame("ORDER-REJECTED", '{"status": "BAD_REQUEST"}'), parseHolding as never,
                          parseOrder as never) as { error: Error };
  assert.equal(bare.error.message, '{"status": "BAD_REQUEST"}');
});

/** Stands in for the EventListener: records sends, fails them on cue. */
class Stub implements OrderListener {
  sent: string[] = [];
  fail = 0;
  closed = false;
  callback!: EventCallback;
  send(destination: string, body: string): void {
    assert.equal(destination, DESTINATION);
    if (this.fail > 0) {
      this.fail -= 1;
      throw new Error("The event stream is not connected.");
    }
    this.sent.push(JSON.parse(body).submissionId);
  }
  close(): void {
    this.closed = true;
  }
}

async function stubbed(maxInFlight?: number) {
  const listener = new Stub();
  const stream = await OrderStream.open(async (callback) => {
    listener.callback = callback;
    return listener;
  }, MP, ERRORS, maxInFlight);
  return { stream, listener };
}

const answered = (sid: string, id: number): FmEvent =>
  ({ kind: "orders-update", orders: [{ ...parseOrder(row(sid, id) as never) }], seq: 1 });

test("the window holds the next order until one is answered, and keeps them in turn", async () => {
  const { stream, listener } = await stubbed(2);
  const first = await stream.submit(order("s-1"));
  await stream.submit(order("s-2"));
  const third = stream.submit(order("s-3"));
  const fourth = stream.submit(order("s-4"));
  await new Promise((r) => setTimeout(r, 50));
  assert.deepEqual(listener.sent, ["s-1", "s-2"]);

  listener.callback(answered("s-1", 101));
  assert.equal((await first.result()).id, 101);
  assert.equal((await third).submissionId, "s-3");
  assert.deepEqual(listener.sent, ["s-1", "s-2", "s-3"]);
  listener.callback(answered("s-2", 102));
  await fourth;
  assert.deepEqual(listener.sent, ["s-1", "s-2", "s-3", "s-4"]);
  stream.close();
});

test("a send that fails is held and sent again on reconnect", async () => {
  const { stream, listener } = await stubbed();
  listener.fail = 1;
  await stream.submit(order("s-1"));
  await stream.submit(order("s-2"));
  assert.deepEqual(listener.sent, []);

  listener.fail = 1;
  listener.callback({ kind: "reconnected", marketplaceId: MP });
  assert.deepEqual(listener.sent, []);
  listener.callback({ kind: "reconnected", marketplaceId: MP });
  assert.deepEqual(listener.sent, ["s-1", "s-2"]);
  stream.close();
});

test("answers that name no submission, and events it does not read, answer nothing", async () => {
  const { stream, listener } = await stubbed();
  const pending = await stream.submit(order("s-1"));
  listener.callback({ kind: "order-accepted", submissionId: null, order: parseOrder(row("s-1", 1) as never) });
  listener.callback({ kind: "order-rejected", submissionId: null, error: new InvalidArgumentError("x") });
  listener.callback({ version: 3 });
  listener.callback({ kind: "frame-unreadable", command: "ERROR", headers: {}, body: "", exception: new Error("?") });
  assert.ok(!pending.done);
  listener.callback({ kind: "order-rejected", submissionId: "s-1", error: new InvalidArgumentError("refused") });
  await assert.rejects(pending.result(), /refused/);
  assert.ok(pending.done);
  stream.close();
});

test("an order not sent within the hold is refused, and one sent before the drop is not", async () => {
  // Held for a whole outage, a robot's quotes went onto the book long after it sent them.
  const listener = new Stub();
  const stream = await OrderStream.open(async (callback) => {
    listener.callback = callback;
    return listener;
  }, MP, ERRORS, undefined, 50);
  const sent = await stream.submit(order("s-1"));
  listener.callback({ kind: "stream-dropped", exception: new Error("gone") });
  const held = await stream.submit(order("s-2"));

  await assert.rejects(held.result(), (e: Error) =>
    e instanceof ConnectionFailedError && /was not sent: the order socket was down/.test(e.message));
  assert.ok(!sent.done, "sent before the drop: it may have been placed");
  listener.callback({ kind: "reconnected", marketplaceId: MP });
  assert.deepEqual(listener.sent, ["s-1", "s-1"], "only the one that went out goes again");
  stream.close();
});

test("an order held less than the hold goes when the socket is back", async () => {
  const { stream, listener } = await stubbed();
  listener.callback({ kind: "stream-dropped", exception: new Error("gone") });
  const held = await stream.submit(order("s-1"));
  listener.callback({ kind: "reconnected", marketplaceId: MP });
  assert.deepEqual(listener.sent, ["s-1"]);
  assert.ok(!held.done);
  stream.close();
});

test("closing fails what is unanswered by whether it was sent", async () => {
  const { stream, listener } = await stubbed();
  const sent = await stream.submit(order("s-1"));
  listener.callback({ kind: "stream-dropped", exception: new Error("gone") });
  const held = await stream.submit(order("s-2"));

  stream.close();
  stream.close();
  assert.ok(listener.closed);
  await assert.rejects(sent.result(), (e: Error) =>
    e instanceof OrderOutcomeUnknownError && e.submissionId === "s-1" && /may have been placed/.test(e.message));
  await assert.rejects(held.result(), (e: Error) => e instanceof ConnectionFailedError && /was not sent/.test(e.message));
  for (let i = 0; i < 3; i++) {
    await assert.rejects(stream.submit(order("s-3")), /closed/);
  }
});

test("a refused token closes the stream and says why", async () => {
  const { stream, listener } = await stubbed();
  const pending = await stream.submit(order("s-1"));
  const refusal = new Error("WebSocket refused: the token was rejected.");
  listener.closed = true;
  listener.callback({ kind: "stream-dropped", exception: refusal });
  await assert.rejects(pending.result(), (e: Error) => e instanceof OrderOutcomeUnknownError && e.cause === refusal);
});

test("an order refused that nobody awaits does not end the process", async () => {
  const pending = new PendingOrder("s-1");
  pending._answer(new InvalidArgumentError("refused"));
  await new Promise((r) => setTimeout(r, 20));
  assert.ok(pending.done);
});

test("sendLimit goes on the marketplace socket, and close ends it", async () => {
  const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";
  const rest = http.createServer((_req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ token: TOKEN, person: { id: 7, accountId: 1 }, account: { id: 1, name: "dev" } }));
  });
  await new Promise<void>((resolve) => rest.listen(0, "127.0.0.1", () => resolve()));
  const s = await server();
  const fm = await Flexemarkets.connect(TOKEN, `http://127.0.0.1:${(rest.address() as AddressInfo).port}/api/marketplaces/1`,
                                        "order-stream-test");
  let opened = 0;
  let refusing = false;
  fm._connectEvents = async (marketplaceId: number, callback: EventCallback, history = false,
                              reconnectMaxPauseMs?: number) => {
    // An order socket comes back within five seconds; a subscription waits up to a minute.
    assert.deepEqual([marketplaceId, history, reconnectMaxPauseMs], [MP, false, 5000]);
    opened += 1;
    if (refusing) throw new Error("no socket");
    const listener = new EventListener(s.url, "Bearer t", MP, callback, "fm-sdk-test",
                                       parseHolding as never, parseOrder as never);
    await listener.start();
    return listener;
  };
  try {
    // The first open fails; it is forgotten, and the next order tries again.
    refusing = true;
    await assert.rejects(fm.sendLimit(MP, 11, "SELL", 1, 900), /no socket/);
    refusing = false;
    const [first, second] = await Promise.all([fm.sendLimit(MP, 11, "SELL", 1, 900),
                                               fm.sendLimit(MP, 11, "SELL", 1, 901)]);
    assert.ok(first instanceof PendingOrder);
    assert.equal(opened, 2);
    await until("both sent", () => s.sent(0).length === 2);
    assert.deepEqual(s.sent(0), [first.submissionId, second.submissionId]);
    const body = JSON.parse(decodeFrame(s.received[0].filter((t) => t.startsWith("SEND\n"))[1]).body);
    assert.deepEqual([body.price, body.clientDescription], [901, "order-stream-test"]);
    fm.close();
    await assert.rejects(first.result(), OrderOutcomeUnknownError);
  } finally {
    fm.close();
    rest.close();
    await s.close();
  }
});

test("the status a refusal names chooses the error, as on REST", async () => {
  const { s, stream, done } = await onSocket();
  try {
    const pending = await stream.submit(order("s-1"));
    await until("sent", () => s.sent(0).length === 1);
    s.sockets[0].send(message("ORDER-REJECTED", '{"status": "FORBIDDEN", "message": "Observers cannot trade."}',
                              "submission-id:s-1"));
    await assert.rejects(pending.result(), (e: Error) =>
      e instanceof AuthorizationError && e.message === "Not permitted: Observers cannot trade.");
  } finally { await done(); }
});

/** A client whose order socket is a stub: the whole of each order it sends, and the callback its answers go to. */
async function stubbedClient() {
  const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";
  const rest = http.createServer((_req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ token: TOKEN, person: { id: 7, accountId: 1 }, account: { id: 1, name: "dev" } }));
  });
  await new Promise<void>((resolve) => rest.listen(0, "127.0.0.1", () => resolve()));
  const fm = await Flexemarkets.connect(TOKEN, `http://127.0.0.1:${(rest.address() as AddressInfo).port}/api/marketplaces/1`,
                                        "order-stream-test");
  const bodies: Record<string, unknown>[] = [];
  const listener = new Stub();
  const send = listener.send.bind(listener);
  listener.send = (destination: string, body: string) => {
    send(destination, body);
    bodies.push(JSON.parse(body));
  };
  fm._connectEvents = async (_marketplaceId: number, callback: EventCallback) => {
    listener.callback = callback;
    return listener as never;
  };
  /** The marketplace's order stream, opened on first use as an order would open it. */
  const stream = async () =>
    (fm as unknown as { _orderStream(mp: number): Promise<OrderStream> })._orderStream(MP);
  return { fm, listener, bodies, stream, done: () => { fm.close(); rest.close(); } };
}

test("sendCancel names the original and is answered by its cancel row", async () => {
  const { fm, listener, bodies, done } = await stubbedClient();
  try {
    const pending = await fm.sendCancel(MP, 11, 101);
    assert.ok(pending instanceof PendingOrder);
    assert.deepEqual(bodies, [{ marketplaceId: MP, marketId: 11, type: "CANCEL", original: 101,
                                submissionId: pending.submissionId }]);

    // fm-server's answer to an accepted cancel: the limit it consumed, which
    // carries the limit's own submission id, and the CANCEL row with this one's.
    listener.callback({ kind: "orders-update", seq: 1, orders: [
      parseOrder({ ...row("the-limit", 101), consumer: 102 } as never),
      parseOrder({ id: 102, original: 101, supplier: 102, consumer: null, type: "CANCEL", side: null,
                   units: 1, price: 900, submissionId: pending.submissionId } as never),
    ] });
    const cancel = await pending.result();
    assert.deepEqual([cancel.id, cancel.original, cancel.type], [102, 101, "CANCEL"]);
  } finally { done(); }
});

test("a refused sendCancel rejects with what REST throws", async () => {
  const { fm, listener, done } = await stubbedClient();
  try {
    const pending = await fm.sendCancel(MP, 11, 101);
    listener.callback({ kind: "order-rejected", submissionId: pending.submissionId,
                        error: new InvalidArgumentError("Order 101 is not in the marketplace") });
    await assert.rejects(pending.result(), (e: Error) =>
      e instanceof InvalidArgumentError && /not in the marketplace/.test(e.message));
  } finally { done(); }
});

test("a sendCancel is sent again after a reconnect, and its replay answers it", async () => {
  const { fm, listener, bodies, done } = await stubbedClient();
  try {
    const pending = await fm.sendCancel(MP, 11, 101);
    listener.callback({ kind: "stream-dropped", exception: new Error("gone") });
    listener.callback({ kind: "reconnected", marketplaceId: MP });
    assert.deepEqual(listener.sent, [pending.submissionId, pending.submissionId]);
    assert.deepEqual(bodies[0], bodies[1]);

    listener.callback({ kind: "order-accepted", submissionId: pending.submissionId,
                        order: parseOrder({ id: 102, original: 101, type: "CANCEL" } as never) });
    assert.equal((await pending.result()).id, 102);
  } finally { done(); }
});

test("a sendCancel the socket could not send is refused after the hold", async () => {
  const { fm, listener, bodies, stream, done } = await stubbedClient();
  try {
    (await stream() as unknown as { _holdMs: number })._holdMs = 50;
    listener.callback({ kind: "stream-dropped", exception: new Error("gone") });
    const pending = await fm.sendCancel(MP, 11, 101);
    await assert.rejects(pending.result(), (e: Error) =>
      e instanceof ConnectionFailedError && /was not sent: the order socket was down/.test(e.message)
      && (e as ConnectionFailedError).statusCode === null);
    assert.deepEqual(bodies, []);
  } finally { done(); }
});

test("limits and cancels go in the order sent, and are sent again in it", async () => {
  const { fm, listener, bodies, done } = await stubbedClient();
  try {
    const first = await fm.sendLimit(MP, 11, "SELL", 1, 900);
    const cancel = await fm.sendCancel(MP, 11, 101);
    const second = await fm.sendLimit(MP, 11, "SELL", 1, 905);
    const sent = [first.submissionId, cancel.submissionId, second.submissionId];
    assert.deepEqual(listener.sent, sent);
    assert.deepEqual(bodies.map((b) => b.type), ["LIMIT", "CANCEL", "LIMIT"]);

    listener.callback({ kind: "stream-dropped", exception: new Error("gone") });
    listener.callback({ kind: "reconnected", marketplaceId: MP });
    assert.deepEqual(listener.sent, [...sent, ...sent]);
  } finally { done(); }
});
