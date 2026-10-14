/**
 * The TypeScript client reports its own round trips on Client-Timing, as the
 * Java SDK and fm-ui do: each request carries what the previous one cost --
 * `rtt=<ns>`, and `;net=<ns>` when the server's Server-Timing said its own
 * share -- so a robot class's latency shows in fm-server's Connections view.
 * Against a loopback server, so the header asserted is the one sent.
 */
import { test, before, after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";

import { Flexemarkets } from "../src/client.ts";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZAZGV2In0.c2lnbmF0dXJl";
const SIGNED_IN = {
  token: TOKEN, person: { id: 7, accountId: 1, email: "dev@dev" }, account: { id: 1, name: "dev" },
};

/** The Client-Timing of every request after sign-in, in order; null where none was sent. */
const timings: (string | null)[] = [];
let serverTiming: string | null = "st=1000";
let server: Server;
let base: string;

before(async () => {
  server = createServer((req, res) => {
    req.resume();
    req.on("end", () => {
      const path = (req.url ?? "").split("?")[0];
      const signIn = path.startsWith("/api/tokens");
      if (!signIn) timings.push((req.headers["client-timing"] as string | undefined) ?? null);
      const headers: Record<string, string> = { "Content-Type": "application/json" };
      if (serverTiming !== null) headers["Server-Timing"] = serverTiming;
      res.writeHead(200, headers);
      res.end(JSON.stringify(signIn ? SIGNED_IN : []));
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("no port");
  base = `http://127.0.0.1:${address.port}/api`;
});

after(() => { server.close(); });

beforeEach(() => {
  timings.length = 0;
  serverTiming = "st=1000";
});

const field = (header: string, name: string): bigint | null => {
  const m = new RegExp(`(?:^|;)${name}=(\\d+)`).exec(header);
  return m ? BigInt(m[1]) : null;
};

test("the client's first call has nothing to report yet", async () => {
  const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "timing-test");
  try {
    await fm.activeOrders(1);
    assert.equal(timings[0], null);
  } finally { fm.close(); }
});

test("a later call reports what the one before cost, with the server's share taken out", async () => {
  const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "timing-test");
  try {
    await fm.activeOrders(1);
    await fm.activeOrders(1);
    const header = timings[1];
    assert.ok(header !== null);
    const rtt = field(header, "rtt");
    assert.ok(rtt !== null && rtt > 0n);
    const net = field(header, "net");
    assert.equal(net, rtt > 1000n ? rtt - 1000n : 0n);
  } finally { fm.close(); }
});

test("without the server's share the wire is not claimed", async () => {
  serverTiming = null;
  const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "timing-test");
  try {
    await fm.activeOrders(1);
    await fm.activeOrders(1);
    const header = timings[1]!;
    assert.ok((field(header, "rtt") ?? 0n) > 0n);
    assert.ok(!header.includes("net="));
  } finally { fm.close(); }
});

test("each figure is reported once", async () => {
  const fm = await Flexemarkets.connect(TOKEN, `${base}/marketplaces/1`, "timing-test");
  try {
    await fm.activeOrders(1);
    await fm.activeOrders(1);
    await fm.activeOrders(1);
    assert.equal(timings.filter((t) => t !== null).length, timings.length - 1);
  } finally { fm.close(); }
});
