/** Flexemarkets TypeScript SDK. */

// Types
export type {
  Person,
  Account,
  Allotment,
  Assets,
  ManagerOtpBundle,
  Token,
  Security,
  Holding,
  Market,
  Marketplace,
  Session,
  Order,
  ClientConnection,
  TickGrid,
  Version,
  ConflictFailure,
} from "./types.js";

export {
  OrderSide,
  OrderType,
  toSide,
  toOrderType,
  SESSION_STATE_INIT,
  SESSION_STATE_OPEN,
  SESSION_STATE_PAUSED,
  SESSION_STATE_CLOSED,
  getSecurity,
  isApproved,
  orderedSecurities,
  holdingUnits,
  priceRound,
  unitRound,
  tickRound,
  gridRound,
  unitGrid,
  displayName,
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
  HttpError,
  ApiError,
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
  findOrder,
} from "./order-utils.js";

// Order book
export { Book } from "./orderbook.js";

// Tape
export { Tape, tradeOf } from "./trades.js";
export type { Trade } from "./trades.js";

// Events
export type {
  StompFrame,
  StreamDropped,
  Reconnected,
  FrameUnreadable,
  FmEvent,
  EventCallback,
} from "./stomp.js";

// Desk
export type { Desk, Subscription, GapEvent, ReconnectEvent } from "./desk.js";
export type { Snapshot } from "./snapshot.js";
export { NO_SEQ } from "./stomp.js";
export type { OrdersUpdate } from "./stomp.js";
