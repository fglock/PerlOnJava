# PerlOnJava Architecture

## Overview

PerlOnJava compiles Perl source code into JVM bytecode, enabling Perl programs to run natively on the Java Virtual Machine. PerlOnJava provides two execution modes:

1. **Compiler Mode** (default): Transforms Perl scripts into JVM bytecode classes at runtime, providing high performance after JIT warmup (~82M ops/sec).
2. **Interpreter Mode** (`--interpreter` flag): Executes Perl bytecode directly in a register-based interpreter, offering faster startup and lower memory usage (~47M ops/sec, 1.75x slower than compiler).

Both modes provide seamless integration with Java libraries and frameworks while maintaining Perl semantics, and share 100% of the same runtime APIs.

**Key features:**
- Compile-time transformation from Perl to JVM bytecode (compiler mode)
- Direct bytecode interpretation with register-based VM (interpreter mode)
- Direct access to Java libraries via JDBC, Maven, and other JVM tools
- Implements most Perl 5.42 features including references, closures, and regular expressions
- Includes 150+ core Perl modules
- Bidirectional calling between compiled and interpreted code

This document describes the internal architecture and compilation pipeline.

## Internal Modules

### Project Structure

```
/
├── src/
│   ├── main/
│   │   ├── perl/
│   │   │   └── lib/
│   │   │       └── Perl modules (strict.pm, etc)
│   │   └── java/
│   │       └── org/
│   │           └── perlonjava/
│   │               ├── app/
│   │               │   ├── cli/
│   │               │   │   ├── Main.java
│   │               │   │   ├── ArgumentParser.java
│   │               │   ├── scriptengine/
│   │               │   │   ├── PerlScriptEngine.java
│   │               │   │   └── other script engine classes
│   │               ├── frontend/
│   │               │   ├── analysis/
│   │               │   │   ├── Visitor.java
│   │               │   │   └── other AST node visitor classes
│   │               │   ├── lexer/
│   │               │   │   ├── Lexer.java
│   │               │   │   └── other lexer classes
│   │               │   ├── astnode/
│   │               │   │   ├── Node.java
│   │               │   │   └── other AST node classes
│   │               │   ├── semantic/
│   │               │   │   ├── ScopedSymbolTable.java
│   │               │   │   └── other symbol table classes
│   │               │   └── parser/
│   │               │       ├── Parser.java
│   │               │       └── other parser classes
│   │               ├── backend/
│   │               │   ├── bytecode/
│   │               │   │   ├── BytecodeInterpreter.java
│   │               │   │   ├── BytecodeCompiler.java
│   │               │   │   ├── InterpretedCode.java
│   │               │   │   ├── Opcodes.java
│   │               │   │   ├── SlowOpcodeHandler.java
│   │               │   │   ├── VariableCaptureAnalyzer.java
│   │               │   │   └── other interpreter classes
│   │               │   └── jvm/
│   │               │       └── JVM bytecode generator classes
│   │               ├── runtime/
│   │               │   ├── perlmodule/
│   │               │   │   ├── Universal.java
│   │               │   │   └── other internalized Perl module classes
│   │               │   ├── operators/
│   │               │   │   ├── OperatorHandler.java
│   │               │   │   ├── ArithmeticOperators.java
│   │               │   │   └── other operator handling classes
│   │               │   ├── regex/
│   │               │   │   ├── RuntimeRegex.java
│   │               │   │   └── other regex classes
│   │               │   ├── io/
│   │               │   │   ├── SocketIO.java
│   │               │   │   └── other io classes
│   │               │   ├── mro/
│   │               │   │   ├── C3.java
│   │               │   │   └── other mro classes
│   │               │   ├── terminal/
│   │               │   │   ├── TerminalHandler.java
│   │               │   │   └── other platform-specific terminal classes
│   │               │   └── runtimetypes/
│   │               │       ├── RuntimeScalar.java
│   │               │       └── other runtime types
│   └── test/
│       ├── java/
│       │   └── org/
│       │       └── perlonjava/
│       │           └── PerlScriptExecutionTest.java
│       └── resources/
│           └── Perl test files
├── build.gradle
├── pom.xml
├── settings.gradle
├── examples/
│   └── Perl example files
├── docs/
│   └── project documentation files
├── dev/
│   └── custom_bytecode/
│       ├── architecture/
│       └── tests/
├── t/
│   └── Perl test suite placeholder
```


### Lexer and Parser

- **Lexer**: Used to split the code into symbols like space, identifier, operator.
- **Parser**: Picks up the symbols and organizes them into an Abstract Syntax Tree (AST) of objects like block, subroutine.
- **StringParser**: Used to parse domain-specific languages within Perl, such as Regex and string interpolation.

### JVM Code Generation (Compiler)

- **EmitterVisitor**: Used to generate the bytecode for the operations within the method. It traverses the AST and generates the corresponding ASM bytecode.
- **EmitterContext**: Holds the current state of the Symbol Table and calling context (void, scalar, list).
- **PrinterVisitor**: Provides pretty-print stringification for the AST.
- **EmitterMethodCreator**: Used to generate the bytecode for the class. The user code is translated into a method, then the generated bytecode is loaded using a custom class loader.

### Custom Bytecode VM

The Custom Bytecode VM provides an alternative execution mode that runs Perl bytecode directly without generating JVM bytecode. It offers faster startup time and lower memory usage compared to the compiler.

- **BytecodeInterpreter**: Main execution loop with register-based architecture. Executes interpreter bytecode using a unified switch statement with tableswitch optimization.
- **BytecodeCompiler**: Translates AST to interpreter bytecode with register allocation. Assigns variables to register indices and generates compact bytecode instructions.
- **InterpretedCode**: Container for compiled interpreter bytecode with metadata (string pool, constants, max registers). Includes disassembler for debugging with `--disassemble` flag.
- **Opcodes**: Defines bytecode instruction set (0-99) organized into categories:
  - Control flow (RETURN, GOTO, conditionals)
  - Constants (LOAD_INT, LOAD_STRING)
  - Variables (GET_VAR, SET_VAR, CREATE_CLOSURE_VAR)
  - Arithmetic and comparison operations
  - Array and hash operations
  - Subroutine calls and references
  - Superinstructions for common patterns
- **SlowOpcodeHandler**: Handles rare operations (system calls, socket operations) via SLOW_OP gateway to keep main interpreter loop compact.
- **VariableCaptureAnalyzer**: Analyzes which lexical variables are captured by named subroutines to enable variable sharing between interpreted and compiled code.

**Key Features:**
- Register-based architecture (not stack-based)
- Dense opcode numbering enables JVM tableswitch optimization
- Shares 100% of runtime APIs with compiler (RuntimeScalar, RuntimeArray, RuntimeHash, etc.)
- Supports closures and bidirectional calling between compiled and interpreted code
- Variable sharing via persistent storage using BEGIN mechanism
- Performance: ~47M ops/sec (1.75x slower than compiler, within 2-5x target)

**Execution Mode:**
- Enabled with `--interpreter` flag: `./jperl --interpreter script.pl`
- Used automatically for `eval STRING` in both compiler and interpreter modes
- Suitable for short-lived scripts, development/debugging, and dynamic code evaluation

### AST Nodes: *Node*

- Representations of AST nodes for code blocks, variable declarations, and operations.

### Runtime packages: *Runtime* and *Operators*

- **Runtime**: Provides the implementation of the behavior of a Perl scalar variable, Code, Array, Hash.

### Symbol Table

- **ScopedSymbolTable**: Manage variable names and their corresponding local variable indices.

### Perl Module classes

- **perlmodule**: Provides ports of Perl classes implemented in Java, such as `UNIVERSAL` and `Symbol`.

### Main Method

- The main method generates the bytecode for the program body.
- The generated method is loaded into a variable as a code reference and executed.

### PerlScriptEngine

- `PerlScriptEngine` is a Java class that allows you to execute Perl scripts using the Java Scripting API (JSR 223).

