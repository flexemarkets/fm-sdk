package fm;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fm.Exceptions.ApiException;
import fm.Exceptions.AuthenticationException;
import fm.Exceptions.ConflictException;
import fm.Exceptions.HttpException;
import fm.Types.Account;
import fm.Types.Allotment;
import fm.Types.ApiRoot;
import fm.Types.ClientConnection;
import fm.Types.ConflictFailure;
import fm.Types.Holding;
import fm.Types.Market;
import fm.Types.ManagerOtpBundle;
import fm.Types.Marketplace;
import fm.Types.Order;
import fm.Types.Person;
import fm.Types.Session;
import fm.Types.Token;

public class HttpFlexemarkets implements Flexemarkets {
    private static final String FM_SDK_CLIENT = "fm-sdk-java/0.1.0";

    // Jackson 3 mappers are immutable and built, not configured after the fact.
    // java.time support is in databind now, so there is no module to register.
    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private static final TypeReference<Token>               TOKEN_TYPE        = new TypeReference<>() {};
    private static final TypeReference<ApiRoot>              API_ROOT_TYPE     = new TypeReference<>() {};
    private static final TypeReference<List<Marketplace>>    MARKETPLACES_TYPE = new TypeReference<>() {};
    private static final TypeReference<Marketplace>          MARKETPLACE_TYPE  = new TypeReference<>() {};
    private static final TypeReference<List<Market>>         MARKETS_TYPE      = new TypeReference<>() {};
    private static final TypeReference<Market>               MARKET_TYPE       = new TypeReference<>() {};
    private static final TypeReference<List<Session>>        SESSIONS_TYPE     = new TypeReference<>() {};
    private static final TypeReference<Session>              SESSION_TYPE      = new TypeReference<>() {};
    private static final TypeReference<List<Order>>          ORDERS_TYPE       = new TypeReference<>() {};
    private static final TypeReference<Order>                ORDER_TYPE        = new TypeReference<>() {};
    private static final TypeReference<List<Holding>>        HOLDINGS_TYPE     = new TypeReference<>() {};
    private static final TypeReference<List<ClientConnection>> CONNECTIONS_TYPE = new TypeReference<>() {};
    private static final TypeReference<ConflictFailure>      CONFLICT_TYPE     = new TypeReference<>() {};
    private static final TypeReference<List<Account>>        ACCOUNTS_TYPE     = new TypeReference<>() {};
    private static final TypeReference<Account>              ACCOUNT_TYPE      = new TypeReference<>() {};
    private static final TypeReference<Person>               PERSON_TYPE       = new TypeReference<>() {};
    private static final TypeReference<Types.Approval>       APPROVAL_TYPE     = new TypeReference<>() {};
    private static final TypeReference<ManagerOtpBundle>     OTP_BUNDLE_TYPE   = new TypeReference<>() {};
    private static final TypeReference<List<String>>         SYMBOLS_TYPE      = new TypeReference<>() {};
    private static final TypeReference<List<Person>>         PERSONS_TYPE      = new TypeReference<>() {};
    private static final TypeReference<List<Allotment>>      ALLOTMENTS_TYPE   = new TypeReference<>() {};

    /**
     * Who the server should treat the caller as, rather than whoever the
     * bearer token names. Admin-only, and refused server-side otherwise.
     */
    private static final String HEADER_IMPERSONATION = "X-FM-Account";

    /**
     * What the previous call cost, reported to the server on the next one.
     *
     * <p>The same header fm-ui has always sent, and the same one fm-server
     * already parses into its connectivity histograms — a robot is another
     * client on the same path, so it reports the same way rather than on a
     * header of its own.
     *
     * <p>This is how a {@code container:} robot's distance from the exchange
     * becomes visible at all. Its orders arrive at fm-server as ordinary REST
     * calls, so the server sees them land and never sees them sent; only the
     * caller holds both ends of the round trip.
     */
    private static final String HEADER_CLIENT_TIMING = "Client-Timing";

    /** The server's own handling time, which it reports back on every response. */
    private static final String HEADER_SERVER_TIMING = "Server-Timing";

    private static final java.util.regex.Pattern SERVER_TIMING_ST =
        java.util.regex.Pattern.compile("st=(\\d+)");

    private final Properties properties;
    private final HttpClient httpClient;
    private final String bearerToken;
    private final Token token;
    private final Account account;
    private final Person user;
    private final ApiRoot apiRoot;

    private final String impersonateAccount;
    private final boolean capture;

    private Events events;
    private volatile boolean closed;

    HttpFlexemarkets(Properties properties) {
        this.properties = properties;
        // NORMAL, not the JDK's default of NEVER. The edge in front of
        // production answers plain HTTP with a 301 to the same host on HTTPS,
        // and a client that does not follow it reads the edge's HTML error page
        // as the response -- which then fails as a JSON parse error, nowhere
        // near the cause. NORMAL declines to follow HTTPS back down to HTTP, so
        // an endpoint cannot be quietly downgraded.
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        var impersonate = properties.getProperty("impersonate-account");
        this.impersonateAccount = impersonate == null || impersonate.isBlank() ? null : impersonate;
        this.capture = Boolean.parseBoolean(properties.getProperty("capture"));

        this.token = signIn();
        this.account = token.account();
        this.user = token.person();
        this.bearerToken = "Bearer " + token.token();

        this.apiRoot = fetchApiRoot();
    }


    public Account account() { return account; }
    public long accountId() { return account.id(); }
    public String accountName() { return account.name(); }
    public Person user() { return user; }
    public long userId() { return user.id(); }

    public String endpointUrl() {
        return properties.getProperty("endpoint");
    }

    public long endpointMarketplaceId() {
        return resourceId(endpointUrl());
    }

    // --- REST APIs ---

    public List<Marketplace> marketplaces() {
        return get(uriParam(apiRoot, "marketplaces", "format=application/json"), MARKETPLACES_TYPE);
    }

    public Marketplace marketplace(long marketplaceId) {
        return get(uriId(apiRoot, "marketplaces", marketplaceId), MARKETPLACE_TYPE);
    }

    public List<Market> markets(long marketplaceId) {
        return get(uriIdSegmentParam(apiRoot, "marketplaces", marketplaceId, "markets", "format=application/json"), MARKETS_TYPE);
    }

    @Override
    public List<String> symbols(long marketplaceId) {
        return get(uriIdSegment(apiRoot, "marketplaces", marketplaceId, "symbols"), SYMBOLS_TYPE);
    }

    @Override
    public Token token() {
        return token;
    }

    /** Roles come from the sign-in token; an absent roles array is not admin. */
    @Override
    public boolean isAdmin() {
        if (user == null || user.roles() == null) {
            return false;
        }
        for (var role : user.roles()) {
            if ("ROLE_ADMIN".equals(role)) {
                return true;
            }
        }
        return false;
    }

    public List<Session> sessions(long marketplaceId) {
        return get(uriIdSegmentParam(apiRoot, "marketplaces", marketplaceId, "sessions", "format=application/json"), SESSIONS_TYPE);
    }

    public Session session(long marketplaceId) {
        return get(uriIdSegment(apiRoot, "marketplaces", marketplaceId, "currentSession"), SESSION_TYPE);
    }

    public List<Order> orders(long marketplaceId) {
        return get(uriIdSegment(apiRoot, "marketplaces", marketplaceId, "orders"), ORDERS_TYPE);
    }

    /**
     * V1 active-orders snapshot: every resting limit order on the
     * marketplace's current session, plus the {@code x-fm-as-of-seq}
     * sequence the snapshot was read at. Used by {@link MarketView}
     * for Phase 2a snapshot seeding — clients apply WS deltas whose
     * seq is greater than the returned value and skip those whose
     * seq is less than or equal.
     */
    public Snapshot<List<Order>> activeOrdersV1(long marketplaceId) {
        var url = server(endpointUrl()) + "/v1/marketplaces/" + marketplaceId + "/orders/active";
        return _unwrapOrders(getSnapshot(url, ORDERS_COLLECTION_TYPE));
    }

    /**
     * V1 recent-trades snapshot for seeding the trade-history tape.
     * Same {@code x-fm-as-of-seq} contract as
     * {@link #activeOrdersV1(long)}.
     */
    public Snapshot<List<Order>> recentTradesV1(long marketplaceId, int size) {
        var url = server(endpointUrl()) + "/v1/marketplaces/" + marketplaceId
                + "/orders/recent-trades?size=" + size;
        return _unwrapOrders(getSnapshot(url, ORDERS_COLLECTION_TYPE));
    }

    /** Unwrap the Spring HATEOAS {@code CollectionModel<OrderDto>} envelope
     *  that the V1 endpoints return, defaulting to an empty list when
     *  {@code _embedded} is absent (which fm-server omits on empty
     *  responses). */
    private static Snapshot<List<Order>> _unwrapOrders(Snapshot<HateoasCollection<Order>> raw) {
        List<Order> orders;
        if (raw.body() == null || raw.body().embedded == null || raw.body().embedded.orderDtoes == null) {
            orders = List.of();
        } else {
            orders = raw.body().embedded.orderDtoes;
        }
        return new Snapshot<>(orders, raw.asOfSeq());
    }

    /** Spring HATEOAS CollectionModel envelope, just the bits we need. */
    private static class HateoasCollection<T> {
        @com.fasterxml.jackson.annotation.JsonProperty("_embedded")
        Embedded<T> embedded;
    }

    private static class Embedded<T> {
        @com.fasterxml.jackson.annotation.JsonProperty("orderDtoes")
        List<T> orderDtoes;
    }

    private static final TypeReference<HateoasCollection<Order>> ORDERS_COLLECTION_TYPE = new TypeReference<>() {};

    /** Sensible default — the server caps at 5000 and defaults to 1000. */
    public Snapshot<List<Order>> recentTradesV1(long marketplaceId) {
        return recentTradesV1(marketplaceId, 1000);
    }

    public List<Holding> holdings(long marketplaceId) {
        return get(uriIdSegment(apiRoot, "marketplaces", marketplaceId, "holdings"), HOLDINGS_TYPE);
    }

    /** Comma-separated ids, matching the server's {@code ?sessions=} filter. */
    @Override
    public List<Holding> holdings(long marketplaceId, List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return holdings(marketplaceId);
        }
        var ids = sessionIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return get(uriIdSegmentParam(apiRoot, "marketplaces", marketplaceId, "holdings", "sessions=" + ids),
                   HOLDINGS_TYPE);
    }

    public Holding holding(long marketplaceId) {
        return get(uriIdSegment(apiRoot, "marketplaces", marketplaceId, "currentHolding"), new TypeReference<>() {});
    }

    public List<ClientConnection> connections(long marketplaceId) {
        // Canonical path is /marketplaces/{id}/connections ("/agents" is the
        // retained pre-FM-4 alias); format=application/json yields a plain list
        // (vs the HAL _embedded form).
        return get(uriIdSegmentParam(apiRoot, "marketplaces", marketplaceId, "connections", "format=application/json"), CONNECTIONS_TYPE);
    }

    /**
     * {@code sessionIds=}, not {@code sessions=}. The server spells the filter
     * differently on this route than on the holdings download, and using the
     * wrong one is not an error -- it is an unfiltered answer.
     */
    @Override
    public List<Session> sessions(long marketplaceId, List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return sessions(marketplaceId);
        }
        return get(uriIdSegmentParam(apiRoot, "marketplaces", marketplaceId, "sessions",
                        "sessionIds=" + _ids(sessionIds) + "&format=application/json"),
                   SESSIONS_TYPE);
    }

    @Override
    public List<ClientConnection> connections(long marketplaceId, List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return connections(marketplaceId);
        }
        return get(uriIdSegmentParam(apiRoot, "marketplaces", marketplaceId, "connections",
                        "sessionIds=" + _ids(sessionIds) + "&format=application/json"),
                   CONNECTIONS_TYPE);
    }

    /** {@code sessions=} here, unlike the two routes above. */
    @Override
    public String downloadHoldings(long marketplaceId, List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return downloadHoldings(marketplaceId);
        }
        return getText(uriIdSegmentParam(apiRoot, "marketplaces", marketplaceId,
                        "holdings/downloads", "sessions=" + _ids(sessionIds)));
    }

    /**
     * The symbol-keyed trades route answers with the trade id in
     * {@code original} and no symbol on the orders, because the query already
     * fixed the symbol. Both are filled in here so a caller gets trades rather
     * than half-populated orders -- fm-lib-net does the same, and a study that
     * groups by symbol or keys by id depends on it.
     */
    /** {@code sessionOrdersJson}, not the marketplace's orders collection: that one is current-session only. */
    @Override
    public List<Order> orders(long marketplaceId, List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return orders(marketplaceId);
        }
        var url = uriParam(apiRoot, "sessionOrdersJson", "marketplaceId=" + marketplaceId)
                + "&sessionIds=" + _ids(sessionIds);
        return get(url, ORDERS_TYPE);
    }

    /** The symbol is filled in; the ids are left alone. See {@link #trades}. */
    @Override
    public List<Order> orders(long marketplaceId, String symbol) {
        var url = uriParam(apiRoot, "symbolOrdersJson", "marketplaceId=" + marketplaceId)
                + "&symbol=" + symbol;
        return get(url, ORDERS_TYPE).stream()
                .map(o -> new Order(o.createdDate(), o.lastModifiedDate(), o.id(),
                                    o.original(), o.supplier(), o.consumer(), o.type(), o.side(),
                                    o.units(), o.price(), o.mine(), o.ownerId(), o.marketplaceId(),
                                    o.sessionId(), symbol, o.marketId(), o.ownerTarget(),
                                    o.clientDescription()))
                .toList();
    }

    @Override
    public List<Order> trades(long marketplaceId, String symbol) {
        var url = uriParam(apiRoot, "symbolTradesJson", "marketplaceId=" + marketplaceId)
                + "&symbol=" + symbol;
        return get(url, ORDERS_TYPE).stream()
                .map(o -> new Order(o.createdDate(), o.lastModifiedDate(), o.original(),
                                    o.original(), o.supplier(), o.consumer(), o.type(), o.side(),
                                    o.units(), o.price(), o.mine(), o.ownerId(), o.marketplaceId(),
                                    o.sessionId(), symbol, o.marketId(), o.ownerTarget(),
                                    o.clientDescription()))
                .toList();
    }

    private static String _ids(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    // --- administration -----------------------------------------------------

    @Override
    public Token signup(String accountName, String email, String password) {
        return signup(accountName, email, password, null, null);
    }

    /** A name clash arrives as 409 and becomes a ConflictException carrying the server's suggestion. */
    @Override
    public Token signup(String accountName, String email, String password,
                        String firstName, String lastName) {
        try {
            return post(uri(apiRoot, "accounts"),
                        new SignUp(accountName, email, password, firstName, lastName),
                        TOKEN_TYPE);
        } catch (ConflictException e) {
            // A taken name, with the server's proposed alternative. Raised as
            // its own type so a caller can offer the suggestion rather than
            // parsing it back out of a generic conflict.
            var failure = e.failure();
            throw new Exceptions.AccountNameConflictException(
                    accountName, failure == null ? null : failure.suggestedName());
        }
    }

    @Override
    public Account approveAccount(String accountName) {
        var approval = post(server(endpointUrl()) + "/approvals",
                            new ApproveAccount(accountName, true), APPROVAL_TYPE);
        return approval == null ? null : approval.account();
    }

    @Override
    public Account account(long accountId) {
        return get(uriId(apiRoot, "accounts", accountId), ACCOUNT_TYPE);
    }

    @Override
    public Person user(long userId) {
        return get(uriId(apiRoot, "users", userId), PERSON_TYPE);
    }

    @Override
    public List<String> identifiers(long marketplaceId) {
        return get(uriIdSegment(apiRoot, "marketplaces", marketplaceId, "privateTraders"), SYMBOLS_TYPE);
    }

    @Override
    public void deleteMyAccount() {
        delete(server(endpointUrl()) + "/accounts/me");
    }

    @Override
    public List<Account> accounts() {
        return get(uriParam(apiRoot, "accounts", "format=application/json"), ACCOUNTS_TYPE);
    }

    @Override
    public void deleteAccount(long accountId) {
        delete(uriId(apiRoot, "accounts", accountId));
    }

    @Override
    public Person createUser(String email, String password, String firstName,
                             String lastName, String... roles) {
        return post(uri(apiRoot, "users"),
                    new CreateUser(email, password, firstName, lastName, roles),
                    PERSON_TYPE);
    }

    @Override
    public void deleteUser(long userId) {
        try {
            delete(uriId(apiRoot, "users", userId));
        } catch (ConflictException e) {
            // The user still owns orders or allotments. Deleting them would
            // orphan it, so the server refuses and the caller has to decide
            // what happens to the data first.
            throw new Exceptions.PersonHasMarketplaceDataException(userId, e.getMessage());
        }
    }

    @Override
    public Marketplace createMarketplace(String name, String description) {
        return post(uri(apiRoot, "marketplaces"),
                    new CreateMarketplace(name, description), MARKETPLACE_TYPE);
    }

    @Override
    public void deleteMarketplace(long marketplaceId) {
        delete(uriId(apiRoot, "marketplaces", marketplaceId));
    }

    /** Unit bounds are fixed at 1/100/1, as fm-lib-net sends them. */
    @Override
    public Market createMarket(long marketplaceId, String symbol, String name,
                               long priceMinimum, long priceMaximum, long priceTick,
                               boolean privateMarket) {
        return post(uriIdSegment(apiRoot, "marketplaces", marketplaceId, "markets"),
                    new CreateMarket(symbol, name, priceMinimum, priceMaximum, priceTick,
                                     1, 100, 1, privateMarket),
                    MARKET_TYPE);
    }

    @Override
    public ManagerOtpBundle managerOtpBundle(List<Long> userIds) {
        return post(server(endpointUrl()) + "/otp/manager",
                    new ManagerOtpRequest(userIds), OTP_BUNDLE_TYPE);
    }

    /*
     * Request bodies. Records rather than maps so a field that the server
     * renamed fails to compile here rather than being silently dropped from
     * the JSON -- the shape of the grants/securities bug.
     */
    private record SignUp(String accountName, String ownerEmail, String ownerPassword,
                          String firstName, String lastName) {}

    private record ApproveAccount(String name, Boolean approval) {}

    private record CreateUser(String email, String password, String firstName,
                              String lastName, String[] roles) {}

    private record CreateMarketplace(String name, String description) {}

    private record CreateMarket(String symbol, String name,
                                long priceMinimum, long priceMaximum, long priceTick,
                                long unitMinimum, long unitMaximum, long unitTick,
                                boolean privateMarket) {}

    private record ManagerOtpRequest(List<Long> userIds) {}

    public Order submitLimit(long marketplaceId, long marketId, String side, long units, long price) {
        var order = Map.of(
            "marketplaceId", marketplaceId,
            "marketId",      marketId,
            "type",          Order.TYPE_LIMIT,
            "side",          side,
            "units",         units,
            "price",         price,
            "clientDescription", clientDescription()
        );
        return post(uri(apiRoot, "orders"), order, ORDER_TYPE);
    }

    public Order submitCancel(long marketplaceId, long marketId, long originalId) {
        var order = Map.of(
            "marketplaceId",    marketplaceId,
            "marketId",         marketId,
            "type",             Order.TYPE_CANCEL,
            "id",               originalId,
            "original",         originalId,
            "supplier",         originalId,
            "clientDescription", clientDescription()
        );
        return post(uri(apiRoot, "orders"), order, ORDER_TYPE);
    }

    public Order submitMarket(long marketplaceId, long marketId, String side, long units) {
        var order = Map.of(
            "marketplaceId", marketplaceId,
            "marketId",      marketId,
            "type",          Order.TYPE_LIMIT,
            "side",          side,
            "units",         units,
            "price",         Order.SIDE_BUY.equals(side) ? Long.MAX_VALUE : 0L,
            "clientDescription", clientDescription()
        );
        return post(uri(apiRoot, "orders"), order, ORDER_TYPE);
    }

    // --- management ---------------------------------------------------------

    @Override
    public Session openSession(long marketplaceId) {
        return patch(uriIdSegment(apiRoot, "marketplaces", marketplaceId, "open"), SESSION_TYPE);
    }

    @Override
    public Session pauseSession(long marketplaceId) {
        return patch(uriIdSegment(apiRoot, "marketplaces", marketplaceId, "pause"), SESSION_TYPE);
    }

    @Override
    public Session closeSession(long marketplaceId) {
        return patch(uriIdSegment(apiRoot, "marketplaces", marketplaceId, "close"), SESSION_TYPE);
    }

    /** {@code usersJson} rather than {@code users}: the latter is the HAL form. */
    @Override
    public List<Person> users() {
        return get(uri(apiRoot, "usersJson"), PERSONS_TYPE);
    }

    /** Not on the API root -- allotments are a V1 route, addressed from the server. */
    @Override
    public List<Allotment> allotments(long marketplaceId, long allocationId) {
        var url = server(endpointUrl()) + "/v1/marketplaces/" + marketplaceId
                + "/allotments?allocation=" + allocationId;
        return List.copyOf(get(url, ALLOTMENTS_TYPE));
    }

    /** V1 route, addressed from the server rather than through a HAL link. */
    @Override
    public Marketplace createMarketplaceFromJson(String json) {
        Object definition;
        try {
            definition = MAPPER.readValue(json, new TypeReference<Object>() {});
        } catch (JacksonException e) {
            throw new ApiException("Marketplace definition is not valid JSON", e);
        }
        return post(server(endpointUrl()) + "/v1/marketplaces", definition, MARKETPLACE_TYPE);
    }

    @Override
    public List<Holding> allocate(long marketplaceId, List<Holding> holdings) {
        var allotments = holdings.stream().map(h -> _toAllotment(marketplaceId, h)).toList();
        return _toHoldings(post(
                uriIdSegment(apiRoot, "marketplaces", marketplaceId, "allocations"),
                allotments, ALLOTMENTS_TYPE));
    }

    @Override
    public String downloadHoldings(long marketplaceId) {
        return getText(uriIdSegment(apiRoot, "marketplaces", marketplaceId, "holdings/downloads"));
    }

    @Override
    public List<Holding> uploadHoldings(long marketplaceId, Path csv) {
        return _toHoldings(postMultipart(
                uriIdSegment(apiRoot, "marketplaces", marketplaceId, "holdings/uploads"),
                "file", csv, ALLOTMENTS_TYPE));
    }

    /*
     * Allotment <-> Holding. The allocation endpoints speak allotments; callers
     * hold holdings. Converting here keeps that asymmetry out of every caller,
     * which is the whole reason allocate() takes holdings.
     */

    private static Allotment _toAllotment(long marketplaceId, Holding holding) {
        var assets = new Types.Assets(null, holding.name(), holding.cash(), holding.securities());
        return new Allotment(null, null, marketplaceId, holding.ownerId(), holding.name(), assets);
    }

    private static List<Holding> _toHoldings(List<Allotment> allotments) {
        return allotments.stream().map(HttpFlexemarkets::_toHolding).toList();
    }

    /**
     * An allotment is an opening position, so it has no session yet: sessionId
     * is 0 until one is opened over it, and availableCash equals cash because
     * nothing has been committed against it.
     */
    private static Holding _toHolding(Allotment allotment) {
        var assets = allotment.assets();
        long cash = assets != null ? assets.cash() : 0L;
        return new Holding(
                _unbox(allotment.marketplaceId()),
                0L,
                _unbox(allotment.allocationId()),
                _unbox(allotment.ownerId()),
                allotment.name(),
                cash,
                cash,
                assets != null ? assets.securities() : List.of());
    }

    private static long _unbox(Long value) {
        return value != null ? value : 0L;
    }

    public void listen(long marketplaceId, BlockingQueue<Object> queue) {
        events = new Events(wsUrl(), bearerToken, marketplaceId, clientDescription(), MAPPER, queue);
        events.connect();
    }

    /**
     * Package-private helper used by {@link DefaultMarketView} (Phase 2d)
     * to own its own {@link Events} subscription rather than clobbering
     * {@link #events}. Lets multiple {@code observe(marketplaceId)}
     * calls — for the same or different marketplaces — coexist within
     * one {@code Flexemarkets} instance without trampling each other's
     * WS connections.
     */
    @Override
    public Subscription subscribe(long marketplaceId, BlockingQueue<Object> queue) {
        return _connectEvents(marketplaceId, queue);
    }

    Events _connectEvents(long marketplaceId, BlockingQueue<Object> queue) {
        var ev = new Events(wsUrl(), bearerToken, marketplaceId, clientDescription(), MAPPER, queue);
        ev.connect();
        return ev;
    }

    private final java.util.Map<Long, SharedMarketView> sharedViews = new java.util.HashMap<>();
    private final Object viewLock = new Object();

    private static final class SharedMarketView {
        final DefaultMarketView view;
        int refCount;
        SharedMarketView(DefaultMarketView v) { this.view = v; this.refCount = 0; }
    }

    /**
     * Open a stateful {@link MarketView} on this marketplace. Multiple
     * calls for the same {@code marketplaceId} share a single
     * underlying view + WS subscription within this {@code Flexemarkets}
     * instance — each call returns a fresh handle, the handles
     * refcount, and the shared resources tear down on the last close.
     *
     * <p>Sharing is intentionally per-{@code Flexemarkets} (i.e.
     * per-bearer). Two callers with different identities each get
     * their own view — multi-tenant WS multiplexing is a server-side
     * concern, not a client-side one.
     */
    public MarketView observe(long marketplaceId) {
        DefaultMarketView shared;
        synchronized (viewLock) {
            SharedMarketView entry = sharedViews.get(marketplaceId);
            if (entry == null) {
                // Hold the lock while constructing — observe() should
                // be a cold-path operation, and we'd rather block
                // duplicate observers than race two parallel WS
                // subscriptions into existence. The DefaultMarketView
                // constructor itself blocks on REST snapshots, so a
                // dozen-ms first call is acceptable.
                shared = new DefaultMarketView(this, marketplaceId, markets(marketplaceId));
                entry = new SharedMarketView(shared);
                sharedViews.put(marketplaceId, entry);
            }
            entry.refCount++;
            shared = entry.view;
        }
        return new MarketViewHandle(shared, () -> _releaseSharedView(marketplaceId));
    }

    void _releaseSharedView(long marketplaceId) {
        DefaultMarketView toClose = null;
        synchronized (viewLock) {
            SharedMarketView entry = sharedViews.get(marketplaceId);
            if (entry == null) return;
            if (--entry.refCount <= 0) {
                sharedViews.remove(marketplaceId);
                toClose = entry.view;
            }
        }
        if (toClose != null) toClose.close();
    }

    public void reconnect() throws InterruptedException {
        if (events != null) {
            events.reconnect();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (events != null) {
            events.close();
        }
        // Force-close any remaining shared MarketViews. Well-behaved
        // callers close their handles first; this is the safety net.
        java.util.List<DefaultMarketView> toClose;
        synchronized (viewLock) {
            toClose = new java.util.ArrayList<>(sharedViews.size());
            for (var entry : sharedViews.values()) toClose.add(entry.view);
            sharedViews.clear();
        }
        for (var v : toClose) {
            try { v.close(); } catch (Throwable ignored) { /* best-effort */ }
        }
        httpClient.close();
    }

    // --- HTTP helpers ---

    /**
     * A request builder carrying everything every authenticated call sends:
     * the bearer token, the client's user agent, and — when the caller asked
     * to act as another account — the impersonation header.
     *
     * <p>Every authenticated request goes through here so impersonation cannot
     * be applied to some routes and quietly missed on others. That failure
     * mode is silent in the shape that matters: the call succeeds, answering
     * for the wrong account.
     */
    private HttpRequest.Builder request(String url, String accept) {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", bearerToken)
            .header("Accept", accept)
            .header("User-Agent", FM_SDK_CLIENT);

        if (impersonateAccount != null) {
            builder.header(HEADER_IMPERSONATION, impersonateAccount);
        }

        // What the previous call cost, carried on this one. A round trip is not
        // known until it has finished, and by then the request that would have
        // reported it has gone -- so each measurement arrives one call late,
        // which for a robot on an interval is a lag of one tick.
        //
        // Taken rather than read, so a figure is reported once. Sending the
        // same measurement on every subsequent request would weight a single
        // slow call by however many quiet ones followed it.
        var timing = lastTiming.getAndSet(null);

        if (timing != null) {
            builder.header(HEADER_CLIENT_TIMING, timing.header());
        }

        return builder;
    }

    /**
     * What one call cost this client, split into the wire and the server.
     *
     * <p>Nanoseconds, and both measured on this machine's clock: the round trip
     * here, and the server's own share reported back by it. Neither is ever
     * compared with the other's clock, which is what keeps skew out of the
     * figure -- a robot in a container is a machine nobody has promised
     * anything about the time on.
     *
     * @param roundTripNanos the whole wait, as this client measured it
     * @param networkNanos   what is left after the server's share, or -1 when
     *                       the server did not say
     */
    private record Timing(long roundTripNanos, long networkNanos) {

        String header() {
            var value = new StringBuilder("rtt=").append(roundTripNanos);

            // Omitted rather than sent as zero when unknown. fm-server reads an
            // absent net= as "all of it was the server", which understates the
            // wire; a zero would claim there was none.
            if (networkNanos >= 0) {
                value.append(";net=").append(networkNanos);
            }

            return value.toString();
        }
    }

    /**
     * Send one request, tracing it to stdout when the caller asked for capture.
     *
     * <p>Every send goes through here, sign-in included, so {@code capture}
     * cannot cover most of a session and silently miss the rest.
     *
     * <p>The {@code Authorization} value is redacted, and the sign-in routes'
     * bodies are withheld entirely — that document *is* a token. Capture
     * writes to stdout and stdout is what gets pasted into a bug report;
     * fm-lib-net printed both in full, which this deliberately does not.
     */
    private HttpResponse<String> exchange(HttpRequest request) throws IOException, InterruptedException {
        var started = System.nanoTime();
        var response = dispatch(request);

        // Only a completed call is a measurement. A request that threw took an
        // unknown amount of an unknown thing -- a refused connection is not a
        // slow network -- so nothing is recorded and the next request simply
        // carries no header.
        recordTiming(System.nanoTime() - started, response);

        return response;
    }

    /**
     * How long the last call took, waiting to be told to whoever asks next.
     *
     * <p>Held rather than sent immediately because there is nowhere to put it:
     * the response carrying the answer has already been written by the time the
     * answer exists.
     */
    private final java.util.concurrent.atomic.AtomicReference<Timing> lastTiming =
        new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Record what a call cost, taking the server's share out of it.
     *
     * <p>The server reports its own handling as {@code Server-Timing: st=<nanos>},
     * so what remains of the round trip is the wire. Without that header the
     * network figure is unknown rather than zero, and is left out — fm-server
     * then charges the whole trip to itself, which overstates its own share and
     * can never hide a slow link behind it.
     */
    private void recordTiming(long roundTripNanos, HttpResponse<String> response) {
        var serverNanos = response.headers().firstValue(HEADER_SERVER_TIMING)
            .map(HttpFlexemarkets::serviceNanos)
            .orElse(-1L);

        var networkNanos = serverNanos < 0 ? -1L : Math.max(0, roundTripNanos - serverNanos);

        lastTiming.set(new Timing(roundTripNanos, networkNanos));
    }

    /** The {@code st=} field of a Server-Timing header, or -1 if it has none. */
    private static long serviceNanos(String header) {
        var matcher = SERVER_TIMING_ST.matcher(header);

        if (!matcher.find()) {
            return -1;
        }

        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private HttpResponse<String> dispatch(HttpRequest request) throws IOException, InterruptedException {
        if (!capture) {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }

        var out = System.out;
        out.printf("> %s %s%n", request.method(), request.uri());
        printCapturedHeaders(out, ">", request.headers().map());
        out.println(">");

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        out.printf("< %s%n", response.statusCode());
        printCapturedHeaders(out, "<", response.headers().map());
        if (isCredentialRoute(request)) {
            out.printf("<%n[body withheld: credential document]%n");
        } else if (response.body() != null && !response.body().isEmpty()) {
            out.printf("<%n%s%n", response.body());
        }
        out.println("<");
        out.flush();

        return response;
    }

    /** Routes whose body is a credential rather than a document about one. */
    private static boolean isCredentialRoute(HttpRequest request) {
        var path = request.uri().getPath();

        return path.endsWith("/tokens") || path.endsWith("/refresh") || path.contains("/otp");
    }

    private static void printCapturedHeaders(java.io.PrintStream out, String prefix,
                                             Map<String, List<String>> headers) {
        headers.keySet().stream().sorted().forEach(name -> {
            var value = "authorization".equalsIgnoreCase(name)
                    ? "[redacted]"
                    : headers.get(name).toString();
            out.printf("%s %s: %s%n", prefix, name, value);
        });
    }

    private <T> T get(String url, TypeReference<T> type) {
        var request = request(url, "application/json")
            .GET()
            .build();
        return send(request, type);
    }

    /**
     * GET helper that returns the parsed body bundled with the
     * {@code x-fm-as-of-seq} response header so callers (notably
     * {@link MarketView}) can correlate the snapshot with the WS
     * delta stream. Returns {@link Snapshot#NO_SEQ} when the header
     * is absent.
     */
    private <T> Snapshot<T> getSnapshot(String url, TypeReference<T> type) {
        var request = request(url, "application/json")
            .GET()
            .build();
        try {
            var response = exchange(request);
            var statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                T body = MAPPER.readValue(response.body(), type);
                long asOfSeq = response.headers().firstValue("x-fm-as-of-seq")
                        .map(Long::parseLong)
                        .orElse(Snapshot.NO_SEQ);
                return new Snapshot<>(body, asOfSeq);
            }
            if (statusCode == 401) {
                throw new Exceptions.AuthenticationException("Authentication failed: " + response.body());
            }
            throw new Exceptions.HttpException(statusCode, response.body());
        } catch (Exceptions.FlexemarketsException e) {
            throw e;
        } catch (IOException e) {
            throw new Exceptions.ApiException("Snapshot request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exceptions.ApiException("Snapshot request interrupted", e);
        }
    }

    private <T> T post(String url, Object body, TypeReference<T> type) {
        String json;

        // Only the write is guarded. The catch used to cover send() as well, so
        // a failure parsing the *response* was reported as "Failed to serialize
        // request body" -- a message that names the wrong direction, the wrong
        // payload, and sends the reader to the wrong side of the wire. It cost
        // an afternoon on a null the response carried.
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (JacksonException e) {
            throw new ApiException("Failed to serialize request body", e);
        }

        var request = request(url, "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        return send(request, type);
    }

    /**
     * PATCH with no body -- the shape every session transition takes
     * ({@code /open}, {@code /pause}, {@code /close}): the verb and the path
     * carry the whole request.
     */
    private <T> T patch(String url, TypeReference<T> type) {
        var request = request(url, "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.noBody())
            .build();
        return send(request, type);
    }

    /**
     * GET returning the body verbatim, for endpoints that answer with something
     * other than JSON. The holdings download is a CSV, and parsing it as JSON
     * would fail on the first line.
     */
    /** DELETE, whose answer is a status and nothing worth parsing. */
    private void delete(String url) {
        var request = request(url, "application/json")
            .DELETE()
            .build();
        sendDiscardingBody(request);
    }

    private String getText(String url) {
        var request = request(url, "text/csv, */*")
            .GET()
            .build();
        try {
            var response = exchange(request);
            var statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                return response.body();
            }
            if (statusCode == 401) {
                throw new AuthenticationException("Authentication failed: " + response.body());
            }
            throw new HttpException(statusCode, response.body());
        } catch (Exceptions.FlexemarketsException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException("HTTP request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("HTTP request interrupted", e);
        }
    }

    /**
     * POST one file as {@code multipart/form-data}.
     *
     * <p>Assembled by hand because {@code java.net.http} has no multipart body
     * publisher and this is the only place the SDK needs one -- a dependency
     * would cost more than the twenty lines. The parts are written as bytes,
     * not through a string, so the file's own encoding survives.
     */
    private <T> T postMultipart(String url, String partName, Path file, TypeReference<T> type) {
        var boundary = "fm-sdk-" + java.util.UUID.randomUUID();
        try {
            var head = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + partName + "\"; filename=\""
                    + file.getFileName() + "\"\r\n"
                    + "Content-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            var tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            var content = Files.readAllBytes(file);

            var body = new java.io.ByteArrayOutputStream();
            body.write(head);
            body.write(content);
            body.write(tail);

            var request = request(url, "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
            return send(request, type);
        } catch (IOException e) {
            throw new ApiException("Failed to read " + file, e);
        }
    }

    private <T> T send(HttpRequest request, TypeReference<T> type) {
        try {
            var response = exchange(request);
            var statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 300) {
                return MAPPER.readValue(response.body(), type);
            }

            if (statusCode == 401) {
                throw new AuthenticationException("Authentication failed: " + response.body());
            }

            if (statusCode == 409) {
                var failure = tryParseConflict(response.body());
                throw new ConflictException("Conflict: " + response.body(), failure);
            }

            throw new HttpException(statusCode, response.body());
        } catch (Exceptions.FlexemarketsException e) {
            throw e;
        } catch (JacksonException e) {
            // The call succeeded and its answer is unreadable, which is a
            // different fault from the call failing and worth naming: it means
            // this client and the server disagree about a type. Unwrapped, it
            // surfaced as a bare Jackson exception naming a field, with nothing
            // to say which SDK call had produced it.
            throw new ApiException("Failed to parse the response body", e);
        } catch (IOException e) {
            throw new ApiException("HTTP request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("HTTP request interrupted", e);
        }
    }

    /**
     * Send a request whose answer is a status, not a document.
     *
     * <p>DELETE answers 204 with an empty body, which {@link #send} would try
     * to parse as JSON and fail on. The error mapping is otherwise the same,
     * including 409 -- deleting a user who still owns something is a conflict,
     * and it says which.
     */
    private void sendDiscardingBody(HttpRequest request) {
        try {
            var response = exchange(request);
            var statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 300) {
                return;
            }

            if (statusCode == 401) {
                throw new AuthenticationException("Authentication failed: " + response.body());
            }

            if (statusCode == 409) {
                var failure = tryParseConflict(response.body());
                throw new ConflictException("Conflict: " + response.body(), failure);
            }

            throw new HttpException(statusCode, response.body());
        } catch (Exceptions.FlexemarketsException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException("HTTP request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("HTTP request interrupted", e);
        }
    }

    private Token signIn() {
        var endpoint = server(endpointUrl()) + "/tokens";
        var account = properties.getProperty("account");
        var email = properties.getProperty("email");
        var password = properties.getProperty("password");
        var tokenValue = properties.getProperty("token");

        HttpRequest request;

        if (tokenValue != null && !tokenValue.isBlank()) {
            // A caller who already holds a token has no account/email/password
            // to present, so signing in is not available: POSTing /tokens with
            // the blanks left by loadCredential is rejected, and the identity
            // this constructor needs never arrives. Refreshing the token both
            // validates it and returns the account and person behind it.
            //
            // This is the second time: fm-lib-net carries the same branch, with
            // a comment recording that an earlier rewrite dropped it. Restored
            // here, and covered by a test so it cannot be dropped a third time.
            request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/refresh"))
                .header("Authorization", "Bearer " + tokenValue)
                .header("Accept", "application/json")
                .header("User-Agent", FM_SDK_CLIENT)
                .GET()
                .build();
        } else {
            var username = account + "|" + email;
            var basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));

            var body = Map.of("username", username, "password", password);
            try {
                var json = MAPPER.writeValueAsString(body);
                request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", basicAuth)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", FM_SDK_CLIENT)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            } catch (JacksonException e) {
                throw new ApiException("Failed to serialize sign-in body", e);
            }
        }

        try {
            var response = exchange(request);
            if (response.statusCode() == 401) {
                throw new AuthenticationException("Authentication failed.");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new HttpException(response.statusCode(), response.body());
            }
            return MAPPER.readValue(response.body(), TOKEN_TYPE);
        } catch (Exceptions.FlexemarketsException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException("Sign-in request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Sign-in request interrupted", e);
        }
    }

    private ApiRoot fetchApiRoot() {
        var url = server(endpointUrl());
        var request = request(url, "application/json")
            .GET()
            .build();
        return send(request, API_ROOT_TYPE);
    }

    private String clientDescription() {
        return properties.getProperty("client-description", "Unspecified client");
    }

    private String wsUrl() {
        return server(endpointUrl()).replaceFirst("http", "ws") + "/events";
    }

    // --- HATEOAS URI builders ---

    static String uri(ApiRoot apiRoot, String linkName) {
        var href = apiRoot.getLink(linkName)
            .orElseThrow(() -> new ApiException("Link '%s' not found in API root".formatted(linkName)));
        return processTemplate(href);
    }

    static String uriId(ApiRoot apiRoot, String linkName, long id) {
        return uri(apiRoot, linkName) + "/" + id;
    }

    static String uriIdSegment(ApiRoot apiRoot, String linkName, long id, String segment) {
        return uriId(apiRoot, linkName, id) + "/" + segment;
    }

    static String uriParam(ApiRoot apiRoot, String linkName, String param) {
        return uri(apiRoot, linkName) + "?" + param;
    }

    static String uriIdSegmentParam(ApiRoot apiRoot, String linkName, long id, String segment, String param) {
        var href = uriIdSegment(apiRoot, linkName, id, segment);
        if (param != null && !param.isBlank()) {
            href = href + "?" + param;
        }
        return href;
    }

    static String processTemplate(String href) {
        if (href != null) {
            int index = href.indexOf('{');
            if (index >= 0) {
                return href.substring(0, index);
            }
        }
        return href;
    }

    static String server(String endpoint) {
        // Locate "/api" in the path, not in the scheme/host. A host like
        // "https://api.flexemarkets.com" otherwise matches at the "//api" of
        // the host and truncates the base URL to "https://api" (unresolvable).
        // Skip past the scheme + host before searching for the "/api" segment.
        int scheme = endpoint.indexOf("://");
        int pathStart = scheme >= 0 ? endpoint.indexOf('/', scheme + 3) : 0;
        if (pathStart < 0) return endpoint;
        int idx = endpoint.indexOf("/api", pathStart);
        return idx < 0 ? endpoint : endpoint.substring(0, idx + 4);
    }

    static long resourceId(String endpoint) {
        if (endpoint == null) throw new NullPointerException("Endpoint is null.");
        var segments = endpoint.split("/");
        return Long.parseLong(segments[segments.length - 1]);
    }

    private static ConflictFailure tryParseConflict(String body) {
        try {
            return MAPPER.readValue(body, CONFLICT_TYPE);
        } catch (Exception e) {
            return null;
        }
    }

    // --- Credential loading ---

    public static Properties loadProperties(String credential, String endpoint, String clientDescription) throws IOException {
        var properties = setDefaultProperties();

        if (credential != null) {
            loadCredential(properties, credential);
        }

        if (endpoint != null) {
            loadEndpoint(properties, endpoint);
        }

        // Last, because an endpoint arrives from three places and only one of
        // them used to expand a bare id: the argument. A file's contents are
        // loaded verbatim, so "endpoint=1234" in ~/.fm/endpoint -- read by
        // setDefaultProperties on every connection -- or in a file named by -E
        // reached the HTTP client as "1234". Normalising here covers all three,
        // and is a no-op on the form the argument path already produced.
        expandBareMarketplaceId(properties);

        if (clientDescription != null) {
            properties.setProperty("client-description", clientDescription);
        } else {
            properties.setProperty("client-description", "Unspecified client");
        }

        return properties;
    }

    private static Properties setDefaultProperties() {
        var properties = new Properties();

        properties.setProperty("account", "");
        properties.setProperty("email", "");
        properties.setProperty("password", "");

        var envUrl = System.getenv("FM_API_URL");
        properties.setProperty("endpoint", envUrl != null ? envUrl : Endpoints.DEFAULT_HOST);

        for (var file : List.of("credential", "endpoint")) {
            var filePath = Path.of(System.getProperty("user.home"), ".fm", file);
            loadConfiguration(properties, filePath);
        }

        return properties;
    }

    private static void loadCredential(Properties properties, String credential) {
        var credentialPath = Path.of(credential);

        properties.setProperty("account", "");
        properties.setProperty("email", "");
        properties.setProperty("password", "");

        if (Files.isRegularFile(credentialPath)) {
            loadConfiguration(properties, credentialPath);
        } else if (isValidToken(credential)) {
            properties.setProperty("token", credential);
        } else {
            throw new IllegalArgumentException("Invalid credential: '%s' is not a file or token.".formatted(credential));
        }
    }

    private static void loadEndpoint(Properties properties, String endpoint) {
        // A bare marketplace id (e.g. "2540") resolves to that marketplace on the
        // default production host. Development environments give a full URL when
        // localhost is wanted. Checked before the URL branch: a bare number is a
        // valid relative URI, so isValidUrl would otherwise swallow it.
        if (isMarketplaceId(endpoint)) {
            properties.setProperty("endpoint", marketplaceEndpoint(endpoint));
            return;
        }

        var endpointPath = Path.of(endpoint);

        if (Files.isRegularFile(endpointPath)) {
            loadConfiguration(properties, endpointPath);
        } else if (isValidUrl(endpoint)) {
            properties.setProperty("endpoint", endpoint);
        } else {
            throw new IllegalArgumentException("Invalid endpoint: '%s' is not a marketplace id, file, or URL.".formatted(endpoint));
        }
    }

    private static boolean isMarketplaceId(String endpoint) {
        return endpoint != null && endpoint.matches("\\d+");
    }

    /** Expand an endpoint that is still a bare marketplace id, wherever it came from. */
    private static void expandBareMarketplaceId(Properties properties) {
        var endpoint = properties.getProperty("endpoint");
        if (endpoint != null && isMarketplaceId(endpoint.trim())) {
            properties.setProperty("endpoint", marketplaceEndpoint(endpoint.trim()));
        }
    }

    /** A bare marketplace id resolves to that marketplace on the default production host. */
    private static String marketplaceEndpoint(String marketplaceId) {
        return Endpoints.DEFAULT_HOST + "/api/marketplaces/" + marketplaceId;
    }

    private static void loadConfiguration(Properties properties, Path filePath) {
        try (var input = Files.newInputStream(filePath)) {
            properties.load(input);
        } catch (IOException ignored) {}
    }

    private static boolean isValidUrl(String url) {
        try {
            new URI(url);
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean isValidToken(String token) {
        return token != null && !token.isBlank()
            && (token.matches("^\\$2[abxy]?\\$\\d{2}\\$[./A-Za-z0-9]{53}$")
                || token.matches("^[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+$"));
    }
}
