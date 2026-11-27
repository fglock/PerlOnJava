# Reference Ownership and Closure-Pad Lifetime

Status: Implemented — 2026-11-27 (merged in `48c56dbfb8`, PR #1938, issue #2043)

PerlOnJava decides reference counts, weak-reference clearing, closure-pad
lifetime, and deterministic `DESTROY` from explicit owner slots: every strong
Perl edge is a named owner token, acquired and released at the transition that
creates or retires the edge. Java reachability never creates or retires a Perl
owner, and no lifecycle decision depends on a memory scan, snapshot, or sweep.
To change behaviour, fix the owning transition or maintain an index; do not
add a tree walk.

[Weaken & DESTROY](weaken-destroy.md) describes the user-visible `weaken` and
`DESTROY` behaviour built on this model. The delivery plan and its evidence
are archived in
[refcount-owner-ledger.md](../archive/design/refcount-owner-ledger.md).

## Ownership model

```text
RuntimeCode --semantic capture--> RuntimeScalar pad cell --strong slot--> referent
```

- The declaring scope and semantic closure captures own the pad cell.
- A strong pad cell owns exactly one referent token; a weak pad cell owns none.
- Closures sharing one pad cell do not change the referent count.
- Compiler metadata and generated JVM object fields are not Perl roots and
  own neither the pad nor its referent.

## Invariants

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

## Runtime state

- `slotOwnsReferent` (currently `refCountOwned`): the scalar slot owns one
  strong referent token.
- `scopeOwnerAlive`: the declaring lexical scope still owns the pad cell.
- `semanticCaptureCount`: live Perl closures sharing the pad cell.
- `metadataCaptureCount`: conservative or diagnostic captures with no
  ownership effect.
- `CaptureBinding`: a `RuntimeCode` record pairing a pad cell with capture
  provenance; each semantic binding is released exactly once.

`scopeExited` is derived lifecycle state, not an ownership heuristic.

## State transitions

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

## Capture provenance

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

## Owner ledger

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

## Scan-free mechanisms

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

## Behaviour decisions

These follow from the model and from the Perl version whose core tests the
project imports; each has focused regression coverage.

- **Anonymous literals are statement temporaries.** `[...]` and `{...}` queue a
  lifecycle-only-at-zero token when the literal creates its reference
  (`MortalList.anonymousLiteral`). A literal no Perl slot owns by the time the
  statement boundary drains is freed there, so dereferenced temporaries run
  `DESTROY` promptly. A literal stored or returned in the meantime has a
  positive count and the token only completes.
- **`\$#` keeps an anonymous array alive.** PerlOnJava reports a
  reference-counted stack (`Internals::stack_refcounted() & 1`). As on such a
  perl, a reference to an anonymous array's `$#` proxy retains the array
  (`RuntimeArraySizeLvalue.createReference`); an rvalue `$#{[...]}` does not,
  and a lexical array is still orphaned at scope exit. Copies through the
  proxy read the array's current last index.
- **Argument aliases defer only the lifecycle decision.** A counted release of
  a referent still reached through an active argument alias completes its
  count transition immediately; only the zero-count decision waits for the
  frame, so `Internals::SvREFCNT` matches the remaining slots while the
  callee runs.
- **`require` does not modify `@INC`.** The bundled library is an implicit
  fallback searched after the visible entries and is not listed in the
  "@INC entries checked" diagnostic.
- **Statement line numbers follow Perl 5.45.4** (GH #24396), as documented in
  [the feature matrix](../../docs/reference/feature-matrix.md#namespaces-and-global-variables).

## Known limitations

- `tied_handler_value_cell_alias.t` fails only on the interpreter and
  reproduces on master `4a5c33753f`.
- Five DBIx `t/resultset/*.t` files exceed 600 seconds on the interpreter;
  unclassified. All-backend DBIx acceptance is not claimed.
- System Perl reports three `update_delete.t` SQL-status mismatches in the
  DBIx resultset sweep, unrelated to leak witnesses.
- A full `jcpan -t DBIx::Class` run does not finish within one hour.
- #2037 and #2034 are separate follow-up work.

## Regression coverage

New or changed Perl tests must pass system Perl, and blead where behaviour
changed after 5.42, before their PerlOnJava results drive a change.

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
