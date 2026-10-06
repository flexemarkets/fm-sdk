#!/usr/bin/env python3
"""Time the server-initiated herd: a session state change pushed to a whole class.

When a manager opens, pauses or closes a marketplace, fm-server pushes the new
session to every subscriber -- and, on open, each one's holding and the order
book. This connects N distinct users (one-time codes minted by the manager, as
marketplace_load_repro.py does), waits until every socket has its first state,
then has the manager pause and re-open (a resume), and close and re-open (a new
session), timing:

  * the manager's own request (the push may run inside it);
  * per user, from the request to the Session update carrying the new state,
    and on open to that user's Holding and the OrdersUpdate.

    ./session_fanout.py --marketplace 3948 --users 1000 \\
        --credential ~/.fm/credential --endpoint http://127.0.0.1:8090/api
"""
from __future__ import annotations

import argparse
import queue
import statistics
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import httpx

from fm.client import Flexemarkets
from fm.events import OrdersUpdate
from fm.types import Holding, Session

from connect_stress import SharedConn, _ws_url
from marketplace_load_repro import mint_user_tokens, _root


def pct(values, p):
    if not values:
        return None
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]


def fmt(v):
    return "-" if v is None else f"{v:.2f}s"


class Watcher:
    """One user's socket, re-reading its own queue for the events of one change."""

    def __init__(self, conn: SharedConn):
        self.conn = conn

    def drain(self):
        while True:
            try:
                self.conn._q.get_nowait()
            except queue.Empty:
                return

    def await_change(self, t0: float, state: str, want_book: bool, timeout: float):
        got = {"session": None, "holding": None, "orders": None}
        deadline = time.perf_counter() + timeout
        while time.perf_counter() < deadline:
            try:
                ev = self.conn._q.get(timeout=max(0.01, deadline - time.perf_counter()))
            except queue.Empty:
                break
            now = time.perf_counter() - t0
            sessions = ev if isinstance(ev, list) else [ev]
            if got["session"] is None and any(isinstance(s, Session) and s.state == state for s in sessions):
                got["session"] = now
            elif isinstance(ev, Holding) and got["holding"] is None:
                got["holding"] = now
            elif isinstance(ev, OrdersUpdate) and got["orders"] is None:
                got["orders"] = now
            if got["session"] is not None and (not want_book or (got["holding"] is not None and got["orders"] is not None)):
                break
        return got


def change(http, root, auth, marketplace, action, watchers, state, want_book, timeout):
    for w in watchers:
        w.drain()
    t0 = time.perf_counter()
    r = http.patch(f"{root}/marketplaces/{marketplace}/{action}", headers=auth, timeout=300)
    request_s = time.perf_counter() - t0
    with ThreadPoolExecutor(max_workers=len(watchers)) as pool:
        results = list(pool.map(lambda w: w.await_change(t0, state, want_book, timeout), watchers))
    print(f"  {action:5} -> {state:6}: manager request {r.status_code} in {request_s:.2f}s")
    for key in ("session", "holding", "orders") if want_book else ("session",):
        vals = [g[key] for g in results if g[key] is not None]
        print(f"      {key:8}: {len(vals)}/{len(watchers)} received; p50 {fmt(pct(vals, 50))} "
              f"p90 {fmt(pct(vals, 90))} p99 {fmt(pct(vals, 99))} max {fmt(max(vals) if vals else None)}")
    return results


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--marketplace", type=int, required=True)
    p.add_argument("--users", type=int, default=50)
    p.add_argument("--credential", required=True, help="MANAGER credential of the marketplace's account")
    p.add_argument("--endpoint", default="http://localhost:8080/api")
    p.add_argument("--ready-timeout", type=float, default=60.0)
    p.add_argument("--timeout", type=float, default=120.0, help="seconds to wait for a change to reach everyone")
    args = p.parse_args(argv)

    mgr = Flexemarkets(credential=args.credential, endpoint=f"{args.endpoint}/marketplaces/{args.marketplace}")
    root = _root(mgr.endpoint_url)
    auth = {"Authorization": mgr._bearer_token, "Accept": "application/json"}
    users = mint_user_tokens(mgr, args.marketplace, args.users)
    print(f"  {len(users)} users; connecting ...", flush=True)

    ws = _ws_url(args.endpoint)
    watchers = [Watcher(SharedConn(ws, u["bearer"], args.marketplace, f"fanout-{i}")) for i, u in enumerate(users)]
    with ThreadPoolExecutor(max_workers=len(watchers)) as pool:
        list(pool.map(lambda w: w.conn.connect(), watchers))
        ready = list(pool.map(lambda w: w.conn.wait_for_state(args.ready_timeout)[0], watchers))
    print(f"  ready: {sum(1 for r in ready if r not in ('timeout', 'ERROR'))}/{len(watchers)}", flush=True)
    time.sleep(2)

    with httpx.Client() as http:
        change(http, root, auth, args.marketplace, "pause", watchers, "PAUSED", False, args.timeout)
        time.sleep(2)
        change(http, root, auth, args.marketplace, "open", watchers, "OPEN", True, args.timeout)
        time.sleep(2)
        change(http, root, auth, args.marketplace, "close", watchers, "CLOSED", False, args.timeout)
        time.sleep(2)
        change(http, root, auth, args.marketplace, "open", watchers, "OPEN", True, args.timeout)

    for w in watchers:
        w.conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
