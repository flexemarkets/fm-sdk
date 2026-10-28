package fm.model;

import fm.Decimals;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * @param mechanism      how it trades: {@link #MECHANISM_CONTINUOUS}, an order
 *                       trading the moment it crosses, or {@link #MECHANISM_CALL},
 *                       a call auction -- orders collect, and a call clears them
 *                       all at one price; continuous when the
 *                       server does not say
 * @param callIntervalSeconds for a call market, the seconds between calls while
 *                       the session is open, or null when only a manager calls it
 * @param callCarry      for a call market, whether what a call leaves unfilled
 *                       waits for the next one; true when the server does not say
 * @param priceDecimals  the decimal places a price, the price tick and the price
 *                       bounds carry: at 2, a price of 5000 is 50.00; 2 when the
 *                       server does not say. {@link Decimals} converts
 * @param unitDecimals   the decimal places units, the unit tick and the unit
 *                       bounds carry, and so the grants and holdings of this
 *                       market's asset: at 0 units are whole; 0 when the server
 *                       does not say
 * @param callBook       for a call market, what it shows of its book during the
 *                       call: {@link #CALL_BOOK_SEALED}, a participant's own
 *                       orders only, or {@link #CALL_BOOK_INDICATIVE}, also the
 *                       price it would clear at now and the volume
 *                       ({@link CallIndicative}); sealed when the server does
 *                       not say
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
    long unitTick,
    String mechanism,
    Integer callIntervalSeconds,
    Boolean callCarry,
    Integer priceDecimals,
    Integer unitDecimals,
    String callBook) {

    /** The market trades continuously: an order trades when it crosses. */
    public static final String MECHANISM_CONTINUOUS = "CONTINUOUS";

    /** The market is a call auction: orders collect, and a call clears them together. */
    public static final String MECHANISM_CALL = "CALL";

    /** A call market's book shows nothing of others' orders until a call trades them. */
    public static final String CALL_BOOK_SEALED = "SEALED";

    /** A call market also publishes the price its book would clear at now, and the volume. */
    public static final String CALL_BOOK_INDICATIVE = "INDICATIVE";

    /**
     * The server's defaults, settled here once: a market from a server older
     * than call auctions, or one that leaves the fields out, is continuous,
     * and a call market that does not say carries. One from a server older
     * than per-market decimals counts cents and whole units, and one older
     * than the indicative price is sealed.
     */
    public Market {
        mechanism = null == mechanism ? MECHANISM_CONTINUOUS : mechanism;
        callCarry = null == callCarry || callCarry;
        priceDecimals = null == priceDecimals ? Decimals.DEFAULT_PRICE_DECIMALS : priceDecimals;
        unitDecimals = null == unitDecimals ? Decimals.DEFAULT_UNIT_DECIMALS : unitDecimals;
        callBook = null == callBook ? CALL_BOOK_SEALED : callBook;
    }

    /**
     * A continuous market: the shape before call auctions, kept so code
     * built against it (fm-robots constructs markets this way) still compiles.
     *
     * @param id            the market's id
     * @param marketplaceId the marketplace it belongs to
     * @param name          its name
     * @param description   its description
     * @param symbol        its symbol
     * @param privateMarket whether it is hidden from ordinary participants
     * @param priceMinimum  the lowest legal price
     * @param priceMaximum  the highest legal price
     * @param priceTick     the price step
     * @param unitMinimum   the smallest legal size
     * @param unitMaximum   the largest legal size
     * @param unitTick      the size step
     */
    public Market(long id, long marketplaceId, String name, String description, String symbol,
            boolean privateMarket, long priceMinimum, long priceMaximum, long priceTick,
            long unitMinimum, long unitMaximum, long unitTick) {
        this(id, marketplaceId, name, description, symbol, privateMarket, priceMinimum, priceMaximum, priceTick,
                unitMinimum, unitMaximum, unitTick, MECHANISM_CONTINUOUS, null, true);
    }

    /**
     * A market at the default scales, cents and whole units: the shape before
     * per-market decimals, kept so code built against it (fm-robots copies a
     * call market this way) still compiles.
     *
     * @param id                  the market's id
     * @param marketplaceId       the marketplace it belongs to
     * @param name                its name
     * @param description         its description
     * @param symbol              its symbol
     * @param privateMarket       whether it is hidden from ordinary participants
     * @param priceMinimum        the lowest legal price
     * @param priceMaximum        the highest legal price
     * @param priceTick           the price step
     * @param unitMinimum         the smallest legal size
     * @param unitMaximum         the largest legal size
     * @param unitTick            the size step
     * @param mechanism           how it trades; continuous when null
     * @param callIntervalSeconds for a call market, the seconds between calls
     * @param callCarry           for a call market, whether what a call leaves
     *                            unfilled waits for the next one; true when null
     */
    public Market(long id, long marketplaceId, String name, String description, String symbol,
            boolean privateMarket, long priceMinimum, long priceMaximum, long priceTick,
            long unitMinimum, long unitMaximum, long unitTick, String mechanism,
            Integer callIntervalSeconds, Boolean callCarry) {
        this(id, marketplaceId, name, description, symbol, privateMarket, priceMinimum, priceMaximum, priceTick,
                unitMinimum, unitMaximum, unitTick, mechanism, callIntervalSeconds, callCarry, null, null);
    }

    /**
     * A sealed market: the shape before the indicative price, kept so code
     * built against it still compiles.
     *
     * @param id                  the market's id
     * @param marketplaceId       the marketplace it belongs to
     * @param name                its name
     * @param description         its description
     * @param symbol              its symbol
     * @param privateMarket       whether it is hidden from ordinary participants
     * @param priceMinimum        the lowest legal price
     * @param priceMaximum        the highest legal price
     * @param priceTick           the price step
     * @param unitMinimum         the smallest legal size
     * @param unitMaximum         the largest legal size
     * @param unitTick            the size step
     * @param mechanism           how it trades; continuous when null
     * @param callIntervalSeconds for a call market, the seconds between calls
     * @param callCarry           for a call market, whether what a call leaves
     *                            unfilled waits for the next one; true when null
     * @param priceDecimals       the decimal places a price carries; 2 when null
     * @param unitDecimals        the decimal places units carry; 0 when null
     */
    public Market(long id, long marketplaceId, String name, String description, String symbol,
            boolean privateMarket, long priceMinimum, long priceMaximum, long priceTick,
            long unitMinimum, long unitMaximum, long unitTick, String mechanism,
            Integer callIntervalSeconds, Boolean callCarry, Integer priceDecimals, Integer unitDecimals) {
        this(id, marketplaceId, name, description, symbol, privateMarket, priceMinimum, priceMaximum, priceTick,
                unitMinimum, unitMaximum, unitTick, mechanism, callIntervalSeconds, callCarry, priceDecimals,
                unitDecimals, null);
    }

    /**
     * Whether this is a call auction, where a crossing order rests until a
     * call rather than trading, and a participant sees only their own resting
     * orders.
     *
     * @return true for a call market
     */
    @JsonIgnore
    public boolean isCall() {
        return MECHANISM_CALL.equals(mechanism);
    }

    /**
     * Whether this is a call market that publishes the price its book would
     * clear at now: {@link fm.role.Reading#indicative} reads it, and the
     * stream delivers each change as a {@link CallIndicative}.
     *
     * @return true for an indicative call market
     */
    @JsonIgnore
    public boolean showsIndicative() {
        return isCall() && CALL_BOOK_INDICATIVE.equals(callBook);
    }

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
