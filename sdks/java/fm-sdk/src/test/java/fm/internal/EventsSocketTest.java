package fm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fm.Snapshot;
import fm.error.AuthenticationException;
import fm.event.FrameUnreadable;
import fm.event.OrdersUpdate;
import fm.event.StreamDropped;
import fm.event.StreamReconnected;
import fm.model.Holding;

/**
 * {@link Events} against a real WebSocket: the handshake, the STOMP frames it
 * sends, the frames it reads, and what a server closing the socket does.
 *
 * <p>StreamReconnectTest stubs {@code connect()}, so before this nothing below
 * it ran in a test -- connect, onText and the frame dispatch were at 0%
 * (test risk map, plan item 5). The server here is the least of RFC 6455 that
 * a STOMP client needs: the handshake, text and continuation frames, close.
 */
class EventsSocketTest {

    private static final long MP = 7L;

    private _Server server;
    private Events events;
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();

    @BeforeEach
    void start() throws IOException {
        server = new _Server();
    }

    @AfterEach
    void stop() throws IOException {
        if (events != null) events.close();
        server.close();
    }

    private Events connected() {
        events = new Events(server.url(), "Bearer the-token", MP, "fm-sdk-test", HttpFlexemarkets.MAPPER, queue);
        events.connect();
        return events;
    }

    private Object next() throws InterruptedException {
        Object event = queue.poll(5, TimeUnit.SECONDS);
        assertThat(event).as("an event within 5 s").isNotNull();
        return event;
    }

    private static String message(String type, String extraHeader, String body) {
        return "MESSAGE\ndestination:/user/queue/marketplaces/7\nmessage-type:" + type + "\n"
                + (extraHeader == null ? "" : extraHeader + "\n") + "\n" + body + "\0";
    }

    @Test
    @Timeout(20)
    void connectingPresentsTheTokenAndSubscribesToTheMarketplace() throws Exception {
        connected();

        _Connection socket = server.connection(0);
        assertThat(socket.headers.get("authorization")).isEqualTo("Bearer the-token");

        String connect = socket.received(0);
        assertThat(connect).startsWith("CONNECT\n")
                .contains("heart-beat:30000,30000")
                .contains("agent-description:fm-sdk-test")
                .contains("marketplace-id:7");

        assertThat(socket.awaitReceived(4)).as("CONNECT then three SUBSCRIBEs").isTrue();
        assertThat(socket.received(1)).contains("destination:/user/queue/marketplaces/7");
        assertThat(socket.received(2)).contains("destination:/topic/marketplaces/7");
        assertThat(socket.received(3)).contains("destination:/app/v1/marketplaces/7");
    }

    @Test
    @Timeout(20)
    void anOrdersUpdateArrivesWithItsSequenceNumber() throws Exception {
        connected();

        server.connection(0).send(message("ORDERS-UPDATE", "seq:41",
                "[{\"id\":5,\"type\":\"LIMIT\",\"side\":\"BUY\",\"units\":2,\"price\":300}]"));

        OrdersUpdate update = (OrdersUpdate) next();
        assertThat(update.seq()).isEqualTo(41);
        assertThat(update.orders()).hasSize(1);
        assertThat(update.orders()[0].id()).isEqualTo(5L);
    }

    /** A body that spans lines is one body, not its first line. */
    @Test
    @Timeout(20)
    void aBodyThatSpansLinesIsReadWhole() throws Exception {
        connected();

        server.connection(0).send(message("HOLDING-UPDATE", null, "{\n  \"cash\": 1500,\n  \"availableCash\": 900\n}"));

        Holding holding = (Holding) next();
        assertThat(holding.availableCash()).isEqualTo(900L);
    }

    @Test
    @Timeout(20)
    void anUnreadableSequenceNumberIsNoSequenceNumber() throws Exception {
        connected();

        server.connection(0).send(message("ORDERS-UPDATE", "seq:not-a-number", "[]"));

        assertThat(((OrdersUpdate) next()).seq()).isEqualTo(Snapshot.NO_SEQ);
    }

    @Test
    @Timeout(20)
    void aStompErrorAndAnUnreadableBodyAreReportedNotDropped() throws Exception {
        connected();
        _Connection socket = server.connection(0);

        socket.send("ERROR\nmessage:refused\n\nno such marketplace\0");
        assertThat(next()).isInstanceOf(FrameUnreadable.class);

        socket.send(message("HOLDING-UPDATE", null, "{not json"));
        assertThat(next()).isInstanceOf(FrameUnreadable.class);
    }

    /** A frame the server sends in WebSocket fragments is one frame to the client. */
    @Test
    @Timeout(20)
    void aFrameSentInFragmentsIsReassembled() throws Exception {
        connected();

        String whole = message("ORDERS-UPDATE", "seq:9", "[]");
        server.connection(0).sendFragmented(whole.substring(0, 20), whole.substring(20));

        assertThat(((OrdersUpdate) next()).seq()).isEqualTo(9);
    }

    /**
     * Frames reach the queue in the order the server sent them. A desk reads
     * ORDERS-UPDATE seq numbers in order and treats a jump as a gap to reseed
     * from, so two updates swapped in transit read as lost data.
     */
    @Test
    @Timeout(30)
    void framesReachTheQueueInTheOrderTheyWereSent() throws Exception {
        connected();
        _Connection socket = server.connection(0);

        int count = 300;
        for (int seq = 1; seq <= count; seq++) {
            socket.send(message("ORDERS-UPDATE", "seq:" + seq, "[]"));
        }

        List<Long> received = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            received.add(((OrdersUpdate) next()).seq());
        }
        assertThat(received).isSorted();
    }

    /** The server closing the socket is a drop, recovered and announced. */
    @Test
    @Timeout(30)
    void aServerCloseIsADropThatReconnectsAndSaysSo() throws Exception {
        connected();

        server.connection(0).close();

        assertThat(next()).isInstanceOf(StreamDropped.class);
        assertThat(next()).isEqualTo(new StreamReconnected(MP));
        assertThat(server.connectionCount()).isEqualTo(2);
        assertThat(server.connection(1).awaitReceived(4)).as("the new socket subscribes again").isTrue();
    }

    /**
     * A token the server refuses on reconnect ends the stream with the reason,
     * instead of retrying a final answer every two seconds.
     */
    @Test
    @Timeout(30)
    void aRefusedTokenOnReconnectEndsTheStreamAndSaysWhy() throws Exception {
        connected();
        server.refuseWith(401);

        server.connection(0).close();

        assertThat(next()).isInstanceOf(StreamDropped.class);
        StreamDropped refused = (StreamDropped) next();
        assertThat(refused.failure()).isInstanceOf(AuthenticationException.class);
        assertThat(queue.poll(3, TimeUnit.SECONDS)).as("and nothing after it").isNull();
        assertThat(server.handshakes()).as("one refused attempt, not a retry loop").isEqualTo(2);
    }

    @Test
    @Timeout(20)
    void versionAndSessionMessagesArriveAsTheirOwnTypes() throws Exception {
        connected();
        _Connection socket = server.connection(0);

        socket.send(message("VERSION", null, "{\"version\":4}"));
        socket.send(message("SESSION-LIST", null, "[{\"id\":30,\"state\":\"OPEN\"},{\"id\":31,\"state\":\"INIT\"}]"));
        socket.send(message("SESSION-UPDATE", null, "{\"id\":30,\"state\":\"PAUSED\"}"));

        assertThat(next()).isEqualTo(new fm.event.Version(4));
        assertThat((fm.model.Session[]) next()).extracting(fm.model.Session::id).containsExactly(30L, 31L);
        assertThat(((fm.model.Session) next()).state()).isEqualTo(fm.model.Session.STATE_PAUSED);
    }

    /**
     * What this client does not read is skipped, not queued: a frame that is
     * not a MESSAGE, even one carrying a message-type, and a MESSAGE of a type
     * it does not know. The update after them is the next thing on the queue.
     */
    @Test
    @Timeout(20)
    void framesThisClientDoesNotReadAreSkipped() throws Exception {
        connected();
        _Connection socket = server.connection(0);

        socket.send("RECEIPT\nreceipt-id:1\nmessage-type:ORDERS-UPDATE\nseq:1\n\n[]\0");
        socket.send(message("MARKET-NEWS", null, "{}"));
        socket.send(message("ORDERS-UPDATE", "seq:3", "[]"));

        assertThat(((OrdersUpdate) next()).seq()).isEqualTo(3);
        assertThat(queue).isEmpty();
    }

    /**
     * The heartbeat this client advertises is actually written. It once
     * advertised one and never sent it; the interval is shortened here so
     * that is visible in a test rather than in twenty-five seconds.
     */
    @Test
    @Timeout(20)
    void heartbeatsAreWrittenWhileConnected() throws Exception {
        events = new Events(server.url(), "Bearer the-token", MP, "fm-sdk-test", HttpFlexemarkets.MAPPER, queue);
        events.heartbeatIntervalMillis = 50;
        events.connect();
        _Connection socket = server.connection(0);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (socket.heartbeats.get() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(socket.heartbeats.get()).isGreaterThanOrEqualTo(2);
    }

    /** A server that accepts the socket and never says CONNECTED fails connect(), saying which frame it was waiting for. */
    @Test
    @Timeout(20)
    void aServerThatNeverSaysConnectedFailsTheConnect() {
        server.silent = true;
        events = new Events(server.url(), "Bearer the-token", MP, "fm-sdk-test", HttpFlexemarkets.MAPPER, queue);
        events.connectedTimeoutMillis = 200;

        org.assertj.core.api.Assertions.assertThatExceptionOfType(fm.error.ApiException.class)
            .isThrownBy(events::connect)
            .withMessage("STOMP CONNECTED frame not received within timeout");
    }

    /** A connection cut without a close frame is a drop too, recovered and announced. */
    @Test
    @Timeout(30)
    void aConnectionCutWithoutACloseIsADropThatReconnects() throws Exception {
        connected();

        server.connection(0).abort();

        assertThat(next()).isInstanceOf(StreamDropped.class);
        assertThat(next()).isEqualTo(new StreamReconnected(MP));
        assertThat(server.connection(1).awaitReceived(4)).as("the new socket subscribes again").isTrue();
    }

    /**
     * A reconnect that cannot reach the server is a blip, not a verdict: it
     * waits and tries again, where a refused token ends the stream. The wait
     * is what keeps a client from hammering a server that is restarting.
     *
     * <p>Two handshakes are dropped because the JDK's HTTP client quietly
     * retries a GET whose connection closed before any response, once; only
     * the second drop reaches Events.
     */
    @Test
    @Timeout(30)
    void aReconnectThatCannotReachTheServerWaitsAndTriesAgain() throws Exception {
        connected();
        server.dropHandshakes(2);

        long dropped = System.nanoTime();
        server.connection(0).close();

        assertThat(next()).isInstanceOf(StreamDropped.class);
        assertThat(next()).isEqualTo(new StreamReconnected(MP));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dropped)).as("waited before retrying")
            .isGreaterThanOrEqualTo(1_500L);
    }

    /**
     * An fm.net.ws.api-version other than v0 or v1 is refused when Events
     * loads, naming what it got, rather than quietly subscribing to a
     * destination the server does not have. The prefix is read once per
     * class loader, so the class is loaded afresh with the property set.
     */
    @Test
    void anUnknownApiVersionIsRefusedByName() throws Exception {
        java.net.URL[] path = java.util.stream.Stream.of(
                Events.class, tools.jackson.databind.ObjectMapper.class,
                tools.jackson.core.type.TypeReference.class, com.fasterxml.jackson.annotation.JsonAlias.class)
            .map(type -> type.getProtectionDomain().getCodeSource().getLocation())
            .toArray(java.net.URL[]::new);
        String previous = System.getProperty("fm.net.ws.api-version");
        System.setProperty("fm.net.ws.api-version", "v2");

        try (var loader = new java.net.URLClassLoader(path, ClassLoader.getPlatformClassLoader())) {
            org.assertj.core.api.Assertions.assertThatExceptionOfType(ExceptionInInitializerError.class)
                .isThrownBy(() -> Class.forName(Events.class.getName(), true, loader))
                .havingCause()
                .isInstanceOf(IllegalArgumentException.class)
                .withMessage("fm.net.ws.api-version must be 'v0' or 'v1', got: v2");
        } finally {
            if (previous == null) System.clearProperty("fm.net.ws.api-version");
            else System.setProperty("fm.net.ws.api-version", previous);
        }
    }

    // --- the least of a WebSocket server a STOMP client needs ---

    private static final class _Server implements AutoCloseable {
        private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        private final ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final List<_Connection> connections = new CopyOnWriteArrayList<>();
        private volatile int refuseWith;
        private volatile int handshakes;
        private final java.util.concurrent.atomic.AtomicInteger dropHandshakes = new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean silent;

        _Server() throws IOException {
            Thread.startVirtualThread(this::_accept);
        }

        String url() {
            return "ws://127.0.0.1:" + socket.getLocalPort() + "/api/events";
        }

        void refuseWith(int status) {
            refuseWith = status;
        }

        /** Hang up on the next {@code count} handshakes without a word: no status, so no handshake failure either. */
        void dropHandshakes(int count) {
            dropHandshakes.set(count);
        }

        int handshakes() {
            return handshakes;
        }

        int connectionCount() {
            return connections.size();
        }

        _Connection connection(int index) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (connections.size() <= index && System.nanoTime() < deadline) Thread.sleep(10);
            return connections.get(index);
        }

        private void _accept() {
            while (!socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    Thread.startVirtualThread(() -> _handshake(client));
                } catch (IOException closed) {
                    return;
                }
            }
        }

        private void _handshake(Socket client) {
            try {
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                Map<String, String> headers = _readHeaders(in);
                handshakes++;
                if (dropHandshakes.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    client.close();
                    return;
                }
                if (refuseWith != 0) {
                    out.write(("HTTP/1.1 " + refuseWith + " Refused\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    client.close();
                    return;
                }
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((headers.get("sec-websocket-key") + GUID).getBytes(StandardCharsets.US_ASCII)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\nSec-WebSocket-Protocol: v12.stomp\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                _Connection connection = new _Connection(client, headers, !silent);
                connections.add(connection);
                connection.read();
            } catch (Exception gone) {
                try { client.close(); } catch (IOException ignored) { /* already gone */ }
            }
        }

        private static Map<String, String> _readHeaders(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int b = in.read();
                if (b < 0) throw new IOException("closed during handshake");
                head.write(b);
                matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            Map<String, String> headers = new HashMap<>();
            for (String line : head.toString(StandardCharsets.US_ASCII).split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            return headers;
        }

        @Override
        public void close() throws IOException {
            socket.close();
            for (_Connection connection : connections) connection.close();
        }
    }

    private static final class _Connection {
        final Map<String, String> headers;
        private final Socket socket;
        private final List<String> received = new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger heartbeats = new java.util.concurrent.atomic.AtomicInteger();
        private final boolean answerConnect;

        _Connection(Socket socket, Map<String, String> headers, boolean answerConnect) {
            this.socket = socket;
            this.headers = headers;
            this.answerConnect = answerConnect;
        }

        String received(int index) throws InterruptedException {
            assertThat(awaitReceived(index + 1)).as("message %d from the client", index).isTrue();
            return received.get(index);
        }

        boolean awaitReceived(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (received.size() < count && System.nanoTime() < deadline) Thread.sleep(10);
            return received.size() >= count;
        }

        /** Reads client frames until close; answers CONNECT with CONNECTED. */
        void read() throws IOException {
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            while (true) {
                int first = in.read();
                if (first < 0) return;
                int second = in.read();
                boolean fin = (first & 0x80) != 0;
                int opcode = first & 0x0F;
                long length = second & 0x7F;
                if (length == 126) length = (in.read() << 8) | in.read();
                else if (length == 127) { length = 0; for (int i = 0; i < 8; i++) length = (length << 8) | in.read(); }
                byte[] mask = (second & 0x80) != 0 ? in.readNBytes(4) : new byte[4];
                byte[] payload = in.readNBytes((int) length);
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                if (opcode == 0x8) { close(); return; }
                if (opcode != 0x1 && opcode != 0x0) continue;
                message.write(payload);
                if (!fin) continue;
                String text = message.toString(StandardCharsets.UTF_8);
                message.reset();
                if (text.isBlank()) { heartbeats.incrementAndGet(); continue; }
                received.add(text);
                if (answerConnect && text.startsWith("CONNECT\n")) send("CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0");
            }
        }

        synchronized void send(String text) throws IOException {
            _frame(0x80 | 0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        synchronized void sendFragmented(String first, String rest) throws IOException {
            _frame(0x1, first.getBytes(StandardCharsets.UTF_8));
            _frame(0x80, rest.getBytes(StandardCharsets.UTF_8));
        }

        /** Cut the connection without a close frame, as a dead network does. */
        synchronized void abort() {
            try {
                socket.setSoLinger(true, 0);
                socket.close();
            } catch (IOException ignored) { /* already closed */ }
        }

        synchronized void close() {
            try {
                _frame(0x80 | 0x8, new byte[] { 0x03, (byte) 0xE8 });
                socket.close();
            } catch (IOException ignored) { /* already closed */ }
        }

        private void _frame(int head, byte[] payload) throws IOException {
            OutputStream out = socket.getOutputStream();
            out.write(head);
            if (payload.length < 126) {
                out.write(payload.length);
            } else if (payload.length < 65536) {
                out.write(126);
                out.write(payload.length >> 8);
                out.write(payload.length & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) out.write((int) ((long) payload.length >> (8 * i)) & 0xFF);
            }
            out.write(payload);
            out.flush();
        }
    }
}
