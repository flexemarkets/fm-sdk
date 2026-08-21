package fm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Order(
    String createdDate,
    String lastModifiedDate,
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

}
