# PadWalker Support Plan

## Problem

`Reply` fails under `./jcpan -t Reply` because
`Reply::Plugin::LexicalPersistence` loads `PadWalker` and calls:

```perl
use PadWalker 'peek_sub', 'closed_over';
```

PerlOnJava currently cannot load the CPAN XS implementation:

```text
Can't load loadable object for module PadWalker: no Java XS implementation available
```

This is a PerlOnJava runtime capability gap, not a CPAN packaging issue.
PadWalker exposes Perl lexical pads: live `my` variables, closure captures,
and caller-frame lexical variables. Java reflection cannot recover this from
JVM local variables or stack frames after compilation. PerlOnJava must preserve
the relevant lexical metadata itself and expose it through a Java-backed module.

## Goals

1. Unblock practical CPAN users such as `Reply` with a small, supportable
   subset first.
2. Preserve correct aliasing: returned PadWalker values must refer to the live
   lexical cells, not copies.
3. Keep overhead low for programs that never load PadWalker.
4. Leave a clear path toward fuller PadWalker compatibility without promising
   unsupported behavior in the first phase.

## API Surface

PadWalker 2.5 exports:

```perl
peek_my peek_our closed_over peek_sub var_name set_closed_over
```

Phase 1 should implement only:

- `peek_sub($coderef)`
- `closed_over($coderef)`

Phase 2 should add:

- `peek_my($level)`
- `peek_our($level)`
- `var_name($level_or_coderef, $var_ref)`

Phase 3 should consider:

- `set_closed_over($coderef, \%vars)`

`set_closed_over` is intentionally last because it mutates a closure's captured
lexical bindings and has the largest risk of breaking PerlOnJava's existing
closure lifetime and reference-count tracking.

## Phase 1: Reply-Compatible Code Reference Introspection

### Required behavior

Implement a bundled `PadWalker.pm` facade backed by Java methods registered via
the existing XSLoader mechanism.

For `closed_over($coderef)`:

- Return a hash reference.
- Keys are sigil-qualified lexical names such as `'$x'`, `'@items'`, and
  `'%seen'`.
- Values are references to the live captured lexical cells.
- Include only variables used by the subroutine but declared outside it.

For `peek_sub($coderef)`:

- Return a hash reference.
- Include captured lexical variables that PerlOnJava can name and alias
  correctly.
- Do not fake entries for local pad variables whose runtime cells are not
  preserved on the `RuntimeCode` object.
- Document this as an initial PerlOnJava limitation.

This subset is enough for `Reply::Plugin::LexicalPersistence`, which calls
`peek_sub($code)` and then deletes keys returned by `closed_over($code)`.
The remaining entries become Reply's persisted lexical environment.

### Runtime metadata

PerlOnJava already tracks closure capture values in multiple places:

- JVM-backed code refs use captured instance fields and
  `RuntimeCode.capturedScalars` for scalar lifetime tracking.
- Interpreter-backed code refs use `InterpretedCode.capturedVars`.
- Eval-string paths carry captured variable names and runtime values for
  lexical visibility.

PadWalker needs a single runtime-facing metadata structure that records:

- sigil-qualified lexical name
- declaration kind where known (`my`, `state`, `our`)
- captured cell value (`RuntimeScalar`, `RuntimeArray`, `RuntimeHash`)
- whether the cell is closed over from an outer scope

Add this metadata to `RuntimeCode`, for example as a list or ordered map of
`PadEntry` records. Populate it when closures are constructed, not by scanning
Java stack frames later.

### Backend integration

Both code-generation paths must populate equivalent metadata:

- JVM backend: when `SubroutineParser` constructs a code object with captured
  parameters, pass the capture names alongside the captured runtime values.
- Interpreter backend: when `InterpretedCode.withCapturedVars(...)` is used,
  retain the parallel captured variable names already computed by the
  bytecode compiler/eval-string path.

The ordering must be stable and should follow the existing capture ordering
used for constructor/register setup. This avoids a separate name/value matching
algorithm.

### Module loading

Add:

- `src/main/perl/lib/PadWalker.pm`
- `src/main/java/org/perlonjava/runtime/perlmodule/PadWalker.java`

`PadWalker.pm` should match the CPAN module's public import shape but should
state in comments that this is a PerlOnJava-backed compatibility module.
`PadWalker.java` should register methods in package `PadWalker`, following
the existing Java-backed module pattern.

## Phase 2: Caller Frame Pad Introspection

`peek_my($level)` and `peek_our($level)` cannot be implemented from code-ref
capture metadata alone. They require the current call stack's live lexical
environment at the call point.

Add a frame-level lexical registry only when needed:

1. Introduce a lightweight `LexicalFrame` runtime object containing named live
   lexical cells for the current scope.
2. Push/pop lexical frames around subroutine calls only when PadWalker support,
   debugger support, or an equivalent introspection mode is active.
3. Expose frame lookup by PadWalker level using the same call-stack semantics
   as `caller`.
4. Keep `peek_my` and `peek_our` separate so `our` aliases can be filtered
   from true `my` lexicals once declaration-kind metadata is available.

Avoid always-on frame tracking until measured. Many PerlOnJava workloads depend
on low overhead in normal execution, and PadWalker is mostly a debugging and
REPL-support feature.

## Phase 3: Mutating Captures

`set_closed_over($coderef, \%vars)` should only be implemented after Phase 1
tests prove that captured cells are represented consistently across both
backends.

Required semantics:

- Rebind only variables already closed over by the target code ref.
- Require hash values to be references, matching PadWalker behavior.
- Preserve cell type compatibility by sigil.
- Update lifetime tracking so replaced scalar captures do not leak or lose
  DESTROY/weaken behavior.

If these invariants cannot be met cleanly, leave `set_closed_over` unsupported
with a clear error.

## Tests

Add focused unit tests before validating CPAN modules:

1. `closed_over(sub { $x })` returns `'$x'`.
2. Mutating `${ closed_over($sub)->{'$x'} }` updates the original lexical.
3. Captured scalar, array, and hash variables preserve sigils and aliasing.
4. Duplicate lexical names prefer the innermost captured binding.
5. `peek_sub` includes the captured variables needed by Reply.
6. Unsupported local pad entries are absent rather than copied or faked.
7. JVM backend and interpreter backend return the same metadata.
8. Closure lifetime tests still pass when PadWalker metadata is present.

Then validate:

```bash
timeout 900 ./jcpan -t Reply > /tmp/jcpan-reply-padwalker.log 2>&1
printf 'EXIT: %s\n' $? >> /tmp/jcpan-reply-padwalker.log
make > /tmp/make-padwalker.log 2>&1
printf 'EXIT: %s\n' $? >> /tmp/make-padwalker.log
```

Expected Phase 1 result: `Reply::Plugin::LexicalPersistence` loads past the
PadWalker failure. Any later Reply failure should be investigated separately.

## Risks

- Over-capturing every visible lexical would regress memory use and undo recent
  selective-capture optimizations.
- Returning copied values would make Reply appear to work while breaking
  lexical persistence semantics.
- Caller-frame APIs may impose runtime overhead if frame tracking is always on.
- `set_closed_over` can corrupt closure lifetime tracking if implemented before
  capture metadata is unified.

## Progress Tracking

### Current Status

Plan only. No PadWalker implementation has landed.

### Next Steps

1. Add Phase 1 runtime metadata to `RuntimeCode`.
2. Populate metadata from both JVM and interpreter closure construction paths.
3. Add the bundled `PadWalker.pm` facade and Java-backed `PadWalker` module.
4. Add Phase 1 unit tests.
5. Re-run `./jcpan -t Reply` under `timeout`.

### Open Questions

- Should PadWalker frame tracking share infrastructure with the debugger plan?
- Should `peek_sub` eventually preserve local pad variables, or should
  PerlOnJava document captured-variable support as the long-term boundary?
- What runtime flag should enable caller-frame lexical tracking for Phase 2?
