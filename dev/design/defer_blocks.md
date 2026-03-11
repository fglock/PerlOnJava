# Defer Blocks Implementation Design

## Overview

This document describes the implementation of Perl's `defer` feature in PerlOnJava. The `defer` statement registers a block of code to be executed when the current scope exits, regardless of how it exits (normal flow, return, exception, etc.).

## Perl Semantics

From [perlsyn](https://perldoc.perl.org/perlsyn#defer-blocks):

```perl
use feature 'defer';

{
    defer { print "cleanup\n"; }
    print "body\n";
}
# Output: body\ncleanup\n
```

Key behaviors:
- **LIFO execution**: Multiple defer blocks execute in reverse order (last-in, first-out)
- **Lexical capture**: Variables are captured at the point where `defer` is encountered
- **Exception safety**: Defer blocks run even during exception unwinding
- **Conditional registration**: A defer block only registers if control flow reaches it
- **No return value impact**: Defer blocks don't affect the return value of the enclosing scope
- **Works with all exit mechanisms**: return, last, next, redo, die, goto

Restrictions (compile-time errors):
- Cannot `goto` into a defer block
- Cannot `goto` out of a defer block
- Cannot use `last`/`next`/`redo` to exit a defer block

## Implementation Strategy

### Reusing `pushLocalVariable()` Mechanism

PerlOnJava already has a `DynamicVariableManager` with a stack-based scope cleanup mechanism used for `local` variables:

```java
// At scope entry
int savedLevel = DynamicVariableManager.getLocalLevel();

// Register cleanup items
DynamicVariableManager.pushLocalVariable(item);

// At scope exit (in finally block)
DynamicVariableManager.popToLocalLevel(savedLevel);
```

**Key insight**: The `DynamicState.dynamicRestoreState()` method can execute arbitrary code, not just restore state. A `DeferBlock` class implementing `DynamicState` can execute its code block in `dynamicRestoreState()`.

This approach provides:
- LIFO ordering (stack semantics)
- Exception safety (finally blocks already call `popToLocalLevel()`)
- Interaction with `local` variables (same stack, correct ordering)
- Minimal code changes (reuses existing infrastructure)

## Components

### 1. DeferNode (AST Node)

```java
// src/main/java/org/perlonjava/frontend/astnode/DeferNode.java
public class DeferNode extends AbstractNode {
    public final Node block;
    
    public DeferNode(Node block, int tokenIndex) {
        this.block = block;
        this.tokenIndex = tokenIndex;
    }
    
    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }
}
```

### 2. DeferBlock (Runtime Class)

```java
// src/main/java/org/perlonjava/runtime/runtimetypes/DeferBlock.java
public class DeferBlock implements DynamicState {
    private final RuntimeCode code;
    
    public DeferBlock(RuntimeCode code) {
        this.code = code;
    }
    
    @Override
    public void dynamicSaveState() {
        // Nothing to save - this just registers the defer
    }
    
    @Override
    public void dynamicRestoreState() {
        // Execute the defer block when scope exits
        try {
            code.apply(new RuntimeArray(), RuntimeContextType.VOID);
        } catch (PerlDieException e) {
            // Re-throw - will be caught by modified popToLocalLevel
            throw e;
        } catch (Exception e) {
            // Wrap unexpected exceptions
            throw new RuntimeException("Exception in defer block", e);
        }
    }
}
```

### 3. Parser Changes

In `StatementResolver.java`:
```java
case "defer" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("defer")
        ? StatementParser.parseDeferStatement(parser)
        : handleUnknownIdentifier(parser);
```

In `StatementParser.java`:
```java
public static Node parseDeferStatement(Parser parser) {
    int index = parser.tokenIndex;
    TokenUtils.consume(parser, LexerTokenType.IDENTIFIER); // "defer"
    
    // Parse the defer block
    TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
    Node deferBlock = ParseBlock.parseBlock(parser);
    TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
    
    return new DeferNode(deferBlock, index);
}
```

### 4. JVM Backend Emission

```java
// In EmitStatement.java or new EmitDefer.java
public static void emitDefer(EmitterVisitor emitterVisitor, DeferNode node) {
    MethodVisitor mv = emitterVisitor.ctx.mv;
    EmitterContext ctx = emitterVisitor.ctx;
    int index = node.tokenIndex;
    
    // Compile the defer block as a closure
    // This captures lexical variables at this point
    Node closureNode = new SubroutineNode(null, null, null, node.block, false, index);
    closureNode.accept(emitterVisitor);
    
    // Stack: RuntimeCode (the closure)
    
    // Wrap in DeferBlock
    mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/DeferBlock");
    mv.visitInsn(Opcodes.DUP_X1);
    mv.visitInsn(Opcodes.SWAP);
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, 
        "org/perlonjava/runtime/runtimetypes/DeferBlock",
        "<init>", 
        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeCode;)V", 
        false);
    
    // Push onto dynamic variable stack
    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
        "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
        "pushLocalVariable",
        "(Lorg/perlonjava/runtime/runtimetypes/DynamicState;)V",
        false);
}
```

### 5. Bytecode Interpreter Backend

Add opcode for defer:
```java
// In Opcodes.java
public static final int DEFER = ...;

// In BytecodeCompiler.java
public void visit(DeferNode node) {
    // Compile block as closure, push DeferBlock
    ...
}

// In BytecodeInterpreter.java
case Opcodes.DEFER: {
    RuntimeCode code = (RuntimeCode) registers[bytecode[pc++]];
    DynamicVariableManager.pushLocalVariable(new DeferBlock(code));
    break;
}
```

### 6. Exception Handling in popToLocalLevel()

Modify `DynamicVariableManager.popToLocalLevel()` to continue cleanup even if a defer block throws:

```java
public static void popToLocalLevel(int targetLocalLevel) {
    if (targetLocalLevel < 0 || targetLocalLevel > variableStack.size()) {
        throw new IllegalArgumentException("Invalid target local level: " + targetLocalLevel);
    }

    Throwable pendingException = null;
    
    while (variableStack.size() > targetLocalLevel) {
        DynamicState variable = variableStack.pop();
        try {
            variable.dynamicRestoreState();
        } catch (Throwable t) {
            // For defer blocks: last exception wins (Perl semantics)
            // For local variable restoration: shouldn't throw, but handle anyway
            pendingException = t;
        }
    }
    
    if (pendingException != null) {
        if (pendingException instanceof RuntimeException re) {
            throw re;
        } else if (pendingException instanceof Error e) {
            throw e;
        } else {
            throw new RuntimeException(pendingException);
        }
    }
}
```

### 7. Compile-time Restrictions

Add checks in `ControlFlowDetectorVisitor.java` or similar:

```java
// Detect goto/last/next/redo that would exit a defer block
public void visit(DeferNode node) {
    insideDefer = true;
    node.block.accept(this);
    insideDefer = false;
}

// In goto/last/next/redo handling:
if (insideDefer && wouldExitDefer(target)) {
    throw new PerlCompilerException("Can't \"" + controlOp + "\" out of a \"defer\" block");
}
```

## Test Cases (from perl5_t/t/op/defer.t)

| Test | Description |
|------|-------------|
| Basic invocation | `defer { $x = "a" }` executes on scope exit |
| Multiple statements | Defer block can contain multiple statements |
| LIFO order | Multiple defer blocks execute in reverse order |
| After main body | Defer runs after main block code |
| Per-iteration | Defer in loop runs each iteration |
| Conditional branch | Defer doesn't run if branch not taken |
| Early exit | `last` before defer means defer doesn't register |
| Redo support | Defer can execute multiple times with redo |
| Nested defer | Defer inside defer works |
| do {} block | Defer works inside do {} |
| Subroutine | Defer works inside sub |
| Early return | Defer doesn't run if return before defer |
| Tail call | Defer runs before goto &name |
| Lexical capture | Captures correct variable bindings |
| local interaction | Works correctly with local variables |
| Exception unwind | Defer runs during die unwinding |
| Defer throws | Defer can throw exception |
| Exception in exception | Last exception wins |
| goto restrictions | Compile-time errors for goto into/out of defer |

## Files to Modify

### New Files
- `src/main/java/org/perlonjava/frontend/astnode/DeferNode.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/DeferBlock.java`

### Modified Files
- `src/main/java/org/perlonjava/frontend/parser/StatementParser.java` - Add parseDeferStatement
- `src/main/java/org/perlonjava/frontend/parser/StatementResolver.java` - Add defer case
- `src/main/java/org/perlonjava/frontend/parser/ParserTables.java` - Add defer prototype
- `src/main/java/org/perlonjava/frontend/analysis/Visitor.java` - Add visit(DeferNode)
- `src/main/java/org/perlonjava/runtime/runtimetypes/DynamicVariableManager.java` - Exception-safe cleanup
- `src/main/java/org/perlonjava/backend/jvm/EmitStatement.java` or new EmitDefer.java
- `src/main/java/org/perlonjava/backend/bytecode/BytecodeCompiler.java`
- `src/main/java/org/perlonjava/backend/bytecode/BytecodeInterpreter.java`
- `src/main/java/org/perlonjava/backend/bytecode/Opcodes.java`
- Various visitor implementations (PrintVisitor, etc.)

## Implementation Order

1. **Phase 1**: Core Infrastructure
   - Create DeferNode AST node
   - Create DeferBlock runtime class
   - Modify DynamicVariableManager for exception safety

2. **Phase 2**: Parser
   - Add parseDeferStatement
   - Add feature flag check
   - Add to parser tables

3. **Phase 3**: JVM Backend
   - Implement EmitDefer for JVM compilation
   - Add visitor methods

4. **Phase 4**: Bytecode Interpreter Backend
   - Add DEFER opcode
   - Implement in BytecodeCompiler
   - Implement in BytecodeInterpreter

5. **Phase 5**: Restrictions & Polish
   - Add compile-time checks for goto/last/next/redo
   - Add warnings support
   - Create unit tests

## Related Documents

- `dev/design/local_variable_codegen.md` - Local variable mechanism
- `dev/design/dynamic_variables.md` - DynamicVariableManager details

## Progress Tracking

### Current Status: Initial implementation complete (PR #301)

### Completed Phases
- [x] Phase 1: Core Infrastructure (2026-03-11)
  - Created DeferNode AST node
  - Created DeferBlock runtime class (accepts RuntimeScalar, not RuntimeCode)
  - Modified DynamicVariableManager for exception-safe cleanup
  - Files: DeferNode.java, DeferBlock.java, DynamicVariableManager.java

- [x] Phase 2: Parser (2026-03-11)
  - Added parseDeferStatement() in StatementParser.java
  - Added case "defer" in StatementResolver.java
  - Feature flag "defer" already existed in FeatureFlags.java
  - Files: StatementParser.java, StatementResolver.java

- [x] Phase 3: JVM Backend (2026-03-11)
  - Implemented EmitStatement.emitDefer() for JVM compilation
  - Added visit(DeferNode) to all 14 visitor implementations
  - Updated FindDeclarationVisitor.containsLocalOrDefer() for scope detection
  - Updated Local.java to trigger popToLocalLevel() for blocks with defer
  - Files: EmitStatement.java, Local.java, FindDeclarationVisitor.java, all visitors

- [x] Phase 4: Bytecode Interpreter Backend (2026-03-11)
  - Added PUSH_DEFER opcode (378) in Opcodes.java
  - Implemented BytecodeCompiler.visit(DeferNode)
  - Implemented InlineOpcodeHandler.executePushDefer()
  - Updated BytecodeCompiler.visit(BlockNode) to detect defer
  - Added disassembly support in Disassemble.java
  - Files: Opcodes.java, BytecodeCompiler.java, InlineOpcodeHandler.java, Disassemble.java

- [x] Unit Tests (2026-03-11)
  - Created src/test/resources/unit/defer.t with 14 test cases
  - Tests cover: basic, LIFO, foreach, closures, nested, exceptions, return

- [x] @_ Capture Support (2026-03-11)
  - DeferBlock now captures enclosing subroutine's @_ array
  - JVM: EmitStatement.emitDefer() loads slot 1 (@_) and passes to constructor
  - Interpreter: PUSH_DEFER opcode takes two registers (code + args)
  - Files: DeferBlock.java, EmitStatement.java, BytecodeCompiler.java, InlineOpcodeHandler.java

- [x] ASM Fallback to Interpreter (2026-03-11)
  - When JVM compilation fails due to ASM frame computation issues
  - InterpreterFallbackException signals fallback needed
  - EmitterMethodCreator catches ASM errors and triggers fallback
  - EmitSubroutine generates code that loads InterpretedCode from registry
  - RuntimeCode.interpretedSubs stores fallback implementations
  - Files: InterpreterFallbackException.java (new), EmitterMethodCreator.java, EmitSubroutine.java, RuntimeCode.java

- [x] Unit Tests (2026-03-11)
  - Created src/test/resources/unit/defer.t with 15 test cases
  - Tests cover: basic, LIFO, foreach, closures, nested, exceptions, return, @_ capture

- [x] Eval catching defer exceptions (2026-03-11)
  - Fixed eval not catching exceptions from defer blocks during teardown
  - Wrap localTeardown in try-catch when useTryCatch=true
  - Last exception wins (Perl semantics)
  - Files: EmitterMethodCreator.java

### Remaining Work (Phase 5)
- [ ] Add compile-time restrictions for goto/last/next/redo out of defer blocks
- [ ] Fix redo to re-register defer blocks (test 9: got "A" expected "AAAAA")
- [ ] Fix goto &sub to trigger defer before tail call (test 15: got "acb" expected "abc")

### Test Status
- **Unit tests**: 15/15 passing
- **Perl5 op/defer.t**: 25/33 passing (up from 22/33)
  - Test 9 fails: redo doesn't re-execute defer multiple times
  - Test 15 fails: goto &sub doesn't run defer before tail call
  - Tests 26, 28, 30-33: compile-time restrictions and warnings not yet implemented

### Open Questions
- Should we emit a warning for `use feature 'defer'` like Perl does ("defer is experimental")?

### Key Implementation Decisions
1. **DeferBlock accepts RuntimeScalar (code ref)** instead of RuntimeCode directly
   - This matches how closures work in the EmitterContext
   - Avoids VerifyError from stack type mismatch

2. **Scope cleanup via containsLocalOrDefer()**
   - Single method checks for both local operators and defer statements
   - Triggers GET_LOCAL_LEVEL/POP_LOCAL_LEVEL or localSetup/localTeardown

3. **@_ capture at defer registration time**
   - Defer block captures enclosing sub's @_ when defer statement executes
   - Captured array is stored in DeferBlock and passed when block runs

4. **ASM fallback mechanism**
   - Complex control flow can confuse ASM's automatic frame computation
   - Fallback compiles subroutine to bytecode interpreter instead
   - Fallback is per-subroutine, not whole-program

5. **Eval catching defer exceptions during teardown**
   - Wrap localTeardown in try-catch when useTryCatch=true
   - Spill RuntimeList to slot before try block to keep stack clean
   - Both normal and catch paths join with empty stack, then reload from slot
