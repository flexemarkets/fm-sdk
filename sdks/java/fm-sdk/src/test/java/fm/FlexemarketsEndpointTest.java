package fm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Endpoint resolution is pure (no network): a bare marketplace id expands to the
 * default production host, a full URL is preserved, anything else is rejected.
 */
public class FlexemarketsEndpointTest {

    @Test
    public void bareIdResolvesToDefaultProductionHost() throws Exception {
        var properties = Flexemarkets.loadProperties(null, "2540", "fm-endpoint-test");

        assertThat(properties.getProperty("endpoint"))
            .isEqualTo("https://api.flexemarkets.com/api/marketplaces/2540");
    }

    @Test
    public void fullUrlIsPreserved() throws Exception {
        var url = "http://localhost:8080/api/marketplaces/2540";
        var properties = Flexemarkets.loadProperties(null, url, "fm-endpoint-test");

        assertThat(properties.getProperty("endpoint")).isEqualTo(url);
    }

    @Test
    public void nonIdNonFileNonUrlIsRejected() {
        assertThatThrownBy(() -> Flexemarkets.loadProperties(null, "not a valid endpoint", "fm-endpoint-test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("marketplace id, file, or URL");
    }
}
