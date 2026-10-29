# Upgrading from fm-sdk 0.8.x to 0.9.x

## How to use this document

0.9.0 changes only the study contract, in Java's `fm-spi`: study contract
0.2, `fm-spi` 0.0.16 (`StudyProvider.STUDY_SPI_VERSION` is `"0.2"`). A study
can be handed the session's orders beside its holdings, a carry can end the
run before the plan's last period, and `setup` can be told who is setting the
run up (b490f09, 8309c05). The design is
[STUDY-SPI-0.2.md](STUDY-SPI-0.2.md).

The client SDKs are unchanged. Code that **calls** fm-sdk -- in Java,
TypeScript or Python -- needs nothing; the TypeScript and Python packages
differ from 0.8.0 only in their version, and the Java `fm-sdk` artifact does
not depend on `fm-spi`.

Code that **implements** `StudyProvider` needs nothing either: every addition
is a default method or a new type, and the defaults answer as a 0.1 study
does. Code that **builds** or **takes apart** a `Carry` -- a host, or a
study's tests -- may need one change:

| | What breaks | Fix |
|---|---|---|
| Java | record patterns over `Carry`: the record gains a last component, `Next next` | add it to the pattern; the three-argument constructor `new Carry(holdings, state, carried)` is kept and means `Next.PERIOD` |
| Java | a host that reads `Carry.holdings()`: it is null when `next()` is `Next.SETTLE` | check `next()` before staging; see [What a host does](#what-a-host-does) |
| TypeScript | nothing: there is no study contract in TypeScript | |
| Python | nothing: there is no study contract in Python | |

What 0.8.0 changed is in [UPGRADING-0.8.md](UPGRADING-0.8.md) and is not
revisited here.

---

## What a study may add

Each of these is optional. A study paid from holdings, with the periods its
plan sets, leaves them all alone and runs as it did on contract 0.1
(`StudyProviderCloseTest`).

**`settle` and `carry` can read the session's orders.** `StudyProvider` gains
two default overloads, and a host on contract 0.2 calls these and only these:

```java
default Settlement settle(Map<String, String> parameters, Rotation rotation, Close close, String carried)
default Carry carry(Map<String, String> parameters, Rotation rotation, Close close, String carried)
```

By default each passes `close.holdings()` to the existing
`settle(parameters, rotation, List<ParticipantHolding>, carried)` and
`carry(parameters, rotation, List<ParticipantHolding>, carried)`, so a study
that overrides only those is called as before. A study that does not carry
still throws `UnsupportedOperationException` through the new `carry`. Override
the `Close` forms when the payoff or the next period depends on what the
orders say rather than what anyone holds.

`Close` is a record of `holdings` (`List<ParticipantHolding>`) and `orders`
(`List<SessionOrder>`, every order of the session in the order it was
placed). It keeps its own copies and holds empty lists, never nulls;
`Close.of(holdings)` makes one with no orders.

`SessionOrder` is a record of `id`, `ownerId` (as `ParticipantHolding.id`),
`owner` (the email), `symbol`, `side` (`Side.BUY` or `Side.SELL`), `price` in
cents, `units` (always positive), `state` and `counterparty`. `state` is how
the order ended -- `State.RESTING` (still in the book at close; also
`resting()`), `State.TRADED` or `State.CANCELLED` -- worked out by the host,
so a study does not pair cancels from the platform's `consumer` itself.
`counterparty` is the id of the order it traded with, and is set exactly when
`state` is `TRADED`. A partially filled order is two orders, as on the
platform. The constructor refuses a blank `symbol`, a null `side` or `state`,
`units` of zero or less, and a `counterparty` that disagrees with `state`.

**A carry can end the run.** `Carry` gains `next`, a `Carry.Next`:

| `next` | Made with | Meaning |
|---|---|---|
| `PERIOD` | `new Carry(holdings, state, carried)` | stage the next period; more may follow, as the plan has them (contract 0.1's carry) |
| `LAST` | `Carry.last(holdings, state, carried)` | stage the next period, and settle once it has run |
| `SETTLE` | `Carry.settle(carried)` | stage nothing; the period just closed is settled |

A null `next` in the four-argument constructor means `PERIOD`. A `SETTLE`
carry must have null `holdings` and `state`; every other carry still needs
non-blank `holdings`. After `SETTLE`, the host hands `settle` the same
`Close` this carry read, with this carry's `carried`: a study that counted
something from that close in `carry` must say so in `carried`, and not count
it again in `settle`.

**`setup` can be told who is setting the run up.** `StudyProvider` gains

```java
default StudyPlan setup(Map<String, String> parameters, List<Participant> participants, Participant manager)
```

which by default returns `setup(parameters, participants)`. A study whose
plan gives the manager a part -- Credit Bubble allots its pool to them --
overrides it. The manager is not a participant, and a study pays them
nothing. The two-argument `setup` is still abstract, so such a study
implements both.

---

## What a host does

A host on contract 0.2 calls the three-argument `setup` and the `Close`
forms of `carry` and `settle`, reading the session's orders into `Close`.
After a `LAST` carry it runs the staged period and then settles; after a
`SETTLE` carry it stages nothing and settles the period that just closed.
Either way the plan's remaining rotations are not run.

A provider that leaves `studySpiVersion()` alone reports `"0.2"` under
`fm-spi` 0.0.16.

---

## What you need

For callers of the client SDKs, the same server as 0.8.0.

A hosted study that uses any of contract 0.2 needs **fm-server 4.14.0 or
later**, the first release that hosts it (fm-server 1a8b0dd15). Before 4.14.0,
fm-server checks only the contract's major, so it loads such a study and calls
only the holdings forms: no orders, no early ending, and no manager. From
4.14.0 it also leaves out a study whose minor is newer than its own.

To build a study against contract 0.2, depend on `com.flexemarkets:fm-spi`
0.0.16 or later.

---

## Checking your code

```bash
# Record patterns over Carry, and hosts that stage a carry's holdings
grep -rnE "Carry\(" src/ | grep -E "instanceof|case "
grep -rn "\.holdings()" src/ | grep -i carry
# Studies that may want the orders, an early end, or the manager
grep -rn "implements StudyProvider" src/
```
