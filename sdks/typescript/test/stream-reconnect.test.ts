/**
 * What a caller draining `listen()` learns when the stream drops.
 *
 * Java's `Events` restores the subscription itself and then delivers a
 * `Reconnected`, so a consumer knows its state is stale and reseeds.
 * TypeScript delivered `StreamDropped` and stopped: the stream stayed dead
 * until someone noticed and called `fm.reconnect()`, and nothing was ever
 * delivered to say it had come back.
 *
 * `MarketView` papered over this by reconnecting on `StreamDropped` itself, so
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
    if (this.connects <= this.failConnects) throw new Error("refused");
  }
}

function collector() {
  const events: FmEvent[] = [];
  return { events, push: (e: FmEvent) => void events.push(e) };
}

const settle = () => new Promise((r) => setTimeout(r, 50));

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
  const { events, push } = collector();
  const listener = new StubListener(push);
  await listener.start();
  listener.failConnects = listener.connects + 1; // hold the first attempt open

  for (let i = 0; i < 5; i++) listener._onStreamDropped(new Error("connection reset"));
  await new Promise((r) => setTimeout(r, 2500));

  const reconnected = events.filter((e) => (e as Reconnected).kind === "reconnected");
  assert.equal(reconnected.length, 1, `expected one Reconnected, got ${reconnected.length}`);
  listener.close();
});
