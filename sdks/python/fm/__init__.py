"""Flexemarkets Python SDK."""

from .client import Flexemarkets
from .enums import OrderType, Side
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
from .market_view import GapEvent, MarketView, ReconnectEvent, Subscription
from .orderbook import OrderBook, OrderBooks
from .trades import MarketplaceTrades, Trades
from .types import (
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
    Token,
    Version,
)

__all__ = [
    "Flexemarkets",
    # side and order type
    "Side",
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
    "Token",
    "Version",
    # orderbook & trades
    "OrderBook",
    "OrderBooks",
    "Trades",
    "MarketplaceTrades",
    # market view
    "MarketView",
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
