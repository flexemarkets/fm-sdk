# Public API changes under consideration for 0.3

What 0.2.0 left on the table. Each item carries the reasoning that produced it,
so a later reader can tell what has not been got to from what was looked at and
declined. Nothing here is scheduled, and everything here breaks a consumer,
which is why none of it fits a patch release.

What 0.2.0 *settled* is in [UPGRADING-0.2.md](UPGRADING-0.2.md) and is not
revisited.

---

## Open

### 1. The three SDKs do not export the same surface

The largest item here, and the only one with work already done against it.

0.2.0's simplifications landed in **Java alone**:

- Java deleted `isBuy`, `isSell`, `isCancel` and `isLimit` as redundant with the
  enums — "two ways to ask one question is one too many". TypeScript still
  exports all four; Python still defines them in `order_utils`.
- Java moved `contra` onto `OrderSide`. TypeScript and Python still export a
  free function.
- Java withdrew `OrderUtils` and `Timestamps` to `fm.internal`. TypeScript
  exports `toInstant` and eleven order utilities from its entry point. Python
  leaves both out of `__all__` but keeps `order_utils.py` and `timestamps.py`
  importable — it already has the underscore convention for private modules,
  in `_hal.py`, and applies it to neither.

Some divergence is idiom and should stay: TypeScript's wire types are structural
`interface`s and cannot carry methods, so behaviour Java hangs off a record has
to be a free function there.

**What has been done.** `check-parity.py` now compares the export lists — Java's
exported packages, Python's `__all__`, TypeScript's `index.ts` — with the
idiomatic differences recorded in `EXPORT_EXEMPTIONS` rather than ignored. It
found four names declared in all three SDKs but exported by only some
(`Allotment`, `Assets`, `ConflictFailure`, `ManagerOtpBundle`); those were
additive and are fixed. This drifted invisibly because a name exported by one
SDK and not another is not a wire field, a client method, a read-side member or
a failure, so it fell through all four existing checks while every one passed.

**What remains is the breaking half**, which is why it is here: withdrawing the
four redundant predicates and `contra` from TypeScript, and making Python's
`order_utils` and `timestamps` modules private. Both change what a consumer can
import.

**One case is a genuine either/or rather than an oversight.** `isResting` is
defined in TypeScript's `order-utils.ts` and not re-exported from `index.ts`, so
TypeScript callers cannot reach it at all, while Java (`Orders.isResting`) and
Python (`order_utils.is_resting`) can. Exporting it makes TypeScript consistent
with its own other eleven utilities; leaving it makes TypeScript consistent with
where this item is heading. It should be decided with the rest of this, not
before — and the export check cannot see it, since no SDK exports it at top
level.

### 2. `Reconnected` and `ReconnectEvent`

Two types whose names are one concept apart and whose meanings are two layers
apart. `Reconnected` is a queue event on the raw stream — the transport is back.
`ReconnectEvent` is the payload of `Desk.onReconnect`, carrying `success` and
`reason`, because the desk also re-seeds over REST and that can fail.

Both are needed; the names do not say which is which. This was open for 0.2 and
not taken. It gets cheaper to defer and never cheaper to do: every release that
ships both names adds call sites.

### 3. The role interfaces have no uptake

`Reading`, `Writing`, `Identity`, `Management`, `Administration` and `Streaming`
exist so a caller can narrow — `void report(Reading books)` says what the code
can do to a live marketplace.

**No consumer does it.** Measured at 0.2.0: fm-robots, fm-server and
fm-robots-server import zero types from `fm.role`. Both migrations hit
structural reasons not to narrow — a robot holds a `Supplier<Flexemarkets>`, and
Java cannot spell an intersection of roles as a field type;
`RecordingFlexemarkets` is a decorator whose contract is that everything passes
through.

Not a proposal to delete them: they make `Flexemarkets` a composition rather
than a list, which is what made "every role method is abstract" expressible.

The option worth weighing is narrower. A public interface may extend a
non-exported one — the inherited methods stay callable through `Flexemarkets`,
while `fm.role.Reading` stops being a name a consumer can write. That keeps the
composition and withdraws six names nobody uses. **Since nobody imports them,
the migration cost today is zero.** It only rises.

### 4. HAL-less and V1-only

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
