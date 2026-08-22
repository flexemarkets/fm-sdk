package fm.internal;

import fm.ApiException;
import fm.Flexemarkets;
import fm.FlexemarketsException;
import fm.Holding;
import fm.MarketView;
import fm.Order;
import fm.OrdersUpdate;
import fm.Reconnected;
import fm.Session;
import fm.Snapshot;
import fm.Subscription;
import fm.WsException;
import fm.WsTransportError;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;


public class Events implements Subscription {

    /**
     * Prefix on the {@code /app} SUBSCRIBE destination selecting fm-server's
     * WS API version. Empty string → V0 ({@code /app/marketplaces/{id}});
     * {@code "/v1"} → V1 ({@code /app/v1/marketplaces/{id}}). V1 omits the
     * bulk ORDERS-UPDATE snapshot on subscribe, keeping inbound frames
     * small. V1 is the default; override with
     * {@code -Dfm.net.ws.api-version=v0} if talking to an old fm-server
     * that doesn't speak V1.
     *
     * <p>NB: V1 SUBSCRIBE delivers an empty ORDERS-UPDATE; consumers that
     * need the active book at startup should fetch it via REST
     * ({@code GET /api/v1/marketplaces/{id}/orders/active}) and reconcile
     * against incoming deltas using the {@code seq} header.
     */
    private static final String API_VERSION_PREFIX = _resolveApiVersionPrefix();

    private static String _resolveApiVersionPrefix() {
        String version = System.getProperty("fm.net.ws.api-version", "v1").trim();
        if ("v0".equalsIgnoreCase(version)) return "";
        if ("v1".equalsIgnoreCase(version)) return "/v1";
        throw new IllegalArgumentException(
                "fm.net.ws.api-version must be 'v0' or 'v1', got: " + version);
    }

    private static final String MESSAGE_TYPE = "message-type";
    private static final String SEQ          = "seq";

    private static final String MESSAGE_TYPE_VERSION        = "VERSION";
    private static final String MESSAGE_TYPE_SESSION_LIST   = "SESSION-LIST";
    private static final String MESSAGE_TYPE_SESSION_UPDATE = "SESSION-UPDATE";
    private static final String MESSAGE_TYPE_HOLDING_UPDATE = "HOLDING-UPDATE";
    private static final String MESSAGE_TYPE_ORDERS_UPDATE  = "ORDERS-UPDATE";

    private static final TypeReference<Version>    VERSION_TYPE  = new TypeReference<>() {};
    private static final TypeReference<Session[]>  SESSIONS_TYPE = new TypeReference<>() {};
    private static final TypeReference<Session>    SESSION_TYPE  = new TypeReference<>() {};
    private static final TypeReference<Holding>    HOLDING_TYPE  = new TypeReference<>() {};
    private static final TypeReference<Order[]>    ORDERS_TYPE   = new TypeReference<>() {};

    private final String wsUrl;
    private final String bearerToken;
    private final long marketplaceId;
    private final String clientDescription;
    private final ObjectMapper mapper;
    private final BlockingQueue<Object> queue;

    private volatile WebSocket webSocket;
    private volatile boolean closed;

    /** One reconnect at a time: onClose and onError can both fire for one drop. */
    private final java.util.concurrent.atomic.AtomicBoolean reconnecting =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final AtomicInteger subscriptionId = new AtomicInteger(0);

    Events(String wsUrl, String bearerToken, long marketplaceId, String clientDescription,
           ObjectMapper mapper, BlockingQueue<Object> queue) {
        this.wsUrl = wsUrl;
        this.bearerToken = bearerToken;
        this.marketplaceId = marketplaceId;
        this.clientDescription = clientDescription;
        this.mapper = mapper;
        this.queue = queue;
    }

    void connect() {
        try {
            var connectedLatch = new CountDownLatch(1);
            var listener = new StompListener(connectedLatch);

            // NORMAL rather than the JDK's default of NEVER, for the same reason
            // the REST client uses it: an edge that upgrades plain HTTP to HTTPS
            // answers the handshake with a 301 the client must follow.
            this.webSocket = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .newWebSocketBuilder()
                .header("Authorization", bearerToken)
                .subprotocols("v12.stomp", "v11.stomp", "v10.stomp")
                .buildAsync(URI.create(wsUrl), listener)
                .join();

            sendStompConnect();

            if (!connectedLatch.await(10, TimeUnit.SECONDS)) {
                throw new ApiException("STOMP CONNECTED frame not received within timeout");
            }

            // fm-server publishes broadcasts on the V0 destination paths
            // (/topic/marketplaces/{id}, /user/queue/marketplaces/{id})
            // for both V0 and V1 clients — only the @SubscribeMapping
            // gating the initial snapshot lives at the /v1 prefix. So
            // pub/sub subscriptions stay on V0 paths regardless of the
            // chosen api-version; only the /app destination flips.
            // Mirrors fm-ui's web-socket.service.ts and fm-robots'
            // EventParser pattern.
            subscribe("/user/queue/marketplaces/" + marketplaceId);
            subscribe("/topic/marketplaces/" + marketplaceId);
            subscribe("/app" + API_VERSION_PREFIX + "/marketplaces/" + marketplaceId);
        } catch (FlexemarketsException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("WebSocket connection failed", e);
        }
    }

    /**
     * Re-establish the stream, retrying until it succeeds or this is closed.
     *
     * <p>Driven from {@link #_reconnectInBackground()} rather than by a
     * consumer: whether the socket is up is this class's business, and a
     * consumer that had to run the retry loop would be doing the transport's
     * job on its own thread.
     *
     * <p>Package-private, not private, because {@link Flexemarkets#reconnect()}
     * still lets a caller force one deliberately. That is a different thing
     * from recovering a drop, and only the recovery moved in here.
     */
    void reconnect() throws InterruptedException {
        while (!closed) {
            try {
                closeWebSocket();
                connect();
                return;
            } catch (Exception e) {
                TimeUnit.SECONDS.sleep(2);
            }
        }
    }

    /**
     * React to a dropped stream by restoring it, off the callback thread.
     *
     * <p>The WebSocket listener must not block, and reconnect() sleeps between
     * attempts. On success a {@link Reconnected} goes onto the queue so
     * consumers know their state needs reseeding.
     */
    private void _reconnectInBackground() {
        if (closed || !reconnecting.compareAndSet(false, true)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                reconnect();
                if (!closed) {
                    queue.offer(new Reconnected(marketplaceId));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                reconnecting.set(false);
            }
        });
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        closeWebSocket();
    }

    private void closeWebSocket() {
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
            } catch (Exception ignored) {}
        }
    }

    // --- STOMP frame encoding/decoding ---

    private void sendStompConnect() {
        var frame = stompFrame("CONNECT",
            List.of(
                "accept-version:1.2",
                "heart-beat:30000,30000",
                "agent-description:" + clientDescription,
                "marketplace-id:" + marketplaceId
            ),
            null);
        webSocket.sendText(frame, true);
    }

    private void subscribe(String destination) {
        var id = "sub-" + subscriptionId.getAndIncrement();
        var frame = stompFrame("SUBSCRIBE",
            List.of("id:" + id, "destination:" + destination),
            null);
        webSocket.sendText(frame, true);
    }

    private static String stompFrame(String command, List<String> headers, String body) {
        var sb = new StringBuilder();
        sb.append(command).append('\n');
        for (var header : headers) {
            sb.append(header).append('\n');
        }
        sb.append('\n');
        if (body != null) {
            sb.append(body);
        }
        sb.append('\0');
        return sb.toString();
    }

    private void dispatchStompMessage(String frame) {
        var lines = frame.split("\n", -1);
        if (lines.length < 2) return;

        var command = lines[0].trim();

        if ("CONNECTED".equals(command)) {
            return; // handled by latch in listener
        }

        if ("ERROR".equals(command)) {
            queue.offer(new WsException("STOMP ERROR: " + frame, null));
            return;
        }

        if (!"MESSAGE".equals(command)) {
            return;
        }

        String messageType = null;
        long seq = Snapshot.NO_SEQ;
        int bodyStart = -1;

        for (int i = 1; i < lines.length; i++) {
            var line = lines[i];
            if (line.isEmpty()) {
                bodyStart = i + 1;
                break;
            }
            if (line.startsWith(MESSAGE_TYPE + ":")) {
                messageType = line.substring(MESSAGE_TYPE.length() + 1).trim();
            } else if (line.startsWith(SEQ + ":")) {
                // Per-marketplace ORDERS-UPDATE sequence number stamped
                // by fm-server (commit c6eea6eca). Used by MarketView /
                // Phase 2a snapshot reconciliation. Parse defensively —
                // a malformed value just falls back to NO_SEQ.
                try {
                    seq = Long.parseLong(line.substring(SEQ.length() + 1).trim());
                } catch (NumberFormatException ignored) { /* leave as NO_SEQ */ }
            }
        }

        if (messageType == null || bodyStart < 0) return;

        var bodyBuilder = new StringBuilder();
        for (int i = bodyStart; i < lines.length; i++) {
            if (i > bodyStart) bodyBuilder.append('\n');
            bodyBuilder.append(lines[i]);
        }
        var body = bodyBuilder.toString();
        // strip trailing null byte
        if (body.endsWith("\0")) {
            body = body.substring(0, body.length() - 1);
        }

        try {
            // ORDERS-UPDATE gets wrapped so the seq header reaches
            // consumers; everything else is pushed as the parsed
            // payload directly. This is a breaking change for callers
            // that did `case Order[] orders` — they need to switch to
            // `case OrdersUpdate update` and read update.orders().
            Object event = switch (messageType) {
                case MESSAGE_TYPE_VERSION        -> mapper.readValue(body, VERSION_TYPE);
                case MESSAGE_TYPE_SESSION_LIST   -> mapper.readValue(body, SESSIONS_TYPE);
                case MESSAGE_TYPE_SESSION_UPDATE -> mapper.readValue(body, SESSION_TYPE);
                case MESSAGE_TYPE_HOLDING_UPDATE -> mapper.readValue(body, HOLDING_TYPE);
                case MESSAGE_TYPE_ORDERS_UPDATE  -> new OrdersUpdate(mapper.readValue(body, ORDERS_TYPE), seq);
                default -> null;
            };
            if (event != null) {
                queue.offer(event);
            }
        } catch (Exception e) {
            queue.offer(new WsException("Failed to parse STOMP message: " + messageType, e));
        }
    }

    // --- WebSocket.Listener ---

    private class StompListener implements WebSocket.Listener {
        private final CountDownLatch connectedLatch;
        private final StringBuilder buffer = new StringBuilder();

        StompListener(CountDownLatch connectedLatch) {
            this.connectedLatch = connectedLatch;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                var frame = buffer.toString();
                buffer.setLength(0);

                if (frame.startsWith("CONNECTED")) {
                    connectedLatch.countDown();
                }

                // Dispatch in a virtual thread to avoid blocking the WS receive thread
                Thread.startVirtualThread(() -> dispatchStompMessage(frame));
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.sendPong(message);
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!closed) {
                queue.offer(new WsTransportError(
                    new Exception("WebSocket closed: %d %s".formatted(statusCode, reason))));
                _reconnectInBackground();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            queue.offer(new WsTransportError(error));
            _reconnectInBackground();
        }
    }
}
