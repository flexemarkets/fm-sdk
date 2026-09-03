package fm.model;

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
class TickRoundTest {

    /**
     * The arithmetic this replaced, kept so the defect is demonstrable rather
     * than merely described. Every SDK carried this line.
     */
    private static long _legacyRound(long value, long minimum, long maximum, long tick) {
        return Math.min(Math.max(value - value % tick, minimum), maximum);
    }

    /**
     * The server's own rule, transcribed from {@code OrderDtoConverter}: a
     * value must lie within its bounds and, unless the dimension is fixed, sit
     * on a tick measured from the minimum. This is the oracle -- what the
     * exchange will actually accept -- rather than an assertion about what the
     * SDK happens to compute.
     */
    private static boolean _serverWouldAccept(long value, long minimum, long maximum, long tick) {
        if (value < minimum || value > maximum) {
            return false;
        }
        return tick <= 0 || 0 == (value - minimum) % tick;
    }

    private static Market _market(long minimum, long maximum, long tick) {
        return new Market(11L, 1L, "Stock", null, "STK", false,
                minimum, maximum, tick, 1L, 100L, 1L);
    }

    /** A floor on the tick: the old arithmetic happened to agree here. */
    @Test
    void aGridAnchoredAtAMultipleOfTheTick() {
        var stock = _market(100, 200, 25);

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
        var stock = _market(110, 199, 25);

        assertThat(stock.priceRound(137)).isEqualTo(135L);
        assertThat(stock.priceRound(199)).isEqualTo(185L);
        assertThat(stock.priceRound(110)).isEqualTo(110L);
    }

    @Test
    void pricesOutsideTheRangeAreClamped() {
        var stock = _market(110, 199, 25);

        assertThat(stock.priceRound(5)).isEqualTo(110L);
        assertThat(stock.priceRound(10_000)).isEqualTo(185L);
    }

    /** A tick of zero is a fixed dimension. This used to divide by zero. */
    @Test
    void aFixedPriceMarketHasOneLegalPrice() {
        var fixed = _market(150, 150, 0);

        assertThat(fixed.priceRound(137)).isEqualTo(150L);
        assertThat(fixed.priceRound(150)).isEqualTo(150L);
        assertThat(fixed.priceRound(9_999)).isEqualTo(150L);
    }

    /**
     * Units are the same rule, and had no rounding at all — the server refuses
     * an off-tick size with "units is not on a tic" just as it does a price.
     */
    @Test
    void unitsRoundOntoTheirOwnGrid() {
        var odd = new Market(11L, 1L, "Stock", null, "STK", false,
                100L, 200L, 25L, 3L, 97L, 5L);

        assertThat(odd.unitRound(20)).as("legal sizes are 3, 8, 13, 18, 23").isEqualTo(18L);
        assertThat(odd.unitRound(1)).isEqualTo(3L);
        assertThat(odd.unitRound(1_000)).as("the highest legal tick, not 97").isEqualTo(93L);
    }

    /** Price and units are one rule applied twice, so they agree. */
    @Test
    void bothDimensionsShareTheGrid() {
        var square = new Market(11L, 1L, "Stock", null, "STK", false,
                110L, 199L, 25L, 110L, 199L, 25L);

        assertThat(square.unitRound(137)).isEqualTo(square.priceRound(137));
    }

    // --- the defects, demonstrated -------------------------------------------

    /**
     * The anchoring bug, shown rather than asserted: with a floor of 110 the
     * old arithmetic returns 125, and the server refuses it.
     */
    @Test
    void theOldArithmeticProducedPricesTheServerRefuses() {
        long minimum = 110, maximum = 199, tick = 25;

        long legacy = _legacyRound(137, minimum, maximum, tick);
        assertThat(legacy).isEqualTo(125L);
        assertThat(_serverWouldAccept(legacy, minimum, maximum, tick))
                .as("125 is inside the bounds and off the tick: \"price is not on a tic\"")
                .isFalse();

        long fixed = TickGrid.round(137, minimum, maximum, tick);
        assertThat(fixed).isEqualTo(135L);
        assertThat(_serverWouldAccept(fixed, minimum, maximum, tick)).isTrue();
    }

    /**
     * The clamping bug, which was mine rather than inherited: rounding above
     * the range and clamping to the maximum lands off the grid.
     */
    @Test
    void clampingToTheMaximumWouldAlsoBeRefused() {
        long minimum = 110, maximum = 199, tick = 25;

        assertThat(_serverWouldAccept(maximum, minimum, maximum, tick))
                .as("199 is the ceiling and is not itself a legal price")
                .isFalse();

        long fixed = TickGrid.round(210, minimum, maximum, tick);
        assertThat(fixed).isEqualTo(185L);
        assertThat(_serverWouldAccept(fixed, minimum, maximum, tick)).isTrue();
    }

    /** The fixed-dimension bug: a tick of zero used to divide by zero. */
    @Test
    void theOldArithmeticDividedByZeroOnAFixedDimension() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> _legacyRound(137, 150, 150, 0))
                .isInstanceOf(ArithmeticException.class);

        assertThat(TickGrid.round(137, 150, 150, 0)).isEqualTo(150L);
    }

    /**
     * The whole grid, both dimensions, judged by the server's rule.
     *
     * <p>Sweeping is what caught the clamping bug, and it is what makes this a
     * proof rather than a handful of examples: every value the SDK can produce
     * for these markets is one the exchange would accept.
     */
    @Test
    void everyRoundedValueWouldBeAcceptedByTheServer() {
        long[][] grids = {
            {110, 199, 25},   // range not a whole number of ticks
            {100, 200, 25},   // floor on the tick
            {3, 97, 5},       // the unit dimension from the test above
            {150, 150, 0},    // fixed
            {0, 1000, 1},     // tick of one
        };

        for (long[] grid : grids) {
            long minimum = grid[0], maximum = grid[1], tick = grid[2];
            for (long value = -50; value <= maximum + 50; value++) {
                long rounded = TickGrid.round(value, minimum, maximum, tick);
                assertThat(_serverWouldAccept(rounded, minimum, maximum, tick))
                        .as("grid [%d,%d]/%d rounded %d to %d, which the server refuses",
                            minimum, maximum, tick, value, rounded)
                        .isTrue();
            }
        }
    }

    /** Every result is a price the server would accept. */
    @Test
    void everyResultSitsOnTheGrid() {
        var stock = _market(110, 199, 25);

        for (long price = 0; price <= 300; price++) {
            long rounded = stock.priceRound(price);
            assertThat((rounded - stock.priceMinimum()) % stock.priceTick())
                    .as("rounding %d gave %d, which is off the tick", price, rounded)
                    .isZero();
            assertThat(rounded).isBetween(stock.priceMinimum(), stock.priceMaximum());
        }
    }
}
