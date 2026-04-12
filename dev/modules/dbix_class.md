# DBIx::Class Fix Plan

## Overview

**Module**: DBIx::Class 0.082844  
**Test command**: `./jcpan -t DBIx::Class`  
**Branch**: `feature/dbix-class-destroy-weaken`  
**PR**: https://github.com/fglock/PerlOnJava/pull/485  
**Status**: Phases 1-14 DONE + deferred-capture cleanup. Two work directions remain: GC liveness (Direction 1) and real test failures (Direction 2).

## How to Run the Suite

```bash
cd /Users/fglock/projects/PerlOnJava3 && make

cd /Users/fglock/.perlonjava/cpan/build/DBIx-Class-0.082844-13
JPERL=/Users/fglock/projects/PerlOnJava3/jperl
mkdir -p /tmp/dbic_suite
for t in t/*.t t/storage/*.t t/inflate/*.t t/multi_create/*.t t/prefetch/*.t \
         t/relationship/*.t t/resultset/*.t t/row/*.t t/search/*.t \
         t/sqlmaker/*.t t/sqlmaker/limit_dialects/*.t t/delete/*.t t/cdbi/*.t; do
    [ -f "$t" ] || continue
    timeout 120 "$JPERL" -Iblib/lib -Iblib/arch "$t" > /tmp/dbic_suite/$(echo "$t" | tr '/' '_' | sed 's/\.t$//').txt 2>&1
done

for f in /tmp/dbic_suite/*.txt; do
    ok=$(grep -c "^ok " "$f" 2>/dev/null); ok=${ok:-0}
    notok=$(grep -c "^not ok " "$f" 2>/dev/null); notok=${notok:-0}
    [ "$notok" -gt 0 ] && echo "FAIL($notok): $(basename $f .txt)"
done | sort
```

---

## Current Test Results (2026-04-12)

| Category | Count | Notes |
|----------|-------|-------|
| Full pass | 28+ | t/100populate.t now passes (deferred-capture fix) |
| GC-only failures | ~146 | Only `Expected garbage collection` failures — real tests all pass |
| Real failures | ~25 | Have non-GC `not ok` lines |
| Skip/no output | 43 | No TAP output (skipped, errored, or missing deps) |
| **Total files** | **241** | |

**Key recent fix**: Deferred-capture cleanup (`MortalList.flushDeferredCaptures`) runs after
main script returns but before END blocks. Fixes t/101populate_rs.t test 166 and t/100populate.t.
Commit `d32de1dba`.

---

## Direction 1: GC Test Failures (~146 files, ~658 assertions)

### Root Cause

**`B::svref_2object($ref)->REFCNT` leaks refcount.** The `B::SV->new` constructor was
`bless { ref => $ref }, 'B::SV'` — the hash literal goes through
`createReferenceWithTrackedElements()` which bumps the referent's refCount. The B::SV hash
is a JVM-local temporary that never gets `scopeExitCleanup`, so the bump is never reversed.
DBIC's leak tracer calls this on every registered object → ALL get inflated refcounts.

**Partial fix applied**: B.pm now uses two-step construction (`my $self = bless {}; $self->{ref} = $ref`)
which enters cooperative refcounting properly. Commit `d32de1dba`. **Needs full suite re-run to verify.**

### Fix Plan

| Step | What | Difficulty | Impact |
|------|------|------------|--------|
| **GC-1** | ~~Fix `B::SV::new`~~ DONE — two-step construction in B.pm | Easy | Should fix most 658 GC assertions |
| **GC-2** | Re-run full suite, measure improvement | - | Verify the fix was sufficient |
| **GC-3** | If gaps remain, investigate file-scope lexical destruction | Medium | Fix remaining GC failures |

### Issue 15.1: `in_global_destruction` Bareword Error During Cleanup

**Symptom**: `(in cleanup) Bareword "in_global_destruction" not allowed while "strict subs"` —
278 occurrences per test.

**Root cause**: `namespace::clean` removes `in_global_destruction` from the
`DBIx::Class::ResultSource` stash. The compiled bytecode should resolve it via `pinnedCodeRefs`
but fails during DESTROY.

**Fix approach**: Add logging in `getGlobalCodeRef()` when a bareword error fires for a key
that exists in pinnedCodeRefs. If the key IS there → bytecode instruction selection bug.
If NOT → `namespace::clean` interaction with pinnedCodeRefs bug.

### Issue 15.2: Class::XSAccessor "Attempt to reload" Warning

**Symptom**: `Class::XSAccessor exists but failed to load` — 2 stderr lines per test.

**Fix**: In `ModuleOperators.java:861`, change `set undef` to `delete` for `$INC{...}` on
failed `require`. Matches Perl 5 behavior. **Easy fix, broad impact** — helps all CPAN XS
module fallbacks.

---

## Direction 2: Real (Non-GC) Test Failures

### Category A: DBI Statement Handle Lifecycle (t/60core.t)

**Symptom**: `Unreachable cached statement still active: SELECT me.artistid, me.name...`

After `$or_rs->reset` and `$rel_rs->reset`, `CachedKids` statement handles remain `Active`
because Cursor DESTROY doesn't fire deterministically on JVM. The test at t/60core.t:313-316
iterates `$schema->storage->dbh->{CachedKids}` and fails for each Active handle.

**Fix options** (in order of preference):
1. Fix cooperative refcount for Cursor lifecycle — Cursor refcount should reach 0 when
   ResultSet goes out of scope, triggering `__finish_sth`
2. Add explicit `$sth->finish()` in DBI `fetchrow_arrayref` when result set is exhausted
3. Auto-finish stale Active statements in `prepare_cached()` (workaround already partially
   implemented in DBI.pm line 632-636)

**Priority**: HIGH — ~12 failures from single root cause.

### Category B: Transaction Wrapping / TxnScopeGuard DESTROY

**t/100populate.t**: Now passes with deferred-capture fix. ✅

**t/storage/txn_scope_guard.t (4 failures)**: Guard goes out of scope without `commit()` —
expects DESTROY to fire rollback + warning. Same GC non-determinism root cause.

**Pre-built patches** exist at `dev/patches/cpan/DBIx-Class-0.082844/` (ResultSet.pm.patch,
Storage-DBI.pm.patch) wrapping `txn_scope_guard` code in explicit `eval { ... } or do { rollback; die }`.
**Status: NOT YET APPLIED.** These may fix remaining txn_scope_guard failures.

**Priority**: MEDIUM — patches ready to try.

### Category C: UTF-8 Byte/Character Distinction (t/85utf8.t — 8 failures)

**Symptom**: `got: 'weird Ѧ stuff'` / `expected: 'weird Ѧ stuff'` — looks identical but
differs at byte level. Test returns 255 (fatal).

**Root cause**: JVM strings are always Unicode. The test creates two versions of `"weird \x{466} stuff"`:
- `$utf8_title` — Perl-internal Unicode string
- `$bytestream_title` — `utf8::encode()`d to raw bytes

PerlOnJava can't maintain this distinction because:
- `utf8::encode` may not produce a distinct byte representation on JVM
- JDBC always returns Unicode strings (can't get raw bytes from DB)
- `utf8::is_utf8()` on DB-fetched data always returns true

**Priority**: LOW — systemic JVM limitation. UTF8Columns is deprecated upstream.
Some tests are `local $TODO` even in Perl 5 (broken since rev 1191, March 2006).

### Category D: Premature Schema Detachment (t/sqlmaker/order_by_bindtransport.t — 7 failures)

Schema's cooperative refcount reaches 0 at statement end when used as a temporary:
```perl
my $rs = DBICTest->init_schema->resultset('FourKeys');  # schema is a temporary
```
→ ResultSource::DESTROY weakens `{schema}` → ResultSet finds detached source.

**Fix options**:
1. Fix cooperative refcounting so schema temporary doesn't prematurely reach 0
2. Have ResultSet keep a strong ref to schema (PerlOnJava-specific change)

**Priority**: MEDIUM — 7 failures, broader impact on weak-ref chains.

### Category E: DBI Environment Variables (t/storage/dbi_env.t — 6 failures)

Marked **DONE** in Phase 12. The 6 failures may be stale. **Re-run to verify.**

### Category F: FilterColumn Callback Counting (t/row/filter_column.t — 6 failures)

Exact invocation counts of `filter_from_storage`/`filter_to_storage` callbacks don't match.
Needs investigation with `DBIC_TRACE=1` to compare callback patterns between Perl 5 and
PerlOnJava.

**Priority**: MEDIUM — 6 failures, requires investigation.

### Category G: Upstream TODO Failures (3 files, 15 failures — NO ACTION NEEDED)

- t/prefetch/count.t (7): `local $TODO = "Chaining with prefetch is fundamentally broken"`
- t/multi_create/existing_in_chain.t (4): `todo_skip $TODO_msg`
- t/prefetch/manual.t (4): `local $TODO = "...deprecated..."`

These fail in Perl 5 too.

### Category H: t/52leaks.t — Leak Detection Test

**Symptom**: `Failed test 'how did we get so far?!'` at line 151 + GC failures.

**`begin_work` inside `txn_do`**: The test calls `$storage->_dbh->begin_work` inside a
`txn_do` block (which already began a transaction). In Perl 5 DBI, this throws
`Already in a transaction`. In PerlOnJava, `begin_work` (DBI.java:784) silently calls
`conn.setAutoCommit(false)` even if AutoCommit is already off → no error thrown →
the `fail()` guard is reached.

**Fix**: In `DBI.java:begin_work()`, check if `AutoCommit` is already false before
proceeding. If so, throw `Already in a transaction` (matching Perl 5 DBI behavior).

**GC failures** (`Expected garbage collection of DBI::db`): Same root cause as Direction 1 —
`B::svref_2object` refcount leak. Will be resolved by GC-1 fix.

**Other note**: t/52leaks.t was previously listed as skipped (needs `fork`), but it does
produce output and some tests run. The `fork` requirement may only affect the re-run-under-
persistent-environment part (lines 528+).

**Priority**: HIGH for `begin_work` fix (easy, 1 failure). GC failures blocked by Direction 1.

### Category I: CDBI Compatibility — `select_row` Method (t/cdbi/ tests)

**Symptom**: `Can't locate object method "select_row" via package "DBI::st"` at
`t/cdbi/testlib/DBIC/Test/SQLite.pm` line 83.

**Root cause**: `set_sql` (via `DBIx::Class::CDBICompat::ImaDBI`) creates statement handles
that should be blessed into a class using `DBIx::ContextualFetch`, which provides `select_row`.
The method chain is:
1. `$class->sql__table_pragma` → returns an Ima::DBI-style sth object
2. `->select_row` → method from `DBIx::ContextualFetch` (at `~/.perlonjava/lib/DBIx/ContextualFetch.pm:85`)

The statement handle is being returned as a plain `DBI::st` instead of a
`DBIx::ContextualFetch`-enhanced handle.

**Investigation needed**:
1. Check how `set_sql`/`ImaDBI` creates statement handles — does it bless into the right class?
2. Check if `DBIx::ContextualFetch` is loaded and its `@ISA` is set up correctly
3. Check if `DBI::db::prepare` returns the correct handle class

**Fix approach**: Ensure `ImaDBI::set_sql` creates handles blessed into a class that has
`DBIx::ContextualFetch` in its `@ISA`. May need to add `selectcol_arrayref` to DBI.pm
(completely missing — needed by ContextualFetch).

**DBI.pm missing method — `selectcol_arrayref`**:
```perl
# Add to DBI.pm — needed by DBIx::ContextualFetch and CDBI compat
sub selectcol_arrayref {
    my ($dbh, $statement, $attr, @bind_values) = @_;
    my $sth = ref($statement) ? $statement : $dbh->prepare($statement, $attr)
        or return undef;
    $sth->execute(@bind_values) or return undef;
    my @col;
    my $columns = $attr && $attr->{Columns} ? $attr->{Columns} : [1];
    if (@$columns == 1) {
        my $idx = $columns->[0] - 1;
        while (my $row = $sth->fetchrow_arrayref()) {
            push @col, $row->[$idx];
        }
        return \@col;
    }
    while (my $row = $sth->fetchrow_arrayref()) {
        push @col, map { $row->[$_ - 1] } @$columns;
    }
    return \@col;
}
```

**Priority**: LOW — CDBI compat is legacy. But `selectcol_arrayref` is a real DBI method that
other modules may need.

### Category J: Version Mismatch Warning (informational — NO ACTION NEEDED)

**Symptom** (from t/00describe_environment.t):
```
Mismatch of versions '1.1' and '1.45', obtained respectively via
`Params::ValidationCompiler::Exception::Named::Required->VERSION` and parsing
the version out of .../Exception/Class.pm with ExtUtils::MakeMaker@7.78.
```

**Not a test failure.** This is a diagnostic warning. `Exception::Class::_make_subclass()`
assigns `$VERSION = '1.1'` to all dynamically-generated exception classes. When
`ExtUtils::MakeMaker` parses the source file, it finds the parent's `$VERSION = '1.45'`.
This mismatch is expected and occurs in Perl 5 too.

### Recommended Fix Order

| Step | Category | Target | Failures Fixed | Effort |
|------|----------|--------|----------------|--------|
| 1 | GC-2 | Full suite | ~658 GC | Re-run — B.pm fix already applied |
| 2 | H (partial) | `begin_work` | 1 | Easy — add AutoCommit check in DBI.java |
| 3 | 15.2 | `$INC` cleanup | stderr noise | Easy — delete vs undef in ModuleOperators.java |
| 4 | B | txn_scope_guard | 4 | Easy — apply existing patches |
| 5 | E | dbi_env.t | 6 | Verify — likely already fixed |
| 6 | A | t/60core.t | ~12 | Medium — Cursor DESTROY / auto-finish |
| 7 | D | order_by_bindtransport | 7 | Medium — schema temporary refcount |
| 8 | F | filter_column.t | 6 | Medium — investigate callbacks |
| 9 | I | CDBI compat | varies | Medium — ContextualFetch + selectcol_arrayref |
| 10 | 15.1 | bareword cleanup | stderr noise | Medium — pinnedCodeRefs investigation |
| 11 | C | t/85utf8.t | 8 | Hard — systemic JVM UTF-8 limitation |
| — | G | upstream TODOs | 15 | None — fail in Perl 5 too |
| — | J | version warning | 0 | None — informational only |

**Total fixable** (steps 1-10): ~700+ (most are GC)  
**Hard/systemic** (step 11): 8 — require deep JVM-level changes  
**No action** (G, J): 15 failures + 1 warning — not PerlOnJava bugs

---

## Tests That Are Legitimately Skipped (43 files — NO ACTION NEEDED)

| Category | Count | Reason |
|----------|-------|--------|
| Missing external DB (MySQL, PG, Oracle, etc.) | 20 | Need `$ENV{DBICTEST_*_DSN}` |
| Missing Perl modules | 14 | Need DateTime::Format::*, SQL::Translator, Moose, etc. |
| No ithread support | 3 | PerlOnJava platform limitation |
| Deliberately skipped by test design | 4 | `is_plain` check, segfault-prone, disabled upstream |
| Need external DB (t/746sybase.t) | 1 | Need Sybase DSN |
| CDBI optional (t/cdbi/22-deflate_order.t) | 1 | Needs Time::Piece::MySQL |

---

## What Didn't Work (avoid re-trying)

| Approach | Why it didn't work |
|----------|--------------------|
| `System.gc()` before END assertions | Advisory, no guarantee of collection |
| Store intermediate `my $sv = B::svref_2object($ref); $sv->REFCNT` | `$sv` still holds the leaking B::SV hash — doesn't fix the refcount bump |
| `releaseCaptures()` on ALL unblessed containers | False positives — cooperative refCount falsely reaches 0 via stash refs; caused Moo infinite recursion |
| Decrement refCount for captured blessed refs at inner scope exit | Breaks `destroy_collections.t` test 20 ("object alive while closure exists") — closures in outer scopes legitimately keep objects alive |
| Fall-through in `scopeExitCleanup` for blessed refs with `DestroyDispatch.classHasDestroy` when `captureCount > 0` | Same as above — correct for file scope but wrong for inner scope. Fixed by deferred-capture list approach instead |
| Using `interpreterFrameIndex` as proxy for lazy CallerStack entries | Frame count doesn't match lazy entry count; fixed with `CallerStack.countLazyFromTop()` |
| `Math.max(callerStackIndex, interpreterFrameIndex)` for caller() skipping | Wrong when interpreter frame count ≠ lazy entry count |
| `git stash` for testing alternatives | **Lost completed work** — never use git stash during active debugging |

---

## Dependencies (ALL PASS)

**Runtime**: DBI, Sub::Name, Try::Tiny, Text::Balanced, Moo (v2.005005), Sub::Quote,
MRO::Compat, namespace::clean, Scope::Guard, Class::Inspector, Class::Accessor::Grouped,
Class::C3::Componentised, Config::Any, Context::Preserve, Data::Dumper::Concise,
Devel::GlobalDestruction, Hash::Merge, Module::Find, Path::Class, SQL::Abstract::Classic

**Test**: Test::More, Test::Deep, Test::Warn, File::Temp, Package::Stash, Test::Exception,
DBD::SQLite (JDBC shim)

---

## Completed Phases (1-14 + deferred-capture)

| Phase | Date | What |
|-------|------|------|
| 1-4 | 2025-03-31 | Unblock Makefile.PL, install 11 deps, DBI/DBD::SQLite JDBC shim, parser fixes |
| 5 | 2026-03-31 — 04-02 | 58 runtime fixes: DBI core, transactions, parser, Storable, B module, overload. 15→96.7% pass rate |
| 6 | 2026-04-02 | DBI statement handle lifecycle (`Active` flag): t/60core.t 45→12 failures |
| 9-13 | 2026-04-10 — 04-11 | DESTROY/weaken integration, DBI env vars, HandleError, DESTROY-on-die |
| 14 | 2026-04-12 | `stashRefCount` — prevent premature weak ref clearing for stash-installed closures. `DBICTest->init_schema()` succeeds, 1275 tests pass. Commits `db846e687`, `ef424f783` |
| — | 2026-04-12 | `wait` operator: full implementation (parser, JVM, bytecode, interpreter). Commit `dddab71e1` |
| — | 2026-04-12 | caller() fix: `countLazyFromTop()` for interpreter. Commit `f08d437f5` |
| — | 2026-04-12 | Deferred-capture cleanup: `MortalList.flushDeferredCaptures()` + B.pm two-step construction. Fixes t/101populate_rs.t test 166 + t/100populate.t. Commit `d32de1dba` |

<details>
<summary>Phase 5 milestones (click to expand)</summary>

- 5.1-5.12: DBI core (bind_columns, column_info, etc.)
- 5.13-5.16: Transaction handling (AutoCommit, BEGIN/COMMIT/ROLLBACK)
- 5.17-5.24: Parser/compiler ($^S, MODIFY_CODE_ATTRIBUTES, @INC CODE refs)
- 5.25-5.37: JDBC errors, Storable hooks, //= short-circuit, parser disambiguation
- 5.38-5.56: SQL counter, multi-create FK, Storable binary, DBI Active flag lifecycle
- 5.57-5.58: Post-rebase regressions, pack/unpack 32-bit

</details>

<details>
<summary>Phase 14 details — stashRefCount (click to expand)</summary>

- **Problem**: Moo/Sub::Quote infinite recursion — `releaseCaptures()` fired on CODE refs
  whose cooperative refCount falsely reached 0 because stash assignments
  (`*Foo::bar = $coderef`) were invisible to the cooperative refCount mechanism
- **Fix**: Added `stashRefCount` field to `RuntimeCode`; `DestroyDispatch` skips
  `releaseCaptures()` when `stashRefCount > 0`; `releaseCaptures()` only cascades
  `deferDecrementIfTracked` for blessed referents (not unblessed containers)
- **Results**: `DBICTest->init_schema()` succeeds; 1275 functional tests pass; all 42
  `weaken_edge_cases.t` pass

</details>

---

## Known Bugs (reference)

| Bug | Status | Details |
|-----|--------|---------|
| `B::svref_2object` refcount leak | **Partially fixed** | B.pm two-step construction applied. Full suite re-run needed (GC-2) |
| RowParser.pm line 260 crash | Open | `Not a HASH reference` in `_resolve_collapse` during END blocks. Non-blocking |
| UTF-8 byte-level strings | Systemic | JVM strings always Unicode. See Category C |
| `begin_work` no nested-txn check | Open | `DBI.java:784` doesn't throw when AutoCommit already off. See Category H |
| `$INC` poisoning on failed require | Open | Sets undef instead of deleting. See Issue 15.2 |

---

## Architecture Reference

- `dev/architecture/weaken-destroy.md` — refCount state machine, MortalList, WeakRefRegistry, scopeExitCleanup
- `dev/design/destroy_weaken_plan.md` — DESTROY/weaken implementation plan (PR #464)
- `dev/sandbox/destroy_weaken/` — DESTROY/weaken test sandbox
- `dev/modules/moo_support.md` — Moo support
- `dev/modules/cpan_client.md` — jcpan CPAN client
- `dev/patches/cpan/DBIx-Class-0.082844/` — unapplied patches for txn_scope_guard wrapping
