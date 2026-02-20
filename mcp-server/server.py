"""MCP server exposing Flexemarkets API as tools."""

from __future__ import annotations

import os
import sys

from mcp.server.fastmcp import FastMCP

from fm import Flexemarkets, Order, Session
from fm.order_utils import is_buy, is_consumed, is_limit

mcp = FastMCP("Flexemarkets")

# ---------------------------------------------------------------------------
# Connection — created once at startup
# ---------------------------------------------------------------------------

_fm: Flexemarkets | None = None


def _get_fm() -> Flexemarkets:
    global _fm
    if _fm is None:
        credential = os.environ.get("FM_CREDENTIAL")
        endpoint = os.environ.get("FM_ENDPOINT")
        _fm = Flexemarkets.connect(credential, endpoint, "fm-mcp")
    return _fm


# ---------------------------------------------------------------------------
# Formatting helpers
# ---------------------------------------------------------------------------


def _price(cents: int) -> str:
    return f"${cents / 100:.2f}"


def _format_order(o: Order) -> str:
    side = o.side or "?"
    return (
        f"  {o.type} {side} {o.units}u @ {_price(o.price)}"
        f"  (id={o.id}, symbol={o.symbol or '?'})"
    )


def _format_session(s: Session) -> str:
    lines = [
        f"Session {s.id} — {s.state}",
        f"  Name: {s.name or '(none)'}",
        f"  Marketplace: {s.marketplace_id}",
    ]
    if s.open_date:
        lines.append(f"  Opened: {s.open_date}")
    if s.close_date:
        lines.append(f"  Closed: {s.close_date}")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Read-only tools
# ---------------------------------------------------------------------------


@mcp.tool()
def list_marketplaces() -> str:
    """List all available marketplaces."""
    fm = _get_fm()
    mps = fm.marketplaces()
    if not mps:
        return "No marketplaces found."
    lines = []
    for mp in mps:
        markets_desc = ", ".join(
            m.symbol or m.name or str(m.id) for m in (mp.markets or [])
        )
        lines.append(f"• {mp.name} (id={mp.id}) — {mp.description or ''}")
        if markets_desc:
            lines.append(f"  Markets: {markets_desc}")
    return "\n".join(lines)


@mcp.tool()
def get_marketplace(marketplace_id: int) -> str:
    """Get a single marketplace with its markets.

    Args:
        marketplace_id: The marketplace ID to look up.
    """
    fm = _get_fm()
    mp = fm.marketplace(marketplace_id)
    lines = [
        f"{mp.name} (id={mp.id})",
        f"Description: {mp.description or '(none)'}",
        "",
        "Markets:",
    ]
    for m in mp.markets:
        lines.append(
            f"  • {m.symbol} — {m.name} (id={m.id})"
            f"  price: {_price(m.price_minimum)}–{_price(m.price_maximum)}"
            f"  tick: {_price(m.price_tick)}"
        )
    return "\n".join(lines)


@mcp.tool()
def get_session(marketplace_id: int) -> str:
    """Get the current trading session for a marketplace.

    Args:
        marketplace_id: The marketplace ID.
    """
    fm = _get_fm()
    s = fm.session(marketplace_id)
    return _format_session(s)


@mcp.tool()
def get_holding(marketplace_id: int) -> str:
    """Get the current user's holding (portfolio) in a marketplace.

    Args:
        marketplace_id: The marketplace ID.
    """
    fm = _get_fm()
    h = fm.holding(marketplace_id, fm.user_id)
    lines = [
        f"Holding for {h.name or 'user'} in marketplace {h.marketplace_id}",
        f"  Cash: {_price(h.cash)}  (available: {_price(h.available_cash)})",
        "",
        "  Securities:",
    ]
    for s in sorted(h.securities, key=lambda s: s.market_id):
        lines.append(
            f"    Market {s.market_id}: {s.units} units"
            f" (available: {s.available_units})"
            f"  buy={'yes' if s.can_buy else 'no'}"
            f"  sell={'yes' if s.can_sell else 'no'}"
        )
    return "\n".join(lines)


@mcp.tool()
def list_orders(marketplace_id: int, symbol: str | None = None) -> str:
    """List orders in a marketplace, optionally filtered by symbol.

    Args:
        marketplace_id: The marketplace ID.
        symbol: Optional symbol to filter orders (e.g. "AAPL").
    """
    fm = _get_fm()
    orders = fm.orders(marketplace_id, symbol=symbol)
    if not orders:
        return "No orders found."
    lines = [f"Orders in marketplace {marketplace_id}:"]
    for o in orders:
        lines.append(_format_order(o))
    return "\n".join(lines)


@mcp.tool()
def get_trades(marketplace_id: int, symbol: str) -> str:
    """Get trade history for a symbol in a marketplace.

    Args:
        marketplace_id: The marketplace ID.
        symbol: The market symbol (e.g. "AAPL").
    """
    fm = _get_fm()
    orders = fm.trades(marketplace_id, symbol)
    if not orders:
        return f"No trades found for {symbol}."

    # Pair consumed orders into trades
    trades: list[str] = []
    consumers: dict[int, Order] = {}
    for o in orders:
        if is_limit(o) and is_consumed(o):
            consumers[o.id] = o
            pair = consumers.get(o.consumer)  # type: ignore[arg-type]
            if pair is not None:
                resting = o if o.original < pair.original else pair
                aggressor = pair if resting is o else o
                buyer = resting if is_buy(resting.side or "") else aggressor
                trades.append(
                    f"  {_price(resting.price)}  {resting.units}u"
                    f"  (buyer={buyer.owner_id})"
                )

    if not trades:
        return f"No completed trades for {symbol}."
    lines = [f"Recent trades for {symbol}:"]
    lines.extend(trades[-20:])  # last 20
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Trading tools
# ---------------------------------------------------------------------------


@mcp.tool()
def submit_limit_order(
    marketplace_id: int,
    market_id: int,
    side: str,
    units: int,
    price: int,
) -> str:
    """Submit a limit order.

    Args:
        marketplace_id: The marketplace ID.
        market_id: The market ID.
        side: Order side — "BUY" or "SELL".
        units: Number of units to trade.
        price: Price in cents (e.g. 5000 = $50.00).
    """
    fm = _get_fm()
    order = fm.submit_limit(marketplace_id, market_id, side.upper(), units, price)
    return (
        f"Order submitted successfully.\n"
        f"  ID: {order.id}\n"
        f"  {order.type} {order.side} {order.units}u @ {_price(order.price)}\n"
        f"  Symbol: {order.symbol or '?'}, Market: {order.market_id}"
    )


@mcp.tool()
def cancel_order(
    marketplace_id: int,
    market_id: int,
    original_id: int,
) -> str:
    """Cancel a resting order.

    Args:
        marketplace_id: The marketplace ID.
        market_id: The market ID.
        original_id: The original order ID to cancel.
    """
    fm = _get_fm()
    order = fm.submit_cancel(marketplace_id, market_id, original_id)
    return (
        f"Cancel submitted successfully.\n"
        f"  ID: {order.id}\n"
        f"  Cancelling original order: {original_id}"
    )


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> None:
    mcp.run()


if __name__ == "__main__":
    main()
