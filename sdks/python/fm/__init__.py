"""Flexemarkets Python SDK."""

from .client import Flexemarkets
from .events import EventListener, NO_SEQ, OrdersUpdate, WsException, WsTransportError
from .snapshot import Snapshot
from .exceptions import (
    AccountNameConflictError,
    AuthenticationError,
    AuthorizationError,
    ConfigurationError,
    ConflictError,
    ConnectionFailedError,
    FlexemarketsError,
    InvalidArgumentError,
    PersonHasMarketplaceDataError,
)
from .market_view import MarketView, MarketViewHandle, Subscription
from .orderbook import OrderBook, OrderBooks
from .trades import MarketplaceTrades, Trades
from .types import (
    Account,
    Allotment,
    ApiRoot,
    Assets,
    ClientConnection,
    Holding,
    Market,
    Marketplace,
    Order,
    Person,
    Security,
    Session,
    Token,
    Version,
)

__all__ = [
    "Flexemarkets",
    # types
    "Account",
    "Allotment",
    "ApiRoot",
    "Assets",
    "ClientConnection",
    "Holding",
    "Market",
    "Marketplace",
    "Order",
    "Person",
    "Security",
    "Session",
    "Token",
    "Version",
    # orderbook & trades
    "OrderBook",
    "OrderBooks",
    "Trades",
    "MarketplaceTrades",
    # market view (Phase 2d-complete — REST snapshot seeding +
    # seq-filtered deltas + gap recovery + auto-reconnect + sharing)
    "MarketView",
    "MarketViewHandle",
    "Subscription",
    # events
    "EventListener",
    "WsTransportError",
    "WsException",
    "OrdersUpdate",
    # snapshot (Phase 2a)
    "Snapshot",
    "NO_SEQ",
    # exceptions
    "FlexemarketsError",
    "AuthenticationError",
    "AuthorizationError",
    "InvalidArgumentError",
    "AccountNameConflictError",
    "PersonHasMarketplaceDataError",
    "ConflictError",
    "ConnectionFailedError",
    "ConfigurationError",
]
