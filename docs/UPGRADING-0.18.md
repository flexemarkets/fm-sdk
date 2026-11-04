# Upgrading from fm-sdk 0.17.x to 0.18.x

## How to use this document

0.18.0 is additive for code that **calls** the SDK: no signature changes, and
code that connects as before gets the behaviour it had. Java code that
**implements** `Writing` or `Flexemarkets` has one method to add, a second
`sendCancel`.

It needs the same server as 0.17.0, fm-server 4.17, except where noted:
the REST order route below needs fm-server 4.19, as `submitLimits` does, and a
cancel by submission id needs fm-server 4.20.

Earlier releases are covered by their own upgrade notes, listed in the
[README](../README.md#upgrade-notes), and are not revisited here.

---

## What is new

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

---

## Checking your code

```bash
# Java classes implementing Writing or Flexemarkets, which need sendCancel(long, long, String) (run from the robot's source root)
grep -rnE "implements .*(Writing|Flexemarkets)\b" .
```

Nothing else to change. To try the REST route on a robot that already uses
`sendLimit`, connect with it; nothing else changes.
