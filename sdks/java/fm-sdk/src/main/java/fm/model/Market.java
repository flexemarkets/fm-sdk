package fm.model;

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
     * {@code price} moved down to the nearest price this market will accept.
     *
     * @param price the price to round
     * @return the largest legal price not greater than {@code price}, clamped
     *         into the market's bounds
     */
    public long priceRound(long price) {
        return TickGrid.round(price, priceMinimum, priceMaximum, priceTick);
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
        return TickGrid.round(units, unitMinimum, unitMaximum, unitTick);
    }

}
