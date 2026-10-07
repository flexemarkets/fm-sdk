// Loaded before every test file (package.json's `test` script). The client
// reads ~/.fm/credential and ~/.fm/endpoint before anything else, so without
// this a test runs as whoever runs it: their account, their password, and
// their endpoint, which can be production. Each file gets an empty home and
// no FM_* overrides -- except the live-server test, whose job is to read the
// real ~/.fm, and which skips unless FM_LIVE_TESTS=1.
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

if (!process.argv.some((arg) => arg.includes("flexemarkets-live-server"))) {
  process.env.HOME = mkdtempSync(join(tmpdir(), "fm-sdk-test-home-"));
  delete process.env.FM_API_URL;
  delete process.env.FM_URL;
}
