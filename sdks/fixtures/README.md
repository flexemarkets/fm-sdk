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

## Behaviour fixtures: `trades/`

The documents above are one payload each, run through every parser that claims
to produce their type. `trades/pairing.json` is a different thing: a batch of
orders and the trades the tape must build from them. It lives in a
subdirectory because the runner globs `*.json` at the top level and maps `type`
to a parser, and this case has no single type to map.

It pins two answers that were wrong in all three SDKs at once and looked right
in each:

- **which side is which.** A trade is a pair of orders, and the tape kept the
  resting one — so "who took this trade" named the maker, at a real price, in a
  well-formed line. `Trade` now keeps both, and the fixture asserts the
  aggressor by id and owner.
- **what order the tape is in.** `/v1/orders/recent-trades` answered
  newest-first up to and including fm-server 4.3.1, and the tape appended in
  array order, so it was reversed and the newest trade sat at the front. The
  fixture is deliberately delivered newest-first, the way that server did —
  the tape sorts by time regardless of what arrives, so it stays right against
  a server on either side of that change.

Within each pair the resting order carries the *later* timestamp — so a tape
that reads the trade's time off the wrong side fails on the value rather than
on the ordering, which the ordering assertion alone would not catch.

## Adding one

Name it for the case, not the type — `account-pending`, not `account-2` — and
fill in `why` with what goes wrong when it is absent. A fixture whose absence
breaks nothing is a fixture nobody will maintain.

Add it once. All three SDKs pick it up with no further change.
