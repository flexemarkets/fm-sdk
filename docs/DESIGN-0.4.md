# Public API changes under consideration for 0.4

What 0.3.0 left on the table. Each item carries the reasoning that produced it,
so a later reader can tell what has not been got to from what was looked at and
declined. Nothing here is scheduled, and everything under **Open** breaks a
consumer, which is why none of it fits a patch release.

What 0.3.0 *settled* is in [UPGRADING-0.3.md](UPGRADING-0.3.md) and is not
revisited. This file replaces `DESIGN-0.3.md`: 0.3.0 shipped without taking any
of it, so both of that file's items carry forward unchanged in substance, with
their status brought up to date. Items 2 and 3 are new.

---

## Open

### 1. HAL-less and V1-only

*Carried from DESIGN-0.3 §1. Still the headline item, still blocked, and the
blocker is now a single issue rather than three.*

The SDK reads `GET /api`, pulls hrefs out of `_links` and rebases them onto the
configured endpoint. It should call versioned `/v1` routes and mention HAL
nowhere.

**The dependency is narrower than the call-site count suggests.** All three SDKs
resolve exactly five HAL link names — `accounts`, `marketplaces`, `orders`,
`users`, `usersJson` — and derive every other path by appending segments. The 42
`_uri*` sites in `sdks/python/fm/client.py` are 42 uses of those five. A `_v1()`
helper already exists and is used at three call sites, so the shape of the
answer is settled; what is left is how many links can point at it.

**Three routes are still missing, and they are the whole blocker.**
fm-server#964, #965 and #966 were each closed on 2026-09-06 — *consolidated*,
not resolved — into **[fm-server#968](https://github.com/adhocmarkets/fm-server/issues/968)**,
which is open. Its own reasoning is why they travel together: the SDK cannot
stop reading `_links` while any single name still lacks a versioned route, so
shipping one or two of three buys a caller nothing and pays the
review-and-deploy cost three times for one decision repeated.

> Read the three closures carefully before concluding this is unblocked. Three
> `CLOSED [COMPLETED]` issues look exactly like three delivered routes, and they
> are not.

**Everything else the SDK reaches is already versioned.** `MarketplaceV1Controller`
publishes `/{id}/markets`, `/{id}/markets/{marketId}`, `/{id}/holdings` with
`me`, `uploads` and `downloads`, `/{id}/allocations`, `/{id}/allotments`, and
`/{id}/sessions` with `open`, `pause`, `close` and `current`.
`OrderV1Controller` covers `/active`, `/by-sessions`,
`/market/{marketId}/standing` and `/recent-trades`; `TradeV1Controller` covers
trades.

**The two halves decouple.** Everything reachable through `marketplaces`,
`orders` and `users` can move to `_v1()` now, without waiting on fm-server. That
shrinks the HAL dependency from five links to three and is worth doing on its
own: it is the bulk of the 42 sites, and it makes what remains a three-line
change once the routes land.

Once all five are gone, `ApiRoot` and `_process_template` go with them. `ApiRoot`
is already private in all three languages, so deleting it needs no version bump.

### 2. Diagnostics: three languages, three postures

*New in 0.4. Raised by the run of 2026-09-22, where a study ran through a
dropped stream and left no evidence it had.*

The SDK narrates what it cannot keep to itself — a transport error, an
unreadable frame, a failed reseed, a sequence gap, a proxy that is not
forwarding the request scheme. Where that narration goes differs by language,
and one of the three was made consistent in 0.3.x:

| SDK | Narration | Assessment |
|---|---|---|
| Python | `logging.getLogger(__name__)` in `desk.py`, `events.py` | the canonical library pattern; nothing to do |
| Java | `System.Logger` | brought level with Python; was `System.err.println` |
| TypeScript | `console.warn` / `console.error` | the Node idiom, but the only one a host cannot redirect |

**The question for 0.4 is whether TypeScript needs an injectable logger.** It is
the one of the three where a host has no way to route SDK output into its own
logs: Python's `logging` and Java's `System.Logger` both defer to whatever the
application configured, and `console.warn` defers to nothing. The counter-argument
is that `console.warn` *is* what Node libraries do, and that adding a
`logger?: Logger` option is new public API in an alpha SDK to solve a problem
nobody has filed.

**What is NOT the question.** All three SDKs already expose typed hooks for the
events that matter — `onGap`/`on_gap` and `onRecovery`/`on_recovery` — and those
are the interface. The narration is the fallback for a caller who subscribed to
neither. Anyone who wants these events in their own telemetry can have them
today, in any of the three languages, without this change; fm-robots simply
never subscribed until 2026-09-23.

So this is a convenience question, not a capability one, and it should be priced
that way.

### 3. Per-market subscriptions have no all-markets form

*New in 0.4. Raised while reviewing the Desk surface on 2026-09-23.*

The six subscriptions split two ways, and the split is clean:

| Scope | Subscriptions | Bulk read |
|---|---|---|
| Per market | `onBookChange(marketId, ...)`, `onTrade(marketId, ...)` | `books()`, `tapes()` |
| Marketplace-wide | `onSessionChange`, `onHoldingChange`, `onGap`, `onRecovery` | — |

So a caller watching a whole marketplace loops:

```java
for (var m : desk.markets()) desk.onBookChange(m.id(), handler);
```

Note this is **not** a book-versus-tape asymmetry. Books and tapes are treated
identically: both have a per-market subscription and an all-markets bulk read,
in all three languages, with no overload anywhere. Whatever is decided applies
to both or neither.

**The case for an overload** is that the loop is boilerplate every consumer
writes, and the handler then has no idea which market fired — so the signature
is not the obvious one. `Consumer<Book>` would have to become
`BiConsumer<Long, Book>`, or `Book` would have to carry its market id, and the
second is a change to a published type rather than an addition beside it. That
is three languages times two events, plus a decision about the payload.

**The case against** is that the loop is exactly equivalent and one line. Which
brings up the thing actually worth noticing:

> **A desk's market set is fixed for its lifetime.** `markets()` is documented
> as "captured when the desk was opened" — `List.copyOf` in the constructor,
> with `BookIndex` and `TapeIndex` built from that list and never refreshed. A
> market added to the marketplace after the desk opened has no book, no tape,
> and cannot be subscribed to.

An all-markets subscription would therefore **not** fix the case it looks like
it fixes. Built on the same fixed index, it would miss a new market exactly as
the loop does. So the two questions are separable, and the second is the
interesting one:

1. *Convenience.* Should the per-market subscriptions have an all-markets form?
   Low value, real cost, and the loop is equivalent.
2. *Lifetime.* Should a desk notice a market added while it is open, or is
   "re-open the desk" the answer? That is a behaviour question, not a signature
   one, and nobody has hit it — every study creates its markets before opening a
   desk. Worth settling before anything is built on the assumption either way.

Taking (1) without (2) would ship an API whose name promises what it does not
do, which is worse than the loop.

---

## Rejected for 0.4: an `Endpoint` value type

*Carried from DESIGN-0.3, where it was rejected for 0.3 after being live for
0.2. The argument has not changed and neither has the answer.*

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
Neither has happened since this was written for 0.3.

---

## Not revisited

**Study views staying Java-only.** Asked and answered in 0.3: the two readers
are the study CLI and fm-server, both JVM, and a copy on PyPI or npm would be a
third copy to keep level with no reader for it. See
[UPGRADING-0.3.md](UPGRADING-0.3.md). Revisit only if a study is written in
Python or TypeScript.
