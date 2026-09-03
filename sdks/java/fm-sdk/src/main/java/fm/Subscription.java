package fm;

/**
 * An open event stream, delivering onto a queue until closed.
 *
 * <p>Independent by construction: several may coexist on one connection, each
 * with its own stream and lifetime, which is what lets more than one
 * {@link Desk} live in a single {@link Flexemarkets} without trampling
 * the others.
 *
 * <p>Reconnection is not here. A stream that drops is the transport's problem
 * and the transport's to fix; an implementation that crosses a network retries
 * on its own and announces the result on the queue, and one that does not cross
 * anything has nothing to announce. Putting it on this interface made every
 * implementation carry a method most of them could only no-op.
 *
 * @see Flexemarkets#subscribe(long, java.util.concurrent.BlockingQueue)
 */
public interface Subscription extends AutoCloseable {

    @Override
    void close();
}
