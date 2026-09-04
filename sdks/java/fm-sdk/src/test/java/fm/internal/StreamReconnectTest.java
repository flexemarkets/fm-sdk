package fm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fm.error.ApiException;
import fm.event.Reconnected;

/**
 * What a caller draining {@code listen()} learns when the stream drops.
 *
 * <p>Python and TypeScript have covered this all along and Java did not, which
 * is backwards: Java is where the auto-reconnect was written first, and the
 * other two were brought up to it. The behaviour is that {@link Events}
 * restores the subscription itself and then puts a {@link Reconnected} on the
 * queue, so a consumer knows its state is stale and reseeds.
 *
 * <p>It matters beyond the raw queue. {@code DefaultDesk} reseeds its books
 * from that Reconnected -- a reconnect is the largest possible sequence gap --
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

        _Listener(BlockingQueue<Object> queue) {
            super("ws://127.0.0.1:0/events", "t", MP, "fm-sdk-test",
                  HttpFlexemarkets.MAPPER, queue);
        }

        @Override
        void connect() {
            connects++;
            if (connects <= failConnects) {
                throw new ApiException("refused");
            }
        }
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
            .isInstanceOf(Reconnected.class);
        assertThat(listener.connects).isEqualTo(1);
    }

    @Test
    @Timeout(20)
    void reconnectedCarriesTheMarketplaceItBelongsTo() throws Exception {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(16);
        var listener = new _Listener(queue);

        listener.reconnectInBackground();

        // One Flexemarkets can hold desks on several marketplaces, so a
        // Reconnected that did not name one would tell every desk to reseed.
        assertThat(_poll(queue)).isEqualTo(new Reconnected(MP));
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

    @Test
    @Timeout(20)
    void oneDropStartsOneReconnect() throws Exception {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(16);
        var listener = new _Listener(queue);
        // Two attempts fail, so the retry loop is still running when the
        // second and third drops arrive -- which is what a dying socket
        // actually does: onClose and onError both fire for one outage.
        listener.failConnects = 2;

        listener.reconnectInBackground();
        listener.reconnectInBackground();
        listener.reconnectInBackground();

        assertThat(_poll(queue)).isInstanceOf(Reconnected.class);
        assertThat(queue.poll(250, TimeUnit.MILLISECONDS))
            .as("a burst of errors from one dying socket is one outage, not three")
            .isNull();
    }
}
