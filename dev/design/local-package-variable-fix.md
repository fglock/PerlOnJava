# Fix Plan: `local` Package Variable Bug

## Problem Statement

When `local $Foo::X` is executed from outside package `Foo`, subroutines inside `Foo` that access `$X` via the short name don't see the localized value.

### Reproduction

```perl
package Foo;
our $X = 0;
sub check { print "X=$X\n"; }  # Uses short name

package main;
local $Foo::X = 1;
Foo::check();  # jperl: "X=0", Perl: "X=1"
```

### Important Constraint: `our` Aliases Persist Across Package Changes

The `our` declaration creates a **lexical** alias that must persist even when the package changes:

```perl
our $x = 10;        # Creates alias $x -> $main::x
package ZZZ;
print $x;           # Must still print 10 (referring to $main::x)
```

This already works in jperl. The fix must preserve this behavior - the `our` variable's **target package** is fixed at declaration time, not at access time.

Combined test case (currently broken):
```perl
our $x = 10;
sub check { print "x=$x\n"; }
package ZZZ;
{
    local $main::x = 99;
    main::check();  # jperl: "x=10", Perl: "x=99"
}
```

### Impact

- Breaks `$Carp::CarpLevel` (affects error reporting in all modules using Carp)
- Affects 16+ Log4perl tests
- Likely affects many CPAN modules that use `local $Module::Variable` pattern

## Root Cause Analysis

### Current Implementation

1. **`our` declaration** (`our $X` in package Foo):
   ```java
   int reg = addVariable(varName, "our");
   emit(Opcodes.LOAD_GLOBAL_SCALAR);  // Load from symbol table
   emitReg(reg);                       // Store in register
   emit(nameIdx);                      // Using name "Foo::X"
   ```

2. **Subsequent access** (`$X` in subroutine):
   ```java
   if (hasVariable(varName)) {
       return getVariableRegister(varName);  // Returns cached register!
   }
   ```

3. **`local` execution** (`local $Foo::X = 1`):
   - Creates a new RuntimeScalar object
   - Replaces the entry in GlobalVariable.globalVariables map
   - The subroutine's cached register still points to the OLD object

### Why Perl Works Differently

In Perl, `our` creates a lexical alias to the **glob slot**, not to the scalar value. When `local` replaces the scalar in the glob slot, the alias still points to the slot (which now contains the new value).

In jperl, `our` caches a **reference to the scalar object** in a register. When `local` puts a new object in the symbol table, the register still holds the old reference.

## Proposed Solution

### Option A: Re-fetch `our` Variables on Every Access (Recommended)

**Approach:** When accessing an `our` variable inside a subroutine, always emit `LOAD_GLOBAL_*` instead of using the cached register.

**Changes Required:**

1. **Track `our` variable declarations separately from lexicals**
   - Modify `ScopedSymbolTable` to track `our` declarations differently
   - Store the normalized global name with each `our` declaration

2. **Modify variable access emission**
   - In `BytecodeCompiler.visit(OperatorNode)` for `$` sigil:
   - If variable is `our`-declared, emit `LOAD_GLOBAL_SCALAR` instead of register access
   - Same for `@` and `%` sigils

3. **Affected files:**
   - `src/main/java/org/perlonjava/frontend/semantic/ScopedSymbolTable.java`
   - `src/main/java/org/perlonjava/backend/bytecode/BytecodeCompiler.java`
   - `src/main/java/org/perlonjava/backend/jvm/EmitVariable.java`

**Pros:**
- Semantically correct - matches Perl behavior exactly
- Simple conceptually

**Cons:**
- Performance impact: extra indirection on every `our` variable access
- May need optimization pass later (e.g., caching when provably safe)

### Option B: Make GlobalVariable Entries Indirect References

**Approach:** Instead of storing RuntimeScalar directly in globalVariables map, store an indirection object that holds the current scalar.

**Changes Required:**

1. **Create `GlobalScalarSlot` class:**
   ```java
   public class GlobalScalarSlot {
       private RuntimeScalar current;
       public RuntimeScalar get() { return current; }
       public void set(RuntimeScalar s) { current = s; }
   }
   ```

2. **Modify `GlobalVariable.globalVariables`:**
   - Store `GlobalScalarSlot` instead of `RuntimeScalar`
   - All accesses go through the slot

3. **Modify `local` implementation:**
   - `local` calls `slot.set(newScalar)` instead of replacing map entry

**Pros:**
- `our` variables automatically see changes (no emission changes needed)
- Potentially better performance (register still valid)

**Cons:**
- Significant refactoring of GlobalVariable infrastructure
- Breaking change to RuntimeScalar usage patterns
- More complex memory model

### Option C: Hybrid - Invalidate Cached Registers on `local`

**Approach:** Track which registers hold `our` variable references. When `local` is executed, invalidate those registers so they're re-fetched.

**Cons:**
- Complex to implement correctly
- Cross-cutting concern (local affects unrelated code)
- Hard to handle dynamic scopes correctly

## Recommended Implementation: Option A

Option A is recommended because:
1. Conceptually simple and correct
2. Minimal risk of introducing new bugs
3. Performance impact is acceptable for correctness
4. Can be optimized later if profiling shows it's a bottleneck

## Detailed Implementation Plan

### Phase 1: Modify Symbol Table (Low Risk)

**File:** `src/main/java/org/perlonjava/frontend/semantic/ScopedSymbolTable.java`

**Key Insight:** The global name must be captured at `our` declaration time, not at access time. This ensures that after `package ZZZ;`, the `$x` still refers to `$main::x` (the package where `our $x` was declared).

1. Add new tracking for `our` declarations:
   ```java
   // Map from short variable name to normalized global name (captured at declaration time)
   private Map<String, String> ourVariableGlobalNames = new HashMap<>();
   
   public void addOurVariable(String varName, String globalName) {
       ourVariableGlobalNames.put(varName, globalName);
   }
   
   public String getOurVariableGlobalName(String varName) {
       return ourVariableGlobalNames.get(varName);
   }
   
   public boolean isOurVariable(String varName) {
       return ourVariableGlobalNames.containsKey(varName);
   }
   ```

2. Modify `addVariable()` to populate this map when `declarationType.equals("our")`

### Phase 2: Modify Bytecode Compiler (Medium Risk)

**File:** `src/main/java/org/perlonjava/backend/bytecode/BytecodeCompiler.java`

1. In `visit(OperatorNode node)` for sigil operators (`$`, `@`, `%`):

   Current code path for variable access:
   ```java
   if (hasVariable(varName)) {
       int reg = getVariableRegister(varName);
       // Use register directly
   }
   ```

   Modified code path:
   ```java
   if (hasVariable(varName)) {
       String globalName = symbolTable.getOurVariableGlobalName(varName);
       if (globalName != null) {
           // It's an 'our' variable - always fetch from global
           int reg = allocateRegister();
           int nameIdx = addToStringPool(globalName);
           emit(Opcodes.LOAD_GLOBAL_SCALAR);  // or ARRAY/HASH
           emitReg(reg);
           emit(nameIdx);
           lastResultReg = reg;
       } else {
           // Regular lexical - use cached register
           int reg = getVariableRegister(varName);
           lastResultReg = reg;
       }
   }
   ```

### Phase 3: Modify JVM Emitter (Medium Risk)

**File:** `src/main/java/org/perlonjava/backend/jvm/EmitVariable.java`

Apply similar changes to `emitVariable()` method:
- Check if variable is `our`-declared
- If so, emit code to fetch from GlobalVariable instead of using cached reference

### Phase 4: Testing

1. **Unit test for the specific bug:**
   ```perl
   package Foo;
   our $X = 0;
   sub check { return $X; }
   
   package main;
   use Test::More;
   is(Foo::check(), 0, 'before local');
   {
       local $Foo::X = 1;
       is(Foo::check(), 1, 'inside local');  # Currently fails
   }
   is(Foo::check(), 0, 'after local');
   done_testing();
   ```

2. **Run Log4perl tests:** Should see improvement in t/020Easy.t, t/022Wrap.t, t/024WarnDieCarp.t, t/051Extra.t

3. **Run full test suite:** Ensure no regressions

4. **Performance testing:** Benchmark before/after on code-heavy `our` variable usage

### Phase 5: Optimization (Future)

If Phase 4 shows performance issues:

1. **Static analysis:** If we can prove `local` is never called on a variable within a compilation unit, use the cached register

2. **Inline caching:** First access fetches from global and caches; subsequent accesses use cache but have a guard that invalidates on `local`

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Performance regression | Medium | Medium | Phase 5 optimizations |
| Breaking existing code | Low | High | Comprehensive testing |
| Incomplete fix | Low | Medium | Thorough test coverage |
| Interpreter backend inconsistency | Medium | Medium | Apply same changes to both backends |

## Timeline Estimate

- Phase 1: 1-2 hours
- Phase 2: 2-4 hours  
- Phase 3: 2-4 hours
- Phase 4: 2-4 hours
- Phase 5: Future work

**Total: 1-2 days**

## Success Criteria

1. Reproduction case above works correctly
2. `$Carp::CarpLevel` works correctly with `local`
3. Log4perl test failures related to CarpLevel are resolved
4. No regressions in existing test suite
5. Performance impact is acceptable (< 5% on typical workloads)

## Related Issues

- Log4perl compatibility: `dev/design/log4perl-compatibility.md`
- Affects: t/020Easy.t, t/022Wrap.t, t/024WarnDieCarp.t, t/051Extra.t

## Implementation Attempt Notes (2024-03-19)

### What Was Tried

**Approach 1: Skip `our` variables from closure capture**

Attempted to modify `SubroutineParser.java` and `EmitSubroutine.java` to skip `our` variables when building the closure variable list, so they wouldn't be captured at subroutine definition time and would instead be looked up fresh at runtime.

**Files modified:**
- `SubroutineParser.java` (lines ~706-812): Skip variables with `declarationType.equals("our")` in closure capture loop
- `EmitSubroutine.java` (lines ~102-138): Filter `our` from `visibleVariables` for anonymous subs

**Result:** The basic test case passed (`X=1` was printed correctly), but it broke Test::More and other modules with a subroutine constructor mismatch error:

```
Subroutine error: org.perlonjava.anon51.<init>(org.perlonjava.runtime.runtimetypes.RuntimeHash,org.perlonjava.runtime.runtimetypes.RuntimeScalar,...) at jar:PERL5LIB/Test2/Util.pm line 8
```

**Root Cause of Failure:** The issue is that when we skip `our` variables from closure capture, the generated class constructor signature changes (fewer parameters), but existing code that creates instances of those anonymous subroutines still passes the old number of arguments. This creates a method signature mismatch at runtime.

### Deeper Analysis Required

The fix needs to be more surgical:

1. **Don't change constructor signatures** - `our` variables still need to be in the closure capture list to maintain API compatibility
2. **Change how captured `our` variables are used** - Instead of using the captured value, emit code to re-fetch from the global symbol table at access time

This aligns with "Option A" in the design doc but requires changes at the **variable access** level, not at the **closure capture** level.

### Recommended Next Steps

1. **Phase 1:** Implement `getOurVariableGlobalName()` in `ScopedSymbolTable.java` - track which variables are `our` and their fully-qualified global names
2. **Phase 2:** In `EmitVariable.java` (JVM backend) and `BytecodeCompiler.java` (interpreter), when emitting code to access a variable, check if it's an `our` variable and if so, emit `LOAD_GLOBAL_*` instead of using the register
3. **Phase 3:** Test with both the basic reproduction case AND Test::More

The key insight is: **`our` variables should still be captured in closures (to maintain constructor signatures), but the captured value should be ignored at access time in favor of a fresh global lookup.**

### Approach 2: Make `our` variables non-lexical in EmitVariable.java (2024-03-19)

**What Was Tried:**

Modified `EmitVariable.java` to treat `our` variables as non-lexical:
1. Removed `our` from the `isLexical` check (lines 383-389)
2. Added `@_` as a special exception (it's declared as `our` but is actually passed as a parameter)
3. Added `isOurDeclaration` to `createIfNotExists` to allow `our` variable access

Also modified:
- `ScopedSymbolTable.java`: Added overload `addVariable(name, decl, perlPackage, ast)` to preserve package
- `SubroutineParser.java`: Used new overload for `our` variables when copying to subroutine scope

**Result:** 
- Basic `our`/`local` test cases PASSED (`X=1` printed correctly)
- `@_` continued to work correctly
- BUT: Test::More and Test2 modules FAILED with "Can't call method 'stack' on an undefined value"

**Root Cause of Test2 Failure:**

The Test2 framework uses a pattern like:
```perl
my $INST;
use Test2::API::Instance(\$INST);  # Sets $INST via import
my $STACK = $INST->stack;          # $INST is undef here!
```

When loading a module, the `use` statement runs at compile time (as a BEGIN block). The lexical `$INST` declared with `my` should persist from the BEGIN block to the subsequent runtime code.

**Investigation revealed:** This is a pre-existing issue with how lexical variables work in BEGIN blocks:
```perl
my $x = 1;
BEGIN { $x = 99; }
print "x = $x\n";  # Prints "x = 1", not "x = 99"
```

This behavior exists BOTH with and without the `our` fix. However, the Test2/Test::More modules work in the current codebase through some mechanism that my changes apparently break.

The interaction between my `our` variable changes and the BEGIN/lexical behavior needs further investigation. The changes to symbol table handling during subroutine compilation (SubroutineParser.java) may be affecting how lexicals are captured in BEGIN blocks.

### Current Status

- **Test cases added:** `local.t` now contains test cases for cross-package `our`/`local` (in `__END__` section, documented as known failures)
- **Design documented:** This file contains the full analysis
- **Fix NOT committed:** The fix breaks Test::More due to complex interactions with BEGIN blocks

### Next Investigation Steps

1. Understand why Test2::API works without the fix - what mechanism allows `$INST` to persist?
2. Investigate how the symbol table is being used differently in BEGIN blocks vs regular subroutines
3. Consider if there's a way to apply the `our` fix only to named subroutines, not BEGIN blocks
4. Look into how Perl itself handles this - does it use a different storage mechanism for lexicals that need to persist across compile/runtime?

## References

- Perl `our` documentation: https://perldoc.perl.org/functions/our
- Perl `local` documentation: https://perldoc.perl.org/functions/local
- PerlGuts on symbol tables: https://perldoc.perl.org/perlguts#Stashes-and-Globs
