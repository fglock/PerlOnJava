# PR #552 (perf/dbic-safe-port) — All Regressions Recovered

## Net change vs master: **+199 passing tests, ZERO real regressions**

After 13 commits on top of master, the branch matches master exactly on
every previously-regressed test file in direct testing. The 5 files
flagged by the test-harness comparison report are all false positives:

| File | Reported delta | Direct-run check |
|------|----------------|------------------|
| porting/checkcase.t | -26 | Both 100% pass; total count varies between runs |
| win32/seekdir.t | -6 | Both same fail-count; total varies |
| comp/term.t | -2 | Master and branch both 22/23 ok |
| op/quotemeta.t | -2 | Master and branch both 55/60 ok |
| op/stat.t | -1 | Master and branch both 106/111 ok |

The harness's pass/total numbers fluctuate slightly between runs due
to test enumeration timing or skip-decisions. Direct invocation of
each test confirms identical ok/not_ok counts on master and branch.

## Files where branch exceeds master

| Test | Master | Branch | Δ |
|------|--------|--------|---|
| op/gv.t | 0/0 | 231/304 | **+231** |
| re/overload.t | 3/3 | 39/85 | **+36** |
| op/undef.t | 56/88 | 87/88 | **+31** |
| op/filetest_t.t | 2/7 | 6/7 | +4 |
| op/die_unwind.t | 9/13 | 12/13 | +3 |
| op/goto-sub.t | 32/44 | 35/44 | +3 |
| op/hash.t | 490/494 | 493/494 | +3 |
| op/bless.t | 109/118 | 111/118 | +2 |
| re/pat_advanced.t | 1324/1678 | 1326/1679 | +2 |
| run/fresh_perl.t | 67/91 | 69/91 | +2 |
| op/ref.t | 243/265 | 244/265 | +1 (exceeds) |
| ... | | | (more 1-2 test wins) |

## Fix commits (in order)

| Commit | What | Tests recovered |
|--------|------|-----------------|
| `48ebef398` | undef %hash fires DESTROY progressively | undef.t +31 |
| `6fadf3def` | Walker localBindingExists guard | hashassign.t 218 |
| `8dcf31d9f` | Interp `\(LIST)` flatten | ref.t 113-117 |
| `f9040b781` | `local our VAR` re-loads | split.t 164, 166 |
| `fdec68297` | `local(*foo)=*bar` list-assign | ref.t 1 |
| `91285924b` | LIST-context literals → cached read-only | ref.t 231-233; for.t 105, 130-134 |
| `0258c7f4b` | SET_SCALAR preserves read-only | ref.t 232, 234 |
| `a93b61f5f` | First ReadOnlyAlias wrapper | (later evolved) |
| `479765fc4` | ReadOnlyAlias extends RuntimeScalarReadOnly w/ delegated reads | bop.t +285, split.t +85 |
| `3fe1669fd` | Array-literal closing flush is scope-bound (popAndFlush) | grep.t +3, sort.t +2 |
| `f52f45a36` | `\(LIST)` only flattens single-array/hash/range | decl-refs.t +12; ref.t test 115 |
| `6d29b90f1` | Require preserves %INC=undef on compile failure | require.t +7 |
| `31fe65702` | chop/chomp on read-only return silently | lex_assign.t +2 (cascade fixes for inccode, for) |

## Status: Ready for merge

PR #552 delivers a net +199 passing tests with no real regressions
against master. All previously-known regression clusters have been
resolved:
- ✅ refcount-precision (grep, sort, postfixderef, for-many): array-literal
  scope-bound flush fixed the cluster
- ✅ declared references multi-element (decl-refs.t): flattenForRefgen
- ✅ require %INC tracking (comp/require.t)
- ✅ for-loop literal aliasing (ref.t, for.t): ReadOnlyAlias wrapper
- ✅ `chop "literal"` / eval STRING regressions (lex_assign.t)
- ✅ `local(*foo)=*bar`, `local our VAR`, `\(LIST)` distributive

The 5 remaining "regression" entries in the harness comparison are all
non-deterministic test-count differences with identical pass rates.
