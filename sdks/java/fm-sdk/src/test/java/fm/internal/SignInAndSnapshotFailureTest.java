package fm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fm.Flexemarkets;
import fm.Snapshot;
import fm.error.ApiException;
import fm.error.AuthenticationException;
import fm.error.AuthorizationException;
import fm.model.OrderSide;

/**
 * What a caller sees when sign-in, a desk's seed, or an order's answer goes
 * wrong. HttpFailureMappingTest pins the status-to-exception table; these pin
 * that each of these paths goes through it, which none of them did in a test:
 * the sign-in's refusal, the snapshot's refusal and missing sequence header,
 * and a response that arrived but cannot be read.
 */
class SignInAndSnapshotFailureTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private HttpServer _server;

    /**
     * path, with its query when it has one -> status, body, and an optional
     * x-fm-as-of-seq. Anything not here is answered 404: there is no API root
     * to fall back on, so a client asking for one, or for a route that moved,
     * fails rather than reading a plausible answer.
     */
    private final Map<String, Object[]> _answers = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        _server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        _answers.put("/api/tokens", new Object[] {200, """
            {"token":"%s","person":{"id":7,"accountId":1,"email":"dev@dev"},"account":{"id":1,"name":"dev"}}
            """.formatted(TOKEN), null});
        _answers.put("/api/tokens/refresh", _answers.get("/api/tokens"));
        _server.createContext("/", exchange -> {
            var uri = exchange.getRequestURI();
            Object[] answer = _answers.get(uri.getRawQuery() == null
                                           ? uri.getPath() : uri.getPath() + "?" + uri.getRawQuery());
            if (answer == null) {
                answer = new Object[] {404, _refusal("NOT_FOUND", "No route " + uri + ".", 404), null};
            }
            _respond(exchange, (Integer) answer[0], (String) answer[1], (String) answer[2]);
        });
        _server.start();
    }

    @AfterEach
    void stopServer() {
        if (_server != null) _server.stop(0);
    }

    private static void _respond(HttpExchange exchange, int status, String body, String seq) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (seq != null) exchange.getResponseHeaders().add("x-fm-as-of-seq", seq);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Flexemarkets _connect() throws IOException {
        return Flexemarkets.connect(TOKEN,
                "http://127.0.0.1:" + _server.getAddress().getPort() + "/api/marketplaces/1",
                "failure-test");
    }

    private static String _refusal(String error, String message, int status) {
        return """
            {"error":"%s","message":"%s","path":"/api/tokens","shortDigest":"abc123","status":%d}
            """.formatted(error, message, status);
    }

    /** The sentence a student acts on, not the envelope: "Authentication failed: Wrong password." */
    @Test
    void aRefusedPasswordIsAnAuthenticationFailureCarryingTheServersSentence(@TempDir Path dir) throws IOException {
        _answers.put("/api/tokens", new Object[] {401, _refusal("ACCOUNT_INVALID_CREDENTIALS", "Wrong password.", 401), null});
        Path credential = Files.writeString(dir.resolve("credential"), "account=dev\nemail=dev@dev\npassword=nope\n");

        assertThatExceptionOfType(AuthenticationException.class)
                .isThrownBy(() -> Flexemarkets.connect(credential.toString(),
                        "http://127.0.0.1:" + _server.getAddress().getPort() + "/api/marketplaces/1", "failure-test"))
                .withMessage("Authentication failed: Wrong password.");
    }

    /** A token is checked by refreshing it, so an expired one fails at connect, saying why. */
    @Test
    void aRefusedTokenFailsAtConnectSayingWhy() {
        _answers.put("/api/tokens/refresh", new Object[] {401, _refusal("TOKEN_EXPIRED", "Token expired.", 401), null});

        assertThatExceptionOfType(AuthenticationException.class).isThrownBy(this::_connect)
                .withMessage("Authentication failed: Token expired.");
    }

    @Test
    void aSnapshotCarriesTheSequenceItWasTakenAt() throws Exception {
        _answers.put("/api/v1/marketplaces/1/orders?state=ACTIVE", new Object[] {200, "[]", "41"});

        try (var fm = _connect()) {
            Snapshot<?> snapshot = fm.activeOrders(1L);
            assertThat(snapshot.asOfSeq()).isEqualTo(41L);
            assertThat((java.util.List<?>) snapshot.body()).isEmpty();
        }
    }

    /** No header -- an older server -- is "no sequence", which a desk must not mistake for 0. */
    @Test
    void aSnapshotWithoutASequenceSaysSo() throws Exception {
        _answers.put("/api/v1/marketplaces/1/orders?state=ACTIVE", new Object[] {200, "[]", null});

        try (var fm = _connect()) {
            assertThat(fm.activeOrders(1L).asOfSeq()).isEqualTo(Snapshot.NO_SEQ);
        }
    }

    @Test
    void aRefusedSnapshotIsTheServersRefusal() throws Exception {
        _answers.put("/api/v1/marketplaces/1/orders?state=ACTIVE",
                new Object[] {403, _refusal("NOT_PERMITTED", "Not your marketplace.", 403), null});

        try (var fm = _connect()) {
            assertThatExceptionOfType(AuthorizationException.class).isThrownBy(() -> fm.activeOrders(1L))
                    .withMessage("Not permitted: Not your marketplace.");
        }
    }

    /** An answer that arrived but does not parse is named as that, not as the request failing. */
    @Test
    void anOrderAnswerThatCannotBeReadSaysSo() throws Exception {
        _answers.put("/api/v1/marketplaces/1/orders", new Object[] {200, "<html>edge error page</html>", null});

        try (var fm = _connect()) {
            assertThatExceptionOfType(ApiException.class)
                    .isThrownBy(() -> fm.submitLimit(1L, 11L, OrderSide.BUY, 1L, 100L))
                    .withMessage("Failed to parse the response body");
        }
    }
}
