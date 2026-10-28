/**
 * Orders over the socket, several in flight (fm-sdk#7, docs/SUBMIT-RATE.md).
 *
 * A REST order waits for its answer before the next can go, so a robot's order
 * rate is one over the round trip. fm-server keeps the orders one socket sends
 * in the order it sent them (its SocketOrderSequence), so a client may send the
 * next before the last is answered and still have them matched in sequence.
 * That is what this does: each order goes out as a STOMP SEND at once, and its
 * answer comes back on the stream -- the ORDERS-UPDATE fan-out for an order
 * placed, ORDER-REJECTED for one refused, ORDER-ACCEPTED for a resend of one
 * already placed -- matched to it by the submission id it was sent with.
 *
 * As Python's order_stream and Java's OrderStream.
 */

import type { EventCallback, FmEvent } from "./stomp.js";
import type { Order } from "./types.js";

/**
 * How many orders may be unanswered at once before {@link OrderStream.submit}
 * waits for one. Bounds what a burst puts in front of the server and on the
 * user's 4 MB per-session send buffer, where the answers and the marketplace's
 * fan-out queue together. A first guess; fm-server's W3 load run should set it.
 */
export const MAX_IN_FLIGHT = 64;

/**
 * An order sent over the socket, not yet answered.
 *
 * `result()` resolves to the {@link Order} fm-server made, or rejects with
 * what REST would have thrown for its refusal -- or `OrderOutcomeUnknownError`
 * if the stream closed before the answer came.
 */
export class PendingOrder {
  readonly submissionId: string;
  private readonly _result: Promise<Order>;
  private _settle!: (outcome: Order | Error) => void;
  private _done = false;

  constructor(submissionId: string) {
    this.submissionId = submissionId;
    this._result = new Promise<Order>((resolve, reject) => {
      this._settle = (outcome) => (outcome instanceof Error ? reject(outcome) : resolve(outcome));
    });
    // Handled here as well as by whoever awaits it: an order refused that
    // nobody asked about must not end the process as an unhandled rejection.
    this._result.catch(() => {});
  }

  /** The order fm-server made, once it has answered. */
  result(): Promise<Order> {
    return this._result;
  }

  /** Whether the answer has come. */
  get done(): boolean {
    return this._done;
  }

  /** @internal */
  _answer(outcome: Order | Error): void {
    this._done = true;
    this._settle(outcome);
  }
}

/**
 * The longest an order socket waits between attempts to come back: five
 * seconds, where a subscription waits up to a minute. Orders are waiting on it,
 * and every second it waits is a second they sit unsent -- an outage ran
 * 24-33 s past the server's return on the minute's back-off. It still doubles
 * from 2 s and is drawn from the upper half, so a classroom's robots do not come
 * back in step; and an order socket asks for no history, which is what the
 * minute spares the server.
 */
export const RECONNECT_MAX_MS = 5_000;

/**
 * The longest an order waits, unsent, for the order socket to come back. Long
 * enough for a reconnect after a blip (the socket retries within
 * {@link RECONNECT_MAX_MS}); after that the order is refused as not sent, as
 * REST refused it with a 503 during an outage. Held for the whole outage
 * instead, a robot's quotes went onto the book up to a minute and a half after
 * it sent them -- stale, and on top of the ones it sent again once it saw the
 * stream come back.
 */
export const HOLD_MS = 5_000;

/** What an order stream needs of its event listener. */
export interface OrderListener {
  send(destination: string, body: string): void;
  close(): void;
  readonly closed: boolean;
}

/** The errors an order stream fails its orders with; the client's own classes. */
export interface OrderStreamErrors {
  /** The order was not sent: the stream closed first. */
  notSent(message: string): Error;
  /** The order was sent and no answer can now come. */
  outcomeUnknown(message: string, submissionId: string): Error;
}

interface Unanswered {
  pending: PendingOrder;
  body: string;
  /** Whether a send of it was attempted; one that never was cannot have been placed. */
  sent: boolean;
  /** While it waits, unsent, for the socket: the timer that refuses it at the hold's end. */
  hold?: NodeJS.Timeout;
}

/**
 * One marketplace's order socket: sends, matches answers, resends.
 *
 * While the socket is down an order is held rather than sent another way, and
 * every unanswered order is sent again, in the order first submitted, once it
 * is back. Sending the held ones over REST meanwhile would let them overtake
 * orders sent before the drop, and in-order is the point. An order that has
 * not gone out at all is held for at most {@link HOLD_MS}, then refused as not
 * sent; one sent before the drop may have been placed, and is sent again
 * whenever the socket returns.
 */
export class OrderStream {
  private readonly _destination: string;
  private readonly _errors: OrderStreamErrors;
  private readonly _maxInFlight: number;
  private readonly _holdMs: number;
  /** In submission order: a Map iterates in insertion order. */
  private readonly _unanswered = new Map<string, Unanswered>();
  /** Callers waiting for a place in the window, first come first served. */
  private readonly _waiting: Array<() => void> = [];
  private _inFlight = 0;
  private _listener!: OrderListener;
  private _up = true;
  private _closed = false;

  private constructor(marketplaceId: number, errors: OrderStreamErrors, maxInFlight: number, holdMs: number) {
    this._destination = `/app/marketplaces/${marketplaceId}/orders`;
    this._errors = errors;
    this._maxInFlight = maxInFlight;
    this._holdMs = holdMs;
  }

  /** Open the stream: `connect` starts a listener delivering to the callback it is given. */
  static async open(
    connect: (callback: EventCallback) => Promise<OrderListener>,
    marketplaceId: number,
    errors: OrderStreamErrors,
    maxInFlight = MAX_IN_FLIGHT,
    holdMs = HOLD_MS,
  ): Promise<OrderStream> {
    const stream = new OrderStream(marketplaceId, errors, maxInFlight, holdMs);
    stream._listener = await connect((event) => stream._onEvent(event));
    return stream;
  }

  /**
   * Send `order`, which names its `submissionId`, without waiting for the answer.
   *
   * Waits only while {@link MAX_IN_FLIGHT} orders are unanswered, and then in
   * turn, so orders go out in the order this was called. Rejects with the
   * client's `ConnectionFailedError` once the stream is closed.
   */
  async submit(order: Record<string, unknown>): Promise<PendingOrder> {
    await this._acquire();
    if (this._closed) {
      this._release();
      throw this._errors.notSent("The order stream is closed; the order was not sent.");
    }
    const pending = new PendingOrder(order.submissionId as string);
    const entry: Unanswered = { pending, body: JSON.stringify(order), sent: false };
    this._unanswered.set(pending.submissionId, entry);
    if (this._up) this._send(entry);
    else this._hold(entry);
    return pending;
  }

  /**
   * Stop, and fail every unanswered order: one that was sent with
   * `OrderOutcomeUnknownError`, one that was not with `ConnectionFailedError`.
   */
  close(cause?: Error): void {
    if (this._closed) return;
    this._closed = true;
    const left = [...this._unanswered.values()];
    this._unanswered.clear();
    this._listener.close();
    for (const entry of left) {
      const id = entry.pending.submissionId;
      const error = entry.sent
        ? this._errors.outcomeUnknown(
            `Order ${id} was sent and its stream closed before the answer; it may have been placed.`, id)
        : this._errors.notSent(`Order ${id} was not sent: its stream closed first.`);
      if (cause !== undefined) error.cause = cause;
      this._answer(entry, error);
    }
  }

  // -- the window ------------------------------------------------------------

  private _acquire(): Promise<void> {
    if (this._inFlight < this._maxInFlight && this._waiting.length === 0) {
      this._inFlight += 1;
      return Promise.resolve();
    }
    return new Promise((resolve) => this._waiting.push(resolve));
  }

  /** A place freed: handed to the next in line, or back to the window. */
  private _release(): void {
    const next = this._waiting.shift();
    if (next !== undefined) next();
    else this._inFlight -= 1;
  }

  // -- events ----------------------------------------------------------------

  /** Send one. A failure leaves it held for the reconnect. */
  /** Hold an order the socket cannot send now, until it can or the hold ends. */
  private _hold(entry: Unanswered): void {
    const id = entry.pending.submissionId;
    entry.hold = setTimeout(() => {
      if (this._unanswered.get(id) !== entry) return;
      this._unanswered.delete(id);
      this._answer(entry, this._errors.notSent(
        `Order ${id} was not sent: the order socket was down for more than ${this._holdMs / 1000} s.`));
    }, this._holdMs);
    entry.hold.unref?.();
  }

  private _send(entry: Unanswered): void {
    clearTimeout(entry.hold);
    entry.hold = undefined;
    entry.sent = true;
    try {
      this._listener.send(this._destination, entry.body);
    } catch {
      this._up = false;
    }
  }

  private _onEvent(event: FmEvent): void {
    if (!("kind" in event)) return;
    switch (event.kind) {
      case "orders-update":
        for (const order of event.orders) {
          if (order.submissionId !== null) this._answerById(order.submissionId, order);
        }
        return;
      case "order-accepted":
        if (event.submissionId !== null) this._answerById(event.submissionId, event.order);
        return;
      case "order-rejected":
        if (event.submissionId !== null) this._answerById(event.submissionId, event.error);
        return;
      case "stream-dropped":
        // The listener has given up: its token was refused twice.
        if (this._listener.closed) this.close(event.exception);
        else this._up = false;
        return;
      case "reconnected":
        this._up = true;
        for (const entry of this._unanswered.values()) {
          if (!this._up) break;
          this._send(entry);
        }
        return;
      default:
        return;
    }
  }

  private _answerById(submissionId: string, outcome: Order | Error): void {
    const entry = this._unanswered.get(submissionId);
    if (entry === undefined) return;
    this._unanswered.delete(submissionId);
    this._answer(entry, outcome);
  }

  private _answer(entry: Unanswered, outcome: Order | Error): void {
    clearTimeout(entry.hold);
    entry.pending._answer(outcome);
    this._release();
  }
}
