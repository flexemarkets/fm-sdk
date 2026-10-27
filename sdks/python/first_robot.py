#!/usr/bin/env python3
"""fm-first-robot -- the smallest robot that trades politely.

A worked example for docs/FIRST-ROBOT.md. Every few seconds, while the session
is OPEN, it quotes one unit one tick inside the spread on each side its
holding allows, and replaces a quote when the best price moves. It is not a
strategy worth copying; it is the shape a robot should have:

* it quotes only while the session is OPEN, so it sends nothing the server
  will refuse after the close;
* it quotes only the sides its holding grants, so it sends nothing the server
  will refuse as not allowed;
* it keeps one order per side, cancelling the old one before placing the new;
* a refused order is reported and the robot carries on; it does not stop;
* it cancels what it has resting when it stops, and stops when the session
  closes or you press Ctrl-C.

Run it against a marketplace you manage:

    python first_robot.py -C ~/.fm/credential -E 1234
"""

from __future__ import annotations

import argparse
import threading
from dataclasses import dataclass, field

from fm import Flexemarkets, InvalidArgumentError, Market, Session

#: How often the robot looks at the book, in seconds. Not faster: a robot
#: that acts every 0.2 s is most of a class's order traffic on its own.
INTERVAL_SECONDS = 2.0


def quotes(market: Market, best_buy: int, best_sell: int,
           can_buy: bool, can_sell: bool) -> dict[str, int]:
    """The prices to quote one unit at, by side: one tick inside the spread.

    A side is left out when the holding does not grant it, when the book is
    empty on that side, or when stepping inside would cross the other side.
    Prices are integers at the market's ``price_decimals``, as the server
    takes them; one tick is ``price_tick`` of them, whatever the scale.
    """
    tick = market.price_tick or 1
    wanted: dict[str, int] = {}
    if can_buy and best_buy >= 0:
        price = market.price_round(best_buy + tick)
        if best_sell < 0 or price < best_sell:
            wanted["BUY"] = price
    if can_sell and best_sell >= 0:
        price = market.price_round(best_sell - tick)
        if best_buy < 0 or price > best_buy:
            wanted["SELL"] = price
    return wanted


@dataclass
class FirstRobot:
    """One robot on one desk. :meth:`tick` is one look at the book."""

    desk: object
    resting: dict[tuple[int, str], tuple[int, int]] = field(default_factory=dict)
    refusals: list[str] = field(default_factory=list)

    def tick(self) -> None:
        session = self.desk.session()
        if session is None or session.state != Session.STATE_OPEN:
            return
        holding = self.desk.holding()
        for market in self.desk.markets():
            security = holding.security(market.id) if holding is not None else None
            if security is None:
                continue
            book = self.desk.book(market.id)
            if book is None:
                continue
            wanted = quotes(market, book.best_buy_price(), book.best_sell_price(),
                            security.can_buy, security.can_sell)
            for side in ("BUY", "SELL"):
                self._requote(market.id, side, wanted.get(side))

    def _requote(self, market_id: int, side: str, price: int | None) -> None:
        current = self.resting.get((market_id, side))
        if current is not None and current[1] == price:
            return                                   # already where we want it
        if current is not None:
            self._send(lambda: self.desk.submit_cancel(market_id, current[0]))
            del self.resting[(market_id, side)]
        if price is not None:
            order = self._send(lambda: self.desk.submit_limit(market_id, side, 1, price))
            if order is not None:
                self.resting[(market_id, side)] = (order.original or order.id, price)

    def _send(self, call):
        """One order, reported rather than raised when it is refused."""
        try:
            return call()
        except InvalidArgumentError as refused:
            # The server said no -- a closed session, a side not granted, a
            # price off the grid. Say so and carry on with the next tick.
            self.refusals.append(str(refused))
            print(f"refused: {refused}")
            return None

    def cancel_all(self) -> None:
        for (market_id, _side), (original, _price) in list(self.resting.items()):
            self._send(lambda: self.desk.submit_cancel(market_id, original))
        self.resting.clear()


def main() -> None:
    parser = argparse.ArgumentParser(prog="fm-first-robot", description=__doc__.splitlines()[0])
    parser.add_argument("-C", "--credential", default=None, help="credential file path or token")
    parser.add_argument("-E", "--endpoint", default=None, help="marketplace id, endpoint file or URL")
    args = parser.parse_args()

    with Flexemarkets.connect(args.credential, args.endpoint, "fm-first-robot") as fm:
        desk = fm.desk(fm.endpoint_marketplace_id)
        robot = FirstRobot(desk)
        closed = threading.Event()
        desk.on_session_change(lambda s: closed.set() if s.state == Session.STATE_CLOSED else None)
        try:
            while not closed.wait(INTERVAL_SECONDS):
                robot.tick()
            print("Session closed.")
        except KeyboardInterrupt:
            print("\nStopped.")
        finally:
            robot.cancel_all()
            desk.close()


if __name__ == "__main__":
    main()
