package fm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fm.Flexemarkets;

/**
 * The User-Agent names the version built (fm-server#1012). It was the
 * constant "fm-sdk-java/0.1.0" from 0.1.0 to 0.3.3, so the server could not
 * tell one release from another -- and fm-server now keys its endpoint
 * metrics by client and version to know who still calls a route.
 */
class UserAgentTest {

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    /**
     * VERSION at the repo root is what {@code make set-version} fans out to
     * the pom; the filtered resource carries the pom's. A SNAPSHOT build
     * reads x.y.z-SNAPSHOT, which the server still cuts to x.y.
     */
    private static String _released() throws IOException {
        return Files.readString(Path.of("../../../VERSION")).trim();
    }

    @Test
    void namesTheVersionBeingBuilt() throws Exception {
        assertThat(HttpFlexemarkets.FM_SDK_CLIENT)
            .startsWith("fm-sdk-java/" + _released())
            .doesNotContain("${");
    }

    @Test
    void theWireCarriesIt() throws Exception {
        List<String> agents = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tokens", exchange -> {
            agents.add(String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent")));
            _respond(exchange, """
                {"token":"%s",
                 "person":{"id":7,"accountId":1,"email":"dev@dev","roles":["ROLE_USER"]},
                 "account":{"id":1,"name":"dev"}}
                """.formatted(TOKEN));
        });
        server.createContext("/api", exchange -> {
            agents.add(String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent")));
            _respond(exchange, "{\"_links\":{}}");
        });
        server.start();
        try {
            String api = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
            try (Flexemarkets fm = Flexemarkets.connect(TOKEN, api + "/marketplaces/1", "user-agent-test")) {
                assertThat(fm).isNotNull();
            }
        } finally {
            server.stop(0);
        }

        assertThat(agents).as("requests that reached the server").isNotEmpty();
        assertThat(Set.copyOf(agents)).containsExactly(HttpFlexemarkets.FM_SDK_CLIENT);
    }

    private static void _respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
