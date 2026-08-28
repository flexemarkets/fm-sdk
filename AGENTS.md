# Working in this repository

Three SDKs — Java, Python, TypeScript — for one API. They are **hand-written,
not generated**, so every type, method and behaviour is declared three times and
nothing in the language keeps the copies honest. Most of the bugs this repo has
had were one SDK drifting from the other two, or all three agreeing on something
the server never promised.

## The one rule

**Anything you add to one SDK, add to all three.** A field, a method, an
exception, a default. If a difference is deliberate, record it in the matching
exemption map in `scripts/check-parity.py` with the reason — never leave it
unexplained, and never add an exemption to make the check quiet.

## Before you say you are done

```bash
make check          # parity, then each SDK's own checks
make test           # all three test suites
```

**GitHub Actions does not run either of these.** `ci.yml` builds all three and
runs Java's tests as a side effect of `mvn package`; it does not run `pytest`,
`npm test`, or `scripts/check-parity.py`. A green board means the three SDKs
compile — not that they agree, and not that Python or TypeScript work. Run them
locally or your change is unverified.

Java additionally holds **zero javadoc warnings**. Check on a clean build; a
dirty build and `mvn -q` both report a false zero.

## What check-parity.py compares

| | |
|---|---|
| wire types | the same fields, with the same names, in all three |
| client surface | the same methods on `Flexemarkets` and its roles, with the same required arguments |
| read-side surface | the same members on `MarketView`, `OrderBook`, `Trades` and their containers |
| documentation | a ratchet: a method documented in one SDK and not the others |
| failure types | the same exceptions catchable in each |

It cannot tell you the three are *right* — only that they say the same thing.
Being wrong together is this repo's most expensive failure mode and no
cross-SDK comparison can see it. That is what the fixtures are for.

## Fixtures: add once, all three run it

- `sdks/fixtures/*.json` — **wire** fixtures. A payload the server sends and the
  values every SDK must read out of it.
- `sdks/fixtures/behaviour/*.json` — **behaviour** fixtures. A sequence of
  updates and what `OrderBook` or `Trades` must hold afterwards.

Adding a fixture requires no code change in any SDK. Prefer one over three
hand-written tests: a fixture cannot be right in one language and wrong in
another without saying so.

Write `why` as what breaks when the fixture is absent. A fixture whose absence
breaks nothing is one nobody will maintain.

## Understand the order model before touching order code

Read **[docs/ORDER-MODEL.md](docs/ORDER-MODEL.md)** first, and the canonical
[Order Data Format](https://github.com/adhocmarkets/fm-ui/blob/main/src/assets/docs/ORDERS-CSV.md)
it points at. The API does not send trades, it sends orders that refer to other
orders by id, and the reconstruction rules are not guessable. In particular:

- `consumer` holds three states in one field — `null`, `0`, and a real id. A
  null check answers the wrong question.
- A cancellation is two rows and a partial fill is three or more. Act on each
  set once; acting twice is silent and leaves a plausible, wrong book.
- Which side of a match was resting is `OrderUtils.isResting`, not "the lower
  id". They agree until an order is split, which is where it matters.

## Publishing

A tag matching `v*.*.*` pushed to the repository publishes to **PyPI, npm and
Maven Central** — `.github/workflows/release.yml`. There is no staging step and
no undo. Do not create or push a tag as part of ordinary work.

## Two habits this codebase expects

- **No compatibility shims.** 0.1.0 removed accreted API on purpose;
  re-adding it under another name puts it back. See `docs/UPGRADING-0.1.md`.
- **No test that cannot fail.** If you fix something, the test you add should
  fail against the code you just changed. Several bugs here survived for years
  under tests that passed either way — a cancel that removed twice, under a test
  using a single one-unit order where removing twice and once look identical.
