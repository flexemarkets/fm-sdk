/** Flexemarkets TypeScript SDK. */

// Types
export type {
  Person,
  Account,
  Token,
  Security,
  Holding,
  Market,
  Marketplace,
  Session,
  Order,
  ClientConnection,
  Version,
  ApiRoot,
  ConflictFailure,
} from "./types.js";

export {
  Side,
  OrderType,
  toSide,
  toOrderType,
  SESSION_STATE_INIT,
  SESSION_STATE_OPEN,
  SESSION_STATE_PAUSED,
  SESSION_STATE_CLOSED,
  getSecurity,
  orderedSecurities,
  holdingUnits,
  priceRound,
  unitRound,
  tickRound,
  getLink,
} from "./types.js";

// Client
export {
  Flexemarkets,
  FlexemarketsError,
  AuthenticationError,
  AuthorizationError,
  InvalidArgumentError,
  ConnectionFailedError,
  ConfigurationError,
  ConflictError,
  AccountNameConflictError,
  PersonHasMarketplaceDataError,
} from "./client.js";

export { toInstant } from "./timestamps.js";

// Order utils
export {
  isCancel,
  isLimit,
  isBuy,
  isSell,
  contra,
  isAvailable,
  isConsumed,
  isSplit,
  isSymbol,
  isSubmit,
  isResting,
  findOrder,
} from "./order-utils.js";

// Order book
export { OrderBook, OrderBooks } from "./orderbook.js";

// Trades
export { Trades, MarketplaceTrades } from "./trades.js";

// Events
export { EventListener } from "./stomp.js";
export type {
  StompFrame,
  WsTransportError,
  WsException,
  FmEvent,
  EventCallback,
} from "./stomp.js";

// MarketView
export { DefaultMarketView, MarketViewHandle } from "./market-view.js";
export type { MarketView, Subscription, GapEvent, ReconnectEvent } from "./market-view.js";
export type { Snapshot } from "./snapshot.js";
export { NO_SEQ } from "./stomp.js";
export type { OrdersUpdate } from "./stomp.js";
