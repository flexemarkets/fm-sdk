# Your first trading robot

A robot is a program that trades in a marketplace on your behalf. This page
walks through the smallest one that trades well, in Python and TypeScript:

- `sdks/python/first_robot.py`
- `sdks/typescript/src/first-robot.ts`

Each is about a hundred lines, and each is tested
(`sdks/python/tests/test_first_robot.py`, `sdks/typescript/test/first-robot.test.ts`)
so it keeps doing what this page says.

## Run it

You need a marketplace you can trade in, and a credential: a file holding
`account=`, `email=` and `password=`, or a token.

```sh
make first-robot-python     ARGS="-C ~/.fm/credential -E 1234"
make first-robot-typescript ARGS="-C ~/.fm/credential -E 1234"
```

`-E` takes the marketplace's id, an endpoint file, or its URL. With `FM_URL`
set, a bare id is that host's marketplace rather than production's.

## What it does

Every two seconds, while the session is open, it quotes one unit **one tick
inside the spread** on each side it is allowed to trade: a bid one tick above
the best bid, and an offer one tick below the best offer. When the best price
moves, it cancels its quote and places a new one. When the session closes, or
you press Ctrl-C, it cancels what it has resting and stops.

It is not a strategy worth copying. It is the **shape** a robot should have,
and each rule below is there because robots without it caused trouble in real
classes.

## The rules it follows, and why

**1. Trade only while the session is OPEN.** An order sent before the open, or
after the close, is refused. One course's student robots sent 15,000–41,000
refused orders a day, most of them after the session closed. The robot reads
`desk.session()` on every tick and does nothing unless its state is `OPEN`.

**2. Trade only the sides your holding grants.** A market may let you buy and
not sell, or the reverse. `desk.holding()` says which (`can_buy` /
`can_sell`, `canBuy` / `canSell`), so the robot never sends an order the
server will refuse as not allowed.

**3. One order per side, replaced rather than added.** A robot that places a
new order every tick without cancelling the last one fills the book with its
own stale quotes. This one remembers what it has resting and cancels before
it re-quotes, and leaves the order alone when the price hasn't moved.

**4. Never cross the book by accident.** One tick inside the spread is still
inside it. When the spread is a single tick, there's no room, and the robot
waits.

**5. A refusal is news, not a crash.** The server can still say no: a price
off the market's grid, a session that closed a moment ago. The robot catches
the refusal (`InvalidArgumentError`), prints it, and carries on at the next
tick. A robot that dies on its first refusal trades for nobody, and one that
swallows refusals silently trades for nobody without telling you.

**6. Don't hammer.** Two seconds between looks is plenty for a classroom
market. A robot that acts every fraction of a second is most of a class's
traffic on its own, and slows the market for everyone.

**7. Clean up when you stop.** Resting orders outlive the program that placed
them. The robot cancels its own before it exits.

## Where the prices come from

All prices are integers in **cents**: `405` is 4.05. The robot reads the best
bid and offer from the desk's book (`best_buy_price()` / `bestBuyPrice()`,
`-1` when that side is empty), and moves them by the market's `price_tick`
(`priceTick`). The desk keeps the book current from the marketplace's event
stream, so the robot never asks the server for it.

## Changing it

Start in `quotes()`. It decides the prices from the book and the holding's
grants, and nothing else. Change how far inside the spread to quote, quote
more than one unit, or quote only one side. The rest of the robot already
handles sending, replacing, refusals and stopping. Run the tests after a
change: they check the rules above, not the strategy.
