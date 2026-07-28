# fm-robots: CLI, agents and the plugin SPI

[`fm-robots`](https://github.com/adhocmarkets/fm-robots) is the companion
repository to this one: a Maven reactor of libraries and runnable applications
that drive a Flexemarkets marketplace from outside the browser. Where the SDKs
in this repository are what you *build with*, fm-robots is what you *run*.

Three things live there, and this document is the reference for each:

1. **`fm-manager`** — the management and traffic-generation CLI.
2. **Robot agents** — the maker/taker family, run locally or hosted in-app.
3. **The plugin contract** — `com.flexemarkets:fm-spi`, published from this
   repository, that lets a robot be dropped into a host without either side
   compiling against the other.

- [Getting the tools](#getting-the-tools)
- [fm-manager](#fm-manager)
  - [Global options](#global-options)
  - [Command map](#command-map)
  - [Reading a marketplace](#reading-a-marketplace)
  - [Allocating assets](#allocating-assets)
  - [Submitting and generating orders](#submitting-and-generating-orders)
  - [Traffic generation: `trader`](#traffic-generation-trader)
  - [Acting on behalf of users: `tokens`](#acting-on-behalf-of-users-tokens)
- [Robot agents](#robot-agents)
- [The plugin SPI](#the-plugin-spi)

---

## Getting the tools

fm-robots is distributed as source — clone the repository and build. There is no
separate jar release; the repository *is* the release.

```bash
git clone https://github.com/adhocmarkets/fm-robots.git
cd fm-robots
mvn install -DskipTests
```

Requires **JDK 25+** and Maven 3+. The build copies each runnable application's
jar to `~/.fm/<artifact>.jar`, so after a build you have `~/.fm/fm-manager.jar`,
`~/.fm/fm-maker.jar` and friends regardless of where you cloned.

An alias makes the CLI bearable:

```bash
alias fm='java -jar ~/.fm/fm-manager.jar'
```

Every example below assumes it.

Credentials and endpoint come from `~/.fm/credential` and `~/.fm/endpoint` in
the same format the SDKs use — see the
[API reference](API-REFERENCE.md#base-url-and-endpoint-resolution).

## fm-manager

```
fm --help
```

### Global options

| Option | Meaning |
|--------|---------|
| `-C`, `--credential=<file>` | credential file **or** a bare token value |
| `-E`, `--endpoint=<value>` | marketplace id, full URL, or endpoint file |
| `-A`, `--account=<name-or-id>` | operate on another account (**admin only**) |
| `-x`, `--exception` | print the underlying error, not just the message |
| `-X`, `--capture` | dump the HTTP request/response exchange |
| `-V`, `--version` | print the build version |

`-E` accepts a bare marketplace id, so `-E 2540` targets marketplace 2540 on
production without a file.

Most read commands also take `-j`/`--json` for machine-readable output and
`--columns=a,b,c` to pick columns. Anything session-scoped takes
`-s`/`--sessions=<ids>` where `0` means the current session.

### Command map

| Command | What it does |
|---------|--------------|
| `account` | `show` · `list` · `signup` · `approve` · `delete` |
| `user` | `show` · `list` · `create` · `delete` |
| `marketplace` | `show` · `list` · `summary` · `create` · `delete` · `create-market`, plus marketplace-scoped forms of the commands below |
| `session` | `show` · `list` · `details` (session-chain summary) |
| `open` / `pause` / `close` | session state transitions |
| `holding` | `show` · `list` (`-d` for transaction detail) |
| `allocation` | `show` · `list` |
| `allocate` | stage assets and permissions from a file |
| `upload` | same, from a holdings CSV |
| `download` | export orders and holdings as CSV |
| `orders` / `trades` | session order and trade listings |
| `submits` | submitted-order history (the CSV `trader replay` consumes) |
| `submit` | submit orders from a file and/or generate book depth, trades and churn |
| `trader` | `replay` · `zi` · `run` · `load` — traffic generation |
| `tokens issue` | mint per-user OTPs so the manager can act on their behalf |
| `events` | stream marketplace events (sessions, orders, holdings) |
| `connections` | list client connections |
| `test` / `validate` | fm-data API integration tests and a role-based access matrix (**admin credentials**) |

Most commands exist both at the top level and under `marketplace` — `fm orders`
and `fm marketplace orders` are the same operation, the latter letting you name
a marketplace id positionally instead of via `-E`.

### Reading a marketplace

```bash
fm -E 2540 marketplace show
fm -E 2540 session details
fm -E 2540 orders --sessions 0 -j
fm -E 2540 holding list -d
fm -E 2540 trades -g            # include the trade graph
fm -E 2540 download -o run.csv
```

`fm -E 2540 events` opens a live WebSocket stream of session, order and holding
events — the quickest way to see what a robot is actually doing.

### Allocating assets

```bash
fm -E 2540 allocate allotments.csv
fm -E 2540 upload holdings.csv
```

Both **stage** the next allocation; it lands when a **closed** session is
opened. Pausing and re-opening does not consume it.

If the CSV names participants by email, they are resolved to user ids for you.
A row naming an unknown person, marketplace or asset fails the request with
`ALLOTMENT_INVALID` and a `400` — read the `message`, fix the row, re-run.

Column formats live in the in-app guides (`/documentation/HOLDINGS-CSV`,
`/documentation/USERS-CSV`).

### Submitting and generating orders

`fm submit` does two jobs: replay an explicit order file, and synthesise order
flow. They compose — a file plus generators in one invocation.

```bash
# from a file
fm -E 2540 submit orders.csv

# generate a resting book: mid 100¢, 4¢ spread, 5 levels a side, 10 units deep
fm -E 2540 submit --symbol AAPL --book mid=100,spread=4,levels=5,depth=10

# generate 40 executed trades of 1 unit between 98¢ and 102¢
fm -E 2540 submit --symbol AAPL --trades count=40,units=1,band=98-102

# overlay stochastic churn for 30s at 1 order/user/sec, 10% cancels
fm -E 2540 submit --symbol AAPL --churn arrivals=1.0,duration=30,cancels=0.1
```

| Option | Effect |
|--------|--------|
| `--symbol=sym[,sym]` or `all` | target market(s); optional when the marketplace has exactly one |
| `--users=list` | participants who own the generated orders |
| `--provision` | fund `--users` for the generated orders and **(re)open the session** before submitting |
| `--dry-run` | build and print the generated CSV without submitting or provisioning |
| `--phased` | sequence file/book/trades/churn into disjoint windows instead of interleaving — guarantees exact trade counts |
| `--seed=n` | RNG seed for reproducible generation (default `0`) |
| `--timing=t` | `0` submits as fast as possible; `1.0` reproduces elapsed time |
| `--continue-on-error` | keep going past a rejection and print a grouped summary at the end (default: stop at the first) |
| `--step` | step through interactively, showing holdings before and after each order |

Generation specs, required keys in CAPS:

```
--book   MID,SPREAD,LEVELS,DEPTH       (all required, > 0)
--trades COUNT[,units=1][,band=lo-hi]  (or a bare count: --trades 40)
--churn  ARRIVALS,DURATION[,cancels=0][,cancel-delay=2][,side=both]
         [,band=lo-hi][,units=lo-hi]
```

Prices snap to the market tick and must fall inside its bounds. The full
generation reference — every key, its default, and the interaction rules — is
`applications/manager/docs/SUBMIT-GENERATION.md` in the fm-robots repository.

`--provision` is the option that turns a bare marketplace into a runnable
scenario in one command: it funds the named users for exactly the orders about
to be submitted and cycles the session so the funding takes effect. Without it,
generated orders fail with `ORDER_INSUFFICIENT_ASSETS` against an unfunded
participant. Pair it with `--dry-run` first to see what will be submitted.

### Traffic generation: `trader`

```
fm trader --help
```

| Subcommand | Purpose |
|------------|---------|
| `replay` | replay a `SubmittedOrder` CSV (the output of `fm submits`) |
| `zi` | stochastic zero-intelligence order flow through named users |
| `run` | replay one CSV while ZI users add background flow |
| `load` | provision a fresh marketplace and users, then drive ZI load through them |

**Replay** reproduces a recorded run:

```bash
fm -E 2540 submits -o run.csv
fm -E 2540 trader replay run.csv --timing 1.0    # real time
fm -E 2540 trader replay run.csv --asap          # alias for --timing 0.0
fm -E 2540 trader replay run.csv --step          # ENTER between rows
```

**ZI** generates Poisson-arrival order flow. `--duration`, `--price-min`,
`--price-max`, `--markets` and `--users` are required:

```bash
fm -E 2540 trader zi \
  --markets AAPL,IBM --users p1@example.com,p2@example.com \
  --duration 120 --price-min 90 --price-max 110 \
  --arrivals 0.5 --cancels 0.2 --cancel-delay 2 --units-max 5
```

`--side B|S|both` restricts direction and `--seed` makes a run reproducible.

**Load** is the self-contained option — it provisions everything, runs, and
cleans up:

```bash
fm trader load --marketplaces 2 --users 10 --markets AAPL \
  --events 200 --price-min 90 --price-max 110 --keep
```

`--events=n` bounds the run by orders per user (`0` = time-bounded only);
`--marketplaces=n` runs several in parallel; `--keep` leaves the marketplace and
users behind instead of deleting them; `--detailed` prints server-side
order-phase histograms and queue-depth series and needs the manager to hold
`ROLE_ADMIN`.

### Acting on behalf of users: `tokens`

```bash
fm tokens issue p1@example.com p2@example.com 503
```

Mints a single-use OTP per user via `POST /api/otp/manager` and prints the
bundle as JSON. Every id or email must belong to your own account — one stray
entry refuses the whole batch. OTPs expire after five minutes and are redeemed
with `GET /api/otp?otp=…`.

See [Acting on behalf of participants](API-REFERENCE.md#acting-on-behalf-of-participants-otp)
for the protocol, and the in-app `/documentation/AUTH-AND-OTP` guide for the
concept.

## Robot agents

Each robot is a standalone application with its own jar and a picocli
interface. They share the global `-C` / `-E` options plus:

| Option | Meaning |
|--------|---------|
| `-i`, `--interval=<mS>` | evaluation interval (default 2000 mS) |
| `-s`, `--interval-spread=<mS>` | random variation added to the interval |

| Robot | Positional arguments | Behaviour |
|-------|----------------------|-----------|
| `fm-maker` | `(SIDE SYMBOL PRICE)…` | liquidity-**making** orders at a fixed price |
| `fm-taker` | `(SIDE SYMBOL PRICE)…` | liquidity-**taking** orders at a fixed price |
| `fm-maker-mvo` | `PENALTY (SYMBOL SPREAD)…` | mean-variance-optimised making across several assets |
| `fm-taker-mvo` | `PENALTY (SYMBOL SPREAD)…` | mean-variance-optimised taking |
| `fm-maker-ols` | `SYMBOL LOOKBACK` | making on an OLS regression of recent trades |
| `fm-taker-ols` | `SYMBOL LOOKBACK` | taking on an OLS regression of recent trades |

```bash
java -jar ~/.fm/fm-maker.jar -E 2540 -C ~/.fm/credential BUY AAPL 950
java -jar ~/.fm/fm-taker-mvo.jar -E 2540 0.5 AAPL 20 IBM 15 -P '[2 0 1; 0 1 2; 1 1 1]'
```

The MVO robots take a risk penalty for payoff variance and a `-P/--payoffs`
matrix of payoff states and probabilities. The OLS robots take a lookback in
transactions and accept `-r/--reverse` to trade against momentum rather than
with it.

**Hosted robots.** Four of these — `fm-maker`, `fm-taker`, `fm-maker-mvo`,
`fm-taker-mvo` — can also be run *inside* the platform, controlled by
participants from the marketplace screen instead of from a shell. A manager
enables them in the marketplace's agent configuration (the account needs the
`robots` feature). The choice is stored as keywords in the marketplace's
`configuration` field, which is why older material tells you to type `fm-maker`
into the description — that was the pre-configuration-field mechanism.
`fm-agent-stop-allowed` additionally lets participants stop a running robot.
The OLS robots are local-only; they have no in-app control surface.

**Study applications.** `applications/studies` holds full experiment runners
built on the same libraries — `fm-smith62` (Vernon Smith 1962 demand-supply),
`fm-capm`, `fm-credit-bubble`, `fm-lucas`, `fm-simple-dividend`, `fm-ssw`,
`fm-venture-capital`. Each is a multi-command CLI that generates a user list,
builds allotment and value files, runs the sessions and produces the analysis.
Start from `fm-smith62 --help`; the design of each study is in its README.

## The plugin SPI

`com.flexemarkets:fm-spi` is published to Maven Central from **this**
repository (`sdks/java/fm-spi`). It is the contract between a robot and whatever
host runs it — interfaces only, no implementation, no host internals. A host
discovers and runs a robot without compiling against it, and a robot ships as a
plugin jar without exposing its source.

```xml
<dependency>
    <groupId>com.flexemarkets</groupId>
    <artifactId>fm-spi</artifactId>
    <version>0.0.5</version>
</dependency>
```

Two interfaces:

```java
public interface Service {
    void start();   // connect, subscribe, schedule — must return promptly
    void stop();    // release everything, including transports and clients
}

public interface ServiceProvider {
    String  name();                    // unique catalog key, e.g. "fm-maker"
    Service create(String[] arguments); // CLI-style: -E <endpoint> -C <credential> …
    default String spiVersion() { return SPI_VERSION; }
}
```

A provider declares itself in `META-INF/services/fm.service.ServiceProvider`;
the host loads providers with `ServiceLoader`, over the classpath or over a
per-jar `URLClassLoader` when plugins are dropped in as external jars:

```java
Map<String, ServiceProvider> catalog = new HashMap<>();
for (ServiceProvider provider : ServiceLoader.load(ServiceProvider.class, loader)) {
    catalog.put(provider.name(), provider);
}
```

`create` does not start work — the host calls `start()` when it wants the robot
running and `stop()` when it doesn't. Implementations must release every
resource in `stop()`, network transports especially; a host cycles robots many
times over a session's life and a leaked client accumulates until the host runs
out of memory.

`SPI_VERSION` is currently `"0.0"`. A host accepts any provider whose **major**
matches its own: bump the minor for additive changes, the major for anything
either side must adapt to.

---

## See also

- [`API-REFERENCE.md`](API-REFERENCE.md) — the REST and WebSocket surface underneath all of this
- [fm-robots](https://github.com/adhocmarkets/fm-robots) — source, per-application READMEs, and `applications/manager/docs/SUBMIT-GENERATION.md`
