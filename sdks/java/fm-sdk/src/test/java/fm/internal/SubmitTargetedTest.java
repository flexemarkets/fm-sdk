package fm.internal;

import fm.Flexemarkets;
import fm.model.OrderSide;

import static org.assertj.core.api.Assertions.assertThat;

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
 * A limit order that names its counterparty — what a private market requires.
 *
 * <p>A private market matches a LIMIT order only against one whose owner and
 * target are its own target and owner, so an untargeted order never trades
 * there: the server refuses it outright with {@code ORDER_INVALID ... The
 * submitted order's owner target is unknown: 'null'}. Until this overload
 * there was no call in this SDK that could trade in one at all, which is why
 * the only client that ever has is the browser.
 *
 * <p>Two things have to be true on the wire, and the second is the one a
 * {@code Map.of} would have got wrong: a targeted order carries
 * {@code ownerTargetId}, and an untargeted one omits the key rather than
 * sending it null. A private order whose target is null is not an ordinary
 * order — it is the refused one.
 */
class SubmitTargetedTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private HttpServer server;
    private final List<String> submitted = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/api/tokens", exchange -> respond(exchange, """
            {"token":"%s",
             "person":{"id":7,"accountId":1,"email":"dev@dev"},
             "account":{"id":1,"name":"dev"}}
            """.formatted(TOKEN)));

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
                "submit-targeted-test");
    }

    /** The target reaches the server, as the id the server asks for. */
    @Test
    void aTargetedOrderCarriesItsOwnerTargetId() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.submitLimit(1L, 11L, OrderSide.BUY, 1L, 850L, 106771L);
        }

        assertThat(submitted.get(0))
                .contains("\"ownerTargetId\":106771")
                .contains("\"side\":\"BUY\"")
                .contains("\"price\":850");
    }

    /**
     * A null target omits the key. Sending {@code "ownerTargetId":null} would
     * describe a private order with no target, which is exactly what the
     * server refuses — so the difference between "no target" and "a null
     * target" has to survive serialization.
     */
    @Test
    void anUntargetedOrderOmitsTheKeyRatherThanNullingIt() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.submitLimit(1L, 11L, OrderSide.SELL, 1L, 900L, null);
        }

        assertThat(submitted.get(0)).doesNotContain("ownerTargetId");
    }

    /** The five-argument call is the six-argument one with no target. */
    @Test
    void theUntargetedOverloadSendsTheSameBody() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.submitLimit(1L, 11L, OrderSide.BUY, 2L, 700L);
            fm.submitLimit(1L, 11L, OrderSide.BUY, 2L, 700L, null);
        }

        assertThat(submitted.get(0)).isEqualTo(submitted.get(1));
    }
}
