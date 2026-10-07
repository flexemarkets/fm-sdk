package fm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fm.Book;
import fm.Desk;
import fm.Snapshot;
import fm.Subscription;
import fm.event.OrdersUpdate;
import fm.model.Market;
import fm.model.Order;
import fm.model.OrderSide;
import fm.model.OrderType;

/**
 * {@code Flexemarkets.desk(id)}, the call every caller makes: one desk and one
 * stream per marketplace however many handles ask, torn down when the last
 * handle closes.
 *
 * <p>DeskTest drives {@link DefaultDesk} directly, so until this the sharing
 * and the {@link DeskHandle} every caller actually holds ran only in
 * FlexemarketsLiveServerTest, which skips without a server: 0% in every
 * release gate. Here the client is real -- it signs in and reads the API root
 * from a stub server -- and only the four calls a desk makes to it are
 * scripted.
 */
class DeskSharingTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";
    private static final long MP = 1L;

    private static final Market ALPHA = new Market(11L, MP, "ALPHA", "ALPHA", "ALPHA", false, 0, 10_000, 1, 1, 100, 1);

    private HttpServer _server;

    /** A client whose desk-facing calls answer from the test. */
    static final class Scripted extends HttpFlexemarkets {
        final AtomicInteger activeReads = new AtomicInteger();
        final AtomicInteger unsubscribes = new AtomicInteger();
        final Map<Long, BlockingQueue<Object>> streams = new ConcurrentHashMap<>();
        final List<Long> subscribed = new CopyOnWriteArrayList<>();
        final List<String> submitted = new CopyOnWriteArrayList<>();

        Scripted(String endpoint) throws IOException {
            super(HttpFlexemarkets.loadProperties(TOKEN, endpoint, "desk-sharing-test"));
        }

        @Override public List<Market> markets(long marketplaceId) {
            return List.of(new Market(ALPHA.id(), marketplaceId, "ALPHA", "ALPHA", "ALPHA", false, 0, 10_000, 1, 1, 100, 1));
        }

        @Override public Snapshot<List<Order>> activeOrders(long marketplaceId) {
            activeReads.incrementAndGet();
            return new Snapshot<>(List.of(_limit(marketplaceId, 101L, OrderSide.BUY, 5, 1000)), 4L);
        }

        @Override public Snapshot<List<Order>> recentTrades(long marketplaceId) {
            return new Snapshot<>(List.of(), 4L);
        }

        @Override public Snapshot<List<Order>> recentTrades(long marketplaceId, int size) {
            return new Snapshot<>(List.of(), 4L);
        }

        @Override public Subscription subscribe(long marketplaceId, BlockingQueue<Object> queue) {
            subscribed.add(marketplaceId);
            streams.put(marketplaceId, queue);
            return () -> {
                unsubscribes.incrementAndGet();
                streams.remove(marketplaceId, queue);
            };
        }

        @Override public Order submitLimit(long marketplaceId, long marketId, OrderSide side, long units, long price) {
            submitted.add(marketplaceId + "/" + marketId + " " + side + " " + units + "@" + price);
            return _limit(marketplaceId, 900L, side, units, price);
        }

        void post(long marketplaceId, Object event) throws InterruptedException {
            streams.get(marketplaceId).put(event);
        }
    }

    @BeforeEach
    void startServer() throws IOException {
        _server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        _server.createContext("/api/tokens", exchange -> _respond(exchange, """
            {"token":"%s",
             "person":{"id":7,"accountId":1,"email":"dev@dev"},
             "account":{"id":1,"name":"dev"}}
            """.formatted(TOKEN)));
        _server.createContext("/api", exchange -> _respond(exchange, "{\"_links\":{}}"));
        _server.start();
    }

    @AfterEach
    void stopServer() {
        if (_server != null) _server.stop(0);
    }

    private Scripted _connect() throws IOException {
        return new Scripted("http://127.0.0.1:" + _server.getAddress().getPort() + "/api/marketplaces/" + MP);
    }

    private static void _respond(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Order _limit(long marketplaceId, long id, OrderSide side, long units, long price) {
        return new Order(null, null, id, id, id, null,
                         OrderType.LIMIT, side, units, price, null, null,
                         marketplaceId, 1L, ALPHA.symbol(), ALPHA.id(), null, null);
    }

    private static void _await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(2);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    @Test
    @Timeout(20)
    void twoHandlesShareOneDeskOneSeedAndOneStream() throws Exception {
        try (var fm = _connect()) {
            Desk a = fm.desk(MP);
            Desk b = fm.desk(MP);

            assertThat(fm.subscribed).containsExactly(MP);
            assertThat(fm.activeReads).hasValue(1);

            // One state behind both: a delta through the one stream reaches each.
            fm.post(MP, new OrdersUpdate(new Order[] { _limit(MP, 102L, OrderSide.BUY, 3, 1100) }, 5L));
            _await("the delta to land", () -> a.book(ALPHA.id()).bestBuyPrice() == 1100L);
            assertThat(b.book(ALPHA.id()).bestBuyPrice()).isEqualTo(1100L);

            a.close();
            b.close();
        }
    }

    @Test
    @Timeout(20)
    void theStreamClosesWhenTheLastHandleDoesAndNotBefore() throws Exception {
        try (var fm = _connect()) {
            Desk a = fm.desk(MP);
            Desk b = fm.desk(MP);

            a.close();
            assertThat(fm.unsubscribes).as("closed with a handle still open").hasValue(0);
            assertThat(b.book(ALPHA.id()).bestBuyPrice()).isEqualTo(1000L);

            b.close();
            assertThat(fm.unsubscribes).hasValue(1);
        }
    }

    /** A second close of one handle must not release the desk another handle still holds. */
    @Test
    @Timeout(20)
    void closingAHandleTwiceReleasesItOnce() throws Exception {
        try (var fm = _connect()) {
            Desk a = fm.desk(MP);
            Desk b = fm.desk(MP);

            a.close();
            a.close();

            assertThat(fm.unsubscribes).hasValue(0);
            assertThat(b.book(ALPHA.id())).isNotNull();
            b.close();
        }
    }

    @Test
    @Timeout(20)
    void aDeskOpenedAfterTheLastCloseIsANewOne() throws Exception {
        try (var fm = _connect()) {
            fm.desk(MP).close();
            try (Desk again = fm.desk(MP)) {
                assertThat(fm.subscribed).containsExactly(MP, MP);
                assertThat(fm.activeReads).hasValue(2);
                assertThat(again.book(ALPHA.id()).bestBuyPrice()).isEqualTo(1000L);
            }
        }
    }

    @Test
    @Timeout(20)
    void eachMarketplaceHasItsOwnDesk() throws Exception {
        try (var fm = _connect();
             Desk one = fm.desk(1L);
             Desk two = fm.desk(2L)) {
            assertThat(fm.subscribed).containsExactlyInAnyOrder(1L, 2L);
            assertThat(one.marketplaceId()).isEqualTo(1L);
            assertThat(two.marketplaceId()).isEqualTo(2L);
        }
    }

    @Test
    @Timeout(20)
    void aClosedHandleRefusesUseWhileTheOthersCarryOn() throws Exception {
        try (var fm = _connect()) {
            Desk a = fm.desk(MP);
            Desk b = fm.desk(MP);
            a.close();

            assertThatIllegalStateException().isThrownBy(() -> a.book(ALPHA.id()))
                .withMessageContaining("closed");
            assertThatIllegalStateException().isThrownBy(a::markets);
            assertThatIllegalStateException().isThrownBy(() -> a.onBookChange(ALPHA.id(), book -> { }));
            assertThatIllegalStateException().isThrownBy(() -> a.submitLimit(ALPHA.id(), OrderSide.BUY, 1, 1000));
            assertThat(b.markets()).extracting(Market::id).containsExactly(ALPHA.id());
            b.close();
        }
    }

    /** A handler registered through a handle stops when that handle closes; the other handle's keeps firing. */
    @Test
    @Timeout(20)
    void aHandlesHandlersStopWhenItCloses() throws Exception {
        try (var fm = _connect()) {
            Desk a = fm.desk(MP);
            Desk b = fm.desk(MP);
            List<Long> seenByA = new CopyOnWriteArrayList<>();
            List<Long> seenByB = new CopyOnWriteArrayList<>();
            a.onBookChange(ALPHA.id(), book -> seenByA.add(book.bestBuyPrice()));
            b.onBookChange(ALPHA.id(), book -> seenByB.add(book.bestBuyPrice()));

            a.close();
            fm.post(MP, new OrdersUpdate(new Order[] { _limit(MP, 102L, OrderSide.BUY, 3, 1100) }, 5L));

            _await("b's handler to fire", () -> seenByB.contains(1100L));
            assertThat(seenByA).isEmpty();
            b.close();
        }
    }

    @Test
    @Timeout(20)
    void aHandleReadsAndTradesThroughTheSharedDesk() throws Exception {
        try (var fm = _connect(); Desk desk = fm.desk(MP)) {
            Book book = desk.book(ALPHA.id());
            assertThat(book.bestBuyPrice()).isEqualTo(1000L);
            assertThat(desk.books()).hasSize(1);
            assertThat(desk.tapes()).hasSize(1);
            assertThat(desk.tape(ALPHA.id())).isNotNull();

            desk.submitLimit(ALPHA.id(), OrderSide.SELL, 2, 1500);
            assertThat(fm.submitted).containsExactly(MP + "/" + ALPHA.id() + " SELL 2@1500");
        }
    }

    @Test
    @Timeout(20)
    void closingTheClientClosesTheDesksItsCallersLeftOpen() throws Exception {
        var fm = _connect();
        fm.desk(MP);
        fm.desk(2L);

        fm.close();

        assertThat(fm.unsubscribes).hasValue(2);
    }
}
