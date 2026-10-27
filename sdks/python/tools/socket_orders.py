"""Orders over the STOMP socket, against REST: does it work, what does it cost, does it keep order.

fm-server has taken orders on ``SEND /app/marketplaces/{id}/orders`` since
August: an order names its submission, an accepted one comes back on the
ORDERS-UPDATE fan-out, a refused one as ORDER-REJECTED to the sender. No client
has ever used it -- fm-ui and every SDK send orders over REST -- so before an
SDK does, this asks four things of a local server:

  correct   one order each way; a refused one; a submission sent twice
  latency   N orders one at a time, socket against REST, ms per order
  order     a window of orders sent without waiting: how often the server's
            order ids come back out of the order they were sent in.
            fm-server keeps no per-session order on inbound frames
            (setPreserveReceiveOrder is off), so this is the number that says
            whether more than one order may be in flight
  load      C sockets at once, each sending one order at a time

Local servers only: the credentials it is given are commonly good on
production too. Each run builds its own marketplace, so it shares nothing with
anything else on the server.

  socket_orders.py --url http://localhost:8080 --account dev \\
      --manager ctm@dev:PASSWORD --trader ct1@dev:PASSWORD correct latency order
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
import warnings
from urllib.parse import urlparse

import websockets.sync.client as ws_sync

# websockets 14+ warns on connect() outside a with-block; fm-sdk's own stream
# connects the same way (a follow-up of its own), and the warning here would
# bury the results.
warnings.filterwarnings("ignore", category=DeprecationWarning, module=__name__)

LOOPBACK = {"localhost", "127.0.0.1", "::1"}
NULL = "\x00"


# -- HTTP ------------------------------------------------------------------------------------------

class Api:
    def __init__(self, url: str, account: str):
        self.url = url.rstrip("/")
        self.account = account

    def sign_in(self, email: str, password: str) -> str:
        req = urllib.request.Request(self.url + "/api/v1/tokens", method="POST",
                                     data=json.dumps({"username": f"{self.account}|{email}", "password": password}).encode(),
                                     headers={"Content-Type": "application/json", "Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.headers["Authorization"]

    def call(self, method: str, path: str, token: str, body=None, data: bytes | None = None,
             content_type: str = "application/json"):
        req = urllib.request.Request(self.url + path, method=method,
                                     data=data if data is not None else (None if body is None else json.dumps(body).encode()),
                                     headers={"Content-Type": content_type, "Accept": "application/json",
                                              "Authorization": token})
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                text = resp.read().decode()
                return resp.status, (json.loads(text) if text else None)
        except urllib.error.HTTPError as err:
            text = err.read().decode()
            try:
                return err.code, json.loads(text)
            except ValueError:
                return err.code, text


def build_marketplace(api: Api, manager_token: str, traders: list[str]) -> tuple[int, int]:
    """A marketplace of its own: one public market with room for thousands of non-crossing orders."""
    status, created = api.call("POST", "/api/v1/marketplaces", manager_token, {
        "name": f"socket-orders {uuid.uuid4().hex[:8]}", "description": "fm-sdk tools/socket_orders.py",
        "configuration": "",
        "markets": [{"symbol": "S", "name": "Socket", "description": "", "priceMinimum": 1, "priceMaximum": 1000,
                     "priceTick": 1, "unitMinimum": 1, "unitMaximum": 100, "unitTick": 1, "privateMarket": False}]})
    if status >= 300:
        sys.exit(f"could not create a marketplace: {status} {created}")
    mp, market = created["id"], created["markets"][0]["id"]
    key = f"S_{market}"
    header = ["email", "name", "cash__initial", key + "__initial", key + "__shorts", key + "__buy", key + "__sell"]
    rows = [f"{email},T{i},100000000,100000,0,true,true" for i, email in enumerate(traders)]
    csv_text = "\n".join(["# holdings -- begin", ",".join(header), *rows, "# holdings -- end"])
    boundary = uuid.uuid4().hex
    data = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"h.csv\"\r\n"
            f"Content-Type: text/csv\r\n\r\n{csv_text}\r\n--{boundary}--\r\n").encode()
    status, body = api.call("POST", f"/api/marketplaces/{mp}/holdings/uploads", manager_token, data=data,
                            content_type=f"multipart/form-data; boundary={boundary}")
    if status >= 300:
        sys.exit(f"could not upload holdings: {status} {body}")
    status, body = api.call("PATCH", f"/api/v1/marketplaces/{mp}/sessions/current", manager_token, {"state": "OPEN"})
    if status >= 300:
        sys.exit(f"could not open the session: {status} {body}")
    return mp, market


def close_marketplace(api: Api, manager_token: str, mp: int) -> None:
    api.call("PATCH", f"/api/v1/marketplaces/{mp}/sessions/current", manager_token, {"state": "CLOSED"})


# -- STOMP -----------------------------------------------------------------------------------------

def _frame(command: str, headers: dict, body: str = "") -> str:
    return command + "\n" + "".join(f"{k}:{v}\n" for k, v in headers.items()) + "\n" + body + NULL


def _parse(raw: str) -> tuple[str, dict, str]:
    head, _, body = raw.partition("\n\n")
    lines = head.split("\n")
    headers = {}
    for line in lines[1:]:
        k, _, v = line.partition(":")
        headers.setdefault(k, v)
    return lines[0], headers, body.rstrip(NULL)


class Answer:
    """What came back for one submission, and when."""

    def __init__(self, sent: float):
        self.sent = sent
        self.done = threading.Event()
        self.at = 0.0
        self.order: dict | None = None
        self.refused: dict | None = None
        self.by_submission_id = False

    def settle(self, order=None, refused=None, by_submission_id=False):
        if self.done.is_set():
            return
        self.at = time.perf_counter()
        self.order, self.refused, self.by_submission_id = order, refused, by_submission_id
        self.done.set()

    @property
    def ms(self) -> float:
        return (self.at - self.sent) * 1000


class Socket:
    """One trader's STOMP connection: sends orders and matches each to what comes back."""

    def __init__(self, api: Api, token: str, mp: int, market: int):
        self.mp, self.market = mp, market
        url = api.url.replace("https://", "wss://").replace("http://", "ws://") + "/api/events"
        self.ws = ws_sync.connect(url, additional_headers={"Authorization": token},
                                  subprotocols=["v12.stomp"], max_size=128 * 1024 * 1024)
        self.ws.send(_frame("CONNECT", {"accept-version": "1.2", "heart-beat": "0,0",
                                        "agent-description": "fm-sdk socket_orders", "marketplace-id": str(mp)}))
        command, _, _ = _parse(self.ws.recv(timeout=10))
        if command != "CONNECTED":
            raise RuntimeError(f"STOMP said {command}")
        for i, dest in enumerate((f"/user/queue/marketplaces/{mp}", f"/topic/marketplaces/{mp}",
                                  f"/app/v1/marketplaces/{mp}")):
            self.ws.send(_frame("SUBSCRIBE", {"id": f"sub-{i}", "destination": dest}))
        self.pending: dict[str, Answer] = {}
        self.lock = threading.Lock()
        self.send_lock = threading.Lock()
        self.unmatched_refusals = 0
        self.closed = False
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()
        time.sleep(0.3)  # the subscriptions registered before the first order

    def _read(self) -> None:
        while not self.closed:
            try:
                raw = self.ws.recv()
            except Exception:
                return
            if not raw or raw == "\n":
                continue
            command, headers, body = _parse(raw)
            if command != "MESSAGE":
                continue
            kind = headers.get("message-type")
            if kind == "ORDER-REJECTED":
                answer = self._take(headers.get("submission-id"))
                if answer is None:
                    self.unmatched_refusals += 1
                else:
                    answer.settle(refused=json.loads(body) if body else {}, by_submission_id=True)
            elif kind == "ORDERS-UPDATE":
                for order in json.loads(body) if body else []:
                    if order.get("type") != "LIMIT" or order.get("original") != order.get("id"):
                        continue
                    sid = order.get("submissionId")
                    answer = self._take(sid)
                    if answer is not None:
                        answer.settle(order=order, by_submission_id=True)
                        continue
                    answer = self._take(order.get("clientDescription"))
                    if answer is not None:
                        answer.settle(order=order)

    def _take(self, key):
        if not key:
            return None
        with self.lock:
            return self.pending.pop(key, None)

    def send(self, side: str, price: int, submission_id: str | None = None) -> tuple[str, Answer]:
        """Send one order and return its submission id and the answer it will settle."""
        sid = submission_id or uuid.uuid4().hex
        answer = Answer(time.perf_counter())
        with self.lock:
            self.pending[sid] = answer
        body = json.dumps({"marketplaceId": self.mp, "marketId": self.market, "type": "LIMIT", "side": side,
                           "units": 1, "price": price, "clientDescription": sid, "submissionId": sid})
        with self.send_lock:
            self.ws.send(_frame("SEND", {"destination": f"/app/marketplaces/{self.mp}/orders",
                                         "content-type": "application/json", "content-length": str(len(body.encode()))},
                                body))
        return sid, answer

    def close(self) -> None:
        self.closed = True
        try:
            self.ws.send(_frame("DISCONNECT", {}))
            self.ws.close()
        except Exception:
            pass


def rest_order(api: Api, token: str, mp: int, market: int, side: str, price: int) -> tuple[float, int, dict]:
    sid = uuid.uuid4().hex
    started = time.perf_counter()
    status, body = api.call("POST", f"/api/v1/marketplaces/{mp}/orders", token, {
        "marketplaceId": mp, "marketId": market, "type": "LIMIT", "side": side, "units": 1, "price": price,
        "clientDescription": sid, "submissionId": sid})
    return (time.perf_counter() - started) * 1000, status, body


# -- the experiments -------------------------------------------------------------------------------

def _non_crossing(i: int) -> tuple[str, int]:
    """Buys low, sells high: nothing trades, so every answer is the order itself."""
    return ("BUY", 1 + i % 100) if i % 2 == 0 else ("SELL", 900 + i % 100)


def _pct(xs: list[float], p: float) -> float:
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(p * len(xs)))] if xs else float("nan")


def correct(api, token, mp, market) -> dict:
    s = Socket(api, token, mp, market)
    try:
        _, ok = s.send("BUY", 10)
        ok.done.wait(10)
        _, refused = s.send("BUY", 100_000)  # above priceMaximum
        refused.done.wait(10)
        sid, first = s.send("BUY", 11)
        first.done.wait(10)
        _, again = s.send("BUY", 11, submission_id=sid)
        again.done.wait(5)
        status, mine = api.call("GET", f"/api/v1/marketplaces/{mp}/orders?state=ACTIVE", token)
        rows = mine if isinstance(mine, list) else (mine or {}).get("orders", [])
        copies = sum(1 for o in rows if o.get("clientDescription") == sid)
        return {
            "accepted": ok.done.is_set() and ok.order is not None,
            "accepted_matched_by_submissionId": ok.by_submission_id,
            "accepted_ms": round(ok.ms, 1) if ok.done.is_set() else None,
            "refused_as_ORDER-REJECTED": refused.done.is_set() and refused.refused is not None,
            "refusal_reason": (refused.refused or {}).get("error") if refused.done.is_set() else None,
            "resent_submission_answered": again.done.is_set(),
            "resent_submission_orders_on_book": copies,
            "unmatched_refusals": s.unmatched_refusals,
        }
    finally:
        s.close()


def latency(api, token, mp, market, n: int) -> dict:
    s = Socket(api, token, mp, market)
    sock, rest, lost = [], [], 0
    try:
        for i in range(n):
            side, price = _non_crossing(i)
            _, answer = s.send(side, price)
            if answer.done.wait(10) and answer.order is not None:
                sock.append(answer.ms)
            else:
                lost += 1
            ms, status, _ = rest_order(api, token, mp, market, side, price)
            if status < 300:
                rest.append(ms)
    finally:
        s.close()
    summary = lambda xs: {"n": len(xs), "p50": round(_pct(xs, .5), 1), "p95": round(_pct(xs, .95), 1),
                          "mean": round(statistics.fmean(xs), 1) if xs else None}
    return {"socket_ms": summary(sock), "rest_ms": summary(rest), "socket_unanswered": lost}


def order(api, token, mp, market, n: int, window: int) -> dict:
    """Send `window` orders back to back, wait for all, repeat; count id inversions against send order."""
    s = Socket(api, token, mp, market)
    inversions = answered = bursts = 0
    worst = 0
    try:
        for start in range(0, n, window):
            sent = [s.send(*_non_crossing(start + k)) for k in range(min(window, n - start))]
            ids = []
            for _, answer in sent:
                if answer.done.wait(10) and answer.order is not None:
                    ids.append(answer.order["id"])
            answered += len(ids)
            bursts += 1
            pairs = [(a, b) for i, a in enumerate(ids) for b in ids[i + 1:] if a > b]
            inversions += len(pairs)
            if pairs:
                worst = max(worst, max(a - b for a, b in pairs))
    finally:
        s.close()
    return {"sent": n, "window": window, "bursts": bursts, "answered": answered,
            "out_of_order_pairs": inversions,
            "largest_id_gap_inverted": worst}


def load(api, tokens, mp, market, sockets: int, seconds: float) -> dict:
    """`sockets` connections, each sending one order at a time for `seconds`."""
    results: list[list[float]] = [[] for _ in range(sockets)]
    unanswered = [0] * sockets
    stop = time.perf_counter() + seconds

    def run(k: int):
        s = Socket(api, tokens[k % len(tokens)], mp, market)
        i = 0
        try:
            while time.perf_counter() < stop:
                _, answer = s.send(*_non_crossing(k * 1_000_000 + i))
                if answer.done.wait(10) and answer.order is not None:
                    results[k].append(answer.ms)
                else:
                    unanswered[k] += 1
                i += 1
        finally:
            s.close()

    threads = [threading.Thread(target=run, args=(k,)) for k in range(sockets)]
    started = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.perf_counter() - started
    all_ms = [ms for r in results for ms in r]
    return {"sockets": sockets, "seconds": round(elapsed, 1), "orders": len(all_ms),
            "orders_per_s": round(len(all_ms) / elapsed, 1), "p50_ms": round(_pct(all_ms, .5), 1),
            "p95_ms": round(_pct(all_ms, .95), 1), "p99_ms": round(_pct(all_ms, .99), 1),
            "max_ms": round(max(all_ms), 1) if all_ms else None, "unanswered": sum(unanswered)}


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("experiments", nargs="+", choices=["correct", "latency", "order", "load"])
    p.add_argument("--url", default="http://localhost:8080")
    p.add_argument("--account", default="dev")
    p.add_argument("--manager", required=True,
                   help="email:password of a manager of the account, or 'credential' for ~/.fm/credential's")
    p.add_argument("--trader", action="append", required=True, help="email:password; repeat for more")
    p.add_argument("--n", type=int, default=200, help="orders for latency and order")
    p.add_argument("--window", type=int, default=10, help="orders in flight at once, for order")
    p.add_argument("--sockets", type=int, default=20, help="connections, for load")
    p.add_argument("--seconds", type=float, default=30, help="duration, for load")
    args = p.parse_args()

    if urlparse(args.url).hostname not in LOOPBACK:
        sys.exit(f"socket_orders.py drives a local server only, not {urlparse(args.url).hostname}")
    api = Api(args.url, args.account)
    if args.manager == "credential":
        cred = dict(line.strip().split("=", 1) for line in open(os.path.expanduser("~/.fm/credential")) if "=" in line)
        manager_email, manager_password = cred["email"], cred["password"]
    else:
        manager_email, manager_password = args.manager.split(":", 1)
    traders = [t.split(":", 1) for t in args.trader]
    manager_token = api.sign_in(manager_email, manager_password)
    mp, market = build_marketplace(api, manager_token, [email for email, _ in traders])
    tokens = [api.sign_in(email, password) for email, password in traders]
    print(json.dumps({"marketplace": mp, "market": market}))
    try:
        for name in args.experiments:
            if name == "correct":
                result = correct(api, tokens[0], mp, market)
            elif name == "latency":
                result = latency(api, tokens[0], mp, market, args.n)
            elif name == "order":
                result = order(api, tokens[0], mp, market, args.n, args.window)
            else:
                result = load(api, tokens, mp, market, args.sockets, args.seconds)
            print(json.dumps({name: result}))
            sys.stdout.flush()
    finally:
        close_marketplace(api, manager_token, mp)


if __name__ == "__main__":
    main()
