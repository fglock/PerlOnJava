# For1 Loop Superinstruction Design

## Current Implementation Analysis

### Bytecode Pattern
A typical `for my $x (@array) { body }` generates:

```
# Setup (before loop)
list_eval                    # Evaluate @array
ITERATOR_CREATE r_iter, r_list  # Create iterator from list

# Loop iteration (repeated for each element)
loop_start:
  ITERATOR_HAS_NEXT r_bool, r_iter    # Check if more elements (1 dispatch)
  GOTO_IF_FALSE r_bool -> loop_end    # Exit check (1 dispatch)
  ITERATOR_NEXT r_var, r_iter         # Get next element (1 dispatch)
  ... body bytecodes ...              # User code
  GOTO -> loop_start                  # Back to start (1 dispatch)
loop_end:
```

### Performance Overhead
**Per iteration overhead: 4 opcode dispatches**
1. ITERATOR_HAS_NEXT
2. GOTO_IF_FALSE
3. ITERATOR_NEXT
4. GOTO (back jump)

For a loop with 1000 iterations, this is **4000 opcode dispatches** just for loop control.

### Optimization Opportunity
The pattern `ITERATOR_HAS_NEXT + GOTO_IF_FALSE + ITERATOR_NEXT` appears in **every** For1 loop and is highly predictable. This is an ideal candidate for a superinstruction.

## Proposed Superinstruction: FOREACH_LOOP

### Design Option 1: Combined Check-Next-Jump

Create a single opcode that combines the iteration check, element fetch, and loop control:

```java
FOREACH_LOOP r_var, r_iter, body_length
```

**Semantics:**
1. Check `iterator.hasNext()`
2. If false: skip forward by `body_length` shorts (exit loop)
3. If true: `r_var = iterator.next()`, continue to next instruction (body)
4. After body, emit `GOTO_BACK` to return to FOREACH_LOOP

**Bytecode structure:**
```
loop_start:
  FOREACH_LOOP r_var, r_iter, body_length  # Single dispatch per iteration
  ... body bytecodes ...
  GOTO_BACK -> loop_start
loop_end:
```

**Benefits:**
- Reduces 3 dispatches per iteration to 1 (3x reduction in loop overhead)
- Better instruction cache locality
- Fewer PC updates and bounds checks

**Tradeoffs:**
- Need to calculate `body_length` at compile time
- Slightly more complex opcode implementation
- Less flexible for optimization passes

### Design Option 2: Fused Check-Next

Combine only `ITERATOR_HAS_NEXT + ITERATOR_NEXT`:

```java
FOREACH_NEXT_OR_EXIT r_var, r_iter, exit_offset
```

**Semantics:**
1. Check `iterator.hasNext()`
2. If false: jump forward by `exit_offset`
3. If true: `r_var = iterator.next()`, fall through

**Bytecode structure:**
```
loop_start:
  FOREACH_NEXT_OR_EXIT r_var, r_iter, exit_offset  # 1 dispatch
  ... body bytecodes ...
  GOTO -> loop_start                              # 1 dispatch
loop_end:
```

**Benefits:**
- Reduces 3 dispatches to 2 (50% reduction)
- Simpler implementation than Option 1
- Still uses standard GOTO for back-jump
- Easier to integrate with existing code

**Tradeoffs:**
- Not quite as optimal as Option 1
- Still need GOTO for loop back

### Design Option 3: Full Loop Superinstruction

Create a complete loop handler that executes the entire loop:

```java
FOREACH_SUPERLOOP r_var, r_iter, body_code_ref
```

**Semantics:**
- Completely handles the loop: hasNext check, element assignment, body execution
- Body is compiled as separate InterpretedCode and stored in constant pool
- Loop control is internal to the opcode

**Benefits:**
- Maximum optimization potential
- Could inline simple bodies
- Opportunity for JIT-style optimizations

**Tradeoffs:**
- Much more complex implementation
- Breaks debugging/stepping model
- May hurt performance for complex bodies due to interpreter overhead
- Reduced visibility for profiling

## Recommended Approach: Option 2 (FOREACH_NEXT_OR_EXIT)

### Rationale
1. **Good performance improvement**: 50% reduction in loop overhead is significant
2. **Moderate complexity**: Easier to implement and test than Option 1 or 3
3. **Maintainability**: Fits naturally into existing bytecode model
4. **Debugging**: Still allows instruction-level stepping
5. **Future-proof**: Can evolve to Option 1 later if needed

### Implementation Plan

#### 1. Add New Opcode

**File:** `Opcodes.java`
```java
// Superinstruction for foreach loops
// rd = iterator.next() if hasNext, else jump forward
// Format: FOREACH_NEXT_OR_EXIT rd iter_reg exit_offset(int)
public static final byte FOREACH_NEXT_OR_EXIT = 109;
```

#### 2. Update BytecodeCompiler

**File:** `BytecodeCompiler.java` - `visit(For1Node)`

Replace:
```java
// Old pattern
ITERATOR_HAS_NEXT hasNextReg, iterReg
GOTO_IF_FALSE hasNextReg, exit_offset
ITERATOR_NEXT varReg, iterReg
```

With:
```java
// New superinstruction
FOREACH_NEXT_OR_EXIT varReg, iterReg, exit_offset
```

**Code changes:**
```java
// Step 5: Loop start - combined check/next/exit
int loopStartPc = bytecode.size();

// Emit superinstruction
emit(Opcodes.FOREACH_NEXT_OR_EXIT);
emitReg(varReg);        // destination register for element
emitReg(iterReg);       // iterator register
int loopEndJumpPc = bytecode.size();
emitInt(0);             // placeholder for exit offset (to be patched)

// Step 6: Execute body
if (node.body != null) {
    node.body.accept(this);
}

// Step 7: Jump back to loop start
emit(Opcodes.GOTO);
emitInt(loopStartPc);

// Step 8: Loop end - patch the forward jump
int loopEndPc = bytecode.size();
patchJump(loopEndJumpPc, loopEndPc);
```

#### 3. Implement Interpreter Logic

**File:** `BytecodeInterpreter.java`

```java
case Opcodes.FOREACH_NEXT_OR_EXIT: {
    // Superinstruction: check hasNext, get next element, or exit
    // rd = iterator.next() if hasNext, else jump forward
    int rd = bytecode[pc++];
    int iterReg = bytecode[pc++];
    int exitOffset = readInt(bytecode, pc);
    pc += 2;  // Skip the int we just read

    RuntimeScalar iterScalar = (RuntimeScalar) registers[iterReg];
    @SuppressWarnings("unchecked")
    java.util.Iterator<RuntimeScalar> iterator =
        (java.util.Iterator<RuntimeScalar>) iterScalar.value;

    if (iterator.hasNext()) {
        // Get next element and continue
        registers[rd] = iterator.next();
        // Fall through to body (next instruction)
    } else {
        // Exit loop - jump forward
        pc += exitOffset;
    }
    break;
}
```

#### 4. Add Disassembler Support

**File:** `InterpretedCode.java`

```java
case Opcodes.FOREACH_NEXT_OR_EXIT:
    rd = bytecode[pc++];
    int iterReg = bytecode[pc++];
    int exitOffset = readInt(bytecode, pc);
    pc += 2;
    sb.append("FOREACH_NEXT_OR_EXIT r").append(rd)
      .append(" = r").append(iterReg).append(".next() or exit(+")
      .append(exitOffset).append(")\n");
    break;
```

### Performance Expected Impact

**Benchmark:** `for my $i (1..1000000) { $sum += $i }`

**Before:**
- 4 million opcode dispatches for loop control
- ~3 million for body operations
- Total: ~7 million dispatches

**After:**
- 2 million opcode dispatches for loop control (50% reduction)
- ~3 million for body operations (unchanged)
- Total: ~5 million dispatches (28% overall reduction)

**Expected speedup:** 1.3x - 1.5x for loop-heavy code

### Testing Strategy

1. **Unit tests:** Verify correctness of superinstruction
   - Empty loop
   - Loop with simple body
   - Loop with complex body
   - Nested loops
   - Early exit (last/next)

2. **Regression tests:** Ensure existing tests pass
   ```bash
   make test-unit
   ./jperl --interpreter src/test/resources/unit/demo.t
   ```

3. **Performance tests:** Measure speedup
   ```perl
   # Benchmark: tight loop
   use Benchmark qw(timethis);
   timethis(1, sub {
       my $sum = 0;
       for my $i (1..1000000) { $sum += $i }
   });
   ```

4. **Disassembly verification:**
   ```bash
   ./jperl --interpreter --disassemble test.pl
   # Should show FOREACH_NEXT_OR_EXIT instead of separate opcodes
   ```

### Future Enhancements

1. **Specialize for common cases:**
   - `FOREACH_RANGE_INT`: Optimized for integer ranges (1..N)
   - `FOREACH_ARRAY_DIRECT`: Direct array access without iterator overhead

2. **Evolve to Option 1:** If profiling shows GOTO_BACK is still significant

3. **JIT compilation:** Hot loops could be compiled to native code

## Alternative Consideration: Counted Loops

For simple integer ranges, we could detect:
```perl
for my $i (0..999) { body }
```

And emit a specialized counted loop opcode:
```java
FOREACH_COUNTED_LOOP r_var, start, end, body_len
```

This would be even faster than iterator-based loops for the common case.

---

**Document Author:** Claude Opus 4.6
**Date:** 2026-02-14
**Status:** Design Proposal
