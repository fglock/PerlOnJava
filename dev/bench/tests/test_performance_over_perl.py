"""Unit tests for paired performance benchmark analysis."""

from __future__ import annotations

import importlib.util
import json
import os
import shutil
import subprocess
import unittest
from pathlib import Path
from types import SimpleNamespace


MODULE_PATH = Path(__file__).resolve().parents[1] / "performance_over_perl.py"
SPEC = importlib.util.spec_from_file_location("performance_over_perl", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
benchmark = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark)


def run(rate: float, checksum: str = "same") -> dict:
    window = {"work_units_per_second": rate, "checksum": checksum}
    return {"stable": True, "measurements": [window, window], "checksum": checksum}


class StabilityTests(unittest.TestCase):
    def test_requires_five_warmup_windows(self) -> None:
        windows = [{"work_units_per_second": 100.0}] * 4
        self.assertFalse(benchmark.stability(windows)["stable"])

    def test_requires_low_slope_and_low_variation(self) -> None:
        stable = [{"work_units_per_second": 100.0 + i * 0.1} for i in range(5)]
        self.assertTrue(benchmark.stability(stable)["stable"])

        noisy = [{"work_units_per_second": value}
                 for value in (80.0, 120.0, 100.0, 120.0, 80.0)]
        result = benchmark.stability(noisy)
        self.assertFalse(result["stable"])
        self.assertLess(abs(result["slope"]), benchmark.SLOPE_LIMIT)
        self.assertGreater(result["coefficient_of_variation"], benchmark.CV_LIMIT)


class ProtocolEligibilityTests(unittest.TestCase):
    def test_requires_full_pinned_acceptance_configuration(self) -> None:
        accepted = SimpleNamespace(
            workloads=["method_anchor", "closure_anchor", "life"],
            pairs=7,
            initial_warmup_windows=10,
            max_warmup_windows=60,
            measurement_windows=15,
            window_seconds=1.0,
            nice=19,
            perl_source_revision="v5.42.2",
        )
        self.assertTrue(benchmark.protocol_eligibility(accepted)["acceptance_protocol"])

        smoke = SimpleNamespace(**{**vars(accepted), "pairs": 1,
                                   "measurement_windows": 3,
                                   "window_seconds": 0.5,
                                   "nice": -1,
                                   "perl_source_revision": None})
        result = benchmark.protocol_eligibility(smoke)
        self.assertFalse(result["acceptance_protocol"])
        self.assertEqual(len(result["reasons"]), 4)


class LaunchOrderTests(unittest.TestCase):
    def test_seeded_order_is_reproducible_and_balanced(self) -> None:
        first = benchmark.launch_order_schedule("life", 7, 1863)
        second = benchmark.launch_order_schedule("life", 7, 1863)

        self.assertEqual(first, second)
        self.assertEqual(len(first), 7)
        self.assertEqual(first.count(("perl", "jperl")), 4)
        self.assertEqual(first.count(("jperl", "perl")), 3)

    def test_workloads_get_distinct_randomized_schedules(self) -> None:
        method = benchmark.launch_order_schedule("method_anchor", 7, 1863)
        closure = benchmark.launch_order_schedule("closure_anchor", 7, 1863)

        self.assertNotEqual(method, closure)

    def test_supports_named_artifact_comparisons(self) -> None:
        schedule = benchmark.launch_order_schedule(
            "closure_anchor", 7, 1863, "candidate", "reference"
        )

        self.assertEqual(schedule.count(("candidate", "reference")), 4)
        self.assertEqual(schedule.count(("reference", "candidate")), 3)


class SummaryTests(unittest.TestCase):
    def test_summarizes_only_stable_checksum_matching_pairs(self) -> None:
        pairs = [
            {"workload": "method_anchor", "pair_number": 1,
             "engines": {"perl": run(100.0), "jperl": run(110.0)}},
            {"workload": "method_anchor", "pair_number": 2,
             "engines": {"perl": run(100.0), "jperl": run(900.0, "wrong")}},
            {"workload": "method_anchor", "pair_number": 3,
             "engines": {"perl": run(100.0), "jperl": {"stable": False,
                                                              "measurements": []}}},
        ]

        result = benchmark.summarize(pairs, ("method_anchor",))

        self.assertEqual(result["groups"]["method_anchor"]["complete_pairs"], 1)
        self.assertAlmostEqual(
            result["groups"]["method_anchor"]["jperl_over_perl_geometric_mean"], 1.1
        )
        self.assertEqual(result["complete_portfolio_pairs"], 1)

    def test_bootstrap_interval_is_repeatable_and_contains_constant_ratio(self) -> None:
        first = benchmark.bootstrap_interval([1.25] * 7)
        second = benchmark.bootstrap_interval([1.25] * 7)
        self.assertEqual(first, second)
        self.assertAlmostEqual(first[0], 1.25)
        self.assertAlmostEqual(first[1], 1.25)

    def test_excludes_pairs_with_unverified_priority(self) -> None:
        pair = {
            "workload": "life",
            "pair_number": 1,
            "engines": {
                "perl": {**run(100.0), "priority_verified": False},
                "jperl": {**run(110.0), "priority_verified": True},
            },
        }

        result = benchmark.summarize([pair], ("life",))

        self.assertEqual(result["groups"]["life"]["complete_pairs"], 0)
        self.assertIsNone(result["groups"]["life"]["jperl_over_perl_geometric_mean"])

    def test_summarizes_exact_candidate_against_parent_jar(self) -> None:
        pair = {
            "workload": "closure_anchor",
            "pair_number": 1,
            "engines": {
                "candidate": run(120.0),
                "reference": run(100.0),
            },
        }

        result = benchmark.summarize(
            [pair], ("closure_anchor",), "candidate", "reference"
        )

        self.assertAlmostEqual(
            result["groups"]["closure_anchor"][
                "numerator_over_denominator_geometric_mean"
            ],
            1.2,
        )
        self.assertEqual(result["numerator_engine"], "candidate")
        self.assertEqual(result["denominator_engine"], "reference")


class AnchorWorkerTests(unittest.TestCase):
    def test_standard_perl_matches_fixed_method_and_closure_anchors(self) -> None:
        perl = shutil.which("perl")
        timeout = shutil.which("timeout")
        self.assertIsNotNone(perl)
        self.assertIsNotNone(timeout)
        root = MODULE_PATH.parents[2]
        worker = root / "dev/bench/performance_workload.pl"

        expected = {"method_anchor": (20_000, "400080000"),
                    "closure_anchor": (20_000, "1440000")}
        for workload, (operations, checksum) in expected.items():
            with self.subTest(workload=workload):
                result = subprocess.run(
                    [timeout, "15", perl, str(worker), workload],
                    cwd=root,
                    env={**os.environ, "PERLONJAVA_REPO_ROOT": str(root)},
                    input='{"seconds":0.01}\n',
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    check=True,
                )
                ready, measured = [json.loads(line) for line in result.stdout.splitlines()]
                self.assertTrue(ready["ready"])
                self.assertEqual(measured["operation_units"], operations)
                self.assertEqual(measured["checksum"], checksum)
                self.assertEqual(measured["work_units"],
                                 measured["operations"] * operations)


if __name__ == "__main__":
    unittest.main()
