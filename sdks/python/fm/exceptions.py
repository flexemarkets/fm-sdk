"""Flexemarkets API exceptions."""


class FlexemarketsError(Exception):
    """Base exception for all Flexemarkets errors."""


class AuthenticationError(FlexemarketsError):
    """Raised on 401 Unauthorized responses."""


class AuthorizationError(FlexemarketsError):
    """Raised on 403 Forbidden responses."""


class InvalidArgumentError(FlexemarketsError):
    """Raised on 400 Bad Request responses."""


class ConflictError(FlexemarketsError):
    """General 409 Conflict error."""


class AccountNameConflictError(ConflictError):
    """Raised when an account name is already taken (409).

    A subclass of :class:`ConflictError` rather than a sibling, so a caller
    that handles conflicts generally still catches this one. It derived from
    :class:`FlexemarketsError` directly until 0.0.14, so ``except
    ConflictError`` did not catch it -- a caller who handled conflicts lost
    exactly the conflict the SDK went to the trouble of describing.
    """

    def __init__(self, message: str, suggested_name: str | None = None):
        super().__init__(message)
        self.suggested_name = suggested_name


class PersonHasMarketplaceDataError(ConflictError):
    """Raised when deleting a user who has marketplace data (409).

    Also a conflict, and catchable as one, for the same reason.
    """


class ConnectionFailedError(FlexemarketsError):
    """Raised on 5xx server errors."""


class ConfigurationError(FlexemarketsError):
    """Raised for configuration problems."""
