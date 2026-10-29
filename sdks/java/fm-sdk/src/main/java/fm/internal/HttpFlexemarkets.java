package fm.internal;

import fm.role.Reading;
import fm.model.Account;
import fm.model.Allotment;
import fm.model.Assets;
import fm.model.CallIndicative;
import fm.model.ClientConnection;
import fm.model.ConflictFailure;
import fm.model.CurrentSession;
import fm.model.Holding;
import fm.model.Limit;
import fm.model.ManagerOtpBundle;
import fm.model.Market;
import fm.model.MarketCall;
import fm.model.Marketplace;
import fm.model.Order;
import fm.model.ParticipantState;
import fm.model.MarketplaceRobot;
import fm.model.RobotParameterConfig;
import fm.model.RobotParameterControl;
import fm.model.Widget;
import fm.model.WidgetPush;
import fm.model.OrderSide;
import fm.model.OrderType;
import fm.model.Person;
import fm.model.Session;
import fm.model.TickGrid;
import fm.model.Token;
import fm.Flexemarkets;
import fm.Desk;
import fm.Page;
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
import java.time.Duration;
import java.time.Instant;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

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
     * Where this client says what it could not keep to itself.
     *
     * <p>{@link System.Logger}, not a facade: this is a library published
     * to three registries, and imposing slf4j on every consumer to print
     * a misconfiguration warning is not a trade worth making. An
     * application with a LoggerFinder on the path gets these in its own
     * logs; one with none gets java.util.logging.
     */
    private static final System.Logger LOG = System.getLogger(HttpFlexemarkets.class.getName());

    /**
     * The HTTP connection, built from the arguments {@link Flexemarkets#connect}
     * takes.
     *
     * <p>A factory rather than a public constructor: this class is the
     * implementation, and the only thing outside {@code fm.internal} that
     * should be able to say is "make me one from these". Properties are its own
     * business.
     *
     * @param credential         a password, a token, {@code refresh:} and a refresh
     *                           token, or a path to a credential file holding one
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
        return open(credential, endpoint, clientDescription, capture, impersonateAccount, null);
    }

    /**
     * {@link #open(String, String, String, boolean, String)}, sending
     * {@code sendLimit} and {@code sendCancel} orders by {@code orderRoute}.
     *
     * @param credential         a password, a token, {@code refresh:} and a refresh
     *                           token, or a path to a credential file holding one
     * @param endpoint           the endpoint to connect to
     * @param clientDescription  how this client identifies itself
     * @param capture            whether to write each request and response to
     *                           stdout
     * @param impersonateAccount the account an administrator wishes to act as,
     *                           or null
     * @param orderRoute         how sent orders go; null for the socket
     * @return an open connection, which the caller must close
     * @throws IOException if the connection cannot be established
     */
    public static Flexemarkets open(String credential, String endpoint,
                                    String clientDescription, boolean capture,
                                    String impersonateAccount, fm.OrderRoute orderRoute) throws IOException {
        var properties = loadProperties(credential, endpoint, clientDescription);
        if (null != orderRoute) {
            properties.setProperty("order-route", orderRoute.name());
        }

        if (capture) {
            properties.setProperty("capture", "true");
        }
        if (null != impersonateAccount && !impersonateAccount.isBlank()) {
            properties.setProperty("impersonate-account", impersonateAccount);
        }

        return new HttpFlexemarkets(properties);
    }
    /**
     * The User-Agent, naming this SDK and the version built. fm-server keys
     * its endpoint metrics by it (fm-server#1012), cut to major.minor, to know
     * which clients still call a route before retiring it. It was a constant,
     * "fm-sdk-java/0.1.0", for every release from 0.1.0 to 0.3.3.
     */
    static final String FM_SDK_CLIENT = "fm-sdk-java/" + sdkVersion();

    /** The version Maven filtered into sdk-version.properties, or 0.0.0 if it is missing. */
    static String sdkVersion() {
        try (var in = HttpFlexemarkets.class.getResourceAsStream("sdk-version.properties")) {
            if (null == in) return "0.0.0";
            var properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", "").trim();
            return version.isEmpty() || version.startsWith("${") ? "0.0.0" : version;
        } catch (IOException e) {
            return "0.0.0";
        }
    }

    // Jackson 3 mappers are immutable and built, not configured after the fact.
    // java.time support is in databind now, so there is no module to register.
    //
    // Package-private rather than private so WireFixturesTest reads the wire
    // with the mapper the SDK actually uses. A test that builds its own
    // lookalike passes while the real one is misconfigured.
    static final ObjectMapper MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        // The server sends an explicit null for a primitive it has no value
        // for -- Market.marketplaceId, Session.allocationId, Order.sessionClosed
        // -- and the SDK reads those as the type's default, which is what the
        // wire has always meant.
        //
        // Stated rather than inherited, because the default moved underneath
        // us: Jackson 3.1.5 rejects a null primitive and 3.2.1 coerces it, so
        // the SDK parsed the same payload differently depending on which patch
        // release a consumer happened to resolve. It was pinned to 3.2.1 here
        // while fm-server inherits 3.1.5 from spring-boot-starter-parent, and
        // the difference stayed invisible only because fm-robots' shaded jars
        // were putting 3.2.1 on fm-server's classpath anyway.
        .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();

    private static final TypeReference<Token>               TOKEN_TYPE        = new TypeReference<>() {};
    private static final TypeReference<Renewed>             RENEWAL_TYPE      = new TypeReference<>() {};

    /**
     * Where a credential carries a refresh token: a {@code refresh-token=} line
     * in a credential file, or the credential {@code refresh:<token>}.
     */
    static final String REFRESH_TOKEN_PROPERTY = "refresh-token";
    private static final String REFRESH_TOKEN_PREFIX = "refresh:";
    private static final TypeReference<List<Marketplace>>    MARKETPLACES_TYPE = new TypeReference<>() {};
    private static final TypeReference<Marketplace>          MARKETPLACE_TYPE  = new TypeReference<>() {};
    private static final TypeReference<List<CurrentSession>> CURRENT_SESSIONS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Market>>         MARKETS_TYPE      = new TypeReference<>() {};
    private static final TypeReference<Market>               MARKET_TYPE       = new TypeReference<>() {};
    private static final TypeReference<MarketCall>           MARKET_CALL_TYPE  = new TypeReference<>() {};
    private static final TypeReference<CallIndicative>       INDICATIVE_TYPE   = new TypeReference<>() {};
    private static final TypeReference<List<MarketCall>>     MARKET_CALLS_TYPE = new TypeReference<>() {};
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
    private static final TypeReference<List<Person>>         PERSONS_TYPE      = new TypeReference<>() {};
    private static final TypeReference<List<Allotment>>      ALLOTMENTS_TYPE   = new TypeReference<>() {};
    private static final TypeReference<List<ParticipantState>> PARTICIPANT_STATES_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Widget>>         WIDGETS_TYPE      = new TypeReference<>() {};
    private static final TypeReference<List<MarketplaceRobot>> MARKETPLACE_ROBOTS_TYPE = new TypeReference<>() {};
    private static final TypeReference<RobotParameterConfig> ROBOT_PARAMETER_CONFIG_TYPE = new TypeReference<>() {};
    /** The {@code before} in a {@code Link} header's {@code rel="next"} target. */
    private static final Pattern NEXT_LINK = Pattern.compile("<[^>]*[?&]before=(\\d+)[^>]*>\\s*;\\s*rel=\"?next\"?");

    /**
     * Who the server should treat the caller as, rather than whoever the
     * bearer token names. Admin-only, and refused server-side otherwise.
     */
    private static final String HEADER_IMPERSONATION = "X-FM-Account";

    /** Names a create, so it can be sent again without making two (fm-server 4.17). */
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /**
     * The token fm-server asks for beside {@link #HEADER_IMPERSONATION}
     * (fm-server 4.7, SECURITY-VULNERABILITIES #3): issued to this
     * administrator for that account by {@code POST /api/v1/impersonation},
     * given their password again, and good for fifteen minutes. Without it
     * the server refuses the account header, however the caller signed in.
     */
    private static final String HEADER_IMPERSONATION_TOKEN = "X-FM-Impersonation";

    /** How long before it expires an impersonation token is asked for again, so none lapses in flight. */
    static final Duration IMPERSONATION_RENEW_BEFORE = Duration.ofSeconds(30);

    /** What {@code POST /api/v1/impersonation} answers. */
    private record ImpersonationGrant(String token, Long accountId, Instant expiresAt) {
    }

    private static final TypeReference<ImpersonationGrant> IMPERSONATION_GRANT_TYPE = new TypeReference<>() {};

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
    /**
     * How long to wait for a connection, and for the exchange that follows.
     *
     * <p>BOTH ARE SET BECAUSE NEITHER HAS A DEFAULT. {@code HttpClient}
     * waits forever to connect and {@code HttpRequest} waits forever for a
     * reply, so a connection that drops without the peer sending anything
     * -- a laptop changing networks, a VPN going down, a NAT forgetting the
     * flow -- leaves the call blocked with no exception to catch and
     * nothing to retry. The process stays alive doing nothing.
     *
     * <p>That is not hypothetical. On 2026-09-22 three study runs on two
     * laptops stopped that way within minutes of each other: no output, no
     * exception, the session left OPEN on the server, the JVM still
     * running. A Python client against the same server kept working, its
     * library having timeouts by default.
     *
     * <p>Whether it hangs or surfaces as "Connection reset" is the
     * operating system's choice, not ours -- Windows will sit on a dead
     * socket far longer than macOS -- which is exactly why it cannot be
     * left to the operating system.
     *
     * <p>Both are overridable for a caller on a slow link or fetching
     * something large: {@code connect-timeout-seconds} and
     * {@code request-timeout-seconds} in the credential properties. Zero
     * or negative restores the old behaviour of waiting forever, which is
     * a thing somebody may genuinely want while debugging and should not
     * have to patch the SDK to get.
     */
    /**
     * Thirty seconds, which is Reactor Netty's {@code CONNECT_TIMEOUT_MILLIS}
     * and therefore what a Spring {@code WebClient} uses. Matching it is
     * deliberate: this is the one of the two where Spring has a real
     * default, and an SDK that timed out connecting sooner than the stack
     * everything else here runs on would be surprising for no gain.
     */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Sixty seconds, and NOT what Spring uses, which is nothing.
     *
     * <p>Spring Boot sets no response timeout for WebClient and none for
     * RestClient either -- {@code spring.http.client.read-timeout} exists
     * and defaults to unset. Matching that would mean matching the defect:
     * a jar on Spring WebClient is exactly what sat forever on a dead
     * socket on 2026-09-22, while a Python client against the same server
     * carried on, its library having a timeout.
     *
     * <p>Sixty is chosen against what this SDK actually asks for. The
     * period loop's calls are small and answer in milliseconds; the
     * largest is a holdings upload for a full marketplace, which is
     * seconds. A minute is far outside normal and well inside "something
     * is wrong".
     */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final Duration _connectTimeout;
    private final Duration _requestTimeout;

    private final HttpClient _httpClient;
    /**
     * The sign-in this client presents, replaced whole when an expired one is
     * renewed (see {@link #_renewToken}); the bearer header is derived from
     * it, so {@link #token()} and every request agree on which token is current.
     */
    private volatile Token _token;
    /**
     * Whether this client can sign in again: it holds a password, or a refresh
     * token. A client given only an access token cannot.
     */
    private final boolean _canRenew;
    /**
     * The refresh token the next renewal presents, replaced by the one each
     * renewal hands back: fm-server takes each once, and one presented twice
     * ends every token renewed from it. Null for a client that has none.
     */
    private volatile String _refreshToken;
    private final Object _renewLock = new Object();
    private final Account _account;
    private final Person _user;

    private final String _impersonateAccount;
    /** The current impersonation token, renewed by {@link #_impersonationToken}; null when not impersonating. */
    private volatile ImpersonationGrant _impersonation;
    private final Object _impersonationLock = new Object();
    private final boolean _capture;
    /** How sendLimit and sendCancel send this client's orders, for its whole life. */
    private final fm.OrderRoute _orderRoute;

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
        this._connectTimeout = _timeout(properties, "connect-timeout-seconds", DEFAULT_CONNECT_TIMEOUT);
        this._requestTimeout = _timeout(properties, "request-timeout-seconds", DEFAULT_REQUEST_TIMEOUT);

        this._httpClient = HttpClient.newBuilder()
                .connectTimeout(_connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        var impersonate = properties.getProperty("impersonate-account");
        this._impersonateAccount = impersonate == null || impersonate.isBlank() ? null : impersonate;
        this._capture = Boolean.parseBoolean(properties.getProperty("capture"));
        this._orderRoute = fm.OrderRoute.valueOf(properties.getProperty("order-route", fm.OrderRoute.SOCKET.name()));

        var refreshToken = properties.getProperty(REFRESH_TOKEN_PROPERTY);
        this._refreshToken = _isBlank(refreshToken) ? null : refreshToken.trim();
        this._canRenew = this._refreshToken != null
                || (_isBlank(properties.getProperty("token"))
                    && !_isBlank(properties.getProperty("account"))
                    && !_isBlank(properties.getProperty("email"))
                    && !_isBlank(properties.getProperty("password")));
        if (_impersonateAccount != null) {
            // Only a password gets an impersonation token. Refused here, at
            // connect, rather than as a 403 on the first call that names the
            // account -- by which time a robot has started and a script has
            // half run.
            if (_isBlank(properties.getProperty("password"))) {
                throw new ConfigurationException("Acting as another account (" + _impersonateAccount
                        + ") needs the administrator's password: fm-server asks for it again to issue an"
                        + " impersonation token. Connect with a credential file holding account, email and"
                        + " password, not a token.");
            }
        }

        this._token = _signIn();
        this._account = _token.account();
        this._user = _token.person();

        if (_impersonateAccount != null) {
            this._impersonation = _grantImpersonation();
        }
    }

    /** The Authorization header for the current sign-in. */
    private String _bearer() {
        return "Bearer " + _token.token();
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
        return _get(_v1("/marketplaces"), MARKETPLACES_TYPE);
    }

    @Override
    public Page<Marketplace> marketplacesPage(int limit, Long before) {
        return _getPage(_v1("/marketplaces"), limit, before, MARKETPLACES_TYPE);
    }

    @Override
    public List<CurrentSession> currentSessions() {
        return _get(_v1("/marketplaces/current-sessions"), CURRENT_SESSIONS_TYPE);
    }

    @Override
    public Page<CurrentSession> currentSessionsPage(int limit, Long before) {
        return _getPage(_v1("/marketplaces/current-sessions"), limit, before, CURRENT_SESSIONS_TYPE);
    }

    public Marketplace marketplace(long marketplaceId) {
        return _get(_marketplace(marketplaceId), MARKETPLACE_TYPE);
    }

    public List<Market> markets(long marketplaceId) {
        return _get(_marketplace(marketplaceId) + "/markets", MARKETS_TYPE);
    }

    /** The markets' own symbols: V1 has no {@code /symbols}, which was this projection server-side. */
    @Override
    public List<String> symbols(long marketplaceId) {
        return markets(marketplaceId).stream().map(Market::symbol).toList();
    }

    @Override
    public CallIndicative indicative(long marketplaceId, long marketId) {
        return _get(_marketplace(marketplaceId) + "/markets/" + marketId + "/indicative", INDICATIVE_TYPE);
    }

    @Override
    public Token token() {
        return _token;
    }

    // isAdmin/isManager/hasRole are the interface's defaults: roles come from
    // the sign-in token, which this class exposes through user(), so there was
    // nothing here the default could not do.

    public List<Session> sessions(long marketplaceId) {
        return _get(_marketplace(marketplaceId) + "/sessions", SESSIONS_TYPE);
    }

    @Override
    public Page<Session> sessionsPage(long marketplaceId, int limit, Long before) {
        return _getPage(_marketplace(marketplaceId) + "/sessions", limit, before, SESSIONS_TYPE);
    }

    public Session session(long marketplaceId) {
        return _get(_marketplace(marketplaceId) + "/sessions/current", SESSION_TYPE);
    }

    /**
     * The current session's orders less every cancelled one, the CANCEL that
     * consumed it, and both legs of a self-cross -- what V0's marketplace
     * orders answered, and what a study ranking participants needs. Without
     * {@code cancelled=false} the V1 read is the raw lifecycle.
     */
    public List<Order> orders(long marketplaceId) {
        return _get(_marketplace(marketplaceId) + "/orders?cancelled=false", ORDERS_TYPE);
    }

    @Override
    public Page<Order> ordersPage(long marketplaceId, int limit, Long before) {
        return _getPage(_marketplace(marketplaceId) + "/orders?cancelled=false", limit, before, ORDERS_TYPE);
    }

    /**
     * The active-orders snapshot: every resting limit order on the
     * marketplace's current session, plus the {@code x-fm-as-of-seq}
     * sequence the snapshot was read at. Used by {@link Desk}
     * for snapshot seeding — clients apply WS deltas whose
     * seq is greater than the returned value and skip those whose
     * seq is less than or equal.
     */
    public Snapshot<List<Order>> activeOrders(long marketplaceId) {
        var url = _marketplace(marketplaceId) + "/orders?state=ACTIVE";
        return _ordersOf(_getSnapshot(url, SNAPSHOT_TYPE));
    }

    /**
     * The recent-trades snapshot, for seeding the trade-history tape.
     * Same {@code x-fm-as-of-seq} contract as
     * {@link #activeOrders(long)}.
     */
    public Snapshot<List<Order>> recentTrades(long marketplaceId, int size) {
        var url = _marketplace(marketplaceId) + "/orders?state=TRADED&limit=" + size;
        return _ordersOf(_getSnapshot(url, SNAPSHOT_TYPE));
    }

    /**
     * The orders in a snapshot answer, which is a bare JSON array or a failure.
     *
     * <p>Only the array is read because the V1 route never sent anything else:
     * {@code GET /api/v1/marketplaces/{id}/orders} has always answered a bare
     * array, and 0.4 requires fm-server 4.6.3. The HAL envelopes earlier SDKs
     * tolerated belonged to the routes 0.4 no longer calls.
     *
     * <p>Anything else throws rather than reading as empty. An empty snapshot
     * is the failure that hid twice before: {@link fm.Desk} seeded an empty
     * book, filled it from live deltas, and looked plausible.
     *
     * @throws ApiException when the answer is not a JSON array
     */
    static Snapshot<List<Order>> _ordersOf(Snapshot<JsonNode> raw) {
        JsonNode body = raw.body();
        if (body == null || !body.isArray()) {
            throw new ApiException("The orders snapshot answer was not a list of orders: got "
                    + (body == null || body.isMissingNode() ? "null"
                       : body.getNodeType().name().toLowerCase(Locale.ROOT)));
        }
        List<Order> orders = List.of(MAPPER.treeToValue(body, Order[].class));
        return new Snapshot<>(orders, raw.asOfSeq());
    }

    private static final TypeReference<JsonNode> SNAPSHOT_TYPE = new TypeReference<>() {};

    /** Sensible default — the server caps at 5000 and defaults to 1000. */
    public Snapshot<List<Order>> recentTrades(long marketplaceId) {
        return recentTrades(marketplaceId, 1000);
    }

    @Override
    public Snapshot<List<Order>> recentTrades(long marketplaceId, long marketId, int size) {
        var url = _marketplace(marketplaceId) + "/orders?state=TRADED&market=" + marketId + "&limit=" + size;
        return _ordersOf(_getSnapshot(url, SNAPSHOT_TYPE));
    }

    public List<Holding> holdings(long marketplaceId) {
        return _get(_marketplace(marketplaceId) + "/holdings", HOLDINGS_TYPE);
    }

    /** Comma-separated ids, matching the server's {@code ?sessions=} filter. */
    @Override
    public List<Holding> holdings(long marketplaceId, List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return holdings(marketplaceId);
        }
        return _get(_marketplace(marketplaceId) + "/holdings?sessions=" + _ids(sessionIds), HOLDINGS_TYPE);
    }

    public Holding holding(long marketplaceId) {
        return _get(_marketplace(marketplaceId) + "/participants/me/holding", new TypeReference<>() {});
    }

    public List<ClientConnection> connections(long marketplaceId) {
        return _get(_marketplace(marketplaceId) + "/connections", CONNECTIONS_TYPE);
    }

    @Override
    public Page<ClientConnection> connectionsPage(long marketplaceId, int limit, Long before) {
        return _getPage(_marketplace(marketplaceId) + "/connections", limit, before, CONNECTIONS_TYPE);
    }

    @Override
    public String downloadHoldings(long marketplaceId, List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return downloadHoldings(marketplaceId);
        }
        return _getText(_marketplace(marketplaceId) + "/holdings?sessions=" + _ids(sessionIds));
    }

    /**
     * Every LIMIT and CANCEL row of the named runs, as the exchange stored
     * them: the lifecycle read, a manager's.
     */
    @Override
    public List<Order> orders(long marketplaceId, List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return orders(marketplaceId);
        }
        return _get(_marketplace(marketplaceId) + "/orders?sessions=" + _ids(sessionIds), ORDERS_TYPE);
    }

    /** The symbol is filled in; the ids are left alone. See {@link #trades}. */
    @Override
    public List<Order> orders(long marketplaceId, String symbol) {
        var url = _marketplace(marketplaceId) + "/orders?state=ACTIVE&symbol=" + _query(symbol);
        return _get(url, ORDERS_TYPE).stream()
                .map(o -> new Order(o.createdDate(), o.lastModifiedDate(), o.id(),
                                    o.original(), o.supplier(), o.consumer(), o.type(), o.side(),
                                    o.units(), o.price(), o.mine(), o.ownerId(), o.marketplaceId(),
                                    o.sessionId(), symbol, o.marketId(), o.ownerTarget(),
                                    o.clientDescription(), o.ownerTargetId()))
                .toList();
    }

    /**
     * One market's traded legs, as many as the server gives
     * ({@link #MAX_TRADED_LEGS}). The answer carries the trade id in
     * {@code original} and no symbol on the orders, because the query already
     * fixed the symbol. Both are filled in here so a caller gets trades rather
     * than half-populated orders -- fm-lib-net does the same, and a study that
     * groups by symbol or keys by id depends on it.
     */
    @Override
    public List<Order> trades(long marketplaceId, String symbol) {
        var url = _marketplace(marketplaceId) + "/orders?state=TRADED&symbol=" + _query(symbol)
                + "&limit=" + MAX_TRADED_LEGS;
        return _get(url, ORDERS_TYPE).stream()
                .map(o -> new Order(o.createdDate(), o.lastModifiedDate(), o.original(),
                                    o.original(), o.supplier(), o.consumer(), o.type(), o.side(),
                                    o.units(), o.price(), o.mine(), o.ownerId(), o.marketplaceId(),
                                    o.sessionId(), symbol, o.marketId(), o.ownerTarget(),
                                    o.clientDescription(), o.ownerTargetId()))
                .toList();
    }

    /**
     * The most traded legs fm-server answers in one read. The route this
     * replaced had no limit; asking for the ceiling keeps as much of that as
     * the server allows.
     */
    static final int MAX_TRADED_LEGS = 5000;

    private static String _query(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
            return _post(_v1("/accounts"),
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

    /**
     * V1 approves an account by id; the name is looked up in the account list
     * first, so callers keep naming the account they signed up.
     */
    @Override
    public Account approveAccount(String accountName) {
        var account = accounts().stream()
                .filter(a -> accountName.equals(a.name()))
                .findFirst()
                .orElseThrow(() -> new InvalidArgumentException("No account named '" + accountName + "'"));
        var approval = _post(_v1("/accounts/" + account.id() + "/approvals"),
                            new ApproveAccount(true), APPROVAL_TYPE);
        return approval == null ? null : approval.account();
    }

    @Override
    public Account accountById(long accountId) {
        return _get(_v1("/accounts/" + accountId), ACCOUNT_TYPE);
    }

    @Override
    public Person userById(long userId) {
        return _get(_v1("/users/" + userId), PERSON_TYPE);
    }

    /** The names of the participants a caller may target: {@code GET participants}, names only. */
    @Override
    public List<String> identifiers(long marketplaceId) {
        return _get(_marketplace(marketplaceId) + "/participants", PARTICIPANTS_TYPE).stream()
                .map(ParticipantName::name)
                .toList();
    }

    @Override
    public void deleteMyAccount() {
        _delete(_v1("/accounts/me"));
    }

    @Override
    public List<Account> accounts() {
        return _get(_v1("/accounts"), ACCOUNTS_TYPE);
    }

    @Override
    public Page<Account> accountsPage(int limit, Long before) {
        return _getPage(_v1("/accounts"), limit, before, ACCOUNTS_TYPE);
    }

    @Override
    public void deleteAccount(long accountId) {
        _delete(_v1("/accounts/" + accountId));
    }

    @Override
    public Person createUser(String email, String password, String firstName,
                             String lastName, String... roles) {
        var user = new CreateUser(email, password, firstName, lastName, roles);
        return _create(key -> _post(_v1("/users"), user, PERSON_TYPE, key));
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
        _delete(_marketplace(marketplaceId));
    }

    @Override
    public Market createMarket(long marketplaceId, String symbol, String name,
                               TickGrid price, TickGrid units, boolean privateMarket) {
        return _createMarket(marketplaceId, new CreateMarket(symbol, name,
                                     price.minimum(), price.maximum(), price.tick(),
                                     units.minimum(), units.maximum(), units.tick(),
                                     privateMarket, null, null, null, null));
    }

    @Override
    public Market createCallMarket(long marketplaceId, String symbol, String name,
                                   TickGrid price, TickGrid units, Integer intervalSeconds, boolean carry,
                                   String callBook) {
        return _createMarket(marketplaceId, new CreateMarket(symbol, name,
                                     price.minimum(), price.maximum(), price.tick(),
                                     units.minimum(), units.maximum(), units.tick(),
                                     false, Market.MECHANISM_CALL, intervalSeconds, carry, callBook));
    }

    private Market _createMarket(long marketplaceId, CreateMarket market) {
        return _create(key -> _post(_marketplace(marketplaceId) + "/markets", market, MARKET_TYPE, key));
    }

    @Override
    public ManagerOtpBundle managerOtpBundle(List<Long> userIds) {
        return _post(_v1("/otp/manager"),
                    new ManagerOtpRequest(userIds), OTP_BUNDLE_TYPE);
    }

    /*
     * Request bodies. Records rather than maps so a field that the server
     * renamed fails to compile here rather than being silently dropped from
     * the JSON -- the shape of the grants/securities bug.
     */
    private record SignUp(String accountName, String ownerEmail, String ownerPassword,
                          String firstName, String lastName) {}

    private record ApproveAccount(Boolean approve) {}

    /** A row of {@code GET participants}: a name, and for a manager the user id, not needed here. */
    private record ParticipantName(String name) {}

    private static final TypeReference<List<ParticipantName>> PARTICIPANTS_TYPE = new TypeReference<>() {};

    private record CreateUser(String email, String password, String firstName,
                              String lastName, String[] roles) {}


    /** The call fields go only when set, so a continuous market's body is what it always was. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CreateMarket(String symbol, String name,
                                long priceMinimum, long priceMaximum, long priceTick,
                                long unitMinimum, long unitMaximum, long unitTick,
                                boolean privateMarket,
                                String mechanism, Integer callIntervalSeconds, Boolean callCarry,
                                String callBook) {}

    private record ManagerOtpRequest(List<Long> userIds) {}

    /** The value, with the control's bounds flat beside it; no bounds is a fixed parameter. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ConfigureRobotParameter(String value, BigDecimal min, BigDecimal max, BigDecimal step) {
        static ConfigureRobotParameter of(String value, RobotParameterControl control) {
            return null == control
                    ? new ConfigureRobotParameter(value, null, null, null)
                    : new ConfigureRobotParameter(value, control.min(), control.max(), control.step());
        }
    }

    public Order submitLimit(long marketplaceId, long marketId, OrderSide side, long units, long price) {
        return submitLimit(marketplaceId, marketId, side, units, price, null);
    }

    public Order submitLimit(long marketplaceId, long marketId, OrderSide side, long units, long price,
                             Long ownerTargetId) {
        return submitLimit(marketplaceId, marketId, side, units, price, null, ownerTargetId, null);
    }

    public Order submitLimit(long marketplaceId, long marketId, OrderSide side, long units, long price,
                             String ownerTarget, Long ownerTargetId, String clientDescription) {
        var order = _limitOrder(marketplaceId, marketId, side, units, price, ownerTarget, ownerTargetId,
                                clientDescription);
        var url = _marketplace(marketplaceId) + "/orders";
        try {
            return _post(url, order, ORDER_TYPE);
        } catch (ApiException lost) {
            if (!(lost.getCause() instanceof IOException)) {
                throw lost;
            }
            return _post(url, order, ORDER_TYPE);
        }
    }

    @Override
    public fm.PendingOrder sendLimit(long marketplaceId, long marketId, OrderSide side, long units, long price) {
        return sendLimit(marketplaceId, marketId, side, units, price, null, null, null);
    }

    @Override
    public fm.PendingOrder sendLimit(long marketplaceId, long marketId, OrderSide side, long units, long price,
                                     String ownerTarget, Long ownerTargetId, String clientDescription) {
        var order = _limitOrder(marketplaceId, marketId, side, units, price, ownerTarget, ownerTargetId,
                                clientDescription);
        return _send(marketplaceId, (String) order.get("submissionId"), order);
    }

    /** The order to the marketplace's socket, or to its REST window, by this client's route. */
    private fm.PendingOrder _send(long marketplaceId, String submissionId, LinkedHashMap<String, Object> order) {
        if (fm.OrderRoute.REST == _orderRoute) {
            return _restWindow(marketplaceId).submit(submissionId, order);
        }
        return _orderStream(marketplaceId).submit(submissionId, MAPPER.writeValueAsString(order));
    }

    /** The marketplace's REST window, made on first use. */
    RestOrderWindow _restWindow(long marketplaceId) {
        synchronized (_orderStreams) {
            if (_closed) {
                throw new fm.error.ConnectionFailedException("The client is closed; the order was not sent.");
            }
            return _restWindows.computeIfAbsent(marketplaceId, id -> {
                var url = _marketplace(id) + "/orders/batch";
                return new RestOrderWindow(batch -> _postBatch(url, batch), id);
            });
        }
    }

    /** Each marketplace's REST window, for a client connected with OrderRoute.REST; guarded by _orderStreams. */
    private final java.util.Map<Long, RestOrderWindow> _restWindows = new java.util.HashMap<>();

    @Override
    public fm.PendingOrder sendCancel(long marketplaceId, long marketId, long originalId) {
        var cancel = new LinkedHashMap<String, Object>();
        cancel.put("marketplaceId", marketplaceId);
        cancel.put("marketId",      marketId);
        cancel.put("type",          OrderType.CANCEL);
        cancel.put("original",      originalId);
        // Named, as a limit is: a cancel sent again after a reconnect is
        // answered with the one fm-server already recorded.
        var submissionId = UUID.randomUUID().toString();
        cancel.put("submissionId", submissionId);
        if (fm.OrderRoute.REST == _orderRoute) {
            // As a REST CANCEL has always named its order. The exchange matches
            // it by the original as given, which is why it must be one.
            cancel.put("id",       originalId);
            cancel.put("supplier", originalId);
        }
        return _send(marketplaceId, submissionId, cancel);
    }

    /** The most orders one {@code POST .../orders/batch} carries: fm-server's OrderBatchV1Controller.MOST_ORDERS. */
    static final int MOST_BATCHED = 64;

    @Override
    public List<fm.PendingOrder> submitLimits(long marketplaceId, List<Limit> limits) {
        var orders = new ArrayList<LinkedHashMap<String, Object>>(limits.size());
        for (Limit limit : limits) {
            orders.add(_limitOrder(marketplaceId, limit.marketId(), limit.side(), limit.units(), limit.price(),
                                   limit.ownerTarget(), limit.ownerTargetId(), limit.clientDescription()));
        }
        var url = _marketplace(marketplaceId) + "/orders/batch";
        var answered = new ArrayList<fm.PendingOrder>(orders.size());
        for (int start = 0; start < orders.size(); start += MOST_BATCHED) {
            var batch = orders.subList(start, Math.min(start + MOST_BATCHED, orders.size()));
            var outcomes = _postBatch(url, new ArrayList<Map<String, Object>>(batch));
            for (int i = 0; i < batch.size(); i++) {
                var outcome = outcomes.get(i);
                var pending = new fm.PendingOrder((String) batch.get(i).get("submissionId"));
                if (null != outcome.refusal()) {
                    pending.completeExceptionally(outcome.refusal());
                } else {
                    pending.complete(outcome.order());
                }
                answered.add(pending);
            }
        }
        return answered;
    }

    /**
     * One {@code POST .../orders/batch}, sent once more, whole, if its answer was
     * lost: each order's placed order, or the exception its refusal throws.
     */
    private List<RestOrderWindow.Outcome> _postBatch(String url, List<Map<String, Object>> batch) {
        List<BatchEntry> entries;
        try {
            entries = _post(url, batch, BATCH_TYPE);
        } catch (ApiException lost) {
            if (!(lost.getCause() instanceof IOException)) {
                throw lost;
            }
            entries = _post(url, batch, BATCH_TYPE);
        }
        if (null == entries || entries.size() != batch.size()) {
            throw new ApiException("Expected " + batch.size() + " answers to a batch of " + batch.size() + " orders");
        }
        var outcomes = new ArrayList<RestOrderWindow.Outcome>(entries.size());
        for (var entry : entries) {
            outcomes.add(entry.status() >= 200 && entry.status() < 300
                    ? new RestOrderWindow.Outcome(entry.order(), null)
                    : new RestOrderWindow.Outcome(null,
                            _failureFor(entry.status(), MAPPER.writeValueAsString(entry.failure()))));
        }
        return outcomes;
    }

    /** One order's answer in a batch: the status its own POST would have had, and its order or failure body. */
    private record BatchEntry(int status, Order order, JsonNode failure) {}

    private static final TypeReference<List<BatchEntry>> BATCH_TYPE = new TypeReference<>() {};

    /** The marketplace's order socket, opened on first use. */
    OrderStream _orderStream(long marketplaceId) {
        synchronized (_orderStreams) {
            if (_closed) {
                throw new fm.error.ConnectionFailedException("The client is closed; the order was not sent.");
            }
            return _orderStreams.computeIfAbsent(marketplaceId, id -> new OrderStream(
                    queue -> _connectEvents(id, queue, false, OrderStream.RECONNECT_MAX_MS), id,
                    OrderStream.MAX_IN_FLIGHT));
        }
    }

    /** The socket each marketplace's sendLimit orders go out on, opened by the first of them. */
    private final java.util.Map<Long, OrderStream> _orderStreams = new java.util.HashMap<>();

    /** The body of a limit order, named with a fresh submission id. */
    private LinkedHashMap<String, Object> _limitOrder(long marketplaceId, long marketId, OrderSide side, long units,
                                                      long price, String ownerTarget, Long ownerTargetId,
                                                      String clientDescription) {
        if (null != ownerTarget && null != ownerTargetId) {
            throw new InvalidArgumentException("Name the counterparty by ownerTarget or ownerTargetId, not both");
        }
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
        order.put("clientDescription", null != clientDescription && !clientDescription.isEmpty()
                ? clientDescription : _clientDescription());
        if (null != ownerTarget) {
            order.put("ownerTarget", ownerTarget);
        }
        if (null != ownerTargetId) {
            order.put("ownerTargetId", ownerTargetId);
        }
        // Named, so an order whose answer was lost can be sent again without
        // trading twice: fm-server answers a submission it already holds with
        // the order it made, not a second one. A send that timed out or lost
        // its connection may or may not have landed; reporting it as refused
        // invited the caller to place it again and double the position.
        order.put("submissionId", UUID.randomUUID().toString());
        return order;
    }

    /**
     * DELETE the order: the server builds the CANCEL from the order's own
     * lineage and market, and answers with it. {@code marketId} is no longer
     * sent -- the order already says which market it is in.
     */
    public Order submitCancel(long marketplaceId, long marketId, long originalId) {
        return _deleteFor(_marketplace(marketplaceId) + "/orders/" + originalId, ORDER_TYPE);
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
     *
     * <p>The side must be named. This was {@code BUY == side ? ... : priceMinimum},
     * the complement of buy rather than a test for sell, so a null side priced
     * the order at the bottom of the range -- the most aggressive *sell* this
     * market accepts. {@code submitMarket} is public API and takes the side
     * straight from its caller, so that turned a missing argument into a real
     * order crossing the wrong side of the book.
     *
     * @throws IllegalArgumentException if the side is null
     */
    static long marketableLimit(Market market, OrderSide side) {
        // The highest legal price is what priceRound gives for the ceiling, so
        // this is the grid rule rather than a fourth copy of it.
        return switch (side) {
            case BUY  -> market.priceRound(market.priceMaximum());
            case SELL -> market.priceMinimum();
            case null -> throw new IllegalArgumentException(
                "A market order must name its side; got: null");
        };
    }

    // --- management ---------------------------------------------------------

    @Override
    public Session openSession(long marketplaceId) {
        return _sessionState(marketplaceId, "OPEN");
    }

    @Override
    public Session pauseSession(long marketplaceId) {
        return _sessionState(marketplaceId, "PAUSED");
    }

    @Override
    public Session closeSession(long marketplaceId) {
        return _sessionState(marketplaceId, "CLOSED");
    }

    /**
     * A session's lifecycle is its state, PATCHed. Asking for the state it is
     * already in changes nothing, and so does asking for one it cannot reach
     * from where it is -- closing or pausing a session never opened, pausing a
     * closed one: the server answers the session as it stands, as V0 always
     * did, so read the answered state when it matters. fm-server refused those
     * with 400 from 4.5.37 until 2026-10-19, which stopped fm-robots' studies,
     * which close before they stage, on a marketplace never opened.
     */
    private Session _sessionState(long marketplaceId, String state) {
        return _patch(_marketplace(marketplaceId) + "/sessions/current", Map.of("state", state), SESSION_TYPE);
    }

    @Override
    public List<Person> users() {
        return _get(_v1("/users"), PERSONS_TYPE);
    }

    @Override
    public Page<Person> usersPage(int limit, Long before) {
        return _getPage(_v1("/users"), limit, before, PERSONS_TYPE);
    }

    @Override
    public List<Allotment> allotments(long marketplaceId, long allocationId) {
        return List.copyOf(_get(_marketplace(marketplaceId) + "/allocations/" + allocationId + "/allotments",
                                ALLOTMENTS_TYPE));
    }

    @Override
    public Marketplace createMarketplaceFromJson(String json) {
        Object definition;
        try {
            definition = MAPPER.readValue(json, new TypeReference<Object>() {});
        } catch (JacksonException e) {
            throw new ApiException("Marketplace definition is not valid JSON", e);
        }
        return _create(key -> _post(_v1("/marketplaces"), definition, MARKETPLACE_TYPE, key));
    }

    @Override
    public List<Holding> allocate(long marketplaceId, List<Holding> holdings) {
        var allotments = holdings.stream().map(h -> _toAllotment(marketplaceId, h)).toList();
        return _toHoldings(_create(key -> _post(_marketplace(marketplaceId) + "/allocations", allotments,
                ALLOTMENTS_TYPE, key)));
    }

    @Override
    public String downloadHoldings(long marketplaceId) {
        return _getText(_marketplace(marketplaceId) + "/holdings");
    }

    @Override
    public List<Holding> uploadHoldings(long marketplaceId, Path csv) {
        return _toHoldings(_create(key -> _sendCsv("POST", _marketplace(marketplaceId) + "/allocations", csv,
                ALLOTMENTS_TYPE, key)));
    }

    @Override
    public List<Holding> uploadHoldingsCsv(long marketplaceId, String csv) {
        byte[] content = csv.getBytes(StandardCharsets.UTF_8);
        return _toHoldings(_create(key -> _sendCsv("POST", _marketplace(marketplaceId) + "/allocations", content,
                ALLOTMENTS_TYPE, key)));
    }

    @Override
    public List<ParticipantState> uploadState(long marketplaceId, Path csv) {
        return List.copyOf(_sendCsv("PUT", _marketplace(marketplaceId) + "/state", csv, PARTICIPANT_STATES_TYPE));
    }

    /** Always an array on the wire, even for one widget, so the answer is always a list. */
    @Override
    public List<Widget> pushWidgets(long marketplaceId, List<WidgetPush> widgets) {
        return List.copyOf(_create(key -> _post(_marketplace(marketplaceId) + "/widgets", widgets, WIDGETS_TYPE, key)));
    }

    @Override
    public boolean removeWidget(long marketplaceId, String key) {
        return _removeWidget(_marketplace(marketplaceId) + "/widgets/" + _segment(key));
    }

    @Override
    public boolean removeWidget(long marketplaceId, String key, long userId) {
        return _removeWidget(_marketplace(marketplaceId) + "/participants/" + userId + "/widgets/" + _segment(key));
    }

    /**
     * 404 with no body is the server saying there was nothing under that key.
     * A 404 that carries a failure document is something else -- a marketplace
     * that does not exist -- and is raised, not read as "already gone".
     */
    private boolean _removeWidget(String url) {
        try {
            _delete(url);
            return true;
        } catch (HttpException e) {
            if (e.statusCode() == 404 && (e.body() == null || e.body().isBlank())) {
                return false;
            }
            throw e;
        }
    }

    /** A key is {@code [A-Za-z0-9_-]} on the server; encoded anyway, so a bad one is refused there rather than rerouted here. */
    private static String _segment(String key) {
        return java.net.URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * The widgets of the frame every widgets read answers, {@code {marketplaceId,
     * snapshot, widgets, removed}}. A bare list is read too: {@code widgets/all},
     * which this read replaced, answered one, and 0.4.0 to 0.11.0 expected it
     * here, failing on every call.
     */
    @Override
    public List<Widget> allWidgets(long marketplaceId) {
        JsonNode body = _get(_marketplace(marketplaceId) + "/widgets?participant=all", SNAPSHOT_TYPE);
        JsonNode widgets = body.isArray() ? body : body.path("widgets");
        return widgets.isArray() ? List.of(MAPPER.treeToValue(widgets, Widget[].class)) : List.of();
    }

    @Override
    public MarketCall clearNow(long marketplaceId, long marketId) {
        // Created where the calls are listed (fm-server 4.17); the market's own route is superseded.
        return _create(key -> _post(_marketplace(marketplaceId) + "/calls", Map.of("marketId", marketId),
                MARKET_CALL_TYPE, key));
    }

    @Override
    public List<MarketCall> calls(long marketplaceId) {
        return List.copyOf(_get(_marketplace(marketplaceId) + "/calls", MARKET_CALLS_TYPE));
    }

    @Override
    public Page<MarketCall> callsPage(long marketplaceId, int limit, Long before) {
        return _getPage(_marketplace(marketplaceId) + "/calls", limit, before, MARKET_CALLS_TYPE);
    }

    @Override
    public List<MarketCall> calls(long marketplaceId, long sessionId) {
        return List.copyOf(_get(_marketplace(marketplaceId) + "/calls?sessions=" + sessionId, MARKET_CALLS_TYPE));
    }

    @Override
    public List<MarketplaceRobot> marketplaceRobots(long marketplaceId) {
        return List.copyOf(_get(_marketplace(marketplaceId) + "/robots", MARKETPLACE_ROBOTS_TYPE));
    }

    /**
     * The server reads the control flat, beside the value: no bounds at all
     * is a fixed parameter, so a null control sends only the value.
     */
    @Override
    public RobotParameterConfig configureRobotParameter(long marketplaceId, String robotId, String paramName,
                                                        String value, RobotParameterControl control) {
        return _put(_marketplace(marketplaceId) + "/robots/" + _segment(robotId)
                + "/parameters/" + _segment(paramName), ConfigureRobotParameter.of(value, control),
                ROBOT_PARAMETER_CONFIG_TYPE);
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
        // One per connection: the stream this replaces is closed, not dropped
        // with its socket, heartbeats and reconnects still running.
        if (_events != null) _events.close();
        _events = new Events(_wsUrl(), this::_bearer, marketplaceId, _clientDescription(), MAPPER, queue,
            this::_renewToken);
        _events.connect();
    }

    /**
     * Package-private helper used by {@link DefaultDesk} (Phase 2d)
     * to own its own {@link Events} subscription rather than clobbering
     * {@link #events}. Lets multiple {@code desk(marketplaceId)}
     * calls — for the same or different marketplaces — coexist within
     * one {@code Flexemarkets} instance without trampling each other's
     * WS connections.
     */
    @Override
    public Subscription subscribe(long marketplaceId, BlockingQueue<Object> queue) {
        return _connectEvents(marketplaceId, queue);
    }

    @Override
    public Subscription subscribe(long marketplaceId, BlockingQueue<Object> queue, boolean history) {
        return _connectEvents(marketplaceId, queue, history);
    }

    Events _connectEvents(long marketplaceId, BlockingQueue<Object> queue) {
        return _connectEvents(marketplaceId, queue, false);
    }

    Events _connectEvents(long marketplaceId, BlockingQueue<Object> queue, boolean history) {
        return _connectEvents(marketplaceId, queue, history, Events.RECONNECT_MAX_PAUSE_MS);
    }

    /** A stream that waits at most {@code reconnectMaxPauseMillis} between attempts to come back. */
    Events _connectEvents(long marketplaceId, BlockingQueue<Object> queue, boolean history,
                          long reconnectMaxPauseMillis) {
        var ev = new Events(_wsUrl(), this::_bearer, marketplaceId, _clientDescription(), MAPPER, queue,
            this::_renewToken, history);
        ev.reconnectMaxPauseMillis = reconnectMaxPauseMillis;
        ev.connect();
        return ev;
    }

    private final java.util.Map<Long, SharedDesk> _sharedViews = new java.util.HashMap<>();
    private final Object _viewLock = new Object();

    private static final class SharedDesk {
        final DefaultDesk desk;
        int refCount;
        SharedDesk(DefaultDesk v) { this.desk = v; this.refCount = 0; }
    }

    /**
     * Open a stateful {@link Desk} on this marketplace. Multiple
     * calls for the same {@code marketplaceId} share a single
     * underlying desk + WS subscription within this {@code Flexemarkets}
     * instance — each call returns a fresh handle, the handles
     * refcount, and the shared resources tear down on the last close.
     *
     * <p>Sharing is intentionally per-{@code Flexemarkets} (i.e.
     * per-bearer). Two callers with different identities each get
     * their own desk — multi-tenant WS multiplexing is a server-side
     * concern, not a client-side one.
     */
    @Override
    public Desk desk(long marketplaceId, Desk.OrderChecks checks) {
        DefaultDesk shared;
        synchronized (_viewLock) {
            SharedDesk entry = _sharedViews.get(marketplaceId);
            if (entry == null) {
                // Hold the lock while constructing — desk() should
                // be a cold-path operation, and we'd rather block
                // duplicate desks than race two parallel WS
                // subscriptions into existence. The DefaultDesk
                // constructor itself blocks on REST snapshots, so a
                // dozen-ms first call is acceptable.
                shared = new DefaultDesk(this, marketplaceId, markets(marketplaceId));
                entry = new SharedDesk(shared);
                _sharedViews.put(marketplaceId, entry);
            }
            entry.refCount++;
            shared = entry.desk;
        }
        return new DeskHandle(shared, () -> _releaseSharedView(marketplaceId), checks);
    }

    void _releaseSharedView(long marketplaceId) {
        DefaultDesk toClose = null;
        synchronized (_viewLock) {
            SharedDesk entry = _sharedViews.get(marketplaceId);
            if (entry == null) return;
            if (--entry.refCount <= 0) {
                _sharedViews.remove(marketplaceId);
                toClose = entry.desk;
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
        // Before the listeners: an order still unanswered is told so, rather
        // than waiting on a socket that has gone.
        java.util.List<OrderStream> streams;
        java.util.List<RestOrderWindow> windows;
        synchronized (_orderStreams) {
            _closed = true;
            streams = new java.util.ArrayList<>(_orderStreams.values());
            _orderStreams.clear();
            windows = new java.util.ArrayList<>(_restWindows.values());
            _restWindows.clear();
        }
        for (var stream : streams) {
            stream.close();
        }
        for (var window : windows) {
            window.close();
        }
        if (_events != null) {
            _events.close();
        }
        // Force-close any remaining shared Desks. Well-behaved
        // callers close their handles first; this is the safety net.
        java.util.List<DefaultDesk> toClose;
        synchronized (_viewLock) {
            toClose = new java.util.ArrayList<>(_sharedViews.size());
            for (var entry : _sharedViews.values()) toClose.add(entry.desk);
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
            .header("Authorization", _bearer())
            .header("Accept", accept)
            .header(GzipBodies.ACCEPT_ENCODING, GzipBodies.GZIP)
            .header("User-Agent", FM_SDK_CLIENT);

        _applyTimeout(builder, _requestTimeout);

        if (_impersonateAccount != null) {
            builder.header(HEADER_IMPERSONATION, _impersonateAccount);
            builder.header(HEADER_IMPERSONATION_TOKEN, _impersonationToken());
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
        var response = _exchangeOnce(request);
        var presented = request.headers().firstValue("Authorization").orElse(null);
        if (presented != null && _tokenExpired(response) && _renewToken(presented)) {
            // Without the timing the first send carried, too: that figure was
            // reported once already, and the resend is a call of its own.
            var renewed = HttpRequest.newBuilder(request, (name, value) -> !"Authorization".equalsIgnoreCase(name)
                    && !HEADER_CLIENT_TIMING.equalsIgnoreCase(name))
                .header("Authorization", _bearer())
                .build();
            response = _exchangeOnce(renewed);
        }
        return response;
    }

    /** A 401 that says the token ran out, as distinct from one that is wrong. */
    static boolean _tokenExpired(HttpResponse<String> response) {
        if (response.statusCode() != 401 || response.body() == null) {
            return false;
        }
        var failure = _tryParseConflict(response.body());
        return failure != null && "ACCOUNT_TOKEN_EXPIRED".equals(failure.error());
    }

    /**
     * Sign in again with the credentials this client was made with.
     *
     * <p>Tokens live 24 hours, so a robot that ran longer ended the first time
     * it asked for anything after that, or looped on the refusal -- a Utah
     * poller, about 250 times a day at 00Z. One sign-in renews it. False when
     * this client was given a token rather than credentials, or the sign-in
     * fails; the caller then answers with the refusal it had.
     *
     * <p>{@code presented} is the Authorization header the refused request or
     * stream connect carried. If another thread has renewed since, the token
     * is not renewed again: the caller retries with the one now current.
     */
    boolean _renewToken(String presented) {
        if (!_canRenew) {
            return false;
        }
        synchronized (_renewLock) {
            if (!presented.equals(_bearer())) {
                return true;
            }
            try {
                _token = _signIn();
                return true;
            } catch (FlexemarketsException refused) {
                return false;
            }
        }
    }

    private static boolean _isBlank(String value) {
        return value == null || value.isBlank();
    }

    private HttpResponse<String> _exchangeOnce(HttpRequest request) throws IOException, InterruptedException {
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
            return _httpClient.send(request, GzipBodies.ofString());
        }

        var out = System.out;
        out.printf("> %s %s%n", request.method(), request.uri());
        _printCapturedHeaders(out, ">", request.headers().map());
        out.println(">");

        var response = _httpClient.send(request, GzipBodies.ofString());

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

        return path.endsWith("/tokens") || path.endsWith("/refresh") || path.endsWith("/renew") || path.contains("/otp")
                || path.endsWith("/impersonation");
    }

    private static void _printCapturedHeaders(java.io.PrintStream out, String prefix,
                                             Map<String, List<String>> headers) {
        headers.keySet().stream().sorted().forEach(name -> {
            var value = "authorization".equalsIgnoreCase(name) || HEADER_IMPERSONATION_TOKEN.equalsIgnoreCase(name)
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
     * {@link Desk}) can correlate the snapshot with the WS
     * delta stream. Returns {@link Snapshot#NO_SEQ} when the header
     * is absent.
     */
    private <T> Snapshot<T> _getSnapshot(String url, TypeReference<T> type) {
        return _getRead(url, type, "Snapshot", (body, headers) -> new Snapshot<>(body,
                headers.firstValue("x-fm-as-of-seq").map(Long::parseLong).orElse(Snapshot.NO_SEQ)));
    }

    /**
     * A page of a collection: {@code url} with {@code ?limit=} and
     * {@code ?before=}, and the {@code before} of the page after it read from
     * the {@code Link: <...>; rel="next"} the server sends while older rows
     * remain.
     */
    private <T> Page<T> _getPage(String url, int limit, Long before, TypeReference<List<T>> type) {
        var paged = url + (url.contains("?") ? "&" : "?") + "limit=" + limit
                + (null == before ? "" : "&before=" + before);
        return _getRead(paged, type, "Page", (rows, headers) -> new Page<>(rows, _next(headers.allValues("Link"))));
    }

    /** The {@code before} a {@code rel="next"} link names, or null when there is none. */
    private static Long _next(List<String> links) {
        Matcher next = NEXT_LINK.matcher(String.join(",", links));
        return next.find() ? Long.valueOf(next.group(1)) : null;
    }

    /** GET a body the answer needs a response header beside: a snapshot's sequence, a page's next link. */
    private <T, R> R _getRead(String url, TypeReference<T> type, String what, BiFunction<T, HttpHeaders, R> answer) {
        var request = _request(url, "application/json")
            .GET()
            .build();
        try {
            var response = _exchange(request);
            var statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                return answer.apply(MAPPER.readValue(response.body(), type), response.headers());
            }
            throw _failureFor(statusCode, response.body());
        } catch (FlexemarketsException e) {
            throw e;
        } catch (IOException e) {
            throw transportFailure(what + " request failed", request, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(what + " request interrupted", e);
        }
    }

    private <T> T _post(String url, Object body, TypeReference<T> type) {
        return _post(url, body, type, null);
    }

    /** POST, under {@code idempotencyKey} when there is one (see {@link #_create}). */
    private <T> T _post(String url, Object body, TypeReference<T> type, String idempotencyKey) {
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
            .POST(HttpRequest.BodyPublishers.ofString(json));
        if (null != idempotencyKey) {
            request.header(IDEMPOTENCY_KEY, idempotencyKey);
        }

        return _send(request.build(), type);
    }

    /**
     * Send a create under an {@code Idempotency-Key}, and once more under the
     * same key if its answer was lost. A create that timed out or lost its
     * connection may or may not have landed; fm-server 4.17 answers a key it
     * already holds with what it answered the first time (its
     * IdempotencyFilter), so the resend makes one marketplace, market or
     * call, never two. Orders do the same with their own submissionId.
     */
    private <T> T _create(java.util.function.Function<String, T> send) {
        String key = UUID.randomUUID().toString();
        try {
            return send.apply(key);
        } catch (ApiException lost) {
            if (!(lost.getCause() instanceof IOException)) {
                throw lost;
            }
            return send.apply(key);
        }
    }

    /** PUT {@code body} as the resource's new state -- a robot parameter's configuration, say. */
    private <T> T _put(String url, Object body, TypeReference<T> type) {
        var request = _request(url, "application/json")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(_requestJson(body)))
            .build();
        return _send(request, type);
    }

    /** PATCH the fields named in {@code body} -- a session's {@code state}, say. */
    private <T> T _patch(String url, Object body, TypeReference<T> type) {
        var request = _request(url, "application/json")
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(_requestJson(body)))
            .build();
        return _send(request, type);
    }

    /** A request body as JSON; only the write is guarded, for the reason {@link #_post} gives. */
    private static String _requestJson(Object body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JacksonException e) {
            throw new ApiException("Failed to serialize request body", e);
        }
    }

    /** DELETE, whose answer is a status and nothing worth parsing. */
    private void _delete(String url) {
        var request = _request(url, "application/json")
            .DELETE()
            .build();
        _sendDiscardingBody(request);
    }

    /** DELETE whose answer is the resource as it ended -- an order's CANCEL. */
    private <T> T _deleteFor(String url, TypeReference<T> type) {
        var request = _request(url, "application/json")
            .DELETE()
            .build();
        return _send(request, type);
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
     * Send one CSV file as the body, {@code Content-Type: text/csv} -- format
     * by header (fm-server API-V1 rule 7), where 0.3 posted it as a
     * multipart upload to a {@code /uploads} segment. Bytes, not a string, so
     * the file's own encoding survives.
     */
    private <T> T _sendCsv(String method, String url, Path file, TypeReference<T> type) {
        return _sendCsv(method, url, file, type, null);
    }

    /** The file form, under {@code idempotencyKey} when there is one. */
    private <T> T _sendCsv(String method, String url, Path file, TypeReference<T> type, String idempotencyKey) {
        byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ApiException("Failed to read " + file, e);
        }
        return _sendCsv(method, url, content, type, idempotencyKey);
    }

    /** CSV bytes as the body, {@code Content-Type: text/csv}; see the file form above. */
    private <T> T _sendCsv(String method, String url, byte[] content, TypeReference<T> type, String idempotencyKey) {
        var request = _request(url, "application/json")
            .header("Content-Type", "text/csv")
            .method(method, HttpRequest.BodyPublishers.ofByteArray(content));
        if (null != idempotencyKey) {
            request.header(IDEMPOTENCY_KEY, idempotencyKey);
        }
        return _send(request.build(), type);
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
        // /api/v1/tokens, as every route the SDK calls is V1 (fm-server
        // 4.16.0); /api/tokens answers the same for older clients.
        var endpoint = _v1("/tokens");
        if (_refreshToken != null) {
            return _renewWithRefreshToken(endpoint);
        }
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
                .timeout(DEFAULT_REQUEST_TIMEOUT)
                .uri(URI.create(endpoint + "/refresh"))
                .header("Authorization", "Bearer " + tokenValue)
                .header("Accept", "application/json")
                .header("User-Agent", FM_SDK_CLIENT)
                // POST, since it mints a token (fm-server 4.6).
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        } else {
            // The credentials go in the body only. A Basic header as well made
            // the server's Basic filter check the password before /tokens did,
            // so every sign-in hashed it twice -- two of the few concurrent
            // hashes fm-server allows during a class's sign-in burst.
            var username = account + "|" + email;
            // Two strings in a map: there is nothing here Jackson cannot write.
            var json = MAPPER.writeValueAsString(Map.of("username", username, "password", password));
            request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", FM_SDK_CLIENT)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        }

        return _credentialExchange(request, TOKEN_TYPE, "Sign-in");
    }

    /**
     * Sign in with the refresh token in hand: fm-server takes it once, and
     * answers with an access token and the refresh token to present next time.
     *
     * <p>This is how a robot fm-server launched outlives its access token. It
     * is handed a refresh token of its own, not the participant's access token,
     * which stops working after hours while the robot runs on.
     */
    private Token _renewWithRefreshToken(String endpoint) {
        // A string in a map: there is nothing here Jackson cannot write.
        var json = MAPPER.writeValueAsString(Map.of("refreshToken", _refreshToken));
        var request = HttpRequest.newBuilder()
            .timeout(DEFAULT_REQUEST_TIMEOUT)
            .uri(URI.create(endpoint + "/renew"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", FM_SDK_CLIENT)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        var renewed = _credentialExchange(request, RENEWAL_TYPE, "Renewal");
        if (!_isBlank(renewed.refreshToken())) {
            _refreshToken = renewed.refreshToken();
        }
        return new Token(renewed.requestUrl(), renewed.person(), renewed.account(), renewed.token());
    }

    /** What {@code POST /tokens/renew} answers: a sign-in, and the next refresh token. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Renewed(String requestUrl, Person person, Account account, String token, String refreshToken) {
    }

    /**
     * Send a request whose answer is a credential -- a sign-in, an
     * impersonation token -- and read it, or throw what the server said.
     * {@code what} names the request in a transport failure.
     */
    private <T> T _credentialExchange(HttpRequest request, TypeReference<T> type, String what) {
        try {
            var response = _exchange(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw _failureFor(response.statusCode(), response.body());
            }
            return MAPPER.readValue(response.body(), type);
        } catch (FlexemarketsException e) {
            throw e;
        } catch (IOException e) {
            throw transportFailure(what + " request failed", request, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(what + " request interrupted", e);
        }
    }

    /** The impersonation token to send, asked for again when it is within {@link #IMPERSONATION_RENEW_BEFORE} of expiring. */
    private String _impersonationToken() {
        var grant = _impersonation;
        if (_current(grant)) {
            return grant.token();
        }
        synchronized (_impersonationLock) {
            if (!_current(_impersonation)) {
                _impersonation = _grantImpersonation();
            }
            return _impersonation.token();
        }
    }

    private static boolean _current(ImpersonationGrant grant) {
        return grant != null && grant.expiresAt() != null
                && Instant.now().isBefore(grant.expiresAt().minus(IMPERSONATION_RENEW_BEFORE));
    }

    /**
     * Trade the administrator's password for a token to act as the account
     * named at connect. Built here rather than by {@link #_request}, which
     * would put the impersonation headers on the very call that gets them.
     */
    private ImpersonationGrant _grantImpersonation() {
        var json = MAPPER.writeValueAsString(Map.of(
                "account", _impersonateAccount, "currentPassword", _properties.getProperty("password")));
        var builder = HttpRequest.newBuilder()
            .uri(URI.create(_v1("/impersonation")))
            .header("Authorization", _bearer())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", FM_SDK_CLIENT)
            .POST(HttpRequest.BodyPublishers.ofString(json));
        _applyTimeout(builder, _requestTimeout);
        return _credentialExchange(builder.build(), IMPERSONATION_GRANT_TYPE, "Impersonation token");
    }

    /** Applies a timeout unless it was configured away. */
    static void _applyTimeout(HttpRequest.Builder builder, Duration timeout) {
        if (null != timeout && !timeout.isZero() && !timeout.isNegative()) {
            builder.timeout(timeout);
        }
    }

    /**
     * A timeout from the properties, or the default.
     *
     * <p>Unparseable means the default rather than an exception: a typo in
     * a credential file must not stop a study starting, and the default is
     * the safe answer.
     */
    static Duration _timeout(java.util.Properties properties, String key, Duration fallback) {
        String configured = null == properties ? null : properties.getProperty(key);
        if (null == configured || configured.isBlank()) {
            return fallback;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(configured.trim()));
        } catch (NumberFormatException x) {
            return fallback;
        }
    }

    private String _clientDescription() {
        return _properties.getProperty("client-description", "Unspecified client");
    }

    private String _wsUrl() {
        return _v1("/events").replaceFirst("http", "ws");
    }

    // --- V1 routes ---

    /**
     * A V1 route, addressed from the server. 0.4 reads no API root: every
     * path is knowable without one, which is what a versioned API is for, and
     * a client that found its routes through the root was one server refactor
     * from failing whole (fm-server 4.5.6).
     */
    private String _v1(String path) {
        return server(endpointUrl()) + "/v1" + path;
    }

    /** {@code /api/v1/marketplaces/{id}}, which most routes hang off. */
    private String _marketplace(long marketplaceId) {
        return _v1("/marketplaces/" + marketplaceId);
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

    /**
     * Scheme and authority: where the request went, without the path. An
     * HttpRequest always has both -- its builder refuses a URI without an
     * http(s) scheme and a host.
     */
    private static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getRawAuthority();
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
    static FlexemarketsException _failureFor(int statusCode, String body) {
        return _bare(statusCode, body).withResponse(statusCode, _reason(body), body);
    }

    /** {@link #_failureFor}, before the answer is attached. */
    private static FlexemarketsException _bare(int statusCode, String body) {
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

    /** The {@code error} a failure body names, or null when it names none. */
    private static String _reason(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            var error = MAPPER.readTree(body).get("error");
            return error != null && error.isString() && !error.asString().isEmpty() ? error.asString() : null;
        } catch (Exception e) {
            return null;
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
     * @param credential        a password, a token, {@code refresh:} and a refresh
     *                          token, or a path to a credential file holding one
     * @param endpoint          the endpoint to connect to
     * @param clientDescription how this client identifies itself
     * @return the resolved properties
     * @throws IOException if a named credential file cannot be read
     */
    public static Properties loadProperties(String credential, String endpoint, String clientDescription) throws IOException {
        return loadProperties(System::getenv, Path.of(System.getProperty("user.home")), credential, endpoint, clientDescription);
    }

    /** As {@link #loadProperties(String, String, String)}, with the environment and home directory a test supplies. */
    static Properties loadProperties(Function<String, String> env, Path home,
                                     String credential, String endpoint, String clientDescription) throws IOException {
        var properties = setDefaultProperties(env, home);

        if (credential != null) {
            _loadCredential(properties, credential);
        }

        if (endpoint != null) {
            _loadEndpoint(properties, endpoint, env);
        }

        // Last, because an endpoint arrives from three places and only one of
        // them used to expand a bare id: the argument. A file's contents are
        // loaded verbatim, so "endpoint=1234" in ~/.fm/endpoint -- read by
        // setDefaultProperties on every connection -- or in a file named by -E
        // reached the HTTP client as "1234". Normalising here covers all three,
        // and is a no-op on the form the argument path already produced.
        _expandBareMarketplaceId(properties, env);

        if (clientDescription != null) {
            properties.setProperty("client-description", clientDescription);
        } else {
            properties.setProperty("client-description", "Unspecified client");
        }

        return properties;
    }

    /**
     * The endpoint and credential before any argument: {@code $FM_API_URL},
     * {@code ~/.fm/credential} and {@code ~/.fm/endpoint}, and where the
     * endpoint came from. The environment and home directory are parameters
     * so that a test can supply them; callers pass the real ones.
     */
    static Properties setDefaultProperties(Function<String, String> env, Path home) {
        var properties = new Properties();

        properties.setProperty("account", "");
        properties.setProperty("email", "");
        properties.setProperty("password", "");

        properties.setProperty("endpoint", Endpoints.DEFAULT_HOST);
        properties.setProperty("endpoint-source", "the default host");

        for (var file : List.of("credential", "endpoint")) {
            var filePath = home.resolve(".fm").resolve(file);
            var before = properties.getProperty("endpoint");
            _loadConfiguration(properties, filePath);
            _noteEndpointSource(properties, before, _abbreviate(filePath));
        }

        // Last, so it wins over the files, as in the Python and TypeScript
        // SDKs: a script that sets FM_API_URL to aim at a server must reach
        // that server, not whichever one ~/.fm/endpoint names.
        var envUrl = env.apply("FM_API_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            properties.setProperty("endpoint", envUrl);
            properties.setProperty("endpoint-source", "$FM_API_URL");
        }

        return properties;
    }

    private static void _loadCredential(Properties properties, String credential) {
        var credentialPath = Path.of(credential);

        // A credential the caller names replaces the one ~/.fm/credential holds,
        // whole: a refresh token left over from it would be the one the client
        // signs in with, and every client on the machine would present it (#8).
        properties.setProperty("account", "");
        properties.setProperty("email", "");
        properties.setProperty("password", "");
        properties.remove("token");
        properties.remove(REFRESH_TOKEN_PROPERTY);

        if (credential.startsWith(REFRESH_TOKEN_PREFIX) && !_isBlank(credential.substring(REFRESH_TOKEN_PREFIX.length()))) {
            properties.setProperty(REFRESH_TOKEN_PROPERTY, credential.substring(REFRESH_TOKEN_PREFIX.length()));
        } else if (Files.isRegularFile(credentialPath)) {
            _loadConfiguration(properties, credentialPath);
        } else if (_isValidToken(credential)) {
            properties.setProperty("token", credential);
        } else {
            throw new IllegalArgumentException("Invalid credential: '%s' is not a file or token.".formatted(credential));
        }
    }

    private static void _loadEndpoint(Properties properties, String endpoint, Function<String, String> env) {
        // A bare marketplace id (e.g. "2540") resolves to that marketplace on
        // $FM_URL's host, or the default production host. Checked before the
        // URL branch: a bare number is a valid relative URI, so isValidUrl
        // would otherwise swallow it.
        if (_isMarketplaceId(endpoint)) {
            properties.setProperty("endpoint", _marketplaceEndpoint(endpoint, env));
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
    private static void _expandBareMarketplaceId(Properties properties, Function<String, String> env) {
        var endpoint = properties.getProperty("endpoint");
        if (endpoint != null && _isMarketplaceId(endpoint.trim())) {
            properties.setProperty("endpoint", _marketplaceEndpoint(endpoint.trim(), env));
        }
    }

    /**
     * A bare marketplace id resolves to that marketplace on {@code $FM_URL}'s
     * host, or on the default production host when it is unset or blank --
     * as {@code Endpoints.marketplaceUrl} and the Python and TypeScript SDKs
     * do. This one ignored {@code $FM_URL}, so {@code FM_URL=http://localhost:8080
     * fm-ssw -E 3305}, which reads as a local rehearsal, ran against
     * production (2026-09-24).
     */
    private static String _marketplaceEndpoint(String marketplaceId, Function<String, String> env) {
        var host = env.apply("FM_URL");
        return (host == null || host.isBlank() ? Endpoints.DEFAULT_HOST : host) + "/api/marketplaces/" + marketplaceId;
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
