package fm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A call that will never be answered gives up.
 *
 * <p>Neither half of Java's HTTP client has a timeout by default:
 * {@code HttpClient} waits forever to connect and {@code HttpRequest}
 * waits forever for a reply. So a connection that drops without the peer
 * saying anything -- a laptop changing networks, a VPN going down, a NAT
 * forgetting the flow -- leaves the call blocked with no exception to
 * catch and nothing to retry, and the process sits alive doing nothing.
 *
 * <p>On 2026-09-22 three study runs on two laptops stopped exactly that
 * way within minutes of each other: no output, no exception, the session
 * left OPEN on the server, the JVM still running. A Python client against
 * the same server kept working, its library having timeouts by default.
 * Whether a dead socket hangs or surfaces as "Connection reset" is the
 * operating system's choice -- Windows sits on one far longer than macOS
 * -- which is why it cannot be left to the operating system.
 */
class HttpTimeoutTest {

    /**
     * The failure itself, against a server that accepts and then says
     * nothing -- which is what a dropped connection looks like from this
     * side. Without the timeout this blocks until the test's own deadline
     * kills it; with it, it gives up in under a second.
     */
    @Test
    @Timeout(30)
    void aServerThatNeverAnswersDoesNotBlockForever() throws Exception {
        var accepted = new CountDownLatch(1);

        try (var server = new ServerSocket(0, 1, InetSocketAddress.createUnresolved("localhost", 0).getAddress())) {
            var listener = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    accepted.countDown();
                    // Hold it open and answer nothing, forever.
                    Thread.sleep(25_000);
                } catch (IOException | InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            listener.setDaemon(true);
            listener.start();

            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            var request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:" + server.getLocalPort() + "/api"))
                    .timeout(Duration.ofMillis(500))
                    .GET()
                    .build();

            long started = System.nanoTime();
            try {
                client.send(request, HttpResponse.BodyHandlers.ofString());
                org.junit.jupiter.api.Assertions.fail("expected the request to time out");
            } catch (HttpTimeoutException expected) {
                long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
                assertThat(elapsedMillis)
                    .as("gave up on its own rather than waiting to be killed")
                    .isLessThan(20_000L);
            }

            assertThat(accepted.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void defaultsApplyWhenNothingIsConfigured() {
        var properties = new Properties();

        assertThat(HttpFlexemarkets._timeout(properties, "connect-timeout-seconds", Duration.ofSeconds(10)))
            .isEqualTo(Duration.ofSeconds(10));
        assertThat(HttpFlexemarkets._timeout(null, "request-timeout-seconds", Duration.ofSeconds(60)))
            .isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void aCallerOnASlowLinkCanRaiseThem() {
        var properties = new Properties();
        properties.setProperty("request-timeout-seconds", "300");

        assertThat(HttpFlexemarkets._timeout(properties, "request-timeout-seconds", Duration.ofSeconds(60)))
            .isEqualTo(Duration.ofSeconds(300));
    }

    /**
     * Zero restores the old behaviour of waiting forever. Somebody
     * debugging a slow server may genuinely want that, and should not have
     * to patch the SDK to get it.
     */
    @Test
    void zeroMeansWaitForever() {
        var properties = new Properties();
        properties.setProperty("request-timeout-seconds", "0");

        var configured = HttpFlexemarkets._timeout(properties, "request-timeout-seconds", Duration.ofSeconds(60));

        assertThat(configured).isZero();

        // And a zero timeout is not handed to the builder, because
        // HttpRequest.Builder#timeout rejects it.
        var builder = HttpRequest.newBuilder().uri(java.net.URI.create("http://localhost/api"));
        HttpFlexemarkets._applyTimeout(builder, configured);
        assertThat(builder.GET().build().timeout()).isEmpty();
    }

    /**
     * A typo is not a reason to refuse to start. The default is the safe
     * answer, and a study that will not launch because a credential file
     * says "sixty" is a worse outcome than one that waits sixty seconds.
     */
    @Test
    void anUnparseableValueFallsBackRatherThanThrowing() {
        var properties = new Properties();
        properties.setProperty("connect-timeout-seconds", "sixty");

        assertThat(HttpFlexemarkets._timeout(properties, "connect-timeout-seconds", Duration.ofSeconds(10)))
            .isEqualTo(Duration.ofSeconds(10));
    }
}
