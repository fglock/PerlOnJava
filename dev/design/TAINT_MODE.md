# Taint Mode Implementation Plan

## Overview

Perl's taint mode (`-T` flag) tracks data from external sources (environment variables, command line arguments, file input, etc.) and prevents their use in potentially dangerous operations like `system()` calls without explicit validation.

## Requirements

1. **No extra storage for normal scalars** - RuntimeScalar size must not increase
2. **No extra runtime checks for normal scalars** - Only tainted scalars incur overhead
3. **Gradual implementation** - Each phase delivers working functionality

## Design: TAINTED Type (Wrapper Pattern)

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
    if (scalar.isTainted()) {
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

After implementing the TAINTED type approach:
- Remove `RuntimeScalarTaint.java` (no longer needed)
- Remove any WeakHashMap-based taint tracking code

---

## Progress Tracking

### Current Status: Phase 1 not started

### Completed
- [x] `-T` flag parsing
- [x] `${^TAINT}` variable

### Phase 1 TODO
- [ ] Modify IPC::System::Simple to check ${^TAINT}
- [ ] Test with t/10_formatting.t

### Phase 2 TODO
- [ ] Add TAINTED type constant
- [ ] Add helper methods
- [ ] Mark $^X, %ENV, @ARGV as tainted
- [ ] Update tainted() function

### Open Questions
- Should @ARGV be tainted? (Yes in Perl)
- Handle taint in hash/array element access?
- Taint and references - should $$ref propagate taint?
