# Route fixtures

`routes.json` holds one case per client call: the call, its arguments, and the
requests it must send, in order. Java, Python and TypeScript each run every
case against a loopback server that answers `/api/tokens` and, for the rest,
exactly the requests the case lists. A request the case does not list fails
it, and the one that matters most is `GET /api`: 0.4.0 does not read the HAL
root, and this is what keeps it from drifting back.

The wire fixtures one directory up pin what a payload *means*. These pin where
each call *goes* -- the half that broke every SDK at once when fm-server 4.5.6
dropped three links from its root.

## Format

```json
{
  "call": "orders",
  "args": { "marketplaceId": 1, "sessionIds": [41, 44] },
  "why": "what breaks without this case",
  "requests": [
    { "method": "GET", "path": "/api/v1/marketplaces/1/orders",
      "query": { "sessions": "41,44" },
      "response": { "status": 200, "body": [] } }
  ],
  "returns": ["optional: what the call answers, where that is the point"]
}
```

- `call` is the Java and TypeScript name; Python converts it to snake_case.
  `args` are named, and each SDK's runner maps them onto its own signature,
  overloads and option objects included.
- `path` is compared exactly. `query` is compared as a set: every parameter
  named, and no other -- a stray `?format=` is a failure.
- `contentType` and `accept`, when given, must be what the request says. An
  `accept` naming `text/csv` must name it; JSON is the default and is not
  checked.
- `body`: a JSON object is compared on the fields named, so a client may send
  more (a `clientDescription`); anything else -- an array, a CSV string -- is
  compared whole.
- `response` is what the loopback server answers: `status`, and `body` with
  `contentType` (JSON by default). No body means none.
- `returns`, when present, is what the call must answer.

Adding a case needs no code in any SDK unless the call is new to the runner's
argument table.
