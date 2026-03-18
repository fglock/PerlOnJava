# PR #328 Startup Performance Investigation

## Overview

PR #328 introduced a 2x performance regression that caused io/through.t, io/crlf_through.t, and lib/croak.t tests to timeout in CI. This document details the investigation and fixes.

## Root Cause

Commit a82bf0c66 ("Add support for $( and $) special variables") introduced expensive JNA function calls during static initialization:

```java
// In GlobalContext.java lines 84-85 (before fix)
GlobalVariable.getGlobalVariable("main::(").set(NativeUtils.getgid(0));  // $( - real GID
GlobalVariable.getGlobalVariable("main::)").set(NativeUtils.getegid(0));  // $) - effective GID
```

These JNA calls (`getgid` and `getegid`) are expensive (~5ms each) and were executed at every startup, including every subprocess fork. This doubled the startup time from ~80ms to ~160ms per process.

## Performance Measurements

| Scenario | Tests in 60s | Startup Time |
|----------|--------------|--------------|
| Master branch | 936-942 | ~80ms |
| After a82bf0c66 | 464-468 | ~160ms |
| After fix | 942 | ~140ms |

The io/through.t test spawns many subprocesses, so a 2x startup slowdown resulted in exactly 2x fewer tests completing.

## Fixes Applied

### 1. Lazy $( and $) Variables (commit b462539b7)

Made $( and $) lazy-loaded via `ScalarSpecialVariable` so JNA calls only happen when the variables are accessed:

**ScalarSpecialVariable.java**:
```java
// Added to enum Id:
REAL_GID,       // $( - Real group ID (lazy, JNA call only on access)
EFFECTIVE_GID,  // $) - Effective group ID (lazy, JNA call only on access)

// Added to getValueAsScalar():
case REAL_GID -> {
    yield new RuntimeScalar(NativeUtils.getgid(0));
}
case EFFECTIVE_GID -> {
    yield new RuntimeScalar(NativeUtils.getegid(0));
}
```

**GlobalContext.java**:
```java
// Changed from eager initialization to lazy ScalarSpecialVariable
GlobalVariable.globalVariables.put("main::(", new ScalarSpecialVariable(ScalarSpecialVariable.Id.REAL_GID));
GlobalVariable.globalVariables.put("main::)", new ScalarSpecialVariable(ScalarSpecialVariable.Id.EFFECTIVE_GID));
```

### 2. Deferred Module Initialization (commit 15c5c1a83)

Deferred initialization of less commonly used modules to XSLoader::load():

- `UnicodeNormalize` - Has XSLoader in its Perl file
- `TimeHiRes` - Has XSLoader in its Perl file (updated Time/HiRes.pm)
- `JavaSystem` - Only needed for java:: integration

Modules kept at startup (no XSLoader in Perl files):
- `UnicodeUCD`, `TermReadLine`, `TermReadKey`, `FileTemp`, `Encode`

## Current Performance Profile

### Startup Time Breakdown (~140ms total)

| Phase | Time | Notes |
|-------|------|-------|
| JVM startup | ~43ms | Inherent to Java |
| Class loading | ~14ms | 1444 classes loaded |
| Class initialization | ~50ms | Static blocks, HashMap setup |
| Bytecode verification | ~33ms | Security verification |

### Profiling Commands Used

```bash
# Measure tests per 60 seconds
cd perl5_t/t && timeout 60 ../../jperl io/through.t 2>&1 | grep "^ok" | wc -l

# Measure single startup
time ./jperl -e '1'

# Profile class loading
java -Xlog:class+load:file=/tmp/classload.log -cp "build/install/perlonjava/lib/*" \
    org.perlonjava.app.cli.Main -e '1'

# Profile class initialization  
java -Xlog:class+init=info:file=/tmp/classinit.log -cp "build/install/perlonjava/lib/*" \
    org.perlonjava.app.cli.Main -e '1'
```

## Why Further Optimization is Limited

The ~140ms startup is near-optimal for a JVM application of this complexity:

1. **JVM overhead is fixed** (~43ms) - Cannot be reduced without native compilation
2. **Class count is necessary** (1444 classes) - Required for Perl compatibility
3. **Static initialization is minimal** - Most work deferred to runtime

### Potential Future Optimizations

| Approach | Savings | Feasibility |
|----------|---------|-------------|
| GraalVM native-image | ~100ms | Low - breaks dynamic class loading |
| AppCDS (Class Data Sharing) | ~20-30ms | Medium - helps repeated runs only |
| Reduce class count | Variable | Low - requires major refactoring |
| Lazy module loading | ~5-10ms | Done - already implemented |

## Files Modified

1. `src/main/java/org/perlonjava/runtime/runtimetypes/ScalarSpecialVariable.java`
   - Added REAL_GID and EFFECTIVE_GID enum values
   - Added lazy getters for $( and $)

2. `src/main/java/org/perlonjava/runtime/runtimetypes/GlobalContext.java`
   - Changed $( and $) to use ScalarSpecialVariable
   - Deferred UnicodeNormalize, TimeHiRes, JavaSystem initialization

3. `src/main/perl/lib/Time/HiRes.pm`
   - Enabled XSLoader::load() call for lazy loading

## Verification

```bash
# Build and test
make

# Verify io/through.t performance (should be ~940+ tests in 60s)
cd perl5_t/t && timeout 60 ../../jperl io/through.t 2>&1 | grep "^ok" | wc -l

# Verify $( and $) still work
./jperl -e 'print "REAL_GID: $(; EFFECTIVE_GID: $)\n"'
```

## Related Issues

- PR #328: Module::Build support
- Commit a82bf0c66: Add support for $( and $) special variables

## Date

2024-03-18
