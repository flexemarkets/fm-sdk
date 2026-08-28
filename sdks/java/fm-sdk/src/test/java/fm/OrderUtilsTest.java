package fm;

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
class OrderUtilsTest {

    private static Order order(long id, long original, long supplier, OrderType type) {
        return new Order(null, null, id, original, supplier, null, type, OrderSide.BUY,
                10L, 100L, null, 8L, 1L, 300L, "STK", 11L, null, null);
    }

    @Test
    void anOrderThatIsItsOwnOriginalAndSupplierWasSubmitted() {
        assertThat(OrderUtils.isSubmit(order(5, 5, 5, OrderType.LIMIT))).isTrue();
    }

    @Test
    void aSplitOrTradeCarriesTheIdItCameFromAndWasNot() {
        assertThat(OrderUtils.isSubmit(order(9, 5, 5, OrderType.LIMIT)))
                .as("a child of order 5")
                .isFalse();
        assertThat(OrderUtils.isSubmit(order(9, 9, 5, OrderType.LIMIT)))
                .as("supplied by order 5")
                .isFalse();
    }

    /** Somebody sent it, even though it names the order it cancels. */
    @Test
    void aCancelCountsAsASubmission() {
        assertThat(OrderUtils.isSubmit(order(9, 5, 5, OrderType.CANCEL))).isTrue();
    }
}
