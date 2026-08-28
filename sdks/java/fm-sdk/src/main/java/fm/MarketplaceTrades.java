package fm;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;


/**
 * Every market's trade tape in one marketplace, keyed by market id.
 *
 * <p>The trade-side counterpart to {@link MarketplaceBooks}, and fed the same way:
 * one update, fanned at every tape, each keeping what belongs to it.
 */
public class MarketplaceTrades {
    private final Map<Long, MarketTrades> collection = new ConcurrentHashMap<>();

    /**
     * An empty tape per market.
     *
     * @param markets  the markets to keep trades for
     * @param capacity how many trades each tape retains
     */
    public MarketplaceTrades(List<Market> markets, int capacity) {
        for (var market : markets) {
            collection.put(market.id(), new MarketTrades(market, capacity));
        }
    }

    /**
     * Apply an orders update to every tape.
     *
     * @param orders orders as the stream delivered them
     * @return the trades each market gained, keyed by market id, with markets
     *         that gained none left out -- which is most of them on most
     *         updates. {@code MarketView} dispatches {@code onTrade} from this
     *         rather than diffing tape sizes, since a full tape drops its
     *         oldest as it takes a new one and the size does not move.
     */
    public Map<Long, List<Trade>> update(Order[] orders) {
        Map<Long, List<Trade>> added = new LinkedHashMap<>();

        collection.forEach((marketId, tape) -> {
            Trade[] fresh = tape.update(orders);
            if (fresh.length > 0) {
                added.put(marketId, List.of(fresh));
            }
        });

        return added;
    }

    /**
     * One market's tape.
     *
     * <p>The counterpart to {@link MarketplaceBooks#get}, so a caller holding either
     * aggregator reaches one market's view the same way.
     *
     * @param marketId the market to look up
     * @return its tape, or null if no tape is kept for that market
     */
    public MarketTrades get(long marketId) {
        return collection.get(marketId);
    }

    /**
     * Every tape being kept.
     *
     * @return the tapes, in no particular order
     */
    public Collection<MarketTrades> collection() {
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
            .sorted(Comparator.comparingLong(MarketTrades::marketId))
            .map(MarketTrades::mostRecentTrades)
            .map(trades -> Stream.of(trades).mapToLong(Trade::price).toArray())
            .toArray(long[][]::new);
    }

    /** Empty every per-market trade tape — see {@link MarketTrades#clear()}. */
    public void clear() {
        collection.values().forEach(MarketTrades::clear);
    }
}
