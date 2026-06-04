#!/usr/bin/env python3
"""Reproduce the fm-data#751 marketplace-endpoint stall under concurrent load.

Symptom (production, marketplace 1524): a single ``GET /api/marketplaces/{id}``
is slow, and a handful of them issued *at the same time* — e.g. 7 browsers
refreshing together — push the response time to ~20s and can wedge the server.
No robots, no trading: just the marketplace fetch.

This tool reproduces that with N *distinct* users (mirroring N browsers), not
one client hammering the endpoint. A manager (the marketplace owner) mints a
one-time password per user via ``POST /api/otp/manager``, each OTP is redeemed
for that user's token via ``GET /api/otp?otp=…``, and then every user, all at
once, does what a freshly loaded / refreshed browser does:

  * opens the marketplace WebSocket and listens   (``--ws``, on by default)
  * fetches ``GET /api/marketplaces/{id}``         (the suspect endpoint)

We time each marketplace fetch and the wall-clock span, and contrast it with a
single-user baseline so the concurrency cliff is obvious.

Usage:
  ./marketplace_load_repro.py --marketplace 1524 --users 7
  ./marketplace_load_repro.py --marketplace 1524 --users 7 --no-ws
  ./marketplace_load_repro.py --marketplace 1524 --users 7 --rounds 3

Defaults target a local server with the manager credential ``fain-premium``.
"""
from __future__ import annotations

import argparse
import statistics
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import httpx

from fm.client import Flexemarkets, _server

# Reuse the WS plumbing connect_stress.py already exercises.
from connect_stress import SharedConn, _ws_url  # noqa: E402


def _root(endpoint: str) -> str:
    return _server(endpoint)


def mint_user_tokens(mgr: Flexemarkets, marketplace_id: int, count: int):
    """As the manager, discover marketplace traders, mint an OTP per user, and
    redeem each for a per-user bearer token. Returns a list of dicts:
    {user_id, email, bearer}."""
    root = _root(mgr.endpoint_url)
    auth = {"Authorization": mgr._bearer_token, "Accept": "application/json",
            "Content-Type": "application/json"}

    mp = mgr._http.get(f"{root}/marketplaces/{marketplace_id}", headers=auth).json()
    traders = mp.get("traders") or []
    if len(traders) < count:
        print(f"  ! marketplace has only {len(traders)} traders; "
              f"requested {count}", file=sys.stderr)
    user_ids = [t["id"] for t in traders[:count]]
    if not user_ids:
        raise SystemExit("no traders found on marketplace — cannot mint OTPs")

    resp = mgr._http.post(f"{root}/otp/manager", headers=auth,
                          json={"userIds": user_ids})
    resp.raise_for_status()
    entries = resp.json()["otps"]

    users = []
    for e in entries:
        rr = mgr._http.get(f"{root}/otp", params={"otp": e["otp"]},
                           headers={"Accept": "application/json"})
        rr.raise_for_status()
        users.append({
            "user_id": e["userId"],
            "email": e["email"],
            "bearer": f"Bearer {rr.json()['token']}",
        })
    return users


def fetch_marketplace(root: str, bearer: str, marketplace_id: int, timeout: float):
    """One browser's marketplace fetch. Returns (status, seconds, bytes)."""
    t = time.perf_counter()
    with httpx.Client(timeout=timeout) as c:
        r = c.get(f"{root}/marketplaces/{marketplace_id}",
                  headers={"Authorization": bearer, "Accept": "application/json"})
    return r.status_code, time.perf_counter() - t, len(r.content)


def open_ws(ws_url: str, bearer: str, marketplace_id: int, label: str,
            ready_timeout: float):
    """Open a marketplace WS like a browser does on load; wait for first state.
    Returns (SharedConn, ready_seconds_or_None)."""
    conn = SharedConn(ws_url, bearer, marketplace_id, label)
    t = time.perf_counter()
    conn.connect()
    kind, _ = conn.wait_for_state(ready_timeout)
    ready = time.perf_counter() - t if kind not in ("timeout", "ERROR") else None
    return conn, ready


def summarize(label: str, times):
    ok = [t for t in times if t is not None]
    if not ok:
        print(f"  {label}: no successful fetches")
        return
    ok.sort()
    p90 = ok[min(len(ok) - 1, int(0.9 * len(ok)))]
    print(f"  {label}: n={len(ok)}  min {min(ok):.2f}s | "
          f"median {statistics.median(ok):.2f}s | p90 {p90:.2f}s | "
          f"max {max(ok):.2f}s")


def main(argv=None):
    p = argparse.ArgumentParser(
        description="Reproduce fm-data#751 concurrent marketplace-fetch stall.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--marketplace", type=int, required=True,
                   help="marketplace id to load (e.g. 1524)")
    p.add_argument("--users", type=int, default=7,
                   help="number of distinct users / simulated browsers (default 7)")
    p.add_argument("--credential", default="/home/jan/.fm/credential.fain-premium",
                   help="MANAGER credential used to mint OTPs (owner of the marketplace)")
    p.add_argument("--endpoint", default="http://localhost:8080/api",
                   help="API endpoint (default local server)")
    p.add_argument("--rounds", type=int, default=1,
                   help="simultaneous-refresh rounds to run (default 1)")
    p.add_argument("--ws", dest="ws", action="store_true", default=True,
                   help="open the marketplace WebSocket per user (default on)")
    p.add_argument("--no-ws", dest="ws", action="store_false",
                   help="REST-only: skip WebSockets, isolate the marketplace fetch")
    p.add_argument("--timeout", type=float, default=120.0,
                   help="per-request HTTP timeout seconds (default 120)")
    p.add_argument("--ready-timeout", type=float, default=30.0,
                   help="seconds to wait for first WS state per user (default 30)")
    args = p.parse_args(argv)

    print(f"  authenticating manager from {args.credential} ...", flush=True)
    mgr = Flexemarkets.connect(
        credential=args.credential, endpoint=args.endpoint,
        client_description="repro751 manager",
    )
    if not mgr.is_manager():
        print(f"  ! manager credential lacks ROLE_MANAGER (roles={mgr.user.roles}); "
              f"OTP minting will fail", file=sys.stderr)
    root = _root(mgr.endpoint_url)
    ws_url = _ws_url(mgr.endpoint_url)

    print(f"  minting OTPs + tokens for {args.users} users on "
          f"marketplace {args.marketplace} ...", flush=True)
    users = mint_user_tokens(mgr, args.marketplace, args.users)
    print(f"  ready: {len(users)} distinct users | ws={'on' if args.ws else 'off'} | "
          f"endpoint={root}\n")

    # --- baseline: one user, alone ---------------------------------------
    status, secs, nbytes = fetch_marketplace(root, users[0]["bearer"],
                                             args.marketplace, args.timeout)
    print(f"  baseline (1 user alone): {status} in {secs:.2f}s  ({nbytes} bytes)\n")

    # --- concurrent refresh rounds ---------------------------------------
    for rnd in range(1, args.rounds + 1):
        print(f"  === round {rnd}/{args.rounds}: {len(users)} users refresh "
              f"simultaneously ===")
        ws_conns = []

        def one_browser(u):
            ready = None
            if args.ws:
                conn, ready = open_ws(ws_url, u["bearer"], args.marketplace,
                                      f"repro751 {u['email']}", args.ready_timeout)
                ws_conns.append(conn)
            status, secs, nbytes = fetch_marketplace(
                root, u["bearer"], args.marketplace, args.timeout)
            return u, status, secs, nbytes, ready

        t0 = time.perf_counter()
        rows = []
        with ThreadPoolExecutor(max_workers=len(users)) as ex:
            futs = [ex.submit(one_browser, u) for u in users]
            for f in as_completed(futs):
                rows.append(f.result())
        wall = time.perf_counter() - t0

        rows.sort(key=lambda r: r[0]["email"])
        print(f"  {'user':<14}{'status':>7}{'fetch':>9}{'ws_ready':>10}")
        for u, status, secs, nbytes, ready in rows:
            rds = f"{ready:.2f}s" if ready is not None else ("-" if not args.ws else "TIMEOUT")
            print(f"  {u['email']:<14}{status:>7}{secs:>8.2f}s{rds:>10}")
        print()
        summarize("fetch latency", [r[2] for r in rows])
        ok = sum(1 for r in rows if r[1] == 200)
        print(f"  ok          : {ok}/{len(rows)}")
        print(f"  wall span   : {wall:.2f}s\n")

        for conn in ws_conns:
            try:
                conn.close()
            except Exception:
                pass

    mgr._http.close()


if __name__ == "__main__":
    main()
