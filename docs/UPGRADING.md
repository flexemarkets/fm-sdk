# Upgrading fm-sdk

One section per release that changed something a consumer may have to act on,
newest first. Read each between the version you are on and the one you are
moving to, oldest first: from the bottom up.

| Version | Covers |
|---------|--------|
| [0.19](#019-from-018x) | a lost connection is an `ApiError` in Python and TypeScript; a desk that cannot reseed or apply a frame goes on and says so; a stream connect fails after 10 s; a `PendingOrder` cannot be cancelled; a desk says when its stream drops, and `StreamDropped` gains `permanent`, which Java callers that build or destructure it must handle |
| [0.18](#018-from-017x) | additive: `sendLimit` and `sendCancel` can go over REST, in batches, with one request in flight (fm-server 4.19) |
| [0.17](#017-from-016x) | additive for callers: `sendCancel` over the socket, in order with `sendLimit`; Java implementers have a method to add; a credential passed replaces `~/.fm/credential`'s whole |
| [0.16](#016-from-015x) | no signature changes; `sendLimit`'s socket comes back within 5 s, and an order it cannot send is refused after 5 s instead of placed late |
| [0.15](#015-from-014x) | additive for callers: `indicative`, `sendLimit` over the socket, a call market's sealed book; Java implementers have methods to add |
| [0.14](#014-from-013x) | needs fm-server 4.17; paged reads of growing collections, creates sent with an `Idempotency-Key`, and `clearNow` and `calls` on 4.17's routes |
| [0.13](#013-from-012x) | three new methods to implement, `statusCode()` returns `Integer`, new required TypeScript fields, no currency symbol, and an order resent once when its answer is lost |
| [0.12](#012-from-011x) | needs fm-server 4.16: sign-in and the stream move to `/api/v1`; `allWidgets` works, and a call arrives on the stream |
| [0.11](#011-from-010x) | call-auction markets (fm-server 4.15): four methods to implement in Java, three new `Market` fields, and a market that is not necessarily continuous |
| [0.10](#010-from-09x) | only Java's study contract 0.3, in which a study names the robots it needs; record patterns over `Close` break |
| [0.9](#09-from-08x) | only Java's study contract 0.2: session orders, early endings and setup context; record patterns over `Carry` break |
| [0.8](#08-from-07x) | additive: refresh-token sign-in (fm-server 4.7), last sign-in on `Person`, and the study carry step; code that builds a `Person` may need two more fields |
| [0.7](#07-from-06x) | `Order` gains `ownerTargetId`, which code that builds an `Order` itself must pass |
| [0.6](#06-from-05x) | nothing removed; acting as another account in Java and `CliBuilder` bounds answer differently |
| [0.5](#05-from-04x) | per-market shortcuts removed from the book and tape indexes, and what to call instead |
| [0.4](#04-from-03x) | no signature changes, but calls move to fm-server's model routes and a few answer differently |
| [0.3](#03-from-02x) | which is additive, and says so |
| [0.2](#02-from-01x) | the package moves, the renames, and the five changes that are not renames |
| [0.1](#01-from-00x) | Migrating a codebase from 0.0.x to 0.1.x, in all three languages |

---

## 0.19: from 0.18.x

No signatures change, and it needs the same server as 0.18.0. What changes for a
robot: what a lost connection or an unreadable answer raises, what a desk does
when it cannot reseed or apply a frame, how long a stream connect may take, what
`cancel()` on a `PendingOrder` answers, how a desk reports a dropped stream, and
that an order answered with no id is refused.
Java has one signature change: see `StreamDropped` below.

### A lost connection is an `ApiError` (Python, TypeScript)

A request that never got an answer raised the HTTP library's own error, which
went straight past a caller catching `FlexemarketsError`. It now raises
`ApiError` from every call, as the Java SDK always has, with the original as its
cause. An order or a create is still sent once more first, under the same id.

| | Before | Now |
|---|---|---|
| Python | `httpx.TransportError` | `ApiError`; `__cause__` is the httpx error |
| TypeScript | `TypeError`, or an error named `TimeoutError` | `ApiError`; `cause` is that error |

Code catching the old types no longer catches this. An `ApiError` also means an
answer that came and could not be read, so to tell them apart look at the cause.
Either way an order may have been placed: read the book or your orders before
placing it again.

### A desk that cannot reseed after a gap goes on (all three)

When the read that repairs a sequence gap failed, the desk stopped for good with
nothing said: in Python and Java its thread ended and the books froze, and in
TypeScript the rejection went unhandled, which ends a Node process by default.

Now the desk stays up, `onRecovery` hears `success: false` with the reason, and a
later frame tries again, at most once every 5 seconds. Frames past the gap are
dropped until then rather than applied to a book missing what came before them.
`onRecovery` hears `success: true` when a reseed works.

So `onRecovery` now fires for a gap as well as a reconnect, though a gap that
reseeds first time is still only `onGap`. A robot that stops trading on
`success: false` will start again on `success: true`. One that checked whether the
desk's thread was alive, or closed the desk to recover, can stop.

TypeScript only: a desk left stale by a failed reconnect used to hold every frame
and ignore later reconnects, until the caller closed it. It now behaves as above,
and a frame next in sequence applies, as it does in Python and Java.

### Every Python call renews an expired token

`active_orders`, `recent_trades`, `download_holdings`, `delete_user`,
`remove_widget` and the CSV uploads answered an expired token with
`AuthenticationError`, where every other call signed in again and carried on. A
desk's reseed is the first two, so a robot past its 24 hours could not recover
from a gap or a reconnect. A workaround that re-reads the session to renew can go.

### `first_robot.py` runs

It raised `TypeError` as soon as a session opened: `Desk.markets` is a property,
and the example called it. Anything copied from it needs the same change.

### The expired-token error's name

`docs/API-REFERENCE.md` gave it as `AUTHENTICATION_TOKEN_EXPIRED`. fm-server sends
`ACCOUNT_TOKEN_EXPIRED`, as the SDKs have always read it. Code branching on the
name in the document never matched.

### An answer that cannot be read is a `FlexemarketsError` (all three)

Valid JSON of the wrong shape, or an answer that would not decompress, escaped as
whatever the language threw on reading it. Each is now an `ApiError`, with the
original as its cause. Code catching those types no longer catches this.

| | Before | Now |
|---|---|---|
| Python | `AttributeError`, `TypeError`, `httpx.DecodingError` | `ApiError` |
| TypeScript | `TypeError`; a number, string or list read as an empty object | `ApiError` |
| Java | a Jackson exception, from snapshot and page reads and sign-in; `null` read as no answer | `ApiException` |

A request on a closed Python client is a `ConnectionFailedError`, where it was a
`RuntimeError`. Python's `signup` now renews an expired token, as every other call
does.

### An order with no id is unreadable (all three)

Every field of an order has a default, so an answer of `{}` read as a successful
order 0, which a robot kept as resting or cancelled. An order must now name its
`id`. An answer, a list row or a batch entry without one is an `ApiError`
(`ApiException` in Java), and a stream frame without one is `FrameUnreadable`, so
a desk reseeds. An `id` of 0 is still read. Python and TypeScript also refuse an
`id` that is not a whole number; Java's Jackson settings still read `"5"` as 5.

A fake server or test fixture whose orders have no `id` now fails this way: give
them one.

A socket order whose `ORDER-ACCEPTED` frame cannot be read now fails with that same
`ApiError`, where it waited until a broadcast named it or the stream closed. It
may have been placed, as with REST's unreadable answer, so check before sending it
again. It arrives as an `OrderRejected` after the `FrameUnreadable`, so code reading
the events itself should not take every `OrderRejected` as proof the order was not
placed. In Python and TypeScript, an `ORDER-REJECTED` frame that is not JSON is a
refusal, as it is in Java, where it hung.

### Python ships type information

The package now carries `py.typed`, so mypy and pyright read the SDK's annotations
in your code, and a checker that passed before may now report real problems.
`fm.Desk` is now what `fm.desk()` returns, the handle, so `desk: Desk` is correct;
it was the shared desk behind the handle, which a caller never holds, so code
calling `fm.Desk(...)` directly must use `fm.desk.Desk`. `contra()` of no side is a
`ValueError`, where it was an `AttributeError`. A STOMP frame sent as a binary
message is read, where it dropped the stream.

### A call promising an account or person refuses an answer without one (all three)

An answer of `null`, or an approval with no account in it, returned `None` or
`null` from a method promising the thing: `account_by_id`, `user_by_id`,
`create_user` and `approve_account` in Python, their counterparts in TypeScript,
and `approveAccount` in Java. Each is now an `ApiError` (`ApiException` in Java).
TypeScript's `accountById` and `approveAccount` no longer say `Account | null`, so
a null check on their result is now dead code a linter may flag.

### A frame a desk cannot apply reseeds it (all three)

An `ORDERS-UPDATE` whose orders the book could not apply ended the desk's thread
in Python and Java, and in TypeScript threw out of the socket's listener, which
ends a Node process unless something handles it. In Python and TypeScript a price
or units that were not whole numbers went in quietly, and in Python made every
later read of the book raise.

The desk now treats such a frame as a gap: it reads the snapshot again, and if
that fails `onRecovery` hears `success: false`. Opening a desk on a snapshot it
cannot apply raises `ApiError`.

### A stream connect has a limit (all three)

A server that stalled could hold a connect for good, and a reconnect too, which
then neither retried nor said anything. In Python and TypeScript `CONNECTED` had
no limit; in TypeScript and Java neither had the upgrade. Each wait is now 10 s;
in TypeScript the 10 s covers both, and in Java each has its own.

- A first connect that fails, including opening a desk, raises
  `ConnectionFailedError` in Python and TypeScript, where it raised the socket
  library's own error, and `ApiException` in Java, as before.
- A reconnect that fails tries again, with the usual back-off.
- A STOMP `ERROR` in answer to `CONNECT` now fails the connect at once, with the
  server's message. Python and TypeScript took it as connected, and read nothing
  from then on.

If your server can take longer than 10 s to accept a connection, as one under
a burst of connecting robots can, stagger the robots.

### A `PendingOrder` cannot be cancelled (Python, Java)

`cancel()` now answers false and does nothing: the order has been handed over, and
nothing here can call it back. To take one off the book, cancel the order it made.

In Python, cancelling one ended the thread delivering answers, so no later order
on that marketplace was answered. In Java the caller got a
`CancellationException` for an order that went on to be placed.

### A desk says when its stream drops (all three)

A drop reached only the log, so a robot went on quoting from books that had
stopped. Worse, a stream lost for good, its token refused twice, looked the same:
the books froze, `onRecovery` never fired, and nothing said so.

`onStreamDropped` / `on_stream_dropped` now hears every drop, and `permanent` on
the event says what happens next:

| `permanent` | What it means | What follows |
|---|---|---|
| false | the stream is being restored | `onRecovery`, once the desk has reseeded |
| true | it has given up, and the books will not update again | nothing; a new desk is what starts over |

A robot that has seen a drop should stop trusting the books until `onRecovery`
says `success: true`, and stop for good on `permanent`. One drop that ends for good
arrives as two events: the drop, then the refusal with `permanent` true. A handler
registered after that hears of it at once, because a desk is shared and may have
lost its stream before this robot opened it. The desk stays open either way, so
what it holds can still be read.

Readers of the raw stream get the same field. `StreamDropped` was told apart by
`PermissionError` in Python and the listener's closed state elsewhere; use
`permanent` in all three.

**Java:** `StreamDropped` is a record, so it changes shape:

| | Before | Now |
|---|---|---|
| Building one, usually in a test | `new StreamDropped(failure)` | `new StreamDropped(failure, false)` |
| A record pattern | `StreamDropped(var failure)` | `StreamDropped(var failure, var permanent)` |

`Desk` gains `onStreamDropped`, which code implementing it must add. In Python
`permanent` is required too: `StreamDropped(exception=e, permanent=False)`.

### Checking your code (0.19)

```bash
# Python
grep -rnE "httpx\.[A-Za-z]*(Error|Timeout)|websockets\.|\.cancel\(\)" .
# TypeScript: catch blocks that test for these
grep -rnE "instanceof TypeError|TimeoutError" .
# Java
grep -rnE "JacksonException|CancellationException|new StreamDropped\(|StreamDropped\(var|implements .*\bDesk\b" .
# The name that never matched, and threads watched by name
grep -rnE "AUTHENTICATION_TOKEN_EXPIRED|fm-desk-" .
```

---

## 0.18: from 0.17.x

0.18.0 is additive for code that **calls** the SDK: no signature changes, and
code that connects as before gets the behaviour it had. Java code that
**implements** `Writing` or `Flexemarkets` has one method to add, a second
`sendCancel`.

It needs the same server as 0.17.0, fm-server 4.17, except where noted:
the REST order route below needs fm-server 4.19, as `submitLimits` does, and a
cancel by submission id needs fm-server 4.20.

### What is new (0.18)

**`sendLimit` and `sendCancel` can go over REST.** A client connected with
the REST order route sends them as `POST .../orders/batch` instead of over the
marketplace's socket:

| | Connect |
|---|---|
| Java | `Flexemarkets.connect(credential, endpoint, description, OrderRoute.REST)` |
| TypeScript | `Flexemarkets.connect(credential, endpoint, description, { orderRoute: "REST" })` |
| Python | `Flexemarkets.connect(credential, endpoint, description, order_route=OrderRoute.REST)` |

One request is in flight at a time. Orders sent while it is out -- at most 64;
a caller sending more waits for room -- go together as the next request, in
the order sent, and fm-server matches each list in order. One at a time this
is `submitLimit`; in a burst the requests grow to 64 orders. Each order's
`PendingOrder` completes as it does on the socket: with the order, or with
what `submitLimit` would have thrown for its refusal. A request whose answer
was lost is sent once more, whole, which the submission ids make safe; one
that still has no readable answer, or is answered with a 5xx, leaves each of
its orders `OrderOutcomeUnknown` (it may have been placed), never refused.

The route is the client's for its whole life, so its orders never take two
routes and overtake each other on the way. The socket stays the default and
the faster route; REST is for a caller that cannot hold a socket open
(docs/SUBMIT-RATE.md, section 5).

One difference from the socket: over REST, `sendCancel` matches its order by
the `original` it is given, as the socket did before fm-server 4.19. Pass the
`id` of the order `sendLimit` or `submitLimit` answered, or `order.original`
for an order from the book or the stream -- not the id of a remainder a split
left behind.

**An order can be cancelled before it is answered.** `sendCancel` also takes
the submission id the order was sent with, which its `PendingOrder` has from the
moment it is sent:

| | Send a limit, then pull it |
|---|---|
| Java | `var limit = fm.sendLimit(mp, market, side, units, price);`<br>`fm.sendCancel(mp, market, limit.submissionId());` |
| TypeScript | `const limit = await fm.sendLimit(mp, market, side, units, price);`<br>`await fm.sendCancel(mp, market, limit.submissionId);` |
| Python | `limit = fm.send_limit(mp, market, side, units, price)`<br>`fm.send_cancel(mp, market, limit.submission_id)` |

The cancel goes through the same window as the limit, on the socket or the REST
route, and fm-server takes them in the order sent, so it cannot overtake the
limit. fm-server finds the order and cancels through its original, so a split
makes no difference to this form. Only the caller's own LIMIT orders are found.
One it has none of in the marketplace, which includes a limit that was itself
refused, is refused as not found: 404 `ORDER_NOT_IN_MARKETPLACE` on either
route. fm-server 4.23 and earlier answer it 400 `ORDER_INVALID` on the socket.

**A failed socket order may have gone through.** From the fm-server release
after 4.23, an order on the socket fails with the status, `error` and message
REST would answer. No SDK code changes for this, but a server fault is now
answered as one: 500 `SERVER_ERROR`, or 503 `SERVER_SUPERSEDED` while a server
is being replaced. Both come as a `ConnectionFailed` carrying that status, not
as a refusal, and the order may have been placed. Before sending it again, look
for its `submissionId` among your orders, or the same trade can happen twice.

### Checking your code (0.18)

```bash
# Java classes implementing Writing or Flexemarkets, which need sendCancel(long, long, String) (run from the robot's source root)
grep -rnE "implements .*(Writing|Flexemarkets)\b" .
```

Nothing else to change. To try the REST route on a robot that already uses
`sendLimit`, connect with it; nothing else changes.

---

## 0.17: from 0.16.x

0.17.0 is additive for code that only **calls** the SDK. Code that
**implements** the SDK's types has two methods to add:

| | What breaks | Fix |
|---|---|---|
| Java | a class implementing `Writing` (and so `Flexemarkets`) -- a test double, fm-server's in-process client -- no longer compiles: `sendCancel(long, long, long)` and `submitLimits(long, List<Limit>)` are abstract, as `sendLimit` is | implement them, or throw as the class does for other writes it cannot make |
| TypeScript | nothing | |
| Python | nothing | |

One fix changes what a client signs in with, for code that passes a
credential while `~/.fm/credential` also holds one; see below.

It needs the same server as 0.16.0, fm-server 4.17, except for
`submitLimits`, which needs fm-server 4.19.

### What is new (0.17)

**Cancels can go over the socket.** `sendCancel` (`send_cancel` in Python)
takes `submitCancel`'s arguments -- the marketplace, the market and the
order -- and returns a `PendingOrder` at once, as `sendLimit` does. It
completes with the CANCEL order `submitCancel` would have returned, or fails
with what it would have thrown. The cancel goes on the marketplace's order
socket with the limits `sendLimit` sends, through the same window of 64, the
same 5 s refusal of one the socket could not send, and the same resend when
the socket comes back. fm-server takes one socket's frames in the order sent,
so a cancel and the limit sent after it reach the book in that order: a robot
can replace a quote without waiting for the cancel's answer.

```python
pending = client.send_limit(mp, market, "BUY", 1, 950)
order = pending.result()
client.send_cancel(mp, market, order.id)          # goes first
client.send_limit(mp, market, "BUY", 1, 955)      # then this
```

Two things it cannot do that `submitCancel` can:

- **Before fm-server 4.19, it names the order by its original.**
  `submitCancel`'s DELETE looks the order up and cancels its lineage from any
  of its ids. Up to fm-server 4.18 the socket's CANCEL went to the exchange as
  named, which matches by `original`: the `id` of a remainder a split left
  behind was recorded, consumed by itself, and cancelled nothing, so pass
  `order.original` when the order came from the book or the stream. From
  fm-server 4.19 the socket looks the order up as the DELETE does, so any id
  of its lineage cancels it, and an id the marketplace does not hold is
  refused.
- **It needs an answered order.** An order has no id until fm-server answers
  it, so an order still pending cannot be cancelled this way.

**Several limit orders in one request.** `submitLimits(marketplaceId, limits)`
(`submit_limits` in Python) posts a list of `Limit` values -- each
`submitLimit`'s arguments but the marketplace -- to fm-server 4.19's
`POST .../orders/batch`, up to 64 to a request and a longer list as several,
one after the other. fm-server matches them in list order, each as its own
POST would have been, so the book sees them in the order given, for one round
trip instead of one each. It returns one `PendingOrder` per order, already
answered: the order, or what `submitLimit` would have thrown for its refusal.
One refusal does not stop the rest. A request whose answer was lost is sent
once more, whole, which the orders' submission ids make safe. It is the REST
route for a burst; `sendLimit` over the socket remains the faster one
(docs/SUBMIT-RATE.md, section 5).

```python
from fm import Limit

for pending in client.submit_limits(mp, [Limit(market, "BUY", 1, 950), Limit(market, "BUY", 1, 945)]):
    order = pending.result()                      # or raises the refusal
```

### What changes in behaviour (0.17)

**A credential you pass replaces `~/.fm/credential`'s whole** (fm-sdk#8).
A token, a refresh token or a credential file passed to `connect` used to
replace only the fields it named. A token or refresh token left in the default
file outlived it, and the client signed in with the default file's refresh
token -- so every client on the machine presented the same one, each using up
the others'. Now a credential passed in any form replaces everything the
default file would have supplied. A client that passed a token and relied on
the default file's refresh token to renew it no longer has one: pass the
refresh token (`refresh:<token>`) or a credential file that carries it.

**`subscribe(..., history=True)` takes a history fm-server sends in chunks.**
fm-server from the release after 4.18.1 sends a subscriber's history as
several ORDERS-UPDATE frames of at most 1 MB, each marked `history: i/n`, one
at a time. fm-sdk puts them back together, so the history still arrives as the
first `OrdersUpdate`, once, under one seq; an fm-server that sends it in one
frame, with no mark, works as before. What the history holds is fm-server's
choice: every resting order, plus the orders behind the marketplace's last
`fm.history.trades` trades (default 2000), at most four times that many --
not necessarily every order of the session. If the connection drops part-way
through, the part received is discarded and the reconnect delivers the whole
history again. A malformed or out-of-turn chunk is reported as
`FrameUnreadable` (`frame-unreadable` in TypeScript), and no history is
delivered for that attempt.

### What fm-server changed under you

These came with fm-server 4.18.1 and 4.19, whatever fm-sdk version calls
them. fm-sdk's own calls are unaffected by every one; code that reads the
server's answers itself, or calls routes the SDK does not wrap, may not be.

**V1 refuses a query parameter it does not take** (4.18.1). A
`GET /api/v1/...` with a parameter its route does not read -- a misspelt
`?sesion=`, or one a route never had -- used to be ignored, so the answer was
the unfiltered one and looked right. It is now a 400 `REQUEST_INVALID` naming
it: `GET /api/v1/marketplaces takes no ?bogus=; it takes ?limit= and
?before=`. `?canary=` is taken everywhere, and `/api` without `/v1` is not
refused. Every parameter fm-sdk sends is one its route takes.

**Socket order refusals carry REST's code** (4.18.1). An order refused on the
order socket was answered `ORDER_INVALID` whatever the reason. It now has the
code and message the same order's POST would have had: `ORDER_INSUFFICIENT_ASSETS`,
`ORDER_INVALID_INSTRUMENT`, `ORDER_ALREADY_CANCELLED` (409) or
`ORDER_NOT_ALLOWED`, and `ORDER_INVALID` only for what is invalid. A
`sendLimit` or `sendCancel` that fails raises with that code, as `submitLimit`
and `submitCancel` always did. Code that tested a socket refusal for
`ORDER_INVALID` to mean "refused" should test for the refusal instead.

**A study's roster, payments and consents answer JSON when asked** (4.18.1).
`GET /api/v1/studies/runs/{runId}/roster` and `.../payments`, and
`GET /api/v1/marketplaces/{id}/consents`, were CSV only. With `Accept: application/json` -- named outright, above
`text/csv`, with no `text/plain` beside it -- they answer rows as JSON; any
other `Accept`, a browser's included, still gets the CSV. fm-sdk has no call
for these.

**A market's decimals can be other than 2 and 0** (4.19, fm-server#1059). A
manager can now set a market's `priceDecimals` and `unitDecimals` and a
marketplace's `cashDecimals`, which fm-sdk has read since 0.13 with defaults
2, 0 and 2. Before 4.19 every market had the defaults, so code that assumed
cents and whole units was never wrong; now it can be. Read prices, units and
cash through `Decimals` at the market's and marketplace's scale.

### Checking your code (0.17)

```bash
# Java classes implementing Writing or Flexemarkets, which need sendCancel and submitLimits (run from the robot's source root)
grep -rnE "implements .*(Writing|Flexemarkets)\b" .
# Calls to connect that pass a credential, which no longer borrow ~/.fm/credential's refresh token
grep -rnE "Flexemarkets\.connect|\bconnect\(" .
# Socket refusals tested for ORDER_INVALID, which now carry their own code
grep -rn "ORDER_INVALID" .
# Prices, units or cash turned into dollars by a hard-coded 100, which markets with other decimals break
grep -rnE "/ ?100(\.0)?\b|\* ?100(\.0)?\b|%\.2f|toFixed\(2\)" .
```

---

## 0.16: from 0.15.x

0.16.0 changes no signature, in any language. Code that **implements** the
SDK's types needs nothing, and so does code that **calls** it, with one
exception: code that sends orders over the socket with `sendLimit`
(`send_limit` in Python). For it, an outage now ends differently:

- the order socket usually comes back within about five seconds of the
  server, not up to a minute after it;
- an order sent while the SDK knows the socket is down is refused after 5 s,
  as not sent, where it was held and placed whenever the socket returned.

A robot that sends orders with `sendLimit` must expect that refusal, and
decide whether to send the order again. Code that sends orders only with
`submitLimit` sees no change.

| | Refused, not sent | Unchanged: sent, answer lost |
|---|---|---|
| Java | `PendingOrder` completes exceptionally with `ConnectionFailedException` (the cause of `join()`'s `CompletionException` or `get()`'s `ExecutionException`) | `OrderOutcomeUnknownException` |
| TypeScript | `pending.result()` rejects with `ConnectionFailedError` | `OrderOutcomeUnknownError` |
| Python | `PendingOrder` fails with `ConnectionFailedError` | `OrderOutcomeUnknownError` |

It needs the same server as 0.15.0: fm-server 4.17.

### What changes in behaviour (0.16)

**An order socket comes back within five seconds.** Every socket reconnected
on one back-off, doubling from 2 s to a minute (0.13.0). That suits a
subscription: a classroom's robots should not all ask for their history the
moment fm-server returns. It did not suit the socket `sendLimit`'s orders go
over: through a 90 s outage the waits grew so long that trading resumed 24 to
33 s after the server was back. The order socket now waits at most five
seconds between attempts: it still doubles from 2 s and draws from the upper
half of the range (1-2 s, 2-4 s, then 2.5-5 s), so robots still come back out
of step. A subscription keeps the minute. The ceiling is
`RECONNECT_MAX_SECONDS` in Python's `fm.order_stream`, `RECONNECT_MAX_MS` in
TypeScript's `order-stream.ts` and `OrderStream.RECONNECT_MAX_MS` in Java's
`fm.internal`; none is a setting a caller changes.

**An order the socket could not send is refused after 5 s.** With 0.15.0 an
order sent while the socket was down was held, and placed when the socket
returned: through a 90 s outage, quotes went onto the book a minute and a
half after the robot sent them -- stale, and on top of any it sent again once
it saw the stream come back. Now an order that has not gone out waits at most
5 s (`HOLD_SECONDS` in Python, `HOLD_MS` in TypeScript and Java) for the
socket. If the socket is back by then, the order goes, in the order sent. If
not, its `PendingOrder` fails with `ConnectionFailedException`
(`ConnectionFailedError` in Python and TypeScript), the message saying the
order was not sent because the order socket was down for more than 5 s. It is
the type `submitLimit` meets when REST answers 503 in an outage, but it
carries no status: `statusCode()` (`status_code`) is null.

An order the SDK had already tried to send is not refused this way: it may
have been placed, so it is sent again, as before, whenever the socket
returns. If the stream closes first, it fails as `OrderOutcomeUnknownException`
(`OrderOutcomeUnknownError`), as in 0.15.0. That includes an order sent
before the SDK had noticed the drop -- a socket that dies silently is noticed
only when its heartbeat lapses -- so such an order can still land late.

**`sendLimit` can still wait out an outage.** Orders sent before a drop keep
their place in the window of 64 unanswered orders until the socket returns. A
robot with 64 outstanding waits in `sendLimit` for the reconnect, and its next
order meets no 5 s refusal.

### What to handle

Where a robot reads the outcome of a `PendingOrder`, treat the two failures
differently:

- `ConnectionFailedException` / `ConnectionFailedError` **with no status**:
  the order was not sent and cannot have been placed. Sending it again cannot
  double it; whether to is the robot's decision -- the price it was sent at
  may be stale by the time the socket is back. The same type **with a status**
  (500, 503) is fm-server refusing an order it received, as REST's would be.
- `OrderOutcomeUnknownException` / `OrderOutcomeUnknownError`: it may have
  been placed. Read the orders before sending it again.

A robot that waited out an outage on its pending orders, counting on them to
be placed when it ended, now gets refusals after 5 s instead, and has to send
again what it still wants on the book.

### Checking your code (0.16)

```bash
# Callers of the socket send, and where their outcomes are read (run from the robot's source root)
grep -rnE "sendLimit|send_limit|PendingOrder" .
# Failure handling that may need to tell not-sent from outcome-unknown
grep -rnE "ConnectionFailed(Exception|Error)|OrderOutcomeUnknown(Exception|Error)|Flexemarkets(Exception|Error)|CompletionException" .
```

---

## 0.15: from 0.14.x

0.15.0 is additive for code that only **calls** the SDK. Code that
**implements** the SDK's types has one method to add:

| | What breaks | Fix |
|---|---|---|
| Java | a class implementing `Reading` (and so `Flexemarkets`) -- a test double, fm-server's in-process client -- no longer compiles: `indicative(long, long)` is abstract, as every read on `Reading` is | implement it, or throw as the class does for other reads it cannot answer |
| Java | a class implementing `Administration` no longer compiles: `createCallMarket` with a trailing `String callBook` is abstract, and the seven-argument form is now a default that calls it with `SEALED` | implement the eight-argument form in place of the seven |
| Java | code calling `Market`'s 17-argument constructor still compiles: that shape is kept, sealed. Only the new canonical constructor takes `callBook` | |
| Java | a class implementing `Writing` (and so `Flexemarkets`) no longer compiles: both `sendLimit` forms are abstract | implement them, or throw as the class does for other writes it cannot make |
| Java | code calling `Order`'s earlier constructors still compiles: they are kept, with no submission id. Only the new canonical constructor takes `submissionId` | |
| TypeScript | a hand-built `Market` literal lacks `callBook`, a required field | add `callBook: MARKET_CALL_BOOK_SEALED` |
| TypeScript | a hand-built `Order` literal lacks `submissionId`, a required field | add `submissionId: null` |
| Python | nothing | |

It needs fm-server 4.17, which every 0.14 client already needs. One read
needs more: `currentSessionsPage` pages only on fm-server 4.17.3 and later.
Before that the server ignores `limit` and `before`, so the first page holds
every current session and has no next.

### What is new (0.15)

**A call market can show the price it would clear at now.** A call market
(`Market.isCall()`) has a book setting, `callBook`:

- `SEALED`, the default and what every call market was before: a participant
  sees only their own resting orders until a call trades them.
- `INDICATIVE`: everyone in the marketplace also sees the price the book
  would clear at if it were called now, and the volume, but still no other
  participant's orders.

`Market.showsIndicative()` (Java), `Market.shows_indicative()` (Python) and
`showsIndicative(market)` (TypeScript) say whether a market publishes it. A
market from an older server, or one that does not say, is sealed.

The value is a `CallIndicative`: `price` (null when nothing crosses), `volume`
(0 then), `seq` and `at`. Read it once with `indicative(marketplaceId,
marketId)` when you subscribe; the stream then delivers each change as a
`CallIndicative`, from the `CALL-INDICATIVE` frame. Two values can arrive out
of order, so keep the one with the highest `seq` for the session and drop the
rest. The SDK delivers them as sent; it does not order them for you.

A sealed market, a continuous one, or no open session refuses the read.

An administrator creates an indicative market with `createCallMarket`'s
trailing `callBook` (`call_book` in Python): `CALL_BOOK_INDICATIVE`. Left out,
the server makes the market sealed, as before.

**Orders can go over the socket, several at a time.** `sendLimit`
(`send_limit` in Python) takes `submitLimit`'s arguments and returns a
`PendingOrder` at once, without waiting for the answer: a future that
completes with the order, or with the refusal `submitLimit` would have
thrown. The orders go over the marketplace's own socket, opened by the first
of them, and fm-server matches them in the order sent; at most 64 are
unanswered at once, and the call waits, in turn, while they are. An order
whose answer is lost when the socket closes fails as
`OrderOutcomeUnknownException` (`OrderOutcomeUnknownError` in Python and
TypeScript), never as refused, so a robot does not place it twice. Against a
local server this sent 500 to 1,000 orders a second where `submitLimit`, one
at a time, sent about 200. `submitLimit` is unchanged.
The design and the measurements are in [SUBMIT-RATE.md](SUBMIT-RATE.md).

An order now carries the `submissionId` (`submission_id`) the client named
it with, null for one sent without; it is what ties an order arriving on the
stream to the one sent.

**Every marketplace's current session, in one read.** `currentSessions()`
(`current_sessions()`) answers the current session of each marketplace the
caller can see, as a `CurrentSession`: the marketplace, the session and its
state. A marketplace with no session is left out. `currentSessionsPage(limit,
before)` pages it as `marketplacesPage` pages the marketplaces: by
marketplace, so a page can hold fewer rows than `limit` and still have a
next.

---

## 0.14: from 0.13.x

0.14.0 follows fm-server 4.17, the release that made the V1 API consistent,
and **needs it**: two calls moved to its routes, and a create resent to an
older server makes a second one. Read [What you need](#what-you-need-014) before
anything else.

Otherwise it is additive. Code that only **calls** the SDK needs nothing; the
paged reads are new, and the changes in behaviour below are ones a caller
does not see. Code that **implements** the SDK's types needs nothing either:

| | What breaks | Fix |
|---|---|---|
| Java | nothing: the paged reads on `Flexemarkets` are default methods that page the whole read, so a class implementing it -- a test double, fm-server's in-process client -- compiles and pages as before | override them to page at the source, if it can |
| TypeScript | nothing: the paged reads are methods of the `Flexemarkets` class, which a fake extends | |
| Python | nothing | |

### What is new (0.14)

**Growing collections can be read a page at a time.** fm-server 4.17 pages
every collection that grows with use: `?limit=N` answers the `N` newest by
id, `?before={id}` the ones older than that, and
`Link: <...?before={id}>; rel="next"` says older ones remain. Seven reads
have a paged form:

| Whole | A page |
|---|---|
| `marketplaces()` | `marketplacesPage(limit[, before])` |
| `sessions(marketplaceId)` | `sessionsPage(marketplaceId, limit[, before])` |
| `orders(marketplaceId)` | `ordersPage(marketplaceId, limit[, before])` -- cancellations left out, as `orders` leaves them |
| `users()` | `usersPage(limit[, before])` |
| `connections(marketplaceId)` | `connectionsPage(marketplaceId, limit[, before])` |
| `accounts()` | `accountsPage(limit[, before])` |
| `calls(marketplaceId)` | `callsPage(marketplaceId, limit[, before])` |

Python spells them `sessions_page` and so on. Each answers a `Page`: `rows`,
in the order the whole read answers them, and `next`, the `before` that reads
the older rows -- `null` (`None`) on the last page, which is the page the
server sent no `Link` with, even when it is full. Do not work `next` out from
the rows:

```java
for (var page = fm.sessionsPage(id, 100); ; page = fm.sessionsPage(id, 100, page.next())) {
    page.rows().forEach(this::report);
    if (!page.hasNext()) break;
}
```

`Page.of(rows, id, limit, before)` cuts a whole list the way the server cuts
one, for a fake that holds whole lists to page with.

### What changes in behaviour (0.14)

**Every create sends an `Idempotency-Key`, and is sent once more if its
answer was lost.** Creating a marketplace, a market, a call market or a user,
allocating, uploading holdings, pushing widgets and clearing a call market
now carry a fresh key. When the send fails on the connection -- it may or may
not have landed -- the SDK sends it again under the same key, and fm-server
4.17 answers with what it made the first time. Before, the failure reached
the caller, who would create it again. Orders already did this with their
`submissionId` (0.13.0).

**`clearNow` posts to the marketplace's calls**, `POST
/marketplaces/{id}/calls` with `{"marketId": ...}`, where it posted to the
market's. **`calls(marketplaceId, sessionId)` asks with `?sessions=`**, the
name every V1 list uses for runs, where it sent `?sessionId=`.

### What you need (0.14)

**fm-server 4.17.0 or later.** Against an older server:

- `clearNow` fails: the marketplace's `calls` take no `POST` before 4.17;
- `calls(marketplaceId, sessionId)` answers every session's calls, because an
  older server does not read `?sessions=` there;
- a resent create makes a second marketplace, market or user, because an
  older server ignores the `Idempotency-Key`;
- `ordersPage` is refused with 400, because an older server takes `?limit=`
  on orders only with `?state=TRADED`; the other paged reads answer the whole
  list, as one page with no `next`, because an older server ignores `?limit=`
  there.

### Checking your code (0.14)

```bash
# Callers of the two calls whose route moved
grep -rnE "clearNow|clear_now|\.calls\(" src/
# Whole-list reads worth paging, where a list grows with use
grep -rnE "\.(sessions|orders|users|connections|accounts|calls)\(" src/
```

---

## 0.13: from 0.12.x

0.13.0 is mostly additive: decimal scales on markets and marketplaces, a
`Decimals` class to convert with them, a subscription that carries the
session's history, holdings uploaded from CSV text, a limit order that names
its counterparty and its own reference, and a failure that says what the
server answered. Code that only **calls** the SDK and reads what it returns
needs nothing, with three exceptions in what it prints, waits for and resends,
below.

Code that **implements** or **builds** the SDK's types -- test doubles and
fixtures, mostly -- may need changes:

| | What breaks | Fix |
|---|---|---|
| Java | a class implementing `Flexemarkets` (or `Writing`, `Management`, `Streaming`): three new abstract methods -- `submitLimit(marketplaceId, marketId, side, units, price, ownerTarget, ownerTargetId, clientDescription)`, `uploadHoldingsCsv(marketplaceId, csv)` and `subscribe(marketplaceId, queue, history)` | implement them; delegating the new `submitLimit` to the five-argument one keeps an existing fake's behaviour |
| Java | `HttpException.statusCode()` returns `Integer`, not `int` -- it is now `FlexemarketsException`'s, beside `reason()` and `body()` | source compiles as before (it unboxes); **recompile** anything built against 0.12.x, whose call to `statusCode()I` no longer links |
| Java | record patterns over `Market`, `Marketplace`, `Order`, `Session`, `Security`, `Holding`: each gained components | add them to the pattern; every previous constructor is kept, so `new Market(...)` and the rest compile unchanged |
| TypeScript | an object literal typed as one of these: new required fields -- `Market.priceDecimals`, `Market.unitDecimals`, `Marketplace.cashDecimals`, `Security.initialUnits`, `Holding.initialCash`, `Session.createdDate`, `Session.lastModifiedDate`, `Order.mine`, `Order.originalSessionId` | add them: `2`, `0` and `2` for the scales, `null` for the rest |
| Python | nothing: every new field has a default | |

A jar that shades an fm-sdk 0.12.x or older (a robot built as a fat jar)
must not share a classpath with 0.13.0: whichever `HttpException` loads
first, the other's callers break. Rebuild it against 0.13.0.

### What changes in behaviour (0.13)

**Displays carry no currency symbol, and show each amount at its scale.**
Prices, cash and units are integers at a scale: at the default of two price
decimals, 425 is 4.25. Every display now goes through `Decimals` at its
market's and marketplace's scale. Python's `Book` prints `  4.25` where it
printed `$ 4.25` -- same digits, same width. Today's servers send no scale, so
the defaults (2 for price, 0 for units, 2 for cash) apply and every number
reads as it did.

**A limit order names its submission, and one whose answer was lost is sent
once more.** Each `submitLimit` carries a fresh `submissionId`. If the send
fails on the connection -- it may or may not have landed -- the SDK sends the
same submission again, and fm-server answers with the order it already made
rather than a second one. Before, the failure reached the caller, who would
place the order again and could double the position. fm-server releases up
to 4.16.x can mishandle a copy that arrives before the first has committed
(fm-server W7, fixed after 4.16.0); prefer a server with that fix.

**A dropped stream reconnects with backoff.** The first attempt after a drop
is at once; each failed attempt waits longer, drawn from the upper half of a
range that doubles from 2 s to a minute (1-2 s, 2-4 s, ... 30-60 s). A
connection that falls within a minute of opening counts as a failed attempt.
Before, every client retried every 2 s, together. A drop of the socket a
reconnect has just opened is now restored too; it was lost in Java and
Python.

**TypeScript requests give up after 30 s**, as Java's and Python's do, and
**Python keeps a connection for a minute** between requests rather than
httpx's 5 s.

### What you need (0.13)

The same server as 0.12.0 -- fm-server 4.16.0 or later, for the `/api/v1`
sign-in and stream. The decimal fields are read when the server sends them
and defaulted when it does not.

### Checking your code (0.13)

```bash
# Java: implementations of the client, and record patterns
grep -rnE "implements (Flexemarkets|Writing|Management|Streaming)\b" src/
grep -rnE "(Market|Marketplace|Order|Session|Security|Holding)\(" src/ | grep -E "instanceof|case "
# TypeScript: literals typed as an SDK model
grep -rnE ": (Market|Marketplace|Order|Session|Security|Holding)\b|as (Market|Marketplace|Order|Session|Security|Holding)\b" src/
```

---

## 0.12: from 0.11.x

0.12.0 moves the routes the SDK signs in and streams through to `/api/v1`,
where every other route it calls already was, and **needs fm-server 4.16.0
or later**: against an older server it cannot sign in. Read
[What you need](#what-you-need-012) before anything else.

Otherwise nothing a caller wrote has to change. `allWidgets` keeps its
signature and now works, and the stream delivers one more kind of event, a
call of a call-auction market. Code that reads every event off a
subscription sees the new one:

| | What breaks | Fix |
|---|---|---|
| Java | nothing at compile time: the queue given to `subscribe` or `listen` takes `Object`, and now also receives `MarketCall` -- a consumer whose default branch throws or logs meets it on every call of a call market | ignore it, or handle it |
| TypeScript | the `FmEvent` union, delivered by `subscribe` and to `listen`'s callback, gains `MarketCall`: a guard chain ending in a `never` check no longer compiles, and a hand-written guard that tests only `marketplaceId`, `sessionId` or `id` now also matches a `MarketCall` | handle `MarketCall`, and make guards test a field `MarketCall` lacks (`state`, `securities`) |
| Python | nothing at compile time: the queue given to `subscribe` or `listen` now also receives `MarketCall` -- a consumer whose else branch raises or logs meets it on every call of a call market | ignore it, or handle it |

### What you need (0.12)

**fm-server 4.16.0 or later.** The SDK now calls

| Was | Now |
|---|---|
| `POST /api/tokens` | `POST /api/v1/tokens` |
| `POST /api/tokens/refresh` | `POST /api/v1/tokens/refresh` |
| `POST /api/tokens/renew` | `POST /api/v1/tokens/renew` |
| `POST /api/otp/manager` | `POST /api/v1/otp/manager` |
| `/api/events` | `/api/v1/events` |

and fm-server answers these under `/api/v1` from 4.16.0. Against an older
server the SDK cannot sign in -- by password, by bearer token or by refresh
token -- so no call gets as far as its own route, and `managerOtpBundle`
(`manager_otp_bundle` in Python) and the stream fail too.

fm-server 4.16.0 and later still answer the unversioned paths, so fm-ui and
SDKs up to 0.11.x keep working against them; only 0.12.0 needs the newer
server.

### What changes in behaviour (0.12)

**`allWidgets` works.** `allWidgets(marketplaceId)` (`all_widgets` in
Python) reads `GET /marketplaces/{id}/widgets?participant=all`, which answers
the frame every widgets read answers, `{marketplaceId, snapshot, widgets,
removed}`. 0.4.0 to 0.11.x read the answer as a bare list -- what the
`widgets/all` route it replaced answered -- and failed on every call. 0.12.0
reads the frame's `widgets`, and still reads a bare list. It returns a list
of `Widget`, as before.

**A `CALL-UPDATE` arrives as the call** (fm-server#1036). fm-server sends one
on the marketplace's topic for each call of a call-auction market, shaped as
`calls(marketplaceId)` answers each. 0.11.x dropped the frame -- silently in
Java and TypeScript, with an `Unknown message-type: CALL-UPDATE` warning in
Python. 0.12.0 parses it into the `MarketCall` that `clearNow` (`clear_now`)
and `calls` already return, and delivers it to the subscription. The call's
trades still come as an `ORDERS-UPDATE` of their own.

### Checking your code (0.12)

```bash
# Code that reads every event off a subscription
grep -rnE "\.(subscribe|listen)\(|FmEvent" src/
# Callers of allWidgets that worked around its failure
grep -rnE "allWidgets|all_widgets" src/
```

---

## 0.11: from 0.10.x

0.11.0 adds call-auction markets to all three SDKs (fm-server#1036). A
market's `mechanism` says whether it trades continuously or as a call
auction, an administrator can add a call market, a manager can clear one
and read its calls, and a study can ask for one (study contract 0.4, fm-spi 0.0.18). Code that only
**calls** the SDK needs nothing to keep working. Code that reads markets
should still read [What changes in behaviour](#what-changes-in-behaviour-011):
from fm-server 4.15.0 on, a market is not necessarily continuous.

Code that **implements** or **builds** the SDK's types (test doubles and
fixtures, mostly) may need changes:

| | What breaks | Fix |
|---|---|---|
| Java | a class implementing `Flexemarkets`: four new abstract methods, `createCallMarket(marketplaceId, symbol, name, price, units, Integer intervalSeconds, boolean carry)`, `clearNow(marketplaceId, marketId)`, `calls(marketplaceId)` and `calls(marketplaceId, sessionId)`. One implementing only `Administration` gains `createCallMarket`; only `Management`, the other three | implement them; a fake that does not clear can throw |
| Java | record patterns over `Market`: it gains three last components, `String mechanism`, `Integer callIntervalSeconds` and `Boolean callCarry` | add them to the pattern; the twelve-argument constructor is kept and makes a continuous market, so `new Market(...)` compiles unchanged |
| Java | record patterns over fm-spi's `MarketSpec`: it gains `String mechanism`, `Integer callIntervalSeconds` and `boolean callCarry` | add them to the pattern; the nine-argument constructor is kept and makes a continuous market |
| TypeScript | an object literal typed `Market`: three new required fields, `mechanism: string`, `callIntervalSeconds: number \| null` and `callCarry: boolean` | add `mechanism: "CONTINUOUS"` (or `MARKET_MECHANISM_CONTINUOUS`), `callIntervalSeconds: null`, `callCarry: true` |
| Python | nothing: `mechanism`, `call_interval_seconds` and `call_carry` default to `"CONTINUOUS"`, `None` and `True`, after every existing field | |

### What changes in behaviour (0.11)

**A market may be a call auction.** fm-server 4.15.0 runs a market either
continuously, where an order trades the moment it crosses, or as a call
auction: orders collect, crossed or not, and a call clears them all at one
price. `createMarket` still makes only continuous markets; `createCallMarket`
makes a call auction. Code that assumed every market is continuous breaks in two
ways in a call market:

- a crossing order **rests** until the next call, where code built for a
  continuous market expects it to trade at once. A manager, who sees the
  whole book, can see a bid at or above an ask, and that is normal;
- the book is **sealed**. A participant sees their own resting orders and
  the trades, not anyone else's resting orders, so for a participant a best
  bid or ask worked out from the orders on the stream is only their own. A
  manager sees every order.

Check the market before treating it as continuous: `market.isCall()` in
Java, `market.is_call()` in Python, `isCall(market)` in TypeScript.

A call's trades arrive as orders in an `ORDERS-UPDATE`, as every trade
does. The server also sends a `CALL-UPDATE` frame for each call, which the
0.11.0 streams do not parse: Java and TypeScript drop it, and Python logs
`Unknown message-type: CALL-UPDATE` at WARNING. To see the calls
themselves, read them with `calls` (below).

**Java writes three more fields when it serialises a `Market`**, namely
`mechanism`, `callIntervalSeconds` and `callCarry`. `isCall` is not written.

`createMarket` sends the same body as before, and the markets it makes are
continuous.

### What is new (0.11)

**`Market` says how it trades**, in three fields, which every SDK fills
when a server leaves them out, as servers before fm-server 4.15.0 do:

| Field (Java, TypeScript / Python) | Meaning | When the server does not say |
|---|---|---|
| `mechanism` | `"CONTINUOUS"` or `"CALL"`: `Market.MECHANISM_CONTINUOUS` and `Market.MECHANISM_CALL` in Java and Python, `MARKET_MECHANISM_CONTINUOUS` and `MARKET_MECHANISM_CALL` in TypeScript | `"CONTINUOUS"` |
| `callIntervalSeconds` / `call_interval_seconds` | for a call market, the seconds between calls while a session is open; null when only a manager's Clear now calls it | null |
| `callCarry` / `call_carry` | for a call market, whether what a call leaves unfilled waits for the next call rather than being cancelled | `true` |

In Java the canonical constructor applies these defaults as well, so a
`Market` built with a null `mechanism` or `callCarry` reads as continuous
and carrying.

**Call markets can be added, cleared and read.** An administrator can add
one; a manager can clear one and read its calls:

| | Add a call market | Clear now | The calls |
|---|---|---|---|
| Java | `createCallMarket(marketplaceId, symbol, name, price, units, Integer intervalSeconds, boolean carry)` | `MarketCall clearNow(marketplaceId, marketId)` | `List<MarketCall> calls(marketplaceId)`, and `calls(marketplaceId, sessionId)` |
| Python | `create_call_market(marketplace_id, symbol, name, price, units=None, interval_seconds=None, carry=True)` | `clear_now(marketplace_id, market_id)` | `calls(marketplace_id, session_id=None)` |
| TypeScript | `createCallMarket(marketplaceId, symbol, name, price, units?, intervalSeconds?, carry?)` | `clearNow(marketplaceId, marketId)` | `calls(marketplaceId, sessionId?)` |

`createCallMarket` has no private flag, because a call market cannot be
private and the server refuses the combination. A null `intervalSeconds`
makes a market that is called only by `clearNow`. `clearNow` posts to
`POST .../markets/{marketId}/calls` and works only while a session is open
and only on a call market; the server refuses it otherwise. `calls` reads
`GET .../calls`, oldest first, with `?sessionId=` for one session's.

Each call is a **`MarketCall`** with these fields: `id`, `marketplaceId`,
`marketId`, `sessionId`, `calledDate`, `trigger` (`MANUAL` for Clear now,
`INTERVAL` for the schedule), `price` (null when the book did not cross),
`volume`, `demand`, `supply`, `buyOrders`, `sellOrders` and `carry`. Python
spells them in snake case (`called_date`, `buy_orders`, and so on). The
trigger constants are `MarketCall.TRIGGER_MANUAL` and
`MarketCall.TRIGGER_INTERVAL` in Java and Python, and
`MARKET_CALL_TRIGGER_MANUAL` and `MARKET_CALL_TRIGGER_INTERVAL` in
TypeScript.

**A study's market may be a call auction** (fm-spi 0.0.18, study contract
0.4). `MarketSpec` carries `mechanism` (`MarketSpec.CONTINUOUS` or
`MarketSpec.CALL`), `callIntervalSeconds` and `callCarry`.
`asCall(intervalSeconds, carry)` returns a copy that is a call market, in the
same way that `asPrivate()` returns a private one:

```java
MarketSpec batch = MarketSpec.of("BAT", "Batch").asCall(5, false);
```

A spec with no mechanism is continuous, and `asPrivate()` always returns a
continuous market, because a call market cannot be private.
`StudyProvider.STUDY_SPI_VERSION` is now `"0.4"`, and the default
`studySpiVersion()` returns it.

For a study SPI implementer, a study that keeps continuous markets needs no
change. Its nine-argument `MarketSpec` constructions still compile, and
rebuilt against fm-spi 0.0.18 it reports contract 0.4 through the default
`studySpiVersion()`. To ask for a call market, call `.asCall(...)` on the
spec. Only record patterns over `MarketSpec` need the new components (see
the table above). A study that calls `asCall` needs a host on fm-spi 0.0.18,
fm-server 4.15.0 or later; on an older host it fails with
`NoSuchMethodError`. And a plan that asks for a call market is refused whole
when a manager sets it up -- nothing is created -- because only an
administrator can make a call market (below).

### What you need (0.11)

Everything 0.10.0 did works against the same servers as before. A market
read from a server older than fm-server 4.15.0 reads as continuous.

Call auctions need **fm-server 4.15.0 or later**. That release added the
mechanism fields, `POST .../markets/{marketId}/calls` and `GET .../calls`.
**Only an administrator** can make a market a call auction, whether through
`createCallMarket` or a study's plan, and a manager's request is refused;
that holds from 4.15.0 through every release since. A manager can still clear
and read the call markets an administrator made.

Against fm-server 4.17.0 or later, 0.11.0 also meets what it does not know:
an indicative call market's `CALL-INDICATIVE` frames, which Java and
TypeScript drop and Python logs as `Unknown message-type`, as they do
`CALL-UPDATE`, and a market's `callBook` field, which it ignores.

### Checking your code (0.11)

```bash
# Java: implementations of the client, and record patterns
grep -rnE "implements (Flexemarkets|Administration|Management)\b" src/
grep -rnE "(Market|MarketSpec)\(" src/ | grep -E "instanceof|case "
# TypeScript: literals typed as a Market
grep -rnE ": Market\b|as Market\b|<Market>" src/
```

---

## 0.10: from 0.9.x

0.10.0 changes one thing: fm-spi 0.0.17, which is **study contract 0.3**. A
study can now name the robots it needs -- its own traders, which the host
gives people of their own and launches for each session, or robots it offers
participants with parameters it fixes -- and is told at close which holdings
and orders are its robots' (7cbc42c). The client is unchanged in all three
SDKs: Python and TypeScript have no study SPI, and in Java only fm-spi's
`fm.study` package changed.

Code that only **calls** the SDK needs nothing. A study that **implements**
`StudyProvider` needs nothing either until it names robots: every addition has
a default, and a study without robots plans as it did. Code that **builds** or
**matches** the study types may need a change, and a study that adds robots
must not pay them:

| | What breaks | Fix |
|---|---|---|
| Java | a record pattern over `Close`: the record gains a last component, `Map<String, String> robots` | add the component to the pattern; `new Close(holdings, orders)` is kept and gives no robots |
| Java | a test asserting `studySpiVersion()` is `"0.2"`: `StudyProvider.STUDY_SPI_VERSION` is `"0.3"` | expect `"0.3"` |
| Java | a `settle` that pays every holding, once the study has `OWN` robots: fm-server refuses a settlement that pays a robot | skip owners for whom `close.isRobot(email)` |
| TypeScript | nothing | |
| Python | nothing | |

### What is new (0.10)

**A study names its robots.** `StudyProvider.robots(parameters)` answers a
`List<RobotSpec>`, before the run is planned; the default answers none. A
`RobotSpec` is:

| Component | |
|---|---|
| `key` | the study's name for it, unique in the study: `good`, `bad`, `mvo` |
| `robotId` | the robot's manifest id, as the catalogue names it: `fm-venture-entrepreneur` |
| `marketplaceKey` | the plan's marketplace it runs in |
| `mode` | `RobotSpec.Mode.OWN` or `RobotSpec.Mode.OFFERED` |
| `parameters` | the robot's parameters by manifest name, as text, fixed for the run |
| `rotations` | the rotations an `OWN` robot runs in; empty means every rotation in its marketplace |

`key`, `robotId`, `marketplaceKey` and `mode` are required -- the constructor
throws `IllegalArgumentException` without them; a null `parameters` or
`rotations` is empty. `runsIn(rotation)` says whether it runs in a rotation.

An `OWN` robot is the study's own trader: the host gives it a person, launches
it as that person when each of its rotations' sessions opens, and stops it
when the session closes. It is never paid. An `OFFERED` robot is the
participants': they launch it as any robot, with the study's `parameters`.

**Setup is handed the robots' people.** A new overload,
`setup(parameters, participants, manager, robots)`, takes a
`Map<String, Participant>`: a person for each `OWN` robot, by its key. It is
what a 0.3 host calls. Its default calls 0.2's
`setup(parameters, participants, manager)`, so a study that does not override
it plans as before. A study whose robots trade from an allotment -- Venture's
entrepreneurs hold the notes they sell -- overrides it and writes the robots'
rows into the rotations' holdings as it writes anyone's.

**Close says which owners are the study's robots.** `Close.robots()` maps each
`OWN` robot's key to its email; it is empty for a study without any, and a
null map is empty. `Close.isRobot(email)` matches an owner's email from a
holding or an order, ignoring case. `SessionOrder` is unchanged.

The robots' orders are always in `orders`. Their holdings are in `holdings`
only if the study allotted them one: the host closes on the owners in the
rotations' holdings, so a study that keeps the default `setup` sees its
robots' orders but no robot holdings. **A study must not pay its robots.**
fm-server refuses a settlement with a payoff to one (`<study> paid its own
robot <email>; a study's robots are never paid`), so a `settle` that pays every
holding must skip the owners `close.isRobot(email)` names.

Two more things the host does that a study's robots must allow for:

- It calls `robots(Map.of())` once when it loads the study, every parameter at
  its default, to keep the study's own robots out of each marketplace's robot
  catalogue. A `robots` override must answer at the defaults; if it throws, the
  host logs a warning and those robots stay listed for anyone to launch.
- It launches an `OWN` robot in its own process, so the robot must be one the
  host carries.

### What you need (0.10)

Nothing new of the server for code that only calls the SDK.

For a study, **fm-server 4.14.0 or later**: the first release to host study
contract 0.3, on fm-spi 0.0.17 (it is also the first to host 0.2; 4.13.0 hosts
0.1). An older host still loads a 0.3 study -- a study builds against fm-spi as
`provided`, so it reports the host's contract version -- and the study then
fails when it reaches what the old fm-spi lacks: `NoSuchMethodError` on
`Close.robots()` or `Close.isRobot(...)`, `NoClassDefFoundError` on
`RobotSpec`.

### Checking your code (0.10)

```bash
# Java: record patterns over Close, and tests pinning the contract version
grep -rnE "Close\(" src/ | grep -E "instanceof|case "
grep -rnE "studySpiVersion|STUDY_SPI_VERSION" src/
```

---

## 0.9: from 0.8.x

0.9.0 changes only the study contract, in Java's `fm-spi`: study contract
0.2, `fm-spi` 0.0.16 (`StudyProvider.STUDY_SPI_VERSION` is `"0.2"`). A study
can be handed the session's orders beside its holdings, a carry can end the
run before the plan's last period, and `setup` can be told who is setting the
run up (b490f09, 8309c05). The design is
[STUDY-SPI-0.2.md](STUDY-SPI-0.2.md).

The client SDKs are unchanged. Code that **calls** fm-sdk -- in Java,
TypeScript or Python -- needs nothing; the TypeScript and Python packages
differ from 0.8.0 only in their version, and the Java `fm-sdk` artifact does
not depend on `fm-spi`.

Code that **implements** `StudyProvider` needs nothing either: every addition
is a default method or a new type, and the defaults answer as a 0.1 study
does. Code that **builds** or **takes apart** a `Carry` -- a host, or a
study's tests -- may need one change:

| | What breaks | Fix |
|---|---|---|
| Java | record patterns over `Carry`: the record gains a last component, `Next next` | add it to the pattern; the three-argument constructor `new Carry(holdings, state, carried)` is kept and means `Next.PERIOD` |
| Java | a host that reads `Carry.holdings()`: it is null when `next()` is `Next.SETTLE` | check `next()` before staging; see [What a host does](#what-a-host-does) |
| Java | a call to `settle` or `carry` with a literal `null` for the holdings: each now has a four-argument overload taking `Close` beside the one taking `List<ParticipantHolding>`, so the call is ambiguous | pass `List.of()`, or cast the `null` |
| Java | a test asserting `studySpiVersion()` is `"0.1"`: `StudyProvider.STUDY_SPI_VERSION` is `"0.2"` | expect `"0.2"` |
| TypeScript | nothing: there is no study contract in TypeScript | |
| Python | nothing: there is no study contract in Python | |

### What a study may add

Each of these is optional. A study paid from holdings, with the periods its
plan sets, leaves them all alone and runs as it did on contract 0.1
(`StudyProviderCloseTest`).

**`settle` and `carry` can read the session's orders.** `StudyProvider` gains
two default overloads, and a host on contract 0.2 calls these and only these:

```java
default Settlement settle(Map<String, String> parameters, Rotation rotation, Close close, String carried)
default Carry carry(Map<String, String> parameters, Rotation rotation, Close close, String carried)
```

By default each passes `close.holdings()` to the existing
`settle(parameters, rotation, List<ParticipantHolding>, carried)` and
`carry(parameters, rotation, List<ParticipantHolding>, carried)`, so a study
that overrides only those is called as before. A study that does not carry
still throws `UnsupportedOperationException` through the new `carry`. Override
the `Close` forms when the payoff or the next period depends on what the
orders say rather than what anyone holds.

`Close` is a record of `holdings` (`List<ParticipantHolding>`) and `orders`
(`List<SessionOrder>`, the session's limit orders in the order they were
placed; see [What a host does](#what-a-host-does)). It keeps its own copies and holds empty lists, never nulls;
`Close.of(holdings)` makes one with no orders.

`SessionOrder` is a record of `id`, `ownerId` (as `ParticipantHolding.id`),
`owner` (the email), `symbol`, `side` (`Side.BUY` or `Side.SELL`), `price` in
cents, `units` (always positive), `state` and `counterparty`. `state` is how
the order ended -- `State.RESTING` (still in the book at close; also
`resting()`), `State.TRADED` or `State.CANCELLED` -- worked out by the host,
so a study does not pair cancels from the platform's `consumer` itself.
`counterparty` is the id of the order it traded with, and is set exactly when
`state` is `TRADED`. A partially filled order is two orders, as on the
platform. The constructor refuses a blank `symbol`, a null `side` or `state`,
`units` of zero or less, and a `counterparty` that disagrees with `state`.

**A carry can end the run.** `Carry` gains `next`, a `Carry.Next`:

| `next` | Made with | Meaning |
|---|---|---|
| `PERIOD` | `new Carry(holdings, state, carried)` | stage the next period; more may follow, as the plan has them (contract 0.1's carry) |
| `LAST` | `Carry.last(holdings, state, carried)` | stage the next period, and settle once it has run |
| `SETTLE` | `Carry.settle(carried)` | stage nothing; the period just closed is settled |

A null `next` in the four-argument constructor means `PERIOD`. A `SETTLE`
carry must have null `holdings` and `state`; every other carry still needs
non-blank `holdings`. After `SETTLE`, the host hands `settle` the same
`Close` this carry read, with this carry's `carried`: a study that counted
something from that close in `carry` must say so in `carried`, and not count
it again in `settle`.

**`setup` can be told who is setting the run up.** `StudyProvider` gains

```java
default StudyPlan setup(Map<String, String> parameters, List<Participant> participants, Participant manager)
```

which by default returns `setup(parameters, participants)`. A study whose
plan gives the manager a part -- Credit Bubble allots its pool to them --
overrides it. The manager is not a participant, and a study pays them
nothing. The two-argument `setup` is still abstract, so such a study
implements both.

### What a host does

A host on contract 0.2 calls the three-argument `setup` and the `Close`
forms of `carry` and `settle`, reading the session's orders into `Close`.
After a `LAST` carry it stages the last period, and once that period's
session has run it allows only a settle; after a `SETTLE` carry it stages
nothing, and the next step is the settle of the period that just closed.
Either way the plan's remaining rotations are not run, and the settle is still
the manager's to start.

The host's orders are its session's limit orders as they ended: a partly
filled order is two orders, and a split's parent and a cancel's own record are
not among them.

A provider that leaves `studySpiVersion()` alone reports the contract version
of the fm-spi it runs against. On a host that is the host's fm-spi, since a
study builds against it as `provided`, not the version the study was built
with.

### What you need (0.9)

For callers of the client SDKs, the same server as 0.8.0.

A hosted study that uses any of contract 0.2 needs **fm-server 4.14.0 or
later**, the first release that hosts it (fm-server 1a8b0dd15). An older
fm-server runs fm-spi 0.0.15, contract 0.1, and still loads such a study, but
the study fails when it reaches what 0.0.15 lacks: `NoClassDefFoundError` on
`Close`, `SessionOrder` or `Carry.Next`, `NoSuchMethodError` on `Carry.last`,
`Carry.settle` or `next()`. A study that overrides only the `Close` form of
`carry` is never called there: the host calls the holdings form, whose default
throws `UnsupportedOperationException`.

To build a study against contract 0.2, depend on `com.flexemarkets:fm-spi`
0.0.16 or later.

### Checking your code (0.9)

```bash
# Record patterns over Carry, and hosts that stage a carry's holdings
grep -rnE "Carry\(" src/ | grep -E "instanceof|case "
grep -rn "\.holdings()" src/ | grep -i carry
# Studies that may want the orders, an early end, or the manager
grep -rn "implements StudyProvider" src/
```

---

## 0.8: from 0.7.x

0.8.0 is additive. It adds three things: a sign-in with a refresh token,
which renews as the client runs, in all three SDKs (fm-server#1047; c5c77d9,
139d3d7, 94f4df6); the person's last sign-in time and method on `Person`, in
all three (#4; 2b2cf7e, 3897372, fe8a75b); and a step in the Java study SPI
that carries one period of a study into the next, study contract 0.1 in
fm-spi 0.0.15 (f1fd044). Code that only **calls** the SDK and reads what it
returns needs no changes.

Code that **builds** a `Person` itself, mostly test fixtures, may need a
change:

| | What breaks | Fix |
|---|---|---|
| Java | record patterns over `Person`: the record gains two last components, `Instant lastSignedIn` and `String lastSignInMethod` | add them to the pattern; the nine-argument constructor is kept, so `new Person(...)` compiles unchanged |
| TypeScript | an object literal typed `Person`: the interface gains required `lastSignedIn: Date \| null` and `lastSignInMethod: string \| null` | add `lastSignedIn: null, lastSignInMethod: null` |
| Python | nothing: `last_signed_in` and `last_sign_in_method` come last and default to `None` | |

A Java `StudyProvider` needs nothing either. The new methods are defaults,
and a study that plans every period at setup runs as it did. See
[If you implement a study](#if-you-implement-a-study).

### What is new (0.8)

**A client can sign in with a refresh token.** Pass the credential
`refresh:<token>`, or put a `refresh-token=<token>` line in a credential
file. The client signs in with `POST /api/tokens/renew`
(`{"refreshToken": ...}`). When the access token runs out, it renews the
same way, so it keeps running for as long as the refresh token lasts. Before,
a client given an access token stopped when that token expired, because it
held no password to sign in again with.

fm-server accepts each refresh token once. Each renewal returns the next
one, and the SDK presents that one at the next renewal. Signing in is itself
a renewal, so a client uses up the refresh token it was given as it connects.
If a refresh token is presented twice, fm-server ends the whole family: every
refresh token renewed from the same sign-in, the newest one included.
Access tokens already issued work until they expire. The SDK keeps the newest
refresh token in memory only, never writes it back to the credential file, and
has no way to hand it to you. So:

- give each client its own refresh token, and never share one between two
  processes;
- never start a client -- a second one, or the same one restarted -- from a
  `refresh:` credential or file that has already been used.

The SDK's own password sign-in does not ask for a refresh token. Your client
gets one from fm-server. A robot that fm-server 4.13 or later launches is
handed `refresh:<token>` as its credential (`-C`), and this is the case the
change is built for.

**`Person` says when and how someone last signed in.** In Java and
TypeScript the new fields are `lastSignedIn` and `lastSignInMethod`; in
Python they are `last_signed_in` and `last_sign_in_method`. The time is an
`Instant` in Java, a `Date` in TypeScript and a `datetime` in Python. The
method is spelled as the server spells it: `PASSWORD`, `GOOGLE` or `OTP`.
Both are null (`None`) for someone who has never signed in, and the method
alone is null for a sign-in recorded before fm-server recorded methods.

### What changes in behaviour (0.8)

**A credential that starts with `refresh:` is now a refresh token**, and is
checked before the credential is tried as a file path. Before, such a string
was refused unless a file had that name. A non-blank `refresh-token=` line in
a credential file is used ahead of an `account`/`email`/`password` in the same
file.

**A `refresh-token=` line in `~/.fm/credential` reaches every client.** The
SDK reads that default file first and lays the credential you pass over it,
so its refresh token outlives an access token you pass, or a credential file
without that line, and is the one the client signs in with. Every client on
the machine then presents the same refresh token, which ends its family at
the second sign-in (above). Keep refresh tokens out of `~/.fm/credential`:
put each in a file of its own and pass that file.

### If you implement a study

`StudyProvider.STUDY_SPI_VERSION` is `"0.1"` (it was `"0.0"`), in
`com.flexemarkets:fm-spi:0.0.15`. A host accepts a provider whose major
version matches its own, so the major is still 0 and a 0.0 study and a 0.1
host accept each other.

Contract 0.1 adds two default methods and a record, all for a multi-period
study whose next allotment comes from the last period's close, such as a
dividend drawn and paid or a short allowance carried forward:

- `Carry carry(parameters, rotation, holdings, carried)`. The host calls
  this when a manager carries a closed period that has a next one in the same
  marketplace (below), at most once a period. It receives every participant's holding at close, and
  `carried`, which is what the previous `carry` in that marketplace kept,
  or null before the first. The default throws
  `UnsupportedOperationException` ("plans every period at setup and carries
  nothing").
- `Carry(holdings, state, carried)`. `holdings` is the next period's
  holdings CSV and is required (a blank value throws
  `IllegalArgumentException`). `state` is its private-state CSV, or null.
  Both replace the files the plan gave that rotation. `carried` is the
  study's own state between periods. Only the study reads it: the host keeps
  it on the run and passes it back.
- `Settlement settle(parameters, rotation, holdings, carried)`. The host
  calls this overload. Its default calls the three-argument `settle`, so a
  study that overrides only the three-argument `settle` settles as it did
  before. Override the four-argument `settle` when a payout depends on what
  the periods carried.

The period is `rotation.index()`; no separate argument carries it.

fm-server 4.13.0 is the first host that calls these methods. It carries a
period on `PATCH /api/v1/studies/runs/{id}/rotations/{n}` with
`{"state":"CARRIED"}`, once that period's session has closed. Older servers
call neither `carry` nor the four-argument `settle`. Against them, a study
that overrides only the four-argument `settle` has no settlement, and it
gets no `carry` call between periods.

### What you need (0.8)

The same server as 0.7.0, fm-server 4.6.3 or later (4.7 or later to
impersonate from Java), unless you use the new parts:

- a `refresh:` credential needs **fm-server 4.7.0 or later** for
  `/api/tokens/renew`. Java cannot impersonate with one: `impersonate-account`
  needs a password, and is refused with a `ConfigurationException`;
- carry and the four-argument `settle` need **fm-server 4.13.0 or later**
  as the study host.

`lastSignedIn` and `lastSignInMethod` are read when the server sends them
and are null when it does not.

### Checking your code (0.8)

```bash
# Java: record patterns over Person
grep -rnE "Person\(" src/ | grep -E "instanceof|case "
# TypeScript: literals typed as Person
grep -rnE ": Person\b|as Person\b|<Person>" src/
# Credentials or credential files that might already start with refresh: or carry refresh-token=
grep -rnE "refresh:|refresh-token=" src/ ~/.fm/ 2>/dev/null
```

---

## 0.7: from 0.6.x

0.7.0 adds one field to `Order`, in all three SDKs: `ownerTargetId`
(`owner_target_id` in Python), the counterparty of a private order by owner
id, which a session's order history (`GET .../orders?sessions=`) sends in
place of `ownerTarget`. Before, every private order read from a history had
no counterparty (3c10c62, 52bc4b9, 384ce18).

Reading orders, nothing changes. Code that **builds** an `Order` itself --
test fixtures, mostly -- may need one more argument:

| | What breaks | Fix |
|---|---|---|
| Java | `new Order(...)` and record patterns: `Order` is a record and gains a last component, `Long ownerTargetId` | pass `null` (or the id) last; add the component to a deconstruction pattern |
| TypeScript | an object literal typed `Order`: the interface gains a required `ownerTargetId: number \| null` | add `ownerTargetId: null` |
| Python | only a positional `created_date` / `last_modified_date`: the new field, defaulting to `None`, sits before them | pass those two by keyword |

### What you need (0.7)

The same server as 0.6.0 -- fm-server 4.6.3 or later, 4.7 or later to
impersonate from Java. `ownerTargetId` is null wherever the server does not
send it.

### Checking your code (0.7)

```bash
# Java
grep -rn "new Order(" src/
# TypeScript
grep -rnE ": Order\b|as Order\b|<Order>" src/
```

---

## 0.6: from 0.5.x

**0.6.0 removes nothing and changes no signature** in any of the three
SDKs. Two things answer differently: a Java client acting as another
account, and fm-spi's `CliBuilder` given a value outside a robot's bounds.
If neither applies to you, changing the version and rebuilding is the
whole upgrade.

### What you need (0.6)

**fm-server 4.6.3 or later**, as for 0.5.0 -- except to act as another
account from Java, which needs **fm-server 4.7 or later** (below).

### What answers differently (0.6)

- **Java: acting as another account needs a password** (c337c4f).
  fm-server 4.7 refuses `X-FM-Account` unless `X-FM-Impersonation` carries
  a fifteen-minute token issued to the administrator by
  `POST /api/v1/impersonation`, with their password again. The client asks
  for that token at connect and renews it as it runs. So a client that
  impersonates must connect with a credential file holding account, email
  and password: given a token instead, connect throws
  `ConfigurationException` rather than leaving every call to answer 403. A
  wrong password, or a caller who is not an administrator, is refused at
  connect.
- **fm-spi 0.0.14: `CliBuilder` refuses a numeric value outside the robot's
  own bounds** (7829a88), with `InvalidLaunchException` naming the bounds.
  fm-server already refused such values; a container launched directly did
  not. Parameters with no bounds admit everything, as before.

### What is new (0.6)

- **Python and TypeScript report each call's round trip on the next**, as
  `Client-Timing: rtt=<ns>` (1047887, 43fb10d), as Java already did. Nothing
  to do.
- **Java study views:** the Smith62 view shows each participant their own
  values or costs (f955908, 2ac29f1), and a new
  `ssw-sequestered-dividends` view shows dividends earned, paid at the end
  (833ec40, 0977966).
- **fm-expr** gains `min`, `max`, `round`, `exp`, `ln` and `sqrt` (cc6837c).
- **A first robot** for each SDK, in [FIRST-ROBOT.md](FIRST-ROBOT.md).

### Checking your code (0.6)

```bash
# Java: a client that impersonates -- check it connects with a password
grep -rn "impersonateAccount" src/
```

---

## 0.5: from 0.4.x

Read *What you need* and *What was removed*. 0.5.0 removes a handful of
per-market shortcuts from the **book and tape indexes** -- the containers a
Desk keeps its books and tapes in -- and nothing a Desk or a `Flexemarkets`
client offers. If you read books and tapes through a Desk, as every example
in this repository does, nothing you call is gone. The rest is additions and
fixes, listed so a changed behaviour is not a surprise.

### What you need (0.5)

**fm-server 4.6.3 or later** -- the same as 0.4.0. The two new robot calls
use routes fm-server has served since 4.1.0; reading the catalogue asks the
robot host behind fm-server, so it fails while that host is down.

### What was removed, and what to call instead

All three SDKs drop the same methods (they are held to one surface by
`make check-parity`). None had a caller in any SDK, in fm-robots or in
fm-server: the Desk reaches a market's Book or Tape and reads it there, and
the `Book` and `Tape` methods those shortcuts forwarded to all remain.

| Removed | Java | Python | TypeScript | Instead |
|---|---|---|---|---|
| a market's top-of-book, from the book index | `BookIndex.hasValue(marketId, side)` | `BookIndex.has_value(market_id, side)` | `BookIndex.hasValue(marketId, side)` | the market's book: `desk.book(marketId).hasValue(side)` / `desk.book(market_id).has_value(side)` |
| | `BookIndex.bestPrice(marketId, side)` | `BookIndex.best_price(market_id, side)` | `BookIndex.bestPrice(marketId, side)` | `desk.book(marketId).bestPrice(side)` / `.best_price(side)` -- or `best_buy_price()` / `best_sell_price()` |
| every market's recent trade prices at once | `TapeIndex.mostRecentPrices()` | `TapeIndex.most_recent_prices()` | `TapeIndex.mostRecentPrices()` | per market: `desk.tape(marketId).mostRecentPrices()` / `.most_recent_prices()`, over `desk.tapes()` for all of them |
| indexing an index | -- | `BookIndex.__getitem__` (`books[market_id]`), `TapeIndex.__getitem__` (`tapes[market_id]`) | -- | `books.get(market_id)` / `tapes.get(market_id)` -- or `desk.book(market_id)` / `desk.tape(market_id)` |

**One difference to mind in Python.** `books[market_id]` raised `KeyError`
for a market the marketplace does not have; `get` and `desk.book` answer
`None`. Code that relied on the exception to notice a wrong market id must
now check for `None`.

**In Java the indexes were never public API.** `BookIndex` and `TapeIndex`
live in `fm.internal`, hidden on the module path and only visible on the
classpath (fm-sdk's `fm.internal` is classpath-visible; see the module
notes). If you reached into them anyway, the replacements above are the
supported way.

Also gone, none of them yours to call:

- Java: the event stream's own `onPing(WebSocket, ByteBuffer)` handler in
  `fm.internal`, removed because it answered every server ping twice (see
  below).
- Python: the private helpers `_to_camel`, `_to_snake` and
  `_is_created_earlier`, which nothing called.

### What is new (0.5)

**Reading and configuring a marketplace's robots** (all three SDKs):

| | Read the catalogue | Fix or expose a parameter |
|---|---|---|
| Java | `marketplaceRobots(marketplaceId)` | `configureRobotParameter(marketplaceId, robotId, paramName, value, control)` |
| Python | `marketplace_robots(marketplace_id)` | `configure_robot_parameter(marketplace_id, robot_id, param_name, value, control)` |
| TypeScript | `marketplaceRobots(marketplaceId)` | `configureRobotParameter(marketplaceId, robotId, paramName, value, control)` |

With new types `MarketplaceRobot`, `RobotManifest`, `RobotParameter`,
`RobotParameters`, `RobotParameterConfig` and `RobotParameterControl`. A
manager fixes a parameter (a value; `control` null) or exposes it to
participants within a range. See API-REFERENCE.md, *Robots*.

**A client made with credentials renews its own sign-in** (all three). A
token lives 24 hours; when a call is refused as `ACCOUNT_TOKEN_EXPIRED`, the
client signs in again once and resends, and the event stream renews on a
refused reconnect. A client given a token rather than credentials has
nothing to sign in with, and still gets the 401.

**Java asks for compressed responses** (`Accept-Encoding: gzip`) and
decodes them. Nothing to do.

### What answers differently (0.5)

Fixes that change what a call does, in the order a robot would notice them:

- **A second `listen()` closes the stream it replaces** (all three). Before,
  the first stream's socket, heartbeat and reconnects kept running and kept
  delivering onto the old queue -- a robot that re-listened after an error
  left a second connection open for the life of the process.
- **`isResting` / `is_resting` picks one side when neither original order is
  in the batch** (all three). Each side of one match could call itself the
  older, so both read as resting and a Tape recorded the match as two
  trades.
- **Java answers a WebSocket ping once**, not twice, and **a reconnect you
  ask for no longer reports a drop and reconnects twice**.
- **Java: a bare marketplace id honours `FM_URL`**, as the other SDKs did --
  `-E 3343` goes to the server `FM_URL` names, not to production. (Before,
  a bare id always meant production.)
- **TypeScript: closing a listener while it is still connecting no longer
  crashes the process.** And **the desk now recognises a session update**:
  before, `session()` stayed null for the desk's whole life and
  `onSessionChange` never fired.
- **Python: a closed desk's `books()` raises**, as its other reads already
  did, rather than answering the books it had when it closed.
- **A desk refuses locally what fm-server would refuse** (all three): a
  limit order while the session the desk holds is not open -- after
  reading the session once more, in case the server opened before the
  event arrived -- or on a side the holding does not grant. It raises
  `OrderRefusedLocallyException` (Java) / `OrderRefusedLocallyError`
  (Python, TypeScript), an invalid-argument error, so code that catches
  those catches this too, and no request is sent. To send every order and
  leave the answer to the server: `desk(id, Desk.OrderChecks.SERVER_ONLY)`
  (Java), `desk(id, refuse_locally=False)` or per order
  `submit_limit(..., refuse_locally=False)` (Python),
  `desk(id, { refuseLocally: false })` (TypeScript).
- **Python: a reconnect you ask for no longer reports a drop**, as Java's
  already did not.
- **"view" is back where the 0.4 View -> Desk rename took the English
  word** in the documentation and comments. No API changed.

### Checking your code (0.5)

```bash
# Java
grep -rnE "\.(hasValue|bestPrice)\([^,)]+, |mostRecentPrices\(\)" src/ | grep -i index
# Python
grep -rnE "\.(has_value|best_price)\([^,)]+, |most_recent_prices\(\)|books\[|tapes\[" .
# TypeScript
grep -rnE "\.(hasValue|bestPrice)\([^,)]+, |mostRecentPrices\(\)" src/ | grep -i index
```

A hit on a two-argument `hasValue`/`bestPrice` (market id and side) is the
index form; the one-argument form on a `Book` is unchanged.

---

## 0.4: from 0.3.x

Read *What you need* and *What changed underneath*. **0.4.0 changes no signature in any of the three
SDKs** — every method a 0.3 caller compiles against is still there, taking the
same arguments. What changes is where each call goes, and that changes what a
few of them answer. Those are listed under *What answers differently*; if none
applies to you, changing the version and rebuilding is the whole upgrade.

### What you need (0.4)

**fm-server 4.6.3 or later.** 0.4.0 calls only `/api/v1` routes, in the
model-REST forms of fm-server's `docs/API-V1.md` §4, and two of them —
`DELETE /api/v1/marketplaces/{id}` and `GET orders?cancelled=false` — first
shipped in 4.6.3. Against an older server those two calls answer 404 or 400;
everything else works from 4.6.0.

0.3.x keeps working against 4.6.x: every route it calls is still served.

### What changed underneath

**The SDK no longer reads the API root.** 0.3 fetched `GET /api` on connect,
pulled hrefs out of its HAL `_links`, and rebased them onto the endpoint it
dialled. That made every client one server refactor away from failing whole:
when fm-server 4.5.6 stopped advertising three links, every SDK client failed
to connect for 48 minutes while the server answered 200. 0.4 builds every path
itself, against routes the server versions.

So, as a consequence:

- Connecting is one request (the sign-in), not two.
- The warning about an API root naming a different origin is gone with the
  root. It reported a proxy not forwarding the request scheme; nothing in 0.4
  can be misled by that.
- `--capture` traces show `/api/v1/...` paths.
- An `activeOrders`/`recentTrades` answer that is not a list of orders now raises
  the SDK's API error instead of reading as an empty snapshot.

**The routes** — all three SDKs, held to `sdks/fixtures/routes/routes.json`:

| Call | 0.3 | 0.4 |
|---|---|---|
| `marketplaces`, `marketplace`, `markets` | root links, `?format=application/json` | `GET /v1/marketplaces[/{id}[/markets]]` |
| `symbols` | `GET marketplaces/{id}/symbols` | the markets' symbols, from `GET markets` |
| `session` | `currentSession` | `GET sessions/current` |
| `openSession` · `pauseSession` · `closeSession` | `PATCH /open` · `/pause` · `/close` | `PATCH sessions/current {"state": ...}` |
| `orders(mp)` | V0 marketplace orders | `GET orders?cancelled=false` |
| `orders(mp, sessionIds)` | `sessionOrdersJson` | `GET orders?sessions=` |
| `orders(mp, symbol)` | `symbolOrdersJson` | `GET orders?state=ACTIVE&symbol=` |
| `trades(mp, symbol)` | `symbolTradesJson` | `GET orders?state=TRADED&symbol=&limit=5000` |
| `activeOrders` · `recentTrades` | `orders/active` · `orders/recent-trades?size=` | `GET orders?state=ACTIVE` · `?state=TRADED&limit=` |
| `submitLimit` | `POST /api/orders` | `POST /v1/marketplaces/{id}/orders` |
| `submitCancel` | `POST /api/orders` with `type: CANCEL` | `DELETE /v1/marketplaces/{id}/orders/{orderId}` |
| `holding` | `currentHolding` | `GET participants/me/holding` |
| `identifiers` | `privateTraders` | `GET participants`, names only |
| `downloadHoldings` | `GET holdings/downloads` | `GET holdings`, `Accept: text/csv` |
| `uploadHoldings` | multipart `POST holdings/uploads` | `POST allocations`, `Content-Type: text/csv` |
| `uploadState` | multipart `POST state/uploads` | `PUT state`, `Content-Type: text/csv` |
| `allotments` | `GET allotments?allocation=` | `GET allocations/{id}/allotments` |
| `allWidgets` | `GET widgets/all` | `GET widgets?participant=all` |
| `removeWidget(mp, key, userId)` | `DELETE widgets/{key}?userId=` | `DELETE participants/{userId}/widgets/{key}` |
| `approveAccount(name)` | V0 `POST /api/approvals {name}` | `GET /v1/accounts`, then `POST /v1/accounts/{id}/approvals` |
| `deleteMyAccount` | V0 `DELETE /api/accounts/me` | `DELETE /v1/accounts/me` |
| `deleteMarketplace` | V0 `DELETE /api/marketplaces/{id}` | `DELETE /v1/marketplaces/{id}` |
| `signup`, `accounts`, `accountById`, `deleteAccount`, `users`, `deleteUser`, `createMarket`, `connections`, `allocate` | root links | the same resource under `/v1` |
| token refresh | `GET /api/tokens/refresh` | `POST /api/tokens/refresh` |

Unchanged: sign-in (`/api/tokens`), `managerOtpBundle` (`/api/otp/manager`)
and the event stream (`/api/events`) are bootstrap routes the server keeps
unversioned on purpose; `sessions`, `userById`, `createUser`,
`createMarketplaceFromJson` and `pushWidgets` were V1 already.

### What answers differently (0.4)

Four calls, each because the V1 route says something the old one did not.

**`orders(marketplaceId, sessionIds)` is the raw lifecycle, and a manager's
read.** It read `sessionOrdersJson`, which answered any participant and
passed the rows through the server's read view: private orders scoped to the
caller, `mine` set, and a self-cross rewritten as the older order cancelled.
`GET orders?sessions=` answers every LIMIT and CANCEL row the exchange stored,
to a manager only — the audit and replay view. A participant calling it now
gets an `AuthorizationException`. A self-cross reads as what it was, a LIMIT
consuming a LIMIT of the same owner. Cancels are rows here as they were; this
is the call to count them with.

`orders(marketplaceId)` is unchanged: `?cancelled=false` is the server
applying the same filter V0 did.

**`pauseSession` on a session that cannot pause is refused.** V0 answered the
session unchanged when asked to pause a closed one; the V1 PATCH says the
state cannot be reached. Asking for the state already reached is still a
quiet success.

**`approveAccount(name)` makes two requests,** and throws
`InvalidArgumentException` when no account has that name, before asking the
server to approve anything. It needs the account list, which only an
administrator reads — as only an administrator could approve.

**`submitCancel` answers 404 for an order the marketplace does not hold,**
where the exchange used to refuse the CANCEL. The `marketId` argument is no
longer sent: the server takes the market from the order. It stays in the
signature, which 0.4 does not change.

### What is new (0.4)

**One market's recent trades.** `recentTrades` takes a market:

| Language | Call |
|---|---|
| Java | `Snapshot<List<Order>> recentTrades(long marketplaceId, long marketId, int size)` |
| Python | `recent_trades(marketplace_id, size=1000, market_id=None)` |
| TypeScript | `recentTrades(marketplaceId, size = 1000, marketId?)` |

It reads `orders?state=TRADED&market=`. In Java it is a `default` method on
`Reading`, which narrows the marketplace-wide read to the market, so an
implementation written against 0.3 keeps compiling. The HTTP client
overrides it to ask the server for the market's own legs.

**A desk seeds each tape from its own market.** It used to read the newest
1000 legs of every market together. A busy market filled them, and a quiet
market's tape came up empty though it had traded (fm-server#1029). It now
reads each market's newest legs, two for each trade the tape keeps.

**A tape keeps a trade once.** The trades are read after the orders snapshot
whose sequence the desk follows. So a trade made in between arrived twice:
in the seed, and again as a delta past the watermark. The tape kept both,
and `onTrade` announced the trade twice. A tape now ignores a trade it
already holds, matched on its resting and aggressor order ids.

### What is not in 0.4.0

The other items in [DESIGN-NEXT.md](DESIGN-NEXT.md) — a TypeScript logger,
all-markets subscriptions, diagnostics through `System.Logger` — are open and
not in this release.

---

## 0.3: from 0.2.x

Read *What breaks*. If nothing there applies to you, there is nothing to
do: **0.3.0 breaks no published API in any of the three SDKs.**

That is worth saying plainly, because it is not what a `0.x` bump promises.
[Semantic versioning](https://semver.org/#spec-item-4) makes no compatibility
guarantee below 1.0, the README says outright that anything may be added,
renamed or removed in any release, and 0.2.0 did exactly that — 42 Java types
moved packages and a dozen were renamed. 0.3.0 simply did not need to.

### What breaks

Nothing.

Measured rather than asserted: between `v0.2.0` and `v0.3.0` the Java public
surface is 270 added lines and one removed, and the removal is a `module-info`
line replaced by the two that export the new package. No type was renamed,
moved or withdrawn; no method changed shape; nothing was dropped from the
Python or TypeScript entry points.

So a consumer pinned to 0.2.x can move to 0.3.x by changing the version and
rebuilding. Both consumers in this organisation did exactly that.

### What is new (0.3)

#### `uploadState` — staging a study's private per-participant state

All three SDKs, added together so they stay level:

| Language | Call |
|---|---|
| Java | `List<ParticipantState> uploadState(long marketplaceId, Path csv)` |
| Python | `upload_state(marketplace_id, csv)` |
| TypeScript | `uploadState(marketplaceId, csv)` |

A study often needs each participant to begin with something only they can see
— a private valuation, a draw, an endowment that is not in their holdings. That
had no home: the holdings upload carries cash and securities and nothing else,
so studies passed private state out of band and the server never held it. This
stages it per participant and returns it as stored, one row each.

Nothing else changed to accommodate it. If your study does not have private
per-participant state, you will not notice this exists.

#### Study views — Java only, and deliberately

`fm.views.StudyViews` and `fm.views.StudyView` ship the study view documents
inside the jar, with `fm/views/index.json` beside them as the list.

**This has no Python or TypeScript counterpart, on purpose.** A study's view is
read by exactly two things that never share a process: the study's CLI, which
creates the marketplace with it, and fm-server, which offers it to a manager as
something to start from. Both are JVM. Each used to keep its own copy and the
two drifted, which is the whole reason this exists — putting the document in the
one artifact both already depend on. Shipping a copy to PyPI and npm would add
two more copies to keep level with it and no reader for either.

If you are writing a study in Python or TypeScript, this is not a gap you have
hit; there is nothing on those sides that reads a view.

#### Two artifacts released alongside

Neither is part of the SDK and neither is needed to use it.

- **`fm-expr` 0.0.1** — the evaluator for panel expressions, so the CLI that
  writes a panel and the server that renders one agree on what an expression
  means. Alpha even by the standards of this repo: `0.0.1`, and it says so.
- **`fm-spi` 0.0.13** — the study contract: what a study declares it needs, how
  it plans a session, how it settles a rotation. Consumed by fm-server and the
  study CLIs.

#### The README now says this is alpha

Every README gained a notice at the top, above the install instructions, saying
what `0.x` means here: anything public may be added, renamed or removed in any
release, without a deprecation period, and every `0.x` bump should be treated as
potentially breaking. Pin an exact version rather than a range.

The PyPI classifier moved from `Development Status :: 4 - Beta` to
`3 - Alpha` at the same time, because it had been claiming more than the prose
below it.

### 0.3.1: SDK diagnostics leave stderr

A patch release, and it breaks no API — but it changes something a reader
might be watching for, which is why it is here rather than only in the
release notes.

The Java SDK narrated transport trouble with `System.err.println`, prefixed
`[Desk]` or `[fm-sdk]`:

```
[Desk] WS transport error on marketplace 3302: Connection reset
[Desk] Reseed failed on marketplace 3302; desk is stale: ...
[fm-sdk] The API root names accounts but this client dialled ...
```

Those five lines now go through `System.Logger`, and **the prefixes are gone** —
the logger name (`fm.internal.DefaultDesk`, `fm.internal.HttpFlexemarkets`)
carries the source, and every backend prints it.

**If you grep for `[Desk]` in a terminal or a dyno log, that stops matching.**
Nothing else about the lines changed except one improvement: a transport error
whose cause carries no message — a reset connection, typically — used to print
`null` and now names the exception class.

**Where the lines go now depends on your application, which is the point.** With
a `System.LoggerFinder` on the path — which a Spring Boot service has — they
land in your own logging pipeline. With none, they go to `java.util.logging`,
whose console format differs from a bare `println`.

Python is unaffected: it has always used `logging.getLogger(__name__)`.
TypeScript is unaffected: it keeps `console.warn`, which is the Node idiom.

**None of this is the interface.** These lines are the fallback for a caller who
subscribed to nothing. `onGap`/`on_gap` and `onRecovery`/`on_recovery` carry the
same events as typed records, in all three SDKs, and are what you should act on.

---

## 0.2: from 0.1.x

Everything a consumer has to change, in all three languages, and the handful of
changes that are not renames and so cannot be done by substitution.

Most of 0.2.0 is mechanical: 42 Java types moved package, nine types were
renamed in all three languages, and four read-side methods were named after what
they return. Those sections give you the substitutions.

Read [Changes that are not renames](#changes-that-are-not-renames-02) properly. It
is short, and every item in it changes behaviour rather than spelling — a
codebase that compiles after the substitutions can still be wrong in those five
ways.

The [Verification](#verification-02) section at the end is the checklist that says
you are done.

### Why 0.2.0 breaks

Under semver's 0.x convention a minor bump is what signals breakage. 0.1.0's
headline change was that seventeen records left the `Types` holder class and
became top-level in `fm`. That left `fm` flat — 52 public types in one package,
where the things you *receive* sat beside the things you *call* and the things
you *catch*.

0.2.0 groups them, and takes the renames and withdrawals with it deliberately:
the cost of a mass re-import is paid once, in one release, rather than spread
over three. If you are migrating a large codebase, that is the trade being made
on your behalf — one bad afternoon instead of three mediocre ones.

The measured size of the largest migration, fm-robots: 625 imports across 369
files, plus 322 call sites naming `Side`.

### Java (0.2)

#### Imports: 42 types moved into four packages

Nothing about these types changed. They are in a package that says what they
are.

| new package | what it holds | count |
|---|---|---|
| `fm.model` | the wire records and the enums you hand back — `Account`, `Allotment`, `Assets`, `ClientConnection`, `ConflictFailure`, `Holding`, `ManagerOtpBundle`, `Market`, `Marketplace`, `Order`, `OrderSide`, `OrderType`, `Person`, `Security`, `Session`, `TickGrid`, `Token`, `Trade` | 18 |
| `fm.error` | the eleven exceptions — `FlexemarketsException` and its subtypes | 11 |
| `fm.event` | what arrives on a queue — `FrameUnreadable`, `GapEvent`, `OrdersUpdate`, `ReconnectEvent`, `Reconnected`, `StreamDropped`, `Version` | 7 |
| `fm.role` | the six role interfaces — `Administration`, `Identity`, `Management`, `Reading`, `Streaming`, `Writing`. **Not exported** — see below | 6 |

What stays in `fm`: `Flexemarkets`, `Desk`, `Subscription`, `Book`, `Tape`,
`Orders`, `Endpoints`, `Snapshot`, `FlexemarketsProvider`, `Providers`.

`Endpoints` stays exported deliberately. It is the shared vocabulary for the
`-E/--endpoint` flag, and two repos resolve endpoints through it before a
credential exists.

The substitution, from your source root:

```bash
# The four package moves, plus Side, which is a move and a rename at once.
# Run this before the type renames below.
grep -rl '^import fm\.' --include='*.java' . | xargs sed -i -E \
  -e 's/^import fm\.(Account|Allotment|Assets|ClientConnection|ConflictFailure|Holding|ManagerOtpBundle|Market|Marketplace|Order|OrderType|Person|Security|Session|TickGrid|Token|Trade);/import fm.model.\1;/' \
  -e 's/^import fm\.Side;/import fm.model.OrderSide;/' \
  -e 's/^import fm\.(AccountNameConflict|Api|Authentication|Authorization|Configuration|Conflict|ConnectionFailed|Flexemarkets|Http|InvalidArgument|PersonHasMarketplaceData)Exception;/import fm.error.\1Exception;/' \
  -e 's/^import fm\.(FrameUnreadable|GapEvent|OrdersUpdate|ReconnectEvent|Reconnected|StreamDropped|Version);/import fm.event.\1;/' \
  -e 's/^import fm\.(Administration|Identity|Management|Reading|Streaming|Writing);/import fm.role.\1;/'
```

`import fm.Side;` has to be handled here rather than by the rename pass below:
it is the one type that both moves package and changes name, and a rename that
runs after the moves would leave you with `import fm.OrderSide;` — a package
that no longer holds it.

#### Renames (0.2)

Nine types changed name. The reasoning is in the commits; what matters here is
that `MarketView` was neither a view nor per-market, and the book and tape types
were named after the container rather than the thing.

| 0.1.x | 0.2.0 | note |
|---|---|---|
| `fm.MarketView` | `fm.Desk` | |
| `fm.OrderBook` | `fm.Book` | methods unchanged |
| `fm.Trades` | `fm.Tape` | |
| `fm.OrderUtils` | `fm.Orders` | and lost five methods — see below |
| `fm.Side` | `fm.model.OrderSide` | move *and* rename |
| `fm.OrderBooks` | `fm.internal.BookIndex` | withdrawn |
| `fm.MarketplaceTrades` | `fm.internal.TapeIndex` | withdrawn |
| `fm.DefaultMarketView` | `fm.internal.DefaultDesk` | was already implementation |
| `fm.Reconnected` | `fm.event.StreamReconnected` | move *and* rename |
| `fm.ReconnectEvent` | `fm.event.DeskRecovery` | move *and* rename |
| `fm.MarketViewHandle` | `fm.internal.DeskHandle` | was already implementation |

`Side` is the expensive one: it is a rename rather than a path move, so
`Side.BUY` does not fall out of an import rewrite. In fm-robots it was 322 call
sites — `Side.BUY` 141, `Side.SELL` 90, `Side.BOTH` 29.

```bash
grep -rl '\bSide\b\|MarketView\|OrderBook\|OrderUtils' \
  --include='*.java' . | xargs sed -i -E \
  -e 's/\bDefaultMarketView\b/DefaultDesk/g' \
  -e 's/\bMarketViewHandle\b/DeskHandle/g' \
  -e 's/\bMarketView\b/Desk/g' \
  -e 's/\bOrderBook\b/Book/g' \
  -e 's/\bOrderUtils\b/Orders/g' \
  -e 's/\bSide\b/OrderSide/g'
```

Three cautions, each of which cost something to find:

**It is not idempotent.** `\bSide\b` → `OrderSide` turns an already-migrated
`OrderSide` into `OrderOrderSide`. Run it once, on a clean tree, and grep for
`OrderOrderSide` afterwards.

**`OrderBooks`, `MarketplaceTrades` and `Trades` are deliberately not in that
script.** The first two are withdrawn rather than renamed — rewriting them to
`BookIndex` and `TapeIndex` produces an import that looks right and does not resolve,
which is worse than a name the compiler rejects outright. Let it fail, then
follow [the section below](#orderbooks-and-marketplacetrades-are-no-longer-yours-to-construct).
`Trades` is out because it collides with unrelated builder methods: rename
`fm.Trades` → `fm.Tape` by import and fix the use sites the compiler finds.

**Check whether you own a type of the same name.** These are ordinary words, and
a blanket rename does not know whose they are. fm-robots had its own
`fm.robot.Books` alongside the SDK's, and a `\bOrderBooks\b` pass would have
walked into it. Grep your own packages for `Book`, `Books`, `Tape`, `Tapes`,
`Orders` and `Desk` before you run anything.

#### The order helpers agree across the three SDKs

`fm.Orders` (Java), `fm.order_utils` (Python) and `order-utils` (TypeScript) had
drifted apart in both directions.

**Withdrawn from TypeScript's entry point:** `isBuy`, `isSell`, `isCancel`,
`isLimit`. They became one-line comparisons the moment side and type became
enums, and Java deleted them in 0.1.0:

```ts
// before
if (isBuy(order)) …
// after
if (OrderSide.BUY === order.side) …
```

They still exist inside the TypeScript and Python SDKs, which use them
internally where Java's did not — but no SDK offers them as public API now.
`contra` stays exported in TypeScript alone: `OrderSide` is a string union
there, so unlike Java's and Python's enums it cannot carry the method that
replaced it.

**Added to Python and TypeScript**, having existed in Java only:

| | answers |
|---|---|
| `isConsumedOrSplit(order)` | has this order been traded against at all — the positive complement of `isAvailable` |
| `isSupplier(lhs, rhs)` | are these the two sides of one trade |
| `limit(market, side, units, price)` | a limit order ready to submit, without an object literal of mostly nulls |

**Newly reachable in TypeScript:** `isResting` was defined and never exported,
so TypeScript callers could not reach it while Java and Python could.

#### The two reconnect types say which layer they are

`Reconnected` and `ReconnectEvent` were one word apart and two layers apart,
which is the worst distance for a pair of names: close enough to look
interchangeable, far enough that using the wrong one is a category error.

| 0.1.x | 0.2.0 | what it is |
|---|---|---|
| `Reconnected` | `StreamReconnected` | the transport is back. Arrives on the `listen()` queue. Cannot fail |
| `ReconnectEvent` | `DeskRecovery` | the desk finished reconnecting **and** re-seeding over REST. Carries `success`, because either step can throw |
| `Desk.onReconnect` | `Desk.onRecovery` | subscribes to the second |

`StreamReconnected` now pairs with its sibling `StreamDropped`, which is what it
should always have done. `DeskRecovery` drops the `Event` suffix: every type in
`fm.event` is an event, so the suffix distinguished nothing while appearing to.

If you implement `Desk` — a test double, say — rename the override. If you only
call it, `onReconnect` becomes `onRecovery` and the payload type changes name;
its fields are unchanged.

#### The role interfaces are no longer names you can write

`Reading`, `Writing`, `Identity`, `Management`, `Administration` and `Streaming`
were public in `fm` through 0.1.x. They are now in `fm.role`, which the module
does not export.

**Nothing you call changes.** `Flexemarkets` still extends all six, so every
method they declare is still on the interface and still callable:

```java
List<Market> markets = flexemarkets.markets(marketplaceId);   // Reading, unchanged
```

What you can no longer do is name one — `void report(Reading books)` stops
compiling on the module path. They were exported so a signature could narrow to
one, and in two releases nothing ever did: fm-robots, fm-server and
fm-robots-server import zero of them between them. Both migrations found
structural reasons not to narrow, a robot holding a `Supplier<Flexemarkets>`
being the clearest — Java cannot spell an intersection of roles as a field type.

If you were narrowing, declare `Flexemarkets` instead. Re-exporting is additive,
so say so if you have a use for it.

#### `OrderBooks` and `MarketplaceTrades` are no longer yours to construct

Both moved to `fm.internal`, and were renamed there to `BookIndex` and
`TapeIndex`. Nothing outside the SDK held one any more — the
tickers and fm-robots' `Taker`, `TakerMvo` and `Venture` all read theirs from a
desk.

If you were building one, the replacement is a desk, which also seeds and
reseeds it for you:

```java
// before
var books = new OrderBooks(markets);
flexemarkets.listen(marketplaceId, queue);
// ... seed from activeOrders, track asOfSeq, drop deltas at or below it

// after
try (var desk = flexemarkets.desk(marketplaceId)) {
    var book = desk.book(marketId);   // seeded, gap-recovered, kept current
}
```

**How firmly this is enforced differs by language, and it is worth being exact.**
On the Java *module* path `module-info` does not export `fm.internal`, so the
import does not resolve. On the *classpath* it still does — fm-robots consumes
the SDK there and could import `fm.internal.BookIndex` if it chose to. Treat the
withdrawal as the contract regardless of which one your build uses.

`Book` and `Tape` stay public, so a `listen()` consumer can still aggregate. It
keeps its own `Map<Long, Book>` rather than a container.

#### Read-side methods are named after what they return

`orderBook(marketId)` returns a `Book` and `trades(marketId)` returns a `Tape`.

| 0.1.x | 0.2.0 |
|---|---|
| `flexemarkets.observe(marketplaceId)` | `flexemarkets.desk(marketplaceId)` |
| `desk.orderBook(marketId)` | `desk.book(marketId)` |
| `desk.trades(marketId)` | `desk.tape(marketId)` |
| `desk.onOrderBookChange(...)` | `desk.onBookChange(...)` |

`onTrade` keeps its name — it hands over a `Trade`, not a tape.

**Only the one-argument read-side method moved.** `Reading.trades(marketplaceId,
symbol)` is a different method that returns `List<Order>` off the REST path and
is unchanged. If you rename by name alone you will break it.

#### Five helpers were removed rather than moved

`OrderUtils` became `Orders`, and shed everything that had become a one-line
comparison once side and type were enums.

| gone | write instead |
|---|---|
| `OrderUtils.isBuy(order)` | `OrderSide.BUY == order.side()` |
| `OrderUtils.isSell(order)` | `OrderSide.SELL == order.side()` |
| `OrderUtils.isCancel(order)` | `OrderType.CANCEL == order.type()` |
| `OrderUtils.isLimit(order)` | `OrderType.LIMIT == order.type()` |
| `OrderUtils.contra(String)` | `order.side().contra()` |

What `Orders` keeps is what can only be answered by looking at several orders
together — `isAvailable`, `isConsumed`, `isConsumedOrSplit`, `isResting`,
`isSplit`, `isSubmit`, `isSupplier`, `isSymbol`, `findOrder`, `limit`.

### Python (0.2)

#### Renames (0.2, Python)

```bash
grep -rl 'MarketView\|OrderBook\|MarketplaceTrades\|\bSide\b\|order_book\|observe(' \
  --include='*.py' . | xargs sed -i -E \
  -e 's/\bMarketView\b/Desk/g' \
  -e 's/\bOrderBook\b/Book/g' \
  -e 's/\bSide\b/OrderSide/g' \
  -e 's/\bon_order_book_change\b/on_book_change/g' \
  -e 's/\border_book\(/book(/g' \
  -e 's/\.observe\(/.desk(/g'
```

`OrderBooks`, `MarketplaceTrades` and `Trades` are left out for the same reasons
as Java. The first two are withdrawn, not renamed: rewriting them to `BookIndex`
and `TapeIndex` gives you a name `fm` does not export, which is a worse failure than the
one the compiler would have given you. Rename `Trades` → `Tape` by import and
let the failures find the use sites.

| 0.1.x | 0.2.0 |
|---|---|
| `flexemarkets.observe(mp_id)` | `flexemarkets.desk(mp_id)` |
| `desk.order_book(market_id)` | `desk.book(market_id)` |
| `desk.trades(market_id)` | `desk.tape(market_id)` |
| `desk.on_order_book_change(...)` | `desk.on_book_change(...)` |

#### No longer exported from `fm` (0.2)

`MarketView`, `MarketplaceTrades`, `OrderBook`, `OrderBooks`, `Side` and
`Trades` are out of `__all__`. Four names replace them: `Book`, `Desk`,
`OrderSide`, `Tape`.

`BookIndex` and `TapeIndex` (formerly `OrderBooks` and `MarketplaceTrades`) are
withdrawn as API. Unlike TypeScript, Python cannot enforce that —
`from fm.orderbook import BookIndex` still resolves. It is
convention, and the convention is that a caller who wants books kept for them
asks for a desk.

### TypeScript (0.2)

#### Renames (0.2, TypeScript)

```bash
grep -rl 'MarketView\|OrderBook\|MarketplaceTrades\|\bSide\b\|orderBook\|observe(' \
  --include='*.ts' src test | xargs sed -i -E \
  -e 's/\bMarketView\b/Desk/g' \
  -e 's/\bOrderBook\b/Book/g' \
  -e 's/\bSide\b/OrderSide/g' \
  -e 's/\bonOrderBookChange\b/onBookChange/g' \
  -e 's/\.orderBook\(/.book(/g' \
  -e 's/\.observe\(/.desk(/g'
```

`OrderBooks` and `MarketplaceTrades` are deliberately absent, as in Java: they
are withdrawn rather than renamed, and TypeScript is the language that
*enforces* it — `package.json` exports only `"."`, so a rewrite to `BookIndex` gives
you an import that cannot resolve.

The method table is the Java one — TypeScript uses the same camelCase names.

#### No longer exported from the package entry point (0.2)

`MarketView`, `MarketplaceTrades`, `OrderBook`, `OrderBooks`, `Side` and
`Trades` are gone from `index.ts`; `Book`, `Desk`, `OrderSide` and `Tape`
replace them.

This is the one language where the withdrawal is enforced rather than agreed:
`package.json` exports only `"."`, so a deep import of `./orderbook.js` does not
resolve. If you were reaching past the entry point, that stops working now.

### Changes that are not renames (0.2)

Five things behave differently. A codebase that compiles after the
substitutions above can still be wrong in these ways.

#### 1. A desk seeds and re-seeds the book for you

This is the change that removes code rather than moving it. In 0.1.x a caller
who wanted a maintained book did the reconciliation itself: build `OrderBooks`, seed
from `activeOrders`, record the snapshot's `asOfSeq`, and drop deltas at or
below it.

A desk does all of that — and re-seeds after a sequence gap, which most
hand-written versions did not. fm-robots' `Taker` is the worked example: it lost
its `OrderBooks` field, its `seededAtSeq` field and its `_seedBook` method, and
gained gap recovery it never had.

If you keep your own aggregation off `listen()`, nothing changes for you. If you
move to a desk, delete your reconciliation rather than porting it.

#### 2. `Desk` answers the whole marketplace

`Desk.books()` and `Desk.tapes()` are new, alongside the existing
`book(marketId)` and `tape(marketId)`. A caller scanning every market previously
had to zip `markets()` against `book()`, or reach for the `OrderBooks` container
— which was the only remaining reason to hold one.

`Desk.books()` returns `Collection<Book>` and `Desk.tapes()` returns
`Collection<Tape>` — both of the public per-market types.

Each returns a snapshot of the collection; the books and tapes inside it stay
live, on the same terms as `book()` and `tape()`.

`Desk.tape(marketId)` is also new in the sense that matters: 0.1.x built the
tape, seeded it with a REST call on every desk, fed it on every delta — and
exposed no accessor. Every desk paid for something unreachable.

#### 3. Python and TypeScript now send the STOMP heartbeat they advertise

All three SDKs put `heart-beat:30000,30000` in CONNECT — "I will send one every
30s" — and only Java did. The other two wrote nothing to the socket after
SUBSCRIBE.

TypeScript was the exposed one: `ws` sends no WebSocket ping of its own unless
asked, so that client had no keepalive at any layer, and an idle robot's socket
was Heroku's to reap at 55s and log as an H15. Python was covered by accident —
the `websockets` library pings every 20s — but a ping is not a STOMP heartbeat.

Both now send a bare EOL every 25s, on the same lifecycle as Java. **If you
built a keepalive of your own to work around this, remove it.**

#### 4. One drop starts one reconnect

The reconnect is now held behind a gate in all three languages, so a burst of
drops produces a single reconnect rather than one per drop. If you were
de-duplicating `Reconnected` events downstream, you can stop.

#### 5. Java: a null primitive reads as the type's default

The SDK pinned `jackson-bom` 3.2.1 while fm-server inherits 3.1.5 from
`spring-boot-starter-parent` — so the SDK was tested against a Jackson its
largest consumer never ran. The two disagree about a default: 3.1.5 *rejects* a
null mapped onto a primitive, 3.2.1 coerces it. The server sends explicit nulls
for primitives it has no value for — `Market.marketplaceId`,
`Session.allocationId` — so the same payload parsed or threw depending on which
patch release you resolved.

0.2.0 takes Jackson 3.1.5, matching Spring Boot, and disables
`FAIL_ON_NULL_FOR_PRIMITIVES` explicitly. A null primitive now reads as the
type's default — `0` for a `long` — as a decision rather than as a default that
travelled with a patch version.

**What this means for you:** `market.marketplaceId() == 0` and
`session.allocationId() == 0` are how "the server said null" now presents. If
you were relying on an exception to tell you the field was absent, you were
relying on 3.1.5 and it will not throw any more.

### Verification (0.2)

```bash
# 1. The SDK's own checks, from the fm-sdk checkout
make check-sdks

# 2. Your codebase
#    Java — the whole reactor, not one module
mvn -q clean install
#    Python
pytest
#    TypeScript
npm run build && npm test
```

Then, by hand:

- [ ] No `import fm.<Type>;` remains for a type that moved — the compiler finds
      these, but check `fm.role` in particular, since six interfaces nobody
      imports will not announce themselves.
- [ ] No `OrderOrderSide` anywhere — the sign the `Side` script ran twice.
- [ ] `Reading.trades(marketplaceId, symbol)` still says `trades`. Only the
      one-argument desk method became `tape`.
- [ ] Every `isBuy` / `isSell` / `isCancel` / `isLimit` call site is now an enum
      comparison, and every `contra` is `side().contra()`.
- [ ] Nothing constructs `OrderBooks` or `MarketplaceTrades`. If something does,
      it wants a desk.
- [ ] Any hand-rolled WebSocket keepalive in a Python or TypeScript consumer is
      removed.
- [ ] Java: anywhere you treated a missing `marketplaceId` or `allocationId` as
      an exception now treats it as `0`.

---

## 0.1: from 0.0.x

This section is written to be executed, not just read. It is aimed at an agent
or engineer migrating a codebase that depends on fm-sdk 0.0.12 or earlier.

Every change below is mechanical unless it appears under
[Changes that are not renames](#changes-that-are-not-renames-01). Those alter
behaviour, and applying them blindly will produce code that compiles and is
wrong. Read that section before starting.

1. Work one language at a time. A codebase using two SDKs migrates twice.
2. For each row in the tables, search for the **Find** pattern and apply
   **Replace**. The patterns are chosen to be unambiguous; where one is not,
   the row says so in **Notes**.
3. Apply [Changes that are not renames](#changes-that-are-not-renames-01) by
   reading each call site, not by pattern.
4. Run the [verification](#verification-01) steps. Do not report the migration
   complete on a green compile alone — several of these changes compile fine
   and behave differently.

**Do not** add compatibility shims, aliases, or wrappers that restore the old
names. 0.1.0 exists to remove accreted API, and re-adding it under another name
reintroduces exactly what was removed.

### Why 0.1.0 breaks

0.0.x grew by accretion. Half the client interface was optional-at-runtime,
timestamps were strings each caller parsed differently, side and order type
were strings with constants beside them, and the same tick-rounding rule
existed in four places with two of them wrong. The version is `0.1.0` rather
than `0.0.13` because under semver's 0.x convention a minor bump is what
signals breakage, and the 0.0.x line had been additive throughout.

### Java (0.1)

#### Imports: types and exceptions moved out of holder classes

Seventeen records lived inside `public class Types` and seven exceptions inside
`public class Exceptions`. Both wrappers are gone; every type is top-level in
package `fm`.

| Find | Replace | Notes |
|---|---|---|
| `import fm.Types.X;` | `import fm.X;` | any of the 17 record types |
| `import fm.Types;` | *(delete)* | |
| `Types.X` | `X` | qualified references in code |
| `import fm.Exceptions.Y;` | `import fm.Y;` | any of the 7 exception types |
| `import fm.Exceptions;` | *(delete)* | |
| `Exceptions.Y` | `Y` | |

Types: `ApiRoot`, `Person`, `Account`, `Token`, `Session`, `Marketplace`,
`Market`, `Order`, `Holding`, `Security`, `Allotment`, `Assets`,
`ClientConnection`, `Version`, `ManagerOtpBundle`, `Approval`,
`ConflictFailure`.

Exceptions: `FlexemarketsException`, `AuthenticationException`,
`HttpException`, `ConflictException`, `AccountNameConflictException`,
`PersonHasMarketplaceDataException`, `ApiException`.

`ManagerOtpBundle.Entry` remains nested — it is part of its enclosing type.

Two of those seventeen do **not** become `fm.X`: `ApiRoot` (with its nested
`LinkObject`) and `Approval` moved to `fm.internal` instead, and the answer is
to delete the import rather than repoint it. See
[Types that moved to `fm.internal`](#types-that-moved-to-fminternal) before
applying the table above to either.

Three event records left the STOMP client, which was never meant to be API:

| Find | Replace |
|---|---|
| `import fm.Events.WsTransportError;` | `import fm.StreamDropped;` |
| `import fm.Events.WsException;` | `import fm.FrameUnreadable;` |
| `import fm.Events.Reconnected;` | `import fm.Reconnected;` |
| `WsTransportError` | `StreamDropped` |
| `WsException` | `FrameUnreadable` |

The two renames are not cosmetic. Both are **records delivered on the event
queue and never thrown**, and `Exception`/`Error` told a caller to `catch` what
they have to `instanceof`. They read now as three things that can happen to a
stream: `StreamDropped`, `Reconnected`, `FrameUnreadable`. Python and TypeScript
rename identically.

#### Types that moved to `fm.internal`

Two public types no caller could reach — nothing on `Flexemarkets`, the roles
or `Desk` returns or accepts either:

| type | was |
|---|---|
| `ApiRoot`, `ApiRoot.LinkObject` | HAL plumbing, read once at connect |
| `Approval` | `approveAccount` returns `Account`, not this |

If you import one, you were reaching past the contract.

**`Books`, `Tapes` and `Version` are unchanged.** A draft of
this guide listed them here; that was wrong and it never shipped that way.
`new Books(markets)` is a supported thing to write — `fm-robots` does it
in four places — and `Version` arrives on your event queue, so you need to be
able to name it. They are still `fm.Books`, `fm.Tapes` and
`fm.Version`. No import changes.

`Desk` remains the easier way to a maintained order book: it keeps the
books and the trade history for you, and `desk(marketplaceId)` is one call.
Building the aggregators yourself stays supported for code that wants to drive
the event queue directly.

#### The implementation is no longer importable

`fm.HttpFlexemarkets` and `fm.Events` moved to `fm.internal`, along with
`DefaultDesk` and `DeskHandle`. They were public by accident of
sharing a package with the interface they implement — `HttpFlexemarkets` was a
public class nobody could construct.

Nothing should be importing them. If something does, it is reaching past the
contract: `Flexemarkets.connect` builds the client and `Desk.over` builds
the desk, and those are the supported ways to get one. Do not import from
`fm.internal` to restore the old code — that is the same reach with a longer
name.

`Endpoints.DEFAULT_HOST` is now public, if what you wanted was the default host.

#### Renames (0.1)

| Find | Replace |
|---|---|
| `.activeOrdersV1(` | `.activeOrders(` |
| `.recentTradesV1(` | `.recentTrades(` |
| `.account(someId)` | `.accountById(someId)` |
| `.user(someId)` | `.userById(someId)` |
| `.getSecurity(` | `.security(` |
| `.getSecurities()` | `.securities()` |

`createMarket` takes the unit grid it used to hardcode:

```java
// before — unit bounds fixed at 1/100/1, with no way to say otherwise
fm.createMarket(5, "STK", "Stock", 0, 10_000, 1, false);

// after — both dimensions, named so they cannot be transposed
fm.createMarket(5, "STK", "Stock",
                new TickGrid(0, 10_000, 1), TickGrid.units(), false);
```

`TickGrid.units()` is the old default. Python and TypeScript default the unit
grid when it is omitted; Java requires both, having no default arguments.

**`account()` and `user()` with no argument are unchanged** — they answer who
the connection is signed in as. Only the single-argument forms were renamed, so
a blind replace of `.account(` is wrong. Match on the argument.

#### OrderSide and order type are enums

| Find | Replace |
|---|---|
| `Order.SIDE_BUY` | `OrderSide.BUY` |
| `Order.SIDE_SELL` | `OrderSide.SELL` |
| `Order.TYPE_LIMIT` | `OrderType.LIMIT` |
| `Order.TYPE_CANCEL` | `OrderType.CANCEL` |
| `OrderUtils.contra(side)` | `side.contra()` |
| `OrderUtils.isBuy(sideString)` | `OrderSide.BUY == side` |
| `OrderUtils.isBuy(order)` | `OrderSide.BUY == order.side()` |
| `OrderUtils.isSell(order)` | `OrderSide.SELL == order.side()` |
| `OrderUtils.isCancel(order)` | `OrderType.CANCEL == order.type()` |
| `OrderUtils.isLimit(order)` | `OrderType.LIMIT == order.type()` |

`OrderUtils` keeps what needs a set of orders to answer — `isAvailable`,
`isConsumed`, `isSplit`, `isResting`, `isSubmit`, `isSymbol`, `findOrder`. The
four above became one-line enum comparisons when side and type became types, and
two ways to ask one question is one too many.

`submitLimit`, `submitMarket` and `Desk.submitLimit` take `OrderSide`.
`Order.side()` returns `OrderSide` and `Order.type()` returns `OrderType`; both may
be null — a cancel carries no side, and an unrecognised value parses to null
rather than throwing.

To convert a string of unknown provenance, use `OrderSide.of(value)` or
`OrderType.of(value)`. Both are case-insensitive and return null for anything
unrecognised.

#### Implementing `Flexemarkets`

This affects test fakes and alternative implementations, not ordinary callers.

`Flexemarkets` now composes six role interfaces — `Identity`, `Reading`,
`Writing`, `Management`, `Administration`, `Streaming` — and **every method on
them is abstract**. In 0.0.x roughly half were `default` methods that threw, so
a partial implementation compiled. It no longer does.

Two ways forward, in order of preference:

1. **Narrow the type.** A fake that only reads should implement `Reading` and
   the code under test should declare `Reading`. A fake for a trading robot
   implements `Writing`. This is the reason the roles exist: a `Writing` fake is
   three methods where a `Flexemarkets` fake is sixty.
2. **Implement everything.** If the fake genuinely stands in for a whole
   connection, add the missing members. The compiler lists them.

Prefer declaring the narrowest role in your own signatures too:
`void report(Reading books)` says in the type that the method cannot place an
order.

### Python (0.1)

Python's changes are smaller: `StrEnum` members *are* strings, so most existing
code keeps working.

| Find | Replace |
|---|---|
| `.active_orders_v1(` | `.active_orders(` |
| `.recent_trades_v1(` | `.recent_trades(` |
| `.get_security(` | `.security(` |
| `Order.SIDE_BUY` | `OrderSide.BUY` |
| `Order.SIDE_SELL` | `OrderSide.SELL` |
| `Order.TYPE_LIMIT` | `OrderType.LIMIT` |
| `Order.TYPE_CANCEL` | `OrderType.CANCEL` |

`OrderSide` and `OrderType` are importable from `fm`. Passing a plain `"BUY"` still
works — but compare with `==`, never `is`: `"BUY" is OrderSide.BUY` is `False`.

**`connect_with_token` is gone.** Use `connect(token, endpoint, description)`.
See [token authentication](#7-token-authentication-never-worked) — this is a
deletion rather than a rename, because what it did never worked.

`create_market` takes the unit grid it used to hardcode:

```python
# before
fm.create_market(5, "STK", "Stock", 0, 10_000, 1)

# after — units default to TickGrid.units() when omitted
fm.create_market(5, "STK", "Stock", TickGrid(0, 10_000, 1))
fm.create_market(5, "STK", "Stock", TickGrid(0, 10_000, 1), TickGrid(10, 500, 10))
```

#### No longer exported from `fm` (0.1)

| import | do this instead |
|---|---|
| `from fm import ApiRoot` | nothing — HAL is private (`fm._hal`) and is going away |
| `from fm import EventListener` | `listen()` returns a callable that stops it |
| `from fm import DeskHandle` | `fm.desk(marketplace_id)` returns one |

These matched Java, where the same three are unreachable. Nothing in a public
signature mentions them.

`Version`, `Books` and `Tapes` are still exported.

### TypeScript (0.1)

| Find | Replace |
|---|---|
| `.activeOrdersV1(` | `.activeOrders(` |
| `.recentTradesV1(` | `.recentTrades(` |
| `ORDER_SIDE_BUY` | `OrderSide.BUY` |
| `ORDER_SIDE_SELL` | `OrderSide.SELL` |
| `ORDER_TYPE_LIMIT` | `OrderType.LIMIT` |
| `ORDER_TYPE_CANCEL` | `OrderType.CANCEL` |
| `FlexemarketsOptions` | *(delete — no function ever accepted it)* |

`OrderSide` and `OrderType` are literal unions with same-named const objects, so
`"BUY"` and `OrderSide.BUY` are interchangeable.

#### No longer exported from the package entry point (0.1)

| import | do this instead |
|---|---|
| `ApiRoot` | nothing — HAL is private (`src/hal.ts`) and is going away |
| `getLink` | it had no callers in any SDK; `root.links[name]` was always the body |
| `EventListener` | `listen()` returns a function that stops it |
| `DefaultDesk`, `DeskHandle` | `fm.desk(marketplaceId)` returns one |

`Version`, `Books` and `Tapes` are still exported.

`createMarket` takes the unit grid it used to hardcode; omit it for the old
default:

```ts
// before
await fm.createMarket(5, "STK", "Stock", 0, 10_000, 1, false);

// after
await fm.createMarket(5, "STK", "Stock", { minimum: 0, maximum: 10_000, tick: 1 });
```

### Changes that are not renames (0.1)

Each of these compiles after a mechanical migration and behaves differently.
Review every call site by hand.

#### 1. Timestamps are typed, and were previously parsed wrongly

Every date field changed from a string to a moment: `Instant` in Java, an
**aware** `datetime` in Python, `Date` in TypeScript.

Affected: `createdDate`, `lastModifiedDate`, `openDate`, `closeDate`,
`established`, `terminated`, `expiresAt` (and their snake_case spellings).

**The old values were being misread.** The server sends two shapes: audit
fields arrive bare (`"2017-04-11T00:54:35.135"`, no zone) and `expiresAt`
arrives zoned (`"2026-08-15T18:00:00Z"`). A bare timestamp is UTC, because the
server's clock is. Anything that did:

- JavaScript: `new Date(order.createdDate)` — reads a bare value as **local
  time**, so it was adrift by the reader's offset. Correct in UTC, correct in
  CI, wrong on a laptop west of Greenwich.
- Python: `datetime.fromisoformat(...)` — returns a **naive** datetime, which
  raises when compared with an aware one and assumes local time in
  `.timestamp()`.

…was wrong, and is now fixed by the SDK. **Delete your own parsing**; do not
wrap the new value to restore a string. If you need one, format it explicitly.

Unparseable values are `null`/`None`, never an exception or an `Invalid Date`.

#### 2. `submitMarket` was broken and is now immediate-or-cancel

In 0.0.x it sent `Long.MAX_VALUE` to buy and `0` to sell — prices no market
accepts. Every buy was refused; a sell survived only where `priceMinimum`
happened to be zero. If you worked around it, remove the workaround.

It now crosses the book at the extreme legal price and **cancels the
remainder**. Two consequences:

- It costs an extra round trip (it fetches the market to learn its bounds) and
  a second call for the cancel.
- Unfilled units do not rest. If your code relied on a market order leaving a
  resting order, it will not.

If the cancel fails the order is still placed, and the SDK throws saying so.
**Do not retry the whole call** on that error, or you will trade twice.

#### 3. `priceRound` returns different values, and `unitRound` now exists

The tick grid is anchored at the *minimum*, not at zero — the server checks
`(value - minimum) % tick`. The old implementation subtracted `value % tick`,
which is correct only when the floor is a multiple of the tick.

On a market with `priceMinimum=110, priceTick=25`, rounding `137` returned
`125` before and returns `135` now. `125` was inside the bounds, off the grid,
and refused by the server with *"price is not on a tic"*. If you compensated
for that, remove the compensation.

A tick of `0` marks a fixed dimension and used to divide by zero.

`unitRound` is new. The server checks units on identical terms and refuses with
*"units is not on a tic"*, so code that rounded a price and passed raw units
was guarded on one axis only.

#### 4. A holding's positions are ordered and may be absent

`securities` is now always present (never null) and always in market order. Two
holdings of the same positions now compare equal regardless of the order the
server listed them in — if you sorted defensively, you can stop.

`getSecurity` **threw** for a market the holder had no position in. It now
returns `Optional.empty()` (Java), `None` (Python) or `null` (TypeScript).
Having no position is ordinary — it is what every holding looks like before the
first allocation. Any `try`/`catch` around it is now dead code and will hide a
real error later.

#### 5. Conflict exceptions changed shape

`AccountNameConflictException` and `PersonHasMarketplaceDataException` now
extend `ConflictException`. Both extended the base directly, so
`catch (ConflictException)` did not catch either — the two cases the server
describes most precisely were the two a general handler missed. TypeScript
gained all three types, which it did not have at all: a 409 arrived as a bare
`FlexemarketsError` reading `HTTP 409: {...}`.

In Python, `ConflictError` is now actually raised for a 409 — it was exported
and never thrown, so a handler for it caught nothing and `httpx.HTTPStatusError`
escaped instead. If you were catching `httpx.HTTPStatusError` for 409s, catch
`ConflictError`.

#### 6. `sessions` and `connections` no longer take a session filter

They never filtered. `GET /marketplaces/{id}/sessions` and
`/marketplaces/{id}/connections` accept only `format` — the server has no
parameter for it — so the SDKs put `?sessionIds=` on the wire and got the whole
history back, looking filtered.

```java
// before — the argument was ignored, silently
fm.sessions(1744, List.of(300L, 301L));      // returned all 672
fm.connections(1744, List.of(300L));         // returned all 49

// after — read them and filter
fm.sessions(1744).stream().filter(s -> wanted.contains(s.id())).toList();
```

A connection carries the session it belonged to, so "who was present in that
run" is a filter on the result.

**Most call sites need no change**, because most already filter client-side:
fm-ui fetches `/sessions` and `/connections` unfiltered and narrows in the
component, fm-manager's `_sessions` filters in a stream, and capm carries a
comment explaining that a marketplace's session list is small enough to read
whole. Only code that passed the ids and trusted the answer is affected — and
that code was getting the wrong answer.

**`holdings` and `downloadHoldings` are unaffected.** Those routes do take
`sessions=` and genuinely filter; only these two invented one.

#### 7. Token authentication never worked

**Python and TypeScript only.** Connecting with a token instead of a password
has failed since each SDK was written. Both POSTed `/tokens` with the bearer
header and a body carrying an empty password, and fm-server answers that
`400 MESSAGE_NOT_READABLE`. Both now use `GET /tokens/refresh`, which is what
the Java SDK has always used.

Two consequences for a migrating codebase:

- **Remove any workaround.** Anything that fetched a token by hand, or fell
  back to a password because "the token path doesn't work", can go.
- **`connect_with_token` is deleted, not renamed.** It was a second spelling of
  the same broken request. Use `connect(token, endpoint, description)`.

Python had a second defect behind the first: a real JWT is around 470
characters, and `Path.is_file()` raises `OSError: File name too long` rather
than returning `False`, so `connect(token, ...)` raised before reaching the
network. If you have a `try`/`except OSError` around a connect call, it is now
dead code.

If your tests stub the transport, they will not have caught any of this — every
fake in this SDK asserted the broken request, because that is what the SDK
sent. Check test fakes answer `GET /api/tokens/refresh`.

#### 8. `createMarketplace` is gone; it could never have worked

`createMarketplace(name, description)` sent exactly that, and the server
requires at least one market:

```
MARKETPLACE_INVALID: At least one market is required to create a marketplace
```

So every call was a 400, in all three SDKs, for as long as the method existed.
Use `createMarketplaceFromJson`, which is the path every study already takes —
the javadoc there reads like a preference for keeping definitions as files, and
was in fact describing the only thing that worked.

```java
// before — always 400
var mp = fm.createMarketplace("course", "class 2");

// after
var mp = fm.createMarketplaceFromJson("""
    {"name":"course","description":"class 2","configuration":"",
     "markets":[{"symbol":"STK","name":"Stock","description":"",
                 "priceMinimum":100,"priceMaximum":200,"priceTick":25,
                 "unitMinimum":1,"unitMaximum":100,"unitTick":1,
                 "privateMarket":false}]}
    """);
```

`createMarket` still adds a market to a marketplace that exists, and is
unaffected.

**fm-robots has sixteen call sites**, all in `api-validator` —
`MarketplaceValidator` (5) and `SessionValidator` (1) plus their helpers. They
have never succeeded, so whatever those suites were reporting was not what they
meant to report.

#### 9. The stream restores itself, and says so (Python and TypeScript)

Java has always reconnected the subscription in the background after a
`StreamDropped` and then put a `Reconnected` on the queue. Python and
TypeScript did neither: `listen()` delivered a `StreamDropped` and the stream
stayed dead until you called `fm.reconnect()` yourself.

If your code does this, **delete it** — you will now reconnect twice:

```python
# before — the caller drove the reconnect
if isinstance(event, StreamDropped):
    fm.reconnect()
```

```python
# after — the listener has already done it; reseed when it confirms
if isinstance(event, Reconnected):
    reseed()          # everything missed while the socket was down
```

`Reconnected` carries `marketplace_id` / `marketplaceId`, since one client can
hold several subscriptions. `fm.reconnect()` still exists and still works.

Code using `desk()` needs no change: `Desk` reseeds and fires
`on_reconnect` / `onReconnect` exactly as before.

TypeScript's discriminants changed with the type names — the `kind` tag was
left speaking the old vocabulary:

| Find | Replace |
|---|---|
| `kind === "transport-error"` | `kind === "stream-dropped"` |
| `kind === "ws-exception"` | `kind === "frame-unreadable"` |

`tsc` catches every one of these, provided your `tsconfig` covers the files
that switch on it.

### Verification (0.1)

A green compile is necessary and not sufficient. Run all of it.

```bash
# 1. The SDK's own checks, from the fm-sdk checkout
make check-parity          # types, method surfaces and failure types agree
make check                 # the above, plus every SDK's build and test suite

# 2. Your codebase
#    Java — the whole reactor, not one module
mvn -o test
#    Python
python -m pytest
#    TypeScript
npm run check && npm test
```

Two traps in that second block, both of which caught this repo during 0.1.0:

- **`mvn -pl <module> test` is not enough.** Narrowing the reactor to the
  module you are migrating skips the ones that consume it. A broken example
  went unnoticed for a day that way.
- **`tsc --noEmit` only covers what `tsconfig.json` includes.** If `include`
  is `["src"]`, your tests are never typechecked, and `tsx`/`ts-node` strip
  types without checking them — so a test can assert something the source
  contradicts and pass. Check what your `include` actually covers before
  trusting a green typecheck.

Then confirm by inspection, because these are the ones tests in your codebase
are least likely to cover:

- [ ] No remaining reference to `Types.`, `Exceptions.`, `*V1(`, `SIDE_*`,
      `TYPE_*`, `ORDER_SIDE_*`, `ORDER_TYPE_*`, `getSecurit*`,
      `FlexemarketsOptions`.
- [ ] No hand-rolled timestamp parsing left over — search for `new Date(`,
      `fromisoformat`, `LocalDateTime.parse`, `Instant.parse` near SDK values.
- [ ] Every `catch` around a `getSecurity` call removed.
- [ ] Every workaround for `submitMarket` removed.
- [ ] Any `.account(` / `.user(` call with an argument renamed, and any without
      one left alone.
- [ ] Test fakes either narrowed to a role or completed.
- [ ] Test fakes that stand in for the server answer `GET /api/tokens/refresh`,
      not `POST /api/tokens`, for token auth.
- [ ] No import of `fm.HttpFlexemarkets`, `fm.Events`, or anything under
      `fm.internal`.
- [ ] Every `createMarket` call passes a price grid, and passes a unit grid if
      the market is not 1/100/1.
- [ ] No remaining `connect_with_token`.
- [ ] No remaining `createMarketplace(name, description)`; marketplaces are
      made with `createMarketplaceFromJson`.
- [ ] Every `sessions(...)` and `connections(...)` call passes one argument, and
      anything that relied on the filter now narrows the result itself.
- [ ] No `fm.reconnect()` / `reconnect()` call driven by a `StreamDropped` —
      the listener reconnects itself now, so that code reconnects twice.
- [ ] Anything that reseeded on a `StreamDropped` now reseeds on `Reconnected`.
- [ ] No `kind === "transport-error"` or `kind === "ws-exception"` left in
      TypeScript.
