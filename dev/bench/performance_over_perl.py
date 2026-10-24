#!/usr/bin/env python3
"""Paired, warmup-aware Perl versus PerlOnJava benchmark runner."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import platform
import random
import shutil
import signal
import statistics
import subprocess
import sys
from pathlib import Path
from typing import Any


WORKLOADS = (
    "method_anchor", "closure_anchor", "life",
    "closure", "dispatch", "numeric", "string", "regex", "json",
)
WORKER = Path("dev/bench/performance_workload.pl")
LIFE_SCRIPT = Path("examples/life_bitpacked.pl")
METHOD_ANCHOR = Path("dev/bench/benchmark_method.pl")
CLOSURE_ANCHOR = Path("dev/bench/benchmark_closure.pl")


def run_text(command: list[str], timeout_seconds: int = 30) -> str:
    try:
        result = subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout_seconds,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return f"unavailable: {exc}"
    output = result.stdout.strip()
    return output if result.returncode == 0 else f"exit {result.returncode}: {output}"


def sha256(path: Path) -> str | None:
    if not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def jar_path(args: argparse.Namespace | None = None) -> Path | None:
    if args is not None and getattr(args, "jperl_jar", None):
        return Path(args.jperl_jar).resolve()
    if os.environ.get("PERLONJAVA_JAR"):
        return Path(os.environ["PERLONJAVA_JAR"]).resolve()
    target_jar = ROOT / "target/perlonjava-5.44.1.jar"
    if target_jar.is_file():
        return target_jar
    candidates = sorted((ROOT / "build/install/perlonjava/lib").glob("perlonjava-*.jar"))
    if not candidates:
        candidates = sorted((ROOT / "build/libs").glob("*all.jar"))
    return candidates[-1] if candidates else None


def host_load() -> list[float] | None:
    try:
        return [float(value) for value in os.getloadavg()]
    except (AttributeError, OSError):
        return None


def cpu_model() -> str | None:
    if platform.system() == "Darwin":
        value = run_text(["sysctl", "-n", "machdep.cpu.brand_string"])
        return None if value.startswith("unavailable:") else value
    cpuinfo = Path("/proc/cpuinfo")
    if cpuinfo.is_file():
        for line in cpuinfo.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.lower().startswith(("model name", "hardware")) and ":" in line:
                return line.split(":", 1)[1].strip()
    return platform.processor() or None


def metadata(args: argparse.Namespace) -> dict[str, Any]:
    java = shutil.which("java")
    perl = shutil.which(args.perl)
    jar = jar_path(args)
    try:
        git_commit = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, timeout=10
        ).strip()
    except (OSError, subprocess.SubprocessError):
        git_commit = None

    return {
        "created_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "schema_version": 1,
        "git_commit": git_commit,
        "source_sha256": {
            str(WORKER): sha256(ROOT / WORKER),
            str(LIFE_SCRIPT): sha256(ROOT / LIFE_SCRIPT),
            str(METHOD_ANCHOR): sha256(ROOT / METHOD_ANCHOR),
            str(CLOSURE_ANCHOR): sha256(ROOT / CLOSURE_ANCHOR),
            "dev/bench/performance_over_perl.py": sha256(Path(__file__).resolve()),
        },
        "jar_path": str(jar) if jar else None,
        "jar_sha256": sha256(jar) if jar else None,
        "comparison_reference_jar": (
            str(Path(args.reference_jperl_jar).resolve())
            if getattr(args, "reference_jperl_jar", None) else None
        ),
        "comparison_reference_jar_sha256": (
            sha256(Path(args.reference_jperl_jar).resolve())
            if getattr(args, "reference_jperl_jar", None) else None
        ),
        "host": {
            "platform": platform.platform(),
            "system": platform.uname()._asdict(),
            "processor": platform.processor(),
            "cpu_model": cpu_model(),
            "logical_cpu_count": os.cpu_count(),
            "load_average_before": host_load(),
        },
        "perl_executable": perl,
        "perl_version": run_text([perl, "-e", "print $^V"] if perl else [args.perl]),
        "perl_configuration": run_text([perl, "-V"] if perl else [args.perl, "-V"]),
        "perl_source_revision": args.perl_source_revision,
        "java_executable": java,
        "java_version": run_text([java, "-version"] if java else ["java", "-version"]),
        "jperl_executable": args.jperl,
        "jperl_opts": os.environ.get("JPERL_OPTS", ""),
        "java_tool_options": os.environ.get("JAVA_TOOL_OPTIONS", ""),
        "perl5opt": os.environ.get("PERL5OPT", ""),
        "perl5lib": os.environ.get("PERL5LIB", ""),
        "configuration": {
            "workloads": list(args.workloads),
            "pairs": args.pairs,
            "initial_warmup_windows": args.initial_warmup_windows,
            "measurement_windows": args.measurement_windows,
            "window_seconds": args.window_seconds,
            "max_warmup_windows": args.max_warmup_windows,
            "stability_slope_limit": args.stability_slope_limit,
            "stability_cv_limit": args.stability_cv_limit,
            "requested_nice": args.nice if args.nice >= 0 else None,
            "launch_order_seed": args.launch_order_seed,
        },
        "protocol_eligibility": protocol_eligibility(args),
    }


def worker_command(engine: str, workload: str, args: argparse.Namespace) -> list[str]:
    executable = args.perl if engine == "perl" else args.jperl
    command = [executable, str(WORKER), workload]
    timeout = shutil.which(args.timeout_command)
    if timeout is None:
        raise RuntimeError(f"timeout command not found: {args.timeout_command}")
    command = [timeout, str(args.process_timeout_seconds), *command]
    if args.nice is not None and args.nice >= 0:
        nice = shutil.which("nice")
        if nice is None:
            raise RuntimeError("nice command not found")
        command = [nice, "-n", str(args.nice), *command]
    return command


def read_window(process: subprocess.Popen[str], seconds: float) -> dict[str, Any]:
    assert process.stdin is not None and process.stdout is not None
    process.stdin.write(json.dumps({"seconds": seconds}) + "\n")
    process.stdin.flush()
    line = process.stdout.readline()
    if not line:
        raise RuntimeError("benchmark worker exited before returning a window")
    result = json.loads(line)
    if "error" in result:
        raise RuntimeError(result["error"])
    if result.get("wall_seconds", 0) <= 0 or result.get("operations", 0) <= 0:
        raise RuntimeError(f"invalid benchmark window: {result}")
    result["work_units_per_second"] = result["work_units"] / result["wall_seconds"]
    result["operations_per_second"] = result["operations"] / result["wall_seconds"]
    return result


def process_nice(pid: int) -> int | None:
    try:
        result = subprocess.run(
            ["ps", "-o", "ni=", "-p", str(pid)],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=5,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    try:
        return int(result.stdout.strip()) if result.returncode == 0 else None
    except ValueError:
        return None


def stability(windows: list[dict[str, Any]]) -> dict[str, Any]:
    if len(windows) < 5:
        return {"stable": False, "slope": None, "coefficient_of_variation": None}
    values = [float(window["work_units_per_second"]) for window in windows[-5:]]
    mean = statistics.fmean(values)
    if mean == 0:
        return {"stable": False, "slope": None, "coefficient_of_variation": None}
    slope = (values[-1] - values[0]) / (4 * mean)
    cv = statistics.pstdev(values) / mean
    return {"stable": abs(slope) < SLOPE_LIMIT and cv < CV_LIMIT, "slope": slope,
            "coefficient_of_variation": cv}


def run_engine(engine: str, workload: str, args: argparse.Namespace,
               pair_number: int) -> dict[str, Any]:
    log_dir = args.output.parent / (args.output.stem + "-logs")
    log_dir.mkdir(parents=True, exist_ok=True)
    stderr_path = log_dir / f"pair-{pair_number}-{workload}-{engine}.stderr.txt"
    environment = os.environ.copy()
    environment["PERLONJAVA_REPO_ROOT"] = str(ROOT)
    environment.pop("PERLONJAVA_LIFE_BENCH_LOAD", None)
    selected_jar = None
    if engine == "jperl" or engine == "candidate":
        selected_jar = args.jperl_jar
    elif engine == "reference":
        selected_jar = args.reference_jperl_jar
    if selected_jar:
        environment["PERLONJAVA_JAR"] = str(Path(selected_jar).resolve())
    elif engine in {"candidate", "reference"}:
        environment.pop("PERLONJAVA_JAR", None)
    command = worker_command(engine, workload, args)
    with stderr_path.open("w", encoding="utf-8") as stderr_file:
        process = subprocess.Popen(
            command,
            cwd=ROOT,
            env=environment,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=stderr_file,
            text=True,
            bufsize=1,
            start_new_session=True,
        )
        try:
            assert process.stdout is not None
            ready_line = process.stdout.readline()
            ready = json.loads(ready_line) if ready_line else None
            if not ready or ready.get("ready") is not True:
                raise RuntimeError(f"worker did not become ready: {ready_line!r}")
            requested_nice = args.nice if args.nice >= 0 else None
            observed_nice = process_nice(process.pid)
            priority_verified = (
                requested_nice is None or observed_nice == requested_nice
            )

            warmup: list[dict[str, Any]] = []
            stable_after = None
            for window_number in range(1, args.max_warmup_windows + 1):
                warmup.append(read_window(process, args.window_seconds))
                if window_number >= args.initial_warmup_windows:
                    result = stability(warmup)
                    if result["stable"]:
                        stable_after = window_number
                        break

            stable = stable_after is not None
            measured: list[dict[str, Any]] = []
            if stable:
                measured = [read_window(process, args.window_seconds)
                            for _ in range(args.measurement_windows)]
            if process.stdin is not None:
                process.stdin.close()
            return_code = process.wait()
            if return_code != 0:
                raise RuntimeError(f"{engine} worker exited {return_code}; see {stderr_path}")

            return {
                "engine": engine,
                "workload": workload,
                "stable": stable,
                "priority_requested": requested_nice,
                "priority_observed": observed_nice,
                "priority_verified": priority_verified,
                "command": command,
                "ineligible_reason": (
                    "requested process niceness could not be verified"
                    if not priority_verified else None
                ),
                "stable_after_warmup_windows": stable_after,
                "final_warmup_stability": stability(warmup),
                "warmup": warmup,
                "measurements": measured,
                "stderr_path": str(stderr_path),
            }
        except Exception:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except (ProcessLookupError, PermissionError):
                pass
            process.wait(timeout=10)
            raise


def geometric_mean(values: list[float]) -> float | None:
    if not values or any(value <= 0 for value in values):
        return None
    return math.exp(statistics.fmean(math.log(value) for value in values))


def bootstrap_interval(values: list[float], seed: int = 1196) -> list[float] | None:
    if not values:
        return None
    randomizer = random.Random(seed)
    samples = [geometric_mean([randomizer.choice(values) for _ in values])
               for _ in range(10000)]
    samples = sorted(value for value in samples if value is not None)
    low = samples[int(0.025 * (len(samples) - 1))]
    high = samples[int(0.975 * (len(samples) - 1))]
    return [low, high]


def summarize(pairs: list[dict[str, Any]], workloads: tuple[str, ...],
              numerator_engine: str = "jperl",
              denominator_engine: str = "perl") -> dict[str, Any]:
    group_ratios: dict[str, list[float]] = {name: [] for name in workloads}
    pair_ratios: dict[int, dict[str, float]] = {}
    min_ratio: float | None = None

    for pair in pairs:
        workload = pair["workload"]
        engines = pair["engines"]
        denominator = engines.get(denominator_engine)
        numerator = engines.get(numerator_engine)
        if (not denominator or not numerator or not denominator["stable"]
                or not numerator["stable"]):
            continue
        if (not denominator.get("priority_verified", True)
                or not numerator.get("priority_verified", True)):
            continue
        if not denominator["measurements"] or not numerator["measurements"]:
            continue
        if denominator["checksum"] != numerator["checksum"]:
            continue
        denominator_rate = statistics.median(
            item["work_units_per_second"] for item in denominator["measurements"]
        )
        numerator_rate = statistics.median(
            item["work_units_per_second"] for item in numerator["measurements"]
        )
        ratio = numerator_rate / denominator_rate
        group_ratios[workload].append(ratio)
        pair_ratios.setdefault(pair["pair_number"], {})[workload] = ratio
        min_ratio = ratio if min_ratio is None else min(min_ratio, ratio)

    complete_pair_scores = [
        score for ratios in pair_ratios.values()
        if len(ratios) == len(workloads)
        for score in [geometric_mean([ratios[name] for name in workloads])]
        if score is not None
    ]

    groups = {}
    for workload in workloads:
        values = group_ratios[workload]
        groups[workload] = {
            "complete_pairs": len(values),
            "numerator_over_denominator_geometric_mean": geometric_mean(values),
            "bootstrap_95_percent_interval": bootstrap_interval(values),
            "pair_ratios": values,
        }
        if numerator_engine == "jperl" and denominator_engine == "perl":
            groups[workload]["jperl_over_perl_geometric_mean"] = geometric_mean(values)
    return {
        "groups": groups,
        "complete_portfolio_pairs": len(complete_pair_scores),
        "portfolio_numerator_over_denominator_geometric_mean": geometric_mean(complete_pair_scores),
        "portfolio_bootstrap_95_percent_interval": bootstrap_interval(complete_pair_scores),
        "minimum_group_pair_ratio": min_ratio,
        "numerator_engine": numerator_engine,
        "denominator_engine": denominator_engine,
        **({"portfolio_jperl_over_perl_geometric_mean": geometric_mean(complete_pair_scores)}
           if numerator_engine == "jperl" and denominator_engine == "perl" else {}),
    }


def protocol_eligibility(args: argparse.Namespace) -> dict[str, Any]:
    reasons = []
    if getattr(args, "reference_jperl_jar", None):
        reasons.append("PerlOnJava artifact comparison is attribution evidence, not Perl parity")
        if not getattr(args, "jperl_jar", None):
            reasons.append("candidate JAR is not pinned for artifact comparison")
    if set(args.workloads) != {"method_anchor", "closure_anchor", "life"}:
        reasons.append("workloads are not exactly the #1863 acceptance portfolio")
    if args.pairs < 7:
        reasons.append("fewer than seven independent process pairs requested")
    if args.initial_warmup_windows < 10 or args.max_warmup_windows < 60:
        reasons.append("warmup window limits are below the #1863 protocol")
    if args.measurement_windows < 15 or args.window_seconds < 1.0:
        reasons.append("measurement windows are below the #1863 protocol")
    if args.nice != 19:
        reasons.append("requested niceness is not 19")
    if not args.perl_source_revision:
        reasons.append("reference Perl source revision is not pinned")
    return {"acceptance_protocol": not reasons, "reasons": reasons}


def launch_order_schedule(workload: str, pair_count: int, seed: int,
                          first_engine: str = "perl",
                          second_engine: str = "jperl") -> list[tuple[str, str]]:
    """Return a deterministic randomized schedule with balanced first engines."""
    workload_seed = int(hashlib.sha256(workload.encode("utf-8")).hexdigest()[:8], 16)
    randomizer = random.Random(seed + workload_seed)
    first_engine_count = (pair_count + 1) // 2
    schedule = ([(first_engine, second_engine)] * first_engine_count
                + [(second_engine, first_engine)] * (pair_count - first_engine_count))
    randomizer.shuffle(schedule)
    return schedule


def write_result(path: Path, document: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n",
                         encoding="utf-8")
    temporary.replace(path)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True,
                        help="JSON result path; raw warmup and measurement windows are retained")
    parser.add_argument("--perl", default="perl", help="system Perl executable")
    parser.add_argument("--perl-source-revision",
                        help="source revision/build identifier for the reference Perl")
    parser.add_argument("--jperl", default="./jperl", help="PerlOnJava executable")
    parser.add_argument("--jperl-jar", help="pin the PerlOnJava candidate JAR")
    parser.add_argument("--reference-jperl-jar",
                        help="also compare against this PerlOnJava JAR in paired runs")
    parser.add_argument("--timeout-command", default="timeout")
    parser.add_argument("--process-timeout-seconds", type=int, default=900)
    parser.add_argument("--nice", type=int, default=19,
                        help="process niceness (use -1 to avoid the nice wrapper)")
    parser.add_argument("--launch-order-seed", type=int, default=1863,
                        help="seed for balanced randomized engine launch order")
    parser.add_argument("--workloads", nargs="+", choices=WORKLOADS, default=list(WORKLOADS))
    parser.add_argument("--pairs", type=int, default=7)
    parser.add_argument("--initial-warmup-windows", type=int, default=10)
    parser.add_argument("--max-warmup-windows", type=int, default=60)
    parser.add_argument("--measurement-windows", type=int, default=15)
    parser.add_argument("--window-seconds", type=float, default=1.0)
    parser.add_argument("--stability-slope-limit", type=float, default=0.02)
    parser.add_argument("--stability-cv-limit", type=float, default=0.03)
    return parser.parse_args()


ROOT = Path(__file__).resolve().parents[2]
SLOPE_LIMIT = 0.02
CV_LIMIT = 0.03


def main() -> int:
    global SLOPE_LIMIT
    global CV_LIMIT
    args = parse_args()
    args.output = args.output.resolve()
    SLOPE_LIMIT = args.stability_slope_limit
    CV_LIMIT = args.stability_cv_limit
    if args.pairs <= 0 or args.initial_warmup_windows < 5:
        raise SystemExit("pairs must be positive and initial warmup windows must be at least five")
    if args.max_warmup_windows < args.initial_warmup_windows:
        raise SystemExit("max warmup windows must be >= initial warmup windows")
    if args.measurement_windows <= 0 or args.window_seconds <= 0:
        raise SystemExit("measurement windows and window seconds must be positive")
    if args.reference_jperl_jar and not args.jperl_jar:
        raise SystemExit("--reference-jperl-jar requires --jperl-jar")
    for configured_jar in (args.jperl_jar, args.reference_jperl_jar):
        if configured_jar and not Path(configured_jar).is_file():
            raise SystemExit(f"configured PerlOnJava JAR does not exist: {configured_jar}")

    if args.reference_jperl_jar:
        numerator_engine, denominator_engine = "candidate", "reference"
    else:
        numerator_engine, denominator_engine = "jperl", "perl"

    document: dict[str, Any] = {
        "metadata": metadata(args),
        "pairs": [],
        "summary": {},
    }
    write_result(args.output, document)

    for workload in args.workloads:
        launch_schedule = launch_order_schedule(
            workload, args.pairs, args.launch_order_seed,
            numerator_engine, denominator_engine,
        )
        for pair_number in range(args.pairs):
            order = launch_schedule[pair_number]
            pair: dict[str, Any] = {
                "workload": workload,
                "pair_number": pair_number + 1,
                "launch_order": list(order),
                "engines": {},
            }
            for engine in order:
                print(f"[{workload}] pair {pair_number + 1}/{args.pairs}: {engine}",
                      file=sys.stderr, flush=True)
                pair["engines"][engine] = run_engine(engine, workload, args, pair_number + 1)
            denominator_run = pair["engines"][denominator_engine]
            numerator_run = pair["engines"][numerator_engine]
            denominator_checksums = {
                item["checksum"] for item in denominator_run["measurements"]
            }
            numerator_checksums = {
                item["checksum"] for item in numerator_run["measurements"]
            }
            pair["checksum_match"] = bool(
                denominator_run["stable"] and numerator_run["stable"]
                and denominator_checksums and denominator_checksums == numerator_checksums
            )
            pair["measurement_eligible"] = bool(
                pair["checksum_match"]
                and denominator_run.get("priority_verified", True)
                and numerator_run.get("priority_verified", True)
            )
            denominator_rate = statistics.median(
                [item["work_units_per_second"]
                 for item in denominator_run["measurements"]]
            ) if denominator_run["measurements"] else None
            numerator_rate = statistics.median(
                [item["work_units_per_second"]
                 for item in numerator_run["measurements"]]
            ) if numerator_run["measurements"] else None
            pair["numerator_over_denominator"] = (
                numerator_rate / denominator_rate
                if pair["measurement_eligible"] and denominator_rate else None
            )
            if numerator_engine == "jperl" and denominator_engine == "perl":
                pair["jperl_over_perl"] = pair["numerator_over_denominator"]
            for run in (denominator_run, numerator_run):
                run["checksum"] = (run["measurements"][-1]["checksum"]
                                   if run["measurements"] else None)
            document["pairs"].append(pair)
            document["summary"] = summarize(
                document["pairs"], tuple(args.workloads),
                numerator_engine, denominator_engine,
            )
            document["summary"]["evidence_class"] = (
                "acceptance_candidate"
                if document["metadata"]["protocol_eligibility"]["acceptance_protocol"]
                else "diagnostic_only"
            )
            document["metadata"]["host"]["load_average_after_pair"] = host_load()
            write_result(args.output, document)

    document["metadata"]["host"]["load_average_after"] = host_load()
    document["summary"] = summarize(
        document["pairs"], tuple(args.workloads),
        numerator_engine, denominator_engine,
    )
    document["summary"]["evidence_class"] = (
        "acceptance_candidate"
        if document["metadata"]["protocol_eligibility"]["acceptance_protocol"]
        else "diagnostic_only"
    )
    write_result(args.output, document)
    print(args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
