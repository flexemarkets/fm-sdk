# Endpoint fixtures

One JSON document per case: an endpoint, and the API root every SDK must derive
from it. Java, Python and TypeScript each run all of them.

```json
{
  "why": "why this case exists — what breaks without it",
  "endpoint": "https://api.flexemarkets.com",
  "apiRoot": "https://api.flexemarkets.com/api"
}
```

The wire fixtures beside this directory compare what the SDKs *parse*. This
compares what they *derive*, which is the other way a hand-written trio drifts
apart — and it drifted. `server()` is written three times, and 0.1.1 went to
Maven Central, PyPI and npm with the same function fixed in Java and unchanged
in the other two: an endpoint naming only a server resolved to itself, so
sign-in POSTed to `<host>/tokens` and collected a 404. Every registry is
append-only, so that version is divergent for good.

Nothing caught it. `scripts/check-parity.py` compares wire fields, method
surfaces and catchable failures; `server` is private in all three, so it is
none of those. These fixtures are the part of the surface a name check cannot
see.

## Adding one

Name it for the case, not the shape — `host-trailing-slash`, not `endpoint-4` —
and fill in `why` with what goes wrong when it is absent. Add it once; all
three SDKs pick it up with no further change.
