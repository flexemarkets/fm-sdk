package fm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import fm.Types.Account;
import fm.Types.Allotment;
import fm.Types.ClientConnection;
import fm.Types.Holding;
import fm.Types.ManagerOtpBundle;
import fm.Types.Market;
import fm.Types.Marketplace;
import fm.Types.Order;
import fm.Types.Person;
import fm.Types.Session;
import fm.Types.Token;

/**
 * A connection to Flexemarkets.
 *
 * <p>An interface rather than a class, so that anything built on this SDK can
 * be tested without a server. That is not a hypothetical: the first consumer to
 * try — a trading robot moved onto this SDK — could compile against it and not
 * test against it, because {@code connect()} opens a real socket and there was
 * no seam to substitute. A published SDK that cannot be faked pushes every one
 * of its consumers towards integration tests they will not write.
 *
 * <p>Obtain one with {@link #connect}. The returned object owns a connection
 * and must be closed.
 *
 * <p><b>Returned collections are immutable.</b> Callers who need to modify one
 * should copy it. This holds for every implementation, including test fakes, so
 * a mutation bug surfaces in a unit test rather than against a live server.
 *
 * <p><b>A composition, not a list.</b> Everything this can do belongs to one of
 * {@link Identity}, {@link Reading}, {@link Writing}, {@link Management},
 * {@link Administration} or {@link Streaming}, and every one of those methods
 * is abstract. So holding this type means all of it works — which is what it
 * did not mean before, when half the interface was a default that threw and a
 * caller had no way to tell which half.
 *
 * <p>Declare the narrowest role that does the job. A settlement report takes
 * {@code Reading}; a study takes {@code Reading} and {@code Management}; only
 * something that genuinely needs everything takes {@code Flexemarkets}. The
 * signature then says what the code can do to a live marketplace, and a fake
 * only has to model the roles it is standing in for.
 */
public interface Flexemarkets
        extends Identity, Reading, Writing, Management, Administration, Streaming, AutoCloseable {

    /**
     * Connect and authenticate. The caller owns the result and must close it.
     *
     * <p>The endpoint is {@linkplain Endpoints#resolve resolved} first -- a
     * bare marketplace id or a file holding one becomes the endpoint it
     * denotes -- and a registered {@link FlexemarketsProvider} is then offered
     * the result. Resolving first is what lets a provider's endpoint be
     * supplied the same ways any other can, including from a file; asking
     * about the argument as typed meant only a literal could ever be claimed.
     *
     * <p>Nothing is registered by default and the fallback is HTTP, so ordinary
     * use is unchanged -- including when a provider is present but does not
     * recognise the endpoint.
     */
    static Flexemarkets connect(String credential, String endpoint, String clientDescription)
            throws IOException {
        String resolved = Endpoints.resolve(endpoint);

        FlexemarketsProvider provider = Providers.forEndpoint(resolved);
        if (null != provider) {
            return provider.connect(credential, resolved, clientDescription);
        }
        return new HttpFlexemarkets(
                HttpFlexemarkets.loadProperties(credential, endpoint, clientDescription));
    }

    /**
     * Connect, tracing the exchange and/or acting as another account.
     *
     * <p>Both are operator concerns rather than programming ones, which is why
     * they are a separate overload and not options on every call: {@code
     * capture} writes each request and response to stdout while a human works
     * out what the server actually said, and {@code impersonateAccount} makes
     * an administrator's calls answer for that account instead of their own.
     * The server refuses impersonation for anyone else, so this is a request,
     * not a grant.
     *
     * <p>A provider that claims the endpoint is handed the plain connect: these
     * two are properties of the HTTP exchange, and a provider that does not
     * speak HTTP has nothing to apply them to.
     */
    static Flexemarkets connect(String credential, String endpoint, String clientDescription,
                                boolean capture, String impersonateAccount) throws IOException {
        String resolved = Endpoints.resolve(endpoint);

        FlexemarketsProvider provider = Providers.forEndpoint(resolved);
        if (null != provider) {
            return provider.connect(credential, resolved, clientDescription);
        }

        var properties = HttpFlexemarkets.loadProperties(credential, endpoint, clientDescription);

        if (capture) {
            properties.setProperty("capture", "true");
        }
        if (null != impersonateAccount && !impersonateAccount.isBlank()) {
            properties.setProperty("impersonate-account", impersonateAccount);
        }

        return new HttpFlexemarkets(properties);
    }

    /** Releases the connection. Overridden to drop {@code AutoCloseable}'s checked exception. */
    @Override
    void close();
}
