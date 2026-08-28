See **[AGENTS.md](AGENTS.md)** for how to work in this repository, and
**[docs/ORDER-MODEL.md](docs/ORDER-MODEL.md)** before touching anything that
reads orders.

Two things worth repeating here, because both are easy to get wrong and neither
is caught by a build:

- `make check && make test` before reporting a change done. GitHub Actions does
  not run the parity check, `pytest`, or `npm test`.
- A `v*.*.*` tag publishes to PyPI, npm and Maven Central with no undo. Do not
  create or push one as part of ordinary work.
