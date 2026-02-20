#!/usr/bin/env python3
"""fm-ticker — live order book and trade history display."""

import argparse
import queue
import sys

from fm import (
    Flexemarkets,
    Order,
    OrderBooks,
    MarketplaceTrades,
    Session,
    Holding,
    WsTransportError,
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


def display(books: OrderBooks, trades: MarketplaceTrades, session: Session | None, endpoint_url: str = "") -> None:
    state = session.state if session else "---"

    lines: list[str] = []
    lines.append(f"\033[2J\033[H")  # clear screen, cursor home
    lines.append(f"fm-ticker  {endpoint_url}")
    lines.append(f"{state:>59}")
    lines.append("")
    lines.append(f"  {'Symbol':>6}  {'Bid':>6}  {'Ask':>6}  {'Spread':>6}   Last trades")
    lines.append(f"  {'------':>6}  {'------':>6}  {'------':>6}  {'------':>6}   -----------")

    for book in sorted(books.collection(), key=lambda b: b.market_id):
        bid = book.best_buy_price()
        ask = book.best_sell_price()
        symbol = book.symbol or "?"
        recent = trades[book.market_id].most_recent_prices()
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

        books = OrderBooks(markets)
        market_trades = MarketplaceTrades(markets)

        q: queue.Queue[object] = queue.Queue(maxsize=1000)
        fm.listen(marketplace_id, q)

        endpoint_url = fm.endpoint_url
        session: Session | None = None
        display(books, market_trades, session, endpoint_url)

        try:
            while True:
                event = q.get()
                redraw = False

                match event:
                    case list() as items if items and isinstance(items[0], Order):
                        books.update(items)
                        market_trades.update(items)
                        redraw = True
                    case Session() as s:
                        session = s
                        redraw = True
                        if s.state == Session.STATE_CLOSED:
                            display(books, market_trades, session, endpoint_url)
                            print("Session closed.")
                            break
                    case Holding():
                        pass
                    case WsTransportError() as err:
                        print(f"\nConnection lost: {err.exception}", file=sys.stderr)
                        fm.reconnect()
                    case _:
                        pass

                if redraw:
                    display(books, market_trades, session, endpoint_url)

        except KeyboardInterrupt:
            print("\nStopped.")


if __name__ == "__main__":
    main()
