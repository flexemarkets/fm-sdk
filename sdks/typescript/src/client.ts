/**
 * Flexemarkets API client.
 *
 * Port of fm.client (Python) / fm.Flexemarkets (Java).
 */

import { readFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { basename, join } from "node:path";
import { fileURLToPath } from "node:url";
import type {
  Account,
  Allotment,
  ApiRoot,
  Assets,
  ClientConnection,
  Holding,
  Market,
  Marketplace,
  Order,
  Person,
  Security,
  Session,
  Token,
} from "./types.js";
import { EventListener, NO_SEQ, type EventCallback } from "./stomp.js";
import { DefaultMarketView, MarketViewHandle, type MarketView } from "./market-view.js";
import type { Snapshot } from "./snapshot.js";

function readVersion(): string {
  try {
    const dir = fileURLToPath(new URL(".", import.meta.url));
    return readFileSync(join(dir, "..", "..", "..", "VERSION"), "utf-8").trim();
  } catch {
    return "0.0.0";
  }
}

const FM_NETWORK_CLIENT = `fm-sdk-typescript/${readVersion()}`;
const DEFAULT_ENDPOINT = "https://api.flexemarkets.com";

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

// ---------------------------------------------------------------------------
// JSON → type helpers
// ---------------------------------------------------------------------------

type JsonObject = Record<string, unknown>;

function parsePerson(data: JsonObject | null | undefined): Person | null {
  if (!data) return null;
  return {
    id: (data.id as number) ?? 0,
    accountId: (data.accountId as number) ?? 0,
    firstName: (data.firstName as string) ?? null,
    lastName: (data.lastName as string) ?? null,
    email: (data.email as string) ?? null,
    roles: (data.roles as string[]) ?? [],
    accountOwner: (data.accountOwner as boolean) ?? false,
    createdDate: (data.createdDate as string) ?? null,
    lastModifiedDate: (data.lastModifiedDate as string) ?? null,
  };
}

function parseAccount(data: JsonObject | null | undefined): Account | null {
  if (!data) return null;
  return {
    id: (data.id as number) ?? null,
    name: (data.name as string) ?? null,
    description: (data.description as string) ?? null,
    owner: parsePerson(data.owner as JsonObject),
    approval: (data.approval as boolean) ?? false,
    approvalDescription: (data.approvalDescription as string) ?? null,
    createdDate: (data.createdDate as string) ?? null,
    lastModifiedDate: (data.lastModifiedDate as string) ?? null,
  };
}

function parseToken(data: JsonObject): Token {
  return {
    requestUrl: (data.requestUrl as string) ?? null,
    person: parsePerson(data.person as JsonObject),
    account: parseAccount(data.account as JsonObject),
    token: (data.token as string) ?? null,
  };
}

function parseSecurity(data: JsonObject): Security {
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

function parseMarket(data: JsonObject): Market {
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

function parseMarketplace(data: JsonObject): Marketplace {
  return {
    id: (data.id as number) ?? 0,
    name: (data.name as string) ?? null,
    description: (data.description as string) ?? null,
    markets: ((data.markets as JsonObject[]) ?? []).map(parseMarket),
  };
}

function parseSession(data: JsonObject): Session {
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

export function parseOrder(data: JsonObject): Order {
  return {
    id: (data.id as number) ?? 0,
    original: (data.original as number) ?? 0,
    supplier: (data.supplier as number) ?? 0,
    consumer: (data.consumer as number | null) ?? null,
    type: (data.type as string) ?? null,
    side: (data.side as string) ?? null,
    units: (data.units as number) ?? 0,
    price: (data.price as number) ?? 0,
    ownerId: (data.ownerId as number) ?? null,
    marketplaceId: (data.marketplaceId as number) ?? 0,
    sessionId: (data.sessionId as number) ?? 0,
    symbol: (data.symbol as string) ?? null,
    marketId: (data.marketId as number) ?? 0,
    ownerTarget: (data.ownerTarget as string) ?? null,
    clientDescription: (data.clientDescription as string) ?? null,
    createdDate: (data.createdDate as string) ?? null,
    lastModifiedDate: (data.lastModifiedDate as string) ?? null,
  };
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
      securities: securitiesRaw.map(parseSecurity),
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
    securities: securitiesRaw.map(parseSecurity),
  };
}

function parseConnection(data: JsonObject): ClientConnection {
  return {
    marketplaceId: (data.marketplaceId as number) ?? 0,
    connectionId: (data.id as number) ?? (data.connectionId as number) ?? 0,
    ownerId: (data.ownerId as number) ?? 0,
    established: (data.established as string) ?? null,
    terminated: (data.terminated as string) ?? null,
    description: (data.description as string) ?? null,
    sessionId: (data.sessionId as number) ?? null,
  };
}

function parseApiRoot(data: JsonObject): ApiRoot {
  const linksRaw = (data._links as Record<string, unknown>) ?? {};
  const links: Record<string, string> = {};
  for (const [name, value] of Object.entries(linksRaw)) {
    if (typeof value === "object" && value !== null && "href" in value) {
      links[name] = (value as { href: string }).href ?? "";
    } else if (typeof value === "string") {
      links[name] = value;
    }
  }
  return { links };
}

// ---------------------------------------------------------------------------
// HATEOAS link resolution
// ---------------------------------------------------------------------------

function processTemplate(href: string): string {
  const idx = href.indexOf("{");
  return idx >= 0 ? href.substring(0, idx) : href;
}

function uri(root: ApiRoot, linkName: string): string {
  const href = root.links[linkName];
  if (href === undefined) throw new Error(`Link '${linkName}' not found in API root.`);
  return processTemplate(href);
}

function uriId(root: ApiRoot, linkName: string, id: number): string {
  return `${uri(root, linkName)}/${id}`;
}

function uriIdSegment(root: ApiRoot, linkName: string, id: number, segment: string): string {
  return `${uriId(root, linkName, id)}/${segment}`;
}

function uriParam(root: ApiRoot, linkName: string, param: string): string {
  return `${uri(root, linkName)}?${param}`;
}

function uriIdSegmentParam(
  root: ApiRoot,
  linkName: string,
  id: number,
  segment: string,
  param: string,
): string {
  const base = uriIdSegment(root, linkName, id, segment);
  return param ? `${base}?${param}` : base;
}

function uriParamMarketplaceIdParam(
  root: ApiRoot,
  linkName: string,
  id: number,
  param: string | null,
): string {
  let u = uriParam(root, linkName, `marketplaceId=${id}`);
  if (param) u += `&${param}`;
  return u;
}

// ---------------------------------------------------------------------------
// Credential / configuration helpers
// ---------------------------------------------------------------------------

function isValidToken(value: string): boolean {
  return BCRYPT_RE.test(value) || JWT_RE.test(value);
}

function server(endpoint: string): string {
  // Locate "/api" in the path, not in the scheme/host. A host like
  // "https://api.flexemarkets.com" otherwise matches at the "//api" of the
  // host and truncates the base URL to "https://api" (unresolvable). Skip
  // past the scheme + host before searching for the "/api" path segment.
  const scheme = endpoint.indexOf("://");
  const pathStart = scheme >= 0 ? endpoint.indexOf("/", scheme + 3) : 0;
  if (pathStart < 0) return endpoint;
  const idx = endpoint.indexOf("/api", pathStart);
  return idx < 0 ? endpoint : endpoint.substring(0, idx + 4);
}

function resourceId(endpoint: string): number {
  const trimmed = endpoint.replace(/\/+$/, "");
  const last = trimmed.lastIndexOf("/");
  return parseInt(trimmed.substring(last + 1), 10);
}

/** A bare marketplace id resolves to that marketplace on the default production host. */
function marketplaceEndpoint(marketplaceId: string): string {
  return `${DEFAULT_ENDPOINT}/api/marketplaces/${marketplaceId}`;
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

function sessionIdsParam(sessionIds: number[] | null): string {
  if (!sessionIds || sessionIds.length === 0) return "";
  return "sessionIds=" + sessionIds.join(",");
}

function loadPropertiesFile(path: string): Record<string, string> {
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

function loadConfig(): Record<string, string> {
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

function checkResponse(response: Response, body: string): void {
  const status = response.status;
  if (status >= 200 && status < 300) return;
  if (status === 400) throw new InvalidArgumentError(body);
  if (status === 401) throw new AuthenticationError(body);
  if (status === 403) throw new AuthorizationError(body);
  if (status >= 500) throw new ConnectionFailedError(body);
  throw new FlexemarketsError(`HTTP ${status}: ${body}`);
}

// ---------------------------------------------------------------------------
// Flexemarkets client
// ---------------------------------------------------------------------------

export interface FlexemarketsOptions {
  credential?: string;
  endpoint?: string;
  clientDescription?: string;
}

export class Flexemarkets {
  private readonly _clientDescription: string;
  private readonly _endpoint: string;
  private readonly _baseUrl: string;
  private readonly _bearerToken: string;
  private _apiRoot!: ApiRoot;
  private _account!: Account;
  private _user!: Person;
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
    fm._account = tokenObj.account!;
    fm._user = tokenObj.person!;

    // Fetch API root for HATEOAS links
    fm._apiRoot = await fm._fetchApiRoot();

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
        Accept: "application/json, application/hal+json",
        "User-Agent": FM_NETWORK_CLIENT,
      },
    });
    const body = await resp.text();
    checkResponse(resp, body);
    return JSON.parse(body);
  }

  /**
   * GET helper that returns the parsed body bundled with the
   * `x-fm-as-of-seq` response header so callers (notably MarketView)
   * can correlate the snapshot with the WS delta stream. Returns
   * `Snapshot.NO_SEQ` when the header is absent.
   */
  private async _getSnapshot(url: string): Promise<{ data: JsonObject; asOfSeq: number }> {
    const resp = await fetch(url.startsWith("/") ? `${this._baseUrl}${url}` : url, {
      headers: {
        ...this._authHeaders(),
        Accept: "application/json, application/hal+json",
        "User-Agent": FM_NETWORK_CLIENT,
      },
    });
    const body = await resp.text();
    checkResponse(resp, body);
    const raw = resp.headers.get("x-fm-as-of-seq");
    const asOfSeq = raw === null ? NO_SEQ : Number.parseInt(raw, 10);
    return { data: JSON.parse(body), asOfSeq: Number.isFinite(asOfSeq) ? asOfSeq : NO_SEQ };
  }

  /**
   * PATCH with no body — the shape every session transition takes: the verb and
   * the path carry the whole request.
   */
  private async _patch(url: string): Promise<JsonObject> {
    const resp = await fetch(url.startsWith("/") ? `${this._baseUrl}${url}` : url, {
      method: "PATCH",
      headers: {
        ...this._authHeaders(),
        Accept: "application/json, application/hal+json",
        "User-Agent": FM_NETWORK_CLIENT,
      },
    });
    const body = await resp.text();
    checkResponse(resp, body);
    return JSON.parse(body);
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
        Accept: "application/json, application/hal+json",
        "User-Agent": FM_NETWORK_CLIENT,
      },
      body: JSON.stringify(json),
    });
    const body = await resp.text();
    checkResponse(resp, body);
    return JSON.parse(body);
  }

  private async _fetchApiRoot(): Promise<ApiRoot> {
    const data = await this._get(this._baseUrl);
    return parseApiRoot(data);
  }

  // ======================================================================
  // REST APIs
  // ======================================================================

  // -- marketplaces ----------------------------------------------------------

  async marketplaces(): Promise<Marketplace[]> {
    const url = uriParam(this._apiRoot, "marketplaces", "format=application/json");
    const data = await this._get(url);
    return (data as unknown as JsonObject[]).map(parseMarketplace);
  }

  async marketplace(marketplaceId: number): Promise<Marketplace> {
    const url = uriId(this._apiRoot, "marketplaces", marketplaceId);
    return parseMarketplace(await this._get(url));
  }

  // -- markets ---------------------------------------------------------------

  async markets(marketplaceId: number): Promise<Market[]> {
    const url = uriIdSegmentParam(
      this._apiRoot,
      "marketplaces",
      marketplaceId,
      "markets",
      "format=application/json",
    );
    const data = await this._get(url);
    return (data as unknown as JsonObject[]).map(parseMarket);
  }

  async symbols(marketplaceId: number): Promise<string[]> {
    const url = uriIdSegment(this._apiRoot, "marketplaces", marketplaceId, "symbols");
    return (await this._get(url)) as unknown as string[];
  }

  // -- sessions --------------------------------------------------------------

  async sessions(
    marketplaceId: number,
    sessionIds?: number[] | null,
  ): Promise<Session[]> {
    let url: string;
    if (sessionIds && sessionIds.length > 0) {
      url = uriIdSegmentParam(
        this._apiRoot,
        "marketplaces",
        marketplaceId,
        "sessions",
        `${sessionIdsParam(sessionIds)}&format=application/json`,
      );
    } else {
      url = uriIdSegmentParam(
        this._apiRoot,
        "marketplaces",
        marketplaceId,
        "sessions",
        "format=application/json",
      );
    }
    const data = await this._get(url);
    return (data as unknown as JsonObject[]).map(parseSession);
  }

  async session(marketplaceId: number): Promise<Session> {
    const url = uriIdSegment(
      this._apiRoot,
      "marketplaces",
      marketplaceId,
      "currentSession",
    );
    return parseSession(await this._get(url));
  }

  // -- orders ----------------------------------------------------------------

  async submitLimit(
    marketplaceId: number,
    marketId: number,
    side: string,
    units: number,
    price: number,
  ): Promise<Order> {
    const url = uri(this._apiRoot, "orders");
    const data = await this._post(url, {
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

  async submitCancel(
    marketplaceId: number,
    marketId: number,
    originalId: number,
  ): Promise<Order> {
    const url = uri(this._apiRoot, "orders");
    const data = await this._post(url, {
      marketplaceId,
      marketId,
      type: "CANCEL",
      id: originalId,
      original: originalId,
      supplier: originalId,
      clientDescription: this._clientDescription,
    });
    return parseOrder(data);
  }

  /**
   * V1 active-orders snapshot: every resting limit order on the
   * marketplace's current session, plus the `x-fm-as-of-seq` sequence
   * the snapshot was read at. Used by `MarketView` Phase 2a seeding
   * — clients apply WS deltas whose seq is greater than the returned
   * value and skip those whose seq is less than or equal.
   */
  async activeOrdersV1(marketplaceId: number): Promise<Snapshot<Order[]>> {
    const baseRest = this._baseUrl;
    const url = `${baseRest}/v1/marketplaces/${marketplaceId}/orders/active`;
    const { data, asOfSeq } = await this._getSnapshot(url);
    const orders = ((data as unknown as { _embedded?: { orderDtoes?: JsonObject[] } })._embedded?.orderDtoes ?? []).map(parseOrder);
    return { body: orders, asOfSeq };
  }

  /**
   * V1 recent-trades snapshot for seeding the trade-history tape.
   * Same `x-fm-as-of-seq` contract as `activeOrdersV1`. Server caps
   * at 5000; default size is 1000.
   */
  async recentTradesV1(marketplaceId: number, size = 1000): Promise<Snapshot<Order[]>> {
    const url = `${this._baseUrl}/v1/marketplaces/${marketplaceId}/orders/recent-trades?size=${size}`;
    const { data, asOfSeq } = await this._getSnapshot(url);
    const orders = ((data as unknown as { _embedded?: { orderDtoes?: JsonObject[] } })._embedded?.orderDtoes ?? []).map(parseOrder);
    return { body: orders, asOfSeq };
  }

  async orders(
    marketplaceId: number,
    options?: { symbol?: string; sessionIds?: number[] },
  ): Promise<Order[]> {
    if (options?.symbol != null) {
      const url = uriParamMarketplaceIdParam(
        this._apiRoot,
        "symbolOrdersJson",
        marketplaceId,
        `symbol=${options.symbol}`,
      );
      const data = await this._get(url);
      const orders = (data as unknown as JsonObject[]).map(parseOrder);
      for (const o of orders) o.symbol = options.symbol;
      return orders;
    }
    if (options?.sessionIds != null) {
      const url = uriParamMarketplaceIdParam(
        this._apiRoot,
        "sessionOrdersJson",
        marketplaceId,
        sessionIdsParam(options.sessionIds),
      );
      const data = await this._get(url);
      return (data as unknown as JsonObject[]).map(parseOrder);
    }
    const url = uriIdSegment(this._apiRoot, "marketplaces", marketplaceId, "orders");
    const data = await this._get(url);
    return (data as unknown as JsonObject[]).map(parseOrder);
  }

  async trades(marketplaceId: number, symbol: string): Promise<Order[]> {
    const url = uriParamMarketplaceIdParam(
      this._apiRoot,
      "symbolTradesJson",
      marketplaceId,
      `symbol=${symbol}`,
    );
    const data = await this._get(url);
    const orders = (data as unknown as JsonObject[]).map(parseOrder);
    for (const o of orders) {
      // The symbol-keyed route answers with the trade id in `original` and no
      // symbol, because the query already fixed it. Filling both in is what
      // makes the result a trade list rather than half-populated orders.
      o.id = o.original;
      o.symbol = symbol;
    }
    return orders;
  }

  // -- holdings --------------------------------------------------------------

  async holdings(
    marketplaceId: number,
    sessionIds?: number[] | null,
  ): Promise<Holding[]> {
    let url: string;
    if (sessionIds && sessionIds.length > 0) {
      url = uriIdSegmentParam(
        this._apiRoot,
        "marketplaces",
        marketplaceId,
        "holdings",
        `sessions=${sessionIds.join(",")}`,
      );
    } else {
      url = uriIdSegment(this._apiRoot, "marketplaces", marketplaceId, "holdings");
    }
    const data = await this._get(url);
    return (data as unknown as JsonObject[]).map(parseHolding);
  }

  async holding(marketplaceId: number): Promise<Holding> {
    const url = uriIdSegment(
      this._apiRoot,
      "marketplaces",
      marketplaceId,
      "currentHolding",
    );
    return parseHolding(await this._get(url));
  }

  // -- connections -----------------------------------------------------------

  async connections(
    marketplaceId: number,
    sessionIds?: number[] | null,
  ): Promise<ClientConnection[]> {
    // Canonical path is /marketplaces/{id}/connections ("/agents" is the
    // retained pre-FM-4 alias); format=application/json yields a plain list
    // (vs the HAL _embedded form).
    const sid = sessionIdsParam(sessionIds ?? null);
    const param = sid ? `${sid}&format=application/json` : "format=application/json";
    const url = uriIdSegmentParam(
      this._apiRoot,
      "marketplaces",
      marketplaceId,
      "connections",
      param,
    );
    const data = await this._get(url);
    return (data as unknown as JsonObject[]).map(parseConnection);
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
    const url = `${server(this._endpoint)}/v1/marketplaces`;
    return parseMarketplace(await this._post(url, parsed));
  }

  /** Opens the marketplace's session, returning it in its new state. */
  async openSession(marketplaceId: number): Promise<Session> {
    return parseSession(
      await this._patch(uriIdSegment(this._apiRoot, "marketplaces", marketplaceId, "open")),
    );
  }

  async pauseSession(marketplaceId: number): Promise<Session> {
    return parseSession(
      await this._patch(uriIdSegment(this._apiRoot, "marketplaces", marketplaceId, "pause")),
    );
  }

  async closeSession(marketplaceId: number): Promise<Session> {
    return parseSession(
      await this._patch(uriIdSegment(this._apiRoot, "marketplaces", marketplaceId, "close")),
    );
  }

  /** Everyone in the caller's account. `usersJson`, not the HAL `users` form. */
  async users(): Promise<Person[]> {
    const data = await this._get(uri(this._apiRoot, "usersJson"));
    return (data as unknown as JsonObject[]).map((u) => parsePerson(u) as Person);
  }

  /** The opening positions of one allocation. A V1 route, not on the API root. */
  async allotments(marketplaceId: number, allocationId: number): Promise<Allotment[]> {
    const url =
      `${server(this._endpoint)}/v1/marketplaces/${marketplaceId}` +
      `/allotments?allocation=${allocationId}`;
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
    const url = uriIdSegment(this._apiRoot, "marketplaces", marketplaceId, "allocations");
    const body = holdings.map((h) => holdingToAllotment(marketplaceId, h));
    const data = await this._post(url, body);
    return allotmentsToHoldings((data as unknown as JsonObject[]).map(parseAllotment));
  }

  /**
   * The holdings CSV, verbatim, for the current session or for given ones.
   *
   * The filter is spelled `sessions=` on this route and `sessionIds=` on
   * sessions and connections. Using the wrong one is not an error — it is an
   * unfiltered answer.
   */
  async downloadHoldings(marketplaceId: number, sessionIds?: number[] | null): Promise<string> {
    if (sessionIds && sessionIds.length > 0) {
      return this._getText(
        uriIdSegmentParam(
          this._apiRoot,
          "marketplaces",
          marketplaceId,
          "holdings/downloads",
          `sessions=${sessionIds.join(",")}`,
        ),
      );
    }
    return this._getText(
      uriIdSegment(this._apiRoot, "marketplaces", marketplaceId, "holdings/downloads"),
    );
  }

  /**
   * Load opening positions from a holdings CSV. Stages the next allocation on
   * the same terms as {@link allocate}.
   */
  async uploadHoldings(marketplaceId: number, filename: string): Promise<Holding[]> {
    const url = uriIdSegment(this._apiRoot, "marketplaces", marketplaceId, "holdings/uploads");
    const form = new FormData();
    form.append("file", new Blob([readFileSync(filename)]), basename(filename));
    const resp = await fetch(url, {
      method: "POST",
      headers: { ...this._authHeaders(), "User-Agent": FM_NETWORK_CLIENT },
      body: form,
    });
    const body = await resp.text();
    checkResponse(resp, body);
    return allotmentsToHoldings((JSON.parse(body) as JsonObject[]).map(parseAllotment));
  }

  // -- events / WebSocket ----------------------------------------------------

  private readonly _sharedViews = new Map<number, { view: DefaultMarketView; refCount: number }>();
  private readonly _sharedViewPromises = new Map<number, Promise<DefaultMarketView>>();

  /**
   * Open a stateful MarketView on this marketplace. Multiple calls
   * for the same marketplaceId share a single underlying view + WS
   * subscription + materialized state within this Flexemarkets
   * instance — each call returns a fresh handle, the handles
   * refcount, and the shared resources tear down on the last close.
   *
   * Sharing is intentionally per-Flexemarkets (i.e. per-bearer). Two
   * callers with different identities each get their own view —
   * multi-tenant WS multiplexing is a server-side concern, not a
   * client-side one.
   */
  async observe(marketplaceId: number): Promise<MarketView> {
    const existing = this._sharedViews.get(marketplaceId);
    if (existing !== undefined) {
      existing.refCount++;
      return new MarketViewHandle(existing.view, () => this._releaseSharedView(marketplaceId));
    }
    // Two concurrent observe() calls for the same marketplaceId need
    // to dedupe — JS is single-threaded but awaits introduce
    // interleaving. Cache the in-flight Promise so the second caller
    // awaits the first's construction instead of racing a duplicate
    // WS subscription into existence.
    let p = this._sharedViewPromises.get(marketplaceId);
    if (p === undefined) {
      p = DefaultMarketView.open(this, marketplaceId);
      this._sharedViewPromises.set(marketplaceId, p);
    }
    let shared: DefaultMarketView;
    try {
      shared = await p;
    } finally {
      this._sharedViewPromises.delete(marketplaceId);
    }
    // Another observer may have arrived during the await and registered
    // first. Re-check; if so, throw away our just-built view and use
    // theirs.
    const registered = this._sharedViews.get(marketplaceId);
    if (registered !== undefined) {
      if (registered.view !== shared) shared.close();
      registered.refCount++;
      return new MarketViewHandle(registered.view, () => this._releaseSharedView(marketplaceId));
    }
    this._sharedViews.set(marketplaceId, { view: shared, refCount: 1 });
    return new MarketViewHandle(shared, () => this._releaseSharedView(marketplaceId));
  }

  _releaseSharedView(marketplaceId: number): void {
    const entry = this._sharedViews.get(marketplaceId);
    if (entry === undefined) return;
    if (--entry.refCount <= 0) {
      this._sharedViews.delete(marketplaceId);
      entry.view.close();
    }
  }

  /** Start receiving real-time events via WebSocket STOMP. */
  async listen(marketplaceId: number, callback: EventCallback): Promise<void> {
    this._eventListener = await this._connectEvents(marketplaceId, callback);
  }

  /**
   * Package-private helper used by {@link DefaultMarketView} (Phase 2d)
   * to own its own EventListener subscription rather than clobbering
   * the singleton {@link #_eventListener}. Lets multiple
   * observe(marketplaceId) calls coexist within one Flexemarkets.
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
    // Force-close any remaining shared MarketViews — safety net for
    // callers who didn't close their handles first.
    for (const entry of this._sharedViews.values()) {
      try { entry.view.close(); } catch { /* best-effort */ }
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
    const authUrl = `${baseUrl}/tokens`;
    const resp = await fetch(authUrl, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${tok}`,
        "Content-Type": "application/json",
        Accept: "application/json",
        "User-Agent": FM_NETWORK_CLIENT,
      },
      body: JSON.stringify({
        username: `${config.account ?? ""}|${config.email ?? ""}`,
        password: "",
      }),
    });
    const body = await resp.text();
    if (resp.status === 401) {
      throw new AuthenticationError("Authentication failed with provided token.");
    }
    checkResponse(resp, body);
    return parseToken(JSON.parse(body));
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
  if (resp.status === 401) {
    throw new AuthenticationError("Authentication failed.");
  }
  checkResponse(resp, body);
  return parseToken(JSON.parse(body));
}
