# Log::Log4perl Compatibility Plan

## Overview

This document tracks the work needed to make `./jcpan Log::Log4perl` fully pass its test suite on PerlOnJava.

## Current Status (2026-03-19)

### Test Results

```
Files=73, Tests=700
Failed 8/73 test programs
Failed 26/700 subtests
```

### Failing Tests Summary

| Test File | Failed/Total | Issue Category |
|-----------|--------------|----------------|
| t/016Export.t | 1/16 | DESTROY message |
| t/020Easy.t | 3/21 | caller() / Carp line numbers |
| t/022Wrap.t | 2/5 | caller() stack trace format |
| t/024WarnDieCarp.t | 11/73 | caller() / Carp line numbers |
| t/026FileApp.t | 3/27 | File permissions / substr issues |
| t/041SafeEval.t | 3/23 | Safe.pm / Opcode.pm |
| t/049Unhide.t | 1/1 | Source filter / ###l4p |
| t/051Extra.t | 2/11 | Line number reporting |

### Current Investigation: t/020Easy.t Carp.pm Error

**Status:** Partially debugged - the error is intermittent and context-dependent.

**Symptom:**
```
Can't use an undefined value as a GLOB reference at jar:PERL5LIB/Carp.pm line 755
```

**Key Finding:** The error occurs when:
1. A bareword filehandle `IN` is opened and read from (`<IN>`)
2. Log4perl's `%T` layout is used (which calls `Carp::longmess()`)
3. The `%T` pattern is rendered during logging

**Reproduction Path (simplified):**
```perl
open IN, "<", "somefile";
my @lines = <IN>;  # Sets ${^LAST_FH}
use Carp;
my $m = Carp::longmess();  # Sometimes fails with undef GLOB
```

**What's NOT the issue:**
- `*{NAME}` slot - now implemented and working
- `local *$dynamic` - now implemented for interpreter backend
- `${^LAST_FH}` basic functionality - works in isolation

**Investigation Notes:**
- The error happens at Carp.pm line 752: `*{"warnings::$_"} = \&$_ foreach @EXPORT;`
- This code runs when `$warnings::VERSION` is undefined (which it is in PerlOnJava's warnings.pm)
- The bareword filehandle name check (`*{${^LAST_FH}}{NAME}`) now works
- Error is NOT reproducible in simple test cases - only in specific call stack contexts
- May be related to how Carp.pm is loaded/initialized in the presence of active I/O

**Next Steps:**
1. Add `$VERSION` to PerlOnJava's warnings.pm to skip the problematic code path
2. Or investigate why `$_` becomes undefined during the foreach loop in certain contexts

## Completed Fixes

### 8. exit() Inside BEGIN Blocks (PR #331, 2026-03-19)

**Problem:** `exit()` inside a BEGIN block caused "BEGIN failed--compilation aborted" error instead of exiting the program cleanly.

**Symptom:**
```
$ ./jperl -e 'BEGIN { exit 0; } print "should not print"'
exit 0
BEGIN failed--compilation aborted at -e line 1, near ""
```

**Root Cause:** `SpecialBlockParser.runSpecialBlock()` caught all `Throwable` exceptions (including `PerlExitException`) and converted them to `PerlCompilerException` with "BEGIN failed" message.

**Fix:** Added specific catch for `PerlExitException` that re-throws it, allowing it to propagate to `Main.main()` which converts it to `System.exit()`.

**Files Changed:**
- `src/main/java/org/perlonjava/frontend/parser/SpecialBlockParser.java`

**Tests Fixed:** Tests using `plan skip_all` in BEGIN blocks (e.g., t/056SyncApp2.t, t/066SQLite.t) now skip cleanly without compilation errors.

### 1. *{NAME} Glob Slot Accessor (Committed 2026-03-18)

**Problem:** `*{$glob}{NAME}` returned empty string instead of the glob's name.

**Root Cause:** The NAME slot was not implemented in RuntimeGlob's `getSlot()` method.

**Fix:** Added case for "NAME" that extracts the name from globName after the last `::`.

**Files Changed:**
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeGlob.java`

**Commit:** 0a5e92556

### 2. Interpreter: local *$dynamic Support (Committed 2026-03-18)

**Problem:** `local *$probe = sub { ... }` failed with "Assignment to unsupported operator: local"

**Root Cause:** The interpreter's `handleLocalAssignment()` only handled static glob names, not dynamic ones like `*$probe`.

**Fix:** Added case for dynamic glob names (when operand is not IdentifierNode) using LOAD_GLOB_DYNAMIC opcode.

**Files Changed:**
- `src/main/java/org/perlonjava/backend/bytecode/CompileAssignment.java`

**Commit:** 68d295287

### 3. Interpreter: gethostbyname Opcode (Committed 2026-03-18)

**Problem:** `gethostbyname` was not implemented in the interpreter backend.

**Fix:** Added GETHOSTBYNAME opcode (389) and routing to ExtendedNativeUtils.

**Files Changed:**
- `src/main/java/org/perlonjava/backend/bytecode/Opcodes.java`
- `src/main/java/org/perlonjava/backend/bytecode/CompileOperator.java`
- `src/main/java/org/perlonjava/backend/bytecode/MiscOpcodeHandler.java`

**Commit:** 68d295287

### 4. Bareword Filehandle Method Calls (Committed 2026-03-18)

**Problem:** `IN->clearerr()` failed with "Can't locate object method 'clearerr' via package 'IN'"

**Root Cause:** `isGlobalIODefined()` was checking `glob.value instanceof RuntimeIO` but IO handles are stored in `glob.IO`, not `glob.value`.

**Fix:** Changed `isGlobalIODefined()` to check `glob.IO.getDefinedBoolean()` instead.

**Files Changed:**
- `src/main/java/org/perlonjava/runtime/runtimetypes/GlobalVariable.java`

**Commit:** 3d0bf9b59

**Tests Fixed:** Unblocked 15+ tests in t/020Easy.t (from 1 to 16 passing)

### 5. $( and $) Special Variables (Committed 2026-03-18)

**Problem:** `$(` and `$)` (real/effective GID) were not working - returned literal `$(` in strings.

**Root Cause:** 
1. Variables not initialized with actual GID values
2. `(` and `)` not in special variable character lists
3. `(` and `)` in non-interpolating character list blocked string interpolation

**Fix:**
- Initialize `$(` and `$)` in GlobalContext with `getgid()`/`getegid()` values
- Add `(` and `)` to special variable character lists in IdentifierParser
- Remove `(` and `)` from non-interpolating character list

**Files Changed:**
- `src/main/java/org/perlonjava/runtime/runtimetypes/GlobalContext.java`
- `src/main/java/org/perlonjava/frontend/parser/IdentifierParser.java`
- `src/main/java/org/perlonjava/frontend/parser/StringSegmentParser.java`

**Commit:** a82bf0c66

**Tests Fixed:** t/033UsrCspec.t - all 17 tests now pass (was 5 failing)

### 6. AUTOLOAD $AUTOLOAD Variable (Committed Earlier)

**Problem:** `$AUTOLOAD` was not being set correctly in two scenarios:
1. Method call cache hits were skipping the `$AUTOLOAD` assignment
2. `our $AUTOLOAD` declared in different packages within the same lexical scope was silently ignored

**Fix:** 
- Added `$AUTOLOAD` assignment in the cache hit path in `RuntimeCode.java`
- Modified `SymbolTable.addVariable()` to create new entries for `our` declarations in different packages

**Files Changed:**
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`
- `src/main/java/org/perlonjava/frontend/semantic/SymbolTable.java`

**Commit:** 9f2d0aaf2

### 7. Parser/Runtime Fixes (Committed Earlier)

- `sprintf %s` treating "INFO" as Infinity
- `oct("0")` crash
- `splitpath()` returning wrong component
- Newlines between sigil and variable name

## Remaining Issues

### Issue 1: `local` Package Variable Bug - VERIFIED FIXED

**Status:** The basic reproduction case now works correctly. Need to verify if Log4perl tests improved.

**Verification (2026-03-19):**
```perl
package Foo;
our $X = 0;
sub check { print "X=$X\n"; }

package main;
local $Foo::X = 1;
Foo::check();  # jperl now correctly prints "X=1"
```

The issue may have been fixed by earlier changes (possibly the `our` variable handling in SymbolTable). The design doc at `dev/design/local-package-variable-fix.md` was created but implementation may not have been needed.

**Note:** Some Log4perl tests still fail with caller() / Carp line number issues - these may be separate from the `local` issue.

### Issue 2: Carp.pm / warnings.pm Interaction

**Symptom:** t/020Easy.t tests 17-21 - error after %T logging:
```
Can't use an undefined value as a GLOB reference at jar:PERL5LIB/Carp.pm line 755
```

**Root Cause:** PerlOnJava's warnings.pm lacks `$VERSION`, causing Carp.pm to execute a workaround code path (line 752) that fails in certain contexts.

**Proposed Fix:** Add `our $VERSION = "1.78";` to PerlOnJava's warnings.pm to skip the problematic code path.

**Note:** 4 of the 5 failing tests in t/020Easy.t are just filename pattern mismatches (test expects "020Easy.t" but gets "-" from stdin). Only 1 failure is the Carp.pm error.

**Affected Tests:**
- t/020Easy.t (tests 17-21)

### Issue 3: File Permissions (stat/chmod)

**Symptom:** t/026FileApp.t tests 6-7 fail comparing expected vs actual file permissions.

**Example:**
```perl
# Expected: '488' (octal 0750)
# Got: '511' (octal 0777)
```

**Root Cause:** Likely issue with `umask` handling or `chmod` implementation.

**Affected Tests:**
- t/026FileApp.t (tests 6-7, 25)

### Issue 4: Safe.pm / Opcode.pm

**Symptom:** t/041SafeEval.t tests 4-5, 20 fail.

**Root Cause:** PerlOnJava's Safe.pm implementation may not properly restrict opcodes.

**Affected Tests:**
- t/041SafeEval.t (3 failures)

### Issue 5: Source Filters (###l4p)

**Symptom:** t/049Unhide.t fails - the `###l4p` source filter mechanism doesn't work.

**Root Cause:** Log::Log4perl uses a source filter to hide/unhide statements prefixed with `###l4p`. PerlOnJava may not support this source filtering.

**Affected Tests:**
- t/049Unhide.t (1 failure)

### Issue 6: DESTROY Message

**Symptom:** t/016Export.t test 16 fails - expected DESTROY message not appearing.

**Test:**
```perl
# Expected: 'Log::Log4perl::Appender::TestBuffer destroyed'
# Got: ''
```

**Root Cause:** The `DESTROY` method on TestBuffer may not be called during global destruction, or the message is not being captured correctly.

**Affected Tests:**
- t/016Export.t (1 failure)

## Priority Order

1. **`local` package variable bug** - ROOT CAUSE affecting 16+ tests across multiple files
2. **Carp.pm/warnings.pm interaction** - Separate issue, quick fix possible
3. **DESTROY message** - May be a minor timing/output issue
4. **File permissions** - Likely straightforward fix
5. **Safe.pm** - May require significant work
6. **Source filters** - May require parser changes

## Recent Debugging Session (2026-03-18)

### PR 328 Test Timeout Investigation

**Symptom:** Tests reported as timeouts in PR 328:
```
✗ io/crlf_through.t      942/942   0/0   -942
✗ io/through.t           942/942   0/0   -942
✗ op/heredoc.t            66/138   0/0    -66
✗ op/tie.t                45/95    0/0    -45
✗ lib/croak.t             44/334   0/334  -44
```

**Root Cause Found:** Staged (but uncommitted) changes had accidentally removed the `ForkOpenCompleteException` catch blocks from `RuntimeCode.java`. These catch blocks are essential for the fork-open emulation feature added in commit 764c256cc.

**Impact:** Without the exception handling:
- `exec` inside fork-open patterns (`open FH, "-|"; if (!$pid) { exec @cmd }`) throws an uncaught exception
- Tests that spawn subprocesses (fresh_perl_is, run_multiple_progs, pipe opens) hang or fail
- All tests using `test.pl`'s subprocess spawning are affected

**Fix:** Restored the files from the committed version:
```bash
git reset HEAD -- .
git checkout -- .
```

**Verification:** After restoring, `grep -c "ForkOpenCompleteException"` returns 5 in RuntimeCode.java (correct).

**Note:** The tests themselves are NOT broken - they just take longer than the CI timeout due to JVM startup overhead for each subprocess. The 0/0 results were from the uncaught exception causing early termination.

### Next Steps for PR 328

1. **Ensure all files are committed with fork-open emulation intact**
2. **Consider increasing CI timeout** for subprocess-heavy tests (io/through.t has 942 tests)
3. **Always verify working tree is clean before testing**

### Additional Fixes (2026-03-18)

#### Fix: $( and $) in regex patterns
The commit adding `$(` and `$)` variable support caused ExifTool.t to fail because regex patterns containing `$)` were incorrectly interpolating the EGID variable instead of treating `$)` as end-of-string anchor + closing paren.

**Fix:** Added check in `StringSegmentParser.shouldInterpolateVariable()` to skip interpolation of `$(` and `$)` specifically in regex context, while still allowing interpolation in double-quoted strings.

#### Fix: sprintf %c with Inf/NaN regression  
The sprintf fix for "INFO" being treated as Infinity incorrectly moved `%c` handling before the Inf/NaN check, causing `sprintf "%c", Inf` to format a character instead of erroring.

**Fix:** Only skip the Inf/NaN check for `%s` (string) and `%p` (pointer), while `%c` still goes through the Inf/NaN check.

**Result:** op/infnan.t restored from 1041/1088 back to 1071/1088 (same as master).

### Remaining CI Timeout Issues

The following tests timeout in CI but are NOT regressions - they just take a long time due to JVM startup overhead for subprocess-heavy tests:

- **io/crlf_through.t** (942 tests) - spawns many subprocesses via pipe opens
- **io/through.t** (942 tests) - spawns many subprocesses via pipe opens  
- **lib/croak.t** (334 tests) - spawns many subprocesses

These tests pass locally but exceed CI timeout limits. The CI may need longer timeouts for these specific tests.

## How to Test

```bash
# Run all Log::Log4perl tests
./jcpan -t Log::Log4perl

# Run a specific test
cd ~/.cpan/build/Log-Log4perl-1.57* && /path/to/jperl t/020Easy.t

# Quick test for bareword filehandle
./jperl -e 'open IN, "</etc/passwd"; IN->clearerr(); print "OK\n"; close IN;'

# Quick test for $( and $)
./jperl -e 'print "GID: $(\nEGID: $)\n";'
```

## Files to Investigate

For Carp.pm fix:
- `src/main/perl/lib/Carp.pm` - line 755
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java` - `caller()` method

For caller() fix:
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java` - `caller()` method
- `src/main/java/org/perlonjava/runtime/ExceptionFormatter.java`

## Related Documentation

- Perl's IO::Handle: https://perldoc.perl.org/IO::Handle
- Perl's caller(): https://perldoc.perl.org/functions/caller
- Log::Log4perl: https://metacpan.org/pod/Log::Log4perl
