package fm;

import java.util.concurrent.BlockingQueue;

/**
 * The marketplace's event stream, and the views built on it.
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
     */
    void listen(long marketplaceId, BlockingQueue<Object> queue);

    /**
     * Open an <em>independent</em> event subscription, delivering onto
     * {@code queue} until the returned {@link Subscription} is closed.
     *
     * <p>Unlike {@link #listen}, several of these coexist: each has its own
     * stream and its own lifetime. That is what lets more than one
     * {@link MarketView} live in one connection without trampling each other.
     */
    Subscription subscribe(long marketplaceId, BlockingQueue<Object> queue);

    /** A maintained view of the order books, kept current from the event stream. */
    MarketView observe(long marketplaceId);

    void reconnect() throws InterruptedException;
}
