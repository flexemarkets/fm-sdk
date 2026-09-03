package fm.model;

import fm.internal.Timestamps;
import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
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
 *
 * @param marketplaceId the marketplace connected to
 * @param connectionId  the server's id for this connection
 * @param ownerId       the person who connected
 * @param established   when the connection opened
 * @param terminated    when it closed, or null while it is still open
 * @param description   how the client identified itself
 * @param sessionId     the session it belonged to, or null if it connected
 *                      outside one
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientConnection(
    long marketplaceId,
    @JsonAlias("id") long connectionId,
    long ownerId,
    Instant established,
    Instant terminated,
    String description,
    Long sessionId) {

    /**
     * Built from the wire, where a timestamp is a string in one of two shapes.
     * See {@link Timestamps}; a @JsonCreator rather than a @JsonDeserialize so
     * the record does not depend on one Jackson major.
     */
    @JsonCreator
    static ClientConnection fromWire(
            @JsonProperty("marketplaceId") long marketplaceId,
            @JsonProperty("connectionId") long connectionId,
            @JsonProperty("ownerId") long ownerId,
            @JsonProperty("established") String established,
            @JsonProperty("terminated") String terminated,
            @JsonProperty("description") String description,
            @JsonProperty("sessionId") Long sessionId) {
        return new ClientConnection(marketplaceId, connectionId, ownerId, Timestamps.parse(established), Timestamps.parse(terminated), description, sessionId);
    }

}
