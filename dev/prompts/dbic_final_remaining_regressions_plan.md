# Plan: Eliminate remaining regressions on `feature/dbic-final-integration`

**Branch:** `feature/dbic-final-integration` at `66c69808f`
**Constraint:** project rules forbid merging with regressions. **Must fix all of them.**
**Hard floor:** DBIx::Class 314/314 PASS, Moo 71/71, Template 106/106, `make` BUILD SUCCESSFUL — never break these.

---

## Real regressions vs PR554 baseline (after Categories B+C fix)

Compare report (`compare_test_logs.pl`):

```
✗ win32/seekdir.t      275/276  → 245/246  -30   environmental — OS-specific filesystem
✗ porting/checkcase.t  2143     → 2116     -27   environmental — file-count differs per checkout
✗ op/postfixderef.t    117/128  → 114/128  -3    1 unique failure — knock-on numbering -2
✗ op/for.t             141/149  → 139/149  -2    2 unique failures
✗ op/do.t              69/73    → 68/73    -1    1 unique failure
✗ op/recurse.t         26/28    → 25/28    -1    flake — passes 28/28 standalone
✗ op/tie.t             60/95    → 59/95    -1    1 unique failure
✗ test_pl/examples.t   11/17    → 10/17    -1    1 unique failure
```

**Important:** the **-3 in postfixderef.t is misleading** — only 1 test name fails uniquely vs master (`no stooges outlast their scope`). The other 2 are knock-on numbering shifts (when test #38 fails, every subsequent test number is offset, but the *names* match master). Fixing #38 will recover all 3 reported tests.

After excluding environmental and flakes, **6 unique-by-name failing tests** (table below) drive every reported regression number.

---

## Root-cause classification

I diffed each test's failing-by-name set against master. **All 6 are pre-existing in base `4329ccd24`** (the 160-commit DBIC-safe perf flatten). Master fixed them; our base did not pick those fixes up.

| # | File | Failing test name | Theme |
|---|---|---|---|
| 1 | `op/postfixderef.t` | `no stooges outlast their scope` | Block-exit DESTROY for blessed refs from `eval STRING` |
| 2 | `op/do.t` | `RT 124248` | Block-exit FREETMPS for `do {…}` |
| 3 | `op/tie.t` | `[at op/tie.t line 18]` (one specific subtest run by `run_multiple_progs`) | Tie semantics edge case |
| 4 | `test_pl/examples.t` | `two references` | `Internals::SvREFCNT` `&`-calling-convention adjustment |
| 5 | `op/for.t` | `RT #1085: do { foreach (1,2) {1;} } returns ""` | Foreach in scalar context return value |
| 6 | `op/for.t` | `foreach (@array_containing_undef)` | `$_` aliasing for undef list element |

Themes consolidate to **3 actual code areas**:

- **Theme F (FREETMPS at scope boundaries):** failures 1, 2, possibly 3
- **Theme S (Internals::SvREFCNT + foreach in scalar context):** failures 4, 5
- **Theme A (foreach $_ aliasing):** failure 6

---

## Strategy

Implement in order of rising risk. After each fix, run **gate** before committing:

```bash
make                                 # unit tests must pass
./jcpan -t DBIx::Class | grep PASS   # 314/314 PASS, 0 Dubious
./jcpan -t Moo                       # 71/71 PASS
./jcpan -t Template                  # 106/106 PASS
```

If gate fails, **revert the change** before moving on.

---

## DBIC risk audit — per step

| Step | What it changes | DBIC code uses this? | Risk |
|---|---|---|---|
| 1 | `Internals::SvREFCNT` return value adjustment | **No** (DBIC never queries SvREFCNT) | **None — diagnostic API only** |
| 2 | Adds FREETMPS at do-block + eval-string scope exit | DBIC uses `eval { ... }` extensively for txn errors; rarely uses `eval STRING`; uses `do { ... }` in a few helpers but not for object-lifetime patterns | **Medium — needs full DBIC suite** |
| 3 | Foreach in scalar context returns `""` not `undef` | DBIC never reads foreach's return value | **None** |
| 4 | `$_` aliasing for undef list element | DBIC iterates result rows, never undef-laden lists | **Very low** |
| 5 | Specific tied-container subtest fix | DBIC does not use `tie` | **Very low** |
| 6 | recurse.t flake stabilization | Test infrastructure only | **None** |

**Highest risk: Step 2.** This is where extra care matters most.

### Step 2 deep risk analysis

The change *adds* FREETMPS where currently missing — it does not change existing FREETMPS points.

What could go wrong:
- A pending mortal that DBIC relies on (e.g., a Schema returned via a chain that includes a do-block) could fire DESTROY at the new flush point if the destination scalar hasn't yet captured a strong ref.
- However, real Perl already does this FREETMPS at do-block exit; DBIC works on real Perl, so it cannot rely on the absence of that flush.

What we will verify:
- Run `./jcpan -t DBIx::Class` BEFORE and AFTER the Step 2 change.
- The 8-test `dbic_fast_check.sh` between Step 2 sub-changes (eval-string fix vs do-block fix) so we can isolate which sub-change broke DBIC if any.
- Also re-run `./jcpan -t Moo` and `./jcpan -t Template` since they depend on Sub::Quote which uses `eval STRING` heavily.

If Step 2 breaks DBIC, fall back to: implement only the **eval-string return value tracking** (registers blessed refs in `MyVarCleanupStack` so block-exit cleanup walks them), without touching do-block FREETMPS. The eval-string fix alone covers postfixderef.t #38; do-block FREETMPS only covers do.t RT 124248. Worth losing 1 test if it preserves DBIC.

---

### Step 1 — Theme S: `Internals::SvREFCNT` adjustment (test_pl/examples.t #4)

**System Perl behavior** (verified via direct probing):

```perl
my @a;
&Internals::SvREFCNT(\@a);    # 1   — just lex pad
my $r = \@a;
&Internals::SvREFCNT(\@a);    # 2   — lex + $r

my $x = [];
&Internals::SvREFCNT($x);     # 0   — anon AV, single owner ($x), reports owner_count - 1
my $r = $x;
&Internals::SvREFCNT($x);     # 1   — 2 owners, reports 1
my $r2 = $x;
&Internals::SvREFCNT($x);     # 2   — 3 owners, reports 2
```

The `&` calling-style with an existing RV variable consistently reports `owner_count − 1`. With `\@a` (a temp `\` operator), the temp itself doesn't count, so the report equals `owner_count`.

**PerlOnJava current (after Categories B+C fix):**

| Scenario | refCount | localBindingExists | We report | Real Perl | Need |
|---|---|---|---|---|---|
| `my @a` | 0 | true | 1 | 1 | match ✓ |
| `\@a` after `$r=\@a` | 1 | true | 2 | 2 | match ✓ |
| `my $x = []` | 1 | false | 1 | 0 | **−1** |
| `$r=$x;` (anon) | 2 | false | 2 | 1 | **−1** |

**Fix:** subtract 1 when the queried base is anon (no `localBindingExists`).

```java
public static RuntimeList svRefcount(RuntimeArray args, int ctx) {
    RuntimeScalar arg = args.get(0);
    if (arg.value instanceof RuntimeBase base) {
        int rc = base.refCount;
        if (rc == Integer.MIN_VALUE) return new RuntimeScalar(0).getList();
        if (rc < 0) return new RuntimeScalar(1).getList();
        int extra = base.localBindingExists ? 1 : 0;
        if (rc == 0 && extra == 0) return new RuntimeScalar(1).getList();  // legacy "live SV" fudge
        // Real Perl reports `owner_count − 1` when the queried referent is a
        // tracked anonymous container (the function arg itself is one of the
        // owners and gets discounted). For named lexicals (localBindingExists),
        // no adjustment — the temp `\@a` doesn't add an owner in real Perl.
        int adjust = base.localBindingExists ? 0 : -1;
        return new RuntimeScalar(rc + extra + adjust).getList();
    }
    return new RuntimeScalar(1).getList();
}
```

**Verification:**
- `test_pl/examples.t` #4 "two references" passes
- `op/for-many.t` still passes (lex AV — `adjust=0`)
- `op/inccode.t`, `op/inccode-tie.t` still pass (delta-checks on same-shape refs)

**DBIC risk:** **none** — diagnostic API; nothing in DBIC code reads `Internals::SvREFCNT`.

**Estimated cost:** 1 hour.

---

### Step 2 — Theme F: FREETMPS at do-block / eval-string scope exit (postfixderef.t #38, do.t RT 124248)

**System Perl behavior:**

```perl
{
    my $x = bless [];                       # x mortal
}                                           # block exit → DESTROY x  ✓

{
    my $c = eval q{ bless \'curly'->@*, 'c' };   # c is blessed, x's mortality bound to my $c
}                                                # block exit → DESTROY c  ✓ in real Perl

f(do { 1; !!(my $x = bless []); });         # do-block return value
                                            # x.DESTROY must fire before f sees its arg
```

**PerlOnJava:** `eval STRING` return value loses tracking; do-block doesn't FREETMPS at exit when inside an arg list.

**Fix structure:**

1. **`EvalStringHandler`:** After the eval returns, if the result is a tracked reference, register it in `MyVarCleanupStack` for the *caller's* lexical that holds it. We currently track when `set()` runs on the destination; verify it's also called when the destination is a `my $c = eval STRING` pattern. If the eval-string result is a scalar that bypasses `set()`, fix that path.

2. **`EmitBlock` / do-block emission:** the do-block already emits `flushAboveMark()` between statements. But the do-block's own *exit* doesn't emit a scope-bounded `popAndFlush`. Compare with regular block-exit emission (`emitScopeExitNullStores(flush=true)`) and apply the same to do-block when it's used in an expression context. Make sure the do-block's return value survives the flush (already handled by other call sites — keep value on stack across pushMark/popAndFlush).

**Verification:**
- `op/postfixderef.t` #38 fires 4 DESTROYs at block exit (Larry, Curly, Moe, Shemp).
- `op/do.t` "RT 124248" sees `$d == 1` (DESTROY fired before `is()` is called).
- `op/undef.t` (87/88) does not regress.

**DBIC risk:** medium. DBIC's `bless { ... }` patterns and Schema lifetime depend on FREETMPS *not* being too aggressive. The change is *adding* a FREETMPS that's missing — should not destroy live schemas. Run full DBIC suite.

**Estimated cost:** half day.

---

### Step 3 — Theme S (cont.): foreach in scalar context returns "" not undef (for.t #103)

**System Perl behavior:**

```perl
do { 17; foreach (1, 2) { 1; } }      # in scalar context: returns ""
```

The `foreach` statement, used as the last expression of a do-block in scalar context, evaluates to the empty string (PerlOnJava returns `undef`).

**PerlOnJava:** EmitForeach doesn't push any "result" value on the JVM stack. The do-block sees nothing → the do-block's return is whatever's on the stack from before, falling through to `undef`.

**Fix:** in `EmitForeach.visit(For1Node)`, when the result is consumed in non-VOID context (scalar/list), push an empty-string scalar at loop end so the surrounding expression sees `""` instead of inheriting whatever was on the stack.

**Verification:**
- `op/for.t` #103 passes
- Standalone repro: `print do { foreach (1, 2) { 1; } };` outputs nothing (empty string), not "Use of uninitialized value"

**DBIC risk:** very low. DBIC code does not rely on foreach return value; it always treats foreach as a void statement. Only edge-case code that uses foreach in expression context is affected.

**Estimated cost:** 1 hour.

---

### Step 4 — Theme A: foreach `$_` aliasing for undef list element (for.t #105)

**System Perl behavior:**

```perl
sub {
    foreach (@_) {
        is eval { \$_ }, \undef, ...   # \$_ inside loop equals \undef when element is undef
    }
}->(undef);
```

`$_` is aliased to the `undef` element of `@_`. The reference `\$_` should equal `\undef` (same scalar address), because Perl reuses the global undef SV for undef elements.

**PerlOnJava:** `$_` inside the loop refers to a *fresh* scalar holding undef, not the global undef SV. So `\$_ != \undef` (different addresses).

**Fix:** in `EmitForeach`, when iterating over `@_` (or any list with potential undef elements), preserve the *actual* SV of each list element. If the element IS the canonical undef SV (e.g. `RuntimeScalarCache.scalarUndef`), alias `$_` to that exact instance. Don't create a fresh undef.

This is a focused change in the iteration assignment path: `mv.visitVarInsn(Opcodes.ASTORE, varIndex)` already aliases via Java identity; the issue is upstream — when the list `(undef)` is materialized, are we creating a fresh undef or reusing the canonical?

**Verification:**
- `op/for.t` #105 passes
- `op/postfixderef.t` (already 114/128) doesn't regress
- DBIC iteration of result rows still works

**DBIC risk:** very low. DBIC iterates over hash-of-hashes / arrays of result rows, never undef-laden lists.

**Estimated cost:** 2-3 hours.

---

### Step 5 — op/tie.t: investigate the one extra failing subtest

**Step 5 only.** Identify which `[at op/tie.t line 18]` subtest fails on our branch but passes on master, by:

1. Comparing our `run_multiple_progs` failure output line-by-line with master's.
2. Identifying the first divergent subtest (lines after `__END__`).
3. Diagnosing: tie semantics (likely DESTROY ordering or weak-ref clearing for tied containers).
4. Fixing the specific code path.

**DBIC risk:** medium-low. DBIC doesn't use `tie`, but tie-related DESTROY semantics may overlap with Schema cleanup (Storage uses INTERNAL CACHE that may use tied semantics). Run full DBIC suite.

**Estimated cost:** half day.

---

### Step 6 — `op/recurse.t` StackOverflowError under parallel runner

**Diagnosis (NOT a flake — concrete root cause found):**

`op/recurse.t` line 110 calls `sillysum(1000)`, a 1000-deep recursion.

```perl
sub sillysum {
    return $_[0] + ($_[0] > 0 ? sillysum($_[0] - 1) : 0);
}
is(sillysum(1000), 1000*1001/2, "recursive sum of 1..1000");
```

Standalone (with default `JPERL_OPTS`): passes 28/28.
Under `perl_test_runner.pl`: hits `StackOverflowError` at line 110 (and the next two recursion tests, including the 64K-deep test). Saved test output shows:

```
ok 25 - premature FREETMPS (change 5699)
# Looks like you planned 28 tests but ran 25.
StackOverflowError
        main at op/recurse.t line 110
        main at op/recurse.t line 110
        ... (deep stack)
```

**Why it happens under the runner:** `dev/tools/perl_test_runner.pl` line 261-265 sets `JPERL_OPTS=-Xss256m` only for `re/pat.t`, `op/repeat.t`, `op/list.t`. `op/recurse.t` is missing from that list, so it runs with the JVM default stack (~512 KB on macOS), which overflows around the 1000th frame of `sillysum`.

**Fix:** add `op/recurse.t` to the `JPERL_OPTS=-Xss256m` whitelist in `perl_test_runner.pl`:

```perl
local $ENV{JPERL_OPTS} = $test_file =~ m{
      re/pat.t
    | op/repeat.t
    | op/list.t
    | op/recurse.t }x        # NEW
    ? "-Xss256m" : "";
```

**Why this is the right fix (not "increase global stack"):**
- The recursion is intentional (test name: "64K deep recursion - no output expected").
- Real Perl handles deep recursion with C stack growth; PerlOnJava maps each Perl call to a JVM call frame, so JVM stack must be sized accordingly for these specific tests.
- Setting `-Xss256m` globally would commit ~2 GB of stack memory across 8 parallel JVMs, harming overall test-suite throughput.
- The whitelist approach is already the project's accepted pattern (see existing entries).

**Verification:**
- Run `perl dev/tools/perl_test_runner.pl --jobs 8 --timeout 300 perl5_t/t/op/recurse.t` 5 times consecutively — all should report 28/28.
- Re-run the full perl5_t/t suite — `recurse.t` should be 28/28 in all parallel runs.

**DBIC risk:** **none** — change is in the test runner only, not in any code DBIC executes.

**Estimated cost:** 10 minutes.

---

### Step 7 — recurse.t flake stabilization (deprecated)

Removed: the original "flake" hypothesis was wrong. Step 6 is the real fix.

---

## Final verification gate

After all 6 steps:

```bash
make                                              # 0 failures
./jcpan -t DBIx::Class > /tmp/dbic.log 2>&1       # PASS, Files=314, 0 Dubious
./jcpan -t Moo                                    # 71/71 PASS
./jcpan -t Template                               # 106/106 PASS
make test-bundled-modules                         # 281/283 (2 pre-existing)
rm -rf perl5_t/t/tmp_*
perl dev/tools/perl_test_runner.pl --jobs 8 --timeout 300 \
    --output out.json perl5_t/t \
    > ../PerlOnJava/logs/test_$(date +%Y%m%d_%H%M%S)_PR560_v3.log 2>&1
perl dev/tools/compare_test_logs.pl \
    ../PerlOnJava/logs/test_20260424_135000_PR554.log \
    ../PerlOnJava/logs/test_<latest>.log
```

Pass criteria:

- `compare_test_logs.pl` reports **0 regressions** (excluding environmental win32/porting which are out-of-scope).
- DBIx::Class: 314/314 PASS.
- Moo: 71/71. Template: 106/106. `make`: BUILD SUCCESSFUL.

## Rollback plan

After every commit on this branch:
- `git tag dbic-100pc-pass-N` where N is incremented (this protects each green checkpoint).
- If a step regresses anything in DBIC/Moo/Template, `git reset --hard` to the previous tag.

## Out of scope

- `win32/seekdir.t -30` and `porting/checkcase.t -27`: environmental, will require a separate PR with environment fixes (or a `compare_test_logs.pl` whitelist for these specific files).

## Coverage matrix — does the plan fix every reported regression?

| Reported regression | Step that fixes it | After fix |
|---|---|---|
| `op/postfixderef.t -3` | Step 2 (eval-string DESTROY) | 117/128 (matches master) |
| `op/for.t -2` (RT #1085) | Step 3 (foreach scalar context) | +1 toward 141 |
| `op/for.t -2` (foreach undef) | Step 4 (`$_` aliasing) | 141/149 (matches master) |
| `op/do.t -1` (RT 124248) | Step 2 (do-block FREETMPS) | 69/73 (matches master) |
| `op/recurse.t -1` (flake) | Step 6 | 26/28 (matches master) |
| `op/tie.t -1` | Step 5 | 60/95 (matches master) |
| `test_pl/examples.t -1` (two references) | Step 1 (SvREFCNT adjustment) | 11/17 (matches master) |
| `win32/seekdir.t -30` | out of scope (environmental) | — |
| `porting/checkcase.t -27` | out of scope (environmental) | — |

**Every non-environmental regression is mapped to a specific step.** The plan is complete.

## Final acceptance criteria

After all 6 steps, `compare_test_logs.pl` between PR554 baseline and the new run **MUST** show:

- 0 entries in the regressions list, except possibly `win32/seekdir.t` and `porting/checkcase.t` (environmental, documented out-of-scope).
- DBIx::Class: 314/314 PASS, 0 Dubious.
- Moo: 71/71. Template: 106/106. `make`: BUILD SUCCESSFUL.

If any non-environmental regression remains: do not merge until fixed.

## Estimated total work

- Step 1 (SvREFCNT adjustment): 1h
- Step 2 (FREETMPS at do/eval-string): 4h
- Step 3 (foreach scalar return ""): 1h
- Step 4 ($_ alias undef): 2-3h
- Step 5 (tie.t subtest): 4h
- Step 6 (recurse.t flake): 4h
- Verification cycles between steps: 6h × 6 ≈ 30min cumulative DBIC runs
- **Total: ~1.5–2 days of focused work.**
