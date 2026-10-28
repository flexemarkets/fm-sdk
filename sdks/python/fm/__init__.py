"""Flexemarkets Python SDK."""

from .client import Flexemarkets
from .enums import OrderType, OrderSide
from .events import (NO_SEQ, OrdersUpdate, FrameUnreadable, StreamReconnected, StreamDropped,
                     OrderAccepted, OrderRejected)
from .order_stream import PendingOrder
from .page import Page
from .snapshot import Snapshot
from .decimals import Decimals
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
    OrderOutcomeUnknownError,
    OrderRefusedLocallyError,
    PersonHasMarketplaceDataError,
)
from .desk import GapEvent, Desk, DeskRecovery, Subscription
from .orderbook import Book
from .trades import Trade, Tape
from .types import (
    ConflictFailure,
    ManagerOtpBundle,
    Account,
    Allotment,
    Assets,
    CallIndicative,
    ClientConnection,
    Holding,
    Market,
    MarketCall,
    Marketplace,
    Order,
    ParticipantState,
    Person,
    Security,
    Session,
    TickGrid,
    Version,
    Token,
    Widget,
    WidgetPush,
    WidgetTarget,
    MarketplaceRobot,
    RobotManifest,
    RobotParameter,
    RobotParameterConfig,
    RobotParameterControl,
    RobotParameters,
)

__all__ = [
    "Flexemarkets",
    # amounts as decimal text, exactly
    "Decimals",
    # side and order type
    "OrderSide",
    "OrderType",
    # types
    "Account",
    "Allotment",
    "Assets",
    "CallIndicative",
    "ClientConnection",
    "Holding",
    "Market",
    "MarketCall",
    "Marketplace",
    "Order",
    "ParticipantState",
    "Person",
    "Security",
    "Session",
    "TickGrid",
    "Widget",
    "WidgetPush",
    "WidgetTarget",
    "MarketplaceRobot",
    "RobotManifest",
    "RobotParameter",
    "RobotParameterConfig",
    "RobotParameterControl",
    "RobotParameters",
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
    "DeskRecovery",
    # events
    "StreamDropped",
    "FrameUnreadable",
    "StreamReconnected",
    "OrdersUpdate",
    "OrderAccepted",
    "OrderRejected",
    # orders sent without waiting
    "PendingOrder",
    # snapshot
    "Snapshot",
    "NO_SEQ",
    # page
    "Page",
    # exceptions
    "FlexemarketsError",
    "AuthenticationError",
    "AuthorizationError",
    "InvalidArgumentError",
    "OrderRefusedLocallyError",
    "OrderOutcomeUnknownError",
    "AccountNameConflictError",
    "ApiError",
    "HttpError",
    "PersonHasMarketplaceDataError",
    "ConflictError",
    "ConnectionFailedError",
    "ConfigurationError",
]
