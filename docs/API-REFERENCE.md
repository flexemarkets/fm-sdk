# Flexemarkets REST & WebSocket API reference

The deep reference behind the SDKs. Every SDK in this repository is a thin,
idiomatic wrapper over the HTTP and STOMP surface described here — read this
when you are building a client in a language we don't ship, debugging a request
the SDK makes on your behalf, or driving the platform from `curl`.

For a task-level introduction aimed at non-developers, see the in-app
**Developer / SDK** guides (`/documentation/SDK-OVERVIEW` and
`/documentation/AUTH-AND-OTP`) on the platform.

- [Base URL and endpoint resolution](#base-url-and-endpoint-resolution)
- [Authentication](#authentication)
- [Acting on behalf of participants (OTP)](#acting-on-behalf-of-participants-otp)
- [Error responses](#error-responses)
- [API root](#api-root)
- [REST surface](#rest-surface)
- [Snapshots and the sequence contract](#snapshots-and-the-sequence-contract)
- [WebSocket (STOMP)](#websocket-stomp)

---

## Base URL and endpoint resolution

Production runs at `https://api.flexemarkets.com`; the REST base path is
`/api`. All SDKs accept an *endpoint* that names a marketplace and derive the
server base from it by truncating at the `/api` path segment:

| Endpoint value | Resolves to |
|----------------|-------------|
| `2540` (bare digits) | `https://api.flexemarkets.com/api/marketplaces/2540` |
| `https://host/api/marketplaces/123` | used as given; server base = `https://host/api` |
| a readable file path | loaded as Java `.properties`, `endpoint=…` key |

`FM_API_URL` overrides the default host. The marketplace id is the last path
segment of the resolved endpoint — that is where `endpointMarketplaceId` /
`endpoint_marketplace_id` comes from.

## Authentication

Every authenticated request carries a JWT:

```
Authorization: Bearer <token>
```

### Exchange credentials for a token

```http
POST /api/tokens
Content-Type: application/json

{"username": "<account>|<email>", "password": "<password>"}
```

The account name and email are joined by a **pipe**. The response also echoes
the token in an `Authorization` response header:

```json
{
  "requestUrl": "https://api.flexemarkets.com/api/tokens",
  "token": "eyJhbGciOiJIUzUxMiJ9…",
  "person": { "id": 123, "email": "user@example.com", … },
  "account": { "id": 45, "name": "myaccount", … }
}
```

`person` and `account` are how a client learns its own user id, account id and
roles without a second round trip.

| Endpoint | Purpose |
|----------|---------|
| `POST /api/tokens` | credential body (above) → token |
| `POST /api/tokens/basic` | same result from an HTTP Basic header, `account\|email:password` |
| `GET /api/tokens/refresh` | re-issue a token for the current session; `401` once the user or account is gone |

Already holding a bearer token? `POST /api/tokens` with the token in the
`Authorization` header and an empty password exchanges it for the full
`TokenResult` above — this is what the SDKs do when `~/.fm/credential` contains
a `token=` line instead of a password.

> The API root and most resources reject HTTP Basic directly; authenticate
> first, then send the bearer token.

## Acting on behalf of participants (OTP)

To drive many participants at once — running robots, replaying a session — a
**manager** mints short-lived one-time passwords instead of collecting each
participant's password.

**1. Mint (manager only).** `POST /api/otp/manager` requires `ROLE_MANAGER`:

```http
POST /api/otp/manager
Authorization: Bearer <manager token>
Content-Type: application/json

{"userIds": [501, 502, 503]}
```

```json
{
  "expiresAt": "2026-07-28T04:15:00Z",
  "otps": [
    {"userId": 501, "email": "p1@example.com", "otp": "…"},
    {"userId": 502, "email": "p2@example.com", "otp": "…"}
  ]
}
```

Every id must belong to the manager's own account. A single unknown or
cross-account id refuses the **whole batch** with `403 ACCESS_DENIED` — mint per
batch, not per stray id. Bundle OTPs live for **five minutes**.

**2. Redeem.** `GET /api/otp?otp=<otp>` returns a `TokenResult` for that user.
Each OTP is single-use.

`POST /api/otp` is the *other* direction — it mints a single OTP from a
credential body, for handing a signed-in session to another process. It does not
redeem.

No participant password is ever exposed, and impersonation cannot cross account
boundaries.

## Error responses

Failures share one body shape:

```json
{
  "status": "NOT_FOUND",
  "error": "MARKETPLACE_NOT_FOUND",
  "message": "Marketplace not found.",
  "path": "/api/marketplaces/99999999",
  "shortDigest": "F87256"
}
```

| Field | Meaning |
|-------|---------|
| `status` | the HTTP status **name**, not the numeric code |
| `error` | the machine-readable failure kind — branch on this |
| `message` | user-facing text, safe to surface |
| `path` | the request URI |
| `shortDigest` | correlation id; quote it in a bug report to find the server-side record |

**Branch on `error`, not `message`.** The field is called `error` even though
the server-side Java field is named `type` — clients that look for `type` will
silently miss every failure kind.

Failure kinds are grouped by domain: `ACCOUNT_*`, `PERSON_*`, `MARKETPLACE_*`,
`MARKET_*`, `SESSION_*`, `ORDER_*`, `ALLOTMENT_*`, `GRANT_*`,
`OWNERSHIP_TRANSFER_*`, plus `ACCESS_DENIED`, `RESOURCE_NOT_FOUND`,
`MISSING_REQUIRED_PARAMETER`, `AUTHENTICATION_TOKEN_EXPIRED` and
`UNSUPPORTED_MEDIA_TYPE`. The ones a trading client meets most:

| `error` | Typical status | Cause |
|---------|----------------|-------|
| `ACCOUNT_INVALID_CREDENTIALS` | `UNAUTHORIZED` | bad account/email/password |
| `AUTHENTICATION_TOKEN_EXPIRED` | `UNAUTHORIZED` | refresh or re-authenticate |
| `ACCESS_DENIED` | `FORBIDDEN` | authenticated but lacking the role |
| `ORDER_INSUFFICIENT_ASSETS` | `BAD_REQUEST` | not enough cash or units to back the order |
| `ORDER_INVALID` | `BAD_REQUEST` | price off tick, outside bounds, bad units |
| `ORDER_ALREADY_CANCELLED` | `BAD_REQUEST` | CANCEL against an order already gone |
| `ORDER_NOT_ALLOWED` | `BAD_REQUEST` | trading not permitted in this market for this user |
| `SESSION_CLOSED` | `BAD_REQUEST` | the session is not open for trading |
| `ALLOTMENT_INVALID` | `BAD_REQUEST` | allocation names an unknown person, marketplace or asset |

`SERVER_ERROR` is internal and should never reach a client; if you see one, the
`shortDigest` is the fastest route to a diagnosis.

## API root

`GET /api` (authenticated) returns a HAL document whose `_links` are the
entry points the SDKs resolve everything else from. Templated links are
truncated at the first `{`:

| Link | Href |
|------|------|
| `accounts` | `/api/accounts` |
| `users` | `/api/users{?page,size,sort*}` |
| `marketplaces` | `/api/marketplaces{?page,size,sort*}` |
| `orders` | `/api/orders{?page,size,sort*}` |
| `usersJson` | `/api/users-json` |
| `marketplacesJson` | `/api/marketplaces-json` |
| `sessionsJson` | `/api/sessions-json` |
| `symbolOrdersJson` | `/api/orders-json/symbol-orders` |
| `symbolTradesJson` | `/api/orders-json/symbol-trades` |
| `sessionOrdersJson` | `/api/orders-json/by-sessions` |
| `profile` | `/api/profile` |

The `*Json` links are plain-JSON projections of the HAL resources — smaller
payloads, no `_embedded` unwrapping. The SDKs prefer them for list reads and
take `marketplaceId=` plus `symbol=` or `sessionIds=` as query parameters.

Prefer resolving links from the root over hard-coding paths: it is the one part
of the contract designed to survive a move.

## REST surface

Roles below are the *minimum* required. `ROLE_ADMIN` endpoints are platform
operations, listed for completeness rather than for client use.

### Marketplaces and markets

| Method & path | Role | Notes |
|---------------|------|-------|
| `GET /api/marketplaces` | user | marketplaces visible to the caller |
| `GET /api/marketplaces/{id}` | user | one marketplace |
| `GET /api/marketplaces/{id}/markets` | user | markets (assets) in the marketplace |
| `GET /api/marketplaces/{id}/symbols` | user | symbols only |
| `POST /api/marketplaces/{id}/markets` | manager | create a market |
| `POST /api/v1/marketplaces` | manager | create a marketplace (V1; V0 `POST /api/marketplaces` is deprecated) |
| `PUT /api/v1/marketplaces/{id}` | manager | update marketplace configuration |
| `DELETE /api/marketplaces/{id}` | manager | delete a marketplace |
| `GET /api/marketplaces/{id}/definition` | manager | full market-design definition |

### Sessions

| Method & path | Role | Notes |
|---------------|------|-------|
| `GET /api/marketplaces/{id}/session` | user | current session (alias `…/currentSession`) |
| `GET /api/marketplaces/{id}/sessions` | manager | session history |
| `PATCH /api/marketplaces/{id}/open` | manager | open the session |
| `PATCH /api/marketplaces/{id}/pause` | manager | pause the session |
| `PATCH /api/marketplaces/{id}/close` | manager | close the session |
| `GET /api/v1/marketplaces/{id}/sessions` | manager | V1 session list |

Opening a **closed** session is what consumes a staged allocation — pausing and
re-opening does not.

### Orders and trades

| Method & path | Role | Notes |
|---------------|------|-------|
| `POST /api/orders` | user | submit a LIMIT or CANCEL order |
| `GET /api/orders/{id}` | user | one order |
| `GET /api/marketplaces/{id}/orders` | manager | orders in the current session |
| `GET /api/v1/marketplaces/{id}/orders/active` | user | resting-book snapshot + `x-fm-as-of-seq` |
| `GET /api/v1/marketplaces/{id}/orders/recent-trades?size=n` | user | recent trades snapshot + `x-fm-as-of-seq` |
| `GET /api/v1/marketplaces/{id}/orders/by-sessions?sessions=…` | manager | raw unfiltered lifecycle, for audit/replay |
| `DELETE /api/v1/marketplaces/{id}/orders/market/{marketId}/standing` | manager | cancel every standing order in one market; the session must be **PAUSED**. Returns the count cleared |

Submit a limit order:

```http
POST /api/orders
Authorization: Bearer <token>
Content-Type: application/json

{
  "marketplaceId": 2540,
  "marketId": 8801,
  "type": "LIMIT",
  "side": "BUY",
  "units": 1,
  "price": 950,
  "clientDescription": "my-bot"
}
```

Cancel by referencing the original order id in all three of `id`, `original`
and `supplier`:

```json
{
  "marketplaceId": 2540,
  "marketId": 8801,
  "type": "CANCEL",
  "id": 771002,
  "original": 771002,
  "supplier": 771002,
  "clientDescription": "my-bot"
}
```

**Prices are integer cents.** `950` is $9.50. Prices must land on the market's
tick and inside its bounds, or the submit fails with `ORDER_INVALID`.
`clientDescription` is free text that surfaces in manager exports and the
connections desk — set it to something you can grep for.

Reads default to the **current** run. `/api/marketplaces/{id}/orders`,
`…/holdings` and `…/holdings/downloads` take a `sessions` parameter to widen
that:

| `sessions=` | Selects |
|-------------|---------|
| *(omitted)* | the current session only |
| `all` | every session of the marketplace |
| `last` or `current` | the current session, explicitly |
| `41,42` | those session ids |

Forgetting `sessions=all` is the usual reason an export "loses" a previous
run's orders.

### Holdings and allocations

| Method & path | Role | Notes |
|---------------|------|-------|
| `GET /api/marketplaces/{id}/holding` | user | the caller's own holding (alias `…/currentHolding`) |
| `GET /api/marketplaces/{id}/holdings` | manager | every participant's holdings |
| `GET /api/marketplaces/{id}/holdings/downloads` | manager | holdings as CSV |
| `POST /api/marketplaces/{id}/holdings/uploads` | manager | upload a holdings CSV (`multipart/form-data`, field `file`) |
| `POST /api/marketplaces/{id}/allocations` | manager | stage an allocation |
| `GET /api/marketplaces/{id}/allocations/impact` | manager | preview an allocation's effect |
| `DELETE /api/marketplaces/{id}/allocations/{allocationId}` | manager | drop a staged allocation |
| `GET /api/v1/marketplaces/{id}/allotments` | manager | per-participant allotments |

An upload or allocation **stages** the next allocation. It lands when a
**closed** session is opened. CSV column formats are documented in the in-app
guides (`/documentation/HOLDINGS-CSV`, `/documentation/USERS-CSV`,
`/documentation/ORDERS-CSV`).

### Users and accounts

| Method & path | Role | Notes |
|---------------|------|-------|
| `GET /api/v1/users` | user | users in the account |
| `GET /api/v1/users/me` | user | the caller |
| `GET /api/v1/users/{id}` | user | one user |
| `POST /api/v1/users` | manager | create a user |
| `PATCH /api/v1/users/{id}` | manager | update a user |
| `POST /api/v1/users/uploads` | manager | bulk-create from CSV |
| `POST /api/v1/users/{id}/roles` | manager | grant a role |
| `DELETE /api/v1/users/{id}/roles/{role}` | manager | revoke a role |
| `GET /api/v1/users/{id}/delete-check` | manager | whether a user can be hard-deleted |
| `DELETE /api/v1/users/{id}` | manager | delete a user |
| `GET /api/accounts` | manager | accounts (admin sees all) |
| `POST /api/accounts` | — | sign up |
| `DELETE /api/accounts/me` | manager | delete own account |

The `/api/users` (V0) equivalents still respond but are deprecated; new clients
should use `/api/v1/users`.

### Connections and version

| Method & path | Role | Notes |
|---------------|------|-------|
| `GET /api/marketplaces/{id}/connections` | manager | live client connections (alias `…/agents`) |
| `GET /api/version` | — | server build version |

## Snapshots and the sequence contract

The V1 snapshot endpoints exist so a client can seed local state without
receiving a large bulk push over the WebSocket. Both return a response header:

```
x-fm-as-of-seq: 41207
```

That is the value of the marketplace's monotonic `ORDERS-UPDATE` counter at the
moment the snapshot was read. The reconciliation rule is:

> **Apply** a WebSocket delta whose `seq` is **greater than** the snapshot's
> `as-of-seq`. **Skip** one whose `seq` is less than or equal.

The recommended seeding order is: subscribe first (buffer incoming deltas), then
`GET …/orders/active`, then drain the buffer under the rule above. Subscribing
after the snapshot leaves a hole.

The counter is per-marketplace and advances once per logical broadcast, shared
across every subscriber — so two clients see the same `seq` for the same event.
The snapshot's own read is not locked against concurrent publishes, so a
narrow race window remains; treat a one-frame overlap as normal and re-snapshot
if your book detects a crossed state.

`recent-trades` accepts `size` (default 1000, hard ceiling 5000) and returns
both legs of each trade, **oldest first**. Which trades and what order they
arrive in are separate decisions: you get the newest `size` of them, handed
back in the order they happened, so appending the snapshot to a trade tape
leaves the newest trade at the end.

It answered newest-first up to and including fm-server 4.3.1, which made every
client that appended it build its tape backwards — including all three SDKs,
whose "most recent trade" was then the oldest one they had retained. `Tape`
sorts each batch by time regardless of what the server sends, so the tape is
right against a server on either side of that change.

## WebSocket (STOMP)

STOMP 1.2 over a raw WebSocket. No SockJS fallback.

```
wss://api.flexemarkets.com/api/events
```

The same bearer token authenticates the connection, by either route:

- **HTTP handshake header** — `Authorization: Bearer <token>` on the WebSocket
  upgrade request. What the Python, Java and TypeScript SDKs do.
- **STOMP `CONNECT` header** — `authorization: Bearer <token>` in the CONNECT
  frame. What browsers must do, since they cannot set headers on a WebSocket
  handshake.

Two further `CONNECT` headers are conventional and worth sending: an
`agent-description` identifying your client, and `marketplace-id`. Both surface
in the manager's connections desk.

Origins are allow-listed server-side, so browser clients must be served from a
registered origin; non-browser clients are unaffected.

### Destinations

Subscribe to all three when entering a marketplace:

| Destination | Carries |
|-------------|---------|
| `/user/queue/marketplaces/{id}` | everything addressed to *you* — the subscribe seed, `ORDERS-UPDATE`, `HOLDING-UPDATE`, `SESSION-LIST` |
| `/topic/marketplaces/{id}` | marketplace-wide broadcasts — `SESSION-UPDATE` |
| `/app/marketplaces/{id}` *or* `/app/v1/marketplaces/{id}` | the trigger that makes the server send the seed |

The `/app` subscription is what selects the **wire version**:

- `/app/marketplaces/{id}` — **V0**. The seed includes a bulk `ORDERS-UPDATE`
  snapshot of the whole book.
- `/app/v1/marketplaces/{id}` — **V1**. Same lifecycle messages, but the bulk
  `ORDERS-UPDATE` is empty; pull the book from
  `GET /api/v1/marketplaces/{id}/orders/active` instead. V1 exists because a
  busy marketplace's bulk snapshot could exceed the per-session outbound buffer
  and kill the connection. **Prefer V1 for anything that might see load.**

The `/user/queue` and `/topic` destinations are identical in both versions;
only the `/app` prefix flips.

Manager actions are sent, not subscribed:

| Send destination | Effect |
|------------------|--------|
| `/app/marketplaces/{id}/open` | open the session |
| `/app/marketplaces/{id}/pause` | pause the session |
| `/app/marketplaces/{id}/close` | close the session |
| `/app/marketplaces/{id}/sessions/current` | request a fresh `SESSION-LIST` |

### Message types

Every frame carries a `message-type` header naming the payload:

| `message-type` | Payload |
|----------------|---------|
| `VERSION` | wire version, currently `3` — sent first on subscribe |
| `SESSION-UPDATE` | the session and its state (`OPEN` / `PAUSED` / `CLOSED`) |
| `HOLDING-UPDATE` | the recipient's holding after a fill or allocation |
| `ORDERS-UPDATE` | an array of order events — the book delta |
| `SESSION-LIST` | the marketplace's sessions |
| `ERROR` | `{"error": "…"}`, e.g. the marketplace isn't available |

Additional headers on every frame: `system-current-time-millis`,
`local-datetime`, `instant-now`. `ORDERS-UPDATE` frames add `seq` (a string).

An `ORDERS-UPDATE` may legitimately carry an **empty** array: the server always
pushes, even when per-subscriber filtering drops every order, so that the `seq`
you receive never appears to skip. Treat empty as a no-op, not as a gap.

Self-crossing pairs are translated before delivery — the owner sees CANCEL
events rather than two raw LIMIT events that would render as fake trades.

### Transport limits

| Setting | Value |
|---------|-------|
| Heartbeat | server offers 30 s each way |
| Inbound message size limit | 500 KB |
| Per-session outbound buffer | 1 MB |
| Send time limit | 20 s |

Exceeding the outbound buffer terminates the session — which is precisely what
the V1 subscribe path is designed to avoid.

Negotiate a heartbeat **below** 30 s if anything between you and the server has
its own idle timeout: fm-ui uses 25 s each way so a platform idle timer can't
fire on a live connection.

### Reconnecting

On reconnect, re-subscribe and **re-seed**: a fresh `active` snapshot plus the
`as-of-seq` rule. Do not assume the local book survived the gap. A missing
`seq` in the delta stream means frames were dropped; the fix is the same
re-snapshot, not a replay request.

---

## See also

- [`FM-ROBOTS.md`](FM-ROBOTS.md) — the `fm-manager` CLI, robot agents and the plugin SPI
- [`../sdks/python/README.md`](../sdks/python/README.md) · [`../sdks/java/README.md`](../sdks/java/README.md) · [`../sdks/typescript/README.md`](../sdks/typescript/README.md) — per-language quickstarts
