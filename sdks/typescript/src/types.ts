/** Flexemarkets domain models. */

export interface Person {
  id: number;
  accountId: number;
  firstName: string | null;
  lastName: string | null;
  email: string | null;
  roles: string[];
  accountOwner: boolean;
  createdDate: string | null;
  lastModifiedDate: string | null;
}

export interface Account {
  id: number | null;
  name: string | null;
  description: string | null;
  owner: Person | null;
  approval: boolean;
  approvalDescription: string | null;
  createdDate: string | null;
  lastModifiedDate: string | null;
}

export interface Token {
  requestUrl: string | null;
  person: Person | null;
  account: Account | null;
  token: string | null;
}

export interface Security {
  marketId: number;
  units: number;
  availableUnits: number;
  canBuy: boolean;
  canSell: boolean;
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

export function getSecurity(holding: Holding, marketId: number): Security {
  const s = holding.securities.find((s) => s.marketId === marketId);
  if (!s) throw new Error(`Security for market ID ${marketId} not found.`);
  return s;
}

export function holdingUnits(holding: Holding): number[] {
  return [...holding.securities]
    .sort((a, b) => a.marketId - b.marketId)
    .map((s) => s.units);
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
  openDate: string | null;
  closeDate: string | null;
}

export const ORDER_TYPE_LIMIT = "LIMIT";
export const ORDER_TYPE_CANCEL = "CANCEL";
export const ORDER_SIDE_BUY = "BUY";
export const ORDER_SIDE_SELL = "SELL";

export interface Order {
  id: number;
  original: number;
  supplier: number;
  consumer: number | null;
  type: string | null;
  side: string | null;
  units: number;
  price: number;
  ownerId: number | null;
  marketplaceId: number;
  sessionId: number;
  symbol: string | null;
  marketId: number;
  ownerTarget: string | null;
  clientDescription: string | null;
  createdDate: string | null;
  lastModifiedDate: string | null;
}

export interface ClientConnection {
  marketplaceId: number;
  connectionId: number;
  ownerId: number;
  established: string | null;
  terminated: string | null;
  description: string | null;
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
