package fm.internal;

import fm.Book;
import fm.Desk;
import fm.model.Market;
import fm.model.Order;
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
 * <p>Implementation of {@link Desk}, which is how a caller reaches these: the
 * desk owns one and answers {@code book(marketId)} and {@code books()} off it.
 * Nothing outside the SDK constructs one.
 */
public class BookIndex {
    private final Map<Long, Book> _books = new ConcurrentHashMap<>();

    /**
     * An empty book per market.
     *
     * @param markets the markets to keep books for
     */
    public BookIndex(List<Market> markets) {
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
