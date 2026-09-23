# Upgrading from fm-sdk 0.2.x to 0.3.x

## How to use this document

Read the first section. If nothing there applies to you, there is nothing to
do: **0.3.0 breaks no published API in any of the three SDKs.**

That is worth saying plainly, because it is not what a `0.x` bump promises.
[Semantic versioning](https://semver.org/#spec-item-4) makes no compatibility
guarantee below 1.0, the README says outright that anything may be added,
renamed or removed in any release, and 0.2.0 did exactly that — 42 Java types
moved packages and a dozen were renamed. 0.3.0 simply did not need to.

What 0.2.0 settled is in [UPGRADING-0.2.md](UPGRADING-0.2.md) and is not
revisited here.

---

## What breaks

Nothing.

Measured rather than asserted: between `v0.2.0` and `v0.3.0` the Java public
surface is 270 added lines and one removed, and the removal is a `module-info`
line replaced by the two that export the new package. No type was renamed,
moved or withdrawn; no method changed shape; nothing was dropped from the
Python or TypeScript entry points.

So a consumer pinned to 0.2.x can move to 0.3.x by changing the version and
rebuilding. Both consumers in this organisation did exactly that.

---

## What is new

### `uploadState` — staging a study's private per-participant state

All three SDKs, added together so they stay level:

| Language | Call |
|---|---|
| Java | `List<ParticipantState> uploadState(long marketplaceId, Path csv)` |
| Python | `upload_state(marketplace_id, csv)` |
| TypeScript | `uploadState(marketplaceId, csv)` |

A study often needs each participant to begin with something only they can see
— a private valuation, a draw, an endowment that is not in their holdings. That
had no home: the holdings upload carries cash and securities and nothing else,
so studies passed private state out of band and the server never held it. This
stages it per participant and returns it as stored, one row each.

Nothing else changed to accommodate it. If your study does not have private
per-participant state, you will not notice this exists.

### Study views — Java only, and deliberately

`fm.views.StudyViews` and `fm.views.StudyView` ship the study view documents
inside the jar, with `fm/views/index.json` beside them as the list.

**This has no Python or TypeScript counterpart, on purpose.** A study's view is
read by exactly two things that never share a process: the study's CLI, which
creates the marketplace with it, and fm-server, which offers it to a manager as
something to start from. Both are JVM. Each used to keep its own copy and the
two drifted, which is the whole reason this exists — putting the document in the
one artifact both already depend on. Shipping a copy to PyPI and npm would add
two more copies to keep level with it and no reader for either.

If you are writing a study in Python or TypeScript, this is not a gap you have
hit; there is nothing on those sides that reads a view.

### Two artifacts released alongside

Neither is part of the SDK and neither is needed to use it.

- **`fm-expr` 0.0.1** — the evaluator for panel expressions, so the CLI that
  writes a panel and the server that renders one agree on what an expression
  means. Alpha even by the standards of this repo: `0.0.1`, and it says so.
- **`fm-spi` 0.0.13** — the study contract: what a study declares it needs, how
  it plans a session, how it settles a rotation. Consumed by fm-server and the
  study CLIs.

### The README now says this is alpha

Every README gained a notice at the top, above the install instructions, saying
what `0.x` means here: anything public may be added, renamed or removed in any
release, without a deprecation period, and every `0.x` bump should be treated as
potentially breaking. Pin an exact version rather than a range.

The PyPI classifier moved from `Development Status :: 4 - Beta` to
`3 - Alpha` at the same time, because it had been claiming more than the prose
below it.

---

## If you are moving from 0.1.x

Go through [UPGRADING-0.1.md](UPGRADING-0.1.md) and
[UPGRADING-0.2.md](UPGRADING-0.2.md) in order first. 0.2.0 is the release that
will cost you work; this one will not add to it.
