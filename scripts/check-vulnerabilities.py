#!/usr/bin/env python3
"""check-vulnerabilities.py -- refuse a release that ships a known-vulnerable dependency.

fm-sdk 0.22.0 went to Maven Central on 2026-11-14 depending on Jackson 3.1.5,
six weeks after seven advisories against it were published (2026-09-28 to
10-01), and mvnrepository.com flagged the release. Nothing here could have
seen it: the repo had no Dependabot configuration, and GitHub's dependency
graph lists jackson-databind with no version at all, because the version
comes from a BOM import -- so no alert could match it. CI checked nothing.

This lists what each SDK ships with, at the versions it was built and tested
against, and asks OSV (https://osv.dev) about each:

  Java        the runtime classpath of fm-sdk, fm-spi and fm-expr, as Maven
              resolves it (com.flexemarkets artifacts left out)
  TypeScript  the non-dev packages in sdks/typescript/package-lock.json
  Python      fm-sdk's requirements and theirs, as installed in sdks/python/.venv

npm and PyPI consumers resolve the declared ranges themselves, so for those
two this checks the versions the release was tested with, not every version a
consumer may get.

It fails closed: an OSV it cannot reach is a refusal, not a pass, because a
gate that waves a release through when it knows nothing is how this was
missed. `fm-release --sdk publish --skip-vulnerability-check` is the way past.

Usage: scripts/check-vulnerabilities.py [--root DIR] [--osv URL]
Exit:  0 no known vulnerabilities; 1 some, or OSV could not be asked
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

OSV = "https://api.osv.dev"
OWN_GROUP = "com.flexemarkets"
JAVA_MODULES = "fm-sdk,fm-spi,fm-expr"


def java_packages(root: Path) -> set[tuple[str, str, str]]:
    """The runtime classpath of the published Java artifacts, as Maven resolves it."""
    with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as f:
        out = Path(f.name)
    try:
        subprocess.run(
            ["mvn", "-B", "-q", "-pl", JAVA_MODULES, "dependency:list",
             "-DincludeScope=runtime", f"-DexcludeGroupIds={OWN_GROUP}",
             f"-DoutputFile={out}", "-DappendOutput=true"],
            cwd=root / "sdks" / "java", check=True, stdout=subprocess.DEVNULL)
        packages = set()
        # e.g. "   tools.jackson.core:jackson-core:jar:3.1.7:compile -- module tools.jackson.core";
        # a classifier adds a field before the version
        for line in out.read_text().splitlines():
            parts = line.split(" -- ", 1)[0].strip().split(":")
            if len(parts) >= 5 and parts[-1] in ("compile", "runtime"):
                packages.add(("Maven", f"{parts[0]}:{parts[1]}", parts[-2]))
        return packages
    finally:
        out.unlink(missing_ok=True)


def npm_packages(root: Path) -> set[tuple[str, str, str]]:
    """The packages the TypeScript SDK ships with: the lockfile's, less its dev ones."""
    lock = json.loads((root / "sdks" / "typescript" / "package-lock.json").read_text())
    packages = set()
    for key, entry in lock.get("packages", {}).items():
        if not key or entry.get("dev") or "version" not in entry:
            continue
        packages.add(("npm", key.rsplit("node_modules/", 1)[-1], entry["version"]))
    return packages


PY_WALK = """
import importlib.metadata as m, json, re, sys
seen, todo = {}, [r for r in m.requires("fm-sdk") or [] if "extra ==" not in r]
while todo:
    name = re.split(r"[\\s<>=!~;\\[(]", todo.pop().strip(), maxsplit=1)[0]
    key = name.lower().replace("_", "-")
    if key in seen:
        continue
    try:
        dist = m.distribution(name)
    except m.PackageNotFoundError:
        continue
    seen[key] = dist.version
    todo += [r for r in dist.requires or [] if "extra ==" not in r]
json.dump(seen, sys.stdout)
"""


def python_packages(root: Path) -> set[tuple[str, str, str]]:
    """fm-sdk's requirements and theirs, at the versions its venv holds."""
    venv = root / "sdks" / "python" / ".venv" / "bin" / "python"
    found = subprocess.run([str(venv), "-c", PY_WALK], check=True, capture_output=True, text=True)
    return {("PyPI", name, version) for name, version in json.loads(found.stdout).items()}


def _post(url: str, body: dict) -> dict:
    request = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as answer:
        return json.load(answer)


def _get(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=30) as answer:
        return json.load(answer)


def vulnerabilities(osv: str, packages: list[tuple[str, str, str]]) -> dict[tuple[str, str, str], list[dict]]:
    """Each package OSV knows a vulnerability in, with the vulnerabilities."""
    queries = [{"package": {"ecosystem": e, "name": n}, "version": v} for e, n, v in packages]
    results = _post(f"{osv}/v1/querybatch", {"queries": queries}).get("results", [])
    if len(results) != len(packages):
        raise ValueError(f"OSV answered {len(results)} results for {len(packages)} queries")
    found = {}
    for package, result in zip(packages, results):
        ids = [v["id"] for v in result.get("vulns", [])]
        if ids:
            found[package] = [_get(f"{osv}/v1/vulns/{i}") for i in ids]
    return found


def fixed_in(vuln: dict, ecosystem: str, name: str) -> list[str]:
    versions = []
    for affected in vuln.get("affected", []):
        package = affected.get("package", {})
        if package.get("ecosystem") == ecosystem and package.get("name") == name:
            for r in affected.get("ranges", []):
                versions += [e["fixed"] for e in r.get("events", []) if "fixed" in e]
    return versions


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--osv", default=os.environ.get("FM_OSV_URL", OSV))
    args = parser.parse_args()

    packages: set[tuple[str, str, str]] = set()
    for language, collect in (("Java", java_packages), ("TypeScript", npm_packages), ("Python", python_packages)):
        try:
            found = collect(args.root)
        except (subprocess.CalledProcessError, OSError, ValueError, KeyError) as e:
            print(f"vulnerabilities: cannot list the {language} SDK's dependencies: {e}", file=sys.stderr)
            return 1
        if not found:
            # Every SDK has at least one runtime dependency; none means the listing broke.
            print(f"vulnerabilities: found no {language} dependencies, which means the listing failed",
                  file=sys.stderr)
            return 1
        packages |= found
    ordered = sorted(packages)

    try:
        found = vulnerabilities(args.osv, ordered)
    except (urllib.error.URLError, OSError, ValueError, KeyError) as e:
        print(f"vulnerabilities: could not ask OSV ({args.osv}): {e}", file=sys.stderr)
        print("A check that cannot be made is not a pass. Retry, or publish with --skip-vulnerability-check.",
              file=sys.stderr)
        return 1

    if not found:
        print(f"vulnerabilities ok: OSV knows none in the {len(ordered)} packages the SDKs ship with")
        return 0
    print(f"vulnerabilities: OSV knows {sum(len(v) for v in found.values())} in "
          f"{len(found)} of the {len(ordered)} packages the SDKs ship with:", file=sys.stderr)
    for (ecosystem, name, version), vulns in found.items():
        print(f"  {ecosystem} {name} {version}", file=sys.stderr)
        for v in vulns:
            aliases = ", ".join(a for a in v.get("aliases", []) if a.startswith("CVE-"))
            fixes = ", ".join(fixed_in(v, ecosystem, name)) or "no fixed version"
            summary = re.sub(r"\s+", " ", v.get("summary", "")).strip()
            print(f"    {v['id']}{f' ({aliases})' if aliases else ''}: {summary} -- fixed in {fixes}",
                  file=sys.stderr)
    if any(ecosystem == "PyPI" for ecosystem, _, _ in found):
        print("Python has no lockfile: the venv keeps what it was first installed with, while CI and "
              "consumers resolve afresh. `make install-python` upgrades it; check again after.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
