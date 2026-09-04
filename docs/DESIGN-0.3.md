# Public API changes under consideration for 0.3

What 0.2.0 left on the table. Each item carries the reasoning that produced it,
so a later reader can tell what has not been got to from what was looked at and
declined. Nothing here is scheduled, and everything here breaks a consumer,
which is why none of it fits a patch release.

What 0.2.0 *settled* is in [UPGRADING-0.2.md](UPGRADING-0.2.md) and is not
revisited.

---

## Open

### 1. HAL-less and V1-only

Blocked on the server, not the SDK. The SDK reads `GET /api`, pulls hrefs out of
`_links` and rebases them onto the configured endpoint; it should call only
versioned `/v1` routes and mention HAL nowhere.

SDK-side preparation is done — `ApiRoot` is private in all three languages, so
the type can be deleted without a version bump. What remains is URL
construction: roughly 45 `_uri*` sites in `sdks/python/fm/client.py`, 8 in
`sdks/typescript/src/client.ts`, and the matching helpers in
`HttpFlexemarkets.java`. Some `/v1/marketplaces` paths are already hardcoded, so
it is a mix rather than a clean swap.

**Do not start before the server side lands.** fm-server publishes about a dozen
`/v1` routes against a largely unversioned surface — marketplaces, markets,
sessions, holdings, allocations and roles are all still unversioned — so the
full V1 set the SDK needs does not exist yet.

---

## Rejected for 0.3: an `Endpoint` value type

Recorded here rather than above because it was live for 0.2 and the argument for
it has since expired.

`Endpoints` is a holder of statics over `String` guarding a distinction the type
system does not carry: its own doc says provider selection *"has to happen
against the resolved endpoint, not the argument as typed"*. Nothing stops a
caller passing the raw argument where the resolved one is meant. A value type
would hold that as a type rather than a convention.

The case for taking it in 0.2 was sequencing: that release rewrote every import
anyway, so the two call sites outside this repo — fm-robots' `fm-tokens redeem`
and fm-server's `LoopbackProvider` — were being touched regardless and the
marginal cost was near zero. 0.2 shipped without it, so that argument is gone.
It is now new API, priced at full cost, guarding a convention that has not
actually been violated.

Revisit if a third consumer appears, or if the convention is broken in practice.
