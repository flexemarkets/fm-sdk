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
 * An endpoint that answers with a redirect.
 *
 * <p>This is a defect, reported 2026-08-20: an endpoint of
 * {@code http://api.adhocmarkets.com/api/marketplaces/1234} did not work while
 * the same URL on another host did. It was read as a network problem and is not.
 * The edge in front of production upgrades plain HTTP to HTTPS with a
 * {@code 301 Moved Permanently} to the very same host, and
 * {@link java.net.http.HttpClient#newHttpClient()} — which is what the SDK
 * builds — defaults to {@link java.net.http.HttpClient.Redirect#NEVER}. So the
 * SDK receives the 301 and the edge's HTML error page, and fails while parsing
 * that as JSON, a long way from the cause.
 *
 * <p>It looked host-specific only because the two hosts gained the upgrade rule
 * at different times. Either host is affected the moment its edge is configured
 * that way, which makes this a property of the client, not of a hostname.
 *
 * <p>Modelled here the way it actually happens: an <em>edge</em> that redirects
 * every request to an <em>origin</em> serving the API.
 */
class RedirectFollowingTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private HttpServer _origin;
    private HttpServer _edge;

    private final List<String> _originRequests = new ArrayList<>();
    private final List<String> _edgeRequests = new ArrayList<>();

    @BeforeEach
    void startServers() throws IOException {
        _origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        _origin.createContext("/api/tokens", exchange -> {
            _originRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            _respond(exchange, 200, """
                {"token":"%s",
                 "person":{"id":7,"accountId":1,"email":"dev@dev","firstName":"Dev","lastName":"User"},
                 "account":{"id":1,"name":"dev"}}
                """.formatted(TOKEN));
        });

        _origin.createContext("/api/marketplaces/1", exchange -> {
            _originRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            _respond(exchange, 200, "{\"id\":1,\"name\":\"Test\",\"markets\":[]}");
        });

        _origin.createContext("/api", exchange -> {
            _originRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            _respond(exchange, 200, "{\"_links\":{}}");
        });

        _origin.start();

        // The edge: every request is answered with a permanent redirect to the
        // origin, carrying the same path. This is the http -> https upgrade in
        // front of production, with the scheme change removed because a test
        // cannot serve TLS -- the redirect is the part that matters.
        _edge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        _edge.createContext("/", exchange -> {
            _edgeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            String target = "http://127.0.0.1:" + _origin.getAddress().getPort()
                            + exchange.getRequestURI().getPath();
            exchange.getResponseHeaders().add("Location", target);
            // The edge answers with its own HTML, not JSON -- which is what the
            // SDK ends up trying to read.
            byte[] body = "<html><body>Moved Permanently</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(301, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        _edge.start();
    }

    @AfterEach
    void stopServers() {
        if (_edge != null) {
            _edge.stop(0);
        }
        if (_origin != null) {
            _origin.stop(0);
        }
    }

    private static void _respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String _edgeEndpoint() {
        return "http://127.0.0.1:" + _edge.getAddress().getPort() + "/api/marketplaces/1";
    }

    /** The defect: a redirected endpoint must still connect. */
    @Test
    void anEndpointThatRedirectsIsFollowed() throws Exception {
        try (Flexemarkets fm = Flexemarkets.connect(TOKEN, _edgeEndpoint(), "redirect-test")) {
            assertThat(fm.userId()).isEqualTo(7L);
        }

        assertThat(_edgeRequests).as("the edge was asked").isNotEmpty();
        assertThat(_originRequests)
                .as("the redirect was followed through to the origin")
                .isNotEmpty();
    }
}
