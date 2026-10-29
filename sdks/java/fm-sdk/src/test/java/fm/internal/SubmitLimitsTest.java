package fm.internal;

import fm.Flexemarkets;
import fm.PendingOrder;
import fm.error.ApiException;
import fm.error.AuthorizationException;
import fm.error.ConflictException;
import fm.error.FlexemarketsException;
import fm.error.InvalidArgumentException;
import fm.model.Limit;
import fm.model.Order;
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
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Several limit orders in one request: {@code POST .../orders/batch} (fm-sdk#7).
 *
 * <p>REST one order at a time is bounded by the round trip, and several
 * requests at once reach the book out of order. A list is ordered by
 * construction; fm-server runs it in list order and answers each order as its
 * own POST would have. Here the answer is checked to come back to the caller
 * as {@code submitLimit}'s would: the order, or the exception its refusal
 * throws.
 */
class SubmitLimitsTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    /**
     * What to do with each request in turn: "lose" closes without an answer,
     * "short" answers one entry too few, a status refuses the whole request,
     * and anything else answers each order by its price (see {@link #entry}).
     */
    private final Deque<Object> plan = new ArrayDeque<>();
    /** Each batch request's body. */
    private final List<ArrayNode> requests = new ArrayList<>();
    private int placed;
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/tokens", exchange -> respond(exchange, 200, """
            {"token":"%s",
             "person":{"id":7,"accountId":1,"email":"dev@dev"},
             "account":{"id":1,"name":"dev"}}
            """.formatted(TOKEN)));
        server.createContext("/api/v1/marketplaces/1/orders/batch", exchange -> {
            var body = (ArrayNode) HttpFlexemarkets.MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            requests.add(body);
            Object step = plan.poll();
            if ("lose".equals(step)) {
                exchange.close();
                return;
            }
            if (step instanceof Integer status) {
                respond(exchange, status, "{\"status\":\"FORBIDDEN\",\"error\":\"ACCESS_DENIED\",\"message\":\"No.\"}");
                return;
            }
            var answers = HttpFlexemarkets.MAPPER.createArrayNode();
            for (JsonNode order : body) {
                answers.add(entry(order));
            }
            if ("short".equals(step)) {
                answers.remove(0);
            }
            respond(exchange, 200, answers.toString());
        });
        server.start();
    }

    /** An order's answer by its price: 999 is refused for assets, 998 already cancelled, anything else placed. */
    private ObjectNode entry(JsonNode order) {
        var mapper = HttpFlexemarkets.MAPPER;
        long price = order.get("price").asLong();
        if (999 == price) {
            return (ObjectNode) mapper.readTree("""
                {"status":400,"failure":{"status":"BAD_REQUEST","error":"ORDER_INSUFFICIENT_ASSETS","message":"Insufficient assets."}}""");
        }
        if (998 == price) {
            return (ObjectNode) mapper.readTree("""
                {"status":409,"failure":{"status":"CONFLICT","error":"ORDER_ALREADY_CANCELLED","message":"Already cancelled."}}""");
        }
        var answer = mapper.createObjectNode();
        answer.put("status", 201);
        var placedOrder = answer.putObject("order");
        placedOrder.put("id", 1000 + placed++);
        placedOrder.put("marketplaceId", 1);
        placedOrder.set("marketId", order.get("marketId"));
        placedOrder.put("type", "LIMIT");
        placedOrder.set("side", order.get("side"));
        placedOrder.set("units", order.get("units"));
        placedOrder.set("price", order.get("price"));
        placedOrder.set("submissionId", order.get("submissionId"));
        return answer;
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
                "submit-limits-test");
    }

    private static Limit limit(long price) {
        return new Limit(11L, OrderSide.BUY, 1L, price);
    }

    private static List<Long> prices(List<PendingOrder> answered) {
        return answered.stream().map(PendingOrder::join).map(Order::price).toList();
    }

    @Test
    void theOrdersGoInOneRequestInOrder_andComeBackInIt() throws Exception {
        List<PendingOrder> answered;
        try (Flexemarkets fm = connect()) {
            answered = fm.submitLimits(1L, List.of(limit(150), new Limit(11L, OrderSide.SELL, 2L, 160L),
                                                   new Limit(12L, OrderSide.BUY, 3L, 170L, null, null, "mine")));
        }

        assertThat(requests).hasSize(1);
        var body = requests.get(0);
        assertThat(body.valueStream().map(o -> o.get("price").asLong())).containsExactly(150L, 160L, 170L);
        assertThat(body.valueStream().map(o -> o.get("marketId").asLong())).containsExactly(11L, 11L, 12L);
        assertThat(body.get(2).get("clientDescription").asString()).isEqualTo("mine");
        assertThat(body.get(0).get("clientDescription").asString()).isEqualTo("submit-limits-test");
        var ids = body.valueStream().map(o -> o.get("submissionId").asString()).toList();
        assertThat(ids).as("every order names its own submission").doesNotHaveDuplicates().hasSize(3);

        assertThat(answered).allMatch(PendingOrder::isDone);
        assertThat(prices(answered)).containsExactly(150L, 160L, 170L);
        assertThat(answered.stream().map(PendingOrder::submissionId)).containsExactlyElementsOf(ids);
    }

    @Test
    void aRefusedOrderCompletesWithWhatSubmitLimitThrows_andTheRestArePlaced() throws Exception {
        List<PendingOrder> answered;
        try (Flexemarkets fm = connect()) {
            answered = fm.submitLimits(1L, List.of(limit(150), limit(999), limit(998), limit(151)));
        }

        assertThat(answered.get(0).join().price()).isEqualTo(150L);
        assertThatThrownBy(answered.get(1)::join).isInstanceOf(CompletionException.class)
                .cause().isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("Insufficient assets.")
                .satisfies(e -> {
                    assertThat(((FlexemarketsException) e).statusCode()).isEqualTo(Integer.valueOf(400));
                    assertThat(((FlexemarketsException) e).reason()).isEqualTo("ORDER_INSUFFICIENT_ASSETS");
                });
        assertThatThrownBy(answered.get(2)::join).cause().isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((FlexemarketsException) e).reason()).isEqualTo("ORDER_ALREADY_CANCELLED"));
        assertThat(answered.get(3).join().price()).isEqualTo(151L);
    }

    @Test
    void aLongListGoesAsSeveralRequests_oneAfterTheOther() throws Exception {
        var limits = IntStream.range(0, HttpFlexemarkets.MOST_BATCHED * 2 + 1).mapToObj(i -> limit(100 + i)).toList();
        List<PendingOrder> answered;
        try (Flexemarkets fm = connect()) {
            answered = fm.submitLimits(1L, limits);
        }

        assertThat(requests.stream().map(ArrayNode::size))
                .containsExactly(HttpFlexemarkets.MOST_BATCHED, HttpFlexemarkets.MOST_BATCHED, 1);
        assertThat(requests.stream().flatMap(ArrayNode::valueStream).map(o -> o.get("price").asLong()))
                .containsExactlyElementsOf(limits.stream().map(Limit::price).toList());
        assertThat(prices(answered)).containsExactlyElementsOf(limits.stream().map(Limit::price).toList());
    }

    @Test
    void nothingToPlaceSendsNothing() throws Exception {
        try (Flexemarkets fm = connect()) {
            assertThat(fm.submitLimits(1L, List.of())).isEmpty();
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void aRequestWhoseAnswerWasLostIsSentOnceMore_whole() throws Exception {
        plan.add("lose");

        List<PendingOrder> answered;
        try (Flexemarkets fm = connect()) {
            answered = fm.submitLimits(1L, List.of(limit(150), limit(151)));
        }

        assertThat(requests).hasSize(2);
        assertThat(requests.get(1)).as("the same orders, the same submissions").isEqualTo(requests.get(0));
        assertThat(prices(answered)).containsExactly(150L, 151L);
    }

    @Test
    void aRefusalOfTheWholeRequestIsThrown_andNotSentAgain() throws Exception {
        plan.add(403);

        try (Flexemarkets fm = connect()) {
            assertThatThrownBy(() -> fm.submitLimits(1L, List.of(limit(150)))).isInstanceOf(AuthorizationException.class);
        }
        assertThat(requests).hasSize(1);
    }

    @Test
    void anAnswerWithoutOneEntryPerOrderIsAnApiException() throws Exception {
        plan.add("short");

        try (Flexemarkets fm = connect()) {
            assertThatThrownBy(() -> fm.submitLimits(1L, List.of(limit(150), limit(151))))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void bothCounterpartyNamesAreRefusedBeforeAnythingIsSent() throws Exception {
        try (Flexemarkets fm = connect()) {
            assertThatThrownBy(() -> fm.submitLimits(1L, List.of(limit(150),
                    new Limit(11L, OrderSide.BUY, 1L, 150L, "a", 3L, null))))
                    .isInstanceOf(InvalidArgumentException.class);
        }
        assertThat(requests).isEmpty();
    }
}
