package fm;

import fm.error.AuthorizationException;
import fm.error.ConfigurationException;
import fm.model.Account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two operator switches on {@link Flexemarkets#connect(String, String,
 * String, boolean, String)}: tracing the exchange, and acting as another
 * account.
 *
 * <p>Asserted against a loopback server rather than a stubbed transport, for
 * the reason the other API tests give: what matters is the request that
 * actually goes out.
 *
 * <p>The impersonation assertions deliberately cover more than one verb and
 * more than one route. The header is applied in one shared place precisely so
 * it cannot reach some routes and miss others, and that failure is silent in
 * the shape that matters — the call succeeds, answering for the wrong account.
 *
 * <p>Since fm-server 4.7 the account header needs an impersonation token beside
 * it, which the server issues for the administrator's password: so the
 * impersonating tests connect with a credential file, not a token.
 */
class CaptureAndImpersonationTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private HttpServer _server;
    private final List<String> _requests = new ArrayList<>();
    private final List<String> _impersonations = new ArrayList<>();
    private final List<String> _impersonationTokens = new ArrayList<>();
    private final List<String> _grants = new ArrayList<>();
    private final List<String> _stray = new ArrayList<>();
    /** How long each impersonation token the server issues lasts. */
    private Duration _grantLifetime = Duration.ofMinutes(15);
    /** The status the server answers an ask for an impersonation token with. */
    private int _grantStatus = 200;

    @TempDir Path dir;

    @BeforeEach
    void startServer() throws IOException {
        _server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        _server.createContext("/api/v1/tokens", exchange -> {
            _record(exchange);
            _respond(exchange, 200, """
                {"token":"%s",
                 "person":{"id":7,"accountId":1,"email":"dev@dev","roles":["ROLE_ADMIN"]},
                 "account":{"id":1,"name":"dev"}}
                """.formatted(TOKEN));
        });

        _server.createContext("/api/v1/marketplaces", exchange -> {
            _record(exchange);
            if ("DELETE".equals(exchange.getRequestMethod())) {
                _respond(exchange, 204, "");
            } else {
                _respond(exchange, 200, "{\"id\":1,\"name\":\"course\",\"markets\":[]}");
            }
        });

        _server.createContext("/api/v1/impersonation", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            _record(exchange);
            _grants.add(body);
            if (_grantStatus != 200) {
                _respond(exchange, _grantStatus, "{\"error\":\"ACCESS_DENIED\",\"message\":\"Access denied.\"}");
                return;
            }
            _respond(exchange, 200, """
                {"token":"imp-%d","accountId":2,"expiresAt":"%s"}
                """.formatted(_grants.size(), Instant.now().plus(_grantLifetime)));
        });

        _server.createContext("/api/v1/users", exchange -> {
            _record(exchange);
            _respond(exchange, 200, "{\"id\":42,\"accountId\":1,\"email\":\"alice@lab.edu\"}");
        });

        // No API root: 0.4 reads none. Anything else -- GET /api, a V0 route --
        // is recorded as stray and refused, so a client drifting back to the
        // root or to the old routes fails here rather than being answered.
        _server.createContext("/", exchange -> {
            _record(exchange);
            _stray.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            _respond(exchange, 404, "");
        });

        _server.start();
    }

    @AfterEach
    void stopServer() {
        if (_server != null) _server.stop(0);
    }

    @Test
    void impersonationRidesEveryAuthenticatedRequest() throws Exception {
        try (var fm = Flexemarkets.connect(_credential(), _api() + "/marketplaces/1", "test",
                                           false, "acme")) {
            fm.marketplace(1);
            fm.deleteMarketplace(1);
        }

        // Both marketplace calls -- a GET and a DELETE, so the header is not
        // merely on the one verb that was easiest to reach -- on the V1 route,
        // each with the token the server issued beside it.
        assertThat(_stray).isEmpty();
        assertThat(_requests).contains("GET /api/v1/marketplaces/1", "DELETE /api/v1/marketplaces/1");
        assertThat(_onMarketplaces(_impersonations)).hasSize(2).allMatch("acme"::equals);
        assertThat(_onMarketplaces(_impersonationTokens)).hasSize(2).allMatch("imp-1"::equals);
    }

    /**
     * The token is asked for once, at connect, with the account and the
     * administrator's password -- and without the impersonation headers, which
     * it is the call to get.
     */
    @Test
    void theTokenIsAskedForOnceWithThePassword() throws Exception {
        try (var fm = Flexemarkets.connect(_credential(), _api() + "/marketplaces/1", "test",
                                           false, "acme")) {
            fm.marketplace(1);
            fm.marketplace(1);
        }

        assertThat(_grants).hasSize(1);
        assertThat(_grants.get(0)).contains("\"account\":\"acme\"").contains("\"currentPassword\":\"secret\"");
        assertThat(_impersonationOn("/impersonation")).containsExactly((String) null);
        assertThat(_tokenOn("/impersonation")).containsExactly((String) null);
    }

    /** A token about to expire is asked for again before a call goes out with it. */
    @Test
    void aTokenAboutToExpireIsRenewedBeforeTheCall() throws Exception {
        _grantLifetime = Duration.ofSeconds(10);
        try (var fm = Flexemarkets.connect(_credential(), _api() + "/marketplaces/1", "test",
                                           false, "acme")) {
            fm.marketplace(1);
            fm.marketplace(1);
        }

        assertThat(_grants).hasSize(3);
        assertThat(_onMarketplaces(_impersonationTokens)).containsExactly("imp-2", "imp-3");
    }

    /** A token is all a token credential has; it cannot be traded for an impersonation token, and connect says so. */
    @Test
    void impersonatingWithATokenCredentialIsRefusedAtConnect() {
        assertThatThrownBy(() -> Flexemarkets.connect(TOKEN, _api() + "/marketplaces/1", "test", false, "acme"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("password");
        assertThat(_requests).isEmpty();
    }

    /** Someone who is not an administrator, or whose password is wrong, is refused at connect, not on first use. */
    @Test
    void aRefusedTokenIsRefusedAtConnect() {
        _grantStatus = 403;
        assertThatThrownBy(() -> Flexemarkets.connect(_credential(), _api() + "/marketplaces/1", "test", false, "acme"))
                .isInstanceOf(AuthorizationException.class);
        assertThat(_requests).noneMatch(r -> r.contains("/marketplaces"));
    }

    /**
     * Sign-in is who you are, not who you are acting as. The header would be
     * meaningless on it -- there is no established identity yet for the server
     * to substitute for.
     */
    @Test
    void signInDoesNotImpersonate() throws Exception {
        try (var fm = Flexemarkets.connect(_credential(), _api() + "/marketplaces/1", "test",
                                           false, "acme")) {
            fm.marketplace(1);
        }

        assertThat(_impersonationOn("/tokens", "/refresh")).isNotEmpty().allMatch(v -> v == null);
    }

    @Test
    void noHeaderWhenNotImpersonating() throws Exception {
        try (var fm = Flexemarkets.connect(TOKEN, _api() + "/marketplaces/1", "test")) {
            fm.marketplace(1);
        }

        assertThat(_impersonations).allMatch(v -> v == null);
    }

    /** A blank account name is not a request to impersonate nobody-in-particular. */
    @Test
    void blankImpersonationIsNoImpersonation() throws Exception {
        try (var fm = Flexemarkets.connect(TOKEN, _api() + "/marketplaces/1", "test",
                                           false, "   ")) {
            fm.marketplace(1);
        }

        assertThat(_impersonations).allMatch(v -> v == null);
    }

    /** One header's values on the marketplace calls, in order. */
    private List<String> _onMarketplaces(List<String> header) {
        var seen = new ArrayList<String>();
        for (int i = 0; i < _requests.size(); i++) {
            if (_requests.get(i).contains("/marketplaces/")) {
                seen.add(header.get(i));
            }
        }
        return seen;
    }

    private List<String> _impersonationOn(String... pathFragments) {
        return _headerOn(_impersonations, pathFragments);
    }

    private List<String> _tokenOn(String... pathFragments) {
        return _headerOn(_impersonationTokens, pathFragments);
    }

    private List<String> _headerOn(List<String> header, String... pathFragments) {
        var seen = new ArrayList<String>();
        for (int i = 0; i < _requests.size(); i++) {
            for (var fragment : pathFragments) {
                if (_requests.get(i).contains(fragment)) {
                    seen.add(header.get(i));
                }
            }
        }
        return seen;
    }

    private String _credential() throws IOException {
        Path file = dir.resolve("credential");
        Files.writeString(file, "account=dev\nemail=dev@dev\npassword=secret\n");
        return file.toString();
    }

    @Test
    void captureTracesRequestAndResponse() throws Exception {
        var traced = _captureStdout(() -> {
            try (var fm = Flexemarkets.connect(TOKEN, _api() + "/marketplaces/1", "test",
                                               true, null)) {
                fm.marketplace(1);
            }
        });

        assertThat(_stray).isEmpty();
        assertThat(traced).contains("> GET");
        assertThat(traced).contains("/api/v1/marketplaces/1");
        assertThat(traced).contains("< 200");
        assertThat(traced).contains("course");
    }

    /**
     * Capture writes to stdout and stdout is what lands in a bug report, so the
     * bearer token must not be in it. fm-lib-net printed it in full.
     */
    @Test
    void captureRedactsTheBearerToken() throws Exception {
        var traced = _captureStdout(() -> {
            try (var fm = Flexemarkets.connect(TOKEN, _api() + "/marketplaces/1", "test",
                                               true, null)) {
                fm.marketplace(1);
            }
        });

        assertThat(traced).doesNotContain(TOKEN);
        assertThat(traced).contains("[redacted]");
    }

    /** The impersonation token is a credential too: redacted on the way out, and its grant's body withheld. */
    @Test
    void captureRedactsTheImpersonationToken() throws Exception {
        var traced = _captureStdout(() -> {
            try (var fm = Flexemarkets.connect(_credential(), _api() + "/marketplaces/1", "test",
                                               true, "acme")) {
                fm.marketplace(1);
            }
        });

        assertThat(traced).contains("/api/v1/impersonation").contains("X-FM-Impersonation: [redacted]");
        assertThat(traced).doesNotContain("imp-1").doesNotContain("secret");
    }

    @Test
    void nothingIsTracedWithoutCapture() throws Exception {
        var traced = _captureStdout(() -> {
            try (var fm = Flexemarkets.connect(TOKEN, _api() + "/marketplaces/1", "test")) {
                fm.marketplace(1);
            }
        });

        assertThat(traced).isEmpty();
    }

    private interface Body {
        void run() throws Exception;
    }

    private static String _captureStdout(Body body) throws Exception {
        var buffer = new ByteArrayOutputStream();
        var original = System.out;
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            body.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private String _api() {
        return "http://127.0.0.1:" + _server.getAddress().getPort() + "/api";
    }

    private void _record(HttpExchange exchange) {
        _requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
        _impersonations.add(exchange.getRequestHeaders().getFirst("X-FM-Account"));
        _impersonationTokens.add(exchange.getRequestHeaders().getFirst("X-FM-Impersonation"));
        try {
            exchange.getRequestBody().readAllBytes();
        } catch (IOException ignored) {
            // intentional: the body is not what these assertions are about
        }
    }

    private static void _respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
