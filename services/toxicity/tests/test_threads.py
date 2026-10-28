from toxicity_service.threads import intra_op_threads


def test_intra_op_threads_equal_the_cgroup_cpu_limit(tmp_path):
    cpu_max = tmp_path / "cpu.max"
    cpu_max.write_text("200000 100000\n", encoding="ascii")

    assert intra_op_threads(cpu_max) == 2, "a 2 CPU quota must give 2 intra-op threads"


def test_intra_op_threads_are_at_least_one_for_fractional_quota(tmp_path):
    cpu_max = tmp_path / "cpu.max"
    cpu_max.write_text("50000 100000\n", encoding="ascii")

    assert intra_op_threads(cpu_max) == 1, "a 0.5 CPU quota must still give one thread"


def test_unlimited_cgroup_gets_one_thread_not_the_host_core_count(tmp_path):
    cpu_max = tmp_path / "cpu.max"
    cpu_max.write_text("max 100000\n", encoding="ascii")

    assert intra_op_threads(cpu_max) == 1, "an unlimited cgroup must not size the pool from the host"
