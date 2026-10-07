"""Where a client's account, credentials and endpoint come from when the caller
does not pass them: ``~/.fm/credential``, then ``~/.fm/endpoint``, then
``FM_API_URL``, then production. conftest gives each test an empty home."""

from __future__ import annotations

from pathlib import Path

import pytest

from fm.client import _DEFAULT_ENDPOINT, _load_config, _load_properties_file


def _fm_dir() -> Path:
    fm = Path.home() / ".fm"
    fm.mkdir(exist_ok=True)
    return fm


def test_with_no_files_and_no_override_the_endpoint_is_production() -> None:
    assert _load_config() == {"endpoint": _DEFAULT_ENDPOINT}


def test_the_credential_and_endpoint_files_are_read() -> None:
    (_fm_dir() / "credential").write_text("account=lab\nemail=ada@lab.edu\npassword=pw\n")
    (_fm_dir() / "endpoint").write_text("endpoint=http://localhost:8080/api/marketplaces/7\n")

    assert _load_config() == {
        "account": "lab",
        "email": "ada@lab.edu",
        "password": "pw",
        "endpoint": "http://localhost:8080/api/marketplaces/7",
    }


def test_fm_api_url_overrides_the_endpoint_file(monkeypatch: pytest.MonkeyPatch) -> None:
    (_fm_dir() / "endpoint").write_text("endpoint=http://localhost:8080/api/marketplaces/7\n")
    monkeypatch.setenv("FM_API_URL", "http://localhost:9090/api/marketplaces/8")

    assert _load_config()["endpoint"] == "http://localhost:9090/api/marketplaces/8"


def test_a_properties_file_skips_comments_blank_lines_and_lines_without_a_value(tmp_path: Path) -> None:
    path = tmp_path / "credential"
    path.write_text("# account=other\n\n  account = lab  \nnot a property\npassword=a=b\n")

    # Split on the first '=' only: a password may contain one.
    assert _load_properties_file(path) == {"account": "lab", "password": "a=b"}


def test_a_missing_properties_file_is_empty(tmp_path: Path) -> None:
    assert _load_properties_file(tmp_path / "absent") == {}
