package fm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import fm.Types.Market;
import fm.Types.Order;

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
class OrderBookTest {

    private static Market market(long id, String symbol) {
        return new Market(id, 0L, symbol, symbol, symbol, false, 0, 10_000, 1, 1, 100, 1);
    }

    /** A resting limit, its id serving as original and supplier. */
    private static Order limitOf(Market market, long orderId, String side, long units, long price) {
        return new Order(null, null, orderId, orderId, orderId, null,
                         Order.TYPE_LIMIT, side, units, price, null, null,
                         market.marketplaceId(), 0L, market.symbol(), market.id(), null, null);
    }

    /** The SDK's records are positional; this is the {@code toBuilder} the test was written against. */
    private static Order with(Order o, Long original, Long supplier, Long consumer,
                              String side, Long id, Long units) {
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

    private static Order[] toArray(Order... orders) {
        return orders;
    }

    private static String contra(String side) {
        return Order.SIDE_BUY.equalsIgnoreCase(side) ? Order.SIDE_SELL : Order.SIDE_BUY;
    }

    /** What fm-server broadcasts for a cancel: the cancel, and the limit it consumed. */
    private static Order[] cancelSet(Order order) {
        long cancelId = order.id() + 1;

        var cancel = new Order(null, null, cancelId, cancelId, order.id(), order.id(),
                               Order.TYPE_CANCEL, order.side(), order.units(), order.price(),
                               null, null, order.marketplaceId(), order.sessionId(),
                               order.symbol(), order.marketId(), null, null);
        var limit = with(order, null, null, cancel.id(), null, null, null);

        return toArray(limit, cancel);
    }

    /** A full cross: the limit gains a consumer, and the contra side arrives with it. */
    private static Order[] crossSet(Order order) {
        long crossId = order.id() + 1;

        var limit = with(order, null, null, crossId, null, null, null);
        var cross = with(order, crossId, crossId, limit.id(), contra(limit.side()), crossId, null);

        return toArray(limit, cross);
    }

    /**
     * A partial fill: the split marker (the previous fragment, consumer set to
     * zero), the remainder carrying the unfilled units, and the trade pair.
     */
    private static Order[] crossSplitWithUnitsSet(Order order, long units) {
        var split = with(order, null, null, 0L, null, null, null);
        var remainder = with(split, split.id(), split.id(), null, null, split.id() + 1,
                             order.units() - units);
        var match = with(remainder, split.id(), split.id(), null, null, remainder.id() + 1, units);
        var cross = crossSet(match);

        return toArray(split, remainder, cross[0], cross[1]);
    }

    @Test
    void restingCrossingAndCancellingMoveTheTopOfBook() {
        var market = market(1, "N5");
        var book = new OrderBook(market);

        assertThat(book.bestBuyPrice()).isEqualTo(-1L);
        assertThat(book.bestSellPrice()).isEqualTo(-1L);
        assertThat(book.bestBuyUnits()).isEqualTo(-1L);
        assertThat(book.bestSellUnits()).isEqualTo(-1L);

        var sell = limitOf(market, 1, Order.SIDE_SELL, 1, 100);
        book.update(toArray(sell));

        assertThat(book.bestSellPrice()).isEqualTo(100L);
        assertThat(book.bestSellUnits()).isEqualTo(1L);
        assertThat(book.bestBuyPrice()).isEqualTo(-1L);

        book.update(cancelSet(sell));

        assertThat(book.bestSellPrice()).as("cancelled").isEqualTo(-1L);
        assertThat(book.bestSellUnits()).isEqualTo(-1L);

        book.update(toArray(sell));
        book.update(crossSet(sell));

        assertThat(book.bestSellPrice()).as("crossed").isEqualTo(-1L);
        assertThat(book.bestSellUnits()).isEqualTo(-1L);

        var buy = limitOf(market, 1, Order.SIDE_BUY, 30, 900);
        book.update(toArray(buy));
        assertThat(book.bestBuyPrice()).isEqualTo(900L);

        book.update(crossSplitWithUnitsSet(buy, 1));

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
        var market = market(1, "N10");
        var book = new OrderBook(market);

        long price = 708;
        long nextId = 100;
        long originalId = nextId++;
        book.update(toArray(limitOf(market, originalId, Order.SIDE_BUY, 6, price)));
        assertThat(book.bestBuyUnits()).as("initial").isEqualTo(6L);

        long restingId = originalId;
        long restingUnits = 6;
        for (int i = 0; i < 5; i++) {
            long splitId = restingId;
            long remainderId = nextId++;
            long matchId = nextId++;
            long crossId = nextId++;
            long remainingUnits = restingUnits - 1;

            var splitMarker = with(limitOf(market, splitId, Order.SIDE_BUY, restingUnits, price),
                                   null, null, 0L, null, null, null);
            var remainder = with(limitOf(market, remainderId, Order.SIDE_BUY, remainingUnits, price),
                                 originalId, splitId, null, null, null, null);
            var matched = with(limitOf(market, matchId, Order.SIDE_BUY, 1, price),
                               originalId, splitId, crossId, null, null, null);
            var cross = with(limitOf(market, crossId, Order.SIDE_SELL, 1, price),
                             crossId, crossId, matchId, null, null, null);

            book.update(toArray(splitMarker, remainder, matched, cross));

            assertThat(book.bestBuyUnits()).as("after fill " + (i + 1)).isEqualTo(remainingUnits);
            restingId = remainderId;
            restingUnits = remainingUnits;
        }

        assertThat(book.bestBuyUnits()).as("one unit left after five fills").isEqualTo(1L);

        var cancelled = with(limitOf(market, restingId, Order.SIDE_BUY, restingUnits, price),
                             originalId, restingId, null, null, null, null);
        book.update(cancelSet(cancelled));

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
        var market = market(1, "N10");
        var book = new OrderBook(market);

        long price = 708;
        long nextId = 100;
        long originalId = nextId++;
        book.update(toArray(limitOf(market, originalId, Order.SIDE_BUY, 6, price)));

        int dropAtFill = 3;

        long restingId = originalId;
        long restingUnits = 6;
        for (int i = 0; i < 5; i++) {
            long splitId = restingId;
            long remainderId = nextId++;
            long matchId = nextId++;
            long crossId = nextId++;
            long remainingUnits = restingUnits - 1;

            var splitMarker = with(limitOf(market, splitId, Order.SIDE_BUY, restingUnits, price),
                                   null, null, 0L, null, null, null);
            var remainder = with(limitOf(market, remainderId, Order.SIDE_BUY, remainingUnits, price),
                                 originalId, splitId, null, null, null, null);
            var matched = with(limitOf(market, matchId, Order.SIDE_BUY, 1, price),
                               originalId, splitId, crossId, null, null, null);
            var cross = with(limitOf(market, crossId, Order.SIDE_SELL, 1, price),
                             crossId, crossId, matchId, null, null, null);

            book.update(i == dropAtFill
                    ? toArray(remainder, matched, cross)
                    : toArray(splitMarker, remainder, matched, cross));

            restingId = remainderId;
            restingUnits = remainingUnits;
        }

        book.update(cancelSet(limitOf(market, restingId, Order.SIDE_BUY, restingUnits, price)));

        assertThat(book.bestBuyPrice())
            .as("a ghost bid survives the cancel, and only a re-seed clears it")
            .isEqualTo(price);
        assertThat(book.bestBuyUnits()).as("ghost units").isPositive();
    }
}
