package fm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Choosing between a registered provider and this SDK's own HTTP client.
 *
 * <p>Drives {@link Providers#select} directly, over lists these tests build.
 * The cached entry point is awkward to steer, but a test that reimplemented the
 * loop would be asserting against its own copy — green while the real rule
 * drifted. Selection is tested here rather than through
 * {@link Flexemarkets#connect} because the fallback half of that opens a real
 * socket.
 */
class ProvidersTest {

    private static final String LOOPBACK = "loopback:1744";
    private static final String HTTP = "http://localhost:8080/api/marketplaces/1744";

    /** A provider that claims one scheme, as a real one should. */
    private static final class Loopback implements FlexemarketsProvider {
        @Override
        public boolean handles(String endpoint) {
            return null != endpoint && endpoint.startsWith("loopback:");
        }

        @Override
        public Flexemarkets connect(String credential, String endpoint, String description) {
            throw new UnsupportedOperationException("not called by these tests");
        }
    }

    private static final class Broken implements FlexemarketsProvider {
        @Override
        public boolean handles(String endpoint) {
            throw new IllegalStateException("misconfigured");
        }

        @Override
        public Flexemarkets connect(String credential, String endpoint, String description) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void aProviderIsChosenForAnEndpointItClaims() {
        assertThat(Providers.select(java.util.List.of(new Loopback()), LOOPBACK)).isInstanceOf(Loopback.class);
    }

    /** The ordinary case: a provider is present but this is not its endpoint. */
    @Test
    void anHttpEndpointFallsThroughToTheSdksOwnClient() {
        assertThat(Providers.select(java.util.List.of(new Loopback()), HTTP)).isNull();
    }

    @Test
    void withNoProvidersEverythingFallsThrough() {
        assertThat(Providers.select(java.util.List.of(), LOOPBACK)).isNull();
        assertThat(Providers.select(java.util.List.of(), HTTP)).isNull();
    }

    /**
     * A provider that throws while being asked must not be able to break a
     * connection it was never going to serve.
     */
    @Test
    void aProviderThatThrowsIsSkippedRatherThanPropagated() {
        assertThat(Providers.select(java.util.List.of(new Broken()), HTTP)).isNull();
        assertThat(Providers.select(java.util.List.of(new Broken(), new Loopback()), LOOPBACK))
                .isInstanceOf(Loopback.class);
    }

    @Test
    void aNullEndpointClaimsNothing() {
        assertThat(Providers.select(java.util.List.of(new Loopback()), null)).isNull();
    }

    /**
     * Against the provider this module really registers.
     *
     * <p>The http case is the one that matters: a provider on the classpath
     * must leave endpoints it does not serve alone, or adding one to a
     * deployment would silently change every connection in it.
     */
    @Test
    void theRegisteredProviderClaimsItsOwnFormAndNothingElse() {
        assertThat(Providers.forEndpoint(LOOPBACK)).isInstanceOf(TestLoopbackProvider.class);
        assertThat(Providers.forEndpoint(HTTP)).isNull();
        assertThat(Providers.forEndpoint("ws://localhost:8080")).isNull();
    }

    /**
     * The interface is usable as a lambda-free minimal implementation, which is
     * what a host will actually write. Compiling this is the assertion.
     */
    @Test
    void aHostCanImplementTheContract() throws IOException {
        FlexemarketsProvider provider = new FlexemarketsProvider() {
            @Override
            public boolean handles(String endpoint) {
                return LOOPBACK.equals(endpoint);
            }

            @Override
            public Flexemarkets connect(String credential, String endpoint, String description) {
                return null;
            }
        };

        assertThat(provider.handles(LOOPBACK)).isTrue();
        assertThat(provider.connect("c", LOOPBACK, "d")).isNull();
    }
}
