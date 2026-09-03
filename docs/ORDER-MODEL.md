# The order model, from the SDK side

Flex-E-Markets does not send you trades. It sends you **orders**, and expresses
everything that happens to them — a fill, a partial fill, a cancellation — as
orders referring to other orders by id.

**The canonical description of that data model is
[Order Data Format](https://github.com/adhocmarkets/fm-ui/blob/main/src/assets/docs/ORDERS-CSV.md)**,
which is also served in the app under Documentation → Order Data Format. It has
the ID / OID / SID / CID rules and a full worked example of a book being crossed,
split and cancelled, row by row. Read it first; nothing here replaces it.

This document is the companion for someone writing code against the SDK: the
short version of the model, then the one question the data does not answer for
itself, then which call to reach for instead of reconstructing anything by hand.

## The short version

Every order row carries four ids. In the canonical document these are ID, OID,
SID and CID; in the SDKs they are:

| field | is |
|---|---|
| `id` | this row |
| `original` | the originally submitted order this row descends from (OID) |
| `supplier` | the order that supplied this row's units (SID) |
| `consumer` | what happened to it (CID) |

For a freshly submitted order all three of `id`, `original` and `supplier` are
the same number — which is the whole of `OrderUtils.isSubmit`. They diverge when
an order is split more than once: `original` keeps pointing at the root, while
`supplier` points at the fragment this one was cut from.

`consumer` is **three states in one field**, and this is the most misread thing
in the API:

| `consumer` | means | SDK |
|---|---|---|
| `null` | nothing consumed it — it is resting on the book | `isAvailable` |
| `0` | this row is a split marker | `isSplit` |
| `> 0` | consumed by the order with that id | `isConsumed` |

A null check reads a split marker as resting. `isConsumed` and `isSplit` are
mutually exclusive; `isAvailable` excludes both.

A trade is a **pair** of consumed LIMIT rows naming each other through
`consumer`. A cancellation looks structurally similar — also a pair, also
cross-referenced — and is told apart by **type**: in a match both rows are
`LIMIT`, in a cancellation the types differ.

Two details from the canonical document that catch SDK callers specifically:

- A split marker carries the **original** size, not the traded size. The
  fragments say what became of those units.
- Matching has **no self-cross prevention**, so a trader's own orders can cross
  each other. fm-server strips such pairs from its read paths, so they do not
  reach you as trades — but the SDKs do not filter them, so if you obtain orders
  by some other route, a trader appearing on both sides of a "trade" is this and
  not a bug.

## The question the data does not answer: who was the aggressor

Both sides of a match are limit orders. Nothing on either row says which one was
already resting and which came in and crossed it — and that is usually the
question being asked, because it is the one that names a buyer and a seller.

The rule, which fm-manager's `TradesSummary` has always used and the SDKs now
implement:

> An order that is a **consumed LIMIT** whose **consumer is also a LIMIT** is one
> side of a match. `OrderUtils.isResting(orders, order)` says which side. The
> *consumer of the resting order* is the aggressor.

`isResting` needs the whole batch rather than the single order, because resting
is a property of an order's relationships: a consumed order may have left a
remainder that is itself resting, and only the other rows say so.

For an ordinary full match the rule reduces to "the lower id was resting", since
ids increase with time. **Do not use that as your rule.** It agrees with
`isResting` on the simple case and disagrees exactly where an order was split —
the case you are least able to check by eye, and the one where being wrong names
a real trader, at a real price, in a sentence with nothing visibly wrong with it.

There is a second trap in the same place. When an incoming order is larger than
what it takes, the remainder **rests** — so an aggressor's leftover can become
its own best quote, better than anything it had resting beforehand. Code that
reconstructs "the book as it stood before this trade" answers plausibly and
wrongly here.

## Reach for these before reconstructing anything

| you want | use |
|---|---|
| the last trade, and who took it | `Desk.tape(marketId)` → `Tape.last()` → `Trade.aggressor` |
| both sides of every recent trade | `Tape.mostRecentTrades()` → `Trade.resting` / `Trade.aggressor` |
| the aggregated book | `Desk.book(marketId)` |
| which side of a pair was resting | `OrderUtils.isResting(orders, order)` |
| the three `consumer` states | `isAvailable` / `isSplit` / `isConsumed` |
| an order by id within a batch | `OrderUtils.findOrder(orders, id)` |

`Trade` keeps both sides and records which side each number came from: `price`
and `units` from the resting order, `at` from the aggressor — because the trade
happened when the incoming order arrived, not when the quote it took was posted.
It exists because keeping one side and dropping the other is the mistake this
model invites, and the tape used to make it.

## Things that will catch you out

- **`consumer == 0` is not "no consumer".**
- **A cancellation is two rows; a partial fill is three or more.** Act on each
  set once. Acting twice is silent — the book stays plausible and is wrong by a
  few units. Both mistakes have shipped here.
- **An aggressor's remainder can be its own best quote.**
- **Prices are in cents**, everywhere.
- **Timestamps are bare and mean UTC.** They serialise with no zone, so
  `new Date(value)` is off by your local offset and correct only on a UTC
  machine.
- **The trade snapshot's order is not fixed.** `/v1/orders/recent-trades`
  answered newest-first up to and including fm-server 4.3.1 and oldest-first
  after it. `Tape` sorts by time either way; if you read the snapshot list
  directly, sort it yourself.
- **`trades(marketplaceId, symbol)` is the FM-3 route** and returns rows in
  ascending order id — neither chronological nor most-recent-first.

## Where the rules live

| | |
|---|---|
| the data model, in full, with a worked example | [fm-ui's Order Data Format](https://github.com/adhocmarkets/fm-ui/blob/main/src/assets/docs/ORDERS-CSV.md) |
| the three `consumer` states, `isResting`, `findOrder` | `OrderUtils` (Java), `order_utils` (Python), `order-utils` (TypeScript) |
| the pairing rule, in prose and in code | `Trade` / `Tape` in each SDK |
| the reference implementation it was read from | `TradesSummary` in fm-robots' fm-manager |
| worked examples as executable fixtures | `sdks/fixtures/behaviour/` |

The fixtures are the part to trust: each is one document run by all three SDKs,
and each exists because the behaviour it pins was once wrong.
