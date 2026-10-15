# Multi-period studies in the study SPI (SSW first)

Written 2026-10-11 for Top-100 item 57 ("Set up SSW from the UI, then the
other studies"). Status: **decision for the owner**, with a
recommendation. Nothing is built.

## Why SSW can't be a StudyProvider today

`fm.study.StudyProvider` has three calls:

1. `setup` plans every rotation up front, each with its holdings and state.
2. The host stages those rotations in order (`advance`).
3. `settle` pays out once, from each person's holding at open and at close.

Smith 62 fits this: its four rotations are known before anyone trades.

SSW (`fm-robots/applications/studies/ssw`) does not fit, in four ways:

- **Each period's allotment comes from the last period's close.** At every
  period end, `SmithSuchanekWilliams.start` takes the holdings at close,
  draws a dividend (`_dividend`: from the list, at random, or typed in with
  `--manual`), applies it, carries the short allowance (`_carryShorts`),
  and allocates the result before the next period opens. None of this can
  be planned at setup.
- **The payout depends on the path, not just the end.**
  - In accrue mode, period k writes `dividends<k>.csv`: units held at k's
    close × k's dividend, per person. The final allotment sums all of
    them (`_lastAllotments`).
  - `settle` sees only the open and close of one rotation, so it cannot
    compute this.
- **The dividend is drawn while the run is live,** so the run has state
  between periods (which draw came up) that the SPI has nowhere to keep.
- **Shorts are measured against the run's starting allowance** (`S0`), not
  the last period's, for the reason `initialShortsFrom` documents.

Pre-drawing the dividends and paying them on final holdings only would
change the experiment: a share held through ten periods would earn the
same as one bought in the last. That isn't SSW.

## Options

### A. Add a per-period step to the SPI (recommended, SPI 0.1)

A default method:

```java
default Carry carry(Map<String, String> parameters, Rotation rotation,
                    int period, List<ParticipantHolding> holdings, String state)
```

- The host calls it when a period's session closes. It returns
  `Carry(holdings, state)`: the next period's allotment (dividend applied,
  shorts carried) and the study's updated state. In SSW's case the state
  is the draws so far, the per-person dividends and `S0`.
- The host stages the holdings as it already stages a rotation, and keeps
  the state on the run beside the rotation's.
- `settle` then reads the accumulated state.
- The host's period clock is already server-side:
  - session length is a marketplace option, and periods resume after a
    restart (item 27);
  - sessions can open and close at set times (item 54).
- A study that doesn't override `carry` behaves as today.

**Needs:**
- the SPI method and its version bump to 0.1;
- in fm-server, a `POST /api/v1/studies/runs/{id}/carry` (or the same call
  on close) plus run state;
- `SswStudy` in fm-robots, sharing `_addDividends`, `_carryShorts` and
  `_lastAllotments` with the command line so both produce the same files.

**Covers:** SSW, Lucas, and Credit Bubble, the other per-period studies.
That is most of the seven.

### B. Set up from the UI, run from the command line (a half step)

- An `SswStudy` whose `setup` is the command line's `marketplace` plus
  `allotments generate`: one marketplace, `STK`, 20.00 cash and 2 units
  each.
- The manager then runs `fm-ssw start` against that marketplace, and the
  command line reads the server's holdings.
- No SPI change; buildable now.

**Against:**
- The runs still need a terminal, which is the gap the item names.
- The UI would show a run it cannot advance or settle.

### C. Pay pre-drawn dividends on final holdings

Rejected: as shown above, it isn't the experiment.

## Recommendation

**A**, with **B** as its first commit: `SswStudy.setup` is the same under
both, so building it now isn't wasted. A is the only way the item's "sets
up, advances and settles through `/api/v1/studies/ssw/setup`" becomes
true. Simple Dividend, the item's "next", is a one-period study and fits
today's SPI as it is.
