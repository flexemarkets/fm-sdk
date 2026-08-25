package fm.internal;

import fm.Flexemarkets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

/**
 * Endpoint resolution is pure (no network): a bare marketplace id expands to the
 * default production host, a full URL is preserved, anything else is rejected.
 *
 * <p>Calls the implementation directly. Resolution is an implementation detail,
 * not part of the {@link Flexemarkets} contract — a fake has no endpoint to
 * resolve — so it stayed on {@link HttpFlexemarkets} when the interface was
 * extracted.
 */
public class FlexemarketsEndpointTest {

    @Test
    public void bareIdResolvesToDefaultProductionHost() throws Exception {
        var properties = HttpFlexemarkets.loadProperties(null, "2540", "fm-endpoint-test");

        assertThat(properties.getProperty("endpoint"))
            .isEqualTo("https://api.flexemarkets.com/api/marketplaces/2540");
    }

    @Test
    public void fullUrlIsPreserved() throws Exception {
        var url = "http://localhost:8080/api/marketplaces/2540";
        var properties = HttpFlexemarkets.loadProperties(null, url, "fm-endpoint-test");

        assertThat(properties.getProperty("endpoint")).isEqualTo(url);
    }

    /**
     * The same defect on the path a robot actually takes. {@code -E} is usually
     * a file, and {@code ~/.fm/endpoint} is read on every connection whether one
     * is named or not. A file's contents are loaded verbatim, so a bare id in a
     * file never reaches {@code marketplaceEndpoint} — the id expands when typed
     * as the argument and not when written in the file, which is the reported
     * asymmetry.
     */
    @Test
    public void aFileHoldingABareMarketplaceIdExpandsToThatMarketplacesUrl(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("endpoint");
        Files.writeString(file, "endpoint=2540\n");

        var properties = HttpFlexemarkets.loadProperties(null, file.toString(), "fm-endpoint-test");

        assertThat(properties.getProperty("endpoint"))
            .isEqualTo("https://api.flexemarkets.com/api/marketplaces/2540");
    }

    /**
     * The API root, from the cases all three SDKs run.
     *
     * <p>Shared rather than written out here, because this is derived by hand
     * in Java, Python and TypeScript and nothing else holds the three
     * together. check-parity.py compares wire fields and method surfaces, and
     * this is neither -- {@code server} is private in all three, so the
     * divergence that shipped in 0.1.1 (fixed in Java, untouched in the other
     * two) passed every check there was.
     *
     * <p>See {@code sdks/fixtures/endpoints/README.md}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("endpointFixtures")
    public void theApiRootMatchesTheSharedFixtures(String name, String endpoint, String apiRoot) {
        assertThat(HttpFlexemarkets.server(endpoint))
            .as("%s: %s", name, endpoint)
            .isEqualTo(apiRoot);
    }

    /** Guard the guard: a bad path would report everything passing. */
    @Test
    public void thereAreEndpointFixturesToRun() throws Exception {
        assertThat(endpointFixtures().toList()).hasSizeGreaterThanOrEqualTo(6);
    }

    static Stream<Arguments> endpointFixtures() throws IOException {
        var directory = Path.of("..", "..", "fixtures", "endpoints").toAbsolutePath().normalize();

        try (var files = Files.list(directory)) {
            var out = new ArrayList<Arguments>();
            for (var path : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                var doc = HttpFlexemarkets.MAPPER.readTree(Files.readString(path));
                out.add(Arguments.of(path.getFileName().toString().replace(".json", ""),
                                     doc.get("endpoint").asString(),
                                     doc.get("apiRoot").asString()));
            }
            return out.stream();
        }
    }

    @Test
    public void nonIdNonFileNonUrlIsRejected() {
        assertThatThrownBy(() -> HttpFlexemarkets.loadProperties(null, "not a valid endpoint", "fm-endpoint-test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("marketplace id, file, or URL");
    }
}
