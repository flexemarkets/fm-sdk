package fm;

import fm.error.ApiException;
import fm.role.Reading;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * The snapshot reads accept a bare array and nothing else.
 *
 * <p>{@link Reading#activeOrders} and {@link Reading#recentTrades} call
 * {@code GET /api/v1/marketplaces/{id}/orders}, which has only ever answered a
 * bare array. Earlier SDKs also read HAL envelopes, and answered an empty list
 * for any shape they did not recognise -- which is how {@link Desk} once
 * seeded empty books for months and looked plausible. So any other shape is
 * now an {@link ApiException}, not an empty snapshot.
 */
class SnapshotShapeTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private static final String ORDER = """
        {"id":80035520,"original":80035520,"supplier":80035520,"consumer":null,
         "type":"LIMIT","side":"BUY","symbol":"STK","units":5,"price":125,"marketId":6560}
        """;

    /** What the V1 route sends. */
    private static final String BARE_ARRAY = "[" + ORDER + "]";

    /** The HAL envelope older routes sent; the V1 route never has. */
    private static final String ENVELOPE = "{\"_embedded\":{\"orders\":[" + ORDER + "]}}";

    private HttpServer _server;
    private volatile String _snapshot = BARE_ARRAY;

    @BeforeEach
    void startServer() throws IOException {
        _server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        _server.createContext("/api/tokens/refresh", exchange -> _respond(exchange, """
            {"token":"%s","person":{"id":7,"accountId":1,"email":"dev@dev"},
             "account":{"id":1,"name":"dev"}}
            """.formatted(TOKEN)));
        _server.createContext("/api/v1/marketplaces/1/orders",
                exchange -> _respond(exchange, _snapshot));
        _server.createContext("/api", exchange -> _respond(exchange, "{}"));
        _server.start();
    }

    @AfterEach
    void stopServer() {
        if (_server != null) _server.stop(0);
    }

    private static void _respond(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("x-fm-as-of-seq", "7");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Flexemarkets _connect() throws IOException {
        return Flexemarkets.connect(TOKEN,
                "http://127.0.0.1:" + _server.getAddress().getPort() + "/api/marketplaces/1",
                "snapshot-shape-test");
    }

    @Test
    void activeOrdersReadsTheBareArray() throws Exception {
        try (Flexemarkets fm = _connect()) {
            var snapshot = fm.activeOrders(1);

            assertThat(snapshot.body())
                    .as("the order the server sent, not an empty list")
                    .singleElement()
                    .satisfies(order -> assertThat(order.price()).isEqualTo(125L));
            assertThat(snapshot.asOfSeq()).isEqualTo(7L);
        }
    }

    @Test
    void recentTradesReadsTheBareArray() throws Exception {
        try (Flexemarkets fm = _connect()) {
            assertThat(fm.recentTrades(1).body()).hasSize(1);
            var snapshot = fm.recentTrades(1, 10);
            assertThat(snapshot.body()).hasSize(1);
            assertThat(snapshot.asOfSeq()).isEqualTo(7L);
        }
    }

    @Test
    void anEnvelopeIsAnApiException() throws Exception {
        _snapshot = ENVELOPE;
        try (Flexemarkets fm = _connect()) {
            assertThatThrownBy(() -> fm.activeOrders(1))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("not a list of orders");
            assertThatThrownBy(() -> fm.recentTrades(1))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("not a list of orders");
        }
    }

    @Test
    void aBodyThatIsNotAListIsAnApiException() throws Exception {
        try (Flexemarkets fm = _connect()) {
            for (var body : new String[] {"{}", "null", "\"orders\"", "42"}) {
                _snapshot = body;
                assertThatThrownBy(() -> fm.activeOrders(1))
                        .as(body)
                        .isInstanceOf(ApiException.class)
                        .hasMessageContaining("not a list of orders");
            }
        }
    }
}
