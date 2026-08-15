package fm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The management surface: what running an experiment consists of, as opposed to
 * trading in one.
 *
 * <p>This is the half of {@code fm-lib-net} the SDK did not have. Every study in
 * fm-robots drives it, which is why they were all still on fm-lib-net -- and,
 * through it, on Spring WebFlux.
 *
 * <p>Asserted against a real loopback server rather than a mocked client,
 * following {@code TokenAuthenticationTest}: what matters is the request that
 * actually goes out -- its verb, its path, and the field names in its body -- and
 * a mock would assert only that the SDK called itself the way the test expected.
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

        // Session transitions: one handler, echoing back the state the path
        // implies so a test can tell open from close.
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

        server.createContext("/api/usersJson", exchange -> {
            record(exchange);
            respond(exchange, 200, "[{\"id\":7,\"email\":\"dev@dev\"},{\"id\":8,\"email\":\"t1@dev\"}]");
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

    private String api() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }

    private Flexemarkets connect() throws IOException {
        return Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "management-test");
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

    /**
     * The defaults exist so an implementation that only trades -- a test fake, a
     * read-only provider -- keeps compiling as this surface grows. They have to
     * fail in a way that names what was called and by whom, or the cost of that
     * convenience is an unexplained failure in someone else's code.
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
