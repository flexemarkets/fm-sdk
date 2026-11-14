package fm.internal;

import fm.model.Order;
import fm.model.OrderSide;
import fm.model.OrderType;
import fm.model.TimeInForce;
import fm.Flexemarkets;
import fm.error.ApiException;
import fm.error.InvalidArgumentException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A market order: one limit order, immediate or cancel, capped at the book.
 *
 * <p>fm-server 4.30 takes a time-in-force on a limit order (fm-server#1035), so a
 * market order is a LIMIT marked IOC at a cap from the book: the worst price
 * among others' orders it needs to fill the units. Nothing of it rests, and the
 * answer says what traded.
 *
 * <p>Before, it was a limit at the market's extreme and then a cancel: the
 * remainder rested between the two, and a buy was cash-checked at the extreme,
 * refusing buys the trader could afford. That is still what it does against a
 * server or marketplace without IOC -- placed, then cancelled at once. (Earlier
 * still it sent {@code Long.MAX_VALUE} for a buy, a price no market accepts.)
 */
class SubmitMarketTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private static final String MARKETS = """
        [{"id":11,"marketplaceId":1,"symbol":"STK","name":"Stock",
          "priceMinimum":110,"priceMaximum":199,"priceTick":25,
          "unitMinimum":1,"unitMaximum":100,"unitTick":1}]
        """;

    /**
     * Sells at 1.35 (2), 1.60 (2) and 1.85 (5); the reader's own sell at 1.10; a
     * buy at 1.10; and a sell in another market.
     */
    private static final String BOOK = """
        [{"id":1,"type":"LIMIT","side":"SELL","units":2,"price":135,"marketId":11,"mine":false},
         {"id":2,"type":"LIMIT","side":"SELL","units":2,"price":160,"marketId":11,"mine":false},
         {"id":3,"type":"LIMIT","side":"SELL","units":5,"price":185,"marketId":11,"mine":false},
         {"id":4,"type":"LIMIT","side":"SELL","units":9,"price":110,"marketId":11,"mine":true},
         {"id":5,"type":"LIMIT","side":"BUY","units":3,"price":110,"marketId":11,"mine":false},
         {"id":6,"type":"LIMIT","side":"SELL","units":9,"price":110,"marketId":12,"mine":false}]
        """;

    /** How the fake answers a limit: as 4.30, as a server before it, for a marketplace without IOC, or a closed session. */
    private enum Mode { V430, OLD, NO_IOC, CLOSED }

    private HttpServer _server;
    /** Each order request as {@code METHOD uri body}. */
    private final List<String> _submitted = new ArrayList<>();
    private final List<String> _stray = new ArrayList<>();
    private volatile int _cancelStatus = 200;
    private volatile Mode _mode = Mode.V430;
    private volatile String _book = BOOK;

    @BeforeEach
    void startServer() throws IOException {
        _server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        _server.createContext("/api/v1/tokens", exchange -> _respond(exchange, 200, """
            {"token":"%s",
             "person":{"id":7,"accountId":1,"email":"dev@dev"},
             "account":{"id":1,"name":"dev"}}
            """.formatted(TOKEN)));

        // The V1 routes only: a request to any other is recorded as stray and refused.
        _server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String uri = exchange.getRequestURI().toString();
            if ("GET".equals(method) && "/api/v1/marketplaces/1/markets".equals(uri)) {
                _respond(exchange, 200, MARKETS);
            } else if ("GET".equals(method) && "/api/v1/marketplaces/1/orders?state=ACTIVE".equals(uri)) {
                _respond(exchange, 200, _book);
            } else if ("POST".equals(method) && "/api/v1/marketplaces/1/orders".equals(uri)) {
                String body = _body(exchange);
                _submitted.add(method + " " + uri + " " + body);
                _answerLimit(exchange, body);
            } else if ("DELETE".equals(method) && uri.startsWith("/api/v1/marketplaces/1/orders/")) {
                _submitted.add(method + " " + uri + " " + _body(exchange));
                _respond(exchange, _cancelStatus, _cancelStatus != 200 ? "{\"message\":\"exchange closed\"}" : """
                    {"id":43,"original":42,"consumer":42,"type":"CANCEL","marketplaceId":1,"marketId":11}""");
            } else {
                _stray.add(method + " " + uri);
                _respond(exchange, 404, "");
            }
        });

        _server.start();
    }

    private void _answerLimit(HttpExchange exchange, String body) throws IOException {
        boolean ioc = body.contains("\"timeInForce\":\"IOC\"");
        switch (_mode) {
            case CLOSED -> _respond(exchange, 400, "{\"error\":\"SESSION_CLOSED\",\"message\":\"The session is closed.\"}");
            case NO_IOC -> {
                if (ioc) {
                    _respond(exchange, 400,
                            "{\"error\":\"ORDER_INVALID\",\"message\":\"This marketplace takes GTC orders, not IOC.\"}");
                } else {
                    _respond(exchange, 200, "{\"id\":42,\"marketplaceId\":1,\"marketId\":11}");
                }
            }
            case OLD -> _respond(exchange, 200, "{\"id\":42,\"marketplaceId\":1,\"marketId\":11}");
            case V430 -> {
                long units = Long.parseLong(body.replaceAll(".*\"units\":(\\d+).*", "$1"));
                long filled = Math.min(units, 4);
                _respond(exchange, 200, """
                    {"id":42,"marketplaceId":1,"marketId":11,"timeInForce":"IOC",
                     "filledUnits":%d,"averagePrice":147.5,"cancelledUnits":%d}""".formatted(filled, units - filled));
            }
        }
    }

    @AfterEach
    void stopServer() {
        if (_server != null) {
            _server.stop(0);
        }
        assertThat(_stray).as("requests to routes 0.4 does not use").isEmpty();
    }

    private static String _body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void _respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private Flexemarkets _connect() throws IOException {
        return Flexemarkets.connect(TOKEN,
                "http://127.0.0.1:" + _server.getAddress().getPort() + "/api/marketplaces/1",
                "submit-market-test");
    }

    private List<String> _posts() {
        return _submitted.stream().filter(s -> s.startsWith("POST")).toList();
    }

    private List<String> _deletes() {
        return _submitted.stream().filter(s -> s.startsWith("DELETE")).toList();
    }

    @Test
    void aBuyIsOneIocLimitCappedAtTheBook_andNothingIsCancelled() throws Exception {
        Order order;
        try (Flexemarkets fm = _connect()) {
            order = fm.submitMarket(1L, 11L, OrderSide.BUY, 3L);
        }

        assertThat(_posts()).singleElement().asString()
                .contains("\"type\":\"LIMIT\"", "\"price\":160", "\"timeInForce\":\"IOC\"", "\"units\":3");
        assertThat(_deletes()).isEmpty();
        assertThat(order.filledUnits()).isEqualTo(3L);
        assertThat(order.averagePrice()).isEqualTo(147.5);
        assertThat(order.cancelledUnits()).isZero();
        assertThat(order.timeInForce()).isEqualTo(TimeInForce.IOC);
    }

    @Test
    void theCapIsTheWorstPriceNeeded_andAllOfTheSideWhenShort() throws Exception {
        try (Flexemarkets fm = _connect()) {
            fm.submitMarket(1L, 11L, OrderSide.BUY, 2L);
            fm.submitMarket(1L, 11L, OrderSide.BUY, 50L);
        }

        assertThat(_posts().get(0)).contains("\"price\":135");
        assertThat(_posts().get(1)).contains("\"price\":185");
    }

    @Test
    void aSellCapsAtTheBuys() throws Exception {
        try (Flexemarkets fm = _connect()) {
            fm.submitMarket(1L, 11L, OrderSide.SELL, 1L);
        }

        assertThat(_posts().get(0)).contains("\"price\":110", "\"side\":\"SELL\"");
    }

    @Test
    void anEmptySideIsRefusedWithoutSending() throws Exception {
        _book = "[{\"id\":5,\"type\":\"LIMIT\",\"side\":\"BUY\",\"units\":3,\"price\":110,\"marketId\":11}]";

        try (Flexemarkets fm = _connect()) {
            assertThatExceptionOfType(InvalidArgumentException.class)
                    .isThrownBy(() -> fm.submitMarket(1L, 11L, OrderSide.BUY, 1L))
                    .withMessageContaining("market 11 has no sells");
        }
        assertThat(_submitted).isEmpty();
    }

    /** It ignores timeInForce and rests what did not fill; its answer says nothing of what traded, which is how this tells. */
    @Test
    void aServerBeforeTimeInForceIsCancelledAsBefore() throws Exception {
        _mode = Mode.OLD;
        Order order;
        try (Flexemarkets fm = _connect()) {
            order = fm.submitMarket(1L, 11L, OrderSide.BUY, 5L);
        }

        assertThat(_posts()).hasSize(1);
        assertThat(_deletes()).containsExactly("DELETE /api/v1/marketplaces/1/orders/42 ");
        assertThat(order.filledUnits()).isNull();
    }

    @Test
    void aMarketplaceWithoutIocIsSentALimitAndACancel() throws Exception {
        _mode = Mode.NO_IOC;
        try (Flexemarkets fm = _connect()) {
            fm.submitMarket(1L, 11L, OrderSide.BUY, 5L);
        }

        assertThat(_posts()).hasSize(2);
        assertThat(_posts().get(0)).contains("\"timeInForce\":\"IOC\"");
        assertThat(_posts().get(1)).doesNotContain("timeInForce");
        assertThat(_deletes()).containsExactly("DELETE /api/v1/marketplaces/1/orders/42 ");
    }

    @Test
    void anyOtherRefusalIsThrown_andNotRetriedAsAPlainLimit() throws Exception {
        _mode = Mode.CLOSED;
        try (Flexemarkets fm = _connect()) {
            assertThatExceptionOfType(InvalidArgumentException.class)
                    .isThrownBy(() -> fm.submitMarket(1L, 11L, OrderSide.BUY, 5L))
                    .withMessageContaining("session is closed");
        }
        assertThat(_posts()).hasSize(1);
        assertThat(_deletes()).isEmpty();
    }

    /**
     * A cancel that fails after the order was placed says the order is placed.
     * Reported as a plain failure, it would read as "nothing happened", and a
     * caller retrying the whole market order would trade twice.
     */
    @Test
    void aCancelThatFailsSaysTheOrderWasPlacedAndNotToResubmit() throws Exception {
        _mode = Mode.OLD;
        _cancelStatus = 503;

        try (Flexemarkets fm = _connect()) {
            assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> fm.submitMarket(1L, 11L, OrderSide.BUY, 5L))
                .withMessageContaining("Order 42 was placed")
                .withMessageContaining("Do not resubmit")
                .havingCause().isInstanceOf(fm.error.ConnectionFailedException.class);
        }
        assertThat(_submitted).hasSize(2);
    }

    @Test
    void anUnknownMarketSaysSoRatherThanGuessingAPrice() throws Exception {
        try (Flexemarkets fm = _connect()) {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> fm.submitMarket(1L, 99L, OrderSide.BUY, 5L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("99");
        }

        assertThat(_submitted).as("nothing was sent").isEmpty();
    }

    @Test
    void aLimitSendsItsTimeInForceOnlyWhenGiven() throws Exception {
        try (Flexemarkets fm = _connect()) {
            fm.submitLimit(1L, 11L, OrderSide.BUY, 5L, 135L);
            fm.submitLimit(1L, 11L, OrderSide.BUY, 5L, 135L, null, null, null, TimeInForce.FOK);
        }

        assertThat(_posts().get(0)).doesNotContain("timeInForce");
        assertThat(_posts().get(1)).contains("\"timeInForce\":\"FOK\"");
    }

    // --- the cap itself, without a server -----------------------------------

    private static Order _order(OrderSide side, long units, long price, long marketId, Boolean mine) {
        return new Order(null, null, 1L, 1L, 1L, null, OrderType.LIMIT, side, units, price, mine, null,
                         1L, 0L, "STK", marketId, null, null, null);
    }

    @Test
    void ownOrdersAndOtherMarketsAreLeftOut() {
        var book = List.of(_order(OrderSide.SELL, 5, 100, 11, true), _order(OrderSide.SELL, 5, 100, 12, false),
                           _order(OrderSide.SELL, 1, 150, 11, false));
        assertThat(HttpFlexemarkets.marketCap(book, 11, OrderSide.BUY, 1)).isEqualTo(150L);
    }

    @Test
    void anOrderWhoseOwnerTheServerDidNotSayCounts() {
        assertThat(HttpFlexemarkets.marketCap(List.of(_order(OrderSide.SELL, 1, 150, 11, null)), 11, OrderSide.BUY, 1))
                .isEqualTo(150L);
    }

    /**
     * A market order that names no side used to price as the most aggressive sell the market accepts -- because the branch was the complement of buy rather than a test for sell. submitMarket takes the side straight from its caller, so a missing argument crossed the wrong side of the book rather than failing.
     */
    @Test
    void aSidelessMarketOrderIsRefusedRatherThanPricedAsASell() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
            () -> HttpFlexemarkets.marketCap(List.of(_order(OrderSide.SELL, 1, 150, 11, false)), 11, null, 1));
    }

}
