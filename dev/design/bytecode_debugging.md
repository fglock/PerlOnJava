# Bytecode debugging workflow (ASM verifier failures)

This document describes the workflow used to diagnose and fix JVM bytecode verification / ASM frame computation failures in PerlOnJava generated classes (typically `org/perlonjava/anonNNN`).

## Symptoms

Typical failures during compilation / class generation:

- `java.lang.NegativeArraySizeException: -4`
  - Usually from ASM `ClassWriter.COMPUTE_FRAMES` when stack map frame computation fails.
- `org.objectweb.asm.tree.analysis.AnalyzerException`
  - Examples:
    - `Incompatible stack heights`
    - `Cannot pop operand off an empty stack.`
- `ArrayIndexOutOfBoundsException` inside `org.objectweb.asm.Frame.merge`

These errors generally mean the generated method has invalid stack behavior at a control-flow merge.

## Key idea

PerlOnJava generates Java bytecode with ASM. The JVM verifier requires consistent stack-map frames at merge points.

The most common root cause is:

- A control-flow edge (e.g. `GOTO returnLabel`) is taken while **some unrelated value is still on the JVM operand stack**, or
- The compiler’s own stack tracking drifts and emits `POP` instructions that do not correspond to reality.

In practice this happens when a subexpression may perform **non-local control flow** (tagged returns), such as:

- `return`
- `next` / `last` / `redo` outside the immediate loop
- `goto &NAME` tail calls

## Enabling diagnostics

### Environment variables

- `JPERL_ASM_DEBUG=1`
  - Enables detailed debug output when ASM frame computation fails.
- `JPERL_ASM_DEBUG_CLASS=anonNNN` (optional)
  - Restricts debug output to matching generated classes.
- `JPERL_OPTS='-Xmx512m'` (example)
  - Controls JVM options for the launcher.

### Typical repro command

Run from `perl5_t/` so that `./test.pl` and relative includes resolve:

```
JPERL_ASM_DEBUG=1 \
JPERL_OPTS='-Xmx512m' \
../jperl t/op/pack.t \
  > /tmp/perlonjava_pack_out.log \
  2> /tmp/perlonjava_pack_err.log
```

Note: Large code blocks are handled automatically via on-demand refactoring.

## Reading the debug output

When `JPERL_ASM_DEBUG=1` is enabled, `EmitterMethodCreator` prints:

- The failing generated class name: `org/perlonjava/anonNNN`
- The AST index and source file name (if available)
- A verifier run that produces a concrete:
  - method signature
  - failing instruction index

Look for:

- `ASM frame compute crash in generated class: org/perlonjava/anonNNN ...`
- `BasicInterpreter failure in org/perlonjava/anonNNN.apply(... ) at instruction K`

Then inspect the printed instruction window:

- Identify the failing instruction `K`.
- Look for the **last control-flow jump** into the label after `K`.
- Compare the operand stack shape across predecessors (often printed as `frame stack sizes`).

## Mapping failures back to emitters

Common patterns:

### 1) Extra value left on operand stack

A typical signature:

- One predecessor arrives at `returnLabel` with stack size `2` (e.g. `[result, extra]`)
- Other predecessors arrive with stack size `1` (`[result]`)

This is most often due to evaluating a left operand and keeping it on-stack while evaluating a right operand that may jump away.

Fix strategy:

- **Spill intermediate values to locals** before evaluating anything that might trigger tagged control flow.

### 2) Over-eager `POP` emission

A typical signature:

- `AnalyzerException: Cannot pop operand off an empty stack.`
- The instruction window shows multiple `POP`s without corresponding pushes.

Fix strategy:

- Avoid emitting `POP`s based on unreliable stack accounting.
- Prefer spilling to locals at the point where the compiler knows the stack is clean.

## Places to look in the code

- `src/main/java/org/perlonjava/codegen/EmitterMethodCreator.java`
  - Owns:
    - frame computation (`COMPUTE_FRAMES`)
    - the no-frames diagnostic pass
    - `BasicInterpreter` analysis output
- `src/main/java/org/perlonjava/codegen/EmitSubroutine.java`
  - Emits `RuntimeCode.apply(...)`
  - Tagged-return handling at call sites
- `src/main/java/org/perlonjava/codegen/EmitControlFlow.java`
  - Emits bytecode for `return`, `next/last/redo`, `goto`.
- Various operator emitters that evaluate LHS then RHS:
  - Any that keep LHS on stack across RHS evaluation are suspects.

## Practical debugging loop

1. Reproduce with `JPERL_ASM_DEBUG=1`.
2. Record failing `anonNNN` and instruction index.
3. Identify whether it’s:
   - stack height mismatch at merge, or
   - stack underflow from bad POPs.
4. Patch the responsible emitter (usually by spilling intermediates).
5. Rebuild jar:

```
./gradlew shadowJar
```

6. Re-run the test.

## Notes

- `jperl` runs `target/perlonjava-3.0.0.jar`. Rebuild after changes, otherwise you may be debugging stale code.
- `JPERL_ASM_DEBUG_CLASS` is useful to avoid massive logs during large tests.
