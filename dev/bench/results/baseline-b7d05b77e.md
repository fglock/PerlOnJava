# Benchmark baseline — b7d05b77e

**Date:** 2026-04-21T19:52:51Z
**Runs per benchmark:** 3
**jperl:** `/Users/fglock/projects/PerlOnJava3/jperl`
**perl:** `perl` (5.042000)

For "time" benches lower = faster; ratio is `jperl / perl`.
For "Mcells/s" (life_bitpacked) higher = faster; ratio is `perl / jperl`.

| Benchmark | unit | jperl | perl | ratio | parity? |
|---|---|---:|---:|---:|:---:|
| `benchmark_anon_simple` | s | 7.107 | 1.439 | **4.94×** | ❌ |
| `benchmark_closure` | s | 9.685 | 7.721 | **1.25×** | ❌ |
| `benchmark_eval_string` | s | 14.430 | 3.067 | **4.70×** | ❌ |
| `benchmark_global` | s | 14.482 | 10.134 | **1.43×** | ❌ |
| `benchmark_lexical` | s | 3.924 | 10.249 | **0.38×** | ✅ |
| `benchmark_method` | s | 2.518 | 1.452 | **1.73×** | ❌ |
| `benchmark_refcount_anon` | s | 1.723 | 0.447 | **3.85×** | ❌ |
| `benchmark_refcount_bless` | s | 1.236 | 0.197 | **6.27×** | ❌ |
| `benchmark_regex` | s | 2.670 | 2.025 | **1.32×** | ❌ |
| `benchmark_string` | s | 4.043 | 6.770 | **0.60×** | ✅ |
| `life_bitpacked` | Mcells/s | 8.463 | 21.547 | **2.55×** | ❌ |
