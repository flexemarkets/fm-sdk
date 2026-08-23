package fm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
/**
 * One market: what it is called, and what the server will accept in it.
 *
 * <p>Price and units are each a bounded grid. The server checks both as
 * {@code (value - minimum) % tick}, so the legal values are anchored at the
 * minimum rather than at zero -- on a market with {@code priceMinimum=1} and
 * {@code priceTick=2} the legal prices are odd. Round with
 * {@link #priceRound} and {@link #unitRound} rather than by hand.
 *
 * @param id             the market's id
 * @param marketplaceId  the marketplace it belongs to
 * @param name           its name
 * @param description    its description
 * @param symbol         its symbol, which is how orders name it
 * @param privateMarket  whether it is hidden from ordinary participants
 * @param priceMinimum   the lowest legal price
 * @param priceMaximum   the highest legal price
 * @param priceTick      the price step; the grid is anchored at
 *                       {@code priceMinimum}, not at zero
 * @param unitMinimum    the smallest legal size
 * @param unitMaximum    the largest legal size
 * @param unitTick       the size step, on the same terms as {@code priceTick}
 */

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

    /**
     * {@code price} moved down to the nearest price this market will accept.
     *
     * @param price the price to round
     * @return the largest legal price not greater than {@code price}, clamped
     *         into the market's bounds
     */
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
     *
     * @param units the size to round
     * @return the largest legal size not greater than {@code units}, clamped
     *         into the market's bounds
     */
    public long unitRound(long units) {
        return tickRound(units, unitMinimum, unitMaximum, unitTick);
    }

}
