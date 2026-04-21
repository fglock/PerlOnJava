# Benchmark baseline — d071692a3

**Date:** 2026-04-21T18:02:38Z
**Runs per benchmark:** 3
**jperl:** `/Users/fglock/projects/PerlOnJava3/jperl`
**perl:** `perl` (5.042000)

For "time" benches lower = faster; ratio is `jperl / perl`.
For "Mcells/s" (life_bitpacked) higher = faster; ratio is `perl / jperl`.

| Benchmark | unit | jperl | perl | ratio | parity? |
|---|---|---:|---:|---:|:---:|
| `benchmark_anon_simple` | s | 7.184 | 1.425 | **5.04×** | ❌ |
| `benchmark_closure` | s | 9.027 | 8.112 | **1.11×** | ≈ |
| `benchmark_eval_string` | s | 14.897 | 3.323 | **4.48×** | ❌ |
| `benchmark_global` | s | 14.902 | 10.515 | **1.42×** | ❌ |
| `benchmark_lexical` | s | 4.079 | 10.521 | **0.39×** | ✅ |
| `benchmark_method` | s | 2.534 | 1.515 | **1.67×** | ❌ |
| `benchmark_refcount_anon` | s | 1.778 | 0.450 | **3.95×** | ❌ |
| `benchmark_refcount_bless` | s | 1.284 | 0.197 | **6.52×** | ❌ |
| `benchmark_regex` | s | 2.699 | 1.992 | **1.35×** | ❌ |
| `benchmark_string` | s | 4.020 | 6.822 | **0.59×** | ✅ |
| `life_bitpacked` | Mcells/s | 8.427 | 21.527 | **2.55×** | ❌ |
