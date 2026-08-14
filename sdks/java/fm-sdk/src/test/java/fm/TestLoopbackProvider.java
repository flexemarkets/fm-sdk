package fm;

import java.lang.reflect.Proxy;

/**
 * A provider registered for real, through {@code META-INF/services}, so the
 * {@code ServiceLoader} wiring is exercised rather than assumed.
 *
 * <p>Claims only {@code loopback:} endpoints. Every other test in this module
 * connects over http and must be undisturbed by this, which is itself the
 * behaviour worth proving: a host's provider has to be invisible to anything it
 * does not serve.
 *
 * <p>The connection it hands back is a {@link Proxy} rather than a written-out
 * class. What is under test is that <em>a</em> provider's object comes back
 * instead of an HTTP one; implementing two dozen methods to say so would only
 * create something to keep in step with the interface.
 */
public final class TestLoopbackProvider implements FlexemarketsProvider {

    /** Reported as the account name, so a test can identify what it got. */
    static final String DESCRIPTION = "loopback-test-double";

    static final String SCHEME = "loopback:";

    @Override
    public boolean handles(String endpoint) {
        return null != endpoint && endpoint.startsWith(SCHEME);
    }

    @Override
    public Flexemarkets connect(String credential, String endpoint, String clientDescription) {
        return (Flexemarkets) Proxy.newProxyInstance(
                TestLoopbackProvider.class.getClassLoader(),
                new Class<?>[] { Flexemarkets.class },
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "accountName" -> DESCRIPTION;
                    case "endpointUrl" -> endpoint;
                    case "endpointMarketplaceId" -> Long.parseLong(endpoint.substring(SCHEME.length()));
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
