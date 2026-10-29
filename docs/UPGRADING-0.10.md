# Upgrading from fm-sdk 0.9.x to 0.10.x

## How to use this document

0.10.0 changes one thing: fm-spi 0.0.17, which is **study contract 0.3**. A
study can now name the robots it needs -- its own traders, which the host
gives people of their own and launches for each session, or robots it offers
participants with parameters it fixes -- and is told at close which holdings
and orders are its robots' (7cbc42c). The client is unchanged in all three
SDKs: Python and TypeScript have no study SPI, and in Java only fm-spi's
`fm.study` package changed.

Code that only **calls** the SDK needs nothing. A study that **implements**
`StudyProvider` needs nothing either: every addition has a default, and a
study without robots plans as it did. Code that **builds** or **matches** the
study types may need a change:

| | What breaks | Fix |
|---|---|---|
| Java | a record pattern over `Close`: the record gains a last component, `Map<String, String> robots` | add the component to the pattern; `new Close(holdings, orders)` is kept and gives no robots |
| Java | a test asserting `studySpiVersion()` is `"0.2"`: `StudyProvider.STUDY_SPI_VERSION` is `"0.3"` | expect `"0.3"` |
| TypeScript | nothing | |
| Python | nothing | |

What 0.7.0 settled is in [UPGRADING-0.7.md](UPGRADING-0.7.md) and is not
revisited here.

---

## What is new

**A study names its robots.** `StudyProvider.robots(parameters)` answers a
`List<RobotSpec>`, before the run is planned; the default answers none. A
`RobotSpec` is:

| Component | |
|---|---|
| `key` | the study's name for it, unique in the study: `good`, `bad`, `mvo` |
| `robotId` | the robot's manifest id, as the catalogue names it: `fm-venture-entrepreneur` |
| `marketplaceKey` | the plan's marketplace it runs in |
| `mode` | `RobotSpec.Mode.OWN` or `RobotSpec.Mode.OFFERED` |
| `parameters` | the robot's parameters by manifest name, as text, fixed for the run |
| `rotations` | the rotations an `OWN` robot runs in; empty means every rotation in its marketplace |

`key`, `robotId`, `marketplaceKey` and `mode` are required -- the constructor
throws `IllegalArgumentException` without them; a null `parameters` or
`rotations` is empty. `runsIn(rotation)` says whether it runs in a rotation.

An `OWN` robot is the study's own trader: the host gives it a person, launches
it as that person when each of its rotations' sessions opens, and stops it
when the session closes. It is never paid. An `OFFERED` robot is the
participants': they launch it as any robot, with the study's `parameters`.

**Setup is handed the robots' people.** A new overload,
`setup(parameters, participants, manager, robots)`, takes a
`Map<String, Participant>`: a person for each `OWN` robot, by its key. It is
what a 0.3 host calls. Its default calls 0.2's
`setup(parameters, participants, manager)`, so a study that does not override
it plans as before. A study whose robots trade from an allotment -- Venture's
entrepreneurs hold the notes they sell -- overrides it and writes the robots'
rows into the rotations' holdings as it writes anyone's.

**Close says which owners are the study's robots.** `Close.robots()` maps each
`OWN` robot's key to its email; it is empty for a study without any, and a
null map is empty. `Close.isRobot(email)` matches an owner's email from a
holding or an order, ignoring case. The robots' rows are in `holdings`,
because they hold what they trade; a study pays them nothing. `SessionOrder`
is unchanged.

---

## What you need

Nothing new of the server for code that only calls the SDK.

For a study, a host built on fm-spi 0.0.17 or later. A host compares only the
contract's major, which is still 0, so a 0.2 host loads a 0.3 study -- but it
does not call `robots` or the four-argument `setup`, and hands `settle` a
`Close` with no robots.

---

## Checking your code

```bash
# Java: record patterns over Close, and tests pinning the contract version
grep -rnE "Close\(" src/ | grep -E "instanceof|case "
grep -rnE "studySpiVersion|STUDY_SPI_VERSION" src/
```
