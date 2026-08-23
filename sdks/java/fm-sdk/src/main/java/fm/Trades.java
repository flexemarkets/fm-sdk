package fm;

import static fm.OrderUtils.isConsumed;
import static fm.OrderUtils.isSplit;
import static fm.OrderUtils.isSymbol;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;


/**
 * One market's recent trades, newest last, bounded to a capacity.
 *
 * <p>The trade tape {@link MarketView} maintains. A trade is not a distinct
 * thing on the wire -- the exchange expresses one as a pair of orders referring
 * to each other -- so this reads an orders update, pairs the consumer with what
 * it consumed, and keeps the resting side, which is the one that carries the
 * price the trade happened at.
 *
 * <p>Cancels and split markers are skipped: neither is a trade, and counting
 * them would put prices on the tape that nobody paid.
 *
 * <p>Synchronized for the same reason {@link OrderBook} is: the stream writes
 * on its thread while the caller reads on theirs.
 */
public class Trades {
    private final Market market;
    private final int capacity;
    private final ArrayDeque<Order> container;

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
     * @param ordersUpdate orders as the stream delivered them; anything for
     *                     another market, and any cancel or split marker, is
     *                     skipped
     */
    public synchronized void update(Order[] ordersUpdate) {
        Map<Long, Order> consumers = new ConcurrentHashMap<>();

        for (var order : ordersUpdate) {
            if (!isSymbol(market.symbol(), order)) continue;
            if (OrderType.CANCEL == order.type()) continue;
            if (isSplit(order)) continue;

            if (isConsumed(order)) {
                consumers.put(order.id(), order);

                var consumer = consumers.get(order.consumer());
                if (consumer != null) {
                    if (order.original() < consumer.original()) {
                        saveResting(order);
                    } else {
                        saveResting(consumer);
                    }
                }
            }
        }
    }

    /**
     * The tape, oldest first.
     *
     * @return the retained trades as the resting order of each pair
     */
    public synchronized Order[] mostRecentTrades() {
        return container.toArray(new Order[0]);
    }

    /**
     * The prices those trades happened at, in the same order.
     *
     * @return one price per retained trade
     */
    public synchronized long[] mostRecentPrices() {
        return Stream.of(mostRecentTrades())
            .mapToLong(Order::price)
            .toArray();
    }

    /** Empty the trade tape — used by {@code MarketView}'s gap-recovery
     *  flow before reseeding from the {@code /v1/orders/recent-trades}
     *  snapshot. */
    public synchronized void clear() {
        container.clear();
    }

    private void saveResting(Order order) {
        if (container.size() == capacity) {
            container.removeFirst();
        }
        container.addLast(order);
    }
}
