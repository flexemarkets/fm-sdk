# Upgrading from fm-sdk 0.4.x to 0.5.x

## How to use this document

Read *What you need* and *What was removed*. 0.5.0 removes a handful of
per-market shortcuts from the **book and tape indexes** -- the containers a
Desk keeps its books and tapes in -- and nothing a Desk or a `Flexemarkets`
client offers. If you read books and tapes through a Desk, as every example
in this repository does, nothing you call is gone. The rest is additions and
fixes, listed so a changed behaviour is not a surprise.

What 0.4.0 settled is in [UPGRADING-0.4.md](UPGRADING-0.4.md) and is not
revisited here.

---

## What you need

**fm-server 4.6.2 or later** -- the same as 0.4.0. The two new robot calls
use routes fm-server has served since 4.1.0; reading the catalogue asks the
robot host behind fm-server, so it fails while that host is down.

---

## What was removed, and what to call instead

All three SDKs drop the same methods (they are held to one surface by
`make check-parity`). None had a caller in any SDK, in fm-robots or in
fm-server: the Desk reaches a market's Book or Tape and reads it there, and
the `Book` and `Tape` methods those shortcuts forwarded to all remain.

| Removed | Java | Python | TypeScript | Instead |
|---|---|---|---|---|
| a market's top-of-book, from the book index | `BookIndex.hasValue(marketId, side)` | `BookIndex.has_value(market_id, side)` | `BookIndex.hasValue(marketId, side)` | the market's book: `desk.book(marketId).hasValue(side)` / `desk.book(market_id).has_value(side)` |
| | `BookIndex.bestPrice(marketId, side)` | `BookIndex.best_price(market_id, side)` | `BookIndex.bestPrice(marketId, side)` | `desk.book(marketId).bestPrice(side)` / `.best_price(side)` -- or `best_buy_price()` / `best_sell_price()` |
| every market's recent trade prices at once | `TapeIndex.mostRecentPrices()` | `TapeIndex.most_recent_prices()` | `TapeIndex.mostRecentPrices()` | per market: `desk.tape(marketId).mostRecentPrices()` / `.most_recent_prices()`, over `desk.tapes()` for all of them |
| indexing an index | -- | `BookIndex.__getitem__` (`books[market_id]`), `TapeIndex.__getitem__` (`tapes[market_id]`) | -- | `books.get(market_id)` / `tapes.get(market_id)` -- or `desk.book(market_id)` / `desk.tape(market_id)` |

**One difference to mind in Python.** `books[market_id]` raised `KeyError`
for a market the marketplace does not have; `get` and `desk.book` answer
`None`. Code that relied on the exception to notice a wrong market id must
now check for `None`.

**In Java the indexes were never public API.** `BookIndex` and `TapeIndex`
live in `fm.internal`, hidden on the module path and only visible on the
classpath (fm-sdk's `fm.internal` is classpath-visible; see the module
notes). If you reached into them anyway, the replacements above are the
supported way.

Also gone, none of them yours to call:

- Java: the event stream's own `onPing(WebSocket, ByteBuffer)` handler in
  `fm.internal`, removed because it answered every server ping twice (see
  below).
- Python: the private helpers `_to_camel`, `_to_snake` and
  `_is_created_earlier`, which nothing called.

---

## What is new

**Reading and configuring a marketplace's robots** (all three SDKs):

| | Read the catalogue | Fix or expose a parameter |
|---|---|---|
| Java | `marketplaceRobots(marketplaceId)` | `configureRobotParameter(marketplaceId, robotId, paramName, value, control)` |
| Python | `marketplace_robots(marketplace_id)` | `configure_robot_parameter(marketplace_id, robot_id, param_name, value, control)` |
| TypeScript | `marketplaceRobots(marketplaceId)` | `configureRobotParameter(marketplaceId, robotId, paramName, value, control)` |

With new types `MarketplaceRobot`, `RobotManifest`, `RobotParameter`,
`RobotParameters`, `RobotParameterConfig` and `RobotParameterControl`. A
manager fixes a parameter (a value; `control` null) or exposes it to
participants within a range. See API-REFERENCE.md, *Robots*.

**A client made with credentials renews its own sign-in** (all three). A
token lives 24 hours; when a call is refused as `ACCOUNT_TOKEN_EXPIRED`, the
client signs in again once and resends, and the event stream renews on a
refused reconnect. A client given a token rather than credentials has
nothing to sign in with, and still gets the 401.

**Each call reports what the last one cost** (all three). The previous
round trip rides on the next request as `Client-Timing: rtt=<ns>`, with
`;net=<ns>` when the server's `Server-Timing` said its share, so fm-server's
Connections view shows a robot's latency. Nothing to do; the figure is one
call late by design.

**Java asks for compressed responses** (`Accept-Encoding: gzip`) and
decodes them. Nothing to do.

---

## What answers differently

Fixes that change what a call does, in the order a robot would notice them:

- **A second `listen()` closes the stream it replaces** (all three). Before,
  the first stream's socket, heartbeat and reconnects kept running and kept
  delivering onto the old queue -- a robot that re-listened after an error
  left a second connection open for the life of the process.
- **`isResting` / `is_resting` picks one side when neither original order is
  in the batch** (all three). Each side of one match could call itself the
  older, so both read as resting and a Tape recorded the match as two
  trades.
- **Java answers a WebSocket ping once**, not twice, and **a reconnect you
  ask for no longer reports a drop and reconnects twice**.
- **Java: a bare marketplace id honours `FM_URL`**, as the other SDKs did --
  `-E 3343` goes to the server `FM_URL` names, not to production. (Before,
  a bare id always meant production.)
- **TypeScript: closing a listener while it is still connecting no longer
  crashes the process.** And **the desk now recognises a session update**:
  before, `session()` stayed null for the desk's whole life and
  `onSessionChange` never fired.
- **Python: a closed desk's `books()` raises**, as its other reads already
  did, rather than answering the books it had when it closed.
- **A desk refuses locally what fm-server would refuse** (all three): a
  limit order while the session the desk holds is not open -- after
  reading the session once more, in case the server opened before the
  event arrived -- or on a side the holding does not grant. It raises
  `OrderRefusedLocallyException` (Java) / `OrderRefusedLocallyError`
  (Python, TypeScript), an invalid-argument error, so code that catches
  those catches this too, and no request is sent. To send every order and
  leave the answer to the server: `desk(id, Desk.OrderChecks.SERVER_ONLY)`
  (Java), `desk(id, refuse_locally=False)` or per order
  `submit_limit(..., refuse_locally=False)` (Python),
  `desk(id, { refuseLocally: false })` (TypeScript).
- **Python: a reconnect you ask for no longer reports a drop**, as Java's
  already did not.
- **"view" is back where the 0.4 View -> Desk rename took the English
  word** in the documentation and comments. No API changed.

---

## Checking your code

```bash
# Java
grep -rnE "\.(hasValue|bestPrice)\([^,)]+, |mostRecentPrices\(\)" src/ | grep -i index
# Python
grep -rnE "\.(has_value|best_price)\([^,)]+, |most_recent_prices\(\)|books\[|tapes\[" .
# TypeScript
grep -rnE "\.(hasValue|bestPrice)\([^,)]+, |mostRecentPrices\(\)" src/ | grep -i index
```

A hit on a two-argument `hasValue`/`bestPrice` (market id and side) is the
index form; the one-argument form on a `Book` is unchanged.
