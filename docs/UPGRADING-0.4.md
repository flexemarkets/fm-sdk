# Upgrading from fm-sdk 0.3.x to 0.4.x

## How to use this document

Read the first two sections. **0.4.0 changes no signature in any of the three
SDKs** — every method a 0.3 caller compiles against is still there, taking the
same arguments. What changes is where each call goes, and that changes what a
few of them answer. Those are listed under *What answers differently*; if none
applies to you, changing the version and rebuilding is the whole upgrade.

What 0.3.0 settled is in [UPGRADING-0.3.md](UPGRADING-0.3.md) and is not
revisited here.

---

## What you need

**fm-server 4.6.2 or later.** 0.4.0 calls only `/api/v1` routes, in the
model-REST forms of fm-server's `docs/API-V1.md` §4, and two of them —
`DELETE /api/v1/marketplaces/{id}` and `GET orders?cancelled=false` — first
shipped in 4.6.2. Against an older server those two calls answer 404 or 400;
everything else works from 4.6.0.

0.3.x keeps working against 4.6.x: every route it calls is still served.

---

## What changed underneath

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

---

## What answers differently

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

---

## What is new

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

---

## What is not in 0.4.0

The other items in [DESIGN-0.4.md](DESIGN-0.4.md) — a TypeScript logger,
all-markets subscriptions, diagnostics through `System.Logger` — are open and
not in this release.
