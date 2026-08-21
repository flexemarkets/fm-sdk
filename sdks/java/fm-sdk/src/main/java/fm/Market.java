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
     * {@code price} moved down to the nearest price this market will accept.
     *
     * <p>Ticks are anchored at {@link #priceMinimum}, not at zero: the server
     * tests {@code (price - priceMinimum) % priceTick}. This subtracted
     * {@code price % priceTick}, which anchors at zero and is only right when
     * the floor happens to be a multiple of the tick. With a floor of 110 and a
     * tick of 25 the legal prices are 110/135/160/185, and rounding 137 gave
     * 125 — inside the bounds, off the tick, and refused by the server with
     * "price is not on a tic".
     *
     * <p>A tick of zero marks a fixed dimension, where the bounds are equal and
     * there is one legal price. That used to divide by zero.
     *
     * <p>The same rule as {@code HttpFlexemarkets.marketableLimit}, which is
     * where it was got right first; this is the copy that was not.
     */
    public long priceRound(long price) {
        if (priceTick <= 0) {
            return Math.min(Math.max(price, priceMinimum), priceMaximum);
        }

        // The ceiling has to be the highest legal tick, not priceMaximum:
        // clamping to the maximum itself lands off the grid whenever the range
        // is not a whole number of ticks, which is the very case this exists
        // for. With 110/199/25 that returned 199 for anything above 185.
        long highest = priceMinimum + ((priceMaximum - priceMinimum) / priceTick) * priceTick;

        long steps = Math.floorDiv(price - priceMinimum, priceTick);
        long rounded = priceMinimum + steps * priceTick;
        return Math.min(Math.max(rounded, priceMinimum), highest);
    }
}
