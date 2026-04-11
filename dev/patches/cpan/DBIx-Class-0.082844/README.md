# DBIx::Class 0.082844 Patches for PerlOnJava

## Problem

DBIx::Class uses `TxnScopeGuard` which relies on `DESTROY` for automatic 
transaction rollback when a scope guard goes out of scope without being 
committed. On PerlOnJava (JVM), `DESTROY` does not fire deterministically, 
so:

1. Failed bulk inserts leave `transaction_depth` permanently elevated
2. Subsequent transactions silently nest instead of creating new top-level transactions
3. `BEGIN`/`COMMIT` disappear from SQL traces
4. Failed populates don't roll back (partial data left in DB)

## Fix

Wrap `txn_scope_guard`-protected code in `eval { ... } or do { rollback; die }` 
to ensure explicit rollback on error, instead of relying on guard DESTROY.

## Files Patched

### Storage/DBI.pm — `_insert_bulk` method (line ~2415)
- Wraps bulk insert + query_start/query_end + guard->commit in eval block
- On error: sets guard inactivated, calls txn_rollback, re-throws

### ResultSet.pm — `populate` method
- **List context path** (line ~2239): wraps map-insert loop + guard->commit in eval
- **Void context with rels path** (line ~2437): wraps _insert_bulk + children rels + guard->commit in eval

## Applying Patches

Patches must be applied to BOTH locations:
1. Installed modules: `~/.perlonjava/lib/DBIx/Class/Storage/DBI.pm` and `ResultSet.pm`
2. CPAN build dir: `~/.cpan/build/DBIx-Class-0.082844-*/lib/DBIx/Class/Storage/DBI.pm` and `ResultSet.pm`

```bash
# From the PerlOnJava project root:
cd ~/.perlonjava/lib
patch -p0 < path/to/dev/patches/cpan/DBIx-Class-0.082844/Storage-DBI.pm.patch

# Also patch the active CPAN build dir (find the latest one):
BUILDDIR=$(ls -td ~/.cpan/build/DBIx-Class-0.082844-*/lib | head -1)
cd "$BUILDDIR/.."
patch -p0 < path/to/dev/patches/cpan/DBIx-Class-0.082844/Storage-DBI.pm.patch
```

## Tests Fixed

- t/100populate.t: tests 37-42 (void ctx trace BEGIN/COMMIT), 53 (populate is atomic),
  59 (literal+bind normalization), 104-107 (multicol-PK has_many trace)
- Result: 108/108 real tests pass (was 98/108), only GC tests 109-112 remain

## Date

2026-04-11
