# Perl-compatible ownership rewrite

**Status:** Phase 0 oracle established; Phase 1 boundary still under audit

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
CV-body operation. A direct self-pad release hypothesis did not fix the
reproducer and regressed an interpreter control test, so that change was
discarded. Traces show one captured pad replacing its referent as nodes are
constructed and the root remaining in `semanticCaptureOwners`; they have not
identified the exact owner release PerlOnJava misses. Closure pad ownership,
aggregate child slots, and weak parent lifecycle are all in the failing path,
but the complete Phase 1 boundary is not selected until the missing release is
understood. Calls and mortal release on that path must either use the new slot
API or an explicit legacy bridge.

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

### Current status: Phase 0 evidence established; Phase 1 boundary under audit

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
- [x] Determined that scalar-only and scalar-plus-aggregate boundaries cannot
  cover the reduced #1618 failure. Closure pad ownership and weak-parent
  lifecycle are involved, but the exact release edge remains unidentified.
- [x] Kept the minimal strong-tree analogue at
  [`dev/repros/issue-1649-strong-tree-control.t`](../repros/issue-1649-strong-tree-control.t)
  outside the unit gate after confirming it also fails under the current
  interpreter backend. These reproducers are not acceptance tests yet.

### Next steps

1. Map each closure pad created by `new_from_lol` to its owning CV, slot value,
   and all release paths; retain the parent failure and Perl 5.44 oracle logs.
2. Select a complete first path only after this mapping identifies the missing
   edge and backend cleanup behavior.
3. Reproduce #1642's repeated deferred-cleanup root queries with deterministic
   counters on this baseline; separate that cost regression from Phase 1
   correctness unless the selected ownership path demonstrates the connection.
4. Implement explicit owner identity, count/lifecycle separation, and the
   legacy bridge across the selected path.
5. Add permanent oracle-validated primitive, lifecycle, bridge, and external
   regression coverage; validate both backends and run the required immutable
   source gates before requesting review.

### Open questions and blockers

- #1642's performance baseline is still outstanding.
- The local `perl5` checkout is dirty blead 5.45.4 with an existing untracked
  `.local-perl`; it remains untouched. Perl 5.44.0 was built from its local git
  tag in an isolated temporary copy with default macOS Configure options.
- No runtime ownership change has passed the reduced regression. The attempted
  CV-body and self-pad changes were discarded after the test remained red; the
  owner ledger, identity model, cycle retention, and explicit legacy bridge
  remain unimplemented. Do not treat the regression as fixed until the exact
  Perl 5.44 lifecycle passes on both backends.
