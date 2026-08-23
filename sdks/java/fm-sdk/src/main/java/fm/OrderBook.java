package fm;

import static fm.OrderUtils.isAvailable;
import static fm.OrderUtils.isResting;
import static fm.OrderUtils.isSplit;
import static fm.OrderUtils.isSymbol;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;


/**
 * One market's resting interest, aggregated to price levels.
 *
 * <p>Kept current by feeding it {@link #update}; {@link MarketView} does that
 * for a caller who does not want to drive the event queue themselves.
 *
 * <p>Levels are quantity by price, buys highest-first and sells lowest-first,
 * so the best of either side is the first entry. An empty side answers
 * {@code -1} rather than {@code 0}: a taker reads the sentinel as "nothing to
 * cross with", where a zero would read as a free fill.
 *
 * <p>Every accessor is synchronized, because the stream updates the book on its
 * own thread while the caller reads it on theirs.
 */
public class OrderBook {
    private final Market market;
    private final TreeMap<Long, Long> buys;
    private final TreeMap<Long, Long> sells;
    private boolean initialized;

    /**
     * An empty book for one market.
     *
     * @param market the market this book is for; its symbol is what
     *               {@link #update} filters on
     */
    public OrderBook(Market market) {
        this.market = market;
        this.buys = new TreeMap<>(Collections.reverseOrder());
        this.sells = new TreeMap<>();
    }

    /**
     * The market this book is for.
     *
     * @return the market
     */
    public Market market() { return market; }

    /**
     * That market's symbol, which is what {@link #update} filters on.
     *
     * @return the symbol
     */
    public String symbol() { return market.symbol(); }

    /**
     * That market's id.
     *
     * @return the market id
     */
    public long marketId() { return market.id(); }

    /**
     * Apply an orders update, ignoring anything for another market.
     *
     * @param ordersUpdate orders as the stream delivered them; those whose
     *                     symbol is not this book's are skipped
     */
    public synchronized void update(Order[] ordersUpdate) {
        boolean wasSplit = false;

        for (var order : ordersUpdate) {
            if (!isSymbol(symbol(), order)) continue;

            var side = order.side();
            var price = order.price();
            var units = order.units();

            if (isAvailable(order)) {
                add(side, price, units);
                continue;
            }

            if (!initialized) continue;

            if (OrderType.CANCEL == order.type()) {
                remove(side, price, units);
                continue;
            }

            if (isSplit(order)) {
                wasSplit = true;
                remove(side, price, units);
                continue;
            }

            if (!wasSplit && isResting(ordersUpdate, order)) {
                remove(side, price, units);
                continue;
            }
        }

        if (!initialized) {
            initialized = true;
        }
    }

    /**
     * The best bid.
     *
     * @return the highest resting bid price, or -1 if the side is empty
     */
    public synchronized long bestBuyPrice() {
        return buys.isEmpty() ? -1 : buys.firstKey();
    }

    /**
     * The best offer.
     *
     * @return the lowest resting offer price, or -1 if the side is empty
     */
    public synchronized long bestSellPrice() {
        return sells.isEmpty() ? -1 : sells.firstKey();
    }

    /**
     * The size available at the best bid.
     *
     * @return units resting at the best bid, or -1 if the side is empty
     */
    public synchronized long bestBuyUnits() {
        return buys.isEmpty() ? -1 : buys.firstEntry().getValue();
    }

    /**
     * The size available at the best offer.
     *
     * @return units resting at the best offer, or -1 if the side is empty
     */
    public synchronized long bestSellUnits() {
        return sells.isEmpty() ? -1 : sells.firstEntry().getValue();
    }

    /**
     * The buy side, aggregated to price levels.
     *
     * @return units by price, highest price first; a snapshot, not a view
     */
    public synchronized Map<Long, Long> buyLevels() {
        return Collections.unmodifiableMap(new TreeMap<>(buys));
    }

    /**
     * The sell side, aggregated to price levels.
     *
     * @return units by price, lowest price first; a snapshot, not a view
     */
    public synchronized Map<Long, Long> sellLevels() {
        return Collections.unmodifiableMap(new TreeMap<>(sells));
    }

    /**
     * Reset the book to its just-constructed state — empty levels and
     * {@code initialized=false}. Used by {@code MarketView}'s Phase 2b
     * gap-recovery flow: on a detected gap the caller refetches the
     * REST snapshot, calls {@link #clear()}, then replays the snapshot
     * via {@link #update(Order[])} so the next delta with
     * {@code isAvailable=false} doesn't underflow against stale price
     * levels.
     */
    public synchronized void clear() {
        buys.clear();
        sells.clear();
        initialized = false;
    }

    private void add(Side side, long price, long units) {
        var levels = priceLevels(side);
        levels.merge(price, units, Long::sum);
    }

    private void remove(Side side, long price, long units) {
        var levels = priceLevels(side);
        var current = levels.getOrDefault(price, 0L);
        var updated = current - units;
        if (updated <= 0) {
            levels.remove(price);
        } else {
            levels.put(price, updated);
        }
    }

    private TreeMap<Long, Long> priceLevels(Side side) {
        return Side.BUY == side ? buys : sells;
    }
}
