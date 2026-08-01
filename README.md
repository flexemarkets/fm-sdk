# fm-sdk

Client SDKs for the [Flexemarkets](https://api.flexemarkets.com) trading API — Python, Java, and TypeScript — plus an MCP server that exposes the API as tools for LLMs.

## Packages

| Language | Package | Registry |
|----------|---------|----------|
| Python | `fm-sdk` | [PyPI](https://pypi.org/project/fm-sdk/) |
| TypeScript / JavaScript | `@flexemarkets/fm-sdk` | [npm](https://www.npmjs.com/package/@flexemarkets/fm-sdk) |
| Java | `com.flexemarkets:fm-sdk` | [Maven Central](https://central.sonatype.com/artifact/com.flexemarkets/fm-sdk) |

## Documentation

| Document | Covers |
|----------|--------|
| [docs/API-REFERENCE.md](docs/API-REFERENCE.md) | The REST and WebSocket (STOMP) surface the SDKs wrap — auth, OTP, error bodies, endpoints, the snapshot/sequence contract |
| [docs/FM-ROBOTS.md](docs/FM-ROBOTS.md) | The `fm-manager` CLI, the robot agents, and the `fm-spi` plugin contract |

Per-language quickstarts are in each SDK's README, linked from [Usage](#usage)
below.

## Install

**Python**

```bash
pip install fm-sdk
```

**TypeScript / JavaScript**

```bash
npm install @flexemarkets/fm-sdk
```

**Java (Maven)**

```xml
<dependency>
    <groupId>com.flexemarkets</groupId>
    <artifactId>fm-sdk</artifactId>
    <version><!--see latests version --></version>
</dependency>
```

## Configuration

All SDKs share the same configuration mechanism:

1. **Default config files** — `~/.fm/credential` and `~/.fm/endpoint` (Java `.properties` format)
2. **Environment variable** — `FM_API_URL` overrides the endpoint
3. **Connect arguments** — pass `credential` / `endpoint` directly to `connect()`

### Credential file (`~/.fm/credential`)

```properties
account=myaccount
email=user@example.com
password=secret
```

Or with a bearer token:

```properties
token=eyJhbGciOiJIUzI1NiJ9...
```

### Endpoint file (`~/.fm/endpoint`)

```properties
endpoint=https://api.flexemarkets.com/api/marketplaces/123
```

## Authentication & on-behalf (OTP)

The client authenticates with the account/email/password (or bearer `token`) above and sends a JWT on every request.

To drive **many participants at once** — running robots or replaying a session — a **manager** mints short-lived one-time passwords rather than collecting each participant's password:

1. `POST /api/otp/manager` (requires the **MANAGER** role) with a list of user IDs returns a bundle of per-user OTPs, valid for about five minutes.
2. `GET /api/otp?otp=…` redeems each OTP for that user's session, so a tool can submit and cancel orders as that participant. Each OTP is single-use.

Impersonation is scoped to the manager's own account, and no participant password is ever exposed. The platform's in-app **Authentication & OTP** guide (`/documentation/AUTH-AND-OTP`) covers the concept for non-developers.

## Usage

Each SDK exposes the same `Flexemarkets` client. Passing `null`/`None` for the
credential and endpoint falls back to `~/.fm/credential` and `~/.fm/endpoint`.

### Python

```python
from fm import Flexemarkets

with Flexemarkets.connect(None, None, "my-bot") as fm:
    marketplace_id = fm.endpoint_marketplace_id
    markets = fm.markets(marketplace_id)
    order = fm.submit_limit(marketplace_id, markets[0].id, "BUY", 1, 950)
```

See [sdks/python/README.md](sdks/python/README.md) for full documentation.

### Java

```java
import fm.Flexemarkets;

// connect(null, null, ...) falls back to ~/.fm/credential and ~/.fm/endpoint
try (var fm = Flexemarkets.connect(null, null, "my-bot")) {
    var marketplaceId = fm.endpointMarketplaceId();
    var markets = fm.markets(marketplaceId);
    var order = fm.submitLimit(marketplaceId, markets.get(0).id(), "BUY", 1, 950);
}
```

Requires Java 25+ (the SDK uses preview features).

See [sdks/java/README.md](sdks/java/README.md) for full documentation.

### TypeScript

```typescript
import { Flexemarkets } from "@flexemarkets/fm-sdk";

const fm = await Flexemarkets.connect(null, null, "my-bot");
const marketplaceId = fm.endpointMarketplaceId;
const markets = await fm.markets(marketplaceId);
const order = await fm.submitLimit(marketplaceId, markets[0].id, "BUY", 1, 950);
```

See [sdks/typescript/README.md](sdks/typescript/README.md) for full documentation.

## Ticker

Each SDK ships a ticker example — a live terminal display of order book state
and recent trades.

```
fm-ticker  https://api.flexemarkets.com/api/marketplaces/123
                                                          OPEN

  Symbol     Bid     Ask  Spread   Last trades
  ------  ------  ------  ------   -----------
    AAPL  $ 9.50  $10.50  $ 1.00   $9.50  $10.00
     IBM  $ 4.00  $ 5.00  $ 1.00   $4.50
```

## MCP server

The MCP server exposes the FM API as tools for LLMs:

- **Read-only** — `list_marketplaces`, `get_marketplace`, `get_session`, `get_holding`, `list_orders`, `get_trades`
- **Trading** — `submit_limit_order`, `cancel_order`

Configure it via the `FM_CREDENTIAL` and `FM_ENDPOINT` environment variables, or
the standard `~/.fm/` config files.
