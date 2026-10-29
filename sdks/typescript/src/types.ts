/** Flexemarkets domain models. */

import { Decimals } from "./decimals.js";

export interface Person {
  id: number;
  accountId: number;
  firstName: string | null;
  lastName: string | null;
  email: string | null;
  roles: string[];
  accountOwner: boolean;
  createdDate: Date | null;
  lastModifiedDate: Date | null;
  lastSignedIn: Date | null;
  lastSignInMethod: string | null;
}

export interface Account {
  id: number | null;
  name: string | null;
  description: string | null;
  owner: Person | null;
  approval: boolean | null;
  approvalDescription: string | null;
  createdDate: Date | null;
  lastModifiedDate: Date | null;
}

export interface Token {
  requestUrl: string | null;
  person: Person | null;
  account: Account | null;
  token: string | null;
}

/**
 * A position in one market, and how far short the holder may go.
 *
 * `shortUnits` is the absolute floor: the position may not fall below
 * `-shortUnits`, so `availableUnits === units + shortUnits`. It arrives under
 * two names — fm-server's Asset emits `initialShortUnits` for a live session,
 * the allotments path emits `shortUnits` — and both are accepted. Requests
 * carry `shortUnits`.
 */
export interface Security {
  marketId: number;
  units: number;
  availableUnits: number;
  shortUnits: number;
  canBuy: boolean;
  canSell: boolean;
  /** The units the session opened with; null when the response did not say. Never sent. */
  initialUnits: number | null;
}

/** The capital half of an allotment: opening cash and opening positions. */
export interface Assets {
  id: number | null;
  name: string | null;
  cash: number;
  securities: Security[];
}

/**
 * A participant's opening position in an allocation.
 *
 * Distinct from a Holding: an allotment is what a session will START from,
 * so it has no session of its own and nothing has been committed against it.
 */
export interface Allotment {
  id: number | null;
  allocationId: number | null;
  marketplaceId: number | null;
  ownerId: number | null;
  name: string | null;
  assets: Assets | null;
}

/**
 * One participant's private study state for one allocation, as stored.
 *
 * Private means the participant never receives it: panels that read it are
 * evaluated on the server and only their rendered values travel. Keyed on
 * the allocation because it is uploaded before the session that consumes it
 * exists -- staged like an allotment, landing when a closed session opens,
 * which is also what scopes it to that session.
 *
 * `fields` is open: numbers, text, or arrays of numbers under the names the
 * upload declared.
 */
export interface ParticipantState {
  marketplaceId: number | null;
  allocationId: number | null;
  ownerId: number | null;
  ownerEmail: string | null;
  fields: Record<string, unknown>;
}

/**
 * Who a pushed widget is for: the whole marketplace, or one participant.
 *
 * `scope` is `"MARKETPLACE"` or `"USER"`; `userId` names the participant for a
 * `USER` widget. A user-scoped widget outranks a marketplace one of the same key
 * for that participant.
 */
export interface WidgetTarget {
  scope: string;
  userId?: number | null;
}

/**
 * One widget as a study pushes it: content for the panel naming `key`.
 *
 * A push to the same target and key replaces the last one. `content` is a
 * closed schema the server enforces -- `text`, `kv`, `table` or `log`, each a
 * `kind` plus its own fields, never markup -- and is passed through as an
 * object so the server's validator is the one place the rules live. An absent
 * `target` means the marketplace; `emphasis` is `normal` (when absent),
 * `strong` or `warn`; `ttlSeconds` blanks the panel that long after the push,
 * absent for never.
 */
export interface WidgetPush {
  target?: WidgetTarget | null;
  key: string;
  title?: string | null;
  emphasis?: string | null;
  ttlSeconds?: number | null;
  content: Record<string, unknown>;
}

/**
 * One pushed widget as the server stored it.
 *
 * Identity is the marketplace, scope, participant and key: a push to the same
 * four replaces what was there. Every widget in a marketplace is cleared when
 * its session closes. `lastModifiedDate` is the last push, which is what
 * `ttlSeconds` counts from.
 */
export interface Widget {
  id: number | null;
  marketplaceId: number | null;
  scope: string | null;
  userId: number | null;
  key: string | null;
  title: string | null;
  emphasis: string | null;
  ttlSeconds: number | null;
  content: Record<string, unknown>;
  createdDate: Date | null;
  lastModifiedDate: Date | null;
}

export interface Holding {
  marketplaceId: number;
  sessionId: number;
  allocationId: number;
  ownerId: number;
  name: string | null;
  cash: number;
  availableCash: number;
  securities: Security[];
  /** The cash the session opened with; null when the response did not say (fm-robots#472). */
  initialCash: number | null;
}

/**
 * The position in one market, or null if the holder has none.
 *
 * Was a throw. Having no position in a market is ordinary — it is what every
 * holding looks like before the first allocation — so asking is a question, not
 * a mistake, and an Error said the caller had erred when they had only asked.
 */
export function getSecurity(holding: Holding, marketId: number): Security | null {
  return holding.securities.find((s) => s.marketId === marketId) ?? null;
}

/**
 * Positions in market order, never null.
 *
 * Applied where a Holding is built, so `securities` is already ordered by the
 * time a caller sees it — reading it and reading `holdingUnits` cannot disagree,
 * and two holdings of the same positions compare the same way.
 */
export function orderedSecurities(securities: Security[] | null | undefined): Security[] {
  return [...(securities ?? [])].sort((a, b) => a.marketId - b.marketId);
}

/**
 * Approved, treating "not yet decided" as not approved.
 *
 * The third state is kept on the field and folded away here, at the point of
 * asking. Folding it in the parser would lose the difference between a pending
 * account and a suspended one for every caller at once.
 */
export function isApproved(account: Account): boolean {
  return account.approval === true;
}

export function holdingUnits(holding: Holding): number[] {
  return holding.securities.map((s) => s.units);
}

export interface Market {
  id: number;
  marketplaceId: number;
  name: string | null;
  description: string | null;
  symbol: string | null;
  privateMarket: boolean;
  priceMinimum: number;
  priceMaximum: number;
  priceTick: number;
  unitMinimum: number;
  unitMaximum: number;
  unitTick: number;
  /**
   * How it trades: {@link MARKET_MECHANISM_CONTINUOUS}, an order trading the
   * moment it crosses, or {@link MARKET_MECHANISM_CALL}, a call auction --
   * orders collect, and a call clears them all at one price. Continuous when
   * the server does not say.
   */
  mechanism: string;
  /** For a call market, the seconds between calls while the session is open, or null when only a manager calls it. */
  callIntervalSeconds: number | null;
  /** For a call market, whether what a call leaves unfilled waits for the next one. True when the server does not say. */
  callCarry: boolean;
  /**
   * The decimal places a price, the price tick and the price bounds carry: at
   * 2, a price of 5000 is 50.00. 2 when the server does not say;
   * `Decimals` converts.
   */
  priceDecimals: number;
  /**
   * The decimal places units, the unit tick and the unit bounds carry, and so
   * the grants and holdings of this market's asset: at 0 units are whole. 0
   * when the server does not say.
   */
  unitDecimals: number;
  /**
   * For a call market, what it shows of its book during the call:
   * {@link MARKET_CALL_BOOK_SEALED}, a participant's own orders only, or
   * {@link MARKET_CALL_BOOK_INDICATIVE}, also the price it would clear at now
   * and the volume ({@link CallIndicative}). Sealed when the server does not say.
   */
  callBook: string;
}

export const MARKET_MECHANISM_CONTINUOUS = "CONTINUOUS";
export const MARKET_MECHANISM_CALL = "CALL";
export const MARKET_CALL_BOOK_SEALED = "SEALED";
export const MARKET_CALL_BOOK_INDICATIVE = "INDICATIVE";

/**
 * Whether `market` is a call auction, where a crossing order rests until a
 * call rather than trading, and a participant sees only their own resting
 * orders.
 */
export function isCall(market: Market): boolean {
  return market.mechanism === MARKET_MECHANISM_CALL;
}

/**
 * Whether `market` is a call market that publishes the price its book would
 * clear at now: `indicative()` reads it, and the stream delivers each change
 * as a {@link CallIndicative}.
 */
export function showsIndicative(market: Market): boolean {
  return isCall(market) && market.callBook === MARKET_CALL_BOOK_INDICATIVE;
}

/**
 * One call of a call-auction market: when it was made, what made it, and
 * what it cleared.
 *
 * A call trades every crossing order in the market at one price: the price at
 * which the most units trade, then the one leaving the least imbalance, then
 * the midpoint of the range those span. Its trades arrive as orders, as every
 * trade does; this is the record of the call itself.
 */
export interface MarketCall {
  id: number;
  marketplaceId: number;
  marketId: number;
  sessionId: number;
  calledDate: Date | null;
  /** {@link MARKET_CALL_TRIGGER_MANUAL}, a manager's Clear now, or {@link MARKET_CALL_TRIGGER_INTERVAL}, the schedule. */
  trigger: string | null;
  /** The clearing price, or null when the book did not cross. */
  price: number | null;
  volume: number;
  /** Units bid at or above the price; every bid when nothing cleared. */
  demand: number;
  /** Units offered at or below the price; every ask when nothing cleared. */
  supply: number;
  buyOrders: number;
  sellOrders: number;
  /** Whether what it left unfilled waits for the next call. */
  carry: boolean;
}

export const MARKET_CALL_TRIGGER_MANUAL = "MANUAL";
export const MARKET_CALL_TRIGGER_INTERVAL = "INTERVAL";

/**
 * What an indicative call market's book would clear at if it were called
 * now: the price and volume a call would make, and nothing of the orders
 * behind them.
 *
 * The server works it out after each batch of orders that touched the
 * market, and after each call, and publishes it only when it changed. Two
 * values can arrive out of order, so keep the one with the highest `seq` for
 * the session: read it on subscribe, then apply only values with a higher
 * `seq`.
 */
/**
 * A marketplace's current session, as `GET /marketplaces/current-sessions`
 * lists it: which session is current and its state, without the rest of the
 * session.
 */
export interface CurrentSession {
  marketplaceId: number;
  /** Its current session. */
  sessionId: number;
  /** That session's state: OPEN, PAUSED or CLOSED. */
  state: string;
}

export interface CallIndicative {
  marketplaceId: number;
  marketId: number;
  /** The session whose book it is. */
  sessionId: number;
  /** The price the book would clear at, or null when nothing crosses. */
  price: number | null;
  /** Units that would trade there; 0 when nothing crosses. */
  volume: number;
  /** Rises with each change in the market; 0 for a value read before any was published. */
  seq: number;
  /** When it was worked out. */
  at: Date | null;
}

/**
 * The legal values for one dimension of a market: a range, and a step.
 *
 * A market has two of these, and the server enforces both the same way — a
 * value must lie within the bounds and satisfy `(value - minimum) % tick`.
 * Naming the pair is what stops `createMarket` taking six adjacent numbers,
 * where transposing the price tick and the unit minimum would post cleanly and
 * produce a market nobody could trade in.
 *
 * A `tick` of zero marks a fixed dimension: the bounds are equal and there is
 * one legal value.
 */
export interface TickGrid {
  minimum: number;
  maximum: number;
  tick: number;
}

/**
 * The usual unit dimension: whole units, one to a hundred, counted in steps of
 * `10^-unitDecimals`.
 *
 * Whole units, not smallest steps: at three unit decimals this is 1000 to
 * 100000 by 1000 -- the one to a hundred units a person means -- rather than
 * 0.001 to 0.100. At the default of 0 it is 1 to 100 by 1, what `createMarket`
 * used before it took a unit grid. Throws a `RangeError` for negative
 * `unitDecimals`, or one whose grid is beyond a safe integer.
 */
export function unitGrid(unitDecimals: number = Decimals.DEFAULT_UNIT_DECIMALS): TickGrid {
  if (!Number.isInteger(unitDecimals) || unitDecimals < 0) {
    throw new RangeError(`unitDecimals must be a non-negative integer, got ${unitDecimals}`);
  }
  const whole = 10 ** unitDecimals;
  if (!Number.isSafeInteger(whole * 100)) {
    throw new RangeError(`unitDecimals is too large: ${unitDecimals}`);
  }
  return { minimum: whole, maximum: whole * 100, tick: whole };
}

/** `value` moved down onto `grid`, clamped to it. */
export function gridRound(grid: TickGrid, value: number): number {
  return tickRound(value, grid.minimum, grid.maximum, grid.tick);
}

/**
 * A value moved down onto a bounded tick grid.
 *
 * The server applies this rule twice, to price and to units, spelling it the
 * same way both times: a value must lie within its bounds and satisfy
 * `(value - minimum) % tick`. So the grid is anchored at the *minimum*, not at
 * zero — this used to subtract `value % tick`, right whenever the floor happens
 * to be a multiple of the tick and wrong for the rest in a way that yields a
 * plausible number rather than an error.
 *
 * The ceiling is the highest legal tick rather than `maximum` itself, and a
 * tick of zero marks a fixed dimension — which used to return NaN.
 */
export function tickRound(value: number, minimum: number, maximum: number, tick: number): number {
  if (tick <= 0) return Math.min(Math.max(value, minimum), maximum);

  const highest = minimum + Math.floor((maximum - minimum) / tick) * tick;
  const rounded = minimum + Math.floor((value - minimum) / tick) * tick;
  return Math.min(Math.max(rounded, minimum), highest);
}

/** `price` moved down to the nearest price this market will accept. */
export function priceRound(market: Market, price: number): number {
  return tickRound(price, market.priceMinimum, market.priceMaximum, market.priceTick);
}

/**
 * `units` moved down to the nearest size this market will accept.
 *
 * The counterpart to {@link priceRound}, which did not exist even though the
 * server checks units on exactly the same terms.
 */
export function unitRound(market: Market, units: number): number {
  return tickRound(units, market.unitMinimum, market.unitMaximum, market.unitTick);
}


export interface Marketplace {
  id: number;
  name: string | null;
  description: string | null;
  markets: Market[];
  /**
   * The decimal places cash, available cash, starting capital and payoffs
   * carry: at 2, cash of 5000 is 50.00. 2 when the server does not say.
   */
  cashDecimals: number;
}

export const SESSION_STATE_INIT = "INIT";
export const SESSION_STATE_OPEN = "OPEN";
export const SESSION_STATE_PAUSED = "PAUSED";
export const SESSION_STATE_CLOSED = "CLOSED";

export interface Session {
  marketplaceId: number;
  allocationId: number;
  id: number;
  original: number;
  state: string | null;
  name: string | null;
  description: string | null;
  openDate: Date | null;
  closeDate: Date | null;
  createdDate: Date | null;
  lastModifiedDate: Date | null;
}

/**
 * Which way an order goes.
 *
 * A literal union rather than a TypeScript `enum`: the values stay ordinary
 * strings, so they serialise with no encoder, compare equal to the wire
 * spelling, and a caller who was passing `"BUY"` keeps working. A TS `enum`
 * would be a nominal type and would break every one of those.
 *
 * The invalid case still cannot be written -- `"BYU"` is a type error -- which
 * is what the old `ORDER_SIDE_BUY` constant could only suggest.
 */
export type OrderSide = "BUY" | "SELL";

/** The members, for callers who prefer a name to a literal. */
export const OrderSide = {
  BUY: "BUY",
  SELL: "SELL",
} as const satisfies Record<string, OrderSide>;

/**
 * What an order is: a bid or offer, or the withdrawal of one.
 *
 * There is no `MARKET`. The server's type switch shares its default with
 * `LIMIT`, so a market order is a limit at the extreme legal price and
 * `submitMarket` sends `LIMIT`; naming a member the exchange does not have
 * would suggest otherwise.
 */
export type OrderType = "LIMIT" | "CANCEL";

/**
 * How `sendLimit` and `sendCancel` send a client's orders, chosen once, when it
 * connects. `"SOCKET"`, the default: each order goes out on the marketplace's
 * order socket at once, as a STOMP SEND, up to 64 unanswered. `"REST"`: orders
 * go as `POST .../orders/batch`, one request in flight; those sent while it is
 * out wait, and go together as the next. For a caller that cannot hold a
 * socket open. One route for the client's life, so its orders never take two
 * routes and overtake each other on the way (docs/SUBMIT-RATE.md, section 5).
 */
export type OrderRoute = "SOCKET" | "REST";

export const OrderType = {
  LIMIT: "LIMIT",
  CANCEL: "CANCEL",
} as const satisfies Record<string, OrderType>;

/**
 * The side a response names, or null if it names none or one this version does
 * not know.
 *
 * Deliberately lenient, and case-insensitive as the old comparisons were.
 * Refusing an unrecognised value would cost the caller a whole response -- an
 * order list, a holdings snapshot -- over one field they may not read.
 */
export function toSide(value: string | null | undefined): OrderSide | null {
  const upper = value?.trim().toUpperCase();
  return upper === "BUY" || upper === "SELL" ? upper : null;
}

/** Lenient for the same reason; the server has emitted `"MARKET"`. */
export function toOrderType(value: string | null | undefined): OrderType | null {
  const upper = value?.trim().toUpperCase();
  return upper === "LIMIT" || upper === "CANCEL" ? upper : null;
}

export interface Order {
  id: number;
  original: number;
  supplier: number;
  consumer: number | null;
  type: OrderType | null;
  side: OrderSide | null;
  units: number;
  price: number;
  ownerId: number | null;
  marketplaceId: number;
  sessionId: number;
  symbol: string | null;
  marketId: number;
  ownerTarget: string | null;
  clientDescription: string | null;
  /**
   * A private order's counterparty, by owner id: what a session's order
   * history carries in place of `ownerTarget`. Null for an untargeted order,
   * and wherever the server does not send it.
   */
  ownerTargetId: number | null;
  createdDate: Date | null;
  lastModifiedDate: Date | null;
  /**
   * Whether the order is the reader's own, as the server judged it for this
   * connection -- not derivable from ownerId when a manager views as a
   * participant. Null when the response did not say.
   */
  mine: boolean | null;
  /** The session the order was first placed in, when carried into a later one; null when not said. */
  originalSessionId: number | null;
  /**
   * The id the submitting client named the order with, as fm-server keeps it
   * on the order: what ties an order arriving on the stream to the one a
   * client sent over the socket. Null for an order sent without one.
   */
  submissionId: string | null;
}

/**
 * A limit order to place: `submitLimit`'s arguments, as one value, for
 * `submitLimits` to take several of. The marketplace is the call's, and the
 * submission id is made when it is sent.
 */
export interface Limit {
  marketId: number;
  side: string;
  units: number;
  price: number;
  ownerTarget?: string;
  ownerTargetId?: number;
  clientDescription?: string;
}

/**
 * The best human label a person record carries: the trimmed full name, or the
 * email when there is no name.
 *
 * Every consumer of `users()` was writing this, because none of the three
 * fields it reads can be relied on alone — `firstName` and `lastName` are each
 * optional, and a person with neither still has to appear somewhere in a
 * report.
 *
 * Deliberately not `"Name <email>"`: that is a presentation choice, and a
 * caller who wants it can compose one from this and `email`.
 *
 * A function rather than a method because `Person` is a plain wire interface
 * here — Java puts this on the record and Python on the dataclass, which is
 * each language's own idiom for the same thing.
 *
 * @returns the full name, else the email, else null — and null only for a
 *   record carrying neither, which fm-server does not produce since email is
 *   the account key.
 */
export function displayName(person: Person): string | null {
  const name = `${(person.firstName ?? "").trim()} ${(person.lastName ?? "").trim()}`.trim();
  if (name) return name;
  const email = (person.email ?? "").trim();
  return email ? email : null;
}

export interface ClientConnection {
  marketplaceId: number;
  connectionId: number;
  ownerId: number;
  established: Date | null;
  terminated: Date | null;
  description: string | null;
  /**
   * The session the connection was attached during, or null when the
   * marketplace has never opened one. How a study works out who was present in
   * a finished run — which is not the same question as who is attached now, and
   * was silently unanswerable before 0.0.11.
   */
  sessionId: number | null;
}

export interface ManagerOtpEntry {
  userId: number;
  email: string | null;
  otp: string | null;
}

/**
 * One-time passcodes a manager mints on behalf of their users.
 *
 * Credentials, and short-lived: `expiresAt` is when the whole bundle stops
 * working, not a per-entry deadline. This is how a classroom signs in without
 * passwords being handed around, which is also why nothing here should be
 * logged.
 */
export interface ManagerOtpBundle {
  expiresAt: Date | null;
  otps: ManagerOtpEntry[];
}

/** The server's answer to an approval request. */
export interface Approval {
  account: Account | null;
  description: string | null;
  approve: boolean | null;
}

export interface Version {
  version: number;
}

export interface ConflictFailure {
  status: string | null;
  error: string | null;
  message: string | null;
  path: string | null;
  suggestedName: string | null;
}

/**
 * One parameter a robot declares.
 *
 * `min` and `max` are the robot's hard bounds -- what is physically valid, not
 * what suits a study -- and null when it declares none. A manager's range must
 * sit inside them. `defaultValue` is what the robot runs on unless configured;
 * the wire spells it `default`.
 */
export interface RobotParameter {
  name: string;
  label: string | null;
  description: string | null;
  type: string | null;
  valueType: string | null;
  required: boolean;
  defaultValue: string | null;
  unit: string | null;
  min: number | null;
  max: number | null;
}

/**
 * A robot's parameters, grouped by who may set them.
 *
 * A `platform` parameter -- a credential, an endpoint -- is never a manager's
 * to configure, and the server refuses one. A group the manifest leaves out is
 * empty.
 */
export interface RobotParameters {
  manager: RobotParameter[];
  user: RobotParameter[];
  platform: RobotParameter[];
}

/** What a robot declares about itself: its id, version and parameters. */
export interface RobotManifest {
  id: string;
  version: string | null;
  name: string | null;
  description: string | null;
  parameters: RobotParameters;
}

/**
 * The range a manager exposes to participants for one robot parameter.
 *
 * Both ends are inclusive. `step` is how the participant's control moves; the
 * server refuses a launch value outside the range, not one off the step.
 */
export interface RobotParameterControl {
  min: number;
  max: number;
  step: number;
}

/**
 * How a manager has disposed of one robot parameter in one marketplace.
 *
 * `value` is always present. A null `control` means the value is fixed and the
 * participant's launch form shows it read-only; `exposed` is true exactly when
 * there is a control.
 */
export interface RobotParameterConfig {
  marketplaceId: number | null;
  robotId: string;
  paramName: string;
  value: string;
  control: RobotParameterControl | null;
  exposed: boolean;
}

/**
 * One robot the marketplace's robot host offers, as the marketplace sees it.
 *
 * `configuration` holds only the parameters a manager has disposed of; a
 * parameter with no entry runs on the manifest's default and is not shown to
 * participants.
 */
export interface MarketplaceRobot {
  manifest: RobotManifest;
  enabled: boolean;
  configuration: RobotParameterConfig[];
}
