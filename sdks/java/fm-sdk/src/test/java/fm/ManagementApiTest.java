package fm;

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

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/api/tokens", exchange ->
                respond(exchange, 200, """
                    {"token":"%s",
                     "person":{"id":7,"accountId":1,"email":"dev@dev"},
                     "account":{"id":1,"name":"dev"}}
                    """.formatted(TOKEN)));

        // Session transitions: one handler, echoing back the state implied by
        // the path so a test can tell open from close.
        for (var transition : List.of("open", "pause", "close")) {
            server.createContext("/api/marketplaces/1/" + transition, exchange -> {
                record(exchange);
                var state = switch (transition) {
                    case "open" -> "OPEN";
                    case "pause" -> "PAUSED";
                    default -> "CLOSED";
                };
                respond(exchange, 200,
                        "{\"id\":99,\"marketplaceId\":1,\"state\":\"%s\"}".formatted(state));
            });
        }

        server.createContext("/api/marketplaces/1/holdings", exchange -> {
            record(exchange);
            respond(exchange, 200,
                    "[{\"ownerId\":8,\"name\":\"alice\",\"cash\":10000,\"sessionId\":300}]");
        });

        server.createContext("/api/usersJson", exchange -> {
            record(exchange);
            respond(exchange, 200, "[{\"id\":7,\"email\":\"dev@dev\"},{\"id\":8,\"email\":\"t1@dev\"}]");
        });

        server.createContext("/api/v1/marketplaces/1/allotments", exchange -> {
            record(exchange);
            respond(exchange, 200, allotmentsJson());
        });

        server.createContext("/api/v1/marketplaces", exchange -> {
            record(exchange);
            respond(exchange, 200, "{\"id\":77,\"name\":\"simple-dividend\",\"markets\":[]}");
        });

        server.createContext("/api/marketplaces/1/allocations", exchange -> {
            record(exchange);
            respond(exchange, 200, allotmentsJson());
        });

        server.createContext("/api/marketplaces/1/holdings/downloads", exchange -> {
            record(exchange);
            respondCsv(exchange, "owner,cash\nalice,10000\n");
        });

        server.createContext("/api/marketplaces/1/holdings/uploads", exchange -> {
            record(exchange);
            respond(exchange, 200, allotmentsJson());
        });

        server.createContext("/api", exchange -> {
            record(exchange);
            respond(exchange, 200, """
                {"_links":{"marketplaces":{"href":"%1$s/marketplaces"},
                           "usersJson":{"href":"%1$s/usersJson"}}}
                """.formatted(api()));
        });

        server.start();
    }

    /** One allotment, spelling capital the way the server does: "grants". */
    private static String allotmentsJson() {
        return """
            [{"id":5,"allocationId":42,"marketplaceId":1,"ownerId":8,"name":"alice",
              "assets":{"cash":10000,"grants":[{"marketId":10,"units":50}]}}]
            """;
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void record(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void respondCsv(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/csv");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String api() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }

    private Flexemarkets connect() throws IOException {
        return Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "management-test");
    }

    private String bodyOf(String requestPrefix) {
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).startsWith(requestPrefix)) {
                return bodies.get(i);
            }
        }
        throw new AssertionError("no request matching '" + requestPrefix + "' in " + requests);
    }

    @Test
    void sessionTransitionsArePatchesToTheirOwnRoutes() throws Exception {
        try (Flexemarkets fm = connect()) {
            assertThat(fm.openSession(1).state()).isEqualTo("OPEN");
            assertThat(fm.pauseSession(1).state()).isEqualTo("PAUSED");
            assertThat(fm.closeSession(1).state()).isEqualTo("CLOSED");
        }

        assertThat(requests)
                .contains("PATCH /api/marketplaces/1/open")
                .contains("PATCH /api/marketplaces/1/pause")
                .contains("PATCH /api/marketplaces/1/close");
    }

    @Test
    void usersReadsTheJsonRouteRatherThanTheHalOne() throws Exception {
        List<Types.Person> users;
        try (Flexemarkets fm = connect()) {
            users = fm.users();
        }

        assertThat(users).hasSize(2);
        assertThat(users.get(1).email()).isEqualTo("t1@dev");
        assertThat(requests).contains("GET /api/usersJson");
    }

    @Test
    void allotmentsAreReadFromTheV1RouteForOneAllocation() throws Exception {
        List<Types.Allotment> allotments;
        try (Flexemarkets fm = connect()) {
            allotments = fm.allotments(1, 42);
        }

        assertThat(allotments).hasSize(1);
        assertThat(allotments.get(0).assets().securities().get(0).units()).isEqualTo(50L);
        assertThat(requests).contains("GET /api/v1/marketplaces/1/allotments?allocation=42");
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
        var holding = new Types.Holding(1, 0, 0, 8, "alice", 10000, 10000,
                List.of(new Types.Security(10L, 50L, 50L, true, true)));

        try (Flexemarkets fm = connect()) {
            fm.allocate(1, List.of(holding));
        }

        var body = bodyOf("POST /api/marketplaces/1/allocations");
        assertThat(body)
                .as("the server reads positions from 'grants'")
                .contains("\"grants\"")
                .doesNotContain("\"securities\"");
        assertThat(body).contains("\"cash\":10000").contains("\"ownerId\":8");
    }

    @Test
    void allocateReturnsWhatTheServerCreated() throws Exception {
        var holding = new Types.Holding(1, 0, 0, 8, "alice", 10000, 10000, List.of());

        List<Types.Holding> created;
        try (Flexemarkets fm = connect()) {
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
        Types.Marketplace created;
        try (Flexemarkets fm = connect()) {
            created = fm.createMarketplaceFromJson(
                    "{\"name\":\"simple-dividend\",\"markets\":[{\"symbol\":\"STK\"}]}");
        }

        assertThat(created.id()).isEqualTo(77L);
        assertThat(requests).contains("POST /api/v1/marketplaces");
        assertThat(bodyOf("POST /api/v1/marketplaces"))
                .as("the definition is forwarded, not rebuilt")
                .contains("\"STK\"");
    }

    /**
     * Parsed before it is sent, so a malformed definition fails here rather
     * than as a 400 whose message is about a document the caller cannot see.
     */
    @Test
    void malformedMarketplaceJsonFailsBeforeAnyRequest() throws Exception {
        try (Flexemarkets fm = connect()) {
            assertThatThrownBy(() -> fm.createMarketplaceFromJson("{not json"))
                    .isInstanceOf(Exceptions.ApiException.class)
                    .hasMessageContaining("not valid JSON");
        }

        assertThat(requests).noneSatisfy(r -> assertThat(r).contains("POST /api/v1/marketplaces"));
    }

    /**
     * A settlement reads a finished run's positions, which belong to its
     * session rather than to now.
     */
    @Test
    void holdingsCanBeReadForParticularSessions() throws Exception {
        List<Types.Holding> holdings;
        try (Flexemarkets fm = connect()) {
            holdings = fm.holdings(1, List.of(300L, 301L));
        }

        assertThat(holdings).singleElement()
                .satisfies(h -> assertThat(h.sessionId()).isEqualTo(300L));
        assertThat(requests).contains("GET /api/marketplaces/1/holdings?sessions=300,301");
    }

    /** An empty filter means "now", not "no sessions" — and asks for no filter. */
    @Test
    void anEmptySessionFilterFallsBackToTheCurrentHoldings() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.holdings(1, List.of());
        }

        assertThat(requests).contains("GET /api/marketplaces/1/holdings");
    }

    /** A CSV, returned as-is. Parsing it as JSON would fail on the header row. */
    @Test
    void downloadHoldingsReturnsTheCsvVerbatim() throws Exception {
        String csv;
        try (Flexemarkets fm = connect()) {
            csv = fm.downloadHoldings(1);
        }

        assertThat(csv).isEqualTo("owner,cash\nalice,10000\n");
    }

    @Test
    void uploadHoldingsPostsTheFileAsMultipart(@TempDir Path dir) throws Exception {
        var csv = dir.resolve("holdings.csv");
        Files.writeString(csv, "owner,cash\nalice,10000\n");

        List<Types.Holding> created;
        try (Flexemarkets fm = connect()) {
            created = fm.uploadHoldings(1, csv);
        }

        var body = bodyOf("POST /api/marketplaces/1/holdings/uploads");
        assertThat(body)
                .as("the part must be named 'file' and carry the file's own name")
                .contains("name=\"file\"")
                .contains("filename=\"holdings.csv\"")
                .contains("owner,cash");
        assertThat(created).hasSize(1);
    }

    /**
     * The defaults exist so that implementations which cannot manage sessions --
     * test fakes, read-only providers -- keep compiling when this surface grows.
     * They have to fail in a way that names what was called and by whom, or the
     * cost of that convenience is an unexplained failure in someone else's code.
     */
    @Test
    void anImplementationThatCannotManageSaysSo() {
        var readOnly = new Flexemarkets() {
            public Types.Account account() { return null; }
            public long accountId() { return 0; }
            public String accountName() { return null; }
            public Types.Person user() { return null; }
            public long userId() { return 0; }
            public long endpointMarketplaceId() { return 0; }
            public String endpointUrl() { return null; }
            public List<Types.Marketplace> marketplaces() { return List.of(); }
            public Types.Marketplace marketplace(long id) { return null; }
            public List<Types.Market> markets(long id) { return List.of(); }
            public List<Types.Session> sessions(long id) { return List.of(); }
            public Types.Session session(long id) { return null; }
            public List<Types.Order> orders(long id) { return List.of(); }
            public List<Types.Holding> holdings(long id) { return List.of(); }
            public Types.Holding holding(long id) { return null; }
            public List<Types.ClientConnection> connections(long id) { return List.of(); }
            public Snapshot<List<Types.Order>> activeOrdersV1(long id) { return null; }
            public Snapshot<List<Types.Order>> recentTradesV1(long id, int size) { return null; }
            public Snapshot<List<Types.Order>> recentTradesV1(long id) { return null; }
            public Types.Order submitLimit(long m, long k, String s, long u, long p) { return null; }
            public Types.Order submitCancel(long m, long k, long o) { return null; }
            public Types.Order submitMarket(long m, long k, String s, long u) { return null; }
            public void listen(long id, BlockingQueue<Object> queue) { }
            public MarketView observe(long id) { return null; }
            public void reconnect() { }
            public void close() { }
        };

        assertThatThrownBy(() -> readOnly.openSession(1))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("openSession")
                .hasMessageContaining(readOnly.getClass().getName());
    }
}
