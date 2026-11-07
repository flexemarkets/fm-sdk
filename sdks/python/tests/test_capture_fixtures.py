"""scripts/capture-fixtures.py --check, which a release runs and which nothing else holds to anything."""

from __future__ import annotations

import importlib.util
import sys
import urllib.error
from pathlib import Path
from typing import Any

import pytest

_path = Path(__file__).resolve().parents[3] / "scripts" / "capture-fixtures.py"
_spec = importlib.util.spec_from_file_location("capture_fixtures", _path)
capture = importlib.util.module_from_spec(_spec)
sys.modules["capture_fixtures"] = capture
_spec.loader.exec_module(capture)

ROUTE = "/v1/marketplaces/{marketplaceId}/thing"


def _fixture(name: str, payload: Any, **captured: Any) -> tuple[Path, dict[str, Any]]:
    return Path(name), {"source": {"captured": {"path": ROUTE, **captured}}, "payload": payload}


@pytest.fixture
def run(monkeypatch):
    """check() on these fixtures against a server answering from *answers*, by URL; the URLs it asked for."""
    asked: list[str] = []

    def go(fixtures, answers, write=False):
        def get(url, _token):
            asked.append(url)
            answer = answers[url]
            if isinstance(answer, Exception):
                raise answer
            return answer

        monkeypatch.setattr(capture, "_fixtures", lambda: fixtures)
        monkeypatch.setattr(capture, "_get", get)
        server = object.__new__(capture.Server)
        server.api, server.marketplace_id, server.token = "http://s/api", "7", "t"
        return capture.check(server, write), asked

    return go


THING = "http://s/api/v1/marketplaces/7/thing"


def test_a_shape_that_matches_passes(run, capsys):
    code, _ = run([_fixture("a.json", {"id": 1})], {THING: {"id": 2}})
    assert code == 0
    assert "1 captured" in capsys.readouterr().out


def test_a_field_the_server_stopped_sending_fails(run):
    code, _ = run([_fixture("a.json", {"id": 1, "gone": True})], {THING: {"id": 2}})
    assert code == 1


def test_a_route_that_answers_an_error_fails(run):
    gone = urllib.error.HTTPError(THING, 404, "gone", None, None)
    code, _ = run([_fixture("a.json", {"id": 1}), _fixture("b.json", {"id": 1}, path="/v1/other")],
                  {THING: gone, "http://s/api/v1/other": {"id": 1}})
    assert code == 1


def test_a_route_that_is_not_a_read_is_named_and_never_sent(run, capsys):
    """It reached the URL as "/apiPOST /v1/...", and the whole check died with InvalidURL."""
    fixtures = [_fixture("placed.json", {"id": 1}, path="POST " + ROUTE), _fixture("a.json", {"id": 1})]
    code, asked = run(fixtures, {THING: {"id": 2}})
    assert code == 0
    assert asked == [THING], "an order must never be placed by a check"
    assert "placed.json" in capsys.readouterr().err


def test_a_route_written_as_a_get_is_read(run):
    code, asked = run([_fixture("a.json", {"id": 1}, path="GET " + ROUTE)], {THING: {"id": 2}})
    assert (code, asked) == (0, [THING])


def test_a_route_naming_a_session_reads_the_current_one(run):
    """It went out as a literal {sessionId}, and the server answered 400."""
    current = "http://s/api/v1/marketplaces/7/sessions/current"
    orders = "http://s/api/v1/marketplaces/7/orders?sessions=300"
    code, asked = run([_fixture("a.json", {"id": 1}, path="/v1/marketplaces/{marketplaceId}/orders?sessions={sessionId}")],
                      {current: {"id": 300}, orders: [{"id": 2}]})
    assert (code, asked) == (0, [current, orders])


def test_no_current_session_is_not_drift(run, capsys):
    current = "http://s/api/v1/marketplaces/7/sessions/current"
    none = urllib.error.HTTPError(current, 404, "none", None, None)
    fixtures = [_fixture("history.json", {"id": 1}, path="/x?sessions={sessionId}"), _fixture("a.json", {"id": 1})]
    code, _ = run(fixtures, {current: none, THING: {"id": 2}})
    assert code == 0
    assert "history.json" in capsys.readouterr().err


@pytest.mark.parametrize("captured", [{"body": True}, {}], ids=["whole-answer", "first-row"])
def test_an_empty_list_has_no_shape_to_compare(run, capsys, captured):
    """A marketplace with no orders read as the server having dropped every field of an order."""
    fixtures = [_fixture("rows.json", [{"id": 1}], **captured), _fixture("a.json", {"id": 1}, path="/v1/other")]
    code, _ = run(fixtures, {THING: [], "http://s/api/v1/other": {"id": 2}})
    assert code == 0
    assert "rows.json" in capsys.readouterr().err


def test_comparing_nothing_is_not_a_pass(run):
    code, _ = run([_fixture("a.json", [{"id": 1}], select={"type": "LIMIT"})], {THING: []})
    assert code == 1


def test_a_filter_naming_a_field_its_own_payload_lacks_fails_the_audit(monkeypatch, capsys):
    """It can match no row anywhere, so the fixture is silently never compared, as one was for over two weeks."""
    monkeypatch.setattr(capture, "_fixtures", lambda: [_fixture("a.json", {"type": "LIMIT"}, select={"privateMarket": True})])
    assert capture.audit() == 1
    assert "privateMarket" in capsys.readouterr().err


def test_a_filter_its_own_payload_satisfies_passes_the_audit(monkeypatch):
    monkeypatch.setattr(capture, "_fixtures", lambda: [_fixture("a.json", {"type": "LIMIT"}, select={"type": "LIMIT"})])
    assert capture.audit() == 0
