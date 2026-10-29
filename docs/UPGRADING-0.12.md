# Upgrading from fm-sdk 0.11.x to 0.12.x

## How to use this document

0.12.0 moves the routes the SDK signs in and streams through to `/api/v1`,
where every other route it calls already was, and **needs fm-server 4.16.0
or later**: against an older server it cannot sign in. Read
[What you need](#what-you-need) before anything else.

Otherwise nothing a caller wrote has to change. `allWidgets` keeps its
signature and now works, and the stream delivers one more kind of event, a
call of a call-auction market. Code that reads every event off a
subscription sees the new one:

| | What breaks | Fix |
|---|---|---|
| Java | nothing: the queue given to `subscribe(marketplaceId, queue)` takes `Object`, and now also receives `MarketCall` | ignore it, or handle it |
| TypeScript | the `FmEvent` union gains `MarketCall`: a switch or type guard that must cover every member no longer does | handle `MarketCall`, or let it fall through |
| Python | nothing: the queue given to `subscribe(marketplace_id, event_queue)` now also receives `MarketCall` | ignore it, or handle it |

What 0.7.0 settled is in [UPGRADING-0.7.md](UPGRADING-0.7.md) and is not
revisited here.

---

## What you need

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

---

## What changes in behaviour

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

---

## Checking your code

```bash
# Code that reads every event off a subscription
grep -rnE "\.subscribe\(|FmEvent" src/
# Callers of allWidgets that worked around its failure
grep -rnE "allWidgets|all_widgets" src/
```
