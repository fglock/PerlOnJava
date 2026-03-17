# Fix caller() Package Context for Inlined Code

## TL;DR (2026-03-17)

**Problem**: `caller()` returns empty or wrong package for many stack frames.

**Root Cause Found**: `ByteCodeSourceMapper.saveSourceLocation()` is called during **parsing** 
(in `StatementResolver.parseStatement()`), but subroutines compile to **separate Java classes** 
during the emit phase. The source location data is stored under the main file's class name, 
but stack traces show the subroutine's class name → lookup fails.

**Example**: Sub/Quote.pm
- Main file code stored under: `org/perlonjava/anon316`
- Subroutine `quote_sub` compiles to: `org/perlonjava/anon401`
- Stack trace shows `anon401` → no entry found → empty package returned

**Proposed Fix**: See "Issue 1: Subroutines Compile to Separate Classes" in Open Issues section.

---

## Overview

This document describes a feature to fix `caller()` returning incorrect package information when code is compiled in one eval context but executed from another. This affects Moo's `croak-locations.t` tests 15, 28, and 29.

## Problem Statement

When Sub::Quote compiles inlined code (e.g., Moo constructors), the code is compiled in one package context but may be called from a different package. Currently, `caller()` returns the **compile-time package** instead of the **runtime caller package**.

**Example from test failure:**
```
caller(2): pkg=LocationTest::_A001 file=(eval 1576) line=9   # WRONG
caller(3): pkg=Elsewhere file=LocationTestFile line=8        # Correct
```

Frame 2 shows `_A001` (test 4's package where code was compiled) but should show `_A002` (test 15's package where `wrap_new` calls `new()`).

## Current Status

- **Moo croak-locations.t**: 26/29 passing (tests 15, 28, 29 fail)
- **Branch**: `fix/line-directive-unquoted` (PR #325)
- **Root Cause**: Identified and fix in progress

## Root Cause Analysis (2026-03-17)

### Problem 1: Shared Eval Filename Across Multiple Runtime Evals

**Critical Discovery**: When an `eval qq{...}` is inside a loop, ALL iterations share the **same** eval filename!

```perl
for my $n (1..15) {
    eval qq{ package Test::$n; ... };  # All use (eval 228)!
}
```

Debug output proves this:
```
=== Starting test 1 ===
DEBUG caller store: file=(eval 227) tokenIndex=1 pkg=main line=2
DEBUG caller store: file=(eval 227) tokenIndex=15 pkg=LocationTest::_A001 line=1
=== Starting test 2 ===
DEBUG caller store: file=(eval 227) tokenIndex=1 pkg=main line=2
DEBUG caller store: file=(eval 227) tokenIndex=15 pkg=LocationTest::_A002 line=1
=== Starting test 3 ===
DEBUG caller store: file=(eval 227) tokenIndex=15 pkg=LocationTest::_A003 line=1
...
```

**Why this happens**: The eval filename `(eval N)` is assigned at **compile-time**, not runtime. The counter `EmitterMethodCreator.classCounter` is incremented when the eval operator is parsed, not when it's executed.

### Problem 2: TokenIndex Collision with floorEntry()

`ByteCodeSourceMapper` was keyed by `(filename, tokenIndex)`:
- Multiple evals share the same filename `(eval 227)`
- Their tokenIndex values overlap (all start around tokenIndex 1, 15, etc.)
- `floorEntry(tokenIndex)` returns the **last stored entry** which may be from a different test

Example collision:
```
Test 4 stores: (eval 227), tokenIndex=15 → pkg=_A004
Test 15 stores: (eval 227), tokenIndex=15 → pkg=_A015 (overwrites)
Test 15 queries: tokenIndex=61, floorEntry returns key=59 → pkg=_A004 (STALE!)
```

### Problem 3: Eval Uses Interpreter by Default

```java
// RuntimeCode.java
public static final boolean EVAL_USE_INTERPRETER =
    System.getenv("JPERL_EVAL_NO_INTERPRETER") == null;  // TRUE by default
```

When `EVAL_USE_INTERPRETER=true`:
- Eval code runs through `BytecodeInterpreter.execute()`
- No `org.perlonjava.anon*` class frame appears in JVM stack trace
- ExceptionFormatter handles this via `InterpreterState.getStack()`

When `EVAL_USE_INTERPRETER=false` (forced JVM compilation):
- Eval code runs as a normal class
- `org.perlonjava.anon8` appears in stack trace
- Package lookup works correctly with the class-name-based fix

### Problem 4: Frame Skip in caller()

```java
// RuntimeCode.java:1506-1509
// Skip the first frame which is the caller() builtin itself
if (stackTraceSize > 0) {
    frame++;
}
```

This skip assumes the first frame is the `caller()` builtin, but `ExceptionFormatter` already filters to only Perl-relevant frames. The actual first frame is the eval code itself.

## Implementation: Fix ByteCodeSourceMapper

### Solution: Key by Java Class Name Instead of Filename

Each compilation creates a unique Java class name (`org/perlonjava/anon123`). By keying on class name:
- Each compiled class has its own mapping
- No collision between different compilations
- Lookup uses stack trace's actual class name

**New data structure:**
```java
// OLD: Maps filename → tokenIndex → LineInfo
private static final Map<Integer, SourceFileInfo> sourceFiles = new HashMap<>();

// NEW: Maps className → tokenIndex → LineInfo  
private static final Map<String, ClassSourceInfo> classSourceInfo = new HashMap<>();
```

**saveSourceLocation changes:**
```java
public static void saveSourceLocation(EmitterContext ctx, int tokenIndex) {
    // Get unique class name for this compilation
    String className = ctx.javaClassInfo != null 
        ? ctx.javaClassInfo.javaClassName 
        : "file:" + ctx.compilerOptions.fileName;  // fallback
    
    ClassSourceInfo info = classSourceInfo.computeIfAbsent(className, ClassSourceInfo::new);
    
    // Store with file name included in LineInfo for display
    info.tokenToLineInfo.put(tokenIndex, new LineInfo(
        ctx.errorUtil.getLineNumber(tokenIndex),
        getOrCreatePackageId(ctx.symbolTable.getCurrentPackage()),
        getOrCreateFileId(ctx.errorUtil.getFileName()),  // for display
        getOrCreateSubroutineId(subroutineName)
    ));
}
```

**parseStackTraceElement changes:**
```java
public static SourceLocation parseStackTraceElement(StackTraceElement element, ...) {
    // Look up by Java class name (unique per compilation)
    String className = element.getClassName().replace('.', '/');
    ClassSourceInfo info = classSourceInfo.get(className);
    
    // Fallback to filename-based lookup for interpreter frames
    if (info == null) {
        String fileKey = "file:" + element.getFileName();
        info = classSourceInfo.get(fileKey);
    }
    
    // ... rest of lookup using floorEntry ...
}
```

### Key Design Decisions

1. **Fallback mechanism**: When `ctx.javaClassInfo` is null (during parsing before class generation), fall back to `"file:" + compilerOptions.fileName` as the key. This maintains backward compatibility.

2. **Use compilerOptions.fileName, not errorUtil.getFileName()**: The `#line` directive changes `errorUtil.getFileName()` but not `compilerOptions.fileName`. Stack traces use the latter, so we must match it.

3. **Store filename in LineInfo**: The source filename (for error display) comes from `errorUtil.getFileName()` which respects `#line` directives. We store both the class key and the display filename.

## Verification Commands

```bash
# Test with JVM compilation (bypasses interpreter, tests class-name keying)
JPERL_EVAL_NO_INTERPRETER=1 DEBUG_CALLER=1 jperl -e '
eval q{
    package TestPkg;
    my @c = caller(0);
    print "caller(0): pkg=$c[0] file=$c[1] line=$c[2]\n";
};
'

# Run full croak-locations.t
cd ~/.cpan/build/Moo-2.005005-1 && jperl t/croak-locations.t

# Verify no regressions
make
```

## Files Changed

| File | Changes |
|------|---------|
| `backend/jvm/ByteCodeSourceMapper.java` | Key by className instead of filename |
| `runtime/runtimetypes/ExceptionFormatter.java` | Debug output (temporary) |

## Open Issues

### Issue 1: Subroutines Compile to Separate Classes (CRITICAL - 2026-03-17)

**Discovery**: Each Perl file compiles to MULTIPLE Java classes:
- Main file code → `anon316` (package decls, use statements, top-level code)
- Each subroutine → separate class (`anon394`, `anon395`, `anon401`, etc.)

**The problem**: `saveSourceLocation()` is ONLY called from `StatementResolver.parseStatement()`:

```java
// StatementResolver.java:43
ByteCodeSourceMapper.saveSourceLocation(parser.ctx, parser.tokenIndex);
```

This is called during **parsing**, when `ctx.javaClassInfo.javaClassName` is the main file's class. When subroutines are later compiled to their own classes (in `EmitSubroutine.java`), the entries were already stored under the wrong class name.

**Debug evidence**:
```
# Stored during parsing (all under main file's class):
DEBUG saveSourceLocation: javaClassInfo=org/perlonjava/anon316 tokenIndex=660 pkg=Sub::Quote file=Sub/Quote.pm

# Stack trace shows subroutine's class:
DEBUG parseStackTraceElement: className=org/perlonjava/anon401 file=Sub/Quote.pm tokenIndex=710
DEBUG parseStackTraceElement: no info found, returning empty pkg  ← LOOKUP FAILS!
```

**Why subroutines get separate classes** (EmitSubroutine.java:146-172):
```java
// Create the new method context
JavaClassInfo newJavaClassInfo = new JavaClassInfo();  // NEW class!
EmitterContext subCtx = new EmitterContext(
    newJavaClassInfo,  // Subroutine gets its own class
    newSymbolTable,
    ...
);
Class<?> generatedClass = EmitterMethodCreator.createClassWithMethod(subCtx, node.block, ...);
// subCtx.javaClassInfo.javaClassName is now e.g. "org/perlonjava/anon401"
```

**Key insight**: There's a timing mismatch:
1. **Parse time**: `saveSourceLocation()` is called with the main file's class name
2. **Emit time**: Subroutines compile to NEW classes that weren't tracked

### Proposed Solution Options

**Option A: Store under both class name AND file name**
- Simple: always ALSO store under `"file:" + filename` key
- Lookup tries class name first, falls back to file
- Pro: Minimal change, quick fix
- Con: Some data duplication

**Option B: Call saveSourceLocation during emit phase**
- Add calls to `saveSourceLocation()` in `EmitSubroutine.emitSubroutine()` and similar
- Use `subCtx.javaClassInfo.javaClassName` (the subroutine's actual class)
- Pro: Architecturally correct, each class has its own entries
- Con: More invasive, need to ensure tokenIndex values are correct in emit context

**Option C: File-centric data model**
- Change structure: `Map<filename, Map<tokenIndex, LineInfo>>` 
- Ignore class names entirely, use only filename + tokenIndex
- Pro: Simpler mental model
- Con: May have issues with `(eval N)` filename collisions (original problem)

**Recommended: Option B** - Call saveSourceLocation during emit with correct class context

### Issue 2: Interpreter Path Frame Handling

When `EVAL_USE_INTERPRETER=true` (default), eval frames go through `InterpreterState` instead of JVM class frames. The fix for `ByteCodeSourceMapper` doesn't help this path.

**Status**: Need to investigate `InterpreterState.getStack()` and `frame.packageName()`.

### Issue 3: caller() Frame Skip Logic

The `frame++` on line 1508 of RuntimeCode.java may be incorrect. With the filtered stack from ExceptionFormatter, the first frame is already the Perl code, not the caller() builtin.

**To verify**: Test if removing the skip fixes the frame offset issue.

### Issue 4: Lazy Compilation Package Capture

In `SubroutineParser.java:789`:
```java
filteredSnapshot.setCurrentPackage(parser.ctx.symbolTable.getCurrentPackage(), ...);
```

This captures the package at **parse time**, not runtime. For lazily-compiled subs, this may be wrong.

## Progress Tracking

### Current Status: Phase 5 - IMPLEMENTED (2026-03-17)

### Final Solution: Option B Simplified

Instead of a complex AST visitor, the fix was much simpler: modify `setDebugInfoLineNumber()` 
to also call `saveSourceLocation()`. This function is already called for each statement during 
bytecode emission, so it naturally has the correct context:

```java
// ByteCodeSourceMapper.java
static void setDebugInfoLineNumber(EmitterContext ctx, int tokenIndex) {
    Label thisLabel = new Label();
    ctx.mv.visitLabel(thisLabel);
    ctx.mv.visitLineNumber(tokenIndex, thisLabel);
    
    // Also save source location during emit - this ensures subroutine statements
    // are saved with the correct package context from the emit-time symbol table
    saveSourceLocation(ctx, tokenIndex);
}
```

**Why this works:**
1. `setDebugInfoLineNumber()` is called from `EmitBlock.java` and `EmitForeach.java` for each statement
2. During emit, `ctx.symbolTable.getCurrentPackage()` has the correct package for subroutine bodies
3. The existing filename-based keying still works because the emit-time call overwrites the parse-time entry with the correct package
4. No changes to data structures required - simpler and safer

### Investigation Timeline

**Phase 1: Initial Hypothesis**
- Suspected: compile-time vs runtime package capture
- Reality: Much deeper issue with class compilation model

**Phase 2: Discovered Eval Filename Collision**
- Multiple runtime evals share same `(eval N)` filename
- Fixed by keying ByteCodeSourceMapper by class name instead of filename
- Worked for JVM-compiled eval (`JPERL_EVAL_NO_INTERPRETER=1`)

**Phase 3: Discovered Interpreter Path Issue**
- Default path uses interpreter, bypasses ByteCodeSourceMapper
- Frames handled by `InterpreterState.getStack()` instead

**Phase 4: Discovered Subroutine Class Mismatch**
- Root cause: `saveSourceLocation()` called at parse time, not emit time
- All entries stored under main file's class name
- Subroutines compile to SEPARATE classes that have no entries
- This affects ALL Perl files, not just eval contexts

**Phase 5: Implemented Simple Fix (2026-03-17)**
- Modified `setDebugInfoLineNumber()` to also call `saveSourceLocation()`
- This ensures source locations are saved during emit with correct package context
- All unit tests pass
- No data structure changes required

### Completed
- [x] Identified eval filename collision issue
- [x] Identified interpreter path uses different frame handling
- [x] **ROOT CAUSE FOUND**: saveSourceLocation called at wrong time
- [x] **IMPLEMENTED**: Added saveSourceLocation call to setDebugInfoLineNumber
- [x] All unit tests pass

### Files Changed
- `ByteCodeSourceMapper.java`: Added `saveSourceLocation()` call to `setDebugInfoLineNumber()`

### Open Issues (for future work)

### Issue 2: Interpreter Path Frame Handling

When `EVAL_USE_INTERPRETER=true` (default), eval frames go through `InterpreterState` instead of JVM class frames. The fix for `ByteCodeSourceMapper` doesn't help this path.

**Status**: Need to investigate `InterpreterState.getStack()` and `frame.packageName()`.

### Issue 3: caller() Frame Skip Logic

The `frame++` on line 1508 of RuntimeCode.java may be incorrect. With the filtered stack from ExceptionFormatter, the first frame is already the Perl code, not the caller() builtin.

**To verify**: Test if removing the skip fixes the frame offset issue.

### Issue 4: Lazy Compilation Package Capture

In `SubroutineParser.java:789`:
```java
filteredSnapshot.setCurrentPackage(parser.ctx.symbolTable.getCurrentPackage(), ...);
```

This captures the package at **parse time**, not runtime. For lazily-compiled subs, this may be wrong.

---

## Issue 5: croak-locations.t Tests 28-29 (Investigation 2026-03-17)

### Problem Summary

Two tests in Moo's `croak-locations.t` fail with incorrect caller information:

| Test | Description | Expected | Got |
|------|-------------|----------|-----|
| 28 | Moo::Role::create_class_with_roles - default fails isa | LocationTestFile line 21 | LocationTestFile line 18 |
| 29 | Method::Generate::DemolishAll - user croak | LocationTestFile line 9 | (eval N) line 36 |

### Test 28 Deep Dive

**Test code structure:**
```perl
1:  BEGIN {
2:    eval qq{
3:      package ${PACKAGE}::Role;
4:      use Moo::Role;
...
14:   } or die $@;
15: }
16: 
17: use Moo;
18: my $o = $PACKAGE->new;        # ← PerlOnJava reports this line
19: package Elsewhere;
20: use Moo::Role ();
21: Moo::Role->apply_roles_to_object($o, "${PACKAGE}::Role");  # ← Should report this line
```

**Comparing stack traces (caller frames at croak time):**

| Frame | Perl | PerlOnJava |
|-------|------|------------|
| 5 | pkg=Elsewhere file=LocationTestFile **line=21** | pkg=main file=LocationTestFile **line=18** |

**Key observations:**
1. PerlOnJava reports line 18 (`$PACKAGE->new`) instead of line 21 (`apply_roles_to_object`)
2. PerlOnJava reports package `main` instead of `Elsewhere`
3. The offset is exactly 3 lines (18 vs 21)
4. In a SECOND stack dump during error handling, PerlOnJava correctly shows line 21

### Investigation Results

**Basic caller() works correctly:**
```perl
package Before;
main::test();  # caller correctly reports "Before"

package After;
main::test();  # caller correctly reports "After"
```

**Eval with package changes works correctly:**
```perl
eval q{
  package ChangedPkg;
  main::test();  # caller correctly reports "ChangedPkg"
};
```

**Issue is specific to Sub::Quote generated code:**
When Sub::Quote generates code at runtime (used by Moo for constructors/accessors), the caller frame information appears stale.

### Hypotheses

**Hypothesis A: Stale tokenIndex in Generated Code**

When Sub::Quote compiles code at runtime via `eval`, the tokenIndex stored in bytecode LineNumberTable may reference the wrong position. The generated code's LineNumberTable entries may be inheriting stale context from the compilation environment.

**Hypothesis B: Package Not Updated for Runtime-Generated Frames**

The `package Elsewhere;` statement at line 19 changes the package at compile time, but when Sub::Quote generates code, it may be capturing the package context from before this change.

**Hypothesis C: Deferred Compilation Timing**

Sub::Quote uses Sub::Defer to lazily compile subs. When the sub is finally compiled (at first call), it may capture the wrong source location context.

### Test Script for Reproduction

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

# Compare:
echo "=== Perl ===" && perl /tmp/test_croak_moo.pl 2>&1
echo "=== PerlOnJava ===" && ./jperl /tmp/test_croak_moo.pl 2>&1
```

### Next Steps

1. **Trace Sub::Quote compilation**: Add debug output to see what tokenIndex/package is captured when quote_sub compiles code

2. **Check ByteCodeSourceMapper entries**: When the generated code runs, what entries exist in `sourceFiles` and what does `floorEntry` return?

3. **Compare interpreter vs JVM paths**: Test with `JPERL_EVAL_NO_INTERPRETER=1` to see if the issue is in interpreter frame handling

4. **Investigate "eager" caller capture**: As suggested, consider capturing caller info (filename, line, package) at CALL TIME rather than looking it up lazily from bytecode metadata

5. **Check Sub::Defer's undefer_sub**: The first call to a deferred sub triggers compilation. This may be affecting what source location gets stored.

### Potential Fix Approaches

**Approach A: Eager Caller Info Capture**

Instead of storing tokenIndex in bytecode and looking up at caller() time, capture the actual (file, line, package) tuple when each call is made:

```java
// Pseudo-code for method call emission
mv.visitLdcInsn(currentFileName);
mv.visitLdcInsn(currentLineNumber);
mv.visitLdcInsn(currentPackage);
mv.visitMethodInsn(INVOKESTATIC, "CallerContext", "push", ...);
// ... actual method call ...
mv.visitMethodInsn(INVOKESTATIC, "CallerContext", "pop", ...);
```

**Approach B: Fix Sub::Quote Source Location**

Ensure Sub::Quote's generated eval code stores correct source location in its bytecode. May need to pass `#line` directives into the generated code.

**Approach C: Use Runtime Package Tracking**

For the package mismatch, ensure the runtime package (tracked by SET_PACKAGE opcode) is used instead of compile-time package for caller() lookups in generated code.

### Test 29 Notes

Test 29 (`Method::Generate::DemolishAll - user croak`) has a different issue - the error message contains `(eval N) line 36` instead of the actual source file. This is likely related to how DEMOLISH is called from generated destructor code. Since DESTROY is not fully supported in PerlOnJava, this test may be expected to fail.

## Related Documents

- `dev/design/moo_support.md` - Moo integration progress
- PR #325 - Current branch with #line directive fix

## Debug Environment Variables

| Variable | Effect |
|----------|--------|
| `DEBUG_CALLER=1` | Enable ByteCodeSourceMapper debug output |
| `JPERL_EVAL_NO_INTERPRETER=1` | Force JVM compilation for eval (bypass interpreter) |
| `JPERL_SHOW_FALLBACK=1` | Show when interpreter fallback is triggered |
| `JPERL_ASM_DEBUG=1` | ASM bytecode debugging |
