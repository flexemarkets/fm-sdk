# Upgrading from fm-sdk 0.7.x to 0.8.x

## How to use this document

0.8.0 is additive. It adds three things: a sign-in with a refresh token,
which renews as the client runs, in all three SDKs (fm-server#1047; c5c77d9,
139d3d7, 94f4df6); the person's last sign-in time and method on `Person`, in
all three (#4; 2b2cf7e, 3897372, fe8a75b); and a step in the Java study SPI
that carries one period of a study into the next, study contract 0.1 in
fm-spi 0.0.15 (f1fd044). Code that only **calls** the SDK and reads what it
returns needs no changes.

Code that **builds** a `Person` itself, mostly test fixtures, may need a
change:

| | What breaks | Fix |
|---|---|---|
| Java | record patterns over `Person`: the record gains two last components, `Instant lastSignedIn` and `String lastSignInMethod` | add them to the pattern; the nine-argument constructor is kept, so `new Person(...)` compiles unchanged |
| TypeScript | an object literal typed `Person`: the interface gains required `lastSignedIn: Date \| null` and `lastSignInMethod: string \| null` | add `lastSignedIn: null, lastSignInMethod: null` |
| Python | nothing: `last_signed_in` and `last_sign_in_method` come last and default to `None` | |

A Java `StudyProvider` needs nothing either. The new methods are defaults,
and a study that plans every period at setup runs as it did. See
[If you implement a study](#if-you-implement-a-study).

Earlier releases are covered by their own upgrade notes, listed in the
[README](../README.md#upgrade-notes), and are not revisited here.

---

## What is new

**A client can sign in with a refresh token.** Pass the credential
`refresh:<token>`, or put a `refresh-token=<token>` line in a credential
file. The client signs in with `POST /api/tokens/renew`
(`{"refreshToken": ...}`). When the access token runs out, it renews the
same way, so it keeps running for as long as the refresh token lasts. Before,
a client given an access token stopped when that token expired, because it
held no password to sign in again with.

fm-server accepts each refresh token once. Each renewal returns the next
one, and the SDK presents that one at the next renewal. Signing in is itself
a renewal, so a client uses up the refresh token it was given as it connects.
If a refresh token is presented twice, fm-server ends the whole family: every
refresh token renewed from the same sign-in, the newest one included.
Access tokens already issued work until they expire. The SDK keeps the newest
refresh token in memory only, never writes it back to the credential file, and
has no way to hand it to you. So:

- give each client its own refresh token, and never share one between two
  processes;
- never start a client -- a second one, or the same one restarted -- from a
  `refresh:` credential or file that has already been used.

The SDK's own password sign-in does not ask for a refresh token. Your client
gets one from fm-server. A robot that fm-server 4.13 or later launches is
handed `refresh:<token>` as its credential (`-C`), and this is the case the
change is built for.

**`Person` says when and how someone last signed in.** In Java and
TypeScript the new fields are `lastSignedIn` and `lastSignInMethod`; in
Python they are `last_signed_in` and `last_sign_in_method`. The time is an
`Instant` in Java, a `Date` in TypeScript and a `datetime` in Python. The
method is spelled as the server spells it: `PASSWORD`, `GOOGLE` or `OTP`.
Both are null (`None`) for someone who has never signed in, and the method
alone is null for a sign-in recorded before fm-server recorded methods.

---

## What changes in behaviour

**A credential that starts with `refresh:` is now a refresh token**, and is
checked before the credential is tried as a file path. Before, such a string
was refused unless a file had that name. A non-blank `refresh-token=` line in
a credential file is used ahead of an `account`/`email`/`password` in the same
file.

**A `refresh-token=` line in `~/.fm/credential` reaches every client.** The
SDK reads that default file first and lays the credential you pass over it,
so its refresh token outlives an access token you pass, or a credential file
without that line, and is the one the client signs in with. Every client on
the machine then presents the same refresh token, which ends its family at
the second sign-in (above). Keep refresh tokens out of `~/.fm/credential`:
put each in a file of its own and pass that file.

---

## If you implement a study

`StudyProvider.STUDY_SPI_VERSION` is `"0.1"` (it was `"0.0"`), in
`com.flexemarkets:fm-spi:0.0.15`. A host accepts a provider whose major
version matches its own, so the major is still 0 and a 0.0 study and a 0.1
host accept each other.

Contract 0.1 adds two default methods and a record, all for a multi-period
study whose next allotment comes from the last period's close, such as a
dividend drawn and paid or a short allowance carried forward:

- `Carry carry(parameters, rotation, holdings, carried)`. The host calls
  this when a manager carries a closed period that has a next one in the same
  marketplace (below), at most once a period. It receives every participant's holding at close, and
  `carried`, which is what the previous `carry` in that marketplace kept,
  or null before the first. The default throws
  `UnsupportedOperationException` ("plans every period at setup and carries
  nothing").
- `Carry(holdings, state, carried)`. `holdings` is the next period's
  holdings CSV and is required (a blank value throws
  `IllegalArgumentException`). `state` is its private-state CSV, or null.
  Both replace the files the plan gave that rotation. `carried` is the
  study's own state between periods. Only the study reads it: the host keeps
  it on the run and passes it back.
- `Settlement settle(parameters, rotation, holdings, carried)`. The host
  calls this overload. Its default calls the three-argument `settle`, so a
  study that overrides only the three-argument `settle` settles as it did
  before. Override the four-argument `settle` when a payout depends on what
  the periods carried.

The period is `rotation.index()`; no separate argument carries it.

fm-server 4.13.0 is the first host that calls these methods. It carries a
period on `PATCH /api/v1/studies/runs/{id}/rotations/{n}` with
`{"state":"CARRIED"}`, once that period's session has closed. Older servers
call neither `carry` nor the four-argument `settle`. Against them, a study
that overrides only the four-argument `settle` has no settlement, and it
gets no `carry` call between periods.

---

## What you need

The same server as 0.7.0, fm-server 4.6.2 or later (4.7 or later to
impersonate from Java), unless you use the new parts:

- a `refresh:` credential needs **fm-server 4.7.0 or later** for
  `/api/tokens/renew`. Java cannot impersonate with one: `impersonate-account`
  needs a password, and is refused with a `ConfigurationException`;
- carry and the four-argument `settle` need **fm-server 4.13.0 or later**
  as the study host.

`lastSignedIn` and `lastSignInMethod` are read when the server sends them
and are null when it does not.

---

## Checking your code

```bash
# Java: record patterns over Person
grep -rnE "Person\(" src/ | grep -E "instanceof|case "
# TypeScript: literals typed as Person
grep -rnE ": Person\b|as Person\b|<Person>" src/
# Credentials or credential files that might already start with refresh: or carry refresh-token=
grep -rnE "refresh:|refresh-token=" src/ ~/.fm/ 2>/dev/null
```
