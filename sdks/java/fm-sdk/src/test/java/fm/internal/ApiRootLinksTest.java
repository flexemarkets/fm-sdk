package fm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;


/**
 * The API root's link envelope is named {@code links} in Java and {@code _links}
 * on the wire.
 *
 * <p>The server speaks HAL, so the underscore is not ours to drop — but it is
 * also not a name a Java accessor should carry, and the Python and TypeScript
 * SDKs have always exposed it as {@code links}. The rename is therefore a
 * binding question, and these tests are the binding: a record component renamed
 * without {@code @JsonProperty} would still compile, still pass any test that
 * built an ApiRoot by hand, and silently return no links at all against a real
 * server.
 *
 * <p>The payload below is the live response from
 * {@code GET https://api.flexemarkets.com/api}, trimmed, including the
 * {@code templated} sibling that HAL adds and this SDK ignores.
 */
class ApiRootLinksTest {

    private static final String WIRE = """
        {
          "_links": {
            "orders": {
              "href": "https://api.flexemarkets.com/api/orders{?page,size,sort*}",
              "templated": true
            },
            "marketplaces": {
              "href": "https://api.flexemarkets.com/api/marketplaces{?page,size,sort*}",
              "templated": true
            }
          }
        }
        """;

    // Jackson 3 mappers are built, not constructed and configured.
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void readsTheWiresUnderscoreIntoTheLinksComponent() throws Exception {
        ApiRoot root = mapper.readValue(WIRE, ApiRoot.class);

        assertThat(root.links()).containsKeys("orders", "marketplaces");
        assertThat(root.links().get("orders").href())
                .isEqualTo("https://api.flexemarkets.com/api/orders{?page,size,sort*}");
    }

    @Test
    void getLinkResolvesThroughToTheHref() throws Exception {
        ApiRoot root = mapper.readValue(WIRE, ApiRoot.class);

        assertThat(root.getLink("marketplaces"))
                .contains("https://api.flexemarkets.com/api/marketplaces{?page,size,sort*}");
        assertThat(root.getLink("nonesuch")).isEmpty();
    }

    /**
     * The failure this guards. Drop the {@code @JsonProperty} and Jackson looks
     * for a {@code links} key the server never sends: no exception, an empty
     * map, and every subsequent request unable to find its endpoint.
     */
    @Test
    void aPayloadWithoutTheUnderscoreYieldsNoLinks() throws Exception {
        ApiRoot root = mapper.readValue(
                "{\"links\": {\"orders\": {\"href\": \"https://example.com\"}}}", ApiRoot.class);

        assertThat(root.links()).isNullOrEmpty();
    }

    /** Writing must produce what the server produces, not the Java name. */
    @Test
    void writesTheUnderscoreBackOut() throws Exception {
        ApiRoot root = mapper.readValue(WIRE, ApiRoot.class);

        String json = mapper.writeValueAsString(root);

        assertThat(json).contains("\"_links\"");
        assertThat(json).doesNotContain("\"links\"");
    }

    @Test
    void toleratesAnAbsentEnvelope() throws Exception {
        ApiRoot root = mapper.readValue("{}", ApiRoot.class);

        assertThat(root.getLink("orders")).isEmpty();
    }
}
