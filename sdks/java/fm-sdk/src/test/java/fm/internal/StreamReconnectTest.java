package fm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fm.error.ApiException;
import fm.event.StreamReconnected;

/**
 * What a caller draining {@code listen()} learns when the stream drops.
 *
 * <p>Python and TypeScript have covered this all along and Java did not, which
 * is backwards: Java is where the auto-reconnect was written first, and the
 * other two were brought up to it. The behaviour is that {@link Events}
 * restores the subscription itself and then puts a {@link StreamReconnected} on the
 * queue, so a consumer knows its state is stale and reseeds.
 *
 * <p>It matters beyond the raw queue. {@code DefaultDesk} reseeds its books
 * from that StreamReconnected -- a reconnect is the largest possible sequence gap --
 * so a drop that never announced itself would leave a desk serving a stale
 * book with nothing to say so.
 *
 * <p>The socket is stubbed by overriding {@link Events#connect()}: everything
 * below the STOMP frames is what a fake would have to fake, and none of it is
 * what these are about. They are about what reaches the queue after the
 * receive loop dies.
 */
class StreamReconnectTest {

    private static final long MP = 7L;

    /** An Events with the socket stubbed out. */
    private static final class _Listener extends Events {
        int connects;
        int failConnects;

        /** While held, connect() parks here, holding a reconnect open. */
        volatile CountDownLatch gate;
        /** Counts down once a reconnect has reached the gate and is parked. */
        final CountDownLatch reachedGate = new CountDownLatch(1);

        /** The waits reconnect() asked for, when a test has chosen to record them. */
        volatile List<Long> pauses;
        /** Close this listener when reconnect() starts a wait. */
        volatile boolean closeOnSleep;
        /** Close this listener from inside an attempt that then fails. */
        volatile boolean closeOnConnect;
        /** The socket connect() opens, when a test needs one to drop. */
        volatile WebSocket opens;

        @Override
        void _sleep(long millis) throws InterruptedException {
            if (closeOnSleep) {
                close();
            } else if (pauses == null) {
                super._sleep(millis);
            } else {
                pauses.add(millis);
            }
        }

        _Listener(BlockingQueue<Object> queue) {
            super("ws://127.0.0.1:0/events", "t", MP, "fm-sdk-test",
                  HttpFlexemarkets.MAPPER, queue);
        }

        @Override
        void connect() {
            connects++;
            if (opens != null) {
                _webSocket = opens;
            }

            CountDownLatch held = gate;

            if (held != null) {
                reachedGate.countDown();
                try {
                    if (!held.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("gate never opened");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            if (closeOnConnect) {
                close();
            }
            if (connects <= failConnects) {
                throw new ApiException("refused");
            }
        }
    }

    /** A socket that does nothing, for a drop to name. */
    private static WebSocket _socket() {
        return (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                new Class<?>[] {WebSocket.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "socket";
                    default -> null;
                });
    }

    private static Object _poll(BlockingQueue<Object> queue) throws InterruptedException {
        return queue.poll(5, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(20)
    void aDroppedStreamRestoresItselfAndSaysSo() throws Exception {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(16);
        var listener = new _Listener(queue);

        listener.reconnectInBackground();

        assertThat(_poll(queue))
            .as("a consumer is told the stream came back, so it can reseed")
            .isInstanceOf(StreamReconnected.class);
        assertThat(listener.connects).isEqualTo(1);
    }

    @Test
    @Timeout(20)
    void reconnectedCarriesTheMarketplaceItBelongsTo() throws Exception {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(16);
        var listener = new _Listener(queue);

        listener.reconnectInBackground();

        // One Flexemarkets can hold desks on several marketplaces, so a
        // StreamReconnected that did not name one would tell every desk to reseed.
        assertThat(_poll(queue)).isEqualTo(new StreamReconnected(MP));
    }

    @Test
    @Timeout(20)
    void aClosedListenerDoesNotReconnect() throws Exception {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(16);
        var listener = new _Listener(queue);
        listener.close();

        listener.reconnectInBackground();

        assertThat(queue.poll(250, TimeUnit.MILLISECONDS))
            .as("close() is final; a late drop must not resurrect the stream")
            .isNull();
        assertThat(listener.connects).isZero();
    }

    /**
     * Same shape as Python's {@code test_one_drop_starts_one_reconnect} and
     * TypeScript's "one drop starts one reconnect": hold the reconnect open,
     * deliver the burst, prove the window really was open, then let it finish
     * and count.
     *
     * <p>It used to hold the window by failing two connects, so the retry
     * sleep was still running when the later drops arrived. That works, but it
     * is timing, and the Python copy of the same idea drifted into a spelling
     * that held nothing at all and passed anyway about four runs in five. A
     * gate cannot drift that way: if the window is not open, the premise
     * assertion below fails on every run rather than occasionally.
     */
    @Test
    @Timeout(20)
    void oneDropStartsOneReconnect() throws Exception {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(16);
        var listener = new _Listener(queue);
        listener.gate = new CountDownLatch(1);

        // What a dying socket actually does: onClose and onError both fire for
        // one outage, several times over.
        for (int i = 0; i < 5; i++) {
            listener.reconnectInBackground();
        }

        assertThat(listener.reachedGate.await(2, TimeUnit.SECONDS))
            .as("no reconnect reached the gate: the burst was delivered after the "
              + "reconnect had already finished, so nothing here tests the guard")
            .isTrue();
        assertThat(queue.peek())
            .as("a reconnect finished before the burst was delivered")
            .isNull();

        listener.gate.countDown();

        assertThat(_poll(queue)).isInstanceOf(StreamReconnected.class);
        assertThat(queue.poll(250, TimeUnit.MILLISECONDS))
            .as("a burst of errors from one dying socket is one outage, not five")
            .isNull();
    }

    /** Each wait is in the upper half of the range that doubles from 2 s to a minute. */
    private static void _assertPaced(List<Long> pauses, long... ceilings) {
        assertThat(pauses).as("waits").hasSize(ceilings.length);
        for (int i = 0; i < ceilings.length; i++) {
            assertThat(pauses.get(i)).as("wait %d", i).isBetween(ceilings[i] / 2, ceilings[i]);
        }
    }

    @Test
    void eachWaitIsDrawnFromTheUpperHalfOfARangeThatDoublesToAMinute() {
        var ranges = new ArrayList<String>();
        for (int n = 1; n <= 8; n++) {
            Events.reconnectPause(n, (lo, hi) -> {
                ranges.add(lo + "-" + hi);
                return hi;
            });
        }

        assertThat(ranges).containsExactly("1000-2000", "2000-4000", "4000-8000", "8000-16000", "16000-32000",
                                           "30000-60000", "30000-60000", "30000-60000");
        assertThat(Events.reconnectPause(10_000, (lo, hi) -> hi)).as("no overflow, however long it has failed")
            .isEqualTo(60_000L);
    }

    /**
     * Clients dropped together -- a classroom when fm-server restarts -- must
     * not keep coming back in step every two seconds.
     */
    @Test
    @Timeout(20)
    void failedAttemptsWaitLongerAndLonger() throws Exception {
        var listener = new _Listener(new ArrayBlockingQueue<>(16));
        listener.pauses = new ArrayList<>();
        listener.failConnects = 3;

        listener.reconnect();

        assertThat(listener.connects).isEqualTo(4);
        _assertPaced(listener.pauses, 2_000, 4_000, 8_000);
    }

    /** An order socket: orders wait on it, so it comes back within five seconds, not a minute. */
    @Test
    @Timeout(20)
    void aListenerGivenAShorterCeilingWaitsNoLongerThanIt() throws Exception {
        var listener = new _Listener(new ArrayBlockingQueue<>(16));
        listener.reconnectMaxPauseMillis = 5_000;
        listener.pauses = new ArrayList<>();
        listener.failConnects = 5;

        listener.reconnect();

        _assertPaced(listener.pauses, 2_000, 4_000, 5_000, 5_000, 5_000);
        assertThat(Events.reconnectPause(10_000, (lo, hi) -> hi, 5_000)).isEqualTo(5_000);
    }

    private static void _dropped(_Listener listener, BlockingQueue<Object> queue) throws InterruptedException {
        // The last reconnect announces itself before it lets go, and a drop
        // that arrives in between is taken as part of it and starts nothing.
        while (listener._reconnecting.get()) {
            Thread.onSpinWait();
        }
        listener.reconnectInBackground();
        assertThat(_poll(queue)).isInstanceOf(StreamReconnected.class);
    }

    @Test
    @Timeout(20)
    void aStreamThatHeldIsRestoredAtOnce() throws Exception {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(16);
        var listener = new _Listener(queue);
        listener.pauses = new ArrayList<>();
        listener._connectedAt = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(Events.STABLE_CONNECTION_MS + 1);

        _dropped(listener, queue);

        assertThat(listener.pauses).isEmpty();
    }

    /**
     * fm-server closes a session whose history overflows its send buffer. The
     * reconnect asks for the same history and is closed again; at once, every
     * time, that was a loop.
     */
    @Test
    @Timeout(20)
    void aConnectionThatFallsStraightAwayIsAFailureAndWaitsLongerEachTime() throws Exception {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(16);
        var listener = new _Listener(queue);
        listener._connectedAt = System.nanoTime();
        listener.pauses = new ArrayList<>();

        _dropped(listener, queue);
        _dropped(listener, queue);
        _dropped(listener, queue);

        _assertPaced(listener.pauses, 2_000, 4_000, 8_000);
    }

    @Test
    @Timeout(20)
    void aListenerClosedWhileWaitingDoesNotConnectAgain() throws Exception {
        var listener = new _Listener(new ArrayBlockingQueue<>(16));
        listener.closeOnSleep = true;
        listener._failures = 3;

        listener.reconnect();

        assertThat(listener.connects).isZero();
    }

    /**
     * close() lands while an attempt is failing -- the caller gave up on the
     * stream as the server went away. Nothing is left to restore, so the loop
     * ends there rather than waiting out a pause to find that out.
     */
    @Test
    @Timeout(20)
    void aListenerClosedDuringAFailedAttemptStopsWithoutWaiting() throws Exception {
        var listener = new _Listener(new ArrayBlockingQueue<>(16));
        listener.pauses = new ArrayList<>();
        listener.closeOnConnect = true;
        listener.failConnects = 1;

        listener.reconnect();

        assertThat(listener.connects).isEqualTo(1);
        assertThat(listener.pauses).as("waits after the close").isEmpty();
    }

    /**
     * The socket a reconnect has just opened can fall before that reconnect
     * lets go -- it has announced itself, or not yet returned. Its drop was
     * taken as part of the reconnect finishing and started nothing, and the
     * stream stayed down with nothing to say so.
     */
    @Test
    @Timeout(20)
    void aDropOfTheNewSocketBeforeTheReconnectLetsGoIsRestoredToo() throws Exception {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(16);
        var listener = new _Listener(queue);
        listener.pauses = new ArrayList<>();
        listener.opens = _socket();
        listener.gate = new CountDownLatch(1);

        listener.reconnectInBackground();
        assertThat(listener.reachedGate.await(2, TimeUnit.SECONDS)).as("the reconnect is under way").isTrue();
        listener.reconnectInBackground();
        listener.gate.countDown();

        assertThat(_poll(queue)).isInstanceOf(StreamReconnected.class);
        assertThat(_poll(queue)).as("the second drop restored too").isInstanceOf(StreamReconnected.class);
        assertThat(listener.connects).isEqualTo(2);
        assertThat(listener.pauses).as("a socket that fell at once is a failure, and waits").hasSize(1);
    }

    /**
     * onClose and onError of the socket being replaced can arrive while the
     * reconnect is under way. They are the outage it is already restoring.
     */
    @Test
    @Timeout(20)
    void aBurstFromTheSocketBeingReplacedIsStillOneReconnect() throws Exception {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(16);
        var listener = new _Listener(queue);
        listener.pauses = new ArrayList<>();
        var replaced = _socket();
        listener._webSocket = replaced;
        listener.opens = _socket();
        listener.gate = new CountDownLatch(1);

        listener.reconnectInBackground(replaced);
        assertThat(listener.reachedGate.await(2, TimeUnit.SECONDS)).as("the reconnect is under way").isTrue();
        for (int i = 0; i < 4; i++) {
            listener.reconnectInBackground(replaced);
        }
        listener.gate.countDown();

        assertThat(_poll(queue)).isInstanceOf(StreamReconnected.class);
        assertThat(queue.poll(250, TimeUnit.MILLISECONDS)).as("one outage, one reconnect").isNull();
        assertThat(listener.connects).isEqualTo(1);
    }
}
