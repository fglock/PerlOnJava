# Primitive scalar payload

## Objective

Remove avoidable `Integer` and `Long` allocation from ordinary mutable integer
`RuntimeScalar` cells without changing Perl-visible scalar identity, mutation,
reference, tie, watcher, or coercion behavior.

This is a shared runtime boundary: integer cells are used by lexical variables,
array and hash values, arithmetic results, loop topics, and method results. It
is not a Life-, method-, or source-shape specialization.

## Evidence

The steady-state Life recording at
`/private/tmp/perf-life-steady-baseline-20260920.jfr` ran at roughly 2.54--2.62
million operations/second after warmup. JFR allocation samples attribute about
3.5 GB weighted allocation across 332 samples to
`RuntimeScalar.setIntegerValue(long)`. The hot path is an ordinary
`RuntimeArray.setUnsignedWordElement` store which retains a mutable scalar cell
but boxes its changing integer payload through `Integer.valueOf` or
`Long.valueOf`.

The prior native-array carrier experiment is not a remedy: safely selecting
its nested-loop form regressed Life by about 87% against the exact parent.
Avoiding one container path while adding guards is not a representation win.

## Existing model

`RuntimeScalar.value` is a public volatile `Object`. Integer values are stored
as `Integer`, `Long`, or `BigInteger`; a scalar object may itself be observed
as an lvalue through references, aliases, ties, watchers, and container APIs.
The existing `primitiveFlowInteger` fields demonstrate a limited deferred
payload mechanism for compiler-proven closed numeric loops. It cannot be
applied generally: code outside its guarded surface may read `value` directly,
and a stale boxed sentinel would be incorrect.

The initial source audit found 105 potential `RuntimeScalar.value` consumers.
The direct fixed-width INTEGER readers include `RuntimeArray`,
`BitwiseOperators`, `MathOperators`, `StringOperators`, and `RuntimeCode`;
the rest require classification as object-only, already materializing, or
representation-sensitive consumers before any field migration.

## Candidate architecture

Do not change one setter in isolation. A viable general design must provide:

1. A scalar-owned primitive signed-integer payload and an explicit active
   representation state, with `BigInteger` retaining the existing object path.
2. Canonical runtime accessors for integer payload, object payload, copying,
   stringification, numeric coercion, arithmetic, bitwise operations, and
   container reads. Any direct `value` read must either be converted or first
   materialize the current primitive payload.
3. A one-way materialization boundary for identity-sensitive observation:
   references, aliases, tied or read-only scalars, watchers, package roots,
   serialization, cloning, debugger exposure, Java interoperation, and code
   that requests the object payload.
4. An ordinary fallback before any unsupported mutation or observation. Tied,
   read-only, watched, tainted, `BigInteger`, and string-preserving paths must
   retain current behavior.

The scalar cell itself remains allocated and stable. The target is removal of
payload boxing, not reuse of cells or a new array representation.

## Required proof and acceptance

- Map every `RuntimeScalar.value` access that can observe an INTEGER payload;
  replace it with a representation-safe accessor or prove its preceding
  materialization boundary.
- Add permanent tests for scalar and array lvalue identity, references,
  aliases, tie, watcher notification, readonly behavior, taint, `BigInteger`,
  overflow, string/numeric dualvars, copying, and both execution backends.
  Validate new Perl-level tests with system Perl first.
- Show the unfixed parent allocates boxed integer payloads on the focused
  regression, then show the corrected JVM path avoids those allocations while
  interpreter semantics remain ordinary.
- Run JVM/interpreter focused coverage and immutable `make` before measuring.
- Retain an implementation only if alternating fresh-process exact-parent and
  Perl portfolio evidence demonstrates a material, repeatable whole-workload
  gain. A JFR allocation decrease alone is insufficient.

## Status

Phase 1, representation-safe core accessors, completed (2026-09-20). No
payload storage change is selected yet; no native-array widening or
method-body specialization is part of this phase.

### Completed phases

- [x] Phase 1: Fixed-width payload accessors (2026-09-20)
  - Added `RuntimeScalar` predicates and a primitive-flow-aware fixed-width
    integer accessor.
  - Migrated core array, bitwise, math, numeric-flow, and call eligibility
    readers away from direct fixed-width `value` inspection.
  - Added `RuntimeScalarIntegerPayloadTest`, including array and bitwise
    observation of a deferred primitive-flow payload.
  - Routed `getInt`, `getLong`, `getDouble`, `getBigint`, and unsigned-long
    conversion through the fixed-width accessor where valid, while retaining
    `BigInteger` conversion for UV and wide values. The focused test covers
    both representations and prevents the rejected-accessor regression in
    pack, `sprintf`, and integer bitwise paths.
  - Full immutable `make` passed in 4m01s at
    `/tmp/make_primitive_scalar_payload_getters_clean_20260920.log`.

## Next steps

1. Classify the remaining direct INTEGER `value` consumers by materializing
   observation versus primitive-safe numeric use; preserve a direct-object
   path for Java interoperation and identity-sensitive consumers.
2. Define the smallest storage-state transition that cannot expose a stale
   public object payload across either backend.
3. Add focused regression coverage for every newly migrated observation
   boundary before changing representation state.
4. Measure only after a complete fallback and observability proof exists.

## Related work

- [Performance over Perl handoff](performance-over-perl-handoff.md)
- [Main performance design](performance-over-perl.md)
- [Private native-array representation](private-native-array-representation.md)
