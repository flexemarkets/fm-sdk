# Panel expressions

The language a manager writes in a `score` panel: one expression, or a table of
named fields, evaluated per participant on the server. This page is the
language as shipped in `fm-expr` (`sdks/java/fm-expr`). The class Javadoc on
`fm.expr.Expression` and `fm.expr.Panel` is the authority; this page gathers it
with the names fm-server puts in scope, which live in fm-server's
`PanelEvaluator`, not here.

Restored 2026-11-10 from `fm-administration/docs/PANEL-EXPRESSIONS.md`, which
was removed on 2026-09-11 (`3e7d0fc`) along with the panels design docs it
duplicated. Its language section had not survived anywhere else. The old
draft's `%`, `and`/`or`/`not`, `pow`, `floor`, `ceil`, `dot`, `quad`, `at`,
`lag`, `fmt`, `money`, `session.*` and `row.*` were specified but never built,
so they are not here.

## At a glance

```
references   me.side  mkt.period  now.cash  now.units.WDG  open.cash  f.gross
literals     15   0.25   "SELLER"
arithmetic   + - * /   and unary -
comparison   == != < <= > >=
functions    if(cond, a, b)  abs(x)  min(a, b)  max(a, b)  round(x)
             exp(x)  ln(x)  sqrt(x)  sum(v)  take(v, n)
```

## Values

Four kinds, and no conversion between them:

| Kind | Written as | Comes from |
|---|---|---|
| number | `15`, `0.25` | holdings, prices, numeric state |
| text | `"SELLER"` (double quotes) | text state, such as `me.side` |
| boolean | — (no literal) | a comparison, or a true/false state field |
| vector | — (no literal) | `now.units`, `book.last`, a list in state |

A state field holding a list must be all numbers to become a vector; one that
is not is left out of scope.

## References

Every reference is namespaced. A bare name such as `side` is an error that
suggests `me.side`, `mkt.side` and `now.side`; there are no variables.

A reference the scope cannot resolve is an evaluation error, never a default.
A participant with no state row has no side, not a side of zero, and the panel
says so.

What fm-server puts in scope (`PanelEvaluator.values`):

| Name | What it is |
|---|---|
| `me.<field>` | this participant's own state. Never leaves the server. |
| `mkt.<field>` | the session's study values, shared by every participant. A manager sets them in Market values; a view must declare the name. |
| `now.cash`, `now.availableCash` | cash now, in cents |
| `now.units`, `now.available` | vectors, in market order |
| `now.units.<SYM>`, `now.available.<SYM>` | one market, by symbol |
| `now.pnl` | change in cash plus each market's change in units at its last trade, the figure the holdings panel shows |
| `now.pnlPartial` | true when `now.pnl` left out a market with no trade to price it |
| `open.cash`, `open.units`, `open.units.<SYM>` | the opening position. `initial.*` reads the same values. |
| `book.last.<SYM>`, `book.bid.<SYM>`, `book.ask.<SYM>` | prices in cents, present only where the market has one |
| `book.last`, `book.bid`, `book.ask` | vectors, present only when every market has that price |
| `market.symbol`, `market.units`, `market.available`, `market.initial` (or `market.open`), `market.last`, `market.bid`, `market.ask` | the one market a score under a market is evaluated for |
| `f.<id>` | an earlier field of the same panel |

## Operators

`+ - * /` and unary `-` take numbers only; there is no text joining and no
vector arithmetic. Precedence is the usual: unary minus, then `* /`, then
`+ -`, then comparison. Parentheses group.

Comparisons need both sides to be the same kind. Numbers compare by value, so
`1 == 1.0`. Text and booleans compare with `==` and `!=` only. Comparisons do
not chain: `a < b < c` is an error. `=` and `!` alone are errors that point at
`==`.

## Functions

| Function | What it does |
|---|---|
| `if(cond, a, b)` | `cond` must be a boolean. Only the arm taken is evaluated, so the other may reference what this participant lacks. |
| `abs(x)` | magnitude |
| `min(a, b)`, `max(a, b)` | exactly two numbers |
| `round(x)` | half up, to a whole number |
| `exp(x)`, `ln(x)`, `sqrt(x)` | double precision with `StrictMath`, kept to six places. A result that is not finite is an error. |
| `sum(v)` | total of a vector |
| `take(v, n)` | the first `n` entries, or all of `v` when it has fewer. `n` must be a whole number, zero or more. |

Each takes exactly the arguments shown; an unknown name or a wrong count is a
parse error.

## Numbers

Decimal throughout, never binary floating point. Addition, subtraction,
multiplication, `min`, `max` and `round` are exact. Division, `exp`, `ln` and
`sqrt` are carried at scale six, rounded half up. Whole cents in, whole cents
out, unless something divided or took a root, a log or a power of e. Division
by zero is an error.

## Fields

A `score` panel holds one expression (`expr`) or a list of fields (`fields`).
A field has an `id`, an expression, and may be hidden. A field may read an
earlier field as `f.<id>`, and only an earlier one, so the graph is acyclic by
construction. A hidden field is a named subexpression; a visible one is a row.

Smith 62's profit, where units traded appears three times:

```
n      hidden   abs(now.units.WDG - open.units.WDG)
gross  hidden   abs(now.cash - open.cash)
cost   hidden   sum(take(me.valuations, f.n))
total           if(me.side == "S", f.gross - f.cost, f.cost - f.gross)
```

Evaluation never throws. Each field becomes a value or an error, and a field
whose input failed fails with it: a participant never sees a blank where a
number should be, and never sees a number that is wrong.

The panel's other options (`label`, `format`, `as`, and `when: halted`, which
withholds the values while the session is open) are in fm-ui's
`docs/CUSTOMIZED-TRADING-VIEW.md`, under Score.

## Where it runs

Only on the server. fm-server's `PanelEvaluator` builds the scope from a
holding, a state row, the study values and the book, and sends participants
values only: never their state, and never the expression. Nothing is evaluated
in the browser.

`fm-expr` is a library rather than a class inside fm-server so that a study
can evaluate the same expressions offline, when a settlement is re-rendered,
and the live panel and the settlement are the same arithmetic by
construction. That is the intent; as of 2026-11-10 fm-server is its only
user, and no study in fm-robots depends on it yet.

`Expression.parse` runs once, when a configuration is saved or loaded; the
result is immutable and thread-safe. `references()` says what a scope must
provide, and decides when a panel recomputes: `me.*` and `mkt.*` on state,
`now.*` on holdings, and `book.*`, `now.pnl` and `market.last`, `.bid` and
`.ask` on prices.

A manager can check a view before saving it: `POST
/api/v1/marketplaces/{id}/panels/preview` evaluates a draft for one
participant, and `PUT /api/v1/marketplaces/{id}/config/fm.view?dryRun=true`
validates without saving.

## Deliberately absent

- **Loops.** A panel is a handful of fields over values whose length is data.
- **User functions.** Definitions would turn configuration into a program to
  debug.
- **Assignment.** `f.<id>` already shares an intermediate without scope rules.
- **Text joining.** `+` takes numbers only.
- **Randomness.** Assignment must be reproducible and recorded; a draw inside
  an expression is neither.
- **A clock.** Two evaluations would disagree. Time enters as declared state.
- **Other participants.** No reference reaches another person's state.

## Adding to it

The list is closed rather than current: a construct gets in when an existing
study or a written-down customer requirement needs it, and the requirement is
recorded beside it in `Expression`'s Javadoc. The last additions were `exp`,
`ln`, `min`, `max`, `round` and `sqrt`, for instructors scoring a
state-contingent payment without writing a robot (Utah's MVO study, Smith 62,
Cambridge; `CUSTOMER-VALUE-ROADMAP.md` item 47 in fm-administration).
