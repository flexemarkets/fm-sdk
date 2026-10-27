# Decimal fixtures

The cases every SDK's `Decimals` must agree on: an integer the exchange counts
in, a number of decimal places, and the text a person reads or types. Java,
Python and TypeScript each run all of them.

| file | each case | what is checked |
|---|---|---|
| `format.json` | `value`, `decimals`, `text` | `format(value, decimals)` is `text`, and `parse(text, decimals)` is `value` |
| `parse.json` | `text`, `decimals`, `value` | `parse(text, decimals)` is `value` |
| `refused.json` | `text`, `decimals`, `reason` | `parse(text, decimals)` raises the SDK's argument error |

Every `value` is within `Number.MAX_SAFE_INTEGER`, the narrowest of the three
integer ranges (TypeScript's). Beyond it Java and Python read up to a signed
64-bit integer and TypeScript refuses; each SDK's own tests cover that edge.

Nothing here is a payload from the server, so these are outside the wire
fixtures' provenance audit. Add a case to the file it belongs in, with a
`reason` for a refusal; all three SDKs pick it up with no code change.
