# Interpreter Eval Mode Test Improvements

## Final Status: 341/353 tests passing

### Progress Summary
- **Baseline**: 320/353 (from logs/test_20260216_172100.log)
- **Final**: 341/353
- **Improvement**: +21 tests (+6.6%)
- **Target**: 346/353 (need +5 more)

### Changes Implemented

1. **Added Missing Operators** (320 → 323, +3 tests)
   - getppid, getpgrp, setpgrp, getpriority, atan2

2. **Fixed ClassCastException** (323 → 339, +16 tests)
   - Ensured all misc operators receive RuntimeList arguments
   - Added list context evaluation with SCALAR_TO_LIST wrapping
   - Fixed 18 operators: chmod, unlink, utime, rename, link, readlink, umask, 
     getc, fileno, qx, system, caller, each, pack, vec, localtime, gmtime, crypt

3. **Fixed Operator Validation** (339 → 340, +1 test)
   - chmod: Return 0 instead of throwing on insufficient arguments

4. **Fixed crypt Determinism** (340 → 341, +1 test)
   - Pad 1-character salt with '.' for deterministic results

### Remaining 12 Failures

1. **Test 3**: Object destruction (DESTROY mechanism) - Complex
2. **Tests 19, 21**: chop/chomp readonly modification - Needs special handling
3. **Test 86**: each %h - Variable scope issue in eval
4. **Test 89**: %$href - Hash dereference not implemented
5. **Test 91**: split - ClassCastException
6. **Tests 93, 94**: push/unshift - Need opcode implementation
7. **Test 101**: warn - CODE reference issue
8. **Tests 106, 107**: select - ClassCastException
9. **Test 145**: setpgrp - Validation (defaults to 0,0 when no args)

### Quickest Wins for 346/353

To reach 346, these are most accessible:
1. **setpgrp validation** - Allow 0 args, default to setpgrp(0,0)
2. **each %h scope** - Fix variable lookup in eval context  
3. Potentially other validation fixes

### Files Modified

- `src/main/java/org/perlonjava/interpreter/Opcodes.java` - Added opcodes 241-301
- `src/main/java/org/perlonjava/interpreter/BytecodeInterpreter.java` - Added MiscOpcodeHandler cases
- `src/main/java/org/perlonjava/interpreter/CompileOperator.java` - Added operator compilation
- `src/main/java/org/perlonjava/interpreter/MiscOpcodeHandler.java` - New handler class
- `src/main/java/org/perlonjava/interpreter/ScalarUnaryOpcodeHandler.java` - Generated
- `src/main/java/org/perlonjava/interpreter/ScalarBinaryOpcodeHandler.java` - Generated
- `src/main/java/org/perlonjava/operators/Operator.java` - Fixed chmod validation
- `src/main/java/org/perlonjava/operators/Crypt.java` - Fixed salt padding
- `dev/tools/generate_opcode_handlers.pl` - Updated for refactored code
