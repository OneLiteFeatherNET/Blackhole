"""Shared fixtures. Every test builds its own fake model: no ONNX Runtime, no model file, no network."""

from __future__ import annotations

import pytest


class FakeModel:
    """Scores each text by its length in characters, capped to [0, 1]. Records nothing."""

    version = "fake-1"

    def score(self, texts: list[str]) -> list[float]:
        return [min(1.0, len(text) / 100) for text in texts]


@pytest.fixture
def fake_model() -> FakeModel:
    return FakeModel()
