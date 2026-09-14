# Private Native Array Representation

## Status

Design phase — no runtime representation or compiler selection is implemented.

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

## Implementation phases

1. Add a pure frontend dataflow analyzer with declaration-identity facts and
   unit tests for acceptance and every rejection category. It must not affect
   code generation.
2. Add JVM carrier local-slot support and one-way materialization helpers,
   still with selection disabled by default.
3. Enable the narrow direct-read/direct-write/length/copy subset only after
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
