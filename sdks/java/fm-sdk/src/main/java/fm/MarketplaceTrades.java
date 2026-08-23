package fm;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;


/**
 * Every market's trade tape in one marketplace, keyed by market id.
 *
 * <p>The trade-side counterpart to {@link OrderBooks}, and fed the same way:
 * one update, fanned at every tape, each keeping what belongs to it.
 */
public class MarketplaceTrades {
    private final Map<Long, Trades> collection = new ConcurrentHashMap<>();

    /**
     * An empty tape per market.
     *
     * @param markets  the markets to keep trades for
     * @param capacity how many trades each tape retains
     */
    public MarketplaceTrades(List<Market> markets, int capacity) {
        for (var market : markets) {
            collection.put(market.id(), new Trades(market, capacity));
        }
    }

    /**
     * Apply an orders update to every tape.
     *
     * @param orders orders as the stream delivered them
     */
    public void update(Order[] orders) {
        collection.values().forEach(t -> t.update(orders));
    }

    /**
     * Every tape being kept.
     *
     * @return the tapes, in no particular order
     */
    public Collection<Trades> collection() {
        return collection.values();
    }

    /**
     * Recent trade prices for every market, in market-id order.
     *
     * <p>The ordering is the contract: an MVO robot feeds this straight into an
     * optimiser beside a payoff matrix whose rows are in the same order, and a
     * different ordering would value the wrong market.
     *
     * @return one row of prices per market, ordered by market id
     */
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
