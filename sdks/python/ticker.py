#!/usr/bin/env python3
"""fm-ticker — live order book and trade history display."""

import argparse
import sys
import threading

from fm import (
    Flexemarkets,
    Desk,
    Session,
)

TRADE_DISPLAY_COUNT = 5


def _price(value: int) -> str:
    if value < 0:
        return "     -"
    return f"${value / 100:5.2f}"


def _spread(bid: int, ask: int) -> str:
    if bid < 0 or ask < 0:
        return "     -"
    return f"${(ask - bid) / 100:5.2f}"


def _trade_prices(prices: list[int], count: int) -> str:
    recent = prices[-count:] if len(prices) > count else prices
    return "  ".join(f"${p / 100:.2f}" for p in reversed(recent))


def display(desk: Desk, session: Session | None, endpoint_url: str = "") -> None:
    state = session.state if session else "---"

    lines: list[str] = []
    lines.append(f"\033[2J\033[H")  # clear screen, cursor home
    lines.append(f"fm-ticker  {endpoint_url}")
    lines.append(f"{state:>59}")
    lines.append("")
    lines.append(f"  {'Symbol':>6}  {'Bid':>6}  {'Ask':>6}  {'Spread':>6}   Last trades")
    lines.append(f"  {'------':>6}  {'------':>6}  {'------':>6}  {'------':>6}   -----------")

    for book in sorted(desk.books(), key=lambda b: b.market_id):
        bid = book.best_buy_price()
        ask = book.best_sell_price()
        symbol = book.symbol or "?"
        recent = desk.tape(book.market_id).most_recent_prices()
        lines.append(
            f"  {symbol:>6}  {_price(bid)}  {_price(ask)}  {_spread(bid, ask)}"
            f"   {_trade_prices(recent, TRADE_DISPLAY_COUNT)}"
        )

    lines.append("")
    sys.stdout.write("\n".join(lines))
    sys.stdout.flush()


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="fm-ticker",
        description="Live order book and trade history display",
    )
    parser.add_argument("-C", "--credential", default=None,
                        help="credential file path or token")
    parser.add_argument("-E", "--endpoint", default=None,
                        help="marketplace endpoint file path or URL")
    args = parser.parse_args()

    with Flexemarkets.connect(args.credential, args.endpoint, "fm-ticker") as fm:
        marketplace_id = fm.endpoint_marketplace_id
        markets = fm.markets(marketplace_id)
        markets.sort(key=lambda m: m.id)

        # A desk keeps the books and the tape for us: seeded from the REST
        # snapshot, kept current from the same delta stream, and reseeded on a
        # sequence gap. That is the whole reason this example no longer holds
        # aggregators of its own.
        desk = fm.desk(marketplace_id)

        endpoint_url = fm.endpoint_url
        state: dict[str, Session | None] = {"session": None}
        closed = threading.Event()
        dirty = threading.Event()
        dirty.set()

        def _session_changed(s: Session) -> None:
            state["session"] = s
            dirty.set()
            if s.state == Session.STATE_CLOSED:
                closed.set()

        # The desk dispatches on its own thread; these only flag that something
        # moved, so the screen is written from the main thread below.
        desk.on_session_change(_session_changed)
        for market in markets:
            desk.on_book_change(market.id, lambda _b: dirty.set())

        try:
            while not closed.is_set():
                if dirty.wait(timeout=0.1):
                    dirty.clear()
                    display(desk, state["session"], endpoint_url)
            display(desk, state["session"], endpoint_url)
            print("Session closed.")
        except KeyboardInterrupt:
            print("\nStopped.")
        finally:
            desk.close()


if __name__ == "__main__":
    main()
