# Unified Caller Stack Implementation

> **Note:** This document has been consolidated into `dev/design/caller_stack_fix_plan.md`.
> See that document for the current plan and status.

## Status: Merged into caller_stack_fix_plan.md

## Branch: `fix/caller-line-numbers`

## Problem Statement

PerlOnJava has two execution paths (JVM bytecode and interpreter) with different stack tracking mechanisms, leading to inconsistent `caller()` results. The most visible symptom is that the same call site can report different (package, line) values depending on when the stack is examined.

## Observed Behavior

When running Moo's croak-locations.t test, two stack dumps from the same `$SIG{__DIE__}` handler show different values for the same logical call site:

**First dump** (8 frames, during initial croak):
```
Frame 6: pkg=main file=LocationTestFile line=18 sub=Moo::Role::apply_roles_to_object
```

**Second dump** (5 frames, after some unwinding):
```
Frame 3: pkg=Elsewhere file=LocationTestFile line=21 sub=Moo::Role::apply_roles_to_object
```

Expected: Both should show `pkg=Elsewhere line=21` (the actual call site).

## Root Cause Analysis

### The Two Execution Paths

1. **JVM Bytecode Path**: Compiled Perl code runs as JVM bytecode. Stack info comes from `ByteCodeSourceMapper.parseStackTraceElement()` which maps tokenIndex → (line, package, subroutine).

2. **Interpreter Path**: Eval code (by default) runs through `BytecodeInterpreter`. Stack info comes from `InterpreterState` frames plus `errorUtil.getSourceLocationAccurate()`.

### The Package Resolution Bug

In `ExceptionFormatter.java` (lines 96-101), the interpreter path uses different package sources based on frame index:

```java
String pkg = (interpreterFrameIndex == 0)
        ? InterpreterState.currentPackage.get().toString()  // Runtime package
        : frame.packageName();                               // Compile-time package
```

| Frame Index | Package Source | Problem |
|-------------|----------------|---------|
| 0 (innermost) | Runtime `currentPackage` | Correct - reflects `package Foo;` statements |
| > 0 (outer) | `frame.packageName()` | **Wrong** - captured at sub definition, not call site |

### Why This Causes Different Results

Consider this code structure:
```perl
my $sub = eval qq{ sub {
package main;                    # Initial package when sub is defined
#line 1 LocationTestFile
...
my $o = $PACKAGE->new;           # Line 18, package main
package Elsewhere;               # Line 19 - runtime package change
...
Moo::Role->apply_roles_to_object($o, ...);  # Line 21, package Elsewhere
}};
```

When the interpreter frame for this sub is created:
- `frame.packageName()` = "main" (package at sub DEFINITION time)
- But runtime package at line 21 is "Elsewhere"

**First dump**: Frame 6 is NOT index 0 (deeper stack with Carp frames) → uses `frame.packageName()` = "main"

**Second dump**: After Carp unwinds, same logical frame is now index 0 → uses `currentPackage` = "Elsewhere"

### Line Number Discrepancy (18 vs 21)

The line lookup uses `floorEntry(pc)` to find the nearest tokenIndex:

```java
int pc = interpreterPcs.get(interpreterFrameIndex);
var entryPc = frame.code().pcToTokenIndex.floorEntry(pc);
```

Different PC values (due to exception handling state changes) can map to different tokenIndex values, yielding different line numbers.

### JVM Path Package Handling

The JVM bytecode path (`ByteCodeSourceMapper`) stores package info at parse/emit time:

```java
public static void saveSourceLocation(EmitterContext ctx, int tokenIndex) {
    // Stores: tokenIndex → (lineNumber, packageId, subroutineId)
    int packageId = getOrCreatePackageId(ctx.symbolTable.getCurrentPackage());
    info.tokenToLineInfo.put(tokenIndex, new LineInfo(lineNumber, packageId, ...));
}
```

This correctly captures the package at each statement. But:
1. Parse-time captures package BEFORE processing the statement
2. Emit-time may have stale package context for subroutine bodies
3. The `floorEntry` lookup may find wrong entries if tokenIndex gaps exist

## Design Goals

1. **Unified Stack Model**: Single source of truth for caller info, used by both paths
2. **Zero Overhead**: Normal code execution should not be affected
3. **Lazy Evaluation**: Only compute caller info when actually needed (`caller()` or exception)
4. **Correct Package Tracking**: Runtime package at call site, not compile-time package

## Solution: Use ByteCodeSourceMapper for Both Paths

### Key Insight

The JVM bytecode path already correctly tracks package at each tokenIndex via `ByteCodeSourceMapper`. The interpreter path has access to tokenIndex (via `pcToTokenIndex`) but doesn't use it to look up the package.

**The fix**: Make the interpreter path look up package from `ByteCodeSourceMapper` using the tokenIndex, instead of using `frame.packageName()`.

### Implementation

#### 1. Add Package Lookup Method to ByteCodeSourceMapper

```java
public static String getPackageAtLocation(String fileName, int tokenIndex) {
    int fileId = fileNameToId.getOrDefault(fileName, -1);
    if (fileId == -1) return null;
    
    SourceFileInfo info = sourceFiles.get(fileId);
    if (info == null) return null;
    
    Map.Entry<Integer, LineInfo> entry = info.tokenToLineInfo.floorEntry(tokenIndex);
    if (entry == null) return null;
    
    return packageNamePool.get(entry.getValue().packageNameId());
}
```

#### 2. Fix ExceptionFormatter Interpreter Path

```java
// Current (buggy):
String pkg = (interpreterFrameIndex == 0)
        ? InterpreterState.currentPackage.get().toString()
        : frame.packageName();  // Compile-time package - WRONG

// Fixed:
String pkg = null;
if (tokenIndex != null) {
    // Look up package from ByteCodeSourceMapper, same as JVM path
    String fileName = frame.code().errorUtil.getFileName();
    pkg = ByteCodeSourceMapper.getPackageAtLocation(fileName, tokenIndex);
}
if (pkg == null) {
    // Fallback to runtime package for innermost frame, else compile-time
    pkg = (interpreterFrameIndex == 0)
            ? InterpreterState.currentPackage.get().toString()
            : frame.packageName();
}
```

### Why This Works

1. **Parse time**: `saveSourceLocation(tokenIndex)` stores (tokenIndex → line, package) for each statement
2. **Package changes**: When `package Foo;` is parsed, subsequent statements get `pkg=Foo`
3. **Interpreter compilation**: Bytecode gets tokenIndex via `pcToTokenIndex` mapping
4. **Runtime lookup**: Both JVM and interpreter paths use the same `ByteCodeSourceMapper` data

### Files to Modify

1. `ByteCodeSourceMapper.java`: Add `getPackageAtLocation()` method
2. `ExceptionFormatter.java`: Use new method for interpreter frames

## Testing

### Quick Verification

```bash
# Test package changes within a subroutine (JVM path)
./jperl -e '
sub test { print "caller: ", (caller(0))[0], " line ", (caller(0))[2], "\n"; }
sub foo {
    main::test();      # should show main
    package XXX;
    main::test();      # should show XXX
}
foo();
'
# Expected output:
# caller: main line 4
# caller: XXX line 6
```

### Moo croak-locations.t Reproduction

This is the primary test case that exposed the bug. Create a test file:

```bash
cat > /tmp/test_croak_moo.pl << 'EOF'
use strict;
use warnings;
use Carp qw(croak);

my $PACKAGE = "LocationTest";
my $code = <<'END_CODE';
BEGIN {
  eval qq{
    package ${PACKAGE}::Role;
    use Moo::Role;
    use Carp qw(croak);
    has attr => (
      is => 'ro',
      default => sub { 0 },
      isa => sub {
        croak "must be true" unless \$_[0];
      },
    );
    1;
  } or die $@;
}

use Moo;
my $o = $PACKAGE->new;
package Elsewhere;
use Moo::Role ();
Moo::Role->apply_roles_to_object($o, "${PACKAGE}::Role");
END_CODE

my $sub = eval qq{ sub {
package $PACKAGE;
#line 1 LocationTestFile
$code
  } };
die "Compile error: $@" if $@;

local $SIG{__DIE__} = sub {
    my $i = 0;
    print STDERR "=== CALLER FRAMES ===\n";
    while (my ($pkg, $file, $line, $sub) = caller($i++)) {
        print STDERR "  Frame $i: pkg=$pkg file=$file line=$line sub=", ($sub // "<none>"), "\n";
    }
};

eval { $sub->(); 1 };
print "Error: $@";
EOF
```

Run the test:
```bash
./jperl /tmp/test_croak_moo.pl
```

**Expected**: All stack frames showing `file=LocationTestFile` should report:
- `pkg=Elsewhere line=21` for the `apply_roles_to_object` call site

**Before fix**: Shows `pkg=main line=18` in first dump, `pkg=Elsewhere line=21` in second dump (inconsistent)

### Full Moo Test Suite

```bash
# Run all Moo tests and count results
./jperl t/moo/*.t 2>&1 | tail -20

# Check specific test count
./jperl t/moo/*.t 2>&1 | grep -E '^(ok|not ok)' | wc -l
```

### Debug Mode

Enable debug output to trace package lookups:
```bash
DEBUG_CALLER=1 ./jperl /tmp/test_croak_moo.pl 2>&1 | grep -E '(Frame|saveSourceLocation|parseStackTraceElement)'
```

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Performance regression from per-call tracking | High | Use lazy reconstruction (Phase 1-2) not eager push/pop |
| Breaking existing caller() behavior | High | Extensive test suite, compare with Perl 5 |
| Complexity in exception unwinding | Medium | Careful handling of finally blocks |
| Thread safety issues | Medium | Use ThreadLocal consistently |

## Related Documents

- `dev/design/caller_package_context.md` - Previous investigation
- `dev/design/moo_support.md` - Moo integration (uses caller heavily)

## Debug Environment Variables

| Variable | Effect |
|----------|--------|
| `DEBUG_CALLER=1` | Enable CallerInfo debug output |
| `JPERL_EAGER_CALLER=1` | Enable push/pop tracking (Phase 4) |

## Appendix: Current Code Flow

### JVM Path
```
caller() 
  → ExceptionFormatter.formatException() 
  → parseStackTraceElement() 
  → ByteCodeSourceMapper.sourceFiles lookup
  → floorEntry(tokenIndex) 
  → LineInfo(line, packageId, subId)
```

### Interpreter Path
```
caller()
  → ExceptionFormatter.formatException()
  → InterpreterState.getStack()
  → for each frame:
      if index == 0: use currentPackage (runtime)
      else: use frame.packageName() (compile-time) ← BUG
      use errorUtil.getSourceLocationAccurate() for file/line
```

### Unified Path (Proposed)
```
caller()
  → ExceptionFormatter.formatException()
  → for each JVM/interpreter frame:
      derive CallSite from source location metadata
      (file, line, package all from same source)
  → return List<CallSite>
```
