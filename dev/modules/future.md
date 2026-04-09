# Future 0.52 Support for PerlOnJava

## Status: Initial Analysis -- 36/56 test programs pass

- **Module version**: Future 0.52 (PEVANS/Future-0.52.tar.gz)
- **Date started**: 2026-04-08
- **Test command**: `./jcpan -t Future`
- **Build system**: Module::Build (auto-installed as dependency, all 53 tests pass)
- **Result**: 36/56 test programs pass (105/763 subtests fail)

## Background

Future is a foundational async programming module for Perl, used by IO::Async and many
event-driven frameworks. It provides promise/future objects for deferred computations.
Future 0.52 is pure-Perl (Future::PP) with an optional XS backend (Future::XS).

The module builds and loads correctly under PerlOnJava. Most core functionality works --
creating futures, resolving/failing them, chaining with `then`/`else`/`catch`, combinators
(`wait_all`, `needs_all`, etc.), transforms, subclassing, labels, and utilities. The failures
are concentrated in three root causes that are fixable or known JVM limitations.

## Test Results Summary

### Passing Tests (13 PP + 18 XS-skipped + 5 other-skipped = 36)

| Test File | Result | Tests | Notes |
|-----------|--------|-------|-------|
| t/00use.t | **ok** | - | Module loads |
| t/09transform-pp.t | **ok** | - | |
| t/20get-pp.t | **ok** | - | |
| t/20subclass-pp.t | **ok** | - | |
| t/22wrap_cb-pp.t | **ok** | - | |
| t/24label-pp.t | **ok** | - | |
| t/26wrap-unwrap-pp.t | **ok** | - | |
| t/27udata-pp.t | **ok** | - | |
| t/33utils-repeat-generate.t | **ok** | - | |
| t/34utils-repeat-foreach.t | **ok** | - | |
| t/35utils-map-void.t | **ok** | - | |
| t/36utils-map.t | **ok** | - | |
| t/51test-future-deferred.t | **ok** | - | |
| t/99pod.t | skipped | 0 | Test::Pod not installed |
| t/52awaitable-future-pp.t | skipped | 0 | No Test::Future::AsyncAwait::Awaitable |
| t/*-xs.t (18 files) | skipped | 0 | No Future::XS -- expected |

### Failing Tests (20/56)

| Test File | Failed/Total | Exit | Root Cause |
|-----------|-------------|------|------------|
| t/01future-pp.t | 14/79 | 255 | Refcount + B optree crash |
| t/02cancel-pp.t | 10/38 | 10 | Refcount |
| t/03then-pp.t | 6/56 | 6 | Refcount |
| t/04else-pp.t | 8/52 | 8 | Refcount |
| t/05then-else-pp.t | 6/21 | 6 | Refcount |
| t/06followed_by-pp.t | 9/40 | 9 | Refcount |
| t/07catch-pp.t | 8/28 | 8 | Refcount |
| t/10wait_all-pp.t | 6/40 | 6 | Refcount |
| t/11wait_any-pp.t | 6/42 | 6 | Refcount |
| t/12needs_all-pp.t | 6/38 | 6 | Refcount |
| t/13needs_any-pp.t | 6/48 | 6 | Refcount |
| t/21debug-pp.t | 3/15 | 3 | DESTROY not implemented |
| t/23exception-pp.t | 0/14 | 255 | `$@` leakage + B optree crash |
| t/25retain-pp.t | 9/18 | 9 | Refcount |
| t/30utils-call.t | 0/3 | 255 | B optree crash |
| t/31utils-call-with-escape.t | 1/11 | 1 | Refcount |
| t/32utils-repeat.t | 0/22 | 255 | B optree crash |
| t/40mutex.t | 4/42 | 4 | Refcount |
| t/50test-future.t | 3/5 | 0 | Refcount + line number |
| t/52awaitable-future-pp.t | 0/0 | 2 | exit(0) handling |

## Issues Found

### P0: `B::SV::REFCNT` returns 0 instead of 1 -- OPEN

- **Impact**: ~100 of 105 failed subtests across 15 test files
- **Root cause**: `B::SV::REFCNT` in `src/main/perl/lib/B.pm` line 59 returns `0`.
  `Test2::Tools::Refcount::refcount()` delegates to `B::svref_2object($ref)->REFCNT`,
  so all refcount checks get 0. Tests expecting `refcount == 1` fail (expected 1, got 0),
  and tests expecting `refcount == 2` also fail (expected 2, got 0).

  There is an **inconsistency** between the three refcount stubs:

  | Function | Returns | File |
  |----------|---------|------|
  | `B::SV::REFCNT()` | **0** | `src/main/perl/lib/B.pm:59` |
  | `Internals::SvREFCNT()` | **1** | `src/main/java/.../Internals.java:82` |
  | `Devel::Peek::SvREFCNT()` | **1** | `src/main/perl/lib/Devel/Peek.pm:32` |

- **Fix**: Change `B::SV::REFCNT` to return `1` instead of `0`. This aligns it with
  `Internals::SvREFCNT` and `Devel::Peek::SvREFCNT`. On JVM, true reference counting
  is not available, but returning 1 is the best pragmatic default:
  - `is_oneref($ref)` checks will pass (most common case)
  - `is_refcount($ref, 2, ...)` checks will still fail (expected 2, got 1), but this is
    an inherent JVM limitation -- there is no way to know the real refcount

- **File**: `src/main/perl/lib/B.pm` line 59
- **Expected improvement**: ~60% of the 105 failing subtests fixed (those expecting refcount 1);
  ~40% remain (those expecting refcount 2+). Estimated 12 test files go from FAIL to PASS
  or significantly improve.

### P1: `B::OP::next` returns `undef` instead of `B::NULL` -- OPEN

- **Impact**: 4 test files crash with exit 255 (t/01future-pp.t, t/23exception-pp.t,
  t/30utils-call.t, t/32utils-repeat.t). All subtests pass before the crash.
- **Root cause**: Future.pm's `CvNAME_FILE_LINE()` at line 246-250 walks the B optree:
  ```perl
  my $cop = $cv->START;
  $cop = $cop->next while $cop and ref $cop ne "B::COP" and ref $cop ne "B::NULL";
  return $cv->GV->NAME if ref $cop eq "B::NULL";
  sprintf "%s(%s line %d)", $cv->GV->NAME, $cop->file, $cop->line;  # line 250
  ```
  `B::OP::next()` returns `undef` (B.pm line 261), so the while loop exits with
  `$cop = undef`. The guard on line 249 (`ref $cop eq "B::NULL"`) doesn't match
  (`ref undef` is `""`), and line 250 calls `$cop->file` on `undef` -- crash.

  This function is called from `Future->call()` (line 320) when a code ref doesn't
  return a Future, to generate a diagnostic message identifying the anonymous sub.

- **Fix**: Change `B::OP::next` to return `B::NULL->new()` instead of `undef`.
  This makes the optree walk terminate properly at a `B::NULL` sentinel, which is
  what real Perl does. The guard on line 249 will then match and return gracefully.
  ```perl
  # B::OP::next - before:
  sub next { return; }
  # B::OP::next - after:
  sub next { return B::NULL->new(); }
  ```

- **File**: `src/main/perl/lib/B.pm` line 261
- **Expected improvement**: All 4 crashing test files stop crashing. t/30utils-call.t
  and t/32utils-repeat.t should fully pass (0 subtest failures + no crash).

### P2: DESTROY not implemented -- UNFIXABLE (JVM limitation)

- **Impact**: 3 subtests in t/21debug-pp.t
- **Root cause**: Future's debug mode installs a `DESTROY` method that warns when
  incomplete futures are garbage collected ("Lost Future" warnings). PerlOnJava does
  not call DESTROY for regular blessed objects (JVM uses tracing GC, not reference
  counting). The 3 tests check that these warnings are emitted.
- **Status**: Known JVM limitation, documented in AGENTS.md. Cannot be fixed without
  implementing DESTROY support (a major architectural change).
- **Workaround**: None. These 3 tests will remain failing.

### P3: `$@` leakage after `do` + `dies {}` -- OPEN (needs investigation)

- **Impact**: t/23exception-pp.t exits 255 despite all 14 subtests passing
- **Root cause**: The test wrapper pattern is:
  ```perl
  do "./t/23exception.pl";
  die $@ if $@;
  ```
  After `done_testing` completes successfully, `$@` is still set to `"message\n"`
  from an earlier `dies { $f->result }` call (where `$f` is a failed future that
  re-throws). The Test2 `dies {}` function should catch the exception and restore
  `$@`, but `$@` leaks in PerlOnJava.

  Note: this test also triggers the P1 crash (CvNAME_FILE_LINE), so fixing P1 alone
  may change the behavior. Both issues should be investigated together.
- **File**: Likely in eval/die handling in the PerlOnJava runtime, or in
  `Test2::Tools::Exception::dies()` behavior
- **Expected improvement**: 1 test file (t/23exception-pp.t) passes cleanly

### P4: `caller()` line number discrepancy -- OPEN (minor)

- **Impact**: 1 subtest in t/50test-future.t
- **Root cause**: Test expects error at line 37 but PerlOnJava reports line 35. This is
  a minor `caller()` accuracy issue, likely due to how PerlOnJava maps JVM stack frames
  to Perl source lines.
- **Status**: Low priority. The other 2 failures in t/50test-future.t are refcount-related.

### P5: `exit(0)` inside skip_all -- OPEN (minor)

- **Impact**: t/52awaitable-future-pp.t exits with code 2 instead of 0
- **Root cause**: The test calls `exit 0` as part of `skip_all` but PerlOnJava exits
  with code 2. Needs investigation into how `exit()` interacts with the test harness.
- **Status**: Low priority, cosmetic.

## Fix Plan

### Phase 1: Fix `B::SV::REFCNT` (high impact, trivial change)

Change `return 0` to `return 1` in `src/main/perl/lib/B.pm` line 59.

- **Estimated impact**: ~60 of 105 failing subtests fixed
- **Risk**: Low -- only changes a stub return value
- **Verify**: Re-run `./jcpan -t Future` and count improvements

### Phase 2: Fix `B::OP::next` (high impact, trivial change)

Change `B::OP::next` to return `B::NULL->new()` instead of `undef`.

- **Estimated impact**: 4 test files stop crashing (t/01future-pp.t, t/23exception-pp.t,
  t/30utils-call.t, t/32utils-repeat.t)
- **Risk**: Low -- `B::NULL` already exists in B.pm. Other code that checks for
  `undef` from `next()` would need to check `ref $op eq "B::NULL"` instead, but
  the `$cop` truthiness check in the while loop condition still works since
  `B::NULL->new()` is a blessed ref (truthy). Must verify no other code relies on
  `B::OP::next` returning falsy to terminate iteration.

  **Mitigation**: Add `B::NULL::next` returning self (so walking past NULL doesn't crash):
  ```perl
  package B::NULL {
      our @ISA = ('B::OP');
      sub new { bless {}, shift }
      sub next { return $_[0]; }  # NULL->next returns itself (terminal)
  }
  ```
  And ensure the while loop condition in the calling code uses `ref` checks (which it does).

### Phase 3: Investigate `$@` leakage (medium impact)

After Phases 1-2, re-test t/23exception-pp.t. If it still fails, investigate:
1. Whether `Test2::Tools::Exception::dies()` properly localizes `$@`
2. Whether PerlOnJava's eval/die handling has `$@` restoration issues
3. The interaction between `do FILE` and `$@` scoping

### Phase 4: Remaining cleanup (low priority)

- Investigate caller() line number accuracy (P4)
- Investigate exit(0) behavior in skip_all context (P5)

## Expected Results After Fixes

| Category | Current Failures | After Phase 1+2 | Notes |
|----------|-----------------|-----------------|-------|
| Refcount (expect 1) | ~60 subtests | **0** | Fixed by REFCNT=1 |
| Refcount (expect 2+) | ~40 subtests | ~40 subtests | Unfixable (JVM limitation) |
| B optree crash | 4 programs | **0** | Fixed by B::NULL |
| DESTROY | 3 subtests | 3 subtests | Unfixable (JVM limitation) |
| $@ leakage | 1 program | TBD | Needs investigation |
| Line numbers | 1 subtest | 1 subtest | Low priority |
| exit(0) | 1 program | 1 program | Low priority |

**Projected result after Phase 1+2**: ~17/56 programs fail (down from 20), ~65/763 subtests fail (down from 105), and 4 programs no longer crash.

## Dependencies

- **Module::Build**: Required build dependency, installs and passes all 53 tests.
  No issues found.
- **Test2::V0**: Used by most test files. Working correctly under PerlOnJava.
- **Test2::Tools::Refcount**: Used for refcount assertions. Works but returns 0
  due to `B::SV::REFCNT` (see P0).

## Related Documents

- `dev/modules/xs_fallback.md` -- XS fallback mechanism (relevant for Future::XS skip)
- AGENTS.md -- Documents `weaken`/`isweak`/DESTROY limitations
