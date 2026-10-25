package fm.study;

/**
 * One market a study's marketplace trades, in the platform's own terms:
 * prices and units in their smallest unit (cents, units), with the bounds
 * and ticks the platform enforces, and how the market trades.
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
 * @param mechanism     {@link #CONTINUOUS}, an order trading the moment it
 *                      crosses, or {@link #CALL}, a call auction: orders
 *                      collect, and a call clears them all at one price
 *                      (contract 0.4)
 * @param callIntervalSeconds for a call market, the seconds between calls
 *                      while a session is open, or null for calls only when
 *                      a manager clears
 * @param callCarry     for a call market, whether what a call leaves
 *                      unfilled waits for the next one, rather than being
 *                      cancelled
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
        long unitTick,
        String mechanism,
        Integer callIntervalSeconds,
        boolean callCarry) {

    /** The market trades continuously: an order trades when it crosses. */
    public static final String CONTINUOUS = "CONTINUOUS";

    /** The market is a call auction: orders collect, and a call clears them together. */
    public static final String CALL = "CALL";

    /** A market that does not say how it trades is continuous. */
    public MarketSpec {
        mechanism = null == mechanism ? CONTINUOUS : mechanism;
    }

    /**
     * A continuous market: the shape before contract 0.4, kept so a study
     * built against an earlier fm-spi still loads and runs on this host.
     *
     * @param symbol        the ticker
     * @param name          a display name
     * @param privateMarket whether participants see only their own orders
     * @param priceMinimum  the lowest price, in cents
     * @param priceMaximum  the highest price, in cents
     * @param priceTick     the price step, in cents
     * @param unitMinimum   the smallest order, in units
     * @param unitMaximum   the largest order, in units
     * @param unitTick      the size step, in units
     */
    public MarketSpec(String symbol, String name, boolean privateMarket, long priceMinimum, long priceMaximum,
            long priceTick, long unitMinimum, long unitMaximum, long unitTick) {
        this(symbol, name, privateMarket, priceMinimum, priceMaximum, priceTick, unitMinimum, unitMaximum, unitTick,
                CONTINUOUS, null, true);
    }

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
     * @return a copy with {@code privateMarket} set, and continuous, since a
     *         call market cannot be private
     */
    public MarketSpec asPrivate() {
        return new MarketSpec(symbol, name, true, priceMinimum, priceMaximum, priceTick, unitMinimum, unitMaximum,
                unitTick);
    }

    /**
     * The same market as a call auction (contract 0.4): orders collect,
     * crossed or not, and a call clears them all at one price -- every
     * {@code intervalSeconds} while a session is open, as a frequent batch
     * auction does, or when a manager clears.
     *
     * <p>A call market cannot be private; the host refuses the combination
     * when it sets the study up.
     *
     * @param intervalSeconds the seconds between calls, or null for calls only
     *                        when a manager clears
     * @param carry           whether what a call leaves unfilled waits for the
     *                        next one, rather than being cancelled
     * @return a copy that is a call market
     */
    public MarketSpec asCall(Integer intervalSeconds, boolean carry) {
        return new MarketSpec(symbol, name, privateMarket, priceMinimum, priceMaximum, priceTick, unitMinimum,
                unitMaximum, unitTick, CALL, intervalSeconds, carry);
    }
}
