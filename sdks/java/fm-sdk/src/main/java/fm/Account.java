package fm;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Account(
    Instant createdDate,
    Instant lastModifiedDate,
    Long id,
    String name,
    String description,
    Person owner,
    /**
     * Whether the account has been approved, or null where nobody has
     * decided yet.
     *
     * <p>Boxed because that third state is real and the server sends it:
     * a freshly signed-up account carries {@code "approval": null} until
     * an administrator rules on it. As a primitive this could not hold the
     * value, and Jackson refused the whole response -- so signup() threw
     * even though the account had been created, and accounts() threw as
     * soon as one pending account existed anywhere in the list.
     */
    Boolean approval,
    String approvalDescription) {

    /** Approved, treating "not yet decided" as not approved. */
    public boolean isApproved() {
        return Boolean.TRUE.equals(approval);
    }

    /**
     * Built from the wire, where a timestamp is a string in one of two shapes.
     * See {@link Timestamps}; a @JsonCreator rather than a @JsonDeserialize so
     * the record does not depend on one Jackson major.
     */
    @JsonCreator
    static Account fromWire(
            @JsonProperty("createdDate") String createdDate,
            @JsonProperty("lastModifiedDate") String lastModifiedDate,
            @JsonProperty("id") Long id,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("owner") Person owner,
            @JsonProperty("approval") Boolean approval,
            @JsonProperty("approvalDescription") String approvalDescription) {
        return new Account(Timestamps.parse(createdDate), Timestamps.parse(lastModifiedDate), id, name, description, owner, approval, approvalDescription);
    }

}
