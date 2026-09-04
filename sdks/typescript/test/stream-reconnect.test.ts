/**
 * What a caller draining `listen()` learns when the stream drops.
 *
 * Java's `Events` restores the subscription itself and then delivers a
 * `Reconnected`, so a consumer knows its state is stale and reseeds.
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

import { EventListener } from "../src/stomp.ts";
import type { FmEvent, Reconnected, StreamDropped } from "../src/stomp.ts";

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
  assert.equal((events[1] as Reconnected).kind, "reconnected");
  assert.equal((events[1] as Reconnected).marketplaceId, 7);
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
  const isReconnected = (e: FmEvent) => (e as Reconnected).kind === "reconnected";
  assert.equal(events.filter(isReconnected).length, 0, "a reconnect finished before the burst was delivered");

  listener.release();
  await settle();

  const reconnected = events.filter(isReconnected);
  assert.equal(reconnected.length, 1, `expected one Reconnected, got ${reconnected.length}`);
  listener.close();
});
