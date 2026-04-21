# Benchmark baseline — 1400475d3

**Date:** 2026-04-21T18:41:58Z
**Runs per benchmark:** 3
**jperl:** `/Users/fglock/projects/PerlOnJava3/jperl`
**perl:** `perl` (5.042000)

For "time" benches lower = faster; ratio is `jperl / perl`.
For "Mcells/s" (life_bitpacked) higher = faster; ratio is `perl / jperl`.

| Benchmark | unit | jperl | perl | ratio | parity? |
|---|---|---:|---:|---:|:---:|
| `benchmark_anon_simple` | s | 7.466 | 1.456 | **5.13×** | ❌ |
| `benchmark_closure` | s | 9.395 | 8.078 | **1.16×** | ≈ |
| `benchmark_eval_string` | s | 14.561 | 3.347 | **4.35×** | ❌ |
| `benchmark_global` | s | 14.843 | 11.152 | **1.33×** | ❌ |
| `benchmark_lexical` | s | 4.065 | 10.750 | **0.38×** | ✅ |
| `benchmark_method` | s | 2.611 | 1.521 | **1.72×** | ❌ |
| `benchmark_refcount_anon` | s | 1.832 | 0.450 | **4.07×** | ❌ |
| `benchmark_refcount_bless` | s | 1.327 | 0.200 | **6.63×** | ❌ |
| `benchmark_regex` | s | 2.757 | 2.023 | **1.36×** | ❌ |
| `benchmark_string` | s | 4.083 | 6.945 | **0.59×** | ✅ |
| `life_bitpacked` | Mcells/s | 8.067 | 20.983 | **2.60×** | ❌ |
