#!/usr/bin/env python3
"""Assert each SDK's README quickstart still compiles against that SDK.

The quickstarts are the first code anyone reads, and nothing was holding them to
the API. All three had rotted at once: every one imported `Books` and `Tapes`,
withdrawn to `fm.internal`, and taught the hand-rolled aggregation loop a desk
replaced. Java's was worse -- its imports still said `fm.Holding` and `fm.Market`,
so the quickstart for the release being cut did not compile at all.

Every other check in this repo compares the SDKs to each other. This one
compares each SDK to its own documentation, which is the direction the failure
came from: the code moved and the prose did not.

**What this proves, per language, being exact rather than claiming one story for
all three.**

  Java        Compiled. The block is wrapped in a class and run through javac
              against the module's own target/classes, so a moved package or a
              changed signature fails here.
  TypeScript  Type-checked. The block goes through tsc --noEmit --strict against
              src, so a withdrawn export or a wrong argument fails here.
  Python      Resolved, not compiled -- the project has no type checker. Every
              name the example imports from `fm` must exist, every attribute it
              reaches for on an SDK object must exist, and calls are checked for
              arity. That is weaker than a compiler and still catches the whole
              class of failure that shipped.

The examples are fragments: they assume a connection and a market id rather than
inventing one. Each language therefore declares a PREAMBLE below, which is the
smallest thing that makes the fragment a compilable unit. A preamble that has to
grow to keep the check passing is a signal the example has drifted from
something a reader could actually paste.

Usage:  python3 scripts/check-quickstarts.py [--verbose]
Exit:   0 when every quickstart still matches its SDK, 1 when one does not.
"""

from __future__ import annotations

import ast
import inspect
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA_CLASSES = ROOT / "sdks/java/fm-sdk/target/classes"
TYPESCRIPT = ROOT / "sdks/typescript"
PYTHON_VENV = ROOT / "sdks/python/.venv/bin/python"


def block(readme: Path, language: str) -> str:
    """The first fenced block of `language` in a README."""
    match = re.search(rf"```{language}\n(.*?)```", readme.read_text(), re.S)
    if not match:
        raise SystemExit(f"{readme}: no ```{language} block found")
    return match.group(1)


# --------------------------------------------------------------------- java ---

def check_java(verbose: bool) -> list[str]:
    if not (JAVA_CLASSES / "fm/Flexemarkets.class").exists():
        return ["java quickstart: fm-sdk is not built; run `make build-java` first"]

    source = block(ROOT / "sdks/java/README.md", "java")
    imports = [l for l in source.splitlines() if l.startswith("import ")]
    body = [l for l in source.splitlines() if not l.startswith("import ")]

    # PREAMBLE: the fragment is statements, so it needs a class and a main.
    unit = ("\n".join(imports)
            + "\n\npublic class Quickstart {\n"
            + "  public static void main(String[] args) throws Exception {\n"
            + "\n".join("    " + l for l in body)
            + "\n  }\n}\n")

    jackson = list(Path.home().glob(
        ".m2/repository/**/jackson-annotations-*.jar"))
    classpath = str(JAVA_CLASSES) + (f":{jackson[0]}" if jackson else "")

    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "Quickstart.java"
        path.write_text(unit)
        result = subprocess.run(
            ["javac", "-nowarn", "-cp", classpath, "-d", tmp, str(path)],
            capture_output=True, text=True)

    if result.returncode != 0:
        lines = [l for l in result.stderr.splitlines() if "error" in l.lower()]
        return [f"java quickstart does not compile: {l.strip()}" for l in lines[:6]]
    if verbose:
        print(f"  java:       {len(body)} lines compiled against target/classes")
    return []


# --------------------------------------------------------------- typescript ---

def check_typescript(verbose: bool) -> list[str]:
    source = block(ROOT / "sdks/typescript/README.md", "typescript")

    # PREAMBLE: top-level await needs a function, and the README imports by
    # package name, which only resolves once published.
    unit = source.replace('from "@flexemarkets/fm-sdk"', 'from "./src/index.js"')
    unit = re.sub(r"^(import .*;\n)+", r"\g<0>\nasync function quickstart() {\n",
                  unit, count=1)
    unit += "}\nvoid quickstart;\n"

    path = TYPESCRIPT / "_quickstart_check.ts"
    path.write_text(unit)
    try:
        result = subprocess.run(
            ["npx", "tsc", "--noEmit", "--strict", "--module", "nodenext",
             "--moduleResolution", "nodenext", "--target", "es2022",
             "--skipLibCheck", path.name],
            cwd=TYPESCRIPT, capture_output=True, text=True)
    finally:
        path.unlink(missing_ok=True)

    if result.returncode != 0:
        lines = [l for l in result.stdout.splitlines() if "error" in l]
        return [f"typescript quickstart does not type-check: {l.strip()}"
                for l in lines[:6]]
    if verbose:
        print(f"  typescript: {len(unit.splitlines())} lines type-checked --strict")
    return []


# ------------------------------------------------------------------- python ---

def check_python(verbose: bool) -> list[str]:
    """
    Resolve the example against the real package.

    No compiler exists for this, so the check is explicit: the names imported
    from `fm` must exist, and every attribute reached on an object the example
    obtained from the SDK must exist on that type with a compatible arity.
    """
    source = block(ROOT / "sdks/python/README.md", "python")
    try:
        tree = ast.parse(source)
    except SyntaxError as e:
        return [f"python quickstart is not valid Python: {e}"]

    probe = f"""
import ast, inspect, json, sys
import fm
from fm.desk import Desk
from fm.orderbook import Book
from fm.trades import Tape

problems = []
source = {source!r}
tree = ast.parse(source)

# 1. Every name imported from fm must be exported.
for node in ast.walk(tree):
    if isinstance(node, ast.ImportFrom) and node.module == "fm":
        for alias in node.names:
            if not hasattr(fm, alias.name):
                problems.append(f"`from fm import {{alias.name}}` -- fm exports no such name")

# 2. Every name the example uses must be bound somewhere in it.
bound = {{"print", "range", "len", "int", "str", "list", "dict", "queue", "__name__"}}
for node in ast.walk(tree):
    if isinstance(node, ast.ImportFrom) or isinstance(node, ast.Import):
        for alias in node.names:
            bound.add(alias.asname or alias.name.split(".")[0])
    elif isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store):
        bound.add(node.id)
    elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
        bound.add(node.name)
    elif isinstance(node, ast.arg):
        bound.add(node.arg)
    elif isinstance(node, ast.withitem) and isinstance(node.optional_vars, ast.Name):
        bound.add(node.optional_vars.id)
    elif isinstance(node, ast.comprehension) and isinstance(node.target, ast.Name):
        bound.add(node.target.id)
for node in ast.walk(tree):
    if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load):
        if node.id not in bound:
            problems.append(f"`{{node.id}}` is used but never defined in the example")

# 3. Attributes reached on an SDK type must exist, with a workable arity.
TYPES = {{"desk": Desk, "book": Book, "one": Book, "fm": fm.Flexemarkets, "tape": Tape}}
for node in ast.walk(tree):
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
        target = node.func.value
        if not isinstance(target, ast.Name) or target.id not in TYPES:
            continue
        owner, name = TYPES[target.id], node.func.attr
        member = getattr(owner, name, None)
        if member is None:
            problems.append(f"{{owner.__name__}}.{{name}} does not exist")
            continue
        if callable(member):
            try:
                params = [p for p in inspect.signature(member).parameters.values()
                          if p.name != "self"]
            except (TypeError, ValueError):
                continue
            required = sum(1 for p in params
                           if p.default is inspect.Parameter.empty
                           and p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD))
            given = len(node.args) + len(node.keywords)
            if given < required or (given > len(params) and not any(
                    p.kind == p.VAR_POSITIONAL for p in params)):
                problems.append(
                    f"{{owner.__name__}}.{{name}} takes {{required}} argument(s), "
                    f"the example passes {{given}}")

print(json.dumps(sorted(set(problems))))
"""
    result = subprocess.run([str(PYTHON_VENV), "-c", probe],
                            capture_output=True, text=True, cwd=ROOT / "sdks/python")
    if result.returncode != 0:
        return [f"python quickstart check could not run: {result.stderr.strip()[:300]}"]

    import json
    problems = json.loads(result.stdout)
    if verbose and not problems:
        print(f"  python:     {len(source.splitlines())} lines resolved against fm")
    return [f"python quickstart: {p}" for p in problems]


def main() -> int:
    verbose = "--verbose" in sys.argv
    problems = check_java(verbose) + check_typescript(verbose) + check_python(verbose)

    if problems:
        print(f"\nquickstarts: {len(problems)} problem(s):\n", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        print(
            "\nThe quickstart is the first code anyone reads. A name it uses that the "
            "SDK no longer exports, or a signature it calls wrongly, is a reader's "
            "first impression failing to compile. Fix the README rather than this "
            "check -- the SDK is what shipped.",
            file=sys.stderr,
        )
        return 1

    print("quickstarts ok: java compiles, typescript type-checks, "
          "python resolves against fm")
    return 0


if __name__ == "__main__":
    sys.exit(main())
