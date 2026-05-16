# fm-sdk-python

Python SDK for the [Flexemarkets](https://api.flexemarkets.com) API.

## Requirements

- Python 3.11+

## Install

```bash
python3.11 -m pip install .
```

Or install dependencies directly:

```bash
python3.11 -m pip install httpx websockets
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
from fm import Flexemarkets, OrderBooks, MarketplaceTrades

# Connect
fm = Flexemarkets.connect(
    credential="~/.fm/credential",
    endpoint="https://api.flexemarkets.com/api/marketplaces/123",
    client_description="my-bot",
)

# REST API
marketplace_id = fm.endpoint_marketplace_id
markets = fm.markets(marketplace_id)
session = fm.session(marketplace_id)
holdings = fm.holdings(marketplace_id)

# Submit orders
order = fm.submit_limit(marketplace_id, market_id, "BUY", units=1, price=950)
fm.submit_cancel(marketplace_id, market_id, order.id)

# WebSocket events
import queue

q = queue.Queue(maxsize=1000)
fm.listen(marketplace_id, q)

books = OrderBooks(markets)
trades = MarketplaceTrades(markets)

while True:
    event = q.get()
    match event:
        case list() as orders:
            books.update(orders)
            trades.update(orders)
        case Session() as s:
            print(s.state)
        case Holding() as h:
            print(h.cash)

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
python3.11 ticker.py -C ~/.fm/credential -E https://api.flexemarkets.com/api/marketplaces/123
```

Options:

| Flag | Description |
|------|-------------|
| `-C`, `--credential` | Credential file path or bearer token |
| `-E`, `--endpoint` | Marketplace endpoint file path or URL |

Output:

```
fm-ticker                                              OPEN

  Symbol     Bid     Ask  Spread   Last trades
  ------  ------  ------  ------   -----------
    AAPL  $ 9.50  $10.50  $ 1.00   $9.50  $10.00
     IBM  $ 4.00  $ 5.00  $ 1.00   $4.50
```

The display refreshes on each order book update. Press `Ctrl-C` to stop.
