"""Flexemarkets Python SDK."""

from .client import Flexemarkets
from .enums import OrderType, OrderSide
from .events import NO_SEQ, OrdersUpdate, FrameUnreadable, Reconnected, StreamDropped
from .snapshot import Snapshot
from .exceptions import (
    AccountNameConflictError,
    ApiError,
    AuthenticationError,
    AuthorizationError,
    ConfigurationError,
    ConflictError,
    ConnectionFailedError,
    FlexemarketsError,
    HttpError,
    InvalidArgumentError,
    PersonHasMarketplaceDataError,
)
from .desk import GapEvent, Desk, ReconnectEvent, Subscription
from .orderbook import Book
from .trades import Trade, Tape
from .types import (
    ConflictFailure,
    ManagerOtpBundle,
    Account,
    Allotment,
    Assets,
    ClientConnection,
    Holding,
    Market,
    Marketplace,
    Order,
    Person,
    Security,
    Session,
    TickGrid,
    Version,
    Token,
)

__all__ = [
    "Flexemarkets",
    # side and order type
    "OrderSide",
    "OrderType",
    # types
    "Account",
    "Allotment",
    "Assets",
    "ClientConnection",
    "Holding",
    "Market",
    "Marketplace",
    "Order",
    "Person",
    "Security",
    "Session",
    "TickGrid",
    "ConflictFailure",
    "ManagerOtpBundle",
    "Token",
    # orderbook & trades
    "Book",
    "Tape",
    "Trade",
    "Version",
    # market desk
    "Desk",
    "Subscription",
    "GapEvent",
    "ReconnectEvent",
    # events
    "StreamDropped",
    "FrameUnreadable",
    "Reconnected",
    "OrdersUpdate",
    # snapshot
    "Snapshot",
    "NO_SEQ",
    # exceptions
    "FlexemarketsError",
    "AuthenticationError",
    "AuthorizationError",
    "InvalidArgumentError",
    "AccountNameConflictError",
    "ApiError",
    "HttpError",
    "PersonHasMarketplaceDataError",
    "ConflictError",
    "ConnectionFailedError",
    "ConfigurationError",
]
