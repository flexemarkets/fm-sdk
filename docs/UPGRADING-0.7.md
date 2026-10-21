# Upgrading from fm-sdk 0.6.x to 0.7.x

## How to use this document

0.7.0 adds one field to `Order`, in all three SDKs: `ownerTargetId`
(`owner_target_id` in Python), the counterparty of a private order by owner
id, which a session's order history (`GET .../orders?sessions=`) sends in
place of `ownerTarget`. Before, every private order read from a history had
no counterparty (3c10c62, 52bc4b9, 384ce18).

Reading orders, nothing changes. Code that **builds** an `Order` itself --
test fixtures, mostly -- may need one more argument:

| | What breaks | Fix |
|---|---|---|
| Java | `new Order(...)` and record patterns: `Order` is a record and gains a last component, `Long ownerTargetId` | pass `null` (or the id) last; add the component to a deconstruction pattern |
| TypeScript | an object literal typed `Order`: the interface gains a required `ownerTargetId: number \| null` | add `ownerTargetId: null` |
| Python | only a positional `created_date` / `last_modified_date`: the new field, defaulting to `None`, sits before them | pass those two by keyword |

What 0.6.0 settled is in [UPGRADING-0.6.md](UPGRADING-0.6.md) and is not
revisited here.

---

## What you need

The same server as 0.6.0 -- fm-server 4.6.2 or later, 4.7 or later to
impersonate from Java. `ownerTargetId` is null wherever the server does not
send it.

---

## Checking your code

```bash
# Java
grep -rn "new Order(" src/
# TypeScript
grep -rnE ": Order\b|as Order\b|<Order>" src/
```
