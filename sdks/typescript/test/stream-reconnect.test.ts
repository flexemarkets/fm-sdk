/**
 * What a caller draining `listen()` learns when the stream drops.
 *
 * Java's `Events` restores the subscription itself and then delivers a
 * `StreamReconnected`, so a consumer knows its state is stale and reseeds.
 * TypeScript delivered `StreamDropped` and stopped: the stream stayed dead
 * until someone noticed and called `fm.reconnect()`, and nothing was ever
 * delivered to say it had come back.
 *
 * `Desk` papered over this by reconnecting on `StreamDropped` itself, so
 * the gap only showed for callers using the raw callback -- which is the whole
 * point of `listen()` being public.
 *
 * Also pins the discriminants. The rename to StreamDropped/FrameUnreadable
 * changed the interface names and left `kind` reading "transport-error" and
 * "ws-exception", so the tag a caller actually switches on still spoke the old
 * vocabulary.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { EventListener, STABLE_CONNECTION_MS, reconnectPause } from "../src/stomp.ts";
import type { FmEvent, StreamReconnected, StreamDropped } from "../src/stomp.ts";

/** An EventListener with the socket stubbed out. */
class StubListener extends EventListener {
  connects = 0;
  failConnects = 0;

  /** While held, start() parks here. Lets a test hold a reconnect open. */
  private gate: Promise<void> | null = null;
  private openGate: () => void = () => {};
  /** Resolves once a reconnect has reached the gate and is parked there. */
  reachedGate: Promise<void> = Promise.resolve();
  private markReached: () => void = () => {};

  /** Hold the next reconnect open until release() is called. */
  holdReconnect(): void {
    this.gate = new Promise<void>((resolve) => (this.openGate = resolve));
    this.reachedGate = new Promise<void>((resolve) => (this.markReached = resolve));
  }

  release(): void {
    this.openGate();
  }

  constructor(callback: (event: FmEvent) => void) {
    super(
      "ws://127.0.0.1:0/events",
      "t",
      7,
      callback,
      "stream-reconnect-test",
      (d) => d as never,
      (d) => d as never,
    );
  }

  /** The waits reconnect() asked for, when a test has chosen to record them. */
  pauses: number[] | null = null;

  protected override _sleep(ms: number): Promise<void> {
    if (this.pauses === null) return super._sleep(ms);
    this.pauses.push(ms);
    return Promise.resolve();
  }

  override async start(): Promise<void> {
    this.connects += 1;
    if (this.gate) {
      this.markReached();
      await this.gate;
    }
    if (this.connects <= this.failConnects) throw new Error("refused");
  }
}

function collector() {
  const events: FmEvent[] = [];
  return { events, push: (e: FmEvent) => void events.push(e) };
}

const settle = () => new Promise((r) => setTimeout(r, 50));

/** Reject rather than hang, so a premise that never holds fails loudly. */
async function within(promise: Promise<void>, ms: number, why: string): Promise<void> {
  let timer: ReturnType<typeof setTimeout>;
  const expiry = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(why)), ms);
  });
  try {
    await Promise.race([promise, expiry]);
  } finally {
    clearTimeout(timer!);
  }
}

test("a dropped stream restores itself and says so", async () => {
  const { events, push } = collector();
  const listener = new StubListener(push);
  await listener.start();
  const before = listener.connects;

  listener._onStreamDropped(new Error("connection reset"));
  await settle();

  assert.equal((events[0] as StreamDropped).kind, "stream-dropped");
  assert.ok(events[1], "the caller is never told the stream came back");
  assert.equal((events[1] as StreamReconnected).kind, "reconnected");
  assert.equal((events[1] as StreamReconnected).marketplaceId, 7);
  assert.ok(listener.connects > before, "nothing reconnected");
  listener.close();
});

test("a closed listener does not reconnect", async () => {
  const { events, push } = collector();
  const listener = new StubListener(push);
  await listener.start();
  listener.close();
  const before = listener.connects;

  listener._onStreamDropped(new Error("socket closed"));
  await settle();

  assert.equal(listener.connects, before);
  assert.deepEqual(events, []);
});

test("one drop starts one reconnect", async () => {
  // A burst of errors from a dying socket is one outage, not five.
  //
  // Same shape as Java's oneDropStartsOneReconnect and Python's
  // test_one_drop_starts_one_reconnect: hold the reconnect open, deliver the
  // burst, prove the window really was open, then let it finish and count.
  //
  // This used to hold the window with `failConnects = connects + 1` and a
  // 2.5s sleep, which was correct but timing-based -- and the Python copy of
  // the same idea was written `fail_connects = 1`, forgot the connect start()
  // had already spent, and silently stopped holding anything. A gate cannot
  // drift that way, and costs no wall-clock.
  const { events, push } = collector();
  const listener = new StubListener(push);
  await listener.start();
  listener.holdReconnect();

  for (let i = 0; i < 5; i++) listener._onStreamDropped(new Error("connection reset"));

  // The premise, asserted rather than assumed: a reconnect must be parked in
  // the gate right now. Remove the gate and this never resolves, so the test
  // fails every run instead of passing for the wrong reason.
  await within(
    listener.reachedGate,
    2000,
    "no reconnect reached the gate: the burst was delivered after the reconnect " +
      "had already finished, so nothing here tests the guard",
  );
  const isReconnected = (e: FmEvent) => (e as StreamReconnected).kind === "reconnected";
  assert.equal(events.filter(isReconnected).length, 0, "a reconnect finished before the burst was delivered");

  listener.release();
  await settle();

  const reconnected = events.filter(isReconnected);
  assert.equal(reconnected.length, 1, `expected one StreamReconnected, got ${reconnected.length}`);
  listener.close();
});

test("a reconnect that fails for any reason but the token waits, then tries again", async () => {
  // A server restart or a network drop is a blip; recovering from it is what
  // the loop is for. Waiting between attempts is what keeps it from being a
  // hammer.
  const { push } = collector();
  const listener = new StubListener(push);
  await listener.start();
  listener.failConnects = listener.connects + 1;
  const before = listener.connects;
  const started = Date.now();

  await listener.reconnect();

  assert.equal(listener.connects - before, 2, "one refused, one that succeeded");
  assert.ok(Date.now() - started >= 900, "retried without waiting");
  listener.close();
});

/** Each wait is in the upper half of the range that doubles from 2 s to a minute. */
function assertPaced(pauses: number[], ceilings: number[]): void {
  assert.equal(pauses.length, ceilings.length, `waits ${pauses.join(", ")}`);
  pauses.forEach((ms, i) => assert.ok(ms >= ceilings[i] / 2 && ms <= ceilings[i], `wait ${i}: ${ms} ms`));
}

test("each wait is drawn from the upper half of a range that doubles to a minute", () => {
  const ranges = [1, 2, 3, 4, 5, 6, 7, 8].map((n) => reconnectPause(n, (lo, hi) => [lo, hi] as never));
  assert.deepEqual(ranges, [[1000, 2000], [2000, 4000], [4000, 8000], [8000, 16000], [16000, 32000],
                            [30000, 60000], [30000, 60000], [30000, 60000]]);
  assert.equal(reconnectPause(10_000, (_lo, hi) => hi), 60_000, "no overflow, however long it has failed");
});

test("failed attempts wait longer and longer", async () => {
  // Clients dropped together -- a classroom when fm-server restarts -- must
  // not keep coming back in step every two seconds.
  const listener = new StubListener(() => {});
  await listener.start();
  listener.pauses = [];
  listener.failConnects = listener.connects + 3;

  await listener.reconnect();

  assertPaced(listener.pauses, [2000, 4000, 8000]);
  listener.close();
});

/** Drop the stream and wait until it is restored. */
async function dropped(listener: StubListener, events: FmEvent[]): Promise<void> {
  const before = events.filter((e) => (e as StreamReconnected).kind === "reconnected").length;
  listener._onStreamDropped(new Error("closed"));
  await within(
    (async () => {
      while (events.filter((e) => (e as StreamReconnected).kind === "reconnected").length === before) await settle();
    })(),
    2000,
    "the stream was not restored",
  );
}

test("a stream that held is restored at once", async () => {
  const { events, push } = collector();
  const listener = new StubListener(push);
  await listener.start();
  listener.pauses = [];
  listener._connectedAt = Date.now() - STABLE_CONNECTION_MS - 1;

  await dropped(listener, events);

  assert.deepEqual(listener.pauses, []);
  listener.close();
});

test("a connection that falls straight away is a failure, and waits longer each time", async () => {
  // fm-server closes a session whose history overflows its send buffer. The
  // reconnect asks for the same history and is closed again; at once, every
  // time, that was a loop.
  const { events, push } = collector();
  const listener = new StubListener(push);
  await listener.start();
  listener._connectedAt = Date.now();
  listener.pauses = [];

  await dropped(listener, events);
  await dropped(listener, events);
  await dropped(listener, events);

  assertPaced(listener.pauses, [2000, 4000, 8000]);
  listener.close();
});

test("a listener closed while waiting does not connect again", async () => {
  const listener = new StubListener(() => {});
  await listener.start();
  listener._failures = 3;
  listener.pauses = [];
  const sleep = listener["_sleep"].bind(listener);
  listener["_sleep"] = (ms: number) => {
    listener.close();
    return sleep(ms);
  };

  await listener.reconnect();

  assert.equal(listener.connects, 1);
});
