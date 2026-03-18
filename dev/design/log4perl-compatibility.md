# Log::Log4perl Compatibility Plan

## Overview

This document tracks the work needed to make `./jcpan Log::Log4perl` fully pass its test suite on PerlOnJava.

## Current Status (2026-03-18)

### Test Results

```
Files=73, Tests=670
Failed 9/73 test programs
Failed 26/670 subtests
```

### Failing Tests Summary

| Test File | Failed/Total | Issue Category |
|-----------|--------------|----------------|
| t/016Export.t | 1/16 | DESTROY message |
| t/020Easy.t | 20/21 | Bareword filehandle method calls |
| t/022Wrap.t | 2/5 | caller() stack trace format |
| t/024WarnDieCarp.t | 11/73 | caller() / Carp line numbers |
| t/026FileApp.t | 3/27 | File permissions / substr issues |
| t/033UsrCspec.t | 5/17 | Custom cspec / caller() |
| t/041SafeEval.t | 3/23 | Safe.pm / Opcode.pm |
| t/049Unhide.t | 1/1 | Source filter / ###l4p |
| t/051Extra.t | parse error | clearerr on bareword filehandle |

## Completed Fixes

### 1. AUTOLOAD $AUTOLOAD Variable (Committed)

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

### 2. Parser/Runtime Fixes (Committed Earlier)

- `sprintf %s` treating "INFO" as Infinity
- `oct("0")` crash
- `splitpath()` returning wrong component
- Newlines between sigil and variable name

## Remaining Issues

### Issue 1: Bareword Filehandle Method Calls (HIGH PRIORITY)

**Symptom:**
```perl
open IN, "<file";
IN->clearerr();  # Fails: Can't locate object method "clearerr" via package "IN"
```

**Root Cause Analysis:**

When `IN->clearerr()` is parsed, the bareword `IN` is converted to a string `"IN"` and passed to `RuntimeCode.call()`. The code attempts to check if `"IN"` is a defined filehandle using `GlobalVariable.isGlobalIODefined("main::IN")`, but this returns `false`.

Investigation revealed that when a filehandle is opened with `open IN, ...`:
1. A glob entry is created in `globalIORefs` with key `"main::IN"`
2. The glob's `value` field contains another `RuntimeGlob`, not the `RuntimeIO` directly
3. The `isGlobalIODefined()` check looks for `glob.value instanceof RuntimeIO` which fails

**Current Code (incomplete fix in RuntimeCode.java):**
```java
String normalizedGlobName = NameNormalizer.normalizeVariableName(perlClassName, "main");
if (GlobalVariable.isGlobalIODefined(normalizedGlobName)) {
    // This branch is never taken because isGlobalIODefined returns false
    RuntimeGlob glob = GlobalVariable.getGlobalIO(normalizedGlobName);
    RuntimeScalar globRef = glob.createReference();
    args.elements.removeFirst();
    return call(globRef, method, currentSub, args, callContext);
}
```

**Fix Needed:**

Option A: Fix `isGlobalIODefined()` to check the glob's `IO` slot instead of `value`:
```java
public static boolean isGlobalIODefined(String key) {
    RuntimeGlob glob = globalIORefs.get(key);
    if (glob != null) {
        // Check the IO slot directly, not glob.value
        RuntimeScalar ioSlot = glob.getIO();
        return ioSlot != null && ioSlot.value instanceof RuntimeIO;
    }
    return false;
}
```

Option B: In `RuntimeCode.call()`, check both the glob's `value` and `IO` slot.

**Affected Tests:**
- t/020Easy.t (20 failures)
- t/051Extra.t (clearerr call)

**Test Command:**
```bash
./jperl -e 'open IN, "</etc/passwd"; IN->clearerr(); print "OK\n"; close IN;'
```

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
- t/033UsrCspec.t (5 failures)

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

1. **Bareword filehandle method calls** - Blocks 21+ tests, relatively simple fix
2. **caller() stack trace format** - Affects 18 tests across multiple files
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
```

## Files to Investigate

For bareword filehandle fix:
- `src/main/java/org/perlonjava/runtime/runtimetypes/GlobalVariable.java` - `isGlobalIODefined()`
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeGlob.java` - `getIO()`, `setIO()`
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java` - method call dispatch

For caller() fix:
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java` - `caller()` method
- `src/main/java/org/perlonjava/runtime/ExceptionFormatter.java`

## Related Documentation

- Perl's IO::Handle: https://perldoc.perl.org/IO::Handle
- Perl's caller(): https://perldoc.perl.org/functions/caller
- Log::Log4perl: https://metacpan.org/pod/Log::Log4perl
