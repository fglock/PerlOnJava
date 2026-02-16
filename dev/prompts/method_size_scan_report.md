# Method Size Scan Report - JIT Compilation Limits

**Date**: 2026-02-16
**Tool**: `dev/tools/scan-all-method-sizes.sh`
**JVM Limit**: ~8000 bytes (methods larger than this won't JIT-compile)

## Summary

Total methods analyzed: **4,009**

- 🔴 **Critical** (>= 8000 bytes): **2 methods**
- 🟡 **Warning** (7000-8000 bytes): **1 method**
- 🟢 **Safe** (< 7000 bytes): **4,006 methods**

---

## Critical Methods (Won't JIT Compile)

### 1. `BytecodeCompiler.visit(BinaryOperatorNode)` - 11,365 bytes

**Location**: `src/main/java/org/perlonjava/interpreter/BytecodeCompiler.java`

**Impact**: Affects **compilation speed**
- Slows down script startup when compiling
- Slows down `eval STRING` compilation
- Does NOT affect runtime execution of already-compiled code

**Priority**: Medium (compilation-time only)

**Recommended Solution**:
- Split into helper methods by operator type
- Delegate arithmetic operators to `compileBinaryArithmetic()`
- Delegate comparison operators to `compileBinaryComparison()`
- Delegate assignment operators to `compileBinaryAssignment()`
- Keep only dispatch logic in main `visit()` method

---

### 2. `BytecodeCompiler.visit(OperatorNode)` - 9,544 bytes

**Location**: `src/main/java/org/perlonjava/interpreter/BytecodeCompiler.java`

**Impact**: Affects **compilation speed**
- Slows down script startup when compiling
- Slows down `eval STRING` compilation
- Does NOT affect runtime execution of already-compiled code

**Priority**: Medium (compilation-time only)

**Recommended Solution**:
- Split into helper methods by operator category
- Delegate I/O operators to `compileIOOperator()`
- Delegate list operators to `compileListOperator()`
- Delegate control flow to `compileControlFlowOperator()`
- Keep only dispatch logic in main `visit()` method

---

## Warning Methods (Close to Limit)

### 3. `BytecodeInterpreter.execute()` - 7,270 bytes ✅

**Location**: `src/main/java/org/perlonjava/interpreter/BytecodeInterpreter.java`

**Status**: **FIXED** (was 8,492 bytes, reduced to 7,270 bytes)

**Solution Applied**: Range-based delegation
- Split cold-path opcodes into 4 secondary methods
- `executeComparisons()` - 1,089 bytes
- `executeArithmetic()` - 1,057 bytes
- `executeCollections()` - 1,025 bytes
- `executeTypeOps()` - 929 bytes

**Performance Impact**: 8x speedup achieved (5.03s → 0.63s for 50M iterations)

---

## Top 20 Largest Methods

| Size (bytes) | Status | Method |
|--------------|--------|--------|
| 11,365 | 🔴 | `BytecodeCompiler.visit(BinaryOperatorNode)` |
| 9,544 | 🔴 | `BytecodeCompiler.visit(OperatorNode)` |
| 7,270 | 🟡 | `BytecodeInterpreter.execute()` (FIXED) |
| 6,959 | 🟢 | `InterpretedCode.disassemble()` |
| 5,343 | 🟢 | `ControlFlowDetectorVisitor.scan()` |
| 5,178 | 🟢 | `ParserTables.<clinit>()` |
| 4,604 | 🟢 | `CoreOperatorResolver.parseCoreOperator()` |
| 4,468 | 🟢 | `EmitOperatorNode.emitOperatorNode()` |
| 4,370 | 🟢 | `StatementResolver.parseStatement()` |
| 4,335 | 🟢 | `EmitterMethodCreator.getBytecodeInternal()` |
| 4,184 | 🟢 | `ParseInfix.parseInfixOperation()` |
| 3,365 | 🟢 | `Lexer.consumeOperator()` |
| 3,186 | 🟢 | `OperatorHandler.<clinit>()` |
| 3,005 | 🟢 | `EmitForeach.emitFor1()` |
| 2,953 | 🟢 | `EmitBinaryOperatorNode.emitBinaryOperatorNode()` |
| 2,814 | 🟢 | `Unpack.unpackInternal()` |
| 2,751 | 🟢 | `ModuleOperators.doFile()` |
| 2,742 | 🟢 | `EmitEval.handleEvalOperator()` |
| 2,670 | 🟢 | `FileTestOperator.fileTest()` |
| 2,481 | 🟢 | `EmitVariable.handleVariableOperator()` |

---

## Recommendations

### Immediate Action Required

**None** - The critical runtime performance issue (BytecodeInterpreter) has been fixed.

### Future Improvements

1. **BytecodeCompiler.visit() methods** (when time permits):
   - Use same range-based delegation pattern as BytecodeInterpreter
   - Split into ~5 secondary methods by operator category
   - Will improve compilation speed for large scripts

2. **Monitoring**:
   - Run `./dev/tools/scan-all-method-sizes.sh` after major changes
   - Watch for methods approaching 7,000 bytes
   - Proactively refactor before hitting 8,000-byte limit

3. **Build Integration** (optional):
   - Add scan to CI/CD pipeline
   - Fail builds if new critical methods are introduced
   - Set threshold at 7,500 bytes for early warning

---

## Technical Notes

### Why 8,000 bytes?

The JVM has a hard-coded limit controlled by the `-XX:DontCompileHugeMethods` flag (default: true). Methods exceeding ~8,000 bytes of JVM bytecode:
- Cannot be JIT-compiled by C1 or C2 compilers
- Run in interpreter mode only
- Experience 5-10x performance degradation
- No warmup or optimization occurs

### Scanner Implementation

The scanner uses `javap -c -private` to disassemble all compiled classes and extracts bytecode offsets. It correctly handles:
- `lookupswitch`/`tableswitch` case labels (ignores these)
- Multiple methods per class
- Private/protected/package-private methods
- Inner classes

### Verification

```bash
# Run full scan
./dev/tools/scan-all-method-sizes.sh

# Check specific method
javap -c -private -classpath build/classes/java/main \
  org.perlonjava.interpreter.BytecodeInterpreter | \
  grep "public static.*execute" -A 500 | tail -5
```

---

**Generated**: 2026-02-16
**Tool Version**: 1.0
**Scan Time**: ~2 seconds for 4,009 methods
