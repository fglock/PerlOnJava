# `goto &sub` parity handoff

## Objective

Complete Perl-compatible `goto &sub` behavior on the JVM and bytecode interpreter backends. The acceptance target is every applicable assertion in `perl5_t/t/op/goto-sub.t`, with identical JVM and interpreter results.

## Current state

Both backends use `RuntimeCode.resolveTailCalls()` for tail-call marker dispatch. The core test completes normally in both modes. Named-target `AUTOLOAD`, chained calls, recursion, localized/replaced `@_`, and absent ARRAY slots after `undef *_` and `local *_` pass on both backends.

Completed since the initial handoff:

- Tail-call markers carry explicit named-source identity, deferred eval scope, and the original `@_` container.
- A named target is freshly resolved after source-frame cleanup; core assertion 2 now passes with the required `Goto undefined subroutine ... at file line N` form.
- Literal `@_` uses the live/current-frame-localized argument container in both emitters; sparse `$_[0]` reification now passes core assertion 24.
- `src/test/resources/unit/goto_tailcall_cleanup.t` passes on system Perl, JVM, and interpreter with 60-second process timeouts.

Remaining identical failures are assertions 7, 9, and 18 in `perl5_t/t/op/goto-sub.t`:

1. Temporary arguments from retired tail-call frames still release one call late in the repeated destructor-ordering case (assertions 7 and 9).
2. `eval 'goto &null'` still returns normally rather than setting `$@` to the required eval-string restriction diagnostic (assertion 18). Eval STRING uses the interpreter path even in JVM mode; both `EvalStringHandler` execution paths and direct interpreter marker construction have been updated, but the relevant marker path still needs tracing.

The most recent `make` attempts compiled and produced the shadow JAR, but the parallel unit shards exceeded hard 90--300 second timeouts. Those attempts exited with 124 and left no PerlOnJava3 JVM processes. Do not treat them as passing gates.

## Required implementation

### Tail-call marker

Extend `ControlFlowMarker` / `RuntimeControlFlowList` so a TAILCALL marker stores the original coderef, optional named-symbol identity, source file/line, the unchanged `RuntimeArray` argument container, and ownership of marker-created aliases.

Only a source `goto &name` or `goto &Pkg::name` supplies named-symbol identity. Do not infer it from `RuntimeCode.packageName` / `subName`: anonymous closures and dynamic coderefs can carry those fields. Preserve normal behavior for coderefs, globs, strings, overload, `AUTOLOAD`, and eval.

Do not validate in the marker constructor. After the source frame cleanup, `RuntimeCode.resolveTailCalls()` must freshly look up an explicitly named marker target through normal dispatch, preserving `AUTOLOAD`. For an undefined target, use marker file/line and the exact form:

```
Goto undefined subroutine &Pkg::name at file line N
```

Undefined-target handling precedes any eval-scope error.

### Argument ownership

Always pass the actual current or current-frame-localized `@_` container; never clone it for cleanup control. Audit `RuntimeCode.apply(..., "tailcall", ...)` and `RuntimeCode.resolveTailCalls()` together: each currently has tail-call cleanup paths. Assign cleanup ownership to one layer, consume each marker once, and release only marker-owned temporary aliases after the target returns or yields its next marker. This must fix core assertions 7 and 9 without changing aliasing.

### Sparse arguments and ARRAY slots

Trace `RuntimeArray.getTailCallArrayOfAlias()`, `RuntimeCode.getGotoArgs()`, and `utf8::encode`. A sparse `@_` created with `$#_++` must remain the same container through the handoff, so `utf8::encode($_[0])` reifies its missing element as `""`.

Keep glob slot reads non-vivifying: reads of `*glob{ARRAY}` use a peek; writes and true array dereferences may create a slot. Do not use slot vivification to solve sparse-argument reification.

## Regression coverage

Keep and extend project-owned tests under `src/test/resources/unit` for the exact named-target cleanup diagnostic, repeated destructor ordering, sparse `@_` reification through `utf8::encode`, deferred named-target `AUTOLOAD`, and absent ARRAY slots after `undef *_` / `local *_`.

Run new or modified Perl tests with system Perl first. Do not alter existing core tests.

## Validation

Capture complete output to files and wrap every `jperl` invocation in `timeout`.

1. Run focused unit tests on system Perl, JVM, and interpreter.
2. Run `perl5_t/t/op/goto-sub.t` on JVM and interpreter; require no `not ok` lines and normal exit.
3. Run relevant `goto`, subroutine, typeglob, and UTF-8 tests on both backends.
4. Update `docs/about/changelog.md` under `## Work in progress` when runtime behavior is complete.
5. On an immutable final commit, run `make`, inspect its complete log, then update PR #1205 and monitor CI before UAT.

## Progress Tracking

### Current Status: partial implementation, three core assertions remaining (2026-09-01)

### Completed Phases

- [x] Marker identity and late named-target lookup
  - Files: `ControlFlowMarker.java`, `RuntimeControlFlowList.java`, `RuntimeCode.java`, JVM and bytecode emitters.
- [x] Live `@_` handoff for literal `goto &name`
  - Files: `CompileOperator.java`, `BytecodeInterpreter.java`, `RuntimeArray.java`.
  - Core sparse argument assertion 24 passes on both backends.

### Next Steps

1. Trace ownership from `push @_` through `RuntimeCode.apply(..., "tailcall", ...)`; ensure the marker's ownership carrier is the only release owner and that `DESTROY` runs before the next source call.
2. Disassemble or trace `eval 'goto &null'` to identify the marker producer that still lacks `eval-string` metadata, then verify `$@` on both backends.
3. Extend focused regression coverage for sparse `@_`, deferred `AUTOLOAD`, and eval-string behavior; run new tests on system Perl first.
4. Re-run both core backends, relevant focused suites, and an unbounded immutable `make` only after the targeted cases pass.

## Relevant files

- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/ControlFlowMarker.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList.java`
- `src/main/java/org/perlonjava/backend/bytecode/BytecodeInterpreter.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeGlob.java`
- `src/test/resources/unit/goto_tailcall_cleanup.t`
- `perl5_t/t/op/goto-sub.t`
