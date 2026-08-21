package fm;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Order(
    Instant createdDate,
    Instant lastModifiedDate,
    long id,
    long original,
    long supplier,
    Long consumer,
    OrderType type,
    Side side,
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
            @JsonProperty("side") Side side,
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
