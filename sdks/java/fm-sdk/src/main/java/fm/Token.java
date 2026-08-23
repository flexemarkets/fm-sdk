package fm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
/**
 * Proof of a signed-in identity, and who it belongs to.
 *
 * @param requestUrl the endpoint the token was minted against
 * @param person     the person it signs in as
 * @param account    the account they belong to
 * @param token      the bearer token itself, which
 *                   {@link Flexemarkets#connect} accepts as a credential
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public record Token(
    String requestUrl,
    Person person,
    Account account,
    String token) {
}
