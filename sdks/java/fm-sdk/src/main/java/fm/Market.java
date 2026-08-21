package fm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Market(
    long id,
    long marketplaceId,
    String name,
    String description,
    String symbol,
    boolean privateMarket,
    long priceMinimum,
    long priceMaximum,
    long priceTick,
    long unitMinimum,
    long unitMaximum,
    long unitTick) {

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
     */
    static long tickRound(long value, long minimum, long maximum, long tick) {
        if (tick <= 0) {
            return Math.min(Math.max(value, minimum), maximum);
        }

        long highest = minimum + ((maximum - minimum) / tick) * tick;
        long rounded = minimum + Math.floorDiv(value - minimum, tick) * tick;
        return Math.min(Math.max(rounded, minimum), highest);
    }

    /** {@code price} moved down to the nearest price this market will accept. */
    public long priceRound(long price) {
        return tickRound(price, priceMinimum, priceMaximum, priceTick);
    }

    /**
     * {@code units} moved down to the nearest size this market will accept.
     *
     * <p>The counterpart to {@link #priceRound}, which did not exist even
     * though the server checks units on exactly the same terms and refuses
     * with "units is not on a tic". A caller rounding a price and passing raw
     * units had half a guard.
     */
    public long unitRound(long units) {
        return tickRound(units, unitMinimum, unitMaximum, unitTick);
    }

}
