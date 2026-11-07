"""What a caller's type checker and ``typing.get_type_hints`` are promised."""

from __future__ import annotations

import inspect
from pathlib import Path
from typing import get_type_hints

import pytest

import fm
from fm.desk import DeskHandle
from fm.order_utils import contra

CLASSES = sorted(n for n in fm.__all__ if inspect.isclass(getattr(fm, n)))


def test_the_package_says_it_is_typed():
    """Without this marker a caller's checker ignores every annotation here (PEP 561)."""
    assert (Path(fm.__file__).parent / "py.typed").is_file()


@pytest.mark.parametrize("name", CLASSES)
def test_a_public_class_has_annotations_that_resolve(name):
    """ParticipantState named Any without importing it, so asking for its hints raised NameError."""
    get_type_hints(getattr(fm, name))


def test_the_public_desk_is_what_a_desk_call_returns():
    """A robot annotates `desk: Desk`, as in Java and TypeScript, and it was the shared desk it never holds."""
    assert fm.Desk is DeskHandle


@pytest.mark.parametrize("side", ["sideways", None])
def test_the_other_side_of_no_side_is_refused_by_name(side):
    """It was an AttributeError on None, which said nothing of the side."""
    with pytest.raises(ValueError, match="no contra"):
        contra(side)
