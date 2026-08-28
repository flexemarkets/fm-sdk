package fm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The one human label a {@link Person} record can always produce.
 *
 * <p>Every consumer of {@code users()} was writing this join by hand — the
 * reference robot in fm-robot-llm-eval, and anything else that has to put a
 * name next to an {@code ownerId}. None of the three fields it reads can be
 * relied on alone.
 *
 * <p>The same cases run in the Python and TypeScript suites. They are written
 * out rather than shared as a fixture because this is a pure function over one
 * record, not a sequence of updates over an aggregator — the behaviour fixtures
 * drive {@code MarketBook} and {@code Trades} and have nowhere to put it.
 */
class PersonDisplayNameTest {

    private static Person person(String firstName, String lastName, String email) {
        return new Person(null, null, 1L, 1L, firstName, lastName, email, null, null);
    }

    @ParameterizedTest(name = "[{0}] [{1}] [{2}] -> [{3}]")
    @CsvSource(nullValues = "null", value = {
        "Ada,        Lovelace,       ada@example.com,     Ada Lovelace",
        "Ada,        null,           ada@example.com,     Ada",
        "null,       Lovelace,       ada@example.com,     Lovelace",
        "'  Ada  ',  '  Lovelace  ', null,                Ada Lovelace",
        "null,       null,           ada@example.com,     ada@example.com",
        "'',         '',             ada@example.com,     ada@example.com",
        "'   ',      '   ',          '  ada@example.com ',ada@example.com",
        "null,       null,           null,                null",
        "null,       null,           '   ',               null",
    })
    void displayName(String first, String last, String email, String expected) {
        assertThat(person(first, last, email).displayName()).isEqualTo(expected);
    }

    @Test
    void aNameWinsOverTheEmail() {
        // Not "Name <email>". That is a presentation choice, and a caller who
        // wants it composes one — baking it in would make the common case wrong.
        String label = person("Ada", "Lovelace", "ada@example.com").displayName();

        assertThat(label).isEqualTo("Ada Lovelace").doesNotContain("ada@example.com");
    }
}
