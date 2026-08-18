# Phase 36 Forced-Backend Regex Differential — 2026-08-17

## Scope

This report records the 80-file `perl5_t/t/re` corpus with the temporary Java
and Joni matcher selector on both PerlOnJava execution backends. The corpus
uses the clean pre-PR-1002 binary built from commit `ba85bdc05`; its SHA-256 is:

```text
04be3d3d52348dbda4de1ff187df5cd9d788a274ab14bbb5170bafe879cd12cf
```

The historical comparison is PR 958's captured runner log:

```text
../PerlOnJava/logs/test_20260815_080000_958.log
```

That log contains all 80 regex files and records 50,273/94,771 passing
assertions. Complete logs, runner JSON, and normalized comparison JSON remain
outside Git under `/tmp/phase36-regex-*`.

PR 1002 merged and PR 1005's package-localization fix was prepared after this
binary was frozen. In particular, PR 1005 closes `pat_advanced.t` tests
922–933. Those changes are intentionally not mixed into this same-commit
matrix; a later verification rerun must use one rebuilt current-master binary
for all four legs.

## Method

Each matrix leg inherits one matcher selection and, for interpreter legs,
global interpreter selection. `perl_test_runner.pl` owns a hard timeout and
process group for every `jperl` child. Full output is captured to a log and the
runner writes per-file JSON. The displayed 300-second value is the base limit;
the runner's configured heavy-file overrides raise `pat_psycho*` and other
designated heavy fixtures to 600 seconds.

```text
JPERL_REGEX_BACKEND=java perl dev/tools/perl_test_runner.pl \
  --timeout 300 --jobs 5 --output /tmp/phase36-regex-java-jvm.json perl5_t/t/re

JPERL_REGEX_BACKEND=joni perl dev/tools/perl_test_runner.pl \
  --timeout 300 --jobs 5 --output /tmp/phase36-regex-joni-jvm.json perl5_t/t/re

JPERL_REGEX_BACKEND=java JPERL_INTERPRETER=1 \
  perl dev/tools/perl_test_runner.pl --timeout 300 --jobs 6 \
  --output /tmp/phase36-regex-java-interpreter.json perl5_t/t/re

JPERL_REGEX_BACKEND=joni JPERL_INTERPRETER=1 \
  perl dev/tools/perl_test_runner.pl --timeout 300 --jobs 6 \
  --output /tmp/phase36-regex-joni-interpreter.json perl5_t/t/re
```

The comparison gate now reports execution errors, incomplete files, and
zero-TAP results explicitly. `--expected-files 80 --fail-on-invalid` rejects an
incomplete corpus independently of the existing per-file pass-count regression
gate.

## Matrix Summary

| Matcher | Execution backend | Passing / planned | Delta vs PR 958 | Timeouts | Status |
|---|---|---:|---:|---:|---|
| Java | JVM | 49,923 / 94,823 | -350 / +52 | 0 | complete |
| Joni | JVM | 32,479 / 77,612 | -17,794 / -17,159 | 10 | complete |
| Java | interpreter | 50,021 / 94,823 | -252 / +52 | 0 | complete |
| Joni | interpreter | 32,483 / 77,612 | -17,790 / -17,159 | 10 | complete |

## Complete Java/JVM Comparison

The forced-Java/JVM leg covered all 80 files. It has four per-file pass-count
regressions against PR 958:

| File | PR 958 | Candidate | Delta | Initial classification |
|---|---:|---:|---:|---|
| `pat.t` | 1099/1302 | 239/1302 | -860 | shared source-provenance abort |
| `pat_thr.t` | 1099/1302 | 239/1302 | -860 | same direct failure in thread wrapper |
| `reg_mesg.t` | 1692/2525 | 1664/2521 | -28 | diagnostics and plan change |
| `regexp_normal.t` | 2193/2210 | 2175/2210 | -18 | matcher semantics |

`pat{,_thr}.t` stops immediately after test 239 with:

```text
Eval-group not allowed at runtime, use re 'eval'
```

This is not an optimizer/debug transcript difference or a Java/Joni routing
difference. The forced-Joni leg stops at the same point. The failing literal
uses single-quote regex delimiters, `m'a(?{ ... })b'`; initial reduction shows
that this parse path produces a plain pattern string and loses trusted literal
callback provenance before matcher selection.

The net aggregate loss masks substantial progress since PR 958. In particular,
`pat_re_eval{,_thr}.t` improves from 0/555 to 423/555 each,
`pat_advanced{,_thr}.t` improves from 1376/1687 to 1511/1687 each,
`reg_eval_scope.t` reaches 49/49, and `rxcode.t` reaches 40/42.

## Complete Joni/JVM Comparison

The forced-Joni leg covers all 80 files but falls to 32,479/77,612: 17,794
fewer passing assertions and 17,159 fewer planned assertions than PR 958. It
contains 27 per-file pass-count regressions, 33 execution issues, and 29
zero-TAP files. Ten files reach their runner-owned bounds with zero TAP:

- 300 seconds: `regex_sets_compat.t`, `regexp.t`, `regexp_noamp.t`,
  `regexp_notrie.t`, `regexp_qr.t`, `regexp_qr_embed.t`, and
  `regexp_trielist.t`.
- 600 seconds: `pat_psycho.t`, `pat_psycho_thr.t`, and
  `regexp_qr_embed_thr.t`.

The cluster is matcher-specific: the same files complete in the forced-Java
leg. Reduction must determine whether each stall occurs during Joni compile or
search before any broad implementation change.

Completed Joni/JVM files also expose semantic gaps independent of timeout:

- `pat{,_thr}.t`: 239/1302 each, sharing the matcher-independent literal
  callback provenance abort described above.
- `pat_advanced{,_thr}.t`: 1271/1687 each, versus forced Java 1511/1687.
- `reg_mesg.t`: 1364/2486, versus forced Java 1664/2521.
- `reg_posixcc.t`: 2052/2560, versus forced Java 2560/2560.
- `regexp_normal.t`: 2175/2210 on both matcher selections.
- `regexp_unicode_prop.t`: 1039/1110; its thread wrapper is 1040/1110.

`speed.t` exits incomplete at 1/59 after 451 seconds and `speed_thr.t` exits
incomplete at 1/59 after 301 seconds. These are internal-watchdog/incomplete
results rather than runner timeouts, but still lose 25 passing assertions per
file against PR 958.

## Complete Java JVM/Interpreter Comparison

The forced-Java interpreter leg covers all 80 files at 50,021/94,823. It has
the same zero runner timeouts and planned count as forced-Java/JVM, while
gaining 98 passing assertions in aggregate. That aggregate gain is entirely
driven by `alpha_assertions.t` (2188/2320 on JVM and 2293/2320 on interpreter,
+105); four interpreter-specific losses remain:

| File | JVM | Interpreter | Delta |
|---|---:|---:|---:|
| `reg_eval_scope.t` | 49/49 | 45/49 | -4 |
| `pat_advanced.t` | 1511/1687 | 1510/1687 | -1 |
| `pat_advanced_thr.t` | 1511/1687 | 1510/1687 | -1 |
| `qr.t` | 4/4 | 3/4 | -1 |

Against PR 958, the interpreter leg has five pass-count regressions: the same
four Java/JVM corpus regressions plus `qr.t` at -1. These execution-backend
differences are shared runtime/compiler parity gaps, not Java-vs-Joni matcher
differences.

## Complete Joni JVM/Interpreter Comparison

The forced-Joni interpreter leg covers all 80 files at 32,483/77,612. Its ten
timeouts, 33 execution issues, 29 zero-TAP files, and planned count are
identical to Joni/JVM. It gains four passing assertions in aggregate, again
masking execution-backend differences:

| File | JVM | Interpreter | Delta |
|---|---:|---:|---:|
| `reg_eval_scope.t` | 49/49 | 45/49 | -4 |
| `qr.t` | 4/4 | 3/4 | -1 |
| `alpha_assertions.t` | 2099/2320 | 2108/2320 | +9 |

On the interpreter execution backend, selecting Joni instead of Java changes
50,021/94,823 to 32,483/77,612: 17,538 fewer passing and 17,211 fewer planned
assertions, with 29 regressing and three improving files. The loss categories
match the JVM engine differential, so the primary blocker is matcher selection
rather than execution-backend compilation.

## Classification and Follow-up

- Source policy: fix the matcher-independent single-quote literal callback
  provenance abort after this report PR, with a focused standard-Perl oracle.
- Matcher semantics: separate the shared `regexp_normal.t` loss from
  Joni-only `pat_advanced.t` and POSIX/property losses.
- Diagnostics: treat `reg_mesg.t` independently from matching behavior.
- Timeout behavior: reduce the Joni timeout cluster under individual hard
  timeouts; do not hide it through Java fallback.
- Optimizer/debug transcript: none of the regressions above are currently
  classified as transcript-only exclusions.

The Phase 1 exit criterion is not met. Joni fails to retain every assertion
that passes under Java, introduces ten matcher-specific timeouts on both
execution backends, and exposes independent semantic and diagnostic losses.
The four relevant pairwise comparisons (Java/Joni within each execution
backend and JVM/interpreter within each matcher selection) are complete and
retained under `/tmp/phase36-compare-*`.
