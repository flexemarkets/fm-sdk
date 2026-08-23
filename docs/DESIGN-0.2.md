# Public API changes under consideration for 0.2

What 0.1.0 left on the table, and what was deliberately left alone.

This is a decision record, not a plan. Nothing here is scheduled. The point is
that each item carries the reasoning that produced it, so a later reader can
tell "we have not got to this" from "we looked at this and said no" — and so
the second kind stops being re-proposed.

**Why 0.2 and not 0.1.1.** Under semver's 0.x convention a minor bump is what
signals breakage, which is why 0.1.0 was not 0.0.13. Anything below that
changes what a consumer can compile against — including adding a method to a
published interface, which breaks implementors even though it does not break
callers.

**What is already on Central.** 0.1.0. Everything committed after the `v0.1.0`
tag is unreleased, so the items marked *landed* below are in `main` and not in
anyone's build.

---

## Proposed

### 1. `MarketView` exposes the trade tape — *landed*

`DefaultMarketView` built a `MarketplaceTrades`, seeded it with a REST call to
`recentTrades` on every `observe()`, and fed it on every delta. The
`MarketView` interface had no accessor for it: the tape was constructed,
cleared and updated, and never read. Every observer paid a network round trip
and per-update work for something unreachable through the supported entry
point.

`MarketView.trades(long marketId)` now answers it, mirroring
`orderBook(long marketId)` — same shape, same null-for-unknown-market rule,
same atomicity. `MarketplaceTrades` gained the `get(long)` lookup that
`OrderBooks` already had, so either aggregator reaches one market's view the
same way.

Forces 0.2 because it adds a method to a published interface.

### 2. `MarketplaceTrades` moves to `fm.internal`

Zero references in fm-robots and fm-server. Inside the SDK its only use is
`DefaultMarketView`'s private field, and with item 1 landed a caller reaches a
tape through `MarketView.trades` without ever naming the aggregate.

`OrderBooks` is not in the same position and stays public: `Taker`, `TakerMvo`
and `Venture` each construct one directly, and `fm.robot.Books` takes one as a
parameter. Building the aggregators yourself is a real path — for books.
Nobody has ever done it for trades.

Worth knowing: `UPGRADING-0.1.md` records that a draft proposed moving
`OrderBooks`, `MarketplaceTrades` and `Version` to `fm.internal` and that the
revert was right. It then justifies two of the three — `new OrderBooks(markets)`
is supported and fm-robots does it four times, `Version` arrives on your event
queue so you must be able to name it — and says nothing about
`MarketplaceTrades`. It was swept along with its neighbours. The draft may have
been right about that one.

`Trades` itself stays public: `MarketView.trades` returns it.

---

## Considered and rejected

### `createMarket` taking six `long`s instead of two `TickGrid`s

Rejected. It would reintroduce exactly the defect `TickGrid` exists to prevent
— its own javadoc says so — and the shape makes that concrete:

```java
Market createMarket(long marketplaceId, String symbol, String name,
                    long priceMinimum, long priceMaximum, long priceTick,
                    long unitMinimum,  long unitMaximum,  long unitTick,
                    boolean privateMarket);
```

Ten parameters, seven `long`, and six adjacent and mutually interchangeable.
Transposing the price tick and the unit minimum compiles, posts, and produces a
market nobody can trade in. The flat form is also what hardcoded units at
1/100/1 in the first place.

It would additionally reverse a migration consumers have just done —
`UPGRADING-0.1.md` shows the before/after and its verification checklist has
"Every `createMarket` call passes a price grid" — and fm-robots has nine
converted call sites.

The gain would be one fewer published type, since `TickGrid` could then go
internal. That is not worth a silent transposition on a nine-argument call.

### `Market.priceGrid()` / `Market.unitGrid()`

Rejected, after being written and reverted. `createMarket` takes `TickGrid`s
and a `Market` stores six loose longs, so the accessors looked like they closed
a round trip. They did not: nothing in fm-robots or fm-server reconstructs a
grid from a `Market`, and every `new TickGrid(...)` in either is a literal being
passed *into* `createMarket`. The round trip is not one anyone takes.

Routing `priceRound` through them also allocated a record per call on the order
path, where a robot rounds every quote it computes. Building an object to do
three long operations is not a trade worth making to save a line.

The arithmetic did move to `TickGrid`, which is where it belongs — a bounded
tick grid is the concept, and a market is something that has two of them. That
part landed and changes nothing published: `Market.priceRound` and `unitRound`
call a package-private static over loose bounds.

---

## Open, not yet proposals

### `Reconnected` and `ReconnectEvent`

Two types whose names are one concept apart and whose meanings are two layers
apart. `Reconnected` is a queue event on the raw stream — the transport is
back. `ReconnectEvent` is the payload of `MarketView.onReconnect`, carrying
`success` and `reason` because the view also re-seeds over REST and that can
fail. Both are needed; the names do not say which is which. A rename is
breaking, so it belongs here rather than in a patch.

### The role interfaces have no uptake

`Reading`, `Writing`, `Identity`, `Management`, `Administration` and
`Streaming` exist so a caller can narrow — the guide's example is
`void report(Reading books)`, and the point is that a signature then says what
the code can do to a live marketplace. No consumer does it. fm-robots and
fm-server both declare `Flexemarkets` throughout, and both migrations hit
structural reasons not to narrow: a robot holds a `Supplier<Flexemarkets>`, and
Java cannot spell an intersection of roles as a field type;
`RecordingFlexemarkets` is a decorator whose contract is that everything passes
through.

Not a proposal to remove them — they cost little and make `Flexemarkets` a
composition rather than a list, which is what made "every role method is
abstract" expressible. Recorded because the benefit they were added for is not
yet being realised by anyone, and that is worth knowing before more is built on
the assumption that it is.

### HAL-less and V1-only

Directional, and blocked on the server rather than on the SDK. The SDK reads
`GET /api`, pulls hrefs out of `_links` and rebases them onto the configured
endpoint; it should call only versioned `/v1` routes and mention HAL nowhere.

The SDK-side preparation is done — `ApiRoot` is private in all three languages,
so the type can be deleted without a version bump. What remains is the URL
construction: roughly 45 `_uri*` sites in `sdks/python/fm/client.py`, 8 in
`sdks/typescript/src/client.ts`, and the matching helpers in
`HttpFlexemarkets.java`. Some `/v1/marketplaces` paths are already hardcoded,
so it is a mix rather than a clean swap.

**Do not start this before the server side has landed.** fm-server publishes
about a dozen `/v1` routes against a largely unversioned surface — marketplaces,
markets, sessions, holdings, allocations and roles are all still unversioned —
so the full V1 set the SDK needs does not exist yet.
