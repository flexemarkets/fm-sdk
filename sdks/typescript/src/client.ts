/**
 * Flexemarkets API client.
 *
 * Port of fm.client (Python) / fm.Flexemarkets (Java).
 */

import { readFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { orderedSecurities, toOrderType, toSide, unitGrid } from "./types.js";
import { toInstant } from "./timestamps.js";
import type {
  Account,
  Allotment,
  Assets,
  ClientConnection,
  Holding,
  ManagerOtpBundle,
  Market,
  Marketplace,
  Order,
  ParticipantState,
  Person,
  Security,
  Session,
  TickGrid,
  Token,
  Widget,
  WidgetPush,
} from "./types.js";
import { EventListener, NO_SEQ, type EventCallback } from "./stomp.js";
import {
  DefaultDesk,
  DeskHandle,
  type Desk,
  type Subscription,
} from "./desk.js";
import type { Snapshot } from "./snapshot.js";
import { readVersion } from "./version.js";

const FM_NETWORK_CLIENT = `fm-sdk-typescript/${readVersion()}`;
export const DEFAULT_ENDPOINT = "https://api.flexemarkets.com";

/**
 * The most traded legs fm-server answers in one read. The route `trades`
 * replaced had no limit; asking for the ceiling keeps as much of that as the
 * server allows.
 */
const MAX_TRADED_LEGS = 5000;

const BCRYPT_RE = /^\$2[abxy]?\$\d{2}\$[./A-Za-z0-9]{53}$/;
const JWT_RE = /^[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+$/;

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

export class FlexemarketsError extends Error {}
export class AuthenticationError extends FlexemarketsError {}
export class AuthorizationError extends FlexemarketsError {}
export class InvalidArgumentError extends FlexemarketsError {}
export class ConnectionFailedError extends FlexemarketsError {}
export class ConfigurationError extends FlexemarketsError {}

/**
 * A response the SDK has no better name for, carrying its status and body.
 *
 * The fallback. A status with a meaning worth acting on gets its own type —
 * {@link AuthenticationError}, {@link ConflictError} — and this is what is
 * left, so a caller can read the status rather than parse a message.
 */
export class HttpError extends FlexemarketsError {
  constructor(
    readonly statusCode: number,
    readonly body: string,
  ) {
    super(`HTTP ${statusCode}: ${body}`);
  }
}

/**
 * The call could not be completed: the transport failed, or the response was
 * not something the SDK could read.
 *
 * Distinct from {@link HttpError}, which means the server answered and the
 * answer was an error. This means there was no usable answer at all — a
 * malformed body.
 */
export class ApiError extends FlexemarketsError {}

/** A 409. The Java and Python SDKs have raised this since the admin surface landed. */
export class ConflictError extends FlexemarketsError {}

/**
 * An account name was taken, and the server proposed another.
 *
 * A subclass of {@link ConflictError} rather than a sibling, so a caller that
 * handles conflicts generally still catches this one. The suggestion is worth
 * surfacing rather than retrying blindly: it is the name the account would end
 * up known by.
 */
export class AccountNameConflictError extends ConflictError {
  constructor(
    message: string,
    readonly requestedName: string,
    readonly suggestedName: string | null,
  ) {
    super(message);
  }
}

/**
 * A user could not be deleted because they still own marketplace data —
 * orders or allotments. Deleting them would orphan it, so the server refuses;
 * the caller has to decide what happens to the data first.
 */
export class PersonHasMarketplaceDataError extends ConflictError {
  constructor(
    message: string,
    readonly userId: number,
  ) {
    super(message);
  }
}

// ---------------------------------------------------------------------------
// JSON → type helpers
// ---------------------------------------------------------------------------

type JsonObject = Record<string, unknown>;

export function parsePerson(data: JsonObject | null | undefined): Person | null {
  if (!data) return null;
  return {
    id: (data.id as number) ?? 0,
    accountId: (data.accountId as number) ?? 0,
    firstName: (data.firstName as string) ?? null,
    lastName: (data.lastName as string) ?? null,
    email: (data.email as string) ?? null,
    roles: (data.roles as string[]) ?? [],
    accountOwner: (data.accountOwner as boolean) ?? false,
    createdDate: toInstant(data.createdDate as string),
    lastModifiedDate: toInstant(data.lastModifiedDate as string),
  };
}

export function parseAccount(data: JsonObject | null | undefined): Account | null {
  if (!data) return null;
  return {
    id: (data.id as number) ?? null,
    name: (data.name as string) ?? null,
    description: (data.description as string) ?? null,
    owner: parsePerson(data.owner as JsonObject),
    approval: (data.approval as boolean | null) ?? null,
    approvalDescription: (data.approvalDescription as string) ?? null,
    createdDate: toInstant(data.createdDate as string),
    lastModifiedDate: toInstant(data.lastModifiedDate as string),
  };
}

export function parseToken(data: JsonObject): Token {
  return {
    requestUrl: (data.requestUrl as string) ?? null,
    person: parsePerson(data.person as JsonObject),
    account: parseAccount(data.account as JsonObject),
    token: (data.token as string) ?? null,
  };
}

export function parseSecurity(data: JsonObject): Security {
  return {
    marketId: (data.marketId as number) ?? 0,
    units: (data.units as number) ?? 0,
    availableUnits: (data.availableUnits as number) ?? 0,
    // Either spelling, depending on which response produced the holding.
    shortUnits: (data.shortUnits as number) ?? (data.initialShortUnits as number) ?? 0,
    canBuy: (data.canBuy as boolean) ?? false,
    canSell: (data.canSell as boolean) ?? false,
  };
}

export function parseMarket(data: JsonObject): Market {
  return {
    id: (data.id as number) ?? 0,
    marketplaceId: (data.marketplaceId as number) ?? 0,
    name: (data.name as string) ?? null,
    description: (data.description as string) ?? null,
    symbol: (data.symbol as string) ?? null,
    privateMarket: (data.privateMarket as boolean) ?? false,
    priceMinimum: (data.priceMinimum as number) ?? 0,
    priceMaximum: (data.priceMaximum as number) ?? 0,
    priceTick: (data.priceTick as number) ?? 0,
    unitMinimum: (data.unitMinimum as number) ?? 0,
    unitMaximum: (data.unitMaximum as number) ?? 0,
    unitTick: (data.unitTick as number) ?? 0,
  };
}

export function parseMarketplace(data: JsonObject): Marketplace {
  return {
    id: (data.id as number) ?? 0,
    name: (data.name as string) ?? null,
    description: (data.description as string) ?? null,
    markets: ((data.markets as JsonObject[]) ?? []).map(parseMarket),
  };
}

export function parseSession(data: JsonObject): Session {
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
  };
}

export function parseOrder(data: JsonObject): Order {
  return {
    id: (data.id as number) ?? 0,
    original: (data.original as number) ?? 0,
    supplier: (data.supplier as number) ?? 0,
    consumer: (data.consumer as number | null) ?? null,
    type: toOrderType(data.type as string),
    side: toSide(data.side as string),
    units: (data.units as number) ?? 0,
    price: (data.price as number) ?? 0,
    ownerId: (data.ownerId as number) ?? null,
    marketplaceId: (data.marketplaceId as number) ?? 0,
    sessionId: (data.sessionId as number) ?? 0,
    symbol: (data.symbol as string) ?? null,
    marketId: (data.marketId as number) ?? 0,
    ownerTarget: (data.ownerTarget as string) ?? null,
    clientDescription: (data.clientDescription as string) ?? null,
    createdDate: toInstant(data.createdDate as string),
    lastModifiedDate: toInstant(data.lastModifiedDate as string),
  };
}

export function parseParticipantState(data: JsonObject): ParticipantState {
  return {
    marketplaceId: (data.marketplaceId as number) ?? null,
    allocationId: (data.allocationId as number) ?? null,
    ownerId: (data.ownerId as number) ?? null,
    ownerEmail: (data.ownerEmail as string) ?? null,
    fields: { ...((data.fields as Record<string, unknown>) ?? {}) },
  };
}

export function parseWidget(data: JsonObject): Widget {
  return {
    id: (data.id as number) ?? null,
    marketplaceId: (data.marketplaceId as number) ?? null,
    scope: (data.scope as string) ?? null,
    userId: (data.userId as number) ?? null,
    key: (data.key as string) ?? null,
    title: (data.title as string) ?? null,
    emphasis: (data.emphasis as string) ?? null,
    ttlSeconds: (data.ttlSeconds as number) ?? null,
    content: { ...((data.content as Record<string, unknown>) ?? {}) },
    createdDate: toInstant(data.createdDate as string),
    lastModifiedDate: toInstant(data.lastModifiedDate as string),
  };
}

/**
 * The wire form of a push: the target nested, nothing null.
 *
 * Absent rather than null so the body reads as the server documents it -- a
 * marketplace target is `{"scope":"MARKETPLACE"}`, with no userId.
 */
function widgetPushJson(push: WidgetPush): JsonObject {
  const body: JsonObject = { key: push.key, content: push.content };
  if (push.target != null) {
    body.target = push.target.userId != null
      ? { scope: push.target.scope, userId: push.target.userId }
      : { scope: push.target.scope };
  }
  if (push.title != null) body.title = push.title;
  if (push.emphasis != null) body.emphasis = push.emphasis;
  if (push.ttlSeconds != null) body.ttlSeconds = push.ttlSeconds;
  return body;
}

function parseAllotment(data: JsonObject): Allotment {
  // The server spells the nested capital "capital" on some responses and
  // "assets" on others, and the positions inside it "grants" or "securities".
  const assetsRaw = (data.assets as JsonObject) ?? (data.capital as JsonObject) ?? null;
  let assets: Assets | null = null;
  if (assetsRaw) {
    const securitiesRaw =
      (assetsRaw.grants as JsonObject[]) ?? (assetsRaw.securities as JsonObject[]) ?? [];
    assets = {
      id: (assetsRaw.id as number) ?? null,
      name: (assetsRaw.name as string) ?? null,
      cash: (assetsRaw.cash as number) ?? 0,
      securities: orderedSecurities(securitiesRaw.map(parseSecurity)),
    };
  }
  return {
    id: (data.id as number) ?? null,
    allocationId: (data.allocationId as number) ?? null,
    marketplaceId: (data.marketplaceId as number) ?? null,
    ownerId: (data.ownerId as number) ?? null,
    name: (data.name as string) ?? null,
    assets,
  };
}

/**
 * Encode a holding as the allotment `/allocations` reads.
 *
 * The positions go out as `grants`. That is the server's own field name, and
 * it is the one thing here that fails silently: send `securities` and the
 * server finds no grants, creates the allocation with the cash and no
 * positions, and answers 200 — an experiment whose participants hold nothing.
 */
function holdingToAllotment(marketplaceId: number, holding: Holding): JsonObject {
  return {
    marketplaceId,
    ownerId: holding.ownerId,
    name: holding.name,
    assets: {
      name: holding.name,
      cash: holding.cash,
      grants: holding.securities.map((s) => ({
        marketId: s.marketId,
        units: s.units,
        availableUnits: s.availableUnits,
        shortUnits: s.shortUnits,
        canBuy: s.canBuy,
        canSell: s.canSell,
      })),
    },
  } as unknown as JsonObject;
}

/**
 * An allotment predates the session it will be opened under, so sessionId is 0;
 * nothing has been committed against it, so availableCash equals cash.
 */
function allotmentsToHoldings(allotments: Allotment[]): Holding[] {
  return allotments.map((a) => {
    const cash = a.assets?.cash ?? 0;
    return {
      marketplaceId: a.marketplaceId ?? 0,
      sessionId: 0,
      allocationId: a.allocationId ?? 0,
      ownerId: a.ownerId ?? 0,
      name: a.name,
      cash,
      availableCash: cash,
      securities: a.assets?.securities ?? [],
    };
  });
}

export function parseHolding(data: JsonObject): Holding {
  const securitiesRaw =
    (data.securities as JsonObject[]) ?? (data.assets as JsonObject[]) ?? [];
  return {
    marketplaceId: (data.marketplaceId as number) ?? 0,
    sessionId: (data.sessionId as number) ?? 0,
    allocationId: (data.allocationId as number) ?? 0,
    ownerId: (data.ownerId as number) ?? 0,
    name: (data.name as string) ?? null,
    cash: (data.cash as number) ?? 0,
    availableCash: (data.availableCash as number) ?? 0,
    securities: orderedSecurities(securitiesRaw.map(parseSecurity)),
  };
}

export function parseConnection(data: JsonObject): ClientConnection {
  return {
    marketplaceId: (data.marketplaceId as number) ?? 0,
    connectionId: (data.id as number) ?? (data.connectionId as number) ?? 0,
    ownerId: (data.ownerId as number) ?? 0,
    established: toInstant(data.established as string),
    terminated: toInstant(data.terminated as string),
    description: (data.description as string) ?? null,
    sessionId: (data.sessionId as number) ?? null,
  };
}

/**
 * The orders in a snapshot answer, which is a bare JSON array or a failure.
 *
 * Only the array is read because the V1 route never sent anything else:
 * `GET /api/v1/marketplaces/{id}/orders` has always answered a bare array, and
 * 0.4 requires fm-server 4.6.2. The HAL envelopes earlier SDKs tolerated
 * belonged to the routes 0.4 no longer calls.
 *
 * Anything else throws rather than reading as empty. An empty snapshot is the
 * failure that hid twice before: `Desk` seeded an empty book, filled it from
 * live deltas, and looked plausible.
 *
 * @throws ApiError when the answer is not a JSON array
 */
export function ordersOf(data: unknown): JsonObject[] {
  if (!Array.isArray(data)) {
    const kind = data === null || data === undefined ? "null" : typeof data;
    throw new ApiError(`The orders snapshot answer was not a list of orders: got ${kind}`);
  }
  return data as JsonObject[];
}

/**
 * The most aggressive price this market will accept on `side`.
 *
 * Ticks are anchored at `priceMinimum`, not at zero — the server tests
 * `(price - priceMinimum) % priceTick` — so the top of the range is only legal
 * when the range is a whole number of ticks. The highest legal price is the
 * last tick at or below `priceMaximum`. A tick of zero marks a fixed dimension,
 * where the two bounds are equal and there is one legal price.
 */
export function marketableLimit(market: Market, side: string): number {
  // The side must be named. This was `side?.toUpperCase() !== "BUY"`, the
  // complement of buy rather than a test for sell, so a null side priced the
  // order at the bottom of the range -- the most aggressive *sell* this market
  // accepts. submitMarket is public API and takes the side straight from its
  // caller, so that turned a missing argument into a real order crossing the
  // wrong side of the book.
  const resolved = side?.toUpperCase();
  if (resolved !== "BUY" && resolved !== "SELL") {
    throw new Error(`A market order must name its side; got: ${side}`);
  }

  if (resolved !== "BUY" || market.priceTick <= 0) {
    return market.priceMinimum;
  }

  const span = market.priceMaximum - market.priceMinimum;
  return market.priceMinimum + Math.floor(span / market.priceTick) * market.priceTick;
}

// ---------------------------------------------------------------------------
// Credential / configuration helpers
// ---------------------------------------------------------------------------

function isValidToken(value: string): boolean {
  return BCRYPT_RE.test(value) || JWT_RE.test(value);
}

export function server(endpoint: string): string {
  // Locate "/api" in the path, not in the scheme/host. A host like
  // "https://api.flexemarkets.com" otherwise matches at the "//api" of the
  // host and truncates the base URL to "https://api" (unresolvable). Skip
  // past the scheme + host before searching for the "/api" path segment.
  const scheme = endpoint.indexOf("://");
  const pathStart = scheme >= 0 ? endpoint.indexOf("/", scheme + 3) : 0;
  const idx = pathStart < 0 ? -1 : endpoint.indexOf("/api", pathStart);

  if (idx >= 0) return endpoint.substring(0, idx + 4);

  // An endpoint naming only a server gets the API root appended rather than
  // being handed back as it stands. Returned unchanged, every URL built from
  // it was a segment short: sign-in POSTed to <host>/tokens, which the server
  // answers 404 "No static resource tokens". DEFAULT_ENDPOINT is exactly that
  // shape -- it has to be, since marketplaceEndpoint appends
  // "/api/marketplaces/<id>" to it -- so a client with nothing configured
  // could not sign in at all.
  return endpoint.endsWith("/") ? `${endpoint}api` : `${endpoint}/api`;
}

function resourceId(endpoint: string): number {
  const trimmed = endpoint.replace(/\/+$/, "");
  const last = trimmed.lastIndexOf("/");
  return parseInt(trimmed.substring(last + 1), 10);
}

/**
 * A bare marketplace id resolves to that marketplace on the default host.
 *
 * FM_URL overrides the host, which is what makes the id form usable against a
 * development server. Java has honoured it since the id form existed; this did
 * not, so `-E 123` could only ever mean production here -- the same flag, in
 * the same documented example, doing different things per language.
 */
function marketplaceEndpoint(marketplaceId: string): string {
  const host = process.env.FM_URL || DEFAULT_ENDPOINT;
  return `${host}/api/marketplaces/${marketplaceId}`;
}

/**
 * Resolve an `--endpoint` value to config overrides. A bare marketplace id
 * (e.g. "2540") resolves to that marketplace on the default production host; a
 * file is loaded as Java-style properties; anything else is treated as a full
 * URL. Development environments give a full URL when localhost is wanted.
 */
export function resolveEndpoint(endpoint: string): Record<string, string> {
  if (/^\d+$/.test(endpoint)) {
    return { endpoint: marketplaceEndpoint(endpoint) };
  }
  if (existsSync(endpoint)) {
    return loadPropertiesFile(endpoint);
  }
  return { endpoint };
}

export function loadPropertiesFile(path: string): Record<string, string> {
  const props: Record<string, string> = {};
  if (!existsSync(path)) return props;
  const content = readFileSync(path, "utf-8");
  for (const line of content.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const eqIdx = trimmed.indexOf("=");
    if (eqIdx >= 0) {
      props[trimmed.substring(0, eqIdx).trim()] = trimmed.substring(eqIdx + 1).trim();
    }
  }
  return props;
}

export function loadConfig(): Record<string, string> {
  const config: Record<string, string> = {};

  const fmDir = join(homedir(), ".fm");
  Object.assign(config, loadPropertiesFile(join(fmDir, "credential")));
  Object.assign(config, loadPropertiesFile(join(fmDir, "endpoint")));

  const envUrl = process.env.FM_API_URL;
  if (envUrl) config.endpoint = envUrl;

  if (!config.endpoint) config.endpoint = DEFAULT_ENDPOINT;

  return config;
}

// ---------------------------------------------------------------------------
// Response handling
// ---------------------------------------------------------------------------

/**
 * What the server said, rather than the envelope it said it in.
 *
 * Failures arrive as `{"error","message","path","shortDigest","status"}` and
 * the message is the only part a caller can act on. Falls back to the raw
 * body when it does not parse, since that is when the caller most needs to
 * see what came back. As the Java SDK's `_detail`, which had this fix alone:
 * here a refused sign-in said only "Authentication failed.", and a refused
 * order carried the whole JSON document.
 */
export function detail(body: string): string {
  if (!body || !body.trim()) return "(no response body)";
  try {
    const parsed: unknown = JSON.parse(body);
    if (parsed !== null && typeof parsed === "object" && !Array.isArray(parsed)) {
      const message = (parsed as { message?: unknown }).message;
      if (typeof message === "string" && message.trim()) return message;
    }
  } catch {
    // not JSON: report it as it came
  }
  return body;
}

/**
 * The body of a successful answer, parsed. A 200 that is not JSON -- an edge
 * proxy's HTML error page, a truncated body -- is an answer the SDK cannot
 * read, so it is an {@link ApiError}, as in the Java SDK, rather than a bare
 * SyntaxError escaping past a caller who catches FlexemarketsError.
 */
function readBody(body: string): any {
  try {
    return JSON.parse(body);
  } catch {
    throw new ApiError("Failed to parse the response body");
  }
}

function checkResponse(response: Response, body: string): void {
  const status = response.status;
  if (status >= 200 && status < 300) return;
  if (status === 400) throw new InvalidArgumentError("Invalid request: " + detail(body));
  if (status === 401) throw new AuthenticationError("Authentication failed: " + detail(body));
  if (status === 403) throw new AuthorizationError("Not permitted: " + detail(body));
  if (status === 409) throw new ConflictError(body);
  if (status >= 500) throw new ConnectionFailedError(body);
  throw new HttpError(status, body);
}

/** The server's proposed alternative name, when a 409 body carries one. */
function suggestedNameIn(body: string): string | null {
  try {
    const parsed = JSON.parse(body) as { suggestedName?: string };
    return parsed.suggestedName ?? null;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Flexemarkets client
// ---------------------------------------------------------------------------

export class Flexemarkets {
  private readonly _clientDescription: string;
  private readonly _endpoint: string;
  private readonly _baseUrl: string;
  private readonly _bearerToken: string;
  private _account!: Account;
  private _user!: Person;
  private _tokenObj!: Token;
  private _eventListener: EventListener | null = null;

  private constructor(
    endpoint: string,
    baseUrl: string,
    bearerToken: string,
    clientDescription: string,
  ) {
    this._endpoint = endpoint;
    this._baseUrl = baseUrl;
    this._bearerToken = bearerToken;
    this._clientDescription = clientDescription;
  }

  /** Connect to the Flexemarkets API. */
  static async connect(
    credential?: string | null,
    endpoint?: string | null,
    clientDescription?: string | null,
  ): Promise<Flexemarkets> {
    const desc = clientDescription ?? "Unspecified client";
    const config = loadConfig();

    if (credential != null) {
      if (existsSync(credential)) {
        Object.assign(config, loadPropertiesFile(credential));
      } else if (isValidToken(credential)) {
        config.token = credential;
      } else {
        throw new ConfigurationError(
          `Invalid credential: '${credential}' is not a file or token.`,
        );
      }
    }

    if (endpoint != null) {
      Object.assign(config, resolveEndpoint(endpoint));
    }

    const ep = config.endpoint ?? DEFAULT_ENDPOINT;
    const baseUrl = server(ep);

    // Authenticate
    const tokenObj = await signIn(baseUrl, config, desc);
    const bearer = `Bearer ${tokenObj.token}`;

    const fm = new Flexemarkets(ep, baseUrl, bearer, desc);
    fm._tokenObj = tokenObj;
    fm._account = tokenObj.account!;
    fm._user = tokenObj.person!;

    return fm;
  }

  // -- properties ------------------------------------------------------------

  get account(): Account {
    return this._account;
  }

  get accountId(): number {
    return this._account.id!;
  }

  get accountName(): string {
    return this._account.name!;
  }

  get user(): Person {
    return this._user;
  }

  get userId(): number {
    return this._user.id;
  }

  get endpointUrl(): string {
    return this._endpoint;
  }

  get endpointMarketplaceId(): number {
    return resourceId(this._endpoint);
  }

  // -- internal HTTP helpers -------------------------------------------------

  private _authHeaders(): Record<string, string> {
    return { Authorization: this._bearerToken };
  }

  private async _get(url: string): Promise<JsonObject> {
    const resp = await fetch(url.startsWith("/") ? `${this._baseUrl}${url}` : url, {
      headers: {
        ...this._authHeaders(),
        Accept: "application/json",
        "User-Agent": FM_NETWORK_CLIENT,
      },
    });
    const body = await resp.text();
    checkResponse(resp, body);
    return readBody(body);
  }

  /**
   * GET helper that returns the parsed body bundled with the
   * `x-fm-as-of-seq` response header so callers (notably Desk)
   * can correlate the snapshot with the WS delta stream. Returns
   * `Snapshot.NO_SEQ` when the header is absent.
   */
  private async _getSnapshot(url: string): Promise<{ data: JsonObject; asOfSeq: number }> {
    const resp = await fetch(url.startsWith("/") ? `${this._baseUrl}${url}` : url, {
      headers: {
        ...this._authHeaders(),
        Accept: "application/json",
        "User-Agent": FM_NETWORK_CLIENT,
      },
    });
    const body = await resp.text();
    checkResponse(resp, body);
    const raw = resp.headers.get("x-fm-as-of-seq");
    const asOfSeq = raw === null ? NO_SEQ : Number.parseInt(raw, 10);
    return { data: readBody(body), asOfSeq: Number.isFinite(asOfSeq) ? asOfSeq : NO_SEQ };
  }

  /** PATCH with a JSON body -- the shape every session transition takes. */
  private async _patch(url: string, json: unknown): Promise<JsonObject> {
    const resp = await fetch(url.startsWith("/") ? `${this._baseUrl}${url}` : url, {
      method: "PATCH",
      headers: {
        ...this._authHeaders(),
        "Content-Type": "application/json",
        Accept: "application/json",
        "User-Agent": FM_NETWORK_CLIENT,
      },
      body: JSON.stringify(json),
    });
    const body = await resp.text();
    checkResponse(resp, body);
    return readBody(body);
  }

  /**
   * GET returning the body verbatim, for endpoints that answer with something
   * other than JSON — the holdings download is a CSV, and JSON.parse dies on
   * the header row.
   */
  private async _getText(url: string): Promise<string> {
    const resp = await fetch(url.startsWith("/") ? `${this._baseUrl}${url}` : url, {
      headers: {
        ...this._authHeaders(),
        Accept: "text/csv, */*",
        "User-Agent": FM_NETWORK_CLIENT,
      },
    });
    const body = await resp.text();
    checkResponse(resp, body);
    return body;
  }

  private async _post(url: string, json: unknown): Promise<JsonObject> {
    const resp = await fetch(url.startsWith("/") ? `${this._baseUrl}${url}` : url, {
      method: "POST",
      headers: {
        ...this._authHeaders(),
        "Content-Type": "application/json",
        Accept: "application/json",
        "User-Agent": FM_NETWORK_CLIENT,
      },
      body: JSON.stringify(json),
    });
    const body = await resp.text();
    checkResponse(resp, body);
    return readBody(body);
  }

  // -- administration --------------------------------------------------------

  /*
   * Creating accounts and users, approving them, deleting them, and minting
   * one-time passcodes. fm-server's administrative surface, carried here so
   * that the tools which run a course have a client that is not fm-lib-net.
   *
   * Several are destructive and one issues credentials. They need an admin or
   * manager and the server answers 401/403 otherwise, which is the only
   * guard: possessing the method is not possessing the right.
   */

  /**
   * Register a new account and its owner, returning the owner's token.
   *
   * The owner's credentials go out as `ownerEmail`/`ownerPassword`. Sending
   * `email`/`password` instead creates an account with an owner the server
   * cannot sign in as.
   */
  async signup(
    accountName: string,
    email: string,
    password: string,
    firstName?: string | null,
    lastName?: string | null,
  ): Promise<Token> {
    const url = this._v1("/accounts");
    try {
      const data = await this._post(url, {
        accountName,
        ownerEmail: email,
        ownerPassword: password,
        firstName: firstName ?? null,
        lastName: lastName ?? null,
      });
      return parseToken(data);
    } catch (e) {
      // A taken name, with the server's proposed alternative. Raised as its
      // own type so a caller can offer the suggestion rather than parsing it
      // back out of a generic conflict.
      if (e instanceof ConflictError) {
        const suggested = suggestedNameIn(e.message);
        throw new AccountNameConflictError(
          `Account name '${accountName}' is taken` +
            (suggested === null ? "" : `; server suggests '${suggested}'`),
          accountName,
          suggested,
        );
      }
      throw e;
    }
  }

  /**
   * Approve an account by name, returning it as it now stands.
   *
   * V1 approves an account by id; the name is looked up in the account list
   * first, so callers keep naming the account they signed up.
   */
  async approveAccount(accountName: string): Promise<Account | null> {
    const account = (await this.accounts()).find((a) => a.name === accountName);
    if (account === undefined) {
      throw new InvalidArgumentError(`No account named '${accountName}'`);
    }
    const data = await this._post(this._v1(`/accounts/${account.id}/approvals`), { approve: true });
    return parseAccount(data.account as JsonObject);
  }

  /** One account by id. */
  async accountById(accountId: number): Promise<Account | null> {
    return parseAccount(await this._get(this._v1(`/accounts/${accountId}`)));
  }

  /** One user by id. */
  async userById(userId: number): Promise<Person> {
    return parsePerson(await this._get(this._v1(`/users/${userId}`))) as Person;
  }

  /**
   * The names of the participants a caller may target: `GET participants`,
   * names only. A row carries the user id too, for a manager; not needed here.
   */
  async identifiers(marketplaceId: number): Promise<string[]> {
    const rows = (await this._get(`${this._marketplace(marketplaceId)}/participants`)) as unknown as JsonObject[];
    return rows.map((r) => r.name as string);
  }

  /** Delete the caller's own account. Its own route, not accounts/{yourId}. */
  async deleteMyAccount(): Promise<void> {
    await this._delete(this._v1("/accounts/me"));
  }

  /** Every account on the server. Admin-only. */
  async accounts(): Promise<Account[]> {
    const data = await this._get(this._v1("/accounts"));
    return (data as unknown as JsonObject[]).map(parseAccount) as Account[];
  }

  /** Delete an account. Destructive, and takes its users with it. */
  async deleteAccount(accountId: number): Promise<void> {
    await this._delete(this._v1(`/accounts/${accountId}`));
  }

  /** Create a user in the caller's account. */
  async createUser(
    email: string,
    password: string,
    firstName: string,
    lastName: string,
    roles: string[] = [],
  ): Promise<Person> {
    const data = await this._post(this._v1("/users"), { email, password, firstName, lastName, roles });
    return parsePerson(data) as Person;
  }

  /** Delete a user. Destructive. */
  async deleteUser(userId: number): Promise<void> {
    try {
      await this._delete(this._v1(`/users/${userId}`));
    } catch (e) {
      // The user still owns orders or allotments. Deleting them would orphan
      // it, so the server refuses and the caller has to decide what happens to
      // the data first.
      if (e instanceof ConflictError) {
        throw new PersonHasMarketplaceDataError(
          `User ${userId} has marketplace data and cannot be deleted.`,
          userId,
        );
      }
      throw e;
    }
  }

  /** Create an empty marketplace. See also {@link createMarketplaceFromJson}. */
  /** Delete a marketplace, and with it its sessions and their history. */
  async deleteMarketplace(marketplaceId: number): Promise<void> {
    await this._delete(this._marketplace(marketplaceId));
  }

  /**
   * Add a market to a marketplace.
   *
   * Both dimensions are the caller's. Unit bounds used to be fixed at 1/100/1
   * with no way to say otherwise, on a call that set the price grid three
   * arguments earlier — and the server enforces the two identically, refusing
   * an order for "units is not on a tic" exactly as for a price. Omitting
   * `units` keeps the old default.
   */
  async createMarket(
    marketplaceId: number,
    symbol: string,
    name: string,
    price: TickGrid,
    units: TickGrid = unitGrid(),
    privateMarket = false,
  ): Promise<Market> {
    return parseMarket(await this._post(`${this._marketplace(marketplaceId)}/markets`, {
      symbol,
      name,
      priceMinimum: price.minimum,
      priceMaximum: price.maximum,
      priceTick: price.tick,
      unitMinimum: units.minimum,
      unitMaximum: units.maximum,
      unitTick: units.tick,
      privateMarket,
    }));
  }

  /**
   * Mint one-time passcodes for the given users.
   *
   * These are credentials: not to be logged, not to be persisted, and
   * delivered to the person they belong to.
   */
  async managerOtpBundle(userIds: number[]): Promise<ManagerOtpBundle> {
    // Unversioned: fm-server has no V1 route for passcodes.
    const url = `${this._baseUrl}/otp/manager`;
    const data = await this._post(url, { userIds });
    return {
      expiresAt: toInstant(data.expiresAt as string),
      otps: ((data.otps as JsonObject[]) ?? []).map((o) => ({
        userId: (o.userId as number) ?? 0,
        email: (o.email as string) ?? null,
        otp: (o.otp as string) ?? null,
      })),
    };
  }

  /** DELETE, whose answer is a status and nothing worth parsing. */
  private async _delete(url: string): Promise<void> {
    const resp = await fetch(url.startsWith("/") ? `${this._baseUrl}${url}` : url, {
      method: "DELETE",
      headers: { ...this._authHeaders(), Accept: "application/json" },
    });
    const body = await resp.text();
    checkResponse(resp, body);
  }

  /**
   * DELETE whose answer is the resource as it ended -- an order's CANCEL.
   */
  private async _deleteFor(url: string): Promise<JsonObject> {
    const resp = await fetch(url, {
      method: "DELETE",
      headers: { ...this._authHeaders(), Accept: "application/json", "User-Agent": FM_NETWORK_CLIENT },
    });
    const body = await resp.text();
    checkResponse(resp, body);
    return readBody(body);
  }

  /**
   * Send one CSV file as the body, `Content-Type: text/csv` -- format by
   * header, where 0.3 posted it as a multipart upload to an `/uploads`
   * segment. Bytes, not a string, so the file's own encoding survives.
   */
  private async _sendCsv(method: string, url: string, filename: string): Promise<unknown> {
    const resp = await fetch(url, {
      method,
      headers: {
        ...this._authHeaders(),
        "Content-Type": "text/csv",
        Accept: "application/json",
        "User-Agent": FM_NETWORK_CLIENT,
      },
      body: readFileSync(filename),
    });
    const body = await resp.text();
    checkResponse(resp, body);
    return readBody(body);
  }

  /**
   * A V1 route. 0.4 addresses every route from the server rather than through
   * the HAL root's links: the path is knowable without fetching anything
   * first, and a root that drops a link can no longer take a call with it.
   */
  private _v1(path: string): string {
    return `${this._baseUrl}/v1${path}`;
  }

  /** The V1 route of one marketplace, under which nearly everything lives. */
  private _marketplace(marketplaceId: number): string {
    return this._v1(`/marketplaces/${marketplaceId}`);
  }

  // ======================================================================
  // REST APIs
  // ======================================================================

  // -- marketplaces ----------------------------------------------------------

  async marketplaces(): Promise<Marketplace[]> {
    const data = await this._get(this._v1("/marketplaces"));
    return (data as unknown as JsonObject[]).map(parseMarketplace);
  }

  async marketplace(marketplaceId: number): Promise<Marketplace> {
    return parseMarketplace(await this._get(this._marketplace(marketplaceId)));
  }

  // -- markets ---------------------------------------------------------------

  async markets(marketplaceId: number): Promise<Market[]> {
    const data = await this._get(`${this._marketplace(marketplaceId)}/markets`);
    return (data as unknown as JsonObject[]).map(parseMarket);
  }

  /**
   * The token this connection signed in with.
   *
   * Exposed so a caller can open a sibling connection on the same identity
   * without holding the password again.
   */
  token(): Token {
    return this._tokenObj;
  }

  /** Whether this connection's user holds ROLE_ADMIN. */
  isAdmin(): boolean {
    return this.hasRole("ROLE_ADMIN");
  }

  /**
   * Whether this connection's user holds ROLE_MANAGER — the role that runs a
   * study: opening and closing sessions, staging allocations, minting
   * passcodes. Python has had it since the management surface landed.
   */
  isManager(): boolean {
    return this.hasRole("ROLE_MANAGER");
  }

  hasRole(role: string): boolean {
    return (this._user?.roles ?? []).includes(role);
  }

  /** The symbols of the marketplace's markets, read from the markets themselves. */
  async symbols(marketplaceId: number): Promise<string[]> {
    return (await this.markets(marketplaceId)).map((m) => m.symbol as string);
  }

  // -- sessions --------------------------------------------------------------

  /**
   * The marketplace's sessions — all of them.
   *
   * There is no server-side filter. The route takes no session argument, so
   * the `sessionIds` this used to accept was silently ignored: it returned the
   * whole history and looked like it had filtered. Filter the result; fm-ui
   * already does.
   */
  async sessions(marketplaceId: number): Promise<Session[]> {
    const url = `${this._marketplace(marketplaceId)}/sessions`;
    return ((await this._get(url)) as unknown as JsonObject[]).map(parseSession);
  }

  async session(marketplaceId: number): Promise<Session> {
    return parseSession(await this._get(`${this._marketplace(marketplaceId)}/sessions/current`));
  }

  // -- orders ----------------------------------------------------------------

  async submitLimit(
    marketplaceId: number,
    marketId: number,
    side: string,
    units: number,
    price: number,
  ): Promise<Order> {
    const data = await this._post(`${this._marketplace(marketplaceId)}/orders`, {
      marketplaceId,
      marketId,
      type: "LIMIT",
      side,
      units,
      price,
      clientDescription: this._clientDescription,
    });
    return parseOrder(data);
  }

  /**
   * Cross the book: buy at the highest price this market allows, sell at the
   * lowest. Immediate or cancel — whatever does not fill is cancelled.
   *
   * There is no market order on the server. Its type switch falls through to
   * `LIMIT`, so every submission is bounds-checked against the market and must
   * sit on a tick — which is why this asks the marketplace for the market
   * first, and costs a round trip {@link submitLimit} does not.
   *
   * The cancel is unconditional: the exchange consumes a cancel by itself when
   * no units remain, so a complete fill costs a harmless round trip rather than
   * an inspection that would race the book. Without it, a market order that did
   * not fill would rest at the market's extreme — the best price in the book,
   * standing, for anyone to take.
   *
   * Returns the limit order as submitted. What it filled is a property of the
   * book afterwards, not of this value.
   */
  async submitMarket(
    marketplaceId: number,
    marketId: number,
    side: string,
    units: number,
  ): Promise<Order> {
    const market = await this._market(marketplaceId, marketId);
    const limit = await this.submitLimit(
      marketplaceId, marketId, side, units, marketableLimit(market, side),
    );

    try {
      await this.submitCancel(marketplaceId, marketId, limit.id);
    } catch (e) {
      // The order is placed. Reporting only "cancel failed" would invite a
      // caller to retry the whole thing and trade twice.
      throw new FlexemarketsError(
        `Order ${limit.id} was placed but its remainder could not be cancelled; ` +
          `it may still be resting. Do not resubmit — cancel it. (${String(e)})`,
      );
    }

    return limit;
  }

  private async _market(marketplaceId: number, marketId: number): Promise<Market> {
    for (const candidate of await this.markets(marketplaceId)) {
      if (candidate.id === marketId) return candidate;
    }
    throw new InvalidArgumentError(
      `Market ${marketId} is not in marketplace ${marketplaceId}`,
    );
  }

  /**
   * DELETE the order: the server builds the CANCEL from the order's own
   * lineage and market, and answers with it. `marketId` is no longer sent --
   * the order already says which market it is in.
   */
  async submitCancel(
    marketplaceId: number,
    marketId: number,
    originalId: number,
  ): Promise<Order> {
    return parseOrder(await this._deleteFor(`${this._marketplace(marketplaceId)}/orders/${originalId}`));
  }

  /**
   * The active-orders snapshot: every resting limit order on the
   * marketplace's current session, plus the `x-fm-as-of-seq` sequence
   * the snapshot was read at. Used by `Desk` seeding
   * — clients apply WS deltas whose seq is greater than the returned
   * value and skip those whose seq is less than or equal.
   */
  async activeOrders(marketplaceId: number): Promise<Snapshot<Order[]>> {
    const url = `${this._marketplace(marketplaceId)}/orders?state=ACTIVE`;
    const { data, asOfSeq } = await this._getSnapshot(url);
    const orders = ordersOf(data).map(parseOrder);
    return { body: orders, asOfSeq };
  }

  /**
   * The recent-trades snapshot, for seeding the trade-history tape.
   * Same `x-fm-as-of-seq` contract as `activeOrders`. Server caps
   * at 5000; default size is 1000.
   *
   * Ordering is the server's and has changed: up to and including fm-server
   * 4.3.1 this answered newest first, later versions answer oldest first.
   * Either way it is the newest `size` trades that come back — only their
   * order differs. `Tape` sorts what it is given, so a caller seeding a tape
   * through `Desk` is unaffected; a caller reading this list directly
   * should not assume one.
   *
   * With `marketId`, only that market's legs (`&market=`), `size` of them,
   * two to a trade. What `Desk` seeds each tape from: read for every market
   * at once, a busy market's legs fill the limit and a quiet market's tape
   * comes up empty, though it has traded.
   */
  async recentTrades(marketplaceId: number, size = 1000, marketId?: number): Promise<Snapshot<Order[]>> {
    const market = marketId === undefined ? "" : `&market=${marketId}`;
    const url = `${this._marketplace(marketplaceId)}/orders?state=TRADED${market}&limit=${size}`;
    const { data, asOfSeq } = await this._getSnapshot(url);
    const orders = ordersOf(data).map(parseOrder);
    return { body: orders, asOfSeq };
  }

  /**
   * The marketplace's orders.
   *
   * With no option, the current session's orders less every cancelled one, the
   * CANCEL that consumed it, and both legs of a self-cross -- what a study
   * ranking participants needs. Without `cancelled=false` the V1 read is the
   * raw lifecycle, which is what `sessionIds` asks for: every LIMIT and CANCEL
   * row of the named runs, as the exchange stored them. `symbol` reads one
   * market's active orders, with the symbol filled in.
   */
  async orders(
    marketplaceId: number,
    options?: { symbol?: string; sessionIds?: number[] },
  ): Promise<Order[]> {
    const base = `${this._marketplace(marketplaceId)}/orders`;
    if (options?.symbol != null) {
      const symbol = options.symbol;
      const data = await this._get(`${base}?state=ACTIVE&symbol=${encodeURIComponent(symbol)}`);
      const orders = (data as unknown as JsonObject[]).map(parseOrder);
      for (const o of orders) o.symbol = symbol;
      return orders;
    }
    const url = options?.sessionIds != null && options.sessionIds.length > 0
      ? `${base}?sessions=${options.sessionIds.join(",")}`
      : `${base}?cancelled=false`;
    return ((await this._get(url)) as unknown as JsonObject[]).map(parseOrder);
  }

  /**
   * One market's traded legs, as many as the server gives in one read
   * ({@link MAX_TRADED_LEGS}), in the server's order.
   *
   * The answer carries the trade id in `original` and no symbol on the orders,
   * because the query already fixed the symbol. Both are filled in before
   * returning, which is what makes the result a trade list rather than a set of
   * half-populated orders. Sort by `lastModifiedDate` if you want time order.
   */
  async trades(marketplaceId: number, symbol: string): Promise<Order[]> {
    const url =
      `${this._marketplace(marketplaceId)}/orders?state=TRADED` +
      `&symbol=${encodeURIComponent(symbol)}&limit=${MAX_TRADED_LEGS}`;
    const data = await this._get(url);
    const orders = (data as unknown as JsonObject[]).map(parseOrder);
    for (const o of orders) {
      o.id = o.original;
      o.symbol = symbol;
    }
    return orders;
  }

  // -- holdings --------------------------------------------------------------

  /** Comma-separated ids, matching the server's `?sessions=` filter. */
  async holdings(
    marketplaceId: number,
    sessionIds?: number[] | null,
  ): Promise<Holding[]> {
    let url = `${this._marketplace(marketplaceId)}/holdings`;
    if (sessionIds && sessionIds.length > 0) url += `?sessions=${sessionIds.join(",")}`;
    const data = await this._get(url);
    return (data as unknown as JsonObject[]).map(parseHolding);
  }

  async holding(marketplaceId: number): Promise<Holding> {
    return parseHolding(await this._get(`${this._marketplace(marketplaceId)}/participants/me/holding`));
  }

  // -- connections -----------------------------------------------------------

  /**
   * Who is attached to the marketplace — all of them.
   *
   * No server-side filter, for the reason {@link sessions} gives. A connection
   * carries the session it belonged to, so "who was present in that run" is a
   * filter on the result.
   */
  async connections(marketplaceId: number): Promise<ClientConnection[]> {
    const url = `${this._marketplace(marketplaceId)}/connections`;
    return ((await this._get(url)) as unknown as JsonObject[]).map(parseConnection);
  }

  // -- management ------------------------------------------------------------
  //
  // Running an experiment, as opposed to trading in one: set the opening
  // positions, open the session, close it, collect the result. Authorization
  // stays the server's business — these need a manager or admin, and it
  // answers 401/403 when they are not.

  /**
   * Create a marketplace from its JSON definition, returning what was made.
   *
   * Takes JSON rather than arguments because that is how the definitions
   * exist: a study keeps its marketplace as a document it can print, diff and
   * hand to someone, and the CLI's dry-run prints exactly the document that
   * would be posted. Assembling it from arguments here would mean the thing
   * printed and the thing sent were built by different code.
   *
   * Parsed before it is sent, so a malformed definition fails here rather than
   * as a 400 describing a document the caller never sees.
   */
  async createMarketplaceFromJson(definition: string): Promise<Marketplace> {
    let parsed: unknown;
    try {
      parsed = JSON.parse(definition);
    } catch (e) {
      throw new InvalidArgumentError(
        `Marketplace definition is not valid JSON: ${(e as Error).message}`,
      );
    }
    return parseMarketplace(await this._post(this._v1("/marketplaces"), parsed));
  }

  /** Opens the marketplace's session, returning it in its new state. */
  async openSession(marketplaceId: number): Promise<Session> {
    return this._sessionState(marketplaceId, "OPEN");
  }

  async pauseSession(marketplaceId: number): Promise<Session> {
    return this._sessionState(marketplaceId, "PAUSED");
  }

  async closeSession(marketplaceId: number): Promise<Session> {
    return this._sessionState(marketplaceId, "CLOSED");
  }

  /**
   * A session's lifecycle is its state, PATCHed. Asking for the state it is
   * already in changes nothing; one it cannot reach from where it is --
   * pausing a closed session -- is refused.
   */
  private async _sessionState(marketplaceId: number, state: string): Promise<Session> {
    return parseSession(
      await this._patch(`${this._marketplace(marketplaceId)}/sessions/current`, { state }),
    );
  }

  /** Everyone in the caller's account. */
  async users(): Promise<Person[]> {
    const data = await this._get(this._v1("/users"));
    return (data as unknown as JsonObject[]).map((u) => parsePerson(u) as Person);
  }

  /** The opening positions of one allocation. */
  async allotments(marketplaceId: number, allocationId: number): Promise<Allotment[]> {
    const url = `${this._marketplace(marketplaceId)}/allocations/${allocationId}/allotments`;
    const data = await this._get(url);
    return (data as unknown as JsonObject[]).map(parseAllotment);
  }

  /**
   * Stage the opening positions for the next session.
   *
   * Staged, not applied: an allocation lands when a *closed* session is opened,
   * and pausing and re-opening does not consume it. Calling this against a live
   * session appears to succeed and changes nobody's position.
   *
   * Takes Holdings because that is the shape a caller reads positions in and
   * computes with; the allotment encoding is applied here.
   */
  async allocate(marketplaceId: number, holdings: Holding[]): Promise<Holding[]> {
    const url = `${this._marketplace(marketplaceId)}/allocations`;
    const body = holdings.map((h) => holdingToAllotment(marketplaceId, h));
    const data = await this._post(url, body);
    return allotmentsToHoldings((data as unknown as JsonObject[]).map(parseAllotment));
  }

  /**
   * The holdings CSV, verbatim, for the current session or for given ones.
   *
   * The same route as {@link holdings}, asked for `text/csv`: the format is
   * chosen by the Accept header, not by a `/downloads` segment.
   */
  async downloadHoldings(marketplaceId: number, sessionIds?: number[] | null): Promise<string> {
    let url = `${this._marketplace(marketplaceId)}/holdings`;
    if (sessionIds && sessionIds.length > 0) url += `?sessions=${sessionIds.join(",")}`;
    return this._getText(url);
  }

  /**
   * Load opening positions from a holdings CSV. Stages the next allocation on
   * the same terms as {@link allocate}.
   */
  async uploadHoldings(marketplaceId: number, filename: string): Promise<Holding[]> {
    const url = `${this._marketplace(marketplaceId)}/allocations`;
    const data = (await this._sendCsv("POST", url, filename)) as JsonObject[];
    return allotmentsToHoldings(data.map(parseAllotment));
  }

  /**
   * Load per-participant private state from a CSV, returning what was stored.
   *
   * Staged on the same terms as {@link uploadHoldings}, against the allocation
   * that call staged: it lands when a closed session is opened, and the order
   * is a correctness constraint -- holdings, then state, then open. With no
   * allocation staged the server refuses.
   *
   * The file keys on an `email` column. Every other column becomes a field of
   * that name: a numeric cell is a number, otherwise text; a column whose
   * header is bracketed, `[valuations]`, holds vectors and its cells are JSON
   * arrays. An `id` column, when present, must agree with the person the email
   * resolves to. A study's existing values file needs no change.
   */
  async uploadState(marketplaceId: number, filename: string): Promise<ParticipantState[]> {
    const url = `${this._marketplace(marketplaceId)}/state`;
    const data = (await this._sendCsv("PUT", url, filename)) as JsonObject[];
    return data.map(parseParticipantState);
  }

  /**
   * Push widgets to participants' screens, returning them as stored.
   *
   * One request for the whole list, which is what the server's rate limit is
   * shaped for: a session-open that tells sixty traders their values is one
   * push of sixty, not sixty pushes. A push to a target and key that already
   * has a widget replaces it.
   *
   * Not staged, unlike an allocation: it reaches the screen as soon as the
   * server stores it, and it is cleared when the session closes.
   *
   * The server checks the content and refuses a list with anything wrong in it
   * as an InvalidArgumentError; nothing in the list is stored. Pushing faster
   * than the server allows is answered 429, which arrives as an HttpError with
   * that status: back off and push again.
   */
  async pushWidgets(marketplaceId: number, widgets: WidgetPush[]): Promise<Widget[]> {
    const url = `${this._marketplace(marketplaceId)}/widgets`;
    const data = await this._post(url, widgets.map(widgetPushJson));
    return (data as unknown as JsonObject[]).map(parseWidget);
  }

  /**
   * Take down a widget: the marketplace's for `key`, or one participant's.
   *
   * True if one was removed, false if there was none to remove. With `userId`,
   * only that participant's widget goes -- `participants/{userId}/widgets/{key}`
   * -- and their marketplace widget for the same key, if any, shows again.
   *
   * An empty 404 is the server saying nothing was there. A 404 carrying a
   * failure document -- no such marketplace -- still throws, so a robot pointed
   * at the wrong marketplace does not read its clean-up as done.
   */
  async removeWidget(marketplaceId: number, key: string, userId?: number): Promise<boolean> {
    const owner = userId !== undefined && userId !== null ? `/participants/${userId}` : "";
    const url = `${this._marketplace(marketplaceId)}${owner}/widgets/${encodeURIComponent(key)}`;
    const resp = await fetch(url, {
      method: "DELETE",
      headers: { ...this._authHeaders(), Accept: "application/json", "User-Agent": FM_NETWORK_CLIENT },
    });
    const body = await resp.text();
    if (resp.status === 404 && body.trim() === "") return false;
    checkResponse(resp, body);
    return true;
  }

  /**
   * Every widget pushed to the marketplace and still standing, for every
   * participant -- what a manager reads to check on a robot.
   */
  async allWidgets(marketplaceId: number): Promise<Widget[]> {
    const data = await this._get(`${this._marketplace(marketplaceId)}/widgets?participant=all`);
    return (data as unknown as JsonObject[]).map(parseWidget);
  }

  // -- events / WebSocket ----------------------------------------------------

  private readonly _sharedViews = new Map<number, { desk: DefaultDesk; refCount: number }>();
  private readonly _sharedViewPromises = new Map<number, Promise<DefaultDesk>>();

  /**
   * Open a stateful Desk on this marketplace. Multiple calls
   * for the same marketplaceId share a single underlying desk + WS
   * subscription + materialized state within this Flexemarkets
   * instance — each call returns a fresh handle, the handles
   * refcount, and the shared resources tear down on the last close.
   *
   * Sharing is intentionally per-Flexemarkets (i.e. per-bearer). Two
   * callers with different identities each get their own desk —
   * multi-tenant WS multiplexing is a server-side concern, not a
   * client-side one.
   */
  async desk(marketplaceId: number): Promise<Desk> {
    const existing = this._sharedViews.get(marketplaceId);
    if (existing !== undefined) {
      existing.refCount++;
      return new DeskHandle(existing.desk, () => this._releaseSharedView(marketplaceId));
    }
    // Two concurrent desk() calls for the same marketplaceId need
    // to dedupe — JS is single-threaded but awaits introduce
    // interleaving. Cache the in-flight Promise so the second caller
    // awaits the first's construction instead of racing a duplicate
    // WS subscription into existence.
    let p = this._sharedViewPromises.get(marketplaceId);
    if (p === undefined) {
      p = DefaultDesk.open(this, marketplaceId);
      this._sharedViewPromises.set(marketplaceId, p);
    }
    let shared: DefaultDesk;
    try {
      shared = await p;
    } finally {
      this._sharedViewPromises.delete(marketplaceId);
    }
    // Another caller may have arrived during the await and registered
    // first. Re-check; if so, throw away our just-built desk and use
    // theirs.
    const registered = this._sharedViews.get(marketplaceId);
    if (registered !== undefined) {
      if (registered.desk !== shared) shared.close();
      registered.refCount++;
      return new DeskHandle(registered.desk, () => this._releaseSharedView(marketplaceId));
    }
    this._sharedViews.set(marketplaceId, { desk: shared, refCount: 1 });
    return new DeskHandle(shared, () => this._releaseSharedView(marketplaceId));
  }

  _releaseSharedView(marketplaceId: number): void {
    const entry = this._sharedViews.get(marketplaceId);
    if (entry === undefined) return;
    if (--entry.refCount <= 0) {
      this._sharedViews.delete(marketplaceId);
      entry.desk.close();
    }
  }

  /** Start receiving real-time events via WebSocket STOMP. */
  async listen(marketplaceId: number, callback: EventCallback): Promise<void> {
    this._eventListener = await this._connectEvents(marketplaceId, callback);
  }

  /**
   * Open an *independent* event subscription, delivering to `callback` until
   * the returned unsubscribe function is invoked.
   *
   * Unlike {@link listen}, which is one per connection and replaces itself,
   * several of these coexist: each has its own stream and its own lifetime.
   * That is what lets more than one Desk live in one connection without
   * trampling each other — the mechanism was already here for exactly that, as
   * the package-private `_connectEvents`, but a caller who wanted a second
   * stream of their own had no way to ask for one.
   *
   * Returns an unsubscribe function rather than an object with `close()`,
   * matching what Desk's `on*` handlers already return here. Java
   * returns a `Subscription`; both names describe the same lifetime.
   */
  async subscribe(marketplaceId: number, callback: EventCallback): Promise<Subscription> {
    const listener = await this._connectEvents(marketplaceId, callback);
    return () => {
      void listener.close();
    };
  }

  /**
   * Package-private helper used by {@link DefaultDesk} (Phase 2d)
   * to own its own EventListener subscription rather than clobbering
   * the singleton {@link #_eventListener}. Lets multiple
   * desk(marketplaceId) calls coexist within one Flexemarkets.
   */
  async _connectEvents(marketplaceId: number, callback: EventCallback): Promise<EventListener> {
    const wsUrl =
      server(this._endpoint)
        .replace("https://", "wss://")
        .replace("http://", "ws://") + "/events";
    const events = new EventListener(
      wsUrl,
      this._bearerToken,
      marketplaceId,
      callback,
      this._clientDescription,
      parseHolding,
      parseOrder,
    );
    await events.start();
    return events;
  }

  /** Reconnect the WebSocket after a transport error. */
  async reconnect(): Promise<void> {
    if (this._eventListener !== null) {
      await this._eventListener.reconnect();
    }
  }

  // -- lifecycle -------------------------------------------------------------

  close(): void {
    if (this._eventListener !== null) {
      this._eventListener.close();
      this._eventListener = null;
    }
    // Force-close any remaining shared Desks — safety net for
    // callers who didn't close their handles first.
    for (const entry of this._sharedViews.values()) {
      try { entry.desk.close(); } catch { /* best-effort */ }
    }
    this._sharedViews.clear();
  }
}

// ---------------------------------------------------------------------------
// Authentication
// ---------------------------------------------------------------------------

async function signIn(
  baseUrl: string,
  config: Record<string, string>,
  clientDescription: string,
): Promise<Token> {
  const tok = config.token ?? "";
  if (tok && isValidToken(tok)) {
    // A caller who already holds a token has no account/email/password to
    // present, so signing in is not available: POSTing /tokens with blanks is
    // rejected -- the server answers 400 MESSAGE_NOT_READABLE for an empty
    // password, which is what this used to send. Refreshing the token both
    // validates it and returns the account and person behind it.
    //
    // This is the third time. fm-lib-net carried the branch, an earlier rewrite
    // dropped it, and the Java SDK restored it with a test. This SDK and the
    // Python one never had it, so token auth returned 400 in both from the day
    // it was written.
    // POST, as minting a token is (fm-server 4.6).
    const resp = await fetch(`${baseUrl}/tokens/refresh`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${tok}`,
        Accept: "application/json",
        "User-Agent": FM_NETWORK_CLIENT,
      },
    });
    const body = await resp.text();
    checkResponse(resp, body);
    return parseToken(readBody(body));
  }

  const acct = config.account ?? "";
  const email = config.email ?? "";
  const password = config.password ?? "";

  if (!acct) throw new ConfigurationError("Missing 'account' in configuration.");
  if (!email) throw new ConfigurationError("Missing 'email' in configuration.");
  if (!password) throw new ConfigurationError("Missing 'password' in configuration.");

  const authUrl = `${baseUrl}/tokens`;
  const resp = await fetch(authUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      "User-Agent": FM_NETWORK_CLIENT,
    },
    body: JSON.stringify({
      username: `${acct}|${email}`,
      password,
    }),
  });
  const body = await resp.text();
  checkResponse(resp, body);
  return parseToken(readBody(body));
}
