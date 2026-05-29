"""Flexemarkets Python SDK."""

from .client import Flexemarkets
from .events import EventListener, WsException, WsTransportError
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
from .market_view import MarketView, Subscription
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
    # market view (Phase 1 — skeleton; reconciliation + sharing in Phase 2)
    "MarketView",
    "Subscription",
    # events
    "EventListener",
    "WsTransportError",
    "WsException",
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
