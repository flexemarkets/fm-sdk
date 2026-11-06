# Upgrading from fm-sdk 0.18.x to 0.19.x

## How to use this document

No signatures change, and it needs the same server as 0.18.0. Two things change
for a robot: what a lost connection raises in Python and TypeScript, and what a
desk does when it cannot reseed, in all three.

Earlier releases are covered by their own upgrade notes, listed in the
[README](../README.md#upgrade-notes), and are not revisited here.

---

## A lost connection is an `ApiError` (Python, TypeScript)

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

## A desk that cannot reseed after a gap goes on (all three)

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

## Every Python call renews an expired token

`active_orders`, `recent_trades`, `download_holdings`, `delete_user`,
`remove_widget` and the CSV uploads answered an expired token with
`AuthenticationError`, where every other call signed in again and carried on. A
desk's reseed is the first two, so a robot past its 24 hours could not recover
from a gap or a reconnect. A workaround that re-reads the session to renew can go.

## `first_robot.py` runs

It raised `TypeError` as soon as a session opened: `Desk.markets` is a property,
and the example called it. Anything copied from it needs the same change.

## The expired-token error's name

`docs/API-REFERENCE.md` gave it as `AUTHENTICATION_TOKEN_EXPIRED`. fm-server sends
`ACCOUNT_TOKEN_EXPIRED`, as the SDKs have always read it. Code branching on the
name in the document never matched.

---

## Checking your code

```bash
# Python
grep -rnE "httpx\.[A-Za-z]*(Error|Timeout)" .
# TypeScript: catch blocks that test for these
grep -rnE "instanceof TypeError|TimeoutError" .
# The name that never matched, and threads watched by name
grep -rnE "AUTHENTICATION_TOKEN_EXPIRED|fm-desk-" .
```
