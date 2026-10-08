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
| `POST /api/tokens/refresh` | re-issue a token for the current session; `401` once the user or account is gone (`GET` also answers, for older clients) |

Already holding a bearer token? `POST /api/tokens` with the token in the
`Authorization` header and an empty password exchanges it for the full
`TokenResult` above — this is what the SDKs do when `~/.fm/credential` contains
a `token=` line instead of a password.

> Resources reject HTTP Basic directly; authenticate
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
  "path": "/api/v1/marketplaces/99999999",
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

## REST surface

Everything below is under `/api/v1`, in the model-REST forms of fm-server's
`docs/API-V1.md` §4. Paths in the tables are relative to
`/api/v1/marketplaces/{id}` unless they start with `/`. Roles are the
*minimum* required; `ROLE_ADMIN` routes are platform operations, listed for
completeness.

The fm-sdk calls only these. Older spellings — the HAL root at `GET /api`
and its links, the V0 routes under `/api/...`, and the V1 segment routes
these replaced (`orders/active`, `sessions/open`, `holdings/downloads`, ...)
— still answer, for the FM-3 and FM-4 clients built on them, but a new client
should not start there. A superseded V1 route says so in a `Deprecation`
header, with a `Link: <...>; rel="successor-version"` naming its replacement
where the request lets the server spell it.

### Conventions

The same rules hold on every route, so a route you have not read yet works the
way the last one did:

- **Containment.** What belongs to a marketplace is under
  `/marketplaces/{id}/...`; what belongs to one participant in it is under
  `.../participants/{userId}/...`.
- **`me` and `current`.** `me` goes wherever a user id does — and is the
  only one a participant may name; another participant's id is a manager's
  (`participants/me/holding`, `/users/me`, `/accounts/me`), `current`
  wherever a session id does (`sessions/current`).
- **Verbs.** `GET` reads, `POST` to a collection creates, `PUT` creates or
  replaces at a key you chose, `PATCH` changes the fields you send, `DELETE`
  removes — for an order, cancels.
- **A lifecycle is a `state`.** Opening a session, stopping a robot,
  cancelling a transfer: `PATCH` the resource with `{"state": "..."}`.
  Asking for the state it already has is a quiet success; asking for one it
  cannot reach from where it is is refused.
- **Filters are query parameters**, never path segments, and one thing has
  one name everywhere: a market is `?market={marketId}` or `?symbol=`; a
  count is `?limit=`; runs are `?sessions=`; one item is singular, a list
  plural. Enumerated values are upper case (`?state=ACTIVE`). A parameter a
  route does not take is refused with 400, not ignored.
- **Format by header.** CSV is `Accept: text/csv` to read and
  `Content-Type: text/csv` to send; there are no `/uploads` or `/downloads`.
- **`?dryRun=true`** on a request answers what it would do and does nothing.
- **Status.** A create answers 201 where it is new to V1 (200 where it was
  not, unchanged); a `DELETE` answers 204, except where the answer carries
  something — cancelling an order answers the CANCEL the exchange recorded.

### Marketplaces and markets

| Method & path | Role | Notes |
|---|---|---|
| `GET /marketplaces` | user | the marketplaces the caller may see |
| `POST /marketplaces` | manager | create one from a definition |
| `GET` · `PUT` · `DELETE /marketplaces/{id}` | user · manager · manager | one marketplace |
| `GET markets` | user | its markets; a market's `symbol` is on it |
| `POST markets` | manager | add a market |
| `DELETE markets/{marketId}` | manager | remove a market no order has used |
| `GET participants` | user | the participants the caller may name in a private order: `{name}`, and `userId` for a manager |

### Sessions

| Method & path | Role | Notes |
|---|---|---|
| `GET sessions` | manager | every session, oldest first |
| `GET sessions/current` | user | the live session |
| `PATCH sessions/current` | manager | `{"state": "OPEN" \| "PAUSED" \| "CLOSED"}`; 200 with the session as it now stands, 400 `SESSION_INVALID` for a state it cannot reach |

Opening a **closed** session is what consumes a staged allocation — pausing and
re-opening does not. Resuming a paused session writes a new row, so its `id`
can change; `original` names the run.

### Orders and trades

| Method & path | Role | Notes |
|---|---|---|
| `POST orders` | user | submit a LIMIT (or a CANCEL, the older way to cancel) |
| `DELETE orders/{orderId}` | user | cancel what is left of an order; 200 with the CANCEL order, 404 `ORDER_NOT_IN_MARKETPLACE` |
| `GET orders?state=ACTIVE[&market=\|&symbol=]` | user | the resting book of the current session, with `x-fm-as-of-seq` |
| `GET orders?state=TRADED[&market=\|&symbol=][&limit=][&before=]` | user | the newest `limit` trade legs (default 1000, at most 5000), oldest first, with `x-fm-as-of-seq`; `before` pages back |
| `GET orders[?sessions=]` | manager | every LIMIT and CANCEL row of the runs, unfiltered: the audit and replay view |
| `GET orders?cancelled=false[&sessions=]` | manager | the same less cancelled orders, their CANCELs, and self-crosses |
| `DELETE orders?market=\|symbol=&state=ACTIVE` | manager | withdraw every standing order in one market of a **PAUSED** session; answers the count |
| `GET trades[?market=\|&symbol=][&limit=][&before=]` | user | the newest `limit` trades (default 500, at most 2500), each with both orders, oldest first; `before` pages back |

**The whole tape, a page at a time.** Both trade reads take
`?before={key}`: the newest `limit` trades before that key. A trade's key is
the larger id of its two legs. A full page answers
`Link: <...?before={key}>; rel="next"`, naming the page before it; a short
page is the session's first trades. Seed with the newest trades, then follow
`next` at your own pace. The answer is never cut short by the server's cache
(fm-server 4.6.2, #1029).

Submit a limit order:

```http
POST /api/v1/marketplaces/2540/orders
Authorization: Bearer <token>
Content-Type: application/json

{
  "marketId": 8801,
  "type": "LIMIT",
  "side": "BUY",
  "units": 1,
  "price": 950,
  "clientDescription": "my-bot"
}
```

The marketplace is the path's; a body that names a different one is refused.
Cancel with `DELETE /api/v1/marketplaces/2540/orders/771002`, naming any order
of the lineage — a split's remainder cancels the order it came from.

**Prices are integer cents.** `950` is $9.50. Prices must land on the market's
tick and inside its bounds, or the submit fails with `ORDER_INVALID`.
`clientDescription` is free text that surfaces in manager exports and the
connections desk — set it to something you can grep for.

Reads default to the **current** run. `orders`, `holdings` and
`connections` take `sessions` to widen that:

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
|---|---|---|
| `GET participants/{userId}/holding` | user | one participant's holding; `me` for the caller's own |
| `GET holdings[?sessions=]` | user | every holding the caller may see: all of them to a manager, their own to a participant |
| `GET holdings[?sessions=]`, `Accept: text/csv` | manager | the holdings export, as FM-3 wrote it |
| `POST allocations` | manager | stage an allocation from allotments (JSON) |
| `POST allocations`, `Content-Type: text/csv` | manager | stage one from a holdings CSV, the file as the body |
| `GET allocations/{allocationId}/allotments` | manager | an allocation's allotments |
| `GET allocations/impact` | manager | what re-allocating would reset |
| `DELETE allocations` | manager | drop the staged allocation |
| `DELETE participants/{userId}/allotments` | manager | remove one participant's allotments |
| `GET state` · `PUT state` (`text/csv`) | manager | the staged per-participant private state |

An upload or allocation **stages** the next allocation. It lands when a
**closed** session is opened. CSV column formats are documented in the in-app
guides (`/documentation/HOLDINGS-CSV`, `/documentation/USERS-CSV`,
`/documentation/ORDERS-CSV`).

### Widgets, panels and series

Content a robot pushes into a participant's view, and the price series the
server samples for a return chart. Both are shown by panels a manager places
in the marketplace's view (`fm.view`); a payload for a key no panel names is
stored and not shown, so a robot can start before the view is finished.

| Method & path | Role | Notes |
|---|---|---|
| `POST widgets` | manager | push one widget, or a JSON array of them (one request for sixty traders, not sixty) |
| `PUT widgets/{key}` | manager | the marketplace's widget at that key |
| `PUT participants/{userId}/widgets/{key}` | manager | one participant's |
| `DELETE widgets/{key}` · `DELETE participants/{userId}/widgets/{key}` | manager | take a key down; 204, or an empty 404 when nothing was there |
| `GET widgets` | user | the caller's snapshot: the marketplace's widgets and their own |
| `GET widgets?participant=all` | manager | everything pushed, both scopes |
| `GET participants/{userId}/widgets` | user | what one participant sees; `me` for the caller |
| `GET widget-limits` | user | the content caps and push rates |
| `GET panels` · `GET participants/{userId}/panels` | user | the panels' values: every participant's to a manager, the caller's own otherwise |
| `PUT config/fm.view?dryRun=true&participant={userId}` | manager | what a draft view would show that participant, saving nothing |
| `GET series` | user | the sampled price series of the current session, per market and period |

A widget is:

```jsonc
{ "target": { "scope": "USER", "userId": 8123 },   // or {"scope":"MARKETPLACE"}, the default
  "key": "role",                                    // [A-Za-z0-9_-]{1,64}; the panel's option
  "title": "Your role",                             // optional
  "emphasis": "strong",                             // normal | strong | warn
  "ttlSeconds": 60,                                 // optional: blank the panel this long after the push
  "content": { "kind": "text", "lines": ["You are a SELLER"] } }
```

`content` is one of four closed kinds; there is no markup kind and no
formula language:

| `kind` | Shape | Caps |
|--------|-------|------|
| `text` | `{"lines": [string]}` | 50 lines of 500 |
| `kv` | `{"items": [{"label", "value", "format"?}]}` | 50 items |
| `table` | `{"columns": [{"label", "align"?, "format"?}], "rows": [[cell]]}` | 8 columns × 50 rows, every row as wide as the columns |
| `log` | `{"lines": [string], "cap"?}` | appends to the log already there; keeps the newest `cap` (default 100, at most 500) |

Cells are strings, numbers or booleans. `format` is an enum — `plain`,
`price` (cents, shown in dollars), `units`, `percent`, `signed-percent` —
not a format string; the renderer owns presentation. `align` is `left`,
`right` or `center`. The whole `content` is at most 8 KB.

A push to the same `(scope, user, key)` **replaces** the last one — that is
how stale content is cleared. A participant's own widget outranks the
marketplace's of the same key for them. Widgets are cleared when the session
closes and kept across a pause. Rates: about 200 pushes a second per
marketplace and 2 a second per targeted participant, with a small burst; a
batch over either is refused whole with `429 WIDGET_RATE_LIMITED`, and a
malformed one with `400 WIDGET_INVALID` naming the field.

From the SDKs, on a manager's connection — the push, the take-down and the
read-back of everything; the participant snapshot, `widget-limits` and
`series` have no SDK call:

| | Push | Take down | Everything pushed |
|---|---|---|---|
| Java | `List<Widget> pushWidgets(long marketplaceId, List<WidgetPush> widgets)` | `boolean removeWidget(long marketplaceId, String key)`, and `(…, long userId)` | `List<Widget> allWidgets(long marketplaceId)` |
| Python | `push_widgets(marketplace_id, widgets) -> list[Widget]` | `remove_widget(marketplace_id, key, user_id=None) -> bool` | `all_widgets(marketplace_id) -> list[Widget]` |
| TypeScript | `pushWidgets(marketplaceId, widgets): Promise<Widget[]>` | `removeWidget(marketplaceId, key, userId?): Promise<boolean>` | `allWidgets(marketplaceId): Promise<Widget[]>` |

```python
from fm import WidgetPush, WidgetTarget

fm.push_widgets(marketplace_id, [
    WidgetPush(key="role", title="Your role", emphasis="strong",
               target=WidgetTarget("USER", user_id=8123),
               content={"kind": "text", "lines": ["You are a SELLER"]}),
])
fm.remove_widget(marketplace_id, "role", user_id=8123)   # True; False if nothing was there
```

`pushWidgets` always sends one array, however many widgets, since the rate
limit counts requests. `WidgetPush` and `WidgetTarget` are the body above,
field for field; `content` is passed through as a map and checked by the
server alone. `Widget` is what the server stored: the push's fields flattened
(`scope`, `userId`), plus `id`, `marketplaceId` and the `createdDate` /
`lastModifiedDate` of the first and latest push — `ttlSeconds` counts from the
latter. The server stores an absent `emphasis` as `normal`.

`removeWidget` answers `true` for a 204 and `false` for the empty 404 that
means nothing was there; a 404 with a failure body (`MARKETPLACE_NOT_FOUND`)
raises like any other failure. `400 WIDGET_INVALID` raises the SDK's
invalid-argument error. `429 WIDGET_RATE_LIMITED` has no type of its own: it
raises the generic HTTP error (`HttpException` / `HttpError`) with
`statusCode` 429 — back off and push the batch again.

### Robots

| Method & path | Role | Notes |
|---|---|---|
| `POST participants/me/robot-launches` | user | `{robotId, ...}` → 201 with `Location` |
| `GET robot-launches[?state=LIVE]` · `GET robot-launches/{launchId}` | user | launches, or one |
| `PATCH robot-launches/{launchId}` | manager | `{"state": "STOPPED"}` |

### Studies

A study the platform can set up for a manager from its page — its own
logic, vendored into the server as the robots are, plans the marketplaces
and rotations; the server creates and stages them. What `fm-<study>
marketplace` and `allotments` do from a terminal, from a form.

| Method & path | Role | Notes |
|---|---|---|
| `GET /studies` · `GET /studies/{id}` | user | the studies this server can set up: id, name, description, parameters (the manifest's `ParameterSpec`, with defaults) |
| `POST /studies/{id}/runs` | manager | `{parameters, participantIds}` → creates the study's marketplaces (markets, `fm.view`, an initial session), stages each one's first rotation, and answers 201 with the run |
| `POST /studies/{id}/runs?dryRun=true` | manager | the same body → the plan it would make: marketplaces, rotations, roster — nothing created |
| `GET /studies/runs[?marketplace={marketplaceId}]` | manager | the account's runs, newest first |
| `GET /studies/runs/{runId}` | manager | one run: parameters, the whole plan, `marketplaceIds` by the study's key, `progress` |
| `PATCH /studies/runs/{runId}/rotations/{n}` | manager | `{"state": "STAGED"}` stages rotation `n` (1-based, across the run); `{"state": "SETTLED"}` settles it |
| `GET /studies/runs/{runId}/roster` | manager | the roster the study hands out, as CSV; empty when it has none |

`parameters` is a map of parameter name to its value as text; a missing name
takes the default. A parameter the study refuses answers `400
STUDY_SETUP_INVALID` naming it, and creates nothing. Every `participantId`
must be one of the caller's account's people. Smith 62 plans two
marketplaces, `private` and `public`, and runs its four rotations private,
public, public, private.

### Users and accounts

| Method & path | Role | Notes |
|---|---|---|
| `GET /users` · `GET /users/me` · `GET /users/{id}` | user | users in the account, the caller, one user |
| `POST /users` | manager | create a user; with `Content-Type: text/csv`, create many |
| `PATCH /users/{id}` | manager | change the fields sent |
| `PUT` · `DELETE /users/{id}/roles/{role}` | manager | grant · revoke a role |
| `PUT` · `DELETE /users/{id}/archive` | manager | archive · unarchive |
| `DELETE /users/{id}[?dryRun=true]` | manager | delete a user, or ask whether it can be |
| `PUT /users/me/password` | user | change the caller's password |
| `POST /accounts` | — | sign up |
| `GET /accounts[?approval=PENDING\|APPROVED\|SUSPENDED]` | manager | accounts (an administrator sees all) |
| `GET` · `DELETE /accounts/{id}` | admin | one account |
| `POST /accounts/{id}/approvals` | admin | `{approve, description}` → 201 |
| `PATCH` · `DELETE /accounts/me` | manager | change · close the caller's account |

### Configuration and notifications

| Method & path | Role | Notes |
|---|---|---|
| `GET config` · `PUT` · `DELETE config/{key}` | user · manager | a marketplace's configuration, raw strings |
| `GET /accounts/me/config` · `PUT` · `DELETE /accounts/me/config/{key}` | user · manager | the account's |
| `GET /users/me/notifications` · `GET notifications` | user | what is showing to the caller, and in a marketplace |
| `PUT /notifications/{id}/dismissals/me` | user | dismiss one, for the caller |

### Connections and version

| Method & path | Role | Notes |
|---|---|---|
| `GET connections[?open=true][&sessions=]` | manager | every client connection to the marketplace, terminated included, unless filtered |
| `GET /connections` | manager | open connections across the account |
| `GET /api/version` | — | server build version (unversioned, as the bootstrap routes are) |

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
`GET …/orders?state=ACTIVE`, then drain the buffer under the rule above. Subscribing
after the snapshot leaves a hole.

The counter is per-marketplace and advances once per logical broadcast, shared
across every subscriber — so two clients see the same `seq` for the same event.
The snapshot's own read is not locked against concurrent publishes, so a
narrow race window remains; treat a one-frame overlap as normal and re-snapshot
if your book detects a crossed state.

`orders?state=TRADED` accepts `limit` (default 1000, hard ceiling 5000) and returns
both legs of each trade, **oldest first**. Which trades and what order they
arrive in are separate decisions: you get the newest `limit` of them, handed
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
  `GET /api/v1/marketplaces/{id}/orders?state=ACTIVE` instead. V1 exists because a
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
| `PANELS-UPDATE` | the recipient's evaluated `score` panels, values only (user queue) |
| `SERIES-UPDATE` | a market's sampled price series on one period grid: a snapshot in the subscribe burst, then one point per boundary (topic) |
| `WIDGETS-UPDATE` | pushed widgets: a snapshot of what the recipient may see in the subscribe burst, then upserts and removals (topic for the marketplace's, user queue for their own) |
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
