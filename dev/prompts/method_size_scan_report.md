# Method Size Scan Report - JIT Compilation Limits

**Date**: 2026-02-16
**Tool**: `dev/tools/scan-all-method-sizes.sh`
**JVM Limit**: ~8000 bytes (methods larger than this won't JIT-compile)
**Last Update**: 2026-02-16 - Phase 1 short opcodes migration complete

## Summary

Total methods analyzed: **4,013**

- 🟢 **Critical** (>= 8000 bytes): **0 methods** ✅
- 🟡 **Warning** (7000-8000 bytes): **1 method**
- 🟢 **Safe** (< 7000 bytes): **4,012 methods**

---

## Critical Methods (Won't JIT Compile)

**None!** All critical methods have been fixed. ✅

### 1. `BytecodeCompiler.visit(BinaryOperatorNode)` - ✅ FIXED

**Was**: 11,365 bytes (critical)
**Now**: < 7,000 bytes (safe)

**Solution Applied**: Split into helper methods
- Created `compileAssignmentOperator()` (5,008 bytes) - handles my/our/state variable initialization
- Created `compileBinaryOperatorSwitch()` (2,535 bytes) - handles binary operation dispatch
- Main `visit()` method now properly delegates to helpers

**Result**: Method now JIT-compiles successfully, improving eval STRING and script startup speed

---

### 2. `BytecodeCompiler.visit(OperatorNode)` - ✅ FIXED

**Was**: 9,544 bytes (critical)
**Now**: 5,743 bytes (safe) - **40% reduction!**

**Solution Applied**: Split into helper methods by operator category
- Created `compileVariableDeclaration()` - handles my/our/local declarations
- Created `compileVariableReference()` - handles $/\@/%/*/&/\ operators
- Main `visit()` method now properly delegates to helpers

**Result**: Method now JIT-compiles successfully, improving eval STRING and script startup speed

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
| 7,270 | 🟡 | `BytecodeInterpreter.execute()` (FIXED) |
| 6,959 | 🟢 | `InterpretedCode.disassemble()` |
| 5,743 | 🟢 | `BytecodeCompiler.visit(OperatorNode)` (FIXED) |
| 5,343 | 🟢 | `ControlFlowDetectorVisitor.scan()` |
| 5,178 | 🟢 | `ParserTables.<clinit>()` |
| 5,008 | 🟢 | `BytecodeCompiler.compileAssignmentOperator()` (NEW) |
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
| 2,535 | 🟢 | `BytecodeCompiler.compileBinaryOperatorSwitch()` (NEW) |

---

## Recommendations

### Immediate Action Required

**None!** ✅ All critical performance issues have been resolved.

### Completed Improvements

1. ✅ **BytecodeInterpreter.execute()** - Fixed (8,492 → 7,270 bytes)
   - Used range-based delegation pattern
   - Split into 4 secondary methods by opcode functionality
   - Achieved 8x speedup (5.03s → 0.63s for 50M iterations)

2. ✅ **BytecodeCompiler.visit(BinaryOperatorNode)** - Fixed (11,365 → <7,000 bytes)
   - Extracted `compileAssignmentOperator()` for variable initialization logic
   - Extracted `compileBinaryOperatorSwitch()` for binary operation dispatch
   - Improves eval STRING and script startup compilation speed

3. ✅ **BytecodeCompiler.visit(OperatorNode)** - Fixed (9,544 → 5,743 bytes)
   - Extracted `compileVariableDeclaration()` for my/our/local handling
   - Extracted `compileVariableReference()` for sigil operators
   - Improves eval STRING and script startup compilation speed

4. ✅ **Phase 1: Short Opcodes Migration** - Complete (2026-02-16)
   - Changed opcode type from `byte` to `short` in Opcodes.java
   - Updated BytecodeCompiler emit methods to accept short
   - Unlocked 32,768 opcode space (from 256 slots)
   - Room for 200+ OperatorHandler promotions + 41 SLOW_OP operations
   - All methods remain under 8000-byte limit
   - All unit tests passing
   - Zero performance impact (infrastructure already used short internally)

### Future Improvements

1. **Monitoring**:
   - Run `./dev/tools/scan-all-method-sizes.sh` after major changes
   - Watch for methods approaching 7,000 bytes
   - Proactively refactor before hitting 8,000-byte limit

2. **Build Integration** (optional):
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
