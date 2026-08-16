package fm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
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
 */
class CaptureAndImpersonationTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final List<String> impersonations = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/api/tokens", exchange -> {
            record(exchange);
            respond(exchange, 200, """
                {"token":"%s",
                 "person":{"id":7,"accountId":1,"email":"dev@dev","roles":["ROLE_ADMIN"]},
                 "account":{"id":1,"name":"dev"}}
                """.formatted(TOKEN));
        });

        server.createContext("/api/marketplaces", exchange -> {
            record(exchange);
            if ("DELETE".equals(exchange.getRequestMethod())) {
                respond(exchange, 204, "");
            } else {
                respond(exchange, 200, "{\"id\":1,\"name\":\"course\",\"markets\":[]}");
            }
        });

        server.createContext("/api/users", exchange -> {
            record(exchange);
            respond(exchange, 200, "{\"id\":42,\"accountId\":1,\"email\":\"alice@lab.edu\"}");
        });

        server.createContext("/api", exchange -> {
            record(exchange);
            respond(exchange, 200, """
                {"_links":{"marketplaces":{"href":"%1$s/marketplaces"},
                           "accounts":{"href":"%1$s/accounts"},
                           "users":{"href":"%1$s/users"}}}
                """.formatted(api()));
        });

        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void impersonationRidesEveryAuthenticatedRequest() throws Exception {
        try (var fm = Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "test",
                                           false, "acme")) {
            fm.marketplace(1);
            fm.deleteMarketplace(1);
        }

        // The API root and both marketplace calls -- a GET and a DELETE, so the
        // header is not merely on the one verb that was easiest to reach.
        assertThat(authenticated()).isNotEmpty().allMatch("acme"::equals);
        assertThat(requests).anyMatch(r -> r.startsWith("DELETE"));
    }

    /**
     * Sign-in is who you are, not who you are acting as. The header would be
     * meaningless on it -- there is no established identity yet for the server
     * to substitute for.
     */
    @Test
    void signInDoesNotImpersonate() throws Exception {
        try (var fm = Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "test",
                                           false, "acme")) {
            fm.marketplace(1);
        }

        assertThat(impersonationOn("/tokens", "/refresh")).allMatch(v -> v == null);
    }

    @Test
    void noHeaderWhenNotImpersonating() throws Exception {
        try (var fm = Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "test")) {
            fm.marketplace(1);
        }

        assertThat(impersonations).allMatch(v -> v == null);
    }

    /** A blank account name is not a request to impersonate nobody-in-particular. */
    @Test
    void blankImpersonationIsNoImpersonation() throws Exception {
        try (var fm = Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "test",
                                           false, "   ")) {
            fm.marketplace(1);
        }

        assertThat(impersonations).allMatch(v -> v == null);
    }

    /** The impersonation header as seen on everything but the sign-in routes. */
    private List<String> authenticated() {
        var seen = new ArrayList<String>();
        for (int i = 0; i < requests.size(); i++) {
            if (!requests.get(i).contains("/tokens") && !requests.get(i).contains("/refresh")) {
                seen.add(impersonations.get(i));
            }
        }
        return seen;
    }

    private List<String> impersonationOn(String... pathFragments) {
        var seen = new ArrayList<String>();
        for (int i = 0; i < requests.size(); i++) {
            for (var fragment : pathFragments) {
                if (requests.get(i).contains(fragment)) {
                    seen.add(impersonations.get(i));
                }
            }
        }
        return seen;
    }

    @Test
    void captureTracesRequestAndResponse() throws Exception {
        var traced = captureStdout(() -> {
            try (var fm = Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "test",
                                               true, null)) {
                fm.marketplace(1);
            }
        });

        assertThat(traced).contains("> GET");
        assertThat(traced).contains("< 200");
        assertThat(traced).contains("course");
    }

    /**
     * Capture writes to stdout and stdout is what lands in a bug report, so the
     * bearer token must not be in it. fm-lib-net printed it in full.
     */
    @Test
    void captureRedactsTheBearerToken() throws Exception {
        var traced = captureStdout(() -> {
            try (var fm = Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "test",
                                               true, null)) {
                fm.marketplace(1);
            }
        });

        assertThat(traced).doesNotContain(TOKEN);
        assertThat(traced).contains("[redacted]");
    }

    @Test
    void nothingIsTracedWithoutCapture() throws Exception {
        var traced = captureStdout(() -> {
            try (var fm = Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "test")) {
                fm.marketplace(1);
            }
        });

        assertThat(traced).isEmpty();
    }

    private interface Body {
        void run() throws Exception;
    }

    private static String captureStdout(Body body) throws Exception {
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

    private String api() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }

    private void record(HttpExchange exchange) {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
        impersonations.add(exchange.getRequestHeaders().getFirst("X-FM-Account"));
        try {
            exchange.getRequestBody().readAllBytes();
        } catch (IOException ignored) {
            // intentional: the body is not what these assertions are about
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
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
