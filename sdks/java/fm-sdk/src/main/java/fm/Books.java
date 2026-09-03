package fm;

import fm.model.Market;
import fm.model.Order;
import fm.model.OrderSide;
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
 * <p>Building these by hand is supported -- {@link Desk} does it for a
 * caller who does not want to drive the event queue themselves.
 */
public class Books {
    private final Map<Long, Book> _books = new ConcurrentHashMap<>();

    /**
     * An empty book per market.
     *
     * @param markets the markets to keep books for
     */
    public Books(List<Market> markets) {
        for (var market : markets) {
            _books.put(market.id(), new Book(market));
        }
    }

    /**
     * Apply an orders update to every book.
     *
     * @param orders orders as the stream delivered them; each book keeps only
     *               those carrying its own symbol
     */
    public void update(Order[] orders) {
        _books.values().forEach(b -> b.update(orders));
    }

    /**
     * One market's book.
     *
     * @param marketId the market to look up
     * @return its book, or null if no book is kept for that market
     */
    public Book get(long marketId) {
        return _books.get(marketId);
    }

    /**
     * Whether one market's book has resting units on the given side.
     *
     * @param marketId the market to read
     * @param side     the side to test
     * @return true if that side has any resting units; false when the market
     *         is not in this marketplace, which is the answer rather than a
     *         failure -- the other SDKs raise there, and an absent market has
     *         nothing resting either way
     */
    public boolean hasValue(long marketId, OrderSide side) {
        Book book = get(marketId);
        return book != null && book.hasValue(side);
    }

    /**
     * The best price on one market's given side.
     *
     * @param marketId the market to read
     * @param side     the side to read
     * @return the best resting price, or -1 when that side is empty or the
     *         market is not in this marketplace
     */
    public long bestPrice(long marketId, OrderSide side) {
        Book book = get(marketId);
        return book == null ? -1 : book.bestPrice(side);
    }

    /**
     * Every book being kept.
     *
     * @return the books, in no particular order
     */
    public Collection<Book> collection() {
        return _books.values();
    }

    /** Clear every contained book — see {@link Book#clear()}. */
    public void clear() {
        _books.values().forEach(Book::clear);
    }
}
