# `goto &sub` parity handoff

## Objective

Complete Perl-compatible `goto &sub` behavior on the JVM and bytecode interpreter backends. The acceptance target is every applicable assertion in `perl5_t/t/op/goto-sub.t`, with identical JVM and interpreter results.

## Current state

Both backends use `RuntimeCode.resolveTailCalls()` for tail-call marker dispatch. Named-target `AUTOLOAD`, chained calls, recursion, localized/replaced `@_`, and absent ARRAY slots after `undef *_` and `local *_` pass on both backends.

UAT uncovered two remaining regressions in the imported core tests:

- `perl5_t/t/op/goto-sub.t`: destructor checks 7 and 9 run one handoff late on both JVM and interpreter. Several scoped mortal-cleanup experiments either regressed named redefinition or `Sub::Quote` metadata; none are retained in the current source state.
- `perl5_t/t/uni/goto.t`: JVM now passes all four assertions. A retained named tail-marker diagnostic gives the expected undefined-subroutine message. The interpreter has the same message, but resolves it after the eval-block catcher has unwound, so it exits after test 3 rather than setting `$@` for test 4.

Completed since the initial handoff:

- Tail-call markers carry explicit named-source identity, deferred eval scope, and the original `@_` container.
- A named target is freshly resolved after source-frame cleanup; core assertion 2 now passes with the required `Goto undefined subroutine ... at file line N` form.
- Literal `@_` uses the live/current-frame-localized argument container in both emitters; sparse `$_[0]` reification now passes core assertion 24.
- `src/test/resources/unit/goto_tailcall_cleanup.t` passes on system Perl, JVM, and interpreter with 60-second process timeouts.

The previously remaining failures in assertions 7, 9, and 18 in
`perl5_t/t/op/goto-sub.t` are fixed in the exercised core path on both
backends:

1. Deferred mortal-stack decrements are flushed at the completed `goto &sub`
   handoff, so temporary arguments from retired frames are destroyed before
   the next call (assertions 7 and 9).
2. Eval restrictions now use Perl's required `from an eval-string` and
   `from an eval-block` diagnostics (assertion 18).

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

### Current Status: UAT follow-up in progress (2026-09-01)

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

### Next Steps

1. Resolve `goto &sub` destructor ordering in core assertions 7 and 9 without
   globally draining caller-owned deferred entries.
2. Resolve interpreter tail markers inside the eval-block catcher so
   `uni/goto.t` test 4 sets `$@` rather than escaping at top level.
3. Add permanent focused regression coverage for both UAT observations,
   validate new tests with system Perl, then rerun both backends.
4. Obtain a successful immutable full `make` gate and update PR CI.

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
