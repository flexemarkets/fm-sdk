package fm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fm.error.AccountNameConflictException;
import fm.error.AuthenticationException;
import fm.error.AuthorizationException;
import fm.error.ConflictException;
import fm.error.ConnectionFailedException;
import fm.error.FlexemarketsException;
import fm.error.HttpException;
import fm.error.InvalidArgumentException;

/**
 * The exception a caller meets for each status the server can answer.
 *
 * <p>Started as Java's missing counterpart to Python and TypeScript's
 * exception-hierarchy test, and turned into something else on inspection. The
 * hierarchy half needed neither: {@code FlexemarketsException} is sealed and
 * permits its subtypes by name, and {@code ConflictException} permits exactly
 * the two, so the bug those tests were written for -- a conflict deriving
 * straight from the base while its own docs promised otherwise -- does not
 * compile here. Re-parenting {@code AccountNameConflictException} to try it
 * fails the build with "class is not allowed to extend sealed class". And what
 * is not compiler-enforced, a taken account name arriving as
 * {@code AccountNameConflictException} with the server's suggestion, is
 * already covered in {@link AdminApiTest}.
 *
 * <p>What nothing covered is the mapping. No test in this suite mentioned
 * {@code InvalidArgumentException}, {@code AuthorizationException},
 * {@code ConnectionFailedException} or {@code HttpException} at all. The
 * javadoc on {@code HttpFlexemarkets._failureFor} says why that matters: the
 * mapping was written out four times and the copies drifted, so "two of them
 * handled 409 and two did not", and whether a conflict arrived as a
 * ConflictException or a bare HttpException depended on which method you
 * called.
 *
 * <p>Driven through real HTTP responses rather than by reaching into the
 * private method, so what is pinned is what a caller meets.
 */
class HttpFailureMappingTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private HttpServer _server;
    private int _status = 200;
    private String _body = "{}";

    @BeforeEach
    void startServer() throws IOException {
        _server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        _server.createContext("/api/tokens", exchange -> _respond(exchange, 200, """
            {"token":"%s",
             "person":{"id":7,"accountId":1,"email":"dev@dev","roles":["ROLE_MANAGER"]},
             "account":{"id":1,"name":"dev"}}
            """.formatted(TOKEN)));

        // Everything else answers whatever the case under test asked for.
        _server.createContext("/api", exchange -> _respond(exchange, _status, _body));
        _server.start();
    }

    @AfterEach
    void stopServer() {
        if (_server != null) _server.stop(0);
    }

    private static void _respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } else {
            exchange.close();
        }
    }

    private Flexemarkets _connect() throws IOException {
        return Flexemarkets.connect(
            TOKEN, "http://127.0.0.1:" + _server.getAddress().getPort() + "/api/marketplaces/1",
            "exception-hierarchy-test");
    }

    /** Any call that goes to the server; the route does not matter, the status does. */
    private void _call(int status, String body) throws Exception {
        _status = status;
        _body = body;
        try (var fm = _connect()) {
            fm.marketplaces();
        }
    }

    @Test
    void aPlain409IsAConflictRatherThanABareHttpException() {
        assertThatExceptionOfType(ConflictException.class)
            .as("the drift _failureFor exists to stop: a 409 that arrives as a bare "
              + "HttpException is invisible to a caller handling conflicts")
            .isThrownBy(() -> _call(409, "{\"status\":\"CONFLICT\",\"message\":\"taken\"}"));
    }

    @Test
    void aBadRequestIsAnInvalidArgument() {
        assertThatExceptionOfType(InvalidArgumentException.class)
            .isThrownBy(() -> _call(400, "{\"message\":\"price off the tick\"}"));
    }

    @Test
    void aRefusedTokenIsAnAuthenticationFailure() {
        assertThatExceptionOfType(AuthenticationException.class)
            .isThrownBy(() -> _call(401, "{\"message\":\"bad token\"}"));
    }

    @Test
    void aForbiddenCallIsAnAuthorizationFailure() {
        assertThatExceptionOfType(AuthorizationException.class)
            .isThrownBy(() -> _call(403, "{\"message\":\"not permitted\"}"));
    }

    @Test
    void aServerErrorIsAConnectionFailure() {
        assertThatExceptionOfType(ConnectionFailedException.class)
            .isThrownBy(() -> _call(503, "unavailable"));
    }

    @Test
    void anythingElseIsATypedHttpErrorCarryingItsStatus() {
        // Nothing escapes the family: a caller catching FlexemarketsException,
        // the documented way to catch everything this SDK raises, must not have
        // an IOException or a raw status go past them.
        assertThatExceptionOfType(HttpException.class)
            .isThrownBy(() -> _call(404, "{\"message\":\"no such marketplace\"}"))
            .satisfies(e -> assertThat(e.statusCode()).isEqualTo(404))
            .isInstanceOf(FlexemarketsException.class);
    }
}
