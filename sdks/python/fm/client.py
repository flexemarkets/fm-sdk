"""Flexemarkets API client."""

from __future__ import annotations

import importlib.metadata
import json
import logging
import os
import queue
import re
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote
from collections.abc import Callable
from typing import TYPE_CHECKING, Any, Optional, TypeVar

from .decimals import Decimals
from .page import Page
from .snapshot import NO_SEQ, Snapshot
from .timestamps import parse as _timestamp

if TYPE_CHECKING:
    from .events import EventListener
    from .desk import Desk
    from .order_stream import OrderStream, PendingOrder


@dataclass
class _SharedView:
    """Refcount registry entry — see ``Flexemarkets.desk``."""

    desk: "Desk"
    ref_count: int = 0


import httpx

from .exceptions import (
    AccountNameConflictError,
    AuthenticationError,
    AuthorizationError,
    ConfigurationError,
    HttpError,
    ConflictError,
    ApiError,
    ConnectionFailedError,
    FlexemarketsError,
    InvalidArgumentError,
    PersonHasMarketplaceDataError,
)
from .enums import OrderType, OrderSide
from .types import (
    Account,
    Allotment,
    Approval,
    CallIndicative,
    CurrentSession,
    ClientConnection,
    Holding,
    ManagerOtpBundle,
    ManagerOtpEntry,
    Market,
    MarketCall,
    Marketplace,
    Order,
    ParticipantState,
    Person,
    Security,
    Session,
    TickGrid,
    Token,
    Widget,
    MarketplaceRobot,
    RobotManifest,
    RobotParameter,
    RobotParameterConfig,
    RobotParameterControl,
    RobotParameters,
    WidgetPush,
)

_VERSION_FILE = Path(__file__).resolve().parent.parent.parent.parent / "VERSION"


def _read_version(version_file: Path = _VERSION_FILE, distribution: str = "fm-sdk") -> str:
    """This SDK's version, for the User-Agent fm-server keys its metrics by.

    The repository's VERSION file first, which is where a checkout and a
    vendored copy keep it. An installed wheel has no such file -- it sits in
    site-packages, and four levels up is somebody else's directory -- so this
    used to report ``0.0.0`` for every pip install. hatch stamps the
    distribution's metadata from VERSION at build time, so that is the answer
    there. The parameters exist so a test can stand up the installed case.
    """
    try:
        return version_file.read_text().strip()
    except FileNotFoundError:
        pass
    try:
        return importlib.metadata.version(distribution)
    except importlib.metadata.PackageNotFoundError:
        return "0.0.0"

_FM_NETWORK_CLIENT = f"fm-sdk-python/{_read_version()}"
_DEFAULT_ENDPOINT = "https://api.flexemarkets.com"

# Where a credential carries a refresh token: a ``refresh-token=`` line in a
# credential file, or the credential ``refresh:<token>``.
_REFRESH_TOKEN_PROPERTY = "refresh-token"
_REFRESH_TOKEN_PREFIX = "refresh:"

log = logging.getLogger(__name__)

_BCRYPT_RE = re.compile(r"^\$2[abxy]?\$\d{2}\$[./A-Za-z0-9]{53}$")
_JWT_RE = re.compile(r"^[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+$")
# The before in a Link header's rel="next" target: where a page's older rows start.
_NEXT_LINK = re.compile(r'<[^>]*[?&]before=(\d+)[^>]*>\s*;\s*rel="?next"?')

T = TypeVar("T")


# ---------------------------------------------------------------------------
# JSON ↔ dataclass helpers
# ---------------------------------------------------------------------------

def _parse_person(data: dict[str, Any] | None) -> Person | None:
    if data is None:
        return None
    return Person(
        id=data.get("id", 0),
        account_id=data.get("accountId", 0),
        first_name=data.get("firstName"),
        last_name=data.get("lastName"),
        email=data.get("email"),
        roles=data.get("roles") or [],
        account_owner=data.get("accountOwner", False),
        created_date=_timestamp(data.get("createdDate")),
        last_modified_date=_timestamp(data.get("lastModifiedDate")),
        last_signed_in=_timestamp(data.get("lastSignedIn")),
        last_sign_in_method=data.get("lastSignInMethod"),
    )


def _parse_account(data: dict[str, Any] | None) -> Account | None:
    if data is None:
        return None
    return Account(
        id=data.get("id"),
        name=data.get("name"),
        description=data.get("description"),
        owner=_parse_person(data.get("owner")),
        approval=data.get("approval"),
        approval_description=data.get("approvalDescription"),
        created_date=_timestamp(data.get("createdDate")),
        last_modified_date=_timestamp(data.get("lastModifiedDate")),
    )


def _parse_token(data: dict[str, Any]) -> Token:
    return Token(
        request_url=data.get("requestUrl"),
        person=_parse_person(data.get("person")),
        account=_parse_account(data.get("account")),
        token=data.get("token"),
    )


def _parse_security(data: dict[str, Any]) -> Security:
    return Security(
        market_id=data.get("marketId", 0),
        units=data.get("units", 0),
        available_units=data.get("availableUnits", 0),
        # Either spelling, depending on which response produced the holding.
        short_units=data.get("shortUnits", data.get("initialShortUnits", 0)) or 0,
        can_buy=data.get("canBuy", False),
        can_sell=data.get("canSell", False),
        initial_units=data.get("initialUnits"),
    )


def _parse_market(data: dict[str, Any]) -> Market:
    return Market(
        id=data.get("id", 0),
        marketplace_id=data.get("marketplaceId", 0),
        name=data.get("name"),
        description=data.get("description"),
        symbol=data.get("symbol"),
        private_market=data.get("privateMarket", False),
        price_minimum=data.get("priceMinimum", 0),
        price_maximum=data.get("priceMaximum", 0),
        price_tick=data.get("priceTick", 0),
        unit_minimum=data.get("unitMinimum", 0),
        unit_maximum=data.get("unitMaximum", 0),
        unit_tick=data.get("unitTick", 0),
        mechanism=data.get("mechanism") or Market.MECHANISM_CONTINUOUS,
        call_interval_seconds=data.get("callIntervalSeconds"),
        call_carry=data.get("callCarry") is not False,
        price_decimals=_or_default(data.get("priceDecimals"), Decimals.DEFAULT_PRICE_DECIMALS),
        unit_decimals=_or_default(data.get("unitDecimals"), Decimals.DEFAULT_UNIT_DECIMALS),
        call_book=data.get("callBook") or Market.CALL_BOOK_SEALED,
    )


def _or_default(value: Any, default: int) -> int:
    """*value*, or *default* when the server left it out or sent null.

    Not ``value or default``: zero decimals is a scale, not an absence, and
    reading it as the default shows whole dollars as cents.
    """
    return default if value is None else value


def _parse_current_session(data: dict[str, Any]) -> CurrentSession:
    return CurrentSession(
        marketplace_id=data.get("marketplaceId", 0),
        session_id=data.get("sessionId", 0),
        state=data.get("state", ""),
    )


def _parse_call_indicative(data: dict[str, Any]) -> CallIndicative:
    return CallIndicative(
        marketplace_id=data.get("marketplaceId", 0),
        market_id=data.get("marketId", 0),
        session_id=data.get("sessionId", 0),
        price=data.get("price"),
        volume=data.get("volume", 0),
        seq=data.get("seq", 0),
        at=_timestamp(data.get("at")),
    )


def _parse_market_call(data: dict[str, Any]) -> MarketCall:
    return MarketCall(
        id=data.get("id", 0),
        marketplace_id=data.get("marketplaceId", 0),
        market_id=data.get("marketId", 0),
        session_id=data.get("sessionId", 0),
        called_date=_timestamp(data.get("calledDate")),
        trigger=data.get("trigger"),
        price=data.get("price"),
        volume=data.get("volume", 0),
        demand=data.get("demand", 0),
        supply=data.get("supply", 0),
        buy_orders=data.get("buyOrders", 0),
        sell_orders=data.get("sellOrders", 0),
        carry=data.get("carry", True),
    )


def _parse_marketplace(data: dict[str, Any]) -> Marketplace:
    return Marketplace(
        id=data.get("id", 0),
        name=data.get("name"),
        description=data.get("description"),
        markets=[_parse_market(m) for m in (data.get("markets") or [])],
        cash_decimals=_or_default(data.get("cashDecimals"), Decimals.DEFAULT_CASH_DECIMALS),
    )


def _parse_session(data: dict[str, Any]) -> Session:
    return Session(
        marketplace_id=data.get("marketplaceId", 0),
        allocation_id=data.get("allocationId", 0),
        id=data.get("id", 0),
        original=data.get("original", 0),
        state=data.get("state"),
        name=data.get("name"),
        description=data.get("description"),
        open_date=_timestamp(data.get("openDate")),
        close_date=_timestamp(data.get("closeDate")),
        created_date=_timestamp(data.get("createdDate")),
        last_modified_date=_timestamp(data.get("lastModifiedDate")),
    )


def _parse_order(data: dict[str, Any]) -> Order:
    return Order(
        id=data.get("id", 0),
        original=data.get("original", 0),
        supplier=data.get("supplier", 0),
        consumer=data.get("consumer"),
        type=OrderType.of(data.get("type")),
        side=OrderSide.of(data.get("side")),
        units=data.get("units", 0),
        price=data.get("price", 0),
        owner_id=data.get("ownerId"),
        marketplace_id=data.get("marketplaceId", 0),
        session_id=data.get("sessionId", 0),
        symbol=data.get("symbol"),
        market_id=data.get("marketId", 0),
        owner_target=data.get("ownerTarget"),
        client_description=data.get("clientDescription"),
        owner_target_id=data.get("ownerTargetId"),
        created_date=_timestamp(data.get("createdDate")),
        last_modified_date=_timestamp(data.get("lastModifiedDate")),
        mine=data.get("mine"),
        original_session_id=data.get("originalSessionId"),
        submission_id=data.get("submissionId"),
    )


def _parse_holding(data: dict[str, Any]) -> Holding:
    securities_raw = data.get("securities") or data.get("assets") or []
    return Holding(
        marketplace_id=data.get("marketplaceId", 0),
        session_id=data.get("sessionId", 0),
        allocation_id=data.get("allocationId", 0),
        owner_id=data.get("ownerId", 0),
        name=data.get("name"),
        cash=data.get("cash", 0),
        available_cash=data.get("availableCash", 0),
        securities=[_parse_security(s) for s in securities_raw],
        initial_cash=data.get("initialCash"),
    )


def _parse_allotment(data: dict[str, Any]) -> Allotment:
    assets_raw = data.get("assets") or data.get("capital")
    assets = None
    if assets_raw and isinstance(assets_raw, dict):
        from .types import Assets
        secs_raw = assets_raw.get("securities") or assets_raw.get("grants") or []
        assets = Assets(
            id=assets_raw.get("id"),
            name=assets_raw.get("name"),
            cash=assets_raw.get("cash", 0),
            securities=[_parse_security(s) for s in secs_raw],
        )
    return Allotment(
        id=data.get("id"),
        allocation_id=data.get("allocationId"),
        marketplace_id=data.get("marketplaceId"),
        owner_id=data.get("ownerId"),
        name=data.get("name"),
        assets=assets,
    )


def _parse_participant_state(data: dict[str, Any]) -> ParticipantState:
    return ParticipantState(
        marketplace_id=data.get("marketplaceId"),
        allocation_id=data.get("allocationId"),
        owner_id=data.get("ownerId"),
        owner_email=data.get("ownerEmail"),
        fields=dict(data.get("fields") or {}),
    )


def _number(value: Any) -> float | None:
    return None if value is None else float(value)


def _parse_robot_parameter(data: dict[str, Any]) -> RobotParameter:
    return RobotParameter(
        name=data.get("name") or "",
        label=data.get("label"),
        description=data.get("description"),
        type=data.get("type"),
        value_type=data.get("valueType"),
        # The robots write "required": null for a parameter that is not.
        required=bool(data.get("required")),
        default_value=data.get("default"),
        unit=data.get("unit"),
        min=_number(data.get("min")),
        max=_number(data.get("max")),
    )


def _parse_robot_parameter_config(data: dict[str, Any]) -> RobotParameterConfig:
    control = data.get("control")
    return RobotParameterConfig(
        marketplace_id=data.get("marketplaceId"),
        robot_id=data.get("robotId") or "",
        param_name=data.get("paramName") or "",
        value=data.get("value") or "",
        control=None if control is None else RobotParameterControl(
            min=float(control["min"]), max=float(control["max"]), step=float(control["step"])),
        exposed=bool(data.get("exposed")),
    )


def _parse_marketplace_robot(data: dict[str, Any]) -> MarketplaceRobot:
    manifest = data.get("manifest") or {}
    groups = manifest.get("parameters") or {}
    return MarketplaceRobot(
        manifest=RobotManifest(
            id=manifest.get("id") or "",
            version=manifest.get("version"),
            name=manifest.get("name"),
            description=manifest.get("description"),
            parameters=RobotParameters(**{
                group: [_parse_robot_parameter(p) for p in groups.get(group) or []]
                for group in ("manager", "user", "platform")}),
        ),
        enabled=bool(data.get("enabled")),
        configuration=[_parse_robot_parameter_config(c) for c in data.get("configuration") or []],
    )


def _parse_widget(data: dict[str, Any]) -> Widget:
    return Widget(
        id=data.get("id"),
        marketplace_id=data.get("marketplaceId"),
        scope=data.get("scope"),
        user_id=data.get("userId"),
        key=data.get("key"),
        title=data.get("title"),
        emphasis=data.get("emphasis"),
        ttl_seconds=data.get("ttlSeconds"),
        content=dict(data.get("content") or {}),
        created_date=_timestamp(data.get("createdDate")),
        last_modified_date=_timestamp(data.get("lastModifiedDate")),
    )


def _widget_push_json(push: WidgetPush) -> dict[str, Any]:
    """The wire form of a push: camelCase, the target nested, nothing null.

    Absent rather than null so the body reads as the server documents it -- a
    marketplace target is ``{"scope": "MARKETPLACE"}``, with no userId.
    """
    body: dict[str, Any] = {"key": push.key, "content": push.content}
    if push.target is not None:
        target: dict[str, Any] = {"scope": push.target.scope}
        if push.target.user_id is not None:
            target["userId"] = push.target.user_id
        body["target"] = target
    for name, value in (("title", push.title), ("emphasis", push.emphasis),
                        ("ttlSeconds", push.ttl_seconds)):
        if value is not None:
            body[name] = value
    return body


def _parse_connection(data: dict[str, Any]) -> ClientConnection:
    return ClientConnection(
        marketplace_id=data.get("marketplaceId", 0),
        connection_id=data.get("id", data.get("connectionId", 0)),
        owner_id=data.get("ownerId", 0),
        established=_timestamp(data.get("established")),
        terminated=_timestamp(data.get("terminated")),
        description=data.get("description"),
        session_id=data.get("sessionId"),
    )


def _orders_of(body: Any) -> list[dict[str, Any]]:
    """The orders in a snapshot answer, which is a bare JSON array or a failure.

    Only the array is read because the V1 route never sent anything else:
    ``GET /api/v1/marketplaces/{id}/orders`` has always answered a bare array,
    and 0.4 requires fm-server 4.6.2. The HAL envelopes earlier SDKs tolerated
    belonged to the routes 0.4 no longer calls.

    Anything else raises rather than reading as empty. An empty snapshot is the
    failure that hid twice before: ``Desk`` seeded an empty book, filled it from
    live deltas, and looked plausible.

    :raises ApiError: when the answer is not a JSON array
    """
    if not isinstance(body, list):
        kind = {type(None): "null", dict: "object", str: "string", bool: "boolean",
                int: "number", float: "number"}.get(type(body), type(body).__name__)
        raise ApiError(f"The orders snapshot answer was not a list of orders: got {kind}")
    return body


def _marketable_limit(market: Market, side: str) -> int:
    """The most aggressive price this market will accept on *side*.

    Ticks are anchored at ``price_minimum``, not at zero -- the server tests
    ``(price - price_minimum) % price_tick`` -- so the top of the range is only
    legal when the range is a whole number of ticks. The highest legal price is
    the last tick at or below ``price_maximum``. A tick of zero marks a fixed
    dimension, where the two bounds are equal and there is one legal price.

    The side must be named. This was ``is not OrderSide.BUY``, the complement
    of buy rather than a test for sell, so a ``None`` side priced the order at
    the bottom of the range -- the most aggressive *sell* this market accepts.
    ``submit_market`` is public API and takes the side straight from its
    caller, so that turned a missing argument into a real order crossing the
    wrong side of the book.
    """
    resolved = OrderSide.of(side)
    if resolved is None:
        raise ValueError(f"A market order must name its side; got: {side!r}")

    if resolved is not OrderSide.BUY or market.price_tick <= 0:
        return market.price_minimum

    span = market.price_maximum - market.price_minimum
    return market.price_minimum + (span // market.price_tick) * market.price_tick


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------

def _v1(endpoint: str, path: str) -> str:
    """A V1 route, addressed from the server.

    0.4 reads no API root: every route is knowable from the server and the
    resource ids, so nothing is fetched before the first real call and no
    link the server stops advertising can break one.
    """
    return f"{_server(endpoint)}/v1{path}"


def _marketplace(endpoint: str, marketplace_id: int) -> str:
    """``/api/v1/marketplaces/{id}``, which most routes hang off."""
    return _v1(endpoint, f"/marketplaces/{marketplace_id}")


def _segment(value: str) -> str:
    """One path segment, escaped so a key cannot reach another route."""
    return quote(value, safe="")


# The most traded legs fm-server answers in one read. The route trades() used
# to read had no limit; asking for the ceiling keeps as much of that as the
# server allows.
_MAX_TRADED_LEGS = 5000


# ---------------------------------------------------------------------------
# Credential / configuration helpers
# ---------------------------------------------------------------------------

def _is_valid_token(value: str) -> bool:
    return bool(_BCRYPT_RE.match(value) or _JWT_RE.match(value))


def _server(endpoint: str) -> str:
    # Locate "/api" in the path, not in the scheme/host. A host like
    # "https://api.flexemarkets.com" otherwise matches at the "//api" of the
    # host and truncates the base URL to "https://api" (unresolvable). Skip
    # past the scheme + host before searching for the "/api" path segment.
    scheme = endpoint.find("://")
    path_start = endpoint.find("/", scheme + 3) if scheme >= 0 else 0
    idx = endpoint.find("/api", path_start) if path_start >= 0 else -1

    if idx >= 0:
        return endpoint[: idx + 4]

    # An endpoint naming only a server gets the API root appended rather than
    # being handed back as it stands. Returned unchanged, every URL built from
    # it was a segment short: sign-in POSTed to <host>/tokens, which the server
    # answers 404 "No static resource tokens". _DEFAULT_ENDPOINT is exactly
    # that shape -- it has to be, since _marketplace_endpoint appends
    # "/api/marketplaces/<id>" to it -- so a client with nothing configured
    # could not sign in at all.
    return endpoint + "api" if endpoint.endswith("/") else endpoint + "/api"


def _resource_id(endpoint: str) -> int:
    return int(endpoint.rstrip("/").rsplit("/", 1)[-1])


def _marketplace_endpoint(marketplace_id: str) -> str:
    """A bare marketplace id resolves to that marketplace on the default host.

    FM_URL overrides the host, which is what makes the id form usable against a
    development server. Java has honoured it since the id form existed; this
    did not, so ``-E 123`` could only ever mean production here -- the same
    flag, in the same documented example, doing different things per language.
    """
    host = os.environ.get("FM_URL") or _DEFAULT_ENDPOINT
    return f"{host}/api/marketplaces/{marketplace_id}"


def _resolve_endpoint(endpoint: str) -> dict[str, str]:
    """Resolve an ``--endpoint`` value to config overrides.

    A bare marketplace id (e.g. "2540") resolves to that marketplace on the
    default production host; a file is loaded as Java-style properties;
    anything else is treated as a full URL. Development environments give a
    full URL when localhost is wanted.
    """
    if endpoint.isdigit():
        return {"endpoint": _marketplace_endpoint(endpoint)}
    if _is_file(endpoint):
        return _load_properties_file(Path(endpoint))
    return {"endpoint": endpoint}


def _ids_param(ids: list[int]) -> str:
    return ",".join(str(i) for i in ids)


def _is_file(candidate: str | Path) -> bool:
    """Whether *candidate* names an existing file, for a string of any shape.

    ``Path.is_file()`` returns False for a path that does not exist, but
    *propagates* other OS errors -- and a real JWT is 468 characters, which is
    longer than a filename may be, so probing one raises
    ``OSError: File name too long``. Java's ``Files.isRegularFile`` and Node's
    ``existsSync`` both answer False for the same input; only Python threw, so
    ``connect(token)`` crashed before it reached the network. The test tokens
    were short enough to fit a filename, which is why nothing caught it.
    """
    try:
        return Path(candidate).is_file()
    except OSError:
        return False


def _load_properties_file(path: Path) -> dict[str, str]:
    """Load a Java-style .properties file."""
    props: dict[str, str] = {}
    if not _is_file(path):
        return props
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" in line:
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()
    return props


def _load_config() -> dict[str, str]:
    """Load ~/.fm/credential and ~/.fm/endpoint, plus FM_API_URL env var."""
    config: dict[str, str] = {}

    fm_dir = Path.home() / ".fm"
    config.update(_load_properties_file(fm_dir / "credential"))
    config.update(_load_properties_file(fm_dir / "endpoint"))

    env_url = os.environ.get("FM_API_URL")
    if env_url:
        config["endpoint"] = env_url

    if "endpoint" not in config:
        config["endpoint"] = _DEFAULT_ENDPOINT

    return config


# ---------------------------------------------------------------------------
# Response status handling
# ---------------------------------------------------------------------------

def _detail(body: str) -> str:
    """What the server said, rather than the envelope it said it in.

    Failures arrive as ``{"error","message","path","shortDigest","status"}``
    and the message is the only part a caller can act on. Falls back to the
    raw body when it does not parse, since that is when the caller most needs
    to see what came back. As the Java SDK's ``_detail``, which had this fix
    alone: here a refused sign-in said only "Authentication failed.", and a
    refused order carried the whole JSON document.
    """
    if not body or not body.strip():
        return "(no response body)"
    try:
        parsed = json.loads(body)
    except ValueError:
        return body
    if isinstance(parsed, dict):
        message = parsed.get("message")
        if isinstance(message, str) and message.strip():
            return message
    return body


def _json(response: httpx.Response) -> Any:
    """The body of a successful answer, parsed.

    A 200 that is not JSON -- an edge proxy's HTML error page, a truncated
    body -- is an answer the SDK cannot read, so it is an :class:`ApiError`,
    as in the Java SDK, rather than a bare ``json.JSONDecodeError`` escaping
    past a caller who catches :class:`FlexemarketsError`.
    """
    try:
        return response.json()
    except ValueError as e:
        raise ApiError("Failed to parse the response body") from e


def _token_expired(response: httpx.Response) -> bool:
    """A 401 that says the token ran out, as distinct from one that is wrong."""
    if response.status_code != 401:
        return False
    try:
        body = response.json()
    except ValueError:
        return False
    return isinstance(body, dict) and body.get("error") == "ACCOUNT_TOKEN_EXPIRED"


_SERVER_TIMING_ST = re.compile(r"st=(\d+)")


def _reason(body: str) -> str | None:
    """The ``error`` a failure body names, or None when it names none."""
    try:
        parsed = json.loads(body) if body and body.strip() else None
    except ValueError:
        return None
    error = parsed.get("error") if isinstance(parsed, dict) else None
    return error if isinstance(error, str) and error else None


def _answered(error: FlexemarketsError, response: httpx.Response) -> FlexemarketsError:
    """*error*, carrying the answer it came from."""
    error.status_code = response.status_code
    error.body = response.text
    error.reason = _reason(response.text)
    return error


def _check_response(response: httpx.Response) -> None:
    status = response.status_code
    if 200 <= status < 300:
        return
    raise _refused(status, response.text)


def _refused(status: int, body: str) -> FlexemarketsError:
    """The error for a refusal with this status and failure body.

    REST reads the status off the response; the socket's ORDER-REJECTED
    carries it in the body. One mapping, so an order refused either way
    raises the same thing.
    """
    if status == 400:
        error: FlexemarketsError = InvalidArgumentError("Invalid request: " + _detail(body))
    elif status == 401:
        error = AuthenticationError("Authentication failed: " + _detail(body))
    elif status == 403:
        error = AuthorizationError("Not permitted: " + _detail(body))
    elif status == 409:
        error = ConflictError(body)
    elif status >= 500:
        error = ConnectionFailedError(body)
    else:
        # Anything else, typed rather than left to httpx: a caller catching
        # FlexemarketsError should not have httpx.HTTPStatusError escape past it.
        error = HttpError(status, body)
    error.status_code = status
    error.body = body
    error.reason = _reason(body)
    return error


def _check_conflict_account(response: httpx.Response, account_name: str) -> None:
    if response.status_code == 409:
        suggested = None
        try:
            body = response.json()
            suggested = body.get("suggestedName")
        except Exception:
            pass
        raise _answered(AccountNameConflictError(
            f"Account name '{account_name}' is already taken.", suggested
        ), response)
    _check_response(response)


def _check_conflict_user(response: httpx.Response, user_id: int) -> None:
    if response.status_code == 409:
        raise _answered(PersonHasMarketplaceDataError(
            f"User {user_id} has marketplace data and cannot be deleted."
        ), response)
    _check_response(response)


# ---------------------------------------------------------------------------
# Flexemarkets client
# ---------------------------------------------------------------------------

class Flexemarkets:
    """Synchronous Python client for the Flexemarkets REST API."""

    def __init__(
        self,
        *,
        credential: str | None = None,
        endpoint: str | None = None,
        client_description: str | None = None,
        account: str | None = None,
        email: str | None = None,
        token: str | None = None,
        password: str | None = None,
    ):
        """Connect and sign in.

        An identity named here -- *account*, *email*, *password* -- replaces
        the one in ``~/.fm/credential`` rather than overlaying it. Overlaying
        signed a program in as someone else: a token in the file outranks a
        password, so naming an account and email beside it was silently
        ignored, and a robot meant for one participant traded as whoever owned
        that machine's credential file. A part not named here is missing, not
        borrowed from the file. ``~/.fm/endpoint`` and ``FM_API_URL`` still
        supply the endpoint when *endpoint* is not given.
        """
        self._client_description = client_description or "Unspecified client"

        # Build config from defaults then override with explicit args
        config = _load_config()
        if any(v is not None for v in (account, email, password)):
            for key in ("account", "email", "password", "token", _REFRESH_TOKEN_PROPERTY):
                config.pop(key, None)

        if credential is not None:
            if credential.startswith(_REFRESH_TOKEN_PREFIX) and credential[len(_REFRESH_TOKEN_PREFIX):].strip():
                config[_REFRESH_TOKEN_PROPERTY] = credential[len(_REFRESH_TOKEN_PREFIX):]
            elif _is_file(credential):
                config.update(_load_properties_file(Path(credential)))
            elif _is_valid_token(credential):
                config["token"] = credential
            else:
                raise ConfigurationError(
                    f"Invalid credential: '{credential}' is not a file or token."
                )

        if endpoint is not None:
            config.update(_resolve_endpoint(endpoint))

        if account is not None:
            config["account"] = account
        if email is not None:
            config["email"] = email
        if token is not None:
            config["token"] = token
        if password is not None:
            config["password"] = password

        self._endpoint = config.get("endpoint", _DEFAULT_ENDPOINT)

        # What the previous call cost, carried on the next (see _carry_timing).
        self._timing_lock = threading.Lock()
        self._last_timing: str | None = None

        # Build httpx client
        self._http = httpx.Client(
            base_url=_server(self._endpoint),
            headers={
                "Content-Type": "application/json",
                "User-Agent": _FM_NETWORK_CLIENT,
            },
            timeout=30.0,
            # A connection kept a minute between requests, not httpx's 5 s: a
            # robot quiet for longer paid a new TCP and TLS handshake on its
            # next order, 20-30 ms to Cloudflare's edge and ~600 ms straight to
            # Heroku from Melbourne. Under the 90 s Heroku's router holds one.
            limits=httpx.Limits(keepalive_expiry=60.0),
            event_hooks={"request": [self._carry_timing], "response": [self._record_timing]},
        )

        # Kept only to sign in again when the 24-hour token runs out under a
        # long-running robot; a client given a bare token cannot, and does not.
        self._credentials = (dict(config) if config.get("account") and config.get("email")
                             and config.get("password") and not _is_valid_token(config.get("token", ""))
                             else None)
        self._renew_lock = threading.Lock()
        # The refresh token the next renewal presents, replaced by the one each
        # renewal hands back: fm-server takes each once, and one presented twice
        # ends every token renewed from it. None for a client that has none.
        self._refresh_token: str | None = (config.get(_REFRESH_TOKEN_PROPERTY) or "").strip() or None

        # Authenticate
        self._token_obj = self._sign_in(config)
        self._account = self._token_obj.account
        self._user = self._token_obj.person

        self._event_listener = None

        # Phase 2d shared-desk registry, keyed by marketplace_id.
        self._shared_views: dict[int, _SharedView] = {}
        self._view_lock = threading.Lock()

        # The socket each marketplace's send_limit orders go out on, opened by
        # the first of them.
        self._order_streams: dict[int, "OrderStream"] = {}
        self._order_stream_lock = threading.Lock()

    # -- factory helpers matching Java's connect() overloads ----------------

    @classmethod
    def connect(
        cls,
        credential: str | None = None,
        endpoint: str | None = None,
        client_description: str | None = None,
    ) -> Flexemarkets:
        return cls(
            credential=credential,
            endpoint=endpoint,
            client_description=client_description,
        )

    # -- properties --------------------------------------------------------

    @property
    def account(self) -> Account:
        return self._account  # type: ignore[return-value]

    @property
    def account_id(self) -> int:
        return self._account.id  # type: ignore[union-attr]

    @property
    def account_name(self) -> str:
        return self._account.name  # type: ignore[union-attr,return-value]

    @property
    def user(self) -> Person:
        return self._user  # type: ignore[return-value]

    @property
    def user_id(self) -> int:
        return self._user.id  # type: ignore[union-attr]

    @property
    def endpoint_url(self) -> str:
        return self._endpoint

    @property
    def endpoint_marketplace_id(self) -> int:
        return _resource_id(self._endpoint)

    def token(self) -> Token:
        """The token this connection signed in with.

        Exposed so a caller can mint a sibling connection on the same
        identity without holding the password again.
        """
        return self._token_obj

    def is_admin(self) -> bool:
        return self.has_role("ROLE_ADMIN")

    def is_manager(self) -> bool:
        return self.has_role("ROLE_MANAGER")

    def has_role(self, role: str) -> bool:
        if self._user is None or not self._user.roles:
            return False
        return role in self._user.roles

    # -- internal HTTP helpers ---------------------------------------------

    def _auth_headers(self) -> dict[str, str]:
        return {"Authorization": self._bearer_token()}

    def _bearer_token(self) -> str:
        """The Authorization header for the current sign-in, derived from it
        so that ``token()`` and every request agree on which is current."""
        return f"Bearer {self._token_obj.token}"

    def _renew_token(self, presented: str) -> bool:
        """Sign in again with the credentials this client was made with.

        Tokens live 24 hours, so a robot that runs longer used to end the
        first time it asked for anything after that -- or loop on the refusal
        (a Utah poller, about 250 times a day at 00Z). One sign-in renews it.
        False when this client holds no credentials (it was given a token) or
        the sign-in fails; the caller then answers with the refusal it had.
        ``presented`` is the header the refused request or stream connect
        carried; if another caller has renewed since, the sign-in is skipped
        and the caller retries with the token now current.
        """
        if self._credentials is None and self._refresh_token is None:
            return False
        with self._renew_lock:
            if presented != self._bearer_token():
                return True
            try:
                token = self._sign_in(self._credentials or {})
            except FlexemarketsError:
                return False
            self._token_obj = token
            return True

    def _request(self, method: str, url: str, **kwargs: Any) -> httpx.Response:
        """Send with this client's token; if it has expired, renew it once and send again."""
        extra = kwargs.pop("headers", {})
        presented = self._bearer_token()
        resp = self._http.request(method, url, headers={**self._auth_headers(), **extra}, **kwargs)
        if _token_expired(resp) and self._renew_token(presented):
            resp = self._http.request(method, url, headers={**self._auth_headers(), **extra}, **kwargs)
        _check_response(resp)
        return resp

    def _get(self, url: str) -> httpx.Response:
        return self._request("GET", url, headers={"Accept": "application/json"})

    def _post(self, url: str, json: Any, headers: dict[str, str] | None = None) -> httpx.Response:
        return self._request("POST", url, json=json, headers={"Accept": "application/json", **(headers or {})})

    def _create(self, send: Callable[[dict[str, str]], httpx.Response]) -> httpx.Response:
        """Send a create under an ``Idempotency-Key``, and once more under the
        same key if its answer was lost.

        A create that timed out or lost its connection may or may not have
        landed; fm-server 4.17 answers a key it already holds with what it
        answered the first time (its IdempotencyFilter), so the resend makes
        one marketplace, market or call, never two. Orders do the same with
        their own ``submissionId``.
        """
        key = {"Idempotency-Key": str(uuid.uuid4())}
        try:
            return send(key)
        except httpx.TransportError:
            return send(key)

    def _patch(self, url: str, json: Any) -> httpx.Response:
        return self._request("PATCH", url, json=json, headers={"Accept": "application/json"})

    def _put(self, url: str, json: Any) -> httpx.Response:
        return self._request("PUT", url, json=json, headers={"Accept": "application/json"})

    def _delete(self, url: str) -> httpx.Response:
        return self._request("DELETE", url, headers={"Accept": "application/json"})

    def _send_csv(
        self, method: str, url: str, filename: str, headers: dict[str, str] | None = None,
    ) -> httpx.Response:
        """The file as the request body, declared text/csv.

        V1 says what a body is by its Content-Type (API-V1 rule 7), where V0
        took a multipart upload to a ``.../uploads`` path.
        """
        with open(filename, "rb") as f:
            content = f.read()
        return self._send_csv_content(method, url, content, headers)

    def _send_csv_content(
        self, method: str, url: str, content: bytes, headers: dict[str, str] | None = None,
    ) -> httpx.Response:
        """CSV bytes as the request body, declared text/csv; see :meth:`_send_csv`."""
        resp = self._http.request(
            method, url, content=content,
            headers={
                **self._auth_headers(),
                "Content-Type": "text/csv",
                "Accept": "application/json",
                **(headers or {}),
            },
        )
        _check_response(resp)
        return resp

    # -- round-trip timing -------------------------------------------------

    def _carry_timing(self, request: httpx.Request) -> None:
        """Send what the previous call cost on this one, as ``Client-Timing``.

        A round trip is not known until it has finished, by which time its own
        request has gone, so each figure arrives one call late -- for a robot on
        an interval, a lag of one tick. Taken rather than read, so a figure is
        reported once: repeating it would weight one slow call by however many
        quiet ones followed. The format is the Java SDK's and fm-ui's:
        ``rtt=<ns>`` and, when the server said what its own share was,
        ``;net=<ns>`` (fm-server's ApiMetricsFilter). Every request goes through
        here, sign-in included.
        """
        with self._timing_lock:
            timing, self._last_timing = self._last_timing, None
        if timing is not None:
            request.headers["Client-Timing"] = timing
        request.extensions["fm_started_ns"] = time.perf_counter_ns()

    def _record_timing(self, response: httpx.Response) -> None:
        """Keep what this call cost for the next request to report.

        Measured to the response's headers, on this machine's clock. The
        server reports its own handling as ``Server-Timing: st=<ns>``; what is
        left of the round trip is the wire. Without it the wire is unknown and
        left out rather than sent as zero, which would claim there was none.
        """
        # Always there: httpx runs the request hook before this one.
        rtt = time.perf_counter_ns() - response.request.extensions["fm_started_ns"]
        timing = f"rtt={rtt}"
        served = _SERVER_TIMING_ST.search(response.headers.get("Server-Timing", ""))
        if served:
            timing += f";net={max(0, rtt - int(served.group(1)))}"
        with self._timing_lock:
            self._last_timing = timing

    # -- authentication ----------------------------------------------------

    def _renew_with_refresh_token(self) -> Token:
        """Sign in with the refresh token in hand: fm-server takes it once, and
        answers with an access token and the refresh token to present next time.

        This is how a robot fm-server launched outlives its access token. It is
        handed a refresh token of its own, not the participant's access token,
        which stops working after hours while the robot runs on.
        """
        resp = self._http.post(
            _v1(self._endpoint, "/tokens/renew"),
            json={"refreshToken": self._refresh_token},
            headers={"Accept": "application/json"},
        )
        _check_response(resp)
        data = _json(resp)
        if data.get("refreshToken"):
            self._refresh_token = data["refreshToken"]
        return _parse_token(data)

    def _sign_in(self, config: dict[str, str]) -> Token:
        if self._refresh_token is not None:
            return self._renew_with_refresh_token()
        tok = config.get("token", "")
        if tok and _is_valid_token(tok):
            # A caller who already holds a token has no account/email/password
            # to present, so signing in is not available: POSTing /tokens with
            # blanks is rejected -- the server answers 400 MESSAGE_NOT_READABLE
            # for an empty password, which is what this used to send. Refreshing
            # the token both validates it and returns the account and person
            # behind it.
            #
            # This is the third time. fm-lib-net carried the branch, an earlier
            # rewrite dropped it, and the Java SDK restored it with a test. This
            # SDK and the TypeScript one never had it, so token auth returned
            # 400 in both from the day it was written.
            resp = self._http.post(
                _v1(self._endpoint, "/tokens/refresh"),
                headers={
                    "Authorization": f"Bearer {tok}",
                    "Accept": "application/json",
                },
            )
            _check_response(resp)
            return _parse_token(_json(resp))

        acct = config.get("account", "")
        email = config.get("email", "")
        password = config.get("password", "")

        if not acct:
            raise ConfigurationError("Missing 'account' in configuration.")
        if not email:
            raise ConfigurationError("Missing 'email' in configuration.")
        if not password:
            raise ConfigurationError("Missing 'password' in configuration.")

        auth_url = _v1(self._endpoint, "/tokens")
        resp = self._http.post(
            auth_url,
            json={"username": f"{acct}|{email}", "password": password},
            headers={"Accept": "application/json"},
        )
        _check_response(resp)
        return _parse_token(_json(resp))

    # ======================================================================
    # REST APIs
    # ======================================================================

    # -- accounts ----------------------------------------------------------

    def accounts(self) -> list[Account]:
        url = _v1(self._endpoint, "/accounts")
        data = _json(self._get(url))
        return [_parse_account(a) for a in data]

    def accounts_page(self, limit: int, before: int | None = None) -> Page[Account]:
        """A page of :meth:`accounts`: the newest *limit*, or of those whose id is
        below *before* -- a page's ``next``. The server answers at most 5,000.
        """
        return self._page(_v1(self._endpoint, "/accounts"), limit, before, _parse_account)

    def signup(
        self,
        account_name: str,
        email: str,
        password: str,
        first_name: str | None = None,
        last_name: str | None = None,
    ) -> Token:
        url = _v1(self._endpoint, "/accounts")
        body: dict[str, Any] = {
            "accountName": account_name,
            "ownerEmail": email,
            "ownerPassword": password,
        }
        if first_name is not None:
            body["firstName"] = first_name
        if last_name is not None:
            body["lastName"] = last_name

        resp = self._http.post(
            url,
            json=body,
            headers={
                **self._auth_headers(),
                "Accept": "application/json",
            },
        )
        _check_conflict_account(resp, account_name)
        return _parse_token(_json(resp))

    def approve_account(self, account_name: str) -> Account:
        """Approve the account of that name, returning it as approved.

        V1 approves by id, so the name is looked up in the account list first.
        A name no account has is the caller's mistake, said as one, rather
        than a 404 from a route built around no id.
        """
        account = next((a for a in self.accounts() if a.name == account_name), None)
        if account is None:
            raise InvalidArgumentError(f"No account named '{account_name}'")
        url = _v1(self._endpoint, f"/accounts/{account.id}/approvals")
        approval_data = _json(self._post(url, {"approve": True}))
        return _parse_account(approval_data.get("account"))  # type: ignore[return-value]

    def delete_account(self, account_id: int) -> None:
        self._delete(_v1(self._endpoint, f"/accounts/{account_id}"))

    def manager_otp_bundle(self, user_ids: list[int]) -> ManagerOtpBundle:
        """Mint one-time passcodes for the given users.

        These are credentials: not to be logged, not to be persisted, and
        delivered to the person they belong to.
        """
        url = _v1(self._endpoint, "/otp/manager")
        data = _json(self._post(url, {"userIds": user_ids}))
        return ManagerOtpBundle(
            expires_at=_timestamp(data.get("expiresAt")),
            otps=[
                ManagerOtpEntry(
                    user_id=o.get("userId", 0),
                    email=o.get("email"),
                    otp=o.get("otp"),
                )
                for o in data.get("otps") or []
            ],
        )

    def delete_my_account(self) -> None:
        self._delete(_v1(self._endpoint, "/accounts/me"))

    # -- users -------------------------------------------------------------

    def users(self) -> list[Person]:
        url = _v1(self._endpoint, "/users")
        data = _json(self._get(url))
        return [_parse_person(u) for u in data]  # type: ignore[misc]

    def users_page(self, limit: int, before: int | None = None) -> Page[Person]:
        """A page of :meth:`users`: the newest *limit*, or of those whose id is
        below *before* -- a page's ``next``. The server answers at most 5,000.
        """
        return self._page(_v1(self._endpoint, "/users"), limit, before, _parse_person)  # type: ignore[arg-type]

    def create_user(
        self,
        email: str,
        password: str,
        first_name: str,
        last_name: str,
        *roles: str,
    ) -> Person:
        url = _v1(self._endpoint, "/users")
        user = {
            "email": email,
            "password": password,
            "firstName": first_name,
            "lastName": last_name,
            "roles": list(roles),
        }
        resp = self._create(lambda key: self._post(url, user, key))
        return _parse_person(_json(resp))  # type: ignore[return-value]

    def delete_user(self, user_id: int) -> None:
        url = _v1(self._endpoint, f"/users/{user_id}")
        resp = self._http.delete(url, headers=self._auth_headers())
        _check_conflict_user(resp, user_id)

    # -- marketplaces ------------------------------------------------------

    def marketplaces(self) -> list[Marketplace]:
        url = _v1(self._endpoint, "/marketplaces")
        data = _json(self._get(url))
        return [_parse_marketplace(m) for m in data]

    def marketplaces_page(self, limit: int, before: int | None = None) -> Page[Marketplace]:
        """A page of :meth:`marketplaces`: the newest *limit*, or of those whose id is
        below *before* -- a page's ``next``. The server answers at most 5,000.
        """
        return self._page(_v1(self._endpoint, "/marketplaces"), limit, before, _parse_marketplace)

    def current_sessions(self) -> list[CurrentSession]:
        """Each marketplace :meth:`marketplaces` lists that has a session, with that
        session: one read where :meth:`session` would be one per marketplace. A
        marketplace that has never opened a session is left out.
        """
        url = _v1(self._endpoint, "/marketplaces/current-sessions")
        return [_parse_current_session(s) for s in _json(self._get(url))]

    def current_sessions_page(self, limit: int, before: int | None = None) -> Page[CurrentSession]:
        """A page of :meth:`current_sessions`, paged by marketplace as
        :meth:`marketplaces_page` pages: the current sessions of exactly the
        marketplaces that page holds. One with no session is left out, so a page
        can hold fewer rows than *limit* and still have a ``next``.
        """
        return self._page(_v1(self._endpoint, "/marketplaces/current-sessions"), limit, before,
                          _parse_current_session)

    def marketplace(self, marketplace_id: int) -> Marketplace:
        url = _marketplace(self._endpoint, marketplace_id)
        return _parse_marketplace(_json(self._get(url)))

    def create_marketplace_from_json(self, definition: str) -> Marketplace:
        """Create a marketplace from its JSON definition, returning what was made.

        Takes JSON rather than arguments because that is how the definitions
        exist: a study keeps its marketplace as a document it can print, diff
        and hand to someone, and the CLI's dry-run prints exactly the document
        that would be posted. Building it from arguments here would mean the
        thing printed and the thing sent were assembled by different code.

        The definition is parsed before it is sent, so a malformed one fails
        here rather than as a 400 describing a document the caller never sees.
        """
        try:
            parsed = json.loads(definition)
        except ValueError as e:
            raise InvalidArgumentError(
                f"Marketplace definition is not valid JSON: {e}") from e

        url = _v1(self._endpoint, "/marketplaces")
        return _parse_marketplace(_json(self._create(lambda key: self._post(url, parsed, key))))

    def delete_marketplace(self, marketplace_id: int) -> None:
        self._delete(_marketplace(self._endpoint, marketplace_id))

    # -- markets -----------------------------------------------------------

    def markets(self, marketplace_id: int) -> list[Market]:
        url = _marketplace(self._endpoint, marketplace_id) + "/markets"
        data = _json(self._get(url))
        return [_parse_market(m) for m in data]

    def symbols(self, marketplace_id: int) -> list[str]:
        """The markets' own symbols: V1 has no ``/symbols``, which was this
        projection server-side."""
        return [m.symbol for m in self.markets(marketplace_id)]  # type: ignore[misc]

    def indicative(self, marketplace_id: int, market_id: int) -> CallIndicative:
        """What an indicative call market's book would clear at now: the price
        and volume a call would make, read on subscribe. The stream then
        delivers each change as a :class:`CallIndicative`; apply those with a
        higher ``seq`` for the same session.

        Only for a market that :meth:`Market.shows_indicative`, while a
        session is open; the server refuses a sealed market, a continuous one,
        or no session.
        """
        url = _marketplace(self._endpoint, marketplace_id) + f"/markets/{market_id}/indicative"
        return _parse_call_indicative(_json(self._get(url)))

    def create_market(
        self,
        marketplace_id: int,
        symbol: str,
        name: str,
        price: TickGrid,
        units: TickGrid | None = None,
        private_market: bool = False,
    ) -> Market:
        """Add a market to a marketplace.

        Both dimensions are the caller's. Unit bounds used to be fixed at
        1/100/1 with no way to say otherwise, on a call that set the price grid
        three arguments earlier -- and the server enforces the two identically,
        refusing an order for "units is not on a tic" exactly as for a price.
        Omitting *units* keeps the old default.
        """
        units = units if units is not None else TickGrid.units()
        url = _marketplace(self._endpoint, marketplace_id) + "/markets"
        market = {
            "symbol": symbol,
            "name": name,
            "priceMinimum": price.minimum,
            "priceMaximum": price.maximum,
            "priceTick": price.tick,
            "unitMinimum": units.minimum,
            "unitMaximum": units.maximum,
            "unitTick": units.tick,
            "privateMarket": private_market,
        }
        resp = self._create(lambda key: self._post(url, market, key))
        return _parse_market(_json(resp))

    def create_call_market(
        self,
        marketplace_id: int,
        symbol: str,
        name: str,
        price: TickGrid,
        units: TickGrid | None = None,
        interval_seconds: int | None = None,
        carry: bool = True,
        call_book: str | None = None,
    ) -> Market:
        """Add a call-auction market to a marketplace: orders collect, crossed
        or not, and a call clears them all at one price.

        Calls come from the market's schedule, every *interval_seconds* while a
        session is open, or from a manager's :meth:`clear_now`; None leaves
        only the latter. *carry* is whether what a call leaves unfilled waits
        for the next one rather than being cancelled. A call market cannot be
        private, so there is no flag for it here; the server refuses the
        combination.

        *call_book* is what the book shows during the call:
        :attr:`Market.CALL_BOOK_SEALED`, a participant's own orders only, or
        :attr:`Market.CALL_BOOK_INDICATIVE`, also the price it would clear at
        now and the volume. None leaves it to the server, which makes it
        sealed. Needs fm-server 4.17; an older one ignores it.
        """
        units = units if units is not None else TickGrid.units()
        url = _marketplace(self._endpoint, marketplace_id) + "/markets"
        market = {
            "symbol": symbol,
            "name": name,
            "priceMinimum": price.minimum,
            "priceMaximum": price.maximum,
            "priceTick": price.tick,
            "unitMinimum": units.minimum,
            "unitMaximum": units.maximum,
            "unitTick": units.tick,
            "privateMarket": False,
            "mechanism": Market.MECHANISM_CALL,
            "callIntervalSeconds": interval_seconds,
            "callCarry": carry,
        }
        if call_book is not None:
            market["callBook"] = call_book
        resp = self._create(lambda key: self._post(url, market, key))
        return _parse_market(_json(resp))

    # -- sessions ----------------------------------------------------------

    def sessions(self, marketplace_id: int) -> list[Session]:
        """The marketplace's sessions -- all of them.

        There is no server-side filter. The route takes no session argument,
        so the ``session_ids`` this used to accept was silently ignored: it
        returned the whole history and looked like it had filtered. Filter the
        result; fm-ui, fm-manager and capm all already do.

        On ``GET /api/v1/marketplaces/{id}/sessions``, which answers with the
        same fields as the V0 route it replaces -- verified against a running
        server, not assumed -- and needs no ``format=application/json`` to
        avoid HAL.
        """
        url = _marketplace(self._endpoint, marketplace_id) + "/sessions"
        return [_parse_session(s) for s in _json(self._get(url))]

    def sessions_page(self, marketplace_id: int, limit: int, before: int | None = None) -> Page[Session]:
        """A page of :meth:`sessions`: the newest *limit*, or of those whose id is
        below *before* -- a page's ``next``. The server answers at most 5,000.
        """
        return self._page(_marketplace(self._endpoint, marketplace_id) + "/sessions", limit, before, _parse_session)

    def session(self, marketplace_id: int) -> Session:
        url = _marketplace(self._endpoint, marketplace_id) + "/sessions/current"
        return _parse_session(_json(self._get(url)))

    def account_by_id(self, account_id: int) -> Account:
        """One account by id.

        Named apart from the ``account`` property, which is the account this
        connection signed in to. Java overloads the two; a property cannot
        take an argument, so here they are separate names.
        """
        return _parse_account(_json(self._get(_v1(self._endpoint, f"/accounts/{account_id}"))))

    def user_by_id(self, user_id: int) -> Person:
        """One user by id; see :meth:`account_by_id` for the name."""
        url = _v1(self._endpoint, f"/users/{user_id}")
        return _parse_person(_json(self._get(url)))

    def identifiers(self, marketplace_id: int) -> list[str]:
        """The names of the participants a caller may target: ``GET
        participants``, names only. A row is an object -- a name, and for a
        manager the user id, not needed here."""
        url = _marketplace(self._endpoint, marketplace_id) + "/participants"
        return [p.get("name") for p in _json(self._get(url))]

    def open_session(self, marketplace_id: int) -> Session:
        return self._session_state(marketplace_id, "OPEN")

    def pause_session(self, marketplace_id: int) -> Session:
        return self._session_state(marketplace_id, "PAUSED")

    def close_session(self, marketplace_id: int) -> Session:
        return self._session_state(marketplace_id, "CLOSED")

    def _session_state(self, marketplace_id: int, state: str) -> Session:
        """A lifecycle is a state, PATCHed onto the current session (API-V1 rule 3)."""
        url = _marketplace(self._endpoint, marketplace_id) + "/sessions/current"
        return _parse_session(_json(self._patch(url, {"state": state})))

    # -- orders ------------------------------------------------------------

    def submit_limit(
        self,
        marketplace_id: int,
        market_id: int,
        side: str,
        units: int,
        price: int,
        *,
        owner_target: str | None = None,
        owner_target_id: int | None = None,
        client_description: str | None = None,
    ) -> Order:
        """Place a limit order.

        A private market matches an order only with one aimed back at its
        owner, so an order there names its counterparty: by *owner_target_id*
        when the caller knows it, or by *owner_target*, the allotment name. The
        id is the unambiguous one, but only a manager can learn ids -- a
        participant's ``identifiers()`` are names -- so a participant has the
        name and nothing else. Name one or the other, not both.

        *client_description* is this order's own reference, as the server keeps
        it on the order; it defaults to the description this client connected
        with.
        """
        order = self._limit_order(marketplace_id, market_id, side, units, price, owner_target,
                                  owner_target_id, client_description)
        url = _marketplace(self._endpoint, marketplace_id) + "/orders"
        try:
            resp = self._post(url, order)
        except httpx.TransportError:
            resp = self._post(url, order)
        return _parse_order(_json(resp))

    def send_limit(
        self,
        marketplace_id: int,
        market_id: int,
        side: str,
        units: int,
        price: int,
        *,
        owner_target: str | None = None,
        owner_target_id: int | None = None,
        client_description: str | None = None,
    ) -> "PendingOrder":
        """Place a limit order without waiting for the answer.

        The order goes over the marketplace's socket and this returns a
        :class:`~fm.order_stream.PendingOrder` at once: the next order can go
        before this one is matched, and fm-server still matches them in the
        order sent. Its ``result()`` is the order, or the refusal
        :meth:`submit_limit` would have raised -- or
        :class:`~fm.exceptions.OrderOutcomeUnknownError` if the socket closed
        before the answer came. The socket opens with the first order sent this
        way and stays open until :meth:`close`; at most
        :data:`~fm.order_stream.MAX_IN_FLIGHT` orders are unanswered at once.
        The arguments are :meth:`submit_limit`'s. See docs/SUBMIT-RATE.md.
        """
        order = self._limit_order(marketplace_id, market_id, side, units, price, owner_target,
                                  owner_target_id, client_description)
        return self._order_stream(marketplace_id).submit(order)

    def _limit_order(
        self, marketplace_id: int, market_id: int, side: str, units: int, price: int,
        owner_target: str | None, owner_target_id: int | None, client_description: str | None,
    ) -> dict[str, Any]:
        """The body of a limit order, named with a fresh submission id."""
        if owner_target is not None and owner_target_id is not None:
            raise InvalidArgumentError(
                "Name the counterparty by owner_target or owner_target_id, not both")
        order: dict[str, Any] = {
            "marketplaceId": marketplace_id,
            "marketId": market_id,
            "type": OrderType.LIMIT,
            "side": side,
            "units": units,
            "price": price,
            "clientDescription": client_description or self._client_description,
        }
        # Left out rather than sent as null: a target of null is a private order
        # aimed at nobody, which the server refuses.
        if owner_target is not None:
            order["ownerTarget"] = owner_target
        if owner_target_id is not None:
            order["ownerTargetId"] = owner_target_id
        # Named, so an order whose answer was lost can be sent again without
        # trading twice: fm-server answers a submission it already holds with
        # the order it made, not a second one. A send that timed out or lost
        # its connection may or may not have landed; reporting it as refused
        # invited the caller to place it again and double the position.
        order["submissionId"] = str(uuid.uuid4())
        return order

    def _order_stream(self, marketplace_id: int) -> "OrderStream":
        """The marketplace's order socket, opened on first use."""
        from .order_stream import RECONNECT_MAX_SECONDS, OrderStream

        with self._order_stream_lock:
            stream = self._order_streams.get(marketplace_id)
            if stream is None:
                stream = OrderStream(lambda events: self._connect_events(
                    marketplace_id, events, reconnect_max_seconds=RECONNECT_MAX_SECONDS), marketplace_id)
                self._order_streams[marketplace_id] = stream
            return stream

    def submit_market(
        self, marketplace_id: int, market_id: int, side: str, units: int,
    ) -> Order:
        """Cross the book: buy at the highest price this market allows, sell at
        the lowest. Immediate or cancel -- whatever does not fill is cancelled.

        There is no market order on the server. Its type switch falls through to
        ``LIMIT``, so every submission is bounds-checked against the market and
        must sit on a tick -- which is why this asks the marketplace for the
        market first, and costs a round trip :meth:`submit_limit` does not.

        The cancel is unconditional: the exchange consumes a cancel by itself
        when no units remain, so a complete fill costs a harmless round trip
        rather than an inspection that would race the book. Without it, a market
        order that did not fill would rest at the market's extreme -- the best
        price in the book, standing, for anyone to take.

        Returns the limit order as submitted. What it filled is a property of
        the book afterwards, not of this value.
        """
        price = _marketable_limit(self._market(marketplace_id, market_id), side)
        limit = self.submit_limit(marketplace_id, market_id, side, units, price)

        try:
            self.submit_cancel(marketplace_id, market_id, limit.id)
        except FlexemarketsError as e:
            # The order is placed. Reporting only "cancel failed" would invite a
            # caller to retry the whole thing and trade twice.
            raise FlexemarketsError(
                f"Order {limit.id} was placed but its remainder could not be "
                f"cancelled; it may still be resting. Do not resubmit -- cancel it."
            ) from e

        return limit

    def _market(self, marketplace_id: int, market_id: int) -> Market:
        for candidate in self.markets(marketplace_id):
            if candidate.id == market_id:
                return candidate
        raise InvalidArgumentError(
            f"Market {market_id} is not in marketplace {marketplace_id}"
        )

    def submit_cancel(
        self, marketplace_id: int, market_id: int, original_id: int,
    ) -> Order:
        """Cancel what remains of *original_id*, returning the CANCEL order the
        exchange recorded.

        DELETE cancels (API-V1 rule 2). *market_id* is not on the wire: the
        order names its own market. It stays an argument so the three SDKs
        keep one signature.
        """
        url = _marketplace(self._endpoint, marketplace_id) + f"/orders/{original_id}"
        return _parse_order(_json(self._delete(url)))

    def active_orders(self, marketplace_id: int) -> "Snapshot[list[Order]]":
        """The active-orders snapshot: every resting limit order on the
        marketplace's current session, plus the ``x-fm-as-of-seq``
        sequence the snapshot was read at. Used by
        :class:`~fm.desk.Desk` Phase 2a seeding —
        clients apply WS deltas whose seq is greater than the
        returned value and skip those whose seq is less than or
        equal.
        """
        url = _marketplace(self._endpoint, marketplace_id) + "/orders?state=ACTIVE"
        body, as_of_seq = self._get_snapshot(url)
        orders_raw = _orders_of(body)
        return Snapshot(body=[_parse_order(o) for o in orders_raw], as_of_seq=as_of_seq)

    def recent_trades(
        self, marketplace_id: int, size: int = 1000, market_id: "int | None" = None,
    ) -> "Snapshot[list[Order]]":
        """The recent-trades snapshot, for seeding the trade-history
        tape. Same ``x-fm-as-of-seq`` contract as
        :meth:`active_orders`. Server caps at 5000; default is
        1000.

        Ordering is the server's and has changed: up to and including
        fm-server 4.3.1 this answered newest first, later versions answer
        oldest first. Either way it is the newest ``size`` trades that come
        back -- only their order differs. :class:`~fm.trades.Tape` sorts
        what it is given, so a caller seeding a tape through
        :class:`~fm.desk.Desk` is unaffected; a caller reading
        this list directly should not assume one.

        With *market_id*, only that market's legs (``&market=``), *size* of
        them, two to a trade. What :class:`~fm.desk.Desk` seeds each tape
        from: read for every market at once, a busy market's legs fill the
        limit and a quiet market's tape comes up empty, though it has traded.
        """
        url = _marketplace(self._endpoint, marketplace_id) + "/orders?state=TRADED"
        if market_id is not None:
            url += f"&market={market_id}"
        url += f"&limit={size}"
        body, as_of_seq = self._get_snapshot(url)
        orders_raw = _orders_of(body)
        return Snapshot(body=[_parse_order(o) for o in orders_raw], as_of_seq=as_of_seq)

    def _page(self, url: str, limit: int, before: int | None, parse: Callable[[Any], T]) -> Page[T]:
        """A page of a collection: *url* with ``?limit=`` and ``?before=``, and
        the ``before`` of the page after it read from the ``Link: <...>;
        rel="next"`` the server sends while older rows remain."""
        url += ("&" if "?" in url else "?") + f"limit={limit}"
        if before is not None:
            url += f"&before={before}"
        resp = self._get(url)
        nxt = _NEXT_LINK.search(",".join(resp.headers.get_list("link")))
        return Page(rows=[parse(row) for row in _json(resp)], next=int(nxt.group(1)) if nxt else None)

    def _get_snapshot(self, url: str) -> tuple[dict[str, Any], int]:
        """GET helper that returns the parsed body alongside the
        ``x-fm-as-of-seq`` response header value. Returns
        :data:`NO_SEQ` when the header is absent.
        """
        resp = self._http.get(
            url,
            headers={
                **self._auth_headers(),
                "Accept": "application/json",
            },
        )
        _check_response(resp)
        raw = resp.headers.get("x-fm-as-of-seq")
        try:
            as_of_seq = int(raw) if raw is not None else NO_SEQ
        except ValueError:
            as_of_seq = NO_SEQ
        return _json(resp), as_of_seq

    def orders(
        self,
        marketplace_id: int,
        *,
        symbol: str | None = None,
        session_ids: list[int] | None = None,
    ) -> list[Order]:
        """Orders on the marketplace.

        With no filter, what V0's marketplace orders answered: cancelled orders,
        their CANCEL rows and self-crosses left out (``cancelled=false``). With
        ``session_ids``, the whole lifecycle of those runs, cancels included.
        With ``symbol``, that market's resting book.
        """
        base = _marketplace(self._endpoint, marketplace_id) + "/orders"
        if symbol is not None:
            url = f"{base}?state=ACTIVE&symbol={quote(symbol, safe='')}"
            data = _json(self._get(url))
            orders = [_parse_order(o) for o in data]
            for o in orders:
                o.symbol = symbol
            return orders
        if session_ids is not None:
            url = f"{base}?sessions={_ids_param(session_ids)}"
            data = _json(self._get(url))
            return [_parse_order(o) for o in data]
        data = _json(self._get(f"{base}?cancelled=false"))
        return [_parse_order(o) for o in data]

    def orders_page(self, marketplace_id: int, limit: int, before: int | None = None) -> Page[Order]:
        """A page of :meth:`orders` with no filter -- cancellations left out,
        as there: the newest *limit*, or of those whose id is below *before* --
        a page's ``next``. The server answers at most 5,000.
        """
        url = _marketplace(self._endpoint, marketplace_id) + "/orders?cancelled=false"
        return self._page(url, limit, before, _parse_order)

    def trades(self, marketplace_id: int, symbol: str) -> list[Order]:
        """Tape in one market, in ascending order id.

        The market's traded legs, ``GET orders?state=TRADED&symbol=``, asking
        for the most the server answers in one read (5000) since the route
        this replaced had no limit. The trade id is taken from ``original``
        and the symbol filled in, which is what makes the result a trade list
        rather than a set of half-populated orders.

        The order is the server's, which sorts by order id and nothing else. It is
        neither chronological by trade time nor most-recent-first -- the first
        element is the lowest id, not the latest trade. Sort by
        ``last_modified_date`` if you want time order.
        """
        url = (
            _marketplace(self._endpoint, marketplace_id)
            + f"/orders?state=TRADED&symbol={quote(symbol, safe='')}&limit={_MAX_TRADED_LEGS}"
        )
        data = _json(self._get(url))
        orders = [_parse_order(o) for o in data]
        for o in orders:
            # The trade id is the original order's, and the query already
            # fixed the symbol. Filling both in is what makes the result a
            # trade list rather than a set of half-populated orders.
            o.id = o.original
            o.symbol = symbol
        return orders

    # -- holdings ----------------------------------------------------------

    def holdings(
        self,
        marketplace_id: int,
        session_ids: list[int] | None = None,
    ) -> list[Holding]:
        url = _marketplace(self._endpoint, marketplace_id) + "/holdings"
        if session_ids:
            url += f"?sessions={_ids_param(session_ids)}"
        data = _json(self._get(url))
        return [_parse_holding(h) for h in data]

    def holding(self, marketplace_id: int) -> Holding:
        """The caller's own holding in *marketplace_id*.

        Took a ``user_id`` it never used: the route is
        ``participants/me/holding``, which is the caller's by definition, and the argument was accepted and
        discarded. So every call site had to invent a value, and one that passed
        somebody else's id got its own holding back and no indication of it.
        Java and TypeScript always took the marketplace alone.
        """
        url = _marketplace(self._endpoint, marketplace_id) + "/participants/me/holding"
        return _parse_holding(_json(self._get(url)))

    def download_holdings(
        self, marketplace_id: int, session_ids: list[int] | None = None,
    ) -> str:
        """The holdings CSV, verbatim, for the current session or for given ones.

        The same route as :meth:`holdings`, asking for ``text/csv``: V1 picks
        a format by header, not by path (API-V1 rule 7).
        """
        url = _marketplace(self._endpoint, marketplace_id) + "/holdings"
        if session_ids:
            url += f"?sessions={_ids_param(session_ids)}"
        resp = self._http.get(
            url, headers={**self._auth_headers(), "Accept": "text/csv, */*"})
        _check_response(resp)
        return resp.text

    def upload_holdings(self, marketplace_id: int, filename: str) -> list[Holding]:
        """Stage an allocation from a holdings CSV: ``POST allocations`` with
        the file as a ``text/csv`` body, on the same terms as :meth:`allocate`.
        """
        url = _marketplace(self._endpoint, marketplace_id) + "/allocations"
        resp = self._create(lambda key: self._send_csv("POST", url, filename, key))
        allotments = [_parse_allotment(a) for a in _json(resp)]
        return _allotments_to_holdings(allotments)

    def upload_holdings_csv(self, marketplace_id: int, csv: str) -> list[Holding]:
        """Stage an allocation from holdings CSV text, as :meth:`upload_holdings`
        does from a file.

        For a program that builds the CSV rather than reading one -- an admin
        robot writing back the holdings it downloaded and changed -- which
        otherwise writes a file only to have it read again. The text is sent as
        it is; a ``# holdings -- begin`` / ``-- end`` frame around it is
        accepted, as in a downloaded file.
        """
        url = _marketplace(self._endpoint, marketplace_id) + "/allocations"
        content = csv.encode("utf-8")
        resp = self._create(lambda key: self._send_csv_content("POST", url, content, key))
        allotments = [_parse_allotment(a) for a in _json(resp)]
        return _allotments_to_holdings(allotments)

    def upload_state(self, marketplace_id: int, filename: str) -> list[ParticipantState]:
        """Load per-participant private state from a CSV, returning what was stored.

        Staged on the same terms as :meth:`upload_holdings`, against the
        allocation that call staged: it lands when a closed session is opened,
        and the order is a correctness constraint -- holdings, then state, then
        open. With no allocation staged the server refuses.

        The file keys on an ``email`` column. Every other column becomes a
        field of that name: a numeric cell is a number, otherwise text; a
        column whose header is bracketed, ``[valuations]``, holds vectors and
        its cells are JSON arrays. An ``id`` column, when present, must agree
        with the person the email resolves to. A study's existing values file
        needs no change.
        """
        url = _marketplace(self._endpoint, marketplace_id) + "/state"
        resp = self._send_csv("PUT", url, filename)
        return [_parse_participant_state(s) for s in _json(resp)]

    def push_widgets(self, marketplace_id: int, widgets: list[WidgetPush]) -> list[Widget]:
        """Push widgets to participants' screens, returning them as stored.

        One request for the whole list, which is what the server's rate limit
        is shaped for: a session-open that tells sixty traders their values is
        one push of sixty, not sixty pushes. A push to a target and key that
        already has a widget replaces it.

        Not staged, unlike an allocation: it reaches the screen as soon as the
        server stores it, and it is cleared when the session closes.

        The server checks the content and refuses a list with anything wrong
        in it as :class:`~fm.exceptions.InvalidArgumentError`; nothing in the
        list is stored. Pushing faster than the server allows is answered 429,
        which arrives as :class:`~fm.exceptions.HttpError` with that status:
        back off and push again.
        """
        url = _marketplace(self._endpoint, marketplace_id) + "/widgets"
        pushes = [_widget_push_json(w) for w in widgets]
        resp = self._create(lambda key: self._post(url, pushes, key))
        return [_parse_widget(w) for w in _json(resp)]

    def remove_widget(
        self, marketplace_id: int, key: str, user_id: int | None = None,
    ) -> bool:
        """Take down a widget: the marketplace's for ``key``, or one participant's.

        True if one was removed, False if there was none to remove. With
        ``user_id``, only that participant's widget goes; their marketplace
        widget for the same key, if any, shows again.

        An empty 404 is the server saying nothing was there. A 404 carrying a
        failure document -- no such marketplace -- still raises, so a robot
        pointed at the wrong marketplace does not read its clean-up as done.
        """
        url = _marketplace(self._endpoint, marketplace_id)
        if user_id is not None:
            # A participant's widget is theirs, under the participant.
            url += f"/participants/{user_id}"
        url += f"/widgets/{_segment(key)}"
        resp = self._http.delete(url, headers=self._auth_headers())
        if resp.status_code == 404 and not resp.text.strip():
            return False
        _check_response(resp)
        return True

    def all_widgets(self, marketplace_id: int) -> list[Widget]:
        """Every widget pushed to the marketplace and still standing, for every
        participant -- what a manager reads to check on a robot.

        The answer is the frame every widgets read answers, ``{marketplaceId,
        snapshot, widgets, removed}``; a bare list, which ``widgets/all``
        answered and 0.4.0 to 0.11.0 expected here, is read too."""
        url = _marketplace(self._endpoint, marketplace_id) + "/widgets?participant=all"
        body = _json(self._get(url))
        widgets = body if isinstance(body, list) else body.get("widgets") or []
        return [_parse_widget(w) for w in widgets]

    def clear_now(self, marketplace_id: int, market_id: int) -> MarketCall:
        """Clear a call-auction market now: every crossing order trades at one
        price, the way the market's schedule would.

        Only while a session is open, and only in a call market; the server
        refuses either otherwise. The trades arrive as orders on the stream, as
        every trade does.
        """
        # Created where the calls are listed (fm-server 4.17); the market's own route is superseded.
        url = _marketplace(self._endpoint, marketplace_id) + "/calls"
        return _parse_market_call(_json(self._create(lambda key: self._post(url, {"marketId": market_id}, key))))

    def calls(self, marketplace_id: int, session_id: int | None = None) -> list[MarketCall]:
        """Every call the marketplace's call markets have made, oldest first;
        one session's with *session_id*."""
        url = _marketplace(self._endpoint, marketplace_id) + "/calls"
        if session_id is not None:
            url += f"?sessions={session_id}"
        return [_parse_market_call(c) for c in _json(self._get(url))]

    def calls_page(self, marketplace_id: int, limit: int, before: int | None = None) -> Page[MarketCall]:
        """A page of :meth:`calls`: the newest *limit*, or of those whose id is
        below *before* -- a page's ``next``. The server answers at most 5,000.
        """
        return self._page(_marketplace(self._endpoint, marketplace_id) + "/calls", limit, before, _parse_market_call)

    def marketplace_robots(self, marketplace_id: int) -> list[MarketplaceRobot]:
        """Every robot the marketplace's robot host offers, with whether it is
        enabled here and how a manager has configured its parameters.

        Readable by participants as well as managers: it is what a launch form
        is built from. Asks the robot host for its catalogue, so it fails when
        the host cannot be reached.
        """
        url = _marketplace(self._endpoint, marketplace_id) + "/robots"
        return [_parse_marketplace_robot(r) for r in _json(self._get(url))]

    def configure_robot_parameter(
        self, marketplace_id: int, robot_id: str, param_name: str, value: str,
        control: RobotParameterControl | None,
    ) -> RobotParameterConfig:
        """Set a robot parameter's value in a marketplace, and whether
        participants may move it, returning the configuration as stored.

        ``control`` None fixes the value: participants launching the robot see
        it read-only. A control exposes it within that range, starting at
        ``value``, which must lie inside it. The server refuses a parameter the
        robot does not declare, a platform parameter, and a range outside the
        robot's own bounds.

        The control goes up flat beside the value, and a fixed parameter sends
        the value alone: the server reads any bound as a control.
        """
        body: dict[str, Any] = {"value": value}
        if control is not None:
            body.update(min=control.min, max=control.max, step=control.step)
        url = (_marketplace(self._endpoint, marketplace_id)
               + f"/robots/{quote(robot_id, safe='')}/parameters/{quote(param_name, safe='')}")
        return _parse_robot_parameter_config(_json(self._put(url, body)))

    def allotments(self, marketplace_id: int, allocation_id: int) -> list[Allotment]:
        """The opening positions of one allocation.

        An allocation's allotments are its own: ``allocations/{id}/allotments``.
        """
        url = (
            _marketplace(self._endpoint, marketplace_id)
            + f"/allocations/{allocation_id}/allotments"
        )
        return [_parse_allotment(a) for a in _json(self._get(url))]

    def allocate(self, marketplace_id: int, holdings: list[Holding]) -> list[Holding]:
        """Stage the opening positions for the next session.

        Staged, not applied: an allocation lands when a *closed* session is
        opened, and pausing and re-opening does not consume it. Calling this
        against a live session appears to succeed and changes nobody's
        position.

        Takes holdings because that is the shape a caller reads positions in
        and computes with; the allotment encoding the endpoint wants is applied
        here.
        """
        url = _marketplace(self._endpoint, marketplace_id) + "/allocations"
        body = [_holding_to_allotment(marketplace_id, h) for h in holdings]
        resp = self._create(lambda key: self._post(url, body, key))
        return _allotments_to_holdings([_parse_allotment(a) for a in _json(resp)])

    # -- connections -------------------------------------------------------

    def connections(self, marketplace_id: int) -> list[ClientConnection]:
        """Who is attached to the marketplace -- all of them.

        No server-side filter, for the reason :meth:`sessions` gives. A
        connection carries the session it belonged to, so "who was present in
        that run" is a filter on the result.
        """
        url = _marketplace(self._endpoint, marketplace_id) + "/connections"
        return [_parse_connection(c) for c in _json(self._get(url))]

    def connections_page(self, marketplace_id: int, limit: int, before: int | None = None) -> Page[ClientConnection]:
        """A page of :meth:`connections`: the newest *limit*, or of those whose id is
        below *before* -- a page's ``next``. The server answers at most 5,000.
        """
        return self._page(_marketplace(self._endpoint, marketplace_id) + "/connections", limit, before, _parse_connection)

    # -- events / WebSocket ------------------------------------------------

    def listen(self, marketplace_id: int, event_queue: queue.Queue[object]) -> None:
        """Start receiving real-time events via WebSocket STOMP.

        Events are pushed onto *event_queue* as typed objects:
        :class:`~fm.types.Session`, :class:`~fm.types.Holding`,
        :class:`~fm.events.OrdersUpdate`, :class:`~fm.types.Version`,
        :class:`~fm.events.StreamDropped`, or
        :class:`~fm.events.FrameUnreadable`.

        This mirrors the Java ``Flexemarkets.listen()`` method.

        One per connection: a second call replaces the first. For streams that
        coexist, use :meth:`subscribe`.
        """
        # The stream this replaces is closed, not dropped with its socket,
        # heartbeats and reconnects still running.
        if self._event_listener is not None:
            self._event_listener.close()
            self._event_listener = None
        self._event_listener = self._connect_events(marketplace_id, event_queue)

    def subscribe(
        self, marketplace_id: int, event_queue: queue.Queue[object], *, history: bool = False,
    ) -> Callable[[], None]:
        """Open an *independent* event subscription, delivering onto
        *event_queue* until the returned unsubscribe callable is invoked.

        Unlike :meth:`listen`, several of these coexist: each has its own
        stream and its own lifetime. That is what lets more than one
        :class:`~fm.desk.Desk` live in one connection without
        trampling each other -- the mechanism was already here for exactly
        that, as the private ``_connect_events``, but a caller who wanted a
        second stream of their own had no way to ask for one.

        Returns a callable rather than an object with ``close()``, matching
        what ``Desk``'s ``on_*`` handlers already return here. Java
        returns a ``Subscription``; both names describe the same lifetime.

        With *history*, the first :class:`~fm.events.OrdersUpdate` is every
        order of the current session as this caller may see it -- cancelled
        and split ones included, a counterparty's private order named from the
        caller's side -- stamped with the ``seq`` it was read at, and each
        reconnect delivers it again. That is fm-server's original subscribe,
        and the only whole history a participant can read: the REST history is
        a manager's. Without it the first update is empty and the book comes
        from :meth:`active_orders`. A history is one frame, so on a session
        with a very long one it is a large frame -- fm-server's V1 subscribe
        exists to avoid exactly that.
        """
        listener = self._connect_events(marketplace_id, event_queue, history=history)
        return listener.close

    def _connect_events(
        self, marketplace_id: int, event_queue: queue.Queue[object], history: bool = False,
        reconnect_max_seconds: float | None = None,
    ) -> "EventListener":
        """Internal helper used by :class:`~fm.desk.Desk`
        (Phase 2d) to own its own subscription rather than clobbering
        the singleton ``_event_listener``. Lets multiple
        ``desk(marketplace_id)`` calls coexist within one
        Flexemarkets instance without trampling each other's WS
        connections.
        """
        from .events import EventListener

        ws_url = _v1(self._endpoint, "/events").replace("https://", "wss://").replace("http://", "ws://")
        ev = EventListener(
            ws_url=ws_url,
            bearer_token=self._bearer_token,
            marketplace_id=marketplace_id,
            event_queue=event_queue,
            client_description=self._client_description,
            renew=self._renew_token,
            history=history,
            **({} if reconnect_max_seconds is None else {"reconnect_max_seconds": reconnect_max_seconds}),
        )
        ev.start()
        return ev

    def reconnect(self) -> None:
        """Reconnect the WebSocket after a transport error."""
        if self._event_listener is not None:
            self._event_listener.reconnect()

    def desk(self, marketplace_id: int, *, refuse_locally: bool = True) -> "Desk":
        """Open a stateful :class:`~fm.desk.Desk` on this
        marketplace.

        ``submit_limit`` on the returned handle refuses, without sending, an
        order the desk knows fm-server would refuse -- a session that is not
        open, a side the holding does not grant -- with
        :class:`~fm.exceptions.OrderRefusedLocallyError`. ``refuse_locally=False``
        sends every order and leaves the answer to the server.

        Multiple calls for the same ``marketplace_id`` share a single
        underlying desk + WS subscription + materialized state within
        this Flexemarkets instance — each call returns a fresh handle,
        the handles refcount, and the shared resources tear down on
        the last close.

        Sharing is intentionally per-Flexemarkets (i.e. per-bearer).
        Two callers with different identities each get their own desk
        — multi-tenant WS multiplexing is a server-side concern, not
        a client-side one.
        """
        from .desk import Desk, DeskHandle

        with self._view_lock:
            entry = self._shared_views.get(marketplace_id)
            if entry is None:
                desk = Desk(self, marketplace_id, self.markets(marketplace_id))
                entry = _SharedView(desk=desk, ref_count=0)
                self._shared_views[marketplace_id] = entry
            entry.ref_count += 1
            shared = entry.desk
        return DeskHandle(
            shared, lambda: self._release_shared_view(marketplace_id), refuse_locally
        )

    def _release_shared_view(self, marketplace_id: int) -> None:
        with self._view_lock:
            entry = self._shared_views.get(marketplace_id)
            if entry is None:
                return
            entry.ref_count -= 1
            if entry.ref_count <= 0:
                self._shared_views.pop(marketplace_id, None)
                to_close: Optional["Desk"] = entry.desk
            else:
                to_close = None
        if to_close is not None:
            to_close.close()

    # -- lifecycle ---------------------------------------------------------

    def close(self) -> None:
        # Before the listeners: an order still unanswered is told so, rather
        # than waiting on a socket that has gone.
        with getattr(self, "_order_stream_lock", threading.Lock()):
            streams, self._order_streams = list(getattr(self, "_order_streams", {}).values()), {}
        for stream in streams:
            stream.close()
        if hasattr(self, "_event_listener") and self._event_listener is not None:
            self._event_listener.close()
            self._event_listener = None
        # Force-close any remaining shared Desks — safety net for
        # callers who didn't close their handles first.
        with self._view_lock:
            desks = [entry.desk for entry in self._shared_views.values()]
            self._shared_views.clear()
        for v in desks:
            v.close()
        self._http.close()

    def __enter__(self) -> Flexemarkets:
        return self

    def __exit__(self, *args: object) -> None:
        self.close()


# ---------------------------------------------------------------------------
# Allotment → Holding conversion
# ---------------------------------------------------------------------------

def _holding_to_allotment(marketplace_id: int, holding: Holding) -> dict[str, Any]:
    """Encode a holding as the allotment the /allocations endpoint reads.

    The positions go out as ``grants``. That is the server's own field name,
    and it is the one thing here that fails silently: send ``securities`` and
    the server finds no grants, creates the allocation with the cash and no
    positions, and answers 200 -- an experiment whose participants hold
    nothing. ``_parse_allotment`` accepts either spelling coming back.
    """
    return {
        "marketplaceId": marketplace_id,
        "ownerId": holding.owner_id,
        "name": holding.name,
        "assets": {
            "name": holding.name,
            "cash": holding.cash,
            "grants": [
                {
                    "marketId": s.market_id,
                    "units": s.units,
                    "availableUnits": s.available_units,
                    "shortUnits": s.short_units,
                    "canBuy": s.can_buy,
                    "canSell": s.can_sell,
                }
                for s in holding.securities
            ],
        },
    }


def _allotments_to_holdings(allotments: list[Allotment]) -> list[Holding]:
    holdings: list[Holding] = []
    for a in allotments:
        cash = a.assets.cash if a.assets else 0
        securities = list(a.assets.securities) if a.assets else []
        holdings.append(Holding(
            allocation_id=a.allocation_id or 0,
            cash=cash,
            available_cash=cash,
            marketplace_id=a.marketplace_id or 0,
            name=a.name,
            owner_id=a.owner_id or 0,
            session_id=0,
            securities=securities,
        ))
    return holdings
