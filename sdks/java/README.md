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
    <version>0.2.0-dev0</version><!-- fm-readme-version -->
</dependency>
```

Gradle:

```kotlin
implementation("com.flexemarkets:fm-sdk:0.2.0-dev0") // fm-readme-version
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
import fm.model.Holding;
import fm.model.Market;
import fm.model.Order;
import fm.model.OrderSide;
import fm.model.Session;

import java.util.List;

// connect(null, null, ...) falls back to ~/.fm/credential and ~/.fm/endpoint
try (var fm = Flexemarkets.connect(null, null, "my-bot")) {

    // REST API
    long marketplaceId = fm.endpointMarketplaceId();
    List<Market> markets = fm.markets(marketplaceId);
    Session session = fm.session(marketplaceId);
    Holding holding = fm.holding(marketplaceId);

    // Submit orders
    Order order = fm.submitLimit(marketplaceId, markets.get(0).id(), OrderSide.BUY, 1, 950);
    fm.submitCancel(marketplaceId, markets.get(0).id(), order.id());

    // Live market data. A desk keeps the books and tapes for you: it seeds
    // them over REST, applies deltas, and re-seeds after a sequence gap.
    try (var desk = fm.desk(marketplaceId)) {
        long marketId = markets.get(0).id();

        desk.onBookChange(marketId, book -> System.out.println(book.bestPrice(OrderSide.BUY)));
        desk.onSessionChange(s -> System.out.println(s.state()));
        desk.onHoldingChange(h -> System.out.println(h.cash()));

        var one = desk.book(marketId);            // one market
        for (var book : desk.books()) {           // every market in the marketplace
            System.out.println(book.symbol() + " " + book.bestPrice(OrderSide.BUY));
        }
    }
}
```

## Running an experiment

Trading in a marketplace is one thing; running one is another. These need
manager (or admin) credentials — the server answers 401/403 otherwise.

```java
import fm.Flexemarkets;
import fm.Holding;
import fm.Person;
import java.nio.file.Path;
import java.util.List;

try (var fm = Flexemarkets.connect(null, null, "my-study")) {
    long marketplaceId = fm.endpointMarketplaceId();

    // Who is in the account, so positions can be assigned to real people.
    List<Person> users = fm.users();

    // Stage the opening positions — either built here…
    fm.allocate(marketplaceId, List.of(/* Holding per participant */));
    // …or loaded from a holdings CSV.
    fm.uploadHoldings(marketplaceId, Path.of("holdings.csv"));

    // An allocation LANDS when a CLOSED session is opened. Pausing and
    // re-opening does not consume it, so close first if one is running.
    fm.closeSession(marketplaceId);
    fm.openSession(marketplaceId);

    // ... the run happens ...

    fm.closeSession(marketplaceId);

    // Collect: the server's own CSV, verbatim.
    String csv = fm.downloadHoldings(marketplaceId);
}
```

`allotments(marketplaceId, allocationId)` reads back the opening positions of a
particular allocation.

These methods are `default` on `Flexemarkets` and throw
`UnsupportedOperationException`, so an implementation that only trades — a test
fake, a read-only provider — stays valid without stubbing them.

## Example: ticker

The SDK includes a ticker example — a live terminal display of order book best
bid/ask, spread, and recent trade prices.

```bash
java -jar fm-ticker-0.1.1.jar -C ~/.fm/credential -E 123
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
