package fm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Rounding a price onto the market's tick grid.
 *
 * <p>The grid is anchored at {@code priceMinimum}, because that is what the
 * server checks: {@code (price - priceMinimum) % priceTick}. This subtracted
 * {@code price % priceTick} instead, anchoring at zero — right whenever the
 * floor is a multiple of the tick, which is most markets, and wrong in a way
 * that produces a plausible number for the rest.
 *
 * <p>Same defect as B20's, in a different copy of the same rule.
 */
class PriceRoundTest {

    private static Market market(long minimum, long maximum, long tick) {
        return new Market(11L, 1L, "Stock", null, "STK", false,
                minimum, maximum, tick, 1L, 100L, 1L);
    }

    /** A floor on the tick: the old arithmetic happened to agree here. */
    @Test
    void aGridAnchoredAtAMultipleOfTheTick() {
        var stock = market(100, 200, 25);

        assertThat(stock.priceRound(137)).isEqualTo(125L);
        assertThat(stock.priceRound(125)).isEqualTo(125L);
    }

    /**
     * A floor off the tick, where it did not. Legal prices are 110/135/160/185,
     * so 137 rounds to 135 — the old code gave 125, which the server refuses
     * with "price is not on a tic".
     */
    @Test
    void aGridAnchoredAwayFromZero() {
        var stock = market(110, 199, 25);

        assertThat(stock.priceRound(137)).isEqualTo(135L);
        assertThat(stock.priceRound(199)).isEqualTo(185L);
        assertThat(stock.priceRound(110)).isEqualTo(110L);
    }

    @Test
    void pricesOutsideTheRangeAreClamped() {
        var stock = market(110, 199, 25);

        assertThat(stock.priceRound(5)).isEqualTo(110L);
        assertThat(stock.priceRound(10_000)).isEqualTo(185L);
    }

    /** A tick of zero is a fixed dimension. This used to divide by zero. */
    @Test
    void aFixedPriceMarketHasOneLegalPrice() {
        var fixed = market(150, 150, 0);

        assertThat(fixed.priceRound(137)).isEqualTo(150L);
        assertThat(fixed.priceRound(150)).isEqualTo(150L);
        assertThat(fixed.priceRound(9_999)).isEqualTo(150L);
    }

    /** Every result is a price the server would accept. */
    @Test
    void everyResultSitsOnTheGrid() {
        var stock = market(110, 199, 25);

        for (long price = 0; price <= 300; price++) {
            long rounded = stock.priceRound(price);
            assertThat((rounded - stock.priceMinimum()) % stock.priceTick())
                    .as("rounding %d gave %d, which is off the tick", price, rounded)
                    .isZero();
            assertThat(rounded).isBetween(stock.priceMinimum(), stock.priceMaximum());
        }
    }
}
