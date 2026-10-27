package fm.model;

import fm.Decimals;
import fm.role.Administration;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The legal values for one dimension of a market: a range, and a step.
 *
 * <p>A market has two of these, and the server enforces both the same way — a
 * value must lie within the bounds and satisfy
 * {@code (value - minimum) % tick}. Naming the pair is what stops
 * {@link Administration#createMarket} taking six adjacent {@code long}s, where
 * transposing the price tick and the unit minimum would compile, post, and
 * produce a market nobody could trade in.
 *
 * <p>A {@code tick} of zero marks a fixed dimension: {@code minimum} and
 * {@code maximum} are equal and there is one legal value.
 *
 * @param minimum the lowest legal value
 * @param maximum the highest legal value, which is only itself legal when
 *                the range is a whole number of ticks
 * @param tick    the step between legal values; zero marks a fixed dimension
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TickGrid(long minimum, long maximum, long tick) {


    /**
     * The usual unit dimension at the default scale, whole units: one to a
     * hundred.
     *
     * <p>What {@code createMarket} hardcoded before it took a unit grid, so
     * this is the value that leaves an existing market unchanged.
     *
     * @return a grid of 1 to 100, stepping by 1
     */
    public static TickGrid units() {
        return units(Decimals.DEFAULT_UNIT_DECIMALS);
    }

    /**
     * The usual unit dimension at {@code unitDecimals}: whole units, one to a
     * hundred, counted in steps of {@code 10^-unitDecimals}.
     *
     * <p>Whole units, not smallest steps: at three unit decimals this is
     * 1000 to 100000 by 1000 -- the same one to a hundred units a person
     * means -- rather than 0.001 to 0.100.
     *
     * @param unitDecimals the decimal places the market's units carry
     * @return a grid of {@code 10^d} to {@code 100 * 10^d}, stepping by
     *         {@code 10^d}
     * @throws IllegalArgumentException if {@code unitDecimals} is negative or
     *         the grid would not fit a {@code long}
     */
    public static TickGrid units(int unitDecimals) {
        if (unitDecimals < 0) {
            throw new IllegalArgumentException("unitDecimals must not be negative: " + unitDecimals);
        }
        try {
            long whole = 1;
            for (int i = 0; i < unitDecimals; i++) {
                whole = Math.multiplyExact(whole, 10L);
            }
            return new TickGrid(whole, Math.multiplyExact(whole, 100L), whole);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("unitDecimals is too large: " + unitDecimals);
        }
    }

    /**
     * {@code value} moved down onto this grid, clamped to it.
     *
     * <p>The rule itself, which {@link Market#priceRound} and
     * {@link Market#unitRound} apply to a market's two dimensions -- so a
     * caller holding a grid does not have to hold a market too.
     *
     * @param value the value to round
     * @return the largest legal value not greater than {@code value}, clamped
     *         into the grid
     */
    public long round(long value) {
        return round(value, minimum, maximum, tick);
    }

    /**
     * A value moved down onto a bounded tick grid.
     *
     * <p>The server applies this rule twice, to price and to units, and spells
     * it the same way both times: a value must lie within its bounds and
     * satisfy {@code (value - minimum) % tick}. So the grid is anchored at the
     * <em>minimum</em>, not at zero, and this used to subtract
     * {@code value % tick} — right whenever the floor happens to be a multiple
     * of the tick, and wrong for the rest in a way that yields a plausible
     * number rather than an error. With a floor of 110 and a tick of 25 the
     * legal values are 110/135/160/185, and rounding 137 gave 125.
     *
     * <p>The ceiling is the highest legal tick rather than {@code maximum}
     * itself: clamping to the maximum lands off the grid whenever the range is
     * not a whole number of ticks, which is the case this exists for.
     *
     * <p>A tick of zero marks a fixed dimension — the two bounds are equal and
     * there is one legal value. It used to divide by zero.
     *
     * <p>Static, and taking its bounds loose, for {@link Market}: a market
     * holds six longs rather than two grids, so this spares it building a
     * {@code TickGrid} per rounding call.
     *
     * @param value   the value to round
     * @param minimum the lowest legal value
     * @param maximum the highest legal value
     * @param tick    the step between legal values; zero marks a fixed
     *                dimension
     * @return the largest legal value not greater than {@code value}, clamped
     *         into the grid
     */
    static long round(long value, long minimum, long maximum, long tick) {
        if (tick <= 0) {
            return Math.min(Math.max(value, minimum), maximum);
        }

        long highest = minimum + ((maximum - minimum) / tick) * tick;
        long rounded = minimum + Math.floorDiv(value - minimum, tick) * tick;
        return Math.min(Math.max(rounded, minimum), highest);
    }
}
