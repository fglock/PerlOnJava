# DBIx::Class 0.082844 Patches for PerlOnJava

## Status: Storage-DBI.pm and ResultSet.pm patches are OBSOLETE (2026-04-19)

After the refcount alignment work (Phases 1-3, see
`dev/design/refcount_alignment_plan.md`), the TxnScopeGuard DESTROY
behavior fires deterministically at scope exit. The original
`Storage-DBI.pm.patch` and `ResultSet.pm.patch` — which explicitly
wrapped populate paths in `eval { ... } or do { rollback }` — are no
longer required.

Verification: `t/100populate.t` is **108/108 unpatched** (previously
98/108 without the patches).

The obsolete patch files may still be present on disk from earlier
workflows but are gitignored and no longer referenced.

## Remaining opt-in patch: LeakTracer.pm

`t-lib-DBICTest-Util-LeakTracer.pm.patch` remains as an opt-in to
make `t/52leaks.t` pass all 9 non-TODO tests. Without it, Phase B2a
auto-sweep still closes 4 of the 9 leaks, but 4 Schema/ResultSource
fails and 1 `basic rerefrozen` fail remain. See
`LeakTracer-README.md` for details.

## Historical context (Storage-DBI.pm / ResultSet.pm, kept for reference)

Before refcount alignment, DBIC's `TxnScopeGuard` relied on `DESTROY`
firing at scope exit for automatic transaction rollback. On the JVM,
before Phases 1-3 of the refcount plan, DESTROY did not fire
deterministically, causing:

1. Failed bulk inserts left `transaction_depth` permanently elevated
2. Subsequent transactions silently nested instead of starting fresh
3. `BEGIN` / `COMMIT` disappeared from SQL traces
4. Failed populates didn't roll back (partial data in DB)

The `Storage-DBI.pm.patch` and `ResultSet.pm.patch` previously wrapped
populate/bulk-insert paths in explicit `eval { ... } or do { rollback; die }`
to work around the missing DESTROY. As of the refcount alignment work
these patches are no longer required.

## Date

Updated 2026-04-19.
