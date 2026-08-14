package fm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wiring, end to end.
 *
 * <p>{@link ProvidersTest} checks the selection rule against hand-made lists.
 * This checks the part that has to actually work: a provider declared in
 * {@code META-INF/services/fm.FlexemarketsProvider} is found by
 * {@link Flexemarkets#connect} and used instead of opening a socket.
 *
 * <p>That distinction has teeth. A provider interface with a correct selection
 * rule and a missing or misspelled services file is invisible, and the only
 * symptom is that every connection quietly goes over HTTP — which is exactly
 * what it would do if the feature did not exist.
 */
class ProviderDiscoveryTest {

    @TempDir
    Path directory;

    /**
     * The defect that prompted the endpoint-resolution rework, end to end: a
     * provider's endpoint supplied the way robots are routinely pointed at one.
     * Before resolution moved ahead of selection this reached HttpFlexemarkets,
     * which read the file and tried to dial an endpoint it could not.
     */
    @Test
    void aProviderEndpointCanArriveInAFile() throws IOException {
        Path file = directory.resolve("endpoint");
        Files.writeString(file, "endpoint=loopback:1744\n");

        try (Flexemarkets fm = Flexemarkets.connect("credential", file.toString(), "test")) {
            assertThat(fm.accountName()).isEqualTo(TestLoopbackProvider.DESCRIPTION);
            assertThat(fm.endpointMarketplaceId()).isEqualTo(1744L);
        }
    }

    @Test
    void aRegisteredProviderServesItsOwnEndpointForm() throws IOException {
        try (Flexemarkets fm = Flexemarkets.connect("credential", "loopback:1744", "test")) {
            assertThat(fm.accountName()).isEqualTo(TestLoopbackProvider.DESCRIPTION);
            assertThat(fm.endpointMarketplaceId()).isEqualTo(1744L);
        }
    }

    /**
     * The endpoint is passed through untouched. A host encodes what it needs in
     * its own form, and the SDK must not parse or normalise it on the way.
     */
    @Test
    void theEndpointReachesTheProviderVerbatim() throws IOException {
        try (Flexemarkets fm = Flexemarkets.connect("credential", "loopback:99", "test")) {
            assertThat(fm.endpointUrl()).isEqualTo("loopback:99");
        }
    }

    /**
     * The registered provider does not claim http, so this takes the ordinary
     * path — and fails trying to reach a server that is not there, rather than
     * being quietly served by the provider. A provider that swallowed unrelated
     * endpoints would be far worse than one that does not exist.
     */
    @Test
    void anHttpEndpointStillGoesOverHttpEvenWithAProviderPresent() {
        assertThatThrownBy(() ->
                Flexemarkets.connect("credential", "http://127.0.0.1:1/api/marketplaces/1", "test"))
                .isInstanceOfAny(IOException.class, RuntimeException.class);
    }
}
