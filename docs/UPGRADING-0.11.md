# Upgrading from fm-sdk 0.10.x to 0.11.x

## How to use this document

0.11.0 adds call-auction markets to all three SDKs (fm-server#1036). A
market's `mechanism` says whether it trades continuously or as a call
auction, an administrator can add a call market, a manager can clear one
and read its calls, and a study can ask for one (study contract 0.4, fm-spi 0.0.18). Code that only
**calls** the SDK needs nothing to keep working. Code that reads markets
should still read [What changes in behaviour](#what-changes-in-behaviour):
from fm-server 4.15.0 on, a market is not necessarily continuous.

Code that **implements** or **builds** the SDK's types (test doubles and
fixtures, mostly) may need changes:

| | What breaks | Fix |
|---|---|---|
| Java | a class implementing `Flexemarkets`: four new abstract methods, `createCallMarket(marketplaceId, symbol, name, price, units, Integer intervalSeconds, boolean carry)`, `clearNow(marketplaceId, marketId)`, `calls(marketplaceId)` and `calls(marketplaceId, sessionId)`. One implementing only `Administration` gains `createCallMarket`; only `Management`, the other three | implement them; a fake that does not clear can throw |
| Java | record patterns over `Market`: it gains three last components, `String mechanism`, `Integer callIntervalSeconds` and `Boolean callCarry` | add them to the pattern; the twelve-argument constructor is kept and makes a continuous market, so `new Market(...)` compiles unchanged |
| Java | record patterns over fm-spi's `MarketSpec`: it gains `String mechanism`, `Integer callIntervalSeconds` and `boolean callCarry` | add them to the pattern; the nine-argument constructor is kept and makes a continuous market |
| TypeScript | an object literal typed `Market`: three new required fields, `mechanism: string`, `callIntervalSeconds: number \| null` and `callCarry: boolean` | add `mechanism: "CONTINUOUS"` (or `MARKET_MECHANISM_CONTINUOUS`), `callIntervalSeconds: null`, `callCarry: true` |
| Python | nothing: `mechanism`, `call_interval_seconds` and `call_carry` default to `"CONTINUOUS"`, `None` and `True`, after every existing field | |

Earlier releases are covered by their own upgrade notes, listed in the
[README](../README.md#upgrade-notes), and are not revisited here.

---

## What changes in behaviour

**A market may be a call auction.** fm-server 4.15.0 runs a market either
continuously, where an order trades the moment it crosses, or as a call
auction: orders collect, crossed or not, and a call clears them all at one
price. `createMarket` still makes only continuous markets; `createCallMarket`
makes a call auction. Code that assumed every market is continuous breaks in two
ways in a call market:

- a crossing order **rests** until the next call, where code built for a
  continuous market expects it to trade at once. A manager, who sees the
  whole book, can see a bid at or above an ask, and that is normal;
- the book is **sealed**. A participant sees their own resting orders and
  the trades, not anyone else's resting orders, so for a participant a best
  bid or ask worked out from the orders on the stream is only their own. A
  manager sees every order.

Check the market before treating it as continuous: `market.isCall()` in
Java, `market.is_call()` in Python, `isCall(market)` in TypeScript.

A call's trades arrive as orders in an `ORDERS-UPDATE`, as every trade
does. The server also sends a `CALL-UPDATE` frame for each call, which the
0.11.0 streams do not parse: Java and TypeScript drop it, and Python logs
`Unknown message-type: CALL-UPDATE` at WARNING. To see the calls
themselves, read them with `calls` (below).

**Java writes three more fields when it serialises a `Market`**, namely
`mechanism`, `callIntervalSeconds` and `callCarry`. `isCall` is not written.

`createMarket` sends the same body as before, and the markets it makes are
continuous.

---

## What is new

**`Market` says how it trades**, in three fields, which every SDK fills
when a server leaves them out, as servers before fm-server 4.15.0 do:

| Field (Java, TypeScript / Python) | Meaning | When the server does not say |
|---|---|---|
| `mechanism` | `"CONTINUOUS"` or `"CALL"`: `Market.MECHANISM_CONTINUOUS` and `Market.MECHANISM_CALL` in Java and Python, `MARKET_MECHANISM_CONTINUOUS` and `MARKET_MECHANISM_CALL` in TypeScript | `"CONTINUOUS"` |
| `callIntervalSeconds` / `call_interval_seconds` | for a call market, the seconds between calls while a session is open; null when only a manager's Clear now calls it | null |
| `callCarry` / `call_carry` | for a call market, whether what a call leaves unfilled waits for the next call rather than being cancelled | `true` |

In Java the canonical constructor applies these defaults as well, so a
`Market` built with a null `mechanism` or `callCarry` reads as continuous
and carrying.

**Call markets can be added, cleared and read.** An administrator can add
one; a manager can clear one and read its calls:

| | Add a call market | Clear now | The calls |
|---|---|---|---|
| Java | `createCallMarket(marketplaceId, symbol, name, price, units, Integer intervalSeconds, boolean carry)` | `MarketCall clearNow(marketplaceId, marketId)` | `List<MarketCall> calls(marketplaceId)`, and `calls(marketplaceId, sessionId)` |
| Python | `create_call_market(marketplace_id, symbol, name, price, units=None, interval_seconds=None, carry=True)` | `clear_now(marketplace_id, market_id)` | `calls(marketplace_id, session_id=None)` |
| TypeScript | `createCallMarket(marketplaceId, symbol, name, price, units?, intervalSeconds?, carry?)` | `clearNow(marketplaceId, marketId)` | `calls(marketplaceId, sessionId?)` |

`createCallMarket` has no private flag, because a call market cannot be
private and the server refuses the combination. A null `intervalSeconds`
makes a market that is called only by `clearNow`. `clearNow` posts to
`POST .../markets/{marketId}/calls` and works only while a session is open
and only on a call market; the server refuses it otherwise. `calls` reads
`GET .../calls`, oldest first, with `?sessionId=` for one session's.

Each call is a **`MarketCall`** with these fields: `id`, `marketplaceId`,
`marketId`, `sessionId`, `calledDate`, `trigger` (`MANUAL` for Clear now,
`INTERVAL` for the schedule), `price` (null when the book did not cross),
`volume`, `demand`, `supply`, `buyOrders`, `sellOrders` and `carry`. Python
spells them in snake case (`called_date`, `buy_orders`, and so on). The
trigger constants are `MarketCall.TRIGGER_MANUAL` and
`MarketCall.TRIGGER_INTERVAL` in Java and Python, and
`MARKET_CALL_TRIGGER_MANUAL` and `MARKET_CALL_TRIGGER_INTERVAL` in
TypeScript.

**A study's market may be a call auction** (fm-spi 0.0.18, study contract
0.4). `MarketSpec` carries `mechanism` (`MarketSpec.CONTINUOUS` or
`MarketSpec.CALL`), `callIntervalSeconds` and `callCarry`.
`asCall(intervalSeconds, carry)` returns a copy that is a call market, in the
same way that `asPrivate()` returns a private one:

```java
MarketSpec batch = MarketSpec.of("BAT", "Batch").asCall(5, false);
```

A spec with no mechanism is continuous, and `asPrivate()` always returns a
continuous market, because a call market cannot be private.
`StudyProvider.STUDY_SPI_VERSION` is now `"0.4"`, and the default
`studySpiVersion()` returns it.

For a study SPI implementer, a study that keeps continuous markets needs no
change. Its nine-argument `MarketSpec` constructions still compile, and
rebuilt against fm-spi 0.0.18 it reports contract 0.4 through the default
`studySpiVersion()`. To ask for a call market, call `.asCall(...)` on the
spec. Only record patterns over `MarketSpec` need the new components (see
the table above). A study that calls `asCall` needs a host on fm-spi 0.0.18,
fm-server 4.15.0 or later; on an older host it fails with
`NoSuchMethodError`. And a plan that asks for a call market is refused whole
when a manager sets it up -- nothing is created -- because only an
administrator can make a call market (below).

---

## What you need

Everything 0.10.0 did works against the same servers as before. A market
read from a server older than fm-server 4.15.0 reads as continuous.

Call auctions need **fm-server 4.15.0 or later**. That release added the
mechanism fields, `POST .../markets/{marketId}/calls` and `GET .../calls`.
**Only an administrator** can make a market a call auction, whether through
`createCallMarket` or a study's plan, and a manager's request is refused;
that holds from 4.15.0 through every release since. A manager can still clear
and read the call markets an administrator made.

Against fm-server 4.17.0 or later, 0.11.0 also meets what it does not know:
an indicative call market's `CALL-INDICATIVE` frames, which Java and
TypeScript drop and Python logs as `Unknown message-type`, as they do
`CALL-UPDATE`, and a market's `callBook` field, which it ignores.

---

## Checking your code

```bash
# Java: implementations of the client, and record patterns
grep -rnE "implements (Flexemarkets|Administration|Management)\b" src/
grep -rnE "(Market|MarketSpec)\(" src/ | grep -E "instanceof|case "
# TypeScript: literals typed as a Market
grep -rnE ": Market\b|as Market\b|<Market>" src/
```
