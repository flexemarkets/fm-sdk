package fm.role;

import fm.Flexemarkets;
import fm.Tape;
import fm.MarketView;
import fm.Snapshot;
import fm.model.Allotment;
import fm.model.ClientConnection;
import fm.model.Holding;
import fm.model.Market;
import fm.model.Marketplace;
import fm.model.Order;
import fm.model.Person;
import fm.model.Session;
import java.util.List;


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

    /**
     * Every marketplace the caller can see.
     *
     * @return the visible marketplaces, empty if there are none
     */
    List<Marketplace> marketplaces();

    /**
     * One marketplace by id, with its markets.
     *
     * @param marketplaceId the marketplace to read
     * @return the marketplace
     */
    Marketplace marketplace(long marketplaceId);

    /**
     * The marketplace's markets.
     *
     * @param marketplaceId the marketplace to read
     * @return its markets, in the order the server lists them
     */
    List<Market> markets(long marketplaceId);

    /**
     * The marketplace's market symbols.
     *
     * @param marketplaceId the marketplace to read
     * @return the symbols, one per market
     */
    List<String> symbols(long marketplaceId);

    /**
     * The marketplace's sessions.
     *
     * <p>All of them: there is no server-side filter. {@code GET
     * /marketplaces/{id}/sessions} takes only {@code format}, so an overload
     * accepting session ids existed here and was silently ignored — it
     * returned the whole history and looked like it had filtered.
     *
     * <p>Filter the result. fm-ui, fm-manager and capm all already do, and a
     * marketplace's session list is small enough that reading it whole costs
     * less than the confusion did.
     *
     * @param marketplaceId the marketplace to read
     * @return every session it has ever had, oldest first
     */
    List<Session> sessions(long marketplaceId);

    /**
     * The current session, or null when the marketplace has never opened one.
     *
     * @param marketplaceId the marketplace to read
     * @return the current session, or null if there has never been one
     */
    Session session(long marketplaceId);

    /**
     * Orders in the current session.
     *
     * @param marketplaceId the marketplace to read
     * @return the current session's orders, empty if it has none
     */
    List<Order> orders(long marketplaceId);

    /**
     * Orders from particular sessions, rather than the current one.
     *
     * <p>Answered by a different route from {@link #orders(long)} -- the
     * current-session collection cannot be filtered -- so a study reading a
     * finished run's orders needs this one.
     *
     * @param marketplaceId the marketplace to read
     * @param sessionIds    the sessions to read; empty answers nothing
     * @return their orders
     */
    List<Order> orders(long marketplaceId, List<Long> sessionIds);

    /**
     * Orders in one market, by symbol.
     *
     * <p>The symbol-keyed route answers without the symbol on each order,
     * because the query already fixed it; it is filled in before returning.
     * Unlike {@link #trades(long, String)} the ids are left alone -- an order
     * has its own id, and only a trade carries it in {@code original}.
     *
     * @param marketplaceId the marketplace to read
     * @param symbol        the market's symbol
     * @return that market's orders, each carrying the symbol
     */
    List<Order> orders(long marketplaceId, String symbol);

    /**
     * Tape in one market, in ascending order id.
     *
     * <p>Answered by a symbol-keyed route, so the orders come back without the
     * symbol on them and with the trade id in {@code original}; both are filled
     * in before returning, which is what makes the result usable as a trade
     * list rather than a set of half-populated orders.
     *
     * <p>This is the FM-3 surface ({@code /api/orders-json/symbol-trades}) and
     * its order is the server's, which sorts by order id and nothing else. It
     * is neither chronological by trade time nor most-recent-first, which this
     * said it was: the first element is the lowest id, not the latest trade.
     * Sort by {@code lastModifiedDate} if you want time order.
     *
     * @param marketplaceId the marketplace to read
     * @param symbol        the market's symbol
     * @return that market's trades, in ascending order id
     */
    List<Order> trades(long marketplaceId, String symbol);

    /**
     * Every participant's holding in the current session.
     *
     * @param marketplaceId the marketplace to read
     * @return the current session's holdings
     */
    List<Holding> holdings(long marketplaceId);

    /**
     * Holdings as they stood in particular sessions, rather than now.
     *
     * <p>What a settlement report reads: a finished run's positions are a
     * property of its session, and {@link #holdings(long)} only ever answers
     * for the current one.
     *
     * @param marketplaceId the marketplace to read
     * @param sessionIds    the sessions to read; empty answers nothing
     * @return the holdings as those sessions left them
     */
    List<Holding> holdings(long marketplaceId, List<Long> sessionIds);

    /**
     * The caller's own holding in {@code marketplaceId}.
     *
     * @param marketplaceId the marketplace to read
     * @return the caller's holding
     */
    Holding holding(long marketplaceId);

    /**
     * The holdings CSV, verbatim, as the server renders it.
     *
     * @param marketplaceId the marketplace to export
     * @return the CSV text, unparsed
     */
    String downloadHoldings(long marketplaceId);

    /**
     * The same CSV for particular sessions — a finished run's export, rather
     * than the current one's.
     *
     * @param marketplaceId the marketplace to export
     * @param sessionIds    the sessions to export; empty answers nothing
     * @return the CSV text, unparsed
     */
    String downloadHoldings(long marketplaceId, List<Long> sessionIds);

    /**
     * The opening positions of one allocation.
     *
     * @param marketplaceId the marketplace to read
     * @param allocationId  the allocation whose opening positions to read
     * @return the allotments making up that allocation
     */
    List<Allotment> allotments(long marketplaceId, long allocationId);

    /**
     * Everyone in the caller's account.
     *
     * @return the account's people
     */
    List<Person> users();

    /**
     * Who is attached to the marketplace.
     *
     * <p>All of them, for the reason {@link #sessions} gives: {@code GET
     * /marketplaces/{id}/connections} takes only {@code format}, so the
     * by-session overload that used to sit here filtered nothing.
     *
     * <p>A connection carries the session it belonged to, so "who was present
     * in that run" is a filter on the result.
     *
     * @param marketplaceId the marketplace to read
     * @return every connection it has recorded
     */
    List<ClientConnection> connections(long marketplaceId);

    /**
     * Resting orders, with the sequence number they were correct as of.
     *
     * <p>The sequence lets a caller reconcile this snapshot against the deltas
     * arriving on the event stream, applying only those newer than the
     * snapshot.
     *
     * @param marketplaceId the marketplace to read
     * @return the resting orders, and the sequence they were correct as of
     */
    Snapshot<List<Order>> activeOrders(long marketplaceId);

    /**
     * The most recent trades, with the sequence they were correct as of.
     *
     * <p>Ordering is the server's and has changed: up to and including
     * fm-server 4.3.1 this answered newest first, later versions answer oldest
     * first. Either way it is the newest {@code size} trades that come back —
     * only their order differs. {@link Tape} sorts what it is given, so a
     * caller seeding a tape through {@link MarketView} is unaffected; a caller
     * reading this list directly should not assume one.
     *
     * @param marketplaceId the marketplace to read
     * @param size          how many trades to ask for
     * @return the trades, in the server's order, and their sequence
     */
    Snapshot<List<Order>> recentTrades(long marketplaceId, int size);

    /**
     * The most recent trades, in the server's default quantity.
     *
     * <p>Ordering is the server's and has changed: up to and including
     * fm-server 4.3.1 this answered newest first, later versions answer oldest
     * first. Either way it is the newest {@code size} trades that come back —
     * only their order differs. {@link Tape} sorts what it is given, so a
     * caller seeding a tape through {@link MarketView} is unaffected; a caller
     * reading this list directly should not assume one.
     *
     * @param marketplaceId the marketplace to read
     * @return the trades, in the server's order, and their sequence
     */
    Snapshot<List<Order>> recentTrades(long marketplaceId);
}
