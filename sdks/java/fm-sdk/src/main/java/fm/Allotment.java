package fm;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A participant's opening position in an allocation.
 *
 * <p>The server spells the nested capital {@code capital} on some responses
 * and {@code assets} on others, so both are accepted; requests send
 * {@code assets}, which is what {@code /allocations} reads.
 *
 * @param id            the allotment's own id
 * @param allocationId  the allocation it forms part of
 * @param marketplaceId the marketplace it applies to
 * @param ownerId       the participant it is for
 * @param name          the participant's display name
 * @param assets        the cash and positions they start with
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Allotment(
    Long id,
    Long allocationId,
    Long marketplaceId,
    Long ownerId,
    String name,
    @JsonAlias("capital") Assets assets) {
}
