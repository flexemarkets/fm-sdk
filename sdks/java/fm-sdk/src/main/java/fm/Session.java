package fm;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Session(
    long marketplaceId,
    long allocationId,
    long id,
    long original,
    String state,
    String name,
    String description,
    Instant openDate,
    Instant closeDate) {

    public static final String STATE_INIT   = "INIT";
    public static final String STATE_OPEN   = "OPEN";
    public static final String STATE_PAUSED = "PAUSED";
    public static final String STATE_CLOSED = "CLOSED";

    /**
     * Built from the wire, where a timestamp is a string in one of two shapes.
     * See {@link Timestamps}; a @JsonCreator rather than a @JsonDeserialize so
     * the record does not depend on one Jackson major.
     */
    @JsonCreator
    static Session fromWire(
            @JsonProperty("marketplaceId") long marketplaceId,
            @JsonProperty("allocationId") long allocationId,
            @JsonProperty("id") long id,
            @JsonProperty("original") long original,
            @JsonProperty("state") String state,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("openDate") String openDate,
            @JsonProperty("closeDate") String closeDate) {
        return new Session(marketplaceId, allocationId, id, original, state, name, description, Timestamps.parse(openDate), Timestamps.parse(closeDate));
    }

}
