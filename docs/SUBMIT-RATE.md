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
   rate.
4. **fm-sdk: a leaner REST path for one order at a time**: an asyncio client,
   and less work per request.

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

## 4. fm-sdk: a leaner REST order

For callers that place one order at a time and wait for it:

- An asyncio client (`AsyncFlexemarkets`, on `httpx.AsyncClient`) so an
  asyncio robot such as FMClient skips the executor hop: 0.9 ms an order.
- Profile `_request`: httpx cost 1.4 ms over the hand-written client in the
  same run (3.7 against 2.3 ms). Measure the timing hooks, header
  validation and response parsing, and cut what is not needed.

## Open

- One at a time, the socket is slower than REST (8.3 against ~6 ms): the
  acceptance comes on the marketplace's `ORDERS-UPDATE` fan-out, not as an
  answer to the frame. A direct acceptance to the submitter would close
  that, at the cost of a second message per order.
- The default window: 64 is a guess. W3 (load run, 20+ sockets) should set
  it, and watch for `SessionLimitExceeded` closes.
- Whether `send_limit` should become what robots reach for first once it has
  run in a course.
