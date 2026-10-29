# Upgrading from fm-sdk 0.16.x to 0.17.x

## How to use this document

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

Earlier releases are covered by their own upgrade notes, listed in the
[README](../README.md#upgrade-notes), and are not revisited here.

---

## What is new

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

- **It names the order by its original.** `submitCancel`'s DELETE looks the
  order up and cancels its lineage from any of its ids. The socket's CANCEL
  goes to the exchange as named, which matches by `original`. For the order a
  `sendLimit` or `submitLimit` answered, that is its `id`. Pass the `id` of a
  remainder a split left behind and the CANCEL is recorded, consumed by
  itself, and nothing is cancelled; pass `order.original` when the order came
  from the book or the stream.
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

---

## What changes in behaviour

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

---

## What fm-server changed under you

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

---

## Checking your code

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
