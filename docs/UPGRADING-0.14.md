# Upgrading from fm-sdk 0.13.x to 0.14.x

## How to use this document

0.14.0 follows fm-server 4.17, the release that made the V1 API consistent,
and **needs it**: two calls moved to its routes, and a create resent to an
older server makes a second one. Read [What you need](#what-you-need) before
anything else.

Otherwise it is additive. Code that only **calls** the SDK needs nothing; the
paged reads are new, and the changes in behaviour below are ones a caller
does not see. Code that **implements** the SDK's types needs nothing either:

| | What breaks | Fix |
|---|---|---|
| Java | nothing: the paged reads on `Flexemarkets` are default methods that page the whole read, so a class implementing it -- a test double, fm-server's in-process client -- compiles and pages as before | override them to page at the source, if it can |
| TypeScript | nothing: the paged reads are methods of the `Flexemarkets` class, which a fake extends | |
| Python | nothing | |

Earlier releases are covered by their own upgrade notes, listed in the
[README](../README.md#upgrade-notes), and are not revisited here.

---

## What is new

**Growing collections can be read a page at a time.** fm-server 4.17 pages
every collection that grows with use: `?limit=N` answers the `N` newest by
id, `?before={id}` the ones older than that, and
`Link: <...?before={id}>; rel="next"` says older ones remain. Seven reads
have a paged form:

| Whole | A page |
|---|---|
| `marketplaces()` | `marketplacesPage(limit[, before])` |
| `sessions(marketplaceId)` | `sessionsPage(marketplaceId, limit[, before])` |
| `orders(marketplaceId)` | `ordersPage(marketplaceId, limit[, before])` -- cancellations left out, as `orders` leaves them |
| `users()` | `usersPage(limit[, before])` |
| `connections(marketplaceId)` | `connectionsPage(marketplaceId, limit[, before])` |
| `accounts()` | `accountsPage(limit[, before])` |
| `calls(marketplaceId)` | `callsPage(marketplaceId, limit[, before])` |

Python spells them `sessions_page` and so on. Each answers a `Page`: `rows`,
in the order the whole read answers them, and `next`, the `before` that reads
the older rows -- `null` (`None`) on the last page, which is the page the
server sent no `Link` with, even when it is full. Do not work `next` out from
the rows:

```java
for (var page = fm.sessionsPage(id, 100); ; page = fm.sessionsPage(id, 100, page.next())) {
    page.rows().forEach(this::report);
    if (!page.hasNext()) break;
}
```

`Page.of(rows, id, limit, before)` cuts a whole list the way the server cuts
one, for a fake that holds whole lists to page with.

---

## What changes in behaviour

**Every create sends an `Idempotency-Key`, and is sent once more if its
answer was lost.** Creating a marketplace, a market, a call market or a user,
allocating, uploading holdings, pushing widgets and clearing a call market
now carry a fresh key. When the send fails on the connection -- it may or may
not have landed -- the SDK sends it again under the same key, and fm-server
4.17 answers with what it made the first time. Before, the failure reached
the caller, who would create it again. Orders already did this with their
`submissionId` (0.13.0).

**`clearNow` posts to the marketplace's calls**, `POST
/marketplaces/{id}/calls` with `{"marketId": ...}`, where it posted to the
market's. **`calls(marketplaceId, sessionId)` asks with `?sessions=`**, the
name every V1 list uses for runs, where it sent `?sessionId=`.

---

## What you need

**fm-server 4.17.0 or later.** Against an older server:

- `clearNow` fails: the marketplace's `calls` take no `POST` before 4.17;
- `calls(marketplaceId, sessionId)` answers every session's calls, because an
  older server does not read `?sessions=` there;
- a resent create makes a second marketplace, market or user, because an
  older server ignores the `Idempotency-Key`;
- `ordersPage` is refused with 400, because an older server takes `?limit=`
  on orders only with `?state=TRADED`; the other paged reads answer the whole
  list, as one page with no `next`, because an older server ignores `?limit=`
  there.

---

## Checking your code

```bash
# Callers of the two calls whose route moved
grep -rnE "clearNow|clear_now|\.calls\(" src/
# Whole-list reads worth paging, where a list grows with use
grep -rnE "\.(sessions|orders|users|connections|accounts|calls)\(" src/
```
