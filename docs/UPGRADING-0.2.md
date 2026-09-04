# Upgrading from fm-sdk 0.1.x to 0.2.x

Everything a consumer has to change, in all three languages, and the handful of
changes that are not renames and so cannot be done by substitution.

## How to use this document

Most of 0.2.0 is mechanical: 42 Java types moved package, nine types were
renamed in all three languages, and four read-side methods were named after what
they return. Those sections give you the substitutions.

Read [Changes that are not renames](#changes-that-are-not-renames) properly. It
is short, and every item in it changes behaviour rather than spelling — a
codebase that compiles after the substitutions can still be wrong in those five
ways.

The [Verification](#verification) section at the end is the checklist that says
you are done.

## Why 0.2.0 breaks

Under semver's 0.x convention a minor bump is what signals breakage. 0.1.0's
headline change was that seventeen records left the `Types` holder class and
became top-level in `fm`. That left `fm` flat — 52 public types in one package,
where the things you *receive* sat beside the things you *call* and the things
you *catch*.

0.2.0 groups them, and takes the renames and withdrawals with it deliberately:
the cost of a mass re-import is paid once, in one release, rather than spread
over three. If you are migrating a large codebase, that is the trade being made
on your behalf — one bad afternoon instead of three mediocre ones.

The measured size of the largest migration, fm-robots: 625 imports across 369
files, plus 322 call sites naming `Side`.

---

## Java

### Imports: 42 types moved into four packages

Nothing about these types changed. They are in a package that says what they
are.

| new package | what it holds | count |
|---|---|---|
| `fm.model` | the wire records and the enums you hand back — `Account`, `Allotment`, `Assets`, `ClientConnection`, `ConflictFailure`, `Holding`, `ManagerOtpBundle`, `Market`, `Marketplace`, `Order`, `OrderSide`, `OrderType`, `Person`, `Security`, `Session`, `TickGrid`, `Token`, `Trade` | 18 |
| `fm.error` | the eleven exceptions — `FlexemarketsException` and its subtypes | 11 |
| `fm.event` | what arrives on a queue — `FrameUnreadable`, `GapEvent`, `OrdersUpdate`, `ReconnectEvent`, `Reconnected`, `StreamDropped`, `Version` | 7 |
| `fm.role` | the six role interfaces — `Administration`, `Identity`, `Management`, `Reading`, `Streaming`, `Writing`. **Not exported** — see below | 6 |

What stays in `fm`: `Flexemarkets`, `Desk`, `Subscription`, `Book`, `Tape`,
`Orders`, `Endpoints`, `Snapshot`, `FlexemarketsProvider`, `Providers`.

`Endpoints` stays exported deliberately. It is the shared vocabulary for the
`-E/--endpoint` flag, and two repos resolve endpoints through it before a
credential exists.

The substitution, from your source root:

```bash
# The four package moves, plus Side, which is a move and a rename at once.
# Run this before the type renames below.
grep -rl '^import fm\.' --include='*.java' . | xargs sed -i -E \
  -e 's/^import fm\.(Account|Allotment|Assets|ClientConnection|ConflictFailure|Holding|ManagerOtpBundle|Market|Marketplace|Order|OrderType|Person|Security|Session|TickGrid|Token|Trade);/import fm.model.\1;/' \
  -e 's/^import fm\.Side;/import fm.model.OrderSide;/' \
  -e 's/^import fm\.(AccountNameConflict|Api|Authentication|Authorization|Configuration|Conflict|ConnectionFailed|Flexemarkets|Http|InvalidArgument|PersonHasMarketplaceData)Exception;/import fm.error.\1Exception;/' \
  -e 's/^import fm\.(FrameUnreadable|GapEvent|OrdersUpdate|ReconnectEvent|Reconnected|StreamDropped|Version);/import fm.event.\1;/' \
  -e 's/^import fm\.(Administration|Identity|Management|Reading|Streaming|Writing);/import fm.role.\1;/'
```

`import fm.Side;` has to be handled here rather than by the rename pass below:
it is the one type that both moves package and changes name, and a rename that
runs after the moves would leave you with `import fm.OrderSide;` — a package
that no longer holds it.

### Renames

Nine types changed name. The reasoning is in the commits; what matters here is
that `MarketView` was neither a view nor per-market, and the book and tape types
were named after the container rather than the thing.

| 0.1.x | 0.2.0 | note |
|---|---|---|
| `fm.MarketView` | `fm.Desk` | |
| `fm.OrderBook` | `fm.Book` | methods unchanged |
| `fm.Trades` | `fm.Tape` | |
| `fm.OrderUtils` | `fm.Orders` | and lost five methods — see below |
| `fm.Side` | `fm.model.OrderSide` | move *and* rename |
| `fm.OrderBooks` | `fm.internal.BookIndex` | withdrawn |
| `fm.MarketplaceTrades` | `fm.internal.TapeIndex` | withdrawn |
| `fm.DefaultMarketView` | `fm.internal.DefaultDesk` | was already implementation |
| `fm.MarketViewHandle` | `fm.internal.DeskHandle` | was already implementation |

`Side` is the expensive one: it is a rename rather than a path move, so
`Side.BUY` does not fall out of an import rewrite. In fm-robots it was 322 call
sites — `Side.BUY` 141, `Side.SELL` 90, `Side.BOTH` 29.

```bash
grep -rl '\bSide\b\|MarketView\|OrderBook\|OrderUtils' \
  --include='*.java' . | xargs sed -i -E \
  -e 's/\bDefaultMarketView\b/DefaultDesk/g' \
  -e 's/\bMarketViewHandle\b/DeskHandle/g' \
  -e 's/\bMarketView\b/Desk/g' \
  -e 's/\bOrderBook\b/Book/g' \
  -e 's/\bOrderUtils\b/Orders/g' \
  -e 's/\bSide\b/OrderSide/g'
```

Three cautions, each of which cost something to find:

**It is not idempotent.** `\bSide\b` → `OrderSide` turns an already-migrated
`OrderSide` into `OrderOrderSide`. Run it once, on a clean tree, and grep for
`OrderOrderSide` afterwards.

**`OrderBooks`, `MarketplaceTrades` and `Trades` are deliberately not in that
script.** The first two are withdrawn rather than renamed — rewriting them to
`BookIndex` and `TapeIndex` produces an import that looks right and does not resolve,
which is worse than a name the compiler rejects outright. Let it fail, then
follow [the section below](#books-and-tapes-are-no-longer-yours-to-construct).
`Trades` is out because it collides with unrelated builder methods: rename
`fm.Trades` → `fm.Tape` by import and fix the use sites the compiler finds.

**Check whether you own a type of the same name.** These are ordinary words, and
a blanket rename does not know whose they are. fm-robots had its own
`fm.robot.Books` alongside the SDK's, and a `\bOrderBooks\b` pass would have
walked into it. Grep your own packages for `Book`, `Books`, `Tape`, `Tapes`,
`Orders` and `Desk` before you run anything.

### The role interfaces are no longer names you can write

`Reading`, `Writing`, `Identity`, `Management`, `Administration` and `Streaming`
were public in `fm` through 0.1.x. They are now in `fm.role`, which the module
does not export.

**Nothing you call changes.** `Flexemarkets` still extends all six, so every
method they declare is still on the interface and still callable:

```java
List<Market> markets = flexemarkets.markets(marketplaceId);   // Reading, unchanged
```

What you can no longer do is name one — `void report(Reading books)` stops
compiling on the module path. They were exported so a signature could narrow to
one, and in two releases nothing ever did: fm-robots, fm-server and
fm-robots-server import zero of them between them. Both migrations found
structural reasons not to narrow, a robot holding a `Supplier<Flexemarkets>`
being the clearest — Java cannot spell an intersection of roles as a field type.

If you were narrowing, declare `Flexemarkets` instead. Re-exporting is additive,
so say so if you have a use for it.

### `OrderBooks` and `MarketplaceTrades` are no longer yours to construct

Both moved to `fm.internal`, and were renamed there to `BookIndex` and
`TapeIndex`. Nothing outside the SDK held one any more — the
tickers and fm-robots' `Taker`, `TakerMvo` and `Venture` all read theirs from a
desk.

If you were building one, the replacement is a desk, which also seeds and
reseeds it for you:

```java
// before
var books = new OrderBooks(markets);
flexemarkets.listen(marketplaceId, queue);
// ... seed from activeOrders, track asOfSeq, drop deltas at or below it

// after
try (var desk = flexemarkets.desk(marketplaceId)) {
    var book = desk.book(marketId);   // seeded, gap-recovered, kept current
}
```

**How firmly this is enforced differs by language, and it is worth being exact.**
On the Java *module* path `module-info` does not export `fm.internal`, so the
import does not resolve. On the *classpath* it still does — fm-robots consumes
the SDK there and could import `fm.internal.BookIndex` if it chose to. Treat the
withdrawal as the contract regardless of which one your build uses.

`Book` and `Tape` stay public, so a `listen()` consumer can still aggregate. It
keeps its own `Map<Long, Book>` rather than a container.

### Read-side methods are named after what they return

`orderBook(marketId)` returns a `Book` and `trades(marketId)` returns a `Tape`.

| 0.1.x | 0.2.0 |
|---|---|
| `flexemarkets.observe(marketplaceId)` | `flexemarkets.desk(marketplaceId)` |
| `desk.orderBook(marketId)` | `desk.book(marketId)` |
| `desk.trades(marketId)` | `desk.tape(marketId)` |
| `desk.onOrderBookChange(...)` | `desk.onBookChange(...)` |

`onTrade` keeps its name — it hands over a `Trade`, not a tape.

**Only the one-argument read-side method moved.** `Reading.trades(marketplaceId,
symbol)` is a different method that returns `List<Order>` off the REST path and
is unchanged. If you rename by name alone you will break it.

### Five helpers were removed rather than moved

`OrderUtils` became `Orders`, and shed everything that had become a one-line
comparison once side and type were enums.

| gone | write instead |
|---|---|
| `OrderUtils.isBuy(order)` | `OrderSide.BUY == order.side()` |
| `OrderUtils.isSell(order)` | `OrderSide.SELL == order.side()` |
| `OrderUtils.isCancel(order)` | `OrderType.CANCEL == order.type()` |
| `OrderUtils.isLimit(order)` | `OrderType.LIMIT == order.type()` |
| `OrderUtils.contra(String)` | `order.side().contra()` |

What `Orders` keeps is what can only be answered by looking at several orders
together — `isAvailable`, `isConsumed`, `isConsumedOrSplit`, `isResting`,
`isSplit`, `isSubmit`, `isSupplier`, `isSymbol`, `findOrder`, `limit`.

---

## Python

### Renames

```bash
grep -rl 'MarketView\|OrderBook\|MarketplaceTrades\|\bSide\b\|order_book\|observe(' \
  --include='*.py' . | xargs sed -i -E \
  -e 's/\bMarketView\b/Desk/g' \
  -e 's/\bOrderBook\b/Book/g' \
  -e 's/\bSide\b/OrderSide/g' \
  -e 's/\bon_order_book_change\b/on_book_change/g' \
  -e 's/\border_book\(/book(/g' \
  -e 's/\.observe\(/.desk(/g'
```

`OrderBooks`, `MarketplaceTrades` and `Trades` are left out for the same reasons
as Java. The first two are withdrawn, not renamed: rewriting them to `BookIndex`
and `TapeIndex` gives you a name `fm` does not export, which is a worse failure than the
one the compiler would have given you. Rename `Trades` → `Tape` by import and
let the failures find the use sites.

| 0.1.x | 0.2.0 |
|---|---|
| `flexemarkets.observe(mp_id)` | `flexemarkets.desk(mp_id)` |
| `desk.order_book(market_id)` | `desk.book(market_id)` |
| `desk.trades(market_id)` | `desk.tape(market_id)` |
| `desk.on_order_book_change(...)` | `desk.on_book_change(...)` |

### No longer exported from `fm`

`MarketView`, `MarketplaceTrades`, `OrderBook`, `OrderBooks`, `Side` and
`Trades` are out of `__all__`. Four names replace them: `Book`, `Desk`,
`OrderSide`, `Tape`.

`BookIndex` and `TapeIndex` (formerly `OrderBooks` and `MarketplaceTrades`) are
withdrawn as API. Unlike TypeScript, Python cannot enforce that —
`from fm.orderbook import BookIndex` still resolves. It is
convention, and the convention is that a caller who wants books kept for them
asks for a desk.

---

## TypeScript

### Renames

```bash
grep -rl 'MarketView\|OrderBook\|MarketplaceTrades\|\bSide\b\|orderBook\|observe(' \
  --include='*.ts' src test | xargs sed -i -E \
  -e 's/\bMarketView\b/Desk/g' \
  -e 's/\bOrderBook\b/Book/g' \
  -e 's/\bSide\b/OrderSide/g' \
  -e 's/\bonOrderBookChange\b/onBookChange/g' \
  -e 's/\.orderBook\(/.book(/g' \
  -e 's/\.observe\(/.desk(/g'
```

`OrderBooks` and `MarketplaceTrades` are deliberately absent, as in Java: they
are withdrawn rather than renamed, and TypeScript is the language that
*enforces* it — `package.json` exports only `"."`, so a rewrite to `BookIndex` gives
you an import that cannot resolve.

The method table is the Java one — TypeScript uses the same camelCase names.

### No longer exported from the package entry point

`MarketView`, `MarketplaceTrades`, `OrderBook`, `OrderBooks`, `Side` and
`Trades` are gone from `index.ts`; `Book`, `Desk`, `OrderSide` and `Tape`
replace them.

This is the one language where the withdrawal is enforced rather than agreed:
`package.json` exports only `"."`, so a deep import of `./orderbook.js` does not
resolve. If you were reaching past the entry point, that stops working now.

---

## Changes that are not renames

Five things behave differently. A codebase that compiles after the
substitutions above can still be wrong in these ways.

### 1. A desk seeds and re-seeds the book for you

This is the change that removes code rather than moving it. In 0.1.x a caller
who wanted a maintained book did the reconciliation itself: build `OrderBooks`, seed
from `activeOrders`, record the snapshot's `asOfSeq`, and drop deltas at or
below it.

A desk does all of that — and re-seeds after a sequence gap, which most
hand-written versions did not. fm-robots' `Taker` is the worked example: it lost
its `OrderBooks` field, its `seededAtSeq` field and its `_seedBook` method, and
gained gap recovery it never had.

If you keep your own aggregation off `listen()`, nothing changes for you. If you
move to a desk, delete your reconciliation rather than porting it.

### 2. `Desk` answers the whole marketplace

`Desk.books()` and `Desk.tapes()` are new, alongside the existing
`book(marketId)` and `tape(marketId)`. A caller scanning every market previously
had to zip `markets()` against `book()`, or reach for the `OrderBooks` container
— which was the only remaining reason to hold one.

`Desk.books()` returns `Collection<Book>` and `Desk.tapes()` returns
`Collection<Tape>` — both of the public per-market types.

Each returns a snapshot of the collection; the books and tapes inside it stay
live, on the same terms as `book()` and `tape()`.

`Desk.tape(marketId)` is also new in the sense that matters: 0.1.x built the
tape, seeded it with a REST call on every desk, fed it on every delta — and
exposed no accessor. Every desk paid for something unreachable.

### 3. Python and TypeScript now send the STOMP heartbeat they advertise

All three SDKs put `heart-beat:30000,30000` in CONNECT — "I will send one every
30s" — and only Java did. The other two wrote nothing to the socket after
SUBSCRIBE.

TypeScript was the exposed one: `ws` sends no WebSocket ping of its own unless
asked, so that client had no keepalive at any layer, and an idle robot's socket
was Heroku's to reap at 55s and log as an H15. Python was covered by accident —
the `websockets` library pings every 20s — but a ping is not a STOMP heartbeat.

Both now send a bare EOL every 25s, on the same lifecycle as Java. **If you
built a keepalive of your own to work around this, remove it.**

### 4. One drop starts one reconnect

The reconnect is now held behind a gate in all three languages, so a burst of
drops produces a single reconnect rather than one per drop. If you were
de-duplicating `Reconnected` events downstream, you can stop.

### 5. Java: a null primitive reads as the type's default

The SDK pinned `jackson-bom` 3.2.1 while fm-server inherits 3.1.5 from
`spring-boot-starter-parent` — so the SDK was tested against a Jackson its
largest consumer never ran. The two disagree about a default: 3.1.5 *rejects* a
null mapped onto a primitive, 3.2.1 coerces it. The server sends explicit nulls
for primitives it has no value for — `Market.marketplaceId`,
`Session.allocationId` — so the same payload parsed or threw depending on which
patch release you resolved.

0.2.0 takes Jackson 3.1.5, matching Spring Boot, and disables
`FAIL_ON_NULL_FOR_PRIMITIVES` explicitly. A null primitive now reads as the
type's default — `0` for a `long` — as a decision rather than as a default that
travelled with a patch version.

**What this means for you:** `market.marketplaceId() == 0` and
`session.allocationId() == 0` are how "the server said null" now presents. If
you were relying on an exception to tell you the field was absent, you were
relying on 3.1.5 and it will not throw any more.

---

## Verification

```bash
# 1. The SDK's own checks, from the fm-sdk checkout
make check-sdks

# 2. Your codebase
#    Java — the whole reactor, not one module
mvn -q clean install
#    Python
pytest
#    TypeScript
npm run build && npm test
```

Then, by hand:

- [ ] No `import fm.<Type>;` remains for a type that moved — the compiler finds
      these, but check `fm.role` in particular, since six interfaces nobody
      imports will not announce themselves.
- [ ] No `OrderOrderSide` anywhere — the sign the `Side` script ran twice.
- [ ] `Reading.trades(marketplaceId, symbol)` still says `trades`. Only the
      one-argument desk method became `tape`.
- [ ] Every `isBuy` / `isSell` / `isCancel` / `isLimit` call site is now an enum
      comparison, and every `contra` is `side().contra()`.
- [ ] Nothing constructs `OrderBooks` or `MarketplaceTrades`. If something does,
      it wants a desk.
- [ ] Any hand-rolled WebSocket keepalive in a Python or TypeScript consumer is
      removed.
- [ ] Java: anywhere you treated a missing `marketplaceId` or `allocationId` as
      an exception now treats it as `0`.

## Pinning

```
Java         <version>0.2.0</version>
Python       fm-sdk==0.2.0
TypeScript   "@flexemarkets/fm-sdk": "0.2.0"
```

0.2.0 is not yet published. While it is unreleased the three ecosystems carry
prerelease coordinates — `0.2.0-SNAPSHOT` for Maven, `0.2.0-dev0` for PyPI and
npm — and none of them resolves by accident from a caret or a range.
