package fm;

import fm.model.Allotment;
import fm.model.Assets;
import fm.model.ClientConnection;
import fm.model.Holding;
import fm.model.Market;
import fm.model.Marketplace;
import fm.model.Order;
import fm.model.Person;
import fm.model.Security;
import fm.model.Session;
import fm.error.ApiException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The management surface: opening and closing sessions, setting opening
 * positions, and reading them back.
 *
 * <p>This is what running an experiment consists of, as opposed to trading in
 * one, and it is the half of {@code fm-lib-net} the SDK did not have. Every
 * study in fm-robots drives this sequence, which is why they were all still on
 * fm-lib-net -- and, through it, on Spring WebFlux.
 *
 * <p>Asserted against a real loopback server rather than a mocked client,
 * following {@code TokenAuthenticationTest}: what matters here is the request
 * that actually goes out -- its verb, its path, and above all the field names
 * in its body -- and a mock would assert only that the SDK called itself the
 * way the test expected.
 */
class ManagementApiTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private HttpServer _server;
    private final List<String> _requests = new ArrayList<>();
    private final List<String> _bodies = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        _server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        _server.createContext("/api/tokens", exchange ->
                _respond(exchange, 200, """
                    {"token":"%s",
                     "person":{"id":7,"accountId":1,"email":"dev@dev"},
                     "account":{"id":1,"name":"dev"}}
                    """.formatted(TOKEN)));

        // Session transitions: one handler, echoing back the state implied by
        // the path so a test can tell open from close.
        for (var transition : List.of("open", "pause", "close")) {
            _server.createContext("/api/marketplaces/1/" + transition, exchange -> {
                _record(exchange);
                var state = switch (transition) {
                    case "open" -> "OPEN";
                    case "pause" -> "PAUSED";
                    default -> "CLOSED";
                };
                _respond(exchange, 200,
                        "{\"id\":99,\"marketplaceId\":1,\"state\":\"%s\"}".formatted(state));
            });
        }

        _server.createContext("/api/marketplaces/1/holdings", exchange -> {
            _record(exchange);
            _respond(exchange, 200,
                    "[{\"ownerId\":8,\"name\":\"alice\",\"cash\":10000,\"sessionId\":300}]");
        });

        _server.createContext("/api/v1/marketplaces/1/sessions", exchange -> {
            _record(exchange);
            _respond(exchange, 200, "[{\"id\":300,\"state\":\"CLOSED\"}]");
        });

        _server.createContext("/api/marketplaces/1/connections", exchange -> {
            _record(exchange);
            _respond(exchange, 200,
                    "[{\"id\":9,\"ownerId\":8,\"marketplaceId\":1,\"sessionId\":300}]");
        });

        _server.createContext("/api/symbolOrdersJson", exchange -> {
            _record(exchange);
            // An order keeps its own id; only the symbol is absent.
            _respond(exchange, 200,
                    "[{\"id\":11,\"original\":7,\"units\":5,\"price\":950}]");
        });

        _server.createContext("/api/sessionOrdersJson", exchange -> {
            _record(exchange);
            _respond(exchange, 200, "[{\"id\":12,\"original\":12,\"sessionId\":300}]");
        });

        _server.createContext("/api/symbolTradesJson", exchange -> {
            _record(exchange);
            // The symbol-keyed route answers with the trade id in "original"
            // and no symbol on the order.
            _respond(exchange, 200,
                    "[{\"id\":0,\"original\":4242,\"units\":5,\"price\":950}]");
        });

        _server.createContext("/api/usersJson", exchange -> {
            _record(exchange);
            _respond(exchange, 200, "[{\"id\":7,\"email\":\"dev@dev\"},{\"id\":8,\"email\":\"t1@dev\"}]");
        });

        _server.createContext("/api/v1/marketplaces/1/allotments", exchange -> {
            _record(exchange);
            _respond(exchange, 200, _allotmentsJson());
        });

        _server.createContext("/api/v1/marketplaces", exchange -> {
            _record(exchange);
            _respond(exchange, 200, "{\"id\":77,\"name\":\"simple-dividend\",\"markets\":[]}");
        });

        _server.createContext("/api/marketplaces/1/allocations", exchange -> {
            _record(exchange);
            _respond(exchange, 200, _allotmentsJson());
        });

        _server.createContext("/api/marketplaces/1/holdings/downloads", exchange -> {
            _record(exchange);
            _respondCsv(exchange, "owner,cash\nalice,10000\n");
        });

        _server.createContext("/api/marketplaces/1/holdings/uploads", exchange -> {
            _record(exchange);
            _respond(exchange, 200, _allotmentsJson());
        });

        _server.createContext("/api", exchange -> {
            _record(exchange);
            _respond(exchange, 200, """
                {"_links":{"marketplaces":{"href":"%1$s/marketplaces"},
                           "symbolTradesJson":{"href":"%1$s/symbolTradesJson"},
                           "symbolOrdersJson":{"href":"%1$s/symbolOrdersJson"},
                           "sessionOrdersJson":{"href":"%1$s/sessionOrdersJson"},
                           "usersJson":{"href":"%1$s/usersJson"}}}
                """.formatted(_api()));
        });

        _server.start();
    }

    /** One allotment, spelling capital the way the server does: "grants". */
    private static String _allotmentsJson() {
        return """
            [{"id":5,"allocationId":42,"marketplaceId":1,"ownerId":8,"name":"alice",
              "assets":{"cash":10000,"grants":[{"marketId":10,"units":50}]}}]
            """;
    }

    @AfterEach
    void stopServer() {
        if (_server != null) {
            _server.stop(0);
        }
    }

    private void _record(HttpExchange exchange) throws IOException {
        _requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
        _bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void _respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void _respondCsv(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/csv");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String _api() {
        return "http://127.0.0.1:" + _server.getAddress().getPort() + "/api";
    }

    private Flexemarkets _connect() throws IOException {
        return Flexemarkets.connect(TOKEN, _api() + "/marketplaces/1", "management-test");
    }

    private String _bodyOf(String requestPrefix) {
        for (int i = 0; i < _requests.size(); i++) {
            if (_requests.get(i).startsWith(requestPrefix)) {
                return _bodies.get(i);
            }
        }
        throw new AssertionError("no request matching '" + requestPrefix + "' in " + _requests);
    }

    @Test
    void sessionTransitionsArePatchesToTheirOwnRoutes() throws Exception {
        try (Flexemarkets fm = _connect()) {
            assertThat(fm.openSession(1).state()).isEqualTo("OPEN");
            assertThat(fm.pauseSession(1).state()).isEqualTo("PAUSED");
            assertThat(fm.closeSession(1).state()).isEqualTo("CLOSED");
        }

        assertThat(_requests)
                .contains("PATCH /api/marketplaces/1/open")
                .contains("PATCH /api/marketplaces/1/pause")
                .contains("PATCH /api/marketplaces/1/close");
    }

    @Test
    void usersReadsTheJsonRouteRatherThanTheHalOne() throws Exception {
        List<Person> users;
        try (Flexemarkets fm = _connect()) {
            users = fm.users();
        }

        assertThat(users).hasSize(2);
        assertThat(users.get(1).email()).isEqualTo("t1@dev");
        assertThat(_requests).contains("GET /api/usersJson");
    }

    @Test
    void allotmentsAreReadFromTheV1RouteForOneAllocation() throws Exception {
        List<Allotment> allotments;
        try (Flexemarkets fm = _connect()) {
            allotments = fm.allotments(1, 42);
        }

        assertThat(allotments).hasSize(1);
        assertThat(allotments.get(0).assets().securities().get(0).units()).isEqualTo(50L);
        assertThat(_requests).contains("GET /api/v1/marketplaces/1/allotments?allocation=42");
    }

    /**
     * The bug this type of test exists to catch, and it is silent: the server
     * reads the positions from {@code grants}. Send {@code securities} instead
     * and it finds none, creates the allocation with cash and no positions, and
     * answers 200. Everything downstream then runs an experiment whose
     * participants hold nothing.
     *
     * <p>{@code Assets.securities} therefore carries {@code @JsonProperty},
     * which binds both directions, and not {@code @JsonAlias}, which binds only
     * on the way in -- invisible until something serializes it, which nothing
     * did until allocate().
     */
    @Test
    void allocateSendsPositionsAsGrants() throws Exception {
        var holding = new Holding(1, 0, 0, 8, "alice", 10000, 10000,
                List.of(new Security(10L, 50L, 50L, 0L, true, true)));

        try (Flexemarkets fm = _connect()) {
            fm.allocate(1, List.of(holding));
        }

        var body = _bodyOf("POST /api/marketplaces/1/allocations");
        assertThat(body)
                .as("the server reads positions from 'grants'")
                .contains("\"grants\"")
                .doesNotContain("\"securities\"");
        assertThat(body).contains("\"cash\":10000").contains("\"ownerId\":8");
    }

    @Test
    void allocateReturnsWhatTheServerCreated() throws Exception {
        var holding = new Holding(1, 0, 0, 8, "alice", 10000, 10000, List.of());

        List<Holding> created;
        try (Flexemarkets fm = _connect()) {
            created = fm.allocate(1, List.of(holding));
        }

        assertThat(created).hasSize(1);
        var back = created.get(0);
        assertThat(back.ownerId()).isEqualTo(8L);
        assertThat(back.allocationId()).isEqualTo(42L);
        assertThat(back.cash()).isEqualTo(10000L);
        assertThat(back.availableCash())
                .as("an opening position has committed nothing")
                .isEqualTo(10000L);
        assertThat(back.sessionId())
                .as("an allotment predates the session it will be opened under")
                .isZero();
        assertThat(back.securities().get(0).marketId()).isEqualTo(10L);
    }

    @Test
    void aMarketplaceIsCreatedFromItsJsonDefinition() throws Exception {
        Marketplace created;
        try (Flexemarkets fm = _connect()) {
            created = fm.createMarketplaceFromJson(
                    "{\"name\":\"simple-dividend\",\"markets\":[{\"symbol\":\"STK\"}]}");
        }

        assertThat(created.id()).isEqualTo(77L);
        assertThat(_requests).contains("POST /api/v1/marketplaces");
        assertThat(_bodyOf("POST /api/v1/marketplaces"))
                .as("the definition is forwarded, not rebuilt")
                .contains("\"STK\"");
    }

    /**
     * Parsed before it is sent, so a malformed definition fails here rather
     * than as a 400 whose message is about a document the caller cannot see.
     */
    @Test
    void malformedMarketplaceJsonFailsBeforeAnyRequest() throws Exception {
        try (Flexemarkets fm = _connect()) {
            assertThatThrownBy(() -> fm.createMarketplaceFromJson("{not json"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("not valid JSON");
        }

        assertThat(_requests).noneSatisfy(r -> assertThat(r).contains("POST /api/v1/marketplaces"));
    }

    /**
     * A settlement reads a finished run's positions, which belong to its
     * session rather than to now.
     */
    @Test
    void holdingsCanBeReadForParticularSessions() throws Exception {
        List<Holding> holdings;
        try (Flexemarkets fm = _connect()) {
            holdings = fm.holdings(1, List.of(300L, 301L));
        }

        assertThat(holdings).singleElement()
                .satisfies(h -> assertThat(h.sessionId()).isEqualTo(300L));
        assertThat(_requests).contains("GET /api/marketplaces/1/holdings?sessions=300,301");
    }

    /** An empty filter means "now", not "no sessions" — and asks for no filter. */
    @Test
    void anEmptySessionFilterFallsBackToTheCurrentHoldings() throws Exception {
        try (Flexemarkets fm = _connect()) {
            fm.holdings(1, List.of());
        }

        assertThat(_requests).contains("GET /api/marketplaces/1/holdings");
    }

    /**
     * Sessions and connections are never filtered server-side, and the SDK no
     * longer pretends otherwise.
     *
     * <p>This test used to assert the opposite: that {@code sessions(1, ids)}
     * put {@code ?sessionIds=300,301} on the wire. It did -- and the server
     * ignored it. {@code GET /marketplaces/{id}/sessions} and
     * {@code /connections} accept only {@code format}, so the answer was the
     * whole history looking like a filtered one. Asserting the request without
     * asserting the response is how a defect becomes a requirement.
     */
    @Test
    void sessionsAndConnectionsAreNeverFilteredOnTheWire() throws Exception {
        try (Flexemarkets fm = _connect()) {
            fm.sessions(1);
            fm.connections(1);
        }

        assertThat(_requests).noneSatisfy(r -> assertThat(r).contains("sessionIds="));
        // sessions moved to V1, which needs no format= to avoid HAL; connections
        // has no V1 equivalent with these semantics and stays on V0 for now.
        assertThat(_requests).anySatisfy(r -> assertThat(r)
                .contains("/api/v1/marketplaces/1/sessions"));
        assertThat(_requests).anySatisfy(r -> assertThat(r)
                .contains("/api/marketplaces/1/connections?format="));
    }

    /** The holdings download spells it {@code sessions}. */
    @Test
    void theHoldingsDownloadFiltersOnSessions() throws Exception {
        try (Flexemarkets fm = _connect()) {
            fm.downloadHoldings(1, List.of(300L));
        }

        assertThat(_requests).anySatisfy(r -> assertThat(r)
                .contains("/api/marketplaces/1/holdings/downloads?sessions=300"));
    }

    /**
     * A connection belongs to a session, and that is how a study works out who
     * was present in a run. The record had no such component until 0.0.11, so
     * every connection read as belonging to none.
     */
    @Test
    void aConnectionCarriesItsSession() throws Exception {
        List<ClientConnection> connections;
        try (Flexemarkets fm = _connect()) {
            connections = fm.connections(1);
        }

        assertThat(connections).singleElement()
                .satisfies(c -> assertThat(c.sessionId()).isEqualTo(300L));
    }

    /**
     * MarketTrades come back with the trade id in {@code original} and no symbol,
     * because the query already fixed it. Both are filled in, so the result is
     * a trade list rather than half-populated orders.
     */
    @Test
    void tradesCarryTheirIdAndSymbol() throws Exception {
        List<Order> trades;
        try (Flexemarkets fm = _connect()) {
            trades = fm.trades(1, "STK");
        }

        assertThat(trades).singleElement().satisfies(t -> {
            assertThat(t.id()).as("the trade id, taken from original").isEqualTo(4242L);
            assertThat(t.symbol()).isEqualTo("STK");
        });
        assertThat(_requests).anySatisfy(r -> assertThat(r).contains("symbol=STK"));
    }

    /**
     * Orders from a finished run come off a different route: the marketplace's
     * orders collection is current-session only, so filtering it is not
     * possible and asking it for an old session silently answers about now.
     */
    @Test
    void ordersCanBeReadForParticularSessions() throws Exception {
        List<Order> orders;
        try (Flexemarkets fm = _connect()) {
            orders = fm.orders(1, List.of(300L));
        }

        assertThat(orders).singleElement()
                .satisfies(o -> assertThat(o.sessionId()).isEqualTo(300L));
        assertThat(_requests).anySatisfy(r -> assertThat(r)
                .contains("/api/sessionOrdersJson?marketplaceId=1&sessionIds=300"));
    }

    /**
     * The symbol is filled in, and the id is not touched -- the difference from
     * {@link #tradesCarryTheirIdAndSymbol()}. An order has its own id; only a
     * trade carries it in {@code original}, and copying that here would give
     * every order the id of the order it was matched against.
     */
    @Test
    void symbolOrdersKeepTheirOwnId() throws Exception {
        List<Order> orders;
        try (Flexemarkets fm = _connect()) {
            orders = fm.orders(1, "STK");
        }

        assertThat(orders).singleElement().satisfies(o -> {
            assertThat(o.id()).as("the order's own id, not original").isEqualTo(11L);
            assertThat(o.original()).isEqualTo(7L);
            assertThat(o.symbol()).isEqualTo("STK");
        });
    }

    /** An empty filter means "now", and asks for no filter at all. */
    @Test
    void anEmptyFilterFallsBackToTheUnfilteredRoute() throws Exception {
        try (Flexemarkets fm = _connect()) {
            fm.downloadHoldings(1, List.of());
        }

        assertThat(_requests).noneSatisfy(r -> assertThat(r).contains("?sessions="));
    }

    /** A CSV, returned as-is. Parsing it as JSON would fail on the header row. */
    @Test
    void downloadHoldingsReturnsTheCsvVerbatim() throws Exception {
        String csv;
        try (Flexemarkets fm = _connect()) {
            csv = fm.downloadHoldings(1);
        }

        assertThat(csv).isEqualTo("owner,cash\nalice,10000\n");
    }

    @Test
    void uploadHoldingsPostsTheFileAsMultipart(@TempDir Path dir) throws Exception {
        var csv = dir.resolve("holdings.csv");
        Files.writeString(csv, "owner,cash\nalice,10000\n");

        List<Holding> created;
        try (Flexemarkets fm = _connect()) {
            created = fm.uploadHoldings(1, csv);
        }

        var body = _bodyOf("POST /api/marketplaces/1/holdings/uploads");
        assertThat(body)
                .as("the part must be named 'file' and carry the file's own name")
                .contains("name=\"file\"")
                .contains("filename=\"holdings.csv\"")
                .contains("owner,cash");
        assertThat(created).hasSize(1);
    }

    /**
     * What the roles buy: an implementation models what it can do, and the type
     * says so.
     *
     * <p>This used to build a fake that implemented the whole interface and
     * assert that the management methods threw at runtime, because they were
     * defaults. There are no such defaults now. A reader that cannot manage
     * does not implement {@link Management}, so the compiler refuses the call
     * and no test can reach it -- which is the improvement, and also why the
     * old assertion has nothing left to assert.
     *
     * <p>What is worth holding still is that narrowing works at all: a reader
     * can be written without stubbing sixty methods, and it is not a manager.
     */
    @Test
    void anImplementationCanModelReadingAlone() {
        Reading reader = new Reading() {
            public List<Marketplace> marketplaces() { return List.of(); }
            public Marketplace marketplace(long id) { return null; }
            public List<Market> markets(long id) { return List.of(); }
            public List<String> symbols(long id) { return List.of(); }
            public List<Session> sessions(long id) { return List.of(); }
            public List<Session> sessions(long id, List<Long> s) { return List.of(); }
            public Session session(long id) { return null; }
            public List<Order> orders(long id) { return List.of(); }
            public List<Order> orders(long id, List<Long> s) { return List.of(); }
            public List<Order> orders(long id, String symbol) { return List.of(); }
            public List<Order> trades(long id, String symbol) { return List.of(); }
            public List<Holding> holdings(long id) { return List.of(); }
            public List<Holding> holdings(long id, List<Long> s) { return List.of(); }
            public Holding holding(long id) { return null; }
            public String downloadHoldings(long id) { return ""; }
            public String downloadHoldings(long id, List<Long> s) { return ""; }
            public List<Allotment> allotments(long id, long allocationId) { return List.of(); }
            public List<Person> users() { return List.of(); }
            public List<ClientConnection> connections(long id) { return List.of(); }
            public List<ClientConnection> connections(long id, List<Long> s) { return List.of(); }
            public Snapshot<List<Order>> activeOrders(long id) { return null; }
            public Snapshot<List<Order>> recentTrades(long id, int size) { return null; }
            public Snapshot<List<Order>> recentTrades(long id) { return null; }
        };

        assertThat(reader.markets(1)).isEmpty();
        assertThat(reader)
                .as("a reader is not a manager, and the type is where that is said")
                .isNotInstanceOf(Management.class)
                .isNotInstanceOf(Flexemarkets.class);
    }
}
