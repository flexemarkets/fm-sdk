package fm.internal;

import fm.Flexemarkets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
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
     * The API root, given any endpoint beneath it -- and given one that names
     * no marketplace at all.
     *
     * <p>The last two cases are the reason this exists. An endpoint may name
     * only a server: DEFAULT_HOST is one, FM_API_URL is often one, and
     * {@code -E https://api.adhocmarkets.com} is the natural way to say "that
     * server". Handing such an endpoint back unchanged made every URL built
     * from it a segment short -- sign-in POSTed to {@code <host>/tokens} and
     * collected a 404 -- so a machine with nothing configured could run no
     * command at all.
     */
    @Test
    public void theApiRootIsFoundOrAppended() {
        assertThat(HttpFlexemarkets.server("https://api.flexemarkets.com/api/marketplaces/2540"))
            .isEqualTo("https://api.flexemarkets.com/api");
        assertThat(HttpFlexemarkets.server("http://localhost:8080/api/v1/marketplaces/7"))
            .isEqualTo("http://localhost:8080/api");

        // The host itself starts with "api"; the search must not match there.
        assertThat(HttpFlexemarkets.server("https://api.flexemarkets.com"))
            .isEqualTo("https://api.flexemarkets.com/api");
        assertThat(HttpFlexemarkets.server("http://localhost:8080"))
            .isEqualTo("http://localhost:8080/api");
        assertThat(HttpFlexemarkets.server("http://localhost:8080/"))
            .isEqualTo("http://localhost:8080/api");
    }

    @Test
    public void nonIdNonFileNonUrlIsRejected() {
        assertThatThrownBy(() -> HttpFlexemarkets.loadProperties(null, "not a valid endpoint", "fm-endpoint-test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("marketplace id, file, or URL");
    }
}
