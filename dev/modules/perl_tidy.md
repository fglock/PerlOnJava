# Perl::Tidy Support Plan

## Goal

Make `./jcpan -t Perl::Tidy` run without errors on PerlOnJava.

## Current Status

**Version:** Perl-Tidy-20260204 (SHANCOCK/Perl-Tidy-20260204.tar.gz)
**Install:** Succeeds — 16 files installed to `~/.perlonjava/lib/`
**Tests:** 7/44 files pass, 37/44 fail

### Test Results Summary (after \G fixes)

| Category | Files | Result |
|----------|-------|--------|
| Passing | t/atee.t, t/filter_example.t, t/test.t, t/test_DEBUG.t, t/testsa.t, t/testss.t, t/zero.t | 7 OK |
| Snippet tests (DESTROY) | t/snippets1.t–t/snippets33.t (33 files) | 33 FAIL |
| Wide char tests (DESTROY) | t/testwide.t (2/3 pass), t/testwide-passthrough.t (2/6), t/testwide-tidy.t (2/6) | 3 FAIL |
| EOL tests (DESTROY) | t/test-eol.t (1/4 pass) | 1 FAIL |

### Progress Tracking

| Date | Milestone | Tests Passing |
|------|-----------|---------------|
| 2025-04-08 | Initial investigation | 5/44 |
| 2025-04-09 | \G regex fixes (pos undef + non-/g) | 7/44 |

## Fixes Applied

### Fix 1: \G Regex Anchor — pos() undef case (2025-04-09)

**File:** `src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java` (line 651)

**Problem:** When `pos()` was undef, the `\G` anchor check was skipped entirely
(`if (regex.useGAssertion && isPosDefined && matcher.start() != startPos)`).
This allowed `\G(\s+)` to scan forward and match whitespace anywhere in the
string, even though `\G` should anchor at position 0 when pos is undef.

**Impact:** Perl::Tidy's `parse_args` function uses `\G/gc` patterns to
tokenize option strings. With broken `\G`, options like `-dac` were silently
dropped, causing t/atee.t to fail.

**Fix:** Removed `isPosDefined` from the condition. When pos is undef,
`startPos` defaults to 0, so `\G` correctly anchors at 0.

### Fix 2: \G in Non-/g Matches (2025-04-09)

**File:** `src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java` (line 607)

**Problem:** `pos()` was only looked up for `/g` matches. In Perl, `\G`
should anchor at `pos()` even in non-`/g` matches (e.g. `$str =~ /\Gfoo/`).
PerlOnJava was ignoring pos entirely for non-/g matches containing `\G`.

**Impact:** Perl::Tidy's tokenizer uses `\G` in non-/g matches for signature
detection (line 10060: `$input_line =~ /\G\s*\(/`). Without this fix,
subroutine signatures like `sub foo($bar, %opts)` were misidentified as
prototypes, causing t/filter_example.t to fail.

**Fix:** Changed the pos() lookup condition from `isGlobalMatch()` to
`isGlobalMatch() || useGAssertion`, so pos is looked up whenever `\G` is
present in the pattern.

## Remaining Blocker: Missing DESTROY (33+ test files)

**Symptom:**
```
Attempt to create more than 1 object in Perl::Tidy::Formatter, which is not a true class yet
 at .../Perl/Tidy/Formatter.pm line 1108.
```

This error kills the 2nd (and all subsequent) calls to `perltidy()` within a
single process. Since each snippet test file calls `perltidy()` 8–20 times in
a loop, only the first test passes per file.

**Root cause:** `Perl::Tidy::Formatter` and `Perl::Tidy::Tokenizer` use
closure-scoped instance counters that are incremented in `new()` and
decremented in `DESTROY()`. PerlOnJava does not call `DESTROY`, so the counter
never resets to 0.

**Formatter.pm singleton pattern:**
```perl
{    ## begin closure to count instances
    my $_count = 0;
    sub _increment_count { return ++$_count }
    sub _decrement_count { return --$_count }
}

sub DESTROY {
    my $self = shift;
    _decrement_count();
    return;
}

sub new {
    ...
    if ( _increment_count() > 1 ) {
        confess "Attempt to create more than 1 object...";
    }
    ...
}
```

`Perl::Tidy::Tokenizer` has an identical pattern (lines 271–284, guard at
line 676).

**Other DESTROY methods in Perl::Tidy:** 10 other classes have empty DESTROY
methods (only to prevent AUTOLOAD dispatch) — these are safe with missing
DESTROY. Only `Formatter` and `Tokenizer` have functional DESTROY code.

**Impact:** ~555 subtests across 33+ test files never run.

**Fix (Bundled overlay — Perl/Tidy.pm):**

Patch `Perl::Tidy.pm`'s `perltidy()` function to explicitly call
`_decrement_count()` on Formatter and Tokenizer before returning. This
compensates for the missing DESTROY call with a 2-line surgical change.

In `Perl/Tidy.pm`, add before the final return in `perltidy()` (~line 1395):
```perl
# PerlOnJava: DESTROY not called on JVM — manually reset singleton counters
Perl::Tidy::Formatter::_decrement_count();
Perl::Tidy::Tokenizer::_decrement_count();
```

**Effort:** Low — 2 lines added to one file.

## Implementation Plan

### Phase 1: Fix DESTROY Singleton (unblocks ~555 subtests)

1. **Create bundled overlay** of `Perl/Tidy.pm`
   - Copy upstream `Perl/Tidy.pm` (v20260204) to `src/main/perl/lib/Perl/Tidy.pm`
   - Add `_decrement_count()` calls before `perltidy()`'s return points
   - Mark changes with `# PerlOnJava:` comments
   - Store diff in `dev/patches/cpan/Perl-Tidy-20260204/`

2. **Verify:** Re-run `./jcpan -t Perl::Tidy` — expect snippet tests to
   progress past first test case in each file.

3. **Run `make`** — ensure no regressions in PerlOnJava's own tests.

### Phase 2: Wide Character Alignment (nice to have)

1. **Investigate** string width computation for Unicode characters
2. May require changes to PerlOnJava's `length()` or Perl::Tidy's alignment code
3. **Verify:** t/testwide.t, t/testwide-passthrough.t, t/testwide-tidy.t

## Expected Results After Phase 1

With the DESTROY fix alone, the test results should improve dramatically:

| Before | After (estimated) |
|--------|-------------------|
| 7/44 files pass | ~38/44 files pass |
| 4/53 subtests fail | TBD (most snippet tests should fully pass) |
| Result: FAIL | Closer to PASS |

## Dependency on Other Work

- **DESTROY implementation** (`dev/design/destroy_weaken_plan.md`): Would fix
  this and all other DESTROY-dependent CPAN modules generically. However, it's
  a large project. The targeted Perl::Tidy.pm overlay is the pragmatic
  short-term fix.
- **Perl::Critic** (`dev/modules/perl_critic.md`): Already installed
  (99.9% pass rate). Its `RequireTidyCode` policy fails because Perl::Tidy's
  `Formatter::initialize_self_vars` exceeds the JVM 255-argument method
  signature limit. That issue is separate from the test suite failures
  documented here.

## Related Documents

- [cpan_patch_plan.md](cpan_patch_plan.md) — CPAN patching strategy (Option A: Bundled Overlays)
- [perl_critic.md](perl_critic.md) — Perl::Critic support (uses Perl::Tidy optionally)
- `dev/design/destroy_weaken_plan.md` — DESTROY/weaken implementation design
