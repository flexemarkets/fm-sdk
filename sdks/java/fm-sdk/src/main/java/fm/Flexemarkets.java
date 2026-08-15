package fm;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import fm.Types.Account;
import fm.Types.ClientConnection;
import fm.Types.Holding;
import fm.Types.Market;
import fm.Types.Marketplace;
import fm.Types.Order;
import fm.Types.Person;
import fm.Types.Session;

/**
 * A connection to Flexemarkets.
 *
 * <p>An interface rather than a class, so that anything built on this SDK can
 * be tested without a server. That is not a hypothetical: the first consumer to
 * try — a trading robot moved onto this SDK — could compile against it and not
 * test against it, because {@code connect()} opens a real socket and there was
 * no seam to substitute. A published SDK that cannot be faked pushes every one
 * of its consumers towards integration tests they will not write.
 *
 * <p>Obtain one with {@link #connect}. The returned object owns a connection
 * and must be closed.
 *
 * <p><b>Returned collections are immutable.</b> Callers who need to modify one
 * should copy it. This holds for every implementation, including test fakes, so
 * a mutation bug surfaces in a unit test rather than against a live server.
 */
public interface Flexemarkets extends AutoCloseable {

    /**
     * Connect and authenticate. The caller owns the result and must close it.
     *
     * <p>The endpoint is {@linkplain Endpoints#resolve resolved} first -- a
     * bare marketplace id or a file holding one becomes the endpoint it
     * denotes -- and a registered {@link FlexemarketsProvider} is then offered
     * the result. Resolving first is what lets a provider's endpoint be
     * supplied the same ways any other can, including from a file; asking
     * about the argument as typed meant only a literal could ever be claimed.
     *
     * <p>Nothing is registered by default and the fallback is HTTP, so ordinary
     * use is unchanged -- including when a provider is present but does not
     * recognise the endpoint.
     */
    static Flexemarkets connect(String credential, String endpoint, String clientDescription)
            throws IOException {
        String resolved = Endpoints.resolve(endpoint);

        FlexemarketsProvider provider = Providers.forEndpoint(resolved);
        if (null != provider) {
            return provider.connect(credential, resolved, clientDescription);
        }
        return new HttpFlexemarkets(
                HttpFlexemarkets.loadProperties(credential, endpoint, clientDescription));
    }

    // --- identity -----------------------------------------------------------

    Account account();

    long accountId();

    String accountName();

    Person user();

    long userId();

    /** The marketplace this connection was pointed at, from its endpoint. */
    long endpointMarketplaceId();

    String endpointUrl();

    // --- reading ------------------------------------------------------------

    List<Marketplace> marketplaces();

    Marketplace marketplace(long marketplaceId);

    List<Market> markets(long marketplaceId);

    List<Session> sessions(long marketplaceId);

    /** The current session, or null when the marketplace has never opened one. */
    Session session(long marketplaceId);

    List<Order> orders(long marketplaceId);

    List<Holding> holdings(long marketplaceId);

    /** The caller's own holding in {@code marketplaceId}. */
    Holding holding(long marketplaceId);

    List<ClientConnection> connections(long marketplaceId);

    /**
     * Resting orders, with the sequence number they were correct as of.
     *
     * <p>The sequence lets a caller reconcile this snapshot against the deltas
     * arriving on the event stream, applying only those newer than the
     * snapshot.
     */
    Snapshot<List<Order>> activeOrdersV1(long marketplaceId);

    Snapshot<List<Order>> recentTradesV1(long marketplaceId, int size);

    Snapshot<List<Order>> recentTradesV1(long marketplaceId);

    // --- writing ------------------------------------------------------------

    Order submitLimit(long marketplaceId, long marketId, String side, long units, long price);

    Order submitCancel(long marketplaceId, long marketId, long originalId);

    Order submitMarket(long marketplaceId, long marketId, String side, long units);

    // --- management ---------------------------------------------------------

    /*
     * Running an experiment, as opposed to trading in one. Every study in
     * fm-robots drives this sequence, and none of it existed here -- which is
     * why they are all still on fm-lib-net, and through it on Spring.
     *
     * Defaulted rather than abstract, for the reason given on subscribe(): this
     * is a published interface, and an implementation that only trades -- a test
     * fake, a read-only provider -- should keep compiling and say so plainly if
     * called, rather than be forced to stub a surface it has no use for.
     * Authorization is the server's business: these need a manager or admin, and
     * it answers 401/403 when they are not.
     */

    /** Opens the marketplace's session, returning it in its new state. */
    default Session openSession(long marketplaceId) {
        throw unsupported("openSession");
    }

    default Session pauseSession(long marketplaceId) {
        throw unsupported("pauseSession");
    }

    default Session closeSession(long marketplaceId) {
        throw unsupported("closeSession");
    }

    /** Everyone in the caller's account. */
    default List<Person> users() {
        throw unsupported("users");
    }

    private UnsupportedOperationException unsupported(String operation) {
        return new UnsupportedOperationException(
                getClass().getName() + " does not support " + operation + "(...)");
    }

    // --- events -------------------------------------------------------------

    /**
     * Subscribe to the marketplace's event stream, delivering onto {@code queue}.
     *
     * <p>The queue is the caller's: its capacity is the caller's back-pressure
     * policy.
     */
    void listen(long marketplaceId, BlockingQueue<Object> queue);

    /**
     * Open an <em>independent</em> event subscription, delivering onto
     * {@code queue} until the returned {@link Subscription} is closed.
     *
     * <p>Unlike {@link #listen}, several of these coexist: each has its own
     * stream and its own lifetime. That is what lets more than one
     * {@link MarketView} live in one connection without trampling each other.
     *
     * <p>Defaulted rather than abstract so that existing implementations --
     * test fakes especially -- keep compiling. An implementation that cannot
     * multiplex says so here instead of pretending, and a {@link MarketView}
     * over it will fail loudly rather than silently sharing one stream.
     */
    default Subscription subscribe(long marketplaceId, BlockingQueue<Object> queue) {
        throw new UnsupportedOperationException(
                getClass().getName() + " does not support independent subscriptions");
    }

    /** A maintained view of the order books, kept current from the event stream. */
    MarketView observe(long marketplaceId);

    void reconnect() throws InterruptedException;

    /** Releases the connection. Overridden to drop {@code AutoCloseable}'s checked exception. */
    @Override
    void close();
}
