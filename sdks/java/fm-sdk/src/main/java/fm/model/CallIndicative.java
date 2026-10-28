package fm.model;

import fm.internal.Timestamps;
import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What an {@link Market#CALL_BOOK_INDICATIVE indicative} call market's book
 * would clear at if it were called now: the price and volume a call would
 * make, and nothing of the orders behind them.
 *
 * <p>The server works it out after each batch of orders that touched the
 * market, and after each call, and publishes it only when it changed. Two
 * values can arrive out of order, so keep the one with the highest
 * {@code seq} for the session: read it on subscribe, then apply only values
 * with a higher {@code seq}.
 *
 * @param marketplaceId the marketplace
 * @param marketId      the market
 * @param sessionId     the session whose book it is
 * @param price         the price the book would clear at, or null when nothing crosses
 * @param volume        units that would trade there; 0 when nothing crosses
 * @param seq           rises with each change in the market; 0 for a value
 *                      read before any was published
 * @param at            when it was worked out
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CallIndicative(
    long marketplaceId,
    long marketId,
    long sessionId,
    Long price,
    long volume,
    long seq,
    Instant at) {

    /**
     * Built from the wire, where {@code at} is a string in one of two
     * shapes. See {@link Timestamps}; a @JsonCreator rather than a
     * @JsonDeserialize so the record does not depend on one Jackson major.
     */
    @JsonCreator
    static CallIndicative fromWire(
            @JsonProperty("marketplaceId") long marketplaceId,
            @JsonProperty("marketId") long marketId,
            @JsonProperty("sessionId") long sessionId,
            @JsonProperty("price") Long price,
            @JsonProperty("volume") long volume,
            @JsonProperty("seq") long seq,
            @JsonProperty("at") String at) {
        return new CallIndicative(marketplaceId, marketId, sessionId, price, volume, seq, Timestamps.parse(at));
    }
}
