#!/usr/bin/env python3
"""Hold the wire fixtures against a real fm-server.

The fixtures next door are the only thing that compares the three SDKs by
*value* rather than by name. What nothing compared was the fixtures themselves
against the server they claim to describe -- they were written by hand, from
what somebody believed the response looked like, and then frozen.

That is the failure this repo keeps having, and it is the one a parity check is
structurally blind to: all three SDKs agreeing, and all three being wrong. It
has happened twice on the same field. `_embedded.orderDtoes` became
`_embedded.orders` and every SDK kept reading the old name, returning an empty
book for months while the books filled from live deltas and looked plausible.
Then the envelope was dropped for a bare array and all three broke at once. Both
times every suite stayed green, because every suite was asking the SDK to agree
with a fixture rather than with fm-server.

So: each fixture declares where its payload came from. A `captured` fixture
names a route, and this script re-fetches it and compares the *shape* -- field
names and JSON types, recursively -- against what is stored. A `constructed`
fixture says why no live server produces it: an older envelope the SDK must
still tolerate, a value it must not choke on, a state this server has no example
of. Both are legitimate; not saying which is not.

    ./scripts/capture-fixtures.py --audit    # no server: every fixture declares a source
    ./scripts/capture-fixtures.py --check    # against a server: has the shape moved?
    ./scripts/capture-fixtures.py --write    # refresh captured payloads in place

--audit runs in `make check`. --check and --write need a server, and are what a
release should run: a shape change fm-server made deliberately shows up as a
reviewable diff instead of as an empty book six months later.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "sdks/fixtures"

DEFAULT_ENDPOINT = Path.home() / ".fm" / "endpoint"
DEFAULT_CREDENTIAL = Path.home() / ".fm" / "credential"


# --- talking to the server --------------------------------------------------

def _read_config(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text().splitlines():
        if "=" in line and not line.strip().startswith("#"):
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip()
    return values


def _post(url: str, body: dict[str, Any]) -> Any:
    request = urllib.request.Request(
        url, data=json.dumps(body).encode(), method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def _get(url: str, token: str) -> Any:
    request = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/json, application/hal+json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


class Server:
    """A connected server, resolving routes the way the SDKs do -- through the
    API root's link table rather than through paths spelled out here. A fixture
    naming a link that the root stops advertising is itself a finding."""

    def __init__(self, endpoint: str, credential: Path):
        self.api = endpoint.split("/marketplaces/")[0].rstrip("/")
        self.marketplace_id = (endpoint.split("/marketplaces/")[1].split("/")[0]
                               if "/marketplaces/" in endpoint else None)

        config = _read_config(credential)
        account = config.get("account", "")
        self.token = _post(f"{self.api}/tokens", {
            "username": f"{account}|{config['email']}",
            "password": config["password"]})["token"]

        root = _get(self.api, self.token)
        self.links = {name: (value.get("href") if isinstance(value, dict) else value)
                      for name, value in root.get("_links", root).items()}

    def fetch(self, captured: dict[str, Any]) -> Any:
        if "link" in captured:
            href = self.links.get(captured["link"])
            if href is None:
                raise LookupError(
                    f"the API root no longer advertises {captured['link']!r} "
                    f"(it has: {', '.join(sorted(self.links))})")
            url = href.split("{")[0]
        else:
            url = self.api + captured["path"].replace(
                "{marketplaceId}", str(self.marketplace_id))

        query = captured.get("query", "")
        if query:
            query = query.replace("{marketplaceId}", str(self.marketplace_id))
            url += ("&" if "?" in url else "?") + query

        return _get(url, self.token)


# --- shapes -----------------------------------------------------------------

def _shape(value: Any, prefix: str = "") -> dict[str, str]:
    """Field path -> JSON type. Arrays collapse to their first element, since
    what is being compared is the shape of a row and not how many arrived."""
    shape: dict[str, str] = {}

    if isinstance(value, dict):
        for key, inner in value.items():
            path = f"{prefix}.{key}" if prefix else key
            shape[path] = _type_of(inner)
            shape.update(_shape(inner, path))
    elif isinstance(value, list) and value:
        shape.update(_shape(value[0], f"{prefix}[]"))

    return shape


def _type_of(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, (int, float)):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    return "object"


def _select(body: Any, selector: dict[str, Any] | None, whole: bool = False) -> Any:
    """The representative object a fixture is about: the whole response when the
    fixture is about the envelope itself, otherwise the first row matching the
    selector."""
    if whole:
        return body

    rows = body
    if isinstance(body, dict):
        embedded = body.get("_embedded")
        if isinstance(embedded, dict) and embedded:
            rows = next(iter(embedded.values()))
        elif selector is None:
            return body

    if not isinstance(rows, list):
        return rows

    for row in rows:
        if selector is None or all(row.get(k) == v for k, v in selector.items()):
            return row

    raise LookupError(f"no row on this server matches {selector!r}")


def _drift(stored: dict[str, str], live: dict[str, str]) -> tuple[list[str], list[str]]:
    """Returns (drift, notes).

    Drift is a field the fixture has and the server no longer sends, or one
    whose type changed. Those are the two shapes of the bug this exists for: a
    parser reading a name that moved, and a parser binding the wrong type.

    A field the server sends and the fixture omits is a note, not a failure. A
    case is allowed to be minimal -- most of these are about one field -- and
    failing on every unrelated addition would make the check something people
    turn off.
    """
    problems = []
    notes = []

    for field in sorted(set(stored) - set(live)):
        problems.append(f"    the server no longer sends {field!r} "
                        f"(the fixture still has it, as {stored[field]})")
    for field in sorted(set(live) - set(stored)):
        notes.append(f"    the server also sends {field!r} ({live[field]}), "
                     f"which this fixture does not cover")
    for field in sorted(set(stored) & set(live)):
        # null is compatible with anything: a nullable field is null in this
        # server's data and populated in the fixture, or the other way round,
        # and neither is drift.
        if "null" in (stored[field], live[field]):
            continue
        if stored[field] != live[field]:
            problems.append(f"    {field!r} was {stored[field]} and is now {live[field]}")

    return problems, notes


# --- the commands -----------------------------------------------------------

def _fixtures() -> list[tuple[Path, dict[str, Any]]]:
    found = [(p, json.loads(p.read_text())) for p in sorted(FIXTURES.glob("*.json"))]
    if len(found) < 10:
        raise SystemExit(f"only found {len(found)} fixtures in {FIXTURES}; "
                         "the glob is wrong, not the corpus")
    return found


def audit() -> int:
    problems = []

    for path, doc in _fixtures():
        source = doc.get("source")
        if not isinstance(source, dict) or len(source) != 1 or not (
                set(source) <= {"captured", "constructed"}):
            problems.append(
                f"  {path.name} declares no source. Say where its payload came "
                f"from: \"captured\" with the route it was read from, or "
                f"\"constructed\" with why no live server produces it.")
            continue
        if "constructed" in source and not str(source["constructed"]).strip():
            problems.append(f"  {path.name} is constructed with no reason given.")

    if problems:
        print("fixture provenance:\n" + "\n".join(problems), file=sys.stderr)
        print("\nA fixture with no source is a belief about the server that "
              "nothing can check. Both times this repo shipped a broken parser, "
              "the fixtures agreed with the SDK and neither agreed with "
              "fm-server.", file=sys.stderr)
        return 1

    captured = sum(1 for _, d in _fixtures() if "captured" in d["source"])
    total = len(_fixtures())
    print(f"provenance ok: {total} fixtures, {captured} captured from a route "
          f"and {total - captured} constructed on purpose")
    return 0


def check(server: Server, write: bool, verbose: bool = False) -> int:
    drifted = 0
    checked = 0

    for path, doc in _fixtures():
        captured = doc.get("source", {}).get("captured")
        if not captured:
            continue

        try:
            live = _select(server.fetch(captured), captured.get("select"),
                           captured.get("body", False))
        except (LookupError, urllib.error.HTTPError) as failure:
            print(f"  {path.name}: cannot reach its route -- {failure}", file=sys.stderr)
            drifted += 1
            continue

        checked += 1
        problems, notes = _drift(_shape(doc["payload"]), _shape(live))

        if notes and verbose:
            print(f"\n  {path.name}:", file=sys.stderr)
            print("\n".join(notes), file=sys.stderr)

        if not problems:
            continue

        drifted += 1
        print(f"\n  {path.name} no longer matches "
              f"{captured.get('link') or captured.get('path')}:", file=sys.stderr)
        print("\n".join(problems), file=sys.stderr)

        if write:
            doc["payload"] = live
            path.write_text(json.dumps(doc, indent=2) + "\n")
            print(f"    -> payload refreshed; review the diff and the `expect` block",
                  file=sys.stderr)

    if drifted and not write:
        print(f"\n{drifted} of {checked} captured fixtures no longer describe this "
              f"server. If the change is intended, re-run with --write and review "
              f"the diff -- including whether `expect` still says the right thing.",
              file=sys.stderr)
        return 1

    print(f"shapes ok: {checked} captured fixtures still match this server")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--audit", action="store_true",
                      help="no server: every fixture declares where it came from")
    mode.add_argument("--check", action="store_true",
                      help="against a server: has the shape moved?")
    mode.add_argument("--write", action="store_true",
                      help="against a server: refresh captured payloads in place")
    parser.add_argument("--endpoint", help="marketplace URL; defaults to ~/.fm/endpoint")
    parser.add_argument("--credential", type=Path, default=DEFAULT_CREDENTIAL)
    parser.add_argument("--verbose", action="store_true",
                        help="also list fields the server sends that a fixture omits")
    args = parser.parse_args()

    if args.audit or not (args.check or args.write):
        return audit()

    endpoint = args.endpoint
    if not endpoint:
        if not DEFAULT_ENDPOINT.exists():
            print(f"no --endpoint and no {DEFAULT_ENDPOINT}", file=sys.stderr)
            return 2
        endpoint = _read_config(DEFAULT_ENDPOINT).get("endpoint", "")

    if not args.credential.exists():
        print(f"no credential at {args.credential}", file=sys.stderr)
        return 2

    return check(Server(endpoint, args.credential), args.write, args.verbose)


if __name__ == "__main__":
    raise SystemExit(main())
