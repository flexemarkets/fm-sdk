package fm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fm.Flexemarkets;
import fm.error.AuthenticationException;

/**
 * A client given a refresh token signs in with it, and renews with each one
 * it is handed after.
 *
 * <p>A robot fm-server launches used to run on the participant's access
 * token, and stopped when that expired: it held no password to sign in again
 * with. Once access tokens live two hours rather than a day, that is every
 * robot left running through a class. Now it is handed a refresh token of its
 * own. Against a loopback server that, as fm-server does, takes each refresh
 * token once, answers with the next, and refuses one presented again.
 */
class RefreshTokenRenewalTest {

    private static final String OLD = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJvbGQifQ.b2xk";
    private static final String NEW = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJuZXcifQ.bmV3";
    private static final Pattern REFRESH_TOKEN = Pattern.compile("\"refreshToken\"\\s*:\\s*\"([^\"]*)\"");

    @TempDir Path dir;
    private HttpServer _server;
    /** Each live refresh token, and the access token and next refresh token it is traded for. */
    private final Map<String, String[]> _live = new ConcurrentHashMap<>(Map.of(
            "rt-1", new String[] { OLD, "rt-2" },
            "rt-2", new String[] { NEW, "rt-3" }));
    private final List<String> _renewals = new CopyOnWriteArrayList<>();
    private final List<String> _presented = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        _server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        _server.createContext("/api/tokens/renew", exchange -> {
            Matcher presented = REFRESH_TOKEN.matcher(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String refreshToken = presented.find() ? presented.group(1) : "";
            _renewals.add(refreshToken);
            String[] traded = _live.remove(refreshToken);
            if (traded == null) {
                _respond(exchange, 401, "{\"error\":\"REFRESH_TOKEN_INVALID\",\"message\":\"The refresh token is not a live one\"}");
                return;
            }
            _respond(exchange, 200, """
                {"token":"%s","refreshToken":"%s","person":{"id":7,"accountId":1,"email":"dev@dev"},"account":{"id":1,"name":"dev"}}
                """.formatted(traded[0], traded[1]));
        });
        _server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            _presented.add(auth);
            if (!("Bearer " + NEW).equals(auth)) {
                _respond(exchange, 401, "{\"error\":\"ACCOUNT_TOKEN_EXPIRED\",\"message\":\"JWT expired for dev@dev\"}");
                return;
            }
            _respond(exchange, 200, "[]");
        });
        _server.start();
    }

    @AfterEach
    void stopServer() {
        _server.stop(0);
    }

    private static void _respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String _endpoint() {
        return "http://127.0.0.1:" + _server.getAddress().getPort() + "/api/marketplaces/1";
    }

    @Test
    void itSignsInWithTheRefreshTokenAndRenewsWithTheNextWhenTheAccessTokenExpires() throws Exception {
        try (Flexemarkets fm = Flexemarkets.connect("refresh:rt-1", _endpoint(), "refresh-test")) {
            assertThat(fm.token().token()).as("the access token the first renewal handed out").isEqualTo(OLD);
            assertThat(fm.user().id()).isEqualTo(7L);

            assertThat(fm.markets(1L)).isEmpty();
            assertThat(fm.token().token()).isEqualTo(NEW);
        }

        assertThat(_renewals).as("each refresh token presented once, the second the one the first renewal handed back")
                .containsExactly("rt-1", "rt-2");
        assertThat(_presented).containsExactly("Bearer " + OLD, "Bearer " + NEW);
    }

    @Test
    void aCredentialFileCarriesTheRefreshTokenToo() throws Exception {
        Path file = dir.resolve("credential");
        Files.writeString(file, "refresh-token=rt-2\n");

        try (Flexemarkets fm = Flexemarkets.connect(file.toString(), _endpoint(), "refresh-test")) {
            assertThat(fm.markets(1L)).isEmpty();
        }

        assertThat(_renewals).containsExactly("rt-2");
    }

    @Test
    void aRefreshTokenTheServerWillNotTakeIsRefusedAtConnect() {
        assertThatThrownBy(() -> Flexemarkets.connect("refresh:rt-unknown", _endpoint(), "refresh-test"))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void anExpiredTokenWhoseRenewalIsRefusedAnswersWithTheRefusalItHad() throws Exception {
        _live.remove("rt-2");
        try (Flexemarkets fm = Flexemarkets.connect("refresh:rt-1", _endpoint(), "refresh-test")) {
            assertThatThrownBy(() -> fm.markets(1L))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessageContaining("JWT expired");
        }

        assertThat(_renewals).containsExactly("rt-1", "rt-2");
    }

    /** A second renewal of the same token would present a used refresh token, and end the family. */
    @Test
    void aTokenAnotherThreadAlreadyRenewedIsNotRenewedAgain() throws Exception {
        try (var fm = (HttpFlexemarkets) Flexemarkets.connect("refresh:rt-1", _endpoint(), "refresh-test")) {
            assertThat(fm._renewToken("Bearer something-older")).isTrue();
        }

        assertThat(_renewals).containsExactly("rt-1");
    }
}
