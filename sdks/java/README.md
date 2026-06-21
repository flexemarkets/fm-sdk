# fm-sdk-java

Java SDK for the [Flexemarkets](https://api.flexemarkets.com) API.

## Requirements

- Java 25+ (the SDK uses preview features, so build and run with `--enable-preview`)

## Install

Maven:

```xml
<dependency>
    <groupId>com.flexemarkets</groupId>
    <artifactId>fm-sdk</artifactId>
    <version>0.0.4</version>
</dependency>
```

Gradle:

```kotlin
implementation("com.flexemarkets:fm-sdk:0.0.4")
```

## Configuration

The SDK loads credentials and endpoint from these sources (highest priority first):

1. Arguments passed to `Flexemarkets.connect()`
2. Files `~/.fm/credential` and `~/.fm/endpoint` (`.properties` format)
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

```java
import fm.Flexemarkets;
import fm.OrderBooks;
import fm.MarketplaceTrades;
import fm.Types.Holding;
import fm.Types.Market;
import fm.Types.Order;
import fm.Types.Session;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

// connect(null, null, ...) falls back to ~/.fm/credential and ~/.fm/endpoint
try (var fm = Flexemarkets.connect(null, null, "my-bot")) {

    // REST API
    long marketplaceId = fm.endpointMarketplaceId();
    List<Market> markets = fm.markets(marketplaceId);
    Session session = fm.session(marketplaceId);
    Holding holding = fm.holding(marketplaceId);

    // Submit orders
    Order order = fm.submitLimit(marketplaceId, markets.get(0).id(), "BUY", 1, 950);
    fm.submitCancel(marketplaceId, markets.get(0).id(), order.id());

    // WebSocket events
    BlockingQueue<Object> queue = new LinkedBlockingQueue<>(1000);
    fm.listen(marketplaceId, queue);

    var books = new OrderBooks(markets);
    var trades = new MarketplaceTrades(markets, 50);

    while (true) {
        Object event = queue.take();
        switch (event) {
            case Order[] orders -> { books.update(orders); trades.update(orders); }
            case Session s      -> System.out.println(s.state());
            case Holding h      -> System.out.println(h.cash());
            default             -> { }
        }
    }
}
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
