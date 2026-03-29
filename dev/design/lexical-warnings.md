# Lexical Warnings: Full Perl 5 Parity

## Overview

This document describes the implementation of lexical warnings in PerlOnJava with full Perl 5 compatibility, including:

- Two-variant operators for zero-overhead warning checks
- Per-closure warning bits storage for `caller()[9]`
- User-defined warning categories via `warnings::register`
- Complete `warnings::*` function set
- FATAL warnings support
- `$^W` interaction

## Perl 5 Mechanism

In Perl 5:
1. **Compile-time**: `use warnings 'category'` sets bits in `${^WARNING_BITS}`
2. **Per-COP storage**: Each statement's COP stores its `${^WARNING_BITS}`
3. **PL_curcop**: Global pointer updated at each statement, used by `ckWARN()` macro
4. **Runtime lookup**: `warnings::enabled()` calls `(caller($level))[9]`
5. **Category check**: The category's bit is checked in the retrieved bitmask

### caller() Return Values

```perl
my @info = caller($level);
# $info[8]  = $^H (hints integer)
# $info[9]  = ${^WARNING_BITS} (warning bitmask string)
# $info[10] = %^H (hint hash reference)
```

### Perl 5's Fast Warning Check

```c
// From util.c - Perl_ckwarn()
if (isLEXWARN_off)
    return PL_dowarn & G_WARN_ON;   // Fall back to $^W
    
if (PL_curcop->cop_warnings == pWARN_ALL)
    return TRUE;
if (PL_curcop->cop_warnings == pWARN_NONE)
    return FALSE;

// Bit check: O(1) pointer dereference + bit test
return isWARN_on(PL_curcop->cop_warnings, category);
```

---

## Architecture: Three Mechanisms

### 1. Two-Variant Pattern (Built-in Warnings)

For frequently-checked warnings like `uninitialized`, operators have two variants:
- **Fast path**: `add()`, `getDouble()`, etc. - no warning check
- **Warning path**: `addWarn()`, `getDoubleWarn()`, etc. - checks and warns

**At compile time**, the code generator checks warning state and emits the appropriate call.

**Zero runtime overhead** when warnings are disabled.

### 2. Per-Closure Storage (caller()[9])

Each subroutine/closure stores its compile-time warning bits:
- **JVM backend**: Static `WARNING_BITS` field, registered at class load
- **Interpreter**: `warningBits` field in `InterpretedCode`

**At runtime**, `caller()` looks up bits from `WarningBitsRegistry`.

### 3. ${^WARNING_SCOPE} (Cross-Module Propagation)

For `warnings::warnif()` across compilation units:
- Uses `local` via `DynamicVariableManager`
- Handles cross-module warning suppression

---

## Warning Bits String Format

Compatible with Perl 5. Each category has 2 bits:
- Bit 0: Warning enabled
- Bit 1: Fatal enabled

```java
// Category offsets (from Perl 5's warnings.h)
public static final int WARN_ALL = 0;
public static final int WARN_CLOSURE = 1;
public static final int WARN_DEPRECATED = 2;
// ...
public static final int WARN_UNINITIALIZED = 41;
// ...

// Perl 5 uses 21 bytes (WARNsize), supporting ~80 built-in categories
// Check if enabled: bit at position (category * 2)
// Check if fatal: bit at position (category * 2 + 1)
```

---

## Implementation Details

### Two-Variant Pattern

#### RuntimeScalar.java

```java
// Fast path - called when 'uninitialized' warnings disabled
public RuntimeScalar add(RuntimeScalar other) {
    double left = this.getDouble();
    double right = other.getDouble();
    return new RuntimeScalar(left + right);
}

// Warning path - called when 'uninitialized' warnings enabled
public RuntimeScalar addWarn(RuntimeScalar other) {
    warnIfUndef(this, "addition");
    warnIfUndef(other, "addition");
    return add(other);  // Delegate to fast path
}

private static void warnIfUndef(RuntimeScalar v, String op) {
    if (v.type == UNDEF) {
        Warnings.warnDirect("Use of uninitialized value in " + op);
    }
}
```

#### Methods Needing Two Variants

| Category | Methods |
|----------|---------|
| Arithmetic | `add`, `subtract`, `multiply`, `divide`, `modulo`, `negate`, `power` |
| Numeric conversion | `getDouble`, `getLong`, `getInt` |
| String ops | `concat`, `stringCompare`, `repeat` |
| Comparison | `numericCompare`, `eq`, `ne`, `lt`, `gt`, `le`, `ge` |
| Bitwise | `bitwiseAnd`, `bitwiseOr`, `bitwiseXor`, `bitwiseNot` |

#### Code Generator (EmitOperator.java)

```java
private void emitAddition(EmitterContext ctx, Node left, Node right) {
    emitNode(ctx, left);
    emitNode(ctx, right);
    
    // Check compile-time warning state
    boolean warnUninit = ctx.symbolTable.isWarningEnabled("uninitialized");
    String methodName = warnUninit ? "addWarn" : "add";
    
    mv.visitMethodInsn(INVOKEVIRTUAL, 
        "org/perlonjava/runtime/RuntimeScalar", 
        methodName,
        "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
        false);
}
```

#### Interpreter Opcodes

Use a flag bit in the instruction:

```java
// BytecodeCompiler.java - when compiling operators
int flags = 0;
if (ctx.symbolTable.isWarningEnabled("uninitialized")) {
    flags |= FLAG_WARN_UNINIT;
}
emit(OP_ADD, flags);

// BytecodeInterpreter.java - when executing
case OP_ADD:
    RuntimeScalar right = stack.pop();
    RuntimeScalar left = stack.pop();
    if ((flags & FLAG_WARN_UNINIT) != 0) {
        stack.push(left.addWarn(right));
    } else {
        stack.push(left.add(right));
    }
    break;
```

### Per-Closure Warning Bits Storage

#### WarningBitsRegistry.java (NEW)

```java
package org.perlonjava.runtime;

import java.util.concurrent.ConcurrentHashMap;

public class WarningBitsRegistry {
    private static final ConcurrentHashMap<String, String> registry = 
        new ConcurrentHashMap<>();
    
    public static void register(String className, String bits) {
        registry.put(className, bits);
    }
    
    public static String get(String className) {
        return registry.get(className);
    }
    
    public static void clear() {
        registry.clear();
    }
}
```

#### JVM Backend (EmitterMethodCreator.java)

```java
// Add WARNING_BITS field to generated class
String warningBits = ctx.symbolTable.getWarningBitsString();
cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL,
    "WARNING_BITS", "Ljava/lang/String;", null, warningBits);

// In static initializer, register the bits
MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
clinit.visitLdcInsn(fullClassName);
clinit.visitLdcInsn(warningBits);
clinit.visitMethodInsn(INVOKESTATIC,
    "org/perlonjava/runtime/WarningBitsRegistry",
    "register",
    "(Ljava/lang/String;Ljava/lang/String;)V", false);
clinit.visitInsn(RETURN);
clinit.visitMaxs(2, 0);
clinit.visitEnd();
```

#### Interpreter Backend (InterpretedCode.java)

```java
public class InterpretedCode extends RuntimeCode {
    public final String warningBits;
    
    public InterpretedCode(byte[] bytecode, String sourceName,
                           String[] variableNames, String warningBits) {
        // ...
        this.warningBits = warningBits;
        // Register for caller() lookup
        WarningBitsRegistry.register(this.getClassName(), warningBits);
    }
}
```

#### caller() Implementation (RuntimeCode.java)

```java
public static RuntimeList callerWithSub(RuntimeList args, int ctx, 
                                        RuntimeScalar currentSub) {
    // ... existing code for elements 0-7 ...
    
    // Element [8]: $^H hints
    res.add(new RuntimeScalar(0));  // TODO: implement hints
    
    // Element [9]: ${^WARNING_BITS}
    String className = stackTrace[frame].getClassName();
    String warningBits = WarningBitsRegistry.get(className);
    if (warningBits != null) {
        res.add(new RuntimeScalar(warningBits));
    } else {
        res.add(RuntimeScalarCache.scalarUndef);
    }
    
    // Element [10]: %^H hint hash
    res.add(new RuntimeScalar(new RuntimeHash()));  // TODO: implement
    
    return res;
}
```

### ScopedSymbolTable Changes

```java
public class ScopedSymbolTable {
    // Existing stacks
    private final Stack<BitSet> warningFlagsStack;
    private final Stack<BitSet> warningDisabledStack;
    
    // NEW: Fatal warnings stack
    private final Stack<BitSet> warningFatalStack = new Stack<>();
    
    // NEW: Check if a warning category is enabled
    public boolean isWarningEnabled(String category) {
        int bit = WarningFlags.getBitPosition(category);
        if (bit < 0) return false;
        
        // Check if explicitly disabled
        if (!warningDisabledStack.isEmpty() && 
            warningDisabledStack.peek().get(bit)) {
            return false;
        }
        
        // Check if enabled
        if (!warningFlagsStack.isEmpty() && 
            warningFlagsStack.peek().get(bit)) {
            return true;
        }
        
        return false;
    }
    
    // NEW: Get warning bits as Perl-compatible string
    public String getWarningBitsString() {
        return WarningFlags.toWarningBitsString(
            warningFlagsStack.isEmpty() ? null : warningFlagsStack.peek(),
            warningFatalStack.isEmpty() ? null : warningFatalStack.peek()
        );
    }
}
```

### WarningFlags.java Changes

```java
public class WarningFlags {
    // Built-in categories (from Perl 5's warnings.h)
    private static final Map<String, Integer> BUILTIN_OFFSETS = Map.ofEntries(
        Map.entry("all", 0),
        Map.entry("closure", 1),
        Map.entry("deprecated", 2),
        Map.entry("exiting", 3),
        Map.entry("glob", 4),
        Map.entry("io", 5),
        Map.entry("closed", 6),
        Map.entry("exec", 7),
        Map.entry("layer", 8),
        Map.entry("newline", 9),
        Map.entry("pipe", 10),
        Map.entry("unopened", 11),
        Map.entry("misc", 12),
        Map.entry("numeric", 13),
        Map.entry("once", 14),
        Map.entry("overflow", 15),
        Map.entry("pack", 16),
        Map.entry("portable", 17),
        Map.entry("recursion", 18),
        Map.entry("redefine", 19),
        Map.entry("regexp", 20),
        Map.entry("severe", 21),
        Map.entry("debugging", 22),
        Map.entry("inplace", 23),
        Map.entry("internal", 24),
        Map.entry("malloc", 25),
        Map.entry("signal", 26),
        Map.entry("substr", 27),
        Map.entry("syntax", 28),
        Map.entry("ambiguous", 29),
        Map.entry("bareword", 30),
        Map.entry("digit", 31),
        Map.entry("parenthesis", 32),
        Map.entry("precedence", 33),
        Map.entry("printf", 34),
        Map.entry("prototype", 35),
        Map.entry("qw", 36),
        Map.entry("reserved", 37),
        Map.entry("semicolon", 38),
        Map.entry("taint", 39),
        Map.entry("threads", 40),
        Map.entry("uninitialized", 41),
        Map.entry("unpack", 42),
        Map.entry("untie", 43),
        Map.entry("utf8", 44),
        Map.entry("void", 45)
        // ... more categories
    );
    
    public static int getBitPosition(String category) {
        Integer offset = BUILTIN_OFFSETS.get(category);
        if (offset != null) return offset;
        
        // Check user-defined categories
        offset = userOffsets.get(category);
        return offset != null ? offset : -1;
    }
    
    // Convert BitSets to Perl-compatible warning bits string
    public static String toWarningBitsString(BitSet enabled, BitSet fatal) {
        int numBytes = 21;  // WARNsize from Perl 5
        
        // Extend if user-defined categories need more space
        if (enabled != null && enabled.length() > 0) {
            int needed = (enabled.length() * 2 + 7) / 8;
            numBytes = Math.max(numBytes, needed);
        }
        
        byte[] bytes = new byte[numBytes];
        
        if (enabled != null) {
            for (int i = enabled.nextSetBit(0); i >= 0; i = enabled.nextSetBit(i+1)) {
                int byteIndex = (i * 2) / 8;
                int bitInByte = (i * 2) % 8;
                if (byteIndex < numBytes) {
                    bytes[byteIndex] |= (1 << bitInByte);
                }
            }
        }
        
        if (fatal != null) {
            for (int i = fatal.nextSetBit(0); i >= 0; i = fatal.nextSetBit(i+1)) {
                int byteIndex = (i * 2 + 1) / 8;
                int bitInByte = (i * 2 + 1) % 8;
                if (byteIndex < numBytes) {
                    bytes[byteIndex] |= (1 << bitInByte);
                }
            }
        }
        
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
    
    // Check if category is enabled in bits string
    public static boolean isEnabledInBits(String bits, String category) {
        int offset = getBitPosition(category);
        if (offset < 0 || bits == null) return false;
        
        int byteIndex = (offset * 2) / 8;
        int bitInByte = (offset * 2) % 8;
        
        if (byteIndex >= bits.length()) return false;
        return (bits.charAt(byteIndex) & (1 << bitInByte)) != 0;
    }
    
    // Check if category is fatal in bits string
    public static boolean isFatalInBits(String bits, String category) {
        int offset = getBitPosition(category);
        if (offset < 0 || bits == null) return false;
        
        int byteIndex = (offset * 2 + 1) / 8;
        int bitInByte = (offset * 2 + 1) % 8;
        
        if (byteIndex >= bits.length()) return false;
        return (bits.charAt(byteIndex) & (1 << bitInByte)) != 0;
    }
}
```

---

## User-Defined Warning Categories

### Registration

Modules register custom categories via `warnings::register`:

```perl
package MyModule;
use warnings::register;  # Registers 'MyModule' as a category

sub do_something {
    if (warnings::enabled()) {
        warnings::warn("Something questionable");
    }
}
```

This calls `WarningFlags.registerCategory("MyModule")` which:
1. Assigns a bit position (starting at 128, after built-ins)
2. Stores mapping in `userOffsets` registry

### WarningFlags.java - User Category Support

```java
public class WarningFlags {
    // Built-in categories (from Perl 5)
    private static final Map<String, Integer> BUILTIN_OFFSETS = Map.ofEntries(
        // ... 80+ built-in categories
    );
    
    // User-defined categories (dynamically registered)
    private static final ConcurrentHashMap<String, Integer> userOffsets = 
        new ConcurrentHashMap<>();
    private static final AtomicInteger nextUserOffset = 
        new AtomicInteger(128);  // Start after built-in range
    
    // Register a new category (called by warnings::register)
    public static int registerCategory(String category) {
        // Check if already registered
        Integer existing = userOffsets.get(category);
        if (existing != null) return existing;
        
        // Check built-in
        existing = BUILTIN_OFFSETS.get(category);
        if (existing != null) return existing;
        
        // Assign new offset
        int offset = nextUserOffset.getAndIncrement();
        userOffsets.put(category, offset);
        return offset;
    }
    
    // Get bit position (built-in or user-defined)
    public static int getBitPosition(String category) {
        Integer offset = BUILTIN_OFFSETS.get(category);
        if (offset != null) return offset;
        
        offset = userOffsets.get(category);
        return offset != null ? offset : -1;
    }
}
```

### ScopedSymbolTable - Handle Unknown Categories

```java
// When processing: no warnings 'MyModule'
public void disableWarning(String category) {
    int bit = WarningFlags.getBitPosition(category);
    if (bit < 0) {
        // Unknown category - might be user-defined, register it
        bit = WarningFlags.registerCategory(category);
    }
    
    if (!warningDisabledStack.isEmpty()) {
        warningDisabledStack.peek().set(bit);
    }
}
```

### Usage Examples

```perl
package MyModule;
use warnings::register;

sub check {
    # Check if caller has warnings enabled for 'MyModule'
    if (warnings::enabled()) {
        warnings::warn("Warning from MyModule");
    }
}

# User code:
use MyModule;
{
    no warnings 'MyModule';  # Disable this category
    MyModule::check();       # No warning
}
MyModule::check();           # Warning emitted
```

### Bit Allocation

| Range | Use |
|-------|-----|
| 0-127 | Built-in Perl 5 categories |
| 128+  | User-defined categories (dynamically assigned) |

Warning bits string grows as needed to accommodate user categories.

---

## Warnings.java Implementation

```java
public class Warnings {
    // Direct warning (for two-variant pattern)
    public static void warnDirect(String message) {
        WarnDie.warn(new RuntimeScalar(message));
    }
    
    // warnings::enabled() - no args, check caller's package
    public static RuntimeScalar enabled() {
        // Get caller's package
        RuntimeList callerInfo = RuntimeCode.callerWithSub(
            new RuntimeList(new RuntimeScalar(1)), 
            RuntimeContextType.LIST, 
            null);
        String callerPackage = callerInfo.get(0).toString();  // element 0 is package
        String bits = callerInfo.get(9).toString();           // element 9 is warning bits
        
        return WarningFlags.isEnabledInBits(bits, callerPackage)
            ? RuntimeScalarCache.scalarTrue
            : RuntimeScalarCache.scalarFalse;
    }
    
    // warnings::enabled($category) - explicit category
    public static RuntimeScalar enabled(RuntimeScalar category) {
        RuntimeList callerInfo = RuntimeCode.callerWithSub(
            new RuntimeList(new RuntimeScalar(1)), 
            RuntimeContextType.LIST, 
            null);
        String bits = callerInfo.get(9).toString();
        
        return WarningFlags.isEnabledInBits(bits, category.toString())
            ? RuntimeScalarCache.scalarTrue
            : RuntimeScalarCache.scalarFalse;
    }
    
    // warnings::enabled_at_level($category, $level)
    public static RuntimeScalar enabledAtLevel(RuntimeScalar category, RuntimeScalar level) {
        RuntimeList callerInfo = RuntimeCode.callerWithSub(
            new RuntimeList(level.add(new RuntimeScalar(1))),
            RuntimeContextType.LIST,
            null);
        
        if (callerInfo.size() < 10) {
            return RuntimeScalarCache.scalarFalse;
        }
        
        String bits = callerInfo.get(9).toString();
        return WarningFlags.isEnabledInBits(bits, category.toString())
            ? RuntimeScalarCache.scalarTrue
            : RuntimeScalarCache.scalarFalse;
    }
    
    // warnings::fatal_enabled() - no args
    public static RuntimeScalar fatalEnabled() {
        RuntimeList callerInfo = RuntimeCode.callerWithSub(
            new RuntimeList(new RuntimeScalar(1)),
            RuntimeContextType.LIST,
            null);
        String callerPackage = callerInfo.get(0).toString();
        String bits = callerInfo.get(9).toString();
        
        return WarningFlags.isFatalInBits(bits, callerPackage)
            ? RuntimeScalarCache.scalarTrue
            : RuntimeScalarCache.scalarFalse;
    }
    
    // warnings::fatal_enabled($category)
    public static RuntimeScalar fatalEnabled(RuntimeScalar category) {
        RuntimeList callerInfo = RuntimeCode.callerWithSub(
            new RuntimeList(new RuntimeScalar(1)),
            RuntimeContextType.LIST,
            null);
        
        if (callerInfo.size() < 10) {
            return RuntimeScalarCache.scalarFalse;
        }
        
        String bits = callerInfo.get(9).toString();
        return WarningFlags.isFatalInBits(bits, category.toString())
            ? RuntimeScalarCache.scalarTrue
            : RuntimeScalarCache.scalarFalse;
    }
    
    // warnings::warnif($category, $message)
    public static void warnif(RuntimeScalar category, RuntimeScalar message) {
        RuntimeList callerInfo = RuntimeCode.callerWithSub(
            new RuntimeList(new RuntimeScalar(1)),
            RuntimeContextType.LIST,
            null);
        
        if (callerInfo.size() < 10) return;
        
        String bits = callerInfo.get(9).toString();
        String cat = category.toString();
        
        if (WarningFlags.isEnabledInBits(bits, cat)) {
            if (WarningFlags.isFatalInBits(bits, cat)) {
                WarnDie.die(message);
            } else {
                WarnDie.warn(message);
            }
        }
    }
    
    // warnings::warnif_at_level($category, $level, $message)
    public static void warnifAtLevel(RuntimeScalar category, RuntimeScalar level, 
                                      RuntimeScalar message) {
        RuntimeList callerInfo = RuntimeCode.callerWithSub(
            new RuntimeList(level.add(new RuntimeScalar(1))),
            RuntimeContextType.LIST,
            null);
        
        if (callerInfo.size() < 10) return;
        
        String bits = callerInfo.get(9).toString();
        String cat = category.toString();
        
        if (WarningFlags.isEnabledInBits(bits, cat)) {
            if (WarningFlags.isFatalInBits(bits, cat)) {
                WarnDie.die(message);
            } else {
                WarnDie.warn(message);
            }
        }
    }
    
    // Register category (called by warnings::register)
    public static void registerCategory(RuntimeScalar category) {
        WarningFlags.registerCategory(category.toString());
    }
}
```

---

## FATAL Warnings

### Use Syntax

```perl
use warnings FATAL => 'all';           # All warnings are fatal
use warnings FATAL => 'uninitialized'; # Just this category
use warnings FATAL => qw(io syntax);   # Multiple categories
no warnings FATAL => 'io';             # Downgrade from fatal
```

### Implementation in useWarnings()

```java
// In Warnings.java or pragma handler
public static void useWarnings(RuntimeList args, ScopedSymbolTable symbolTable) {
    boolean fatal = false;
    boolean nonfatal = false;
    
    for (int i = 0; i < args.size(); i++) {
        String arg = args.get(i).toString();
        
        if (arg.equals("FATAL")) {
            fatal = true;
            nonfatal = false;
            continue;
        }
        if (arg.equals("NONFATAL")) {
            nonfatal = true;
            fatal = false;
            continue;
        }
        
        // It's a category name
        if (fatal) {
            symbolTable.enableWarningFatal(arg);
        } else if (nonfatal) {
            symbolTable.disableWarningFatal(arg);
        } else {
            symbolTable.enableWarning(arg);
        }
    }
}
```

---

## $^W Interaction

Rules:
1. Lexical warnings take precedence over `$^W`
2. `$^W` only affects code not under `use warnings` control
3. `no warnings` explicitly disables `$^W` effect

### In Warnings.warnif()

```java
public static void warnif(RuntimeScalar category, RuntimeScalar message) {
    RuntimeList callerInfo = RuntimeCode.callerWithSub(...);
    String bits = callerInfo.get(9).toString();
    
    // Check lexical warnings first
    if (bits != null && !bits.isEmpty()) {
        if (WarningFlags.isEnabledInBits(bits, category.toString())) {
            emitWarning(bits, category, message);
        }
        return;  // Lexical warnings take precedence
    }
    
    // Fall back to $^W
    if (GlobalContext.getWarnFlag()) {
        WarnDie.warn(message);
    }
}
```

---

## Implementation Phases

### Phase 1: Infrastructure
- [ ] Create `WarningBitsRegistry.java`
- [ ] Add `WarningFlags.toWarningBitsString()`, `isEnabledInBits()`, `isFatalInBits()`
- [ ] Add `WarningFlags.registerCategory()` for user-defined categories
- [ ] Add `ScopedSymbolTable.isWarningEnabled()`, `getWarningBitsString()`
- [ ] Add `ScopedSymbolTable.warningFatalStack`

### Phase 2: Two-Variant Methods
- [ ] Add `*Warn()` variants to `RuntimeScalar.java`
- [ ] Update `EmitOperator.java` to call appropriate variant
- [ ] Update `BytecodeCompiler.java` for interpreter opcodes
- [ ] Update `BytecodeInterpreter.java` to handle warning flag

### Phase 3: Per-Closure Storage (JVM Backend)
- [ ] Update `EmitterMethodCreator.java` to add `WARNING_BITS` field
- [ ] Add static initializer to register bits

### Phase 4: Per-Closure Storage (Interpreter Backend)
- [ ] Add `warningBits` field to `InterpretedCode.java`
- [ ] Register bits in constructor

### Phase 5: Fix caller() Return Values
- [ ] Update `RuntimeCode.callerWithSub()` to return elements 8, 9, 10

### Phase 6: warnings:: Functions
- [ ] Update `Warnings.java` with `enabled()`, `warnif()` using `caller()[9]`
- [ ] Add `enabled_at_level()`, `fatal_enabled()`, `fatal_enabled_at_level()`
- [ ] Add `warn_at_level()`, `warnif_at_level()`
- [ ] Add `registerCategory()` for `warnings::register`
- [ ] Update `warnings.pm` to use XS methods

### Phase 7: FATAL Warnings
- [ ] Handle `FATAL => 'category'` in `useWarnings()`
- [ ] Add `ScopedSymbolTable.enableWarningFatal()`, `disableWarningFatal()`
- [ ] Check fatal bits in `warnif()` and die if set

### Phase 8: $^W Interaction
- [ ] Check `$^W` as fallback when no lexical warnings

---

## Files Summary

| File | Status | Changes |
|------|--------|---------|
| `WarningBitsRegistry.java` | NEW | Class name → bits registry |
| `WarningFlags.java` | MODIFY | Bits string format, category offsets, user categories |
| `ScopedSymbolTable.java` | MODIFY | `isWarningEnabled()`, `getWarningBitsString()`, fatal stack |
| `RuntimeScalar.java` | MODIFY | Add `*Warn()` method variants |
| `EmitOperator.java` | MODIFY | Call appropriate method variant |
| `EmitterMethodCreator.java` | MODIFY | Add `WARNING_BITS` field, static init |
| `BytecodeCompiler.java` | MODIFY | Emit warning flag in opcodes |
| `BytecodeInterpreter.java` | MODIFY | Check warning flag, call appropriate method |
| `InterpretedCode.java` | MODIFY | Add `warningBits` field |
| `RuntimeCode.java` | MODIFY | `caller()` elements 8, 9, 10 |
| `Warnings.java` | MODIFY | Rewrite using `caller()[9]`, add `*_at_level()`, user categories |
| `warnings.pm` | MODIFY | Register new XS methods |
| `warnings/register.pm` | MODIFY | Call Java `registerCategory()` |

---

## Testing

### Unit Tests
```bash
make
./jperl src/test/resources/unit/warnings.t
```

### Perl 5 Tests
```bash
perl dev/tools/perl_test_runner.pl perl5/lib/warnings.t
perl dev/tools/perl_test_runner.pl perl5/t/lib/warnings/
```

### Integration
```bash
./jcpan -r DateTime
```

### Manual Tests
```bash
# Two-variant check
./jperl -e 'use warnings; my $x; print $x + 1'  # Should warn
./jperl -e 'no warnings; my $x; print $x + 1'   # Should NOT warn

# caller()[9] check
./jperl -e 'use warnings "all"; sub foo { my @c = caller(0); print defined $c[9] ? "yes" : "no" } foo()'

# FATAL check
./jperl -e 'use warnings FATAL => "all"; my $x; print $x + 1'  # Should die

# User-defined category
./jperl -e '
  package MyMod;
  use warnings::register;
  sub test { warnings::warnif("test warning") if warnings::enabled() }
  package main;
  use warnings;
  { no warnings "MyMod"; MyMod::test() }  # No warning
  MyMod::test();                           # Warning
'
```

---

## Design Validation

### Will This Behave Like System Perl?

**Yes.** The design achieves Perl 5 parity through these mechanisms:

| Feature | Perl 5 Mechanism | Our Mechanism | Parity |
|---------|------------------|---------------|--------|
| Built-in warnings | Per-COP bits, `ckWARN()` check | Per-statement method variant | ✅ Same behavior |
| `caller()[9]` | COP stores bits | Per-closure registry | ✅ Same behavior |
| `warnings::enabled()` | `caller()` + bit check | Same | ✅ Identical |
| User categories | Dynamic `%Bits` | Dynamic allocation 128+ | ✅ Same behavior |
| FATAL warnings | Fatal bit → die | Same | ✅ Identical |
| `$^W` fallback | Checked when no lexical | Same | ✅ Identical |

**Per-statement granularity**: Each statement compiles with the warning state at that point:

```perl
my $x;
print $x + 1;           # Compiled with addWarn() → warns
{ 
    no warnings;
    print $x + 1;       # Compiled with add() → no warning
}
print $x + 1;           # Compiled with addWarn() → warns
```

This matches Perl 5's per-COP behavior exactly.

**One-liners**: Work correctly because each closure/statement is compiled independently:

```bash
./jperl -e 'use warnings; { no warnings; sub{$x+1}->() } sub{$x+1}->()'
#                         ^^^^ no warn closure    ^^^^ warn closure
```

### Will It Be Performant?

**Yes, and better than Perl 5 when warnings are disabled.**

| Operation | Perl 5 Cost | Our Cost | Comparison |
|-----------|-------------|----------|------------|
| Warning check (disabled) | O(1) bit check every time | **Zero** - no code emitted | **Better** |
| Warning check (enabled) | O(1) bit check | O(1) method call + check | Same |
| `caller()[9]` lookup | O(1) pointer deref | O(1) HashMap lookup | Same |
| `warnings::enabled()` | caller + bit check | Same | Same |

**Key advantage**: The two-variant pattern means we emit different bytecode based on compile-time warning state. When warnings are disabled, we call `add()` instead of `addWarn()` - there's no runtime check at all. Perl 5 always executes the `ckWARN()` check.

### Known Limitations

1. **Main-level `caller()[9]`**: For stack frames in main script code (not in a subroutine), `WarningBitsRegistry.get()` returns null. This is rare in practice since `caller()` is typically called from module code.

2. **Interpreter backend**: Uses a flag in opcodes. Same zero-overhead benefit when warnings disabled.

---

## Superseded Documents

The following documents were superseded by this one and have been deleted:
- `dev/design/warnings-scope.md` (deleted)
- `dev/design/WARNINGS_RUNTIME_FIX.md` (deleted)

---

## Progress Tracking

### Status: Phase 1 Complete (2026-03-29)

### Completed
- [x] Design document created
- [x] Superseded design documents deleted
- [x] Phase 1: Infrastructure (2026-03-29)
  - Created `WarningBitsRegistry.java` - HashMap registry for class name → warning bits
  - Enhanced `WarningFlags.java`:
    - Added `PERL5_OFFSETS` map with Perl 5 compatible category offsets
    - Added `userCategoryOffsets` for `warnings::register` support
    - Added `toWarningBitsString()` for caller()[9] bits format
    - Added `isEnabledInBits()` and `isFatalInBits()` utility methods
    - Added `registerUserCategoryOffset()` for dynamic category allocation
  - Enhanced `ScopedSymbolTable.java`:
    - Added `warningFatalStack` for FATAL warnings tracking
    - Updated `enterScope()`/`exitScope()` to handle fatal stack
    - Updated `snapShot()` and `copyFlagsFrom()` to copy fatal stack
    - Added `enableFatalWarningCategory()`, `disableFatalWarningCategory()`, `isFatalWarningCategory()`
    - Added `getWarningBitsString()` for caller()[9] support

### Next Steps
1. Implement Phase 2: Two-variant operator methods (add vs addWarn pattern)
2. Implement Phase 3: Per-closure warning bits storage for JVM backend
3. Implement Phase 4: Per-closure warning bits storage for interpreter
4. Continue with remaining phases (5-8)
