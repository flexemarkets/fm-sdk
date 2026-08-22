"""The HAL envelope the server currently speaks.

Private, and named to say so. HAL is a transport detail: the SDK reads
``_links`` to discover where things live and then hands callers plain objects,
so nothing in the public surface should mention it. The server is moving to
HAL-less endpoints, and when it does this module goes away without a version
bump -- which it could not have done while ``ApiRoot`` was exported from
``fm``.

Java keeps the same type in ``fm.internal`` for the same reason.
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class ApiRoot:
    links: dict[str, str] = field(default_factory=dict)

    def get_link(self, name: str) -> str | None:
        return self.links.get(name)
