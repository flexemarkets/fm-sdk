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

**What is already on Central.** 0.1.4. Everything committed after the `v0.1.4`
tag is unreleased, so the items marked *landed* below are in `main` and not in
anyone's build.

**0.2.0 is open, not cut.** `VERSION` reads `0.2.0-dev0` — a prerelease in all
three ecosystems, so no consumer resolves to it by accident. The number moved
because the packaging change below landed and it breaks compilation; the
release is deliberately held while the surface is still being tried out. Items
marked *landed* are therefore settled in `main` but not yet promised to
anyone, and are still cheap to revisit.

---

## Proposed

### 1. `Desk` exposes the trade tape — *landed*

`DefaultDesk` built a `Tapes`, seeded it with a REST call to
`recentTrades` on every `desk()`, and fed it on every delta. The
`Desk` interface had no accessor for it: the tape was constructed,
cleared and updated, and never read. Every desk paid a network round trip
and per-update work for something unreachable through the supported entry
point.

`Desk.tape(long marketId)` now answers it, mirroring
`book(long marketId)` — same shape, same null-for-unknown-market rule,
same atomicity. `Tapes` gained the `get(long)` lookup that
`Books` already had, so either aggregator reaches one market's desk the
same way.

Forces 0.2 because it adds a method to a published interface.

### 2. `Tapes` moves to `fm.internal`

Zero references in fm-robots and fm-server. Inside the SDK its only use is
`DefaultDesk`'s private field, and with item 1 landed a caller reaches a
tape through `Desk.trades` without ever naming the aggregate.

`Books` is not in the same position and stays public: `Taker`, `TakerMvo`
and `Venture` each construct one directly, and `fm.robot.Books` takes one as a
parameter. Building the aggregators yourself is a real path — for books.
Nobody has ever done it for trades.

Worth knowing: `UPGRADING-0.1.md` records that a draft proposed moving
`Books`, `Tapes` and `Version` to `fm.internal` and that the
revert was right. It then justifies two of the three — `new Books(markets)`
is supported and fm-robots does it four times, `Version` arrives on your event
queue so you must be able to name it — and says nothing about
`Tapes`. It was swept along with its neighbours. The draft may have
been right about that one.

`Tape` itself stays public: `Desk.trades` returns it.

---

### 3. A `fm.model` package — *landed*, and it went further than this sketch

`fm` was flat: **52 public types in one package** — 23 records, 11 exceptions,
10 interfaces, and 8 classes and enums. Nothing groups them, so the types you
*receive* sit beside the types you *call* and the ones you *catch*, and an IDE
completion on `fm.` is a wall.

The split as proposed, which is broadly what landed — the differences are
tabulated further down:

| package | what | roughly |
|---|---|---|
| `fm.model` | the wire records — `Account`, `Allotment`, `Assets`, `ClientConnection`, `Holding`, `ManagerOtpBundle`, `Market`, `Marketplace`, `Order`, `Person`, `Security`, `Session`, `Token` | 13 |
| `fm.event` | what arrives on a queue — `StreamDropped`, `FrameUnreadable`, `Reconnected`, `ReconnectEvent`, `GapEvent`, `OrdersUpdate`, `Version` | 7 |
| `fm` | what you call and catch — the six roles, `Flexemarkets`, `Desk`, `Subscription`, the eleven exceptions, `Endpoints`, the aggregators, `OrderUtils`, `OrderSide`, `OrderType`, `TickGrid`, `Snapshot` | the rest |

Not obvious, and the cost is specific rather than general.

**The cost is a second mass re-import inside one release cycle.** 0.1.0's
headline change was that the seventeen records left the `Types` holder class
and became top-level in `fm`; fm-robots rewrote 478 import lines to follow, in
August 2026. Moving them again — even into a package, which is the idiomatic
answer where a holder class was not — asks every consumer to rewrite the same
imports a second time, months apart, for a benefit they did not ask for.

**So the sequencing matters more than the decision.** If this happens it should
land in the *same* release as any other change that rewrites imports, so a
consumer re-imports once. Doing it alone, in a release whose other contents are
a trade-tape accessor and an internal move, spends the whole cost on
tidiness.

Two smaller questions inside it, if it is taken: whether `Snapshot` and
`TickGrid` are model at all — one is a wrapper, the other a parameter type —
and whether the exceptions want `fm.exception` or are fine where they are,
given `catch` sites read better unqualified. *Both were answered when this
landed: `TickGrid` moved and `Snapshot` did not, and the exceptions went to
`fm.error`. See the table below.*

**The condition below was met, which is why it landed.** This asked to be
sequenced with any other change that rewrites imports, so a consumer
re-imports once. It shipped together with the `fm.error`, `fm.event` and
`fm.role` moves, the `Side` → `OrderSide` rename and the narrowing of
`fm.internal` — one migration, not five.

**Four choices this sketch left open, now made.** Recorded so they stop being
re-proposed:

| the sketch | what landed | why |
|---|---|---|
| exceptions stay in `fm`; `fm.exception` floated | `fm.error` | shorter at the `catch` site than `fm.exception`, and the eleven of them were a third of the flat package |
| `OrderSide`, `TickGrid` stay in `fm` | `fm.model` | both are things you are handed or hand back, which is what `fm.model` means here; `Snapshot` stayed out, being a wrapper |
| the six role interfaces stay in `fm` | `fm.role` | grouping them makes "no uptake" visible rather than hiding six unused names among fifty-two |
| `Endpoints`, `OrderUtils` stay in `fm` | `OrderUtils` to `fm.internal`; **`Endpoints` stays exported in `fm`** | see below — withdrawing `Endpoints` was a mistake, corrected before release |

**`Endpoints` was swept into `fm.internal` and has been put back.** It went
there with the genuinely internal machinery, and that was wrong: it is the
shared vocabulary for a public CLI flag, and withdrawing it is not a rename but
a capability removed, with no mechanical migration for anyone relying on it.

Two repos do. fm-robots calls `Endpoints.resolve` once, in `fm-tokens redeem`
— which runs *before* a credential exists, so it cannot go through
`Flexemarkets.connect` and needs the bare resolved URL to POST an OTP to. It
must also read `-E/--endpoint` exactly as the SDK does, or `-E 1234` would mean
one thing to that subcommand and another to its siblings; that divergence is
the failure this class was centralised to prevent. fm-server calls
`Endpoints.marketplaceId` from `LoopbackProvider`, on the SPI side.

So the exported surface those two need is two static methods, which is already
all `Endpoints` has in public besides `DEFAULT_HOST`. It stays in `fm`.
`OrderUtils` and `Timestamps` did move, and nothing outside imports either.

**Not on `Flexemarkets`, and it is worth saying why**, because it is the
obvious-looking home. That interface documents itself as *a composition, not a
list* — everything it can do belongs to one of the six roles and every one of
those methods is abstract — and it is something you hold and close. Endpoint
resolution is a pure string function you call before you have one. Statics on
an interface are also not inherited, and five types implement `Flexemarkets`,
so `Flexemarkets.resolve` would compile while `FakeFlexemarkets.resolve` would
not. And `marketplaceId`'s caller is a provider, which is the other side of the
boundary from the client interface.

The other 47 moved types are a scripted import rewrite.

**Measured cost to the one real consumer.** fm-robots: 625 imports across 369
files, plus 322 call sites naming `Side` — `Side.BUY` 141, `Side.SELL` 90,
`Side.BOTH` 29 — which is a rename rather than a move and cannot be done by
path substitution alone. fm-robots-server imports no moved type and is
unaffected.

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
back. `ReconnectEvent` is the payload of `Desk.onReconnect`, carrying
`success` and `reason` because the desk also re-seeds over REST and that can
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

They now live in `fm.role` (see item 3), which changes nothing about the uptake
— it only stops six unused names sitting among fifty-two, and makes the absence
of callers easier to see rather than easier to miss.

### An `Endpoint` value type instead of the `Endpoints` statics

`Endpoints` is a holder of statics over `String`, and the thing it is guarding
is a distinction the type system does not carry: its own doc says provider
selection *"has to happen against the resolved endpoint, not the argument as
typed"*. Nothing stops a caller passing the raw argument where the resolved one
is meant. That is a convention held by review.

A value type would hold it instead:

```java
Endpoint.of("1234").url()              // https://api.flexemarkets.com/api/marketplaces/1234
Endpoint.of("~/.fm/endpoint").url()    // whatever the file holds
Endpoint.of(raw).marketplaceId()
```

Resolved-ness becomes a type rather than a discipline, and the two call sites
outside this repo — fm-robots' `fm-tokens redeem` and fm-server's
`LoopbackProvider` — read as what they are.

Not proposed, for now. It is new API rather than a move, both consumers change
again, and the statics work. Recorded because 0.2.0 is the release that can
afford it: it is already rewriting every import, so a consumer that has to
touch these two call sites anyway pays almost nothing extra. If it is not taken
now, the next chance is 0.3.

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
