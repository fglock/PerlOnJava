# Benchmark baseline — d3ee4e200

**Date:** 2026-04-21T18:28:10Z
**Runs per benchmark:** 3
**jperl:** `/Users/fglock/projects/PerlOnJava3/jperl`
**perl:** `perl` (5.042000)

For "time" benches lower = faster; ratio is `jperl / perl`.
For "Mcells/s" (life_bitpacked) higher = faster; ratio is `perl / jperl`.

| Benchmark | unit | jperl | perl | ratio | parity? |
|---|---|---:|---:|---:|:---:|
| `benchmark_anon_simple` | s | 7.141 | 1.421 | **5.03×** | ❌ |
| `benchmark_closure` | s | 9.461 | 7.990 | **1.18×** | ≈ |
| `benchmark_eval_string` | s | 14.270 | 3.292 | **4.33×** | ❌ |
| `benchmark_global` | s | 14.834 | 9.961 | **1.49×** | ❌ |
| `benchmark_lexical` | s | 4.018 | 10.507 | **0.38×** | ✅ |
| `benchmark_method` | s | 2.519 | 1.479 | **1.70×** | ❌ |
| `benchmark_refcount_anon` | s | 1.766 | 0.450 | **3.92×** | ❌ |
| `benchmark_refcount_bless` | s | 1.268 | 0.198 | **6.40×** | ❌ |
| `benchmark_regex` | s | 2.686 | 1.978 | **1.36×** | ❌ |
| `benchmark_string` | s | 4.010 | 6.837 | **0.59×** | ✅ |
| `life_bitpacked` | Mcells/s | 8.240 | 21.287 | **2.58×** | ❌ |
