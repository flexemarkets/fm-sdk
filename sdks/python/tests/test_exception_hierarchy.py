"""What a caller who handles conflicts generally actually catches.

``AccountNameConflictError``'s docstring said it was catchable as a conflict
while it derived from ``FlexemarketsError`` directly, so ``except
ConflictError`` let through exactly the conflict the SDK goes to the trouble of
describing -- the one case where it has a suggested name to offer.

``ConflictError`` itself was exported from the package and never raised: a
plain 409 fell through to ``response.raise_for_status()`` and surfaced as
httpx's own error, so a caller who imported it and handled it caught nothing.
"""

from __future__ import annotations

import httpx
import pytest

from fm.client import _check_response
from fm.exceptions import (
    AccountNameConflictError,
    ConflictError,
    FlexemarketsError,
    PersonHasMarketplaceDataError,
)


def test_a_taken_account_name_is_catchable_as_a_conflict() -> None:
    with pytest.raises(ConflictError) as caught:
        raise AccountNameConflictError("Account name 'acme' is taken.", "acme-2")

    assert caught.value.suggested_name == "acme-2"


def test_a_user_who_owns_data_is_catchable_as_a_conflict() -> None:
    with pytest.raises(ConflictError):
        raise PersonHasMarketplaceDataError("User 7 has marketplace data.")


def test_conflicts_remain_catchable_as_the_base_error() -> None:
    with pytest.raises(FlexemarketsError):
        raise AccountNameConflictError("Account name 'acme' is taken.")


def test_a_plain_409_raises_a_conflict_rather_than_httpx_s_own_error() -> None:
    response = httpx.Response(409, text="Conflict", request=httpx.Request("GET", "http://x/api"))

    with pytest.raises(ConflictError):
        _check_response(response)


def test_a_404_still_falls_through_to_httpx() -> None:
    """Only 409 is claimed here; nothing else changed shape."""
    response = httpx.Response(404, text="Not found", request=httpx.Request("GET", "http://x/api"))

    with pytest.raises(httpx.HTTPStatusError):
        _check_response(response)
