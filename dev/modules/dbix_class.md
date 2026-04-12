# DBIx::Class Fix Plan

## Overview

**Module**: DBIx::Class 0.082844  
**Test command**: `./jcpan -t DBIx::Class`  
**Branch**: `feature/dbix-class-destroy-weaken`  
**PR**: https://github.com/fglock/PerlOnJava/pull/485  
**Status**: Phases 1-14 DONE + deferred-capture cleanup + txn patches applied. **99.3% pass rate** (11,778/11,864). Only 17 real PerlOnJava failures remain in 5 files.

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

## Current Test Results (2026-04-12, post-patch)

| Category | Count | Notes |
|----------|-------|-------|
| Fully passing | **244** | Up from 28+ (B.pm fix + deferred captures + txn patches) |
| GC-only failures | 18 files (36 assertions) | Only `Expected garbage collection` — all real tests pass |
| Upstream TODO | 33 assertions | Fail in Perl 5 too — `# TODO` or `# SKIP` |
| Real PerlOnJava failures | **17 assertions** in 5 files | See breakdown below |
| **Total test files** | **281** | |
| **Total assertions** | **11,778 OK / 86 not-ok** | **99.3% pass rate** |

### Real PerlOnJava Failures (17 assertions in 5 files)

| File | Failures | Category | Notes |
|------|----------|----------|-------|
| t/sqlmaker/order_by_bindtransport.t | 5 | D: Schema temporary refcount | Schema's cooperative refcount reaches 0 when used as temporary |
| t/85utf8.t | 8 | C: UTF-8 systemic | JVM strings always Unicode; can't maintain byte/char distinction |
| t/storage/cursor.t | 2 | New: Custom cursor | Tests 3-4: cursor auto re-load |
| t/storage/txn_scope_guard.t | 1 | B: TxnScopeGuard DESTROY | Test 18: guard DESTROY rollback |
| t/60core.t | 1 | A: Statement handle lifecycle | Unreachable cached statement still active |

**Key recent fix**: Deferred-capture cleanup (`MortalList.flushDeferredCaptures`) runs after
main script returns but before END blocks. Fixes t/101populate_rs.t test 166 and t/100populate.t.
Commit `d32de1dba`.

---

## Direction 1: GC Test Failures (18 files, 36 assertions — down from ~146 files)

### Root Cause

**`B::svref_2object($ref)->REFCNT` leaks refcount.** The `B::SV->new` constructor was
`bless { ref => $ref }, 'B::SV'` — the hash literal goes through
`createReferenceWithTrackedElements()` which bumps the referent's refCount. The B::SV hash
is a JVM-local temporary that never gets `scopeExitCleanup`, so the bump is never reversed.
DBIC's leak tracer calls this on every registered object → ALL get inflated refcounts.

**Fix applied**: B.pm now uses two-step construction (`my $self = bless {}; $self->{ref} = $ref`)
which enters cooperative refcounting properly. Commit `d32de1dba`.

**Results after fix**: GC failures dropped from ~146 files (~658 assertions) to 18 files (36 assertions).
The remaining 18 files have `DBI::db`, `DBICTest::Schema`, or `Storage::DBI` objects that survive
beyond the test's expected lifetime — likely due to deferred captures holding references that
aren't flushed until the script's END phase.

### Fix Plan

| Step | What | Difficulty | Impact |
|------|------|------------|--------|
| **GC-1** | ~~Fix `B::SV::new`~~ DONE — two-step construction in B.pm | Easy | Fixed ~120 files |
| **GC-2** | ~~Re-run full suite~~ DONE — 18 files remain | - | Verified: 244/281 fully pass |
| **GC-3** | Investigate remaining 18 files: DBI::db/Schema/Storage leaks | Medium | Fix last GC failures |

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

## Direction 2: Real (Non-GC) Test Failures (17 assertions in 5 files)

### Fix A: `B::SV::REFCNT` — return real cooperative refcount (HIGH PRIORITY)

**Impact**: Fixes t/sqlmaker/order_by_bindtransport.t (5 failures) + likely many GC-only failures.

**Root cause**: `B::SV::REFCNT` in `src/main/perl/lib/B.pm` line 68-73 hardcodes `return 1`.
DBIC's `Schema::DESTROY` (Schema.pm:1428-1458) has a self-save mechanism that checks
`refcount($srcs->{$source_name}) > 1` — if any ResultSource has refcount > 1, it means
someone else (like `$rs`) still holds a reference, so the Schema reattaches itself.
Since `1 > 1` is always false, the self-save **never fires**.

The t/sqlmaker/order_by_bindtransport.t failure chain:
1. `my $rs = DBICTest->init_schema->resultset('FourKeys')` — Schema is a temporary
2. Cooperative refcount drops to 0 at statement end → `Schema::DESTROY` fires
3. DESTROY calls `refcount()` → `B::svref_2object($ref)->REFCNT` → hardcoded `1`
4. `1 > 1` is false → self-save skipped → Schema dies → "detached result source" error

DBIC's leak tracer also uses `B::svref_2object($ref)->REFCNT` to check if objects have
been properly released. Returning the real cooperative refcount should also fix many of the
18 remaining GC-only failure files.

**Fix**: Make `REFCNT` return the actual cooperative refcount via `Internals::SvREFCNT`:

```perl
# In B.pm, B::SV::REFCNT
sub REFCNT {
    my $self = shift;
    return Internals::SvREFCNT($self->{ref});
}
```

**Files**: `src/main/perl/lib/B.pm` (line ~68)

**Risk**: LOW — `Internals::SvREFCNT` already exists and works (Internals.java:79-88).
This is strictly more correct than hardcoding 1.

---

### Fix B: `AccessorGroup.pm` sentinel check (MEDIUM PRIORITY)

**Impact**: Fixes t/storage/cursor.t tests 3-4 (2 failures).

**Root cause**: `get_component_class` in `Class::Accessor::Grouped::AccessorGroup` uses a
weak-reference sentinel to track whether a component class has been loaded. The mechanism:
1. Strong ref stored in `$DBICTest::Cursor::__LOADED__BY__DBIC__CAG__COMPONENT_CLASS__`
2. Weak ref stored in `$successfully_loaded_components->{'DBICTest::Cursor'}`
3. When `Class::Unload` removes the package, the strong ref is deleted
4. In Perl 5: weak ref becomes undef → cache miss → class re-loaded
5. In PerlOnJava: WEAKLY_TRACKED (`refCount == -2`) objects don't get weak refs cleared
   on hash delete → cache hit → skips re-load → `->new()` fails on empty namespace

**Fix**: Patch `get_component_class` to also verify the strong-side sentinel exists:

```perl
# In AccessorGroup.pm, get_component_class (line ~18)
if (defined $class and (
    ! $successfully_loaded_components->{$class}
    or ! ${"${class}::__LOADED__BY__DBIC__CAG__COMPONENT_CLASS__"}  # added check
)) {
```

**Files**: `~/.perlonjava/lib/Class/Accessor/Grouped/AccessorGroup.pm` (and CPAN build dir)

**Risk**: LOW — adds one package variable lookup, only when the weak ref cache hit occurs.

---

### Fix C: Auto-finish cached statements (MEDIUM PRIORITY)

**Impact**: Fixes t/60core.t test 82 (1 failure).

**Symptom**: `Unreachable cached statement still active: SELECT ...`

After `$or_rs->reset` and `$rel_rs->reset`, `CachedKids` statement handles remain `Active`
because Cursor DESTROY doesn't fire deterministically on JVM. The test at t/60core.t:313-316
iterates `$schema->storage->dbh->{CachedKids}` and fails for each Active handle.

**Fix**: In DBI.pm's `prepare_cached`, when reusing a cached statement that is still Active,
call `$sth->finish()` before returning it. This matches the `if (3)` behavior already
partially implemented (DBI.pm line 632-636). Alternatively, in `Cursor.pm::DESTROY` or
`reset()`, explicitly call `$sth->finish()`.

**Files**: `src/main/perl/lib/DBI.pm` (prepare_cached method)

**Risk**: LOW — auto-finishing stale cached statements is standard DBI behavior.

---

### Fix D: `@DB::args` population for txn_scope_guard test 18 (LOW PRIORITY)

**Impact**: Fixes t/storage/txn_scope_guard.t test 18 (1 failure).

**Root cause**: Test 18 checks that DBIx::Class detects double-DESTROY on TxnScopeGuard
(a defense against `Devel::StackTrace` capturing refs from `@DB::args`). The test:
1. `undef $g` → DESTROY fires → warns
2. `$SIG{__WARN__}` handler calls `caller()` from `package DB`
3. In Perl 5: `@DB::args` gets populated with frame arguments including `$self` (the guard)
4. Guard ref captured in `@arg_capture` → survives DESTROY
5. `@arg_capture = ()` → drops last ref → second DESTROY → "Preventing MULTIPLE DESTROY" warning

In PerlOnJava, `RuntimeCode.java:2020` sets `@DB::args` to empty array in non-debug mode,
so the guard ref is never captured and the double-DESTROY scenario can't occur.

**Fix options**:
1. **Skip/TODO**: This is a niche edge case. The underlying protection works if double-DESTROY
   occurs through other means. The test's trigger mechanism is Perl5-refcounting-specific.
2. **Populate `@DB::args`**: In `RuntimeCode.java`, when `calledFromDB` is true, populate
   `@DB::args` from the actual `@_` of each frame even in non-debug mode. Requires tracking
   `@_` per frame (significant implementation work, affects performance).

**Recommendation**: Skip/TODO this test. The protection mechanism works; only the test trigger
is JVM-incompatible.

**Files**: Would need `RuntimeCode.java` changes (option 2) or test patch (option 1)

---

### Category C (unchanged): UTF-8 Byte/Character Distinction (t/85utf8.t — 8 failures)

**Priority**: LOW — systemic JVM limitation. UTF8Columns is deprecated upstream.

JVM strings are always Unicode. The test creates `$bytestream_title` via `utf8::encode()`
but PerlOnJava can't maintain byte/char distinction. JDBC always returns Unicode strings.
Some tests are `local $TODO` even in Perl 5 (broken since rev 1191, March 2006).

---

### Categories now resolved

| Category | Status | Notes |
|----------|--------|-------|
| B: TxnScopeGuard | **DONE** (3/4) | Patches applied. 1 remaining is test 18 (Fix D above) |
| E: DBI env vars | **DONE** | Verified — fully passing |
| F: FilterColumn | **DONE** | Now passes (was previously 6 failures) |
| G: Upstream TODOs | N/A | 33 assertions fail in Perl 5 too |

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

### Recommended Fix Order (Implementation Plan)

| Step | Fix | Target | Expected Impact | Effort | Status |
|------|-----|--------|-----------------|--------|--------|
| 1 | Fix A | `B::SV::REFCNT` in B.pm | 5 real + many GC | Easy | **TODO** |
| 2 | Fix B | `AccessorGroup.pm` sentinel | 2 real (cursor.t) | Easy | **TODO** |
| 3 | Fix C | DBI.pm prepare_cached auto-finish | 1 real (60core.t) | Easy | **TODO** |
| 4 | Fix D | txn_scope_guard test 18 | 1 real | Skip/TODO | **TODO** |
| 5 | GC-3 | Remaining GC files after Fix A | ~36 GC | Medium | Blocked on Fix A |
| — | C | t/85utf8.t | 8 | Hard | Systemic JVM limitation |
| — | G | upstream TODOs | 33 | None | Fail in Perl 5 too |

**Steps 1-3 are immediately actionable** — all are small, targeted changes.
Step 4 is low priority (niche edge case). Step 5 depends on measuring Fix A's impact.

Previously completed:
- ✅ GC-1,2: B.pm two-step construction (commit `d32de1dba`)
- ✅ begin_work nested-txn check (commit `13a260ee6`)
- ✅ `$INC` cleanup on failed require (commit `13a260ee6`)
- ✅ txn_scope_guard patches applied (2026-04-11)
- ✅ dbi_env.t verified passing

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
| `begin_work` no nested-txn check | **Fixed** | Commit `13a260ee6` — throws when AutoCommit already off |
| `$INC` poisoning on failed require | **Fixed** | Commit `13a260ee6` — deletes instead of setting undef |

---

## Architecture Reference

- `dev/architecture/weaken-destroy.md` — refCount state machine, MortalList, WeakRefRegistry, scopeExitCleanup
- `dev/design/destroy_weaken_plan.md` — DESTROY/weaken implementation plan (PR #464)
- `dev/sandbox/destroy_weaken/` — DESTROY/weaken test sandbox
- `dev/modules/moo_support.md` — Moo support
- `dev/modules/cpan_client.md` — jcpan CPAN client
- `dev/patches/cpan/DBIx-Class-0.082844/` — applied patches for txn_scope_guard wrapping (2026-04-11)
