# Interpreter: Added Support for Negated Regex Match (!~) Operator

**Date:** 2026-02-18
**Status:** ✓ Complete

## Problem

The test `perl5_t/t/re/charset.t` was failing when run with `JPERL_EVAL_USE_INTERPRETER=1` due to missing support for the `!~` (negated regex match) operator in the interpreter's bytecode compiler.

### Discovery Process

The error was hidden inside eval blocks. Added temporary debug output to expose the actual exception:

```
Unsupported operator: !~ at (eval 345) line 1, near ") /x"
```

The error occurred in `BytecodeCompiler.compileBinaryOperatorSwitch` at line 2998 (the default case that throws for unsupported operators).

## Solution

Implemented the `!~` operator following the SKILL.md guide for adding new operators:

### 1. Added Opcode Definition (Opcodes.java)
```java
public static final short MATCH_REGEX_NOT = 217;
```

### 2. Added Compiler Support (BytecodeCompiler.java)
Added case for `!~` in `compileBinaryOperatorSwitch`:
```java
case "!~" -> {
    // $string !~ /pattern/ - negated regex match
    emit(Opcodes.MATCH_REGEX_NOT);
    emitReg(rd);
    emitReg(rs1);
    emitReg(rs2);
    emit(currentCallContext);
}
```

### 3. Added Runtime Implementation (BytecodeInterpreter.java)
```java
case Opcodes.MATCH_REGEX_NOT: {
    // Negated regex match: rd = !RuntimeRegex.matchRegex(...)
    int rd = bytecode[pc++];
    int stringReg = bytecode[pc++];
    int regexReg = bytecode[pc++];
    int ctx = bytecode[pc++];
    RuntimeBase matchResult = org.perlonjava.regex.RuntimeRegex.matchRegex(
        (RuntimeScalar) registers[regexReg],
        (RuntimeScalar) registers[stringReg],
        ctx
    );
    // Negate the boolean result
    registers[rd] = new RuntimeScalar(matchResult.scalar().getBoolean() ? 0 : 1);
    break;
}
```

### 4. Added Disassembly Support (InterpretedCode.java)
```java
case Opcodes.MATCH_REGEX_NOT:
    rd = bytecode[pc++];
    strReg = bytecode[pc++];
    regReg = bytecode[pc++];
    matchCtx = bytecode[pc++];
    sb.append("MATCH_REGEX_NOT r").append(rd).append(" = r").append(strReg)
      .append(" !~ r").append(regReg).append(" (ctx=").append(matchCtx).append(")\n");
    break;
```

## Testing

### Before Fix
Tests 3, 4, 7, 8, 11, 12, 15, 16, etc. were failing with:
```
not ok 3 - my $a = "\t"; $a !~ qr/ (?a: \S ) /x; "\t" is not a \S under /a
```

### After Fix
All tests using `!~` operator now pass:
```
ok 3 - my $a = "\t"; $a !~ qr/ (?a: \S ) /x; "\t" is not a \S under /a
ok 4 - my $a = "\t" x 10; $a !~ qr/ (?a: \S{10} ) /x; "\t" is not a \S under /a
...
```

### Test Results
- Total tests: 5552
- Remaining failures: 270 (unrelated to `!~` operator)
- Unit tests: ✓ All passing

The remaining 270 failures are related to other issues (word boundaries, Unicode character classes) and not the `!~` operator implementation.

## Files Modified

1. `src/main/java/org/perlonjava/interpreter/Opcodes.java` - Added MATCH_REGEX_NOT = 217
2. `src/main/java/org/perlonjava/interpreter/BytecodeCompiler.java` - Added compiler case
3. `src/main/java/org/perlonjava/interpreter/BytecodeInterpreter.java` - Added runtime handler
4. `src/main/java/org/perlonjava/interpreter/InterpretedCode.java` - Added disassembly case

## Key Lessons

1. **Error Hiding in Eval**: Errors inside `eval` blocks are caught and set to `$@`, making debugging difficult without temporarily adding logging.

2. **Follow SKILL.md**: The guide in `dev/interpreter/SKILL.md` provides excellent step-by-step instructions for adding operators.

3. **Disassembly is Critical**: Missing disassembly cases cause PC misalignment and corrupt subsequent bytecode instructions.

4. **Type Conversion**: `RuntimeRegex.matchRegex()` returns `RuntimeBase`, not `RuntimeScalar`, so `.scalar()` must be called before `.getBoolean()`.

## References

- SKILL.md: `dev/interpreter/SKILL.md`
- Test file: `perl5_t/t/re/charset.t`
- Related opcodes: MATCH_REGEX (167), MATCH_REGEX_NOT (217)