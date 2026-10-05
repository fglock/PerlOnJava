# Perl-compatible ownership rewrite

**Status:** Phase 1 in progress; recursive closure pad release is the first verified lifecycle path

**Tracking:** [#1649](https://github.com/fglock/PerlOnJava/issues/1649)

**Baseline:** `c38ef8adff2b8067c4ff51fa929ec491f89baa8e` (2026-10-05)

This document records the implementation contract, migration boundaries, and
evidence for the ownership rewrite proposed in #1649. The older
`refcount_alignment_plan.md` describes historical fixes to the selective
counter; it does not define this rewrite's phase boundaries.

## Contract

Represent each Perl-visible strong owner as an explicit owner slot with a
unique identity and an owner kind. Acquiring, transferring, and releasing a
slot go through one API. Referent lifecycle state is separate from its
nonnegative strong-owner count. Counted cycles remain retained for the owning
runtime while their Perl owner count is positive. At an unmigrated boundary,
an explicit legacy owner bridge accounts for the edge; no edge may be counted
by both systems or by neither.

Java references and `RuntimeScalar` wrappers do not by themselves imply Perl
owners. In particular, lexical aliases, borrowed call arguments, aggregate
slots, closure pads, and mortal temporaries have different ownership rules.
Every path must state whether it creates an owner, borrows a cell, transfers an
owner, or creates a deferred mortal owner.

## Baseline ownership map

| Concern | Current implementation | Phase 1 implication |
|---|---|---|
| Referent identity and lifecycle | `RuntimeBase.refCount` combines selective counts with `-1`, `-2`, and `Integer.MIN_VALUE` sentinels; `blessId`, destruction flags, and owner metadata are separate fields | Count and lifecycle must be split for migrated referents; the legacy fields remain behind the boundary for unmigrated referents |
| Scalar owner identity | `RuntimeScalar` is both a Perl scalar cell and a Java wrapper; its copy constructor does not acquire a referent count; `refCountOwned` is a Boolean on the wrapper | A Boolean cannot describe multiple owner kinds or transfers; ownership token identity must follow the slot, not Java object reachability |
| Reference creation | `RuntimeScalar.createReference`, aggregate `createReference` methods, blessing, and scalar-reference promotion create reference values with different initial tracking states | Each producer must initialize the same identity/lifecycle contract or cross the bridge explicitly |
| Scalar stores | `RuntimeScalar.setLargeRefCounted` adjusts referent counts, ownership metadata, scalar-reference contents, weak state, IO state, and resurrection recovery | Central retain/replace/release ordering must preserve self-assignment and destructor reentrancy |
| Aggregate stores | `RuntimeArray` / `RuntimeHash` mutation paths copy scalar wrappers and use `incrementRefCountForContainerStore`; deletion and scope cleanup are handled separately | The first path cannot stop at lexical scalar stores; container slot acquisition and removal must use the same ownership contract |
| Captures and calls | `RuntimeScalar` capture counters, `RuntimeCode`, call frames, argument arrays, and cleanup code each have special accounting | These remain legacy-bridged unless the first path directly crosses them; borrowed aliases must not be translated into owned slots |
| Deferred cleanup | `MortalList` queues decrements and uses owner metadata, weak registries, root proofs, and `ReachabilityWalker` fallback | Migrated releases must preserve Perl mortality ordering while removing whole-root reachability as the authority for migrated slots |
| Destruction and weak references | `DestroyDispatch`, `WeakRefRegistry`, global teardown, and scalar cleanup interpret the legacy sentinel state | The migrated owner count must be the sole dispatch authority for migrated referents; bridge tests must prevent duplicate dispatch |
| JVM cleanup | `EmitStatement`, `EmitControlFlow`, and `EmitBlock` emit lexical/aggregate cleanup; Java runtime methods are shared by both backends | Cleanup must release the same owner slot on fall-through, return, exceptions, loop exits, goto, and eval for JVM and interpreter |
| Interpreter cleanup | `BytecodeCompiler` emits `SCOPE_EXIT_CLEANUP*`; `BytecodeInterpreter` implements ordinary and exceptional cleanup | Opcode cleanup must use the same slot contract and preserve returned values |
| Cycles and runtime teardown | `ReachabilityWalker` makes corrective decisions over Java object graphs; `PerlRuntime` owns execution state | Migrated positive-count cycles need explicit per-runtime semantic retention and a defined teardown path |

### Boundary finding

A scalar-lexical-only migration is not safe. The same referent may move between
a scalar cell and an array/hash slot, and scalar wrappers may represent copies
or borrowed aliases. Their counter transitions also interact with closure
captures, return values, `MortalList`, weak clearing, and `DESTROY`. Turning on a
new scalar-only counter would omit owners at aggregate stores and could dispatch
destruction while a legacy owner still exists.

This finding rules out a scalar-only migration. The reduced #1618 reproducer
also rules out scalar plus array/hash slots as a complete first path: the
failing `HTML::Element->new_from_lol` lifecycle creates a recursive closure,
stores its node references in aggregate slots, and undefines the closure before
returning. Perl 5.44 releases the closure's pad owners there; PerlOnJava retained
the tree. `undef $sub` is a scalar slot overwrite, not the separate `undef &sub`
CV-body operation.

The first verified fix is at the reachability boundary. The interpreter's
`capturedVars` array is execution metadata and can still contain a pad after
that pad has been removed from the closure's semantic capture-owner list. The
reachability walker treated this retired metadata as a live strong edge, found
a self-cycle, and stopped `releaseCaptures()` from releasing the remaining
pads. The closure owner lists are now authoritative for this path. The new
dependency-free regression reproduces the lifecycle, passes on Perl 5.44 and
both PerlOnJava backends, and fails on the parent interpreter backend.

This fixes the demonstrated path but does not yet implement the new owner-slot
count/lifecycle model. A typed `PerlOwnerSlot` identity is now wired into
closure-pad semantic ownership, including transfer when a captured pad changes
referents and idempotent release. The selective `refCount` increment and
deferred decrement still form the legacy lifecycle bridge; scalar stores,
aggregate slots, weak-reference transitions, and `DESTROY` are not yet migrated.
Phase 1 is complete only after the owner-slot API and bridge cover the selected
closure, scalar, aggregate, and deferred-release path on both backends.

## Phases

| Phase | Scope | Exit evidence |
|---|---|---|
| 0. Oracle and boundary audit | Pin Perl 5.44 oracle; reproduce #1618 and #1642; inventory ownership edges; capture baseline behavior and cleanup costs | Oracle configuration, permanent reproducers, source/sink inventory, and a selected path with a demonstrated boundary |
| 1. First complete owned-slot path | Core owner identity/count/lifecycle API; closure pad slots and release; direct scalar and array/hash owner slots; deferred release bridge; weak/DESTROY transitions; positive-count cycle retention | Enabled by default for the path on both backends; a permanent externally visible regression fails on the parent and passes after; primitive and bridge tests pass |
| 2. Calls and captured values | Argument aliases, return transfers, mortal temporaries, closures, pads, all cleanup exits and nested eval/fallback | Migrated call/capture paths are default-enabled and require no corrective whole-root scans |
| 3. Remaining owner families | Globs, constants, tied/magic and provider boundaries, refcount APIs, thread/shared ownership, resources, runtime reset, global destruction | Inventory has no unsupported ownership edge; lifecycle and integration coverage passes |
| 4. Qualification and legacy removal | Ecosystem/performance acceptance, stabilization, delete obsolete counter corrections and migration bridges | Full acceptance passes before and after removal with recorded CPU, allocation, heap and deterministic cleanup costs |

Phase numbers are delivery boundaries, not estimates of the total project.
The 20–36 engineer-week estimate remains provisional until the ownership audit
and first path establish implementation cost.

## Progress tracking

### Current status: Phase 1 in progress; closure-pad release regression fixed

### Completed

- [x] Re-read the parent issue's implementation contract and reviewed the
  independent lifecycle candidate handoff. Historical candidate test results
  are not treated as current Phase 1 evidence.
- [x] Mapped the principal referent, scalar, aggregate, call/capture,
  deferred-cleanup, lifecycle, cycle, JVM cleanup, and interpreter cleanup
  components.
- [x] Built an isolated Perl 5.44.0 oracle from the local source tag and
  reproduced unchanged HTML-Tree 5.07 `t/refloop.t`: Perl passes 8/8 while the
  parent JVM fails 4/8 with retained weak references.
- [x] Reduced the failure to recursive `new_from_lol` closure release and
  recorded an oracle-validated baseline reproducer at
  [`dev/repros/issue-1649-html-tree-lifecycle.t`](../repros/issue-1649-html-tree-lifecycle.t).
- [x] Added the permanent dependency-free regression at
  [`src/test/resources/unit/refcount/issue_1649_recursive_builder_pad_release.t`](../../src/test/resources/unit/refcount/issue_1649_recursive_builder_pad_release.t).
  Perl 5.44 and both PerlOnJava backends pass; the parent interpreter fails
  the release assertions.
- [x] Removed retired `InterpretedCode.capturedVars` entries from reachability
  ownership walks. The explicit capture-owner lists now determine whether
  closure pads are live Perl edges.
- [x] Added an oracle-validated parser lifecycle reproducer at
  [`dev/repros/issue-1649-html-treebuilder-lifecycle.t`](../repros/issue-1649-html-treebuilder-lifecycle.t)
  for the remaining content and incremental parsing failures.
- [x] Added typed owner-slot identity for closure pads, with acquire/transfer/
  release operations and a primitive identity regression. Existing selective
  refcount and deferred-release transitions remain the compatibility bridge.

### Next steps

1. Make the closure-pad owner slot own its legacy count/deferred-release bridge
   directly, then add acquisition, transfer, overwrite, scope-exit, and weak
   transition bridge tests.
2. Migrate direct scalar and array/hash owner slots through the same API while
   preserving transfers and deferred release ordering.
3. Reproduce #1642's repeated deferred-cleanup root queries with deterministic
   counters on this baseline; separate that cost regression from Phase 1
   correctness unless the selected ownership path demonstrates the connection.
4. Resolve the immediate cleanup failures in the content and incremental
   parser paths; verify both focused reproducers plus unchanged `t/refloop.t`.
5. Run the repository gate from an immutable source commit after the ownership
   path and its bridge tests are complete.

### Open questions and blockers

- #1642's performance baseline is still outstanding.
- The local `perl5` checkout is dirty blead 5.45.4 with an existing untracked
  `.local-perl`; it remains untouched. Perl 5.44.0 was built from its local git
  tag in an isolated temporary copy with default macOS Configure options.
- The focused `new_from_lol` regression now passes, but HTML-Tree 5.07's
  content and incremental parser cases still retain two and three elements at
  the immediate release assertion on both PerlOnJava backends; Perl 5.44 passes
  all cases. The new parser lifecycle reproducer records this open failure.
- The new owner-slot ledger, identity model, scalar/aggregate migration, and
  explicit deferred-release bridge remain unimplemented. These fixes are
  evidence for the Phase 1 boundary, not completion of the ownership rewrite.
