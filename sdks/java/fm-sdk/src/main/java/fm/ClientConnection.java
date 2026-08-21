package fm;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A participant's attachment to a marketplace, and the session it belonged
 * to.
 *
 * <p>{@code sessionId} is nullable because a connection outlives any one
 * session: an open browser tab spans a pause and re-open. It is how a study
 * works out who was present in a particular run, so its absence -- the
 * record simply did not have the component until 0.0.11 -- meant every
 * connection read as belonging to no session at all.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientConnection(
    long marketplaceId,
    @JsonAlias("id") long connectionId,
    long ownerId,
    String established,
    String terminated,
    String description,
    Long sessionId) {
}
