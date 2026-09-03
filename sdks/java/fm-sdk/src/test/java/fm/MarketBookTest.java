package fm;

import fm.model.Market;
import fm.model.Order;
import fm.model.OrderSide;
import fm.model.OrderType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;


/**
 * The client-side book, reconstructed from the ORDERS-UPDATE stream.
 *
 * <p>Ported from fm-robots' fm-lib-orderbook when its last consumer moved to
 * this SDK and the library was deleted. The code had already been carried
 * across; the tests had not, so the SDK's copy of this class was running
 * uncovered while a study depended on it.
 *
 * <p>Two of these reproduce named production failures rather than exercising
 * the API, which is why they were worth carrying rather than rewriting: what
 * makes a book wrong is a missed frame, not a missed method.
 */
class MarketBookTest {

    private static Market _market(long id, String symbol) {
        return new Market(id, 0L, symbol, symbol, symbol, false, 0, 10_000, 1, 1, 100, 1);
    }

    /** A resting limit, its id serving as original and supplier. */
    private static Order _limitOf(Market market, long orderId, OrderSide side, long units, long price) {
        return new Order(null, null, orderId, orderId, orderId, null,
                         OrderType.LIMIT, side, units, price, null, null,
                         market.marketplaceId(), 0L, market.symbol(), market.id(), null, null);
    }

    /** The SDK's records are positional; this is the {@code toBuilder} the test was written against. */
    private static Order _with(Order o, Long original, Long supplier, Long consumer,
                              OrderSide side, Long id, Long units) {
        return new Order(o.createdDate(), o.lastModifiedDate(),
                         id != null ? id : o.id(),
                         original != null ? original : o.original(),
                         supplier != null ? supplier : o.supplier(),
                         consumer,
                         o.type(),
                         side != null ? side : o.side(),
                         units != null ? units : o.units(),
                         o.price(), o.mine(), o.ownerId(), o.marketplaceId(), o.sessionId(),
                         o.symbol(), o.marketId(), o.ownerTarget(), o.clientDescription());
    }

    private static Order[] _toArray(Order... orders) {
        return orders;
    }

    private static OrderSide _contra(OrderSide side) {
        return side.contra();
    }

    /** What fm-server broadcasts for a cancel: the cancel, and the limit it consumed. */
    private static Order[] _cancelSet(Order order) {
        long cancelId = order.id() + 1;

        var cancel = new Order(null, null, cancelId, cancelId, order.id(), order.id(),
                               OrderType.CANCEL, order.side(), order.units(), order.price(),
                               null, null, order.marketplaceId(), order.sessionId(),
                               order.symbol(), order.marketId(), null, null);
        var limit = _with(order, null, null, cancel.id(), null, null, null);

        return _toArray(limit, cancel);
    }

    /** A full cross: the limit gains a consumer, and the contra side arrives with it. */
    private static Order[] _crossSet(Order order) {
        long crossId = order.id() + 1;

        var limit = _with(order, null, null, crossId, null, null, null);
        var cross = _with(order, crossId, crossId, limit.id(), _contra(limit.side()), crossId, null);

        return _toArray(limit, cross);
    }

    /**
     * A partial fill: the split marker (the previous fragment, consumer set to
     * zero), the remainder carrying the unfilled units, and the trade pair.
     */
    private static Order[] _crossSplitWithUnitsSet(Order order, long units) {
        var split = _with(order, null, null, 0L, null, null, null);
        var remainder = _with(split, split.id(), split.id(), null, null, split.id() + 1,
                             order.units() - units);
        var match = _with(remainder, split.id(), split.id(), null, null, remainder.id() + 1, units);
        var cross = _crossSet(match);

        return _toArray(split, remainder, cross[0], cross[1]);
    }

    @Test
    void restingCrossingAndCancellingMoveTheTopOfBook() {
        var market = _market(1, "N5");
        var book = new MarketBook(market);

        assertThat(book.bestBuyPrice()).isEqualTo(-1L);
        assertThat(book.bestSellPrice()).isEqualTo(-1L);
        assertThat(book.bestBuyUnits()).isEqualTo(-1L);
        assertThat(book.bestSellUnits()).isEqualTo(-1L);

        var sell = _limitOf(market, 1, OrderSide.SELL, 1, 100);
        book.update(_toArray(sell));

        assertThat(book.bestSellPrice()).isEqualTo(100L);
        assertThat(book.bestSellUnits()).isEqualTo(1L);
        assertThat(book.bestBuyPrice()).isEqualTo(-1L);

        book.update(_cancelSet(sell));

        assertThat(book.bestSellPrice()).as("cancelled").isEqualTo(-1L);
        assertThat(book.bestSellUnits()).isEqualTo(-1L);

        book.update(_toArray(sell));
        book.update(_crossSet(sell));

        assertThat(book.bestSellPrice()).as("crossed").isEqualTo(-1L);
        assertThat(book.bestSellUnits()).isEqualTo(-1L);

        var buy = _limitOf(market, 1, OrderSide.BUY, 30, 900);
        book.update(_toArray(buy));
        assertThat(book.bestBuyPrice()).isEqualTo(900L);

        book.update(_crossSplitWithUnitsSet(buy, 1));

        assertThat(book.bestBuyPrice()).isEqualTo(900L);
        assertThat(book.bestBuyUnits()).as("one of thirty filled").isEqualTo(29L);
        assertThat(book.bestSellPrice()).isEqualTo(-1L);
    }

    /**
     * The chain observed in production (Paris session 177451, ~10:01): a 6-unit
     * BUY consumed by five sequential 1-unit partial fills, then a cancel of
     * the last remainder. The book must be empty afterwards — anything else is
     * the stale bid a bot would keep firing into.
     */
    @Test
    void aPartialFillChainThenCancelDrainsTheBook() {
        var market = _market(1, "N10");
        var book = new MarketBook(market);

        long price = 708;
        long nextId = 100;
        long originalId = nextId++;
        book.update(_toArray(_limitOf(market, originalId, OrderSide.BUY, 6, price)));
        assertThat(book.bestBuyUnits()).as("initial").isEqualTo(6L);

        long restingId = originalId;
        long restingUnits = 6;
        for (int i = 0; i < 5; i++) {
            long splitId = restingId;
            long remainderId = nextId++;
            long matchId = nextId++;
            long crossId = nextId++;
            long remainingUnits = restingUnits - 1;

            var splitMarker = _with(_limitOf(market, splitId, OrderSide.BUY, restingUnits, price),
                                   null, null, 0L, null, null, null);
            var remainder = _with(_limitOf(market, remainderId, OrderSide.BUY, remainingUnits, price),
                                 originalId, splitId, null, null, null, null);
            var matched = _with(_limitOf(market, matchId, OrderSide.BUY, 1, price),
                               originalId, splitId, crossId, null, null, null);
            var cross = _with(_limitOf(market, crossId, OrderSide.SELL, 1, price),
                             crossId, crossId, matchId, null, null, null);

            book.update(_toArray(splitMarker, remainder, matched, cross));

            assertThat(book.bestBuyUnits()).as("after fill " + (i + 1)).isEqualTo(remainingUnits);
            restingId = remainderId;
            restingUnits = remainingUnits;
        }

        assertThat(book.bestBuyUnits()).as("one unit left after five fills").isEqualTo(1L);

        var cancelled = _with(_limitOf(market, restingId, OrderSide.BUY, restingUnits, price),
                             originalId, restingId, null, null, null, null);
        book.update(_cancelSet(cancelled));

        assertThat(book.bestBuyPrice()).as("price cleared after the cancel").isEqualTo(-1L);
        assertThat(book.buyLevels()).as("buy side empty").isEmpty();
    }

    /**
     * One dropped frame mid-chain, which is what a sequence gap looks like from
     * the book's side: the fourth batch arrives without its split marker.
     *
     * <p>The assertion records the damage rather than a fix — after the final
     * cancel the book still shows a bid that the server does not have, and goes
     * on showing it. That is why a consumer must detect the gap and re-seed
     * from REST rather than trust the book: venture-credit's resync exists for
     * exactly this, and this test is the reason it cannot be dropped.
     */
    @Test
    void aDroppedSplitMarkerLeavesGhostUnitsThatSurviveTheCancel() {
        var market = _market(1, "N10");
        var book = new MarketBook(market);

        long price = 708;
        long nextId = 100;
        long originalId = nextId++;
        book.update(_toArray(_limitOf(market, originalId, OrderSide.BUY, 6, price)));

        int dropAtFill = 3;

        long restingId = originalId;
        long restingUnits = 6;
        for (int i = 0; i < 5; i++) {
            long splitId = restingId;
            long remainderId = nextId++;
            long matchId = nextId++;
            long crossId = nextId++;
            long remainingUnits = restingUnits - 1;

            var splitMarker = _with(_limitOf(market, splitId, OrderSide.BUY, restingUnits, price),
                                   null, null, 0L, null, null, null);
            var remainder = _with(_limitOf(market, remainderId, OrderSide.BUY, remainingUnits, price),
                                 originalId, splitId, null, null, null, null);
            var matched = _with(_limitOf(market, matchId, OrderSide.BUY, 1, price),
                               originalId, splitId, crossId, null, null, null);
            var cross = _with(_limitOf(market, crossId, OrderSide.SELL, 1, price),
                             crossId, crossId, matchId, null, null, null);

            book.update(i == dropAtFill
                    ? _toArray(remainder, matched, cross)
                    : _toArray(splitMarker, remainder, matched, cross));

            restingId = remainderId;
            restingUnits = remainingUnits;
        }

        book.update(_cancelSet(_limitOf(market, restingId, OrderSide.BUY, restingUnits, price)));

        assertThat(book.bestBuyPrice())
            .as("a ghost bid survives the cancel, and only a re-seed clears it")
            .isEqualTo(price);
        assertThat(book.bestBuyUnits()).as("ghost units").isPositive();
    }

    /**
     * The side-generic accessors, which Java did not have.
     *
     * <p>Python and TypeScript have carried {@code hasValue}, {@code bestPrice}
     * and {@code bestUnits} all along; Java shipped only the four fixed
     * variants, so a caller holding a {@link OrderSide} at runtime -- which is most
     * of them, since a side arrives on an order -- branched by hand to reach a
     * book that could already answer. The parity check could not see it: it
     * compared the client surface and stopped there.
     */
    @Test
    void sideGenericAccessorsAgreeWithTheFixedOnes() {
        Market market = _market(1L, "A");
        MarketBook book = new MarketBook(market);
        book.update(_toArray(
            _limitOf(market, 1L, OrderSide.BUY, 10, 100),
            _limitOf(market, 2L, OrderSide.BUY, 5, 90),
            _limitOf(market, 3L, OrderSide.SELL, 7, 110)));

        assertThat(book.bestPrice(OrderSide.BUY)).isEqualTo(book.bestBuyPrice()).isEqualTo(100);
        assertThat(book.bestPrice(OrderSide.SELL)).isEqualTo(book.bestSellPrice()).isEqualTo(110);
        assertThat(book.bestUnits(OrderSide.BUY)).isEqualTo(book.bestBuyUnits()).isEqualTo(10);
        assertThat(book.bestUnits(OrderSide.SELL)).isEqualTo(book.bestSellUnits()).isEqualTo(7);
        assertThat(book.hasValue(OrderSide.BUY)).isTrue();
        assertThat(book.hasValue(OrderSide.SELL)).isTrue();
    }

    @Test
    void sideGenericAccessorsReportAnEmptySide() {
        Market market = _market(1L, "A");
        MarketBook book = new MarketBook(market);
        book.update(_toArray(_limitOf(market, 1L, OrderSide.BUY, 10, 100)));

        assertThat(book.hasValue(OrderSide.SELL)).isFalse();
        assertThat(book.bestPrice(OrderSide.SELL)).isEqualTo(-1);
        assertThat(book.bestUnits(OrderSide.SELL)).isEqualTo(-1);
    }

    @Test
    void marketplaceBooksAnswerForAnUnknownMarketRatherThanFailing() {
        Market market = _market(1L, "A");
        MarketplaceBooks books = new MarketplaceBooks(java.util.List.of(market));
        books.update(_toArray(_limitOf(market, 1L, OrderSide.BUY, 10, 100)));

        assertThat(books.bestPrice(1L, OrderSide.BUY)).isEqualTo(100);
        assertThat(books.hasValue(1L, OrderSide.BUY)).isTrue();

        // An absent market has nothing resting either way, so it is answered
        // rather than raised -- the other two SDKs raise here.
        assertThat(books.hasValue(99L, OrderSide.BUY)).isFalse();
        assertThat(books.bestPrice(99L, OrderSide.BUY)).isEqualTo(-1);
    }

    /**
     * Two markets do not share a book.
     *
     * <p>Carried over from fm-robots' BooksTest when fm.robot.Books was
     * deleted for these methods. Every other case there is covered above; this
     * one was not, and it is the case a keying mistake shows up in -- one
     * market's bid answering for another reads as a plausible price rather
     * than as a failure.
     */
    @Test
    void booksAreKeptApartByMarket() {
        Market alpha = _market(1L, "ALPHA");
        Market beta  = _market(2L, "BETA");
        MarketplaceBooks books = new MarketplaceBooks(java.util.List.of(alpha, beta));

        books.update(_toArray(
            _limitOf(alpha, 1L, OrderSide.BUY, 10, 100),
            _limitOf(beta,  2L, OrderSide.BUY, 10, 500)));

        assertThat(books.bestPrice(alpha.id(), OrderSide.BUY)).isEqualTo(100L);
        assertThat(books.bestPrice(beta.id(),  OrderSide.BUY)).isEqualTo(500L);
    }

    // ---- a side-less order is refused, not guessed at ----------------------
    //
    // _priceLevels was `BUY == side ? _buys : _sells` -- the complement of buy
    // rather than a test for sell -- so an order that named no side was filed
    // as an offer. A null side is a real value: fm-server builds a cancel from
    // type/original/marketplaceId/marketId and nothing else, so a cancel that
    // arrives without the limit it consumed reached that branch and took units
    // off a side the order was never on. _cancelSet fabricates the side onto
    // the cancel row, which is why the pair below is worth having: it is the
    // row as the server actually sends it.

    /** A cancel as fm-server builds one: no side, and no units or price either. */
    private static Order _sidelessCancel(Market market, long cancelledId) {
        return new Order(null, null, cancelledId + 1, cancelledId, cancelledId, null,
                         OrderType.CANCEL, null, 0L, 0L, null, null,
                         market.marketplaceId(), 0L, market.symbol(), market.id(), null, null);
    }

    @Test
    void aSidelessCancelIsRefusedRatherThanTakenOffTheOfferSide() {
        var market = _market(1L, "STK");
        var book = new MarketBook(market);

        book.update(_toArray(_limitOf(market, 1L, OrderSide.BUY, 5L, 100L)));

        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
            () -> book.update(_toArray(_sidelessCancel(market, 1L)))
        );
    }

    @Test
    void readingABookWithoutNamingASideIsRefused() {
        var book = new MarketBook(_market(1L, "STK"));

        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> book.hasValue(null));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> book.bestPrice(null));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> book.bestUnits(null));
    }

}
