# fm-sdk-typescript

TypeScript / JavaScript SDK for the [Flexemarkets](https://api.flexemarkets.com) API.

## Requirements

- Node.js 22+ (ES modules)

## Install

```bash
npm install @flexemarkets/fm-sdk
```

## Configuration

The SDK loads credentials and endpoint from these sources (highest priority first):

1. Arguments passed to `Flexemarkets.connect()`
2. Files `~/.fm/credential` and `~/.fm/endpoint` (Java `.properties` format)
3. Environment variable `FM_API_URL` (defaults to `https://api.flexemarkets.com`)

### Credential file

Create `~/.fm/credential`:

```properties
account=myaccount
email=user@example.com
password=secret
```

Or use a bearer token:

```properties
token=eyJhbGciOiJIUzI1NiJ9...
```

### Endpoint file

Create `~/.fm/endpoint`:

```properties
endpoint=https://api.flexemarkets.com/api/marketplaces/123
```

## SDK usage

```typescript
import { Flexemarkets, OrderBooks, MarketplaceTrades } from "@flexemarkets/fm-sdk";
import type { FmEvent } from "@flexemarkets/fm-sdk";

// connect(null, null, ...) falls back to ~/.fm/credential and ~/.fm/endpoint
const fm = await Flexemarkets.connect(null, null, "my-bot");

// REST API
const marketplaceId = fm.endpointMarketplaceId;
const markets = await fm.markets(marketplaceId);
const session = await fm.session(marketplaceId);
const holding = await fm.holding(marketplaceId);

// Submit orders
const order = await fm.submitLimit(marketplaceId, markets[0].id, "BUY", 1, 950);
await fm.submitCancel(marketplaceId, markets[0].id, order.id);

// WebSocket events
const books = new OrderBooks(markets);
const trades = new MarketplaceTrades(markets);

await fm.listen(marketplaceId, (event: FmEvent) => {
  if ("kind" in event && event.kind === "orders-update") {
    books.update(event.orders);
    trades.update(event.orders);
  } else if (!Array.isArray(event) && "state" in event) {
    console.log(event.state); // Session
  } else if ("cash" in event) {
    console.log(event.cash); // Holding
  }
});

// when done
fm.close();
```

## Example: ticker

The SDK includes a ticker example — a live terminal display of order book best
bid/ask, spread, and recent trade prices.

```
fm-ticker                                              OPEN

  Symbol     Bid     Ask  Spread   Last trades
  ------  ------  ------  ------   -----------
    AAPL  $ 9.50  $10.50  $ 1.00   $9.50  $10.00
     IBM  $ 4.00  $ 5.00  $ 1.00   $4.50
```

The display refreshes on each order book update. Press `Ctrl-C` to stop.
