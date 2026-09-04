package fm.internal;

import fm.model.ManagerOtpBundle;
import fm.model.Person;
import fm.model.Session;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import tools.jackson.databind.ObjectMapper;


import org.junit.jupiter.api.Test;

/**
 * The conversion happens where it matters: on the way off the wire.
 *
 * <p>Timestamps.parse is unit-tested on its own, but a record can carry an
 * Instant component and still never call the parser -- Jackson would simply
 * leave the field null, and every test that does not look at a date would go on
 * passing. So this reads real server JSON through a mapper and checks the
 * value, not the absence of an exception.
 */
class TimestampWireTest {

    // The SDK's own mapper, not a lookalike. This test used to build a bare
    // JsonMapper, and a bare one rejects the null primitives the server sends
    // (Session.allocationId), so it failed the moment HttpFlexemarkets started
    // saying what it wanted instead of inheriting a Jackson default. The class
    // it is testing is reached the same way WireFixturesTest reaches it: read
    // the wire with the mapper the SDK actually uses, or the test can pass
    // while the real one is misconfigured.
    private static final ObjectMapper MAPPER = HttpFlexemarkets.MAPPER;

    /** Bare, three fractional digits, as api.flexemarkets.com sends it. */
    @Test
    void aPersonsAuditDatesArriveAsInstants() {
        var json = """
            {"id":7,"accountId":51,"email":"dev@dev",
             "createdDate":"2017-04-11T00:54:35.135",
             "lastModifiedDate":"2026-05-16T07:44:50.804552"}
            """;

        var person = MAPPER.readValue(json, Person.class);

        assertThat(person.createdDate()).isEqualTo(Instant.parse("2017-04-11T00:54:35.135Z"));
        assertThat(person.lastModifiedDate()).isEqualTo(Instant.parse("2026-05-16T07:44:50.804552Z"));
    }

    /** Absent dates stay absent rather than becoming an epoch. */
    @Test
    void aPersonWithoutDatesHasNullOnes() {
        var person = MAPPER.readValue("{\"id\":7,\"email\":\"dev@dev\"}", Person.class);

        assertThat(person.createdDate()).isNull();
        assertThat(person.email()).isEqualTo("dev@dev");
    }

    /**
     * The other shape. expiresAt is the one field that comes from a real
     * Instant on the server, so it arrives with a Z and must not be shifted
     * again on the way in.
     */
    @Test
    void anOtpBundleExpiryKeepsItsZone() {
        var json = """
            {"expiresAt":"2026-08-15T18:00:00Z",
             "otps":[{"userId":1,"email":"alice@lab.edu","otp":"123456"}]}
            """;

        var bundle = MAPPER.readValue(json, ManagerOtpBundle.class);

        assertThat(bundle.expiresAt()).isEqualTo(Instant.parse("2026-08-15T18:00:00Z"));
        assertThat(bundle.otps()).singleElement()
                .extracting(ManagerOtpBundle.Entry::otp).isEqualTo("123456");
    }

    /** A session's dates are bare, like the audit ones. */
    @Test
    void aSessionsDatesArriveAsInstants() {
        var json = """
            {"id":300,"marketplaceId":1,"state":"CLOSED",
             "openDate":"2026-08-15T09:00:00.5","closeDate":"2026-08-15T17:30:00"}
            """;

        var session = MAPPER.readValue(json, Session.class);

        assertThat(session.openDate()).isEqualTo(Instant.parse("2026-08-15T09:00:00.5Z"));
        assertThat(session.closeDate()).isEqualTo(Instant.parse("2026-08-15T17:30:00Z"));
        assertThat(session.state()).isEqualTo("CLOSED");
    }

    /** The rest of the record still binds; the creator does not drop fields. */
    @Test
    void theOtherComponentsSurviveTheCreator() {
        var json = """
            {"id":7,"accountId":51,"firstName":"Dev","lastName":"User",
             "email":"dev@dev","roles":["ROLE_MANAGER"],"accountOwner":true}
            """;

        var person = MAPPER.readValue(json, Person.class);

        assertThat(person.id()).isEqualTo(7L);
        assertThat(person.accountId()).isEqualTo(51L);
        assertThat(person.firstName()).isEqualTo("Dev");
        assertThat(person.lastName()).isEqualTo("User");
        assertThat(person.roles()).containsExactly("ROLE_MANAGER");
        assertThat(person.accountOwner()).isTrue();
    }
}
