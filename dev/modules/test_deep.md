# Test::Deep Fix Plan

## Overview

This document tracks all errors found when running `./jcpan -t Test::Deep` and the plan to fix them.

**Module**: Test::Deep 1.205  
**Test command**: `./jcpan -t Test::Deep`

## Test Results Summary

### Current Status: 41/42 test files passing (after Phases 1-6)

Only `t/memory.t` fails due to `weaken` being unimplemented.

| Test File | Status | Notes |
|-----------|--------|-------|
| t/00-report-prereqs.t | PASS | - |
| t/all.t | PASS | Fixed: Phase 1 (overload) + Phase 3 (bitwise & overload) |
| t/any.t | PASS | Fixed: Phase 1 (overload) + Phase 3 (bitwise | overload) |
| t/array.t | PASS | - |
| t/array_each.t | PASS | - |
| t/arraylength.t | PASS | Fixed: Phase 1 (reftype) |
| t/bag.t | PASS | Fixed: Phase 1 (overload) |
| t/bagrecursion.t | PASS | - |
| t/blessed.t | PASS | - |
| t/boolean.t | PASS | - |
| t/cache.t | PASS | - |
| t/circular.t | PASS | Fixed: Phase 1 (overload) |
| t/class.t | PASS | - |
| t/code.t | PASS | - |
| t/deep_utils.t | PASS | Fixed: Phase 1 (reftype REGEXP) |
| t/descend.t | PASS | - |
| t/error.t | PASS | - |
| t/hash.t | PASS | - |
| t/hash_each.t | PASS | - |
| t/hashkeys.t | PASS | Fixed: Phase 1 (reftype) |
| t/ignore.t | PASS | Fixed: Phase 4 (SUPER:: at package level) |
| t/import.t | PASS | - |
| t/isa.t | PASS | Fixed: Phase 1 (isa keyword parsing) |
| t/leaf-wrapper.t | PASS | - |
| t/listmethods.t | PASS | - |
| t/memory.t | **FAIL** | `weaken` unimplemented (known limitation) |
| t/methods.t | PASS | - |
| t/no-clobber-globals.t | PASS | - |
| t/none.t | PASS | Fixed: Phase 1 (overload) + Phase 3 (bitwise | overload) |
| t/notest.t | PASS | - |
| t/notest_extra.t | PASS | - |
| t/notest_withtest.t | PASS | - |
| t/number.t | PASS | - |
| t/reftype.t | PASS | - |
| t/regexp.t | PASS | Fixed: Phase 6 (/= tokenization) |
| t/regexpref.t | PASS | Fixed: Phase 6 (/= tokenization) |
| t/rt78288_blessed_object.t | PASS | Fixed: Phase 1 (isa keyword parsing) |
| t/scalar.t | PASS | Fixed: Phase 1 (reftype) |
| t/scalarref.t | PASS | Fixed: Phase 5 (reftype SCALAR vs REF) |
| t/set.t | PASS | Fixed: Phase 1 (overload) |
| t/shallow.t | PASS | - |
| t/string.t | PASS | - |

---

## Error Categories

### 1. CRITICAL: StackOverflowError in overload stringification

**Affected tests**: t/all.t, t/any.t, t/bag.t, t/circular.t, t/none.t, t/set.t (72 failures total)

**Stack trace pattern** (repeating infinitely):
```
overload at jar:PERL5LIB/overload.pm line 3
Test::Deep::Cmp at .../Test/Deep/Cmp.pm line 69
```

**Root Cause**: `no overloading` pragma is not implemented in PerlOnJava.

The call chain that creates infinite recursion:

1. Code stringifies a `Test::Deep::Cmp` object (which has `use overload '""' => \&string`)
2. `RuntimeScalar.toString()` calls `Overload.stringify(this)` for blessed refs
3. The `""` overload calls `Test::Deep::Cmp::string` which calls `overload::StrVal($self)`
4. `overload::StrVal` is aliased to `overload::AddrRef` which does `no overloading; "$_[0]"`
5. `no overloading` sets `$^H |= 0x01000000` but **PerlOnJava never checks this flag**
6. The `"$_[0]"` stringification triggers `RuntimeScalar.toString()` again -> back to step 2
7. Infinite recursion -> `StackOverflowError`

**Why Perl 5 doesn't have this problem**: In Perl 5, `no overloading` sets `HINT_NO_AMAGIC` on subsequent ops at compile time. When `"$_[0]"` runs, Perl's `sv_2pv_flags()` sees the flag and returns the raw `Class=TYPE(0xADDR)` string without calling the `""` overload handler.

**Additional details**:
- The `HINT_NO_AMAGIC` bit value `0x01000000` collides with `HINT_RE_ASCII` in `Strict.java`
- There is no Java built-in for `overload::StrVal`/`overload::AddrRef`; they rely on the pure-Perl `overloading.pm` which depends on `no overloading` working
- `Scalar::Util::refaddr` has a Java built-in in `ScalarUtil.java` (uses `System.identityHashCode()`), but `overload::StrVal` does not

**Fix (two-phase approach)**:

#### Phase 1 (Quick fix): Java built-in for `overload::StrVal` and `overload::AddrRef`

Create a Java built-in that bypasses overload dispatch, following the `ScalarUtil.java` pattern:

- Add `StrVal` and `AddrRef` methods to the existing `Overload.java` or create a new `OverloadUtil.java`
- These methods call `RuntimeScalar.toStringRef()` directly, bypassing `Overload.stringify()`
- Register in `GlobalContext.java`

Files to change:
- `src/main/java/org/perlonjava/runtime/Overload.java` (or new file)
- `src/main/java/org/perlonjava/runtime/GlobalContext.java`

#### Phase 2 (Proper fix): Implement `no overloading` pragma

1. Add `HINT_NO_AMAGIC` to `Strict.java` using a new unused bit (not `0x01000000`)
2. Create Java-side handler for `overloading.pm` import/unimport
3. At bytecode emission time, when `HINT_NO_AMAGIC` is active, emit stringify operations that bypass overloading
4. This also fixes `Carp::_StrVal` which uses the same pattern

**Priority**: HIGH - blocks 72 test failures and affects any module using overloaded objects

---

### 2. HIGH: `isa` parsed as core keyword instead of imported function

**Affected tests**: t/isa.t (no plan), t/rt78288_blessed_object.t (no plan)

**Error**:
```
Too many arguments for isa at t/isa.t line 12, near ""HASH""
Too many arguments for isa at t/rt78288_blessed_object.t line 8, near "'Foo'"
```

**Root Cause**: The parser treats `isa` as a core operator unconditionally. Test::Deep exports `sub isa { ... }` which accepts 1 or 2 arguments, but the parser resolves `isa("HASH")` as core `isa` (prototype `""` = zero args) before checking for an imported subroutine.

**Call path in parser**:
1. `ParsePrimary.java:141`: `operatorEnabled` switch falls to `default -> true` (isa is not feature-checked)
2. `ParsePrimary.java:154`: `isa` is not in `OVERRIDABLE_OP`, so imported-sub check is skipped
3. `CoreOperatorResolver.java:114`: falls to `parseWithPrototype()`
4. `ParserTables.java:166`: `CORE_PROTOTYPES.get("isa")` returns `""` (zero args)
5. Extra argument `"HASH"` triggers "Too many arguments for isa"

**Fix**:
1. Add `isa` to the feature-checked operators in `ParsePrimary.java:141`:
   ```java
   case "isa" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("isa");
   ```
   Without `use feature 'isa'` or `use v5.36+`, `operatorEnabled` returns false and falls through to sub resolution.
2. Consider removing `isa` from `CORE_PROTOTYPES` in `ParserTables.java` since `isa` is an infix operator (`$obj isa Class`), not a prefix function.

Files to change:
- `src/main/java/org/perlonjava/parser/ParsePrimary.java`
- `src/main/java/org/perlonjava/parser/ParserTables.java`

**Priority**: HIGH - compile-time error prevents tests from running at all

---

### 3. MEDIUM: `Scalar::Util::reftype` returns "" instead of undef for non-references

**Affected tests**: t/arraylength.t (1 fail), t/hashkeys.t (1 fail), t/scalar.t (1 fail), t/scalarref.t (1 fail)

**Error**: Diagnostic message comparison failures. Test::Deep expects `got : undef` but gets `got : ''` because `reftype()` returns empty string instead of undef for non-reference values.

**Root Cause**: In `ScalarUtil.java:118-126`:
```java
default -> "";    // BUG: should return undef (null)
```

**Fix**: Change `default -> ""` to `default -> null` and handle null:
```java
return (type != null ? new RuntimeScalar(type) : new RuntimeScalar()).getList();
```

Files to change:
- `src/main/java/org/perlonjava/runtime/perlmodule/ScalarUtil.java`

**Priority**: MEDIUM - 4 failures, easy fix

---

### 4. MEDIUM: `Scalar::Util::reftype` missing REGEXP mapping for regex references

**Affected tests**: t/deep_utils.t (1 fail)

**Error**:
```
Failed test 'class_base base regexp'
# got: ''
# expected: 'REGEXP'
```

**Root Cause**: `ScalarUtil.java` switch on `scalar.type` has no case for `RuntimeScalarType.REGEX`. The type `REGEX = 100 | REFERENCE_BIT` falls through to `default -> ""`.

**Fix**: Add `case REGEX -> "REGEXP"` to the switch in both:
- `ScalarUtil.java` reftype method
- `Builtin.java` reftype method (for consistency)

Files to change:
- `src/main/java/org/perlonjava/runtime/perlmodule/ScalarUtil.java`
- `src/main/java/org/perlonjava/runtime/Builtin.java`

**Priority**: MEDIUM - easy fix, can be combined with #3

---

### 5. MEDIUM: Lexer `/=` tokenization breaks `qr/x/=~` pattern

**Affected tests**: t/regexp.t (no plan), t/regexpref.t (no plan)

**Error**:
```
Unsupported assignment context: 0 at t/regexp.t line 6, near ";"
```

**Failing code**: `my $xism = qr/x/=~/\(\?\^/ ? "^" : "-xism";`

**Root Cause**: The lexer greedily combines `/` + `=` into the `/=` operator. When parsing `qr/x/`, the closing `/` is part of a `/=` token. The string parser puts `=` back into the remain buffer, but after returning, the expression parser sees `=` (assignment) instead of `=~` (binding operator).

The remain `=` doesn't recombine with the following `~` to form the `=~` operator. The expression is parsed as `qr/x/ = ~(/\(\?\^/ ? "^" : "-xism")` which fails because `qr/x/` is not a valid lvalue.

**Fix**: When `parseRawStringWithDelimiter` puts back remain text, check if combining remain + next token forms a multi-character operator (`=~`, `==`, `=>`). Reconstruct the proper compound token.

Files to change:
- `src/main/java/org/perlonjava/parser/StringParser.java` (remain handling)
- OR `src/main/java/org/perlonjava/lexer/Lexer.java` (tokenization)

**Priority**: MEDIUM - compile-time error prevents 2 test files from running

---

### 6. LOW: `Scalar::Util::weaken` is unimplemented (placeholder)

**Affected tests**: t/memory.t (2 fail)

**Error**:
```
Failed test 'left didn't capture'
Failed test 'right didn't capture'
```

**Root Cause**: `weaken()` in `ScalarUtil.java` is a no-op placeholder. The test creates a weak reference, removes the strong reference, and expects the weak ref to become undef. Since `weaken()` does nothing, the weak ref stays alive.

**Java feasibility note**: Java has `java.lang.ref.WeakReference`, but its semantics differ fundamentally from Perl's. Perl weak refs become `undef` **immediately and deterministically** when the last strong reference is removed (reference counting). Java weak refs are cleared by the GC **non-deterministically** -- the timing is unpredictable and depends on GC pressure. Replicating Perl's exact semantics would require building a reference-counting layer on top of Java's GC in `RuntimeScalar`, which is a significant architectural change. This is feasible but expensive to implement and maintain, and may not be worth it for the small number of affected tests.

Files to change:
- `src/main/java/org/perlonjava/runtime/perlmodule/ScalarUtil.java`
- `src/main/java/org/perlonjava/runtime/RuntimeScalar.java`

**Priority**: LOW - known unimplemented feature (documented in AGENTS.md), only 2 test failures, significant implementation cost

---

### 7. LOW: t/ignore.t failures (likely cascade)

**Affected tests**: t/ignore.t (4 fail)

**Root Cause**: Likely a cascade from the `isa` keyword issue (#2) and/or the `reftype` issues (#3, #4). Test::Deep internally uses `$data->isa("Test::Deep::Cmp")` to identify comparator objects and `_td_reftype()` (which calls `Scalar::Util::reftype`) to decide how to wrap expected values.

**Fix**: Fix issues #2, #3, and #4 first, then re-test.

**Priority**: LOW - expected to resolve after fixing higher-priority issues

---

## Fix Plan (Recommended Order)

### Phase 1: Quick wins (easy, high impact)

| Step | Issue | Files | Expected impact |
|------|-------|-------|-----------------|
| 1a | Fix `isa` keyword parsing (#2) | ParsePrimary.java, ParserTables.java | Unblocks t/isa.t, t/rt78288_blessed_object.t, possibly t/ignore.t |
| 1b | Fix reftype undef + REGEXP (#3, #4) | ScalarUtil.java, Builtin.java | Fixes t/deep_utils.t, t/arraylength.t, t/hashkeys.t, t/scalar.t, t/scalarref.t |
| 1c | Add `overload::StrVal` Java built-in (#1) | Overload.java or new file, GlobalContext.java | Fixes t/all.t, t/any.t, t/bag.t, t/circular.t, t/none.t, t/set.t (72 failures) |

### Phase 2: Medium effort

| Step | Issue | Files | Expected impact |
|------|-------|-------|-----------------|
| 2a | Fix `/=` tokenization after regex (#5) | StringParser.java or Lexer.java | Unblocks t/regexp.t, t/regexpref.t |
| 2b | Implement `no overloading` pragma (#1 proper) | Strict.java, overloading.pm, emit code | General fix for all `no overloading` users (Carp, etc.) |

### Phase 3: Long-term

| Step | Issue | Files | Expected impact |
|------|-------|-------|-----------------|
| 3a | Implement weak references (#6) | ScalarUtil.java, RuntimeScalar.java | Fixes t/memory.t, enables Moo/Moose weak_ref |

## Phase 1 Results (Completed)

Phase 1 fixes applied:
- **1a**: `isa` feature-gated in `ParsePrimary.java` (1 line)
- **1b**: `reftype` returns undef for non-refs, `REGEXP` for regex refs in `ScalarUtil.java` and `Builtin.java`
- **1c**: `overload::AddrRef` rewritten in `overload.pm` to use `Scalar::Util::blessed/reftype/refaddr` (Java built-ins that bypass overload dispatch)
- **New**: `OverloadModule.java` created with Java built-ins for `overload::StrVal`/`AddrRef`

### Before/After Comparison

| Test File | Before | After | Change |
|-----------|--------|-------|--------|
| t/all.t | 12/27 fail | 3/27 fail | -9 |
| t/any.t | 11/25 fail | 2/25 fail | -9 |
| t/arraylength.t | 1/40 fail | **PASS** | fixed |
| t/bag.t | 14/48 fail | **PASS** | fixed |
| t/bagrecursion.t | skip | **PASS** | fixed |
| t/circular.t | 4/42 fail | **PASS** | fixed |
| t/deep_utils.t | 1/3 fail | **PASS** | fixed |
| t/hashkeys.t | 1/28 fail | **PASS** | fixed |
| t/ignore.t | 4/6 fail | 4/6 fail | unchanged |
| t/isa.t | 0/0 no plan | **PASS (56 tests)** | fixed |
| t/listmethods.t | PASS | **PASS** | ok |
| t/memory.t | 2/2 fail | 2/2 fail | unchanged (weaken) |
| t/none.t | 13/30 fail | 5/30 fail | -8 |
| t/regexp.t | 0/0 no plan | 0/0 no plan | unchanged (/= bug) |
| t/regexpref.t | 0/0 no plan | 0/0 no plan | unchanged (/= bug) |
| t/rt78288_blessed_object.t | 0/0 no plan | **PASS** | fixed |
| t/scalar.t | 1/24 fail | **PASS** | fixed |
| t/scalarref.t | 1/21 fail | 1/21 fail | diag mismatch |
| t/set.t | 18/54 fail | **PASS** | fixed |
| t/shallow.t | PASS | **PASS** | ok |

**Summary: 17 failing files -> 7 failing files. ~83 failures resolved.**

### Remaining Failures (17 total across 7 files)

| Test File | Failures | Root Cause |
|-----------|----------|------------|
| t/all.t | 3 | overloaded `&` operator on Test::Deep::Cmp - compare diag issues |
| t/any.t | 2 | overloaded `\|` operator on Test::Deep::Cmp - compare diag issues |
| t/ignore.t | 4 | likely needs investigation - `isa` method resolution or `no overloading` in ov_method |
| t/memory.t | 2 | weaken unimplemented (known) |
| t/none.t | 5 | overloaded `\|` operator interaction - compare diag/actual_ok issues |
| t/regexp.t | 0 (no plan) | `/=` tokenization bug (Phase 2) |
| t/regexpref.t | 0 (no plan) | `/=` tokenization bug (Phase 2) |
| t/scalarref.t | 1 | diagnostic message mismatch |

**Passing: 34/41 test files (83%)**

---

## Phase 2: Implement `no overloading` pragma

### Goal
Implement proper `no overloading` pragma support so that `overload::StrVal` / `overload::AddrRef` work using the standard Perl pattern (`no overloading; "$_[0]"`), and revert the Phase 1 workaround in `overload.pm`.

### Background
In Perl 5, `no overloading` sets `HINT_NO_AMAGIC` at compile time. Ops compiled in that scope have overload dispatch suppressed. In PerlOnJava, the equivalent is to emit different runtime method calls (no-overload variants) when the hint flag is active — following the established `use integer` / `use bytes` pattern.

### Architecture (5 layers, following `use integer` pattern)

1. **Hint flag**: `HINT_NO_AMAGIC = 0x00000010` in `Strict.java` (free bit, avoids `0x01000000` collision with `HINT_RE_ASCII`)
2. **Pragma module**: `OverloadingPragma.java` — Java handler for `no overloading` / `use overloading` (sets/clears the hint bit)
3. **Compiler check**: JVM emitter and bytecode compiler check `isStrictOptionEnabled(HINT_NO_AMAGIC)` at compile time
4. **Opcode/method selection**: Emit no-overload variants of join/concat when flag is active
5. **Runtime methods**: `toStringNoOverload()`, `joinNoOverload()`, `stringConcatNoOverload()` that bypass `Overload.stringify()`

### Implementation Steps

| Step | Description | Files | Status |
|------|-------------|-------|--------|
| 2.1 | Add `HINT_NO_AMAGIC` constant | `Strict.java` | |
| 2.2 | Create `OverloadingPragma.java` | New file + `GlobalContext.java` | |
| 2.3 | Add `toStringNoOverload()` | `RuntimeScalar.java` | |
| 2.4 | Add no-overload join/concat variants | `StringOperators.java`, `OperatorHandler.java` | |
| 2.5 | JVM emitter: check hint, emit no-overload | `EmitOperator.java`, `EmitBinaryOperatorNode.java` | |
| 2.6 | Bytecode compiler: check hint, emit no-overload | `CompileBinaryOperator.java`, `Opcodes.java`, `InlineOpcodeHandler.java` | |
| 2.7 | Update `overloading.pm` bit value | `src/main/perl/lib/overloading.pm` | |
| 2.8 | Revert `overload.pm` workaround | `src/main/perl/lib/overload.pm` | |
| 2.9 | Test with `make && ./jcpan -t Test::Deep` | - | |

### Key Technical Details

- **String interpolation path**: `"$obj"` → parser → join with `""` separator → `StringOperators.join()` → `toString()` → `Overload.stringify()` → infinite recursion
- **Fix**: When `HINT_NO_AMAGIC` is active, emit `joinNoOverload` which calls `toStringNoOverload()` that goes to `toStringRef()` directly for blessed refs
- **Concat path**: `$obj . "x"` → `StringOperators.stringConcat()` → `toString()` → same recursion
- **Scope**: Phase 2a focuses on stringify suppression (join/concat). Phase 2b (future) would extend to all overloaded operators (`+`, `-`, `==`, etc.)

### Affected Operator Sites

| Operation | JVM Emitter | Bytecode Compiler | Runtime Method |
|-----------|-------------|-------------------|----------------|
| String interpolation (`"$x"`) | `EmitOperator.handleSubstr()` → `join` | `CompileBinaryOperator.compileJoinBinaryOp()` → `JOIN` opcode | `StringOperators.join()` |
| Concatenation (`.`) | `EmitOperator.handleConcatOperator()` | `CompileBinaryOperatorHelper` → `CONCAT` opcode | `StringOperators.stringConcat()` |

### Phase 2 Results (Completed 2026-03-30)

`no overloading` pragma implemented. Reverted `overload.pm` workaround to standard Perl 5 `no overloading; "$_[0]"`. Test results identical to Phase 1 (34/42 passing).

Files changed:
- `Strict.java` — added `HINT_NO_AMAGIC = 0x00000010`
- `OverloadingPragma.java` (new) — pragma handler for `no overloading` / `use overloading`
- `GlobalContext.java` — registered `OverloadingPragma.initialize()`
- `RuntimeScalar.java` — added `toStringNoOverload()`
- `StringOperators.java` — added `joinNoOverload()`, `stringConcatNoOverload()`
- `OperatorHandler.java` — registered `joinNoOverload`
- `EmitOperator.java` — check `HINT_NO_AMAGIC` for join and concat
- `Opcodes.java` — `JOIN_NO_OVERLOAD` (394), `CONCAT_NO_OVERLOAD` (395)
- `CompileBinaryOperator.java`, `CompileBinaryOperatorHelper.java` — emit no-overload opcodes
- `InlineOpcodeHandler.java`, `BytecodeInterpreter.java` — execute no-overload opcodes
- `Disassemble.java` — disassembly support
- `overload.pm` — reverted workaround

---

## Phase 3: Overloaded bitwise `&` and `|` operators

### Issue
**Affected tests**: t/all.t (3 fail), t/any.t (2 fail), t/none.t (5 fail) — **10 failures total**

Test::Deep::Cmp uses `use overload '&' => \&_all, '|' => \&_any` so users can write `re("^wi") & re('ne$')` to combine comparators. PerlOnJava's `BitwiseOperators.bitwiseAnd()` / `bitwiseOr()` / `bitwiseXor()` **never check for overloaded operators**. They immediately stringify both operands and perform native bitwise ops.

**Evidence**:
```perl
# Expected: overloaded & combines two regex comparators
cmp_deeply("wine", re("^wi") & re('ne$'), "pass")  # actual_ok should be 1

# Actual: & stringifies both operands and does bitwise AND on the strings
# Result: "Test::Deep::Regexp=HASH(0x042 0 &2)" (garbage from bitwise AND on hex strings)
```

All other binary operators (math: `+`, `-`, `*`, `/`, `%`; comparison: `==`, `<`, `>`, etc.) correctly call `OverloadContext.tryTwoArgumentOverload()`. Only `BitwiseOperators` is missing overload dispatch.

### Fix
Add overload dispatch to `BitwiseOperators.bitwiseAnd()`, `bitwiseOr()`, and `bitwiseXor()` following the same pattern as `MathOperators.add()`:

```java
public static RuntimeScalar bitwiseAnd(RuntimeScalar arg1, RuntimeScalar arg2) {
    int blessId = RuntimeScalarType.blessedId(arg1);
    int blessId2 = RuntimeScalarType.blessedId(arg2);
    if (blessId < 0 || blessId2 < 0) {
        RuntimeScalar result = OverloadContext.tryTwoArgumentOverload(
            arg1, arg2, blessId, blessId2, "(&", "&");
        if (result != null) return result;
    }
    // ... existing implementation ...
}
```

Same pattern for `bitwiseOr` (`(|`, `|`) and `bitwiseXor` (`(^`, `^`).

### Files to change
- `src/main/java/org/perlonjava/runtime/operators/BitwiseOperators.java`

### Priority: HIGH — 10 test failures, straightforward fix

---

## Phase 4: `SUPER::` at package level (outside any sub)

### Issue
**Affected tests**: t/ignore.t (4 fail)

`Test::Deep::Ignore` has a package-level statement:
```perl
my $Singleton = __PACKAGE__->SUPER::new;
```

This calls `SUPER::new` outside any subroutine. PerlOnJava resolves `SUPER::` by looking up `currentSub.value.packageName` in `NextMethod.java:302`, but at package level there's no current sub, so `currentSub.value` is null → NPE:
```
Cannot read field "packageName" because "currentSub.value" is null
```

In Perl 5, `SUPER::` at package level resolves relative to the current `package` declaration.

### Fix
In `NextMethod.superMethod()`, handle the case where `currentSub.value` is null by falling back to the current compile-time package name. Options:
1. Pass the caller's package name as a fallback parameter
2. Check `currentSub.value != null` before accessing `packageName`, and fall back to the caller's package from `CallerStack`

### Files to change
- `src/main/java/org/perlonjava/runtime/runtimetypes/NextMethod.java`

### Priority: HIGH — 4 failures, blocks a commonly-used pattern (`$Singleton = __PACKAGE__->SUPER::new`)

---

## Phase 5: `reftype` returns "REF" instead of "SCALAR" for scalar references

### Issue
**Affected tests**: t/scalarref.t (1 fail)

`Scalar::Util::reftype(\$x)` returns `"REF"` in PerlOnJava but `"SCALAR"` in Perl 5. All scalar references (`\$x`, `\"str"`, `\42`) are stored as `RuntimeScalarType.REFERENCE` and `reftype()` maps this to `"REF"` unconditionally.

In Perl 5, `reftype` distinguishes:
- `\$scalar` → `"SCALAR"` (reference to a plain scalar)
- `\\$ref` → `"REF"` (reference to another reference)
- `\@array` → `"ARRAY"`, `\%hash` → `"HASH"`, etc.

The logic to distinguish these already exists in `RuntimeScalar.toStringRef()`:
```java
case REFERENCE -> {
    if (value instanceof RuntimeScalar scalar) {
        typeName = switch (scalar.type) {
            case REGEX, ARRAYREFERENCE, HASHREFERENCE, CODE, GLOBREFERENCE, REFERENCE -> "REF";
            case GLOB -> "GLOB";
            case VSTRING -> "VSTRING";
            default -> "SCALAR";
        };
    }
}
```

### Fix
In `ScalarUtil.reftype()`, replace the flat `case REFERENCE -> "REF"` with logic that inspects the referent's type:

```java
case REFERENCE -> {
    if (scalar.value instanceof RuntimeScalar inner) {
        yield switch (inner.type) {
            case REGEX, ARRAYREFERENCE, HASHREFERENCE, CODE, GLOBREFERENCE, REFERENCE -> "REF";
            case GLOB -> "GLOB";
            case VSTRING -> "VSTRING";
            default -> "SCALAR";
        };
    }
    yield "REF";
}
```

Also update `Builtin.java` reftype for consistency.

### Files to change
- `src/main/java/org/perlonjava/runtime/perlmodule/ScalarUtil.java`
- `src/main/java/org/perlonjava/runtime/perlmodule/Builtin.java`

### Priority: MEDIUM — 1 failure, easy fix

---

## Phase 6: `/=` tokenization after regex close delimiter

### Issue
**Affected tests**: t/regexp.t (no plan), t/regexpref.t (no plan) — **2 test files blocked**

The code `qr/x/=~/\(\?\^/` is mis-tokenized. The lexer greedily combines the `/` closing `qr/x/` with the following `=` to form a `/=` (divide-assign) token. Then `=` is put back into the remain buffer, but it doesn't recombine with `~` to form `=~` (binding operator).

Result: parsed as `qr/x/ = ~(/\(\?\^/ ? ...)` instead of `qr/x/ =~ /\(\?\^/`.

### Fix
When `parseRawStringWithDelimiter` puts back remain text that starts with `=`, check if combining it with the following character forms a multi-character operator (`=~`, `==`, `=>`). If so, reconstruct the compound token.

Alternative: fix the lexer to not greedily form `/=` after a regex close delimiter, since `/` in that position is unambiguously the regex closing delimiter.

### Files to change
- `src/main/java/org/perlonjava/frontend/parser/StringParser.java` (remain handling)
- OR `src/main/java/org/perlonjava/frontend/lexer/Lexer.java` (tokenization)

### Priority: MEDIUM — compile-time error blocks 2 test files from running at all

---

## Remaining Failures Summary (excluding weaken)

| Phase | Issue | Tests | Failures | Priority |
|-------|-------|-------|----------|----------|
| 3 | Overloaded `&`/`\|` not dispatched | t/all.t, t/any.t, t/none.t | 10 | HIGH |
| 4 | `SUPER::` NPE at package level | t/ignore.t | 4 | HIGH |
| 5 | `reftype(\$x)` returns "REF" not "SCALAR" | t/scalarref.t | 1 | MEDIUM |
| 6 | `/=` tokenization after regex | t/regexp.t, t/regexpref.t | 2 files blocked | MEDIUM |
| — | `weaken` unimplemented | t/memory.t | 2 | LOW (known) |

**Expected outcome**: Fixing phases 3-6 should bring Test::Deep to **41/42 passing** (only t/memory.t remaining due to `weaken`).
