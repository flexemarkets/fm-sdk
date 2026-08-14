package fm;

/**
 * An open event stream, delivering onto a queue until closed.
 *
 * <p>Independent by construction: several may coexist on one connection, each
 * with its own stream and lifetime, which is what lets more than one
 * {@link MarketView} live in a single {@link Flexemarkets} without trampling
 * the others.
 *
 * @see Flexemarkets#subscribe(long, java.util.concurrent.BlockingQueue)
 */
public interface Subscription extends AutoCloseable {

    /**
     * Re-establish the stream after a transport failure.
     *
     * <p>A no-op by default, because a subscription with no transport has
     * nothing to re-establish -- an in-process stream cannot drop the way a
     * WebSocket can. Implementations that do cross a network override this;
     * a {@link MarketView} calls it on transport error and reseeds from a
     * snapshot afterwards either way.
     */
    default void reconnect() throws InterruptedException {
        // nothing to reconnect
    }

    @Override
    void close();
}
