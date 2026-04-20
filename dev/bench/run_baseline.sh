#!/usr/bin/env bash
# run_baseline.sh
# Captures timings for every benchmark_*.pl under dev/bench and writes a
# JSON-ish summary to dev/bench/results/<sha>.json.
#
# Usage:
#   dev/bench/run_baseline.sh              # runs against jperl
#   BENCH_RUNS=5 dev/bench/run_baseline.sh # repeat each bench 5 times
#   COMPARE=perl dev/bench/run_baseline.sh # also run each with system perl
#
# The output is intentionally hand-written JSON (no deps) so it's stable in diffs.

set -u

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

JPERL="${JPERL:-$REPO_ROOT/jperl}"
SHA="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
BENCH_RUNS="${BENCH_RUNS:-3}"
OUT_DIR="$REPO_ROOT/dev/bench/results"
mkdir -p "$OUT_DIR"
OUT_FILE="$OUT_DIR/baseline-$SHA.json"

if [ ! -x "$JPERL" ]; then
    echo "ERROR: $JPERL not found or not executable — run 'make dev' first" >&2
    exit 1
fi

echo "Writing results to: $OUT_FILE"
echo "Runs per benchmark: $BENCH_RUNS"
echo

{
    echo "{"
    echo "  \"git_sha\": \"$SHA\","
    echo "  \"date\":    \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
    echo "  \"runs\":    $BENCH_RUNS,"
    echo "  \"jperl\":   \"$JPERL\","
    echo "  \"benchmarks\": {"

    first=1
    for bench in dev/bench/benchmark_*.pl; do
        name="$(basename "$bench" .pl)"
        # Skip memory benches from baseline loop (they are slow + already
        # write their own output files).
        case "$name" in
            benchmark_memory|benchmark_memory_delta) continue ;;
        esac

        echo "  -> $name" >&2
        times=()
        for i in $(seq 1 "$BENCH_RUNS"); do
            # Use Bash's builtin time for wallclock, captured via redirection.
            # Some benches print to stdout; discard it.
            t=$({ TIMEFORMAT='%R'; time "$JPERL" "$bench" >/dev/null 2>&1; } 2>&1)
            times+=("$t")
        done

        [ $first -eq 0 ] && echo ","
        first=0
        printf '    "%s": [%s]' "$name" "$(IFS=,; echo "${times[*]}")"
    done
    echo
    echo "  }"
    echo "}"
} > "$OUT_FILE"

echo
echo "Done. Summary:"
cat "$OUT_FILE"
