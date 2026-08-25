"""Endpoint resolution is pure (no network): a bare marketplace id expands to
the default production host, while a full URL is preserved for development."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from fm.client import _marketplace_endpoint, _resolve_endpoint, _server

# The API root is derived by hand in Java, Python and TypeScript, and nothing
# else holds the three together: check-parity.py compares wire fields and
# method surfaces, and this is neither -- ``_server`` is private in all three.
# That is how 0.1.1 shipped fixed in Java and unchanged here.
#
# See sdks/fixtures/endpoints/README.md.
ENDPOINT_FIXTURES = sorted(
    (Path(__file__).resolve().parents[2] / "fixtures" / "endpoints").glob("*.json")
)


def test_bare_id_resolves_to_default_production_host() -> None:
    assert _resolve_endpoint("2540") == {
        "endpoint": "https://api.flexemarkets.com/api/marketplaces/2540"
    }


def test_full_url_is_preserved() -> None:
    url = "http://localhost:8080/api/marketplaces/2540"
    assert _resolve_endpoint(url) == {"endpoint": url}


def test_marketplace_endpoint_helper() -> None:
    assert _marketplace_endpoint("7") == "https://api.flexemarkets.com/api/marketplaces/7"


@pytest.mark.parametrize("path", ENDPOINT_FIXTURES, ids=lambda p: p.stem)
def test_api_root_matches_the_shared_fixtures(path: Path) -> None:
    case = json.loads(path.read_text())
    assert _server(case["endpoint"]) == case["apiRoot"], case["why"]


def test_there_are_endpoint_fixtures_to_run() -> None:
    """Guard the guard: a bad glob would report everything passing."""
    assert len(ENDPOINT_FIXTURES) >= 6, f"only found {len(ENDPOINT_FIXTURES)}"
