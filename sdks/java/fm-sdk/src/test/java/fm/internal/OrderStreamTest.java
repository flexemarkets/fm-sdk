package fm.internal;

import static fm.internal.EventsSocketTest.message;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fm.PendingOrder;
import fm.error.AuthenticationException;
import fm.error.AuthorizationException;
import fm.error.ConnectionFailedException;
import fm.error.InvalidArgumentException;
import fm.error.OrderOutcomeUnknownException;
import fm.event.OrderAccepted;
import fm.event.OrderRejected;
import fm.event.OrdersUpdate;
import fm.event.StreamDropped;
import fm.event.StreamReconnected;
import fm.model.Order;
import fm.model.OrderSide;

/**
 * Orders over the socket, several in flight (fm-sdk#7).
 *
 * <p>As Python's test_order_stream and TypeScript's order-stream.test.ts. The
 * first tests run against a real WebSocket -- EventsSocketTest's server -- for
 * what only a socket shows: the frames on the wire, in the order sent, and what
 * a drop and a reconnect do to the orders still unanswered. The rest stub the
 * listener, to reach what a socket cannot be made to do on cue.
 */
class OrderStreamTest {

    private static final long MP = 7L;
    private static final String DESTINATION = "/app/marketplaces/7/orders";

    private EventsSocketTest._Server server;
    private final List<AutoCloseable> opened = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = new EventsSocketTest._Server();
    }

    @AfterEach
    void stop() throws Exception {
        for (var c : opened) c.close();
        server.close();
    }

    private static String order(String sid) {
        return "{\"marketplaceId\":7,\"marketId\":11,\"type\":\"LIMIT\",\"side\":\"SELL\",\"units\":1,"
                + "\"price\":900,\"submissionId\":\"" + sid + "\"}";
    }

    private static String row(String sid, long id) {
        return "{\"id\":" + id + ",\"original\":" + id + ",\"supplier\":" + id + ",\"type\":\"LIMIT\","
                + "\"side\":\"SELL\",\"units\":1,\"price\":900,\"mine\":true,\"submissionId\":"
                + (sid == null ? "null" : "\"" + sid + "\"") + "}";
    }

    private static Order parsed(String sid, long id) {
        return HttpFlexemarkets.MAPPER.readValue(row(sid, id), Order.class);
    }

    /** The submission ids of the orders sent on one connection, in order. */
    private List<String> sent(int connection) throws InterruptedException {
        var ids = new ArrayList<String>();
        for (var frame : server.connection(connection).received()) {
            if (!frame.startsWith("SEND\n")) continue;
            assertThat(frame).contains("destination:" + DESTINATION + "\n");
            var body = frame.substring(frame.indexOf("\n\n") + 2, frame.length() - 1);
            ids.add(HttpFlexemarkets.MAPPER.readTree(body).get("submissionId").asString());
        }
        return ids;
    }

    private static void until(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("timed out waiting for: " + what).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private static boolean sentCount(OrderStreamTest test, int connection, int count) {
        try {
            return test.server.connectionCount() > connection && test.sent(connection).size() == count;
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A stream on the test server, reconnecting after 20 ms rather than seconds. */
    private OrderStream onSocket() {
        var stream = new OrderStream(queue -> {
            var events = new Events(server.url(), () -> "Bearer the-token", MP, "fm-sdk-test",
                                    HttpFlexemarkets.MAPPER, queue, presented -> false) {
                @Override
                void _sleep(long millis) throws InterruptedException {
                    Thread.sleep(20);
                }
            };
            events.connect();
            return events;
        }, MP, OrderStream.MAX_IN_FLIGHT);
        opened.add(stream::close);
        return stream;
    }

    @Test
    @Timeout(20)
    void ordersGoOutBackToBackAndEachIsAnsweredByItsOwnRow() throws Exception {
        var stream = onSocket();
        var a = stream.submit("s-1", order("s-1"));
        var b = stream.submit("s-2", order("s-2"));
        var c = stream.submit("s-3", order("s-3"));
        until("three sent before any answer", () -> sentCount(this, 0, 3));
        assertThat(sent(0)).containsExactly("s-1", "s-2", "s-3");
        assertThat(a.isDone() || b.isDone() || c.isDone()).isFalse();

        server.connection(0).send(message("ORDERS-UPDATE", null,
                "[" + row("s-2", 102) + "," + row(null, 99) + "," + row("someone-else", 98) + "," + row("s-1", 101) + "]"));
        assertThat(a.get(5, TimeUnit.SECONDS).id()).isEqualTo(101);
        assertThat(b.get(5, TimeUnit.SECONDS).id()).isEqualTo(102);
        assertThat(c.isDone()).isFalse();
        server.connection(0).send(message("ORDERS-UPDATE", null, "[" + row("s-1", 555) + "," + row("s-3", 103) + "]"));
        assertThat(c.get(5, TimeUnit.SECONDS).id()).isEqualTo(103);
        assertThat(a.get().id()).isEqualTo(101);
    }

    @Test
    @Timeout(30)
    void unansweredOrdersAreSentAgainInOrderOnceTheSocketIsBack() throws Exception {
        var stream = onSocket();
        var first = stream.submit("s-1", order("s-1"));
        until("the first sent", () -> sentCount(this, 0, 1));

        server.refuseWith(503);
        server.connection(0).close();
        until("the drop noticed", () -> !stream.isUp());
        var held = stream.submit("s-2", order("s-2"));
        Thread.sleep(200);
        assertThat(sent(0)).containsExactly("s-1");
        assertThat(server.connectionCount()).isEqualTo(1);

        server.refuseWith(0);
        until("both sent again", () -> sentCount(this, 1, 2));
        assertThat(sent(1)).containsExactly("s-1", "s-2");
        server.connection(1).send(message("ORDER-ACCEPTED", "submission-id:s-1", row("s-1", 101)));
        server.connection(1).send(message("ORDERS-UPDATE", null, "[" + row("s-2", 102) + "]"));
        assertThat(first.get(5, TimeUnit.SECONDS).id()).isEqualTo(101);
        assertThat(held.get(5, TimeUnit.SECONDS).id()).isEqualTo(102);
    }

    @Test
    @Timeout(20)
    void aRefusedOrderFailsWithWhatRestThrowsForItsStatus() throws Exception {
        var stream = onSocket();
        var invalid = stream.submit("s-1", order("s-1"));
        var forbidden = stream.submit("s-2", order("s-2"));
        var unknownStatus = stream.submit("s-3", order("s-3"));
        var jsonString = stream.submit("s-4", order("s-4"));
        var notJson = stream.submit("s-5", order("s-5"));
        until("sent", () -> sentCount(this, 0, 5));
        var socket = server.connection(0);
        socket.send(message("ORDER-REJECTED", "submission-id:s-1",
                "{\"status\": \"BAD_REQUEST\", \"error\": \"ORDER_INVALID\", \"message\": \"Price is off the tick.\"}"));
        socket.send(message("ORDER-REJECTED", "submission-id:s-2",
                "{\"status\": \"FORBIDDEN\", \"message\": \"Observers cannot trade.\"}"));
        socket.send(message("ORDER-REJECTED", "submission-id:s-3", "{\"status\": \"NOT_A_STATUS\"}"));
        socket.send(message("ORDER-REJECTED", "submission-id:s-4", "\"a bare string\""));
        socket.send(message("ORDER-REJECTED", "submission-id:s-5", "not json at all"));

        assertThat(cause(invalid)).isInstanceOf(InvalidArgumentException.class)
                .hasMessage("Invalid request: Price is off the tick.");
        assertThat(cause(forbidden)).isInstanceOf(AuthorizationException.class)
                .hasMessage("Not permitted: Observers cannot trade.");
        assertThat(cause(unknownStatus)).isInstanceOf(InvalidArgumentException.class);
        assertThat(cause(jsonString)).isInstanceOf(InvalidArgumentException.class);
        assertThat(cause(notJson)).isInstanceOf(InvalidArgumentException.class)
                .hasMessage("Invalid request: not json at all");
    }

    private static Throwable cause(PendingOrder pending) throws InterruptedException {
        try {
            pending.get(5, TimeUnit.SECONDS);
            throw new AssertionError("completed normally");
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("no answer", e);
        }
    }

    @Test
    @Timeout(20)
    void anOrderFrameIsOneSendItsLengthInBytes() throws Exception {
        var queue = new java.util.concurrent.LinkedBlockingQueue<Object>();
        var events = new Events(server.url(), "Bearer t", MP, "fm-sdk-test", HttpFlexemarkets.MAPPER, queue);
        assertThatThrownBy(() -> events.send(DESTINATION, "{}"))
                .isInstanceOf(ConnectionFailedException.class).hasMessageContaining("not connected");
        events.connect();
        events.send(DESTINATION, "{\"symbol\": \"é\"}");
        var socket = server.connection(0);
        until("the SEND", () -> socket.received().stream().anyMatch(f -> f.startsWith("SEND\n")));
        var frame = socket.received().stream().filter(f -> f.startsWith("SEND\n")).findFirst().orElseThrow();
        assertThat(frame).isEqualTo("SEND\ndestination:" + DESTINATION
                + "\ncontent-type:application/json\ncontent-length:16\n\n{\"symbol\": \"é\"}\0");

        // A socket that will not take the frame: the send says so.
        var real = events._webSocket;
        events._webSocket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                new Class<?>[] {WebSocket.class},
                (proxy, method, args) -> CompletableFuture.failedFuture(new IOException("broken pipe")));
        assertThatThrownBy(() -> events.send(DESTINATION, "{}"))
                .isInstanceOf(ConnectionFailedException.class).hasMessageContaining("broken pipe");
        events._webSocket = real;

        assertThat(events.isClosed()).isFalse();
        events.close();
        assertThat(events.isClosed()).isTrue();
        assertThatThrownBy(() -> events.send(DESTINATION, "{}")).hasMessageContaining("not connected");
    }

    // --- stubbed ---

    /** Stands in for Events: records sends, fails them on cue. */
    private static final class Stub implements OrderStream.Listener {
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile int fail;
        volatile boolean closed;

        @Override
        public void send(String destination, String body) {
            assertThat(destination).isEqualTo(DESTINATION);
            if (fail > 0) {
                fail--;
                throw new ConnectionFailedException("The event stream is not connected.");
            }
            sent.add(HttpFlexemarkets.MAPPER.readTree(body).get("submissionId").asString());
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }

    private OrderStream stubbed(Stub stub, int maxInFlight) {
        var stream = new OrderStream(queue -> stub, MP, maxInFlight);
        opened.add(stream::close);
        return stream;
    }

    /**
     * Wait until the dispatcher has handled everything offered before this.
     *
     * <p>It takes one event at a time and handles it before taking the next, so
     * once a second marker has been taken the events before the first are done.
     */
    private static void settle(OrderStream stream) throws Exception {
        for (int i = 0; i < 2; i++) {
            stream.events().offer("marker");
            until("the dispatcher took the marker", () -> stream.events().isEmpty());
        }
    }

    @Test
    @Timeout(20)
    void theWindowHoldsTheNextOrderUntilOneIsAnsweredAndKeepsThemInTurn() throws Exception {
        var stub = new Stub();
        var stream = stubbed(stub, 2);
        var first = stream.submit("s-1", order("s-1"));
        stream.submit("s-2", order("s-2"));
        var third = CompletableFuture.supplyAsync(() -> stream.submit("s-3", order("s-3")));
        Thread.sleep(100);
        var fourth = CompletableFuture.supplyAsync(() -> stream.submit("s-4", order("s-4")));
        Thread.sleep(100);
        assertThat(stub.sent).containsExactly("s-1", "s-2");

        stream.events().offer(new OrdersUpdate(new Order[] {parsed("s-1", 101)}, 1));
        assertThat(first.get(5, TimeUnit.SECONDS).id()).isEqualTo(101);
        assertThat(third.get(5, TimeUnit.SECONDS).submissionId()).isEqualTo("s-3");
        stream.events().offer(new OrdersUpdate(new Order[] {parsed("s-2", 102)}, 2));
        assertThat(fourth.get(5, TimeUnit.SECONDS).submissionId()).isEqualTo("s-4");
        assertThat(stub.sent).containsExactly("s-1", "s-2", "s-3", "s-4");
    }

    @Test
    @Timeout(20)
    void aSendThatFailsIsHeldAndSentAgainOnReconnect() throws Exception {
        var stub = new Stub();
        var stream = stubbed(stub, OrderStream.MAX_IN_FLIGHT);
        stub.fail = 1;
        stream.submit("s-1", order("s-1"));
        stream.submit("s-2", order("s-2"));
        assertThat(stub.sent).isEmpty();
        assertThat(stream.isUp()).isFalse();

        stub.fail = 1;
        stream.events().offer(new StreamReconnected(MP));
        settle(stream);
        assertThat(stub.sent).isEmpty();
        stream.events().offer(new StreamReconnected(MP));
        settle(stream);
        assertThat(stub.sent).containsExactly("s-1", "s-2");
    }

    @Test
    @Timeout(20)
    void answersThatNameNoSubmissionAnswerNothing() throws Exception {
        var stub = new Stub();
        var stream = stubbed(stub, OrderStream.MAX_IN_FLIGHT);
        var pending = stream.submit("s-1", order("s-1"));
        stream.events().offer(new OrderAccepted(null, parsed("s-1", 1)));
        stream.events().offer(new OrderRejected(null, new InvalidArgumentException("x")));
        stream.events().offer("a frame this stream does not read");
        settle(stream);
        assertThat(pending.isDone()).isFalse();
        stream.events().offer(new OrderRejected("s-1", new InvalidArgumentException("refused")));
        assertThat(cause(pending)).hasMessage("refused");
    }

    /** Held for a whole outage, a robot's quotes went onto the book long after it sent them. */
    @Test
    @Timeout(20)
    void anOrderNotSentWithinTheHoldIsRefusedAndOneSentBeforeTheDropIsNot() throws Exception {
        var stub = new Stub();
        var stream = new OrderStream(queue -> stub, MP, OrderStream.MAX_IN_FLIGHT, 50);
        opened.add(stream::close);
        var sent = stream.submit("s-1", order("s-1"));
        stream.events().offer(new StreamDropped(new IOException("gone")));
        settle(stream);
        var held = stream.submit("s-2", order("s-2"));

        assertThat(cause(held)).isInstanceOf(ConnectionFailedException.class)
                .hasMessageContaining("was not sent: the order socket was down");
        assertThat(sent.isDone()).as("sent before the drop: it may have been placed").isFalse();
        stream.events().offer(new StreamReconnected(MP));
        settle(stream);
        assertThat(stub.sent).as("only the one that went out goes again").containsExactly("s-1", "s-1");
    }

    @Test
    @Timeout(20)
    void anOrderHeldLessThanTheHoldGoesWhenTheSocketIsBack() throws Exception {
        var stub = new Stub();
        var stream = stubbed(stub, OrderStream.MAX_IN_FLIGHT);
        stream.events().offer(new StreamDropped(new IOException("gone")));
        settle(stream);
        var held = stream.submit("s-1", order("s-1"));
        stream.events().offer(new StreamReconnected(MP));
        settle(stream);
        assertThat(stub.sent).containsExactly("s-1");
        assertThat(held.isDone()).isFalse();
    }

    @Test
    @Timeout(20)
    void closingFailsWhatIsUnansweredByWhetherItWasSent() throws Exception {
        var stub = new Stub();
        var stream = stubbed(stub, OrderStream.MAX_IN_FLIGHT);
        var sent = stream.submit("s-1", order("s-1"));
        stream.events().offer(new StreamDropped(new IOException("gone")));
        settle(stream);
        var held = stream.submit("s-2", order("s-2"));

        stream.close();
        stream.close();
        assertThat(stub.closed).isTrue();
        assertThat(cause(sent)).isInstanceOf(OrderOutcomeUnknownException.class)
                .hasMessageContaining("may have been placed");
        assertThat(((OrderOutcomeUnknownException) cause(sent)).submissionId()).isEqualTo("s-1");
        assertThat(cause(held)).isInstanceOf(ConnectionFailedException.class).hasMessageContaining("was not sent");
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> stream.submit("s-3", order("s-3")))
                    .isInstanceOf(ConnectionFailedException.class).hasMessageContaining("closed");
        }
    }

    @Test
    @Timeout(20)
    void aRefusedTokenClosesTheStreamAndSaysWhy() throws Exception {
        var stub = new Stub();
        var stream = stubbed(stub, OrderStream.MAX_IN_FLIGHT);
        var pending = stream.submit("s-1", order("s-1"));
        var refusal = new AuthenticationException("WebSocket refused: the token was rejected.", null);
        stub.closed = true;
        stream.events().offer(new StreamDropped(refusal));
        assertThat(cause(pending)).isInstanceOf(OrderOutcomeUnknownException.class).hasCause(refusal);
        stream.dispatcher().join(5000);
        assertThat(stream.dispatcher().isAlive()).isFalse();
    }

    /** A dispatcher that is stopped tells every order still waiting, rather than leaving it waiting. */
    @Test
    @Timeout(20)
    void anInterruptedDispatcherClosesTheStream() throws Exception {
        var stub = new Stub();
        var stream = stubbed(stub, OrderStream.MAX_IN_FLIGHT);
        var pending = stream.submit("s-1", order("s-1"));
        stream.dispatcher().interrupt();
        assertThat(cause(pending)).isInstanceOf(OrderOutcomeUnknownException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(stub.closed).isTrue();
    }

    @Test
    @Timeout(20)
    void sendLimitGoesOnTheMarketplaceSocketAndCloseEndsIt() throws Exception {
        var fm = new HttpFlexemarkets(HttpFlexemarkets.loadProperties(
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl",
                server.url().replace("ws://", "http://").replace("/api/v1/events", "/api/marketplaces/7"),
                "order-stream-test"));
        var first = fm.sendLimit(MP, 11, OrderSide.SELL, 1, 900);
        var second = fm.sendLimit(MP, 11, OrderSide.SELL, 1, 901, null, null, null);
        until("both sent", () -> sentCount(this, 0, 2));
        assertThat(sent(0)).containsExactly(first.submissionId(), second.submissionId());
        assertThat(server.connectionCount()).as("one socket for the marketplace").isEqualTo(1);
        // An order socket comes back within five seconds; a subscription waits up to a minute.
        assertThat(((Events) fm._orderStream(MP).listener()).reconnectMaxPauseMillis)
                .isEqualTo(OrderStream.RECONNECT_MAX_MS);
        var frames = server.connection(0).received().stream().filter(f -> f.startsWith("SEND\n")).toList();
        assertThat(frames.get(1)).contains("\"price\":901").contains("\"clientDescription\":\"order-stream-test\"");

        fm.close();
        assertThat(cause(first)).isInstanceOf(OrderOutcomeUnknownException.class);
        assertThatThrownBy(() -> fm.sendLimit(MP, 11, OrderSide.SELL, 1, 900))
                .isInstanceOf(ConnectionFailedException.class).hasMessageContaining("closed");
    }
}
