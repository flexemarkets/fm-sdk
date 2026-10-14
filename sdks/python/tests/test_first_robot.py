"""The first-robot example does what docs/FIRST-ROBOT.md says it does.

An example nobody runs rots like a quickstart nobody compiles. These drive its
one step, ``FirstRobot.tick``, with a desk that answers from the test.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from types import SimpleNamespace

from fm import InvalidArgumentError, Market, Session

_spec = importlib.util.spec_from_file_location("first_robot", Path(__file__).parent.parent / "first_robot.py")
first_robot = importlib.util.module_from_spec(_spec)
sys.modules["first_robot"] = first_robot   # a dataclass looks its module up while it is defined
_spec.loader.exec_module(first_robot)

A = Market(id=11, symbol="A", price_minimum=0, price_maximum=10_000, price_tick=5)


class Desk:
    def __init__(self, state: str = "OPEN", can_buy: bool = True, can_sell: bool = True,
                 bid: int = 400, ask: int = 600) -> None:
        self.state, self.bid, self.ask = state, bid, ask
        self.security = SimpleNamespace(can_buy=can_buy, can_sell=can_sell)
        self.sent: list[str] = []
        self.refuse: str | None = None
        self._next = 100

    def session(self):
        return Session(id=1, original=1, state=self.state)

    def holding(self):
        return SimpleNamespace(security=lambda market_id: self.security if market_id == A.id else None)

    def markets(self):
        return [A]

    def book(self, market_id):
        return SimpleNamespace(best_buy_price=lambda: self.bid, best_sell_price=lambda: self.ask)

    def submit_limit(self, market_id, side, units, price):
        if self.refuse:
            raise InvalidArgumentError(self.refuse)
        self.sent.append(f"{side} {units}@{price}")
        self._next += 1
        return SimpleNamespace(id=self._next, original=self._next)

    def submit_cancel(self, market_id, original_id):
        self.sent.append(f"CANCEL {original_id}")


def test_it_quotes_one_tick_inside_the_spread_on_both_sides():
    desk = Desk()
    first_robot.FirstRobot(desk).tick()
    assert desk.sent == ["BUY 1@405", "SELL 1@595"]


def test_it_sends_nothing_unless_the_session_is_open():
    for state in ("INIT", "PAUSED", "CLOSED"):
        desk = Desk(state=state)
        first_robot.FirstRobot(desk).tick()
        assert desk.sent == [], state


def test_it_quotes_only_the_sides_its_holding_grants():
    buy_only = Desk(can_sell=False)
    first_robot.FirstRobot(buy_only).tick()
    assert buy_only.sent == ["BUY 1@405"]

    sell_only = Desk(can_buy=False)
    first_robot.FirstRobot(sell_only).tick()
    assert sell_only.sent == ["SELL 1@595"]


def test_it_never_crosses_the_book():
    desk = Desk(bid=400, ask=405)          # one tick wide: nothing fits inside
    first_robot.FirstRobot(desk).tick()
    assert desk.sent == []


def test_a_moved_price_replaces_the_quote_and_an_unmoved_one_is_left_alone():
    desk = Desk()
    robot = first_robot.FirstRobot(desk)
    robot.tick()
    robot.tick()                            # nothing moved: nothing sent
    desk.bid = 420
    robot.tick()
    assert desk.sent == ["BUY 1@405", "SELL 1@595", "CANCEL 101", "BUY 1@425"]


def test_a_refused_order_is_reported_and_the_robot_carries_on():
    desk = Desk()
    desk.refuse = "ORDER_NOT_ALLOWED"
    robot = first_robot.FirstRobot(desk)
    robot.tick()
    assert robot.refusals == ["ORDER_NOT_ALLOWED", "ORDER_NOT_ALLOWED"]
    desk.refuse = None
    robot.tick()
    assert desk.sent == ["BUY 1@405", "SELL 1@595"]


def test_stopping_cancels_what_is_resting():
    desk = Desk()
    robot = first_robot.FirstRobot(desk)
    robot.tick()
    robot.cancel_all()
    assert desk.sent[-2:] == ["CANCEL 101", "CANCEL 102"]
    assert robot.resting == {}
