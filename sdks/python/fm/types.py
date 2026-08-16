"""Flexemarkets domain models."""

from __future__ import annotations

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
    created_date: str | None = None
    last_modified_date: str | None = None


@dataclass
class Account:
    id: int | None = None
    name: str | None = None
    description: str | None = None
    owner: Person | None = None
    approval: bool = False
    approval_description: str | None = None
    created_date: str | None = None
    last_modified_date: str | None = None


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

    def get_security(self, market_id: int) -> Security:
        for s in self.securities:
            if s.market_id == market_id:
                return s
        raise ValueError(f"Security for market ID {market_id} not found.")

    def units(self) -> list[int]:
        return [s.units for s in sorted(self.securities, key=lambda s: s.market_id)]


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
        return min(max(price - price % self.price_tick, self.price_minimum), self.price_maximum)


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
    open_date: str | None = None
    close_date: str | None = None

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
    type: str | None = None
    side: str | None = None
    units: int = 0
    price: int = 0
    owner_id: int | None = None
    marketplace_id: int = 0
    session_id: int = 0
    symbol: str | None = None
    market_id: int = 0
    owner_target: str | None = None
    client_description: str | None = None
    created_date: str | None = None
    last_modified_date: str | None = None

    TYPE_LIMIT = "LIMIT"
    TYPE_CANCEL = "CANCEL"
    SIDE_BUY = "BUY"
    SIDE_SELL = "SELL"


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
    established: str | None = None
    terminated: str | None = None
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

    expires_at: str | None = None
    otps: list[ManagerOtpEntry] = field(default_factory=list)


@dataclass
class Version:
    version: int = 0


@dataclass
class ApiRoot:
    links: dict[str, str] = field(default_factory=dict)

    def get_link(self, name: str) -> str | None:
        return self.links.get(name)


@dataclass
class ConflictFailure:
    status: str | None = None
    error: str | None = None
    message: str | None = None
    path: str | None = None
    suggested_name: str | None = None
