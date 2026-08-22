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

## Adding one

Name it for the case, not the type — `account-pending`, not `account-2` — and
fill in `why` with what goes wrong when it is absent. A fixture whose absence
breaks nothing is a fixture nobody will maintain.

Add it once. All three SDKs pick it up with no further change.
