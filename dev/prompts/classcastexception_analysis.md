# ClassCastException Analysis - perf/benchmarks.t

## Issue

When running `perl5_t/t/perf/benchmarks.t` with `JPERL_EVAL_USE_INTERPRETER=1`, 74 tests fail with ClassCastException.

## Minimal Reproduction

```perl
my ($x,$y,$z);
my $r = [];
for (1..1) {
    ($x,$y,$z) = ($r,$r,$r);
    ($x,$y,$z) = ();  # <-- ClassCastException here
}
```

Error:
```
Interpreter error in (eval) line 1 (pc=43): ClassCastException
class java.lang.Integer cannot be cast to class java.util.Iterator
```

## Investigation

The error occurs at bytecode position pc=43, opcode 0x89 (137 decimal = SETPGRP, but that's likely misreported).

The actual issue is in FOREACH_NEXT_OR_EXIT (opcode 109) which does:
```java
RuntimeScalar iterScalar = (RuntimeScalar) registers[iterReg];
java.util.Iterator<RuntimeScalar> iterator = 
    (java.util.Iterator<RuntimeScalar>) iterScalar.value;
```

When an eval happens inside a for loop and that eval contains operations that access registers, there's a mismatch where:
1. The for loop stores its Iterator in a RuntimeScalar in a register
2. The eval'd code tries to use the same register for something else
3. At some point, an Integer value ends up where an Iterator is expected

## Attempted Fixes

### Fix 1: Filter Iterator Objects from Captured Variables ✅ Partial
Added filtering in `EvalStringHandler.java` to skip RuntimeScalars containing Iterator objects when building captured variables.

**Result:** Good defensive measure, but doesn't solve the ClassCastException.

### Root Cause (Hypothesis)

The issue is likely in how `BytecodeCompiler` allocates registers when compiling eval'd code:
1. Parent code has a for loop with iterator in register N
2. Eval'd code gets compiled with captured variables mapped to registers 3+
3. The eval'd code has its own for loop which tries to allocate a register for its iterator
4. Register allocation conflict or state corruption causes wrong types in registers

## Affected Tests

74 failures in `perf/benchmarks.t`, primarily:
- `expr::aassign::*` - Array/list assignment operations
- `expr::concat::*` - String concatenation operations  
- `expr::hash::*` - Hash operations in boolean context
- `func::grep/keys/split/sprintf::*` - Built-in functions

## Next Steps (Unresolved)

To fix this properly would require:
1. Deep dive into `BytecodeCompiler.visit(For1Node)` - how iterators are allocated
2. Review register allocation strategy in eval context
3. Ensure iterator registers don't conflict with captured variable registers
4. Possibly separate "temp" registers (for iterators) from "variable" registers

This is a complex issue requiring significant BytecodeCompiler refactoring.

## Workaround

Tests pass in compiler mode (without `JPERL_EVAL_USE_INTERPRETER=1`).
