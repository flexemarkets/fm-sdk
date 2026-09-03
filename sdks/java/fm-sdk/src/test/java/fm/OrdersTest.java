package fm;

import fm.model.Market;
import fm.model.Order;
import fm.model.OrderSide;
import fm.model.OrderType;
import static org.assertj.core.api.Assertions.assertThat;


import org.junit.jupiter.api.Test;

/**
 * Telling what a participant did from what the exchange did to it.
 *
 * <p>{@code isSubmit} existed in Python and TypeScript from the day the order
 * utilities landed and not in Java, so a Java caller reducing a session's
 * orders to the ones somebody actually sent had to spell out the identity
 * check -- {@code id == original && id == supplier}, plus the cancel case --
 * at each call site, which is exactly the kind of thing an SDK exists to stop
 * its callers rederiving.
 */
class OrdersTest {

    private static Order _order(long id, long original, long supplier, OrderType type) {
        return new Order(null, null, id, original, supplier, null, type, OrderSide.BUY,
                10L, 100L, null, 8L, 1L, 300L, "STK", 11L, null, null);
    }

    @Test
    void anOrderThatIsItsOwnOriginalAndSupplierWasSubmitted() {
        assertThat(Orders.isSubmit(_order(5, 5, 5, OrderType.LIMIT))).isTrue();
    }

    @Test
    void aSplitOrTradeCarriesTheIdItCameFromAndWasNot() {
        assertThat(Orders.isSubmit(_order(9, 5, 5, OrderType.LIMIT)))
                .as("a child of order 5")
                .isFalse();
        assertThat(Orders.isSubmit(_order(9, 9, 5, OrderType.LIMIT)))
                .as("supplied by order 5")
                .isFalse();
    }

    /** Somebody sent it, even though it names the order it cancels. */
    @Test
    void aCancelCountsAsASubmission() {
        assertThat(Orders.isSubmit(_order(9, 5, 5, OrderType.CANCEL))).isTrue();
    }

    // ---- absorbed from fm-robots' own fm.robot.Orders -----------------------
    //
    // These predicates and the factory lived in fm-robots because the SDK did
    // not expose them; the tests came with them. `limit` carries most of the
    // risk and is why they are worth keeping verbatim: Order is a record of
    // nineteen positional components, ten of them long, so transposing two
    // would compile, pass a smoke test, and put orders on the wrong market at
    // the wrong price. Every field is asserted, including the ones left empty.

    @Test
    public void limitPutsEveryValueInTheFieldItBelongsIn() {
        Order order = Orders.limit(market(), OrderSide.BUY, 3L, 250L);

        assertThat(order.marketplaceId()).as("marketplaceId comes from the market").isEqualTo(42L);
        assertThat(order.marketId()).as("marketId is the market's own id").isEqualTo(77L);
        assertThat(order.symbol()).isEqualTo("ALPHA");
        assertThat(order.side()).isEqualTo(OrderSide.BUY);
        assertThat(order.units()).isEqualTo(3L);
        assertThat(order.price()).isEqualTo(250L);
        assertThat(order.type()).isEqualTo(OrderType.LIMIT);
    }

    /**
     * marketId and marketplaceId are both longs and adjacent in meaning, which
     * makes them the pair most likely to be swapped. Distinct values above, and
     * asserted the other way round here, so a transposition cannot pass.
     */
    @Test
    public void limitDoesNotConfuseMarketIdWithMarketplaceId() {
        Order order = Orders.limit(market(), OrderSide.SELL, 1L, 10L);

        assertThat(order.marketId()).isNotEqualTo(order.marketplaceId());
        assertThat(order.marketId()).isEqualTo(77L);
        assertThat(order.marketplaceId()).isEqualTo(42L);
    }

    @Test
    public void limitDoesNotConfuseUnitsWithPrice() {
        Order order = Orders.limit(market(), OrderSide.BUY, 2L, 900L);

        assertThat(order.units()).isEqualTo(2L);
        assertThat(order.price()).isEqualTo(900L);
    }

    /** A new order has no identity or lineage until the platform gives it one. */
    @Test
    public void limitLeavesServerAssignedFieldsEmpty() {
        Order order = Orders.limit(market(), OrderSide.BUY, 1L, 10L);

        assertThat(order.id()).isZero();
        assertThat(order.original()).isZero();
        assertThat(order.supplier()).isZero();
        assertThat(order.consumer()).isNull();
        assertThat(order.sessionId()).isZero();
        assertThat(order.createdDate()).isNull();
        assertThat(order.lastModifiedDate()).isNull();
        assertThat(order.ownerId()).isNull();
        assertThat(order.ownerTarget()).isNull();
        assertThat(order.clientDescription()).isNull();
        assertThat(order.mine()).isNull();
    }

    @Test
    public void limitCarriesTheSideItIsGiven() {
        assertThat(Orders.limit(market(), OrderSide.SELL, 1L, 10L).side())
                .isEqualTo(OrderSide.SELL);
        assertThat(Orders.limit(market(), OrderSide.BUY, 1L, 10L).side())
                .isEqualTo(OrderSide.BUY);
    }

    /**
     * An order is finished from a position's point of desk as soon as it has a
     * consumer, whether it traded whole or was split and left a remainder.
     */
    @Test
    public void isConsumedOrSplitIsTrueOnceThereIsAConsumer() {
        assertThat(Orders.isConsumedOrSplit(withConsumer(null))).isFalse();
        assertThat(Orders.isConsumedOrSplit(withConsumer(9L))).isTrue();
    }

    @Test
    public void isConsumedOrSplitToleratesANullOrder() {
        assertThat(Orders.isConsumedOrSplit(null)).isFalse();
    }

    @Test
    public void isSupplierMatchesAnOrderAgainstTheOneThatSuppliedIt() {
        Order maker = order(100L, 0L);
        Order taker = order(200L, 100L);

        assertThat(Orders.isSupplier(maker, taker)).isTrue();
        assertThat(Orders.isSupplier(taker, maker)).isFalse();
    }

    @Test
    public void isSupplierToleratesNulls() {
        assertThat(Orders.isSupplier(null, order(1L, 0L))).isFalse();
        assertThat(Orders.isSupplier(order(1L, 0L), null)).isFalse();
        assertThat(Orders.isSupplier(null, null)).isFalse();
    }

    private static Market market() {
        return new Market(77L, 42L, "Alpha Co", "desc", "ALPHA",
                          false, 1L, 1000L, 1L, 1L, 100L, 1L);
    }

    private static Order withConsumer(Long consumer) {
        return new Order(null, null, 1L, 1L, 0L, consumer, OrderType.LIMIT, OrderSide.BUY,
                         1L, 1L, null, null, 1L, 1L, "ALPHA", 1L, null, null);
    }

    private static Order order(long id, long supplier) {
        return new Order(null, null, id, id, supplier, null, OrderType.LIMIT, OrderSide.BUY,
                         1L, 1L, null, null, 1L, 1L, "ALPHA", 1L, null, null);
    }
}
