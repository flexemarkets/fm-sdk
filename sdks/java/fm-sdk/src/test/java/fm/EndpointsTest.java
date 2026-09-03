package fm;

import fm.internal.Endpoints;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forms an endpoint arrives in.
 *
 * <p>This class exists because of a defect: provider selection used to run
 * against the argument as typed, so an endpoint supplied through a file could
 * never be claimed. It fell through to HTTP, which read the file, found an
 * endpoint it could not dial, and failed a long way from the cause. The
 * file-form tests below are that defect.
 */
class EndpointsTest {

    @TempDir
    Path directory;

    // --- resolution ------------------------------------------------------------

    @Test
    void aBareMarketplaceIdBecomesThatMarketplacesUrl() throws IOException {
        assertThat(Endpoints.resolve("1744")).endsWith("/api/marketplaces/1744");
    }

    /**
     * A defect, reported 2026-08-20: {@code endpoint=1234} in {@code ~/.fm/endpoint}
     * does not work, though {@code 1234} passed as the argument does.
     *
     * <p>A bare id is expanded only when it arrives as the argument. Arriving
     * from a file it is returned verbatim, because the file branch returns what
     * it read without resolving it again — so the rest of the SDK is handed
     * "1234" where it expects a URL.
     */
    @Test
    void aFileHoldingABareMarketplaceIdResolvesLikeTheIdItself() throws IOException {
        Path file = directory.resolve("endpoint");
        Files.writeString(file, "endpoint=1744\n");

        assertThat(Endpoints.resolve(file.toString())).isEqualTo(Endpoints.resolve("1744"));
    }

    /**
     * The second half of that defect. {@code marketplaceUrl} defaults to
     * {@code https://adhocmarkets.com}, which is the website: it answers 200
     * with 63KB of HTML for {@code /api/marketplaces/1744}. The API is on
     * {@code api.}, and the rest of the SDK already knows that — see
     * {@link FlexemarketsEndpointTest#bareIdResolvesToDefaultProductionHost}.
     * Two defaults for one idea, and this is the one that cannot work.
     */
    @Test
    void aBareMarketplaceIdResolvesOntoTheApiHostNotTheWebsite() throws IOException {
        assertThat(Endpoints.resolve("1744"))
                .isEqualTo("https://api.flexemarkets.com/api/marketplaces/1744");
    }

    @Test
    void aUrlIsAlreadyResolved() throws IOException {
        String url = "http://localhost:8080/api/marketplaces/1744";

        assertThat(Endpoints.resolve(url)).isEqualTo(url);
    }

    @Test
    void aFileYieldsTheEndpointItHolds() throws IOException {
        Path file = directory.resolve("endpoint");
        Files.writeString(file, "endpoint=http://localhost:8080/api/marketplaces/1744\n");

        assertThat(Endpoints.resolve(file.toString()))
                .isEqualTo("http://localhost:8080/api/marketplaces/1744");
    }

    /**
     * The defect, directly. A host's endpoint must be suppliable the same ways
     * any other is — including from the file robots are routinely pointed at.
     */
    @Test
    void aFileCanHoldAProvidersEndpointForm() throws IOException {
        Path file = directory.resolve("endpoint");
        Files.writeString(file, "endpoint=loopback:1744\n");

        assertThat(Endpoints.resolve(file.toString())).isEqualTo("loopback:1744");
    }

    /**
     * Untouched rather than rejected: a form this class does not recognise may
     * be one a provider does, and refusing here would settle that before the
     * providers are asked.
     */
    @Test
    void anUnrecognisedFormIsPassedThroughForAProviderToClaim() throws IOException {
        assertThat(Endpoints.resolve("loopback:1744")).isEqualTo("loopback:1744");
        assertThat(Endpoints.resolve("  loopback:1744  ")).isEqualTo("loopback:1744");
    }

    @Test
    void nothingResolvesToNothing() throws IOException {
        assertThat(Endpoints.resolve(null)).isNull();
        assertThat(Endpoints.resolve("")).isEmpty();
    }

    @Test
    void aFileWithNoEndpointInItIsLeftAlone() throws IOException {
        Path file = directory.resolve("endpoint");
        Files.writeString(file, "credential=something\n");

        assertThat(Endpoints.resolve(file.toString())).isEqualTo(file.toString());
    }

    // --- the marketplace an endpoint names ---------------------------------------

    @Test
    void theMarketplaceIsReadFromEveryForm() {
        assertThat(Endpoints.marketplaceId("1744")).isEqualTo(1744L);
        assertThat(Endpoints.marketplaceId("loopback:1744")).isEqualTo(1744L);
        assertThat(Endpoints.marketplaceId("http://localhost:8080/api/marketplaces/1744")).isEqualTo(1744L);
        assertThat(Endpoints.marketplaceId("https://adhocmarkets.com/api/marketplaces/1744/")).isEqualTo(1744L);
    }

    /**
     * Refused rather than defaulted. A connection pointed at no particular
     * marketplace would attach to whichever came first, and trade in it.
     */
    @Test
    void anEndpointNamingNoMarketplaceIsRefused() {
        assertThatThrownBy(() -> Endpoints.marketplaceId("loopback:"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names its marketplace");
        assertThatThrownBy(() -> Endpoints.marketplaceId("http://localhost:8080/api"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Endpoints.marketplaceId(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
