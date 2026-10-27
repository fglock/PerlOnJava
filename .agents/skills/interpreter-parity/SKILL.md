---
name: interpreter-parity
description: Diagnose and fix PerlOnJava JVM/interpreter differences, including closure metadata, context propagation, eval, and failed-JVM-compilation fallback.
---

# Interpreter/JVM parity

Follow [AGENTS.md](../../../AGENTS.md), the
[testing cadence](../debug-perlonjava/references/testing-cadence.md) and the
[general debugging workflow](../debug-perlonjava/SKILL.md). Keep one concise
current checkpoint and link complete test/disassembly evidence.

## Establish which path differs

Run a reduced case on system Perl, JVM and interpreter. Save baseline results
with source/JAR identity once, using separate immutable worktrees as needed.
Use `JPERL_INTERPRETER=1` for a fully interpreted file including require/do/eval;
check current flag propagation rather than assuming a top-level result covers
loaded code.

```bash
timeout 60 ./jperl repro.pl > /tmp/parity-jvm.log 2>&1
JPERL_INTERPRETER=1 timeout 60 ./jperl repro.pl > /tmp/parity-interpreter.log 2>&1
JPERL_INTERPRETER=1 timeout 60 ./jperl --disassemble repro.pl > /tmp/parity-bytecode.log 2>&1
```

`eval STRING` normally uses interpreter compilation; `JPERL_EVAL_NO_INTERPRETER`
disables that policy. Trace with `JPERL_EVAL_TRACE`/`JPERL_EVAL_VERBOSE`.
`JPERL_SHOW_FALLBACK` reports large-sub fallback and does not establish eval's
execution path. Exercise real test files as well as one-liners.

## Diagnose shared-runtime and lowering differences

Read the relevant parts of [diagnostics.md](references/diagnostics.md) for all
retained source maps and troubleshooting tips:

- metadata/prototypes/attributes through InterpretedCode construction and
  `withCapturedVars`, CREATE_CLOSURE and parser disambiguation;
- scalar/list/void conversion and STORE_GLOB value types;
- compile-time BEGIN side effects and backend flag propagation;
- contiguous opcode definitions, handler/emitter/disassembler alignment;
- AST mutation across failed JVM compilation and interpreter fallback, including
  system/splice operands, shared dereference element lists and annotation checks.

Compare JVM disassembly and runtime method calls first, then interpreter
lowering; reuse shared runtime operations where correct. System Perl resolves
semantic disagreements. At a crash, decode the marked opcode (`>>>...<<<`) and
nearby operands using Opcodes.java; temporarily print register types if needed.

## Complete the fix

Batch the related metadata/context/emitter/handler changes and focused permanent
regression coverage. Validate new Perl tests on system Perl first, retain the
unfixed-parent failure and require both backends. Test loaded modules, BEGIN,
closure copies, eval and fallback when those paths are affected. Do not mutate
the shared AST permanently; prefer local copies or try/finally restoration.

Build with `nice -n 19 make`; `make dev` is disabled. After opcode changes run
`perl dev/tools/check_opcodes.pl` and inspect disassembly operand advancement.
Use `nice -n 19 make test-interpreter` and applicable core/integration gates
without concurrent shared-JAR writers. Keep full output; pre-TAP `0/0` is a
setup/execution failure, not successful coverage. Compare saved parent/master
results to distinguish regressions from existing failures.

## Issue coordination and reporting

Use the [triage guide](../../../docs/guides/issue-triage.md) for issue evidence,
agent claims, dependencies and concise checkpoints. Follow
[AGENTS.md](../../../AGENTS.md) for work preservation and validation.
