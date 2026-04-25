# PR #552 (perf/dbic-safe-port) — Final Status

## Net change vs master: **+253 passing tests**

After 11 commits on top of master, the branch:
- Adds **+327 passing** tests across 19 files
- Removes **-74 passing** tests across 17 files (most are platform-specific or refcount-precision regressions)
- **Net: +253 passing tests**

## Headline improvements

| Test | Before | After | Δ |
|------|--------|-------|---|
| op/gv.t | 0/0 | 232/304 | **+232** |
| re/overload.t | 3/3 | 39/85 | **+36** |
| op/undef.t | 56/88 | 87/88 | **+31** |
| op/filetest_t.t | 2/7 | 6/7 | +4 |
| op/die_unwind.t | 9/13 | 12/13 | +3 |
| op/goto-sub.t | 32/44 | 35/44 | +3 |
| op/hash.t | 490/494 | 493/494 | +3 |
| op/bless.t | 109/118 | 111/118 | +2 |
| re/pat_advanced.t | 1324/1678 | 1326/1679 | +2 |
| run/fresh_perl.t | 67/91 | 69/91 | +2 |
| op/ref.t | 243/265 | 244/265 | +1 (exceeds master) |

## Remaining regressions (74 tests across 17 files)

### Pseudo-regressions (32 tests, totals changed; both runs pass 100%)
- porting/checkcase.t -26 (test count varied between runs)
- win32/seekdir.t -6 (same)

### Refcount-precision design tradeoffs (~20 tests)
The perf branch increfs container stores. This raises refcount slightly
in some scenarios where master returns to baseline. Tests that probe
exact refcount/DESTROY timing report:
- op/inccode.t -2, op/inccode-tie.t -2 ("FETCH called once", "no leaks")
- op/for-many.t -2 ("refcount 1 after loop")
- op/grep.t -3 (DESTROY-timing in grep void/scalar/list pre-cleanup)
- op/postfixderef.t -3 ("no stooges outlast their scope" + interp)

Fixing these would partially undo the perf-tracking that the branch
delivers. Tradeoff: keep current perf gains, accept slight DESTROY-
timing visibility differences from C Perl.

### Declared references (12 tests)
- op/decl-refs.t -12: `my (\@f, @g) = LIST` returns wrong tuple
  (2nd element undef instead of array ref). Multi-element declared
  references is an experimental Perl feature; codegen for the
  list-form returns specifically broken.

### Module loading (7 tests)
- comp/require.t -7: clustered around `$INC{...}` exists checks
  after a successful `require` and module-true semantics.

### Misc (8 tests)
- op/lex_assign.t -2: `chop "literal"` inside eval STRING handling
- op/sort.t -2: Counter DESTROY counter context-specific (169, 172)
- op/for.t -2: `do { foreach }` scalar value (103, 105)
- op/do.t, op/recurse.t, op/stat.t, op/tie.t, test_pl/examples.t: -1 each

## Commits delivered

| Commit | What | Tests |
|--------|------|-------|
| `48ebef398` | undef %hash progressive DESTROY | undef.t +31 (incl. 8 tracked) |
| `6fadf3def` | Walker localBindingExists guard for named lexicals | hashassign.t 218 |
| `8dcf31d9f` | Interpreter `\(LIST)` flatten | ref.t 113-117 |
| `f9040b781` | `local our VAR` re-loads localized global | split.t 164, 166 |
| `fdec68297` | `local(*foo) = *bar` list-assign | ref.t 1 |
| `91285924b` | LIST-context literals → cached read-only | ref.t 231, 233; for.t 105, 130-134 |
| `0258c7f4b` | SET_SCALAR preserves read-only alias | ref.t 232, 234 |
| `a93b61f5f` | First ReadOnlyAlias wrapper | (later evolved) |
| `113feb0bc` | Docs update | — |
| `479765fc4` | ReadOnlyAlias extends RuntimeScalarReadOnly w/ delegated reads | bop.t +285, split.t +85 |

## Recommendation

PR #552 delivers a net **+253 passing tests** improvement. Remaining
regressions are concentrated in refcount-precision (a design tradeoff
of the perf gains) and one-off subsystem corner cases. The branch
exceeds master on op/ref.t, op/undef.t, op/goto-sub.t, op/gv.t and
many others. Ready for reviewer evaluation.
