"""Side and order type, as enums rather than Strings with constants beside them.

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


class Side(StrEnum):
    """Which way an order goes."""

    BUY = "BUY"
    SELL = "SELL"

    def contra(self) -> "Side":
        """The other side: what a maker quotes against, and what a taker lifts."""
        return Side.SELL if self is Side.BUY else Side.BUY

    @classmethod
    def of(cls, value: str | None) -> "Side | None":
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

    There is no ``MARKET``. The server's type switch shares its default with
    ``LIMIT``, so a market order is a limit at the extreme legal price and
    ``submit_market`` sends ``LIMIT``; naming a constant the exchange does not
    have would suggest otherwise.
    """

    LIMIT = "LIMIT"
    CANCEL = "CANCEL"

    @classmethod
    def of(cls, value: str | None) -> "OrderType | None":
        """Lenient for the reason :meth:`Side.of` gives -- and it matters more
        here, because the server has emitted ``"MARKET"`` on at least one path.
        """
        if value is None:
            return None
        for order_type in cls:
            if order_type.value == value.strip().upper():
                return order_type
        return None
