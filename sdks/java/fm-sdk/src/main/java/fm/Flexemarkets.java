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
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import fm.Exceptions.ApiException;
import fm.Exceptions.AuthenticationException;
import fm.Exceptions.ConflictException;
import fm.Exceptions.HttpException;
import fm.Types.Account;
import fm.Types.ApiRoot;
import fm.Types.ClientConnection;
import fm.Types.ConflictFailure;
import fm.Types.Holding;
import fm.Types.Market;
import fm.Types.Marketplace;
import fm.Types.Order;
import fm.Types.Person;
import fm.Types.Session;
import fm.Types.Token;

public class Flexemarkets implements AutoCloseable {
    private static final String FM_SDK_CLIENT = "fm-sdk-java/0.1.0";

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<Token>               TOKEN_TYPE        = new TypeReference<>() {};
    private static final TypeReference<ApiRoot>              API_ROOT_TYPE     = new TypeReference<>() {};
    private static final TypeReference<List<Marketplace>>    MARKETPLACES_TYPE = new TypeReference<>() {};
    private static final TypeReference<Marketplace>          MARKETPLACE_TYPE  = new TypeReference<>() {};
    private static final TypeReference<List<Market>>         MARKETS_TYPE      = new TypeReference<>() {};
    private static final TypeReference<List<Session>>        SESSIONS_TYPE     = new TypeReference<>() {};
    private static final TypeReference<Session>              SESSION_TYPE      = new TypeReference<>() {};
    private static final TypeReference<List<Order>>          ORDERS_TYPE       = new TypeReference<>() {};
    private static final TypeReference<Order>                ORDER_TYPE        = new TypeReference<>() {};
    private static final TypeReference<List<Holding>>        HOLDINGS_TYPE     = new TypeReference<>() {};
    private static final TypeReference<List<ClientConnection>> CONNECTIONS_TYPE = new TypeReference<>() {};
    private static final TypeReference<ConflictFailure>      CONFLICT_TYPE     = new TypeReference<>() {};

    private final Properties properties;
    private final HttpClient httpClient;
    private final String bearerToken;
    private final Token token;
    private final Account account;
    private final Person user;
    private final ApiRoot apiRoot;

    private Events events;
    private volatile boolean closed;

    private Flexemarkets(Properties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newHttpClient();

        this.token = signIn();
        this.account = token.account();
        this.user = token.person();
        this.bearerToken = "Bearer " + token.token();

        this.apiRoot = fetchApiRoot();
    }

    public static Flexemarkets connect(String credential, String endpoint, String clientDescription) throws IOException {
        return new Flexemarkets(loadProperties(credential, endpoint, clientDescription));
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
        return getSnapshot(url, ORDERS_TYPE);
    }

    /**
     * V1 recent-trades snapshot for seeding the trade-history tape.
     * Same {@code x-fm-as-of-seq} contract as
     * {@link #activeOrdersV1(long)}.
     */
    public Snapshot<List<Order>> recentTradesV1(long marketplaceId, int size) {
        var url = server(endpointUrl()) + "/v1/marketplaces/" + marketplaceId
                + "/orders/recent-trades?size=" + size;
        return getSnapshot(url, ORDERS_TYPE);
    }

    /** Sensible default — the server caps at 5000 and defaults to 1000. */
    public Snapshot<List<Order>> recentTradesV1(long marketplaceId) {
        return recentTradesV1(marketplaceId, 1000);
    }

    public List<Holding> holdings(long marketplaceId) {
        return get(uriIdSegment(apiRoot, "marketplaces", marketplaceId, "holdings"), HOLDINGS_TYPE);
    }

    public Holding holding(long marketplaceId) {
        return get(uriIdSegment(apiRoot, "marketplaces", marketplaceId, "currentHolding"), new TypeReference<>() {});
    }

    public List<ClientConnection> connections(long marketplaceId) {
        return get(uriIdSegmentParam(apiRoot, "marketplaces", marketplaceId, "connections", null), CONNECTIONS_TYPE);
    }

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

    public void listen(long marketplaceId, BlockingQueue<Object> queue) {
        events = new Events(wsUrl(), bearerToken, marketplaceId, clientDescription(), MAPPER, queue);
        events.connect();
    }

    /**
     * Open a stateful {@link MarketView} on this marketplace. The
     * returned view drives its own WS subscription, dispatches events
     * into per-market order books and trade tapes, and exposes
     * always-current accessors and listener registration.
     *
     * <p>Phase 1 ({@code DefaultMarketView}) skips REST-snapshot
     * seeding, sequence-gap recovery, per-identity sharing, and
     * automatic reconnect. See {@link DefaultMarketView} for the
     * staged plan. Robots that don't depend on consistency-guaranteed
     * startup state can use this today; those that do should wait
     * for Phase 2.
     */
    public MarketView observe(long marketplaceId) {
        return new DefaultMarketView(this, marketplaceId, markets(marketplaceId));
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
        httpClient.close();
    }

    // --- HTTP helpers ---

    private <T> T get(String url, TypeReference<T> type) {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", bearerToken)
            .header("Accept", "application/json")
            .header("User-Agent", FM_SDK_CLIENT)
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
        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", bearerToken)
            .header("Accept", "application/json")
            .header("User-Agent", FM_SDK_CLIENT)
            .GET()
            .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
        try {
            var json = MAPPER.writeValueAsString(body);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", bearerToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", FM_SDK_CLIENT)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            return send(request, type);
        } catch (IOException e) {
            throw new ApiException("Failed to serialize request body", e);
        }
    }

    private <T> T send(HttpRequest request, TypeReference<T> type) {
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
            // Token-based auth: just GET the API root to validate
            var body = Map.of("username", account + "|" + email, "password", "token");
            try {
                var json = MAPPER.writeValueAsString(body);
                request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + tokenValue)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", FM_SDK_CLIENT)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            } catch (IOException e) {
                throw new ApiException("Failed to serialize sign-in body", e);
            }
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
            } catch (IOException e) {
                throw new ApiException("Failed to serialize sign-in body", e);
            }
        }

        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", bearerToken)
            .header("Accept", "application/json")
            .header("User-Agent", FM_SDK_CLIENT)
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
        return endpoint.substring(0, endpoint.indexOf("/api") + 4);
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

    static Properties loadProperties(String credential, String endpoint, String clientDescription) throws IOException {
        var properties = setDefaultProperties();

        if (credential != null) {
            loadCredential(properties, credential);
        }

        if (endpoint != null) {
            loadEndpoint(properties, endpoint);
        }

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
        properties.setProperty("endpoint", envUrl != null ? envUrl : "https://adhocmarkets.com");

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
        var endpointPath = Path.of(endpoint);

        if (Files.isRegularFile(endpointPath)) {
            loadConfiguration(properties, endpointPath);
        } else if (isValidUrl(endpoint)) {
            properties.setProperty("endpoint", endpoint);
        } else {
            throw new IllegalArgumentException("Invalid endpoint: '%s' is not a file or URL.".formatted(endpoint));
        }
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
