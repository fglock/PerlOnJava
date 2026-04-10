# Large Code Handling — JVM 64KB Method Limit

## Overview

PerlOnJava uses a **two-tier strategy** to handle Perl code that exceeds the JVM's 65,535-byte method size limit:

1. **Proactive**: During codegen, large blocks are detected and wrapped in a closure call to push them into a separate JVM method
2. **Reactive fallback**: If ASM still produces a method that's too large, the code is compiled using the bytecode interpreter backend instead

## The Problem

The JVM limits each method to 65,535 bytes of bytecode. PerlOnJava compiles each Perl subroutine (or eval block) into a single JVM method. Large Perl files — such as test suites with thousands of assertions, or modules with large data structures — can exceed this limit.

### Closure Scoping Complication

The natural fix is to wrap large blocks in anonymous subs: `sub { ...block... }->(@_)`. However, this changes lexical scoping. When `use` or `require` statements are wrapped in closures, their imports happen in the closure's scope instead of the package scope:

```perl
# Original code
package Foo;
use Config qw/%Config/;   # Import %Config into Foo package
my $x = $Config{foo};      # Access imported variable

# After naive refactoring (BROKEN)
package Foo;
sub {
    use Config qw/%Config/;   # Import happens in closure scope!
}->();
my $x = $Config{foo};      # ERROR: %Config not in scope
```

This is why proactive refactoring skips subroutines, special blocks (BEGIN/END/INIT/CHECK/UNITCHECK), and blocks with unsafe control flow.

## Tier 1: Proactive Block Wrapping

### Entry Point

`LargeBlockRefactorer.processBlock()` is called from `EmitBlock.emitBlock()` during bytecode emission for every `BlockNode`.

### Flow

```
EmitBlock.emitBlock(visitor, blockNode)
  └── LargeBlockRefactorer.processBlock(visitor, blockNode)
        ├── Skip if: already refactored, is subroutine, is special block, ≤4 elements
        ├── Estimate bytecode size (capped at 2 × LARGE_BYTECODE_SIZE)
        ├── If estimated > LARGE_BYTECODE_SIZE (40,000):
        │     └── tryWholeBlockRefactoring():
        │           ├── Check for unsafe control flow (unlabeled next/last/redo/goto) → abort if found
        │           ├── Mark blockAlreadyRefactored = true
        │           └── Wrap entire block in: sub { <block> }->(@_)
        └── Return false → normal block emission continues
```

Wrapping pushes the block's code into a separate JVM method (the anonymous sub body), giving it its own 64KB budget. This effectively doubles the available space for that block.

### Limitations

The wrapping is a **single-level** operation — it wraps the entire block in one closure. It does not recursively split the block into smaller chunks. This means:
- For blocks up to ~2x the 64KB limit, wrapping succeeds (the block fits in the new method)
- For blocks larger than ~2x the limit, wrapping is insufficient and the `MethodTooLargeException` still occurs, triggering Tier 2

### Thresholds

| Constant | Value | File | Purpose |
|----------|-------|------|---------|
| `LARGE_BYTECODE_SIZE` | 40,000 bytes | `BlockRefactor.java` | Trigger threshold (below 65,535 for safety margin) |
| `MIN_CHUNK_SIZE` | 4 elements | `BlockRefactor.java` | Minimum block size to consider refactoring |

### Key Classes

- **`BlockRefactor`** (`backend/jvm/astrefactor/BlockRefactor.java`) — Constants and `createAnonSubCall()` utility that creates `sub { ... }->(@_)` AST nodes
- **`LargeBlockRefactorer`** (`backend/jvm/astrefactor/LargeBlockRefactorer.java`) — Orchestrates block-level refactoring: size estimation, control flow safety checks, whole-block wrapping

## Tier 2: Interpreter Fallback

When the proactive wrapping is insufficient (or skipped due to unsafe control flow), ASM throws `MethodTooLargeException`. The fallback catches this and compiles the code using the bytecode interpreter instead.

### Flow

```
EmitterMethodCreator.createRuntimeCode(ctx, ast, useTryCatch)
  └── try: createClassWithMethod() → getBytecode() → ASM toByteArray()
        └── MethodTooLargeException thrown by ASM
  └── catch (MethodTooLargeException):
        └── if USE_INTERPRETER_FALLBACK (default: true):
              └── compileToInterpreter(ast, ctx, useTryCatch)
                    → Returns InterpretedCode (walks AST at runtime)
```

Both `CompiledCode` and `InterpretedCode` extend `RuntimeCode`, so call sites don't need to know which backend was used.

### Configuration

`USE_INTERPRETER_FALLBACK` is enabled by default. It can be disabled with the environment variable `JPERL_DISABLE_INTERPRETER_FALLBACK`.

The fallback also handles other compilation failures (`VerifyError`, `ClassFormatError`, certain `PerlCompilerException` and `RuntimeException` cases).

### User Message

When fallback is triggered with `JPERL_SHOW_FALLBACK=1`:
```
Note: Method too large, using interpreter backend.
```

## Technical Details

### JVM Constraints
- Maximum method bytecode size: 65,535 bytes (64KB)
- Proactive refactoring threshold: 40,000 bytes (safety margin)

### Refactoring Strategy
1. **Whole-block wrapping**: The entire block becomes `sub { <block> }->(@_)`
2. **`@_` passthrough**: Arguments are forwarded so the wrapper is transparent
3. **Anti-recursion guard**: `blockAlreadyRefactored` annotation prevents infinite recursion when the wrapper's BlockNode is processed
4. **Safe boundaries**: Blocks with unlabeled control flow (`next`/`last`/`redo`/`goto` outside loops) are not refactored, since these would break when wrapped in a closure

## Implementation Files

| File | Role |
|------|------|
| `backend/jvm/astrefactor/BlockRefactor.java` | Constants, closure-wrapping utility |
| `backend/jvm/astrefactor/LargeBlockRefactorer.java` | Block-level proactive refactoring |
| `backend/jvm/EmitBlock.java` | Calls `processBlock()` during block emission |
| `backend/jvm/EmitterMethodCreator.java` | Catches `MethodTooLargeException`, triggers interpreter fallback |

## See Also

- [control-flow.md](control-flow.md) — Control flow interacts with refactoring (unsafe control flow prevents block wrapping)
- [../design/interpreter.md](../design/interpreter.md) — Bytecode interpreter design (the fallback backend)
