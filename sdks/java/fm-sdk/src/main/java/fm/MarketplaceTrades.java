package fm;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import fm.Types.Market;
import fm.Types.Order;

public class MarketplaceTrades {
    private final Map<Long, Trades> collection = new ConcurrentHashMap<>();

    public MarketplaceTrades(List<Market> markets, int capacity) {
        for (var market : markets) {
            collection.put(market.id(), new Trades(market, capacity));
        }
    }

    public void update(Order[] orders) {
        collection.values().forEach(t -> t.update(orders));
    }

    public Collection<Trades> collection() {
        return collection.values();
    }

    public long[][] mostRecentPrices() {
        return collection.values().stream()
            .sorted(Comparator.comparingLong(Trades::marketId))
            .map(Trades::mostRecentTrades)
            .map(orders -> Stream.of(orders).mapToLong(Order::price).toArray())
            .toArray(long[][]::new);
    }

    /** Empty every per-market trade tape — see {@link Trades#clear()}. */
    public void clear() {
        collection.values().forEach(Trades::clear);
    }
}
