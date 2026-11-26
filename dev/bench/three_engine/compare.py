#!/usr/bin/env python3
"""Compare PerlOnJava, Perl, and JRuby on matched microbenchmarks."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import itertools
import json
import math
import os
import platform
import random
import shutil
import statistics
import subprocess
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
WORKER_PERL = Path(__file__).with_name("worker.pl")
WORKER_RUBY = Path(__file__).with_name("worker.rb")
WORKLOADS = ("numeric", "closure", "method", "string", "regex", "eval-string")
ENGINES = ("perlonjava", "perl", "jruby")
WORKLOAD_DESCRIPTIONS = {
    "numeric": "Increment a local integer and add each new value to a sink.",
    "closure": "Call a closure capturing 17; add its constant result, 18, to a sink.",
    "method": "Call a method that increments two object fields and returns their sum.",
    "string": "Uppercase five characters, reverse the next four, concatenate, and count length.",
    "regex": "Match four digits in a fixed ASCII string and add the capture to a sink.",
    "eval-string": "Compile and evaluate a distinct arithmetic source string on every operation.",
}


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def run_text(command: list[str], env: dict[str, str]) -> str:
    try:
        result = subprocess.run(
            command,
            cwd=ROOT,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=20,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return f"unavailable: {exc}"
    output = result.stdout.strip()
    return output if result.returncode == 0 else f"exit {result.returncode}: {output}"


def checksum(workload: str, operations: int) -> int:
    if workload == "numeric":
        return operations * (operations + 1) // 2
    if workload == "closure":
        return 18 * operations
    if workload == "method":
        return operations * (operations + 1)
    if workload == "string":
        return 9 * operations
    if workload == "regex":
        return 1234 * operations
    if workload == "eval-string":
        return operations
    raise ValueError(workload)


def parse_response(process: subprocess.Popen[str], workload: str) -> dict[str, Any]:
    assert process.stdout is not None
    line = process.stdout.readline()
    if not line:
        stderr = process.stderr.read() if process.stderr else ""
        raise RuntimeError(f"{workload} worker exited without a result: {stderr[-2000:]}")
    result = json.loads(line)
    operations = int(result["operations"])
    actual = int(result["checksum"])
    expected = checksum(workload, operations)
    if operations <= 0 or actual != expected:
        raise RuntimeError(
            f"{workload} checksum mismatch: operations={operations}, "
            f"expected={expected}, got={actual}"
        )
    elapsed = float(result["elapsed"])
    if elapsed <= 0:
        raise RuntimeError(f"{workload} worker returned invalid elapsed time: {elapsed}")
    return {
        "operations": operations,
        "checksum": actual,
        "elapsed_seconds": elapsed,
        "operations_per_second": operations / elapsed,
    }


def command_for(engine: str, workload: str, args: argparse.Namespace) -> list[str]:
    if engine == "perlonjava":
        executable, worker = args.jperl, str(WORKER_PERL)
    elif engine == "perl":
        executable, worker = args.perl, str(WORKER_PERL)
    else:
        executable, worker = args.jruby, str(WORKER_RUBY)
    return [args.timeout, str(args.process_timeout), executable, worker, workload]


def run_engine(
    engine: str,
    workload: str,
    order: list[str],
    args: argparse.Namespace,
    env: dict[str, str],
) -> dict[str, Any]:
    process = subprocess.Popen(
        command_for(engine, workload, args),
        cwd=ROOT,
        env=env,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
    )
    assert process.stdin is not None

    def window() -> dict[str, Any]:
        process.stdin.write(json.dumps({"seconds": args.window_seconds}) + "\n")
        process.stdin.flush()
        return parse_response(process, workload)

    warmup = []
    measurements = []
    try:
        for _ in range(args.warmup_windows):
            warmup.append(window())
        for _ in range(args.measurement_windows):
            measurements.append(window())
    finally:
        process.stdin.close()
        try:
            return_code = process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            return_code = process.wait()
        stderr = process.stderr.read() if process.stderr else ""

    if return_code != 0:
        raise RuntimeError(
            f"{engine}/{workload} exited {return_code}; stderr: {stderr[-2000:]}"
        )
    if len(measurements) != args.measurement_windows:
        raise RuntimeError(f"{engine}/{workload} did not complete all measurement windows")
    return {
        "launch_order": order,
        "command": command_for(engine, workload, args),
        "warmup": warmup,
        "measurements": measurements,
        "median_operations_per_second": statistics.median(
            item["operations_per_second"] for item in measurements
        ),
        "stderr": stderr.strip(),
    }


def paired_geomean_ci(values: list[float], seed: int) -> list[float]:
    rng = random.Random(seed)
    estimates = []
    for _ in range(50_000):
        sample = [values[rng.randrange(len(values))] for _ in values]
        estimates.append(math.exp(statistics.mean(math.log(value) for value in sample)))
    estimates.sort()
    return [estimates[int(0.025 * len(estimates))], estimates[int(0.975 * len(estimates))]]


def summarize_pairs(pairs: list[dict[str, Any]], seed: int) -> dict[str, Any]:
    comparisons = {
        "perlonjava_over_perl": ("perlonjava", "perl"),
        "jruby_over_perl": ("jruby", "perl"),
        "perlonjava_over_jruby": ("perlonjava", "jruby"),
    }
    summary: dict[str, Any] = {
        "mean_operations_per_second": {
            engine: statistics.mean(
                pair["engines"][engine]["median_operations_per_second"] for pair in pairs
            )
            for engine in ENGINES
        },
        "comparisons": {},
    }
    for offset, (name, (numerator, denominator)) in enumerate(comparisons.items()):
        ratios = [
            pair["engines"][numerator]["median_operations_per_second"]
            / pair["engines"][denominator]["median_operations_per_second"]
            for pair in pairs
        ]
        summary["comparisons"][name] = {
            "paired_speedups": ratios,
            "geometric_mean": math.exp(statistics.mean(math.log(value) for value in ratios)),
            "bootstrap_95_percent_interval": paired_geomean_ci(ratios, seed + offset),
        }
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True,
                        help="raw JSON output; keep it outside the repository")
    parser.add_argument("--java-home", type=Path, required=True,
                        help="JDK home used by both PerlOnJava and JRuby")
    parser.add_argument("--perl", default="perl")
    parser.add_argument("--jperl", default=str(ROOT / "jperl"))
    parser.add_argument("--jruby", default="jruby")
    parser.add_argument("--timeout", default=shutil.which("timeout") or "timeout")
    parser.add_argument("--process-timeout", type=int, default=120)
    parser.add_argument("--pairs", type=int, default=7)
    parser.add_argument("--warmup-windows", type=int, default=5)
    parser.add_argument("--measurement-windows", type=int, default=7)
    parser.add_argument("--window-seconds", type=float, default=1.0)
    parser.add_argument("--seed", type=int, default=278341)
    args = parser.parse_args()

    args.output = args.output.resolve()
    args.jperl = str(Path(args.jperl).resolve())
    args.java_home = args.java_home.resolve()
    try:
        args.output.relative_to(ROOT)
    except ValueError:
        pass
    else:
        parser.error("--output must be outside the repository so raw samples stay untracked")
    java = args.java_home / "bin" / "java"
    if not java.is_file() or not os.access(java, os.X_OK):
        parser.error(f"--java-home has no executable bin/java: {args.java_home}")
    if min(args.pairs, args.warmup_windows, args.measurement_windows) <= 0:
        parser.error("pairs and window counts must be positive")
    if args.window_seconds <= 0 or args.process_timeout <= 0:
        parser.error("window duration and process timeout must be positive")
    for executable in (args.perl, args.jperl, args.jruby, args.timeout):
        if not (Path(executable).is_file() or shutil.which(executable)):
            parser.error(f"executable not found: {executable}")
    jars = sorted((ROOT / "target").glob("perlonjava-*.jar"),
                  key=lambda path: path.stat().st_mtime, reverse=True)
    if not jars:
        parser.error("no built PerlOnJava JAR found under target/")
    perlonjava_jar = jars[0]

    env = os.environ.copy()
    env["JAVA_HOME"] = str(args.java_home)
    rng = random.Random(args.seed)
    schedules = list(itertools.permutations(ENGINES))
    report: dict[str, Any] = {
        "created_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "source_commit": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
        ).strip(),
        "protocol": {
            "engines": list(ENGINES),
            "workloads": list(WORKLOADS),
            "workload_descriptions": WORKLOAD_DESCRIPTIONS,
            "pairs": args.pairs,
            "warmup_windows_per_engine_per_pair": args.warmup_windows,
            "measurement_windows_per_engine_per_pair": args.measurement_windows,
            "window_seconds": args.window_seconds,
            "execution": "Engines run sequentially within each randomized fresh-process triplet.",
            "pairwise_interval": "50,000-resample paired bootstrap percentile 95% interval over fresh-process pairs.",
            "random_seed": args.seed,
        },
        "host": {
            "platform": platform.platform(),
            "system": platform.uname()._asdict(),
            "processor": platform.processor(),
            "logical_cpu_count": os.cpu_count(),
            "load_average_before": list(os.getloadavg()),
        },
        "runtime": {
            "perl_version": run_text([args.perl, "-e", "print $^V"], env),
            "perl_configuration": run_text([args.perl, "-V"], env),
            "perlonjava_version": run_text([args.jperl, "-e", "print $^V"], env),
            "jruby_version": run_text([args.jruby, "-v"], env),
            "java_version": run_text([str(java), "-version"], env),
            "java_home": str(args.java_home),
            "perlonjava_jar": str(perlonjava_jar),
            "perlonjava_jar_sha256": digest(perlonjava_jar),
            "worker_perl_sha256": digest(WORKER_PERL),
            "worker_ruby_sha256": digest(WORKER_RUBY),
            "runner_sha256": digest(Path(__file__).resolve()),
        },
        "configuration": {
            "perl": args.perl,
            "jperl": args.jperl,
            "jruby": args.jruby,
            "timeout": args.timeout,
            "process_timeout_seconds": args.process_timeout,
            "output_path": str(args.output),
        },
        "workloads": {},
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)

    for workload_index, workload in enumerate(WORKLOADS):
        workload_pairs = []
        orders = schedules.copy()
        rng.shuffle(orders)
        for pair_index in range(args.pairs):
            order = list(orders[pair_index % len(orders)])
            print(
                f"[{workload}] pair {pair_index + 1}/{args.pairs}: "
                + " -> ".join(order),
                file=sys.stderr,
                flush=True,
            )
            engines = {}
            for engine in order:
                engines[engine] = run_engine(engine, workload, order, args, env)
            workload_pairs.append({"pair": pair_index + 1, "engines": engines})
        report["workloads"][workload] = {
            "pairs": workload_pairs,
            "summary": summarize_pairs(workload_pairs, args.seed + workload_index * 10),
        }
        temporary = args.output.with_suffix(args.output.suffix + ".tmp")
        temporary.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
        temporary.replace(args.output)
        ratios = report["workloads"][workload]["summary"]["comparisons"]
        print(
            f"[{workload}] geometric mean: "
            f"PerlOnJava/Perl={ratios['perlonjava_over_perl']['geometric_mean']:.3f}x, "
            f"JRuby/Perl={ratios['jruby_over_perl']['geometric_mean']:.3f}x, "
            f"PerlOnJava/JRuby={ratios['perlonjava_over_jruby']['geometric_mean']:.3f}x",
            file=sys.stderr,
            flush=True,
        )

    report["host"]["load_average_after"] = list(os.getloadavg())
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    temporary.replace(args.output)
    print(f"Raw benchmark data written to {args.output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
