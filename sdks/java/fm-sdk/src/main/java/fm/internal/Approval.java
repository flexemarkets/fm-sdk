package fm.internal;

import fm.Account;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The server's answer to an approval request: the account as it now
 * stands, plus what was asked of it.
 *
 * <p>Internal because no caller reaches it: {@code approveAccount} answers an
 * {@link Account}, not this.
 *
 * @param account     the account as it now stands
 * @param description what accompanied the decision
 * @param approve     what was asked of it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Approval(
    Account account,
    String description,
    Boolean approve) {
}
