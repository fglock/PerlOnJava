# Private Native Array Representation

## Status

Active narrow JVM implementation; selection remains deny-by-default.

## Objective

Permit a JVM-compiled lexical array whose complete lifetime is statically
private and native-integer-only to use contiguous primitive storage while it
remains unobservable. The representation must materialize an ordinary
`RuntimeArray` before any Perl operation can observe array or element identity.
This targets the scalar/list transport identified in the Life performance
recording; it is not a specialization for that workload.

## Why an element fast path is insufficient

`EmitVariable` currently lowers isolated word assignments only after guards on
each source and destination element. It still reads and writes a
`RuntimeArray`, and `setUnsignedWordElement` must retain ordinary
`RuntimeScalar` identity for observable element cells. Consequently, a fresh
array and each write still allocate scalar storage. Removing that allocation at
the setter would break references retained across array assignment and other
ordinary array semantics.

The representation must instead span an array's entire private lifetime. It
must not reuse, rebind, or mutate the ordinary representation unless it first
materializes it.

## Representation contract

The JVM-only representation is a compiler-local carrier containing:

- a `long[]` value buffer and logical length;
- a flag describing signed/unsigned word interpretation required when
  materializing scalar values; and
- an optional ordinary `RuntimeArray` after deoptimization/materialization.

Before materialization, no Perl-visible `RuntimeArray` or element scalar exists
for the carrier. On materialization, create distinct ordinary `RuntimeScalar`
cells in order, preserving byte-for-byte integer behavior and all normal
container ownership bookkeeping. Once materialized, the lexical uses the
ordinary path permanently; no re-entry into primitive storage is allowed.

This one-way rule makes aliases, references, and later mutation ordinary. It
also avoids trying to synchronize two authoritative containers.

## Selection proof

Selection is per lexical declaration identity, not by variable name or source
shape. A declaration is eligible only if all control-flow paths prove all of
the following:

1. Its initializer is a fresh primitive producer, a copy of another proven
   private carrier, or an empty private array.
2. Every read is a supported direct indexed native-word read, array length
   query, or supported whole-array copy into another proven carrier.
3. Every write is a supported direct indexed native-word write. Index and RHS
   must be side-effect-free native expressions already accepted by the existing
   word lowerer.
4. No reference, alias, dereference, argument passing, return, interpolation,
   hash/list storage, `@_`, tie, `local`, `eval`, callback, method/user call,
   or dynamic operation reaches the array.
5. No nested closure captures the declaration unless the closure and every
   capture transitively satisfy the same proof with an explicit shared carrier
   lifetime. The first implementation rejects all captures.
6. Every branch, loop back-edge, exception edge, and non-local control-flow
   join has identical carrier facts. Any unknown edge rejects the declaration.
7. Debugger/caller-visible state, warnings, destructor timing, and scope exit
   preserve ordinary behavior: the initial implementation rejects contexts
   where those can inspect or run code against the lexical.

The analysis is deny-by-default. A false negative only uses the existing
`RuntimeArray` path; a false positive would be a Perl semantic bug.

## Materialization boundaries

The emitter must materialize before it emits any of the following:

- array or element lvalue creation, `\@array`, `\$array[index]`, or any
  dereference;
- array/list assignment with an unproven source or destination;
- subroutine/method invocation, return, callback, `eval`, `local`, tie, or
  dynamic symbol-table operation;
- interpolation, stringification, hashing, comparison outside the supported
  native word operators, or an unsupported array built-in;
- closure creation or capture; and
- exception and non-local control-flow paths that can expose a lexical frame.

After such a boundary, the compiler must emit the ordinary AST exactly once,
using the materialized local slot. No partially evaluated expression may be
replayed.

## Progress tracking

### Current status: phases 1--2 complete for a narrow write-only-loop JVM subset (2026-09-15)

Commit `d863f4a09` adds `PrivateNativeArrayAnalyzer` and five focused Java
tests. It is intentionally compiler-inert. The analyzer annotates only the
small direct literal-index native-word subset and rejects references, call
escapes, closures, and dynamic indexes. The final immutable `make` gate passed
in 3m 58s at `/tmp/make-private-native-array-analyzer-final-20260915.log` on
the production-like simulation host. No benchmark was run because this phase
cannot change generated code or runtime performance.

Commit `b3c58f1e7` adds the phase-two `PrivateNativeArrayCarrier` and focused
runtime unit tests. It preserves array holes, performs the existing unsigned
word conversion when materializing, and permanently rejects native access
after materialization. The compiler never instantiates it yet. Its immutable
full `make` gate passed in 3m 43s at
`/tmp/make-private-native-array-carrier-20260915.log`. This remains
infrastructure, not a measured optimization.

Commit `116cb623b` refines the analyzer's supported straight-line subset so
that a native read requires a preceding literal-index write. This preserves
the distinction between a hole/Perl `undef` and a raw zero. Commit `09435ed53`
executes the proof at JVM block emission, beside `NumericFlowAnalyzer`, while
leaving the annotation unconsumed. Their immutable `make` gates passed in
3m 38s and 3m 54s at
`/tmp/make-private-native-array-initialization-proof-20260915.log` and
`/tmp/make-private-native-array-prepass-20260915.log`, respectively.

Commit `32ac5185a` enables the narrow JVM path. It allocates a separate carrier
local slot, emits `setWord`/`wordAt` only in supported void-context direct word
assignments, and materializes the ordinary lexical `RuntimeArray` before any
normal array access. The permanent Perl regression passes on system Perl, JVM,
and interpreter; final full `make` passed in 3m 33s at
`/tmp/make-private-native-array-carrier-final-20260915.log`. Its JVM
disassembly records the carrier and all three handoff methods. The selected
subset remains intentionally small: it rejects branches, loops, closures,
`eval`, and `try`, so it does not yet cover Life and has no portfolio
measurement.

Commit `770d7d7e7` extends that selection only to a generic bounded,
write-only initializer: `for my $i (0 .. N) { $array[$i] = EXPR }`. The proof
requires a lexical loop index, literal non-negative bound, no continue block,
and a body made exclusively of direct carrier writes whose RHS is a literal,
that loop index, or a supported native-word expression. It rejects carrier
reads, so it does not infer an initialization fact across a back edge. The
focused Perl regression passes on system Perl at
`/tmp/prove-private-native-array-loop-perl-20260915.log`, JVM and interpreter
at `/tmp/jperl-private-native-array-loop-jvm-20260915.log` and
`/tmp/jperl-private-native-array-loop-interpreter-20260915.log`; its immutable
full gate passed in 4m at `/tmp/make-private-native-array-loop-20260915.log`.
Disassembly at `/tmp/jperl-private-native-array-loop-disassemble-20260915.log`
confirms `setWord` occurs in the loop and materialization occurs only at the
ordinary observation. It is not a Life result and no benchmark was run.

Commit `775d05a4b` tracks one additional dataflow fact: a
complete earlier `0 .. N` initializer proves the same direct dynamic index is
initialized for a later `0 .. N` self-update. That allows a carrier `wordAt`
only when the proved initialized prefix covers the whole later loop; it rejects
partial ranges and all unmodeled reads. The permanent regression passes on
system Perl at `/tmp/prove-private-native-array-loop-read-perl-20260915.log`,
JVM and interpreter at
`/tmp/jperl-private-native-array-loop-read-jvm-20260915.log` and
`/tmp/jperl-private-native-array-loop-read-interpreter-20260915.log`; the
immutable full gate passed in 3m 40s at
`/tmp/make-private-native-array-loop-read-20260915.log`. Disassembly at
`/tmp/jperl-private-native-array-loop-read-disassemble-20260915.log` confirms
the later loop uses `wordAt` and `setWord` before the final ordinary
observation. This is not a Life result and no benchmark was run.

Commit `3930df7d2` also admits a fresh bare lexical declaration while guarding
Devel::LexAlias/PadWalker-style observability: when lexical alias support is
enabled, the carrier retains the resolved ordinary `RuntimeArray` and is
permanently terminal. The system-Perl, JVM/interpreter, and full-gate evidence
is `/tmp/prove-private-native-array-bare-perl-20260915.log`,
`/tmp/jperl-private-native-array-bare-jvm-20260915.log`,
`/tmp/jperl-private-native-array-bare-interpreter-20260915.log`, and
`/tmp/make-private-native-array-bare-20260915.log` (4m 02s). It is a
prerequisite for `@next`, not a Life performance result.

### Exact resume steps

1. Inspect active benchmark/test processes and their worktrees; wait for all
   children before modifying a checkout. Do not restart completed artifacts.
2. Generalize the initialized-prefix fact into a per-iteration/range lattice
   with exact materialization joins before accepting dynamic bounds, another
   source array, or nested loops. Preserve deny-by-default fallback and add
   focused rejection coverage before accepting each control-flow form.
3. Cover materialization through aliases, callbacks, exceptions, early return,
   and closure rejection with system-Perl-validated tests before widening
   selection beyond the current straight-line subset.
4. Once a representative Life-shaped loop selects, run JVM/interpreter
   coverage and immutable `make`, then alternating exact-parent Life pairs.
   Run the complete source/JAR-matched portfolio only after a retained gain.

## Implementation phases

1. Add a pure frontend dataflow analyzer with declaration-identity facts and
   unit tests for acceptance and every rejection category. It must not affect
   code generation.
2. Add JVM carrier local-slot support and one-way materialization helpers.
3. Enable the narrow direct-read/direct-write subset only after
   system-Perl oracle tests cover materialization, element identity, aliases,
   callbacks, exceptions, early return, and closure rejection.
4. Validate JVM and interpreter parity (the interpreter continues using
   `RuntimeArray`), run immutable `make`, then run alternating exact-parent
   portfolio pairs on the production-like host. Retain only a material,
   repeatable improvement.

## Non-goals

- Do not change `RuntimeArray.setUnsignedWordElement` in isolation.
- Do not reuse the retired scalar-only cleanup annotations or omit cleanup
  walks as a proxy for representation privacy.
- Do not recognize the Life loop, a fixed range, or any benchmark-specific
  AST shape.
- Do not use a global deferred representation or permit a carrier to escape.

## Related work

- [Performance-over-Perl handoff](performance-over-perl-handoff.md)
- [Performance experiments](performance-over-perl-experiments.md)
