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
  - Migrated scalar truth and increment/decrement paths to the canonical
    fixed-width payload. Canonical INTEGER writes now retire deferred state,
    and scalar copies materialize a numeric snapshot instead of copying a
    boxed sentinel. Added focused mutation/copy regression coverage.
  - Routed `ScalarUtils.stringIncrement` empty and decimal INTEGER writes
    through the same canonical transition without a duplicate watcher
    notification. The focused mutation test covers both string forms.
  - Routed `substr` fixed-width offset and length reads through the canonical
    payload accessor; wide values retain the exact `BigInteger` path.
  - Full immutable `make` passed in 4m01s at
    `/tmp/make_primitive_scalar_payload_getters_clean_20260920.log`, and
    again in 3m55s at
    `/tmp/make_primitive_scalar_payload_copy_constructor_final_20260920.log`,
    and in 3m51s at
    `/tmp/make_primitive_scalar_payload_string_increment_20260920.log`, and
    in 3m59s at
    `/tmp/make_primitive_scalar_payload_substr_20260920.log`.

## Next steps

1. Complete a broader observability audit before replacing ordinary boxed
   integer storage: direct object reads must either be unreachable for
   INTEGER, use the payload accessor, or materialize explicitly.
2. Define the smallest storage-state transition that cannot expose a stale
   public object payload across either backend.
3. Add focused regression coverage for every newly migrated observation
   boundary before changing representation state.
4. Measure only after a complete fallback and observability proof exists.

### Direct-payload audit, first slice (2026-09-20)

The first searchable slice separates the apparent 105 `value` consumers into
the following categories. It confirms that a storage experiment must not alter
the public field first: most reads are type-directed reference, IO, regex,
serialization, debugger, or Java-object operations, but a small group does
read ordinary integer objects directly.

| Category | Current sites | Required treatment |
| --- | --- | --- |
| Primitive-safe numeric reads | `RuntimeScalar` boolean and increment/decrement paths; `Operator.substr` offset/length fast path | Change to the fixed-width accessor before a general primitive storage state. Preserve the `BigInteger` fallback. |
| Wide-only object reads | `BitwiseOperators.exactInteger`, `StorableWriter` wide integer encoding | Keep direct `BigInteger` checks: these deliberately need the object representation. |
| Numeric conversion fallbacks | `RuntimeScalar` `getIntLarge`, `getLong` switch, `getDoubleLarge` | Keep `Number` conversion for the `BigInteger`/unusual fallback after the fixed-width fast path. |
| Object-only reads | References, read-only/tied wrappers, globs, IO, regexes, Java objects, cloning, serialization, debugger diagnostics | A plain INTEGER cannot reach these typed paths. They remain object-boundary evidence, not candidates for integer payload access. |
| Direct writers | `RuntimeScalar.setIntegerValue`; `ScalarUtils.stringIncrement` | Route the string increment INTEGER writes through the canonical setter before any storage change. |

The JVM emitter/bytecode compiler did not reveal a generated direct `GETFIELD`
or `PUTFIELD` use of `RuntimeScalar.value` for ordinary INTEGER values in this
slice. That removes one compiler-specific blocker, but does not relax the
runtime audit or the public-field observability constraint.

## Related work

- [Performance over Perl handoff](performance-over-perl-handoff.md)
- [Main performance design](performance-over-perl.md)
- [Private native-array representation](private-native-array-representation.md)
