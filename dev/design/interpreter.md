# Fast Interpreter Architecture for PerlOnJava

## Context

PerlOnJava currently compiles all Perl code to JVM bytecode via ASM, which has significant overhead for two use cases:

1. **Small eval strings** - Compilation overhead (~50ms) dominates execution time for one-liners
2. **Very large code executed once** - Compilation time and memory for class metadata is wasted

The current compilation pipeline:
```
Lexer → Parser → AST → EmitterVisitor → ASM bytecode → ClassLoader → MethodHandle
```

Fixed costs include ClassWriter initialization, ASM frame computation (expensive), and class loading. This makes compilation inefficient for small/one-time code despite an LRU eval cache (100 entries).

**Solution**: Add a VERY FAST interpreter that shares the same internal API and runtime, allowing seamless mixing of compiled and interpreted code.

## Status: Phase 0 - Architecture Benchmarking

Currently implementing minimal prototypes to empirically determine the fastest dispatch architecture.

## Implementation Progress

- [ ] Phase 0: Architecture Benchmarking (IN PROGRESS)
  - [ ] Switch-based prototype
  - [ ] Function-array prototype
  - [ ] Benchmark suite
  - [ ] Architecture decision
- [ ] Phase 1: Core Interpreter
- [ ] Phase 2: Integration
- [ ] Phase 3: Optimization
- [ ] Phase 4: Coverage
- [ ] Phase 5: Benchmarking & Tuning

See full plan details in git history or project documentation.
