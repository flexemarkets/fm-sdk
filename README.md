# fm-sdk

SDK monorepo for the [Flexemarkets](https://api.flexemarkets.com) trading API. Provides client libraries in Python, Java, and TypeScript, plus an MCP server for LLM integration.

## Structure

```
fm-sdk/
├── sdks/
│   ├── python/        Python SDK (httpx + websockets)
│   ├── java/          Java SDK (java.net.http + Jackson)
│   └── typescript/    TypeScript SDK (fetch + ws)
├── mcp-server/        MCP server exposing FM API as tools
├── Makefile           Build, check, and run targets
└── VERSION            Centralized version (0.0.4)
```

## Prerequisites

- Python 3.11+
- Java 25+ (with preview features)
- Node.js 22+
- Maven 3.9+

## Quick start

```bash
make install    # install all dependencies
make check      # verify everything compiles
```

## Configuration

All SDKs share the same configuration mechanism:

1. **Default config files** — `~/.fm/credential` and `~/.fm/endpoint` (Java `.properties` format)
2. **Environment variable** — `FM_API_URL` overrides the endpoint
3. **CLI arguments** — `-C credential -E endpoint`

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

## SDKs

### Python

Install the published package:

```bash
pip install fm-sdk==0.0.4
```

Or, to work in this repo:

```bash
make install-python
```

```python
from fm import Flexemarkets

# credential=None, endpoint=None fall back to ~/.fm/credential and ~/.fm/endpoint
with Flexemarkets.connect(None, None, "my-bot") as fm:
    marketplace_id = fm.endpoint_marketplace_id
    markets = fm.markets(marketplace_id)
    order = fm.submit_limit(marketplace_id, markets[0].id, "BUY", 1, 950)
```

See [sdks/python/README.md](sdks/python/README.md) for full documentation.

### Java

Add the dependency (Maven Central):

```xml
<dependency>
    <groupId>com.flexemarkets</groupId>
    <artifactId>fm-sdk</artifactId>
    <version>0.0.4</version>
</dependency>
```

Or, to work in this repo:

```bash
make install-java
```

```java
import fm.Flexemarkets;

// ... inside a method that throws IOException

// connect(null, null, ...) falls back to ~/.fm/credential and ~/.fm/endpoint
try (var fm = Flexemarkets.connect(null, null, "my-bot")) {
    var marketplaceId = fm.endpointMarketplaceId();
    var markets = fm.markets(marketplaceId);
    var order = fm.submitLimit(marketplaceId, markets.get(0).id(), "BUY", 1, 950);
}
```

### TypeScript

Install the published package:

```bash
npm install @flexemarkets/fm-sdk@0.0.4
```

Or, to work in this repo:

```bash
make install-typescript
```

```typescript
import { Flexemarkets } from "@flexemarkets/fm-sdk";

// connect(null, null, ...) falls back to ~/.fm/credential and ~/.fm/endpoint
const fm = await Flexemarkets.connect(null, null, "my-bot");
const marketplaceId = fm.endpointMarketplaceId;
const markets = await fm.markets(marketplaceId);
const order = await fm.submitLimit(marketplaceId, markets[0].id, "BUY", 1, 950);
```

## Ticker

Each SDK includes a ticker example — a live terminal display of order book state and recent trades.

```
fm-ticker  https://api.flexemarkets.com/api/marketplaces/123
                                                          OPEN

  Symbol     Bid     Ask  Spread   Last trades
  ------  ------  ------  ------   -----------
    AAPL  $ 9.50  $10.50  $ 1.00   $9.50  $10.00
     IBM  $ 4.00  $ 5.00  $ 1.00   $4.50
```

Run with:

```bash
make ticker-python     ARGS="-C ~/.fm/credential -E endpoint-url"
make ticker-java       ARGS="-C ~/.fm/credential -E endpoint-url"
make ticker-typescript  ARGS="-C ~/.fm/credential -E endpoint-url"
```

## MCP server

The MCP server exposes the FM API as tools for LLMs. It uses the Python SDK and provides:

- **Read-only** — `list_marketplaces`, `get_marketplace`, `get_session`, `get_holding`, `list_orders`, `get_trades`
- **Trading** — `submit_limit_order`, `cancel_order`

```bash
make install-mcp
make mcp-server
```

Configure via environment variables `FM_CREDENTIAL` and `FM_ENDPOINT`, or the standard `~/.fm/` config files.

## Version management

The version is centralized in the `VERSION` file. To update all components:

```bash
make set-version V=0.1.0
```

## Makefile targets

| Target | Description |
|--------|-------------|
| `make install` | Install dependencies for all components |
| `make build` | Compile all components |
| `make check` | Type-check / verify all components |
| `make clean` | Remove build artifacts |
| `make set-version V=x.y.z` | Update version everywhere |
| `make ticker-{python,java,typescript}` | Run ticker example |
| `make mcp-server` | Start MCP server |
