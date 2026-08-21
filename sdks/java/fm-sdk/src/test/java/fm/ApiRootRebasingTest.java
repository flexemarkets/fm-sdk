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

    private HttpServer origin;
    private HttpServer decoy;

    private final List<String> originRequests = new ArrayList<>();
    private final List<String> decoyRequests = new ArrayList<>();

    @BeforeEach
    void startServers() throws IOException {
        // Started first: the origin has to know the decoy's port to name it.
        decoy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        decoy.createContext("/", exchange -> {
            decoyRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            respond(exchange, 401, "{\"error\":\"not this host\"}");
        });
        decoy.start();

        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        origin.createContext("/api/tokens", exchange -> {
            originRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            respond(exchange, 200, """
                {"token":"%s",
                 "person":{"id":7,"accountId":1,"email":"dev@dev","firstName":"Dev","lastName":"User"},
                 "account":{"id":1,"name":"dev"}}
                """.formatted(TOKEN));
        });

        origin.createContext("/api/marketplaces/1/markets", exchange -> {
            originRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            respond(exchange, 200, "[{\"id\":11,\"symbol\":\"STK\",\"name\":\"Stock\"}]");
        });

        origin.createContext("/api/marketplaces/1/open", exchange -> {
            originRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"id\":99,\"marketplaceId\":1}");
        });

        origin.createContext("/api", exchange -> {
            originRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            respond(exchange, 200, """
                {"_links":{
                   "marketplaces":{"href":"%s/api/marketplaces{?page,size,sort*}","templated":true},
                   "accounts":{"href":"%s/api/accounts"}}}
                """.formatted(decoyBase(), decoyBase()));
        });

        origin.start();
    }

    @AfterEach
    void stopServers() {
        if (origin != null) {
            origin.stop(0);
        }
        if (decoy != null) {
            decoy.stop(0);
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

    private String decoyBase() {
        return "http://127.0.0.1:" + decoy.getAddress().getPort();
    }

    private String endpoint() {
        return "http://127.0.0.1:" + origin.getAddress().getPort() + "/api/marketplaces/1";
    }

    /** The defect: a read goes through a link, and the link named the wrong host. */
    @Test
    void aReadThroughALinkStaysOnTheHostThatWasDialled() throws Exception {
        try (Flexemarkets fm = Flexemarkets.connect(TOKEN, endpoint(), "rebase-test")) {
            assertThat(fm.markets(1L)).extracting(Types.Market::symbol).containsExactly("STK");
        }

        assertThat(originRequests).contains("GET /api/marketplaces/1/markets");
        assertThat(decoyRequests).as("nothing was sent to the host the links named").isEmpty();
    }

    /**
     * The same for a write, which is where following the redirect would not have
     * saved it: the 301 would have arrived back as a GET with the body gone.
     */
    @Test
    void aWriteThroughALinkStaysOnTheHostThatWasDialled() throws Exception {
        try (Flexemarkets fm = Flexemarkets.connect(TOKEN, endpoint(), "rebase-test")) {
            assertThat(fm.openSession(1L).id()).isEqualTo(99L);
        }

        assertThat(originRequests).contains("PATCH /api/marketplaces/1/open");
        assertThat(decoyRequests).as("nothing was sent to the host the links named").isEmpty();
    }

    /**
     * Rewriting silently would leave the deployment broken and no one told —
     * the SDK keeps working, so nothing ever surfaces the misconfiguration. It
     * says so instead, naming both origins.
     */
    @Test
    void aRewriteSaysSoAndNamesBothOrigins() {
        String reported = onStandardError(() -> HttpFlexemarkets.rebase(
                rootNaming("http://api.example.com"), "https://api.example.com/api"));

        assertThat(reported)
                .contains("http://api.example.com")
                .contains("https://api.example.com")
                .contains("forwarding the request scheme");
    }

    /** A correctly configured server is not nagged at. */
    @Test
    void aRootThatAlreadyAgreesIsSilent() {
        String reported = onStandardError(() -> HttpFlexemarkets.rebase(
                rootNaming("https://api.example.com"), "https://api.example.com/api"));

        assertThat(reported).isEmpty();
    }

    private static Types.ApiRoot rootNaming(String origin) {
        return new Types.ApiRoot(Map.of(
                "marketplaces", new Types.ApiRoot.LinkObject(origin + "/api/marketplaces")));
    }

    private static String onStandardError(Runnable action) {
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
