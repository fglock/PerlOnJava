# Superoperator Design for PerlOnJava Bytecode Interpreter

## Overview

This document analyzes common bytecode instruction sequences in PerlOnJava's interpreter
and proposes "superoperators" - combined opcodes that replace frequent multi-instruction
patterns with single, optimized operations.

## Analysis Methodology

Bytecode was collected from running ExifTool tests with `--interpreter --disassemble`:
```bash
cd Image-ExifTool-13.44
java -jar ../target/perlonjava-3.0.0.jar --interpreter --disassemble -Ilib t/ExifTool.t
```

## Top Operations by Frequency (ExifTool.t)

| Operation | Count | % of Total |
|-----------|-------|------------|
| LOAD_BYTE_STRING | 16,876 | 24.4% |
| LOAD_INT | 6,705 | 9.7% |
| RETURN | 6,492 | 9.4% |
| CREATE_LIST | 5,124 | 7.4% |
| ALIAS | 4,446 | 6.4% |
| LOAD_STRING | 4,042 | 5.8% |
| DEREF_HASH | 3,626 | 5.2% |
| GOTO_IF_FALSE | 3,425 | 5.0% |
| HASH_GET | 3,252 | 4.7% |
| LOAD_UNDEF | 2,420 | 3.5% |
| MATCH_REGEX | 2,125 | 3.1% |
| LOAD_GLOBAL_SCALAR | 2,117 | 3.1% |

## Common Two-Instruction Sequences

| Sequence | Count |
|----------|-------|
| LOAD_BYTE_STRING -> LOAD_BYTE_STRING | 8,587 |
| DEREF_HASH -> LOAD_STRING | 3,014 |
| LOAD_STRING -> HASH_GET | 2,558 |
| LOAD_BYTE_STRING -> QUOTE_REGEX | 1,782 |
| MATCH_REGEX -> ALIAS | 1,660 |
| CREATE_LIST -> CALL_SUB | 1,166 |

## Common Three-Instruction Sequences

| Sequence | Count |
|----------|-------|
| DEREF_HASH -> LOAD_STRING -> HASH_GET | 2,498 |
| LOAD_STRING -> HASH_GET -> MATCH_REGEX | 1,196 |
| MATCH_REGEX -> ALIAS -> RESTORE_REGEX_STATE | 1,188 |
| CREATE_LIST -> CALL_SUB -> RETURN | 681 |

---

## Proposed Superoperators

### 1. HASH_DEREF_FETCH (Priority: P1 - HIGHEST)

**Pattern replaced:**
```
DEREF_HASH r26 = %{r22}
LOAD_STRING r27 = "key"
HASH_GET r28 = r26{r27}
```

**New opcode:**
```
HASH_DEREF_FETCH r28 = %{r22}{"key"}
```

**Occurrences:** 2,498 (ExifTool.t)

**Semantics:**
- Input: hashref register, string constant index
- Output: fetched value
- Equivalent to: `$$hashref{key}` or `$hash->{key}`

**Bytecode format:**
```
HASH_DEREF_FETCH dest_reg, hashref_reg, string_constant_index
```

**Implementation notes:**
- Combines hash dereference + key load + hash get into single dispatch
- Eliminates 2 intermediate register allocations
- String key is stored in constants table (already interned)

---

### 2. ARRAY_DEREF_FETCH (Priority: P1)

**Pattern replaced:**
```
DEREF_ARRAY r10 = @{r8}
LOAD_INT r11 = 0
ARRAY_GET r12 = r10[r11]
```

**New opcode:**
```
ARRAY_DEREF_FETCH r12 = @{r8}[0]
```

**Occurrences:** ~700 (estimated from DEREF_ARRAY + LOAD_INT + ARRAY_GET sequences)

**Semantics:**
- Input: arrayref register, integer index (constant or register)
- Output: fetched element
- Equivalent to: `$$arrayref[n]` or `$array->[n]`

**Bytecode format:**
```
ARRAY_DEREF_FETCH dest_reg, arrayref_reg, index_constant
ARRAY_DEREF_FETCH_REG dest_reg, arrayref_reg, index_reg  # for variable indices
```

---

### 3. MATCH_ALIAS_RESTORE (Priority: P2)

**Pattern replaced:**
```
MATCH_REGEX r10 = r8 =~ r9
ALIAS r11 = r10
RESTORE_REGEX_STATE
```

**New opcode:**
```
MATCH_ALIAS_RESTORE r11 = r8 =~ r9
```

**Occurrences:** 1,188

**Semantics:**
- Perform regex match
- Alias result to destination
- Restore regex state
- All in one dispatch

---

### 4. HASH_FETCH_MATCH (Priority: P2)

**Pattern replaced:**
```
LOAD_STRING r27 = "key"
HASH_GET r28 = r26{r27}
MATCH_REGEX r29 = r28 =~ r30
```

**New opcode:**
```
HASH_FETCH_MATCH r29 = r26{"key"} =~ r30
```

**Occurrences:** 1,196

---

### 5. EQ_STR_BRANCH / EQ_NUM_BRANCH (Priority: P3)

**Pattern replaced:**
```
EQ_STR r10 = r8 eq r9
GOTO_IF_FALSE r10 -> target
```

**New opcode:**
```
EQ_STR_BRANCH_FALSE r8, r9 -> target
```

**Occurrences:** 433 (string) + 396 (numeric) = 829

---

### 6. CALL_RETURN (Priority: P3)

**Pattern replaced:**
```
CREATE_LIST r7 = []
CALL_SUB r3 = r6->(r7, ctx=2)
RETURN r3
```

**New opcode:**
```
CALL_RETURN r3 = r6->([])
```

**Occurrences:** 681

---

## Implementation Priority Matrix

| Superoperator | Ops Saved | Total Savings | Complexity | Priority |
|---------------|-----------|---------------|------------|----------|
| HASH_DEREF_FETCH | 2 | ~5,000 | Low | **P1** |
| ARRAY_DEREF_FETCH | 2 | ~1,400 | Low | **P1** |
| MATCH_ALIAS_RESTORE | 2 | ~2,400 | Medium | **P2** |
| HASH_FETCH_MATCH | 2 | ~2,400 | Medium | **P2** |
| EQ_STR_BRANCH | 1 | ~830 | Low | **P3** |
| CALL_RETURN | 2 | ~1,360 | Low | **P3** |

---

## Files to Modify

### Opcodes.java
Add new opcode constants:
```java
public static final int HASH_DEREF_FETCH = 0x80;
public static final int ARRAY_DEREF_FETCH = 0x81;
public static final int MATCH_ALIAS_RESTORE = 0x82;
// etc.
```

### BytecodeCompiler.java
Detect patterns during compilation and emit superoperators:
- In `visit(HashAccessNode)` - detect deref + fetch pattern
- In `visit(ArrayAccessNode)` - detect deref + fetch pattern

### BytecodeInterpreter.java
Add handler cases for new opcodes:
```java
case Opcodes.HASH_DEREF_FETCH: {
    int destReg = code[ip++];
    int hashrefReg = code[ip++];
    int keyConstIdx = code[ip++];
    RuntimeHash hash = registers[hashrefReg].hashDeref();
    String key = (String) constants[keyConstIdx];
    registers[destReg] = hash.get(key);
    break;
}
```

---

## Performance Expectations

Implementing HASH_DEREF_FETCH and ARRAY_DEREF_FETCH alone would:
- Eliminate ~6,400 instruction dispatches in ExifTool tests
- Reduce interpreter loop iterations by ~10%
- Improve cache locality (fewer register accesses)

The benefits compound in tight loops where hash/array access is repeated.

---

## Testing Strategy

1. Run existing test suite to ensure no regressions
2. Compare bytecode output before/after (count instructions)
3. Benchmark ExifTool test execution time
4. Verify correctness with edge cases:
   - Undefined hash/array refs
   - Autovivification
   - Tied hashes/arrays
   - Magical variables

---

## Profile Comparison: ExifTool vs life_bitpacked.pl

To validate superoperator priorities, we analyzed `examples/life_bitpacked.pl` which has a very different workload profile (bitwise operations vs hash access).

### life_bitpacked.pl - Top Operations

| Operation | Count | Notes |
|-----------|-------|-------|
| LOAD_GLOBAL_SCALAR | 429 | Package variable access |
| SET_PACKAGE | 379 | Mostly Getopt::Long |
| LOAD_INT | 342 | Heavy numeric computation |
| LOAD_BYTE_STRING | 326 | String constants |
| CREATE_LIST | 229 | |
| GOTO_IF_FALSE | 143 | Conditionals |
| BITWISE_AND_BINARY | 56 | Bitpacking operations |
| BITWISE_OR_BINARY | 32 | Bitpacking operations |

### life_bitpacked.pl - Top Two-Instruction Sequences

| Sequence | Count |
|----------|-------|
| SET_PACKAGE 'Getopt::Long' -> LOAD_GLOBAL_SCALAR | 326 |
| LOAD_BYTE_STRING -> LOAD_BYTE_STRING | 108 |
| LOAD_BYTE_STRING -> LOAD_INT | 81 |
| CREATE_LIST -> CALL_SUB | 63 |
| LOAD_BYTE_STRING -> EQ_STR | 38 |
| CREATE_LIST -> JOIN | 38 |
| EQ_STR -> GOTO_IF_FALSE | 33 |
| LOAD_INT -> BITWISE_AND_BINARY | 30 |

### life_bitpacked.pl - Top Three-Instruction Sequences

| Sequence | Count |
|----------|-------|
| SET_PACKAGE 'Getopt::Long' -> LOAD_GLOBAL_SCALAR -> SET_PACKAGE 'Getopt::Long' | 310 |
| CREATE_LIST -> CALL_SUB -> RETURN | 38 |
| LOAD_BYTE_STRING -> EQ_STR -> GOTO_IF_FALSE | 29 |
| LOAD_INT -> BITWISE_AND_BINARY -> LOAD_INT | 19 |
| RIGHT_SHIFT -> LOAD_INT -> BITWISE_AND_BINARY | 18 |
| LOAD_INT -> LEFT_SHIFT -> BITWISE_OR_BINARY | 17 |
| BITWISE_AND_BINARY -> LOAD_INT -> LEFT_SHIFT | 17 |

### Cross-Workload Analysis

**Common patterns (good superoperator candidates):**
1. `CREATE_LIST -> CALL_SUB -> RETURN` - appears in both workloads
2. `EQ_STR -> GOTO_IF_FALSE` - conditional string comparison
3. `LOAD_INT -> BITWISE_AND_BINARY` - bit extraction

**ExifTool-specific patterns (hash-heavy):**
1. `DEREF_HASH -> LOAD_STRING -> HASH_GET` - hash dereference (2,498 occurrences)
2. `MATCH_REGEX -> ALIAS -> RESTORE_REGEX_STATE` - regex matching (1,188 occurrences)

**life_bitpacked.pl-specific patterns (bitwise-heavy):**
1. `LOAD_INT -> BITWISE_AND_BINARY -> LOAD_INT` - bit masking
2. `RIGHT_SHIFT -> LOAD_INT -> BITWISE_AND_BINARY` - bit extraction
3. `LOAD_INT -> LEFT_SHIFT -> BITWISE_OR_BINARY` - bit packing

### Revised Priority Recommendations

Based on cross-workload analysis:

| Priority | Superoperator | Rationale |
|----------|---------------|-----------|
| **P1** | HASH_DEREF_FETCH | High impact for hash-heavy code (ExifTool, most real apps) |
| **P1** | EQ_STR_BRANCH | Common in both workloads |
| **P2** | CALL_RETURN | Common in both workloads |
| **P2** | MATCH_ALIAS_RESTORE | High impact for regex-heavy code |
| **P3** | BIT_EXTRACT (new) | `(value >> shift) & mask` for numeric code |
| **P3** | BIT_INSERT (new) | `value | (bits << shift)` for numeric code |

---

## Future Work

- **Peephole optimizer**: Post-compilation pass to detect and replace patterns
- **Profile-guided optimization**: Collect runtime frequency data to prioritize hot patterns
- **JIT hints**: Mark hot superoperator sequences for potential JVM compilation

---

## Appendix: Raw Analysis Commands

```bash
# Count single operations
grep -E '^\s+[0-9]+:' bytecode.txt | sed 's/^[^:]*: //' | \
  sed 's/ r[0-9].*$//' | sort | uniq -c | sort -rn

# Count two-instruction sequences
grep -E '^\s+[0-9]+:' bytecode.txt | sed 's/^[^:]*: //' | \
  sed 's/ r[0-9].*$//' | \
  awk 'NR>1{print prev" -> "$0} {prev=$0}' | sort | uniq -c | sort -rn

# Count three-instruction sequences
grep -E '^\s+[0-9]+:' bytecode.txt | sed 's/^[^:]*: //' | \
  sed 's/ r[0-9].*$//' | \
  awk 'NR>2{print prev2" -> "prev" -> "$0} {prev2=prev; prev=$0}' | \
  sort | uniq -c | sort -rn
```

---

## Progress Tracking

### Current Status: Phase 3 Complete - P1 Superoperators Implemented

### Completed Phases
- [x] Phase 1: ExifTool bytecode analysis (2025-03-12)
  - Generated bytecode from Image-ExifTool-13.44/t/ExifTool.t
  - Identified HASH_DEREF_FETCH as highest-impact superoperator
  - Documented 6 proposed superoperators with implementation details

- [x] Phase 2: Cross-workload validation (2025-03-12)
  - Analyzed examples/life_bitpacked.pl (numeric/bitwise workload)
  - Validated that EQ_STR_BRANCH and CALL_RETURN are universal
  - Identified workload-specific patterns (hash vs bitwise)
  - Updated priority recommendations based on cross-workload data

- [x] Phase 3: P1 Superoperator Implementation (2025-03-12)
  - Implemented HASH_DEREF_FETCH (opcode 381)
    - Combines: DEREF_HASH + LOAD_STRING + HASH_GET
    - Format: HASH_DEREF_FETCH rd hashref_reg key_string_idx
    - Optimizes: $hashref->{key} with bareword or string literal key
  - Implemented ARRAY_DEREF_FETCH (opcode 382)
    - Combines: DEREF_ARRAY + LOAD_INT + ARRAY_GET
    - Format: ARRAY_DEREF_FETCH rd arrayref_reg index_immediate
    - Optimizes: $arrayref->[n] with integer literal index
  - Files modified:
    - Opcodes.java: Added opcode constants 381, 382
    - CompileBinaryOperator.java: Added pattern detection for -> operator
    - BytecodeCompiler.java: Added pattern detection for general access
    - BytecodeInterpreter.java: Added execution handlers
    - Disassemble.java: Added disassembly support

### Next Steps
1. Implement EQ_STR_BRANCH superoperator (P1)
2. Benchmark performance improvement
3. Consider implementing MATCH_ALIAS_RESTORE (P2)

### Resolved Questions
- Superoperators are emitted at compile time (pattern detection during bytecode generation)
- Autovivification and tied variables: handled by hashDeref()/arrayDeref() calls in handler

### Phase 3.1: Code Refactoring (2025-03-12)
- Added `emitHashDerefGet()` and `emitArrayDerefGet()` helpers in BytecodeCompiler.java
- Refactored `handleGeneralHashAccess()` and `handleGeneralArrayAccess()` to use helpers
- Refactored CompileBinaryOperator.java `->` operator handling to use helpers
- **Result**: Superoperators now work for both `$h->{a}{b}` (implicit arrows) and `$h->{a}->{b}` (explicit arrows)
- Code duplication reduced across 3 call sites

### Phase 3.2: Bug Fix - RuntimeList Handling (2025-03-12)
- **Bug**: Superoperators in `handleGeneralArrayAccess()` and `handleGeneralHashAccess()` 
  caused `(caller)[0]` and similar expressions to fail with:
  `Can't use string ("...") as an ARRAY ref while "strict refs" in use`
- **Root cause**: Superoperators (`ARRAY_DEREF_FETCH`, `HASH_DEREF_FETCH`) expect a scalar
  containing a reference, but these handlers can receive a RuntimeList (e.g., from `(caller)`)
- **Fix**: Reverted `handleGeneralArrayAccess()` and `handleGeneralHashAccess()` to use 
  the original DEREF_ARRAY/HASH + ARRAY/HASH_GET instruction sequence, which correctly
  handles all input types (RuntimeArray, RuntimeList, RuntimeScalar with reference)
- **Superoperators remain in**: CompileBinaryOperator.java `->` operator handler, where
  the left side is always compiled in SCALAR context and thus guaranteed to be a scalar reference
- This fix resolves the Getopt::Long / life_bitpacked.pl regression
