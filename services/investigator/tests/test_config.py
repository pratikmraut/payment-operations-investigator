from dataclasses import replace
from pathlib import Path

import pytest

from investigator.config import Settings


@pytest.mark.parametrize("invalid", [0, -1, 33, True, 1.5])
def test_model_threads_require_a_reasonable_positive_integer(settings, invalid):
    with pytest.raises(ValueError, match="POI_MODEL_THREADS"):
        replace(settings, model_threads=invalid)


def test_model_thread_environment_override(monkeypatch):
    monkeypatch.setenv("POI_MODEL_THREADS", "2")
    assert Settings.from_env().model_threads == 2


def test_provider_timeout_default_and_explicit_override(monkeypatch):
    monkeypatch.delenv("POI_MODEL_TIMEOUT_SECONDS", raising=False)
    assert Settings("test-key", Path("knowledge.json"), Path("checkpoints.sqlite")).model_timeout_seconds == 180.0
    assert Settings.from_env().model_timeout_seconds == 180.0
    monkeypatch.setenv("POI_MODEL_TIMEOUT_SECONDS", "15.5")
    assert Settings.from_env().model_timeout_seconds == 15.5
