package fm;

import fm.model.Market;
import fm.model.Order;
import fm.model.Trade;
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
 * <p>The trade-side counterpart to {@link Books}, and fed the same way:
 * one update, fanned at every tape, each keeping what belongs to it.
 */
public class Tapes {
    private final Map<Long, Tape> _trades = new ConcurrentHashMap<>();

    /**
     * An empty tape per market.
     *
     * @param markets  the markets to keep trades for
     * @param capacity how many trades each tape retains
     */
    public Tapes(List<Market> markets, int capacity) {
        for (var market : markets) {
            _trades.put(market.id(), new Tape(market, capacity));
        }
    }

    /**
     * Apply an orders update to every tape.
     *
     * @param orders orders as the stream delivered them
     * @return the trades each market gained, keyed by market id, with markets
     *         that gained none left out -- which is most of them on most
     *         updates. {@code Desk} dispatches {@code onTrade} from this
     *         rather than diffing tape sizes, since a full tape drops its
     *         oldest as it takes a new one and the size does not move.
     */
    public Map<Long, List<Trade>> update(Order[] orders) {
        Map<Long, List<Trade>> added = new LinkedHashMap<>();

        _trades.forEach((marketId, tape) -> {
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
     * <p>The counterpart to {@link Books#get}, so a caller holding either
     * aggregator reaches one market's desk the same way.
     *
     * @param marketId the market to look up
     * @return its tape, or null if no tape is kept for that market
     */
    public Tape get(long marketId) {
        return _trades.get(marketId);
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
        return _trades.values().stream()
            .sorted(Comparator.comparingLong(Tape::marketId))
            .map(Tape::mostRecentTrades)
            .map(trades -> Stream.of(trades).mapToLong(Trade::price).toArray())
            .toArray(long[][]::new);
    }

    /**
     * Every tape being kept.
     *
     * @return the tapes, in no particular order
     */
    public Collection<Tape> collection() {
        return _trades.values();
    }

    /** Empty every per-market trade tape — see {@link Tape#clear()}. */
    public void clear() {
        _trades.values().forEach(Tape::clear);
    }
}
