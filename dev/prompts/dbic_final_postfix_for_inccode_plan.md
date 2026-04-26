# Plan: Fix postfixderef / for* / inccode regressions vs master

**Branch:** `feature/dbic-final-integration`
**Base:** `4329ccd24` (DBIC-safe perf flatten) + 25 fix commits + recent integration commits
**Status (start):** PR560 vs PR554 shows -78 tests in 5 specific files; the rest is +410, net +334.
**Goal:** recover the -78 without breaking DBIC, Moo, Template.

---

## Status snapshot

| Test file | system Perl | master tip | our base `4329ccd24` | our branch HEAD |
|---|---|---|---|---|
| `op/postfixderef.t` | 128/128 | **117**/128 | 114/128 | 114/128 |
| `op/for.t`          | 149/149 | **141**/149 | 128/149 | 139/149 |
| `op/for-many.t`     |  81/81  |  **72**/81 |  70/81  |  70/81 |
| `op/inccode.t`      |  75/75  |  **70**/75 |  68/75  |  68/75 |
| `op/inccode-tie.t`  |  75/75  |  **74**/75 |  72/75  |  72/75 |

**Crucial finding:** our branch matches **base** behavior, not master. Base `4329ccd24` (which flattened ~160 commits of weakref/destroy/refcount work) regressed these tests vs master. The 27 master-tip commits that came afterwards (which we picked up via rebase) don't fix them.

So the regression source lives inside base's flatten — primarily in:
- DESTROY / weak-ref tracking gates
- `eval STRING` return-value capture
- foreach iterator aliasing
- `do "file"` / `@INC` hook arg-passing refcount symmetry

---

## Tradeoff context

A previous attempt at per-element refcount symmetry (commit history in `RuntimeScalar.addToArray` long comment) **broke DBIC TxnScopeGuard**. So any refcount-symmetry change must keep DBIC passing — verify with `./jcpan -t DBIx::Class` after each change. The chosen approach is **Option 2** from that comment: "Keep the incref in container stores, teach `popArgs()` to walk the args array's elements and decref each one." Per-call cost is small.

---

## Categories

### Category B — for-many alias refcount semantics
Tests: `op/for-many.t` #78, #79, #80 (and #68-71)
Master: 72; ours: 70. **Net: -2.**

#### What system Perl does

```perl
for my ($x, $y) (\@arrx, \@arry) {
    refcount_is \@arrx, 2+1, '...';   # 2 refs alive: outer scope + iterator alias
    refcount_is \@arry, 2+1, '...';
}
refcount_is \@arrx, 1+1, '...';       # iterator alias gone
```

`for my ($x, $y) (LIST)` is *aliasing* iteration — `$x` and `$y` become aliases of LIST elements.
The iteration variables hold a strong refcount on the referent.

#### What PerlOnJava does

Copies LIST elements into the iteration variables (no shared identity, no refcount bump).

#### Fix

- File: `src/main/java/org/perlonjava/backend/jvm/EmitForeach.java`
- For multi-var foreach (`for my ($x, $y)`), treat each iteration step as element-aliasing rather than copy:
    - Iteration entry: `$x.value = elem.value; ScalarRefRegistry.registerRef($x); base.refCount++`
    - Iteration exit: `base.refCount--; deferDestroyIfZero`
- Fall back to copy semantics for non-reference elements (numbers, strings).

#### DBIC risk
Low. DBIC's `for my $row (@results)` semantics already alias in real Perl; matching real Perl is consistent.

#### Estimated cost
1–2 hours. Smallest, well-localized fix. **Start here.**

---

### Category D — for.t do-block return and undef-element iteration
Tests: `op/for.t` #103, #105 (also several "for CORE::my Dog $spot" syntax tests in 141-149).
Master: 141; ours: 139. **Net: -2.**

#### What system Perl does

Test 103:
```perl
print do { foreach (1, 2) { 1; } };   # prints empty (no last value from foreach)
```

Test 105:
```perl
foreach (@array_containing_undef) { ... }   # iterates including undef element
```

#### What PerlOnJava does

Test 103: probably emits `1` instead of nothing (foreach picking up the block's last expression as result in scalar context).
Test 105: fails with undef element handling — possibly tries to alias to undef and trips a read-only check.

#### Fix

- Bisect among the 4 foreach-related work-branch commits:
    - `81876e73a` — `for our $i (...)` — iterator writes to `our` global
    - `30c954a18` — preserve read-only aliasing for literals in LIST context
    - `f45429cdc` — `ReadOnlyAlias` wrapper for foreach literal aliasing
    - `00512b3c3` — `SET_SCALAR` preserves read-only alias through refgen path
- Identify which one introduced the regression vs master.
- Tweak the gate so `ReadOnlyAlias` only fires for *literal* values (`for (3, "abc")`), not for *named lvalue elements* (`for (@array)`).

#### DBIC risk
Low. DBIC iterates `@arrays` and `(*, *, *)` lists. Read-only aliasing should not trigger for those.

#### Estimated cost
1–2 hours.

---

### Category A — block-exit DESTROY for blessed returns from `eval STRING`
Tests: `op/postfixderef.t` #38 ("no stooges outlast their scope") + secondary tests #100-107.
Master: 117; ours: 114. **Net: -3.**

#### What system Perl does

```perl
{
    my $coulda  = eval q{ bless \'curly'->@*, 'coulda' };
    my $shoulda = eval q{ bless \'larry'->%*, 'shoulda' };
}
# DESTROY fires for both at block exit, in declaration-reverse order
```

The blessed referents go through `eval STRING` and are *returned* to the caller. They must be tracked for block-exit DESTROY just like locally-blessed objects.

#### What PerlOnJava does

`a` and `b` (regular `bless \scalar, 'class'`) DESTROY at block exit.
`c` and `d` (created via `eval STRING`) do **not** fire DESTROY at block exit. They fire later in global destruction (or never, due to a NPE in `GlobalDestruction.runGlobalDestruction:42`).

#### Fix

1. **Eval-string return tracking.** In `EvalStringHandler` and the AST visitor that handles its result, ensure the returned scalar:
    - Has `refCountOwned = true` if the value is a tracked reference.
    - Is registered in `ScalarRefRegistry` so the walker sees it.
    - Has its destination lexical's `MyVarCleanupStack.register` called so block-exit cleanup walks it.
2. **GlobalDestruction NPE.** Defensive null check at line 42 of `GlobalDestruction.java` so we still run the rest of global destruction if one entry has a null array.

#### DBIC risk
Low. DBIC does not depend on eval-string blessed objects being missed by cleanup; it depends on them *not being prematurely freed*. Adding tracking only makes cleanup more aggressive at scope exit, never premature.

#### Estimated cost
Half day.

---

### Category C — `do "file"` / `@INC` hook refcount leak
Tests: `op/inccode.t` #61, #63 + `op/inccode-tie.t` #61, #63.
Master: 70 / 74; ours: 68 / 72. **Net: -4.**

#### What system Perl does

```perl
my $die = sub { die };
my $data = [];
unshift @INC, sub { $die, $data };
my $r0 = SvREFCNT($die);
do "foo";
SvREFCNT($die) == $r0;        # no leak
```

The @INC sub returns `$die, $data` as a list. The loader iterates the list, identifies the source-code generator (CODE) and the iterator data, then drops them. Refcounts must be back to baseline.

#### What PerlOnJava does

After `do "foo"`, refcount of `$die` is permanently +1 — the loader's internal capture isn't matched by a release.

#### Where the leak comes from

PerlOnJava's `addToArray` (arg-passing path) does *not* incref, while `set()` (assignment path) *does* incref. When the loader internally does:
```
my @inc_sub_result = @inc_sub_call;  # no incref
my $generator = $inc_sub_result[0];  # set() — increfs
```
We end up with `+1` from `set` that's never matched.

#### Fix

Per the long comment in `RuntimeScalar.addToArray`, **Option 2**:
1. Restore the incref in `addToArray` (so arrays uniformly own their elements).
2. Add a matching decref in `popArgs()` that walks the `@_` array elements at sub-call return and decrefs each.

This restores the symmetry without needing to remember "which arrays incref and which don't".

Verification:
- Run `t/destroy_zombie_captured_by_db_args.t` (the regression test for the original Option-1 workaround).
- Run `./jcpan -t DBIx::Class` to confirm TxnScopeGuard / similar patterns still pass.
- Run `./jcpan -t Template` and `./jcpan -t Moo`.

#### DBIC risk
**Highest.** Past attempts at this exact change broke DBIC's TxnScopeGuard. With our recent fixes (popAndFlush revert, harness, RuntimeHash undef fast path, bless canonicalization), the previous DBIC failure modes are gone — but new ones may surface. Run the **full** DBIC suite and **rollback** if any test fails.

#### Estimated cost
1–2 days. Most invasive, highest risk. **Do last.**

---

## Implementation order

1. **B (for-many alias)** — smallest, lowest risk
2. **D (for.t #103/#105)** — bisect, small fix
3. **A (eval STRING DESTROY)** — medium, half day
4. **C (do/INC refcount symmetry)** — biggest, do last with full DBIC verification

After each step:
```bash
make                                         # unit tests must pass
./jcpan -t DBIx::Class > /tmp/dbic.log 2>&1  # DBIC parity check
./jcpan -t Moo > /tmp/moo.log 2>&1           # Moo
./jcpan -t Template > /tmp/template.log 2>&1 # Template
```

If `make` fails or DBIC degrades from 314/314 PASS, **roll back the change** before moving to the next category.

After all 4 categories:
```bash
rm -rf perl5_t/t/tmp_*
perl dev/tools/perl_test_runner.pl --jobs 8 --timeout 300 \
    --output out.json perl5_t/t \
    > ../PerlOnJava/logs/test_$(date +%Y%m%d_%H%M%S)_PR560_v2.log 2>&1
perl dev/tools/compare_test_logs.pl \
    ../PerlOnJava/logs/test_20260424_135000_PR554.log \
    ../PerlOnJava/logs/test_<latest>.log
```

## Success criteria

- 5 specific test files match or exceed master: postfixderef ≥117, for ≥141, for-many ≥72, inccode ≥70, inccode-tie ≥74.
- DBIx::Class: still **314 files / 13858 tests PASS, 0 Dubious**.
- Moo: still 71/71. Template: still 106/106.
- `make`: BUILD SUCCESSFUL.
- No new perl5_t/t regressions ≥3 tests in any single file.

## Where the changes go

- `src/main/java/org/perlonjava/backend/jvm/EmitForeach.java` — Category B (for-many alias)
- `src/main/java/org/perlonjava/backend/jvm/EmitForeach.java` (different code path) — Category D (foreach + readonly)
- `src/main/java/org/perlonjava/backend/bytecode/EvalStringHandler.java` — Category A (eval-string return tracking)
- `src/main/java/org/perlonjava/runtime/runtimetypes/GlobalDestruction.java` — Category A NPE fix
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java` (`addToArray`) — Category C (restore incref)
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeArray.java` (`popArgs`) — Category C (matching decref)

## Out of scope

- `op/do.t -1`, `op/recurse.t -1`, `op/stat.t -1`, `op/tie.t -1`, `test_pl/examples.t -1` (each lost 1 test) — likely flakes; ignore unless they appear in a category fix.
- `win32/seekdir.t -30`, `porting/checkcase.t -27` — environmental (file count varies per checkout); not real regressions.
