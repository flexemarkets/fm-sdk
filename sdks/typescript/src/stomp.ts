/**
 * WebSocket STOMP event listener for Flexemarkets.
 *
 * Implements STOMP 1.2 framing over WebSocket, matching the Python
 * EventListener / Java StompListener behaviour.
 */

import WebSocket from "ws";
import { toInstant } from "./timestamps.js";
import type { CallIndicative, Holding, MarketCall, Order, Session, Version } from "./types.js";

const HEARTBEAT_MS = 30_000;

/** What this client advertises in CONNECT, and must therefore keep to. */
export const ADVERTISED_HEARTBEAT_MS = HEARTBEAT_MS;

/**
 * How often this client actually sends one. Under the 30s it advertises, so a
 * late timer still lands inside the window it promised, and well under the 55s
 * at which Heroku's router closes an idle connection and records an H15
 * against the app. Named so the interval can be tested against the thing it
 * exists to stay under.
 */
export const HEARTBEAT_INTERVAL_MS = 25_000;
export const HEROKU_IDLE_TIMEOUT_MS = 55_000;
const NULL = "\x00";

// Reconnecting after a drop. The first attempt is at once; each that fails
// waits longer, doubling from 2 s to a minute, and each wait is drawn from the
// upper half of its range, so clients dropped together -- every robot in a
// classroom when fm-server restarts -- do not all come back in the same
// instant. A fixed 2 s brought them back in step, every one asking for its
// session's history at once.
//
// A connection that falls within a minute of opening counts as an attempt that
// failed, not a recovery. fm-server closes a session whose history overflows
// its send buffer; a client that reconnected at once, asked for the same
// history and was closed again did so every 2 s for as long as it ran.
export const RECONNECT_FIRST_PAUSE_MS = 2_000;
export const RECONNECT_MAX_PAUSE_MS = 60_000;
export const STABLE_CONNECTION_MS = 60_000;

/** The wait before the next attempt, after `failures` in a row (one or more). */
export function reconnectPause(
  failures: number,
  draw: (lo: number, hi: number) => number = (lo, hi) => lo + Math.random() * (hi - lo),
): number {
  const ceiling = Math.min(RECONNECT_MAX_PAUSE_MS, RECONNECT_FIRST_PAUSE_MS * 2 ** Math.min(failures - 1, 16));
  return draw(ceiling / 2, ceiling);
}

/**
 * Prefix on the `/app` SUBSCRIBE destination selecting fm-server's WS
 * API version. Empty string → V0 (`/app/marketplaces/{id}`); `"/v1"` →
 * V1 (`/app/v1/marketplaces/{id}`). V1 omits the bulk ORDERS-UPDATE
 * snapshot on subscribe, keeping inbound frames small. V1 is the
 * default; override with `FM_WS_API_VERSION=v0` if talking to an old
 * fm-server that doesn't speak V1.
 *
 * NB: V1 SUBSCRIBE delivers an empty ORDERS-UPDATE; consumers that
 * need the active book at startup should fetch it via REST
 * (`GET /api/v1/marketplaces/{id}/orders?state=ACTIVE`) and reconcile
 * against incoming deltas using the `seq` header.
 */
const API_VERSION_PREFIX = ((): string => {
  const v = (process.env.FM_WS_API_VERSION ?? "v1").trim().toLowerCase();
  if (v === "v0") return "";
  if (v === "v1") return "/v1";
  throw new Error(`FM_WS_API_VERSION must be 'v0' or 'v1', got: ${v}`);
})();

// ---------------------------------------------------------------------------
// STOMP frame codec
// ---------------------------------------------------------------------------

export interface StompFrame {
  command: string;
  headers: Record<string, string>;
  body: string;
}

export function encodeFrame(frame: StompFrame): string {
  const lines = [frame.command];
  for (const [k, v] of Object.entries(frame.headers)) {
    lines.push(`${k}:${v}`);
  }
  lines.push("");
  lines.push(frame.body);
  return lines.join("\n") + NULL;
}

export function decodeFrame(raw: string): StompFrame {
  // Strip trailing NUL and whitespace
  raw = raw.replace(/\x00+$/, "");

  // Split command + headers from body at the first blank line
  const headerEnd = raw.indexOf("\n\n");
  let headerSection: string;
  let body: string;
  if (headerEnd < 0) {
    headerSection = raw;
    body = "";
  } else {
    headerSection = raw.substring(0, headerEnd);
    body = raw.substring(headerEnd + 2);
  }

  const lines = headerSection.split("\n");
  const command = lines[0] ?? "";
  const headers: Record<string, string> = {};
  for (const line of lines.slice(1)) {
    const idx = line.indexOf(":");
    if (idx >= 0) {
      headers[line.substring(0, idx)] = line.substring(idx + 1);
    }
  }

  return { command, headers, body };
}

// ---------------------------------------------------------------------------
// Error / transport event types
// ---------------------------------------------------------------------------

export interface StreamDropped {
  kind: "stream-dropped";
  exception: Error;
}

/**
 * The stream is back.
 *
 * Delivered once the transport has re-established itself after a
 * `StreamDropped`. A consumer's state is stale until it reseeds: whatever
 * happened while the socket was down was not delivered.
 *
 * Carries the marketplace because one client can hold several subscriptions,
 * and "we are back" does not say which desk to reseed.
 */
export interface StreamReconnected {
  kind: "reconnected";
  marketplaceId: number;
}

export interface FrameUnreadable {
  kind: "frame-unreadable";
  command: string;
  headers: Record<string, string>;
  body: string;
  exception: Error;
}

// ---------------------------------------------------------------------------
// Event types
// ---------------------------------------------------------------------------

/**
 * ORDERS-UPDATE delta from the WS stream, carrying the parsed orders
 * plus the per-marketplace `seq` header fm-server stamps each frame
 * with. Consumers reconcile this against a REST `Snapshot`: apply
 * deltas whose `seq` is greater than the snapshot's `asOfSeq` and
 * skip those whose seq is less than or equal.
 *
 * `seq` is `-1` when the server didn't stamp the header (older
 * fm-server).
 */
export interface OrdersUpdate {
  readonly kind: "orders-update";
  readonly orders: Order[];
  readonly seq: number;
}

export type FmEvent =
  | Version
  | Session
  | Session[]
  | Holding
  | OrdersUpdate
  | MarketCall
  | CallIndicative
  | StreamDropped
  | StreamReconnected
  | FrameUnreadable;

export type EventCallback = (event: FmEvent) => void;

// ---------------------------------------------------------------------------
// Event parsing — mirrors Python _parse_event / Java EventParser
// ---------------------------------------------------------------------------

const MESSAGE_TYPE = "message-type";
const SEQ_HEADER   = "seq";

/** Sentinel for "no seq header" — see OrdersUpdate.seq docstring. */
export const NO_SEQ = -1;

function parseEvent(
  frame: StompFrame,
  parseHolding: (data: Record<string, unknown>) => Holding,
  parseOrder: (data: Record<string, unknown>) => Order,
): FmEvent | null {
  const msgType = frame.headers[MESSAGE_TYPE];
  if (msgType === undefined) return null;

  // A body that is not JSON throws, and the receive loop reports the frame as
  // unreadable -- as Java always has -- instead of it vanishing here.
  const data: unknown = frame.body ? JSON.parse(frame.body) : null;

  switch (msgType) {
    case "VERSION":
      return parseVersion(data);
    case "SESSION-LIST":
      return parseSessionList(data);
    case "SESSION-UPDATE":
      return parseSession(data as Record<string, unknown>);
    case "HOLDING-UPDATE":
      return parseHolding(data as Record<string, unknown>);
    case "ORDERS-UPDATE": {
      const seq = _parseSeq(frame.headers[SEQ_HEADER]);
      return { kind: "orders-update", orders: parseOrders(data, parseOrder), seq };
    }
    // A call of a call-auction market; its trades come as an ORDERS-UPDATE of their own.
    case "CALL-UPDATE":
      return parseMarketCall(data as Record<string, unknown>);
    // What an indicative call market would clear at now; delivered as sent,
    // so a caller keeps the highest seq it has seen.
    case "CALL-INDICATIVE":
      return parseCallIndicative(data as Record<string, unknown>);
    default:
      return null;
  }
}

/** Parse the per-frame `seq` header; falls back to NO_SEQ on absent
 *  or malformed values. */
function _parseSeq(value: string | undefined): number {
  if (value === undefined) return NO_SEQ;
  const n = Number.parseInt(value, 10);
  return Number.isFinite(n) ? n : NO_SEQ;
}

function parseVersion(data: unknown): Version {
  if (typeof data === "object" && data !== null && "version" in data) {
    return { version: (data as Record<string, number>).version ?? 0 };
  }
  return { version: typeof data === "number" ? data : 0 };
}

export function parseSession(data: Record<string, unknown>): Session {
  return {
    marketplaceId: (data.marketplaceId as number) ?? 0,
    allocationId: (data.allocationId as number) ?? 0,
    id: (data.id as number) ?? 0,
    original: (data.original as number) ?? 0,
    state: (data.state as string) ?? null,
    name: (data.name as string) ?? null,
    description: (data.description as string) ?? null,
    openDate: toInstant(data.openDate as string),
    closeDate: toInstant(data.closeDate as string),
    createdDate: toInstant(data.createdDate as string),
    lastModifiedDate: toInstant(data.lastModifiedDate as string),
  };
}

export function parseCallIndicative(data: Record<string, unknown>): CallIndicative {
  return {
    marketplaceId: (data.marketplaceId as number) ?? 0,
    marketId: (data.marketId as number) ?? 0,
    sessionId: (data.sessionId as number) ?? 0,
    price: (data.price as number) ?? null,
    volume: (data.volume as number) ?? 0,
    seq: (data.seq as number) ?? 0,
    at: toInstant(data.at as string),
  };
}

export function parseMarketCall(data: Record<string, unknown>): MarketCall {
  return {
    id: (data.id as number) ?? 0,
    marketplaceId: (data.marketplaceId as number) ?? 0,
    marketId: (data.marketId as number) ?? 0,
    sessionId: (data.sessionId as number) ?? 0,
    calledDate: toInstant(data.calledDate as string),
    trigger: (data.trigger as string) ?? null,
    price: (data.price as number) ?? null,
    volume: (data.volume as number) ?? 0,
    demand: (data.demand as number) ?? 0,
    supply: (data.supply as number) ?? 0,
    buyOrders: (data.buyOrders as number) ?? 0,
    sellOrders: (data.sellOrders as number) ?? 0,
    carry: (data.carry as boolean) ?? true,
  };
}

function parseSessionList(data: unknown): Session[] {
  if (Array.isArray(data)) {
    return data.map((s) => parseSession(s as Record<string, unknown>));
  }
  return [];
}

function parseOrders(
  data: unknown,
  parseOrder: (data: Record<string, unknown>) => Order,
): Order[] {
  if (Array.isArray(data)) {
    return data.map((o) => parseOrder(o as Record<string, unknown>));
  }
  return [];
}

// ---------------------------------------------------------------------------
// EventListener — manages WS + STOMP connection
// ---------------------------------------------------------------------------

/**
 * Whether the handshake was refused for who we are rather than for what the
 * network did.
 *
 * <p>`ws` reports a rejected upgrade as `Unexpected server response: 401`, so
 * the status is read back out of that. 403 counts too: a token that is valid
 * but not permitted here will not become permitted by being presented again.
 */
function isAuthRefusal(failure: unknown): boolean {
  for (let cause: unknown = failure; cause instanceof Error; cause = cause.cause) {
    const status = /Unexpected server response:\s*(\d{3})/.exec(cause.message)?.[1];
    if (status === "401" || status === "403") return true;
  }
  return false;
}

export class EventListener {
  private readonly _wsUrl: string;
  /** Read on every connect, so a reconnect after the client renewed its sign-in presents the new token. */
  private readonly _bearerToken: () => string;
  /**
   * Asks the client to sign in again, given the token this stream's refused
   * connect presented -- the check the client's requests make, so a token
   * another caller has just renewed is not renewed twice. False when it cannot.
   */
  private readonly _renew: (presented: string) => Promise<boolean>;
  /** The Authorization header the last connect presented. */
  private _presented = "";
  private readonly _marketplaceId: number;
  private readonly _appPrefix: string;
  private readonly _callback: EventCallback;
  private readonly _clientDescription: string;
  private readonly _parseHolding: (data: Record<string, unknown>) => Holding;
  private readonly _parseOrder: (data: Record<string, unknown>) => Order;

  private _ws: WebSocket | null = null;
  private _closed = false;

  /**
   * Sends the heartbeats this client promises.
   *
   * IT PROMISED THEM AND NEVER SENT ONE. The CONNECT frame has always carried
   * `heart-beat:30000,30000` -- "I will send every 30s, send me one every 30s"
   * -- and nothing here wrote to the socket again except SUBSCRIBE. Worse than
   * the other two SDKs: `ws` sends no WebSocket ping of its own unless asked,
   * so this client had no keepalive at any layer. Java fixed this first;
   * TypeScript and Python advertised the same thing and were left behind.
   *
   * unref()'d, so it can never hold the process open.
   */
  private _heartbeat: NodeJS.Timeout | null = null;
  private _subscriptionCounter = 0;
  private _reconnecting = false;
  /** Attempts that have failed in a row; with `_connectedAt`, paces reconnect(). */
  _failures = 0;
  /** When the socket last opened (Date.now()), or null before it ever has. */
  _connectedAt: number | null = null;

  constructor(
    wsUrl: string,
    bearerToken: string | (() => string),
    marketplaceId: number,
    callback: EventCallback,
    clientDescription: string,
    parseHolding: (data: Record<string, unknown>) => Holding,
    parseOrder: (data: Record<string, unknown>) => Order,
    renew: (presented: string) => Promise<boolean> = async () => false,
    history = false,
  ) {
    this._wsUrl = wsUrl;
    // The V0 subscribe, whose first ORDERS-UPDATE is the session's whole
    // history; see Flexemarkets.subscribe.
    this._appPrefix = history ? "" : API_VERSION_PREFIX;
    this._bearerToken = typeof bearerToken === "function" ? bearerToken : () => bearerToken;
    this._renew = renew;
    this._marketplaceId = marketplaceId;
    this._callback = callback;
    this._clientDescription = clientDescription;
    this._parseHolding = parseHolding;
    this._parseOrder = parseOrder;
  }

  // -- public API ------------------------------------------------------------

  start(): Promise<void> {
    return new Promise((resolve, reject) => {
      this._ws = this._connect();
      this._ws.on("open", () => {
        try {
          this._stompConnect();
        } catch (err) {
          reject(err);
          return;
        }
        // Wait for the server's first frame -- CONNECTED -- then subscribe.
        this._ws!.once("message", () => {
          this._subscribe();
          this._startHeartbeats();
          this._connectedAt = Date.now();
          this._receiveLoop();
          resolve();
        });
      });
      this._ws.on("error", (err) => {
        if (!this._closed) {
          reject(err);
        }
      });
    });
  }

  /**
   * React to a dropped stream by restoring it.
   *
   * On success a `StreamReconnected` is delivered so consumers know their state
   * needs reseeding -- matching Java, where the subscription has always
   * restored itself rather than leaving the caller to notice.
   *
   * The flag is what makes a burst of errors from a dying socket one outage
   * instead of five reconnects; Java uses compareAndSet for the same reason.
   */
  _onStreamDropped(exception: Error): void {
    if (this._closed) return;
    this._callback({ kind: "stream-dropped", exception });
    if (this._reconnecting) return;
    this._reconnecting = true;
    const opened = this._connectedAt;
    this._failures = opened !== null && Date.now() - opened < STABLE_CONNECTION_MS ? this._failures + 1 : 0;

    void (async () => {
      try {
        await this.reconnect();
        if (!this._closed) {
          this._callback({ kind: "reconnected", marketplaceId: this._marketplaceId });
        }
      } finally {
        this._reconnecting = false;
      }
    })();
  }

  /**
   * Reconnect after a transport error. Paced by the failures before it (see
   * RECONNECT_FIRST_PAUSE_MS): at once after a stream that had held, later and
   * later after attempts that failed or connections that fell straight away.
   */
  async reconnect(): Promise<void> {
    let renewed = false;
    while (!this._closed) {
      if (this._failures > 0) {
        await this._sleep(reconnectPause(this._failures));
        if (this._closed) return;
      }
      try {
        this._disconnect();
        await this.start();
        this._connectedAt = Date.now();
        return;
      } catch (exception) {
        // A REJECTED TOKEN IS NOT A BLIP, and retrying it is not patience --
        // it is a client hammering a server that has already given its final
        // answer. Measured 2026-09-03: one NAT gateway produced 11,918
        // handshake 401s in two hours, about 100 a minute, against 360
        // successful connections from every other client combined. Nothing
        // told the operator their token had expired; the loop simply ran.
        //
        // So an auth refusal ends the stream and says why, and every other
        // failure keeps the existing retry: a server restart or a network drop
        // IS a blip, and recovering from it is what this loop is for.
        // Except once: a client that holds credentials signs in again -- the
        // 24-hour token ran out under a long-running robot -- and the next
        // attempt presents the new token. A second refusal is final, as before.
        if (isAuthRefusal(exception) && !renewed && (await this._renew(this._presented))) {
          renewed = true;
          continue;
        }
        if (isAuthRefusal(exception)) {
          this._closed = true;
          this._callback({
            kind: "stream-dropped",
            exception: new Error(
              "WebSocket refused: the token was rejected. It has expired or was " +
                "signed for another server; reconnecting cannot fix it.",
              { cause: exception },
            ),
          });
          return;
        }
        this._failures += 1;
      }
    }
  }

  /** Wait between reconnect attempts; a test replaces it to record the waits. */
  protected _sleep(ms: number): Promise<void> {
    return new Promise((r) => setTimeout(r, ms));
  }

  close(): void {
    if (this._closed) return;
    this._closed = true;
    this._stopHeartbeats();
    this._disconnect();
  }

  /**
   * Send a bare EOL every {@link HEARTBEAT_INTERVAL_MS}.
   *
   * A newline on its own is what STOMP 1.2 defines a heartbeat to be, so the
   * server reads it as one and nothing else parses it as a frame.
   */
  private _startHeartbeats(): void {
    this._stopHeartbeats();
    this._heartbeat = setInterval(() => {
      const ws = this._ws;
      if (this._closed || ws === null || ws.readyState !== WebSocket.OPEN) return;
      try {
        ws.send("\n");
      } catch {
        // a dead socket is the receive loop's problem, not this timer's
      }
    }, HEARTBEAT_INTERVAL_MS);
    this._heartbeat.unref?.();
  }

  private _stopHeartbeats(): void {
    if (this._heartbeat !== null) {
      clearInterval(this._heartbeat);
      this._heartbeat = null;
    }
  }

  // -- WebSocket connection --------------------------------------------------

  private _connect(): WebSocket {
    return new WebSocket(this._wsUrl, ["v12.stomp", "v11.stomp", "v10.stomp"], {
      headers: {
        Authorization: (this._presented = this._bearerToken()),
      },
      maxPayload: 128 * 1024 * 1024,
    });
  }

  private _disconnect(): void {
    if (this._ws !== null) {
      try {
        this._ws.removeAllListeners();
        // A socket closed before its handshake reports that as an 'error' on
        // the next tick; with no listener left, Node throws it out of the
        // process. This socket is being discarded, so the report is too.
        this._ws.on("error", () => {});
        this._ws.close();
      } catch {
        // ignore
      }
      this._ws = null;
    }
  }

  // -- STOMP framing ---------------------------------------------------------

  private _nextId(): string {
    this._subscriptionCounter += 1;
    return `sub-${this._subscriptionCounter}`;
  }

  private _stompConnect(): void {
    const frame: StompFrame = {
      command: "CONNECT",
      headers: {
        "accept-version": "1.2,1.1,1.0",
        "heart-beat": `${HEARTBEAT_MS},${HEARTBEAT_MS}`,
        "agent-description": this._clientDescription,
        "marketplace-id": String(this._marketplaceId),
      },
      body: "",
    };
    this._ws!.send(encodeFrame(frame));
  }

  private _subscribe(): void {
    // fm-server publishes broadcasts on the V0 destination paths
    // (/topic/marketplaces/{id}, /user/queue/marketplaces/{id}) for
    // both V0 and V1 clients — only the @SubscribeMapping gating the
    // initial snapshot lives at the /v1 prefix. So pub/sub subscriptions
    // stay on V0 paths regardless of the chosen api-version; only the
    // /app destination flips. Mirrors fm-ui's web-socket.service.ts
    // and the Java SDK Events.java pattern.
    const resource = `/marketplaces/${this._marketplaceId}`;
    for (const dest of [
      `/user/queue${resource}`,
      `/topic${resource}`,
      `/app${this._appPrefix}${resource}`,
    ]) {
      const frame: StompFrame = {
        command: "SUBSCRIBE",
        headers: {
          id: this._nextId(),
          destination: dest,
        },
        body: "",
      };
      this._ws!.send(encodeFrame(frame));
    }
  }

  // -- receive loop ----------------------------------------------------------

  private _receiveLoop(): void {
    this._ws!.on("message", (raw) => {
      const data = raw.toString();
      if (!data || data === "\n") return; // STOMP heartbeat

      const frame = decodeFrame(data);

      if (frame.command === "MESSAGE") {
        // A frame that cannot be read is reported and the next one read. A
        // non-JSON body used to vanish without a word, and one whose orders
        // could not be parsed threw out of this callback. Java reports both.
        let event: FmEvent | null;
        try {
          event = parseEvent(frame, this._parseHolding, this._parseOrder);
        } catch (e) {
          this._callback({
            kind: "frame-unreadable",
            command: frame.command,
            headers: frame.headers,
            body: frame.body,
            exception: e instanceof Error ? e : new Error(String(e)),
          });
          return;
        }
        if (event !== null) {
          this._callback(event);
        }
      } else if (frame.command === "ERROR") {
        this._callback({
          kind: "frame-unreadable",
          command: frame.command,
          headers: frame.headers,
          body: frame.body,
          exception: new Error(frame.headers.message ?? "STOMP ERROR"),
        });
      }
    });

    this._ws!.on("close", () => {
      this._onStreamDropped(new Error("WebSocket connection closed"));
    });

    this._ws!.on("error", (err) => {
      this._onStreamDropped(err);
    });
  }
}
