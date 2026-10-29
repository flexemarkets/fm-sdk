# Upgrading from fm-sdk 0.14.x to 0.15.x

## How to use this document

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

Earlier releases are covered by their own upgrade notes, listed in the
[README](../README.md#upgrade-notes), and are not revisited here.

---

## What is new

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
