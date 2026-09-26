import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { join } from "node:path";

/**
 * This package's version, from the package.json one level above the module.
 *
 * That holds in both layouts: `src/` beside package.json in a checkout, and
 * `dist/` beside it in the published package, which npm always ships with
 * package.json. It used to read the repository's VERSION file three levels
 * up, which exists only in a checkout, so an npm install reported 0.0.0 --
 * and fm-server keys its endpoint metrics by this (fm-server#1012).
 *
 * @param from the module's own directory; a parameter only so a test can
 *             stand up the installed layout.
 */
export function readVersion(from: URL = new URL(".", import.meta.url)): string {
  try {
    const pkg = JSON.parse(readFileSync(join(fileURLToPath(from), "..", "package.json"), "utf-8"));
    return typeof pkg.version === "string" && pkg.version ? pkg.version : "0.0.0";
  } catch {
    return "0.0.0";
  }
}
