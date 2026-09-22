package fm.study;

/**
 * One market a study's marketplace trades, in the platform's own terms:
 * prices and units in their smallest unit (cents, units), with the bounds
 * and ticks the platform enforces.
 *
 * @param symbol        the ticker; a study's view names it in expressions
 * @param name          a display name
 * @param privateMarket whether participants see only their own orders
 * @param priceMinimum  the lowest price the market accepts, in cents
 * @param priceMaximum  the highest price the market accepts, in cents
 * @param priceTick     the step prices move in, in cents
 * @param unitMinimum   the smallest order, in units
 * @param unitMaximum   the largest order, in units
 * @param unitTick      the step order sizes move in, in units
 */
public record MarketSpec(
        String symbol,
        String name,
        boolean privateMarket,
        long priceMinimum,
        long priceMaximum,
        long priceTick,
        long unitMinimum,
        long unitMaximum,
        long unitTick) {

    /**
     * A public market with the platform's usual bounds: 0.00-100.00 by a
     * cent, 1-1000 units.
     *
     * @param symbol the ticker
     * @param name   a display name
     * @return the market, public, with those bounds
     */
    public static MarketSpec of(String symbol, String name) {
        return new MarketSpec(symbol, name, false, 0, 10_000, 1, 1, 1_000, 1);
    }

    /**
     * The same market, private: a participant sees only their own orders.
     *
     * @return a copy with {@code privateMarket} set
     */
    public MarketSpec asPrivate() {
        return new MarketSpec(symbol, name, true, priceMinimum, priceMaximum, priceTick, unitMinimum, unitMaximum, unitTick);
    }
}
