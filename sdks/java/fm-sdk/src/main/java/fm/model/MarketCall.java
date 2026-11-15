package fm.model;

import fm.internal.Timestamps;
import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One call of a call-auction market: when it was made, what made it, and
 * what it cleared.
 *
 * <p>A call trades every crossing order in the market at one price: the price
 * at which the most units trade, then the one leaving the least imbalance,
 * then the midpoint of the range those span. Its trades arrive as orders, as
 * every trade does; this is the record of the call itself.
 *
 * @param id            the call's id
 * @param marketplaceId the marketplace
 * @param marketId      the market called
 * @param sessionId     the session it was made in
 * @param calledDate    when it was made
 * @param trigger       {@link #TRIGGER_MANUAL}, a manager's Clear now, or
 *                      {@link #TRIGGER_INTERVAL}, the market's schedule
 * @param price         the clearing price, or null when the book did not cross
 * @param volume        units traded
 * @param demand        units bid at or above the price; every bid when nothing cleared
 * @param supply        units offered at or below it; every ask when nothing cleared
 * @param buyOrders     bids in the book the call cleared
 * @param sellOrders    asks in it
 * @param carry         whether what it left unfilled waits for the next call
 * @param rationing     who traded at the margin, the long side's one price
 *                      level the call could not fill whole: one of the
 *                      {@code Market.RATIONING_*} rules, the market's when the
 *                      call was made; {@link Market#RATIONING_TIME} from a
 *                      server older than rationing (fm-server 4.31)
 * @param seed          for a {@link Market#RATIONING_RANDOM} call, the seed its
 *                      margin was drawn with, below 2^53; null for any other.
 *                      The same book and seed clear to the same trades
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketCall(
    long id,
    long marketplaceId,
    long marketId,
    long sessionId,
    Instant calledDate,
    String trigger,
    Long price,
    long volume,
    long demand,
    long supply,
    int buyOrders,
    int sellOrders,
    boolean carry,
    String rationing,
    Long seed) {

    /** A manager's call: Clear now. */
    public static final String TRIGGER_MANUAL = "MANUAL";

    /** The market's interval came round. */
    public static final String TRIGGER_INTERVAL = "INTERVAL";

    /** A call from a server older than rationing cleared by time. */
    public MarketCall {
        rationing = null == rationing ? Market.RATIONING_TIME : rationing;
    }

    /**
     * A call rationed by time, with no seed: the shape before rationing, kept
     * so code built against it still compiles.
     *
     * @param id            the call's id
     * @param marketplaceId the marketplace
     * @param marketId      the market called
     * @param sessionId     the session it was made in
     * @param calledDate    when it was made
     * @param trigger       what made it
     * @param price         the clearing price, or null
     * @param volume        units traded
     * @param demand        units bid at or above the price
     * @param supply        units offered at or below it
     * @param buyOrders     bids in the book
     * @param sellOrders    asks in it
     * @param carry         whether what it left unfilled waits for the next call
     */
    public MarketCall(long id, long marketplaceId, long marketId, long sessionId, Instant calledDate,
            String trigger, Long price, long volume, long demand, long supply, int buyOrders, int sellOrders,
            boolean carry) {
        this(id, marketplaceId, marketId, sessionId, calledDate, trigger, price, volume, demand, supply,
                buyOrders, sellOrders, carry, null, null);
    }

    /**
     * Built from the wire, where {@code calledDate} is a string in one of two
     * shapes. See {@link Timestamps}; a @JsonCreator rather than a
     * @JsonDeserialize so the record does not depend on one Jackson major.
     */
    @JsonCreator
    static MarketCall fromWire(
            @JsonProperty("id") long id,
            @JsonProperty("marketplaceId") long marketplaceId,
            @JsonProperty("marketId") long marketId,
            @JsonProperty("sessionId") long sessionId,
            @JsonProperty("calledDate") String calledDate,
            @JsonProperty("trigger") String trigger,
            @JsonProperty("price") Long price,
            @JsonProperty("volume") long volume,
            @JsonProperty("demand") long demand,
            @JsonProperty("supply") long supply,
            @JsonProperty("buyOrders") int buyOrders,
            @JsonProperty("sellOrders") int sellOrders,
            @JsonProperty("carry") boolean carry,
            @JsonProperty("rationing") String rationing,
            @JsonProperty("seed") Long seed) {
        return new MarketCall(id, marketplaceId, marketId, sessionId, Timestamps.parse(calledDate), trigger, price,
                volume, demand, supply, buyOrders, sellOrders, carry, rationing, seed);
    }
}
