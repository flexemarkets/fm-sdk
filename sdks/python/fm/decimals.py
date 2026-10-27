"""Exact conversion between the integers the exchange counts in and decimal text.

Every price, unit and cash amount is an ``int`` counting the smallest step of
its scale: with ``decimals`` of 2, ``5000`` is ``"50.00"``. This is the one
place the SDK multiplies or divides by a power of ten, and it does so in
integer arithmetic, never ``float``: a float turns 0.29 at two decimals into 28.
"""

from __future__ import annotations

import re

_GRAMMAR = re.compile(r"-?[0-9]+(?:\.[0-9]+)?")

_LONG_MIN = -(2 ** 63)
_LONG_MAX = 2 ** 63 - 1


class Decimals:
    """Format and parse amounts at a given number of decimal places.

    :meth:`parse` refuses rather than rounds. Text with a non-zero digit beyond
    ``decimals`` is not a value the exchange can hold, and quietly moving it to
    a neighbour is how two clients come to disagree about a price. Trailing
    zeros are not refused: ``"1.50"`` at one decimal is exactly ``15``.

    The grammar is the same in all three SDKs: an optional ``-``, one or more
    digits, and optionally a ``.`` followed by one or more digits. No ``+``, no
    exponent, no grouping, no surrounding space.
    """

    DEFAULT_PRICE_DECIMALS = 2
    """Decimals of a price when the market does not say: cents."""

    DEFAULT_UNIT_DECIMALS = 0
    """Decimals of a unit count when the market does not say: whole units."""

    DEFAULT_CASH_DECIMALS = 2
    """Decimals of cash when the marketplace does not say: cents."""

    def __init__(self) -> None:
        raise TypeError("Decimals is not instantiated; call its static methods")

    @staticmethod
    def format(value: int, decimals: int) -> str:
        """``value`` as decimal text with exactly ``decimals`` digits after the
        point, and no point when ``decimals`` is zero.

        ``format(5000, 2)`` is ``"50.00"``; ``format(-5, 2)`` is ``"-0.05"``.
        Raises ``TypeError`` for anything but an ``int`` -- a float has already
        lost the exactness this exists to keep -- and ``ValueError`` for
        negative ``decimals``.
        """
        _require_int(value, "value")
        _require_decimals(decimals)
        sign = "-" if value < 0 else ""
        digits = str(abs(value))
        if decimals == 0:
            return sign + digits
        digits = digits.rjust(decimals + 1, "0")
        return f"{sign}{digits[:-decimals]}.{digits[-decimals:]}"

    @staticmethod
    def parse(text: str, decimals: int) -> int:
        """Decimal text as the integer the exchange counts in, exactly.

        ``parse("50", 2)`` is ``5000``. Raises ``ValueError`` if ``text`` is not
        a decimal number, has a non-zero digit beyond ``decimals``, or does not
        fit a signed 64-bit integer, or if ``decimals`` is negative.
        """
        _require_decimals(decimals)
        if not isinstance(text, str) or not _GRAMMAR.fullmatch(text):
            raise ValueError(f"not a decimal number: {text!r}")
        negative = text.startswith("-")
        whole, _, fraction = text.lstrip("-").partition(".")
        if fraction[decimals:].strip("0"):
            raise ValueError(f"{text!r} has more than {decimals} decimal places")
        magnitude = int(whole + fraction[:decimals].ljust(decimals, "0"))
        value = -magnitude if negative else magnitude
        if not _LONG_MIN <= value <= _LONG_MAX:
            raise ValueError(f"{text!r} is out of range")
        return value


def _require_int(value: object, name: str) -> None:
    if isinstance(value, bool) or not isinstance(value, int):
        raise TypeError(f"{name} must be an int, got {type(value).__name__}")


def _require_decimals(decimals: object) -> None:
    _require_int(decimals, "decimals")
    if decimals < 0:  # type: ignore[operator]
        raise ValueError(f"decimals must not be negative: {decimals}")
