package fm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fm.Flexemarkets;
import fm.model.Holding;

/**
 * The SDK asks for gzip and reads it (MELBOURNE-LATENCY F14): a server that
 * gzips the holdings is answered with the holdings, not with bytes that do
 * not parse; one that answers plain is read as before.
 */
class GzipResponseTest {

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";
    private static final String HOLDINGS = """
        [{"marketplaceId":1,"sessionId":9,"allocationId":3,"ownerId":7,"name":"B01",
          "cash":12345,"availableCash":12000,
          "assets":[{"marketId":10,"units":4,"availableUnits":4}]}]""";

    @Test
    void asksForGzip_andReadsGzippedHoldings() throws Exception {
        List<String> asked = new CopyOnWriteArrayList<>();
        List<Holding> holdings = _holdings(true, asked);

        assertThat(asked).as("Accept-Encoding on the holdings request").containsExactly("gzip");
        assertThat(holdings).singleElement().satisfies(h -> {
            assertThat(h.cash()).isEqualTo(12345);
            assertThat(h.name()).isEqualTo("B01");
        });
    }

    @Test
    void stillReadsAnAnswerThatIsNotGzipped() throws Exception {
        assertThat(_holdings(false, new CopyOnWriteArrayList<>())).singleElement()
            .satisfies(h -> assertThat(h.cash()).isEqualTo(12345));
    }

    @Test
    void readsTheCharsetTheServerNames() {
        assertThat(GzipBodies._charset("application/json; charset=ISO-8859-1")).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(GzipBodies._charset("application/json")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(GzipBodies._charset("text/csv; charset=nonsense")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(GzipBodies._gunzip(new byte[0], StandardCharsets.UTF_8)).isEmpty();
    }

    /** A body that says gzip and is not is an error, not text that fails to parse later. */
    @Test
    void aBodyThatIsNotTheGzipItClaimsIsAnError() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> GzipBodies._gunzip("not gzip".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
            .isInstanceOf(java.io.UncheckedIOException.class)
            .hasMessageContaining("would not decompress");
    }

    private static List<Holding> _holdings(boolean gzip, List<String> asked) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tokens", exchange -> _respond(exchange, """
                {"token":"%s",
                 "person":{"id":7,"accountId":1,"email":"dev@dev","roles":["ROLE_USER"]},
                 "account":{"id":1,"name":"dev"}}
                """.formatted(TOKEN), false));
        server.createContext("/api/v1/marketplaces/1/holdings", exchange -> {
            asked.add(String.valueOf(exchange.getRequestHeaders().getFirst("Accept-Encoding")));
            _respond(exchange, HOLDINGS, gzip);
        });
        server.createContext("/api", exchange -> _respond(exchange, "{\"_links\":{}}", false));
        server.start();
        try {
            String api = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
            try (Flexemarkets fm = Flexemarkets.connect(TOKEN, api + "/marketplaces/1", "gzip-test")) {
                return fm.holdings(1);
            }
        } finally {
            server.stop(0);
        }
    }

    private static void _respond(HttpExchange exchange, String body, boolean gzip) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (gzip) {
            var zipped = new ByteArrayOutputStream();
            try (var out = new GZIPOutputStream(zipped)) {
                out.write(bytes);
            }
            bytes = zipped.toByteArray();
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
