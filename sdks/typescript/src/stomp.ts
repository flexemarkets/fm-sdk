/**
 * WebSocket STOMP event listener for Flexemarkets.
 *
 * Implements STOMP 1.2 framing over WebSocket, matching the Python
 * EventListener / Java StompListener behaviour.
 */

import WebSocket from "ws";
import type { Holding, Order, Session, Version } from "./types.js";

const HEARTBEAT_MS = 30_000;
const NULL = "\x00";

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
 * (`GET /api/v1/marketplaces/{id}/orders/active`) and reconcile
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

export interface WsTransportError {
  kind: "transport-error";
  exception: Error;
}

export interface WsException {
  kind: "ws-exception";
  command: string;
  headers: Record<string, string>;
  body: string;
  exception: Error;
}

// ---------------------------------------------------------------------------
// Event types
// ---------------------------------------------------------------------------

export type FmEvent =
  | Version
  | Session
  | Session[]
  | Holding
  | Order[]
  | WsTransportError
  | WsException;

export type EventCallback = (event: FmEvent) => void;

// ---------------------------------------------------------------------------
// Event parsing — mirrors Python _parse_event / Java EventParser
// ---------------------------------------------------------------------------

const MESSAGE_TYPE = "message-type";

function parseEvent(
  frame: StompFrame,
  parseHolding: (data: Record<string, unknown>) => Holding,
  parseOrder: (data: Record<string, unknown>) => Order,
): FmEvent | null {
  const msgType = frame.headers[MESSAGE_TYPE];
  if (msgType === undefined) return null;

  let data: unknown;
  try {
    data = frame.body ? JSON.parse(frame.body) : null;
  } catch {
    return null;
  }

  switch (msgType) {
    case "VERSION":
      return parseVersion(data);
    case "SESSION-LIST":
      return parseSessionList(data);
    case "SESSION-UPDATE":
      return parseSession(data as Record<string, unknown>);
    case "HOLDING-UPDATE":
      return parseHolding(data as Record<string, unknown>);
    case "ORDERS-UPDATE":
      return parseOrders(data, parseOrder);
    default:
      return null;
  }
}

function parseVersion(data: unknown): Version {
  if (typeof data === "object" && data !== null && "version" in data) {
    return { version: (data as Record<string, number>).version ?? 0 };
  }
  return { version: typeof data === "number" ? data : 0 };
}

function parseSession(data: Record<string, unknown>): Session {
  return {
    marketplaceId: (data.marketplaceId as number) ?? 0,
    allocationId: (data.allocationId as number) ?? 0,
    id: (data.id as number) ?? 0,
    original: (data.original as number) ?? 0,
    state: (data.state as string) ?? null,
    name: (data.name as string) ?? null,
    description: (data.description as string) ?? null,
    openDate: (data.openDate as string) ?? null,
    closeDate: (data.closeDate as string) ?? null,
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

export class EventListener {
  private readonly _wsUrl: string;
  private readonly _bearerToken: string;
  private readonly _marketplaceId: number;
  private readonly _callback: EventCallback;
  private readonly _clientDescription: string;
  private readonly _parseHolding: (data: Record<string, unknown>) => Holding;
  private readonly _parseOrder: (data: Record<string, unknown>) => Order;

  private _ws: WebSocket | null = null;
  private _closed = false;
  private _subscriptionCounter = 0;

  constructor(
    wsUrl: string,
    bearerToken: string,
    marketplaceId: number,
    callback: EventCallback,
    clientDescription: string,
    parseHolding: (data: Record<string, unknown>) => Holding,
    parseOrder: (data: Record<string, unknown>) => Order,
  ) {
    this._wsUrl = wsUrl;
    this._bearerToken = bearerToken;
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
        // Wait for CONNECTED frame then subscribe
        this._ws!.once("message", (raw) => {
          const reply = decodeFrame(raw.toString());
          if (reply.command !== "CONNECTED" && reply.command !== "") {
            // unexpected but continue
          }
          this._subscribe();
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

  async reconnect(): Promise<void> {
    while (!this._closed) {
      try {
        this._disconnect();
        await this.start();
        return;
      } catch {
        await new Promise((r) => setTimeout(r, 2000));
      }
    }
  }

  close(): void {
    if (this._closed) return;
    this._closed = true;
    this._disconnect();
  }

  // -- WebSocket connection --------------------------------------------------

  private _connect(): WebSocket {
    return new WebSocket(this._wsUrl, ["v12.stomp", "v11.stomp", "v10.stomp"], {
      headers: {
        Authorization: this._bearerToken,
      },
      maxPayload: 128 * 1024 * 1024,
    });
  }

  private _disconnect(): void {
    if (this._ws !== null) {
      try {
        this._ws.removeAllListeners();
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
      `/app${API_VERSION_PREFIX}${resource}`,
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
        const event = parseEvent(frame, this._parseHolding, this._parseOrder);
        if (event !== null) {
          this._callback(event);
        }
      } else if (frame.command === "ERROR") {
        this._callback({
          kind: "ws-exception",
          command: frame.command,
          headers: frame.headers,
          body: frame.body,
          exception: new Error(frame.headers.message ?? "STOMP ERROR"),
        });
      }
    });

    this._ws!.on("close", () => {
      if (!this._closed) {
        this._callback({
          kind: "transport-error",
          exception: new Error("WebSocket connection closed"),
        });
      }
    });

    this._ws!.on("error", (err) => {
      if (!this._closed) {
        this._callback({
          kind: "transport-error",
          exception: err,
        });
      }
    });
  }
}
