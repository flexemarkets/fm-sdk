package fm;

import static fm.OrderUtils.findOrder;
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
public class MarketBook {
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
    public MarketBook(Market market) {
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
                // fm-server broadcasts a cancel as two rows: the CANCEL, and the LIMIT it
                // consumed, which carries the cancel as its consumer. The resting branch
                // below removes that limit, because a cancelled limit was on the book and
                // isResting says so. Removing here as well takes the units off twice.
                //
                // Both removals landing on a level that held only the cancelled order left
                // it empty either way, which is why this survived: the one test that
                // covered a cancel used a single one-unit order. Give the level units from
                // a second order and the cancel takes that one down with it.
                //
                // So remove only when the order being cancelled is not in this batch --
                // which keeps a lone CANCEL working, and stops the pair double-counting.
                if (findOrder(ordersUpdate, order.consumer()) == null) {
                    remove(side, price, units);
                }
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
     * Whether either resting order sits on the given side.
     *
     * <p>The side-generic form of {@link #bestBuyPrice} and friends. Java had
     * only the four fixed variants, so a caller holding a {@link OrderSide} at
     * runtime -- which is most of them, since a side arrives on an order --
     * had to branch on it by hand to reach a book that could already answer.
     *
     * @param side the side to test; anything other than {@link OrderSide#BUY} reads
     *             as the sell side, matching {@code is_buy} in the other SDKs
     * @return true if that side has any resting units
     */
    public synchronized boolean hasValue(OrderSide side) {
        return !(OrderSide.BUY == side ? buys : sells).isEmpty();
    }

    /**
     * The best price on the given side.
     *
     * @param side the side to read; anything other than {@link OrderSide#BUY} reads
     *             as the sell side
     * @return the best resting price on that side, or -1 if it is empty
     */
    public synchronized long bestPrice(OrderSide side) {
        return OrderSide.BUY == side ? bestBuyPrice() : bestSellPrice();
    }

    /**
     * The size available at the best price on the given side.
     *
     * @param side the side to read; anything other than {@link OrderSide#BUY} reads
     *             as the sell side
     * @return units resting at the best price on that side, or -1 if it is
     *         empty
     */
    public synchronized long bestUnits(OrderSide side) {
        return OrderSide.BUY == side ? bestBuyUnits() : bestSellUnits();
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

    private void add(OrderSide side, long price, long units) {
        var levels = priceLevels(side);
        levels.merge(price, units, Long::sum);
    }

    private void remove(OrderSide side, long price, long units) {
        var levels = priceLevels(side);
        var current = levels.getOrDefault(price, 0L);
        var updated = current - units;
        if (updated <= 0) {
            levels.remove(price);
        } else {
            levels.put(price, updated);
        }
    }

    private TreeMap<Long, Long> priceLevels(OrderSide side) {
        return OrderSide.BUY == side ? buys : sells;
    }
}
