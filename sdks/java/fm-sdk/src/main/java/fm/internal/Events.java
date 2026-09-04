package fm.internal;

import fm.event.FrameUnreadable;
import fm.event.OrdersUpdate;
import fm.event.StreamReconnected;
import fm.event.StreamDropped;
import fm.event.Version;
import fm.role.Streaming;
import fm.model.Holding;
import fm.model.Order;
import fm.model.Session;
import fm.Flexemarkets;
import fm.Desk;
import fm.Snapshot;
import fm.Subscription;
import fm.error.ApiException;
import fm.error.AuthenticationException;
import fm.error.FlexemarketsException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;


/**
 * The STOMP client underneath {@link fm.Streaming}: one WebSocket
 * subscription, delivering onto a caller's queue.
 *
 * <p>Internal because it is the implementation. A caller reaches this through
 * {@code listen}, {@code subscribe} or {@code desk}, and holds the
 * {@link Subscription} rather than this type.
 */
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

    private final String _wsUrl;
    private final String _bearerToken;
    private final long _marketplaceId;
    private final String _clientDescription;
    private final ObjectMapper _mapper;
    private final BlockingQueue<Object> _queue;

    /**
     * How often a heartbeat goes out, under the 30s this client ADVERTISES in
     * CONNECT so it is never late, and well under the 55 seconds after which
     * Heroku's router closes an idle connection and records an H15.
     */
    static final long HEARTBEAT_INTERVAL_SECONDS = 25;

    /** What this client advertises in CONNECT, and must therefore keep to. */
    static final long ADVERTISED_HEARTBEAT_MS = 30_000;

    /**
     * After this much silence in either direction, Heroku's router closes the
     * connection and records an H15 against the app. Here so the interval
     * above can be tested against the thing it exists to stay under.
     */
    static final long HEROKU_IDLE_TIMEOUT_MS = 55_000;

    private volatile WebSocket _webSocket;
    private volatile boolean _closed;

    /**
     * Sends the heartbeats this client promises.
     *
     * <p>IT PROMISED THEM AND NEVER SENT ONE. The CONNECT frame has always
     * carried `heart-beat:30000,30000` -- "I will send every 30s, send me one
     * every 30s" -- and nothing in this class ever wrote to the socket again
     * except SUBSCRIBE. The server's own heartbeats covered for it, so nothing
     * broke; but a robot that idles is relying on the far end to keep its
     * socket alive, and the advertisement was a claim this client could not
     * back. A daemon thread, so it can never hold a JVM open.
     */
    private final ScheduledExecutorService _heartbeats =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var thread = new Thread(r, "fm-sdk-stomp-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
    private volatile ScheduledFuture<?> _heartbeat;

    /** One reconnect at a time: onClose and onError can both fire for one drop. */
    private final java.util.concurrent.atomic.AtomicBoolean _reconnecting =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final AtomicInteger _subscriptionId = new AtomicInteger(0);

    Events(String wsUrl, String bearerToken, long marketplaceId, String clientDescription,
           ObjectMapper mapper, BlockingQueue<Object> queue) {
        this._wsUrl = wsUrl;
        this._bearerToken = bearerToken;
        this._marketplaceId = marketplaceId;
        this._clientDescription = clientDescription;
        this._mapper = mapper;
        this._queue = queue;
    }

    void connect() {
        try {
            var connectedLatch = new CountDownLatch(1);
            var listener = new StompListener(connectedLatch);

            // NORMAL rather than the JDK's default of NEVER, for the same reason
            // the REST client uses it: an edge that upgrades plain HTTP to HTTPS
            // answers the handshake with a 301 the client must follow.
            this._webSocket = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .newWebSocketBuilder()
                .header("Authorization", _bearerToken)
                .subprotocols("v12.stomp", "v11.stomp", "v10.stomp")
                .buildAsync(URI.create(_wsUrl), listener)
                .join();

            _sendStompConnect();

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
            _subscribe("/user/queue/marketplaces/" + _marketplaceId);
            _subscribe("/topic/marketplaces/" + _marketplaceId);
            _subscribe("/app" + API_VERSION_PREFIX + "/marketplaces/" + _marketplaceId);

            _startHeartbeats();
        } catch (FlexemarketsException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("WebSocket connection failed", e);
        }
    }

    /**
     * Re-establish the stream, retrying until it succeeds or this is closed.
     *
     * <p>Driven from {@link #reconnectInBackground()} rather than by a
     * consumer: whether the socket is up is this class's business, and a
     * consumer that had to run the retry loop would be doing the transport's
     * job on its own thread.
     *
     * <p>Package-private, not private, because {@link Flexemarkets#reconnect()}
     * still lets a caller force one deliberately. That is a different thing
     * from recovering a drop, and only the recovery moved in here.
     */
    void reconnect() throws InterruptedException {
        while (!_closed) {
            try {
                _closeWebSocket();
                connect();
                return;
            } catch (Exception e) {
                // A REJECTED TOKEN IS NOT A BLIP, and retrying it is not
                // patience -- it is a client hammering a server that has
                // already given its final answer. Measured 2026-09-03: one NAT
                // gateway produced 11,918 handshake 401s in two hours, ~100 a
                // minute, against 360 successful connections from every other
                // client combined. Nothing told the operator their token had
                // expired; the loop simply ran.
                //
                // So an auth refusal ends the stream and says why, and every
                // other failure keeps the existing retry: a server restart or
                // a network drop IS a blip and recovering from it is the whole
                // point of this loop.
                if (_isAuthRefusal(e)) {
                    _closed = true;
                    _queue.offer(new StreamDropped(new AuthenticationException(
                        "WebSocket refused: the token was rejected. It has expired or was "
                        + "signed for another server; reconnecting cannot fix it.", e)));
                    return;
                }
                TimeUnit.SECONDS.sleep(2);
            }
        }
    }

    /**
     * Whether the handshake was refused for who we are rather than for what
     * the network did.
     *
     * <p>The status is read off the handshake response rather than a message,
     * because the text is the JDK's and could change under us. 403 counts too:
     * a token that is valid but not permitted here will not become permitted
     * by being presented again.
     */
    private static boolean _isAuthRefusal(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebSocketHandshakeException handshake) {
                var status = handshake.getResponse().statusCode();
                return status == 401 || status == 403;
            }
            if (cause == cause.getCause()) break;
        }
        return false;
    }

    /**
     * React to a dropped stream by restoring it, off the callback thread.
     *
     * <p>The WebSocket listener must not block, and reconnect() sleeps between
     * attempts. On success a {@link StreamReconnected} goes onto the queue so
     * consumers know their state needs reseeding.
     *
     * <p>Package-private for the same reason {@link #reconnect()} is: the
     * recovery path is otherwise reachable only through a live socket, since
     * the listener that calls it is built inside {@link #connect()}. Driving
     * it directly is what lets StreamReconnectTest cover the behaviour Python
     * and TypeScript have covered all along.
     */
    void reconnectInBackground() {
        if (_closed || !_reconnecting.compareAndSet(false, true)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                reconnect();
                if (!_closed) {
                    _queue.offer(new StreamReconnected(_marketplaceId));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                _reconnecting.set(false);
            }
        });
    }

    @Override
    public void close() {
        if (_closed) return;
        _closed = true;
        _closeWebSocket();
        _heartbeats.shutdownNow();
    }

    private void _closeWebSocket() {
        _stopHeartbeats();
        if (_webSocket != null) {
            try {
                _webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Begin sending the STOMP heartbeat: a bare end-of-line, which is what the
     * protocol defines a heartbeat to be.
     *
     * <p>Started only after CONNECTED. Before that there is no STOMP session
     * to heartbeat on, and a stray newline into a socket mid-handshake is a
     * frame the server has no reason to expect.
     *
     * <p>Failures are swallowed on purpose. A heartbeat that cannot be written
     * means the socket is gone, and the listener's onClose/onError is what
     * reconnects -- raising from a scheduled task would kill the schedule and
     * report a fault the transport is already handling.
     */
    private void _startHeartbeats() {
        _stopHeartbeats();
        _heartbeat = _heartbeats.scheduleAtFixedRate(() -> {
            var socket = _webSocket;
            if (_closed || socket == null) return;
            try {
                socket.sendText("\n", true);
            } catch (Exception ignored) {}
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void _stopHeartbeats() {
        var running = _heartbeat;
        if (running != null) {
            running.cancel(false);
            _heartbeat = null;
        }
    }

    // --- STOMP frame encoding/decoding ---

    private void _sendStompConnect() {
        var frame = _stompFrame("CONNECT",
            List.of(
                "accept-version:1.2",
                "heart-beat:" + ADVERTISED_HEARTBEAT_MS + "," + ADVERTISED_HEARTBEAT_MS,
                "agent-description:" + _clientDescription,
                "marketplace-id:" + _marketplaceId
            ),
            null);
        _webSocket.sendText(frame, true);
    }

    private void _subscribe(String destination) {
        var id = "sub-" + _subscriptionId.getAndIncrement();
        var frame = _stompFrame("SUBSCRIBE",
            List.of("id:" + id, "destination:" + destination),
            null);
        _webSocket.sendText(frame, true);
    }

    private static String _stompFrame(String command, List<String> headers, String body) {
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

    private void _dispatchStompMessage(String frame) {
        var lines = frame.split("\n", -1);
        if (lines.length < 2) return;

        var command = lines[0].trim();

        if ("CONNECTED".equals(command)) {
            return; // handled by latch in listener
        }

        if ("ERROR".equals(command)) {
            _queue.offer(new FrameUnreadable("STOMP ERROR: " + frame, null));
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
                // by fm-server (commit c6eea6eca). Used by Desk /
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
                case MESSAGE_TYPE_VERSION        -> _mapper.readValue(body, VERSION_TYPE);
                case MESSAGE_TYPE_SESSION_LIST   -> _mapper.readValue(body, SESSIONS_TYPE);
                case MESSAGE_TYPE_SESSION_UPDATE -> _mapper.readValue(body, SESSION_TYPE);
                case MESSAGE_TYPE_HOLDING_UPDATE -> _mapper.readValue(body, HOLDING_TYPE);
                case MESSAGE_TYPE_ORDERS_UPDATE  -> new OrdersUpdate(_mapper.readValue(body, ORDERS_TYPE), seq);
                default -> null;
            };
            if (event != null) {
                _queue.offer(event);
            }
        } catch (Exception e) {
            _queue.offer(new FrameUnreadable("Failed to parse STOMP message: " + messageType, e));
        }
    }

    // --- WebSocket.Listener ---

    private class StompListener implements WebSocket.Listener {
        private final CountDownLatch _connectedLatch;
        private final StringBuilder _buffer = new StringBuilder();

        StompListener(CountDownLatch connectedLatch) {
            this._connectedLatch = connectedLatch;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            _buffer.append(data);
            if (last) {
                var frame = _buffer.toString();
                _buffer.setLength(0);

                if (frame.startsWith("CONNECTED")) {
                    _connectedLatch.countDown();
                }

                // Dispatch in a virtual thread to avoid blocking the WS receive thread
                Thread.startVirtualThread(() -> _dispatchStompMessage(frame));
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
            if (!_closed) {
                _queue.offer(new StreamDropped(
                    new Exception("WebSocket closed: %d %s".formatted(statusCode, reason))));
                reconnectInBackground();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            _queue.offer(new StreamDropped(error));
            reconnectInBackground();
        }
    }
}
