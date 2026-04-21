#!/usr/bin/env bash
# run_baseline.sh
# Capture wallclock timings for every benchmark_*.pl under dev/bench and
# (optionally) the life_bitpacked example, for both jperl and — if
# COMPARE=perl is set — system perl. Results are written to:
#   dev/bench/results/baseline-<sha>.json   (machine-readable)
#   dev/bench/results/baseline-<sha>.md     (human-readable table)
#
# Usage:
#   dev/bench/run_baseline.sh                  # jperl only
#   COMPARE=perl  dev/bench/run_baseline.sh    # side-by-side with system perl
#   BENCH_RUNS=5  dev/bench/run_baseline.sh    # 5 runs per bench (default 3)
#   PERL=/path/to/perl COMPARE=perl dev/bench/run_baseline.sh   # specific perl
#   SKIP_LIFE=1   dev/bench/run_baseline.sh    # skip examples/life_bitpacked
#
# The JSON output is hand-written so it's stable in diffs (no jq dep).
# Written for bash 3.2+ (macOS default) — no associative arrays.

set -u

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

JPERL="${JPERL:-$REPO_ROOT/jperl}"
PERL="${PERL:-perl}"
SHA="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
BENCH_RUNS="${BENCH_RUNS:-3}"
COMPARE="${COMPARE:-}"
SKIP_LIFE="${SKIP_LIFE:-}"

OUT_DIR="$REPO_ROOT/dev/bench/results"
mkdir -p "$OUT_DIR"
OUT_JSON="$OUT_DIR/baseline-$SHA.json"
OUT_MD="$OUT_DIR/baseline-$SHA.md"

if [ ! -x "$JPERL" ]; then
    echo "ERROR: $JPERL not found or not executable — run 'make dev' first" >&2
    exit 1
fi

PERL_VERSION=""
if [ -n "$COMPARE" ]; then
    if ! command -v "$PERL" >/dev/null 2>&1; then
        echo "ERROR: COMPARE=$COMPARE was requested but '$PERL' is not on PATH" >&2
        exit 1
    fi
    PERL_VERSION="$("$PERL" -e 'print $]' 2>/dev/null || echo unknown)"
fi

echo "Writing JSON to: $OUT_JSON"
echo "Writing MD   to: $OUT_MD"
echo "Runs per benchmark: $BENCH_RUNS"
[ -n "$COMPARE" ] && echo "Comparing against: $PERL (version $PERL_VERSION)"
echo

# -- Benchmark runners ---------------------------------------------------
# run_times <interpreter> <bench-script>
# echoes BENCH_RUNS wallclock seconds, comma-separated.
run_times() {
    local bin="$1" bench="$2"
    local times=() t i
    for i in $(seq 1 "$BENCH_RUNS"); do
        t=$({ TIMEFORMAT='%R'; time "$bin" "$bench" >/dev/null 2>&1; } 2>&1)
        times+=("$t")
    done
    (IFS=,; echo "${times[*]}")
}

# life_mcells <interpreter>
# Runs life_bitpacked.pl with fixed args and extracts Mcells/s values.
life_mcells() {
    local bin="$1"
    local values=() v i
    for i in $(seq 1 "$BENCH_RUNS"); do
        v=$("$bin" examples/life_bitpacked.pl -r none -g 500 2>/dev/null \
            | grep -oE 'Cell updates per second: [0-9.]+ Mcells/s' \
            | grep -oE '[0-9.]+' | head -1)
        [ -z "$v" ] && v="0"
        values+=("$v")
    done
    (IFS=,; echo "${values[*]}")
}

# -- Parallel indexed arrays (bash 3.2 compatible) -----------------------
BENCH_NAMES=()
BENCH_UNITS=()
BENCH_JPERL=()
BENCH_PERL=()

# push_result <name> <unit> <jperl_csv> <perl_csv_or_empty>
push_result() {
    BENCH_NAMES+=("$1")
    BENCH_UNITS+=("$2")
    BENCH_JPERL+=("$3")
    BENCH_PERL+=("$4")
}

for bench in dev/bench/benchmark_*.pl; do
    name="$(basename "$bench" .pl)"
    case "$name" in
        benchmark_memory|benchmark_memory_delta) continue ;;
    esac

    echo "  [jperl] $name" >&2
    jtimes="$(run_times "$JPERL" "$bench")"

    ptimes=""
    if [ -n "$COMPARE" ]; then
        echo "  [perl]  $name" >&2
        ptimes="$(run_times "$PERL" "$bench")"
    fi

    push_result "$name" "s" "$jtimes" "$ptimes"
done

if [ -z "$SKIP_LIFE" ] && [ -f "examples/life_bitpacked.pl" ]; then
    name="life_bitpacked"
    echo "  [jperl] $name" >&2
    jvals="$(life_mcells "$JPERL")"
    pvals=""
    if [ -n "$COMPARE" ]; then
        echo "  [perl]  $name" >&2
        pvals="$(life_mcells "$PERL")"
    fi
    push_result "$name" "Mcells/s" "$jvals" "$pvals"
fi

# -- Helpers --------------------------------------------------------------
# avg_csv "1.0,2.0,3.0" -> "2.000"
avg_csv() {
    awk -v s="$1" 'BEGIN{
        n = split(s, a, ","); sum = 0;
        for (i = 1; i <= n; i++) sum += a[i];
        if (n == 0) { print "0.000"; exit }
        printf "%.3f", sum / n;
    }'
}

# ratio "a" "b" [higher_better] -> printed ratio
ratio() {
    awk -v a="$1" -v b="$2" -v h="${3:-0}" 'BEGIN{
        if (a == 0 || b == 0) { print "inf"; exit }
        r = (h == "1") ? b / a : a / b;
        printf "%.2f", r;
    }'
}

# -- Emit JSON ------------------------------------------------------------
{
    echo "{"
    echo "  \"git_sha\":      \"$SHA\","
    echo "  \"date\":         \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
    echo "  \"runs\":         $BENCH_RUNS,"
    echo "  \"jperl\":        \"$JPERL\","
    if [ -n "$COMPARE" ]; then
        echo "  \"perl\":         \"$PERL\","
        echo "  \"perl_version\": \"$PERL_VERSION\","
    fi
    echo "  \"benchmarks\":   {"

    n=${#BENCH_NAMES[@]}
    for i in $(seq 0 $((n - 1))); do
        [ $i -gt 0 ] && echo ","
        name="${BENCH_NAMES[$i]}"
        unit="${BENCH_UNITS[$i]}"
        jv="${BENCH_JPERL[$i]}"
        pv="${BENCH_PERL[$i]}"
        if [ -n "$COMPARE" ]; then
            printf '    "%s": { "unit": "%s", "jperl": [%s], "perl": [%s] }' \
                "$name" "$unit" "$jv" "$pv"
        else
            printf '    "%s": { "unit": "%s", "jperl": [%s] }' "$name" "$unit" "$jv"
        fi
    done
    echo
    echo "  }"
    echo "}"
} > "$OUT_JSON"

# -- Emit Markdown --------------------------------------------------------
{
    echo "# Benchmark baseline — $SHA"
    echo
    echo "**Date:** $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "**Runs per benchmark:** $BENCH_RUNS"
    echo "**jperl:** \`$JPERL\`"
    if [ -n "$COMPARE" ]; then
        echo "**perl:** \`$PERL\` ($PERL_VERSION)"
        echo
        echo "For \"time\" benches lower = faster; ratio is \`jperl / perl\`."
        echo "For \"Mcells/s\" (life_bitpacked) higher = faster; ratio is \`perl / jperl\`."
        echo
        echo "| Benchmark | unit | jperl | perl | ratio | parity? |"
        echo "|---|---|---:|---:|---:|:---:|"
    else
        echo
        echo "| Benchmark | unit | jperl |"
        echo "|---|---|---:|"
    fi

    n=${#BENCH_NAMES[@]}
    for i in $(seq 0 $((n - 1))); do
        name="${BENCH_NAMES[$i]}"
        unit="${BENCH_UNITS[$i]}"
        jperl_avg="$(avg_csv "${BENCH_JPERL[$i]}")"

        if [ -n "$COMPARE" ]; then
            perl_avg="$(avg_csv "${BENCH_PERL[$i]}")"
            higher_is_better=0
            [ "$unit" = "Mcells/s" ] && higher_is_better=1

            r="$(ratio "$jperl_avg" "$perl_avg" "$higher_is_better")"

            # Parity marker:
            #   ✅  ratio ≤ 1.00× (at or faster than perl)
            #   ≈   ratio ≤ 1.20× (within 20%)
            #   ❌  ratio > 1.20× (slower)
            marker="❌"
            if awk -v r="$r" 'BEGIN{ exit !(r <= 1.00) }'; then
                marker="✅"
            elif awk -v r="$r" 'BEGIN{ exit !(r <= 1.20) }'; then
                marker="≈"
            fi

            printf "| \`%s\` | %s | %s | %s | **%s×** | %s |\n" \
                "$name" "$unit" "$jperl_avg" "$perl_avg" "$r" "$marker"
        else
            printf "| \`%s\` | %s | %s |\n" "$name" "$unit" "$jperl_avg"
        fi
    done
} > "$OUT_MD"

echo
echo "Done. Markdown summary:"
echo
cat "$OUT_MD"
