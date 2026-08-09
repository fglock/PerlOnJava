# Taint Mode Implementation Plan

## Overview

Perl's taint mode (`-T` flag) tracks data from external sources (environment variables, command line arguments, file input, etc.) and prevents their use in potentially dangerous operations like `system()` calls without explicit validation.

## Requirements

1. **Low overhead** - Taint metadata is a single boolean on `RuntimeScalar`
2. **Centralized policy** - Sources, propagation, and dangerous-operation checks use shared helpers
3. **Backend parity** - JVM and interpreter execution must preserve the same taint metadata
4. **Gradual implementation** - Each phase delivers tested functionality

## Design: Scalar Taint Flag

> **Decision (2026-08-09):** The wrapper proposal below was superseded by the
> field-based implementation already present in the runtime. A wrapper adds a
> new scalar type that every value-access fast path must understand; the boolean
> flag composes with tied, read-only, special-variable, and reference scalars
> without changing their existing type.

`RuntimeScalar` owns a `boolean tainted` field. Copies and assignments preserve
the flag, operations call `propagateTaint(...)`, and external sources call
`taintFromExternalInput()`. `isTainted()` resolves special, tied, and read-only
scalars before inspecting the flag. Security-sensitive operations call the
central `RuntimeScalar.checkTaint(value, operation)` helper, which only enforces
the flag when the current thread is running under `-T`.

The original wrapper sketch is retained below as historical context, not as an
implementation target.

### Rejected Alternative: TAINTED Type (Wrapper Pattern)

Add a `TAINTED` type to RuntimeScalarType, following the existing TIED_SCALAR pattern:

```java
// In RuntimeScalarType.java
public static final int TAINTED = 17;  // Next available type

// A tainted scalar:
// - type = TAINTED
// - value = RuntimeScalar (the actual scalar with its own type)
```

**How it meets requirements:**
- Normal scalars unchanged (no extra fields)
- Only tainted scalars have `type == TAINTED`
- Taint check is alongside existing TIED_SCALAR check (not a new check pattern)
- Follows established wrapper pattern in the codebase

**Key methods:**

```java
// In RuntimeScalar.java

public boolean isTainted() {
    return type == TAINTED;
}

// Get the actual scalar (unwrap if tainted)
public RuntimeScalar getActualScalar() {
    return (type == TAINTED) ? (RuntimeScalar) value : this;
}

// Create a tainted wrapper
public static RuntimeScalar taint(RuntimeScalar scalar) {
    if (scalar.type == TAINTED) return scalar;  // Already tainted
    RuntimeScalar tainted = new RuntimeScalar();
    tainted.type = TAINTED;
    tainted.value = scalar;
    return tainted;
}
```

**Taint propagation in set():**

```java
public RuntimeScalar set(RuntimeScalar value) {
    if (value == null) { ... }
    if (value.type == TIED_SCALAR) {
        return set(value.tiedFetch());
    }
    if (this.type == TIED_SCALAR) {
        return this.tiedStore(value);
    }
    // Taint propagation - preserve taint wrapper
    if (value.type == TAINTED) {
        RuntimeScalar inner = (RuntimeScalar) value.value;
        this.type = TAINTED;
        this.value = new RuntimeScalar(inner);
        return this;
    }
    this.type = value.type;
    this.value = value.value;
    return this;
}
```

**Value access (unwrap when needed):**

```java
// Methods that need the actual value unwrap first
public int getInt() {
    if (type == TAINTED) {
        return ((RuntimeScalar) value).getInt();
    }
    // ... existing implementation
}

public String toString() {
    if (type == TAINTED) {
        return ((RuntimeScalar) value).toString();
    }
    // ... existing implementation
}
```

---

## Phase 1: Minimal Fix for IPC::System::Simple

**Goal:** Make `t/10_formatting.t` pass by refusing external commands in taint mode.

**Approach:** Check `${^TAINT}` at dangerous operations rather than tracking propagation.

### Changes

1. **Modify bundled IPC::System::Simple** (`src/main/perl/lib/IPC/System/Simple.pm`):
   ```perl
   # In _check_taint or at the start of system/capture operations:
   if (${^TAINT}) {
       croak("Insecure dependency while running with -T switch");
   }
   ```

2. **Keep existing infrastructure:**
   - `-T` flag parsing (already done)
   - `${^TAINT}` variable (already done)

### Testing
- `t/10_formatting.t` - should pass (command refused in taint mode)

### Limitations
- Not true taint semantics
- All external commands blocked in `-T` mode
- Cannot untaint values

---

## Phase 2: TAINTED Type Infrastructure

**Goal:** Add TAINTED type and basic taint detection.

**Implemented differently:** source marking and detection use the scalar taint
flag described above. `$^X`, `%ENV`, `@ARGV`, file reads, `read`, and directory
reads are tainted while `-T` is active. `Scalar::Util::tainted()` and
`builtin::is_tainted()` query `RuntimeScalar.isTainted()`.

### Changes

1. **Add TAINTED constant to RuntimeScalarType.java:**
   ```java
   public static final int TAINTED = 17;
   ```

2. **Add helper methods to RuntimeScalar.java:**
   ```java
   public boolean isTainted() {
       return type == TAINTED;
   }
   
   public RuntimeScalar getActualScalar() {
       return (type == TAINTED) ? (RuntimeScalar) value : this;
   }
   
   public static RuntimeScalar taint(RuntimeScalar scalar) {
       if (scalar.type == TAINTED) return scalar;
       RuntimeScalar tainted = new RuntimeScalar();
       tainted.type = TAINTED;
       tainted.value = new RuntimeScalar(scalar);  // Copy to avoid aliasing
       return tainted;
   }
   ```

3. **Mark tainted sources in GlobalContext.java:**
   ```java
   // $^X
   if (compilerOptions.taintMode) {
       RuntimeScalar exec = RuntimeScalar.taint(new RuntimeScalar(perlExecutable));
       GlobalVariable.aliasGlobalVariable("main::\030", exec);
   }
   
   // %ENV
   if (compilerOptions.taintMode) {
       env.put(k, RuntimeScalar.taint(new RuntimeScalar(v)));
   }
   ```

4. **Update ScalarUtil.tainted():**
   ```java
   public static RuntimeList tainted(RuntimeArray args, int ctx) {
       return new RuntimeScalar(args.get(0).isTainted()).getList();
   }
   ```

### Testing
- `tainted($^X)` returns true when `-T` is used
- `tainted($ENV{PATH})` returns true when `-T` is used
- `tainted("constant")` returns false

---

## Phase 3: Taint Propagation

**Goal:** Taint propagates through assignment and operations.

**Implemented differently:** constructors and `set()` copy the boolean flag.
Concatenation, interpolation/join, substring lvalues, the primary arithmetic
operators and numeric functions, `length`, case conversion, `ord`, `oct`, and
`hex` preserve taint. Further operator coverage remains an explicit audit item.

### Changes

1. **Update set() to propagate taint:**
   ```java
   public RuntimeScalar set(RuntimeScalar value) {
       // ... existing null and TIED_SCALAR checks ...
       
       // Propagate taint
       if (value.type == TAINTED) {
           RuntimeScalar inner = (RuntimeScalar) value.value;
           this.type = TAINTED;
           this.value = new RuntimeScalar(inner);
           return this;
       }
       
       this.type = value.type;
       this.value = value.value;
       return this;
   }
   ```

2. **Update value access methods to unwrap:**
   ```java
   public int getInt() {
       if (type == TAINTED) return ((RuntimeScalar) value).getInt();
       // ... existing
   }
   
   public double getDouble() {
       if (type == TAINTED) return ((RuntimeScalar) value).getDouble();
       // ... existing
   }
   
   public String toString() {
       if (type == TAINTED) return ((RuntimeScalar) value).toString();
       // ... existing
   }
   
   public boolean getBoolean() {
       if (type == TAINTED) return ((RuntimeScalar) value).getBoolean();
       // ... existing
   }
   ```

3. **Update operations to propagate taint:**
   
   For binary operations, result is tainted if either operand is tainted:
   ```java
   // Example: string concatenation
   public RuntimeScalar concat(RuntimeScalar other) {
       boolean resultTainted = this.isTainted() || other.isTainted();
       RuntimeScalar thisActual = this.getActualScalar();
       RuntimeScalar otherActual = other.getActualScalar();
       
       RuntimeScalar result = new RuntimeScalar(thisActual.toString() + otherActual.toString());
       
       return resultTainted ? RuntimeScalar.taint(result) : result;
   }
   ```

### Testing
- `my $x = $^X; tainted($x)` returns true
- `my $y = $^X . ""; tainted($y)` returns true
- `tainted($clean . $tainted)` returns true

---

## Phase 4: Dangerous Operation Enforcement

**Goal:** Tainted data causes errors in dangerous operations.

### Operations to Protect

1. **Process execution:**
   - `system()`, `exec()`, `qx//`, backticks
   - `open()` with pipe

2. **Code execution:**
   - `eval($string)`, `require($file)`, `do($file)`

3. **File system:**
   - `unlink()`, `mkdir()`, `rmdir()`
   - `chmod()`, `chown()`, `chdir()`
   - `rename()`, `link()`, `symlink()`

### Implementation

```java
// Helper method
public static void checkTaint(RuntimeScalar scalar, String operation) {
    if (GlobalContext.isTaintModeActive() && scalar.isTainted()) {
        throw new PerlCompilerException(
            "Insecure dependency in " + operation + " while running with -T switch"
        );
    }
}

// In SystemOperator.java
public static RuntimeList system(RuntimeArray args, int ctx) {
    for (RuntimeScalar arg : args.elements) {
        checkTaint(arg, "system");
    }
    // ... existing implementation
}
```

---

## Phase 5: Untainting via Regex

**Goal:** Allow validated data to be untainted via regex captures.

### Perl Semantics

```perl
if ($tainted =~ /^([\w\/]+)$/) {
    my $clean = $1;  # $1 is NOT tainted
}
```

### Implementation

Regex captures create normal RuntimeScalar, not tainted:

```java
// In RuntimeRegex capture handling
// Always create non-tainted scalars for captures
RuntimeScalar capture = new RuntimeScalar(matchedText);
// The captured value is untainted regardless of source
```

This behavior is implemented and covered by the taint regression test.

---

## Files to Modify by Phase

### Phase 1
- `src/main/perl/lib/IPC/System/Simple.pm` - Add ${^TAINT} check

### Phase 2
- `RuntimeScalarType.java` - Add TAINTED constant
- `RuntimeScalar.java` - Add isTainted(), getActualScalar(), taint()
- `GlobalContext.java` - Create tainted scalars for $^X, %ENV, @ARGV
- `ScalarUtil.java` - Use isTainted() method
- `Builtin.java` - Update is_tainted()

### Phase 3
- `RuntimeScalar.java` - Update set(), getInt(), getDouble(), toString(), getBoolean()
- String/arithmetic operator classes - Propagate taint in operations

### Phase 4
- `SystemOperator.java` - Taint checks
- `FileOperator.java` - Taint checks  
- `Eval.java` - Taint checks

### Phase 5
- `RuntimeRegex.java` - Ensure captures are not tainted

---

## Cleanup

The rejected wrapper and WeakHashMap approaches were not introduced. There is
no `RuntimeScalarTaint.java` cleanup required.

---

## Progress Tracking

### Current Status: Core taint mode implemented; extended Perl-core parity in progress

### Completed Phases

- [x] **Phase 1: Minimal Fix for IPC::System::Simple** (2026-03-24)
  - Modified `src/main/perl/lib/IPC/System/Simple.pm` `_check_taint()` to block ALL external commands when `${^TAINT}` is set
  - Added `isTainted()` method to RuntimeScalar.java (returns false, ready for Phase 2)
  - Updated `ScalarUtil.tainted()` to use `isTainted()` method
  - **Bonus fix**: Reset `$?` to 0 before END blocks in SpecialBlock.java (Perl semantics) - this fixed spurious "Looks like your test exited with X" warnings from Test::Builder
  - **Test results**: IPC::System::Simple 15/17 test programs pass, 169/181 subtests (93%)

- [x] **Phase 2: Taint sources and detection** (2026-08-09)
  - Uses the existing `RuntimeScalar.tainted` field rather than a wrapper type
  - Marks `$^X`, `%ENV`, `@ARGV`, readline/read, and directory input
  - Supports `Scalar::Util::tainted()` and `builtin::is_tainted()`
  - Files: `RuntimeScalar.java`, `GlobalContext.java`, existing IO source helpers

- [x] **Phase 3: Core propagation** (2026-08-09)
  - Preserves taint through scalar copy/assignment, concatenation, substring,
    primary arithmetic, numeric functions, length, case conversion, and scalar
    numeric conversions
  - Fixed interpreter `length` parity by routing through `StringOperators.length()`
  - Files: `RuntimeScalar.java`, `MathOperators.java`, `StringOperators.java`,
    `ScalarOperators.java`, `SlowOpcodeHandler.java`

- [x] **Phase 4: Dangerous operation enforcement** (2026-08-09)
  - Rejects tainted process commands/arguments for `system`, `exec`, qx/backticks,
    and pipe opens; checks dangerous process environment variables
  - Rejects tainted paths for `unlink`, `mkdir`, `rmdir`, `chdir`, `rename`,
    `link`, `symlink`, `chmod`, `chown`, `require`, and `do`
  - Rejects tainted signal/PID arguments to `kill`
  - Fixed interpreter eval behavior so a tainted eval cannot catch its own error

- [x] **Phase 5: Regex capture untainting** (2026-08-09)
  - Validated captures are clean scalars, matching Perl's standard untaint idiom

- [x] **Cross-backend regression coverage** (2026-08-09)
  - Added `src/test/resources/unit/taint_mode.t` (39 tests)
  - Validated with system Perl, JVM backend, interpreter backend, and full `make`

### Infrastructure Complete
- [x] `-T` flag parsing
- [x] `${^TAINT}` variable
- [x] Scalar taint metadata and `isTainted()` resolution
- [x] Central propagation and enforcement helpers
- [x] Thread-local taint mode for nested/concurrent execution

### Next Steps

1. Audit the remaining string, bitwise, comparison, list, and formatting
   operators against `perl5_t/t/op/taint.t` and add propagation where Perl does.
2. Implement Perl's secure-`PATH` directory checks and nuanced `%ENV{TERM}` rules.
3. Expand external-source coverage for platform/network/database APIs as their
   Perl-core taint cases become runnable.
4. Continue raising `perl5_t/t/op/taint.t` from the current early-stop baseline;
   it presently reaches 23/1065 before unsupported environment/path semantics
   stop the file.

### Open Questions

- Which reference/container operations should propagate value taint versus
  preserve only the contained scalar's taint?
- Should the remaining operator audit be completed in one compatibility PR or
  split by operator family to keep performance review tractable?
