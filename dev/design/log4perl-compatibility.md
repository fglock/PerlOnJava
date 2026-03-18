# Log::Log4perl Compatibility Plan

## Overview

This document tracks the work needed to make `./jcpan Log::Log4perl` fully pass its test suite on PerlOnJava.

## Current Status (2026-03-18)

### Test Results

```
Files=73, Tests=695
Failed 8/73 test programs (down from 9)
Failed 23/695 subtests (down from 28)
```

### Failing Tests Summary

| Test File | Failed/Total | Issue Category |
|-----------|--------------|----------------|
| t/016Export.t | 1/16 | DESTROY message |
| t/020Easy.t | 5/21 | Carp.pm undef GLOB reference |
| t/022Wrap.t | 2/5 | caller() stack trace format |
| t/024WarnDieCarp.t | 11/73 | caller() / Carp line numbers |
| t/026FileApp.t | 3/27 | File permissions / substr issues |
| t/041SafeEval.t | 3/23 | Safe.pm / Opcode.pm |
| t/049Unhide.t | 1/1 | Source filter / ###l4p |
| t/051Extra.t | 2/11 | Line number reporting |

## Completed Fixes

### 1. Bareword Filehandle Method Calls (Committed 2026-03-18)

**Problem:** `IN->clearerr()` failed with "Can't locate object method 'clearerr' via package 'IN'"

**Root Cause:** `isGlobalIODefined()` was checking `glob.value instanceof RuntimeIO` but IO handles are stored in `glob.IO`, not `glob.value`.

**Fix:** Changed `isGlobalIODefined()` to check `glob.IO.getDefinedBoolean()` instead.

**Files Changed:**
- `src/main/java/org/perlonjava/runtime/runtimetypes/GlobalVariable.java`

**Commit:** 3d0bf9b59

**Tests Fixed:** Unblocked 15+ tests in t/020Easy.t (from 1 to 16 passing)

### 2. $( and $) Special Variables (Committed 2026-03-18)

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

### 3. AUTOLOAD $AUTOLOAD Variable (Committed Earlier)

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

### 4. Parser/Runtime Fixes (Committed Earlier)

- `sprintf %s` treating "INFO" as Infinity
- `oct("0")` crash
- `splitpath()` returning wrong component
- Newlines between sigil and variable name

## Remaining Issues

### Issue 1: Carp.pm Undefined GLOB Reference

**Symptom:** t/020Easy.t tests 17-21 fail with:
```
Can't use an undefined value as a GLOB reference at jar:PERL5LIB/Carp.pm line 755
```

**Root Cause:** Something in Carp.pm's stack inspection is encountering an undefined glob.

**Affected Tests:**
- t/020Easy.t (tests 17-21)

### Issue 2: caller() Stack Trace Format

**Symptom:** Stack traces from `Carp::shortmess` include internal PerlOnJava frames.

**Example from t/022Wrap.t:**
```
Expected: 'File: 022Wrap.t Line number: 70 package: main trace: at 022Wrap.t line 70'
Got:      'File: 022Wrap.t Line number: 70 package: main trace: Log::Log4perl::Appender::log() called at ... line 1115, ...'
```

**Root Cause:** The `caller()` implementation is exposing internal call frames that Perl would filter out.

**Fix Needed:** Review `ExceptionFormatter.formatException()` and filter out internal frames from Log::Log4perl's perspective.

**Affected Tests:**
- t/022Wrap.t (2 failures)
- t/024WarnDieCarp.t (11 failures) - tests 51-53, 58-62, 67, 69-70
- t/051Extra.t (2 failures) - line number reporting

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

1. **Carp.pm undef GLOB** - Blocks 5 tests in t/020Easy.t
2. **caller() stack trace format** - Affects 15 tests across multiple files
3. **DESTROY message** - May be a minor timing/output issue
4. **File permissions** - Likely straightforward fix
5. **Safe.pm** - May require significant work
6. **Source filters** - May require parser changes

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
