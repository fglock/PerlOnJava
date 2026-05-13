# Reachability Walk Cache for DBIC Scope Cleanup

**Status:** Phase 1 design complete; Phase 2 implementation pending
**Date:** 2026-05-12
**Branch / PR:** `fix/math-bigint-method-chain-regression` / PR #709

## Problem

DBIx::Class `t/52leaks.t` spends most of its time in repeated
`ReachabilityWalker.isReachableFromExternalRoot(hash)` calls from
`MortalList.scopeExitCleanupHash()`. Each hash scope cleanup walks the same
package/global/live-lexical graph again. In DBIC this becomes quadratic because
many scope-exiting hashes run the same external-root query while the visible
root graph is large.

The fix is to preserve the existing external-root semantics but cache the
snapshot for the duration of a cleanup / flush boundary.

## Baseline

Measured before Java code changes, from
`cpan_build_dir/DBIx-Class-0.082844`:

```sh
timeout 600 /usr/bin/time -p ../../jperl -I../../src/main/perl/lib -Ilib -It/lib t/52leaks.t > /tmp/dbic_52leaks_before_N.log 2>&1
```

| Run | Result | real |
|---|---:|---:|
| 1 | PASS | 69.90s |
| 2 | PASS | 72.36s |
| 3 | PASS | 73.98s |

Median baseline: **72.36s**.

Acceptance threshold for this fix: `t/52leaks.t` must pass in **24.12s or
less** for at least a 3x wall-clock speedup.

## Design

Add `ReachabilityWalker.ExternalRootSnapshot`, a reusable snapshot for the
reachability query currently implemented by
`isReachableFromExternalRoot(RuntimeBase target)`.

The snapshot preserves these current semantics:

- Package roots are roots: `%`, `@`, `$`, and `&` entries from
  `GlobalVariable`.
- `DestroyDispatch` rescued objects are roots.
- Live lexical roots from `MyVarCleanupStack.snapshotLiveVars()` are roots.
- Weak scalars do not create strong reachability edges.
- `RuntimeStash` is not walked.
- `RuntimeHash` values backed by `HashSpecialVariable` are not walked.
- `RuntimeCode` closure captures are not walked.

The one target-specific rule is kept explicitly: a named lexical container
does not make itself externally reachable. The snapshot therefore tracks direct
live-lexical origins. A target that is a direct lexical origin only returns
reachable if it is also reachable from a non-lexical root, or from a different
live lexical root.

`ReachabilityWalker.isReachableFromExternalRoot(target)` remains as a
one-shot compatibility wrapper that builds a snapshot and queries it.

## MortalList Cache

`MortalList.scopeExitCleanupHash()` will query a lazily-built
`ExternalRootSnapshot` instead of launching a fresh root walk for every hash.

The cache is intentionally short-lived and conservative. It is invalidated at:

- `MortalList.flush()`
- `MortalList.flushAboveMark()`
- `MortalList.popAndFlush()`
- `MortalList.drainPendingSince()`
- immediately before and after user `DESTROY` execution in
  `DestroyDispatch.doCallDestroy()`

These boundaries cover refcount drains, scope-boundary processing, and user
code that can mutate roots during destruction.

## Test Plan

Build / unit gate:

```sh
timeout 1200 make > /tmp/make_reachability_cache.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/make_reachability_cache.log
```

DBIx::Class targeted gates:

```sh
cd cpan_build_dir/DBIx-Class-0.082844
timeout 600 /usr/bin/time -p ../../jperl -I../../src/main/perl/lib -Ilib -It/lib t/52leaks.t > /tmp/dbic_52leaks_after.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/dbic_52leaks_after.log
timeout 300 ../../jperl -I../../src/main/perl/lib -Ilib -It/lib t/storage/error.t > /tmp/dbic_storage_error.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/dbic_storage_error.log
timeout 300 ../../jperl -I../../src/main/perl/lib -Ilib -It/lib t/88result_set_column.t > /tmp/dbic_88.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/dbic_88.log
timeout 300 ../../jperl -I../../src/main/perl/lib -Ilib -It/lib t/60core.t > /tmp/dbic_60core.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/dbic_60core.log
timeout 300 ../../jperl -I../../src/main/perl/lib -Ilib -It/lib t/64db.t > /tmp/dbic_64db.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/dbic_64db.log
timeout 300 ../../jperl -I../../src/main/perl/lib -Ilib -It/lib t/87ordered.t > /tmp/dbic_87ordered.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/dbic_87ordered.log
timeout 300 ../../jperl -I../../src/main/perl/lib -Ilib -It/lib t/96_is_deteministic_value.t > /tmp/dbic_96.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/dbic_96.log
timeout 300 ../../jperl -I../../src/main/perl/lib -Ilib -It/lib t/storage/txn.t > /tmp/dbic_storage_txn.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/dbic_storage_txn.log
timeout 300 ../../jperl -I../../src/main/perl/lib -Ilib -It/lib t/storage/txn_scope_guard.t > /tmp/dbic_txn_scope_guard.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/dbic_txn_scope_guard.log
timeout 300 ../../jperl -I../../src/main/perl/lib -Ilib -It/lib t/cdbi/sweet/08pager.t > /tmp/dbic_08pager.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/dbic_08pager.log
```

Math-BigInt targeted gates:

```sh
timeout 60 ./jperl -Isrc/main/perl/lib -e 'use Math::BigInt; my $x = Math::BigInt->new("1"); print $x->bexp(), "\n";' > /tmp/math_bigint_bexp_predicate.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/math_bigint_bexp_predicate.log
cd src/test/resources/module/Math-BigInt
timeout 180 ../../../../../jperl -I../../../../../src/main/perl/lib -Ilib t/bare_mbi.t > /tmp/math_bare_mbi.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/math_bare_mbi.log
timeout 180 ../../../../../jperl -I../../../../../src/main/perl/lib -Ilib t/bigintpm.t > /tmp/math_bigintpm.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/math_bigintpm.log
timeout 180 ../../../../../jperl -I../../../../../src/main/perl/lib -Ilib t/bigfltpm.t > /tmp/math_bigfltpm.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/math_bigfltpm.log
timeout 180 ../../../../../jperl -I../../../../../src/main/perl/lib -Ilib t/bigfltrt.t > /tmp/math_bigfltrt.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/math_bigfltrt.log
timeout 180 ../../../../../jperl -I../../../../../src/main/perl/lib -Ilib t/mbimbf.t > /tmp/math_mbimbf.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/math_mbimbf.log
timeout 180 ../../../../../jperl -I../../../../../src/main/perl/lib -Ilib t/bigrat.t > /tmp/math_bigrat.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/math_bigrat.log
```

Final gates:

```sh
timeout 3600 ./jcpan -t DBIx::Class > /tmp/jcpan_dbic_reachability_cache.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/jcpan_dbic_reachability_cache.log
timeout 1800 make test-bundled-modules > /tmp/test_bundled_reachability_cache.log 2>&1; printf 'EXIT: %s\n' $? >> /tmp/test_bundled_reachability_cache.log
ps aux | awk '$3 > 20 {print $2, $3, $11, $12}'
```

## Progress Tracking

### Current Status: Phase 1 Complete

### Completed Phases

- [x] Phase 1: Design and baseline (2026-05-12)
  - Recorded three pre-fix `t/52leaks.t` timings.
  - Median baseline: 72.36s.
  - Target after fix: <= 24.12s.

### Next Steps

1. Implement `ReachabilityWalker.ExternalRootSnapshot`.
2. Use a cached snapshot from `MortalList.scopeExitCleanupHash()`.
3. Add conservative invalidation at flush/drain/DESTROY boundaries.
4. Run the targeted and final verification gates.

### Open Questions

- None. Cache lifetime should stay conservative unless later profiling shows a
  broader lifetime is necessary.
