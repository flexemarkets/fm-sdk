# Upgrading from fm-sdk 0.5.x to 0.6.x

## How to use this document

**0.6.0 removes nothing and changes no signature** in any of the three
SDKs. Two things answer differently: a Java client acting as another
account, and fm-spi's `CliBuilder` given a value outside a robot's bounds.
If neither applies to you, changing the version and rebuilding is the
whole upgrade.

Earlier releases are covered by their own upgrade notes, listed in the
[README](../README.md#upgrade-notes), and are not revisited here.

---

## What you need

**fm-server 4.6.3 or later**, as for 0.5.0 -- except to act as another
account from Java, which needs **fm-server 4.7 or later** (below).

---

## What answers differently

- **Java: acting as another account needs a password** (c337c4f).
  fm-server 4.7 refuses `X-FM-Account` unless `X-FM-Impersonation` carries
  a fifteen-minute token issued to the administrator by
  `POST /api/v1/impersonation`, with their password again. The client asks
  for that token at connect and renews it as it runs. So a client that
  impersonates must connect with a credential file holding account, email
  and password: given a token instead, connect throws
  `ConfigurationException` rather than leaving every call to answer 403. A
  wrong password, or a caller who is not an administrator, is refused at
  connect.
- **fm-spi 0.0.14: `CliBuilder` refuses a numeric value outside the robot's
  own bounds** (7829a88), with `InvalidLaunchException` naming the bounds.
  fm-server already refused such values; a container launched directly did
  not. Parameters with no bounds admit everything, as before.

---

## What is new

- **Python and TypeScript report each call's round trip on the next**, as
  `Client-Timing: rtt=<ns>` (1047887, 43fb10d), as Java already did. Nothing
  to do.
- **Java study views:** the Smith62 view shows each participant their own
  values or costs (f955908, 2ac29f1), and a new
  `ssw-sequestered-dividends` view shows dividends earned, paid at the end
  (833ec40, 0977966).
- **fm-expr** gains `min`, `max`, `round`, `exp`, `ln` and `sqrt` (cc6837c).
- **A first robot** for each SDK, in [FIRST-ROBOT.md](FIRST-ROBOT.md).

---

## Checking your code

```bash
# Java: a client that impersonates -- check it connects with a password
grep -rn "impersonateAccount" src/
```
