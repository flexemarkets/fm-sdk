package fm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import tools.jackson.databind.json.JsonMapper;

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

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

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
