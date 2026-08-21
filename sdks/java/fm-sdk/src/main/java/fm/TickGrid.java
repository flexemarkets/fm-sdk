package fm;

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
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TickGrid(long minimum, long maximum, long tick) {

    /** The usual unit dimension: whole units, one to a hundred. */
    public static TickGrid units() {
        return new TickGrid(1, 100, 1);
    }

    /**
     * {@code value} moved down onto this grid, clamped to it.
     *
     * <p>The same rule {@link Market#priceRound} and {@link Market#unitRound}
     * apply, so a caller holding a grid does not have to hold a market too.
     */
    public long round(long value) {
        return Market.tickRound(value, minimum, maximum, tick);
    }
}
