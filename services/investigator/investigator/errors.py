class ProviderUnavailable(RuntimeError):
    """An explicitly requested inference or embedding provider did not complete."""


class UatModelTimeout(ProviderUnavailable):
    """A UAT provider request exceeded its bounded transport wait."""


class UatModelBusy(ProviderUnavailable):
    """Another request owns the local model lock; no UAT call was started."""


class InvalidModelResult(RuntimeError):
    """Live output failed evidence/schema validation; it is not silently repaired."""


class SnapshotConflict(RuntimeError):
    """An investigation identifier was reused with a different input snapshot."""
