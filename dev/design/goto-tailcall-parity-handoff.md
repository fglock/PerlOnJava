# `goto &sub` parity handoff

## Objective

Complete Perl-compatible `goto &sub` behavior on the JVM and bytecode interpreter backends. The acceptance target is every applicable assertion in `perl5_t/t/op/goto-sub.t`, with identical JVM and interpreter results.

## Current state

Both backends use `RuntimeCode.resolveTailCalls()` for tail-call marker dispatch. The core test completes normally in both modes. Named-target `AUTOLOAD`, eval restrictions, chained calls, recursion, localized/replaced `@_`, and absent ARRAY slots after `undef *_` and `local *_` pass on both backends.

Remaining identical failures are assertions 2, 7, 9, and 24 in `perl5_t/t/op/goto-sub.t`:

1. The late undefined-target diagnostic says `called at` instead of `Goto undefined subroutine &Pkg::name at file line N`.
2. Temporary arguments from retired tail-call frames release one call too late in the repeated destructor-ordering case.
3. `utf8::encode` does not reify a missing `$_[0]` in the retained `@_` container.

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

## Relevant files

- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/ControlFlowMarker.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList.java`
- `src/main/java/org/perlonjava/backend/bytecode/BytecodeInterpreter.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeGlob.java`
- `src/test/resources/unit/goto_tailcall_cleanup.t`
- `perl5_t/t/op/goto-sub.t`
