/**
 * Flexemarkets API client.
 *
 * Port of fm.client (Python) / fm.Flexemarkets (Java).
 */

import { readFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import type {
  Account,
  ApiRoot,
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
import { EventListener, type EventCallback } from "./stomp.js";

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
    connectionId: (data.connectionId as number) ?? 0,
    ownerId: (data.ownerId as number) ?? 0,
    established: (data.established as string) ?? null,
    terminated: (data.terminated as string) ?? null,
    description: (data.description as string) ?? null,
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
  const idx = endpoint.indexOf("/api");
  return idx < 0 ? endpoint : endpoint.substring(0, idx + 4);
}

function resourceId(endpoint: string): number {
  const trimmed = endpoint.replace(/\/+$/, "");
  const last = trimmed.lastIndexOf("/");
  return parseInt(trimmed.substring(last + 1), 10);
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
      if (existsSync(endpoint)) {
        Object.assign(config, loadPropertiesFile(endpoint));
      } else {
        config.endpoint = endpoint;
      }
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
    for (const o of orders) o.symbol = symbol;
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
    const url = uriIdSegmentParam(
      this._apiRoot,
      "marketplaces",
      marketplaceId,
      "connections",
      sessionIdsParam(sessionIds ?? null),
    );
    const data = await this._get(url);
    return (data as unknown as JsonObject[]).map(parseConnection);
  }

  // -- events / WebSocket ----------------------------------------------------

  /** Start receiving real-time events via WebSocket STOMP. */
  async listen(marketplaceId: number, callback: EventCallback): Promise<void> {
    const wsUrl =
      server(this._endpoint)
        .replace("https://", "wss://")
        .replace("http://", "ws://") + "/events";

    this._eventListener = new EventListener(
      wsUrl,
      this._bearerToken,
      marketplaceId,
      callback,
      this._clientDescription,
      parseHolding,
      parseOrder,
    );
    await this._eventListener.start();
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
