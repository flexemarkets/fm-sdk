package fm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The snapshot routes return orders inside a HAL envelope, under "orders".
 *
 * <p>Every SDK read {@code _embedded.orderDtoes} — Spring HATEOAS pluralising
 * the server's old {@code OrderDto} — long after the server started sending
 * {@code _embedded.orders}. So {@link Reading#activeOrders} and
 * {@link Reading#recentTrades} returned an empty list always, in all three
 * SDKs, for their whole life.
 *
 * <p>Not cosmetic: {@link MarketView} seeds its books from {@code activeOrders},
 * so the seed was always empty and the books filled from live deltas
 * afterwards — which looks plausible until you open a view on a marketplace
 * that already has resting orders and see nothing.
 *
 * <p>Nothing caught it because nothing tested it. The only mention of
 * {@code activeOrders} in the suite was a stub in a fake returning null.
 */
class SnapshotEnvelopeTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    /** Sampled from a running fm-server. */
    private static final String ENVELOPE = """
        {"_embedded":{"orders":[
          {"id":80035520,"original":80035520,"supplier":80035520,"consumer":null,
           "type":"LIMIT","side":"BUY","symbol":"STK","units":5,"price":125,"marketId":6560}]}}
        """;

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tokens/refresh", exchange -> respond(exchange, """
            {"token":"%s","person":{"id":7,"accountId":1,"email":"dev@dev"},
             "account":{"id":1,"name":"dev"}}
            """.formatted(TOKEN)));
        server.createContext("/api/v1", exchange -> respond(exchange, ENVELOPE));
        server.createContext("/api", exchange -> respond(exchange, "{\"_links\":{}}"));
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("x-fm-as-of-seq", "7");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Flexemarkets connect() throws IOException {
        return Flexemarkets.connect(TOKEN,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/marketplaces/1",
                "envelope-test");
    }

    @Test
    void activeOrdersReadsTheEnvelope() throws Exception {
        try (Flexemarkets fm = connect()) {
            var snapshot = fm.activeOrders(1);

            assertThat(snapshot.body())
                    .as("the order the server sent, not an empty list")
                    .singleElement()
                    .satisfies(order -> assertThat(order.price()).isEqualTo(125L));
            assertThat(snapshot.asOfSeq()).isEqualTo(7L);
        }
    }

    @Test
    void recentTradesReadsTheEnvelope() throws Exception {
        try (Flexemarkets fm = connect()) {
            assertThat(fm.recentTrades(1).body()).hasSize(1);
            assertThat(fm.recentTrades(1, 10).body()).hasSize(1);
        }
    }
}
