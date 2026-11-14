"""OrderSide and order type, as enums rather than Strings with constants beside them.

Both were plain strings with ``Order.SIDE_BUY`` and ``Order.TYPE_LIMIT`` next to
them, which is a convention rather than a rule: nothing stopped a caller writing
``"buy"``, ``"Buy"`` or ``"BYU"``. The first two worked because every comparison
in the SDK called ``.upper()``; the third reached the server and came back a 400.

``StrEnum`` rather than ``Enum`` deliberately. The members *are* strings, so they
serialise to JSON with no encoder of their own, compare equal to the wire
spelling, and a caller who was passing ``"BUY"`` keeps working. The type is
gained without the break that a plain ``Enum`` would have forced.
"""

from __future__ import annotations

from enum import StrEnum


class OrderSide(StrEnum):
    """Which way an order goes."""

    BUY = "BUY"
    SELL = "SELL"

    def contra(self) -> "OrderSide":
        """The other side: what a maker quotes against, and what a taker lifts."""
        return OrderSide.SELL if self is OrderSide.BUY else OrderSide.BUY

    @classmethod
    def of(cls, value: str | None) -> "OrderSide | None":
        """The side a response names, or ``None`` if it names none or one this
        version does not know.

        Deliberately lenient. Raising on an unrecognised value would fail an
        entire response -- an order list, a holdings snapshot -- over one field
        the caller may not read. A null side is recoverable and localised; a
        refused response is neither. A cancel legitimately carries no side.
        """
        if value is None:
            return None
        for side in cls:
            if side.value == value.strip().upper():
                return side
        return None


class OrderType(StrEnum):
    """What an order is: a bid or offer, or the withdrawal of one.

    There is no ``MARKET``. A market order is a ``LIMIT`` whose time-in-force
    is :attr:`TimeInForce.IOC` and whose price is a cap from the book, which
    is what ``submit_market`` sends; naming a constant the exchange does not
    have would suggest otherwise.
    """

    LIMIT = "LIMIT"
    CANCEL = "CANCEL"

    @classmethod
    def of(cls, value: str | None) -> "OrderType | None":
        """Lenient for the reason :meth:`OrderSide.of` gives -- and it matters more
        here, because the server has emitted ``"MARKET"`` on at least one path.
        """
        if value is None:
            return None
        for order_type in cls:
            if order_type.value == value.strip().upper():
                return order_type
        return None


class TimeInForce(StrEnum):
    """How long what a limit order does not trade at once may rest
    (fm-server 4.30, fm-server#1035).

    ``GTC``, good till cancelled: the rest rests, as every order did before.
    ``IOC``, immediate or cancel: whatever crosses trades and the exchange
    cancels the rest. ``FOK``, fill or kill: every unit trades at once, or the
    order is refused and nothing is written. A marketplace takes only the
    values its manager lists; without a list, ``GTC`` alone.
    """

    GTC = "GTC"
    IOC = "IOC"
    FOK = "FOK"

    @classmethod
    def of(cls, value: str | None) -> "TimeInForce | None":
        """The time-in-force a response names, or ``None`` for none -- which
        is good till cancelled -- or one this version does not know."""
        if value is None:
            return None
        for time_in_force in cls:
            if time_in_force.value == value.strip().upper():
                return time_in_force
        return None


class OrderRoute(StrEnum):
    """How :meth:`~fm.client.Flexemarkets.send_limit` and ``send_cancel``
    send a client's orders, chosen once, when it connects.

    ``SOCKET``, the default: each order goes out on the marketplace's order
    socket at once, as a STOMP SEND, up to 64 unanswered. ``REST``: orders go
    as ``POST .../orders/batch``, one request in flight; those sent while it is
    out wait, and go together as the next. For a caller that cannot hold a
    socket open. One route for the client's life, so its orders never take two
    routes and overtake each other on the way (docs/SUBMIT-RATE.md, section 5).
    """

    SOCKET = "SOCKET"
    REST = "REST"
