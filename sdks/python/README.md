# fm-sdk-python

Python SDK for the [Flexemarkets](https://api.flexemarkets.com) API.

## Requirements

- Python 3.11+

## Install

```bash
pip install fm-sdk
```

## Configuration

The SDK loads credentials and endpoint from these sources (highest priority first):

1. Arguments passed to `Flexemarkets.connect()`
2. Files `~/.fm/credential` and `~/.fm/endpoint` (Java `.properties` format)
3. Environment variable `FM_API_URL` (defaults to `https://api.flexemarkets.com`)

### Credential file

Create `~/.fm/credential`:

```
account=myaccount
email=user@example.com
password=secret
```

Or use a bearer token:

```
token=eyJhbGciOiJIUzI1NiJ9...
```

### Endpoint file

Create `~/.fm/endpoint`:

```
endpoint=https://api.flexemarkets.com/api/marketplaces/123
```

## SDK usage

```python
from fm import Flexemarkets

# Connect
fm = Flexemarkets.connect(
    credential="~/.fm/credential",
    endpoint="https://api.flexemarkets.com/api/marketplaces/123",
    client_description="my-bot",
)

# REST API
marketplace_id = fm.endpoint_marketplace_id
markets = fm.markets(marketplace_id)
market_id = markets[0].id
session = fm.session(marketplace_id)
holdings = fm.holdings(marketplace_id)

# Submit orders
order = fm.submit_limit(marketplace_id, market_id, "BUY", units=1, price=950)
fm.submit_cancel(marketplace_id, market_id, order.id)

# Live market data: a desk keeps the books and tapes for you -- it seeds
# them over REST, applies deltas, and re-seeds after a sequence gap.
with fm.desk(marketplace_id) as desk:
    desk.on_book_change(market_id, lambda book: print(book.best_price("BUY")))
    desk.on_session_change(lambda session: print(session.state))
    desk.on_holding_change(lambda holding: print(holding.cash))

    one = desk.book(market_id)       # one market
    for book in desk.books():        # every market in the marketplace
        print(book.symbol, book.best_price("BUY"))

fm.close()
```

The client also works as a context manager:

```python
with Flexemarkets.connect(credential, endpoint, "my-bot") as fm:
    ...
```

## Applications

### ticker

Live terminal display of order book best bid/ask, spread, and recent trade prices.

```bash
python3.11 ticker.py -C ~/.fm/credential -E 123
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
