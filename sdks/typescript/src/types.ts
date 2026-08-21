/** Flexemarkets domain models. */

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
}

export interface Account {
  id: number | null;
  name: string | null;
  description: string | null;
  owner: Person | null;
  approval: boolean;
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

export interface Holding {
  marketplaceId: number;
  sessionId: number;
  allocationId: number;
  ownerId: number;
  name: string | null;
  cash: number;
  availableCash: number;
  securities: Security[];
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
}

export function priceRound(market: Market, price: number): number {
  return Math.min(
    Math.max(price - (price % market.priceTick), market.priceMinimum),
    market.priceMaximum,
  );
}

export interface Marketplace {
  id: number;
  name: string | null;
  description: string | null;
  markets: Market[];
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
export type Side = "BUY" | "SELL";

/** The members, for callers who prefer a name to a literal. */
export const Side = {
  BUY: "BUY",
  SELL: "SELL",
} as const satisfies Record<string, Side>;

/**
 * What an order is: a bid or offer, or the withdrawal of one.
 *
 * There is no `MARKET`. The server's type switch shares its default with
 * `LIMIT`, so a market order is a limit at the extreme legal price and
 * `submitMarket` sends `LIMIT`; naming a member the exchange does not have
 * would suggest otherwise.
 */
export type OrderType = "LIMIT" | "CANCEL";

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
export function toSide(value: string | null | undefined): Side | null {
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
  side: Side | null;
  units: number;
  price: number;
  ownerId: number | null;
  marketplaceId: number;
  sessionId: number;
  symbol: string | null;
  marketId: number;
  ownerTarget: string | null;
  clientDescription: string | null;
  createdDate: Date | null;
  lastModifiedDate: Date | null;
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

export interface ApiRoot {
  links: Record<string, string>;
}

export function getLink(root: ApiRoot, name: string): string | undefined {
  return root.links[name];
}

export interface ConflictFailure {
  status: string | null;
  error: string | null;
  message: string | null;
  path: string | null;
  suggestedName: string | null;
}
