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
 */
public interface Flexemarkets extends Identity, Reading, Writing, Management, AutoCloseable {

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

    // --- administration -----------------------------------------------------

    /*
     * Creating accounts and users, approving them, deleting them, and minting
     * one-time passcodes. This is fm-server's administrative surface, and it
     * is here because fm-robots' manager -- the tool that runs a course -- is
     * built on it and had no way off fm-lib-net otherwise.
     *
     * Several of these are destructive and one issues credentials. They need
     * an admin or manager and the server answers 401/403 otherwise, which is
     * the only guard: possessing the method is not possessing the right.
     */

    /**
     * Register a new account and its owner, returning the owner's token.
     *
     * <p>Account names are unique. A clash raises {@link
     * fm.Exceptions.ConflictException}, whose {@code failure().suggestedName()}
     * carries the server's proposed alternative -- worth surfacing rather than
     * retrying blindly, since the suggestion is what the user will be known as.
     */
    default Token signup(String accountName, String email, String password) {
        throw unsupported("signup");
    }

    default Token signup(String accountName, String email, String password,
                         String firstName, String lastName) {
        throw unsupported("signup");
    }

    /** Approve an account by name, returning it as it now stands. */
    default Account approveAccount(String accountName) {
        throw unsupported("approveAccount");
    }

    /** One account by id. */
    default Account account(long accountId) {
        throw unsupported("account(accountId)");
    }

    /** One user by id. */
    default Person user(long userId) {
        throw unsupported("user(userId)");
    }

    /** The marketplace's private-trader identifiers. */
    default List<String> identifiers(long marketplaceId) {
        throw unsupported("identifiers");
    }

    /** Delete the caller's own account. Destructive, and not undoable. */
    default void deleteMyAccount() {
        throw unsupported("deleteMyAccount");
    }

    /** Every account on the server. Admin-only. */
    default List<Account> accounts() {
        throw unsupported("accounts");
    }

    /** Delete an account. Destructive, and takes its users with it. */
    default void deleteAccount(long accountId) {
        throw unsupported("deleteAccount");
    }

    /** Create a user in the caller's account. Roles are optional. */
    default Person createUser(String email, String password, String firstName,
                              String lastName, String... roles) {
        throw unsupported("createUser");
    }

    /** Delete a user. Destructive. */
    default void deleteUser(long userId) {
        throw unsupported("deleteUser");
    }

    /** Create an empty marketplace. See also {@link #createMarketplaceFromJson}. */
    default Marketplace createMarketplace(String name, String description) {
        throw unsupported("createMarketplace");
    }

    /** Delete a marketplace, and with it its sessions and their history. */
    default void deleteMarketplace(long marketplaceId) {
        throw unsupported("deleteMarketplace");
    }

    /**
     * Add a market to a marketplace.
     *
     * <p>Unit bounds are not parameters: they are fixed at 1/100/1, matching
     * fm-lib-net's call. A study that needs other bounds builds its
     * marketplace from JSON, where every field is stated.
     */
    default Market createMarket(long marketplaceId, String symbol, String name,
                                long priceMinimum, long priceMaximum, long priceTick,
                                boolean privateMarket) {
        throw unsupported("createMarket");
    }

    /**
     * Mint one-time passcodes for the given users.
     *
     * <p>These are credentials. They are how a classroom signs in without
     * passwords being handed around, and they should be treated like
     * passwords: not logged, not persisted, and delivered to the person they
     * belong to.
     */
    default ManagerOtpBundle managerOtpBundle(List<Long> userIds) {
        throw unsupported("managerOtpBundle");
    }

    private UnsupportedOperationException unsupported(String operation) {
        return new UnsupportedOperationException(
                getClass().getName() + " does not support " + operation + "(...)");
    }

    // --- events -------------------------------------------------------------

    /**
     * Subscribe to the marketplace's event stream, delivering onto {@code queue}.
     *
     * <p>The queue is the caller's: its capacity is the caller's back-pressure
     * policy.
     */
    void listen(long marketplaceId, BlockingQueue<Object> queue);

    /**
     * Open an <em>independent</em> event subscription, delivering onto
     * {@code queue} until the returned {@link Subscription} is closed.
     *
     * <p>Unlike {@link #listen}, several of these coexist: each has its own
     * stream and its own lifetime. That is what lets more than one
     * {@link MarketView} live in one connection without trampling each other.
     *
     * <p>Defaulted rather than abstract so that existing implementations --
     * test fakes especially -- keep compiling. An implementation that cannot
     * multiplex says so here instead of pretending, and a {@link MarketView}
     * over it will fail loudly rather than silently sharing one stream.
     */
    default Subscription subscribe(long marketplaceId, BlockingQueue<Object> queue) {
        throw new UnsupportedOperationException(
                getClass().getName() + " does not support independent subscriptions");
    }

    /** A maintained view of the order books, kept current from the event stream. */
    MarketView observe(long marketplaceId);

    void reconnect() throws InterruptedException;

    /** Releases the connection. Overridden to drop {@code AutoCloseable}'s checked exception. */
    @Override
    void close();
}
