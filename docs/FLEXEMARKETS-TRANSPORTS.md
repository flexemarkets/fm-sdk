# FM-FLEXEMARKETS-TRANSPORTS

Moved from fm-server/docs on 2026-09-25.

> **Where this stands now.** This doc describes fm-robots' fm-lib-net
> (`fm.net.*`), which was deleted (fm-robots `d5297ce1c`). The
> interface survives as the SDK's `fm.Flexemarkets`
> (`sdks/java/fm-sdk/src/main/java/fm/Flexemarkets.java`), implemented
> by `fm.internal.HttpFlexemarkets`. **`FlexemarketsSession` has no
> successor**: there is no session, mode or shared transport. Callers
> use the two static `Flexemarkets.connect(credential, endpoint, …)`
> overloads, one connection pool per instance; per-user clients come
> from redeeming a manager OTP and connecting with the token
> (fm-robots `applications/manager/.../OtpConnections.java`); and
> in-process hosts plug in through `fm.FlexemarketsProvider`
> (`ServiceLoader`) rather than a `Mode`. The SDK's open API questions,
> including how transport trouble is reported, are in
> [DESIGN-0.4.md](DESIGN-0.4.md) (§2, §4).

Refactor of `Flexemarkets` from a concrete REST+WS class into a stable
public interface backed by pluggable, private transports. One shape that
fits a single interactive operator, a multi-user bot sharing one HTTP
pool, an in-memory fake, and (later) full-duplex multi-user WS or FIX —
without callers noticing.

Status: **Phases 1–5 shipped. Phase 6 (future WS) deferred.** Substrate
for [ROBOT-TRADER](https://github.com/adhocmarkets/fm-robots/blob/main/libraries/trader/ROBOT-TRADER.md) §11.7 — the per-user `RestSubmitter` collapses into
`FlexemarketsSession.mode(REST_ONLY)` and never gets written.

- ✅ **Phase 1** — REST and WS transports extracted into `fm.net.transport`
  (commit `133d95628`).
- ✅ **Phase 2** — `Flexemarkets` promoted to a public interface;
  concrete impl moved to `fm.net.impl.FullDuplexFlexemarkets`
  (commit `a431cdde5`).
- ✅ **Phase 3** — `FlexemarketsSession` factory + `Builder` + `Mode`
  enum in place; static `Flexemarkets.connect(...)` overloads route
  through it (commit `3b946246b`). **Decision flipped from inner
  `Flexemarkets.Session` to sibling `FlexemarketsSession`** —
  documented in §3.3.
- ✅ **Phase 4** — `RestOnlyFlexemarkets` lives in `fm.net.impl`;
  `FlexemarketsSession.as(bearer, mpId)` and `asOtp(otp, mpId)` mint
  REST-only façades sharing one `RestTransport`.
- ✅ **Phase 5** — `fm-manager` `TraderCommand` (load and replay
  subcommands) uses `FlexemarketsSession.mode(REST_ONLY)` plus
  `asOtp(...)` per user.
- ⏳ **Phase 6** — `WS_ONLY` mode and demuxed-STOMP / FIX transports.
  `Mode.WS_ONLY` is declared but no impl yet; no caller asks for it.

---

## 1. Goals

- **`Flexemarkets` is one interface.** Studies, bots, fm-administration,
  and fm-manager all hold the same type. ✅
- **REST and WS are private transports.** Each is an internal SPI with a
  small, stable contract. Public callers never see them. ✅
- **One factory shape, several modes.** A `FlexemarketsSession`
  builder returns a working `Flexemarkets` configured for
  `REST_AND_WS` (default; today's behaviour), `REST_ONLY`
  (submission-heavy bots), `WS_ONLY` (read-only monitors; not yet
  implemented), or `FAKE` (tests; `FakeFlexemarkets` keeps its own
  builder for now). ✅ (mostly)
- **Resource sharing for multi-user.** A `Session` in `REST_ONLY`
  mode owns one `RestTransport` (one `WebClient`, one Netty pool) and
  produces N tiny per-user `Flexemarkets` façades that share it. ✅
- **Future-proof the WS hole.** When the server learns per-user
  subscription filtering on a single STOMP connection — or we switch
  to FIX — the change lands inside `WsTransport` without touching any
  caller.
- **Backward compatible at the source level.** Existing callers
  (`Flexemarkets.connect(credential, endpoint, "…")` in studies,
  fm-administration, fm-manager) compile and behave identically. ✅

---

## 2. Realised architecture

```
                       Flexemarkets                      ← public interface
                            ▲
   ┌────────────────────────┼────────────────────────┐
   │                        │                        │
FullDuplex              RestOnly                    Fake
(REST + WS)             (REST)                  (in-memory)
   │                        │
   └──── delegate ──────────┘
                            ▼
                       Transports                       ← private; fm.net.transport
              ┌──────────────────────────────┐
              │  RestTransport                │
              │     - one WebClient           │
              │     - one Netty pool          │
              │     - per-call bearer         │
              │  WsTransport                  │
              │     - one StompSession        │
              │     - listener thread         │
              │     - per-user demux (Phase 6)│
              └──────────────────────────────┘
                            ▲
                            │ owned by
                            │
                   FlexemarketsSession                 ← factory + lifecycle
                   .endpoint(…).mode(…).build()
                   .connect(credential)   → Flexemarkets    (REST_AND_WS, REST_ONLY)
                   .as(bearer, mpId)      → Flexemarkets    (REST_ONLY only)
                   .asOtp(otp, mpId)      → Flexemarkets    (REST_ONLY only)
                   .close()
```

### 2.1 What lives where

- **`fm.net.Flexemarkets`** — public interface (338 lines). 60-odd
  methods spanning identity, queries, mutations, session lifecycle,
  trading, listen, reconnect, plus the static `connect(...)`
  overloads that delegate to a one-shot session.
- **`fm.net.FlexemarketsSession`** — public interface. `Builder`,
  `Mode` enum, `connect`, `as`, `asOtp`, `close`. Backed by
  `fm.net.impl.DefaultFlexemarketsSession`.
- **`fm.net.impl.FullDuplexFlexemarkets`** — the original concrete
  class, still REST + WS. Built by `Session.connect(...)` in
  `REST_AND_WS` mode. ~760 lines, mostly the per-method wire shapes.
- **`fm.net.impl.RestOnlyFlexemarkets`** — REST façade for one
  bearer; `listen` and `reconnect` throw. Built by `Session.connect`
  / `Session.as` / `Session.asOtp` in `REST_ONLY` mode. ~430 lines.
  Multiple instances share one `RestTransport`.
- **`fm.net.transport`** — `RestTransport` (interface) +
  `WebClientRestTransport` (impl), `WsTransport` (interface) +
  `StompWsTransport` (impl), `WebClientFilters`, `EventParser`. All
  package-private to the impl layer.
- **`fm.test.FakeFlexemarkets`** — `implements Flexemarkets`
  directly, lives in fm-robots `libraries/test`. Kept independent of
  `FlexemarketsSession` for now (its builder is the testing API; see
  [STUDY-TESTING.md](https://github.com/adhocmarkets/fm-robots/blob/main/applications/studies/STUDY-TESTING.md)).

### 2.2 Source compatibility

The static `Flexemarkets.connect(...)` overloads still exist (4 of
them) and delegate through `FlexemarketsSession.endpoint(endpoint)
.clientDescription(...).build().connect(credential)`. Every existing
caller (`Flexemarkets.connect(credential, endpoint, desc)` in studies,
fm-administration, fm-manager) compiles and behaves identically — the
returned `Flexemarkets` is now an interface but the call site doesn't
know.

The lifecycle is also unchanged: closing the returned `Flexemarkets`
closes the one-shot `Session` underneath.

---

## 3. Public surface

### 3.1 `Flexemarkets`

```java
public interface Flexemarkets extends Closeable {
    // identity
    long   endpointMarketplaceId();
    String accountName();
    long   accountId();
    Account account();
    String endpointUrl();
    Person user();
    long   userId();
    Token  token();
    Map<String, Histogram> metrics();
    boolean isAdmin();
    boolean isManager();
    boolean hasRole(String role);

    // queries / mutations / lifecycle / trading — ~60 methods,
    // signatures unchanged from the pre-refactor concrete class
    Mono<List<Market>> markets(long marketplaceId);
    Mono<List<Person>> users();
    Mono<List<Holding>> holdings(long marketplaceId);
    Mono<Order> submitLimit(long marketplaceId, long marketId, String side, long units, long price);
    void listen(long marketplaceId, BlockingQueue<Object> queue);
    void reconnect() throws InterruptedException, ExecutionException;
    @Override void close();

    // static factories (delegate to Session)
    static Flexemarkets connect(String credential, String endpoint, String clientDescription) throws IOException;
    // … 3 more overloads …
}
```

### 3.2 Internal SPI: transports

```java
// package fm.net.transport
interface RestTransport extends AutoCloseable {
    <T> Mono<T> get   (String url, String bearer, ParameterizedTypeReference<T> type);
    <T> Mono<T> post  (String url, String bearer, Object body, ParameterizedTypeReference<T> type);
    <T> Mono<T> patch (String url, String bearer, Object body, ParameterizedTypeReference<T> type);
    Mono<Void>  delete(String url, String bearer);

    /** Build a per-call transport that owns its WebClient. */
    static RestTransport ofPerCall(String endpoint, boolean capture, String impersonate);
    /** Build a shared transport reused across many bearers (REST_ONLY). */
    static RestTransport ofShared(String endpoint, boolean capture, String impersonate);
}

interface WsTransport extends AutoCloseable {
    Subscription subscribe(String bearer, long marketplaceId, BlockingQueue<Object> sink);
    void reconnect();
}
```

- `RestTransport` is **stateless w.r.t. user**. Bearer is per-call.
  `ofPerCall` for the one-shot `connect(...)` path; `ofShared` for
  `Session.as(...)` minting many façades.
- `WsTransport` is **stateful** (owns the STOMP session and listener
  thread). One STOMP per `Subscription` today; future demuxed impl
  shares a single STOMP across many users (Phase 6).

### 3.3 `FlexemarketsSession`

```java
public interface FlexemarketsSession extends Closeable {

    static Builder endpoint(String endpoint);

    Mode mode();

    Flexemarkets connect(String credential) throws IOException;
    Flexemarkets as(String bearerToken, long marketplaceId);
    Flexemarkets asOtp(String otp, long marketplaceId);

    @Override void close();

    interface Builder {
        Builder mode(Mode mode);
        Builder clientDescription(String description);
        Builder impersonate(String accountName);   // X-FM-Account
        Builder capture(boolean capture);          // packet capture
        FlexemarketsSession build();
    }

    enum Mode { REST_AND_WS, REST_ONLY, WS_ONLY, FAKE }
}
```

- One `Session` ⇒ one `RestTransport` (built lazily on first
  `as`/`asOtp`/REST_ONLY `connect`), optionally one `WsTransport`
  (REST_AND_WS), shared by every `Flexemarkets` it mints.
- `connect(credential)` POSTs `/api/tokens`, then builds one
  `Flexemarkets` keyed to that bearer. Identity getters
  (`userId()`, `accountName()`, …) come from the response.
- `as(bearer, mpId)` mints from a token already obtained elsewhere
  (OTP redemption, refresh, externally-issued JWT). REST_ONLY only —
  REST_AND_WS doesn't share transports across users.
- `asOtp(otp, mpId)` redeems a manager-issued OTP via
  `GET /api/otp?otp=…`, then calls `as(...)` with the resulting
  bearer. Used by `fm-manager`'s `TraderCommand` to provision
  per-user clients without paying the `/api/tokens` rate limit.
- `close()` cascades into every minted façade and closes the shared
  transport.

**Sibling, not inner.** `FlexemarketsSession` is a sibling type
rather than a `Flexemarkets.Session` inner — the inner form was
tried first and shadowed `fm.net.Types.Session` inside the
`Flexemarkets` interface body, forcing fully-qualified references
at every method that returns or accepts a session record.

### 3.4 Modes

| Mode | RestTransport | WsTransport | `connect` | `as`/`asOtp` | `listen()` | Callers |
|------|---------------|-------------|-----------|--------------|------------|---------|
| `REST_AND_WS` (default) | per-call | yes | works | throws | works | every study, fm-administration, fm-manager (admin paths) |
| `REST_ONLY` | shared | — | works | works | throws | `fm-manager trader load`/`replay`; future trader bot |
| `WS_ONLY` | — | yes | throws | throws | works | (none — Phase 6) |
| `FAKE` | mocked | mocked | throws | throws | scripted | tests use `FakeFlexemarkets` directly, not via `Session` |

A method that needs an absent transport throws clearly. Misconfigured
modes fail fast at the call site.

---

## 4. Design notes

### 4.1 Identity getters live on `Flexemarkets`

`accountName()`, `accountId()`, `endpointUrl()`,
`endpointMarketplaceId()`, `userId()`, `user()`, `account()`,
`isAdmin()`, `isManager()`, `hasRole(String)` — read repeatedly in
tight loops (e.g., `holding.ownerId() == flexemarkets.userId()`).

Each façade caches its identity at mint time (from the `/api/tokens`
response or the `Token` claims). Façades minted via `Session.as`
have no sign-in record — identity getters return null/0/empty;
callers that need identity should use `REST_AND_WS` + `connect(...)`
instead. `RestOnlyFlexemarkets`'s class-level Javadoc spells this
out.

### 4.2 Manager/admin surface stays on `Flexemarkets`

Beyond the trader subset, `Flexemarkets` exposes ~40 methods used
only by fm-administration and fm-manager: `signup`, `createUser`,
`createMarketplace`, `deleteAccount`, etc. They live on the same
`Flexemarkets` interface for V1.

Splitting (`Flexemarkets` for trader ops, `FlexemarketsAdmin extends
Flexemarkets` for management) is conceptually clean but forces every
caller in fm-administration / fm-manager to pick the right type at
construction. Defer until either (a) the surface grows past
comfortable, or (b) we want to expose a sandboxed `Flexemarkets` to
user-supplied robots that can't accidentally call admin methods.

`REST_ONLY` mode supports all non-`listen` methods — admin endpoints
included. `WS_ONLY` would support only `listen`/`close`/`reconnect`.
`FAKE` supports whatever its tests need; `FakeFlexemarkets`
documents `UnsupportedOperationException("not scripted: …")` for
unimplemented paths.

### 4.3 `Mono<T>` return types stay

Existing callers' `.block()` / `.subscribe()` keep working. Reactive
types are part of the contract; changing them is a separate (much
bigger) project.

---

## 5. WS evolution without client churn

Today's `WsTransport` is "one STOMP session per bearer." Tomorrow's
options:

1. **Demuxed STOMP.** One STOMP session per `Session`; the server
   filters subscriptions by bearer at the message level. Client side:
   `WsTransport.subscribe(bearer, mpId, sink)` registers the sink
   under (bearer, mpId) and the transport demuxes incoming frames to
   the right sink. **No public API change.**
2. **FIX session.** Same `WsTransport` interface, different impl.
   `Mode.REST_AND_FIX` is just another factory branch. Callers using
   `listen(...)` keep working.
3. **WebTransport / HTTP/3.** Same story.

In each case the Session factory picks the impl; transports are an
SPI; public callers see one interface that hasn't moved.

---

## 6. Testing

The refactor was structured so the existing test suite is the
oracle — every phase landed with the build green:

- **Phases 1–2** — every existing test passed unchanged. Extracted
  transports preserved wire behaviour; the interface promotion was
  mechanical.
- **Phase 3** — `FlexemarketsSessionTest` covers Builder validation,
  one-shot lifecycle, idempotent close, and mode-vs-method matrix
  (e.g., `as` on REST_AND_WS throws; `connect` on WS_ONLY throws).
- **Phase 4** — `RestOnlyFlexemarkets` is exercised end-to-end via
  `RestOnlySessionIntegrationTest` against a live local server
  (gated). Negative path: `listen()` and `reconnect()` throw with a
  clear message.
- **Phase 5** — fm-manager's load/replay subcommands run against a
  live local server with multi-user OTP-minted REST-only clients;
  one Netty pool, N façades.

For the transport SPI itself (`RestTransport`, `WsTransport`),
behaviour is verified through the impls — `WebClientRestTransport`
exercised in every Layer-2/3/4 test; `StompWsTransport` exercised
via `WebSocketsTest` and the live integration tests.

---

## 7. How `RestSubmitter` (ROBOT-TRADER §11.7) collapsed

ROBOT-TRADER §11.7 originally proposed a new library class
`fm.trader.RestSubmitter` that owns one `WebClient` and produces tiny
per-user submitters. It never got written:

- The shared `WebClient` lives in `RestTransport`, owned by
  `FlexemarketsSession`.
- The per-user lightweight façade is just a `Flexemarkets` minted by
  `session.as(bearer, mpId)` or `session.asOtp(otp, mpId)`.
- The bot already wants the full `Flexemarkets` interface (it does
  `holdings()`, `users()`, etc.) — there's no second small interface.

ROBOT-TRADER §11.7 now reflects this. With `FlexemarketsSession`
gone (see the note at the top), `fm-manager`'s `TraderCommand` mints
one SDK `Flexemarkets` per user via `OtpConnections`; `RestSubmitter`
still never got written.

---

## 8. Open items

1. **`WS_ONLY` mode.** Declared in the enum, no impl yet. No caller
   asks for it. Ship when the first read-only-monitor use case
   appears.
2. **Demuxed STOMP / FIX.** Phase 6. Lands inside `WsTransport`
   impls; no caller changes. Each option deserves its own design doc.
3. **Per-user metrics under shared transport.** Today's `metrics()`
   returns per-instance `Histogram`s; under `Session.as` mode the
   histograms are session-level. Per-bearer breakdowns can be added
   as labels (token-id tag) if a real use case appears.
4. **`reconnect()` semantics under demuxed WS.** Today `reconnect()`
   re-establishes one STOMP session per `Flexemarkets`. Under demuxed
   WS, one reconnect would affect every façade sharing the transport.
   Document in the Phase 6 design.
5. **Splitting trader vs admin interfaces.** Deferred — see §4.2.
6. **Replacing `Mono<T>`.** A separate, much bigger project.

---

## 9. Out of scope

- WS roadmap details (demuxed STOMP, FIX, etc.) — Phase 6, separate
  doc per option.
- Auth flow changes — OTP issuance, JWT refresh, and token bundle
  handling stay in `TokenHandler` / OTP-bundle code; this refactor
  consumes whatever bearers it gets.
