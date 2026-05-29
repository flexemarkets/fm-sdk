"""WebSocket STOMP event listener for Flexemarkets.

Implements STOMP 1.2 framing over WebSocket, matching the Java
EventParser / Flexemarkets.listen() behaviour.
"""

from __future__ import annotations

import json
import logging
import os
import queue
import threading
import time
from dataclasses import dataclass, field
from typing import Any

import websockets.sync.client as ws_sync

from .types import Holding, Order, Session, Version

log = logging.getLogger(__name__)

_HEARTBEAT_MS = 30_000
_INBOUND_MESSAGE_SIZE = 128 * 1024 * 1024  # 128 MB
_NULL = "\x00"


def _resolve_api_version_prefix() -> str:
    """Prefix on the /app SUBSCRIBE destination selecting fm-server's
    WS API version. Empty string -> V0 (/app/marketplaces/{id});
    "/v1" -> V1 (/app/v1/marketplaces/{id}). V1 omits the bulk
    ORDERS-UPDATE snapshot on subscribe, keeping inbound frames small.
    V1 is the default; override with FM_WS_API_VERSION=v0 if talking
    to an old fm-server that doesn't speak V1.

    NB: V1 SUBSCRIBE delivers an empty ORDERS-UPDATE; consumers that
    need the active book at startup should fetch it via REST
    (GET /api/v1/marketplaces/{id}/orders/active) and reconcile
    against incoming deltas using the seq header.
    """
    v = os.environ.get("FM_WS_API_VERSION", "v1").strip().lower()
    if v == "v0":
        return ""
    if v == "v1":
        return "/v1"
    raise ValueError(f"FM_WS_API_VERSION must be 'v0' or 'v1', got: {v}")


_API_VERSION_PREFIX = _resolve_api_version_prefix()


# ---------------------------------------------------------------------------
# STOMP frame codec
# ---------------------------------------------------------------------------

@dataclass
class StompFrame:
    command: str = ""
    headers: dict[str, str] = field(default_factory=dict)
    body: str = ""


def _encode_frame(frame: StompFrame) -> str:
    lines = [frame.command]
    for k, v in frame.headers.items():
        lines.append(f"{k}:{v}")
    lines.append("")
    lines.append(frame.body)
    return "\n".join(lines) + _NULL


def _decode_frame(raw: str) -> StompFrame:
    # Strip trailing NUL and whitespace
    raw = raw.rstrip(_NULL)

    # Split command + headers from body at the first blank line
    header_end = raw.find("\n\n")
    if header_end < 0:
        # No body
        header_section = raw
        body = ""
    else:
        header_section = raw[:header_end]
        body = raw[header_end + 2:]

    lines = header_section.split("\n")
    command = lines[0] if lines else ""
    headers: dict[str, str] = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k] = v

    return StompFrame(command=command, headers=headers, body=body)


# ---------------------------------------------------------------------------
# Error / transport event types put onto the queue
# ---------------------------------------------------------------------------

@dataclass
class WsTransportError:
    exception: BaseException


@dataclass
class WsException:
    command: str
    headers: dict[str, str]
    body: str
    exception: BaseException


# Sentinel for "no seq header" — see OrdersUpdate.seq docstring.
NO_SEQ = -1


@dataclass
class OrdersUpdate:
    """One ORDERS-UPDATE delta from the WS stream, carrying the parsed
    order list plus the per-marketplace ``seq`` header fm-server stamps
    each frame with (commit c6eea6eca).

    Consumers reconcile this against a REST :class:`Snapshot`: apply
    deltas whose :attr:`seq` is greater than the snapshot's
    ``as_of_seq`` and skip those whose seq is less than or equal.

    :attr:`seq` is :data:`NO_SEQ` when the server didn't stamp the
    header (older fm-server).
    """

    orders: list[Order]
    seq: int


# ---------------------------------------------------------------------------
# Event parsing — mirrors Java EventParser.getPayloadType()
# ---------------------------------------------------------------------------

_MESSAGE_TYPE = "message-type"
_SEQ_HEADER   = "seq"


def _parse_event(frame: StompFrame) -> object | None:
    msg_type = frame.headers.get(_MESSAGE_TYPE)
    if msg_type is None:
        log.warning("Unresolved payload type, headers: %s", frame.headers)
        return None

    try:
        data = json.loads(frame.body) if frame.body else None
    except json.JSONDecodeError:
        log.warning("Failed to decode JSON body for %s", msg_type)
        return None

    match msg_type:
        case "VERSION":
            return _parse_version(data)
        case "SESSION-LIST":
            return _parse_session_list(data)
        case "SESSION-UPDATE":
            return _parse_session(data)
        case "HOLDING-UPDATE":
            return _parse_holding(data)
        case "ORDERS-UPDATE":
            return OrdersUpdate(
                orders=_parse_orders(data),
                seq=_parse_seq(frame.headers.get(_SEQ_HEADER)),
            )
        case _:
            log.warning("Unknown message-type: %s", msg_type)
            return None


def _parse_seq(value: str | None) -> int:
    """Parse the per-frame ``seq`` STOMP header; falls back to
    :data:`NO_SEQ` on absent or malformed values.
    """
    if value is None:
        return NO_SEQ
    try:
        return int(value)
    except ValueError:
        return NO_SEQ


def _parse_version(data: Any) -> Version:
    if isinstance(data, dict):
        return Version(version=data.get("version", 0))
    return Version(version=int(data) if data else 0)


def _parse_session(data: dict[str, Any]) -> Session:
    return Session(
        marketplace_id=data.get("marketplaceId", 0),
        allocation_id=data.get("allocationId", 0),
        id=data.get("id", 0),
        original=data.get("original", 0),
        state=data.get("state"),
        name=data.get("name"),
        description=data.get("description"),
        open_date=data.get("openDate"),
        close_date=data.get("closeDate"),
    )


def _parse_session_list(data: Any) -> list[Session]:
    if isinstance(data, list):
        return [_parse_session(s) for s in data]
    return []


def _parse_holding(data: dict[str, Any]) -> Holding:
    from .client import _parse_holding as _client_parse_holding
    return _client_parse_holding(data)


def _parse_order(data: dict[str, Any]) -> Order:
    from .client import _parse_order as _client_parse_order
    return _client_parse_order(data)


def _parse_orders(data: Any) -> list[Order]:
    if isinstance(data, list):
        return [_parse_order(o) for o in data]
    return []


# ---------------------------------------------------------------------------
# EventListener — background thread that manages WS + STOMP
# ---------------------------------------------------------------------------

class EventListener:
    """Manages a STOMP-over-WebSocket connection and pushes typed events
    into a :class:`queue.Queue`.

    This mirrors the Java ``Flexemarkets.listen()`` + ``EventParser``
    combination.
    """

    def __init__(
        self,
        ws_url: str,
        bearer_token: str,
        marketplace_id: int,
        event_queue: queue.Queue[object],
        client_description: str = "Unspecified client",
    ):
        self._ws_url = ws_url
        self._bearer_token = bearer_token
        self._marketplace_id = marketplace_id
        self._queue = event_queue
        self._client_description = client_description

        self._ws: ws_sync.ClientConnection | None = None
        self._thread: threading.Thread | None = None
        self._closed = False
        self._subscription_counter = 0

    # -- public API --------------------------------------------------------

    def start(self) -> None:
        """Connect and begin receiving events in a background thread."""
        self._ws = self._connect()
        self._stomp_connect()
        self._subscribe()

        self._thread = threading.Thread(
            target=self._receive_loop,
            name="fm-ws-events",
            daemon=True,
        )
        self._thread.start()

    def reconnect(self) -> None:
        """Reconnect after a transport error.  Blocks until connected."""
        while not self._closed:
            try:
                self._disconnect()
                self._ws = self._connect()
                self._stomp_connect()
                self._subscribe()

                self._thread = threading.Thread(
                    target=self._receive_loop,
                    name="fm-ws-events",
                    daemon=True,
                )
                self._thread.start()
                return
            except Exception:
                time.sleep(2)

    def close(self) -> None:
        """Disconnect and stop the background thread."""
        if self._closed:
            return
        self._closed = True
        self._disconnect()

    # -- WebSocket connection ----------------------------------------------

    def _connect(self) -> ws_sync.ClientConnection:
        return ws_sync.connect(
            self._ws_url,
            additional_headers={
                "Authorization": self._bearer_token,
            },
            subprotocols=["v12.stomp", "v11.stomp", "v10.stomp"],
            max_size=_INBOUND_MESSAGE_SIZE,
        )

    def _disconnect(self) -> None:
        if self._ws is not None:
            try:
                self._ws.close()
            except Exception:
                pass
            self._ws = None

    # -- STOMP framing -----------------------------------------------------

    def _next_id(self) -> str:
        self._subscription_counter += 1
        return f"sub-{self._subscription_counter}"

    def _stomp_connect(self) -> None:
        frame = StompFrame(
            command="CONNECT",
            headers={
                "accept-version": "1.2,1.1,1.0",
                "heart-beat": f"{_HEARTBEAT_MS},{_HEARTBEAT_MS}",
                "agent-description": self._client_description,
                "marketplace-id": str(self._marketplace_id),
            },
        )
        self._ws.send(_encode_frame(frame))  # type: ignore[union-attr]

        # Wait for CONNECTED frame
        raw = self._ws.recv()  # type: ignore[union-attr]
        reply = _decode_frame(raw)
        if reply.command not in ("CONNECTED", ""):
            log.debug("STOMP CONNECT reply: %s", reply.command)

    def _subscribe(self) -> None:
        # fm-server publishes broadcasts on the V0 destination paths
        # (/topic/marketplaces/{id}, /user/queue/marketplaces/{id}) for
        # both V0 and V1 clients — only the @SubscribeMapping gating
        # the initial snapshot lives at the /v1 prefix. So pub/sub
        # subscriptions stay on V0 paths regardless of the chosen
        # api-version; only the /app destination flips. Mirrors
        # fm-ui's web-socket.service.ts and the Java/TS SDKs.
        resource = f"/marketplaces/{self._marketplace_id}"
        for dest in (
            f"/user/queue{resource}",
            f"/topic{resource}",
            f"/app{_API_VERSION_PREFIX}{resource}",
        ):
            frame = StompFrame(
                command="SUBSCRIBE",
                headers={
                    "id": self._next_id(),
                    "destination": dest,
                },
            )
            self._ws.send(_encode_frame(frame))  # type: ignore[union-attr]

    # -- receive loop (background thread) ----------------------------------

    def _receive_loop(self) -> None:
        try:
            while not self._closed:
                try:
                    raw = self._ws.recv(timeout=_HEARTBEAT_MS / 1000 * 2)  # type: ignore[union-attr]
                except TimeoutError:
                    continue

                if not raw or raw == "\n":
                    # STOMP heartbeat
                    continue

                frame = _decode_frame(raw)

                if frame.command == "MESSAGE":
                    event = _parse_event(frame)
                    if event is not None:
                        self._queue.put(event)
                elif frame.command == "ERROR":
                    self._queue.put(
                        WsException(
                            command=frame.command,
                            headers=frame.headers,
                            body=frame.body,
                            exception=RuntimeError(frame.headers.get("message", "STOMP ERROR")),
                        )
                    )
                # Ignore RECEIPT, CONNECTED, heartbeats, etc.

        except Exception as exc:
            if not self._closed:
                self._queue.put(WsTransportError(exception=exc))
