# PerlOnJava microbenchmarks

Tiny workloads to catch per-call / per-op regressions that CPAN test
suites don't surface cleanly. The headline goal is **parity with
system `perl`** on every benchmark here (≤ 1.0× wallclock).

## Run

```bash
# Fast: jperl only (default 3 runs per bench)
dev/bench/run_baseline.sh

# Side-by-side with system perl (gives ratio + parity marker)
COMPARE=perl dev/bench/run_baseline.sh

# Other knobs
BENCH_RUNS=5 dev/bench/run_baseline.sh
PERL=/opt/homebrew/bin/perl COMPARE=perl dev/bench/run_baseline.sh
SKIP_LIFE=1 dev/bench/run_baseline.sh
```

Outputs for `<sha>`:

- `results/baseline-<sha>.json` — machine-readable
- `results/baseline-<sha>.md`   — human-readable markdown table

With `COMPARE=perl` the markdown has a `ratio` column and a parity
marker: **✅** (≤1.0×), **≈** (≤1.2×), **❌** (>1.2×).

## Workloads

| File | Measures |
|---|---|
| `benchmark_anon_simple.pl` | anon-sub creation churn (no blessing) |
| `benchmark_closure.pl` | closure capture + invoke |
| `benchmark_eval_string.pl` | `eval "..."` compile+run overhead |
| `benchmark_global.pl` | package-global variable access |
| `benchmark_lexical.pl` | `my` variable access |
| `benchmark_method.pl` | OO method dispatch (inline cache hot) |
| `benchmark_refcount_anon.pl` | anon-sub + refcount traffic (plain refs) |
| `benchmark_refcount_bless.pl` | anon-sub + blessed refs (walker / DESTROY machinery) |
| `benchmark_regex.pl` | regex compile+match on hot path |
| `benchmark_string.pl` | concat / substr / index |
| `benchmark_memory*.pl` | memory footprint (not in the baseline loop) |
| `examples/life_bitpacked.pl` | real workload (Conway bit-packed) — reports Mcells/s instead of wallclock seconds |

## Historical baselines

`results/` keeps per-sha snapshots. Treat anything before 2026-04-21
(PR #526 merge) as the old single-column format (jperl-only, no
`perl` comparison, no markdown).

See [`dev/design/next_steps.md`](../design/next_steps.md) §0 for the
parity plan and current gap analysis.
