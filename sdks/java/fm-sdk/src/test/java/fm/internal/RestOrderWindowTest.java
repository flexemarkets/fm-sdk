package fm.internal;

import fm.Flexemarkets;
import fm.OrderRoute;
import fm.PendingOrder;
import fm.error.ApiException;
import fm.error.AuthorizationException;
import fm.error.ConnectionFailedException;
import fm.error.InvalidArgumentException;
import fm.error.OrderOutcomeUnknownException;
import fm.model.Order;
import fm.model.OrderSide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * sendLimit over REST: one request in flight, the rest waiting (fm-sdk#7).
 *
 * <p>A client connected with {@link OrderRoute#REST} sends its orders as
 * {@code POST .../orders/batch}. One request is in flight at a time, and the
 * orders sent while it is out go together as the next: one at a time it is
 * submitLimit, in a burst the requests grow to the window. Two requests in
 * flight could reach the book out of order, which is what this exists to
 * prevent, so the first tests hold a request open and look at what queues
 * behind it.
 */
class RestOrderWindowTest {

    private static final long TIMEOUT_S = 5;

    /** A batch POST the test answers by hand: each call waits for release(). */
    static final class FakePost implements RestOrderWindow.Post {
        final List<List<Map<String, Object>>> batches = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger mostInFlight = new AtomicInteger();
        /** What the next calls do, in turn: an exception to throw, or null to answer. */
        final List<RuntimeException> plan = Collections.synchronizedList(new ArrayList<>());
        private final Semaphore _released = new Semaphore(0);
        private final Semaphore _arrived = new Semaphore(0);

        @Override
        public List<RestOrderWindow.Outcome> post(List<Map<String, Object>> batch) {
            batches.add(batch);
            mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            _arrived.release();
            try {
                assertThat(_released.tryAcquire(TIMEOUT_S, TimeUnit.SECONDS)).as("the test never answered").isTrue();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            inFlight.decrementAndGet();
            RuntimeException step = plan.isEmpty() ? null : plan.remove(0);
            if (null != step) {
                throw step;
            }
            return batch.stream().map(o -> {
                long price = (Long) o.get("price");
                return 999 == price
                        ? new RestOrderWindow.Outcome(null, new InvalidArgumentException("refused"))
                        : new RestOrderWindow.Outcome(order(price), null);
            }).toList();
        }

        void release() {
            _released.release();
        }

        void waitFor(int n) throws InterruptedException {
            assertThat(_arrived.tryAcquire(n, TIMEOUT_S, TimeUnit.SECONDS)).as("request never came").isTrue();
        }

        List<List<Long>> prices() {
            synchronized (batches) {
                return batches.stream().map(b -> b.stream().map(o -> (Long) o.get("price")).toList()).toList();
            }
        }
    }

    private static Order order(long price) {
        return HttpFlexemarkets.MAPPER.convertValue(Map.of("id", price, "price", price), Order.class);
    }

    private static PendingOrder submit(RestOrderWindow window, long price) {
        var body = new LinkedHashMap<String, Object>();
        body.put("price", price);
        body.put("submissionId", "s" + price);
        return window.submit("s" + price, body);
    }

    private static long priceOf(PendingOrder pending) throws Exception {
        return pending.get(TIMEOUT_S, TimeUnit.SECONDS).price();
    }

    @Test
    void oneOrderGoesAlone_andIsAnswered() throws Exception {
        var post = new FakePost();
        var window = new RestOrderWindow(post, 1L);

        var pending = submit(window, 150);
        post.waitFor(1);
        post.release();

        assertThat(priceOf(pending)).isEqualTo(150L);
        assertThat(post.prices()).containsExactly(List.of(150L));
    }

    @Test
    void ordersSentWhileARequestIsOut_goTogetherAsTheNext_inTheOrderSent() throws Exception {
        var post = new FakePost();
        var window = new RestOrderWindow(post, 1L);

        var first = submit(window, 100);
        post.waitFor(1);
        var behind = new ArrayList<PendingOrder>();
        for (int i = 0; i < 5; i++) {
            behind.add(submit(window, 101 + i));
        }
        post.release();
        post.waitFor(1);
        post.release();

        assertThat(priceOf(first)).isEqualTo(100L);
        var prices = new ArrayList<Long>();
        for (var p : behind) {
            prices.add(priceOf(p));
        }
        assertThat(prices).containsExactly(101L, 102L, 103L, 104L, 105L);
        assertThat(post.prices()).containsExactly(List.of(100L), List.of(101L, 102L, 103L, 104L, 105L));
        assertThat(post.mostInFlight.get()).as("never two requests in flight").isEqualTo(1);
    }

    @Test
    void aFullListMakesTheCallerWaitForRoom() throws Exception {
        var post = new FakePost();
        var window = new RestOrderWindow(post, 1L, 2);
        submit(window, 100);
        post.waitFor(1);
        submit(window, 101);
        submit(window, 102);

        var third = new Thread(() -> submit(window, 103));
        third.start();
        third.join(200);
        assertThat(third.isAlive()).as("a third order waits while two do").isTrue();

        post.release();     // the first answered; 101 and 102 go, 103 has room
        third.join(TIMEOUT_S * 1000);
        assertThat(third.isAlive()).isFalse();
        post.waitFor(1);
        post.release();
        post.waitFor(1);
        post.release();

        assertThat(post.prices()).containsExactly(List.of(100L), List.of(101L, 102L), List.of(103L));
    }

    @Test
    void aRefusedOrderFailsWithItsRefusal_andItsNeighboursArePlaced() throws Exception {
        var post = new FakePost();
        var window = new RestOrderWindow(post, 1L);
        submit(window, 100);
        post.waitFor(1);
        var refused = submit(window, 999);
        var placed = submit(window, 101);
        post.release();
        post.waitFor(1);
        post.release();

        assertThatThrownBy(refused::join).cause().isInstanceOf(InvalidArgumentException.class);
        assertThat(priceOf(placed)).isEqualTo(101L);
    }

    @Test
    void aRefusalOfTheWholeRequestIsEachOrdersRefusal() throws Exception {
        var post = new FakePost();
        post.plan.add(HttpFlexemarkets._failureFor(403, "{\"error\":\"ACCESS_DENIED\"}"));
        var window = new RestOrderWindow(post, 1L);

        var pending = submit(window, 100);
        post.waitFor(1);
        post.release();

        assertThatThrownBy(pending::join).cause().isInstanceOf(AuthorizationException.class);
    }

    @Test
    void aRequestWithNoReadableAnswerLeavesEachOrderUnknown_neverRefused() throws Exception {
        for (RuntimeException failure : List.of(
                new ApiException("lost twice", new IOException("reset")),
                HttpFlexemarkets._failureFor(503, "Service Unavailable"),
                new ApiException("Failed to parse the response body"))) {
            var post = new FakePost();
            post.plan.add(failure);
            var window = new RestOrderWindow(post, 1L);

            var pending = submit(window, 100);
            post.waitFor(1);
            post.release();

            assertThatThrownBy(pending::join).as("%s", failure).isInstanceOf(CompletionException.class)
                    .cause().isInstanceOf(OrderOutcomeUnknownException.class)
                    .satisfies(e -> assertThat(((OrderOutcomeUnknownException) e).submissionId()).isEqualTo("s100"));
        }
    }

    @Test
    void theWindowCarriesOnAfterAFailedRequest() throws Exception {
        var post = new FakePost();
        post.plan.add(new ApiException("lost twice", new IOException("reset")));
        var window = new RestOrderWindow(post, 1L);
        var lost = submit(window, 100);
        post.waitFor(1);
        post.release();
        assertThatThrownBy(lost::join).cause().isInstanceOf(OrderOutcomeUnknownException.class);

        var later = submit(window, 101);
        post.waitFor(1);
        post.release();
        assertThat(priceOf(later)).isEqualTo(101L);
    }

    @Test
    void closingRefusesWhatWaitsAsNotSent_andAnswersWhatIsInFlight() throws Exception {
        var post = new FakePost();
        var window = new RestOrderWindow(post, 1L);
        var inFlight = submit(window, 100);
        post.waitFor(1);
        var waiting = submit(window, 101);

        window.close();
        assertThatThrownBy(waiting::join).cause().isInstanceOf(ConnectionFailedException.class);
        assertThatThrownBy(() -> submit(window, 102)).isInstanceOf(ConnectionFailedException.class);
        post.release();

        assertThat(priceOf(inFlight)).isEqualTo(100L);
        assertThat(post.batches).as("nothing is sent once closed").hasSize(1);
    }

    @Test
    void closingWakesACallerWaitingForRoom_andRefusesItsOrder() throws Exception {
        var post = new FakePost();
        var window = new RestOrderWindow(post, 1L, 1);
        submit(window, 100);
        post.waitFor(1);
        submit(window, 101);
        var refused = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var blocked = new Thread(() -> {
            try {
                submit(window, 102);
            } catch (RuntimeException e) {
                refused.set(e);
            }
        });
        blocked.start();
        blocked.join(200);
        assertThat(blocked.isAlive()).isTrue();

        window.close();
        blocked.join(TIMEOUT_S * 1000);
        assertThat(refused.get()).isInstanceOf(ConnectionFailedException.class);
        post.release();
    }

    // -- through the client ---------------------------------------------------

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void aRestClientSendsSendLimitAndSendCancelAsBatches() throws Exception {
        var requests = Collections.synchronizedList(new ArrayList<ArrayNode>());
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/tokens", exchange -> respond(exchange, """
            {"token":"%s","person":{"id":7,"accountId":1,"email":"dev@dev"},"account":{"id":1,"name":"dev"}}
            """.formatted(TOKEN)));
        server.createContext("/api/v1/marketplaces/1/orders/batch", exchange -> {
            var body = (ArrayNode) HttpFlexemarkets.MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            requests.add(body);
            var answers = HttpFlexemarkets.MAPPER.createArrayNode();
            int i = 0;
            for (JsonNode o : body) {
                var entry = answers.addObject();
                entry.put("status", 201);
                var placed = entry.putObject("order");
                placed.put("id", 500 + i++);
                placed.set("type", o.get("type"));
                placed.put("price", o.has("price") ? o.get("price").asLong() : 0);
                placed.set("submissionId", o.get("submissionId"));
            }
            respond(exchange, answers.toString());
        });
        server.start();
        try (Flexemarkets fm = Flexemarkets.connect(TOKEN,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/marketplaces/1",
                "rest-order-window-test", OrderRoute.REST)) {
            var placed = fm.sendLimit(1L, 11L, OrderSide.BUY, 1L, 150L).get(TIMEOUT_S, TimeUnit.SECONDS);
            var cancel = fm.sendCancel(1L, 11L, placed.id()).get(TIMEOUT_S, TimeUnit.SECONDS);
            assertThat(placed.price()).isEqualTo(150L);
            assertThat(cancel.type()).hasToString("CANCEL");
        } finally {
            server.stop(0);
        }

        assertThat(requests).hasSize(2);
        var sent = requests.get(1).get(0);
        assertThat(sent.get("type").asString()).isEqualTo("CANCEL");
        assertThat(List.of(sent.get("id").asLong(), sent.get("original").asLong(), sent.get("supplier").asLong()))
                .as("a REST cancel names its order as a REST CANCEL always has")
                .containsOnly(500L);
    }

    @Test
    void anUnknownExceptionTypeFromThePostIsStillUnknown() throws Exception {
        // Not a FlexemarketsException at all: whatever broke, the order's fate is not known.
        var post = new FakePost();
        post.plan.add(new IllegalStateException("broken"));
        var window = new RestOrderWindow(post, 1L);
        var pending = submit(window, 100);
        post.waitFor(1);
        post.release();
        assertThatThrownBy(pending::join).cause().isInstanceOf(OrderOutcomeUnknownException.class);
    }
}
