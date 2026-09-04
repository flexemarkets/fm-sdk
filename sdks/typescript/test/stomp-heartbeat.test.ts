/**
 * The heartbeat this client promises in CONNECT.
 *
 * It promised and never sent. The CONNECT frame has always carried
 * `heart-beat:30000,30000` -- "I will send one every 30s" -- while nothing in
 * stomp.ts wrote to the socket again except SUBSCRIBE. Worse than the other
 * two SDKs: `ws` sends no WebSocket ping of its own unless asked, so this
 * client had no keepalive at any layer and an idle robot's socket was Heroku's
 * to reap at 55 seconds.
 *
 * Java caught this and fixed it first. TypeScript and Python advertised
 * exactly the same thing and were left behind, which is what a Java-only
 * StompHeartbeatTest lets happen -- so this is the counterpart, and there is
 * one in Python too.
 *
 * WHAT THESE DO NOT COVER, so nobody reads more into them than is there: that
 * a heartbeat actually reaches the socket. The sender is an interval on a live
 * connection, so proving the write would mean injecting a socket into
 * production code for the test's benefit. These pin the arithmetic instead --
 * which is where the regression worth catching lives, because an interval
 * edited past Heroku's timeout silently restores the original fault.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import { HEARTBEAT_INTERVAL_MS, HEROKU_IDLE_TIMEOUT_MS, ADVERTISED_HEARTBEAT_MS } from "../src/stomp.ts";

test("beats sooner than it promised to", () => {
  // Late is the same as absent to a peer counting the interval, and the margin
  // has to absorb a timer that fires a little behind.
  assert.ok(
    HEARTBEAT_INTERVAL_MS < ADVERTISED_HEARTBEAT_MS,
    `CONNECT advertises ${ADVERTISED_HEARTBEAT_MS}ms, so a slower beat is a broken promise`,
  );
});

test("beats well before the router reaps an idle connection", () => {
  // The reason any of this exists. A beat at or past 55s leaves an idle
  // robot's socket to be closed by the platform and recorded as an H15.
  assert.ok(
    HEARTBEAT_INTERVAL_MS < HEROKU_IDLE_TIMEOUT_MS / 2,
    `Heroku closes a connection idle for ${HEROKU_IDLE_TIMEOUT_MS}ms and calls it an H15`,
  );
});
