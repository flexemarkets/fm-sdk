"""Endpoint resolution is pure (no network): a bare marketplace id expands to
the default production host, while a full URL is preserved for development."""

from __future__ import annotations

from fm.client import _marketplace_endpoint, _resolve_endpoint


def test_bare_id_resolves_to_default_production_host() -> None:
    assert _resolve_endpoint("2540") == {
        "endpoint": "https://api.flexemarkets.com/api/marketplaces/2540"
    }


def test_full_url_is_preserved() -> None:
    url = "http://localhost:8080/api/marketplaces/2540"
    assert _resolve_endpoint(url) == {"endpoint": url}


def test_marketplace_endpoint_helper() -> None:
    assert _marketplace_endpoint("7") == "https://api.flexemarkets.com/api/marketplaces/7"
