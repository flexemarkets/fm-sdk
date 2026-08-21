package fm;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Person(
    Instant createdDate,
    Instant lastModifiedDate,
    Long id,
    Long accountId,
    String firstName,
    String lastName,
    String email,
    String[] roles,
    Boolean accountOwner) {

    public Person {
        id           = Objects.requireNonNullElse(id, 0L);
        accountId    = Objects.requireNonNullElse(accountId, 0L);
        accountOwner = Objects.requireNonNullElse(accountOwner, Boolean.FALSE);
    }

    /**
     * Built from the wire, where a timestamp is a string in one of two shapes.
     *
     * <p>A {@code @JsonCreator} rather than a {@code @JsonDeserialize} on each
     * component: the first is a jackson-annotations concern and travels with
     * the record, the second is databind-specific and would tie this type to
     * one Jackson major. These records are meant to survive a consumer's own
     * ObjectMapper, which is the same reason they carry
     * {@code @JsonIgnoreProperties}.
     */
    @JsonCreator
    static Person fromWire(
            @JsonProperty("createdDate") String createdDate,
            @JsonProperty("lastModifiedDate") String lastModifiedDate,
            @JsonProperty("id") Long id,
            @JsonProperty("accountId") Long accountId,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("lastName") String lastName,
            @JsonProperty("email") String email,
            @JsonProperty("roles") String[] roles,
            @JsonProperty("accountOwner") Boolean accountOwner) {
        return new Person(Timestamps.parse(createdDate), Timestamps.parse(lastModifiedDate),
                id, accountId, firstName, lastName, email, roles, accountOwner);
    }
}
