#!/usr/bin/env python3
"""Assert the Java, Python and TypeScript SDKs describe the same wire format.

The three SDKs are hand-written, not generated. Every type is declared three
times, and nothing has been holding the copies together — a field added to one
and forgotten in the others would ship, and the first person to find out would
be a consumer whose deserialization quietly dropped it.

They are, as it happens, in step today: of thirteen shared types, eleven agree
exactly and the two that differ do so on purpose (see EXEMPTIONS). This exists
so that stays true rather than staying true by luck.

What is compared is the **wire** format, not each language's surface. Java
fields marked @JsonIgnore are client-side conveniences that never cross the
network, so they are excluded automatically rather than exempted by name.
Python's snake_case is normalised to camelCase before comparison, since
per-language naming idiom is deliberate policy here.

Usage:  python3 scripts/check-parity.py [--verbose]
Exit:   0 when the three agree, 1 when they do not.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# The package, not one file: E11 split the Types holder class into a record per
# file, so there is no longer a single place the shapes live.
JAVA = ROOT / "sdks/java/fm-sdk/src/main/java/fm"
PYTHON = ROOT / "sdks/python/fm/types.py"
TYPESCRIPT = ROOT / "sdks/typescript/src/types.ts"

# Divergences that are intended. Each needs a reason, so that adding one is a
# decision someone wrote down rather than a way to silence the check.
#
# Empty, and worth keeping that way. The one entry this started with was
# ApiRoot's HAL envelope, which Java spelled `_links` while Python and
# TypeScript spelled `links`. Renaming the Java component and binding it with
# @JsonProperty("_links") removed the difference rather than excusing it.
EXEMPTIONS: dict[tuple[str, str], str] = {}


def _components(body: str) -> list[str]:
    """Split a record's component list on commas, ignoring those inside generics.

    `Map<String, LinkObject> links` is one component, not two. Splitting naively
    produced a field called `Map<String`, which went unnoticed only because the
    fragment happened to be a single token and was skipped -- until an
    annotation was added and it became two.
    """
    parts, depth, current = [], 0, []
    for char in body:
        if char == "<":
            depth += 1
        elif char == ">":
            depth -= 1
        if char == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    parts.append("".join(current))
    return parts


def java_types(directory: Path) -> dict[str, tuple[list[str], set[str]]]:
    """Record name -> (all components, components excluded from the wire)."""
    source = "\n".join(p.read_text() for p in sorted(directory.glob("*.java")))

    # Strip block comments before looking for components. A record's component
    # list is a natural place to explain a field, and prose there was read as
    # fields: a comment inside Assets reported "in", "alias" and "accepted" as
    # Java-only fields, and one inside Security reported six more. Both times
    # the checker was right that something was wrong with the declaration and
    # wrong about what, which is the worst way for a check to fail. Line
    # comments are left alone -- they cannot span into a component list without
    # ending it.
    source = re.sub(r"/\*.*?\*/", " ", source, flags=re.S)
    types: dict[str, tuple[list[str], set[str]]] = {}

    for match in re.finditer(r"^public record (\w+)\(", source, re.M):
        open_paren = source.index("(", match.start())
        depth, cursor = 0, open_paren
        while True:
            if source[cursor] == "(":
                depth += 1
            elif source[cursor] == ")":
                depth -= 1
                if depth == 0:
                    break
            cursor += 1

        fields: list[str] = []
        ignored: set[str] = set()
        for component in _components(source[open_paren + 1 : cursor]):
            tokens = re.sub(r"@\w+(\([^)]*\))?", " ", component).split()
            if len(tokens) < 2:
                continue
            name = tokens[-1].strip()
            fields.append(name)
            if "@JsonIgnore" in component:
                ignored.add(name)
        types[match.group(1)] = (fields, ignored)

    return types


def python_types(path: Path) -> dict[str, list[str]]:
    types: dict[str, list[str]] = {}
    current: str | None = None

    for line in path.read_text().splitlines():
        header = re.match(r"class (\w+)", line.strip())
        if header and not line.startswith((" ", "\t")):
            current = header.group(1)
            types[current] = []
            continue
        if current is None:
            continue
        if line and not line.startswith((" ", "\t")):
            current = None
            continue
        field = re.match(r"    ([a-z_][a-z0-9_]*)\s*:", line)
        if field:
            types[current].append(field.group(1))

    return types


def typescript_types(path: Path) -> dict[str, list[str]]:
    source = path.read_text()
    types: dict[str, list[str]] = {}

    for match in re.finditer(r"export interface (\w+) \{", source):
        end = source.index("\n}", match.start())
        fields = []
        for line in source[match.start() : end].splitlines()[1:]:
            field = re.match(r"\s+([a-zA-Z_][a-zA-Z0-9_]*)\??\s*:", line)
            if field:
                fields.append(field.group(1))
        types[match.group(1)] = fields

    return types


def camel(name: str) -> str:
    head, *tail = name.split("_")
    return head + "".join(word.capitalize() for word in tail)


# --- method surface ---------------------------------------------------------
#
# The type check above compares what crosses the wire. It says nothing about
# what a caller can call, which is where the three SDKs actually drifted: by
# 0.1.0 Java had submitMarket and subscribe that neither other SDK had, Python
# had is_manager and has_role that Java did not, and TypeScript had neither
# isManager nor a typed conflict. Five gaps, none of which the field check
# could see.

# Flexemarkets itself as well as the roles: since the A1 split it declares only
# connect() and close(), but those are surface too -- and reading the roles
# alone reported both as Python/TypeScript-only, which is the check being wrong
# about the SDKs rather than the other way round.
JAVA_SURFACE = [
    "Flexemarkets.java",
    "Identity.java", "Reading.java", "Writing.java",
    "Management.java", "Administration.java", "Streaming.java",
]

# Differences that are intended. Each needs a reason, so that adding one is a
# decision someone wrote down rather than a way to silence the check.
METHOD_EXEMPTIONS: dict[str, str] = {
    "connectWithToken":
        "Python-only. Java and TypeScript take a token as connect()'s credential "
        "argument, so this may be redundant rather than missing; the decision is "
        "open, and until it is made this records that nobody has forgotten it.",
}


def java_methods(directory: Path) -> set[str]:
    """Public methods declared on the role interfaces.

    The roles, not Flexemarkets: since the split it declares only close(), and
    reading it alone would report the surface as empty -- a check that passes
    because it looked in the wrong place is worse than no check.
    """
    names: set[str] = set()

    for role in JAVA_SURFACE:
        source = (directory / role).read_text()
        source = re.sub(r"/\*.*?\*/", " ", source, flags=re.S)
        for line in source.splitlines():
            if "private" in line:
                continue
            # Exactly four spaces: a member of the interface. Statements inside
            # the body of a default method or the static connect() are indented
            # further, which is what separates a declaration from a call without
            # having to guess from the return type. Filtering on the type
            # instead is what I tried first, and skipping lines beginning
            # "String " quietly dropped accountName, endpointUrl and
            # downloadHoldings -- the check reporting the SDKs wrong when it was
            # the one that was.
            match = re.match(
                r"^    (?:default\s+|static\s+)?[\w<>,\[\]\.\s]+?\s+(\w+)\s*\(", line)
            # `for (` and `if (` at member indentation are statements in a
            # single-expression body; `new HttpFlexemarkets(` is a constructor
            # call. None of them is a declaration.
            if match and match.group(1) not in ("for", "if", "while", "catch",
                                                "switch", "HttpFlexemarkets"):
                names.add(match.group(1))

    return names


def python_methods(path: Path) -> set[str]:
    """Public methods and properties on the Flexemarkets class."""
    names: set[str] = set()
    inside = False

    for line in path.read_text().splitlines():
        if re.match(r"class Flexemarkets\b", line):
            inside = True
            continue
        if inside and line and not line.startswith((" ", "\t")):
            break
        if not inside:
            continue
        match = re.match(r"    def ([a-z][a-z0-9_]*)\s*\(", line)
        if match:
            names.add(camel(match.group(1)))

    return names


def typescript_methods(path: Path) -> set[str]:
    """Public methods and getters on the Flexemarkets class."""
    names: set[str] = set()
    inside = False

    for line in path.read_text().splitlines():
        if re.match(r"export class Flexemarkets\b", line):
            inside = True
            continue
        if inside and re.match(r"^\}", line):
            break
        if not inside:
            continue
        match = re.match(r"  (?:static\s+|async\s+|get\s+)*([a-zA-Z][a-zA-Z0-9]*)\s*\(", line)
        if match and match.group(1) not in ("constructor", "if", "for", "while", "catch"):
            names.add(match.group(1))

    return names


def check_methods(verbose: bool) -> list[str]:
    java = java_methods(JAVA)
    python = python_methods(PYTHON.parent / "client.py")
    typescript = typescript_methods(TYPESCRIPT.parent / "client.ts")

    if not (java and python and typescript):
        return ["method parity: a parser found nothing; it is broken, not the SDKs"]

    problems: list[str] = []
    for name in sorted(java | python | typescript):
        if name in METHOD_EXEMPTIONS:
            continue
        missing = [
            language
            for language, names in (
                ("java", java), ("python", python), ("typescript", typescript))
            if name not in names
        ]
        if missing:
            present = [
                language
                for language, names in (
                    ("java", java), ("python", python), ("typescript", typescript))
                if name in names
            ]
            problems.append(
                f"{name}() is in {', '.join(present)} but missing from {', '.join(missing)}"
            )

    if verbose:
        print(f"\n  method surface: java {len(java)}, python {len(python)}, "
              f"typescript {len(typescript)}")

    return problems


def main() -> int:
    verbose = "--verbose" in sys.argv

    for path in (JAVA, PYTHON, TYPESCRIPT):
        if not path.exists():
            print(f"parity: cannot find {path}", file=sys.stderr)
            return 1

    java = java_types(JAVA)
    python = {name: [camel(f) for f in fields] for name, fields in python_types(PYTHON).items()}
    typescript = typescript_types(TYPESCRIPT)

    shared = sorted(set(java) & set(python) & set(typescript))
    if not shared:
        # Guard the guard: a parser that silently matched nothing would report
        # perfect agreement forever.
        print("parity: no types found in all three SDKs; the parsers are broken",
              file=sys.stderr)
        return 1

    problems: list[str] = []

    for name in shared:
        all_fields, wire_excluded = java[name]
        java_wire = [f for f in all_fields if f not in wire_excluded]
        others = set(python[name]) | set(typescript[name])

        for field in java_wire:
            missing = [
                language
                for language, fields in (("python", python[name]), ("typescript", typescript[name]))
                if field not in fields
            ]
            if missing and (name, field) not in EXEMPTIONS:
                problems.append(
                    f"{name}.{field} is in Java but missing from {', '.join(missing)}"
                )

        for field in sorted(others - set(java_wire)):
            if (name, field) in EXEMPTIONS:
                continue
            present = [
                language
                for language, fields in (("python", python[name]), ("typescript", typescript[name]))
                if field in fields
            ]
            problems.append(
                f"{name}.{field} is in {', '.join(present)} but missing from Java"
            )

        if verbose:
            note = ""
            if wire_excluded:
                note = f"  (java-only, not on the wire: {', '.join(sorted(wire_excluded))})"
            print(f"  {name:20} {len(java_wire):3} wire fields{note}")

    only_java = sorted(set(java) - set(python) - set(typescript))
    if verbose and only_java:
        print(f"\n  declared only in Java: {', '.join(only_java)}")

    if problems:
        print(f"\nparity: the SDKs disagree about {len(problems)} field(s):\n", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        print(
            "\nEvery type is declared in all three SDKs and nothing generates them, "
            "so a field added to one must be added to the others. If a difference "
            "is intended, record it in EXEMPTIONS with the reason.",
            file=sys.stderr,
        )
        return 1

    method_problems = check_methods(verbose)
    if method_problems:
        print(f"\nparity: the SDKs disagree about {len(method_problems)} method(s):\n",
              file=sys.stderr)
        for problem in method_problems:
            print(f"  - {problem}", file=sys.stderr)
        print(
            "\nA method added to one SDK is a method the other two are missing. If a "
            "difference is intended, record it in METHOD_EXEMPTIONS with the reason.",
            file=sys.stderr,
        )
        return 1

    print(f"parity ok: {len(shared)} shared types agree on their wire fields, "
          f"and the three method surfaces agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
