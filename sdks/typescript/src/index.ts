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

// Order utils -- what can only be worked out from a set of orders together.
//
// isBuy, isSell, isCancel and isLimit are deliberately not here. They became
// one-line comparisons the moment side and type became enums: OrderSide.BUY ===
// order.side says the same thing as isBuy(order), reads the same way, and is
// one fewer name to know. Java deleted them outright; they survive here only
// because this SDK's own code uses them, where Java's did not.
//
// contra stays: OrderSide is a string union in TypeScript, so unlike Java and
// Python it cannot carry the method that replaced this.
export {
  contra,
  findOrder,
  isAvailable,
  isConsumed,
  isConsumedOrSplit,
  isResting,
  isSplit,
  isSubmit,
  isSupplier,
  isSymbol,
  limit,
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
  StreamReconnected,
  FrameUnreadable,
  FmEvent,
  EventCallback,
} from "./stomp.js";

// Desk
export type { Desk, Subscription, GapEvent, DeskRecovery } from "./desk.js";
export type { Snapshot } from "./snapshot.js";
export { NO_SEQ } from "./stomp.js";
export type { OrdersUpdate } from "./stomp.js";
