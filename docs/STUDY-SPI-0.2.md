# Study contract 0.2: the session's orders, early endings, and robots

Written 2026-10-24 for fm-robots#491 (Credit Bubble), #492 (Venture
Capital) and the robot half of #476 (CAPM). Status: **proposed, nothing
built.** Follows `STUDY-SPI-PERIODS.md`, which added `carry` (contract 0.1,
fm-spi 0.0.15).

Five studies are hosted on contract 0.1: Smith 62, Simple Dividend, SSW,
Lucas and CAPM without robots. The two that remain, and CAPM's robots, need
the four things below that 0.1 cannot express.

## What 0.1 cannot express

### 1. The close is holdings only

`carry` and `settle` get each participant's holding at close. Two studies
are paid from what the session's *orders* say:

- **Credit Bubble.** A claim is a SELL in the private claim market that
  rests and never trades. `ClaimPool._claims` counts each participant's
  unconsumed orders there (`consumer == null`, `units > 0`, not the pool
  manager's), capped at the tickets they hold. A holding shows the tickets
  but not the claims against them.
- **Venture.** Each note bought pays the buyer
  `(1 − failure rate of the seller) × repay − price` (`Venture._updatePerformance`).
  That needs every trade and which entrepreneur sold it, good or bad. A
  holding shows how many notes someone holds, not who sold them.

(Venture's README gives a success/failure payoff that is drawn at random.
The code pays the expected value, without a draw. The hosted study follows
the code, so the web and the command line agree.)

### 2. The number of periods is fixed at setup

The host carries until the plan's last rotation in a marketplace, then
offers Settle. Lucas got around this by drawing its period count at setup.
Credit Bubble cannot, because its ending depends on what participants do
(`ClaimPool.submit`, `_lastOpenPeriod`):

- If a period's claims exceed the pool, or the last period is reached, the
  pool is liquidated.
- **Weak liquidation** runs one more period with claims disabled, so the
  ticket market can trade, then settles.
- **Strong liquidation** settles straight away.

A 9-period run that liquidates in period 4 ends after period 4 or 5.
Periods 6 to 9 never run.

### 3. A plan cannot touch robots

- **Venture's sellers are robots.** There are two entrepreneur agents, good
  and bad. Each is its own account, so a VC cannot tell them apart. Each
  sells into the best bid when its mean-variance score clears its threshold
  (`Venture._attemptTakes`). The CLI runs them in its own process from the
  `-G` and `-B` credentials. A hosted run without them has no sellers.
- **CAPM's robots are participants'.** Participants launch MVO robots
  themselves. #476 asks that the plan enable them and set their `penalty`
  and `payoffs`, so the robots and the study's settlement cannot disagree.
  Today a manager configures them by hand.

These are two different modes: robots the study runs as its own traders,
and robots the study offers to participants with fixed parameters.

### 4. Traders who are not participants

Venture's entrepreneurs hold the notes, so they need rows in the rotation's
holdings CSV, which is keyed on email. `setup` is only given the
participants, so it cannot write those rows. And at settlement, their
holdings must not be paid as a participant's would be.

**Not a gap: Credit Bubble's pool manager.** The CLI gives the pool manager
a holding so that its screen shows the pool's cash and the remaining
claims. Hosted, the pool's cash lives in `carried`, and the settlement
report shows it. The manager is not in the plan's holdings, so fm-server
015a772e6 already leaves them out of the close.

## Proposal

Every change is additive, with defaults that keep 0.1 providers working
unchanged. That makes it **contract 0.2, fm-spi 0.0.16**.

### 1. `Close`: holdings and orders

```java
/** What a session ended with: every holding, and every order it took. */
public record Close(List<ParticipantHolding> holdings, List<SessionOrder> orders) {}

/**
 * One order as the platform kept it. A partial fill is two orders, as on
 * the platform.
 *
 * @param owner        the owner's email
 * @param robot        the RobotSpec key when a study's own robot placed
 *                     it, else null
 * @param state        RESTING at close, TRADED or CANCELLED
 * @param counterparty the order it traded with, when TRADED
 */
public record SessionOrder(long id, long ownerId, String owner, String robot,
        String symbol, Side side, long price, long units,
        State state, Long counterparty) {
    public enum Side { BUY, SELL }
    public enum State { RESTING, TRADED, CANCELLED }
}
```

`StudyProvider` gains two overloads. The host calls these and only these:

```java
default Carry carry(Map<String, String> parameters, Rotation rotation, Close close, String carried) {
    return carry(parameters, rotation, close.holdings(), carried);
}

default Settlement settle(Map<String, String> parameters, Rotation rotation, Close close, String carried) {
    return settle(parameters, rotation, close.holdings(), carried);
}
```

- **Why `state`, not the platform's `consumer`.** On the platform,
  `consumer` means traded *or* cancelled. fm-server's `OrderPredicates`
  pairs cancels by hand to tell which. Every study that re-derived this
  would be a new place for the same mistake, so the host resolves it once.
- **Keeping the CLI's result the same.** Credit Bubble's claim count and
  Venture's performance become functions over `SessionOrder`. One adapter
  in fm-robots maps the SDK's `Order` to `SessionOrder` for the CLI. The
  study's tests check that both paths give the same answer for the same
  recorded session, as they do for the five studies already hosted.
- **Cost.** The host reads one more query per close: the session's orders.
  A 60-person session is a few thousand rows.
- **Rejected: a `needsOrders()` flag**, so that only studies that use
  orders would get them. It saves little and adds a second way to call a
  study.

### 2. A carry can end the run

`Carry` gains `Next next`. The existing three-argument constructor keeps
`PERIOD`:

| `next` | Meaning | Credit Bubble case |
|---|---|---|
| `PERIOD` | Stage the holdings. More periods may follow. This is today's behaviour. | Pool solvent |
| `LAST` | Stage the holdings. After that period closes, settle. | Weak liquidation |
| `SETTLE` | Stage nothing. Settle this close. | Strong liquidation |

This also needs factories, `Carry.last(holdings, state, carried)` and
`Carry.settle(carried)`.

On the host (fm-server `StudyRunHandler`):

- **After `LAST`:** once the staged period closes, carry is refused ("the
  study ended at period 5; settle it"), and the strip offers Settle.
- **After `SETTLE`:** the run is settled from the same closed session.
  Today's guard ("staged but has not run") refuses that, so it gives way
  when the carry ended the run.
- **The plan's later rotations** are shown as *not run* on the run page.
  The rotation state gains `SKIPPED`.
- **Settle after `SETTLE` gets the same `Close` the carry just read.**
  `carried` records which period the provider has already counted, so it
  does not count the claims twice. That bookkeeping is the provider's
  responsibility, and the contract's Javadoc says so.
- **Rejected: letting `settle` decide** whether the run is over. The UI
  has to know whether to offer Carry or Settle *before* the manager clicks,
  and carry's answer is that knowledge.

### 3. Robots in the plan

```java
/**
 * A robot the study needs in one of its marketplaces.
 *
 * @param key            the study's name for it: good, bad, mvo
 * @param robotId        the robot's manifest id
 * @param marketplaceKey where
 * @param mode           OWN: the study's own trader, launched by the host;
 *                       OFFERED: enabled for participants to launch
 * @param parameters     fixed values; a manager cannot change them
 *                       during the run
 * @param rotations      the rotations it runs in; empty means all of the
 *                       marketplace's
 */
public record RobotSpec(String key, String robotId, String marketplaceKey, Mode mode,
        Map<String, String> parameters, Set<Integer> rotations) {
    public enum Mode { OWN, OFFERED }
}
```

`StudyProvider` gains two methods:

```java
/** Asked before setup, because setup needs the OWN robots' people. */
default List<RobotSpec> robots(Map<String, String> parameters) { return List.of(); }

/** Plans the run with each OWN robot's person, keyed by RobotSpec key. */
default StudyPlan setup(Map<String, String> parameters, List<Participant> participants,
        Map<String, Participant> robots) {
    return setup(parameters, participants);
}
```

**`OWN`, for Venture.**

1. The host finds or creates a person for each spec (see decision 1),
   then calls the three-argument `setup`.
2. The provider writes the robots' rows, notes to sell, into the holdings
   CSV the same way it writes anyone's. A manager uploading that CSV would
   get the same allotment.
3. When a rotation's session opens, the host launches the spec's robot
   through `MarketplaceRobotHandler`, *as the robot's person*. It records
   the launch in the ledger with `launchedBy` set to the manager. It stops
   the robot when the session closes.
4. `LaunchCredentials` gains an issue-for-person method that only the host
   can call, with no REST route. Today a robot always runs as whoever
   launched it.
5. In the `Close`, the robot's orders carry its `robot` key, and its
   holdings are marked the same way. The host refuses a payoff addressed
   to a robot, and stages robots' holdings zeroed and locked like everyone
   else's.
6. The run keeps `robots: key → personId`, so the host can tell a robot's
   holding from a participant's.

**`OFFERED`, for CAPM.**

1. At setup, the host enables the robot in the marketplace and writes its
   `parameters` as the manager's fixed values (`MarketplaceRobotConfig`
   with no control). Participants launch it as they do today.
2. While the run is live, an edit to a plan-fixed parameter is refused.
   This is the test #476 asks for: a robot's penalty cannot differ from
   the study's.

**Venture's entrepreneur becomes a robot.** The take loop moves out of
`Venture.java` into an `fm-venture-entrepreneur` robot in fm-robots: a
`ServiceProvider` with a manifest for failure rate, high and low payoff,
repays, and interval. It is vendored with the study.

- The CLI runs two of these from its `-G` and `-B` credentials, so the CLI
  and the hosted study run the same entrepreneur.
- The robot runs in-process (`local:`) or in the container. The spec names
  the robot unqualified, as the catalogue does.

### Versioning

- **Contract 0.2, fm-spi 0.0.16** on Central. Every addition has a
  default.
- **Host rule.** `StudyCatalog` today refuses a provider only when its
  major version differs. A 0.2 provider on a 0.1 host would then load, and
  its robots, orders and endings would be silently ignored. Credit Bubble
  would carry claims as if nobody had made any. **Proposed:** a host also
  refuses a provider whose minor is newer than its own. This is one line,
  and it ships with the 0.2 host.

## Build order

#491 needs only parts 1 and 2; #492 needs parts 1 and 3. They can ship
apart, Credit Bubble first.

1. **fm-sdk:** the records, the overloads and their tests; release fm-spi
   0.0.16 (decision 4).
2. **fm-server**, harness-tested:
   - reading the session's orders into `Close`;
   - handling `Next` and `SKIPPED`;
   - the minor-version refusal;
   - then the robot work: people, OWN launch and stop, OFFERED
     configuration, and the guard on plan-fixed parameters.
3. **fm-robots:**
   - **Credit Bubble:** `CreditBubbleStudy`, sharing `ClaimPool` with the
     CLI. Claims are counted over `SessionOrder` in both. Includes a test
     that the CLI and the hosted study pay the same.
   - **Venture:** the `fm-venture-entrepreneur` robot, with the CLI moved
     onto it, then `VentureStudy`.
   - **CAPM:** `CapmStudy.robots`.
4. **fm-ui:** the strip offers Settle when the run has ended. The run page
   shows skipped rotations and the run's robots.
5. **Validation, all four layers per study:**
   - the CLI-parity test;
   - the harness run;
   - `validate-studies.sh --hosted`;
   - e2e scenario 71 (Credit Bubble, weak and strong) and 72 (Venture).

## Decisions for the owner

1. **Who the robot people are.** Options: a person per run, a reusable
   person per account per study and key (`venture-good`), or a new kind of
   person.
   - **Recommended:** reusable per account, flagged as a robot and left out
     of the class list, so that they appear in no participant picker.
   - That needs a `Person` flag and a migration. Without a flag they show
     up among the people.
   - Also: do they count against seats?
2. **When OWN robots run.**
   - **Recommended:** launched when the session opens and stopped when it
     closes, with nothing for the manager to press.
   - The alternative is a launch button on the run page.
3. **Venture's mid-session reveal.** The CLI's pause prints how many trades
   went to the good and to the bad entrepreneur, for the manager to share.
   - **Recommended:** leave it out of hosted v1; the manager can see the
     trades.
   - The alternative is a study "announcement" hook, which would be
     contract 0.3.
4. **Releasing fm-spi 0.0.16 to Central.** Recommended once fm-server's
   half of part 1 or 2 is green against a snapshot, as 0.0.15 was.
5. **Shipping in two parts.** Recommended: Credit Bubble (parts 1 and 2)
   first, then Venture and CAPM's robots (part 3).
