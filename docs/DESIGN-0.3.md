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

The SDK reads `GET /api`, pulls hrefs out of `_links` and rebases them onto the
configured endpoint. It should call versioned `/v1` routes and mention HAL
nowhere.

**The dependency is narrower than the call-site count suggests.** All three SDKs
resolve exactly five HAL link names — `accounts`, `marketplaces`, `orders`,
`users`, `usersJson` — and derive every other path by appending segments. The 42
`_uri*` sites in `sdks/python/fm/client.py` are 42 uses of those five. A `_v1()`
helper already exists and is used at three call sites, so the shape of the
answer is settled; what is left is how many links can point at it.

**Three routes are missing, and they are the whole blocker:**

| SDK needs | today | `/v1` | filed |
|---|---|---|---|
| the `accounts` link | `/api/accounts`, `AccountV0Controller` | absent. The only `/api/v1/accounts/*` routes are `OwnershipTransferV1Controller`'s transfer endpoints | [fm-server#964](https://github.com/adhocmarkets/fm-server/issues/964) |
| the `usersJson` link | `/api/users-json`, `RootController` | absent. It exists because `/api/v1/users` is paginated and misses freshly-created users on accounts with many persons | [fm-server#965](https://github.com/adhocmarkets/fm-server/issues/965) |
| `marketplaces/{id}/symbols` | `MarketplaceV0Controller` | absent from `MarketplaceV1Controller` | [fm-server#966](https://github.com/adhocmarkets/fm-server/issues/966) |

Two of the three may close without a new route. #965 is arguably a pagination
default rather than a missing endpoint, and #966 asks whether `symbols` is worth
keeping at all now that v1's `markets` returns each market's symbol. Either
resolution unblocks the SDK.

**Everything else the SDK reaches is already versioned**, which an earlier draft
of this note got wrong — it listed marketplaces, markets, sessions, holdings,
allocations and roles as unversioned. `MarketplaceV1Controller` publishes
`/{id}/markets`, `/{id}/markets/{marketId}`, `/{id}/holdings` with `me`,
`uploads` and `downloads`, `/{id}/allocations`, `/{id}/allotments`, and
`/{id}/sessions` with `open`, `pause`, `close` and `current`. `OrderV1Controller`
covers `/active`, `/by-sessions`, `/market/{marketId}/standing` and
`/recent-trades`; `TradeV1Controller` covers trades.

**The two halves decouple.** Everything reachable through `marketplaces`,
`orders` and `users` can move to `_v1()` now, without waiting on fm-server. That
shrinks the HAL dependency from five links to three and is worth doing on its
own: it is the bulk of the 42 sites, and it makes what remains a three-line
change once the routes land.

Once all five are gone, `ApiRoot` and `_process_template` go with them. `ApiRoot`
is already private in all three languages, so deleting it needs no version bump.

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
