# Upgrading from fm-sdk 0.15.x to 0.16.x

## How to use this document

0.16.0 changes no signature, in any language. Code that **implements** the
SDK's types needs nothing, and so does code that **calls** it, with one
exception: code that sends orders over the socket with `sendLimit`
(`send_limit` in Python). For it, an outage now ends differently:

- the order socket comes back within five seconds of the server, not up to a
  minute after it;
- an order sent while the socket is down is refused after 5 s, as not sent,
  where it was held and placed whenever the socket returned.

A robot that sends orders with `sendLimit` must expect that refusal, and
decide whether to send the order again. Code that sends orders only with
`submitLimit` sees no change.

| | Refused, not sent | Unchanged: sent, answer lost |
|---|---|---|
| Java | `PendingOrder` completes exceptionally with `ConnectionFailedException` | `OrderOutcomeUnknownException` |
| TypeScript | `PendingOrder` rejects with `ConnectionFailedError` | `OrderOutcomeUnknownError` |
| Python | `PendingOrder` fails with `ConnectionFailedError` | `OrderOutcomeUnknownError` |

It needs the same server as 0.15.0: fm-server 4.17.

What 0.15.0 changed is in [UPGRADING-0.15.md](UPGRADING-0.15.md) and is not
revisited here.

---

## What changes in behaviour

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
order was not sent because the order socket was down for more than 5 s. That
is the failure `submitLimit` meets in an outage, when REST answers 503.

An order the SDK had already tried to send is not refused this way: it may
have been placed, so it is sent again, as before, whenever the socket
returns. If the stream closes first, it fails as `OrderOutcomeUnknownException`
(`OrderOutcomeUnknownError`), as in 0.15.0.

---

## What to handle

Where a robot reads the outcome of a `PendingOrder`, treat the two failures
differently:

- `ConnectionFailedException` / `ConnectionFailedError`: the order was not
  sent and cannot have been placed. Sending it again cannot double it; whether
  to is the robot's decision -- the price it was sent at may be stale by the
  time the socket is back.
- `OrderOutcomeUnknownException` / `OrderOutcomeUnknownError`: it may have
  been placed. Read the orders before sending it again.

A robot that waited out an outage on its pending orders, counting on them to
be placed when it ended, now gets refusals after 5 s instead, and has to send
again what it still wants on the book.

---

## Checking your code

```bash
# Callers of the socket send, and where their outcomes are read
grep -rnE "sendLimit|send_limit|PendingOrder" src/
# Failure handling that may need to tell not-sent from outcome-unknown
grep -rnE "ConnectionFailed(Exception|Error)|OrderOutcomeUnknown(Exception|Error)" src/
```
