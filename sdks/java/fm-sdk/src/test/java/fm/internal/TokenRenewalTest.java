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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fm.Flexemarkets;
import fm.error.AuthenticationException;

/**
 * A client that signed in with a password renews its token when it expires.
 *
 * <p>Tokens live 24 hours. A robot that ran longer ended the first time it
 * asked for anything after that, or looped on the refusal -- a Utah poller,
 * about 250 times a day at 00Z. Against a loopback server that accepts only
 * the second token it hands out, and refuses the first the way fm-server
 * refuses an expired one: 401 with {@code ACCOUNT_TOKEN_EXPIRED}.
 */
class TokenRenewalTest {

    private static final String OLD = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJvbGQifQ.b2xk";
    private static final String NEW = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJuZXcifQ.bmV3";

    @TempDir Path dir;
    private HttpServer _server;
    private final AtomicInteger _signIns = new AtomicInteger();
    private final List<String> _presented = new CopyOnWriteArrayList<>();
    private volatile String _refusal = "ACCOUNT_TOKEN_EXPIRED";
    private volatile int _signInsBeforeFailing = Integer.MAX_VALUE;

    @BeforeEach
    void startServer() throws IOException {
        _server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        _server.createContext("/api/tokens", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (_signIns.get() >= _signInsBeforeFailing) {
                _signIns.incrementAndGet();
                _respond(exchange, 503, "{\"error\":\"SERVICE_UNAVAILABLE\"}");
                return;
            }
            String token = _signIns.incrementAndGet() == 1 ? OLD : NEW;
            _respond(exchange, 200, """
                {"token":"%s","person":{"id":7,"accountId":1,"email":"dev@dev"},"account":{"id":1,"name":"dev"}}
                """.formatted(token));
        });
        _server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            _presented.add(auth);
            if (!("Bearer " + NEW).equals(auth)) {
                _respond(exchange, 401, "{\"error\":\"" + _refusal + "\",\"message\":\"JWT expired for dev@dev\"}");
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

    private String _credential() throws IOException {
        Path file = dir.resolve("credential");
        Files.writeString(file, "account=dev\nemail=dev@dev\npassword=secret\n");
        return file.toString();
    }

    @Test
    void anExpiredTokenIsRenewedOnceAndTheRequestRepeated() throws Exception {
        try (Flexemarkets fm = Flexemarkets.connect(_credential(), _endpoint(), "renewal-test")) {
            assertThat(fm.markets(1L)).isEmpty();
            assertThat(fm.token().token()).as("the token the client reports is the one it presents").isEqualTo(NEW);
        }

        assertThat(_signIns).hasValue(2);
        assertThat(_presented).containsExactly("Bearer " + OLD, "Bearer " + NEW);
    }

    @Test
    void aClientGivenATokenCannotRenewItAndSaysSo() throws Exception {
        try (Flexemarkets fm = Flexemarkets.connect(OLD, _endpoint(), "renewal-test")) {
            assertThatThrownBy(() -> fm.markets(1L)).isInstanceOf(AuthenticationException.class);
        }

        assertThat(_signIns).as("the refresh that validated the token at connect, and no more").hasValue(1);
    }

    @Test
    void aTokenRefusedForAnotherReasonIsNotRenewed() throws Exception {
        _refusal = "ACCOUNT_INVALID_CREDENTIALS";
        try (Flexemarkets fm = Flexemarkets.connect(_credential(), _endpoint(), "renewal-test")) {
            assertThatThrownBy(() -> fm.markets(1L)).isInstanceOf(AuthenticationException.class);
        }

        assertThat(_signIns).hasValue(1);
    }

    @Test
    void aRenewalThatFailsAnswersWithTheRefusalItHad() throws Exception {
        _signInsBeforeFailing = 1;
        try (Flexemarkets fm = Flexemarkets.connect(_credential(), _endpoint(), "renewal-test")) {
            assertThatThrownBy(() -> fm.markets(1L))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("JWT expired");
        }

        assertThat(_signIns).hasValue(2);
    }

    @Test
    void aTokenAnotherThreadAlreadyRenewedIsNotRenewedAgain() throws Exception {
        try (var fm = (HttpFlexemarkets) Flexemarkets.connect(_credential(), _endpoint(), "renewal-test")) {
            assertThat(fm._renewToken("Bearer something-older")).isTrue();
        }

        assertThat(_signIns).hasValue(1);
    }

    /** The stream's renewal signs in again; the stream then reads the new token. */
    @Test
    void theStreamsRenewalSignsInAgain() throws Exception {
        try (var fm = (HttpFlexemarkets) Flexemarkets.connect(_credential(), _endpoint(), "renewal-test")) {
            assertThat(fm._renewToken("Bearer " + OLD)).isTrue();
            assertThat(fm.markets(1L)).as("the renewed token is the one presented").isEmpty();
        }

        assertThat(_signIns).hasValue(2);
        assertThat(_presented).containsExactly("Bearer " + NEW);
    }
}
