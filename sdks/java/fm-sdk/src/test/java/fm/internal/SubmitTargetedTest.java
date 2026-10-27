package fm.internal;

import fm.Flexemarkets;
import fm.model.OrderSide;

import fm.error.InvalidArgumentException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private final List<String> stray = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/api/v1/tokens", exchange -> respond(exchange, 200, """
            {"token":"%s",
             "person":{"id":7,"accountId":1,"email":"dev@dev"},
             "account":{"id":1,"name":"dev"}}
            """.formatted(TOKEN)));

        server.createContext("/api/v1/marketplaces/1/orders", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())
                    || !"/api/v1/marketplaces/1/orders".equals(exchange.getRequestURI().toString())) {
                stray.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                respond(exchange, 404, "");
                return;
            }
            submitted.add(body(exchange));
            respond(exchange, 200, "{\"id\":42,\"marketplaceId\":1,\"marketId\":11}");
        });

        // No API root, and no V0 /api/orders: either is recorded and refused.
        server.createContext("/", exchange -> {
            stray.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 404, "");
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

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    @AfterEach
    void nothingStrayed() {
        assertThat(stray).as("requests to anything but POST /api/v1/marketplaces/1/orders").isEmpty();
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

        // Each names its own submission; everything else is the same.
        var unnamed = "\"submissionId\":\"[^\"]+\"";
        assertThat(submitted.get(0).replaceAll(unnamed, "")).isEqualTo(submitted.get(1).replaceAll(unnamed, ""));
    }

    /** A participant learns names, not ids, so a name must reach the server as one. */
    @Test
    void aParticipantNamesItsCounterpartyByAllotmentName() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.submitLimit(1L, 11L, OrderSide.SELL, 1L, 900L, "T2", null, null);
        }

        assertThat(submitted.get(0)).contains("\"ownerTarget\":\"T2\"").doesNotContain("ownerTargetId");
    }

    @Test
    void namingTheCounterpartyBothWaysIsRefusedBeforeSending() throws Exception {
        try (Flexemarkets fm = connect()) {
            assertThatThrownBy(() -> fm.submitLimit(1L, 11L, OrderSide.SELL, 1L, 900L, "T2", 106771L, null))
                    .isInstanceOf(InvalidArgumentException.class);
        }

        assertThat(submitted).isEmpty();
    }

    /** The order's own reference replaces the client's, which is what it falls back to. */
    @Test
    void anOrderCarriesItsOwnReference() throws Exception {
        try (Flexemarkets fm = connect()) {
            fm.submitLimit(1L, 11L, OrderSide.BUY, 1L, 850L, null, null, "r1");
            fm.submitLimit(1L, 11L, OrderSide.BUY, 1L, 850L);
        }

        assertThat(submitted.get(0)).contains("\"clientDescription\":\"r1\"");
        assertThat(submitted.get(1)).contains("\"clientDescription\":\"submit-targeted-test\"");
    }
}
