# `goto &sub` parity handoff

## Objective

Complete Perl-compatible `goto &sub` behavior on the JVM and bytecode interpreter backends. The acceptance target is every applicable assertion in `perl5_t/t/op/goto-sub.t`, with identical JVM and interpreter results.

## Current state

Both backends use `RuntimeCode.resolveTailCalls()` for tail-call marker dispatch. Named-target `AUTOLOAD`, chained calls, recursion, localized/replaced `@_`, and absent ARRAY slots after `undef *_` and `local *_` pass on both backends.

The two UAT regressions are resolved on both backends:

- `RuntimeCode.resolveTailCalls()` drains only deferred referents preserved in
  the tail-call argument container after a completed replacement call, so
  destructor ordering is correct without touching caller-owned metadata.
- Eval-scoped tail-call markers are resolved inside the bytecode interpreter's
  active eval boundary. This preserves the named-undefined-target-before-eval
  diagnostic order and lets the catcher populate `$@`.
- Dynamic `goto $coderef` now carries compile-time eval scope in its bytecode
  operand rather than inferring eval-string provenance from a caller's runtime
  eval depth. A normal sub can therefore tail-call through a tied coderef when
  invoked by an eval block.

Completed since the initial handoff:

- Tail-call markers carry explicit named-source identity, deferred eval scope, and the original `@_` container.
- A named target is freshly resolved after source-frame cleanup; core assertion 2 now passes with the required `Goto undefined subroutine ... at file line N` form.
- Literal `@_` uses the live/current-frame-localized argument container in both emitters; sparse `$_[0]` reification now passes core assertion 24.
- `src/test/resources/unit/goto_tailcall_cleanup.t` passes on system Perl, JVM, and interpreter with 60-second process timeouts.

Focused validation now passes on system Perl, JVM, and interpreter:
`goto_tailcall_cleanup.t`, all 44 assertions in `goto-sub.t`, and all four
assertions in `uni/goto.t`. A successful immutable full `make` gate remains
required before the PR can be updated.

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

### Current Status: Implementation complete; full gate pending (2026-09-01)

### Completed Phases

- [x] Marker identity and late named-target lookup
  - Files: `ControlFlowMarker.java`, `RuntimeControlFlowList.java`, `RuntimeCode.java`, JVM and bytecode emitters.
- [x] Live `@_` handoff for literal `goto &name`
  - Files: `CompileOperator.java`, `BytecodeInterpreter.java`, `RuntimeArray.java`.
  - Core sparse argument assertion 24 passes on both backends.
- [x] Tail-call cleanup timing and exercised eval restriction diagnostics
  - `RuntimeCode.resolveTailCalls()` flushes deferred source-frame decrements
    after consuming marker ownership and emits the exact eval diagnostics.
  - Core `goto-sub.t` assertions cover the eval-string diagnostic; the focused
    regression covers target lookup and repeated destructor ordering.
- [x] Direct JVM eval-string trampoline and sparse argument handoff
  - `evalStringWithInterpreter()` now resolves tail-call markers at the eval
    execution boundary, matching `EvalStringHandler`.
  - `CompileOperator` recognizes a list-wrapped literal `@_` and preserves the
    live argument container for bytecode tail calls.
  - Expanded `goto_tailcall_cleanup.t` covers eval strings, late `AUTOLOAD`,
    sparse argument reification, and absent ARRAY slots after `undef *_` and
    `local *_` on system Perl, JVM, and interpreter.
- [x] Top-level anonymous-coderef invocation
  - `Variable.parseCoderefVariable()` now lowers bare `&{sub {...}}` to an
    auto-call sharing `@_`, while `\&{sub {...}}` remains reference-taking.
  - Added `top_level_coderef_call.t`; system Perl, JVM, and interpreter pass
    invocation side-effect, scalar-return, and reference-taking assertions.
- [x] Rebase regression repair
  - Tail-call scope cleanup now drains only the retired frame's mortal entries;
    it no longer releases caller-owned deferred `Sub::Quote` metadata.
  - Restored refcount-aware ARRAY/HASH typeglob detachment so saved slots can
    be re-installed after `undef`.
  - `sub_quote_qsub_metadata.t`, `typeglob_undef_slot_semantics.t`, and
    `goto_tailcall_cleanup.t` pass on system Perl, JVM, and interpreter.
- [x] JVM undefined Unicode tail-marker diagnostic
  - Top-level marker resolution and named-target preservation now report
    `Goto undefined subroutine &main::因` rather than an internal escaped-marker error.
  - `perl5_t/t/uni/goto.t` passes all four assertions on the JVM backend.
- [x] Completed handoff cleanup and eval-boundary parity
  - `RuntimeCode.resolveTailCalls()` drains only pending referents from the
    preserved tail-call argument container after the replacement call
    completes, fixing core destructor assertions 7 and 9 without regressing
    `Sub::Quote` metadata.
  - `GOTO_TAILCALL` resolves eval-scoped markers inside the interpreter's
    catcher; `GOTO_DYNAMIC` carries compile-time eval scope to avoid treating
    normal subs called from eval as eval-string code.
  - `goto_tailcall_cleanup.t` adds destructor-ordering, Unicode eval-block,
    and dynamic tied-coderef regressions; it passes on system Perl, JVM, and
    interpreter. Core `goto-sub.t` (44 assertions) and `uni/goto.t` (4)
    pass on both backends.

### Next Steps

1. Obtain a successful immutable full `make` gate and inspect its complete log.
2. Commit the implementation, update PR #1205, and monitor CI before UAT.

### Validation note

Repeated bounded `make` gates rebuilt Java sources and the shadow JAR and
passed Joni packaging verification. Four unit shards completed; the remaining
shard repeatedly stayed CPU-active in `unit/goto_named_redefinition.t` until
the bounded local gate was stopped. Do not treat those attempts as passing
full gates.

The direct JVM one-liner (`sub target{}; eval q{goto &target}`) is now covered
by the focused regression and reports the expected eval-string diagnostic on
both backends.

## Relevant files

- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/ControlFlowMarker.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList.java`
- `src/main/java/org/perlonjava/backend/bytecode/BytecodeInterpreter.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeGlob.java`
- `src/test/resources/unit/goto_tailcall_cleanup.t`
- `perl5_t/t/op/goto-sub.t`
