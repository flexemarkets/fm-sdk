# Upgrading from fm-sdk 0.12.x to 0.13.x

## How to use this document

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

What 0.7.0 settled is in [UPGRADING-0.7.md](UPGRADING-0.7.md) and is not
revisited here.

---

## What changes in behaviour

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

---

## What you need

The same server as 0.12.0 -- fm-server 4.16.0 or later, for the `/api/v1`
sign-in and stream. The decimal fields are read when the server sends them
and defaulted when it does not.

---

## Checking your code

```bash
# Java: implementations of the client, and record patterns
grep -rnE "implements (Flexemarkets|Writing|Management|Streaming)\b" src/
grep -rnE "(Market|Marketplace|Order|Session|Security|Holding)\(" src/ | grep -E "instanceof|case "
# TypeScript: literals typed as an SDK model
grep -rnE ": (Market|Marketplace|Order|Session|Security|Holding)\b|as (Market|Marketplace|Order|Session|Security|Holding)\b" src/
```
