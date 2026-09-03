package fm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * The heartbeat this client promises in CONNECT.
 *
 * <p>It promised and never sent. The CONNECT frame has always carried
 * `heart-beat:30000,30000` -- "I will send one every 30s" -- while nothing in
 * {@link Events} wrote to the socket again except SUBSCRIBE. fm-server's own
 * heartbeats covered for it, so nothing visibly broke; the advertisement was
 * simply a claim this client could not back, and a robot idling on a server
 * that stopped heartbeating would have been reaped by Heroku at 55 seconds.
 *
 * <p>WHAT THESE DO NOT COVER, so nobody reads more into them than is there:
 * that a heartbeat actually reaches the socket. {@link Events} builds its own
 * {@code HttpClient} and is reachable only through a live connection, so
 * proving the write would mean injecting a socket factory into production code
 * for the test's benefit. These pin the arithmetic instead -- which is where
 * the regression worth catching lives, because an interval edited past
 * Heroku's timeout silently restores the original fault.
 */
class StompHeartbeatTest {

    @Test
    void beats_sooner_than_it_promised_to() {
        // Late is the same as absent to a peer counting the interval, and the
        // margin has to absorb a scheduler that fires a little behind.
        assertThat(TimeUnit.SECONDS.toMillis(Events.HEARTBEAT_INTERVAL_SECONDS))
            .as("CONNECT advertises %dms, so a slower beat is a broken promise",
                Events.ADVERTISED_HEARTBEAT_MS)
            .isLessThan(Events.ADVERTISED_HEARTBEAT_MS);
    }

    @Test
    void beats_well_before_the_router_reaps_an_idle_connection() {
        // The reason any of this exists. A beat at or past 55s leaves an idle
        // robot's socket to be closed by the platform and recorded as an H15.
        assertThat(TimeUnit.SECONDS.toMillis(Events.HEARTBEAT_INTERVAL_SECONDS))
            .as("Heroku closes a connection idle for %dms and calls it an H15",
                Events.HEROKU_IDLE_TIMEOUT_MS)
            .isLessThan(Events.HEROKU_IDLE_TIMEOUT_MS / 2);
    }
}
