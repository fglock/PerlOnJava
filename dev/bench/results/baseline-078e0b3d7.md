# Benchmark baseline — 078e0b3d7

**Date:** 2026-04-21T21:17:48Z
**Runs per benchmark:** 3
**jperl:** `/Users/fglock/projects/PerlOnJava3/jperl`
**perl:** `perl` (5.042000)

For "time" benches lower = faster; ratio is `jperl / perl`.
For "Mcells/s" (life_bitpacked) higher = faster; ratio is `perl / jperl`.

| Benchmark | unit | jperl | perl | ratio | parity? |
|---|---|---:|---:|---:|:---:|
| `benchmark_anon_simple` | s | 7.127 | 1.439 | **4.95×** | ❌ |
| `benchmark_closure` | s | 9.445 | 7.982 | **1.18×** | ≈ |
| `benchmark_eval_string` | s | 14.636 | 3.192 | **4.59×** | ❌ |
| `benchmark_global` | s | 14.636 | 10.485 | **1.40×** | ❌ |
| `benchmark_lexical` | s | 4.019 | 10.537 | **0.38×** | ✅ |
| `benchmark_method` | s | 2.588 | 1.486 | **1.74×** | ❌ |
| `benchmark_refcount_anon` | s | 1.792 | 0.448 | **4.00×** | ❌ |
| `benchmark_refcount_bless` | s | 1.303 | 0.197 | **6.61×** | ❌ |
| `benchmark_regex` | s | 2.717 | 1.995 | **1.36×** | ❌ |
| `benchmark_string` | s | 4.074 | 6.910 | **0.59×** | ✅ |
| `life_bitpacked` | Mcells/s | 8.203 | 20.757 | **2.53×** | ❌ |
