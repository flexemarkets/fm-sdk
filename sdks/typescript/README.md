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
import { Flexemarkets } from "@flexemarkets/fm-sdk";

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

// Live market data. A desk keeps the books and tapes for you: it seeds them
// over REST, applies deltas, and re-seeds after a sequence gap.
const desk = await fm.desk(marketplaceId);
const marketId = markets[0].id;

desk.onBookChange(marketId, (book) => console.log(book.bestPrice("BUY")));
desk.onSessionChange((s) => console.log(s.state));
desk.onHoldingChange((h) => console.log(h.cash));

const one = desk.book(marketId);          // one market
for (const book of desk.books()) {        // every market in the marketplace
  console.log(book.symbol, book.bestPrice("BUY"));
}

desk.close();
```

## Example: ticker

The SDK includes a ticker example — a live terminal display of order book best
bid/ask, spread, and recent trade prices.

```bash
npx tsx src/ticker.ts -C ~/.fm/credential -E 123
```

Options:

| Flag | Description |
|------|-------------|
| `-C`, `--credential` | Credential file path or bearer token |
| `-E`, `--endpoint` | Marketplace id, endpoint file path, or URL |

Output:

```
fm-ticker                                              OPEN

  Symbol     Bid     Ask  Spread   Last trades
  ------  ------  ------  ------   -----------
    AAPL  $ 9.50  $10.50  $ 1.00   $9.50  $10.00
     IBM  $ 4.00  $ 5.00  $ 1.00   $4.50
```

The display refreshes on each order book update. Press `Ctrl-C` to stop.
