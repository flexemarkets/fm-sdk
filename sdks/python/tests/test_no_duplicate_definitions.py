"""No method in the client is defined twice.

account_by_id and user_by_id each were. Python keeps the last definition
silently, so the earlier pair was dead code that read as live -- and a reader
who found the first copy would have been reading something that never ran.

They happened to be equivalent, so nothing misbehaved. The next pair might not
be, and the failure would be invisible: the file would say one thing and the
class would do another.
"""

from __future__ import annotations

import ast
from collections import Counter
from pathlib import Path

SOURCES = sorted((Path(__file__).resolve().parent.parent / "fm").glob("*.py"))


def test_no_class_defines_a_method_twice() -> None:
    duplicates: list[str] = []

    for source in SOURCES:
        tree = ast.parse(source.read_text())
        for node in ast.walk(tree):
            if not isinstance(node, ast.ClassDef):
                continue
            names = Counter(
                child.name
                for child in node.body
                if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef))
                # A property and its setter share a name legitimately.
                and not any(
                    isinstance(d, ast.Attribute) and d.attr == "setter"
                    for d in child.decorator_list
                )
            )
            duplicates += [
                f"{source.name}: {node.name}.{name} defined {count} times"
                for name, count in names.items()
                if count > 1
            ]

    assert duplicates == []
