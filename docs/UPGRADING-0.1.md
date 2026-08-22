# Upgrading from fm-sdk 0.0.x to 0.1.x

This document is written to be executed, not just read. It is aimed at an agent
or engineer migrating a codebase that depends on fm-sdk 0.0.12 or earlier.

Every change below is mechanical unless it appears under
[Changes that are not renames](#changes-that-are-not-renames). Those alter
behaviour, and applying them blindly will produce code that compiles and is
wrong. Read that section before starting.

## How to use this document

1. Work one language at a time. A codebase using two SDKs migrates twice.
2. For each row in the tables, search for the **Find** pattern and apply
   **Replace**. The patterns are chosen to be unambiguous; where one is not,
   the row says so in **Notes**.
3. Apply [Changes that are not renames](#changes-that-are-not-renames) by
   reading each call site, not by pattern.
4. Run the [verification](#verification) steps. Do not report the migration
   complete on a green compile alone — several of these changes compile fine
   and behave differently.

**Do not** add compatibility shims, aliases, or wrappers that restore the old
names. 0.1.0 exists to remove accreted API, and re-adding it under another name
reintroduces exactly what was removed.

## Why 0.1.0 breaks

0.0.x grew by accretion. Half the client interface was optional-at-runtime,
timestamps were strings each caller parsed differently, side and order type
were strings with constants beside them, and the same tick-rounding rule
existed in four places with two of them wrong. The version is `0.1.0` rather
than `0.0.13` because under semver's 0.x convention a minor bump is what
signals breakage, and the 0.0.x line had been additive throughout.

---

## Java

### Imports: types and exceptions moved out of holder classes

Seventeen records lived inside `public class Types` and seven exceptions inside
`public class Exceptions`. Both wrappers are gone; every type is top-level in
package `fm`.

| Find | Replace | Notes |
|---|---|---|
| `import fm.Types.X;` | `import fm.X;` | any of the 17 record types |
| `import fm.Types;` | *(delete)* | |
| `Types.X` | `X` | qualified references in code |
| `import fm.Exceptions.Y;` | `import fm.Y;` | any of the 7 exception types |
| `import fm.Exceptions;` | *(delete)* | |
| `Exceptions.Y` | `Y` | |

Types: `ApiRoot`, `Person`, `Account`, `Token`, `Session`, `Marketplace`,
`Market`, `Order`, `Holding`, `Security`, `Allotment`, `Assets`,
`ClientConnection`, `Version`, `ManagerOtpBundle`, `Approval`,
`ConflictFailure`.

Exceptions: `FlexemarketsException`, `AuthenticationException`,
`HttpException`, `ConflictException`, `AccountNameConflictException`,
`PersonHasMarketplaceDataException`, `ApiException`.

`ApiRoot.LinkObject` and `ManagerOtpBundle.Entry` remain nested — they are
parts of their enclosing type. Reference them as `ApiRoot.LinkObject`.

Three event records left the STOMP client, which was never meant to be API:

| Find | Replace |
|---|---|
| `import fm.Events.WsException;` | `import fm.WsException;` |
| `import fm.Events.WsTransportError;` | `import fm.WsTransportError;` |
| `import fm.Events.Reconnected;` | `import fm.Reconnected;` |
| `Events.WsException` | `WsException` |

### Types that moved to `fm.internal`

Five public types no caller could reach — nothing on `Flexemarkets`, the roles
or `MarketView` returned or accepted any of them:

| type | was |
|---|---|
| `ApiRoot`, `ApiRoot.LinkObject` | HAL plumbing, read once at connect |
| `Approval` | `approveAccount` returns `Account`, not this |
| `Version` | the WebSocket protocol handshake |
| `MarketplaceTrades`, `OrderBooks` | aggregators behind `MarketView` |

If you import one, you were reaching past the contract. `MarketView` is the
supported way to a maintained order book.

### The implementation is no longer importable

`fm.HttpFlexemarkets` and `fm.Events` moved to `fm.internal`, along with
`DefaultMarketView` and `MarketViewHandle`. They were public by accident of
sharing a package with the interface they implement — `HttpFlexemarkets` was a
public class nobody could construct.

Nothing should be importing them. If something does, it is reaching past the
contract: `Flexemarkets.connect` builds the client and `MarketView.over` builds
the view, and those are the supported ways to get one. Do not import from
`fm.internal` to restore the old code — that is the same reach with a longer
name.

`Endpoints.DEFAULT_HOST` is now public, if what you wanted was the default host.

### Renames

| Find | Replace |
|---|---|
| `.activeOrdersV1(` | `.activeOrders(` |
| `.recentTradesV1(` | `.recentTrades(` |
| `.account(someId)` | `.accountById(someId)` |
| `.user(someId)` | `.userById(someId)` |
| `.getSecurity(` | `.security(` |
| `.getSecurities()` | `.securities()` |

`createMarket` takes the unit grid it used to hardcode:

```java
// before — unit bounds fixed at 1/100/1, with no way to say otherwise
fm.createMarket(5, "STK", "Stock", 0, 10_000, 1, false);

// after — both dimensions, named so they cannot be transposed
fm.createMarket(5, "STK", "Stock",
                new TickGrid(0, 10_000, 1), TickGrid.units(), false);
```

`TickGrid.units()` is the old default. Python and TypeScript default the unit
grid when it is omitted; Java requires both, having no default arguments.

**`account()` and `user()` with no argument are unchanged** — they answer who
the connection is signed in as. Only the single-argument forms were renamed, so
a blind replace of `.account(` is wrong. Match on the argument.

### Side and order type are enums

| Find | Replace |
|---|---|
| `Order.SIDE_BUY` | `Side.BUY` |
| `Order.SIDE_SELL` | `Side.SELL` |
| `Order.TYPE_LIMIT` | `OrderType.LIMIT` |
| `Order.TYPE_CANCEL` | `OrderType.CANCEL` |
| `OrderUtils.contra(side)` | `side.contra()` |
| `OrderUtils.isBuy(sideString)` | `Side.BUY == side` |
| `OrderUtils.isBuy(order)` | `Side.BUY == order.side()` |
| `OrderUtils.isSell(order)` | `Side.SELL == order.side()` |
| `OrderUtils.isCancel(order)` | `OrderType.CANCEL == order.type()` |
| `OrderUtils.isLimit(order)` | `OrderType.LIMIT == order.type()` |

`OrderUtils` keeps what needs a set of orders to answer — `isAvailable`,
`isConsumed`, `isSplit`, `isResting`, `isSubmit`, `isSymbol`, `findOrder`. The
four above became one-line enum comparisons when side and type became types, and
two ways to ask one question is one too many.

`submitLimit`, `submitMarket` and `MarketView.submitLimit` take `Side`.
`Order.side()` returns `Side` and `Order.type()` returns `OrderType`; both may
be null — a cancel carries no side, and an unrecognised value parses to null
rather than throwing.

To convert a string of unknown provenance, use `Side.of(value)` or
`OrderType.of(value)`. Both are case-insensitive and return null for anything
unrecognised.

### Implementing `Flexemarkets`

This affects test fakes and alternative implementations, not ordinary callers.

`Flexemarkets` now composes six role interfaces — `Identity`, `Reading`,
`Writing`, `Management`, `Administration`, `Streaming` — and **every method on
them is abstract**. In 0.0.x roughly half were `default` methods that threw, so
a partial implementation compiled. It no longer does.

Two ways forward, in order of preference:

1. **Narrow the type.** A fake that only reads should implement `Reading` and
   the code under test should declare `Reading`. A fake for a trading robot
   implements `Writing`. This is the reason the roles exist: a `Writing` fake is
   three methods where a `Flexemarkets` fake is sixty.
2. **Implement everything.** If the fake genuinely stands in for a whole
   connection, add the missing members. The compiler lists them.

Prefer declaring the narrowest role in your own signatures too:
`void report(Reading books)` says in the type that the method cannot place an
order.

---

## Python

Python's changes are smaller: `StrEnum` members *are* strings, so most existing
code keeps working.

| Find | Replace |
|---|---|
| `.active_orders_v1(` | `.active_orders(` |
| `.recent_trades_v1(` | `.recent_trades(` |
| `.get_security(` | `.security(` |
| `Order.SIDE_BUY` | `Side.BUY` |
| `Order.SIDE_SELL` | `Side.SELL` |
| `Order.TYPE_LIMIT` | `OrderType.LIMIT` |
| `Order.TYPE_CANCEL` | `OrderType.CANCEL` |

`Side` and `OrderType` are importable from `fm`. Passing a plain `"BUY"` still
works — but compare with `==`, never `is`: `"BUY" is Side.BUY` is `False`.

**`connect_with_token` is gone.** Use `connect(token, endpoint, description)`.
See [token authentication](#7-token-authentication-never-worked) — this is a
deletion rather than a rename, because what it did never worked.

`create_market` takes the unit grid it used to hardcode:

```python
# before
fm.create_market(5, "STK", "Stock", 0, 10_000, 1)

# after — units default to TickGrid.units() when omitted
fm.create_market(5, "STK", "Stock", TickGrid(0, 10_000, 1))
fm.create_market(5, "STK", "Stock", TickGrid(0, 10_000, 1), TickGrid(10, 500, 10))
```

---

## TypeScript

| Find | Replace |
|---|---|
| `.activeOrdersV1(` | `.activeOrders(` |
| `.recentTradesV1(` | `.recentTrades(` |
| `ORDER_SIDE_BUY` | `Side.BUY` |
| `ORDER_SIDE_SELL` | `Side.SELL` |
| `ORDER_TYPE_LIMIT` | `OrderType.LIMIT` |
| `ORDER_TYPE_CANCEL` | `OrderType.CANCEL` |
| `FlexemarketsOptions` | *(delete — no function ever accepted it)* |

`Side` and `OrderType` are literal unions with same-named const objects, so
`"BUY"` and `Side.BUY` are interchangeable.

`createMarket` takes the unit grid it used to hardcode; omit it for the old
default:

```ts
// before
await fm.createMarket(5, "STK", "Stock", 0, 10_000, 1, false);

// after
await fm.createMarket(5, "STK", "Stock", { minimum: 0, maximum: 10_000, tick: 1 });
```

---

## Changes that are not renames

Each of these compiles after a mechanical migration and behaves differently.
Review every call site by hand.

### 1. Timestamps are typed, and were previously parsed wrongly

Every date field changed from a string to a moment: `Instant` in Java, an
**aware** `datetime` in Python, `Date` in TypeScript.

Affected: `createdDate`, `lastModifiedDate`, `openDate`, `closeDate`,
`established`, `terminated`, `expiresAt` (and their snake_case spellings).

**The old values were being misread.** The server sends two shapes: audit
fields arrive bare (`"2017-04-11T00:54:35.135"`, no zone) and `expiresAt`
arrives zoned (`"2026-08-15T18:00:00Z"`). A bare timestamp is UTC, because the
server's clock is. Anything that did:

- JavaScript: `new Date(order.createdDate)` — reads a bare value as **local
  time**, so it was adrift by the reader's offset. Correct in UTC, correct in
  CI, wrong on a laptop west of Greenwich.
- Python: `datetime.fromisoformat(...)` — returns a **naive** datetime, which
  raises when compared with an aware one and assumes local time in
  `.timestamp()`.

…was wrong, and is now fixed by the SDK. **Delete your own parsing**; do not
wrap the new value to restore a string. If you need one, format it explicitly.

Unparseable values are `null`/`None`, never an exception or an `Invalid Date`.

### 2. `submitMarket` was broken and is now immediate-or-cancel

In 0.0.x it sent `Long.MAX_VALUE` to buy and `0` to sell — prices no market
accepts. Every buy was refused; a sell survived only where `priceMinimum`
happened to be zero. If you worked around it, remove the workaround.

It now crosses the book at the extreme legal price and **cancels the
remainder**. Two consequences:

- It costs an extra round trip (it fetches the market to learn its bounds) and
  a second call for the cancel.
- Unfilled units do not rest. If your code relied on a market order leaving a
  resting order, it will not.

If the cancel fails the order is still placed, and the SDK throws saying so.
**Do not retry the whole call** on that error, or you will trade twice.

### 3. `priceRound` returns different values, and `unitRound` now exists

The tick grid is anchored at the *minimum*, not at zero — the server checks
`(value - minimum) % tick`. The old implementation subtracted `value % tick`,
which is correct only when the floor is a multiple of the tick.

On a market with `priceMinimum=110, priceTick=25`, rounding `137` returned
`125` before and returns `135` now. `125` was inside the bounds, off the grid,
and refused by the server with *"price is not on a tic"*. If you compensated
for that, remove the compensation.

A tick of `0` marks a fixed dimension and used to divide by zero.

`unitRound` is new. The server checks units on identical terms and refuses with
*"units is not on a tic"*, so code that rounded a price and passed raw units
was guarded on one axis only.

### 4. A holding's positions are ordered and may be absent

`securities` is now always present (never null) and always in market order. Two
holdings of the same positions now compare equal regardless of the order the
server listed them in — if you sorted defensively, you can stop.

`getSecurity` **threw** for a market the holder had no position in. It now
returns `Optional.empty()` (Java), `None` (Python) or `null` (TypeScript).
Having no position is ordinary — it is what every holding looks like before the
first allocation. Any `try`/`catch` around it is now dead code and will hide a
real error later.

### 5. Conflict exceptions changed shape

`AccountNameConflictException` and `PersonHasMarketplaceDataException` now
extend `ConflictException`. Both extended the base directly, so
`catch (ConflictException)` did not catch either — the two cases the server
describes most precisely were the two a general handler missed. TypeScript
gained all three types, which it did not have at all: a 409 arrived as a bare
`FlexemarketsError` reading `HTTP 409: {...}`.

In Python, `ConflictError` is now actually raised for a 409 — it was exported
and never thrown, so a handler for it caught nothing and `httpx.HTTPStatusError`
escaped instead. If you were catching `httpx.HTTPStatusError` for 409s, catch
`ConflictError`.

### 6. `sessions` and `connections` no longer take a session filter

They never filtered. `GET /marketplaces/{id}/sessions` and
`/marketplaces/{id}/connections` accept only `format` — the server has no
parameter for it — so the SDKs put `?sessionIds=` on the wire and got the whole
history back, looking filtered.

```java
// before — the argument was ignored, silently
fm.sessions(1744, List.of(300L, 301L));      // returned all 672
fm.connections(1744, List.of(300L));         // returned all 49

// after — read them and filter
fm.sessions(1744).stream().filter(s -> wanted.contains(s.id())).toList();
```

A connection carries the session it belonged to, so "who was present in that
run" is a filter on the result.

**Most call sites need no change**, because most already filter client-side:
fm-ui fetches `/sessions` and `/connections` unfiltered and narrows in the
component, fm-manager's `_sessions` filters in a stream, and capm carries a
comment explaining that a marketplace's session list is small enough to read
whole. Only code that passed the ids and trusted the answer is affected — and
that code was getting the wrong answer.

**`holdings` and `downloadHoldings` are unaffected.** Those routes do take
`sessions=` and genuinely filter; only these two invented one.

### 7. Token authentication never worked

**Python and TypeScript only.** Connecting with a token instead of a password
has failed since each SDK was written. Both POSTed `/tokens` with the bearer
header and a body carrying an empty password, and fm-server answers that
`400 MESSAGE_NOT_READABLE`. Both now use `GET /tokens/refresh`, which is what
the Java SDK has always used.

Two consequences for a migrating codebase:

- **Remove any workaround.** Anything that fetched a token by hand, or fell
  back to a password because "the token path doesn't work", can go.
- **`connect_with_token` is deleted, not renamed.** It was a second spelling of
  the same broken request. Use `connect(token, endpoint, description)`.

Python had a second defect behind the first: a real JWT is around 470
characters, and `Path.is_file()` raises `OSError: File name too long` rather
than returning `False`, so `connect(token, ...)` raised before reaching the
network. If you have a `try`/`except OSError` around a connect call, it is now
dead code.

If your tests stub the transport, they will not have caught any of this — every
fake in this SDK asserted the broken request, because that is what the SDK
sent. Check test fakes answer `GET /api/tokens/refresh`.

### 8. `createMarketplace` is gone; it could never have worked

`createMarketplace(name, description)` sent exactly that, and the server
requires at least one market:

```
MARKETPLACE_INVALID: At least one market is required to create a marketplace
```

So every call was a 400, in all three SDKs, for as long as the method existed.
Use `createMarketplaceFromJson`, which is the path every study already takes —
the javadoc there reads like a preference for keeping definitions as files, and
was in fact describing the only thing that worked.

```java
// before — always 400
var mp = fm.createMarketplace("course", "class 2");

// after
var mp = fm.createMarketplaceFromJson("""
    {"name":"course","description":"class 2","configuration":"",
     "markets":[{"symbol":"STK","name":"Stock","description":"",
                 "priceMinimum":100,"priceMaximum":200,"priceTick":25,
                 "unitMinimum":1,"unitMaximum":100,"unitTick":1,
                 "privateMarket":false}]}
    """);
```

`createMarket` still adds a market to a marketplace that exists, and is
unaffected.

**fm-robots has sixteen call sites**, all in `api-validator` —
`MarketplaceValidator` (5) and `SessionValidator` (1) plus their helpers. They
have never succeeded, so whatever those suites were reporting was not what they
meant to report.

---

## Verification

A green compile is necessary and not sufficient. Run all of it.

```bash
# 1. The SDK's own checks, from the fm-sdk checkout
make check-parity          # types and method surfaces agree across the three SDKs
make check                 # per-language build and tests

# 2. Your codebase
#    Java
mvn -o test
#    Python
python -m pytest
#    TypeScript
npm run check && npm test
```

Then confirm by inspection, because these are the ones tests in your codebase
are least likely to cover:

- [ ] No remaining reference to `Types.`, `Exceptions.`, `*V1(`, `SIDE_*`,
      `TYPE_*`, `ORDER_SIDE_*`, `ORDER_TYPE_*`, `getSecurit*`,
      `FlexemarketsOptions`.
- [ ] No hand-rolled timestamp parsing left over — search for `new Date(`,
      `fromisoformat`, `LocalDateTime.parse`, `Instant.parse` near SDK values.
- [ ] Every `catch` around a `getSecurity` call removed.
- [ ] Every workaround for `submitMarket` removed.
- [ ] Any `.account(` / `.user(` call with an argument renamed, and any without
      one left alone.
- [ ] Test fakes either narrowed to a role or completed.
- [ ] Test fakes that stand in for the server answer `GET /api/tokens/refresh`,
      not `POST /api/tokens`, for token auth.
- [ ] No import of `fm.HttpFlexemarkets`, `fm.Events`, or anything under
      `fm.internal`.
- [ ] Every `createMarket` call passes a price grid, and passes a unit grid if
      the market is not 1/100/1.
- [ ] No remaining `connect_with_token`.
- [ ] No remaining `createMarketplace(name, description)`; marketplaces are
      made with `createMarketplaceFromJson`.
- [ ] Every `sessions(...)` and `connections(...)` call passes one argument, and
      anything that relied on the filter now narrows the result itself.

## Pinning

```xml
<dependency>
    <groupId>com.flexemarkets</groupId>
    <artifactId>fm-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

`fm-spi` is versioned separately and is **unaffected** by this release. Do not
change its pin as part of this migration.
