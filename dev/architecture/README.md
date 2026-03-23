# PerlOnJava Architecture Overview

This directory contains architecture documentation for the PerlOnJava compiler and runtime.

## Quick Overview

PerlOnJava is a Perl 5 implementation that compiles Perl source code to JVM bytecode. The system consists of:

1. **Frontend** (`org.perlonjava.frontend`)
   - Lexer: Tokenizes Perl source code
   - Parser: Builds Abstract Syntax Tree (AST)
   - Semantic analysis: Variable resolution, scope handling

2. **Backend** (`org.perlonjava.backend`)
   - JVM backend: Emits JVM bytecode using ASM library
   - Bytecode interpreter: Interprets a subset of operations for eval STRING

3. **Runtime** (`org.perlonjava.runtime`)
   - Runtime types: RuntimeScalar, RuntimeArray, RuntimeHash, RuntimeCode
   - Operators: Arithmetic, string, comparison, I/O
   - Perl modules: Built-in implementations of core modules

## Key Architecture Documents

| Document | Description |
|----------|-------------|
| [dynamic-scope.md](dynamic-scope.md) | Dynamic scoping via `local` and DynamicVariableManager |
| [lexical-pragmas.md](lexical-pragmas.md) | Lexical warnings, strict, and features |
| [../design/interpreter.md](../design/interpreter.md) | Bytecode interpreter design |
| [../design/variables_and_values.md](../design/variables_and_values.md) | Runtime value representation |

## Compilation Pipeline

```
Perl Source
    │
    ▼
┌─────────┐
│  Lexer  │  Tokenizes source into LexerTokens
└────┬────┘
     │
     ▼
┌─────────┐
│ Parser  │  Builds AST (AbstractNode tree)
└────┬────┘
     │
     ▼
┌─────────────┐
│  Visitors   │  Analysis passes (variable resolution, etc.)
└─────┬───────┘
      │
      ▼
┌───────────────┐
│ JVM Emitter   │  Generates bytecode via ASM
└───────┬───────┘
        │
        ▼
   JVM Bytecode
```

## Runtime Architecture

```
┌────────────────────────────────────────────────┐
│                  Perl Code                     │
└────────────────────────────────────────────────┘
                      │
                      ▼
┌────────────────────────────────────────────────┐
│              Runtime Types                      │
│  RuntimeScalar, RuntimeArray, RuntimeHash      │
│  RuntimeCode, RuntimeGlob, RuntimeIO           │
└────────────────────────────────────────────────┘
                      │
                      ▼
┌────────────────────────────────────────────────┐
│              Global State                       │
│  GlobalVariable (package variables)            │
│  DynamicVariableManager (local scoping)        │
│  CallerStack (call frame tracking)             │
└────────────────────────────────────────────────┘
                      │
                      ▼
┌────────────────────────────────────────────────┐
│                   JVM                          │
└────────────────────────────────────────────────┘
```

## Key Design Decisions

1. **Direct JVM Bytecode**: We emit bytecode directly rather than generating Java source, enabling better optimization and avoiding Java language limitations.

2. **Dual Backend**: JVM bytecode for compiled code, bytecode interpreter for `eval STRING` to avoid runtime class generation overhead.

3. **Dynamic Scoping**: Implemented via `DynamicVariableManager` which maintains a stack of saved values, restored on scope exit.

4. **Lexical Pragmas**: Warnings and strict are tracked in the symbol table at compile time and propagate via `CompilerFlagNode`.

## See Also

- [AGENTS.md](../../AGENTS.md) - Development guidelines
- [dev/design/](../design/) - Detailed design documents
