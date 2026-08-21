package fm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Account(
    String createdDate,
    String lastModifiedDate,
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
}
