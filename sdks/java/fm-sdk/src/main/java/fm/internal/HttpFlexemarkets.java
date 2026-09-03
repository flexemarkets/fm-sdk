package fm.internal;

import fm.role.Reading;
import fm.model.Account;
import fm.model.Allotment;
import fm.model.Assets;
import fm.model.ClientConnection;
import fm.model.ConflictFailure;
import fm.model.Holding;
import fm.model.ManagerOtpBundle;
import fm.model.Market;
import fm.model.Marketplace;
import fm.model.Order;
import fm.model.OrderSide;
import fm.model.OrderType;
import fm.model.Person;
import fm.model.Session;
import fm.model.TickGrid;
import fm.model.Token;
import fm.Flexemarkets;
import fm.MarketView;
import fm.Snapshot;
import fm.Subscription;
import fm.error.AccountNameConflictException;
import fm.Endpoints;
import fm.error.ApiException;
import fm.error.AuthenticationException;
import fm.error.AuthorizationException;
import fm.error.ConfigurationException;
import fm.error.ConflictException;
import fm.error.ConnectionFailedException;
import fm.error.FlexemarketsException;
import fm.error.HttpException;
import fm.error.InvalidArgumentException;
import fm.error.PersonHasMarketplaceDataException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;

import javax.net.ssl.SSLException;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;


/**
 * The HTTP implementation of {@link Flexemarkets}.
 *
 * <p>Internal, and public only by accident of having to be constructed from
 * {@code fm}. Nothing should import it: {@link Flexemarkets#connect} builds one
 * and answers the interface, which is the supported way to get a connection.
 */
public class HttpFlexemarkets implements Flexemarkets {

    /**
     * The HTTP connection, built from the arguments {@link Flexemarkets#connect}
     * takes.
     *
     * <p>A factory rather than a public constructor: this class is the
     * implementation, and the only thing outside {@code fm.internal} that
     * should be able to say is "make me one from these". Properties are its own
     * business.
     *
     * @param credential         a password, a token, or a path to a credential
     *                           file holding one
     * @param endpoint           the endpoint to connect to
     * @param clientDescription  how this client identifies itself
     * @param capture            whether to write each request and response to
     *                           stdout
     * @param impersonateAccount the account an administrator wishes to act as,
     *                           or null
     * @return an open connection, which the caller must close
     * @throws IOException if the connection cannot be established
     */
    public static Flexemarkets open(String credential, String endpoint,
                                    String clientDescription, boolean capture,
                                    String impersonateAccount) throws IOException {
        var properties = loadProperties(credential, endpoint, clientDescription);

        if (capture) {
            properties.setProperty("capture", "true");
        }
        if (null != impersonateAccount && !impersonateAccount.isBlank()) {
            properties.setProperty("impersonate-account", impersonateAccount);
        }

        return new HttpFlexemarkets(properties);
    }
    private static final String FM_SDK_CLIENT = "fm-sdk-java/0.1.0";

    // Jackson 3 mappers are immutable and built, not configured after the fact.
    // java.time support is in databind now, so there is no module to register.
    //
    // Package-private rather than private so WireFixturesTest reads the wire
    // with the mapper the SDK actually uses. A test that builds its own
    // lookalike passes while the real one is misconfigured.
    static final ObjectMapper MAPPER = JsonMapper.builder()
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
    private static final TypeReference<Approval>       APPROVAL_TYPE     = new TypeReference<>() {};
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

    /** How the endpoint argument is spelled, for a message that tells you what to change. */
    private static final String ENDPOINT_ARGUMENT = "-E/--endpoint";

    private static final java.util.regex.Pattern SERVER_TIMING_ST =
        java.util.regex.Pattern.compile("st=(\\d+)");

    private final Properties _properties;
    private final HttpClient _httpClient;
    private final String _bearerToken;
    private final Token _token;
    private final Account _account;
    private final Person _user;
    private final ApiRoot _apiRoot;

    private final String _impersonateAccount;
    private final boolean _capture;

    private Events _events;
    private volatile boolean _closed;

    HttpFlexemarkets(Properties properties) {
        this._properties = properties;
        // NORMAL, not the JDK's default of NEVER. The edge in front of
        // production answers plain HTTP with a 301 to the same host on HTTPS,
        // and a client that does not follow it reads the edge's HTML error page
        // as the response -- which then fails as a JSON parse error, nowhere
        // near the cause. NORMAL declines to follow HTTPS back down to HTTP, so
        // an endpoint cannot be quietly downgraded.
        this._httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        var impersonate = properties.getProperty("impersonate-account");
        this._impersonateAccount = impersonate == null || impersonate.isBlank() ? null : impersonate;
        this._capture = Boolean.parseBoolean(properties.getProperty("capture"));

        this._token = _signIn();
        this._account = _token.account();
        this._user = _token.person();
        this._bearerToken = "Bearer " + _token.token();

        this._apiRoot = _fetchApiRoot();
    }


    public Account account() { return _account; }
    public long accountId() { return _account.id(); }
    public String accountName() { return _account.name(); }
    public Person user() { return _user; }
    public long userId() { return _user.id(); }

    public String endpointUrl() {
        return _properties.getProperty("endpoint");
    }

    public long endpointMarketplaceId() {
        return resourceId(endpointUrl());
    }

    // --- REST APIs ---

    public List<Marketplace> marketplaces() {
        return _get(uriParam(_apiRoot, "marketplaces", "format=application/json"), MARKETPLACES_TYPE);
    }

    public Marketplace marketplace(long marketplaceId) {
        return _get(uriId(_apiRoot, "marketplaces", marketplaceId), MARKETPLACE_TYPE);
    }

    public List<Market> markets(long marketplaceId) {
        return _get(uriIdSegmentParam(_apiRoot, "marketplaces", marketplaceId, "markets", "format=application/json"), MARKETS_TYPE);
    }

    @Override
    public List<String> symbols(long marketplaceId) {
        return _get(uriIdSegment(_apiRoot, "marketplaces", marketplaceId, "symbols"), SYMBOLS_TYPE);
    }

    @Override
    public Token token() {
        return _token;
    }

    // isAdmin/isManager/hasRole are the interface's defaults: roles come from
    // the sign-in token, which this class exposes through user(), so there was
    // nothing here the default could not do.

    public List<Session> sessions(long marketplaceId) {
        return _get(_v1("/marketplaces/" + marketplaceId + "/sessions"), SESSIONS_TYPE);
    }

    public Session session(long marketplaceId) {
        return _get(uriIdSegment(_apiRoot, "marketplaces", marketplaceId, "currentSession"), SESSION_TYPE);
    }

    public List<Order> orders(long marketplaceId) {
        return _get(uriIdSegment(_apiRoot, "marketplaces", marketplaceId, "orders"), ORDERS_TYPE);
    }

    /**
     * The active-orders snapshot: every resting limit order on the
     * marketplace's current session, plus the {@code x-fm-as-of-seq}
     * sequence the snapshot was read at. Used by {@link MarketView}
     * for snapshot seeding — clients apply WS deltas whose
     * seq is greater than the returned value and skip those whose
     * seq is less than or equal.
     */
    public Snapshot<List<Order>> activeOrders(long marketplaceId) {
        var url = _v1("/marketplaces/" + marketplaceId + "/orders/active");
        return _unwrapOrders(_getSnapshot(url, SNAPSHOT_TYPE));
    }

    /**
     * The recent-trades snapshot, for seeding the trade-history tape.
     * Same {@code x-fm-as-of-seq} contract as
     * {@link #activeOrders(long)}.
     */
    public Snapshot<List<Order>> recentTrades(long marketplaceId, int size) {
        var url = server(endpointUrl()) + "/v1/marketplaces/" + marketplaceId
                + "/orders/recent-trades?size=" + size;
        return _unwrapOrders(_getSnapshot(url, SNAPSHOT_TYPE));
    }

    /**
     * The orders in a snapshot response, whatever shape it arrives in.
     *
     * <p>Three shapes, because the envelope has moved twice and both older ones
     * are still deployed: a bare array, which is what fm-server sends now;
     * {@code _embedded.orders}, the Spring HATEOAS CollectionModel; and
     * {@code _embedded.orderDtoes}, HATEOAS pluralising {@code OrderDto}.
     *
     * <p>Each move broke every SDK at once and neither was caught. The first
     * returned an empty list forever, so {@link fm.MarketView}'s books seeded
     * from live deltas instead and looked plausible. The second threw
     * {@code MismatchedInputException} binding an array to the envelope bean,
     * which took {@code observe()} down with it.
     *
     * <p>Reading the shape rather than assuming one is the fix that
     * generalises. Accepting both <em>names</em> was the fix last time, and it
     * did not survive the envelope itself being dropped.
     */
    static Snapshot<List<Order>> _unwrapOrders(Snapshot<JsonNode> raw) {
        JsonNode body = raw.body();
        JsonNode array = null;

        if (body != null) {
            if (body.isArray()) {
                array = body;
            } else {
                JsonNode embedded = body.path("_embedded");
                array = embedded.has("orders") ? embedded.path("orders")
                      : embedded.path("orderDtoes");
            }
        }

        List<Order> orders = (array == null || !array.isArray())
            ? List.of()
            : List.of(MAPPER.treeToValue(array, Order[].class));

        return new Snapshot<>(orders, raw.asOfSeq());
    }

    private static final TypeReference<JsonNode> SNAPSHOT_TYPE = new TypeReference<>() {};

    /** Sensible default — the server caps at 5000 and defaults to 1000. */
    public Snapshot<List<Order>> recentTrades(long marketplaceId) {
        return recentTrades(marketplaceId, 1000);
    }

    public List<Holding> holdings(long marketplaceId) {
        return _get(uriIdSegment(_apiRoot, "marketplaces", marketplaceId, "holdings"), HOLDINGS_TYPE);
    }

    /** Comma-separated ids, matching the server's {@code ?sessions=} filter. */
    @Override
    public List<Holding> holdings(long marketplaceId, List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return holdings(marketplaceId);
        }
        var ids = sessionIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return _get(uriIdSegmentParam(_apiRoot, "marketplaces", marketplaceId, "holdings", "sessions=" + ids),
                   HOLDINGS_TYPE);
    }

    public Holding holding(long marketplaceId) {
        return _get(uriIdSegment(_apiRoot, "marketplaces", marketplaceId, "currentHolding"), new TypeReference<>() {});
    }

    public List<ClientConnection> connections(long marketplaceId) {
        // Canonical path is /marketplaces/{id}/connections ("/agents" is the
        // retained pre-FM-4 alias); format=application/json yields a plain list
        // (vs the HAL _embedded form).
        return _get(uriIdSegmentParam(_apiRoot, "marketplaces", marketplaceId, "connections", "format=application/json"), CONNECTIONS_TYPE);
    }

    /** {@code sessions=} here, unlike the two routes above. */
    @Override
    public String downloadHoldings(long marketplaceId, List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return downloadHoldings(marketplaceId);
        }
        return _getText(uriIdSegmentParam(_apiRoot, "marketplaces", marketplaceId,
                        "holdings/downloads", "sessions=" + _ids(sessionIds)));
    }

    /**
     * {@code sessionOrdersJson}, not the marketplace's orders collection: that
     * one is current-session only.
     *
     * <p>{@code sessionIds=}, not {@code sessions=}. The server spells the
     * filter differently on this route than on the holdings download, and
     * using the wrong one is not an error -- it is an unfiltered answer.
     */
    @Override
    public List<Order> orders(long marketplaceId, List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return orders(marketplaceId);
        }
        var url = uriParam(_apiRoot, "sessionOrdersJson", "marketplaceId=" + marketplaceId)
                + "&sessionIds=" + _ids(sessionIds);
        return _get(url, ORDERS_TYPE);
    }

    /** The symbol is filled in; the ids are left alone. See {@link #trades}. */
    @Override
    public List<Order> orders(long marketplaceId, String symbol) {
        var url = uriParam(_apiRoot, "symbolOrdersJson", "marketplaceId=" + marketplaceId)
                + "&symbol=" + symbol;
        return _get(url, ORDERS_TYPE).stream()
                .map(o -> new Order(o.createdDate(), o.lastModifiedDate(), o.id(),
                                    o.original(), o.supplier(), o.consumer(), o.type(), o.side(),
                                    o.units(), o.price(), o.mine(), o.ownerId(), o.marketplaceId(),
                                    o.sessionId(), symbol, o.marketId(), o.ownerTarget(),
                                    o.clientDescription()))
                .toList();
    }

    /**
     * The symbol-keyed trades route answers with the trade id in
     * {@code original} and no symbol on the orders, because the query already
     * fixed the symbol. Both are filled in here so a caller gets trades rather
     * than half-populated orders -- fm-lib-net does the same, and a study that
     * groups by symbol or keys by id depends on it.
     */
    @Override
    public List<Order> trades(long marketplaceId, String symbol) {
        var url = uriParam(_apiRoot, "symbolTradesJson", "marketplaceId=" + marketplaceId)
                + "&symbol=" + symbol;
        return _get(url, ORDERS_TYPE).stream()
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
            return _post(uri(_apiRoot, "accounts"),
                        new SignUp(accountName, email, password, firstName, lastName),
                        TOKEN_TYPE);
        } catch (ConflictException e) {
            // A taken name, with the server's proposed alternative. Raised as
            // its own type so a caller can offer the suggestion rather than
            // parsing it back out of a generic conflict.
            var failure = e.failure();
            throw new AccountNameConflictException(
                    accountName, failure == null ? null : failure.suggestedName());
        }
    }

    @Override
    public Account approveAccount(String accountName) {
        var approval = _post(server(endpointUrl()) + "/approvals",
                            new ApproveAccount(accountName, true), APPROVAL_TYPE);
        return approval == null ? null : approval.account();
    }

    @Override
    public Account accountById(long accountId) {
        return _get(uriId(_apiRoot, "accounts", accountId), ACCOUNT_TYPE);
    }

    @Override
    public Person userById(long userId) {
        return _get(_v1("/users/" + userId), PERSON_TYPE);
    }

    @Override
    public List<String> identifiers(long marketplaceId) {
        return _get(uriIdSegment(_apiRoot, "marketplaces", marketplaceId, "privateTraders"), SYMBOLS_TYPE);
    }

    @Override
    public void deleteMyAccount() {
        _delete(server(endpointUrl()) + "/accounts/me");
    }

    @Override
    public List<Account> accounts() {
        return _get(uriParam(_apiRoot, "accounts", "format=application/json"), ACCOUNTS_TYPE);
    }

    @Override
    public void deleteAccount(long accountId) {
        _delete(uriId(_apiRoot, "accounts", accountId));
    }

    @Override
    public Person createUser(String email, String password, String firstName,
                             String lastName, String... roles) {
        return _post(_v1("/users"),
                    new CreateUser(email, password, firstName, lastName, roles),
                    PERSON_TYPE);
    }

    @Override
    public void deleteUser(long userId) {
        try {
            _delete(_v1("/users/" + userId));
        } catch (ConflictException e) {
            // The user still owns orders or allotments. Deleting them would
            // orphan it, so the server refuses and the caller has to decide
            // what happens to the data first.
            throw new PersonHasMarketplaceDataException(userId, e.getMessage());
        }
    }


    @Override
    public void deleteMarketplace(long marketplaceId) {
        _delete(uriId(_apiRoot, "marketplaces", marketplaceId));
    }

    /** Unit bounds are fixed at 1/100/1, as fm-lib-net sends them. */
    @Override
    public Market createMarket(long marketplaceId, String symbol, String name,
                               TickGrid price, TickGrid units, boolean privateMarket) {
        return _post(uriIdSegment(_apiRoot, "marketplaces", marketplaceId, "markets"),
                    new CreateMarket(symbol, name,
                                     price.minimum(), price.maximum(), price.tick(),
                                     units.minimum(), units.maximum(), units.tick(),
                                     privateMarket),
                    MARKET_TYPE);
    }

    @Override
    public ManagerOtpBundle managerOtpBundle(List<Long> userIds) {
        return _post(server(endpointUrl()) + "/otp/manager",
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


    private record CreateMarket(String symbol, String name,
                                long priceMinimum, long priceMaximum, long priceTick,
                                long unitMinimum, long unitMaximum, long unitTick,
                                boolean privateMarket) {}

    private record ManagerOtpRequest(List<Long> userIds) {}

    public Order submitLimit(long marketplaceId, long marketId, OrderSide side, long units, long price) {
        return submitLimit(marketplaceId, marketId, side, units, price, null);
    }

    public Order submitLimit(long marketplaceId, long marketId, OrderSide side, long units, long price,
                             Long ownerTargetId) {
        // A LinkedHashMap rather than Map.of: the target is absent for an
        // ordinary order, and Map.of rejects a null value rather than omitting
        // the key. Omitting it is the point -- an order carrying
        // "ownerTargetId": null is a private order with no target, which the
        // server refuses.
        var order = new LinkedHashMap<String, Object>();
        order.put("marketplaceId", marketplaceId);
        order.put("marketId",      marketId);
        order.put("type",          OrderType.LIMIT);
        order.put("side",          side);
        order.put("units",         units);
        order.put("price",         price);
        order.put("clientDescription", _clientDescription());
        if (null != ownerTargetId) {
            order.put("ownerTargetId", ownerTargetId);
        }
        return _post(uri(_apiRoot, "orders"), order, ORDER_TYPE);
    }

    public Order submitCancel(long marketplaceId, long marketId, long originalId) {
        var order = Map.of(
            "marketplaceId",    marketplaceId,
            "marketId",         marketId,
            "type",             OrderType.CANCEL,
            "id",               originalId,
            "original",         originalId,
            "supplier",         originalId,
            "clientDescription", _clientDescription()
        );
        return _post(uri(_apiRoot, "orders"), order, ORDER_TYPE);
    }

    public Order submitMarket(long marketplaceId, long marketId, OrderSide side, long units) {
        var limit = submitLimit(marketplaceId, marketId, side, units,
                                marketableLimit(_market(marketplaceId, marketId), side));

        // Unconditional, and safe when the order filled completely: the exchange
        // consumes a CANCEL by itself when no units remain (Exchange's javadoc
        // says so). Asking first would cost a round trip to learn something the
        // cancel handles anyway, and would race the book between the two calls.
        // It targets the submitted id because CANCEL identifies its target by
        // original id, which is what survives a split.
        try {
            submitCancel(marketplaceId, marketId, limit.id());
        } catch (FlexemarketsException e) {
            // The order is placed. Saying only "cancel failed" would invite a
            // caller to retry the whole thing and trade twice.
            throw new ApiException(
                    "Order " + limit.id() + " was placed but its remainder could not be"
                    + " cancelled; it may still be resting. Do not resubmit -- cancel it.", e);
        }

        return limit;
    }

    /** The market by id, from the marketplace's own list. */
    private Market _market(long marketplaceId, long marketId) {
        for (var market : markets(marketplaceId)) {
            if (marketId == market.id()) {
                return market;
            }
        }
        throw new ApiException(
                "Market " + marketId + " is not in marketplace " + marketplaceId);
    }

    /**
     * The most aggressive price this market will accept on {@code side}.
     *
     * <p>The server has no market order: {@code OrderDtoConverter}'s type switch
     * falls through to {@code LIMIT}, so every submission is bounds-checked
     * against the market and must sit on a tick. A buy therefore crosses the
     * book by bidding the highest legal price, and a sell by offering the
     * lowest.
     *
     * <p>Ticks are anchored at {@code priceMinimum}, not at zero -- the server
     * tests {@code (price - priceMinimum) % priceTick} -- so the top of the
     * range is only legal when the range is a whole number of ticks. The
     * highest legal price is the last tick at or below {@code priceMaximum}.
     * A tick of zero marks a fixed dimension, where the two bounds are equal
     * and there is one legal price.
     */
    static long marketableLimit(Market market, OrderSide side) {
        // The highest legal price is what priceRound gives for the ceiling, so
        // this is the grid rule rather than a fourth copy of it.
        return OrderSide.BUY == side ? market.priceRound(market.priceMaximum()) : market.priceMinimum();
    }

    // --- management ---------------------------------------------------------

    @Override
    public Session openSession(long marketplaceId) {
        return _patch(uriIdSegment(_apiRoot, "marketplaces", marketplaceId, "open"), SESSION_TYPE);
    }

    @Override
    public Session pauseSession(long marketplaceId) {
        return _patch(uriIdSegment(_apiRoot, "marketplaces", marketplaceId, "pause"), SESSION_TYPE);
    }

    @Override
    public Session closeSession(long marketplaceId) {
        return _patch(uriIdSegment(_apiRoot, "marketplaces", marketplaceId, "close"), SESSION_TYPE);
    }

    /** {@code usersJson} rather than {@code users}: the latter is the HAL form. */
    @Override
    public List<Person> users() {
        return _get(uri(_apiRoot, "usersJson"), PERSONS_TYPE);
    }

    /** Not on the API root -- allotments are a V1 route, addressed from the server. */
    @Override
    public List<Allotment> allotments(long marketplaceId, long allocationId) {
        var url = server(endpointUrl()) + "/v1/marketplaces/" + marketplaceId
                + "/allotments?allocation=" + allocationId;
        return List.copyOf(_get(url, ALLOTMENTS_TYPE));
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
        return _post(_v1("/marketplaces"), definition, MARKETPLACE_TYPE);
    }

    @Override
    public List<Holding> allocate(long marketplaceId, List<Holding> holdings) {
        var allotments = holdings.stream().map(h -> _toAllotment(marketplaceId, h)).toList();
        return _toHoldings(_post(
                uriIdSegment(_apiRoot, "marketplaces", marketplaceId, "allocations"),
                allotments, ALLOTMENTS_TYPE));
    }

    @Override
    public String downloadHoldings(long marketplaceId) {
        return _getText(uriIdSegment(_apiRoot, "marketplaces", marketplaceId, "holdings/downloads"));
    }

    @Override
    public List<Holding> uploadHoldings(long marketplaceId, Path csv) {
        return _toHoldings(_postMultipart(
                uriIdSegment(_apiRoot, "marketplaces", marketplaceId, "holdings/uploads"),
                "file", csv, ALLOTMENTS_TYPE));
    }

    /*
     * Allotment <-> Holding. The allocation endpoints speak allotments; callers
     * hold holdings. Converting here keeps that asymmetry out of every caller,
     * which is the whole reason allocate() takes holdings.
     */

    private static Allotment _toAllotment(long marketplaceId, Holding holding) {
        var assets = new Assets(null, holding.name(), holding.cash(), holding.securities());
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
        _events = new Events(_wsUrl(), _bearerToken, marketplaceId, _clientDescription(), MAPPER, queue);
        _events.connect();
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
        var ev = new Events(_wsUrl(), _bearerToken, marketplaceId, _clientDescription(), MAPPER, queue);
        ev.connect();
        return ev;
    }

    private final java.util.Map<Long, SharedMarketView> _sharedViews = new java.util.HashMap<>();
    private final Object _viewLock = new Object();

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
        synchronized (_viewLock) {
            SharedMarketView entry = _sharedViews.get(marketplaceId);
            if (entry == null) {
                // Hold the lock while constructing — observe() should
                // be a cold-path operation, and we'd rather block
                // duplicate observers than race two parallel WS
                // subscriptions into existence. The DefaultMarketView
                // constructor itself blocks on REST snapshots, so a
                // dozen-ms first call is acceptable.
                shared = new DefaultMarketView(this, marketplaceId, markets(marketplaceId));
                entry = new SharedMarketView(shared);
                _sharedViews.put(marketplaceId, entry);
            }
            entry.refCount++;
            shared = entry.view;
        }
        return new MarketViewHandle(shared, () -> _releaseSharedView(marketplaceId));
    }

    void _releaseSharedView(long marketplaceId) {
        DefaultMarketView toClose = null;
        synchronized (_viewLock) {
            SharedMarketView entry = _sharedViews.get(marketplaceId);
            if (entry == null) return;
            if (--entry.refCount <= 0) {
                _sharedViews.remove(marketplaceId);
                toClose = entry.view;
            }
        }
        if (toClose != null) toClose.close();
    }

    public void reconnect() throws InterruptedException {
        if (_events != null) {
            _events.reconnect();
        }
    }

    @Override
    public void close() {
        if (_closed) return;
        _closed = true;
        if (_events != null) {
            _events.close();
        }
        // Force-close any remaining shared MarketViews. Well-behaved
        // callers close their handles first; this is the safety net.
        java.util.List<DefaultMarketView> toClose;
        synchronized (_viewLock) {
            toClose = new java.util.ArrayList<>(_sharedViews.size());
            for (var entry : _sharedViews.values()) toClose.add(entry.view);
            _sharedViews.clear();
        }
        for (var v : toClose) {
            try { v.close(); } catch (Throwable ignored) { /* best-effort */ }
        }
        _httpClient.close();
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
    private HttpRequest.Builder _request(String url, String accept) {
        var builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", _bearerToken)
            .header("Accept", accept)
            .header("User-Agent", FM_SDK_CLIENT);

        if (_impersonateAccount != null) {
            builder.header(HEADER_IMPERSONATION, _impersonateAccount);
        }

        // What the previous call cost, carried on this one. A round trip is not
        // known until it has finished, and by then the request that would have
        // reported it has gone -- so each measurement arrives one call late,
        // which for a robot on an interval is a lag of one tick.
        //
        // Taken rather than read, so a figure is reported once. Sending the
        // same measurement on every subsequent request would weight a single
        // slow call by however many quiet ones followed it.
        var timing = _lastTiming.getAndSet(null);

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
    private HttpResponse<String> _exchange(HttpRequest request) throws IOException, InterruptedException {
        var started = System.nanoTime();
        var response = _dispatch(request);

        // Only a completed call is a measurement. A request that threw took an
        // unknown amount of an unknown thing -- a refused connection is not a
        // slow network -- so nothing is recorded and the next request simply
        // carries no header.
        _recordTiming(System.nanoTime() - started, response);

        return response;
    }

    /**
     * How long the last call took, waiting to be told to whoever asks next.
     *
     * <p>Held rather than sent immediately because there is nowhere to put it:
     * the response carrying the answer has already been written by the time the
     * answer exists.
     */
    private final java.util.concurrent.atomic.AtomicReference<Timing> _lastTiming =
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
    private void _recordTiming(long roundTripNanos, HttpResponse<String> response) {
        var serverNanos = response.headers().firstValue(HEADER_SERVER_TIMING)
            .map(HttpFlexemarkets::_serviceNanos)
            .orElse(-1L);

        var networkNanos = serverNanos < 0 ? -1L : Math.max(0, roundTripNanos - serverNanos);

        _lastTiming.set(new Timing(roundTripNanos, networkNanos));
    }

    /** The {@code st=} field of a Server-Timing header, or -1 if it has none. */
    private static long _serviceNanos(String header) {
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

    private HttpResponse<String> _dispatch(HttpRequest request) throws IOException, InterruptedException {
        if (!_capture) {
            return _httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }

        var out = System.out;
        out.printf("> %s %s%n", request.method(), request.uri());
        _printCapturedHeaders(out, ">", request.headers().map());
        out.println(">");

        var response = _httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        out.printf("< %s%n", response.statusCode());
        _printCapturedHeaders(out, "<", response.headers().map());
        if (_isCredentialRoute(request)) {
            out.printf("<%n[body withheld: credential document]%n");
        } else if (response.body() != null && !response.body().isEmpty()) {
            out.printf("<%n%s%n", response.body());
        }
        out.println("<");
        out.flush();

        return response;
    }

    /** Routes whose body is a credential rather than a document about one. */
    private static boolean _isCredentialRoute(HttpRequest request) {
        var path = request.uri().getPath();

        return path.endsWith("/tokens") || path.endsWith("/refresh") || path.contains("/otp");
    }

    private static void _printCapturedHeaders(java.io.PrintStream out, String prefix,
                                             Map<String, List<String>> headers) {
        headers.keySet().stream().sorted().forEach(name -> {
            var value = "authorization".equalsIgnoreCase(name)
                    ? "[redacted]"
                    : headers.get(name).toString();
            out.printf("%s %s: %s%n", prefix, name, value);
        });
    }

    private <T> T _get(String url, TypeReference<T> type) {
        var request = _request(url, "application/json")
            .GET()
            .build();
        return _send(request, type);
    }

    /**
     * GET helper that returns the parsed body bundled with the
     * {@code x-fm-as-of-seq} response header so callers (notably
     * {@link MarketView}) can correlate the snapshot with the WS
     * delta stream. Returns {@link Snapshot#NO_SEQ} when the header
     * is absent.
     */
    private <T> Snapshot<T> _getSnapshot(String url, TypeReference<T> type) {
        var request = _request(url, "application/json")
            .GET()
            .build();
        try {
            var response = _exchange(request);
            var statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                T body = MAPPER.readValue(response.body(), type);
                long asOfSeq = response.headers().firstValue("x-fm-as-of-seq")
                        .map(Long::parseLong)
                        .orElse(Snapshot.NO_SEQ);
                return new Snapshot<>(body, asOfSeq);
            }
            throw _failureFor(statusCode, response.body());
        } catch (FlexemarketsException e) {
            throw e;
        } catch (IOException e) {
            throw transportFailure("Snapshot request failed", request, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Snapshot request interrupted", e);
        }
    }

    private <T> T _post(String url, Object body, TypeReference<T> type) {
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

        var request = _request(url, "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        return _send(request, type);
    }

    /**
     * PATCH with no body -- the shape every session transition takes
     * ({@code /open}, {@code /pause}, {@code /close}): the verb and the path
     * carry the whole request.
     */
    private <T> T _patch(String url, TypeReference<T> type) {
        var request = _request(url, "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.noBody())
            .build();
        return _send(request, type);
    }

    /** DELETE, whose answer is a status and nothing worth parsing. */
    private void _delete(String url) {
        var request = _request(url, "application/json")
            .DELETE()
            .build();
        _sendDiscardingBody(request);
    }

    /**
     * GET returning the body verbatim, for endpoints that answer with something
     * other than JSON. The holdings download is a CSV, and parsing it as JSON
     * would fail on the first line.
     */
    private String _getText(String url) {
        var request = _request(url, "text/csv, */*")
            .GET()
            .build();
        try {
            var response = _exchange(request);
            var statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                return response.body();
            }
            throw _failureFor(statusCode, response.body());
        } catch (FlexemarketsException e) {
            throw e;
        } catch (IOException e) {
            throw transportFailure("HTTP request failed", request, e);
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
    private <T> T _postMultipart(String url, String partName, Path file, TypeReference<T> type) {
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

            var request = _request(url, "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
            return _send(request, type);
        } catch (IOException e) {
            throw new ApiException("Failed to read " + file, e);
        }
    }

    private <T> T _send(HttpRequest request, TypeReference<T> type) {
        try {
            var response = _exchange(request);
            var statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 300) {
                return MAPPER.readValue(response.body(), type);
            }

            throw _failureFor(statusCode, response.body());
        } catch (FlexemarketsException e) {
            throw e;
        } catch (JacksonException e) {
            // The call succeeded and its answer is unreadable, which is a
            // different fault from the call failing and worth naming: it means
            // this client and the server disagree about a type. Unwrapped, it
            // surfaced as a bare Jackson exception naming a field, with nothing
            // to say which SDK call had produced it.
            throw new ApiException("Failed to parse the response body", e);
        } catch (IOException e) {
            throw transportFailure("HTTP request failed", request, e);
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
    private void _sendDiscardingBody(HttpRequest request) {
        try {
            var response = _exchange(request);
            var statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 300) {
                return;
            }

            throw _failureFor(statusCode, response.body());
        } catch (FlexemarketsException e) {
            throw e;
        } catch (IOException e) {
            throw transportFailure("HTTP request failed", request, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("HTTP request interrupted", e);
        }
    }

    private Token _signIn() {
        var endpoint = server(endpointUrl()) + "/tokens";
        var account = _properties.getProperty("account");
        var email = _properties.getProperty("email");
        var password = _properties.getProperty("password");
        var tokenValue = _properties.getProperty("token");

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
            var response = _exchange(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw _failureFor(response.statusCode(), response.body());
            }
            return MAPPER.readValue(response.body(), TOKEN_TYPE);
        } catch (FlexemarketsException e) {
            throw e;
        } catch (IOException e) {
            throw transportFailure("Sign-in request failed", request, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Sign-in request interrupted", e);
        }
    }

    private ApiRoot _fetchApiRoot() {
        var url = server(endpointUrl());
        var request = _request(url, "application/json")
            .GET()
            .build();
        return rebase(_send(request, API_ROOT_TYPE), url);
    }

    /**
     * Point the API root's links back at the host that was dialled.
     *
     * <p>The server builds these hrefs from the request it believes it
     * received, and behind an edge that belief can be wrong: production on
     * {@code api.adhocmarkets.com} answers {@code GET /api} with links spelled
     * {@code http://}, while the same application on
     * {@code api.flexemarkets.com} spells them {@code https://}. Every call
     * that goes through a link — which is most of them — then leaves on plain
     * HTTP and meets the edge's 301. A GET survives it. A POST does not: the
     * JDK follows a 301 by re-sending as GET with the body dropped, so placing
     * an order or opening a session fails as a 401 with no order placed and
     * nothing pointing at the scheme.
     *
     * <p>Only the origin is replaced. The path, query and any URI template are
     * the server's to choose; where it is reachable is not, and the token in
     * hand was issued by the origin dialled, not by whatever the links name.
     */
    static ApiRoot rebase(ApiRoot apiRoot, String endpoint) {
        if (null == apiRoot || null == apiRoot.links()) {
            return apiRoot;
        }

        String origin = _httpOrigin(endpoint);
        if (null == origin) {
            return apiRoot;
        }

        var rebased = new LinkedHashMap<String, ApiRoot.LinkObject>();
        var moved = new LinkedHashSet<String>();

        apiRoot.links().forEach((name, link) -> {
            if (null == link) {
                rebased.put(name, null);
                return;
            }

            String named = _httpOrigin(link.href());
            if (null != named && !named.equals(origin)) {
                moved.add(named);
            }
            rebased.put(name, new ApiRoot.LinkObject(rebase(link.href(), origin)));
        });

        if (!moved.isEmpty()) {
            // Said out loud, because the rewrite would otherwise hide a
            // deployment that is genuinely misconfigured -- and a silent
            // correction here is how it stays misconfigured. The SDK keeps
            // working; the operator still gets told where to look.
            System.err.println("[fm-sdk] The API root names " + String.join(", ", moved)
                + " but this client dialled " + origin + "; rewriting " + moved.size()
                + " link origin(s) to match.");
            System.err.println("[fm-sdk] The server is behind a proxy that is not forwarding the"
                + " request scheme, so its links are wrong. Fix it at the edge -- this rewrite only"
                + " keeps calls working.");
        }

        return new ApiRoot(Collections.unmodifiableMap(rebased));
    }

    /**
     * An absolute HTTP href moved to {@code origin}; anything else left alone.
     *
     * <p>A relative href already resolves against the origin it was fetched
     * from, and a non-HTTP one is not ours to rewrite. Neither is the shape
     * this exists to correct.
     */
    private static String rebase(String href, String origin) {
        String named = _httpOrigin(href);
        return null == named || named.equals(origin) ? href : origin + href.substring(named.length());
    }

    /**
     * The {@code scheme://host:port} of an absolute {@code http}/{@code https}
     * URL, or null for anything else — a relative href, or a scheme the SDK has
     * no business rewriting.
     */
    private static String _httpOrigin(String url) {
        if (null == url) {
            return null;
        }

        int end = url.indexOf("://");
        if (end < 0) {
            return null;
        }

        String scheme = url.substring(0, end);
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            return null;
        }

        int pathStart = url.indexOf('/', end + 3);
        return pathStart < 0 ? url : url.substring(0, pathStart);
    }

    private String _clientDescription() {
        return _properties.getProperty("client-description", "Unspecified client");
    }

    private String _wsUrl() {
        return server(endpointUrl()).replaceFirst("http", "ws") + "/events";
    }

    // --- HATEOAS URI builders ---

    /**
     * A V1 route, addressed from the server rather than through a HAL link.
     *
     * <p>V1 is flat and versioned: the path is knowable without fetching the
     * API root first, which is the point of it. Every call that moves here
     * loses a HAL dependency as well as a version.
     */
    private String _v1(String path) {
        return server(endpointUrl()) + "/v1" + path;
    }

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
        int idx = pathStart < 0 ? -1 : endpoint.indexOf("/api", pathStart);

        if (idx >= 0) {
            return endpoint.substring(0, idx + 4);
        }

        // An endpoint naming only a server gets the API root appended rather
        // than being handed back as it stands. It used to be returned
        // unchanged, and every URL built from it was then a segment short: the
        // sign-in POSTed to <host>/tokens, which the server answers 404 "No
        // static resource tokens". DEFAULT_HOST is exactly this shape, so a
        // machine with no endpoint configured -- no -E, no FM_API_URL, no
        // ~/.fm/endpoint -- failed that way on every command, which made an
        // endpoint look mandatory when it is not.
        //
        // DEFAULT_HOST stays path-less deliberately: marketplaceUrl appends
        // "/api/marketplaces/<id>" to it, so the "/api" cannot live there.
        return endpoint.endsWith("/") ? endpoint + "api" : endpoint + "/api";
    }

    static long resourceId(String endpoint) {
        if (endpoint == null) throw new NullPointerException("Endpoint is null.");
        var segments = endpoint.split("/");
        return Long.parseLong(segments[segments.length - 1]);
    }


    /**
     * The exception an exchange that never completed deserves.
     *
     * <p>Named for the transport, not for whichever call happened to be
     * first. A stale {@code ~/.fm/endpoint} pointing at a local server that
     * was not running reported "Sign-in request failed" -- which reads as a
     * rejected password, and sent the reader to their credential. Nothing was
     * wrong with the credential, and nothing in the message named the address
     * actually dialled or said where that address had come from.
     *
     * <p>So a server that could not be reached says so, in those terms. Any
     * other {@code IOException} did happen against a server that answered, and
     * keeps the caller's wording -- with the underlying message appended,
     * which the caller's wording alone never carried.
     */
    private FlexemarketsException transportFailure(String what, HttpRequest request, IOException e) {
        var reason = unreachableReason(e);

        if (null == reason) {
            return new ApiException(what + ": " + e, e);
        }

        var source = _properties.getProperty("endpoint-source");

        return new ApiException(
            "Cannot reach the server at %s (%s).%s".formatted(
                origin(request.uri()),
                reason,
                null == source ? "" : " That address came from " + source + "."),
            e);
    }

    /**
     * Why the server could not be reached, or null if it was.
     *
     * <p>The JDK's connect failures carry no message at all -- every exception
     * in the chain answers null -- so the phrasing has to come from the types.
     * The chain is worth walking for one distinction in particular: a name
     * that does not resolve and a host that will not answer both surface as
     * {@code ConnectException}, and only the root cause tells them apart. It
     * is the difference between a misspelled endpoint and a server that is not
     * running.
     *
     * <p>What is left stays deliberately vague. {@code ConnectException} is
     * refused connections in practice, but it is not only refused connections,
     * and a message that guesses wrong here is the very thing this replaced.
     */
    private static String unreachableReason(IOException e) {
        if (unresolvedAddress(e)) {
            return "unknown host";
        }

        var phrase = switch (e) {
            case HttpConnectTimeoutException ignored -> "connection timed out";
            case ConnectException ignored            -> "refused or unreachable";
            case SSLException ignored                -> "TLS handshake failed";
            default                                  -> null;
        };

        if (null == phrase) {
            return null;
        }

        var detail = deepestMessage(e);
        return null == detail ? phrase : phrase + ": " + detail;
    }

    /** Whether the failure is DNS: the host name never became an address. */
    private static boolean unresolvedAddress(Throwable t) {
        for (Throwable cause = t; null != cause; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException) {
                return true;
            }
        }
        return false;
    }

    /** The innermost cause that has something to say. */
    private static String deepestMessage(Throwable t) {
        String message = null;

        for (Throwable cause = t; null != cause; cause = cause.getCause()) {
            var candidate = cause.getMessage();
            if (null != candidate && !candidate.isBlank()) {
                message = candidate;
            }
        }

        return message;
    }

    /** Scheme and authority: where the request went, without the path. */
    private static String origin(URI uri) {
        return null == uri.getScheme() || null == uri.getRawAuthority()
                ? uri.toString()
                : uri.getScheme() + "://" + uri.getRawAuthority();
    }

    /**
     * The exception a non-2xx response deserves.
     *
     * <p>One place, because this mapping was written out four times and the
     * copies had drifted: two of them handled 409 and two did not, so whether a
     * conflict arrived as ConflictException or as a bare HttpException
     * depended on which method you called.
     *
     * <p>A status with a meaning a caller can act on gets its own type. What is
     * left is HttpException, carrying the status so a caller can still ask.
     */
    private static FlexemarketsException _failureFor(int statusCode, String body) {
        return switch (statusCode) {
            case 400 -> new InvalidArgumentException("Invalid request: " + _detail(body));
            case 401 -> new AuthenticationException("Authentication failed: " + _detail(body));
            case 403 -> new AuthorizationException("Not permitted: " + _detail(body));
            case 409 -> new ConflictException("Conflict: " + body, _tryParseConflict(body));
            default -> statusCode >= 500
                    ? new ConnectionFailedException("Server error " + statusCode + ": " + body)
                    : new HttpException(statusCode, body);
        };
    }

    /**
     * What the server said, rather than the envelope it said it in.
     *
     * <p>Failures arrive as {@code {"error","message","path","shortDigest","status"}}
     * and the message is the only part a caller can act on. Pasting the whole
     * document into the exception buried it: a study given an allotments file
     * naming people who are not in the account reported the rows and addresses
     * at fault -- genuinely useful -- inside six lines of JSON, so the sentence
     * that told you which rows to fix was the hardest part to find.
     *
     * <p>Falls back to the raw body, because a failure that does not parse is
     * exactly when the caller most needs to see what actually came back.
     */
    private static String _detail(String body) {
        if (body == null || body.isBlank()) {
            return "(no response body)";
        }
        try {
            ConflictFailure failure = MAPPER.readValue(body, CONFLICT_TYPE);
            return failure != null && failure.message() != null && !failure.message().isBlank()
                    ? failure.message()
                    : body;
        } catch (Exception e) {
            return body;
        }
    }

    private static ConflictFailure _tryParseConflict(String body) {
        try {
            return MAPPER.readValue(body, CONFLICT_TYPE);
        } catch (Exception e) {
            return null;
        }
    }

    // --- Credential loading ---

    /**
     * The connection's properties, from defaults overlaid with the credential
     * file and the arguments.
     *
     * @param credential        a password, a token, or a path to a credential
     *                          file holding one
     * @param endpoint          the endpoint to connect to
     * @param clientDescription how this client identifies itself
     * @return the resolved properties
     * @throws IOException if a named credential file cannot be read
     */
    public static Properties loadProperties(String credential, String endpoint, String clientDescription) throws IOException {
        var properties = _setDefaultProperties();

        if (credential != null) {
            _loadCredential(properties, credential);
        }

        if (endpoint != null) {
            _loadEndpoint(properties, endpoint);
        }

        // Last, because an endpoint arrives from three places and only one of
        // them used to expand a bare id: the argument. A file's contents are
        // loaded verbatim, so "endpoint=1234" in ~/.fm/endpoint -- read by
        // setDefaultProperties on every connection -- or in a file named by -E
        // reached the HTTP client as "1234". Normalising here covers all three,
        // and is a no-op on the form the argument path already produced.
        _expandBareMarketplaceId(properties);

        if (clientDescription != null) {
            properties.setProperty("client-description", clientDescription);
        } else {
            properties.setProperty("client-description", "Unspecified client");
        }

        return properties;
    }

    private static Properties _setDefaultProperties() {
        var properties = new Properties();

        properties.setProperty("account", "");
        properties.setProperty("email", "");
        properties.setProperty("password", "");

        var envUrl = System.getenv("FM_API_URL");
        properties.setProperty("endpoint", envUrl != null ? envUrl : Endpoints.DEFAULT_HOST);
        properties.setProperty("endpoint-source", envUrl != null ? "$FM_API_URL" : "the default host");

        for (var file : List.of("credential", "endpoint")) {
            var filePath = Path.of(System.getProperty("user.home"), ".fm", file);
            var before = properties.getProperty("endpoint");
            _loadConfiguration(properties, filePath);
            _noteEndpointSource(properties, before, _abbreviate(filePath));
        }

        return properties;
    }

    private static void _loadCredential(Properties properties, String credential) {
        var credentialPath = Path.of(credential);

        properties.setProperty("account", "");
        properties.setProperty("email", "");
        properties.setProperty("password", "");

        if (Files.isRegularFile(credentialPath)) {
            _loadConfiguration(properties, credentialPath);
        } else if (_isValidToken(credential)) {
            properties.setProperty("token", credential);
        } else {
            throw new IllegalArgumentException("Invalid credential: '%s' is not a file or token.".formatted(credential));
        }
    }

    private static void _loadEndpoint(Properties properties, String endpoint) {
        // A bare marketplace id (e.g. "2540") resolves to that marketplace on the
        // default production host. Development environments give a full URL when
        // localhost is wanted. Checked before the URL branch: a bare number is a
        // valid relative URI, so isValidUrl would otherwise swallow it.
        if (_isMarketplaceId(endpoint)) {
            properties.setProperty("endpoint", _marketplaceEndpoint(endpoint));
            properties.setProperty("endpoint-source", ENDPOINT_ARGUMENT);
            return;
        }

        var endpointPath = Path.of(endpoint);

        if (Files.isRegularFile(endpointPath)) {
            var before = properties.getProperty("endpoint");
            _loadConfiguration(properties, endpointPath);
            _noteEndpointSource(properties, before, _abbreviate(endpointPath));
        } else if (_isValidUrl(endpoint)) {
            properties.setProperty("endpoint", endpoint);
            properties.setProperty("endpoint-source", ENDPOINT_ARGUMENT);
        } else {
            throw new IllegalArgumentException("Invalid endpoint: '%s' is not a marketplace id, file, or URL.".formatted(endpoint));
        }
    }

    /**
     * Where the endpoint in hand came from, for a failure to name.
     *
     * <p>An endpoint arrives from four places -- the argument,
     * {@code $FM_API_URL}, {@code ~/.fm/endpoint}, {@code ~/.fm/credential} --
     * and which one won is exactly what someone staring at an address they did
     * not type needs to know. The files are read for their whole contents, so
     * whether one carried an endpoint at all is only visible as a change in
     * the value.
     */
    private static void _noteEndpointSource(Properties properties, String before, String source) {
        if (!Objects.equals(before, properties.getProperty("endpoint"))) {
            properties.setProperty("endpoint-source", source);
        }
    }

    /** A path under the home directory, written the way its owner would write it. */
    private static String _abbreviate(Path path) {
        var home = Path.of(System.getProperty("user.home"));
        return path.startsWith(home) ? "~/" + home.relativize(path) : path.toString();
    }

    private static boolean _isMarketplaceId(String endpoint) {
        return endpoint != null && endpoint.matches("\\d+");
    }

    /** Expand an endpoint that is still a bare marketplace id, wherever it came from. */
    private static void _expandBareMarketplaceId(Properties properties) {
        var endpoint = properties.getProperty("endpoint");
        if (endpoint != null && _isMarketplaceId(endpoint.trim())) {
            properties.setProperty("endpoint", _marketplaceEndpoint(endpoint.trim()));
        }
    }

    /** A bare marketplace id resolves to that marketplace on the default production host. */
    private static String _marketplaceEndpoint(String marketplaceId) {
        return Endpoints.DEFAULT_HOST + "/api/marketplaces/" + marketplaceId;
    }

    private static void _loadConfiguration(Properties properties, Path filePath) {
        try (var input = Files.newInputStream(filePath)) {
            properties.load(input);
        } catch (IOException ignored) {}
    }

    private static boolean _isValidUrl(String url) {
        try {
            new URI(url);
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean _isValidToken(String token) {
        return token != null && !token.isBlank()
            && (token.matches("^\\$2[abxy]?\\$\\d{2}\\$[./A-Za-z0-9]{53}$")
                || token.matches("^[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+$"));
    }
}
