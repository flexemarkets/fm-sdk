package fm;

import static fm.OrderUtils.findOrder;
import static fm.OrderUtils.isConsumed;
import static fm.OrderUtils.isResting;
import static fm.OrderUtils.isSymbol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Stream;


/**
 * One market's recent trades, newest last, bounded to a capacity.
 *
 * <p>The trade tape {@link MarketView} maintains. A trade is not a distinct
 * thing on the wire -- the exchange expresses one as a pair of orders referring
 * to each other -- so this reads an orders update, pairs each consumed limit
 * with the limit that consumed it, and keeps {@link Trade both} sides.
 *
 * <p>Which side is which is decided by {@link OrderUtils#isResting}, the same
 * rule {@code TradesSummary} in fm-manager applies. That matters more than it
 * looks: the cheaper test of "whichever has the older original id" agrees on an
 * ordinary match and disagrees exactly where an order was split, which is the
 * case a caller is least able to check by eye.
 *
 * <p>Cancels and split markers never pair, so neither reaches the tape: a
 * cancel is not a limit, and a split marker's consumer is zero rather than an
 * order. Counting either would put prices on the tape that nobody paid.
 *
 * <p><b>Ordering.</b> Each batch is sorted by the time the aggressor arrived
 * before it is appended, which is what makes "newest last" true rather than
 * merely intended. Up to and including fm-server 4.3.1 the
 * {@code /v1/orders/recent-trades} snapshot -- what {@link MarketView} seeds
 * and re-seeds from, on open, on a sequence gap and after a reconnect --
 * arrived newest <em>first</em>, so a tape that appended in array order held
 * its trades backwards and the caller asking for the latest one got the oldest
 * it had retained. Later servers send it oldest-first; sorting here is what
 * makes the tape's own contract independent of which one answered, the way
 * reading the snapshot's <em>shape</em> rather than assuming it is.
 *
 * <p>Synchronized for the same reason {@link OrderBook} is: the stream writes
 * on its thread while the caller reads on theirs.
 */
public class Trades {
    private final Market market;
    private final int capacity;
    private final ArrayDeque<Trade> container;

    /**
     * An empty tape for one market.
     *
     * @param market   the market whose trades to keep; its symbol is what
     *                 {@link #update} filters on
     * @param capacity how many trades to retain; the oldest is dropped once
     *                 full
     * @throws NullPointerException     if {@code market} is null
     * @throws IllegalArgumentException if {@code capacity} is less than one
     */
    public Trades(Market market, int capacity) {
        if (market == null) throw new NullPointerException("Market is required.");
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be greater than zero.");
        this.market = market;
        this.capacity = capacity;
        this.container = new ArrayDeque<>(capacity);
    }

    /**
     * An empty tape retaining the default hundred trades.
     *
     * @param market the market whose trades to keep
     */
    public Trades(Market market) {
        this(market, 100);
    }

    /**
     * The market this tape is for.
     *
     * @return the market
     */
    public Market market() { return market; }

    /**
     * That market's id.
     *
     * @return the market id
     */
    public long marketId() { return market.id(); }

    /**
     * How many trades this tape retains.
     *
     * @return the capacity it was built with
     */
    public int capacity() { return capacity; }

    /**
     * How many trades it currently holds.
     *
     * @return the count, never more than {@link #capacity}
     */
    public int size() { return container.size(); }

    /**
     * Apply an orders update, keeping whatever trades it describes.
     *
     * <p>Both sides of a match must be in the same array for it to be seen as
     * one, which is how the server delivers them: an {@code ORDERS-UPDATE}
     * carries the pair together, and the recent-trades snapshot carries both
     * rows of each.
     *
     * @param ordersUpdate orders as the stream delivered them; anything for
     *                     another market, and anything that is not one side of
     *                     a limit-against-limit match, is skipped
     */
    public synchronized void update(Order[] ordersUpdate) {
        var found = new ArrayList<Trade>();

        for (var order : ordersUpdate) {
            if (!isSymbol(market.symbol(), order)) continue;
            if (OrderType.LIMIT != order.type() || !isConsumed(order)) continue;

            var aggressor = findOrder(ordersUpdate, order.consumer());
            if (aggressor == null || OrderType.LIMIT != aggressor.type()) continue;
            if (!isResting(ordersUpdate, order)) continue;

            found.add(Trade.of(order, aggressor));
        }

        found.sort(Comparator.comparing(Trade::at,
            Comparator.nullsLast(Comparator.naturalOrder())));
        found.forEach(this::save);
    }

    /**
     * The tape, oldest first.
     *
     * @return the retained trades, each carrying both of its sides
     */
    public synchronized Trade[] mostRecentTrades() {
        return container.toArray(new Trade[0]);
    }

    /**
     * The most recent trade, which is the one a caller asking "what just
     * happened" wants.
     *
     * @return the newest retained trade, or null when nothing has traded yet
     */
    public synchronized Trade last() {
        return container.peekLast();
    }

    /**
     * The prices those trades happened at, in the same order.
     *
     * @return one price per retained trade
     */
    public synchronized long[] mostRecentPrices() {
        return Stream.of(mostRecentTrades())
            .mapToLong(Trade::price)
            .toArray();
    }

    /**
     * Take everything on the tape and leave it empty.
     *
     * <p>For a caller that consumes trades rather than inspecting them -- a
     * reporter draining what has accumulated since it last looked. Distinct
     * from {@link #clear()}, which throws the trades away.
     *
     * @return the retained trades, oldest first
     */
    public synchronized Trade[] drain() {
        Trade[] drained = mostRecentTrades();
        container.clear();
        return drained;
    }

    /** Empty the trade tape — used by {@code MarketView}'s gap-recovery
     *  flow before reseeding from the {@code /v1/orders/recent-trades}
     *  snapshot. */
    public synchronized void clear() {
        container.clear();
    }

    private void save(Trade trade) {
        if (container.size() == capacity) {
            container.removeFirst();
        }
        container.addLast(trade);
    }
}
