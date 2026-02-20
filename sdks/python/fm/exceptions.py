"""Flexemarkets API exceptions."""


class FlexemarketsError(Exception):
    """Base exception for all Flexemarkets errors."""


class AuthenticationError(FlexemarketsError):
    """Raised on 401 Unauthorized responses."""


class AuthorizationError(FlexemarketsError):
    """Raised on 403 Forbidden responses."""


class InvalidArgumentError(FlexemarketsError):
    """Raised on 400 Bad Request responses."""


class AccountNameConflictError(FlexemarketsError):
    """Raised when an account name is already taken (409)."""

    def __init__(self, message: str, suggested_name: str | None = None):
        super().__init__(message)
        self.suggested_name = suggested_name


class PersonHasMarketplaceDataError(FlexemarketsError):
    """Raised when deleting a user who has marketplace data (409)."""


class ConflictError(FlexemarketsError):
    """General 409 Conflict error."""


class ConnectionFailedError(FlexemarketsError):
    """Raised on 5xx server errors."""


class ConfigurationError(FlexemarketsError):
    """Raised for configuration problems."""
