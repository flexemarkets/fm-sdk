package fm;

import java.util.List;

import fm.Types.Allotment;
import fm.Types.ClientConnection;
import fm.Types.Holding;
import fm.Types.Market;
import fm.Types.Marketplace;
import fm.Types.Order;
import fm.Types.Person;
import fm.Types.Session;

/**
 * Everything a connection can be asked, and nothing it can change.
 *
 * <p>The role a caller narrows to when it only observes: a settlement report, a
 * dashboard, a robot deciding what to quote. Declaring {@code Reading} rather
 * than {@link Flexemarkets} says in the signature that the method cannot place
 * an order or open a session, which no comment has to be trusted for.
 *
 * <p>Some of these used to sit under management, on the strength of being what
 * a study calls rather than of what they do. {@link #trades} and
 * {@link #downloadHoldings} read; they are here.
 *
 * <p>Every method is abstract. An implementation that cannot answer a read has
 * no business claiming this role — the point of the split is that possessing
 * the type means the calls work.
 */
public interface Reading {

    List<Marketplace> marketplaces();

    Marketplace marketplace(long marketplaceId);

    List<Market> markets(long marketplaceId);

    /** The marketplace's market symbols. */
    List<String> symbols(long marketplaceId);

    List<Session> sessions(long marketplaceId);

    /** Particular sessions by id, rather than the marketplace's whole history. */
    List<Session> sessions(long marketplaceId, List<Long> sessionIds);

    /** The current session, or null when the marketplace has never opened one. */
    Session session(long marketplaceId);

    List<Order> orders(long marketplaceId);

    /**
     * Orders from particular sessions, rather than the current one.
     *
     * <p>Answered by a different route from {@link #orders(long)} -- the
     * current-session collection cannot be filtered -- so a study reading a
     * finished run's orders needs this one.
     */
    List<Order> orders(long marketplaceId, List<Long> sessionIds);

    /**
     * Orders in one market, by symbol.
     *
     * <p>The symbol-keyed route answers without the symbol on each order,
     * because the query already fixed it; it is filled in before returning.
     * Unlike {@link #trades(long, String)} the ids are left alone -- an order
     * has its own id, and only a trade carries it in {@code original}.
     */
    List<Order> orders(long marketplaceId, String symbol);

    /**
     * Trades in one market, most recent first.
     *
     * <p>Answered by a symbol-keyed route, so the orders come back without the
     * symbol on them and with the trade id in {@code original}; both are filled
     * in before returning, which is what makes the result usable as a trade
     * list rather than a set of half-populated orders.
     */
    List<Order> trades(long marketplaceId, String symbol);

    List<Holding> holdings(long marketplaceId);

    /**
     * Holdings as they stood in particular sessions, rather than now.
     *
     * <p>What a settlement report reads: a finished run's positions are a
     * property of its session, and {@link #holdings(long)} only ever answers
     * for the current one.
     */
    List<Holding> holdings(long marketplaceId, List<Long> sessionIds);

    /** The caller's own holding in {@code marketplaceId}. */
    Holding holding(long marketplaceId);

    /** The holdings CSV, verbatim, as the server renders it. */
    String downloadHoldings(long marketplaceId);

    /**
     * The same CSV for particular sessions — a finished run's export, rather
     * than the current one's.
     */
    String downloadHoldings(long marketplaceId, List<Long> sessionIds);

    /** The opening positions of one allocation. */
    List<Allotment> allotments(long marketplaceId, long allocationId);

    /** Everyone in the caller's account. */
    List<Person> users();

    List<ClientConnection> connections(long marketplaceId);

    /**
     * Connections attached during particular sessions.
     *
     * <p>Who was present in a given run, which is not the same question as who
     * is attached now. An empty or null filter means the latter.
     */
    List<ClientConnection> connections(long marketplaceId, List<Long> sessionIds);

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
}
