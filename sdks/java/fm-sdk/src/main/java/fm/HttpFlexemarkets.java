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
    private static final TypeReference<List<Session>>        SESSIONS_TYPE     = new TypeReference<>() {};
    private static final TypeReference<Session>              SESSION_TYPE      = new TypeReference<>() {};
    private static final TypeReference<List<Order>>          ORDERS_TYPE       = new TypeReference<>() {};
    private static final TypeReference<Order>                ORDER_TYPE        = new TypeReference<>() {};
    private static final TypeReference<List<Holding>>        HOLDINGS_TYPE     = new TypeReference<>() {};
    private static final TypeReference<List<ClientConnection>> CONNECTIONS_TYPE = new TypeReference<>() {};
    private static final TypeReference<ConflictFailure>      CONFLICT_TYPE     = new TypeReference<>() {};
    private static final TypeReference<List<Person>>         PERSONS_TYPE      = new TypeReference<>() {};
    private static final TypeReference<List<Allotment>>      ALLOTMENTS_TYPE   = new TypeReference<>() {};

    private final Properties properties;
    private final HttpClient httpClient;
    private final String bearerToken;
    private final Token token;
    private final Account account;
    private final Person user;
    private final ApiRoot apiRoot;

    private Events events;
    private volatile boolean closed;

    HttpFlexemarkets(Properties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newHttpClient();

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

    private static String _ids(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
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
        } catch (JacksonException e) {
            throw new ApiException("Failed to serialize request body", e);
        }
    }

    /**
     * PATCH with no body -- the shape every session transition takes
     * ({@code /open}, {@code /pause}, {@code /close}): the verb and the path
     * carry the whole request.
     */
    private <T> T patch(String url, TypeReference<T> type) {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", bearerToken)
            .header("Accept", "application/json")
            .header("User-Agent", FM_SDK_CLIENT)
            .method("PATCH", HttpRequest.BodyPublishers.noBody())
            .build();
        return send(request, type);
    }

    /**
     * GET returning the body verbatim, for endpoints that answer with something
     * other than JSON. The holdings download is a CSV, and parsing it as JSON
     * would fail on the first line.
     */
    private String getText(String url) {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", bearerToken)
            .header("Accept", "text/csv, */*")
            .header("User-Agent", FM_SDK_CLIENT)
            .GET()
            .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", bearerToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .header("User-Agent", FM_SDK_CLIENT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
            return send(request, type);
        } catch (IOException e) {
            throw new ApiException("Failed to read " + file, e);
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

    /** A bare marketplace id resolves to that marketplace on the default production host. */
    private static String marketplaceEndpoint(String marketplaceId) {
        return "https://api.flexemarkets.com/api/marketplaces/" + marketplaceId;
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
