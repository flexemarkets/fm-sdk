package fm;

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
 * Connecting with a token that is already held, rather than with credentials.
 *
 * <p>This is how every robot connects: the container holds the participant's
 * JWT and passes it as {@code -C}, so there is no account, email or password to
 * present. {@link HttpFlexemarkets} must therefore <em>refresh</em> the token —
 * which validates it and returns the account and person behind it — rather than
 * sign in.
 *
 * <p>The SDK signed in regardless, POSTing {@code /tokens} with the blank
 * username and password that {@code loadCredential} leaves behind, and the
 * server rightly answered 401. No robot could connect. It was invisible to
 * every other test because it needs a server to refuse you.
 *
 * <p>It is also the second time: fm-lib-net carries the same branch with a
 * comment recording that an earlier rewrite dropped it. Hence this test, which
 * stands up a real HTTP server on a loopback port and asserts the request the
 * SDK makes — so the branch cannot be dropped a third time in silence.
 */
class TokenAuthenticationTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/api/tokens", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                         + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
            // A sign-in attempt with no credentials is refused, exactly as the
            // real server refuses it. Only the refresh path should succeed.
            if (!exchange.getRequestURI().getPath().endsWith("/refresh")) {
                respond(exchange, 401, "{\"message\":\"unauthorized\"}");
                return;
            }
            respond(exchange, 200, """
                {"token":"%s",
                 "person":{"id":7,"accountId":1,"email":"dev@dev","firstName":"Dev","lastName":"User"},
                 "account":{"id":1,"name":"dev"}}
                """.formatted(TOKEN));
        });

        server.createContext("/api/marketplaces/1", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"id\":1,\"name\":\"Test\",\"markets\":[]}");
        });

        server.createContext("/api", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"_links\":{}}");
        });

        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/marketplaces/1";
    }

    @Test
    void connectingWithATokenRefreshesItRatherThanSigningIn() throws Exception {
        try (Flexemarkets fm = Flexemarkets.connect(TOKEN, endpoint(), "token-test")) {
            assertThat(fm.userId()).isEqualTo(7L);
            assertThat(fm.accountName()).isEqualTo("dev");
        }

        assertThat(requests)
                .as("a held token is refreshed, never exchanged for another")
                .anySatisfy(r -> assertThat(r).contains("GET /api/tokens/refresh"))
                .noneSatisfy(r -> assertThat(r).isEqualTo("POST /api/tokens auth=null"));
    }

    @Test
    void theTokenIsPresentedAsABearer() throws Exception {
        try (Flexemarkets fm = Flexemarkets.connect(TOKEN, endpoint(), "token-test")) {
            assertThat(fm.userId()).isEqualTo(7L);
        }

        assertThat(requests).anySatisfy(r -> {
            assertThat(r).contains("/api/tokens/refresh");
            assertThat(r).contains("auth=Bearer " + TOKEN);
        });
    }

    /**
     * The failure this replaced. A POST to /tokens carrying the blanks that
     * loadCredential leaves is what the server answered 401 to, and the only
     * symptom was a robot that would not start.
     */
    @Test
    void theSignInEndpointIsNotPostedToAtAll() throws Exception {
        try (Flexemarkets fm = Flexemarkets.connect(TOKEN, endpoint(), "token-test")) {
            assertThat(fm.userId()).isEqualTo(7L);
        }

        assertThat(requests)
                .noneSatisfy(r -> assertThat(r).startsWith("POST /api/tokens auth"));
    }
}
