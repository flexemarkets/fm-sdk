package fm;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Every market's order book in one marketplace, keyed by market id.
 *
 * <p>Fans each update at every book and lets each filter on its own symbol, so
 * a caller feeds the whole orders update once rather than routing it. An order
 * carrying a symbol no book recognises is silently dropped, which is worth
 * knowing when a book comes back unexpectedly empty.
 *
 * <p>Building these by hand is supported -- {@link MarketView} does it for a
 * caller who does not want to drive the event queue themselves.
 */
public class OrderBooks {
    private final Map<Long, OrderBook> books = new ConcurrentHashMap<>();

    /**
     * An empty book per market.
     *
     * @param markets the markets to keep books for
     */
    public OrderBooks(List<Market> markets) {
        for (var market : markets) {
            books.put(market.id(), new OrderBook(market));
        }
    }

    /**
     * Apply an orders update to every book.
     *
     * @param orders orders as the stream delivered them; each book keeps only
     *               those carrying its own symbol
     */
    public void update(Order[] orders) {
        books.values().forEach(b -> b.update(orders));
    }

    /**
     * One market's book.
     *
     * @param marketId the market to look up
     * @return its book, or null if no book is kept for that market
     */
    public OrderBook get(long marketId) {
        return books.get(marketId);
    }

    /**
     * Every book being kept.
     *
     * @return the books, in no particular order
     */
    public Collection<OrderBook> collection() {
        return books.values();
    }

    /** Clear every contained book — see {@link OrderBook#clear()}. */
    public void clear() {
        books.values().forEach(OrderBook::clear);
    }
}
