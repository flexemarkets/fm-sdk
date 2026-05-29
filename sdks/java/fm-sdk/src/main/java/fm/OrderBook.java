package fm;

import static fm.OrderUtils.isAvailable;
import static fm.OrderUtils.isBuy;
import static fm.OrderUtils.isCancel;
import static fm.OrderUtils.isResting;
import static fm.OrderUtils.isSplit;
import static fm.OrderUtils.isSymbol;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import fm.Types.Market;
import fm.Types.Order;

public class OrderBook {
    private final Market market;
    private final TreeMap<Long, Long> buys;
    private final TreeMap<Long, Long> sells;
    private boolean initialized;

    public OrderBook(Market market) {
        this.market = market;
        this.buys = new TreeMap<>(Collections.reverseOrder());
        this.sells = new TreeMap<>();
    }

    public Market market() { return market; }
    public String symbol() { return market.symbol(); }
    public long marketId() { return market.id(); }

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

            if (isCancel(order)) {
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

    public synchronized long bestBuyPrice() {
        return buys.isEmpty() ? -1 : buys.firstKey();
    }

    public synchronized long bestSellPrice() {
        return sells.isEmpty() ? -1 : sells.firstKey();
    }

    public synchronized long bestBuyUnits() {
        return buys.isEmpty() ? -1 : buys.firstEntry().getValue();
    }

    public synchronized long bestSellUnits() {
        return sells.isEmpty() ? -1 : sells.firstEntry().getValue();
    }

    public synchronized Map<Long, Long> buyLevels() {
        return Collections.unmodifiableMap(new TreeMap<>(buys));
    }

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

    private void add(String side, long price, long units) {
        var levels = priceLevels(side);
        levels.merge(price, units, Long::sum);
    }

    private void remove(String side, long price, long units) {
        var levels = priceLevels(side);
        var current = levels.getOrDefault(price, 0L);
        var updated = current - units;
        if (updated <= 0) {
            levels.remove(price);
        } else {
            levels.put(price, updated);
        }
    }

    private TreeMap<Long, Long> priceLevels(String side) {
        return isBuy(side) ? buys : sells;
    }
}
