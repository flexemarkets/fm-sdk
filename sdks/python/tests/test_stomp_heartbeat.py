"""The heartbeat this client promises in CONNECT.

It promised and never sent. The CONNECT frame has always carried
``heart-beat:30000,30000`` -- "I will send one every 30s" -- while nothing in
:mod:`fm.events` wrote to the socket again except SUBSCRIBE. The ``websockets``
library's own 20s ping kept the connection off Heroku's idle timer, so nothing
visibly broke; but a ping is not a STOMP heartbeat, and the advertisement was a
claim this client could not back.

Java caught this and fixed it first. Python and TypeScript advertised exactly
the same thing and were left behind, which is what a Java-only
StompHeartbeatTest lets happen -- so this is the counterpart, and there is one
in TypeScript too.

WHAT THESE DO NOT COVER, so nobody reads more into them than is there: that a
heartbeat actually reaches the socket. The sender is a daemon thread on a live
connection, so proving the write would mean injecting a socket into production
code for the test's benefit. These pin the arithmetic instead -- which is where
the regression worth catching lives, because an interval edited past Heroku's
timeout silently restores the original fault.
"""

from fm.events import (
    _HEARTBEAT_INTERVAL_SECONDS,
    _HEARTBEAT_MS,
    _HEROKU_IDLE_TIMEOUT_MS,
)


def test_beats_sooner_than_it_promised_to():
    # Late is the same as absent to a peer counting the interval, and the
    # margin has to absorb a scheduler that fires a little behind.
    assert _HEARTBEAT_INTERVAL_SECONDS * 1000 < _HEARTBEAT_MS, (
        f"CONNECT advertises {_HEARTBEAT_MS}ms, so a slower beat is a broken promise"
    )


def test_beats_well_before_the_router_reaps_an_idle_connection():
    # The reason any of this exists. A beat at or past 55s leaves an idle
    # robot's socket to be closed by the platform and recorded as an H15.
    assert _HEARTBEAT_INTERVAL_SECONDS * 1000 < _HEROKU_IDLE_TIMEOUT_MS / 2, (
        f"Heroku closes a connection idle for {_HEROKU_IDLE_TIMEOUT_MS}ms "
        f"and calls it an H15"
    )
