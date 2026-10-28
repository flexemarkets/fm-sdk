package fm;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;

import fm.error.InvalidArgumentException;

/**
 * One page of a collection that grows without bound, read with {@code ?limit=}
 * and {@code ?before=} (fm-server 4.17).
 *
 * <p>A page is the {@code limit} newest rows by id, in the order the
 * collection is always answered in. {@link #next()} is what to pass as
 * {@code before} for the page after it -- the older rows -- and is null on the
 * last page. The server says so with {@code Link: <...?before=...>;
 * rel="next"}; a page without that header is the last, even when it is full.
 *
 * <pre>{@code
 * for (var page = fm.sessionsPage(id, 100); ; page = fm.sessionsPage(id, 100, page.next())) {
 *     page.rows().forEach(this::report);
 *     if (!page.hasNext()) break;
 * }
 * }</pre>
 *
 * @param rows the page, in the collection's own order
 * @param next the {@code before} that reads the older rows, or null when there are none
 * @param <T>  what the collection holds
 */
public record Page<T>(List<T> rows, Long next) {

    /**
     * A page, its rows copied.
     *
     * @param rows the page, in the collection's own order
     * @param next the {@code before} that reads the older rows, or null when there are none
     */
    public Page {
        rows = List.copyOf(rows);
    }

    /**
     * Whether older rows remain.
     *
     * @return true unless this is the last page
     */
    public boolean hasNext() {
        return null != next;
    }

    /**
     * A page of a collection already read whole, cut as fm-server cuts one:
     * the {@code limit} newest rows by id among those below {@code before},
     * kept in the collection's order. What {@code Flexemarkets}' paged reads
     * do by default, so an implementation that reads only whole collections --
     * a test fake, an in-process client -- pages the same way the server does.
     *
     * <p>The server also caps a page at 5,000 rows; this does not.
     *
     * @param rows   the whole collection, in its own order
     * @param id     each row's id: newer rows have larger ids
     * @param limit  how many rows, 1 or more
     * @param before only rows whose id is below this, or null for the newest
     * @param <T>    what the collection holds
     * @return the page, with {@link #next()} set while older rows remain
     * @throws InvalidArgumentException when {@code limit} is below 1
     */
    public static <T> Page<T> of(List<T> rows, ToLongFunction<T> id, int limit, Long before) {
        if (limit < 1) {
            throw new InvalidArgumentException("limit is 1 or more, not " + limit);
        }
        List<T> older = rows.stream().filter(row -> null == before || id.applyAsLong(row) < before).toList();
        List<Long> newest = older.stream().map(id::applyAsLong).sorted(Comparator.reverseOrder()).limit(limit).toList();
        if (newest.isEmpty()) {
            return new Page<>(List.of(), null);
        }
        long oldest = newest.getLast();
        List<T> page = older.stream().filter(row -> id.applyAsLong(row) >= oldest).toList();
        return new Page<>(page, older.size() > page.size() ? oldest : null);
    }
}
