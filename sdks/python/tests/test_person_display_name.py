"""The one human label a Person record can always produce.

Every consumer of ``users()`` was writing this join by hand -- the reference
robot in fm-robot-llm-eval, and anything else that has to put a name next to an
``owner_id``. None of the three fields it reads can be relied on alone.

The same cases run in the Java and TypeScript suites. They are written out
rather than shared as a fixture because this is a pure function over one record,
not a sequence of updates over an aggregator -- the behaviour fixtures drive
``MarketBook`` and ``MarketTrades`` and have nowhere to put it.
"""

from __future__ import annotations

import pytest

from fm.types import Person


@pytest.mark.parametrize(
    "first,last,email,expected",
    [
        ("Ada", "Lovelace", "ada@example.com", "Ada Lovelace"),
        ("Ada", None, "ada@example.com", "Ada"),
        (None, "Lovelace", "ada@example.com", "Lovelace"),
        ("  Ada  ", "  Lovelace  ", None, "Ada Lovelace"),
        (None, None, "ada@example.com", "ada@example.com"),
        ("", "", "ada@example.com", "ada@example.com"),
        ("   ", "   ", "  ada@example.com  ", "ada@example.com"),
        (None, None, None, None),
        (None, None, "   ", None),
    ],
)
def test_display_name(first, last, email, expected) -> None:
    assert Person(first_name=first, last_name=last, email=email).display_name() == expected


def test_a_name_wins_over_the_email() -> None:
    """Not "Name <email>". That is a presentation choice, and a caller who wants
    it composes one -- baking it in would make the common case wrong."""
    person = Person(first_name="Ada", last_name="Lovelace", email="ada@example.com")
    assert person.display_name() == "Ada Lovelace"
    assert "ada@example.com" not in person.display_name()
