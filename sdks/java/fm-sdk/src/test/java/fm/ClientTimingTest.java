package fm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a call cost, reported to the server on the next one.
 *
 * <p>This is how a {@code container:} robot's distance from the exchange
 * becomes measurable at all. Its orders reach fm-server as ordinary REST
 * calls, so the server watches them land and never watches them leave — only
 * the caller holds both ends of the round trip, and only the caller can say
 * how long it took.
 *
 * <p>Asserted against a loopback server rather than a stubbed transport, like
 * the other API tests here: the header that actually goes out over a socket is
 * the whole subject, so a fake that never serialises one would be testing the
 * wrong thing.
 */
class ClientTimingTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";

    private static final Pattern RTT = Pattern.compile("rtt=(\\d+)");
    private static final Pattern NET = Pattern.compile("net=(\\d+)");

    private HttpServer server;

    /** Every Client-Timing seen, in order; null for a request that carried none. */
    private final List<String> timings = new ArrayList<>();

    /** What the server claims its own handling cost, or null to send nothing. */
    private volatile String serverTiming = "st=1000000";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/api/marketplaces", exchange -> {
            record(exchange);
            respond(exchange, 200, "{\"id\":1,\"name\":\"course\",\"markets\":[]}");
        });

        server.createContext("/api", exchange -> {
            record(exchange);
            respond(exchange, 200, """
                {"_links":{"marketplaces":{"href":"%1$s/marketplaces"},
                           "accounts":{"href":"%1$s/accounts"},
                           "users":{"href":"%1$s/users"}}}
                """.formatted(api()));
        });

        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private void record(HttpExchange exchange) {
        timings.add(exchange.getRequestHeaders().getFirst("Client-Timing"));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");

        if (serverTiming != null) {
            exchange.getResponseHeaders().add("Server-Timing", serverTiming);
        }

        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String api() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }

    private static long field(String header, Pattern pattern) {
        var matcher = pattern.matcher(header);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
    }

    /** Every Client-Timing that was actually sent, in order. */
    private List<String> reported() {
        return timings.stream().filter(t -> t != null).toList();
    }

    // ---------------------------------------------------------------------

    /**
     * Nothing to report on the first call. A round trip is not known until it
     * has finished, and by then the request that would have carried it is gone.
     */
    @Test
    void theFirstRequestCarriesNoTiming() throws Exception {
        try (var fm = Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "test")) {
            fm.marketplace(1);
        }

        assertThat(timings).isNotEmpty();
        assertThat(timings.get(0)).as("the first request has nothing to say yet").isNull();
    }

    @Test
    void aLaterRequestReportsWhatAnEarlierOneCost() throws Exception {
        try (var fm = Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "test")) {
            fm.marketplace(1);
            fm.marketplace(1);
            fm.marketplace(1);
        }

        assertThat(reported()).as("later calls report the earlier ones").isNotEmpty();
        assertThat(reported()).allMatch(t -> field(t, RTT) > 0);
    }

    /**
     * The point of the pair: the round trip contains the server's own work, so
     * on its own it cannot say whether a slow call was a slow link or a slow
     * exchange. Those are different problems with different answers.
     */
    @Test
    void theServersShareIsTakenOutOfTheWire() throws Exception {
        serverTiming = "st=1000000";                       // 1ms of server time

        try (var fm = Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "test")) {
            fm.marketplace(1);
            fm.marketplace(1);
        }

        var header = reported().get(0);

        assertThat(field(header, NET)).as("net= is present once the server has said").isNotNegative();
        assertThat(field(header, NET))
                .as("the wire is what is left after the server's share")
                .isEqualTo(field(header, RTT) - 1_000_000);
    }

    /**
     * A server that says nothing leaves the wire unknown, not zero. fm-server
     * reads an absent net= as "all of it was mine", which overstates its own
     * share — the safe direction, since it can never hide a slow link.
     */
    @Test
    void anUnreportedServerShareLeavesTheWireUnstated() throws Exception {
        serverTiming = null;                               // no Server-Timing at all

        try (var fm = Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "test")) {
            fm.marketplace(1);
            fm.marketplace(1);
        }

        var header = reported().get(0);

        assertThat(field(header, RTT)).as("the round trip is still known").isPositive();
        assertThat(header).as("but the wire is not claimed").doesNotContain("net=");
    }

    /** A garbled Server-Timing is treated as none rather than as a number. */
    @Test
    void anUnparseableServerShareIsIgnored() throws Exception {
        serverTiming = "st=banana";

        try (var fm = Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "test")) {
            fm.marketplace(1);
            fm.marketplace(1);
        }

        assertThat(reported().get(0)).doesNotContain("net=");
    }

    /**
     * Reported once, not until replaced.
     *
     * <p>Repeating one measurement on every later request would weight a single
     * slow call by however many quiet ones followed it, and a robot on a
     * two-second interval makes a great many quiet ones.
     */
    @Test
    void oneMeasurementIsReportedOnlyOnce() throws Exception {
        try (var fm = Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "test")) {
            fm.marketplace(1);
            fm.marketplace(1);
            fm.marketplace(1);
        }

        var sent = reported();

        assertThat(sent).as("more than one measurement to compare").hasSizeGreaterThan(1);
        assertThat(sent).as("each request carries its own predecessor's cost")
                .doesNotHaveDuplicates();
    }

    /**
     * The server's share cannot exceed the trip containing it. Clamped rather
     * than allowed negative, because a negative wire is not a fast one.
     */
    @Test
    void aServerShareLargerThanTheTripCannotGoNegative() throws Exception {
        serverTiming = "st=" + Long.MAX_VALUE;

        try (var fm = Flexemarkets.connect(TOKEN, api() + "/marketplaces/1", "test")) {
            fm.marketplace(1);
            fm.marketplace(1);
        }

        assertThat(field(reported().get(0), NET)).isZero();
    }
}
