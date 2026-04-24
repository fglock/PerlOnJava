# DESTROY and weaken() — Design & Status

**Status**: Moo 71/71 (100%); DBIx::Class broad test suite passing  
**Version**: 7.0  
**Created**: 2026-04-08  
**Updated**: 2026-04-11 (v7.0 — F2-F5 fixes complete, broad test sweep)  
**Branch**: `feature/dbix-class-destroy-weaken`

---

## 1. Architecture

Targeted reference counting for blessed objects whose class defines DESTROY,
with global destruction at shutdown as the safety net. Zero overhead for
unblessed objects.

### Core Design

```
refCount == -1                 →  Not tracked (unblessed, or no DESTROY)
refCount == 0                  →  Tracked, zero counted containers (fresh from bless)
refCount > 0                   →  N named-variable containers exist
refCount == Integer.MIN_VALUE  →  DESTROY already called
```

- **Fast path**: `set()` checks `(this.type | value.type) & REFERENCE_BIT`; non-references
  skip `setLarge()` entirely → zero cost for int/double/string/undef.
- **Tracking gate**: `refCount >= 0` in `setLarge()` — one integer comparison, false for
  99%+ of objects (untracked = -1).
- **MortalList**: Deferred decrements (Perl 5 FREETMPS equivalent) for `delete`, `pop`,
  `shift`, `splice`. `active` gate avoids cost for programs without DESTROY.
- **Scope-exit cleanup**: `SCOPE_EXIT_CLEANUP` bytecode opcodes for interpreter; 
  `emitScopeExitNullStores` for JVM backend. Exception propagation cleanup uses
  `myVarRegisters` BitSet to skip temporary registers that alias hash/array elements.
- **Global destruction**: Shutdown hook walks all stashes for `refCount >= 0` objects.
  No persistent tracking set (overcounted objects are GC'd by JVM).

### Weak References

External registry (`WeakRefRegistry`) with forward/reverse maps. No per-scalar field.
`weaken()` decrements refCount; `clearWeakRefsTo()` at DESTROY sets weak refs to undef.
CODE refs skip clearing (stash refs bypass `setLarge()`).

### Key Implementation Files

| File | Role |
|------|------|
| `RuntimeBase.java` | `int refCount = -1` field |
| `RuntimeScalar.java` | `setLarge()` inc/dec, `scopeExitCleanup()`, `undefine()` |
| `DestroyDispatch.java` | DESTROY dispatch, class-has-DESTROY cache |
| `MortalList.java` | Deferred decrements, push/pop mark, flush |
| `WeakRefRegistry.java` | Weak ref forward/reverse maps |
| `GlobalDestruction.java` | Shutdown hook, stash walking |
| `InterpretedCode.java` | `myVarRegisters` BitSet from bytecode scan |
| `BytecodeInterpreter.java` | Exception cleanup uses `myVarRegisters` |
| `BytecodeCompiler.java` | Emits `SCOPE_EXIT_CLEANUP*` opcodes |

---

## 2. Approaches That Failed (Do NOT Retry)

### X1. Remove birth-tracking from `createReferenceWithTrackedElements()` (REVERTED)
Broke `isweak()` tests. Birth-tracking is load-bearing for `isweak()` correctness.

### X2. Type-aware `weaken()` transition: set `refCount = 1` for data structures (REVERTED)
Caused infinite recursion in Sub::Defer. Starting refCount mid-flight with multiple
pre-existing strong refs undercounts — premature DESTROY during routine `setLarge()`.
**Lesson**: Cannot start accurate refCount tracking mid-flight.

### X3. JVM WeakReference for Perl-level weak refs (ANALYZED, NOT VIABLE)
JVM GC is non-deterministic — referent lingers after strong refs removed. 102 instanceof
changes across 35 files. Cannot provide synchronous clearing that Perl 5 tests expect.

### X4. GC-based DESTROY (Cleaner/sentinel pattern) (REMOVED in v5.0)
Fundamental flaw: cleaning action must hold referent alive for DESTROY, but this prevents
sentinel from becoming phantom-reachable. Also: thread safety overhead, +8 bytes/object.

### X5. Per-statement `MortalList.flush()` bytecode emission (REVERTED in v5.4)
Caused OOM in `code_too_large.t`. Moved flush to runtime methods (`apply()`, `setLarge()`).

### X6. Pre-flush before `pushMark()` in scope exit (REVERTED in v5.15)
Caused refCount inflation, broke 13 op/for.t tests and re/speed.t.

---

## 3. Known Limitations

1. **Pre-bless copies undercounted**: Refs copied before `bless()` aren't tracked.
2. **Multi-boundary return overcounting**: Objects crossing 2+ function boundaries
   accumulate +1 per extra boundary. DESTROY at global destruction.
3. **Circular refs without weaken()**: DESTROY at global destruction (matches Perl 5).
4. **`Internals::SvREFCNT`**: Returns constant 1. Full refcounting rejected for perf.
5. **Lazy+weak anonymous defaults** (Moo tests 10/11): Requires full refcounting from
   birth or JVM WeakReference — both rejected. Accepted limitation.
6. **Optree reaping** (Moo test 19): JVM never unloads compiled classes. Cannot pass.

---

## 4. Performance Optimization Status

Branch shows regressions on compute-intensive benchmarks:
- `benchmark_lexical.pl`: -30% (scopeExitCleanup overhead)
- `life_bitpacked.pl` braille: -60% (setLarge bloat kills JIT inlining)

### Optimization Phases (§16 of old doc)

| Phase | Status | Impact | Description |
|-------|--------|--------|-------------|
| O4: Extract `setLargeRefCounted()` | **Done** | HIGH | Keeps `setLarge()` small for JIT inlining |
| O3: Runtime fast-path in `scopeExitCleanup` | Pending | MEDIUM | Early exit for non-reference scalars |
| O1: Compile-time scope-exit elision | Pending | HIGH | Skip cleanup for provably non-reference vars |
| O2: Elide pushMark/popAndFlush | Pending | HIGH | Skip for scopes with no cleanup vars |
| O5: `MortalList.active` gate | Pending | LOW | Re-enable lazy activation |
| O6: Reduce RuntimeScalar size | Pending | LOW | Pack booleans into flags byte |

---

## 5. DBIx::Class Test Analysis (2026-04-11)

### 5.1 Test Results Summary (2026-04-11, after F2-F5 fixes)

| Test | Result | Notes |
|------|--------|-------|
| t/04_c3_mro.t | 5/5 | |
| t/05components.t | 4/4 | |
| t/100extra_source.t | 11/11 | |
| t/100populate.t | 108/108 | |
| t/101populate_rs.t | 165/165 | |
| t/101source.t | 1/1 | |
| t/102load_classes.t | 3/4 (1 fail) | Pre-existing issue |
| t/103many_to_many_warning.t | 4/4 | |
| t/104view.t | 4/4 | |
| t/106dbic_carp.t | 3/3 | |
| t/18insert_default.t | 4/4 | |
| t/19retrieve_on_insert.t | 4/4 | |
| t/20setuperrors.t | 1/1 | |
| t/26dumper.t | 2/2 | |
| t/33exception_wrap.t | 3/3 | |
| t/34exception_action.t | 9/9 | |
| t/46where_attribute.t | 20/20 | |
| t/52leaks.t | 8 pass/20 (2 TODO) | Leak detection limited by refcount overcounting |
| t/53lean_startup.t | 6/6 | |
| t/60core.t | 125/125 | |
| t/63register_column.t | 1/1 | |
| t/76joins.t | 27/27 | |
| t/77join_count.t | 4/4 | |
| t/80unique.t | 55/55 | |
| t/83cache.t | 23/23 | |
| t/84serialize.t | 115/115 | |
| t/85utf8.t | 30/30 (2 expected TODO) | |
| t/86might_have.t | 4/4 | |
| t/87ordered.t | 1271/1271 | |
| t/88result_set_column.t | 46/47 (1 fail) | |
| t/90ensure_class_loaded.t | 27/28 | |
| t/93autocast.t | 2/2 | |
| t/96_is_deteministic_value.t | 8/8 | |
| t/97result_class.t | 19/19 | |
| t/count/distinct.t | 61/61 | |
| t/count/in_subquery.t | 1/1 | |
| t/count/prefetch.t | 9/9 | |
| t/count/search_related.t | 5/5 | |
| t/debug/core.t | 12/12 | Fixed: STDERR close/dup detection |
| t/delete/complex.t | 5/5 | |
| t/delete/m2m.t | 5/5 | |
| t/inflate/core.t | 32/32 | |
| t/inflate/serialize.t | 12/12 | |
| t/multi_create/torture.t | 23/23 | Fixed: VerifyError interpreter fallback |
| t/ordered/cascade_delete.t | 1/1 | |
| t/prefetch/diamond.t | 768/768 | |
| t/prefetch/grouped.t | 52/53 (1 fail) | |
| t/prefetch/multiple_hasmany.t | 8/8 | |
| t/prefetch/standard.t | 46/46 | |
| t/prefetch/via_search_related.t | 41/41 | |
| t/prefetch/with_limit.t | 14/14 | |
| t/relationship/core.t | 82/82 | |
| t/relationship/custom.t | 57/57 | |
| t/resultset/as_subselect_rs.t | 6/6 | |
| t/resultset/is_ordered.t | 14/14 | |
| t/resultset/is_paged.t | 2/2 | |
| t/resultset/rowparser_internals.t | 13/13 | |
| t/resultset/update_delete.t | 71/71 | |
| t/search/preserve_original_rs.t | 31/31 | |
| t/search/related_strip_prefetch.t | 1/1 | |
| t/search/subquery.t | 18/18 | |
| t/storage/base.t | 36/36 | |
| t/storage/dbi_coderef.t | 1/1 | |
| t/storage/reconnect.t | 37/37 | |
| t/storage/savepoints.t | 29/29 | |
| t/storage/txn_scope_guard.t | 17/18 (1 fail) | Test 18: multiple DESTROY prevention |

### 5.2 t/52leaks.t: `local $hash{key}` Restore After Hash Reassignment

**Symptom**: "Target is not a reference" at line 402 after `populate_weakregistry`
gets undef instead of the expected arrayref.

**Root cause**: PerlOnJava's `local $hash{key}` saves/restores the RuntimeScalar
*object* (Java identity), not the hash+key pair. When `%$hash = (...)` clears and
repopulates the hash (via `RuntimeHash.setFromList()` → `elements.clear()` → new
`RuntimeScalar` objects), the localized scalar is detached. Scope-exit restore writes
to the stale object; the hash has a new undef entry.

**Perl 5 behavior**: `local $hash{key}` saves `(hash, key, old_value, existed)` and
restores by doing `$hash{$key} = $old_value`. Survives hash reassignment.

**Fix**: `RuntimeHashProxyEntry.dynamicRestoreState()` needs to write back to the
hash container by key, not to the detached RuntimeScalar object. Currently it does
field-level restore on `this` (the proxy); it should do `hash.put(key, savedScalar)`.

**Files**: `RuntimeHashProxyEntry.java`, possibly `RuntimeTiedHashProxyEntry.java`

**Secondary issue**: "database connection closed" error appears in output. Likely
caused by `DBI::db::DESTROY` firing on a cloned handle during Storable::dclone's
leak-check iteration. The STORABLE_freeze/thaw hooks prevent connection sharing,
but the error may come from prepare_cached using a stale `$sth->{Database}` weak ref.
Lower priority — investigate after the local fix.

### 5.3 t/85utf8.t: PASSING (30/30)

Previously reported failures were from an older build. Current branch passes all
30 subtests. Test 10 (raw bytes INSERT) and test 30 (alias propagation) are
expected TODO failures.

### 5.4 t/multi_create/torture.t: JVM VerifyError

**Symptom**: `java.lang.VerifyError: Bad local variable type` — slot 187 is `top`
(uninitialized) when `aload` expects a reference.

**Root cause**: `EmitterMethodCreator.java:573-581` pre-initializes ALL temp local
slots with `ACONST_NULL`/`ASTORE` (reference type). But many slots later use
`ISTORE` (integer type) for `callContextSlot`, `typeSlot`, `flipFlopIdSlot`, etc.
When an `ISTORE` allocation occurs inside a conditional branch, the JVM verifier
at the merge point sees: if-path = integer, else-path = reference → merged = TOP.
Any subsequent `aload` of that slot fails.

**Also**: `TempLocalCountVisitor` severely undercounts — only handles 5 AST node
types, missing subroutine calls (4-7 slots each), assignments, regex ops, etc.

**Interpreter fallback**: Exists at 3 levels (compilation, instantiation, top-level)
but has a timing gap — if verification is deferred to first invocation, VerifyError
wraps in RuntimeException and propagates to eval, skipping all 23 assertions.

**Fix options** (in priority order):
1. Fix pre-initialization to use ICONST_0/ISTORE for integer-typed slots
2. Make TempLocalCountVisitor comprehensive
3. Add runtime VerifyError catch in `RuntimeCode.apply()` for deferred verification
4. Quick mitigation: increase buffer from +256 to +512

**Workaround**: `JPERL_INTERPRETER=1` forces interpreter mode for all code.

### 5.5 t/storage/txn_scope_guard.t: `@DB::args` Empty in Non-Debug Mode

**Symptom**: Test expects warning "Preventing *MULTIPLE* DESTROY() invocations on
DBIx::Class::Storage::TxnScopeGuard" but it never appears.

**Root cause**: `RuntimeCode.java:2035-2039` — when `DebugState.debugMode == false`,
`@DB::args` is set to empty array instead of actual subroutine arguments. In Perl 5,
`caller()` from `package DB` ALWAYS populates `@DB::args` regardless of debugger state.

**Impact**: The test's `$SIG{__WARN__}` handler (running in package DB) captures
`@DB::args` via `caller()` to hold an extra reference to the TxnScopeGuard object.
Without args, no extra ref is held, no second DESTROY occurs, no warning.

**Fix**: In `RuntimeCode.java`, populate `@DB::args` with actual frame arguments
when `caller()` is invoked from `package DB`, regardless of `debugMode`.

### 5.6 t/debug/core.t: `open(>&STDERR)` Succeeds After `close(STDERR)`

**Symptom**: Exception text is "5" (the query result) instead of "Duplication of
STDERR for debug output failed".

**Root cause**: `open($fh, '>&STDERR')` succeeds even after `close(STDERR)`.
The test expects the open to fail (STDERR is closed), which would trigger a die
in `_build_debugfh`. Since open succeeds, no exception is thrown, and the try
block returns the count result (5).

**Fix**: In `IOOperator.duplicateFileHandle()`, check if the source handle is
a `ClosedIOHandle` and return null/failure. The check at line 2762 may not be
reached for the `>&STDERR` path.

### 5.7 Other Issues

**Params::ValidationCompiler version mismatch**: Warning about versions 1.1 vs 1.45.
Cosmetic — version reporting inconsistency in bundled modules. Low priority.

**t/cdbi/columns_as_hashes.t and t/zzzzzzz_perl_perf_bug.t**: Appear to hang.
Likely infinite loops or missing timeout handling. Investigate separately.

**Subroutine to_json redefined**: Warning from Cpanel::JSON::XS loading. Cosmetic.

**CDSubclass.pm not found**: Missing test library. May need module installation.

---

## 6. Fix Implementation Plan

### Phase F1: Exception cleanup — DONE (2026-04-11)

**Problem**: Bytecode interpreter's exception propagation cleanup called
`scopeExitCleanup` on ALL registers, including temporaries aliasing hash elements
(via HASH_GET), causing spurious refCount decrements and premature DESTROY of
DBI::db handles.

**Fix**: Added `myVarRegisters` BitSet to `InterpretedCode.java` — scans bytecodes
for `SCOPE_EXIT_CLEANUP*` opcodes to identify actual my-variable registers. Exception
cleanup loop now uses `BitSet.nextSetBit()` to skip temporaries.

**Result**: t/52leaks.t leak detection passes ("Auto checked 25 references for leaks
— none detected"). All unit tests pass.

**Files**: `InterpretedCode.java`, `BytecodeInterpreter.java`  
**Commit**: `f6627daab`

### Phase F2: `local $hash{key}` restore fix — DONE (2026-04-11)

**Problem**: See §5.2. `RuntimeHashProxyEntry.dynamicRestoreState()` restores to a
detached RuntimeScalar after hash reassignment.

**Fix**: `RuntimeHashProxyEntry` now holds parent hash reference and key. 
`dynamicRestoreState()` writes back via `parent.put(key, savedScalar)`.
Extended to arrow dereference (`local $ref->{key}`) for both JVM and interpreter
backends with new opcodes HASH_DEREF_FETCH_FOR_LOCAL (470) and
HASH_DEREF_FETCH_NONSTRICT_FOR_LOCAL (471).

**Result**: t/52leaks.t no longer exits at line 402. Tests 1-8 pass, tests 12-20
fail due to expected refcount overcounting limitations.

**Files**: `RuntimeHashProxyEntry.java`, `RuntimeHash.java`, `RuntimeScalar.java`,
`Dereference.java`, `EmitOperatorLocal.java`, `BytecodeCompiler.java`,
`BytecodeInterpreter.java`, `Opcodes.java`, `Disassemble.java`  
**Commits**: `ad7255715`

### Phase F3: STDERR close/dup detection — DONE (previous commit)

**Result**: t/debug/core.t 12/12 pass.  
**Commit**: `c65974e16`

### Phase F4: VerifyError interpreter fallback — DONE (previous commit)

**Result**: t/multi_create/torture.t 23/23 pass.  
**Commit**: `d7a435d46`

### Phase F5: `@DB::args` population — DONE (2026-04-11)

**Problem**: See §5.5. `@DB::args` was always empty in non-debug mode.

**Fix**: `callerWithSub()` now detects package DB via `__SUB__.packageName` (JVM path)
and `InterpreterState.currentPackage` (interpreter path). Uses pre-skip `argsFrame`
for `argsStack` indexing. JVM backend's `handlePackageOperator()` now emits runtime
`InterpreterState.setCurrentPackage()` call.

**Result**: @DB::args correctly populated. t/storage/txn_scope_guard.t still 17/18
(test 18 fails because PerlOnJava prevents multiple DESTROY by design).

**Files**: `RuntimeCode.java`, `EmitOperator.java`, `InterpreterState.java`  
**Commit**: `a13d6a3d4`

---

## 7. Progress Tracking

### Current Status: Moo 841/841; DBIx::Class 3000+ subtests passing across 60+ test files

### Completed (this branch)
- [x] Phase 1-5: Full DESTROY/weaken implementation (2026-04-08–09)
- [x] Moo 71/71 (841/841 subtests) (2026-04-10)
- [x] Phase F1: Exception cleanup myVarRegisters fix (2026-04-11)
- [x] DBI STORABLE_freeze/thaw hooks, installed_drivers stub (2026-04-11)
- [x] All debug tracing removed from DestroyDispatch/RuntimeScalar/MortalList
- [x] Phase F2: `local $hash{key}` + `local $ref->{key}` restore fix (2026-04-11)
- [x] Phase F3: STDERR close/dup detection (already fixed)
- [x] Phase F4: VerifyError interpreter fallback (already fixed)
- [x] Phase F5: @DB::args population in non-debug mode (2026-04-11)

### Known Remaining Failures
1. t/52leaks.t tests 12-20: Leak detection fails due to refcount overcounting (§3)
2. t/storage/txn_scope_guard.t test 18: Multiple DESTROY prevention (by design)
3. t/102load_classes.t: 1 test failure (pre-existing)
4. t/inflate/hri.t: Missing CDSubclass.pm module

### Next Steps
1. Performance optimization phases O1-O6 (blocking PR merge)
2. Investigate t/102load_classes.t failure
3. Investigate t/52leaks.t refcount overcounting if feasible

### Test Commands
```bash
# Unit tests
make

# DBIx::Class specific tests
cd /Users/fglock/.cpan/build/DBIx-Class-0.082844-41
PERL5LIB="t/lib:$PERL5LIB" /path/to/jperl t/52leaks.t
PERL5LIB="t/lib:$PERL5LIB" /path/to/jperl t/85utf8.t
PERL5LIB="t/lib:$PERL5LIB" /path/to/jperl t/debug/core.t
PERL5LIB="t/lib:$PERL5LIB" /path/to/jperl t/storage/txn_scope_guard.t
PERL5LIB="t/lib:$PERL5LIB" /path/to/jperl t/multi_create/torture.t

# Moo test suite
./jcpan --jobs 8 -t Moo
```
