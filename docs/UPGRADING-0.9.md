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
| Java | a call to `settle` or `carry` with a literal `null` for the holdings: each now has a four-argument overload taking `Close` beside the one taking `List<ParticipantHolding>`, so the call is ambiguous | pass `List.of()`, or cast the `null` |
| Java | a test asserting `studySpiVersion()` is `"0.1"`: `StudyProvider.STUDY_SPI_VERSION` is `"0.2"` | expect `"0.2"` |
| TypeScript | nothing: there is no study contract in TypeScript | |
| Python | nothing: there is no study contract in Python | |

Earlier releases are covered by their own upgrade notes, listed in the
[README](../README.md#upgrade-notes), and are not revisited here.

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
(`List<SessionOrder>`, the session's limit orders in the order they were
placed; see [What a host does](#what-a-host-does)). It keeps its own copies and holds empty lists, never nulls;
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
After a `LAST` carry it stages the last period, and once that period's
session has run it allows only a settle; after a `SETTLE` carry it stages
nothing, and the next step is the settle of the period that just closed.
Either way the plan's remaining rotations are not run, and the settle is still
the manager's to start.

The host's orders are its session's limit orders as they ended: a partly
filled order is two orders, and a split's parent and a cancel's own record are
not among them.

A provider that leaves `studySpiVersion()` alone reports the contract version
of the fm-spi it runs against. On a host that is the host's fm-spi, since a
study builds against it as `provided`, not the version the study was built
with.

---

## What you need

For callers of the client SDKs, the same server as 0.8.0.

A hosted study that uses any of contract 0.2 needs **fm-server 4.14.0 or
later**, the first release that hosts it (fm-server 1a8b0dd15). An older
fm-server runs fm-spi 0.0.15, contract 0.1, and still loads such a study, but
the study fails when it reaches what 0.0.15 lacks: `NoClassDefFoundError` on
`Close`, `SessionOrder` or `Carry.Next`, `NoSuchMethodError` on `Carry.last`,
`Carry.settle` or `next()`. A study that overrides only the `Close` form of
`carry` is never called there: the host calls the holdings form, whose default
throws `UnsupportedOperationException`.

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
