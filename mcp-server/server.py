"""MCP server exposing Flexemarkets API as tools."""

from __future__ import annotations

import os
import sys

from mcp.server.fastmcp import FastMCP

from fm import Decimals, Flexemarkets, Market, Marketplace, Order, Session
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


# Every amount is shown at its own scale -- a price at its market's
# priceDecimals, units at its unitDecimals, cash at the marketplace's
# cashDecimals -- and goes through Decimals, so none of them divides by hand.
# A market the marketplace does not list is shown at the server's defaults.
_DEFAULT_MARKET = Market()


class _Scales:
    """The scales of one marketplace, read once per tool call."""

    def __init__(self, marketplace: Marketplace):
        self.cash_decimals = marketplace.cash_decimals
        self._markets = {m.id: m for m in marketplace.markets}

    def market(self, market_id: int) -> Market:
        return self._markets.get(market_id, _DEFAULT_MARKET)


def _scales(fm: Flexemarkets, marketplace_id: int) -> _Scales:
    return _Scales(fm.marketplace(marketplace_id))


def _price(price: int, market: Market) -> str:
    return Decimals.format(price, market.price_decimals)


def _units(units: int, market: Market) -> str:
    return Decimals.format(units, market.unit_decimals)


def _cash(cash: int, scales: _Scales) -> str:
    return Decimals.format(cash, scales.cash_decimals)


def _format_order(o: Order, scales: _Scales) -> str:
    side = o.side or "?"
    market = scales.market(o.market_id)
    return (
        f"  {o.type} {side} {_units(o.units, market)}u @ {_price(o.price, market)}"
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
        f"Cash decimals: {mp.cash_decimals}",
        "",
        "Markets:",
    ]
    for m in mp.markets:
        lines.append(
            f"  • {m.symbol} — {m.name} (id={m.id})"
            f"  price: {_price(m.price_minimum, m)}–{_price(m.price_maximum, m)}"
            f"  tick: {_price(m.price_tick, m)}"
            f"  units: {_units(m.unit_minimum, m)}–{_units(m.unit_maximum, m)}"
            f"  tick: {_units(m.unit_tick, m)}"
            f"  (priceDecimals={m.price_decimals}, unitDecimals={m.unit_decimals})"
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
    scales = _scales(fm, marketplace_id)
    lines = [
        f"Holding for {h.name or 'user'} in marketplace {h.marketplace_id}",
        f"  Cash: {_cash(h.cash, scales)}  (available: {_cash(h.available_cash, scales)})",
        "",
        "  Securities:",
    ]
    for s in sorted(h.securities, key=lambda s: s.market_id):
        market = scales.market(s.market_id)
        lines.append(
            f"    Market {s.market_id}: {_units(s.units, market)} units"
            f" (available: {_units(s.available_units, market)})"
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
    scales = _scales(fm, marketplace_id)
    lines = [f"Orders in marketplace {marketplace_id}:"]
    for o in orders:
        lines.append(_format_order(o, scales))
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

    scales = _scales(fm, marketplace_id)
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
                market = scales.market(resting.market_id)
                trades.append(
                    f"  {_price(resting.price, market)}  {_units(resting.units, market)}u"
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
        units: Units to trade, as an integer count of the market's smallest
            unit step: at unitDecimals 0 (the default) a count of whole units.
        price: The limit price, as an integer count of the market's smallest
            price step: at priceDecimals 2 (the default) 5000 is 50.00.
            get_marketplace shows each market's priceDecimals and unitDecimals.
    """
    fm = _get_fm()
    order = fm.submit_limit(marketplace_id, market_id, side.upper(), units, price)
    market = _scales(fm, marketplace_id).market(order.market_id)
    return (
        f"Order submitted successfully.\n"
        f"  ID: {order.id}\n"
        f"  {order.type} {order.side} {_units(order.units, market)}u @ {_price(order.price, market)}\n"
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
