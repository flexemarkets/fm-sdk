package fm;

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
    String openDate,
    String closeDate) {

    public static final String STATE_INIT   = "INIT";
    public static final String STATE_OPEN   = "OPEN";
    public static final String STATE_PAUSED = "PAUSED";
    public static final String STATE_CLOSED = "CLOSED";
}
