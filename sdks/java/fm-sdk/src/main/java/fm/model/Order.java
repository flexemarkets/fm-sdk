package fm.model;

import fm.internal.OrderUtils;
import fm.internal.Timestamps;
import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
/**
 * One order, and its place among the orders it came from.
 *
 * <p>The exchange has no separate trade: a trade is a pair of orders
 * referring to each other, and a cancel is an order that consumes a resting
 * one. So most questions about an order are questions about its
 * relationships, which is why {@link OrderUtils} takes the surrounding
 * orders rather than just this one.
 *
 * @param createdDate       when the order arrived
 * @param lastModifiedDate  when it last changed
 * @param id                the order's id
 * @param original          the order this one descends from; its own id for
 *                          a submission
 * @param supplier          the order that supplied it; its own id for a
 *                          submission
 * @param consumer          the order that consumed it: null if none has,
 *                          zero if it was split -- see
 *                          {@link OrderUtils#isConsumed}
 * @param type              LIMIT or CANCEL, or null if the server named one
 *                          this version does not know
 * @param side              which way round it goes, or null for a cancel
 * @param units             how many units
 * @param price             the price, in the cents the exchange counts in
 * @param mine              whether it is the caller's own; never on the wire
 * @param ownerId           who submitted it
 * @param marketplaceId     the marketplace it was submitted to
 * @param sessionId         the session it belongs to
 * @param symbol            the market's symbol
 * @param marketId          the market's id
 * @param ownerTarget       the named counterparty, resolved server-side
 * @param clientDescription how the submitting client identified itself
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public record Order(
    Instant createdDate,
    Instant lastModifiedDate,
    long id,
    long original,
    long supplier,
    Long consumer,
    OrderType type,
    OrderSide side,
    long units,
    long price,
    @JsonIgnore
    Boolean mine,
    Long ownerId,
    long marketplaceId,
    long sessionId,
    String symbol,
    long marketId,
    String ownerTarget,
    String clientDescription) {


    /**
     * Built from the wire, where a timestamp is a string in one of two shapes.
     * See {@link Timestamps}; a @JsonCreator rather than a @JsonDeserialize so
     * the record does not depend on one Jackson major.
     */
    @JsonCreator
    static Order fromWire(
            @JsonProperty("createdDate") String createdDate,
            @JsonProperty("lastModifiedDate") String lastModifiedDate,
            @JsonProperty("id") long id,
            @JsonProperty("original") long original,
            @JsonProperty("supplier") long supplier,
            @JsonProperty("consumer") Long consumer,
            @JsonProperty("type") OrderType type,
            @JsonProperty("side") OrderSide side,
            @JsonProperty("units") long units,
            @JsonProperty("price") long price,
            @JsonProperty("mine") Boolean mine,
            @JsonProperty("ownerId") Long ownerId,
            @JsonProperty("marketplaceId") long marketplaceId,
            @JsonProperty("sessionId") long sessionId,
            @JsonProperty("symbol") String symbol,
            @JsonProperty("marketId") long marketId,
            @JsonProperty("ownerTarget") String ownerTarget,
            @JsonProperty("clientDescription") String clientDescription) {
        return new Order(Timestamps.parse(createdDate), Timestamps.parse(lastModifiedDate), id, original, supplier, consumer, type, side, units, price, mine, ownerId, marketplaceId, sessionId, symbol, marketId, ownerTarget, clientDescription);
    }

}
