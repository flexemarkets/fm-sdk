# Upgrading from fm-sdk 0.17.x to 0.18.x

## How to use this document

0.18.0 is additive, for code that **calls** the SDK and for code that
**implements** its types: no signature changes, and no method to add. Code
that connects as before gets the behaviour it had.

It needs the same server as 0.17.0, fm-server 4.17, except where noted:
the REST order route below needs fm-server 4.19, as `submitLimits` does.

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

---

## Checking your code

Nothing to change. To try the REST route on a robot that already uses
`sendLimit`, connect with it; nothing else changes.
