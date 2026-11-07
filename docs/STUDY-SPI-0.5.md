# Study contract 0.5: marketplaces first, people later

Status: decided by the owner on 2026-11-07, being built. The contract half is on
`feat/study-spi-marketplaces` in fm-sdk.

## Why

Setting up a study takes nine steps and leaves the app on the way:

1. the flask in the side rail;
2. Set up a study, which opens the public landing page;
3. the study's public page;
4. Set up this study;
5. review the parameters;
6. pick participants from people who already exist;
7. Preview, which is required;
8. Create;
9. the run page.

A plain marketplace takes one screen. Its people are added afterwards, in
Participants → Next, where accounts are created inline.

The study contract forces the long way round. Its one planning call,
`setup`, returns the marketplaces together with every participant's holdings,
and every study refuses to plan for nobody. A host therefore cannot create a
study's marketplaces until it knows who takes part.

## The contract (fm-spi, contract 0.5)

`StudyProvider.marketplaces(parameters)` returns the marketplaces a run needs,
before anyone takes part. They must be the marketplaces `setup` returns, by
key, whoever takes part. The host creates these at setup and stages the
plan's rotations into them later.

- **Every study so far** builds its marketplaces from its parameters alone:
  `marketplace(Config)`, or Smith62's `marketplace(key, symbol, minutes)`.
  Each overrides the method with the builder its plan already uses.
- **The default**, for a study that does not override it, plans for
  stand-ins and keeps the marketplaces. The stand-ins are one participant, a
  manager, and a person for each robot of the study's own, all with negative
  ids and `.invalid` emails. This is right only when the marketplaces do not
  depend on who takes part, and the Javadoc says so.
- `STUDY_SPI_VERSION` is 0.5. A 0.5 study on a 0.4 host is refused by the
  minor-version rule, so the host ships first.

## The flow (fm-server, fm-ui)

1. **New study** sits beside New Marketplace. It shows the study's
   parameters with their defaults and a Create button: no participant picker
   and no Preview.
2. Create makes the run and its marketplaces from `marketplaces(...)`. The
   run is *unplanned*: no plan and no participants yet. The manager lands in
   the first marketplace.
3. People are added in Participants → Next, the normal way: emails,
   autocomplete, + All users, paste and import, with accounts created
   inline.
4. **Every Next save** on an unplanned or not-yet-started study marketplace
   plans the run for the staged allocation's owners. Robots are left out,
   and the owners go in grid order, which is Smith62's group order. The plan
   stages rotation 1 into each of the run's marketplaces, and the grid
   reloads with the generated holdings. Saving again re-plans, until the
   run's first session opens; after that the population is fixed, as today.
5. Settle, the following rotations and carry work as today.

## Host work (fm-server)

- **Migration:** `fm_study_run.plan` becomes nullable, and participants may
  be empty until the run is planned. Privacy comments go in the same file.
- **Create** no longer requires `participantIds`. When they are given, it
  plans at once as today, which keeps the SDKs and the command line working.
- **Plan-on-save:** when a staged allocation is saved on a marketplace that
  belongs to a run not yet started, the host plans with its owners and
  stages rotation 1 everywhere. The hook point is `AllotmentStore`'s
  `allocateAndStage` and the holdings import, which are the two writers of a
  staged allocation.
- **Guards:** settle, advance, carry, roster and robot launch say "add
  participants first" rather than failing on a null plan.
- **Robots:** their people are made at create, and their ids are kept for
  the planning.

## UI work (fm-ui)

- A New study screen: the catalogue, the parameters, and Create.
- Next on a study marketplace manages the people only. Its figures are the
  study's and read-only, since the plan would overwrite any edit.
- The study strip says "add participants to plan this study" or "planned for
  N".
- `/studies/:slug/setup` redirects to New study.

## Releases

1. fm-sdk: fm-spi with contract 0.5 (a `v*.*.*` tag publishes everywhere).
2. fm-server: the host speaking 0.5.
3. fm-robots: the eight studies override `marketplaces`, then are
   re-vendored into fm-server.
4. fm-ui.

Until step 3, fm-server falls back to the default for 0.4 studies. That
plans each with stand-ins, so the host works against today's vendored
studies.

## Known costs

- Lucas, SSW and Prediction Market draw unseeded, so each re-plan redraws.
  That already happened between today's Preview and Create.
- A Next edit to a study's cells is overwritten by the plan, which is why
  the UI makes them read-only.
