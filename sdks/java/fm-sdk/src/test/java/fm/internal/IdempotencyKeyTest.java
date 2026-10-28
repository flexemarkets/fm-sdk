package fm.internal;

import fm.Flexemarkets;
import fm.error.ApiException;
import fm.error.InvalidArgumentException;
import fm.model.Holding;
import fm.model.Security;
import fm.model.TickGrid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A create whose answer is lost is sent again, and makes one thing.
 *
 * <p>The order path's submissionId (SubmissionIdTest), for everything else a
 * client creates: a marketplace, a market, a user, an allocation, widgets, a
 * call. Each is sent under its own {@code Idempotency-Key}, and fm-server 4.17
 * answers a key it already holds with what it answered the first time (its
 * IdempotencyFilterTest), so the client can send it once more itself.
 */
class IdempotencyKeyTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";
    private static final String CALL = """
            {"id":91,"marketplaceId":1,"marketId":12,"sessionId":77,"calledDate":"2026-10-25T09:00:05",
             "trigger":"MANUAL","price":550,"volume":2,"demand":3,"supply":2,"buyOrders":2,"sellOrders":1,
             "carry":true}""";

    /** What to do with each create in turn: "lose" closes without an answer, a status refuses. */
    private final Deque<Object> plan = new ArrayDeque<>();
    /** Each create as "METHOD path key", the key "-" when none was sent. */
    private final List<String> sent = new ArrayList<>();
    private HttpServer server;

    @TempDir
    Path dir;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/tokens", exchange -> respond(exchange, 200, """
            {"token":"%s",
             "person":{"id":7,"accountId":1,"email":"dev@dev"},
             "account":{"id":1,"name":"dev"}}
            """.formatted(TOKEN)));
        server.createContext("/api/v1/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            sent.add(exchange.getRequestMethod() + " " + path + " " + (null == key ? "-" : key));
            Object step = plan.poll();
            if ("lose".equals(step)) {
                exchange.close();
                return;
            }
            if (step instanceof Integer status) {
                respond(exchange, status, "{\"error\":\"REQUEST_INVALID\",\"message\":\"no\"}");
                return;
            }
            respond(exchange, 201, _answerTo(path));
        });
        server.start();
    }

    private static String _answerTo(String path) {
        if (path.endsWith("/calls")) {
            return CALL;
        }
        if (path.endsWith("/allocations") || path.endsWith("/widgets")) {
            return "[]";
        }
        return "{\"id\":5}";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Flexemarkets connect() throws IOException {
        return Flexemarkets.connect(TOKEN,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/marketplaces/1",
                "idempotency-key-test");
    }

    private static String keyOf(String request) {
        return request.substring(request.lastIndexOf(' ') + 1);
    }

    @Test
    void everyCreateNamesItsOwnKey() throws Exception {
        Path csv = Files.writeString(dir.resolve("holdings.csv"), "name,cash\nalice,100\n");
        var grid = new TickGrid(1, 100, 1);
        var holding = new Holding(1, 0, 0, 8, "alice", 10000, 10000,
                List.of(new Security(10L, 50L, 50L, 0L, true, true)));

        try (Flexemarkets fm = connect()) {
            fm.createMarketplaceFromJson("{\"name\":\"mp\"}");
            fm.createMarket(1, "STK", "Stock", grid, grid, false);
            fm.createCallMarket(1, "CLL", "Call", grid, grid, 60, true);
            fm.createUser("u@x", "pw", "U", "X", "USER");
            fm.allocate(1, List.of(holding));
            fm.uploadHoldings(1, csv);
            fm.uploadHoldingsCsv(1, "name,cash\nalice,100\n");
            fm.pushWidgets(1, List.of());
            fm.clearNow(1, 12);
        }

        assertThat(sent).extracting(r -> r.substring(0, r.lastIndexOf(' '))).containsExactly(
                "POST /api/v1/marketplaces",
                "POST /api/v1/marketplaces/1/markets",
                "POST /api/v1/marketplaces/1/markets",
                "POST /api/v1/users",
                "POST /api/v1/marketplaces/1/allocations",
                "POST /api/v1/marketplaces/1/allocations",
                "POST /api/v1/marketplaces/1/allocations",
                "POST /api/v1/marketplaces/1/widgets",
                "POST /api/v1/marketplaces/1/calls");
        assertThat(sent).extracting(IdempotencyKeyTest::keyOf)
                .doesNotContain("-")
                .doesNotHaveDuplicates();
    }

    @Test
    void aPostThatIsNotACreateCarriesNoKey() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.managerOtpBundle(List.of(7L));
        }

        assertThat(sent).singleElement().asString().endsWith(" -");
    }

    @Test
    void aCreateWhoseAnswerWasLostIsSentOnceMoreUnderTheSameKey() throws Exception {
        plan.add("lose");

        try (Flexemarkets fm = connect()) {
            assertThat(fm.clearNow(1, 12).id()).isEqualTo(91L);
        }

        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).as("the same create, the same key").isEqualTo(sent.get(0));
    }

    @Test
    void aCsvCreateWhoseAnswerWasLostIsSentOnceMoreUnderTheSameKey() throws Exception {
        plan.add("lose");

        try (Flexemarkets fm = connect()) {
            fm.uploadHoldingsCsv(1, "name,cash\nalice,100\n");
        }

        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).isEqualTo(sent.get(0));
    }

    @Test
    void aRefusedCreateIsNotSentAgain() throws Exception {
        plan.add(400);

        try (Flexemarkets fm = connect()) {
            assertThatThrownBy(() -> fm.clearNow(1, 12)).isInstanceOf(InvalidArgumentException.class);
        }

        assertThat(sent).hasSize(1);
    }

    @Test
    void aCreateLostTwiceIsReportedAndNotSentAThirdTime() throws Exception {
        plan.add("lose");
        plan.add("lose");

        try (Flexemarkets fm = connect()) {
            assertThatThrownBy(() -> fm.clearNow(1, 12))
                .isInstanceOf(ApiException.class)
                .hasCauseInstanceOf(IOException.class);
        }

        assertThat(sent).hasSize(2);
    }
}
