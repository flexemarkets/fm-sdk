package fm;

import static fm.OrderUtils.isCancel;
import static fm.OrderUtils.isConsumed;
import static fm.OrderUtils.isSplit;
import static fm.OrderUtils.isSymbol;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import fm.Types.Market;
import fm.Types.Order;

public class Trades {
    private final Market market;
    private final int capacity;
    private final ArrayDeque<Order> container;

    public Trades(Market market, int capacity) {
        if (market == null) throw new NullPointerException("Market is required.");
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be greater than zero.");
        this.market = market;
        this.capacity = capacity;
        this.container = new ArrayDeque<>(capacity);
    }

    public Trades(Market market) {
        this(market, 100);
    }

    public Market market() { return market; }
    public long marketId() { return market.id(); }
    public int capacity() { return capacity; }
    public int size() { return container.size(); }

    public synchronized void update(Order[] ordersUpdate) {
        Map<Long, Order> consumers = new ConcurrentHashMap<>();

        for (var order : ordersUpdate) {
            if (!isSymbol(market.symbol(), order)) continue;
            if (isCancel(order)) continue;
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

    public synchronized Order[] mostRecentTrades() {
        return container.toArray(new Order[0]);
    }

    public synchronized long[] mostRecentPrices() {
        return Stream.of(mostRecentTrades())
            .mapToLong(Order::price)
            .toArray();
    }

    private void saveResting(Order order) {
        if (container.size() == capacity) {
            container.removeFirst();
        }
        container.addLast(order);
    }
}
