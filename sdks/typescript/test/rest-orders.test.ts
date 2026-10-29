/**
 * sendLimit over REST: one request in flight, the rest waiting (fm-sdk#7).
 *
 * A client connected with `orderRoute: "REST"` sends its orders as
 * `POST .../orders/batch`. One request is in flight at a time, and the orders
 * sent while it is out go together as the next: one at a time it is
 * submitLimit, in a burst the requests grow to the window. Two requests in
 * flight could reach the book out of order, which is what this exists to
 * prevent, so the first tests hold a request open and look at what queues
 * behind it.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";

import {
  AuthorizationError,
  ConnectionFailedError,
  FlexemarketsError,
  Flexemarkets,
  InvalidArgumentError,
  OrderOutcomeUnknownError,
} from "../src/client.ts";
import { RestOrderWindow, type RestOrderErrors } from "../src/rest-orders.ts";
import type { Order } from "../src/types.ts";

type Body = Record<string, unknown>;

const ERRORS: RestOrderErrors = {
  notSent: (message) => new ConnectionFailedError(message),
  outcomeUnknown: (message, submissionId) => new OrderOutcomeUnknownError(message, submissionId),
  refusedWhole: (failure) => failure instanceof FlexemarketsError
    && typeof failure.statusCode === "number" && failure.statusCode >= 400 && failure.statusCode < 500,
};

/** A batch POST the test answers by hand: each call waits for release(). */
class FakePost {
  readonly batches: Body[][] = [];
  inFlight = 0;
  mostInFlight = 0;
  /** What the next calls do, in turn: an error to throw, or undefined to answer. */
  readonly plan: Array<unknown> = [];
  private readonly _held: Array<() => void> = [];
  private readonly _arrivals: Array<() => void> = [];
  private _arrived = 0;

  readonly post = async (batch: Body[]): Promise<Array<Order | Error>> => {
    this.batches.push(batch);
    this.inFlight++;
    this.mostInFlight = Math.max(this.mostInFlight, this.inFlight);
    this._arrived++;
    this._arrivals.splice(0).forEach((wake) => wake());
    await new Promise<void>((resolve) => this._held.push(resolve));
    this.inFlight--;
    const step = this.plan.shift();
    if (step !== undefined) throw step;
    return batch.map((o) => o.price === 999
      ? new InvalidArgumentError("refused")
      : ({ id: o.price, price: o.price } as unknown as Order));
  };

  /** Wait until `n` requests have arrived in all. */
  async arrived(n: number): Promise<void> {
    while (this._arrived < n) await new Promise<void>((resolve) => this._arrivals.push(resolve));
  }

  release(): void {
    this._held.shift()?.();
  }
}

const order = (price: number): Body => ({ price, submissionId: `s${price}` });
const prices = (post: FakePost) => post.batches.map((b) => b.map((o) => o.price));

test("one order goes alone and is answered", async () => {
  const post = new FakePost();
  const window = new RestOrderWindow(post.post, ERRORS);

  const pending = await window.submit(order(150));
  await post.arrived(1);
  post.release();

  assert.equal((await pending.result()).price, 150);
  assert.deepEqual(prices(post), [[150]]);
});

test("orders sent while a request is out go together as the next, in the order sent", async () => {
  const post = new FakePost();
  const window = new RestOrderWindow(post.post, ERRORS);

  const first = await window.submit(order(100));
  await post.arrived(1);
  const behind = [];
  for (let i = 0; i < 5; i++) behind.push(await window.submit(order(101 + i)));
  post.release();
  await post.arrived(2);
  post.release();

  assert.equal((await first.result()).price, 100);
  assert.deepEqual((await Promise.all(behind.map((p) => p.result()))).map((o) => o.price), [101, 102, 103, 104, 105]);
  assert.deepEqual(prices(post), [[100], [101, 102, 103, 104, 105]]);
  assert.equal(post.mostInFlight, 1, "never two requests in flight");
});

test("a full list makes the caller wait for room", async () => {
  const post = new FakePost();
  const window = new RestOrderWindow(post.post, ERRORS, 2);
  await window.submit(order(100));
  await post.arrived(1);
  await window.submit(order(101));
  await window.submit(order(102));

  let third = false;
  const thirdSent = window.submit(order(103)).then(() => (third = true));
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(third, false, "a third order waits while two do");

  post.release(); // the first answered; 101 and 102 go, 103 has room
  await thirdSent;
  await post.arrived(2);
  post.release();
  await post.arrived(3);
  post.release();
  await new Promise((resolve) => setTimeout(resolve, 10));

  assert.deepEqual(prices(post), [[100], [101, 102], [103]]);
});

test("a refused order rejects with its refusal, and its neighbours are placed", async () => {
  const post = new FakePost();
  const window = new RestOrderWindow(post.post, ERRORS);
  await window.submit(order(100));
  await post.arrived(1);
  const refused = await window.submit(order(999));
  const placed = await window.submit(order(101));
  post.release();
  await post.arrived(2);
  post.release();

  await assert.rejects(refused.result(), InvalidArgumentError);
  assert.equal((await placed.result()).price, 101);
});

function withStatus<E extends FlexemarketsError>(error: E, status: number): E {
  (error as { statusCode: number | null }).statusCode = status;
  return error;
}

test("a refusal of the whole request is each order's refusal", async () => {
  const post = new FakePost();
  post.plan.push(withStatus(new AuthorizationError("Not permitted"), 403));
  const window = new RestOrderWindow(post.post, ERRORS);

  const pending = await window.submit(order(100));
  await post.arrived(1);
  post.release();

  await assert.rejects(pending.result(), AuthorizationError);
});

for (const [name, failure] of [
  ["lost twice", new TypeError("fetch failed")],
  ["a 5xx", withStatus(new ConnectionFailedError("Service Unavailable"), 503)],
  ["an unreadable answer", new SyntaxError("Unexpected token")],
] as const) {
  test(`a request with ${name} leaves each order unknown, never refused`, async () => {
    const post = new FakePost();
    post.plan.push(failure);
    const window = new RestOrderWindow(post.post, ERRORS);

    const pending = await window.submit(order(100));
    await post.arrived(1);
    post.release();

    await assert.rejects(pending.result(), (e: unknown) =>
      e instanceof OrderOutcomeUnknownError && e.submissionId === "s100");
  });
}

test("the window carries on after a failed request", async () => {
  const post = new FakePost();
  post.plan.push(new TypeError("fetch failed"));
  const window = new RestOrderWindow(post.post, ERRORS);
  const lost = await window.submit(order(100));
  await post.arrived(1);
  post.release();
  await assert.rejects(lost.result(), OrderOutcomeUnknownError);

  const later = await window.submit(order(101));
  await post.arrived(2);
  post.release();
  assert.equal((await later.result()).price, 101);
});

test("closing refuses what waits as not sent, and answers what is in flight", async () => {
  const post = new FakePost();
  const window = new RestOrderWindow(post.post, ERRORS);
  const inFlight = await window.submit(order(100));
  await post.arrived(1);
  const waiting = await window.submit(order(101));

  window.close();
  await assert.rejects(waiting.result(), ConnectionFailedError);
  await assert.rejects(window.submit(order(102)), ConnectionFailedError);
  post.release();

  assert.equal((await inFlight.result()).price, 100);
  assert.equal(post.batches.length, 1, "nothing is sent once closed");
});

test("closing wakes a caller waiting for room, and refuses its order", async () => {
  const post = new FakePost();
  const window = new RestOrderWindow(post.post, ERRORS, 1);
  await window.submit(order(100));
  await post.arrived(1);
  await window.submit(order(101));
  const blocked = window.submit(order(102));

  window.close();
  await assert.rejects(blocked, ConnectionFailedError);
  post.release();
});

// -- through the client -------------------------------------------------------

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

async function serving() {
  const requests: Array<{ path: string; body: Body[] }> = [];
  const server = http.createServer((req, res) => {
    let raw = "";
    req.on("data", (chunk) => (raw += chunk));
    req.on("end", () => {
      res.writeHead(200, { "Content-Type": "application/json" });
      if ((req.url ?? "").startsWith("/api/v1/tokens")) {
        res.end(JSON.stringify({ token: TOKEN, person: { id: 7, accountId: 1 }, account: { id: 1, name: "dev" } }));
        return;
      }
      const body = JSON.parse(raw) as Body[];
      requests.push({ path: req.url ?? "", body });
      res.end(JSON.stringify(body.map((o, i) => ({
        status: 201,
        order: { id: 500 + i, type: o.type, price: o.price ?? 0, original: o.original ?? 500 + i,
                 submissionId: o.submissionId },
      }))));
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const url = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api/marketplaces/1`;
  return { url, requests, stop: () => server.close() };
}

test("a REST client sends sendLimit and sendCancel as batches", async () => {
  const { url, requests, stop } = await serving();
  const fm = await Flexemarkets.connect(TOKEN, url, "rest-orders-test", { orderRoute: "REST" });
  try {
    const placed = await (await fm.sendLimit(1, 11, "BUY", 1, 150)).result();
    const cancel = await (await fm.sendCancel(1, 11, placed.id)).result();
    assert.equal(placed.price, 150);
    assert.equal(cancel.type, "CANCEL");
  } finally {
    fm.close();
    stop();
  }
  assert.deepEqual(requests.map((r) => r.path), Array(2).fill("/api/v1/marketplaces/1/orders/batch"));
  const sent = requests[1].body[0];
  assert.equal(sent.type, "CANCEL");
  assert.ok(sent.id === sent.original && sent.original === sent.supplier && sent.id === 500,
            "a REST cancel names its order as a REST CANCEL always has");
});

test("the route is named either way, and nothing else", async () => {
  const { url, requests, stop } = await serving();
  try {
    const fm = await Flexemarkets.connect(TOKEN, url, "rest-orders-test", { orderRoute: "rest" });
    await (await fm.sendLimit(1, 11, "BUY", 1, 150)).result();
    fm.close();
    assert.equal(requests.length, 1);
    await assert.rejects(Flexemarkets.connect(TOKEN, url, "rest-orders-test", { orderRoute: "carrier-pigeon" }),
                         InvalidArgumentError);
  } finally {
    stop();
  }
});
