"""A refusal carries the server's answer, not only a sentence about it.

The message is for people. A caller deciding what to do -- an order refused
because the session closed, against one refused for its price -- branches on
the body's ``error``, and a transport handing the refusal on (FMClientLibrary's
order_rejected receives the body and the status) needs both as sent.
"""

import json

import httpx
import pytest

from fm.client import _check_response
from fm.exceptions import (AuthenticationError, ConflictError, ConnectionFailedError, FlexemarketsError,
                           HttpError, InvalidArgumentError)


def _response(status: int, body: str) -> httpx.Response:
    return httpx.Response(status, text=body, request=httpx.Request("POST", "http://fm.test/api/v1/orders"))


REFUSED = json.dumps({"error": "SESSION_CLOSED", "message": "Closed session.", "path": "/api/orders",
                      "status": "BAD_REQUEST"})


@pytest.mark.parametrize("status, kind", [(400, InvalidArgumentError), (401, AuthenticationError),
                                          (409, ConflictError), (418, HttpError),
                                          (503, ConnectionFailedError)])
def test_every_refusal_carries_status_reason_and_body(status, kind):
    with pytest.raises(kind) as raised:
        _check_response(_response(status, REFUSED))

    assert raised.value.status_code == status
    assert raised.value.reason == "SESSION_CLOSED"
    assert raised.value.body == REFUSED


def test_a_body_that_names_no_error_leaves_the_reason_unknown():
    for body in ("", "<html>bad gateway</html>", json.dumps({"message": "no code"})):
        with pytest.raises(FlexemarketsError) as raised:
            _check_response(_response(400, body))
        assert raised.value.reason is None
        assert raised.value.body == body


def test_an_error_the_sdk_raised_itself_carries_no_answer():
    error = InvalidArgumentError("raised locally")
    assert (error.status_code, error.reason, error.body) == (None, None, None)
