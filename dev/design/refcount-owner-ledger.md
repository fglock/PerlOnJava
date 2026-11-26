# Perl-Exact Reference Ownership and Closure-Pad Lifetime

Status: Active — 2026-11-26

**Delivery issue:** [#2043](https://github.com/fglock/PerlOnJava/issues/2043)
(origin [#1132](https://github.com/fglock/PerlOnJava/issues/1132), history in #1649).
**Target PR:** [#1938](https://github.com/fglock/PerlOnJava/pull/1938).
**Related:** [Weaken & DESTROY](../architecture/weaken-destroy.md),
[Refcount alignment plan](refcount_alignment_plan.md),
[Refcount alignment progress](refcount_alignment_progress.md).

## Objective

Align PerlOnJava's observable reference counts, weak-reference lifetime,
closure capture lifetime, cycles, and deterministic `DESTROY` with system
Perl, using explicit owner tokens instead of JVM reachability or
selective-refcount heuristics.

Issue #2043 adds one constraint: no lifecycle decision may depend on a memory
scan. Reference counts, weak-observer clearing, and destruction timing must
follow maintained owner-slot state. Do not add tree walks, snapshots, or
proxy-list sweeps to fix a failure; fix the owner transition or maintain an
index.

## Current state (2026-11-26)

| Item | Value |
|---|---|
| Local branch | `wip/issue2043-pr1938-20261125`, tracking `origin/wip/alias-cell-ownership-20261030-185849` (the PR head branch) |
| Gated commit | `6e0d709701`: the fixes below and two master merges (`4e2c8a471e`, `513d00c0fa`) on top of `7288fb4faf`. PR #1938 is at `f1b900b129`; `6e0d709701` adds only the literal-token fast path and is not yet pushed |
| Full gate | `nice -n 19 make` on `6e0d709701`: `BUILD SUCCESSFUL` in 8m08, exit 0 (`/tmp/issue2043-final-make4.log`); earlier `544f830ada` passed in 7m24. Mergeable with `origin/master` `859ab46dde` |
| Next step | Push `6e0d709701` and this record, rerun hosted CI (the `f1b900b129` Ubuntu run timed out a 10-second child process in `zz_perlonjava_process.t` during a 44-minute run), then maintainer review |

### Delivered in this batch

| Commit | Change | Regression coverage |
|---|---|---|
| `3d0d924a2f` | `require` errors list every searched `@INC` entry; lvalue subs reject readonly values from explicit `return`; CORE builtin identity and prototype survive glob aliases; JVM `AUTOLOAD` reports the called alias | Six new unit tests, all passing system Perl |
| `49a4895009` | Anonymous `[...]`/`{...}` literals queue a lifecycle-only-at-zero mortal at the JVM and interpreter literal sites, so a dereferenced, unowned literal is freed at statement end. Java module constructors that share `createReferenceWithTrackedElements` are unchanged | `anonymous_literal_statement_temporary.t`; fails 4 of 9 on master and PR head |
| `1993fde9da` | Copies through `\$#array` read the current last index, and `undef` after the array is freed | `array_last_index_reference_current_value.t`; fails 3 of 4 on master and PR head |
| `42bd3500da` | A counted release on a referent reached through an active argument alias completes its count transition at once; only the zero-count lifecycle decision waits for the frame. Fixes `op/inccode.t` test 60, which was the first `my (undef, ...) = @_` call in the file, not an `@INC` hook leak | `list_assign_undef_target_argument_refcount.t`; fails on PR head |
| `219240d2a6` | Interpreter direct calls of an undefined CV through a glob alias compare the call site with the name `apply` reports, so `AUTOLOAD` sees the alias | `autoload_named_glob_alias_name.t` |
| `1d41d3f85e` | `PerlThreadControlBlock` hands terminal release to exactly one of `detach()` and the finishing child. Pre-existing on master; found as an intermittent gate failure | JUnit `detachBetweenFinishAndCompletionReleasesChildRuntime`; scratch harness shows the leak before the fix (`/tmp/issue2043-detach-race-unfixed.log`) |
| `6e0d709701` | A lifecycle-only token on an aggregate that has gained a counted owner only completes, and literal entries carry no owner scalar. Restores the cost of a literal-heavy loop (2 million stored `[..]`/`{..}`) to the level before the literal mortal; it had been about 10% slower | Interleaved timings in the commit message; lifetime probes, focused matrix, and `unit/refcount/` rerun on the change |

The `MortalList.pushMark()` wrapper around `invokeIncHook`, the
`UNTRACKED_REFERENCE` orphaning hook in `PerlOwnerSlot`, and a brace-only
`RuntimeBase` edit were removed as ineffective.

### Final focused matrix

Every cell passes on JVM and interpreter after the second master merge
(`/tmp/issue2043-final3/`): `op/inccode.t` and `op/inccode-tie.t` 75 of 75;
`array_last_index_anon_array_owner.t` 3 of 3; `autoload_named_glob_alias_name.t`,
`core_builtin_reference_alias.t`, `core_prototype_alias_origin.t`,
`lvalue_sub_explicit_return_readonly.t`, `require_error_lists_all_searched_paths.t`,
`eval_regex_code_hints_refcount.t`, `goto_tailcall_cleanup.t`,
`readonly_coercion_argument_lifetime.t`, the three new tests above, and master's
`nested_require_dependency_package_context.t`, `require_package_context.t`, and
`nested_require_preserves_package.t`. All nine new tests pass system Perl
(`/tmp/issue2043-final/perl.txt`). `op/array.t` stays at 197 passes, and the
`unit/refcount/` directory has no failures on JVM.

## Completed phases

| Date | Delivered scope | Source and evidence |
|---|---|---|
| 2026-11-19 | Maintained owner-kind counts and narrow identity indexes; correct nested hash-slot `B::REFCNT` during `DESTROY` | Owner-ledger unit gate; DBIx `t/60core.t`, `t/101populate_rs.t` |
| 2026-11-21 | Interpreter foreach source owners retained through normal and `last` exits; overwritten temporary call-argument owners released | `4edc5faa51`, `eb8907f362`; system Perl, JVM, interpreter |
| 2026-11-21 | eval/die `DESTROY` LIFO order; weak-tracked scalar storage, raw untracked slots, and async foreach owner restoration | Fails on unfixed `aebe3b0742`; full `make` passed |
| 2026-11-21 | Ordinary aggregate cleanup retires direct outgoing slots; `RecursiveAggregateReleaseWalker` and `CleanupTargetProbe` removed from that path | `nested_untracked_aggregate_owner_release.t` on system Perl and both backends |
| 2026-11-22 | Scope-exit pre-scans removed; tied arrays use raw storage during slot retirement | Full `make` passed |
| 2026-11-22 | Runtime-only eval captures separated from semantic closure owners; JVM frame-exit owner release | All 12 DBIx `t/resultset/*.t` pass on JVM with LeakTracer clean |
| 2026-11-22 | Anonymous array call arguments stage an owner token; explicit `exit` unwinds lexical owners before `END` | `dbix_schema_init_shutdown_lifecycle.t`; DBIx `t/relationship/core.t`, `t/inflate/*.t` |
| 2026-11-22 | Package-array root marking skipped for already rooted aggregates | `11838413af`; `PackageArrayRootMarkingFastPathTest` |
| 2026-11-24 | `releaseDetachedUntrackedReferenceOwners` iterates the untracked-owner index instead of an owner-slot snapshot | `339bf5cd7a`; full `make` passed |
| 2026-11-24 | Eight-target CPAN acceptance on exact PR head | `ee827a2ad5`; see Acceptance evidence |
| 2026-11-26 | Reachability scans removed: eval-created CV templates own explicit slots, the global-destruction cleanup pre-scan is gone, and `ownerSlotCount` reads maintained per-kind counts | `9d22a6a6ce`; `OwnerSlotKindCountIndexTest` |
| 2026-11-26 | `undef %hash` selects destructor ordering from a maintained direct-value identity index | `RuntimeHashDestroyableValueIndexTest`, `undef_hash_owner_index.t` |
| 2026-11-26 | Master merged into the PR branch | `2edf51f698`; full `make` passed in 8m18; hosted checks green |
| 2026-11-26 | Regression fixes for `op/inccode.t`, `\$#{[]}`, interpreter `AUTOLOAD` aliases; statement-end release of unowned anonymous literals; detached-thread release race; master merged twice | `544f830ada`; full `make` passed in 7m24; see Current state |

## Design

### Ownership model

```text
RuntimeCode --semantic capture--> RuntimeScalar pad cell --strong slot--> referent
```

- The declaring scope and semantic closure captures own the pad cell.
- A strong pad cell owns exactly one referent token; a weak pad cell owns none.
- Closures sharing one pad cell do not change the referent count.
- Compiler metadata and generated JVM object fields are not Perl roots and
  own neither the pad nor its referent.

### Invariants

1. A live pad cell owns at most one token for its current referent, and
   exactly one when the cell is strong and the referent is tracked.
2. `semanticCaptureCount` affects pad lifetime, not referent cardinality.
3. Metadata captures never affect Perl lifetime.
4. Reassignment releases the old token before acquiring the new one.
5. `weaken()` releases the pad token; captures do not recreate it.
6. The final pad owner releases the token exactly once.
7. Lifecycle decisions follow owner slots. Java reachability neither creates
   nor retires a Perl owner.
8. Strong Perl cycles stay alive until a strong edge is explicitly broken.
9. No root traversal, snapshot, or sweep changes reference counts, weak
   observers, or destruction timing.

### Runtime state

- `slotOwnsReferent` (currently `refCountOwned`): the scalar slot owns one
  strong referent token.
- `scopeOwnerAlive`: the declaring lexical scope still owns the pad cell.
- `semanticCaptureCount`: live Perl closures sharing the pad cell.
- `metadataCaptureCount`: conservative or diagnostic captures with no
  ownership effect.
- `CaptureBinding`: a `RuntimeCode` record pairing a pad cell with capture
  provenance; each semantic binding is released exactly once.

`scopeExited` is derived lifecycle state, not an ownership heuristic.

### State transitions

| Event | Pad ownership | Referent ownership |
|---|---|---|
| Strong lexical assignment | Scope owns pad | Pad owns one token |
| Semantic closure capture (first or additional) | Add pad owner | Unchanged |
| Metadata capture | Unchanged | Unchanged |
| Declaring scope exits | Remove scope owner | Token kept while semantic captures remain |
| One of several closures is released | Remove one pad owner | Unchanged |
| Final closure is released after scope exit | Pad becomes dead | Release one token |
| Captured scalar is reassigned | Pad remains live | Release old token; acquire one new token |
| Captured scalar is weakened | Pad remains live but weak | Release token |
| Captured scalar is unweakened | Pad remains live and strong | Acquire one token |

A borrowed scalar promoted into a semantic captured pad acquires one pad-slot
token. Further closures sharing that pad acquire none.

### Capture provenance

Both backends produce equivalent capture descriptors.

- **JVM:** attach semantic `CaptureBinding` records at compile time. Do not
  infer ownership from reflected generated fields.
- **Interpreter:** distinguish lexicals the closure references from
  conservative snapshots of visible registers. Preserve `eval STRING` access
  to lexicals without making unrelated registers permanent owners. Release
  bindings through `RuntimeCode.releaseCaptures()`, as the JVM backend does.
- **Threads:** rebuild slot ownership and semantic captures in an ithread
  clone from capture descriptors, not from the source scalar's transient
  `refCountOwned`.

### Owner ledger

Every owner-producing path names an owner kind: scalar or pad slot, aggregate
element, package global, glob or stash slot, CODE storage, tie wrapper, pad
constant, temporary call hold, explicit destruction rescue, or another
runtime-specific strong Perl edge. Debug validation asserts
`refCount == sum(authoritative owner tokens)`. Temporary aliases, generated
fields, and conservative captures produce no token.

A referent stays alive while any strong Perl slot remains, including edges in
strong cycles. Releasing the final slot performs cleanup through the ordinary
container and lifecycle paths. Temporary leases and suspended-frame owners
are explicit slots.

### Scan-free mechanisms

| Decision | Mechanism |
|---|---|
| Owner-kind presence and counts | Per-kind counts maintained on add, release, and reclassification |
| Final array and hash cleanup | Retire direct element owners; child containers cascade through their own slots |
| `undef %hash` destructor ordering | Direct-value identity index maintained by hash mutation, scalar replacement, and blessing; aliased cells and wrapped shared maps take progressive cleanup |
| Global destruction | Visit each nested aggregate once through the existing cycle-detection set; no cleanup-target pre-scan |
| Eval-created anonymous CVs | Explicit owner slots, released at eval completion or CV teardown |
| Package-array roots | Global table writes establish the root; reads of a rooted aggregate skip re-marking |
| Detached untracked owners | Iterate the untracked-owner index |
| `\$#array` proxies (`RuntimeArraySizeLvalue`) | Generation identity on the array; orphaning replaces the generation, and copies read the current value |

## Acceptance evidence

**CPAN, `f1b900b129` (2026-11-26).** `make test-cpan-release-acceptance`
exited 2 (`/tmp/issue2043-acceptance-make.log`, per-target logs in
`/tmp/cpan_random_logs/20261126-145614-6579/`). PPR (1,255 tests), DateTime
(3,592), Excel::Writer::XLSX, Template Toolkit, and Image::ExifTool pass. Each
failure was rerun against master and an earlier build:

| Target | Failure | Classification |
|---|---|---|
| Catalyst | 24 of 3,820: `t/arg_constraints.t` 21 and `t/query_constraints.t` 3 return `Bad Request` for Type::Tiny argument constraints | Master regression: first bad `a4ee1c0c99` (#2176), parent `c9f4de5f3b` passes 18/18; identical on master `859ab46dde`; [#2194](https://github.com/fglock/PerlOnJava/issues/2194) |
| Mojolicious | `t/mojo/template.t` 24 of 226 lose template error line and context | Master regression: first bad `e75f4d39d9` (#2187), parent `357bccf2c9` passes 226/226; identical on master; [#2195](https://github.com/fglock/PerlOnJava/issues/2195) |
| Mojolicious | `t/mojo/asset.t` cannot fork | Waived by the maintainer, [#2169](https://github.com/fglock/PerlOnJava/issues/2169) |
| DBIx::Class | 1 of 43,020: `t/storage/txn.t` test 89 reports the opening lines (448, 447) of a multi-line nested `txn_do` call where Perl reports the closing line (451) | Pre-existing `caller` line gap. Master passes this file, but reports the same opening lines when the block runs on its own (`/tmp/issue2043-txn-min3.pl`), and a plain multi-line call with an anonymous sub argument gives opening lines on master and this branch where Perl gives closing lines (`/tmp/issue2043-callerline3.pl`). Tracked in [#2196](https://github.com/fglock/PerlOnJava/issues/2196); [#2170](https://github.com/fglock/PerlOnJava/issues/2170) is closed |

The earlier run on `ee827a2ad5` (2026-11-24) passed the same five targets and
had the same `txn.t` failure, then attributed to #2170.

**DBIx::Class leak coverage.** `diamond_prefetch_cursor_leak.t`,
`diamond_prefetch_cursor_leak_permutations.t`, and
`diamond_prefetch_shutdown_leak.t` under `src/test/resources/unit/refcount/`
require `DBIC_CLASS_TEST_ROOT` and skip without it. They pass on system Perl
and both backends. Upstream `t/prefetch/diamond.t`, `t/60core.t`,
`t/52leaks.t`, and all 12 `t/resultset/*.t` pass on JVM with LeakTracer clean.

**Performance, `chart_axis28.t`, seven checksum-matched pairs at nice 19–20.**
Results are per workload and are not a portfolio-wide claim; JFR figures are
sampled weights.

| Change | Throughput ratio (95% interval) | Allocation |
|---|---|---|
| Direct-slot cleanup against the recursive walker | 0.970 [0.869, 1.075] and 0.993 [0.886, 1.128]: no reliable change | Unchanged (7.80 GB) |
| Package-array root fast path (`11838413af` against `2362185545`) | 11.92 [10.53, 13.68] | Not measured |
| Untracked-owner index (`339bf5cd7a` against `3e97440c60`) | 1.074 [1.061, 1.088] | 8.126 GB to 7.617 GB |

## Known limitations

- `tied_handler_value_cell_alias.t` fails only on the interpreter and
  reproduces on master `4a5c33753f`.
- Five DBIx `t/resultset/*.t` files exceed 600 seconds on the interpreter;
  unclassified. All-backend DBIx acceptance is not claimed.
- System Perl reports three `update_delete.t` SQL-status mismatches in the
  DBIx resultset sweep, unrelated to leak witnesses.
- A full `jcpan -t DBIx::Class` run does not finish within one hour.
- #2037 and #2034 are separate follow-up work.
- `glob_alias_named_sub_identity.t` fails only on the interpreter (`Undefined
  subroutine &main::foo` at line 19) with identical output on master and PR head.

## Regression matrix

New or changed Perl tests must pass system Perl before PerlOnJava output is
used to drive a fix.

**Pad and closure ownership**

- Zero, one, and two closures sharing one lexical retain one referent owner.
- Independent lexical copies produce independent referent owners.
- Scope exit preserves a referent until the final closure is released.
- Releasing one of two closures does not release the shared pad token.
- The final release clears weak observers and invokes `DESTROY` exactly once.
- Nested closures and returned closures share the correct pad cell.
- Argument aliases and borrowed captures are promoted exactly once.
- Self-capturing CODE and strong closure cycles match Perl.

**Mutation**

- Weakening a captured slot makes it non-owning; unweakening restores one owner.
- Reassignment releases the old referent and retains the new one.
- Repeated reassignment cannot release a stale referent through deferred work.
- Ordinary weak references clear when no strong owner exists.

**Capture provenance**

- JVM and interpreter closures report equivalent semantic captures.
- Interpreter register snapshots do not retain unrelated objects.
- `eval STRING` retains only the Perl-visible lexical environment for the
  correct code-object lifetime.
- Generated JVM objects and reflected fields do not become Perl roots.
- ithread clones reconstruct independent ownership.

**Compatibility guards to keep green**

- `unit/weak_localized_cache_lifetime.t`
- Closure-cycle, captured-scalar, weak-callback, Sub::Quote, and Sub::Defer
  tests under `unit/refcount/`
- Focused Moo and Sub::Quote tests, DBIx::Class leak-tracer and weak-registry
  tests
- Exact-refcount tests using `B::REFCNT` or `Test2::Tools::Refcount`
- Issue #1132: the Future reproducer prints `completed=1`,
  `Future::Utils::repeat` prints `ready=1 result=final`, and
  Net::Async::HTTP `t/05redir.t`, `t/30timeout.t`, and `t/32remove.t` pass on
  both backends

## Final gates

- Every new or changed Perl test passes system Perl first.
- The focused test fails on the unfixed parent, with the log kept.
- Focused tests pass on JVM and interpreter.
- `make` passes with complete captured output on the exact source commit.
- `make check-links` passes.
- No unexpected high-CPU PerlOnJava JVM remains.

## History

The [ownership rewrite handoff](perl-ownership-rewrite.md#current-handoff-2026-11-15)
and PR [#1204](https://github.com/fglock/PerlOnJava/pull/1204) (branch
`fix/issue-1132-closure-lifetime`) record the earlier issue #1132 delivery.
Both are superseded by the current state above.

## Open question

Can the remaining compiler-created aggregate temporaries be made explicit in
the owner ledger instead of relying on opcode-specific transfers?
