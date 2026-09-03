package fm;

import fm.model.Account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

/**
 * An account nobody has ruled on yet.
 *
 * <p>The server sends {@code "approval": null} for a freshly signed-up
 * account, and {@link Account#approval()} was a primitive {@code
 * boolean}. Jackson cannot map null into one, so it refused the response --
 * and because the refusal happened while <em>reading</em>, two calls broke in
 * ways that did not look like a type problem:
 *
 * <ul>
 *   <li>{@code signup()} threw although the account had been created. Only
 *       parsing the answer failed.</li>
 *   <li>{@code accounts()} threw as soon as a single pending account existed
 *       anywhere in the list, taking every other account down with it. That
 *       one is quiet and cumulative: the api-validator's own cleanup calls
 *       accounts(), so it stopped cleaning up and left its test accounts
 *       behind, each of them pending, each making the next run worse.</li>
 * </ul>
 */
class PendingApprovalTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void anAccountAwaitingApprovalParses() {
        var json = """
            {"id":7,"name":"pending-account","approval":null}""";

        var account = MAPPER.readValue(json, Account.class);

        assertThat(account.name()).isEqualTo("pending-account");
        assertThat(account.approval()).isNull();
        assertThat(account.isApproved()).isFalse();
    }

    @Test
    void undecidedIsNotTheSameAsRefused() {
        var pending  = MAPPER.readValue("{\"id\":1,\"approval\":null}", Account.class);
        var refused  = MAPPER.readValue("{\"id\":2,\"approval\":false}", Account.class);
        var approved = MAPPER.readValue("{\"id\":3,\"approval\":true}", Account.class);

        // The distinction the primitive could not hold: all three are
        // different, and only the last is approved.
        assertThat(pending.approval()).isNull();
        assertThat(refused.approval()).isFalse();
        assertThat(approved.approval()).isTrue();

        assertThat(pending.isApproved()).isFalse();
        assertThat(refused.isApproved()).isFalse();
        assertThat(approved.isApproved()).isTrue();
    }

    @Test
    void onePendingAccountDoesNotSpoilTheList() {
        var json = """
            [{"id":1,"name":"approved","approval":true},
             {"id":2,"name":"pending","approval":null},
             {"id":3,"name":"also-approved","approval":true}]""";

        assertThatCode(() -> {
            var accounts = MAPPER.readValue(json, Account[].class);

            assertThat(accounts).hasSize(3);
            assertThat(accounts[1].approval()).isNull();
        }).doesNotThrowAnyException();
    }

    /** An absent field, not merely a null one, reads the same way. */
    @Test
    void anAbsentApprovalIsAlsoUndecided() {
        var account = MAPPER.readValue("{\"id\":9,\"name\":\"quiet\"}", Account.class);

        assertThat(account.approval()).isNull();
        assertThat(account.isApproved()).isFalse();
    }
}
