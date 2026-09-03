# Wire fixtures

One JSON document per case: a payload fm-server sends, and the values every SDK
must read out of it. Java, Python and TypeScript each run all of them.

`scripts/check-parity.py` compares the three declarations of each type and can
only see *names*. It reported `ok` for months while `approval` was `Boolean` in
Java, `bool` in Python and `boolean` in TypeScript — three SDKs agreeing on a
field name and disagreeing about whether it could be null, so a pending account
read as suspended in two of them. These fixtures compare *values*, which is the
half a name check cannot reach.

They also hold the parsers together. `Session`, `Holding` and `Order` are each
parsed twice per SDK — once on the REST path, once on the WebSocket path — and
nothing made the two agree. Every fixture runs through every parser that claims
to produce its type.

## Format

```json
{
  "type": "Session",
  "why": "why this case exists — what breaks without it",
  "payload": { "openDate": "2026-08-21T09:00:00" },
  "expect":  { "openDate": { "epochMilli": 1755766800000 } }
}
```

- `type` names the wire type, and each SDK maps it to its parsers.
- `payload` is exactly what the server sends. Do not tidy it.
- `expect` is keyed by wire name; Python converts to snake_case.
- Only the fields a case is about need to appear in `expect`.

Values compare directly, except:

| value | means |
|---|---|
| `{"epochMilli": N}` | an instant, compared as epoch milliseconds |
| `null` | null / `None` — and not a default that happens to be falsey |
| `[{...}]` | compared element-wise, recursively |

Epoch milliseconds rather than an ISO string on purpose: comparing an instant
to a string would run it back through the SDK's own timestamp parser, so a
parser that is wrong in both directions would agree with itself. A number does
not.

## Where a payload came from

Every fixture declares a `source`, and it is one of two things:

```json
"source": { "captured": { "path": "/v1/marketplaces/{marketplaceId}/connections",
                          "server": "4.3.1", "on": "2026-08-28" } }
"source": { "constructed": "why no live server produces this" }
```

A **captured** fixture names a route, and `scripts/capture-fixtures.py --check`
re-fetches it and compares the *shape* — field names and JSON types, recursively
— against what is stored. Use `link` instead of `path` for a route the API root
advertises, so a link the root stops advertising is itself a finding. A
**constructed** fixture is an envelope an older server sends, a value the SDK
must tolerate, or a state this server has no example of. Both are legitimate;
not saying which is not.

This exists because a fixture is a *belief* about the server, and nothing
compared the beliefs to the server. That is how `_embedded.orderDtoes` became
`_embedded.orders` with every SDK still reading the old name, returning an empty
book for months — every suite green, because every suite was asking the SDK to
agree with a fixture rather than with fm-server. The check found a stale one on
its first run: `connection` still claimed `connectionId`, which the server had
replaced with `id`.

```bash
make check-fixtures                     # offline: every fixture declares a source
make check-fixtures-live ENDPOINT=...   # against a server: has a shape moved?
scripts/capture-fixtures.py --write     # refresh captured payloads for review
```

A field the server sends that a fixture omits is a note, not a failure — a case
is allowed to be about one field. A field the fixture has and the server no
longer sends, or one whose type changed, is the failure.

## Behaviour fixtures: `behaviour/`

The documents above are one payload each, run through every parser that claims
to produce their type. They compare *parsed field values*, and say nothing about
what `Book` and `Tape` do with a sequence of them — which is where the
three SDKs have actually been wrong *together*. A book that double-counts a
cancel and a tape that holds its trades backwards both parse every field
correctly.

So `behaviour/` holds inputs and answers rather than payloads and fields:

```json
{
  "type": "Book",
  "why": "why this case exists — what breaks without it",
  "market": { "id": 7, "symbol": "A" },
  "deliveredIds": [101, 102],
  "steps": [
    { "note": "what this step is", "clear": false, "orders": [ ... ] }
  ],
  "expect": { "bestBuyPrice": 1000, "buyLevels": [[1000, 10]] }
}
```

- `type` names the aggregator to drive: `Book` or `Tape`.
- `steps` are applied in order, each an `update()`. `"clear": true` calls
  `clear()` first, which is how `Desk` recovers from a sequence gap.
- `"refused": "<text>"` on a step means that `update()` must raise rather than
  apply, with `<text>` somewhere in the message. Use it for input the SDK
  cannot act on and must not guess at — an order that names no side. The
  remaining steps and `expect` still run, so a fixture can also pin that the
  refusal left the aggregator alone rather than half-applying.
- `expect` is checked against the aggregator's state at the end. Only the keys
  a case is about need to appear.
- `deliveredIds` is every order id across every step, in order. It exists
  because a case can be silently destroyed by tidying its input: the
  `trades-ordering` fixture is only a test at all because its orders arrive
  newest-first, and sorting them would leave it green and meaningless.

`Book` reads `bestBuyPrice`, `bestBuyUnits`, `bestSellPrice`,
`bestSellUnits`, `hasValueBuy`, `hasValueSell`, `buyLevels` and `sellLevels`;
levels are `[price, units]` pairs in the order the SDK returns them, so the
three are held to one sequence despite Java returning a `Map` and the other two
a list. `Tape` reads `size`, `trades`, `last` and `drain`; a trade compares
`price`, `units`, `restingId`, `aggressorId`, `restingOwnerId`,
`aggressorOwnerId` and `at`.

What is in there now, and what each would let through if it went missing:

| | |
|---|---|
| `trades-pairing` | the tape keeping the resting order and dropping the incoming one, so "who took this trade" names the maker |
| `trades-ordering` | the tape built backwards from a newest-first snapshot, so the *last* trade is the oldest one retained |
| `orderbook-levels` | the two sides coming back in the wrong sequence, so `levels[0]` is not the top of book |
| `orderbook-cancel` | a cancel removing twice — it found this one, live in all three SDKs |
| `orderbook-split` | a partial fill counted twice, taking the level negative |
| `orderbook-gap-reseed` | `clear()` leaving the book initialised, so the first delta after a gap underflows |
| `orderbook-sideless-refused` | a cancel arriving without the limit it consumed — it names no side, and all three SDKs put it on the offer book |

Both fixture families ban a shortcut worth naming: an instant is compared as
`{"epochMilli": N}`, never as a string, for the reason the wire section gives.

## Adding one

Name it for the case, not the type — `account-pending`, not `account-2` — and
fill in `why` with what goes wrong when it is absent. A fixture whose absence
breaks nothing is a fixture nobody will maintain.

Add it once. All three SDKs pick it up with no further change.
