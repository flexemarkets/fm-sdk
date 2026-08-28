package fm.internal;

import fm.model.Market;
import fm.Flexemarkets;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An API root whose links name somewhere other than the host that was dialled.
 *
 * <p>The defect reported 2026-08-20 was read as "an endpoint that redirects"
 * and fixed by following redirects. That is not what was happening. Both
 * endpoints in the report were {@code https://}, and both signed in: the
 * difference was the API root. Production on {@code api.adhocmarkets.com}
 * answers {@code GET /api} with every href spelled {@code http://}, while the
 * same application on {@code api.flexemarkets.com} spells them {@code https://}
 * — the server builds them from the request it believes it received, and behind
 * the edge that belief is wrong. Every call that goes through a link, which is
 * most of them, then leaves on plain HTTP.
 *
 * <p>Following the redirect does not repair it. The JDK answers a 301 on a
 * {@code POST} by re-sending as {@code GET} with the body dropped, so an order
 * is never placed and a session never opens, and the failure names neither.
 * The links have to be pointed back at the host the token came from.
 *
 * <p>Modelled here as an origin that serves the API and a decoy it names in its
 * links instead. The scheme downgrade is stood in for by a different port,
 * because a test cannot serve TLS; naming somewhere the client was not told to
 * talk to is the part that matters.
 */
class ApiRootRebasingTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private HttpServer _origin;
    private HttpServer _decoy;

    private final List<String> _originRequests = new ArrayList<>();
    private final List<String> _decoyRequests = new ArrayList<>();

    @BeforeEach
    void startServers() throws IOException {
        // Started first: the origin has to know the decoy's port to name it.
        _decoy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        _decoy.createContext("/", exchange -> {
            _decoyRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            _respond(exchange, 401, "{\"error\":\"not this host\"}");
        });
        _decoy.start();

        _origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        _origin.createContext("/api/tokens", exchange -> {
            _originRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            _respond(exchange, 200, """
                {"token":"%s",
                 "person":{"id":7,"accountId":1,"email":"dev@dev","firstName":"Dev","lastName":"User"},
                 "account":{"id":1,"name":"dev"}}
                """.formatted(TOKEN));
        });

        _origin.createContext("/api/marketplaces/1/markets", exchange -> {
            _originRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            _respond(exchange, 200, "[{\"id\":11,\"symbol\":\"STK\",\"name\":\"Stock\"}]");
        });

        _origin.createContext("/api/marketplaces/1/open", exchange -> {
            _originRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            _respond(exchange, 200, "{\"id\":99,\"marketplaceId\":1}");
        });

        _origin.createContext("/api", exchange -> {
            _originRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            _respond(exchange, 200, """
                {"_links":{
                   "marketplaces":{"href":"%s/api/marketplaces{?page,size,sort*}","templated":true},
                   "accounts":{"href":"%s/api/accounts"}}}
                """.formatted(_decoyBase(), _decoyBase()));
        });

        _origin.start();
    }

    @AfterEach
    void stopServers() {
        if (_origin != null) {
            _origin.stop(0);
        }
        if (_decoy != null) {
            _decoy.stop(0);
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

    private String _decoyBase() {
        return "http://127.0.0.1:" + _decoy.getAddress().getPort();
    }

    private String _endpoint() {
        return "http://127.0.0.1:" + _origin.getAddress().getPort() + "/api/marketplaces/1";
    }

    /** The defect: a read goes through a link, and the link named the wrong host. */
    @Test
    void aReadThroughALinkStaysOnTheHostThatWasDialled() throws Exception {
        try (Flexemarkets fm = Flexemarkets.connect(TOKEN, _endpoint(), "rebase-test")) {
            assertThat(fm.markets(1L)).extracting(Market::symbol).containsExactly("STK");
        }

        assertThat(_originRequests).contains("GET /api/marketplaces/1/markets");
        assertThat(_decoyRequests).as("nothing was sent to the host the links named").isEmpty();
    }

    /**
     * The same for a write, which is where following the redirect would not have
     * saved it: the 301 would have arrived back as a GET with the body gone.
     */
    @Test
    void aWriteThroughALinkStaysOnTheHostThatWasDialled() throws Exception {
        try (Flexemarkets fm = Flexemarkets.connect(TOKEN, _endpoint(), "rebase-test")) {
            assertThat(fm.openSession(1L).id()).isEqualTo(99L);
        }

        assertThat(_originRequests).contains("PATCH /api/marketplaces/1/open");
        assertThat(_decoyRequests).as("nothing was sent to the host the links named").isEmpty();
    }

    /**
     * Rewriting silently would leave the deployment broken and no one told —
     * the SDK keeps working, so nothing ever surfaces the misconfiguration. It
     * says so instead, naming both origins.
     */
    @Test
    void aRewriteSaysSoAndNamesBothOrigins() {
        String reported = _onStandardError(() -> HttpFlexemarkets.rebase(
                _rootNaming("http://api.example.com"), "https://api.example.com/api"));

        assertThat(reported)
                .contains("http://api.example.com")
                .contains("https://api.example.com")
                .contains("forwarding the request scheme");
    }

    /** A correctly configured server is not nagged at. */
    @Test
    void aRootThatAlreadyAgreesIsSilent() {
        String reported = _onStandardError(() -> HttpFlexemarkets.rebase(
                _rootNaming("https://api.example.com"), "https://api.example.com/api"));

        assertThat(reported).isEmpty();
    }

    private static ApiRoot _rootNaming(String origin) {
        return new ApiRoot(Map.of(
                "marketplaces", new ApiRoot.LinkObject(origin + "/api/marketplaces")));
    }

    private static String _onStandardError(Runnable action) {
        PrintStream original = System.err;
        var captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
