# Test Pass Rate Improvement Plan

**Date:** 2025-03-31
**Branch:** fix/sregex-args-propagation
**Baseline:** PR #410 test run — 93.4% individual test pass rate, 256/620 files fully passing

## Current State

| Metric | Value |
|--------|-------|
| Total test files | 620 |
| Fully passing | 256 (41.3%) |
| Failed (some tests not ok) | 198 (31.9%) |
| Errors (0/0 crash before plan) | 97 (15.6%) |
| Incomplete (crash mid-run) | 68 (11.0%) |
| Total individual tests | 257,957 |
| Passing individual tests | 241,036 (93.4%) |

## Root Cause Categories

Investigation of the worst-performing tests reveals **10 distinct root cause categories**
that explain the vast majority of failures. Fixing these in priority order maximizes
test improvement per unit of effort.

---

## Category 1: Compile-Time Crash — Syntax/Parse Errors on Valid Perl (0/0 tests)

**Impact: ~3,300+ blocked tests across ~20 files**

These tests die during compilation before emitting any TAP output. Each fix
unlocks an entire test file.

| Test File | Planned Tests | Blocking Error |
|-----------|--------------|----------------|
| op/taint.t | 1,065 | `Unknown warnings category 'experimental::smartmatch'` |
| re/pat_re_eval.t | 552 | Syntax error — file needs `(?{...})` support at parse level |
| op/smartmatch.t | 353 | `given`/`when` not implemented |
| op/tie_fetch_count.t | 343 | Syntax error near `check_count` — parse bug |
| op/local.t | 319 | `delete &{$variable}` — dynamic delete not implemented |
| op/sub_lval.t | 215 | `Internals::SvREADONLY` wrong argument count |
| op/sort.t | 206 | Prototype-based `sort` with stacked comparison not parsed |
| op/switch.t | 197 | `CORE::given` not a keyword |
| op/lvref.t | 203 | Lvalue reference syntax `\my $x = \$y` not parsed |
| op/method.t | 163 | Syntax error in indirect method call parsing |
| op/goto.t | 87 | Syntax error at line 525 |
| op/require_errors.t | 73 | `qr/\\` parsing in test file |
| op/loopctl.t | 69 | `last EXPR` (last with expression) not implemented |
| op/lex.t | 53 | `delete &{$variable}` |
| op/sysio.t | 45 | `sysseek` operator not implemented |
| op/override.t | 36 | `java.lang.VerifyError` — bytecode generation bug |
| op/gv.t | many | `Node.accept()` NPE — AST visitor crash |
| op/const-optree.t | — | `method` keyword parsing |
| op/magic-27839.t | — | Syntax error |

### Quick wins in this category

1. **op/taint.t (1,065 tests)**: Just registering `experimental::smartmatch` as a known
   warnings category (even if it does nothing) would unblock this file.
2. **op/tie_fetch_count.t (343 tests)**: Likely a small parser bug — investigate the exact
   syntax that fails.
3. **op/local.t (319 tests)**: Implement `delete &{$expr}` for dynamic coderef deletion.
4. **op/sort.t (206 tests)**: Fix prototype argument count for stacked sort comparators.
5. **op/override.t (36 tests)**: Fix the VerifyError bytecode generation bug.

---

## Category 2: Missing `(?{...})` and `(??{...})` Regex Code Blocks

**Impact: ~670+ tests across ~10 files**

Perl allows embedded code execution inside regex patterns. PerlOnJava's Java-based
regex engine cannot support this natively. This is the single largest regex limitation.

| Test File | Passed/Total | Blocked Tests |
|-----------|-------------|---------------|
| re/pat_re_eval.t | 0/552 | 552 (compile error) |
| re/reg_eval_scope.t | 0/49 | 49 |
| re/reg_eval.t | 0/8 | 8 |
| re/subst.t | 184/281 | ~30 from code blocks |
| re/substT.t | 184/281 | ~30 |
| re/pat_advanced.t | 49/83 | ~5 |
| + scattered in regexp.t variants | | |

**Approach options:**
- Full implementation requires a custom regex engine or Joni integration (major effort)
- Partial: support simple `(?{ $var++ })` by pre/post-processing around Java regex
- Could also mark these as known-skip to unblock test counting

---

## Category 3: Regex Engine Gaps (Recursion, Conditionals, Backtracking Verbs)

**Impact: ~2,500+ test failures across ~15 files**

These are limitations of Java's `java.util.regex` engine vs Perl's regex engine.

| Sub-feature | Est. Failures | Key Test Files |
|-------------|--------------|----------------|
| Error message text mismatches | ~1,800 | re/reg_mesg.t (1676/2509) |
| Recursive patterns `(?R)`, `(?1)`, `(?&name)` | ~60 | re/regexp.t variants |
| `(?(cond)yes\|no)` conditionals | ~55 | re/regexp.t, re/reg_email.t |
| Backtracking verbs `(*PRUNE)`, `(*FAIL)`, etc. | ~50 | re/regexp.t variants |
| Branch reset `(?|...)` | ~27 | re/regexp.t variants |
| `$^N` (last successful capture) | ~30 | re/pat_advanced.t |
| `\G` in substitution | ~24 | re/subst.t |
| Named group `%+` edge cases | ~18 | re/regexp.t |
| `(*pla:...)` control verb aliases | ~110 | re/alpha_assertions.t |

**Combined impact on regexp.t family:** Each of regexp.t, regexp_noamp.t, regexp_qr.t,
regexp_notrie.t, regexp_trielist.t, regexp_qr_embed.t shares ~400 failures from the
same re_tests data file. Fixing the underlying regex features fixes all 6 files at once.

**Approach:**
- reg_mesg.t: Add a Perl-compatible error message mapping layer for common patterns
- Consider integrating Joni (JRuby's regex engine) which supports recursion, conditionals,
  and backtracking verbs natively
- `(*pla:...)` aliases: Map to existing `(?=...)` lookahead syntax in the regex parser

---

## Category 4: Incomplete `caller()` Implementation

**Impact: ~66 failures in op/caller.t (46/112), plus uni/caller.t (11/18)**

| Missing Element | caller() Index | Est. Fixes |
|----------------|---------------|------------|
| `%^H` hints hash | [10] | ~35 tests |
| Eval text | [6] | ~7 tests |
| `__ANON__` sub name | [3] | ~5 tests |
| `#line` directive tracking | — | ~7 tests |
| `@DB::args` / nested `use` depth | — | ~16 tests |

**Quick win:** Implementing `caller()[10]` (hints hash) alone fixes ~35 tests.

---

## Category 5: Crash-on-Valid-Code — Fatal Errors That Block Remaining Tests

**Impact: ~600+ blocked tests from mid-file crashes**

These are tests that start running but crash fatally, blocking all subsequent tests.

| Test File | Passed/Total | Blocking Bug | Tests Unblocked |
|-----------|-------------|--------------|-----------------|
| op/ref.t | 96/265 | `@UNIVERSAL::ISA` not followed in MRO | **156** |
| op/state.t | 69/170 | Dynamic `goto $variable` silently exits | **101** |
| op/heredoc.t | 66/138 | Heredoc inside `eval 's//<<~/e'` | **~60** |
| op/filetest.t | 227/436 | NPE from NUL in filename (`-T "TEST\0"`) | **5** |
| op/universal.t | 90/142 | `$1` undef after successful regex match | **38** |
| op/vec.t | 37/78 | `vec()` rejects 64-bit width | **28** |
| op/fh.t | 0/8 | NPE in `fileno()` on unopened handle | **8** |

### Quick wins

1. **op/fh.t**: Add null-check in `fileno()` → unlocks 8 tests.
2. **op/filetest.t**: Guard against null path after NUL detection → unlocks 5 tests.
3. **op/vec.t**: Extend vec() to support 64-bit widths → unlocks 28 tests.
4. **op/ref.t**: Implement `@UNIVERSAL::ISA` traversal in MRO → unlocks 156 tests.
5. **op/state.t**: Fix dynamic `goto $variable` → unlocks 101 tests.

---

## Category 6: Attribute System Not Implemented

**Impact: ~82 failures in op/attrs.t (44/126), plus uni/attrs.t (5/31)**

| Missing Component | Est. Fixes |
|-------------------|------------|
| `attributes.pm` module | ~40 tests |
| `MODIFY_*_ATTRIBUTES` / `FETCH_*_ATTRIBUTES` callbacks | ~10 tests |
| `attributes::get()` function | ~10 tests |
| Invalid attribute error messages | ~15 tests |
| Attribute validation | ~5 tests |

The parser already collects attributes into `RuntimeCode.attributes`. The missing piece
is the runtime dispatch layer: `attributes.pm`, the `MODIFY_*` callbacks, and `attributes::get()`.

---

## Category 7: Tie Magic Dispatch Incomplete

**Impact: ~49 failures in op/tie.t (49/95), plus op/tiehandle.t (35/67)**

| Sub-issue | Est. Fixes |
|-----------|------------|
| FETCH not called at right times/counts | ~20 tests |
| DESTROY not called on scope exit | ~6 tests |
| Tied special variables cause StackOverflow | ~7 tests |
| Missing untie warnings, read-only checks | ~9 tests |
| Error message mismatches | ~9 tests |

**Note:** DESTROY not firing is a known limitation (see AGENTS.md). The FETCH
invocation count issues are more tractable.

---

## Category 8: `given`/`when`/`smartmatch` Not Implemented

**Impact: ~550+ tests across 3+ files**

| Test File | Planned Tests |
|-----------|--------------|
| op/smartmatch.t | 353 |
| op/switch.t | 197 |
| op/coreamp.t | partial |

These are all Perl 5.10+ experimental features. While deprecated in recent Perl,
many test files still use them. Even registering the `experimental::smartmatch`
warnings category would unblock op/taint.t (1,065 tests).

---

## Category 9: Signatures and Prototype Edge Cases

**Impact: ~265 failures in op/signatures.t (643/908), plus related tests**

From the test log's feature analysis, signatures/prototypes/subroutines affect ~58 test
files. The op/signatures.t file alone has 265 failures, suggesting gaps in:
- Default value expressions
- Slurpy parameter edge cases
- Error message text for too many/few arguments
- Interaction with attributes

---

## Category 10: Missing Perl Modules and Infrastructure

**Impact: ~20 files can't even load**

| Missing Module/Feature | Files Affected |
|-----------------------|----------------|
| `NEXT.pm` | mro/next_NEXT.t |
| `attributes.pm` | op/attrs.t (40 tests) |
| `Unicode::UCD::UnicodeVersion` | re/reg_fold.t |
| `re::optimization` | re/opt.t |
| `Internals::SvREADONLY` | op/sub_lval.t |
| `TestProp.pl` data | re/uniprops01-10.t (10 files) |
| `NEXT.pm` | mro/next_NEXT.t, next_NEXT_utf8.t |

---

## Prioritized Action Plan

### Phase 1: Quick Wins (est. +2,000 tests, low effort)

These are small, targeted fixes that each unblock many tests:

| # | Fix | Tests Gained | Effort |
|---|-----|-------------|--------|
| 1.1 | Register `experimental::smartmatch` warnings category | ~1,065 (taint.t) | Tiny |
| 1.2 | Null-check in `fileno()` for unopened handles | 8 (fh.t) | Tiny |
| 1.3 | Null-guard in file test operator for NUL-in-filename | 5 (filetest.t) | Tiny |
| 1.4 | `@UNIVERSAL::ISA` traversal in MRO | 156 (ref.t) | Small |
| 1.5 | Fix dynamic `goto $variable` | 101 (state.t) | Small |
| 1.6 | Extend `vec()` to 64-bit widths | 28 (vec.t) | Small |
| 1.7 | Fix `$1` capture after successful match (state corruption) | 38 (universal.t) | Small |
| 1.8 | Fix unrecognized-switch error message (add trailing `.`) | ~56 (switches.t) | Tiny |
| 1.9 | Fix op/tie_fetch_count.t parse error | ~343 | Small |
| 1.10 | Fix op/sort.t prototype argument handling | ~206 | Small |

### Phase 2: Medium Fixes (est. +2,500 tests, moderate effort)

| # | Fix | Tests Gained | Effort |
|---|-----|-------------|--------|
| 2.1 | Implement `caller()` elements [6] and [10] | ~42 (caller.t) | Medium |
| 2.2 | Implement `delete &{$expr}` (dynamic coderef delete) | ~372 (local.t, lex.t) | Medium |
| 2.3 | Fix heredoc inside `eval 's//<<~/e'` + space delimiters | ~60 (heredoc.t) | Medium |
| 2.4 | Implement `sysseek` operator | ~45 (sysio.t) | Medium |
| 2.5 | Implement `attributes.pm` + `attributes::get()` | ~50 (attrs.t) | Medium |
| 2.6 | Fix `op/method.t` indirect method call parsing | ~163 | Medium |
| 2.7 | Fix `op/override.t` VerifyError | ~36 | Medium |
| 2.8 | Fix lvalue reference syntax `\my $x` | ~203 (lvref.t) | Medium |
| 2.9 | Add `(*pla:...)` regex verb aliases | ~110 (alpha_assertions.t) | Medium |
| 2.10 | Fix FETCH invocation count in tie dispatch | ~20 (tie.t) | Medium |

### Phase 3: Large Features (est. +3,000+ tests, significant effort)

| # | Fix | Tests Gained | Effort |
|---|-----|-------------|--------|
| 3.1 | `given`/`when`/`smartmatch` implementation | ~550 | Large |
| 3.2 | Regex error message compatibility layer | ~800 (reg_mesg.t) | Large |
| 3.3 | Regex: recursive patterns, conditionals, branch reset | ~400 x6 files | Large |
| 3.4 | `(?{...})` regex code blocks | ~670 | Very Large |
| 3.5 | Signatures edge cases | ~265 | Large |
| 3.6 | `op/goto.t` full implementation | ~87 | Large |

### Phase 4: Infrastructure & Polish

| # | Fix | Tests Gained | Effort |
|---|-----|-------------|--------|
| 4.1 | Port `NEXT.pm` module | ~few | Small |
| 4.2 | `re::optimization`, `re 'debug'` support | ~48 (recompile.t) | Medium |
| 4.3 | Unicode property test data (TestProp.pl) | 10 files | Medium |
| 4.4 | MODIFY_*_ATTRIBUTES callbacks | ~10 (attrs.t) | Medium |
| 4.5 | `@DB::args` and debugger integration | ~16 (caller.t) | Large |

---

## Expected Outcome

If Phases 1-2 are completed:
- Individual test pass rate: ~93.4% → ~96%+
- Fully passing files: ~256 → ~290+
- ~4,500 additional tests passing

If all phases completed:
- Individual test pass rate: ~98%+
- Fully passing files: ~350+
- ~10,000+ additional tests passing

---

## Tests to Exclude From Prioritization

These tests are inherently incompatible with PerlOnJava and should not be targets:

| Category | Examples | Reason |
|----------|---------|--------|
| Perl internals | porting/*.t (most) | Test Perl C source code |
| Threading | *_thr.t variants | Perl threads not supported |
| XS-dependent | regexp_nonull.t | Requires XS::APItest |
| Platform-specific | win32/*.t (most) | Windows-specific tests |
| DESTROY-dependent | class/destruct.t | Known limitation |
| Perl optree | perf/opcount.t, optree.t | Test Perl's internal optree |

---

## Tracking

Update this document as fixes land. Use the test runner to measure progress:

```bash
perl dev/tools/perl_test_runner.pl --output out.json perl5_t/t/
```

Compare against baseline: **241,036 / 257,957 passing (93.4%)**.
