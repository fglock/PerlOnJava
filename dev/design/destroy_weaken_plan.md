# DESTROY and weaken() — Design & Status

**Status**: Moo 71/71 (100%); DBIx::Class 52leaks leak-free; t/85utf8.t 30/30  
**Version**: 6.0  
**Created**: 2026-04-08  
**Updated**: 2026-04-11 (v6.0 — DBIx::Class test analysis, compress doc, fix plans)  
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

### 5.1 Test Results Summary

| Test | Result | Root Cause | Fix Plan |
|------|--------|------------|----------|
| t/52leaks.t | 6/6 pass, leak-free; exits 255 at line 402 | `local $hash{key}` restore to detached scalar | §5.2 |
| t/53lean_startup.t | ok | — | — |
| t/63register_column.t | ok | — | — |
| t/85utf8.t | 30/30 (2 expected TODO) | — | — |
| t/90ensure_class_loaded.t | ok | — | — |
| t/debug/show-progress.t | ok | — | — |
| t/debug/core.t | 11/12 (1 fail) | `open(>&STDERR)` succeeds after `close(STDERR)` | §5.6 |
| t/multi_create/torture.t | 0/23 | JVM VerifyError (ISTORE/ASTORE conflict) | §5.4 |
| t/storage/txn_scope_guard.t | 17/18 (1 fail) | `@DB::args` empty in non-debug mode | §5.5 |
| t/pager/.../constructor.t | ok (no tests run) | Missing CDSubclass.pm | Low priority |

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

### Phase F2: `local $hash{key}` restore fix — NEXT

**Problem**: See §5.2. `RuntimeHashProxyEntry.dynamicRestoreState()` restores to a
detached RuntimeScalar after hash reassignment.

**Fix approach**: In `dynamicRestoreState()`, write back to the hash by key instead
of restoring fields on `this`. This requires the proxy to hold a reference to its
parent hash and key.

**Impact**: Fixes t/52leaks.t "Target is not a reference" error (line 402).

### Phase F3: STDERR close/dup detection — PLANNED

**Problem**: See §5.6.

**Fix approach**: Ensure `IOOperator.duplicateFileHandle()` checks `ClosedIOHandle`
on the `>&STDERR` path.

**Impact**: Fixes t/debug/core.t (1 failing subtest).

### Phase F4: VerifyError interpreter fallback — PLANNED

**Problem**: See §5.4.

**Fix approach**: Add `catch (VerifyError)` in `RuntimeCode.apply()` that recompiles
to interpreter mode. This handles deferred verification.

**Impact**: Fixes t/multi_create/torture.t (23 subtests).

### Phase F5: `@DB::args` population — PLANNED

**Problem**: See §5.5.

**Fix approach**: In `RuntimeCode.java`, always populate `@DB::args` with actual
frame arguments when caller package is `DB`.

**Impact**: Fixes t/storage/txn_scope_guard.t (1 failing subtest).

---

## 7. Progress Tracking

### Current Status: Moo 841/841; DBIx::Class improving

### Completed (this branch)
- [x] Phase 1-5: Full DESTROY/weaken implementation (2026-04-08–09)
- [x] Moo 71/71 (841/841 subtests) (2026-04-10)
- [x] Phase F1: Exception cleanup myVarRegisters fix (2026-04-11)
- [x] DBI STORABLE_freeze/thaw hooks, installed_drivers stub (2026-04-11)
- [x] All debug tracing removed from DestroyDispatch/RuntimeScalar/MortalList

### Next Steps
1. Phase F2: Fix `local $hash{key}` restore (highest impact for 52leaks.t)
2. Phase F3: Fix STDERR close/dup detection (debug/core.t)
3. Phase F4: VerifyError runtime fallback (torture.t)
4. Phase F5: `@DB::args` in non-debug mode (txn_scope_guard.t)
5. Performance optimization phases O1-O6 (blocking PR merge)

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
