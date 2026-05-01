# CPAN Compatibility Reports

Automated compatibility tracking for CPAN modules under PerlOnJava.

## Purpose

This directory stores the results of running CPAN module test suites against
PerlOnJava (`jcpan`). The data is used to:

- Track which modules pass, fail, or need to be skipped
- Measure progress as new language features are implemented
- Identify which modules are safe to recommend to users

## Files

| File | Description |
|------|-------------|
| `cpan-compatibility.md` | Human-readable summary report |
| `cpan-compatibility-pass.dat` | Modules whose test suites pass |
| `cpan-compatibility-fail.dat` | Modules whose test suites fail |
| `cpan-compatibility-skip.dat` | Modules skipped (e.g. require XS, fork, threads) |
| `Memoize.md` | Detailed notes for the Memoize module |
| `Scalar-Util.md` | Detailed notes for Scalar::Util |

## Updating the Data

The `.dat` files are updated by the automated CPAN tester
(`dev/tools/cpan_random_tester.pl` or similar). Do not edit them by hand
unless correcting an obvious error — they are append-only logs.

When a module's status changes (e.g. newly passing after a fix), update the
corresponding per-module `.md` note as well.

## See Also

- `dev/modules/` — Design documents for porting specific CPAN modules
- `AGENTS.md` — Rules for running tests and git workflow
