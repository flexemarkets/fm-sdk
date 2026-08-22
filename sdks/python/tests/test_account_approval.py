"""A signed-up account nobody has decided about yet.

fm-server declares ``private Boolean approval`` -- three states, not two. A
freshly signed-up account carries ``"approval": null`` until an administrator
approves or suspends it.

Python read that with ``data.get("approval", False)``. That is right about a
present null -- ``.get`` returns the stored ``None`` -- and wrong about an
absent key, which becomes ``False``: a *suspended* account. The field was typed
``bool`` besides, so the value the parser did produce contradicted its own
annotation, and anything gating on it treated "waiting for you" as "refused".

Java has had this right since the field was boxed: ``Boolean approval`` plus
``isApproved()`` folding null to false at the point of asking, rather than at
the point of parsing where the third state is lost for good.
"""

from __future__ import annotations

from fm.client import _parse_account


def test_a_pending_account_is_not_a_suspended_one() -> None:
    account = _parse_account({"id": 5, "name": "acme", "approval": None})

    assert account.approval is None, "null became False, so pending reads as suspended"


def test_an_approved_account_is_approved() -> None:
    assert _parse_account({"id": 5, "approval": True}).approval is True


def test_a_suspended_account_is_suspended() -> None:
    assert _parse_account({"id": 5, "approval": False}).approval is False


def test_a_field_the_server_omitted_is_also_undecided() -> None:
    """Absent and null mean the same thing here, and neither means False."""
    assert _parse_account({"id": 5}).approval is None


def test_is_approved_answers_the_question_callers_actually_ask() -> None:
    """The convenience Java has, so a caller need not spell the null check.

    Folding the third state away is fine here -- this is the point of asking,
    not the point of parsing.
    """
    assert _parse_account({"id": 5, "approval": True}).is_approved() is True
    assert _parse_account({"id": 5, "approval": False}).is_approved() is False
    assert _parse_account({"id": 5, "approval": None}).is_approved() is False
