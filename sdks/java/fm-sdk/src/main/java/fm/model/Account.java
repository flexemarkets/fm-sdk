package fm.model;

import fm.role.Administration;
import fm.role.Identity;
import fm.role.Reading;
import fm.internal.Timestamps;
import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
/**
 * An account, and the person who owns it.
 *
 * <p>The unit a server bills and administers. Everyone in one is reachable
 * through {@link Reading#users()}; the account itself through
 * {@link Identity#account()} for the caller's own, or
 * {@link Administration#accountById} for anyone else's.
 *
 * @param createdDate         when the account was created
 * @param lastModifiedDate    when it last changed
 * @param id                  the account's id
 * @param name                the account name, unique across the server
 * @param description         the account's description
 * @param owner               the person who owns it
 * @param approval            whether it has been approved, or null where
 *                            nobody has decided yet
 * @param approvalDescription what the decision was accompanied by
 */

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

    /**
     * Approved, treating "not yet decided" as not approved.
     *
     * @return true only where an administrator has approved the account
     */
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
