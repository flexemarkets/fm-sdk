package fm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.ThrowingConsumer;

import fm.error.ApiException;
import fm.error.HttpException;

/**
 * What each kind of request says when the exchange never completes.
 *
 * <p>The client sends through four helpers -- a parsed document, a snapshot
 * with its sequence, a body returned verbatim, and a status with no body --
 * and each catches a dropped connection and an interrupted caller on its own.
 * Only the sign-in's and the parsed document's had been seen to fail; the
 * rest were written and never run. Here each is driven through the public
 * call that uses it.
 *
 * <p>An interrupted caller must get its interrupt back as well as the
 * exception: the helpers catch InterruptedException, and one that swallowed
 * the flag would leave a robot's shutdown waiting on a thread that no longer
 * knows it was asked to stop.
 */
class TransportFailureTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private HttpServer _server;
    /** Whether a call other than sign-in has its connection dropped instead of answered. */
    private volatile boolean _hangUp;
    private final List<AutoCloseable> _closing = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        _server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        _server.createContext("/api/tokens", exchange -> _respond(exchange, 200, """
            {"token":"%s",
             "person":{"id":7,"accountId":1,"email":"dev@dev"},
             "account":{"id":1,"name":"dev"}}
            """.formatted(TOKEN)));
        _server.createContext("/api", exchange -> {
            if (_hangUp) {
                // A handler that throws has its connection closed with nothing sent.
                throw new IOException("hanging up");
            }
            _respond(exchange, 404, "{\"message\":\"no such thing\"}");
        });
        _server.start();
    }

    @AfterEach
    void stop() throws Exception {
        Thread.interrupted();
        for (AutoCloseable c : _closing) c.close();
        if (_server != null) _server.stop(0);
    }

    private static void _respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String _endpoint() {
        return "http://127.0.0.1:" + _server.getAddress().getPort() + "/api/marketplaces/1";
    }

    private HttpFlexemarkets _connect() throws IOException {
        var fm = new HttpFlexemarkets(HttpFlexemarkets.loadProperties(TOKEN, _endpoint(), "transport-failure-test"));
        _closing.add(fm);
        return fm;
    }

    /** One public call per helper, named for the helper it goes through. */
    private static final List<Object[]> CALLS = List.of(
        new Object[] { "snapshot", "Snapshot request", (ThrowingConsumer<HttpFlexemarkets>) fm -> fm.activeOrders(1L) },
        new Object[] { "text", "HTTP request", (ThrowingConsumer<HttpFlexemarkets>) fm -> fm.downloadHoldings(1L) },
        new Object[] { "document", "HTTP request", (ThrowingConsumer<HttpFlexemarkets>) fm -> fm.marketplaces() },
        new Object[] { "status", "HTTP request", (ThrowingConsumer<HttpFlexemarkets>) fm -> fm.deleteUser(5L) });

    @SuppressWarnings("unchecked")
    private static ThrowingConsumer<HttpFlexemarkets> _call(Object[] row) {
        return (ThrowingConsumer<HttpFlexemarkets>) row[2];
    }

    /**
     * A server that hangs up mid-request is not "unreachable" -- it answered
     * the connection -- so the call keeps its own wording and appends what
     * the transport said, which the wording alone never carried.
     */
    @Test
    @Timeout(30)
    void aConnectionDroppedMidRequestIsReportedByEveryKindOfCall() throws Exception {
        var fm = _connect();
        _hangUp = true;

        for (Object[] row : CALLS) {
            assertThatExceptionOfType(ApiException.class)
                .as("the %s call", row[0])
                .isThrownBy(() -> _call(row).accept(fm))
                .withMessageStartingWith(row[1] + " failed: java.io.IOException")
                .withCauseInstanceOf(IOException.class);
        }
    }

    @Test
    @Timeout(30)
    void anInterruptedCallerGetsAnExceptionAndKeepsItsInterrupt() throws Exception {
        var fm = _connect();

        for (Object[] row : CALLS) {
            Thread.currentThread().interrupt();
            assertThatExceptionOfType(ApiException.class)
                .as("the %s call", row[0])
                .isThrownBy(() -> _call(row).accept(fm))
                .withMessage(row[1] + " interrupted")
                .withCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.interrupted()).as("the %s call kept the interrupt", row[0]).isTrue();
        }
    }

    @Test
    @Timeout(30)
    void anInterruptedSignInSaysSoAndKeepsTheInterrupt() {
        Thread.currentThread().interrupt();

        assertThatExceptionOfType(ApiException.class)
            .isThrownBy(this::_connect)
            .withMessage("Sign-in request interrupted");
        assertThat(Thread.interrupted()).isTrue();
    }

    /** The CSV download answers a refusal like every other call, not with the error page as its text. */
    @Test
    @Timeout(30)
    void aRefusedDownloadIsTheServersRefusal() throws Exception {
        var fm = _connect();

        assertThatExceptionOfType(HttpException.class)
            .isThrownBy(() -> fm.downloadHoldings(1L))
            .satisfies(e -> assertThat(e.statusCode()).isEqualTo(404));
    }

    /** https to a server speaking plain http fails in TLS, and says that, with what TLS said. */
    @Test
    @Timeout(30)
    void tlsToAPlainServerIsNamedAsATlsFailure() throws Exception {
        try (var plain = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.startVirtualThread(() -> {
                while (!plain.isClosed()) {
                    try (Socket client = plain.accept()) {
                        // Answer the ClientHello in plain text, and hold the
                        // connection until the client gives up on it.
                        client.getInputStream().read();
                        client.getOutputStream().write(
                            "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        client.getInputStream().transferTo(OutputStream.nullOutputStream());
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
            String https = "https://127.0.0.1:" + plain.getLocalPort();

            assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> new HttpFlexemarkets(
                    HttpFlexemarkets.loadProperties(TOKEN, https + "/api/marketplaces/1", "transport-failure-test")))
                .withMessageStartingWith("Cannot reach the server at " + https + " (TLS handshake failed: ")
                .withMessageNotContaining("TLS handshake failed: )");
        }
    }

    /**
     * A server whose accept queue is full never completes the TCP handshake,
     * which is what a connect timeout looks like from here -- and is named
     * as one, not as a refusal.
     */
    @Test
    @Timeout(30)
    void aConnectThatTimesOutIsNamedAsATimeout() throws Exception {
        try (var full = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var fillers = new ArrayList<Socket>();
            try {
                for (int i = 0; i < 4; i++) {
                    var filler = new Socket();
                    try {
                        filler.connect(full.getLocalSocketAddress(), 200);
                        fillers.add(filler);
                    } catch (IOException queueFull) {
                        filler.close();
                        break;
                    }
                }
                Properties properties = HttpFlexemarkets.loadProperties(
                    TOKEN, "http://127.0.0.1:" + full.getLocalPort() + "/api/marketplaces/1", "transport-failure-test");
                properties.setProperty("connect-timeout-seconds", "1");

                assertThatExceptionOfType(ApiException.class)
                    .isThrownBy(() -> new HttpFlexemarkets(properties))
                    .withMessageContaining("(connection timed out");
            } finally {
                for (Socket filler : fillers) filler.close();
            }
        }
    }
}
