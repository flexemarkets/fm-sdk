#!/usr/bin/env python3
"""fm-connect-stress — open many FM WebSocket connections, concurrently or
staggered, and measure how long each takes to establish *and become ready*
(i.e. receive initial session state).

Built to reproduce and quantify the "N robots take ~24s to connect" report
(fm-ui#751). Each connection is a STOMP-over-WebSocket session to
``/api/events`` — the same unit fm-server records as a ``ClientConnection``
(see fm.events.EventListener: WS open -> STOMP CONNECT/CONNECTED -> SUBSCRIBE).

Two phases are timed separately:
  establish  WS upgrade + STOMP CONNECT/CONNECTED + SUBSCRIBE frames sent
  ready      from subscribe to the first real state event pushed back by the
             server (SESSION-UPDATE / HOLDING-UPDATE / ORDERS-UPDATE). A STOMP
             ERROR frame (e.g. unauthorized for the marketplace) counts as a
             FAILURE, not a ready connection.

Pacing
------
  --connect-interval 0   (default) all connections fire as simultaneously as
                         possible (one thread each, released by a barrier) — "ASAP"
  --connect-interval S   connections open one at a time, S seconds apart
                         (use 9 to mimic Melbourne's FMClient cadence)

Auth
----
  --auth shared      authenticate once, reuse the bearer for all N WS
                     connections — isolates pure WS cost (default)
  --auth per-conn    authenticate a fresh client per connection — includes the
                     token round-trip, closer to N independent robots

Examples
--------
  # 7 robots, all at once (ASAP), against marketplace 1524, then verify
  ./connect_stress.py --marketplace 1524 --count 7 --verify-server

  # 7 connections, 9s apart (Melbourne cadence)
  ./connect_stress.py --marketplace 1524 --count 7 --connect-interval 9
"""

from __future__ import annotations

import argparse
import json
import queue
import statistics
import sys
import threading
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone

from fm import Flexemarkets
from fm.client import _server
from fm.events import EventListener, OrdersUpdate, WsException, WsTransportError
from fm.types import Holding, Session, Version

# Events that mean "the server has pushed us real session state".
_STATE_TYPES = (Session, Holding, OrdersUpdate)
_ERROR_TYPES = (WsException, WsTransportError)


# ---------------------------------------------------------------------------
# One connection's outcome
# ---------------------------------------------------------------------------

@dataclass
class Result:
    index: int
    description: str
    start_offset_s: float        # seconds from run start to when this connect began
    establish_s: float | None    # WS + STOMP CONNECT/CONNECTED + SUBSCRIBE sent
    ready_s: float | None        # subscribe -> first session-state event
    state_type: str | None       # event type received, or 'ERROR' / 'timeout'
    ok: bool                     # established AND received state (no ERROR/timeout)
    error: str | None = None
    wall_start_utc: str = ""     # ISO-8601 UTC when establish began


# ---------------------------------------------------------------------------
# Connection strategies — each exposes connect(), wait_for_state(), close()
# ---------------------------------------------------------------------------

def _wait_for_state(q: "queue.Queue[object]", timeout: float):
    """Drain *q* until a real state event arrives, an ERROR is pushed, or we
    time out. Returns (label, event). Skips Version (protocol handshake noise).
    """
    deadline = time.perf_counter() + timeout
    while True:
        remaining = deadline - time.perf_counter()
        if remaining <= 0:
            return "timeout", None
        try:
            ev = q.get(timeout=remaining)
        except queue.Empty:
            return "timeout", None
        if isinstance(ev, _ERROR_TYPES):
            return "ERROR", ev
        if isinstance(ev, list) and ev and isinstance(ev[0], Session):
            return "SessionList", ev           # SESSION-LIST snapshot
        if isinstance(ev, _STATE_TYPES):
            return type(ev).__name__, ev
        if isinstance(ev, Version):
            continue                            # keep waiting for real state
        # Unknown payloads: ignore and keep waiting.


class SharedConn:
    """WS connection reusing a pre-authenticated bearer (isolates WS cost)."""

    def __init__(self, ws_url: str, bearer: str, marketplace_id: int, description: str):
        self._q: queue.Queue[object] = queue.Queue()
        self._listener = EventListener(
            ws_url=ws_url, bearer_token=bearer, marketplace_id=marketplace_id,
            event_queue=self._q, client_description=description,
        )

    def connect(self) -> None:
        self._listener.start()

    def wait_for_state(self, timeout: float):
        return _wait_for_state(self._q, timeout)

    def close(self) -> None:
        self._listener.close()


class PerConnClient:
    """Full client per connection — authenticates, then opens its own WS."""

    def __init__(self, credential, endpoint, marketplace_id: int, description: str):
        self._credential = credential
        self._endpoint = endpoint
        self._marketplace_id = marketplace_id
        self._description = description
        self._q: queue.Queue[object] = queue.Queue()
        self._fm: Flexemarkets | None = None

    def connect(self) -> None:
        self._fm = Flexemarkets.connect(
            credential=self._credential, endpoint=self._endpoint,
            client_description=self._description,
        )
        self._fm.listen(self._marketplace_id, self._q)

    def wait_for_state(self, timeout: float):
        return _wait_for_state(self._q, timeout)

    def close(self) -> None:
        if self._fm is not None:
            self._fm.close()


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------

def _ws_url(endpoint: str) -> str:
    return _server(endpoint).replace("https://", "wss://").replace("http://", "ws://") + "/events"


def _run_one(conn, index: int, description: str, origin: float, ready_timeout: float):
    start_offset = time.perf_counter() - origin
    wall = datetime.now(timezone.utc).isoformat()
    t0 = time.perf_counter()
    try:
        conn.connect()
    except Exception as e:  # noqa: BLE001 — record any establish failure
        return Result(index, description, start_offset, None, None, None, False, repr(e), wall), conn

    establish = time.perf_counter() - t0

    if ready_timeout <= 0:
        # Establish-only mode (no state wait); not "ready" but not a failure.
        return Result(index, description, start_offset, establish, None, "(not waited)", True,
                      wall_start_utc=wall), conn

    tw = time.perf_counter()
    label, ev = conn.wait_for_state(ready_timeout)
    if label == "timeout":
        return Result(index, description, start_offset, establish, None, "timeout", False,
                      f"no state within {ready_timeout}s", wall), conn
    if label == "ERROR":
        body = getattr(ev, "body", None) or repr(getattr(ev, "exception", ev))
        return Result(index, description, start_offset, establish, None, "ERROR", False,
                      str(body)[:200], wall), conn
    return Result(index, description, start_offset, establish, time.perf_counter() - tw, label, True,
                  wall_start_utc=wall), conn


def run_asap(make_conn, count, origin, ready_timeout):
    results: list[Result | None] = [None] * count
    conns: list[object | None] = [None] * count
    barrier = threading.Barrier(count)
    threads: list[threading.Thread] = []

    def worker(i: int):
        conn, desc = make_conn(i)
        barrier.wait()                      # release all together
        results[i], conns[i] = _run_one(conn, i, desc, origin, ready_timeout)

    for i in range(count):
        t = threading.Thread(target=worker, args=(i,), name=f"stress-{i}", daemon=True)
        threads.append(t)
        t.start()
    for t in threads:
        t.join()
    return [r for r in results if r], [c for c in conns if c]


def run_staggered(make_conn, count, interval, origin, ready_timeout):
    results, conns = [], []
    for i in range(count):
        conn, desc = make_conn(i)
        r, c = _run_one(conn, i, desc, origin, ready_timeout)
        results.append(r)
        conns.append(c)
        if i < count - 1:
            time.sleep(interval)
    return results, conns


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------

def _fmt(v):
    return "    -" if v is None else f"{v:6.2f}s"


def report(results: list[Result], args) -> dict:
    oks = [r for r in results if r.ok]
    ready = sorted(r.ready_s for r in oks if r.ready_s is not None)

    starts = [r.start_offset_s for r in results]
    # "ready" wall span: first start -> last connection that became ready
    fin = [r.start_offset_s + (r.establish_s or 0) + (r.ready_s or 0)
           for r in oks if r.establish_s is not None]
    span = (max(fin) - min(starts)) if fin else 0.0

    def pct(p):
        if not ready:
            return None
        k = max(0, min(len(ready) - 1, int(round(p / 100 * (len(ready) - 1)))))
        return ready[k]

    summary = {
        "marketplace": args.marketplace,
        "mode": "asap" if args.connect_interval <= 0 else "staggered",
        "connect_interval_s": args.connect_interval,
        "auth": args.auth,
        "requested": args.count,
        "ready": len(oks),
        "failed": len(results) - len(oks),
        "wall_span_s": round(span, 3),
        "ready_min_s": round(ready[0], 3) if ready else None,
        "ready_median_s": round(statistics.median(ready), 3) if ready else None,
        "ready_p90_s": round(pct(90), 3) if ready else None,
        "ready_max_s": round(ready[-1], 3) if ready else None,
    }

    if args.json:
        print(json.dumps({"summary": summary, "results": [asdict(r) for r in results]}, indent=2))
        return summary

    pacing = "asap" if args.connect_interval <= 0 else f"interval={args.connect_interval}s"
    print(f"\n  marketplace {args.marketplace} | {pacing} | auth={args.auth} | n={args.count}\n")
    print(f"  {'#':>3}  {'start@':>8}  {'establish':>9}  {'ready':>7}  {'state':<12} status")
    print(f"  {'-'*3}  {'-'*8}  {'-'*9}  {'-'*7}  {'-'*12} {'-'*6}")
    for r in sorted(results, key=lambda x: x.index):
        status = "ok" if r.ok else f"FAIL {r.error or ''}"
        print(f"  {r.index+1:>3}  {r.start_offset_s:7.2f}s  {_fmt(r.establish_s)}  "
              f"{_fmt(r.ready_s)}  {(r.state_type or ''):<12} {status}")

    print(f"\n  ready       : {summary['ready']}/{summary['requested']}"
          + (f"   FAILED: {summary['failed']}" if summary['failed'] else ""))
    print(f"  wall span   : {summary['wall_span_s']:.2f}s   (first start -> last ready)")
    if ready:
        print(f"  ready time  : min {summary['ready_min_s']:.2f}s | "
              f"median {summary['ready_median_s']:.2f}s | "
              f"p90 {summary['ready_p90_s']:.2f}s | max {summary['ready_max_s']:.2f}s")
    return summary


def _server_connections(hub: Flexemarkets, marketplace_id: int):
    """Fetch the server's connection list via the /agents sub-resource.

    NB: the SDK's hub.connections() targets /connections, which 404s — the
    real resource is /marketplaces/{id}/agents (see fm-sdk connections() bug).
    """
    root = _server(hub.endpoint_url)
    resp = hub._http.get(
        f"{root}/marketplaces/{marketplace_id}/agents?format=application/json",
        headers={"Authorization": hub._bearer_token, "Accept": "application/json"},
    )
    resp.raise_for_status()
    out = []
    for c in resp.json():
        out.append(type("Conn", (), {
            "connection_id": c.get("connectionId") or c.get("id") or 0,
            "owner_id": c.get("ownerId", 0),
            "established": c.get("established"),
            "terminated": c.get("terminated"),
            "description": c.get("description"),
        }))
    return out


def verify_server(hub: Flexemarkets, marketplace_id: int, tag: str):
    conns = [c for c in _server_connections(hub, marketplace_id) if c.description and tag in c.description]
    if not conns:
        print(f"\n  [verify-server] no server-side connections matched tag '{tag}'")
        return

    def parse(ts):
        return datetime.fromisoformat(ts) if ts else None

    rows = sorted(((parse(c.established), c) for c in conns), key=lambda x: (x[0] or datetime.max))
    base = rows[0][0]
    print(f"\n  [verify-server] {len(rows)} connection(s) recorded by fm-server:")
    print(f"  {'established (UTC)':<28}{'+gap(s)':>9}  conn      owner   open?")
    prev = None
    for est, c in rows:
        gap = f"{(est-prev).total_seconds():.1f}" if (prev and est) else "-"
        openq = "open" if not c.terminated else "closed"
        print(f"  {str(est):<28}{gap:>9}  {c.connection_id:<8}  {c.owner_id}  {openq}")
        prev = est
    if rows[-1][0] and base:
        print(f"  server-side span: {(rows[-1][0]-base).total_seconds():.1f}s (first -> last established)")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv=None):
    p = argparse.ArgumentParser(
        description="Stress-test FM WebSocket connection establishment + readiness.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--marketplace", type=int, required=True, help="marketplace id to connect to")
    p.add_argument("--count", type=int, default=7, help="number of connections (default 7)")
    p.add_argument("--connect-interval", type=float, default=0.0,
                   help="seconds between connection starts; 0 (default) connects all at once (ASAP)")
    p.add_argument("--auth", choices=["shared", "per-conn"], default="shared",
                   help="reuse one bearer (shared) or authenticate per connection (per-conn)")
    p.add_argument("--ready-timeout", type=float, default=15.0,
                   help="max seconds to wait for initial session state per connection; "
                        "0 = establish only, don't wait for state (default 15)")
    p.add_argument("--hold", type=float, default=3.0,
                   help="seconds to keep connections open after the last is ready (default 3)")
    p.add_argument("--credential", default=None, help="credential file or token (default ~/.fm/credential)")
    p.add_argument("--endpoint", default=None, help="endpoint file or URL (default ~/.fm/endpoint)")
    p.add_argument("--client-description", default="fm-sdk-stress",
                   help="base client description; a unique per-connection suffix is appended")
    p.add_argument("--verify-server", action="store_true",
                   help="after holding, query fm-server for this run's connections and show cadence")
    p.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    args = p.parse_args(argv)

    run_id = datetime.now(timezone.utc).strftime("%H%M%S")
    tag = f"stress[{run_id}]"

    def describe(i: int) -> str:
        return f"{args.client_description} {tag} #{i+1}/{args.count}"

    if not args.json:
        print(f"  authenticating hub client (tag {tag}) ...", flush=True)
    hub = Flexemarkets.connect(
        credential=args.credential, endpoint=args.endpoint,
        client_description=f"{args.client_description} {tag} hub",
    )
    ws_url = _ws_url(hub.endpoint_url)

    if args.auth == "shared":
        bearer = hub._bearer_token  # internal, fine for an in-repo tool
        def make_conn(i):
            d = describe(i)
            return SharedConn(ws_url, bearer, args.marketplace, d), d
    else:
        def make_conn(i):
            d = describe(i)
            return PerConnClient(args.credential, args.endpoint, args.marketplace, d), d

    origin = time.perf_counter()
    if args.connect_interval <= 0:
        results, conns = run_asap(make_conn, args.count, origin, args.ready_timeout)
    else:
        results, conns = run_staggered(make_conn, args.count, args.connect_interval, origin, args.ready_timeout)

    summary = report(results, args)

    if args.hold > 0:
        if not args.json:
            print(f"\n  holding {args.hold:.0f}s ...", flush=True)
        time.sleep(args.hold)

    if args.verify_server:
        try:
            verify_server(hub, args.marketplace, tag)
        except Exception as e:  # noqa: BLE001
            print(f"  [verify-server] failed: {e!r}")

    for c in conns:
        try:
            c.close()
        except Exception:
            pass
    hub.close()

    return 1 if summary["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
