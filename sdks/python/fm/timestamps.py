"""Reading the two ways the server spells a moment.

It sends both. Audit fields -- ``createdDate``, ``lastModifiedDate``, a
session's ``openDate`` -- come from a Java ``LocalDateTime`` and arrive bare:
``"2017-04-11T00:54:35.135"``, no zone, with anywhere between three and nine
fractional digits. ``expiresAt`` comes from a real ``Instant`` and arrives
``"2026-08-15T18:00:00Z"``.

**A bare timestamp is UTC.** The server writes them from a clock running UTC,
and reading one as local time is wrong by the reader's offset -- silently, and
only for people not in UTC, which is the worst way for a bug to be distributed.

``datetime.fromisoformat`` parses both shapes but leaves the bare one *naive*:
``tzinfo=None``. A naive datetime is not wrong so much as unanswerable -- it
cannot be compared with an aware one without raising, and ``.timestamp()`` on it
quietly assumes local time. So the assumption is made explicit here and every
value this returns is timezone-aware.
"""

from __future__ import annotations

from datetime import UTC, datetime


def parse(value: str | None) -> datetime | None:
    """The moment a value names, or ``None`` if it names none.

    ``None`` rather than a raise for an unreadable value, the same rule the
    enums follow: one unparseable field should cost the caller that field, not
    the whole response it arrived in.
    """
    if value is None or not value.strip():
        return None

    try:
        parsed = datetime.fromisoformat(value.strip())
    except ValueError:
        return None

    return parsed.replace(tzinfo=UTC) if parsed.tzinfo is None else parsed
