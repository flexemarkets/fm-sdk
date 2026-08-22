"""Flexemarkets domain models."""

from __future__ import annotations

from datetime import datetime

from .enums import OrderType, Side

from dataclasses import dataclass, field


@dataclass
class Person:
    id: int = 0
    account_id: int = 0
    first_name: str | None = None
    last_name: str | None = None
    email: str | None = None
    roles: list[str] = field(default_factory=list)
    account_owner: bool = False
    created_date: "datetime | None" = None
    last_modified_date: "datetime | None" = None


@dataclass
class Account:
    id: int | None = None
    name: str | None = None
    description: str | None = None
    owner: Person | None = None
    approval: bool = False
    approval_description: str | None = None
    created_date: "datetime | None" = None
    last_modified_date: "datetime | None" = None


@dataclass
class Token:
    request_url: str | None = None
    person: Person | None = None
    account: Account | None = None
    token: str | None = None


@dataclass
class Approval:
    account: Account | None = None
    description: str | None = None
    approve: bool | None = None


@dataclass
class Security:
    """A position in one market, and how far short the holder may go.

    ``short_units`` is the absolute floor: the position may not fall below
    ``-short_units``, so ``available_units == units + short_units``. It reaches
    the client under two names -- fm-server's Asset emits
    ``initialShortUnits`` for a live session, the allotments path emits
    ``shortUnits`` -- and both are accepted. Requests carry ``shortUnits``.
    """

    market_id: int = 0
    units: int = 0
    available_units: int = 0
    short_units: int = 0
    can_buy: bool = False
    can_sell: bool = False


@dataclass
class Holding:
    marketplace_id: int = 0
    session_id: int = 0
    allocation_id: int = 0
    owner_id: int = 0
    name: str | None = None
    cash: int = 0
    available_cash: int = 0
    securities: list[Security] = field(default_factory=list)

    def __post_init__(self) -> None:
        """Positions are never None and always in market order.

        They used to arrive in whatever order the server listed them, while
        ``units()`` sorted on the way out -- so two reads of the same holding
        disagreed about order, and two holdings of the same positions compared
        unequal. Normalising once here means the question cannot be got wrong.
        """
        self.securities = sorted(self.securities or [], key=lambda s: s.market_id)

    def security(self, market_id: int) -> Security | None:
        """The position in one market, or ``None`` if the holder has none.

        Was ``get_security``, which raised ValueError. Having no position in a
        market is ordinary -- it is what every holding looks like before the
        first allocation -- so asking is a question, not a mistake.
        """
        for s in self.securities:
            if s.market_id == market_id:
                return s
        return None

    def units(self) -> list[int]:
        return [s.units for s in self.securities]


def tick_round(value: int, minimum: int, maximum: int, tick: int) -> int:
    """A value moved down onto a bounded tick grid.

    The server applies this rule twice, to price and to units, spelling it the
    same way both times: a value must lie within its bounds and satisfy
    ``(value - minimum) % tick``. So the grid is anchored at the *minimum*, not
    at zero -- this used to subtract ``value % tick``, right whenever the floor
    happens to be a multiple of the tick and wrong for the rest in a way that
    yields a plausible number rather than an error.

    The ceiling is the highest legal tick rather than ``maximum`` itself:
    clamping to the maximum lands off the grid when the range is not a whole
    number of ticks, which is the case this exists for.

    A tick of zero marks a fixed dimension -- the bounds are equal and there is
    one legal value. It used to raise ZeroDivisionError.
    """
    if tick <= 0:
        return min(max(value, minimum), maximum)

    highest = minimum + ((maximum - minimum) // tick) * tick
    rounded = minimum + ((value - minimum) // tick) * tick
    return min(max(rounded, minimum), highest)


@dataclass
class TickGrid:
    """The legal values for one dimension of a market: a range, and a step.

    A market has two of these, and the server enforces both the same way -- a
    value must lie within the bounds and satisfy ``(value - minimum) % tick``.
    Naming the pair is what stops ``create_market`` taking six adjacent
    integers, where transposing the price tick and the unit minimum would post
    cleanly and produce a market nobody could trade in.

    A ``tick`` of zero marks a fixed dimension: the bounds are equal and there
    is one legal value.
    """

    minimum: int = 0
    maximum: int = 0
    tick: int = 0

    @staticmethod
    def units() -> "TickGrid":
        """The usual unit dimension: whole units, one to a hundred."""
        return TickGrid(1, 100, 1)

    def round(self, value: int) -> int:
        """*value* moved down onto this grid, clamped to it."""
        return tick_round(value, self.minimum, self.maximum, self.tick)


@dataclass
class Market:
    id: int = 0
    marketplace_id: int = 0
    name: str | None = None
    description: str | None = None
    symbol: str | None = None
    private_market: bool = False
    price_minimum: int = 0
    price_maximum: int = 0
    price_tick: int = 0
    unit_minimum: int = 0
    unit_maximum: int = 0
    unit_tick: int = 0

    def price_round(self, price: int) -> int:
        """*price* moved down to the nearest price this market will accept."""
        return tick_round(price, self.price_minimum, self.price_maximum, self.price_tick)

    def unit_round(self, units: int) -> int:
        """*units* moved down to the nearest size this market will accept.

        The counterpart to :meth:`price_round`, which did not exist even though
        the server checks units on exactly the same terms and refuses with
        "units is not on a tic".
        """
        return tick_round(units, self.unit_minimum, self.unit_maximum, self.unit_tick)


@dataclass
class Marketplace:
    id: int = 0
    name: str | None = None
    description: str | None = None
    markets: list[Market] = field(default_factory=list)


@dataclass
class Session:
    marketplace_id: int = 0
    allocation_id: int = 0
    id: int = 0
    original: int = 0
    state: str | None = None
    name: str | None = None
    description: str | None = None
    open_date: "datetime | None" = None
    close_date: "datetime | None" = None

    STATE_INIT = "INIT"
    STATE_OPEN = "OPEN"
    STATE_PAUSED = "PAUSED"
    STATE_CLOSED = "CLOSED"


@dataclass
class Order:
    id: int = 0
    original: int = 0
    supplier: int = 0
    consumer: int | None = None
    type: "OrderType | None" = None
    side: "Side | None" = None
    units: int = 0
    price: int = 0
    owner_id: int | None = None
    marketplace_id: int = 0
    session_id: int = 0
    symbol: str | None = None
    market_id: int = 0
    owner_target: str | None = None
    client_description: str | None = None
    created_date: "datetime | None" = None
    last_modified_date: "datetime | None" = None



@dataclass
class Allotment:
    id: int | None = None
    allocation_id: int | None = None
    marketplace_id: int | None = None
    owner_id: int | None = None
    name: str | None = None
    assets: Assets | None = None


@dataclass
class Assets:
    id: int | None = None
    name: str | None = None
    cash: int = 0
    securities: list[Security] = field(default_factory=list)


@dataclass
class ClientConnection:
    marketplace_id: int = 0
    connection_id: int = 0
    owner_id: int = 0
    established: "datetime | None" = None
    terminated: "datetime | None" = None
    description: str | None = None
    #: The session the connection was attached during, or None when the
    #: marketplace has never opened one. How a study works out who was present
    #: in a finished run -- which is not the same question as who is attached
    #: now, and was silently unanswerable before 0.0.11.
    session_id: int | None = None


@dataclass
class ManagerOtpEntry:
    user_id: int = 0
    email: str | None = None
    otp: str | None = None


@dataclass
class ManagerOtpBundle:
    """One-time passcodes a manager mints on behalf of their users.

    Credentials, and short-lived: ``expires_at`` is when the whole bundle
    stops working, not a per-entry deadline. This is how a classroom signs
    in without passwords being handed around, which is also why nothing
    here should be logged.
    """

    expires_at: "datetime | None" = None
    otps: list[ManagerOtpEntry] = field(default_factory=list)


@dataclass
class Version:
    version: int = 0


@dataclass
class ConflictFailure:
    status: str | None = None
    error: str | None = None
    message: str | None = None
    path: str | None = None
    suggested_name: str | None = None
