# IO::Stringy Fix Plan

## Overview

**Module**: IO::Stringy 2.113 (IO::Scalar, IO::Lines, IO::ScalarArray, IO::InnerFile, IO::WrapTie)  
**Test command**: `./jcpan -t IO::Stringy`  
**Status**: 3/8 pass, 5/8 fail — caused by 3 distinct PerlOnJava bugs

## Test Results Summary

### Current Status: 3/8 test files pass

| Test File | Status | Root Cause |
|-----------|--------|------------|
| t/00-report-prereqs.t | PASS | |
| t/IO_WrapTie.t | PASS | |
| t/simple.t | PASS | |
| t/IO_InnerFile.t | FAIL 0/7 | Bug 3: `File::Temp::tempfile()` OPEN=>0 return value |
| t/IO_Lines.t | FAIL 16/33 | Bug 2: `Symbol::geniosym()` not implemented |
| t/IO_Scalar.t | FAIL 17/39 | Bug 2: `Symbol::geniosym()` not implemented |
| t/IO_ScalarArray.t | FAIL 16/33 | Bug 2: `Symbol::geniosym()` not implemented |
| t/two.t | FAIL 0/3 | Bug 1: `\do { local *FH }` glob identity |

## Bug Details

### Bug 1: `\do { local *FH }` returns same glob every time (CRITICAL)

**Impact**: t/two.t (3/3 fail), any module using the `bless \do { local *GLOB }` idiom  
**Root cause**: In Perl 5, `local *FH` replaces the stash entry with a fresh GV. PerlOnJava
keeps the same `RuntimeGlob` object, just saving/restoring its slots. All references point to
the same Java object, so multiple IO::Scalar handles share state.

**Evidence**:
```
# PerlOnJava: all same address
a: Foo=GLOB(0xff72c13b)  b: Foo=GLOB(0xff72c13b)  c: Foo=GLOB(0xff72c13b)
# Standard Perl: distinct addresses  
a: Foo=GLOB(0x1010ed478)  b: Foo=GLOB(0x1010ed610)  c: Foo=GLOB(0xa5cc29c18)
```

**Fix**: Modify `RuntimeGlob.dynamicSaveState()` / `dynamicRestoreState()` to swap the glob
object in `globalIORefs` instead of modifying the same object's slots:
- `dynamicSaveState()`: Create a NEW `RuntimeGlob`, install it in `globalIORefs`
- `dynamicRestoreState()`: Put the OLD glob back in `globalIORefs`
- Update `EmitOperatorLocal`, `InlineOpcodeHandler.executeLocalGlob`, and
  `executeLocalGlobDynamic` to fetch the new glob from `globalIORefs` after `pushLocalVariable`

**Files**:
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeGlob.java`
- `src/main/java/org/perlonjava/backend/jvm/EmitOperatorLocal.java`
- `src/main/java/org/perlonjava/backend/bytecode/InlineOpcodeHandler.java`

### Bug 2: `Symbol::geniosym()` throws "not implemented"

**Impact**: t/IO_Lines.t, t/IO_Scalar.t, t/IO_ScalarArray.t (tied handle tests skipped)  
**Root cause**: Java-native `Symbol.java` registers `geniosym` as a stub that throws
`PerlJavaUnimplementedException`. This overrides the working pure-Perl implementation in `Symbol.pm`.

**Fix**: Remove the Java `geniosym` registration (line 38 of Symbol.java) and the stub method,
allowing the pure-Perl `Symbol.pm` implementation to handle it.

**Files**:
- `src/main/java/org/perlonjava/runtime/perlmodule/Symbol.java`

### Bug 3: `File::Temp::tempfile()` with `OPEN => 0` returns wrong list

**Impact**: t/IO_InnerFile.t (0/7 run)  
**Root cause**: When `OPEN => 0`, `tempfile()` returns `$path` (single scalar) instead of
`(undef, $path)`. Callers using `(undef, $file) = tempfile(...)` get `$file` as undef.

**Fix**: Change line 243 of File/Temp.pm from `return $path unless $open;` to
`return wantarray ? (undef, $path) : $path unless $open;`

**Files**:
- `src/main/perl/lib/File/Temp.pm`

## Fix Order

1. **Bug 3** (trivial, 1-line Perl fix)
2. **Bug 2** (simple, remove Java stub)
3. **Bug 1** (complex, runtime glob identity change)
4. Run `make` to verify no regressions
5. Re-run `./jcpan -t IO::Stringy` to verify fixes

## Progress Tracking

### Current Status: In progress

### Completed Phases
- [x] Investigation (2025-03-31) — Identified all 3 root causes
