# Perl::Tidy Support Plan

## Goal

Make `./jcpan -t Perl::Tidy` run without errors on PerlOnJava.

## Current Status

**Version:** Perl-Tidy-20260204 (SHANCOCK/Perl-Tidy-20260204.tar.gz)
**Install:** Succeeds — 16 files installed to `~/.perlonjava/lib/`
**Tests:** 5/44 files pass, 39/44 fail (14/49 subtests fail, but most files die mid-run)

### Test Results Summary

| Category | Files | Result |
|----------|-------|--------|
| Passing | t/filter_example.t, t/test.t, t/testsa.t, t/testss.t, t/zero.t | 5 OK |
| Snippet tests (DESTROY) | t/snippets1.t–t/snippets33.t (33 files) | 33 FAIL |
| Wide char tests (DESTROY + alignment) | t/testwide.t, t/testwide-passthrough.t, t/testwide-tidy.t | 3 FAIL |
| Filter/option tests | t/atee.t | 1 FAIL (1/2 subtests) |
| EOL tests | t/test-eol.t | 1 FAIL (0/4 subtests ran) |
| Debug output test | t/test_DEBUG.t | 1 FAIL (1/2 subtests) |

## Blockers

### Blocker 1: Missing DESTROY Breaks Singleton Guards (Critical — 36+ test files)

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

**Impact:** ~555 subtests across 36 test files never run.

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

This works because `_decrement_count` is a package-scoped sub (not a lexical),
so it's callable from outside the class.

**Alternative approaches:**
- **Patch Formatter.pm and Tokenizer.pm** — change `new()` to not rely on
  DESTROY (e.g., reset `$_count` before incrementing). Requires patching 2
  files instead of 1.
- **Implement DESTROY in PerlOnJava** — the most complete fix but a very large
  undertaking (see `dev/design/destroy_weaken_plan.md`).

**Effort:** Low — 2 lines added to one file.

### Blocker 2: perltidyrc String Ref Option Parsing (Moderate — affects first test in some snippet files + t/atee.t)

**Symptom (t/atee.t):**
```
# Test 1 got: "# block comment\n\n=pod\nsome pod\n=cut\n\nprint \"hello world\\n\";\n$xx++;    # side comment\n"
#   Expected: "\nprint \"hello world\\n\";\n$xx++;\n"
```

The test passes options via `perltidyrc => \$params` where `$params` is a
string like `"-dac -tac -D -g"`. The formatter runs (whitespace is adjusted)
but option-specific features (delete comments, brace placement) don't take
effect.

**Root cause hypothesis:** The options are passed through
`expand_command_abbreviations` and then `Getopt::Long::GetOptions`. Possible
PerlOnJava issues:
1. **`Getopt::Long` `!` (negatable boolean) handling** — delete options use
   `'delete-block-comments' => '!'`. If PerlOnJava's Getopt::Long doesn't
   handle `!` correctly, options are silently ignored.
2. **Abbreviation expansion** — `-dac` must be expanded to
   `--delete-block-comments --delete-side-comments --delete-pod`. If the
   expansion regex fails, unexpanded `-dac` is passed to GetOptions which
   doesn't recognize it.
3. **Hash key population** — The expanded options may not populate
   `$rOpts->{'delete-block-comments'}` correctly.

**Affected tests:**
- t/atee.t (test 1 of 2) — `-dac -tac -D -g` options
- t/snippets18.t (test 1 — braces.braces1) — `-bl -asbl` (Allman brace style)
- t/snippets22.t (test 1 — bbhb.bbhb2) — `-bbhb` (break before hash brace)
- t/snippets30.t, t/snippets31.t — first test also fails with formatting diffs

**Investigation needed:**
```bash
# Test if atomic options work (no abbreviation expansion needed):
./jperl -e '
  use Perl::Tidy;
  my $src = "# comment\nprint 1;\n";
  my $out;
  Perl::Tidy::perltidy(
    source => \$src, destination => \$out,
    perltidyrc => \"--delete-block-comments",
    argv => ""
  );
  print $out;
'
# Expected: "\nprint 1;\n" (comment deleted)
# If comment remains: Getopt::Long ! handling is broken
# If comment deleted: abbreviation expansion is broken
```

**Fix approach:** Once root cause is confirmed, either:
- Fix `Getopt::Long` `!` handling in PerlOnJava's bundled Getopt::Long
- Fix abbreviation expansion regex in Perl::Tidy (less likely needed)
- Patch Perl::Tidy.pm's option processing to work around the issue

**Effort:** Medium — requires debugging the option parsing pipeline.

### Blocker 3: Wide Character Alignment (Low — 3 test files)

**Symptom (t/testwide.t):**
```perl
# Got:     4-space indent (standard formatting)
# Expected: right-aligned to opening parenthesis
```

The formatter doesn't compute correct display widths for multi-byte Unicode
characters (Cyrillic, Polish, German umlauts). This causes hash value
alignment to use standard indentation instead of right-aligning to the `(`
column.

**Affected tests:** t/testwide.t (3/3 fail), t/testwide-passthrough.t (3/4
fail), t/testwide-tidy.t (3/4 fail)

**Root cause hypothesis:** Perl::Tidy measures string widths using `length()`
which in Perl returns codepoint count. For alignment purposes, display width
matters. Wide characters (CJK) take 2 columns, while most Latin/Cyrillic
take 1. The issue may be in how PerlOnJava handles `length()` on decoded
Unicode strings, or in how Perl::Tidy's alignment calculations interact with
PerlOnJava's string handling.

Note: Tests 1-2 in testwide.t both fail with the same alignment issue, and
test 3 fails due to the DESTROY singleton bug (Blocker 1). Fixing Blocker 1
won't fix tests 1-2 but will allow test 3 to run.

**Effort:** Medium — requires investigating string width calculations.

### Blocker 4: EOL Handling (Low — 1 test file)

**Symptom (t/test-eol.t):** All 4 subtests produce no output (0 tests ran).

The test likely checks for correct handling of different line endings (CR, LF,
CRLF). The test may die early or produce no TAP output at all.

**Investigation needed:**
```bash
cd /path/to/Perl-Tidy-build && PERL5LIB="./blib/lib:./blib/arch" \
  /path/to/jperl t/test-eol.t 2>&1
```

**Effort:** Low–Medium — needs investigation.

### Blocker 5: DEBUG File Output (Low — 1 test file)

**Symptom (t/test_DEBUG.t):** Test 2 expects debug output via `debugfile =>
\$string` but gets `undef`.

**Root cause:** The `debugfile` parameter to `perltidy()` writes debug/token
type information. The output goes to a `Perl::Tidy::Debugger` object which
writes to the file handle. The output may not be reaching the scalar ref.

**Effort:** Low — likely a minor I/O or filehandle issue.

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

### Phase 2: Fix Option Parsing (unblocks ~5 first-test failures)

1. **Investigate** Getopt::Long `!` negatable boolean handling
   - Test atomic options (no abbreviation needed) vs abbreviated options
   - If `!` handling is broken, fix in `src/main/perl/lib/Getopt/Long.pm`
   - If abbreviation expansion is broken, investigate Perl::Tidy's regex

2. **Verify:** t/atee.t test 1 passes, snippets18/22/30/31 first tests pass

### Phase 3: Fix Wide Character Alignment (nice to have)

1. **Investigate** string width computation for Unicode characters
2. May require changes to PerlOnJava's `length()` or Perl::Tidy's alignment code
3. **Verify:** t/testwide.t, t/testwide-passthrough.t, t/testwide-tidy.t

### Phase 4: Fix EOL and DEBUG (nice to have)

1. Investigate t/test-eol.t — likely minor I/O issue
2. Investigate t/test_DEBUG.t — debug file handle output

## Expected Results After Phase 1

With the DESTROY fix alone, the test results should improve dramatically:

| Before | After (estimated) |
|--------|-------------------|
| 5/44 files pass | ~35/44 files pass |
| 14/49 subtests fail | TBD (most snippet tests should fully pass) |
| Result: FAIL | Closer to PASS |

The snippet tests that also have first-test formatting failures
(snippets18, 22, 28, 30, 31, 32, 33) may still fail a few subtests due to
Blocker 2, but they will progress past the first test case.

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
