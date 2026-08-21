package fm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fm.Types.Market;
import fm.Types.Order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A market order, on an exchange that has none.
 *
 * <p>{@code OrderDtoConverter}'s type switch falls through to {@code LIMIT}, so
 * every submission is bounds-checked against the market and must sit on a tick.
 * This sent {@code Long.MAX_VALUE} for a buy and {@code 0} for a sell: prices no
 * real market accepts. Every buy came back "price above market maximum", and a
 * sell survived only where {@code priceMinimum} happened to be zero.
 *
 * <p>It stayed broken because nothing exercised it. The only implementations in
 * the tree were fakes returning null, so the price never reached a server that
 * would have refused it.
 */
class SubmitMarketTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private HttpServer server;
    private final List<String> submitted = new ArrayList<>();

    /** priceMinimum 110, tick 25 — so the legal prices are 110, 135, 160, 185. */
    private String marketsJson = """
        [{"id":11,"marketplaceId":1,"symbol":"STK","name":"Stock",
          "priceMinimum":110,"priceMaximum":199,"priceTick":25,
          "unitMinimum":1,"unitMaximum":100,"unitTick":1}]
        """;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/api/tokens", exchange -> respond(exchange, """
            {"token":"%s",
             "person":{"id":7,"accountId":1,"email":"dev@dev"},
             "account":{"id":1,"name":"dev"}}
            """.formatted(TOKEN)));

        server.createContext("/api/marketplaces/1/markets", exchange -> respond(exchange, marketsJson));

        server.createContext("/api/orders", exchange -> {
            submitted.add(body(exchange));
            respond(exchange, "{\"id\":42,\"marketplaceId\":1,\"marketId\":11}");
        });

        server.createContext("/api", exchange -> {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
            respond(exchange, """
                {"_links":{"marketplaces":{"href":"%s/marketplaces"},
                           "orders":{"href":"%s/orders"}}}
                """.formatted(base, base));
        });

        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Flexemarkets connect() throws IOException {
        return Flexemarkets.connect(TOKEN,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/marketplaces/1",
                "submit-market-test");
    }

    /**
     * The defect. A buy has to be inside the market, and on a tick: 185, not
     * Long.MAX_VALUE and not 199, which is within the bounds but off-tick.
     */
    @Test
    void aBuyBidsTheHighestLegalPrice() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.submitMarket(1L, 11L, Order.SIDE_BUY, 5L);
        }

        assertThat(submitted).hasSize(1);
        assertThat(submitted.get(0))
                .contains("\"price\":185")
                .doesNotContain(String.valueOf(Long.MAX_VALUE));
    }

    /** A sell offers the floor, which is always on a tick. */
    @Test
    void aSellOffersTheLowestLegalPrice() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.submitMarket(1L, 11L, Order.SIDE_SELL, 5L);
        }

        assertThat(submitted).hasSize(1);
        assertThat(submitted.get(0)).contains("\"price\":110");
    }

    /** It is a LIMIT on the wire, because that is the only type the server has. */
    @Test
    void itIsSubmittedAsALimit() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.submitMarket(1L, 11L, Order.SIDE_BUY, 5L);
        }

        assertThat(submitted.get(0)).contains("\"type\":\"LIMIT\"");
    }

    @Test
    void anUnknownMarketSaysSoRatherThanGuessingAPrice() throws Exception {
        try (Flexemarkets fm = connect()) {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> fm.submitMarket(1L, 99L, Order.SIDE_BUY, 5L))
                    .isInstanceOf(Exceptions.ApiException.class)
                    .hasMessageContaining("99");
        }

        assertThat(submitted).as("nothing was sent").isEmpty();
    }

    // --- the price rule itself, without a server ----------------------------

    private static Market market(long min, long max, long tick) {
        return new Market(11L, 1L, "Stock", null, "STK", false, min, max, tick, 1L, 100L, 1L);
    }

    @Test
    void theTopOfTheRangeIsUsedWhenItIsOnATick() {
        assertThat(HttpFlexemarkets.marketableLimit(market(100, 200, 25), Order.SIDE_BUY))
                .isEqualTo(200L);
    }

    /**
     * Ticks are anchored at priceMinimum, not at zero -- the server tests
     * {@code (price - priceMinimum) % priceTick}. With a floor of 110 and a tick
     * of 25 the legal prices are 110/135/160/185, so a ceiling of 199 rounds
     * down to 185. Anchoring at zero would give 175, which this market refuses.
     */
    @Test
    void aRangeThatIsNotAWholeNumberOfTicksRoundsDownToOne() {
        assertThat(HttpFlexemarkets.marketableLimit(market(110, 199, 25), Order.SIDE_BUY))
                .isEqualTo(185L);
    }

    /** A tick of zero marks a fixed dimension: one legal price, both bounds equal. */
    @Test
    void aFixedPriceMarketHasOnlyItsFloor() {
        assertThat(HttpFlexemarkets.marketableLimit(market(150, 150, 0), Order.SIDE_BUY))
                .isEqualTo(150L);
        assertThat(HttpFlexemarkets.marketableLimit(market(150, 150, 0), Order.SIDE_SELL))
                .isEqualTo(150L);
    }
}
