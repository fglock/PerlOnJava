# Opcode Conflicts Report

When attempting to add bulk operator support to the interpreter, we discovered that many operators already have opcodes defined:

## Already Exist (with existing opcodes):
- RAND (91) - duplicate at 239
- LENGTH (30) - duplicate at 249
- RINDEX (173) - duplicate at 257
- INDEX (172) - duplicate at 258  
- REQUIRE (170) - duplicate at 267
- ISA (105) - duplicate at 268
- BLESS (104) - duplicate at 269
- REF (103) - duplicate at 270
- JOIN (88) - duplicate at 273
- PROTOTYPE (158) - duplicate at 274

## New opcodes that don't conflict (221-274 range):
- INT, LOG, SQRT, COS, SIN, EXP, ABS, ATAN2
- BINARY_AND, BINARY_OR, BINARY_XOR, BINARY_NOT, INTEGER_BITWISE_NOT
- ORD, ORD_BYTES, OCT, HEX, SRAND
- EQ, NE, LT, LE, GT, GE, CMP
- CHR, CHR_BYTES, LENGTH_BYTES
- QUOTEMETA, FC, LC, LCFIRST, UC, UCFIRST
- SLEEP, TELL, GETC, RMDIR, CLOSEDIR, REWINDDIR, TELLDIR, CHDIR
- EXIT, X

## Next Steps:
1. Remove duplicate opcode definitions from Opcodes.java
2. Update handler classes to remove or redirect duplicate operators
3. Remove duplicate case labels from BytecodeInterpreter.java and InterpretedCode.java
4. Fix method signature issues (getc, prototype)
5. Add BytecodeCompiler integration to emit these opcodes for function calls

## BytecodeCompiler Integration:
Need to add cases in visit(OperatorNode) around line 5700+ where other built-ins like "length" are handled.
Example pattern:
```java
} else if (op.equals("chr")) {
    // chr($x) - convert codepoint to character
    if (node.operand instanceof ListNode) {
        ListNode list = (ListNode) node.operand;
        if (!list.elements.isEmpty()) {
            list.elements.get(0).accept(this);
        } else {
            throwCompilerException("chr requires an argument");
        }
    } else {
        node.operand.accept(this);
    }
    int argReg = lastResultReg;
    
    int rd = allocateRegister();
    emit(Opcodes.CHR);
    emitReg(rd);
    emitReg(argReg);
    
    lastResultReg = rd;
}
```

This needs to be added for each new operator.
