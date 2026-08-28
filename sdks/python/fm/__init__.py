"""Flexemarkets Python SDK."""

from .client import Flexemarkets
from .enums import OrderType, OrderSide
from .events import NO_SEQ, OrdersUpdate, StreamDropped
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
from .orderbook import MarketBook, MarketplaceBooks
from .trades import MarketplaceTrades, Trade, MarketTrades
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
    "Token",
    # orderbook & trades
    "MarketBook",
    "MarketplaceBooks",
    "MarketTrades",
    "MarketplaceTrades",
    "Trade",
    # market view
    "MarketView",
    "Subscription",
    "GapEvent",
    "ReconnectEvent",
    # events
    "StreamDropped",
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
