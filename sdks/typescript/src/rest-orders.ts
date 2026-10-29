/**
 * Orders over REST, several to a request (fm-sdk#7, docs/SUBMIT-RATE.md, section 5).
 *
 * What `OrderStream` is for the socket, for a client connected with
 * `orderRoute: "REST"`. One request is in flight at a time; orders sent while
 * it is out wait in a list, and go together as the next
 * `POST .../orders/batch` once it is answered. One at a time this is
 * `submitLimit`; in a burst the requests grow to {@link MOST_WAITING}.
 *
 * One request, not several: two lists in flight could reach the book out of
 * order, which is what this exists to prevent. fm-server runs each list in the
 * order given, so orders reach the exchange in the order sent.
 */

import { PendingOrder } from "./order-stream.js";
import type { Order } from "./types.js";

/**
 * The most orders that wait for the next request; a caller sending more waits
 * for room. The most one request carries, so in a burst every request is full.
 */
export const MOST_WAITING = 64;

/**
 * Posts a list of orders and answers each, in order, with the order it made or
 * the error its refusal throws. Sends once more, whole, when the answer is
 * lost; rejects when the request as a whole fails.
 */
export type BatchPost = (batch: Array<Record<string, unknown>>) => Promise<Array<Order | Error>>;

/** The client's errors, given rather than imported: client.ts imports this module. */
export interface RestOrderErrors {
  notSent(message: string): Error;
  outcomeUnknown(message: string, submissionId: string): Error;
  /** Whether a failure is a refusal of the request as a whole, before any of it ran: a 4xx. */
  refusedWhole(failure: unknown): boolean;
}

interface Waiting {
  order: Record<string, unknown>;
  pending: PendingOrder;
}

/** One marketplace's orders over REST: one request in flight, the rest waiting. */
export class RestOrderWindow {
  private _waiting: Waiting[] = [];
  private _room: Array<() => void> = [];
  private _sending = false;
  private _closed = false;

  constructor(
    private readonly _post: BatchPost,
    private readonly _errors: RestOrderErrors,
    private readonly _mostWaiting: number = MOST_WAITING,
  ) {}

  /** Queue `order` for the next request, waiting while the list is full. */
  async submit(order: Record<string, unknown>): Promise<PendingOrder> {
    while (!this._closed && this._waiting.length >= this._mostWaiting) {
      await new Promise<void>((resolve) => this._room.push(resolve));
    }
    if (this._closed) {
      throw this._errors.notSent("The client is closed; the order was not sent.");
    }
    const pending = new PendingOrder(order.submissionId as string);
    this._waiting.push({ order, pending });
    if (!this._sending) {
      this._sending = true;
      void this._send();
    }
    return pending;
  }

  /** Refuse what waits as not sent; a request in flight is answered as it lands. */
  close(): void {
    this._closed = true;
    const waiting = this._waiting;
    this._waiting = [];
    this._wake();
    for (const { pending } of waiting) {
      pending._answer(this._errors.notSent("The client closed before the order was sent."));
    }
  }

  /** Send what waits, a list at a time, until nothing does. */
  private async _send(): Promise<void> {
    while (this._waiting.length > 0) {
      const batch = this._waiting;
      this._waiting = [];
      this._wake();
      await this._answer(batch);
    }
    this._sending = false;
  }

  private async _answer(batch: Waiting[]): Promise<void> {
    let outcomes: Array<Order | Error>;
    try {
      outcomes = await this._post(batch.map((w) => w.order));
    } catch (failed) {
      // A 4xx is a refusal of the request as a whole -- the token, the route --
      // before any of it ran, so none was placed: each gets the refusal, as
      // submitLimit would have thrown it.
      if (this._errors.refusedWhole(failed)) {
        for (const { pending } of batch) pending._answer(failed as Error);
        return;
      }
      // Anything else -- sent twice without an answer, a 5xx, an answer that
      // cannot be read -- may come after some of the list was placed. Unknown,
      // never refused: a refusal invites placing it again, and if the first had
      // landed, holding the position twice.
      for (const { pending } of batch) {
        pending._answer(this._errors.outcomeUnknown(
          `No readable answer to the request carrying it: ${String(failed)}. Read the book to learn whether it stands.`,
          pending.submissionId));
      }
      return;
    }
    batch.forEach(({ pending }, i) => pending._answer(outcomes[i]));
  }

  private _wake(): void {
    const room = this._room;
    this._room = [];
    for (const resolve of room) resolve();
  }
}
