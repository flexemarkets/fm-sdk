package fm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Signing in with account, email and password sends them once, in the body.
 *
 * <p>The SDK also sent them as an HTTP Basic header on the same POST. The
 * server's Basic filter checks any such header before {@code /api/tokens} is
 * reached, so every SDK sign-in hashed the password twice -- and since
 * fm-server 4.5.32 bounds how many hashes run at once (fm-server#983), a
 * robot fleet signing in during a class's burst took two of those places
 * each. A wrong password in the header failed the sign-in even with the right
 * one in the body, which is how the double check was confirmed.
 */
class CredentialSignInTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    @TempDir
    Path _dir;

    private HttpServer _server;
    private final List<String> _signIns = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        _server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        _server.createContext("/api/tokens", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            _signIns.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                         + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                         + " body=" + body);
            _respond(exchange, 200, """
                {"token":"%s",
                 "person":{"id":7,"accountId":1,"email":"dev@dev","firstName":"Dev","lastName":"User"},
                 "account":{"id":1,"name":"dev"}}
                """.formatted(TOKEN));
        });

        _server.createContext("/api/marketplaces/1", exchange ->
            _respond(exchange, 200, "{\"id\":1,\"name\":\"Test\",\"markets\":[]}"));

        _server.createContext("/api", exchange -> _respond(exchange, 200, "{\"_links\":{}}"));

        _server.start();
    }

    @AfterEach
    void stopServer() {
        if (_server != null) {
            _server.stop(0);
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

    private String _endpoint() {
        return "http://127.0.0.1:" + _server.getAddress().getPort() + "/api/marketplaces/1";
    }

    @Test
    void credentialsTravelInTheBodyAndNotAsABasicHeader() throws Exception {
        Path credential = _dir.resolve("credential");
        Files.writeString(credential, "account=dev\nemail=dev@dev\npassword=s3cret\n");

        try (Flexemarkets fm = Flexemarkets.connect(credential.toString(), _endpoint(), "sign-in-test")) {
            assertThat(fm.userId()).isEqualTo(7L);
        }

        assertThat(_signIns).singleElement().satisfies(r -> {
            assertThat(r).startsWith("POST /api/tokens auth=null ");
            assertThat(r).contains("\"username\":\"dev|dev@dev\"").contains("\"password\":\"s3cret\"");
        });
    }
}
