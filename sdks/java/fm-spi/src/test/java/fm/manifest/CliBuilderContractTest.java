package fm.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The rules a host and a robot must agree on, exercised without a parser.
 *
 * <p>This artifact carries the manifest and the builder precisely so both sides
 * derive arguments the same way. What that means concretely is here: which
 * owner may supply which parameter, and the order the arguments come out in.
 *
 * <p>Fixtures are constructed rather than parsed, because reading JSON needs a
 * databind implementation and this artifact deliberately depends on annotations
 * alone. The JSON path is covered where the parser lives — fm-robots'
 * {@code CliBuilderTest} drives these same rules through {@code ManifestLoader}
 * against real manifest text, and fm-server parses the shipped manifests in
 * {@code RobotBundleTest}. Between them the wire shape is not taken on trust.
 */
class CliBuilderContractTest {

    private static ParameterSpec option(String name, String cli, String defaultValue) {
        return new ParameterSpec(name, cli, ParameterType.OPTION, ValueType.INTEGER,
                null, null, false, defaultValue, null, null, null, null);
    }

    private static ParameterSpec positional(String name, boolean required) {
        return new ParameterSpec(name, name.toUpperCase(java.util.Locale.ROOT), ParameterType.POSITIONAL,
                ValueType.FLOAT, null, null, required, null, null, null, null, null);
    }

    private static ParameterSpec pairs(String name) {
        return new ParameterSpec(name, "(SYMBOL SPREAD)...", ParameterType.POSITIONAL_PAIRS,
                null, null, null, true, null, null, null, null, null);
    }

    private static ParameterSpec platform(String name, String cli, String source) {
        return new ParameterSpec(name, cli, ParameterType.OPTION, ValueType.STRING,
                null, null, true, null, null, source, null, null);
    }

    private static Manifest manifest() {
        return new Manifest("fm-maker-mvo", "MVO Market Maker", "0.9.0", null, null, "jar",
                new Manifest.Parameters(
                        List.of(positional("penalty", true), option("interval", "-i", "2000")),
                        List.of(pairs("symbolSpreads")),
                        List.of(platform("credential", "-C", "session-token"))));
    }

    // --- ownership --------------------------------------------------------------

    /**
     * The reason this class is in the contract artifact. A host that decided for
     * itself who may set what would be a second implementation of the rule,
     * differing from the first only where it was wrong.
     */
    @Test
    void aParticipantCannotSupplyAManagerParameter() {
        assertThatThrownBy(() -> CliBuilder.merge(manifest(),
                Map.of(), Map.of("interval", "1"), Map.of()))
                .isInstanceOf(CliBuilder.InvalidLaunchException.class);
    }

    @Test
    void nobodyButThePlatformSuppliesAPlatformParameter() {
        assertThatThrownBy(() -> CliBuilder.merge(manifest(),
                Map.of("credential", "stolen"), Map.of(), Map.of()))
                .isInstanceOf(CliBuilder.InvalidLaunchException.class);
    }

    @Test
    void eachOwnerSuppliesItsOwnAndTheyCombine() {
        Map<String, String> merged = CliBuilder.merge(manifest(),
                Map.of("penalty", "0.03"),
                Map.of("symbolSpreads", "AAPL 0.5"),
                Map.of("credential", "a-token"));

        assertThat(merged)
                .containsEntry("penalty", "0.03")
                .containsEntry("symbolSpreads", "AAPL 0.5")
                .containsEntry("credential", "a-token");
    }

    /** A declared default applies when nobody supplies a value. */
    @Test
    void aDeclaredDefaultFillsIn() {
        assertThat(CliBuilder.merge(manifest(),
                Map.of("penalty", "0.03"), Map.of("symbolSpreads", "AAPL 0.5"),
                Map.of("credential", "a-token")))
                .containsEntry("interval", "2000");
    }

    // --- ordering ----------------------------------------------------------------

    /**
     * Options, then positionals, then repeating groups. The groups must come
     * last: a trailing {@code (SYMBOL SPREAD)...} swallows whatever follows it,
     * so a different order silently feeds a robot the wrong arguments rather
     * than failing.
     */
    @Test
    void argumentsComeOutOptionsThenPositionalsThenGroups() {
        List<String> arguments = CliBuilder.arguments(manifest(),
                Map.of("penalty", "0.03", "interval", "5000"),
                Map.of("symbolSpreads", "AAPL 0.5 MSFT 0.25"),
                Map.of("credential", "a-token"));

        assertThat(arguments.subList(arguments.size() - 4, arguments.size()))
                .containsExactly("AAPL", "0.5", "MSFT", "0.25");
        assertThat(arguments).containsSequence("-i", "5000");
        assertThat(arguments.indexOf("0.03"))
                .as("the positional precedes the groups")
                .isLessThan(arguments.indexOf("AAPL"));
    }

    @Test
    void platformValuesAreSpeltAsTheManifestDeclares() {
        assertThat(CliBuilder.arguments(manifest(),
                Map.of("penalty", "0.03"), Map.of("symbolSpreads", "AAPL 0.5"),
                Map.of("credential", "a-token")))
                .containsSequence("-C", "a-token");
    }

    /** Half a pair is a typo, not an argument list. */
    @Test
    void anIncompleteGroupIsRefused() {
        assertThatThrownBy(() -> CliBuilder.arguments(manifest(),
                Map.of("penalty", "0.03"), Map.of("symbolSpreads", "AAPL 0.5 MSFT"),
                Map.of("credential", "a-token")))
                .isInstanceOf(CliBuilder.InvalidLaunchException.class)
                .hasMessageContaining("groups of 2");
    }

    @Test
    void aRequiredParameterWithNoValueIsRefused() {
        assertThatThrownBy(() -> CliBuilder.arguments(manifest(),
                Map.of(), Map.of("symbolSpreads", "AAPL 0.5"),
                Map.of("credential", "a-token")))
                .isInstanceOf(CliBuilder.InvalidLaunchException.class)
                .hasMessageContaining("penalty");
    }
}
