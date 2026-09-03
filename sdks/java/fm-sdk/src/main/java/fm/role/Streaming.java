package fm.role;

import fm.event.Reconnected;
import fm.event.StreamDropped;
import fm.Desk;
import fm.Subscription;
import java.util.concurrent.BlockingQueue;

/**
 * The marketplace's event stream, and the desks built on it.
 *
 * <p>Named for what it does rather than for what it carries, because
 * {@code Events} is already the STOMP client this sits above.
 *
 * <p>Separate from {@link Reading} because they answer different questions. A
 * read is a question asked once; a stream is a standing arrangement that has to
 * be closed, can drop, and can fall behind. An implementation may perfectly
 * well answer every read over plain HTTP and have no socket to offer.
 */
public interface Streaming {

    /**
     * Subscribe to the marketplace's event stream, delivering onto {@code queue}.
     *
     * <p>The queue is the caller's: its capacity is the caller's back-pressure
     * policy.
     *
     * <p>One per connection: a second call replaces the first. For streams that
     * coexist, use {@link #subscribe}.
     *
     * @param marketplaceId the marketplace to stream
     * @param queue         where events are delivered; its capacity is the
     *                      caller's back-pressure policy
     */
    void listen(long marketplaceId, BlockingQueue<Object> queue);

    /**
     * Open an <em>independent</em> event subscription, delivering onto
     * {@code queue} until the returned {@link Subscription} is closed.
     *
     * <p>Unlike {@link #listen}, several of these coexist: each has its own
     * stream and its own lifetime. That is what lets more than one
     * {@link Desk} live in one connection without trampling each other.
     *
     * @param marketplaceId the marketplace to stream
     * @param queue         where events are delivered
     * @return the subscription, which stops delivery when closed
     */
    Subscription subscribe(long marketplaceId, BlockingQueue<Object> queue);

    /**
     * A maintained desk of the order books, kept current from the event stream.
     *
     * @param marketplaceId the marketplace to open a desk on
     * @return a desk that keeps itself current until closed
     */
    Desk desk(long marketplaceId);

    /**
     * Re-establish the subscription by hand.
     *
     * <p>Rarely needed: the client reconnects in the background after a
     * {@link StreamDropped} and puts a {@link Reconnected} on the queue when it
     * has. Calling this as well reconnects twice.
     *
     * @throws InterruptedException if the wait for the new subscription is
     *                              interrupted
     */
    void reconnect() throws InterruptedException;
}
