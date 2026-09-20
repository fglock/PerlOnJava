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
  - Routed scalar stringification and native-array eligibility through the
    fixed-width payload while retaining INTEGER-tagged `Double` and wide-value
    fallbacks.
  - Added a one-way object-observation materialization boundary. Deep cloning,
    YAML cycle tracking, and bytecode disassembly invoke it before observing
    object identity; the focused test proves the deferred value is retained.
  - Added a canonical payload-copy protocol to central scalar assignment paths.
    An assignment copies an active payload into ordinary destination storage
    without flushing the source; the focused test covers this invariant.
  - Migrated base hash/array proxy synchronization, aliases, dynamic snapshots,
    and substring lvalue snapshots to the payload-copy protocol. Added a
    focused array-proxy regression for an active deferred element.
  - Migrated scalar dynamic save/restore/resume, read-only alias construction,
    and graph-cloner metadata copying to the payload-copy protocol. Graph
    cloning now converts an active deferred fixed-width payload into ordinary
    target storage before it can inspect the public object field.
  - Migrated tied FETCH/STORE caches, tied array/hash proxies, vector lvalues,
    localized package scalars, and hash-proxy restoration to the payload-copy
    protocol. Added focused tied FETCH-cache coverage for an active deferred
    source payload.
  - Made payload copying an explicit scalar API and migrated scalar
    constructors, overload result copies, and regex callback rollback to it.
    Added focused rollback coverage for an active deferred payload.
  - Migrated external Storable/Clone copies, readonly unwrap/wrap paths, and
    scalar `untie` restoration to the public payload-copy API.
  - Migrated mutable bytecode copies of readonly scalars, direct-call cache
    refresh, input-record validation rollback, and errno scope resumption to
    the payload-copy API.
  - Completed the raw-copy classification: the final INTEGER-capable copies in
    readonly method invocation and `$@` save/restore now use the protocol.
    Remaining raw pairs are CODE-only compiler/call snapshots or IO glob-slot
    copies and cannot observe an ordinary INTEGER payload.
  - Full immutable `make` passed in 4m01s at
    `/tmp/make_primitive_scalar_payload_getters_clean_20260920.log`, and
    again in 3m55s at
    `/tmp/make_primitive_scalar_payload_copy_constructor_final_20260920.log`,
    and in 3m51s at
    `/tmp/make_primitive_scalar_payload_string_increment_20260920.log`, and
    in 3m59s at
    `/tmp/make_primitive_scalar_payload_substr_20260920.log`, and in 4m05s at
    `/tmp/make_primitive_scalar_payload_stringification_20260920.log`, and
    in 4m12s at
    `/tmp/make_primitive_scalar_payload_object_observation_20260920.log`, and
    in 4m04s at
    `/tmp/make_primitive_scalar_payload_copy_protocol_20260920.log`, and in
    4m08s at `/tmp/make_primitive_scalar_payload_proxy_copy_20260920.log`, and
    in 4m at `/tmp/make_primitive_scalar_payload_graph_copy_20260920.log`, and
    in 3m51s at `/tmp/make_primitive_scalar_payload_tied_copy_20260920.log`.
    The scalar snapshot migration also passed in 3m47s at
    `/tmp/make_primitive_scalar_payload_regex_snapshot_20260920.log`.
    The external-copy migration also passed in 3m57s at
    `/tmp/make_primitive_scalar_payload_external_copy_20260920.log`.
    The bytecode/special-variable migration also passed in 3m51s at
    `/tmp/make_primitive_scalar_payload_special_copy_20260920.log`.
    The final raw-copy classification migration passed in 4m02s at
    `/tmp/make_primitive_scalar_payload_final_raw_copy_20260920.log`.

## Next steps

1. Define the smallest storage-state transition that cannot expose a stale
   public object payload across either backend.
2. Add focused regression coverage for every newly migrated observation
   boundary before changing representation state.
3. Measure only after a complete fallback and observability proof exists.

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

### Broad object-observation audit, second slice (2026-09-20)

The broader source scan found 759 scalar-associated raw payload references.
The dominant classes are type-gated: references, globs, IO, Java objects,
regexes, tied/read-only wrappers, and code values. An INTEGER cannot reach
those object casts, so they are not integer materialization sites.

Three generic paths are materialization-critical:

| Path | Why it matters | Required storage rule |
| --- | --- | --- |
| `Storable.deepClone` | Its identity map probes `scalar.value` before type dispatch. A shared integer sentinel could falsely join unrelated scalar values. | Track identity only for reference/object payloads, or materialize a per-cell object at this boundary. |
| `YAMLPP.convertRuntimeScalarToYaml` | Its cycle map likewise probes `scalar.value` before its INTEGER conversion. | Move cycle tracking behind reference cases or materialize explicitly. |
| bytecode `Disassemble` | It calls `scalar.value.getClass()` without a type guard for diagnostic output. | Use a null-safe materialized payload/class accessor. |

Therefore the initial general-storage prototype must not use one shared boxed
sentinel with an otherwise unchanged public field. It needs an explicit
per-cell active-payload state and the three boundary changes above. This is a
design constraint, not yet an implementation decision.

### Assignment and proxy audit, third slice (2026-09-20)

The follow-up assignment scan found `RuntimeScalar.set(RuntimeScalar)` copies
`type` and `value` directly on its ordinary fast path. More than fifty
proxy, clone, localization, tied-value, graph-copy, and package-state paths
likewise assign one scalar's `value` field to another. A new primitive active
state would silently lose its payload in those copies, or propagate a sentinel
as if it were the source value.

Before general storage can change, add a canonical scalar-payload copy
protocol and route each of these assignments through it. Assignment may
materialize at a documented identity/alias boundary, but must never copy only
the public object field. This prevents a setter-only optimization and keeps
the current work at the representation-proof phase.

## Related work

- [Performance over Perl handoff](performance-over-perl-handoff.md)
- [Main performance design](performance-over-perl.md)
- [Private native-array representation](private-native-array-representation.md)
