package fm.model;

import fm.internal.Timestamps;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One pushed widget as the server stored it.
 *
 * <p>Identity is the marketplace, the scope, the participant and the key: a
 * push to the same four replaces what was there. Every widget in a marketplace
 * is cleared when its session closes, so a run starts empty.
 *
 * @param createdDate      when this target and key were first pushed
 * @param lastModifiedDate when they were last pushed, which is what
 *                         {@code ttlSeconds} counts from
 * @param id               the stored widget's id
 * @param marketplaceId    the marketplace it was pushed to
 * @param scope            {@code MARKETPLACE} or {@code USER}
 * @param userId           the participant, for a {@code USER} widget; null
 *                         otherwise
 * @param key              the panel's key
 * @param title            the heading, or null
 * @param emphasis         {@code normal}, {@code strong} or {@code warn}, or
 *                         null for normal
 * @param ttlSeconds       seconds after the last push at which the panel
 *                         blanks, or null for never
 * @param content          the content as it was pushed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Widget(
    Instant createdDate,
    Instant lastModifiedDate,
    Long id,
    Long marketplaceId,
    String scope,
    Long userId,
    String key,
    String title,
    String emphasis,
    Integer ttlSeconds,
    Map<String, Object> content) {

    /**
     * Built from the wire, where a timestamp is a string in one of two shapes.
     * See {@link Timestamps}; a {@code @JsonCreator} rather than a
     * {@code @JsonDeserialize} so the record does not depend on one Jackson
     * major.
     */
    @JsonCreator
    static Widget fromWire(
            @JsonProperty("createdDate") String createdDate,
            @JsonProperty("lastModifiedDate") String lastModifiedDate,
            @JsonProperty("id") Long id,
            @JsonProperty("marketplaceId") Long marketplaceId,
            @JsonProperty("scope") String scope,
            @JsonProperty("userId") Long userId,
            @JsonProperty("key") String key,
            @JsonProperty("title") String title,
            @JsonProperty("emphasis") String emphasis,
            @JsonProperty("ttlSeconds") Integer ttlSeconds,
            @JsonProperty("content") Map<String, Object> content) {
        return new Widget(Timestamps.parse(createdDate), Timestamps.parse(lastModifiedDate),
                id, marketplaceId, scope, userId, key, title, emphasis, ttlSeconds,
                content == null ? Map.of() : content);
    }
}
