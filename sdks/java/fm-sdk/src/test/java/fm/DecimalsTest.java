package fm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import fm.model.TickGrid;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link Decimals}, held to the cases all three SDKs run.
 *
 * <p>See {@code sdks/fixtures/decimals/README.md}.
 */
public class DecimalsTest {

    record Case(String name, long value, int decimals, String text) {
        @Override
        public String toString() {
            return name;
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formatCases")
    public void formatsAsTheSharedFixturesSay(Case c) {
        assertThat(Decimals.format(c.value(), c.decimals())).isEqualTo(c.text());
        assertThat(Decimals.parse(c.text(), c.decimals())).isEqualTo(c.value());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parseCases")
    public void parsesAsTheSharedFixturesSay(Case c) {
        assertThat(Decimals.parse(c.text(), c.decimals())).isEqualTo(c.value());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedCases")
    public void refusesWhatTheSharedFixturesRefuse(Case c) {
        assertThatThrownBy(() -> Decimals.parse(c.text(), c.decimals()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** Guard the guard: a bad path would report everything passing. */
    @Test
    public void thereAreFixturesToRun() throws IOException {
        assertThat(formatCases()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(parseCases()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(refusedCases()).hasSizeGreaterThanOrEqualTo(10);
    }

    /** Java reads the whole of a {@code long}, which TypeScript cannot. */
    @Test
    public void theWholeLongRangeRoundTrips() {
        assertThat(Decimals.format(Long.MAX_VALUE, 2)).isEqualTo("92233720368547758.07");
        assertThat(Decimals.format(Long.MIN_VALUE, 2)).isEqualTo("-92233720368547758.08");
        assertThat(Decimals.parse("92233720368547758.07", 2)).isEqualTo(Long.MAX_VALUE);
        assertThat(Decimals.parse("-92233720368547758.08", 2)).isEqualTo(Long.MIN_VALUE);
        assertThatThrownBy(() -> Decimals.parse("92233720368547758.08", 2))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("out of range");
    }

    @Test
    public void refusesNullAndNegativeDecimals() {
        assertThatThrownBy(() -> Decimals.parse(null, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Decimals.format(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void saysWhyItRefused() {
        assertThatThrownBy(() -> Decimals.parse("50.001", 2))
            .hasMessageContaining("more than 2 decimal places");
        assertThatThrownBy(() -> Decimals.parse("1e2", 2))
            .hasMessageContaining("not a decimal number");
    }

    /**
     * The displays moved onto {@code format} printed {@code "%5.2f"} of
     * {@code cents / 100.0}. Over the range a price takes, the two agree
     * character for character, so the move changes no output.
     */
    @Test
    public void agreesWithTheCentsDisplayItReplaced() {
        for (long cents = -20_000; cents <= 2_000_000; cents++) {
            assertThat("%5s".formatted(Decimals.format(cents, Decimals.DEFAULT_PRICE_DECIMALS)))
                .isEqualTo(String.format(Locale.ROOT, "%5.2f", cents / 100.0));
        }
    }

    @ParameterizedTest(name = "whole units at {0} decimals")
    @MethodSource("unitGridCases")
    public void theDefaultUnitGridIsWholeUnitsAtTheMarketsScale(int decimals, long minimum, long maximum, long tick) {
        assertThat(TickGrid.units(decimals)).isEqualTo(new TickGrid(minimum, maximum, tick));
    }

    @ParameterizedTest(name = "{0} decimals")
    @MethodSource("unitGridRefused")
    public void aUnitGridAtAnImpossibleScaleIsRefused(int decimals) {
        assertThatThrownBy(() -> TickGrid.units(decimals)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void theUnitGridDefaultsToWholeUnitsAndStopsAtALong() {
        assertThat(TickGrid.units()).isEqualTo(new TickGrid(1, 100, 1));
        assertThat(TickGrid.units(16).maximum()).isEqualTo(1_000_000_000_000_000_000L);
        assertThatThrownBy(() -> TickGrid.units(17)).hasMessageContaining("too large");
    }

    static List<Arguments> unitGridCases() throws IOException {
        List<Arguments> out = new ArrayList<>();
        for (JsonNode c : unitGrid().get("cases")) {
            out.add(Arguments.of(c.get("unitDecimals").asInt(), c.get("minimum").asLong(),
                                 c.get("maximum").asLong(), c.get("tick").asLong()));
        }
        return out;
    }

    static List<Integer> unitGridRefused() throws IOException {
        List<Integer> out = new ArrayList<>();
        for (JsonNode d : unitGrid().get("refused")) {
            out.add(d.asInt());
        }
        return out;
    }

    private static JsonNode unitGrid() throws IOException {
        Path path = Path.of("..", "..", "fixtures", "decimals", "unit-grid.json").toAbsolutePath().normalize();
        return JsonMapper.builder().build().readTree(Files.readString(path));
    }

    static List<Case> formatCases() throws IOException {
        return cases("format.json", "text");
    }

    static List<Case> parseCases() throws IOException {
        return cases("parse.json", "text");
    }

    static List<Case> refusedCases() throws IOException {
        return cases("refused.json", "reason");
    }

    private static List<Case> cases(String file, String label) throws IOException {
        Path path = Path.of("..", "..", "fixtures", "decimals", file).toAbsolutePath().normalize();
        JsonNode doc = JsonMapper.builder().build().readTree(Files.readString(path));
        List<Case> out = new ArrayList<>();
        for (JsonNode c : doc.get("cases")) {
            out.add(new Case(
                file + ": " + c.get(label).asString() + " @" + c.get("decimals").asInt(),
                c.has("value") ? c.get("value").asLong() : 0,
                c.get("decimals").asInt(),
                c.get("text").asString()));
        }
        return out;
    }
}
