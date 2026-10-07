"""Every test runs with an empty home directory and no FM_* overrides.

``Flexemarkets()`` loads ``~/.fm/credential`` and ``~/.fm/endpoint`` before
anything else, so without this a test reads whoever runs it: their account,
their password, and their endpoint, which can be production. The suite also
measured differently on a developer machine than on CI, because only the
first has those files.

The live-server tests are the exception: reading the real ``~/.fm`` is what
they are for, and they skip unless asked.
"""

from __future__ import annotations

from pathlib import Path

import pytest


@pytest.fixture(autouse=True)
def _isolated_home(request: pytest.FixtureRequest, monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    if request.module.__name__.endswith("test_flexemarkets_live_server"):
        return
    home = tmp_path / "home"
    home.mkdir()
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.delenv("FM_API_URL", raising=False)
    monkeypatch.delenv("FM_URL", raising=False)
