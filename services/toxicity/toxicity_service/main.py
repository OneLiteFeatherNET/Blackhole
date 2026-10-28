"""Process entry point. Configuration comes from the environment; the defaults match the Helm chart."""

from __future__ import annotations

import logging
import os

from .api import make_server
from .scorer import OnnxToxicityScorer
from .threads import DEFAULT_CPU_MAX_PATH, intra_op_threads


def main() -> None:
    logging.basicConfig(level=os.environ.get("TOXICITY_LOG_LEVEL", "INFO"), format="%(message)s")
    threads = intra_op_threads(os.environ.get("TOXICITY_CPU_MAX_PATH", DEFAULT_CPU_MAX_PATH))
    model = OnnxToxicityScorer(
        model_path=os.environ.get("TOXICITY_MODEL_PATH", "/models/model.onnx"),
        tokenizer_dir=os.environ.get("TOXICITY_TOKENIZER_DIR", "/models"),
        toxic_index=int(os.environ.get("TOXICITY_TOXIC_INDEX", "1")),
        version=os.environ.get("TOXICITY_MODEL_VERSION", "unknown"),
        intra_op_threads=threads,
    )
    server = make_server(model, "0.0.0.0", int(os.environ.get("TOXICITY_PORT", "8080")))
    logging.getLogger("toxicity").info("serving model_version=%s intra_op_threads=%d", model.version, threads)
    server.serve_forever()


if __name__ == "__main__":
    main()
