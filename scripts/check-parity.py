#!/usr/bin/env python3
"""Assert the Java, Python and TypeScript SDKs describe the same wire format.

The three SDKs are hand-written, not generated. Every type is declared three
times, and nothing has been holding the copies together — a field added to one
and forgotten in the others would ship, and the first person to find out would
be a consumer whose deserialization quietly dropped it.

They are, as it happens, in step today: eighteen shared types agree exactly and
EXEMPTIONS is empty. This exists so that stays true rather than staying true by
luck -- and so that a type quietly dropping out of the comparison, which is the
failure a check like this is worst at noticing, is itself a failure.

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
# The modules that declare the wire format, listed rather than globbed. The
# event objects -- StreamDropped, GapEvent, OrdersUpdate -- are client-side and
# deliberately shaped per language: Java carries a Throwable where Python and
# TypeScript carry the STOMP command, headers and body. Scanning the whole
# package pulls those in and reports eighteen differences that are all correct.
PYTHON_PKG = ROOT / "sdks/python/fm"
TYPESCRIPT_SRC = ROOT / "sdks/typescript/src"
# trades is here for Trade, which is not a wire type -- the exchange sends no
# such object, the SDKs assemble it from a pair of orders. It is compared
# anyway, because the three assemble it identically on purpose and nothing else
# would notice if one of them stopped. Java needs no listing: java_types rglobs
# the package, so its record was already being read -- and read alone, which is
# the shape of the miss. A type only Java declares falls out of the
# intersection and is not compared at all, so `parity ok` said 18 shared types
# both before Trade existed and after it existed in one language.
PYTHON = [PYTHON_PKG / "types.py", PYTHON_PKG / "_hal.py", PYTHON_PKG / "trades.py"]
TYPESCRIPT = [TYPESCRIPT_SRC / "types.ts", TYPESCRIPT_SRC / "hal.ts",
              TYPESCRIPT_SRC / "trades.ts"]

# What a full run compares. A list of modules cannot notice a type that moves
# out of one of them: ApiRoot went to the private _hal module and the run
# dropped from eighteen shared types to seventeen and still printed ok. Java
# had already taught this once -- three types moved to fm.internal and vanished
# from the comparison -- and rglob fixed it there, which a wire/not-wire split
# rules out here. So the count is the guard, and lowering it is an edit someone
# has to make on purpose. It went 18 -> 19 when Trade was added to all three.
EXPECTED_SHARED = 19

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
    # rglob, not glob: a wire type may live in fm.internal -- ApiRoot, Approval
    # and Version moved there when the public surface was trimmed, and scanning
    # only the top package silently dropped three types from the comparison.
    source = "\n".join(p.read_text() for p in sorted(directory.rglob("*.java")))

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


def python_types(paths: list[Path]) -> dict[str, list[str]]:
    """Only what @dataclass marks, across the wire modules.

    The decorator, not the file, is what says "this is a shape the server
    sends". Every wire type is a dataclass and nothing else in the package is,
    so a helper added beside them cannot become a type the other two SDKs are
    then reported as missing.
    """
    types: dict[str, list[str]] = {}

    for path in paths:
        current: str | None = None
        decorated = False
        quote: str | None = None

        for line in path.read_text().splitlines():
            # Docstrings are prose, and a class's is a natural place to explain
            # its fields. "...has always\n    used: an order that..." was read
            # as a field called `used`, reported against Java, and the checker
            # was confidently wrong about the SDKs -- the same way a comment
            # inside a Java record's component list once was, which is why
            # java_types strips block comments before it looks.
            if quote is not None:
                if quote in line:
                    quote = None
                continue
            opener = re.match(r'\s*[rbuf]*("""|\'\'\')', line)
            if opener:
                mark = opener.group(1)
                rest = line[opener.end():]
                if mark not in rest:
                    quote = mark
                continue

            stripped = line.strip()
            header = re.match(r"class (\w+)", stripped)
            if header and not line.startswith((" ", "\t")):
                current = header.group(1) if decorated else None
                decorated = False
                if current:
                    types[current] = []
                continue
            if not line.startswith((" ", "\t")):
                decorated = stripped.startswith("@dataclass")
                if stripped:
                    current = None
                continue
            if current is None:
                continue
            field = re.match(r"    ([a-z_][a-z0-9_]*)\s*:", line)
            if field:
                types[current].append(field.group(1))

    return types


def typescript_types(paths: list[Path]) -> dict[str, list[str]]:
    """Interfaces across the wire modules -- see python_types for the shape."""
    types: dict[str, list[str]] = {}

    for path in paths:
        source = path.read_text()
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
    "role/Identity.java", "role/Reading.java", "role/Writing.java",
    "role/Management.java", "role/Administration.java", "role/Streaming.java",
]

# Differences that are intended. Each needs a reason, so that adding one is a
# decision someone wrote down rather than a way to silence the check.
# Empty, and worth keeping that way. The one entry this started with was
# connectWithToken, a Python-only factory that looked like a naming question and
# turned out to be a second spelling of a route that never worked: both it and
# connect(token) POSTed /tokens with an empty password, which fm-server answers
# 400. Deleting it was the fix, not an exemption.
METHOD_EXEMPTIONS: dict[str, str] = {}

# Methods whose *required*-argument count differs on purpose. Java has no
# default arguments, so anything Python and TypeScript make optional is
# mandatory there; that is idiom, not divergence. Everything else here is a
# caller having to supply something the other SDKs do not ask for.
ARITY_EXEMPTIONS: dict[str, str] = {
    "connect":
        "Python and TypeScript default every argument, so connect() reads "
        "~/.fm on its own. Java cannot, and takes all three.",
    "createMarket":
        "The unit grid and privateMarket default in Python and TypeScript; "
        "Java has no default arguments and takes all six.",
}


def _signature(lines: list[str], start: int) -> str:
    """The parameter text of a declaration, however many lines it spans.

    Signatures wrap: Python and TypeScript both put one parameter per line once
    there are more than two or three. Reading only the opening line reported a
    required-argument count of zero for eleven of them -- the check confidently
    wrong about the SDKs again, which is the failure mode this file keeps
    finding new ways to hit.
    """
    text, depth = "", 0
    for line in lines[start:]:
        for char in line:
            if char == "(":
                depth += 1
                if depth == 1:
                    continue
            elif char == ")":
                depth -= 1
                if depth == 0:
                    return text
            if depth >= 1:
                text += char
        text += " "
    return text


def _required(params: str, *, drop_self: bool = False) -> int:
    """How many arguments a caller must supply.

    Not the total: optional parameters and overloads are idiom, and differ
    between languages on purpose. What matters is the floor -- if one SDK
    demands an argument the others do not, a caller has to find something to
    put there. Python's holding(marketplace_id, user_id) demanded a user_id it
    never used, while Java and TypeScript took the marketplace alone, and a
    name-only check called that parity.
    """
    depth, current, parts = 0, [], []
    for char in params:
        if char in "<([{":
            depth += 1
        elif char in ">)]}":
            depth -= 1
        if char == "," and depth == 0:
            parts.append("".join(current)); current = []
        else:
            current.append(char)
    parts.append("".join(current))

    count = 0
    for part in parts:
        part = part.strip()
        if not part or part in ("self", "cls", "*"):
            continue
        if drop_self and part.startswith("*"):
            continue          # *args / keyword-only marker
        if "=" in part or part.endswith("?") or "?:" in part:
            continue          # has a default, or is optional
        if "..." in part:
            continue          # Java varargs: createUser(..., String... roles)
        count += 1
    return count


def java_methods(directory: Path) -> dict[str, int]:
    """Public methods declared on the role interfaces.

    The roles, not Flexemarkets: since the split it declares only close(), and
    reading it alone would report the surface as empty -- a check that passes
    because it looked in the wrong place is worse than no check.
    """
    names: dict[str, int] = {}

    for role in JAVA_SURFACE:
        source = (directory / role).read_text()
        source = re.sub(r"/\*.*?\*/", " ", source, flags=re.S)
        java_lines = source.splitlines()
        for index, line in enumerate(java_lines):
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
                name = match.group(1)
                params = _signature(java_lines, index)
                # Overloads: the smallest is what a caller must supply.
                names[name] = min(names.get(name, 99), _required(params))

    return names


def python_methods(path: Path) -> dict[str, int]:
    """Public methods and properties on the Flexemarkets class."""
    names: dict[str, int] = {}
    inside = False

    py_lines = path.read_text().splitlines()
    for index, line in enumerate(py_lines):
        if re.match(r"class Flexemarkets\b", line):
            inside = True
            continue
        if inside and line and not line.startswith((" ", "\t")):
            break
        if not inside:
            continue
        match = re.match(r"    def ([a-z][a-z0-9_]*)\s*\(", line)
        if match:
            names[camel(match.group(1))] = _required(_signature(py_lines, index),
                                                     drop_self=True)

    return names


def typescript_methods(path: Path) -> dict[str, int]:
    """Public methods and getters on the Flexemarkets class."""
    names: dict[str, int] = {}
    inside = False

    ts_lines = path.read_text().splitlines()
    for index, line in enumerate(ts_lines):
        if re.match(r"export class Flexemarkets\b", line):
            inside = True
            continue
        if inside and re.match(r"^\}", line):
            break
        if not inside:
            continue
        match = re.match(r"  (?:static\s+|async\s+|get\s+)*([a-zA-Z][a-zA-Z0-9]*)\s*\(", line)
        if match and match.group(1) not in ("constructor", "if", "for", "while", "catch"):
            names[match.group(1)] = _required(_signature(ts_lines, index))

    return names


# --- failure types ----------------------------------------------------------
#
# What a caller can catch. Java mapped two statuses to types and let the rest
# fall through to HttpException, while Python and TypeScript mapped four -- so
# `catch (AuthorizationError)` was expressible in two SDKs and not the third,
# and neither the field check nor the method check could see it: an exception
# is thrown, never returned, so it appears in no signature.

FAILURE_EXEMPTIONS: dict[str, str] = {}


def _failure_concept(name: str) -> str:
    """The concept behind a type name, with the language's suffix removed.

    Java spells them Exception and Python and TypeScript spell them Error.
    That much is idiom and not worth flagging; what matters is whether the
    same set of things can be caught in each.
    """
    for suffix in ("Exception", "Error"):
        if name.endswith(suffix) and name != suffix:
            return name[: -len(suffix)]
    return name


def java_failures(directory: Path) -> set[str]:
    """Thrown types: classes, not records.

    WsException is a record delivered on the event queue and never thrown, so
    the file name alone is not enough to go on -- which is the naming problem
    this check exists beside.
    """
    concepts = set()
    for path in sorted(directory.rglob("*.java")):
        source = re.sub(r"/\*.*?\*/", " ", path.read_text(), flags=re.S)
        if re.search(r"^public (?:final |sealed |abstract )?class \w+", source, re.M):
            if re.search(r"extends \w*(Exception|Error|RuntimeException)", source):
                concepts.add(_failure_concept(path.stem))
    return concepts


def python_failures(path: Path) -> set[str]:
    return {_failure_concept(m) for m in re.findall(r"^class (\w+)\(", path.read_text(), re.M)}


def typescript_failures(path: Path) -> set[str]:
    return {_failure_concept(m) for m in
            re.findall(r"^export class (\w+) extends \w*(?:Error)\b", path.read_text(), re.M)}


# --- read-side surface ------------------------------------------------------
#
# check_methods compares Flexemarkets and its roles: what a caller can ask the
# server to do. It stops there. Desk, Book and Tape -- what a
# caller does with the data that comes back -- were never compared, and that is
# where the three drifted furthest. Desk.tape(marketId) existed in Java
# and in neither of the others, so "what was the last trade, and who took it"
# had no answer at all in Python or TypeScript, for the whole life of the type,
# while this script reported three SDKs in agreement.
#
# Construction is excluded on purpose: static factories are per-language idiom
# -- Java's Desk.over, TypeScript's static open -- and comparing them
# reports three correct SDKs as three different ones. So is privacy: a helper
# spelled `_add` in two languages and `private` in the third is one decision,
# not three.
#
# What is compared is what a caller can read or call. Which means the reader
# has to see past three spellings of the same idea -- Java's marketId(),
# Python's plain attribute, TypeScript's getter are one member, and a check
# that cannot say so reports idiom as divergence and is worse than nothing.
READ_SURFACE = {
    "Desk": ("Desk.java", ("desk.py", "Desk"),
                   ("desk.ts", "Desk", "interface")),
    "Book": ("Book.java", ("orderbook.py", "Book"),
                  ("orderbook.ts", "Book", "class")),
    # BookIndex and TapeIndex are no longer callable from outside -- fm.internal
    # in Java, unexported in the other two. They stay compared anyway: they are
    # still three hand-written implementations of one behaviour, and drift
    # between them is the bug class this file exists for. A book that
    # double-counted a cancel was found exactly this way, in all three at once.
    #
    # They were Books and Tapes until the aggregates were renamed to stop them
    # colliding with Desk.books() and Desk.tapes(), which are public and return
    # Collection<Book> and Collection<Tape>.
    "BookIndex": ("internal/BookIndex.java", ("orderbook.py", "BookIndex"),
                   ("orderbook.ts", "BookIndex", "class")),
    "Tape": ("Tape.java", ("trades.py", "Tape"),
               ("trades.ts", "Tape", "class")),
    "TapeIndex": ("internal/TapeIndex.java", ("trades.py", "TapeIndex"),
                          ("trades.ts", "TapeIndex", "class")),
}

# Members that exist in some SDKs and not others on purpose. Same rule as the
# other exemption maps: each needs a reason, so adding one is a decision
# somebody wrote down rather than a way to make the check quiet.
SURFACE_EXEMPTIONS: dict[str, str] = {}


def _java_surface(path: Path) -> set[str]:
    """Public instance members of a Java class or interface.

    Static is dropped (construction idiom) and so is anything not public. An
    interface has no `public` keyword on its members, so the two are read
    differently -- reading an interface with the class rule finds nothing, and
    a check that finds nothing passes.
    """
    source = re.sub(r"/\*.*?\*/", " ", path.read_text(), flags=re.S)
    declared_type = path.stem
    members: set[str] = set()

    interface = re.search(r"^public\s+interface\s+\w+", source, re.M) is not None

    for line in source.splitlines():
        if not line.startswith("    ") or line.startswith("     "):
            continue
        stripped = line.strip()
        if stripped.startswith(("*", "//", "@", "}")) or "static" in stripped:
            continue
        if interface:
            if not stripped.endswith(");"):
                continue
        elif not stripped.startswith("public "):
            continue
        match = re.search(r"(\w+)\s*\(", stripped)
        if match and match.group(1) != declared_type:
            members.add(match.group(1))

    return members


def _python_surface(path: Path, name: str) -> set[str]:
    """Public members of a Python class: its methods, its properties, and the
    attributes it assigns in __init__.

    The last of those is not optional. Desk publishes marketplace_id and
    markets as plain attributes, where Java and TypeScript use accessors --
    read methods alone and the two of them are reported as missing from Python,
    which is idiom being called divergence.
    """
    source = path.read_text()
    body = source[source.index(f"class {name}"):]
    following = re.search(r"\nclass ", body)
    if following:
        body = body[: following.start()]

    members = {m.group(1) for m in re.finditer(r"^    def ([a-z]\w*)\(", body, re.M)}
    members |= {m.group(1) for m in re.finditer(r"^        self\.([a-z]\w*)\s*[:=]", body, re.M)}
    return {m for m in members if not m.startswith("_")}


def _typescript_surface(path: Path, name: str, kind: str) -> set[str]:
    """Public members of a TypeScript class or interface: methods, getters and
    readonly fields."""
    source = path.read_text()
    body = source[source.index(f"{kind} {name}"):]
    following = re.search(r"\n(?:export )?(?:class|interface|function|const) ", body[1:])
    if following:
        body = body[: following.start() + 1]

    members: set[str] = set()
    for line in body.splitlines():
        if not line.startswith("  ") or line.startswith("   "):
            continue
        stripped = line.strip()
        if stripped.startswith(("*", "//", "/*", "}")) or stripped.startswith("private"):
            continue
        if "static" in stripped.split("(")[0]:
            continue
        match = re.match(r"(?:async\s+|get\s+|readonly\s+)*([a-zA-Z]\w*)\s*[(:<]", stripped)
        if match and match.group(1) not in ("constructor", "return", "if", "for", "while"):
            members.add(match.group(1))

    return {m for m in members if not m.startswith("_")}


def check_surface(verbose: bool) -> list[str]:
    problems: list[str] = []
    compared = 0

    for name, (java_file, (py_module, py_class), (ts_module, ts_name, ts_kind)) in \
            sorted(READ_SURFACE.items()):
        java = _java_surface(JAVA / java_file)
        python = {camel(m) for m in _python_surface(PYTHON_PKG / py_module, py_class)}
        typescript = _typescript_surface(TYPESCRIPT_SRC / ts_module, ts_name, ts_kind)

        if not (java and python and typescript):
            return [f"{name}: a reader found nothing; it is broken, not the SDKs"]

        compared += len(java | python | typescript)

        for member in sorted(java | python | typescript):
            if f"{name}.{member}" in SURFACE_EXEMPTIONS:
                continue
            missing = [lang for lang, side in (("java", java), ("python", python),
                                               ("typescript", typescript))
                       if member not in side]
            if missing:
                present = [lang for lang, side in (("java", java), ("python", python),
                                                   ("typescript", typescript))
                           if member in side]
                problems.append(
                    f"{name}.{member} is on {', '.join(present)} but not "
                    f"{', '.join(missing)}")

        if verbose:
            print(f"  {name:20s} java {len(java):2d}, python {len(python):2d}, "
                  f"typescript {len(typescript):2d}")

    if verbose:
        print(f"  read surface:   {compared} member declarations across "
              f"{len(READ_SURFACE)} types")

    return problems


# --- documentation surface --------------------------------------------------
#
# The method check says the same method exists in all three. It says nothing
# about whether a caller in each language is told the same thing about it, and
# that is where the next gap was: `trades()` documented an ordering in Java --
# "most recent first" -- that the route does not have (it answers in ascending
# order id), while Python and TypeScript documented no ordering at all. One
# wrong sentence, in the one SDK that had a sentence to be wrong.
#
# A checker cannot tell whether prose is true. What it can tell is whether a
# claim is being made in only one language, which is what let this one sit
# unread: nobody working on the Python or TypeScript method had a sentence in
# front of them to contradict.
#
# Java documents all 55; Python and TypeScript far fewer. Failing on every gap
# at once would need a 45-name exemption list, which is a way to silence a
# check rather than a check. So this is a ratchet: the debt is a number, a method
# newly documented in one SDK and not the others pushes it up and fails, and
# closing one pushes it down and fails until the number is lowered to match.
# Both directions are a one-line edit someone makes on purpose.
DOC_DEBT = 45

_PY_DOCSTRING_OPENERS = ('"""', "'''", 'r"""')


def java_documented(directory: Path) -> set[str]:
    """Methods on the role interfaces carrying a javadoc block."""
    documented: set[str] = set()

    for name in JAVA_SURFACE:
        lines = (directory / name).read_text().splitlines()
        for index, line in enumerate(lines):
            match = re.search(r"\b(\w+)\s*\(", line)
            if not match or line.strip().startswith(("*", "//", "@")):
                continue
            cursor = index - 1
            while cursor >= 0 and lines[cursor].strip().startswith("@"):
                cursor -= 1
            if cursor >= 0 and lines[cursor].strip().endswith("*/"):
                documented.add(match.group(1))

    return documented


def python_documented(path: Path) -> set[str]:
    """Methods whose body opens with a docstring.

    The signature is found first and then walked to its closing paren: most of
    these wrap over several lines, and the docstring sits after the `:`, not
    after the `def`. Reading only the opening line found ten of them and missed
    the rest -- the same wrapped-signature trap _signature() documents.
    """
    lines = path.read_text().splitlines()
    documented: set[str] = set()

    for index, line in enumerate(lines):
        match = re.match(r"    def ([a-z][a-z0-9_]*)\s*\(", line)
        if not match:
            continue
        depth = 0
        for cursor in range(index, min(len(lines), index + 25)):
            depth += lines[cursor].count("(") - lines[cursor].count(")")
            if depth > 0 or not lines[cursor].rstrip().endswith(":"):
                continue
            for after in range(cursor + 1, min(len(lines), cursor + 3)):
                if not lines[after].strip():
                    continue
                if lines[after].strip().startswith(_PY_DOCSTRING_OPENERS):
                    documented.add(match.group(1))
                break
            break

    return documented


def typescript_documented(path: Path) -> set[str]:
    """Methods preceded by a block comment."""
    lines = path.read_text().splitlines()
    documented: set[str] = set()

    for index, line in enumerate(lines):
        match = re.match(r"  (?:async )?([a-zA-Z][a-zA-Z0-9_]*)\s*(?:<[^>]*>)?\(", line)
        if match and index and lines[index - 1].strip().endswith("*/"):
            documented.add(match.group(1))

    return documented


def check_docs(verbose: bool) -> list[str]:
    java = java_methods(JAVA)
    python = python_methods(PYTHON_PKG / "client.py")
    typescript = typescript_methods(TYPESCRIPT_SRC / "client.ts")
    shared = set(java) & set(python) & set(typescript)

    documented = (
        java_documented(JAVA),
        python_documented(PYTHON_PKG / "client.py"),
        typescript_documented(TYPESCRIPT_SRC / "client.ts"),
    )
    if not all(documented):
        return ["doc parity: a parser found nothing; it is broken, not the SDKs"]

    lopsided = sorted(
        name for name in shared
        if any(name in side for side in documented)
        and not all(name in side for side in documented)
    )

    if verbose:
        print(f"  documented:     java {len(shared & documented[0])}, "
              f"python {len(shared & documented[1])}, "
              f"typescript {len(shared & documented[2])} (of {len(shared)} shared)")

    if len(lopsided) > DOC_DEBT:
        listed = "\n".join(f"      {name}" for name in lopsided)
        return [
            f"{len(lopsided)} methods are documented in some SDKs and not others, "
            f"up from {DOC_DEBT}. Document the new one everywhere, or say why not:\n"
            + listed
        ]

    if len(lopsided) < DOC_DEBT:
        return [
            f"only {len(lopsided)} methods are now documented unevenly, down from "
            f"{DOC_DEBT} -- lower DOC_DEBT to {len(lopsided)} so the ratchet holds"
        ]

    return []


def check_failures(verbose: bool) -> list[str]:
    java = java_failures(JAVA)
    python = python_failures(PYTHON_PKG / "exceptions.py")
    typescript = typescript_failures(TYPESCRIPT_SRC / "client.ts")

    if not (java and python and typescript):
        return ["failure parity: a parser found nothing; it is broken, not the SDKs"]

    problems = []
    for concept in sorted(java | python | typescript):
        if concept in FAILURE_EXEMPTIONS:
            continue
        missing = [lang for lang, names in (("java", java), ("python", python),
                                            ("typescript", typescript)) if concept not in names]
        if missing:
            present = [lang for lang, names in (("java", java), ("python", python),
                                                ("typescript", typescript)) if concept in names]
            problems.append(
                f"{concept} is catchable in {', '.join(present)} but not in {', '.join(missing)}")

    if verbose:
        print(f"  failure types:  java {len(java)}, python {len(python)}, "
              f"typescript {len(typescript)}")
    return problems


def check_methods(verbose: bool) -> list[str]:
    java = java_methods(JAVA)
    python = python_methods(PYTHON_PKG / "client.py")
    typescript = typescript_methods(TYPESCRIPT_SRC / "client.ts")

    if not (java and python and typescript):
        return ["method parity: a parser found nothing; it is broken, not the SDKs"]

    problems: list[str] = []
    for name in sorted(set(java) | set(python) | set(typescript)):
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
            continue

        if name in ARITY_EXEMPTIONS:
            continue

        required = {language: names[name] for language, names in (
            ("java", java), ("python", python), ("typescript", typescript))}
        if len(set(required.values())) > 1:
            shape = ", ".join(f"{lang} {n}" for lang, n in required.items())
            problems.append(
                f"{name}() requires a different number of arguments in each SDK: {shape}"
            )

    if verbose:
        print(f"\n  method surface: java {len(java)}, python {len(python)}, "
              f"typescript {len(typescript)}")

    return problems


# ---------------------------------------------------------------- exports ---
#
# What each SDK actually hands a consumer: Java's exported packages, Python's
# __all__, TypeScript's index.ts. Everything above this compares things that
# exist; this compares whether a caller can reach them.
#
# It exists because 0.2.0's simplifications landed in Java alone and nothing
# noticed. Java deleted isBuy/isSell/isCancel/isLimit as redundant with the
# enums and moved contra onto OrderSide; TypeScript still exports all five.
# Every other check in this file passed throughout, because a name that is
# exported by one SDK and not another is not a wire field, a client method, a
# read-side member or a failure -- it falls through all four.

# Java spells an exception what the other two spell an error.
def _export_concept(name: str) -> str:
    return name[:-len("Exception")] + "Error" if name.endswith("Exception") else name


# Names one SDK exports and the others do not, on purpose. Same rule as every
# other exemption map here: each needs a reason, so adding one is a decision
# somebody wrote down rather than a way to make the check quiet.
EXPORT_EXEMPTIONS: dict[str, str] = {
    # Java-only by language, not by choice.
    "FlexemarketsProvider": "the SPI; Java service loading has no counterpart in the other two",
    "Providers":            "SPI lookup, same reason",
    "Endpoints":            "endpoint resolution for the -E flag; Python keeps it private in _hal, TypeScript has no CLI",
    "Orders":               "a holder class for statics; Python and TypeScript use plain functions in a module",
    "Administration":       "role interface; narrowing a client to a subset has no idiom in Python or TypeScript",
    "Identity":             "role interface, same reason",
    "Management":           "role interface, same reason",
    "Reading":              "role interface, same reason",
    "Streaming":            "role interface, same reason",
    "Writing":              "role interface, same reason",

    # Members in Java, top-level names in the other two.
    "NO_SEQ":               "Snapshot.NO_SEQ in Java, a module constant in Python and TypeScript",
    "SESSION_STATE_INIT":   "Session.State enum in Java, string constants in TypeScript",
    "SESSION_STATE_OPEN":   "same",
    "SESSION_STATE_PAUSED": "same",
    "SESSION_STATE_CLOSED": "same",

    # TypeScript's wire types are structural interfaces and cannot carry
    # methods, so behaviour Java hangs off a record has to be a free function.
    # These are the ones that answer a question about a single value.
    "displayName":       "Person.displayName() in Java, a free function in TypeScript",
    "getSecurity":       "Holding.security() in Java",
    "isApproved":        "Account.isApproved() in Java",
    "orderedSecurities": "Holding.orderedSecurities() in Java",
    "holdingUnits":      "Holding.units() in Java",
    "priceRound":        "Market.priceRound() in Java",
    "unitRound":         "Market.unitRound() in Java",
    "unitGrid":          "Market.unitGrid() in Java",
    "tickRound":         "TickGrid arithmetic, package-private in Java",
    "gridRound":         "TickGrid arithmetic, package-private in Java",
    "toSide":            "OrderSide.of() in Java",
    "toOrderType":       "OrderType.of() in Java",
    "tradeOf":           "Trade construction, a static in Java",
    "toInstant":         "fm.internal.Timestamps in Java; TypeScript has no Instant type to hide it behind",

    # TypeScript-only transport shapes. Java models the stream with typed
    # events; TypeScript hands the frame over.
    "StompFrame":    "TypeScript exposes the raw frame; Java and Python do not",
    "FmEvent":       "the TypeScript union of stream events; Java uses a queue of Object",
    "EventCallback": "TypeScript callback type; Java and Python pass a queue",

    # Tracked in DESIGN-0.3 item 4 rather than exempted away: these are the
    # four predicates Java deleted as redundant with the enums, plus contra.
    # They are still exported by TypeScript and still defined by Python.
    # Withdrawing them breaks callers, so it waits for 0.3.
    "isBuy":    "DESIGN-0.3 item 4: deleted in Java, still exported by TypeScript",
    "isSell":   "DESIGN-0.3 item 4: deleted in Java, still exported by TypeScript",
    "isCancel": "DESIGN-0.3 item 4: deleted in Java, still exported by TypeScript",
    "isLimit":  "DESIGN-0.3 item 4: deleted in Java, still exported by TypeScript",
    "contra":   "DESIGN-0.3 item 4: OrderSide.contra() in Java, still a free function elsewhere",

    # The relational predicates. Java groups them as statics on fm.Orders, so
    # they are members rather than exported names; Python has them in
    # order_utils and leaves that module out of __all__. Recorded rather than
    # hidden: a Python caller cannot reach find_order from fm at all, which is
    # the asymmetry DESIGN-0.3 item 4 is about.
    "findOrder":   "Orders.findOrder in Java; Python's order_utils is not in __all__",
    "isAvailable": "Orders.isAvailable in Java; same for Python",
    "isConsumed":  "Orders.isConsumed in Java; same for Python",
    "isSplit":     "Orders.isSplit in Java; same for Python",
    "isSubmit":    "Orders.isSubmit in Java; same for Python",
    "isSymbol":    "Orders.isSymbol in Java; same for Python",
}


def java_exports(directory: Path) -> set[str]:
    """Public types in the packages module-info exports."""
    module_info = directory.parent / "module-info.java"
    packages = re.findall(r"exports\s+([\w.]+)\s*;", module_info.read_text())
    names = set()
    for package in packages:
        package_dir = directory.parent / package.replace(".", "/")
        for path in package_dir.glob("*.java"):
            if re.search(r"^public\s+(final\s+|abstract\s+|sealed\s+)*"
                         r"(class|interface|record|enum)\s", path.read_text(), re.M):
                names.add(path.stem)
    return names


def python_exports(path: Path) -> set[str]:
    body = re.search(r"__all__\s*=\s*\[(.*?)\]", path.read_text(), re.S)
    if not body:
        return set()
    return {a or b for a, b in re.findall(r'"([^"]+)"|\'([^\']+)\'', body.group(1))}


def typescript_exports(path: Path) -> set[str]:
    names = set()
    for block in re.findall(r"export\s+(?:type\s+)?\{([^}]*)\}", path.read_text()):
        for name in block.split(","):
            name = name.strip().split(" as ")[-1].strip()
            if name:
                names.add(name)
    return names


def check_exports(verbose: bool) -> list[str]:
    java = {_export_concept(n) for n in java_exports(JAVA)}
    python = {_export_concept(n) for n in python_exports(PYTHON_PKG / "__init__.py")}
    typescript = {_export_concept(n) for n in typescript_exports(TYPESCRIPT_SRC / "index.ts")}

    if not (java and python and typescript):
        return ["export parity: a parser found nothing; it is broken, not the SDKs"]

    languages = (("java", java), ("python", python), ("typescript", typescript))

    problems = []
    for name in sorted(java | python | typescript):
        if name in EXPORT_EXEMPTIONS:
            continue
        missing = [lang for lang, names in languages if name not in names]
        if missing:
            present = [lang for lang, names in languages if name in names]
            problems.append(
                f"{name} is exported by {', '.join(present)} but not by {', '.join(missing)}")

    if verbose:
        print(f"  exported names: java {len(java)}, python {len(python)}, "
              f"typescript {len(typescript)}; {len(EXPORT_EXEMPTIONS)} exempt")
    return problems


def main() -> int:
    verbose = "--verbose" in sys.argv

    for path in (JAVA, *PYTHON, *TYPESCRIPT):
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

    if len(shared) < EXPECTED_SHARED:
        # A type that stops being compared is worse than one that disagrees:
        # nothing is reported and coverage quietly shrinks. See EXPECTED_SHARED.
        print(f"parity: comparing {len(shared)} shared types, expected at least "
              f"{EXPECTED_SHARED}; a type is no longer being read from all three "
              f"SDKs. Check that it did not move out of a module listed above.",
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
            "\nA method added to one SDK is a method the other two are missing, and a "
            "method that demands an extra argument in one SDK is one its callers have to "
            "invent a value for. If a difference is intended, record it in "
            "METHOD_EXEMPTIONS (missing) or ARITY_EXEMPTIONS (arguments), with the reason.",
            file=sys.stderr,
        )
        return 1

    surface_problems = check_surface(verbose)
    if surface_problems:
        print(f"\nparity: the SDKs disagree about {len(surface_problems)} read-side "
              "member(s):\n", file=sys.stderr)
        for problem in surface_problems:
            print(f"  - {problem}", file=sys.stderr)
        print(
            "\nDesk, Book and Tape are what a caller does with the data "
            "the client returns. A member on one and not the others is work its callers "
            "have to do by hand -- which is how Desk.tape sat in Java alone. "
            "If a difference is intended, record it in SURFACE_EXEMPTIONS with the "
            "reason.",
            file=sys.stderr,
        )
        return 1

    doc_problems = check_docs(verbose)
    if doc_problems:
        print("\nparity: the SDKs document the same method unevenly:\n", file=sys.stderr)
        for problem in doc_problems:
            print(f"  - {problem}", file=sys.stderr)
        print(
            "\nA method documented in one SDK and not the others is a contract claim "
            "only one set of callers can read -- and only one maintainer can notice is "
            "wrong. See DOC_DEBT for why this is a ratchet rather than pass/fail.",
            file=sys.stderr,
        )
        return 1

    failure_problems = check_failures(verbose)
    if failure_problems:
        print(f"\nparity: the SDKs disagree about {len(failure_problems)} failure type(s):\n",
              file=sys.stderr)
        for problem in failure_problems:
            print(f"  - {problem}", file=sys.stderr)
        print(
            "\nA failure a caller can catch in one SDK and not another is a caller writing "
            "different code for the same server response. If a difference is intended, record "
            "it in FAILURE_EXEMPTIONS with the reason.",
            file=sys.stderr,
        )
        return 1

    export_problems = check_exports(verbose)
    if export_problems:
        print(f"\nparity: the SDKs disagree about {len(export_problems)} exported name(s):\n",
              file=sys.stderr)
        for problem in export_problems:
            print(f"  - {problem}", file=sys.stderr)
        print(
            "\nA name exported by one SDK and not another is a capability one set of "
            "callers has and another does not -- and it falls through every other check "
            "here, because it is not a wire field, a client method, a read-side member or "
            "a failure. If a difference is intended, record it in EXPORT_EXEMPTIONS with "
            "the reason.",
            file=sys.stderr,
        )
        return 1

    print(f"parity ok: {len(shared)} shared types agree on their wire fields, "
          f"the client and read-side surfaces agree, the same failures are "
          f"catchable in each, and the three export the same names")
    return 0


if __name__ == "__main__":
    sys.exit(main())
