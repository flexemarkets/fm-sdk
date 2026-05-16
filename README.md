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
└── VERSION            Centralized version (0.0.1)
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

1. **Config files** — `~/.fm/credential` and `~/.fm/endpoint` (Java `.properties` format)
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

```bash
make install-python
```

```python
from fm import Flexemarkets, OrderBooks, MarketplaceTrades

with Flexemarkets.connect(credential, endpoint, "my-bot") as fm:
    markets = fm.markets(fm.endpoint_marketplace_id)
    order = fm.submit_limit(marketplace_id, market_id, "BUY", units=1, price=950)
```

See [sdks/python/README.md](sdks/python/README.md) for full documentation.

### Java

```bash
make install-java
```

```java
try (var fm = Flexemarkets.connect(credential, endpoint, "my-bot")) {
    var markets = fm.markets(fm.endpointMarketplaceId());
    var order = fm.submitLimit(marketplaceId, marketId, "BUY", 1, 950);
}
```

### TypeScript

```bash
make install-typescript
```

```typescript
import { Flexemarkets } from "fm-sdk";

const fm = await Flexemarkets.connect(credential, endpoint, "my-bot");
const markets = await fm.markets(fm.endpointMarketplaceId);
const order = await fm.submitLimit(marketplaceId, marketId, "BUY", 1, 950);
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
