"""The scoring seam. The HTTP layer depends only on `ToxicityModel`. ONNX Runtime and the tokenizer are
imported inside `OnnxToxicityScorer`, so the tests and the HTTP layer run without them installed.
"""

from __future__ import annotations

import os
from typing import Protocol


class ToxicityModel(Protocol):
    """A loaded model. `score` returns one toxic probability in [0, 1] per text, in input order."""

    version: str

    def score(self, texts: list[str]) -> list[float]: ...


class OnnxToxicityScorer:
    """XLM-RoBERTa sequence classifier exported to ONNX (int8 weights), run on the CPU execution provider.

    The model is loaded once in `__init__`. `score` keeps no state between calls.
    """

    MAX_TOKENS = 128

    def __init__(
        self,
        model_path: str,
        tokenizer_dir: str,
        toxic_index: int,
        version: str,
        intra_op_threads: int,
    ) -> None:
        import onnxruntime as ort
        from tokenizers import Tokenizer

        options = ort.SessionOptions()
        options.intra_op_num_threads = intra_op_threads
        options.inter_op_num_threads = 1
        self._session = ort.InferenceSession(
            model_path, sess_options=options, providers=["CPUExecutionProvider"]
        )
        self._tokenizer = Tokenizer.from_file(os.path.join(tokenizer_dir, "tokenizer.json"))
        self._tokenizer.enable_truncation(max_length=self.MAX_TOKENS)
        self._tokenizer.enable_padding()
        self._toxic_index = toxic_index
        self.version = version

    def score(self, texts: list[str]) -> list[float]:
        import numpy as np

        encodings = self._tokenizer.encode_batch(texts)
        input_ids = np.array([e.ids for e in encodings], dtype=np.int64)
        attention_mask = np.array([e.attention_mask for e in encodings], dtype=np.int64)
        logits = self._session.run(None, {"input_ids": input_ids, "attention_mask": attention_mask})[0]
        shifted = logits - logits.max(axis=1, keepdims=True)
        probabilities = np.exp(shifted) / np.exp(shifted).sum(axis=1, keepdims=True)
        return [min(1.0, max(0.0, float(p))) for p in probabilities[:, self._toxic_index]]
