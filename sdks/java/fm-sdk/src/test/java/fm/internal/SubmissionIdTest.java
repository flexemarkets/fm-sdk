package fm.internal;

import fm.Flexemarkets;
import fm.error.ApiException;
import fm.error.InvalidArgumentException;
import fm.model.OrderSide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An order whose answer is lost is sent again, and trades once.
 *
 * <p>A send that times out or loses its connection may or may not have reached
 * the exchange. Reported as a failure, it invited the caller to place the
 * order again, and if the first had landed the position doubled with nothing
 * anywhere saying so. Each order now names its submission, and fm-server
 * answers a submission it already holds with the order it made (its
 * OrderIdempotencyTest), so the client can send it once more itself.
 */
class SubmissionIdTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";
    private static final Pattern SUBMISSION = Pattern.compile("\"submissionId\":\"([^\"]+)\"");

    /** What to do with each order in turn: "lose" closes without an answer, a status refuses. */
    private final Deque<Object> plan = new ArrayDeque<>();
    private final List<String> submitted = new ArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/tokens", exchange -> respond(exchange, 200, """
            {"token":"%s",
             "person":{"id":7,"accountId":1,"email":"dev@dev"},
             "account":{"id":1,"name":"dev"}}
            """.formatted(TOKEN)));
        server.createContext("/api/v1/marketplaces/1/orders", exchange -> {
            submitted.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Object step = plan.poll();
            if ("lose".equals(step)) {
                exchange.close();
                return;
            }
            if (step instanceof Integer status) {
                respond(exchange, status, "{\"error\":\"ORDER_INVALID\"}");
                return;
            }
            respond(exchange, 200, "{\"id\":42,\"marketplaceId\":1,\"marketId\":11}");
        });
        server.start();
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
                "submission-id-test");
    }

    private static String submissionOf(String order) {
        var m = SUBMISSION.matcher(order);
        assertThat(m.find()).as("a submissionId in %s", order).isTrue();
        return m.group(1);
    }

    @Test
    void eachOrderNamesItsOwnSubmission() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.submitLimit(1L, 11L, OrderSide.BUY, 5L, 135L);
            fm.submitLimit(1L, 11L, OrderSide.BUY, 5L, 135L);
        }

        assertThat(submissionOf(submitted.get(0))).isNotEqualTo(submissionOf(submitted.get(1)));
    }

    @Test
    void anOrderWhoseAnswerWasLostIsSentOnceMoreAsTheSameSubmission() throws Exception {
        plan.add("lose");

        try (Flexemarkets fm = connect()) {
            assertThat(fm.submitLimit(1L, 11L, OrderSide.BUY, 5L, 135L).id()).isEqualTo(42L);
        }

        assertThat(submitted).hasSize(2);
        assertThat(submitted.get(1)).as("the same order, the same submission").isEqualTo(submitted.get(0));
    }

    @Test
    void aRefusedOrderIsNotSentAgain() throws Exception {
        plan.add(400);

        try (Flexemarkets fm = connect()) {
            assertThatThrownBy(() -> fm.submitLimit(1L, 11L, OrderSide.BUY, 5L, 135L))
                .isInstanceOf(InvalidArgumentException.class);
        }

        assertThat(submitted).hasSize(1);
    }

    @Test
    void anOrderLostTwiceIsReportedAndNotSentAThirdTime() throws Exception {
        plan.add("lose");
        plan.add("lose");

        try (Flexemarkets fm = connect()) {
            assertThatThrownBy(() -> fm.submitLimit(1L, 11L, OrderSide.BUY, 5L, 135L))
                .isInstanceOf(ApiException.class)
                .hasCauseInstanceOf(IOException.class);
        }

        assertThat(submitted).hasSize(2);
    }
}
