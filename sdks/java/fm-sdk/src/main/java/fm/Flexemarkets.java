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
public interface Flexemarkets extends AutoCloseable {

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

    // --- identity -----------------------------------------------------------

    Account account();

    long accountId();

    String accountName();

    Person user();

    long userId();

    /**
     * The token this connection signed in with.
     *
     * <p>Exposed so a caller can mint a sibling connection on the same
     * identity without holding the password again -- {@code connect(token
     * .token(), ...)} takes it directly.
     */
    default Token token() {
        throw unsupported("token");
    }

    /**
     * Whether this connection's user holds {@code role}, spelled as the server
     * spells it — {@code "ROLE_ADMIN"}, {@code "ROLE_MANAGER"}, {@code
     * "ROLE_USER"}.
     *
     * <p>The general form of {@link #isAdmin} and {@link #isManager}, which are
     * named because they are the two a caller asks about. Anything the server
     * grows later is reachable through this without another method.
     */
    default boolean hasRole(String role) {
        var person = user();
        if (null == person || null == person.roles()) {
            return false;
        }
        for (var held : person.roles()) {
            if (null != role && role.equals(held)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this connection's user holds ROLE_ADMIN. */
    default boolean isAdmin() {
        return hasRole("ROLE_ADMIN");
    }

    /**
     * Whether this connection's user holds ROLE_MANAGER.
     *
     * <p>The role that runs a study — opening and closing sessions, staging
     * allocations, minting passcodes. Python has had this since the management
     * surface landed.
     */
    default boolean isManager() {
        return hasRole("ROLE_MANAGER");
    }

    /** The marketplace this connection was pointed at, from its endpoint. */
    long endpointMarketplaceId();

    String endpointUrl();

    // --- reading ------------------------------------------------------------

    List<Marketplace> marketplaces();

    Marketplace marketplace(long marketplaceId);

    List<Market> markets(long marketplaceId);

    /** The marketplace's market symbols. */
    default List<String> symbols(long marketplaceId) {
        throw unsupported("symbols");
    }

    List<Session> sessions(long marketplaceId);

    /** Particular sessions by id, rather than the marketplace's whole history. */
    default List<Session> sessions(long marketplaceId, List<Long> sessionIds) {
        throw unsupported("sessions(marketplaceId, sessionIds)");
    }

    /** The current session, or null when the marketplace has never opened one. */
    Session session(long marketplaceId);

    List<Order> orders(long marketplaceId);

    /**
     * Orders from particular sessions, rather than the current one.
     *
     * <p>Answered by a different route from {@link #orders(long)} -- the
     * current-session collection cannot be filtered -- so a study reading a
     * finished run's orders needs this one. Both Python and TypeScript have
     * had it since the management surface landed; Java had not.
     */
    default List<Order> orders(long marketplaceId, List<Long> sessionIds) {
        throw unsupported("orders(marketplaceId, sessionIds)");
    }

    /**
     * Orders in one market, by symbol.
     *
     * <p>The symbol-keyed route answers without the symbol on each order,
     * because the query already fixed it; it is filled in before returning.
     * Unlike {@link #trades(long, String)} the ids are left alone -- an order
     * has its own id, and only a trade carries it in {@code original}.
     */
    default List<Order> orders(long marketplaceId, String symbol) {
        throw unsupported("orders(marketplaceId, symbol)");
    }

    List<Holding> holdings(long marketplaceId);

    /**
     * Holdings as they stood in particular sessions, rather than now.
     *
     * <p>What a settlement report reads: a finished run's positions are a
     * property of its session, and {@link #holdings(long)} only ever answers
     * for the current one. Defaulted so implementations that only follow live
     * state need not answer for history.
     */
    default List<Holding> holdings(long marketplaceId, List<Long> sessionIds) {
        throw unsupported("holdings(marketplaceId, sessionIds)");
    }

    /** The caller's own holding in {@code marketplaceId}. */
    Holding holding(long marketplaceId);

    List<ClientConnection> connections(long marketplaceId);

    /**
     * Connections attached during particular sessions.
     *
     * <p>Who was present in a given run, which is not the same question as who
     * is attached now. An empty or null filter means the latter.
     */
    default List<ClientConnection> connections(long marketplaceId, List<Long> sessionIds) {
        throw unsupported("connections(marketplaceId, sessionIds)");
    }

    /**
     * Resting orders, with the sequence number they were correct as of.
     *
     * <p>The sequence lets a caller reconcile this snapshot against the deltas
     * arriving on the event stream, applying only those newer than the
     * snapshot.
     */
    Snapshot<List<Order>> activeOrdersV1(long marketplaceId);

    Snapshot<List<Order>> recentTradesV1(long marketplaceId, int size);

    Snapshot<List<Order>> recentTradesV1(long marketplaceId);

    // --- writing ------------------------------------------------------------

    Order submitLimit(long marketplaceId, long marketId, String side, long units, long price);

    Order submitCancel(long marketplaceId, long marketId, long originalId);

    /**
     * Cross the book: buy at the highest price this market allows, sell at the
     * lowest.
     *
     * <p>There is no market order on the server. Its type switch falls through
     * to {@code LIMIT}, so every submission is bounds-checked against the
     * market and must sit on a tick — which is why this asks the marketplace
     * for the market first, and costs a round trip that {@link #submitLimit}
     * does not.
     *
     * <p>It used to send {@code Long.MAX_VALUE} for a buy and {@code 0} for a
     * sell, prices no real market accepts. Every buy was refused with "price
     * above market maximum", and a sell survived only where {@code
     * priceMinimum} happened to be zero. Nothing called it and no test reached
     * the wire, so it stayed that way.
     *
     * <p><b>Immediate or cancel.</b> Whatever does not fill at once is
     * cancelled. Without that a market order leaves a bid at the market's
     * maximum, or an offer at its minimum, resting where anyone may take it —
     * which is not what "market order" means to the person who sent it.
     *
     * <p>So this is two calls, and the second is unconditional: the exchange
     * consumes a cancel by itself when no units remain, so a complete fill
     * costs a harmless round trip rather than an inspection that would race the
     * book. If the cancel fails the order is still placed, and this throws
     * saying so — do not resubmit, or you will trade twice.
     *
     * <p>Returns the limit order as submitted. What it filled is a property of
     * the book afterwards, not of this value.
     */
    Order submitMarket(long marketplaceId, long marketId, String side, long units);

    // --- management ---------------------------------------------------------

    /*
     * Running an experiment, as opposed to trading in one: set up the opening
     * positions, open the session, close it, collect the result. Every study in
     * fm-robots drives this sequence, and none of it existed here -- which is
     * why they are all still on fm-lib-net, and through it on Spring.
     *
     * Defaulted rather than abstract, for the reason given on subscribe(): this
     * is a published interface, and an implementation that cannot manage
     * sessions -- a test fake, a read-only provider -- should keep compiling and
     * say so plainly if called, rather than force every implementor to write
     * eight stubs. Authorization is the server's business: these need a manager
     * or admin, and it answers 401/403 when they are not.
     */

    /** Opens the marketplace's session, returning it in its new state. */
    default Session openSession(long marketplaceId) {
        throw unsupported("openSession");
    }

    default Session pauseSession(long marketplaceId) {
        throw unsupported("pauseSession");
    }

    default Session closeSession(long marketplaceId) {
        throw unsupported("closeSession");
    }

    /** Everyone in the caller's account. */
    default List<Person> users() {
        throw unsupported("users");
    }

    /** The opening positions of one allocation. */
    default List<Allotment> allotments(long marketplaceId, long allocationId) {
        throw unsupported("allotments");
    }

    /**
     * Create a marketplace from its JSON definition, returning what was made.
     *
     * <p>Takes JSON rather than a builder because that is how the definitions
     * exist: a study keeps its marketplace as a file it can print, diff and
     * hand to someone, and the CLI's {@code --dry-run} prints exactly the
     * document that would be posted. A typed builder here would mean the thing
     * printed and the thing sent were assembled by different code.
     *
     * <p>The JSON is parsed before it is sent, so a malformed definition fails
     * locally rather than as a 400 from the server.
     */
    default Marketplace createMarketplaceFromJson(String json) {
        throw unsupported("createMarketplaceFromJson");
    }

    /**
     * Stage the opening positions for the next session, returning them as read
     * back from the server.
     *
     * <p><b>Staged, not applied.</b> An allocation lands when a <em>closed</em>
     * session is opened; pausing and re-opening does not consume it. So the
     * sequence is allocate, then {@link #closeSession} if one is running, then
     * {@link #openSession}. Calling this against a live session appears to
     * succeed and changes nobody's position.
     *
     * <p>Takes {@link Holding}s because that is what a caller has -- the shape
     * it reads positions in and computes with. The allotment form the endpoint
     * wants is an encoding detail and is applied here.
     */
    default List<Holding> allocate(long marketplaceId, List<Holding> holdings) {
        throw unsupported("allocate");
    }

    /** The holdings CSV, verbatim, as the server renders it. */
    default String downloadHoldings(long marketplaceId) {
        throw unsupported("downloadHoldings");
    }

    /**
     * The same CSV for particular sessions — a finished run's export, rather
     * than the current one's.
     */
    default String downloadHoldings(long marketplaceId, List<Long> sessionIds) {
        throw unsupported("downloadHoldings(marketplaceId, sessionIds)");
    }

    /**
     * Trades in one market, most recent first.
     *
     * <p>Answered by a symbol-keyed route, so the orders come back without the
     * symbol on them and with the trade id in {@code original}; both are filled
     * in before returning, which is what makes the result usable as a trade
     * list rather than a set of half-populated orders.
     */
    default List<Order> trades(long marketplaceId, String symbol) {
        throw unsupported("trades");
    }

    /**
     * Load opening positions from a holdings CSV, returning what was created.
     *
     * <p>Stages the next allocation on the same terms as {@link #allocate}: it
     * lands when a closed session is opened.
     *
     * <p>A {@link Path}: the file is the thing being uploaded. fm-lib-net's
     * equivalent takes a Spring {@code Resource}, which is exactly the sort of
     * dependency in a signature that this SDK exists to avoid.
     */
    default List<Holding> uploadHoldings(long marketplaceId, Path csv) {
        throw unsupported("uploadHoldings");
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
