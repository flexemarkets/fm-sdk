package fm;

import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Finds the {@link FlexemarketsProvider}s on the classpath, if any.
 *
 * <p>Package-private: providers are discovered, never asked for. A caller of
 * {@link Flexemarkets#connect} should not have to know whether one exists.
 */
final class Providers {

    private Providers() {
    }

    /**
     * Loaded once.
     *
     * <p>{@code connect()} may be called per robot, and a fleet of them starting
     * at once should not each walk the classpath. Providers are a deployment
     * fact: a jar that was not on the classpath at first call will not appear
     * later.
     */
    private static final class Holder {
        private static final List<FlexemarketsProvider> PROVIDERS = load();

        private static List<FlexemarketsProvider> load() {
            try {
                return ServiceLoader.load(FlexemarketsProvider.class).stream()
                        .map(ServiceLoader.Provider::get)
                        .toList();
            } catch (ServiceConfigurationError e) {
                // A broken provider declaration must not stop an ordinary HTTP
                // connection. The SDK's own path does not depend on any of this.
                return List.of();
            }
        }
    }

    /**
     * The first provider claiming {@code endpoint}, or null for none.
     *
     * <p>First rather than only: a host may register more than one form. A
     * provider that throws while being asked is treated as not claiming it,
     * because a provider unrelated to this endpoint must not be able to break a
     * connection it was never going to serve.
     */
    static FlexemarketsProvider forEndpoint(String endpoint) {
        return select(Holder.PROVIDERS, endpoint);
    }

    /**
     * The rule itself, over a given list.
     *
     * <p>Separate from {@link #forEndpoint} so it can be exercised against
     * providers a test constructs. The cached list makes the public entry
     * awkward to drive, and a test that reimplemented this loop would be
     * asserting against its own copy -- which stays green while the real one
     * drifts.
     */
    static FlexemarketsProvider select(List<FlexemarketsProvider> providers, String endpoint) {
        if (null == endpoint || null == providers) {
            return null;
        }
        for (FlexemarketsProvider provider : providers) {
            try {
                if (provider.handles(endpoint)) {
                    return provider;
                }
            } catch (RuntimeException ignored) {
                // Next provider; the HTTP fallback remains.
            }
        }
        return null;
    }
}
