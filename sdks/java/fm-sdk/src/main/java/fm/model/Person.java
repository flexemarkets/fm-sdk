package fm.model;

import fm.role.Identity;
import fm.internal.Timestamps;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
/**
 * One person: who they are, and what they are allowed to do.
 *
 * @param createdDate      when the person was created
 * @param lastModifiedDate when they last changed
 * @param id               the person's id
 * @param accountId        the account they belong to
 * @param firstName        their given name
 * @param lastName         their family name
 * @param email            their email, which is also their sign-in name
 * @param roles            the roles the server granted, spelled as it spells
 *                         them -- see {@link Identity#hasRole}
 * @param accountOwner     whether they own the account they belong to
 */

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

    /**
     * The best human label this record carries: the trimmed full name, or the
     * email when there is no name.
     *
     * <p>Every consumer of {@code users()} was writing this, because none of
     * the three fields it reads can be relied on alone -- {@code firstName} and
     * {@code lastName} are each optional, and a person with neither still has
     * to appear somewhere in a report.
     *
     * <p>Deliberately not "Name &lt;email&gt;": that is a presentation choice,
     * and a caller who wants it can compose one from this and {@link #email}.
     *
     * @return the full name, else the email, else null -- and null only for a
     *         record carrying neither, which fm-server does not produce since
     *         email is the account key
     */
    public String displayName() {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        String name = (first + " " + last).trim();

        if (!name.isEmpty()) {
            return name;
        }
        return email == null || email.isBlank() ? null : email.trim();
    }

    /**
     * Substitutes zero for an absent id or account id, so a caller never has
     * to unbox a null on either.
     */
    public Person {
        id           = Objects.requireNonNullElse(id, 0L);
        accountId    = Objects.requireNonNullElse(accountId, 0L);
        accountOwner = Objects.requireNonNullElse(accountOwner, Boolean.FALSE);
        // A user with no roles: the server omits the field rather than sending
        // []. The other three defaults were here and this one was not, so
        // `for (var r : person.roles())` threw where Python and TypeScript
        // iterated an empty list. Identity.hasRole guards against the null,
        // which is a use site defending what construction should have settled.
        roles        = Objects.requireNonNullElse(roles, new String[0]);
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
