# PerlOnJava Architecture

## Overview

PerlOnJava compiles Perl source code into JVM bytecode, enabling Perl programs to run natively on the Java Virtual Machine. The system provides two execution backends:

1. **JVM Backend** (default): Transforms Perl scripts into JVM bytecode classes at runtime using the ASM library, leveraging JIT compilation for optimized execution.
2. **Interpreter Backend**: Executes Perl bytecode directly in a register-based interpreter, used automatically for `eval STRING` and large code blocks that exceed JVM method size limits.

Both backends share 100% of the same runtime APIs and can call each other seamlessly.

**Key capabilities:**
- Compile-time transformation from Perl to JVM bytecode
- Direct bytecode interpretation with register-based VM
- Direct access to Java libraries via JDBC, JSR-223, and standard JVM tools
- Implements most Perl 5.40+ features including references, closures, and regular expressions
- Includes 150+ core Perl modules
- Bidirectional calling between compiled and interpreted code

This document describes the internal architecture and compilation pipeline.

## Project Structure

```
src/main/java/org/perlonjava/
├── app/
│   ├── cli/
│   │   ├── Main.java              # Entry point
│   │   ├── ArgumentParser.java    # Command-line argument parsing
│   │   └── CompilerOptions.java   # Compilation settings
│   └── scriptengine/
│       ├── PerlScriptEngine.java       # JSR-223 ScriptEngine
│       ├── PerlScriptEngineFactory.java
│       ├── PerlCompiledScript.java     # Compilable interface
│       └── PerlLanguageProvider.java   # Language server protocol
│
├── core/
│   └── Configuration.java         # Version and build info
│
├── frontend/
│   ├── lexer/
│   │   └── Lexer.java             # Tokenizer
│   ├── parser/
│   │   ├── Parser.java            # Main parser
│   │   ├── StatementParser.java   # Statement parsing
│   │   ├── OperatorParser.java    # Expression/operator parsing
│   │   ├── SubroutineParser.java  # Subroutine definitions
│   │   └── StringParser.java      # String interpolation, regex
│   ├── astnode/
│   │   ├── Node.java              # Base AST node interface
│   │   ├── AbstractNode.java      # Common node implementation
│   │   ├── BlockNode.java         # Code blocks
│   │   ├── SubroutineNode.java    # Subroutine definitions
│   │   ├── OperatorNode.java      # Unary operators
│   │   ├── BinaryOperatorNode.java
│   │   └── ...                    # 30+ AST node types
│   ├── analysis/
│   │   ├── Visitor.java           # AST visitor interface
│   │   ├── EmitterVisitor.java    # Code generation visitor
│   │   ├── PrintVisitor.java      # AST pretty-printer
│   │   └── ...                    # Analysis passes
│   └── semantic/
│       ├── ScopedSymbolTable.java # Variable scoping
│       └── SymbolTable.java       # Symbol management
│
├── backend/
│   ├── jvm/                       # JVM bytecode generation
│   │   ├── EmitterMethodCreator.java  # Class/method generation
│   │   ├── EmitBlock.java         # Block compilation
│   │   ├── EmitOperator.java      # Operator compilation
│   │   ├── EmitSubroutine.java    # Subroutine compilation
│   │   ├── EmitControlFlow.java   # if/while/for/loop
│   │   ├── CompiledCode.java      # Compiled code container
│   │   ├── CustomClassLoader.java # Dynamic class loading
│   │   └── ...                    # 20+ emit classes
│   └── bytecode/                  # Interpreter backend
│       ├── BytecodeInterpreter.java   # Main execution loop
│       ├── BytecodeCompiler.java      # AST to bytecode
│       ├── InterpretedCode.java       # Bytecode container
│       ├── Opcodes.java               # Instruction definitions
│       ├── SlowOpcodeHandler.java     # Infrequent operations
│       └── ...                        # Opcode handlers
│
└── runtime/
    ├── runtimetypes/
    │   ├── RuntimeScalar.java     # Perl scalar ($x)
    │   ├── RuntimeArray.java      # Perl array (@x)
    │   ├── RuntimeHash.java       # Perl hash (%x)
    │   ├── RuntimeCode.java       # Perl subroutine
    │   ├── RuntimeGlob.java       # Perl typeglob (*x)
    │   ├── RuntimeIO.java         # File handles
    │   ├── RuntimeList.java       # List context values
    │   ├── GlobalVariable.java    # Package variables
    │   └── ...                    # 70+ runtime classes
    ├── operators/
    │   ├── Operator.java          # Core operators
    │   ├── StringOperators.java   # String operations
    │   ├── MathOperators.java     # Numeric operations
    │   ├── IOOperator.java        # I/O operations
    │   ├── ListOperators.java     # List operations
    │   └── ...                    # 30+ operator classes
    ├── perlmodule/
    │   ├── Universal.java         # UNIVERSAL methods
    │   ├── DBI.java               # Database interface
    │   ├── Json.java              # JSON encoding
    │   ├── DateTime.java          # DateTime XS fallback
    │   └── ...                    # 60+ Java XS modules
    ├── regex/
    │   ├── RuntimeRegex.java      # Regex compilation/matching
    │   └── ...                    # Regex support classes
    ├── mro/
    │   ├── InheritanceResolver.java  # Method resolution
    │   └── C3.java                # C3 linearization
    ├── io/
    │   └── SocketIO.java          # Socket operations
    ├── nativ/
    │   └── ffm/                   # Foreign Function & Memory API
    │       └── LibC.java          # POSIX bindings
    ├── debugger/
    │   └── DebugHooks.java        # Perl debugger support
    ├── terminal/
    │   └── TerminalHandler.java   # Terminal I/O
    └── util/
        └── ...                    # Utility classes
```

## Compilation Pipeline

### 1. Lexer

The `Lexer` class performs fast, simple tokenization of Perl source code:
- Identifiers, numbers, operators, whitespace, newlines
- Optimized for speed; the Parser handles ambiguity resolution (e.g., `qq<=>` vs `<=>`)

### 2. Parser

The parser transforms tokens into an Abstract Syntax Tree (AST), handling Perl's context-sensitive syntax:
- **Parser.java**: Entry point and coordination
- **StatementParser.java**: Statement-level constructs
- **OperatorParser.java**: Expression parsing with precedence
- **SubroutineParser.java**: Subroutine and method definitions
- **StringParser.java**: Here-documents, quote-like operators (`q//`, `qq//`, `qw//`), regex literals, string interpolation

### 3. Code Generation

Two backends share the same AST and runtime:

#### JVM Backend (`backend/jvm/`)

Generates JVM bytecode using the ASM library:
- **EmitterVisitor**: Traverses AST and generates bytecode
- **EmitterMethodCreator**: Creates Java class structure
- **EmitBlock/EmitOperator/etc.**: Specialized code generators
- **CustomClassLoader**: Loads generated classes at runtime

#### Interpreter Backend (`backend/bytecode/`)

Generates and executes custom bytecode:
- **BytecodeCompiler**: Translates AST to interpreter bytecode
- **BytecodeInterpreter**: Register-based execution loop
- **InterpretedCode**: Container for bytecode and metadata
- **Opcodes**: Defines ~100 bytecode instructions

### 4. Runtime System

Both backends use the same runtime classes:

#### Core Types (`runtime/runtimetypes/`)
- **RuntimeScalar**: Perl scalar with type coercion
- **RuntimeArray**: Perl array with autovivification
- **RuntimeHash**: Perl hash with tied variable support
- **RuntimeCode**: Subroutine references and closures
- **RuntimeGlob**: Symbol table entries

#### Operators (`runtime/operators/`)
Java implementations of Perl operators, organized by category.

#### Perl Modules (`runtime/perlmodule/`)
Java XS implementations for modules that normally use C XS code (DateTime, DBI, JSON, etc.).

#### Memory Management
PerlOnJava relies on the JVM's tracing garbage collector for general memory
reclamation, including circular references. On top of that, a small
**selective reference-counting overlay** provides Perl 5's deterministic
`DESTROY` timing and `Scalar::Util::weaken` semantics for the narrow set of
objects that need them. See [Memory Management](memory-management.md) for
the user-facing summary, the relationship to the GC literature
(Bacon 2004, Blackburn & McKinley 2003, *The Garbage Collection Handbook*),
and a comparison with Perl 5 / JVM finalization.

## Interpreter Backend Details

The interpreter provides an alternative execution path:

### Architecture
- **Register-based**: Variables mapped to register indices (not stack-based)
- **Dense opcodes**: Enables JVM `tableswitch` optimization in main loop
- **Shared runtime**: 100% API compatibility with JVM backend

### Opcode Categories
- Control flow: `RETURN`, `GOTO`, `JUMP_IF_FALSE`, `JUMP_IF_TRUE`
- Constants: `LOAD_INT`, `LOAD_STRING`, `LOAD_DOUBLE`
- Variables: `GET_VAR`, `SET_VAR`, `CREATE_CLOSURE_VAR`
- Operations: Arithmetic, comparison, string, bitwise
- Data structures: Array/hash access, push/pop/shift
- Subroutines: `CALL`, `CALL_METHOD`, `TAILCALL`

### Automatic Usage
The interpreter is used automatically for:
- `eval STRING` expressions (both backends)
- Code blocks exceeding JVM method size limits (~64KB bytecode)
- Dynamic code generation scenarios

## Symbol Table

**ScopedSymbolTable** manages lexical scoping:
- Tracks variable declarations (`my`, `our`, `local`, `state`)
- Assigns JVM local variable indices
- Handles closure variable capture
- Manages pragma state (`strict`, `warnings`)

## JSR-223 Script Engine

PerlOnJava implements the Java Scripting API:
- **PerlScriptEngine**: `ScriptEngine` implementation
- **PerlScriptEngineFactory**: Engine discovery
- **PerlCompiledScript**: `Compilable` interface for compile-once, run-many

## Test Structure

```
src/test/
├── java/org/perlonjava/
│   └── PerlScriptExecutionTest.java  # JUnit test runner
└── resources/
    └── unit/                          # Perl test files (.t)
```

Tests use Perl's TAP (Test Anything Protocol) format and are executed via JUnit integration.

## Related Documentation

- [Memory Management](memory-management.md) - Selective reference-counting
  overlay for `DESTROY` and `weaken`, with literature context.
- `dev/architecture/` - Deep-dive architecture documents for contributors:
  - [Overview and index](../../dev/architecture/README.md)
  - [DESTROY and weak references](../../dev/architecture/weaken-destroy.md) - Implementation details
  - [Dynamic scoping](../../dev/architecture/dynamic-scope.md) - `local` and DynamicVariableManager
  - [Lexical pragmas](../../dev/architecture/lexical-pragmas.md) - Warnings, strict, and features
  - [Control flow](../../dev/architecture/control-flow.md) - die/eval, loop control, exceptions
- `dev/design/` - Design documents and implementation plans:
  - [Interpreter design](../../dev/design/interpreter.md)
  - [Variables and values](../../dev/design/variables_and_values.md)
  - [Shared AST transformer](../../dev/design/shared_ast_transformer.md)
