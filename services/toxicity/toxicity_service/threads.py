"""Intra-op thread count derived from the container CPU limit.

ONNX Runtime sizes its thread pool from the host core count unless told otherwise, which oversubscribes
a pod with a CPU limit. The thread count is therefore read from the cgroup v2 `cpu.max` file.
"""

from __future__ import annotations

import os
from pathlib import Path

DEFAULT_CPU_MAX_PATH = "/sys/fs/cgroup/cpu.max"


def intra_op_threads(cpu_max_path: str | os.PathLike[str] = DEFAULT_CPU_MAX_PATH) -> int:
    """Return the whole number of CPUs granted by the cgroup quota, at least 1.

    The file holds `<quota> <period>` in microseconds, or `max <period>` when the container has no limit.
    An unlimited container gets one thread: the pod is sized for its limit, not for the host.
    """
    quota_text, period_text = Path(cpu_max_path).read_text(encoding="ascii").split()
    if quota_text == "max":
        return 1
    return max(1, int(quota_text) // int(period_text))
