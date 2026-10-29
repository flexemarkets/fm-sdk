# Submitting at the server's rate (fm-sdk#7)

fm-sdk should place orders faster than FMClientLibrary's existing aiohttp
transport, and be limited by fm-server rather than by itself. Measured on one
laptop on 2026-10-28, the server is nowhere near the limit: it answers an
order in 1-3 ms and keeps up with 650 a second on one socket. The clients
lose the time, mainly by waiting for each answer before sending the next.

The plan, in order of what it buys:

1. **fm-sdk: orders over the socket, several in flight** (W4 in fm-server's
   `docs/SOCKET-ORDERS.md`). The server already keeps one socket's orders in
   the order they were sent (W2, 2026-10-27). Measured: 647 orders a second,
   none out of order, against 137 for FMClient's existing transport.
2. **FMClientLibrary: send queued orders when they are queued**, not on a
   250 ms poll, and hand them to the socket window without waiting for each
   answer.
3. **fm-server: one lookup per submission id, not two.** Socket orders run
   one at a time per socket, so the server's time per order is the socket's
   rate. Measured: no saving, not taken (section 3).
4. **fm-sdk: a leaner REST path for one order at a time**: an asyncio client,
   and less work per request.
5. **fm-server and fm-sdk: several REST orders at once, in order**, as one
   request (section 5). REST is otherwise held to one order per round trip.

## What was measured

All on `localhost` against fm-server 4.17.0 + afb076413 (now 4.17.1), one-unit
resting sells, scripts in the FMClient comparison bench
(`~/.fm/compare-20261028/bench/`: `floor_probe.py`, `wait_probe.py`,
`submit_probe.py`). A laptop that idles between requests wakes slowly, so a
single order varied from 1.1 to 2.3 ms between runs; each comparison below
is within one run, with the modes rotated or alternated.

| Client | Orders a second | Per order (ms, p50) | Out of order |
| --- | --- | --- | --- |
| Hand-written HTTP, one at a time | 380-790 | 1.1-2.3 | n/a |
| Hand-written HTTP, 2 / 4 / 8 in flight | 1,400 / 1,380 / 2,700 | | 20 / 26 / 49 of 150 |
| One socket, 150 orders back to back | 647 | | 0 of 150 |
| One socket, one at a time (wait for each `ORDERS-UPDATE`) | 121 | 8.3 | n/a |
| aiohttp, one session (FMClient's existing transport) | 191 | 5.3 | n/a |
| httpx, as fm-sdk configures it | 128-254 | 3.7-7.8 | n/a |
| `Flexemarkets.submit_limit` | 124 | 8.1 | n/a |
| The same through a 2-thread executor (FMClient's sdk transport) | 112 | 9.0 | n/a |
| FMClient burst, existing transport, as shipped | 137 | | |
| FMClient burst, fm-sdk transport, as shipped | 88 | | |

What the server costs, from the hand-written client in alternating blocks:
a `submissionId` adds 1.1 ms (1.72 → 2.80 ms, higher in 9 of 10 blocks).
The v0 and v1 order paths cost the same. A request whose headers and body
go out in two writes with Nagle on waits 46 ms for a delayed ACK; httpx and
aiohttp both avoid that, so it is not today's problem, but any hand-rolled
transport must send one write or set `TCP_NODELAY`.

Several orders in flight over REST reach the server's real capacity (2,700 a
second), but they reach the book out of order, and price-time priority makes
order matter. REST stays one at a time unless a caller says order does not
matter. The socket gives the rate with the order kept.

## 1. fm-sdk: socket submission with an in-flight window

Python first, then TypeScript and Java (W4).

- `send_limit(...)` (`sendLimit` in TypeScript and Java), with
  `submit_limit`'s arguments, returns a `PendingOrder`, a `concurrent.futures.Future` with the
  submission id. It sends a STOMP `SEND` to `/app/marketplaces/{id}/orders`
  on the marketplace's order socket -- its own connection, opened by the
  first such order and closed with the client -- and returns at once.
  `submit_limit` keeps today's REST call and its answer. A separate method
  rather than a `wait` flag on `submit_limit`: Java cannot vary a method's
  return type by an argument, and the three SDKs keep one set of names.
- The future resolves when an `ORDERS-UPDATE` carries the robot's own order
  with that submission id (accepted), or an `ORDER-REJECTED` names it
  (refused, with the same failure body as REST).
- A window of at most `max_in_flight` orders (default 64) blocks the caller
  when full, so a robot cannot outrun the server's 4 MB per-session send
  buffer.
- While the socket is down, an order is held, not sent. On reconnect every
  unanswered order is sent again, in the order first submitted, with its own
  submission id; the server answers a repeat with the order it already made.
  (The first draft sent held orders over REST. That lets them overtake orders
  sent before the drop and still waiting to be resent, and keeping the order
  is the point.)
- An order whose outcome cannot be learned fails as `OrderOutcomeUnknown`,
  never as refused. Today both transports report a lost answer as a
  refusal, which invites the robot to place the order again
  (FMClient comparison, "Resubmit protection").
- An asyncio caller awaits `asyncio.wrap_future(pending)`; nothing in the
  SDK needs an event loop.

Done when: on one socket, `send_limit` sustains within 10% of
the hand-written socket burst in the same run, with nothing out of order.

**Python, 2026-10-28** (`feat/socket-orders`): 737 orders a second, median
of 6 runs of 200 (509-874), none of 1,200 out of order, against 564 for the
hand-written socket burst and 201 for REST in the same runs, on a local
fm-server 4.17.0 (`live_wait_false.py` in the comparison bench).

**TypeScript and Java, 2026-10-28**: `sendLimit` in both, the same design.
Against the same local server, three rounds of 200 each: TypeScript 682,
1,037 and 939 orders a second, Java 967, 1,022 and 799, none of 600 out of
order in either (`live-sdks/` in the comparison bench).

## 2. FMClientLibrary: no poll, and the window

- The agent's outgoing loop sleeps `MONITOR_ORDER_BOOK_DELAY` (250 ms)
  between looks at its queue. That sleep is about 250 of the ~260 ms every
  variant takes from `send_order` to `order_accepted`. Await the queue
  instead, so an order goes when it is queued. This helps both transports.
- The sdk transport submits with `send_limit` and lets the futures call
  `order_accepted` / `order_rejected` on the agent's loop, instead of
  awaiting each answer on an executor thread.

Done when: the FMClient burst benchmark has the fm-sdk transport above the
existing one in the same run, as shipped and with 1 ms polls, and above 130
orders a second as shipped (fm-sdk#7).

## 3. fm-server: one lookup on the common path

`OrderHandler` looks up `(owner, submissionId)` before claiming the
submission and again after. Claiming first and looking up once keeps the
race that W7 closed closed, and saves one database query per order. Socket
orders run one after another per socket, each after the last was matched,
so the server's time per order sets the socket's rate. Measure the saving
against the 1.1 ms before taking anything further.

Measured 2026-10-29 on fm-server 4.18.1 (`rest_split.py` in the comparison
bench, httpx with and without a `submissionId` interleaved, the server's own
time from `Server-Timing`, one warm-up run discarded, then four): a
`submissionId` adds 0.75 ms (median of the four runs, 0.46-1.02). With one
lookup it adds 0.72 ms (0.43-0.84). The second lookup costs too little to
measure, so the change was not taken. The 1.1 ms above, and 1.2 ms in one
later run, were single runs.

## 4. fm-sdk: a leaner REST order

For callers that place one order at a time and wait for it:

- An asyncio client (`AsyncFlexemarkets`, on `httpx.AsyncClient`) so an
  asyncio robot such as FMClient skips the executor hop: 0.9 ms an order.
- Profile `_request`: httpx cost 1.4 ms over the hand-written client in the
  same run (3.7 against 2.3 ms). Measure the timing hooks, header
  validation and response parsing, and cut what is not needed.

## 5. REST: several orders in one request

Where things stand on fm-server 4.18.1, through FMClient (10 rounds each,
medians): the socket places 879 orders a second in a burst, the existing
aiohttp transport 409, the original library with its fixes 188, and fm-sdk
over REST 146. REST is the fallback while the socket is down, and the route
for a caller that cannot hold a socket open, so it should not be the slowest.

One at a time it cannot catch up. Through FMClient, fm-sdk's REST order takes
6.7 ms to the existing transport's 2.9, in alternating rounds of one sitting.
Eight runs of `rest_split.py` (comparison bench), every layer interleaved,
account for the 3.8 ms (medians of the per-run differences):

| Layer | ms |
| --- | --- |
| fm-server: the `submissionId` and the v1 route | 1.1 |
| httpx against aiohttp | 0.6 |
| fm-sdk's call around httpx | 0.2 |
| FMClient's loop to an executor thread and back | 1.0 |
| FMClient's transport: the order in, the answer out | 0.3 |
| The robot's event stream open (four runs, 0.2-2.0), less ~0.6 B spends above its HTTP stack | 0.7 |

The server's 1.1 ms is what resubmit protection and the current API cost,
and stays. The stream's share is the threads competing: while the next order
is sent, fm-sdk's socket thread and FMClient's forwarder handle the last
one's `ORDERS-UPDATE`, and Python runs one thread at a time. Section 4's
asyncio client takes back the hop, and with aiohttp the library's 0.6 --
enough to pass 188 but not 409: a burst sent one at a time is bounded by the
round trip, and fm-sdk's always carries more server work than the existing
transport's.

So REST needs several orders in flight, and order kept. Sending several
requests at once reaches the book out of order (20-49 of 150 above). Ways to
keep order, from the client side:

- **HTTP/1.1 pipelining** on one connection. Tomcat answers a connection's
  requests in turn, but neither httpx nor aiohttp pipelines, and nothing
  between a robot and Heroku -- Cloudflare, Heroku's router -- promises to
  keep one client's requests on one connection in order. Not something to
  depend on.
- **HTTP/2 streams.** Tomcat runs each stream on its own thread: the same
  race as the socket before W2.
- **A sequence number per request** (`Order-Sequence: <lane>/<n>`), with
  fm-server holding a request until the lane's earlier ones have run. This is
  the design the socket rejected (`SOCKET-ORDERS.md`, "take orders where order
  still exists"): a request that never arrives stalls its lane until a
  timeout, and every held request holds a Tomcat thread.
- **Several orders in one request.** Ordered by construction: the body is a
  list, and the server runs it in list order, as `SocketOrderSequence` runs a
  socket's lane. Nothing waits for a request that never comes, and nothing is
  held between requests.

The last is the one to build.

### fm-server: `POST /api/v1/marketplaces/{id}/orders/batch`

- The body is a list of up to 64 orders, each as `POST .../orders` takes
  one, each with its own `submissionId`.
- The orders run one at a time, in list order, each through
  `OrderHandler.submitOrder` exactly as a single POST does: consent gate,
  replay check, rate limiter, matching. An order reaches the exchange only
  once the one before it has been matched.
- The answer is 200 with a list in the same order: for each order either the
  placed order (what a single POST answers with 201) or the failure body a
  single POST answers with its 4xx, and the status it would have had. One
  refusal does not stop the rest; a robot that wants all-or-nothing sends
  one at a time.
- A resend of the whole list is safe: each order already placed is answered
  with the order it made, as a single resend is.
- The rate limiter counts orders, not requests.
- `Server-Timing` gives the whole request's time; the per-order phases are
  already measured in `OrderHandler`.

### fm-sdk: `submit_limits` and a REST window

- `submit_limits(orders)` (`submitLimits` in TypeScript and Java) posts one
  list and returns one result per order, in order, each what `submit_limit`
  would have returned or raised.
- Behind `send_limit`, when the order route is REST rather than the socket:
  keep one request in flight; orders sent while it is out wait in a list, and
  go together as the next request when it is answered. One at a time this is
  `submit_limit`; in a burst the requests grow to the window. Each order's
  `PendingOrder` resolves from its entry in the answer. A request that fails
  without an answer is sent again whole, which the submission ids make safe.
- One request in flight, not several: two lists in flight could reach the
  book out of order, the problem this exists to remove.

### What it should buy

The hand-written client reached 2,700 orders a second with 8 requests in
flight, so the server is far from the limit. Each order still runs through
the matching thread one at a time, about 1-2 ms of server time with a
`submissionId`. With 64 in a request and ~6 ms of client and network per
request, a burst should reach 400-700 orders a second: past the existing
transport, short of the socket. Estimates, to be measured.

Done when: FMClient's burst benchmark has fm-sdk over REST above the existing
transport in the same run (B, 409 a second on 4.18.1), with nothing out of
order.

## Open

- One at a time, the socket was slower than REST (8.3 against ~6 ms): the
  acceptance comes on the marketplace's `ORDERS-UPDATE` fan-out, not as an
  answer to the frame. Through FMClient on fm-server 4.18.1 it is now the
  faster of the two: 4.3 ms a single order against 6.8 ms over REST.
  A direct acceptance was tried on 2026-10-28: fm-server answered every
  socket order with `ORDER-ACCEPTED` to the submitter. Against the same
  server without it, a single order took 1.02x as long (95% interval
  0.97-1.11), a burst placed 0.81x as many orders (0.67-1.01), and the
  client's CPU per order was 1.12x (1.05-1.23). Not taken: the reply goes
  through the same broker send, under the same lock, as the fan-out.
- The default window: 64 is a guess. W3 (load run, 20+ sockets) should set
  it, and watch for `SessionLimitExceeded` closes.
- Whether `send_limit` should become what robots reach for first once it has
  run in a course.
