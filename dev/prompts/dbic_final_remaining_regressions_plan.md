# Plan: Eliminate remaining regressions — minimum DBIC risk approach

**Branch:** `feature/dbic-final-integration` at `b425526ed`
**Tag:** `dbic-100pc-pass-2`
**Constraint:** project rules forbid merging with regressions. **Must fix all real regressions.**
**Absolute hard floor:** DBIx::Class 314/314 PASS, Moo 71/71, Template 106/106, `make` BUILD SUCCESSFUL — **never** break these.

---

## Status as of tag `dbic-100pc-pass-2`

`./jcpan -t DBIx::Class`: **314/314 PASS, 0 Dubious** ✓
`./jcpan -t Moo`: **71/71** ✓
`./jcpan -t Template`: **106/106** ✓
perl_test_runner vs PR554: **+347 net passing tests**.

Already-fixed in this session: `op/recurse.t`, `op/for.t`, `test_pl/examples.t` (now BEAT master). 4 unique-by-name regressions remain.

---

## Remaining regressions

```
✗ op/postfixderef.t    117/128  → 114/128  -3    1 unique failure (test #38) + 2 numbering knock-ons
✗ op/do.t              69/73    → 68/73    -1    RT 124248
✗ op/stat.t            107/111  → 106/111  -1    runner flake — passes 106/111 standalone
✗ op/tie.t             60/95    → 59/95    -1    test #20: parser error message format
```

(`win32/seekdir.t` and `porting/checkcase.t` are environmental, out of scope.)

---

## Lessons learned from prior plan execution

1. **Anything that flushes the mortal pending list mid-statement risks DBIC.** Step 2 attempt to re-enable do-block FREETMPS broke `t/60core.t` (14 tests). Even the scope-bounded `pushMark+popAndFlush` is risky.
2. **Diagnostic-only changes** (`Internals::SvREFCNT`) are zero-risk.
3. **Test-runner config changes** are zero-risk.
4. **Parser changes** are low risk (don't touch runtime mortal flow).
5. **Per-variable scope-exit treatment** (touching only specific lexicals, not the global pending list) is plausible but needs careful design.

---

## Revised strategy — order tests by risk

For each remaining regression, identify the **least invasive fix** that doesn't go near the mortal-pending-list semantics that DBIC depends on.

### Step A — `op/stat.t` flake (zero-risk fix)

**Problem.** Compare-log shows -1 vs PR554 for `op/stat.t`, but standalone runs of master AND ours both produce 106/111 with identical failing tests (45 `-t`, 46 `tty is -c`, 48 `-t on STDIN`, 52 `-B`, 53 `!-T`). The "regression" is a runner artifact: `-t STDIN` returns different values depending on whether prove allocated a tty for the subprocess.

**Fix.** Document in `compare_test_logs.pl` (or its consumer) that `op/stat.t` is a known parallel-runner-tty flake. Either:
- (a) Add `op/stat.t` to a tty-flake whitelist in `compare_test_logs.pl` so it doesn't trip the merge gate, OR
- (b) Patch `dev/tools/perl_test_runner.pl` to set `JPERL_DISABLE_TTY_TESTS=1` (or similar) for `op/stat.t` so the tty subtests get TODO'd consistently.

(b) is cleaner. Option (a) is the fallback.

**DBIC risk:** zero — runner-config only.
**Effort:** 30 min.

### Step B — `op/tie.t` test #20 (low-risk parser fix)

**Problem.** Test 20 expects:

```perl
tie FH, 'main';
# Real Perl:    Can't modify constant item in tie at - line 3, near "'main';"
# PerlOnJava:   (parses OK, fails at runtime with "Can't locate object method TIESCALAR")
```

Real Perl rejects a bareword first arg to `tie` at compile time because `tie SCALAR, CLASSNAME, …` requires the first arg to be an lvalue scalar/array/hash/glob — a bareword `FH` isn't.

**Fix.** In the parser path that handles `tie`/`untie`, after parsing the first argument, check if it is a bareword/constant. If yes, emit a compile-time `die` with the exact message "Can't modify constant item in tie".

**Files:** `src/main/java/org/perlonjava/frontend/parser/OperatorParser.java` (or wherever `tie` is parsed). Single conditional check.

**DBIC risk:** zero — DBIC never `tie`s a bareword constant. Adds an early compile-time error for invalid syntax that doesn't affect any valid program.
**Effort:** 1 hour.

### Step C — `op/postfixderef.t` #38 (medium risk; constrained scope)

**Problem.** `eval q{ bless \'curly'->@*, 'coulda' }` returns a blessed array ref. The lexical `$coulda` holds it. At outer block exit, real Perl fires `coulda::DESTROY`. PerlOnJava does not.

The `'curly'->@*` (postfix-array-deref via symref) on a `local`'d array `@curly` produces a reference that gets blessed. The blessed referent is `@curly` (the local'd version — the temporary array Perl creates for `local`). That temporary's DESTROY should fire when the lexical referencing it (`$coulda`) goes out of scope.

**Lowest-risk fix path.** Do NOT touch the mortal flush. Instead:

1. In `bless` (`ReferenceOperators.bless`), when the referent is a tracked container (refCount ≥ 0) and the surrounding scope's `MyVarCleanupStack` is active, register the referent so that scope-exit DOES decrement its refCount and fire DESTROY when zero.

2. Specifically: ensure the blessed referent's refCount-ownership is transferred to the lexical that captures it (`my $coulda = bless ...`) via `setLargeRefCounted`'s existing incref + `MyVarCleanupStack.register`. This already happens for the simple cases — the failing case is when the referent came from `local`-protected storage.

3. Avoid: changing how `local` stores or restores values, or changing flush semantics.

**Implementation approach.**

- Reproduce in isolation. Confirm the failing path is `bless \@local_array, 'class'` returned from `eval STRING` and assigned to a `my` lexical.
- Trace: at the `my $coulda = ...` assignment, does `setLargeRefCounted` see the blessed referent? Does `MyVarCleanupStack.register` get called?
- If `register` is missed for this path, add it.
- After fix, run **`./jcpan -t DBIx::Class`** to confirm DBIC parity. If DBIC degrades, **revert the change** and document this test as known-deferred.

**DBIC risk assessment.**
- DBIC uses `bless { ... }, $class` for Schema, Source, ResultSet objects. These already work.
- The targeted scenario (`bless \@local_array, $class` from eval-string) is rare in DBIC. DBIC code does not use `local`-then-`bless-symref` patterns.
- The fix only adds tracking; it doesn't remove any existing tracking. Safe in principle.
- **Main risk**: if `MyVarCleanupStack.register` triggers earlier-than-expected DESTROY for an object DBIC was relying on staying alive. Mitigated by the existing `localBindingExists` semantics — registered referents are not destroyed while the named binding is alive.

**Effort:** half day (if fix works first try) to a full day (if it requires multiple iterations).

### Step D — `op/do.t` RT 124248 (highest risk; defer)

**Problem.** `f(do { 1; !!(my $x = bless []); })` should fire `DESTROY` for `$x`'s referent before `f`'s body runs. PerlOnJava doesn't.

**Why this is highest risk.**
- Already attempted re-enabling do-block FREETMPS in this session. Result: **DBIC `t/60core.t` failed 14 tests**.
- The pattern `$self->{cursor} ||= do { my $x = ...; create_obj() }` (DBIC) requires that the do-block's return value survive scope exit; flushing the do-block's pending mortals *can* destroy the return value if it shares mortal state with $x.
- Even the scope-bounded `pushMark+popAndFlush` mechanism didn't help, because $x's cleanup at do-block exit ADDS to pending and then popAndFlush drains it — and the JVM stack's return value scalar may have a refcount path through that pending entry.

**Conservative approach (preferred).**

**Defer indefinitely with documentation.** Treat this as an intentional, documented divergence. The cost is one test (`op/do.t` test #70), which tests block-exit FREETMPS — a Perl-internal mechanism that doesn't affect any user-visible behavior except the timing of DESTROY firing. The DESTROY DOES fire eventually (at the next statement boundary), just one statement later than real Perl.

**Aggressive approach (only if Step C succeeds and we have appetite).**

Implement per-my-var "scope-exit DESTROY" without touching the global pending list:

1. At do-block scope exit, walk **only** the my-var slots declared in this scope.
2. For each slot holding a blessed reference where the referent has refCount==1 and is not held elsewhere, fire DESTROY directly (without going through the mortal pending list).
3. Don't decrement other refCounts; don't touch pending; don't touch mortals from outer scopes.

This is a focused per-variable cleanup that NEVER touches DBIC's mortal flow. Implementation requires:
- `ScopeExitDirectDestroy.cleanupMyVars(int scopeIndex)` — new method that iterates the scope's variable indices and fires DESTROY on stand-alone blessed objects.
- Hook into `EmitBlock` for do-block exit to call this method.

**DBIC risk if attempted.**
- Could double-destroy if a value is in BOTH a my-var slot AND the pending list (race with later flush).
- Mitigation: mark destroyed objects so a later flush is a no-op for them (`refCount = Integer.MIN_VALUE`).
- Still: high risk; needs full DBIC verification before commit.

**Recommendation.** **Skip Step D for this PR.** Document `op/do.t` test #70 as known limitation. Move it to a separate follow-up issue.

---

## Order of execution

| # | Step | Effort | DBIC risk | Tests recovered |
|---|---|---|---|---|
| A | stat.t flake (runner config) | 30 min | none | 1 |
| B | tie.t #20 (parser) | 1 hour | none | 1 |
| C | postfixderef.t #38 (bless tracking) | half-full day | medium | 3 (incl knock-on) |
| D | do.t RT 124248 | (deferred) | very high | 1 |

After A+B+C, the regression list shrinks from 4 → 1 (do.t #70).

After A+B (skip C if too risky), 4 → 2 (do.t #70 + postfixderef #38).

---

## Hard rules for execution

1. **Never** modify `MortalList` flush behavior, mark stack, or `popAndFlush` semantics.
2. **Never** add a `MortalList.flush()` or `popAndFlush()` call in a path that touches DBIC's `txn`/`schema`/`storage` flow.
3. **After every commit**: run the full gate:
   ```bash
   make                                  # BUILD SUCCESSFUL
   ./jcpan -t DBIx::Class                # 314/314 PASS, 0 Dubious
   ./jcpan -t Moo                        # 71/71 PASS
   ./jcpan -t Template                   # 106/106 PASS
   ```
4. If **any** of those degrade, immediately `git reset --hard dbic-100pc-pass-2` to roll back.
5. Tag each successful step (`dbic-100pc-pass-3`, etc.) so we always have green checkpoints.

---

## Acceptance criteria

After Steps A + B + C:

- `make`: BUILD SUCCESSFUL
- `./jcpan -t DBIx::Class`: 314/314 PASS, 0 Dubious
- `./jcpan -t Moo`: 71/71
- `./jcpan -t Template`: 106/106
- `compare_test_logs.pl` regression list contains AT MOST: `op/do.t -1` (RT 124248, documented), `win32/seekdir.t`, `porting/checkcase.t`.

Per project rules, `op/do.t -1` is a regression. **Mitigation:**

- Document the limitation in commit message + plan + commit comment in the relevant code area.
- Justification: DBIC parity is the project's primary goal; the cost of fixing is breaking 14 DBIC tests, the benefit is 1 perl5_t test that exercises a Perl-internal mechanism (scope-exit DESTROY timing) not visible to typical user code.
- This is the same kind of trade-off already accepted for the popAndFlush / array-literal-flush questions.

If the project owner insists on **zero** regressions including `op/do.t #70`, then implement Step D's "aggressive approach" with full per-my-var cleanup — but only after extensive DBIC stress-testing, and roll back at first sign of DBIC degradation.

---

## Appendix: why Step C is not "FREETMPS at eval-string scope exit"

The previous plan version proposed a Step 2 that would FREETMPS at do-block AND eval-string boundaries together. Lessons learned:

- **eval STRING is not the bottleneck.** Tracing showed the `eval q{ bless \'curly'->@*, 'coulda' }` path produces a tracked blessed object — `Internals::jperl_refstate` reports it correctly.
- **The bug is at outer block exit**, not at eval-string exit. The blessed referent should be DESTROY'd when its lexical owner (`$coulda`) goes out of scope at the outer block's exit, not at eval-string's exit.
- A FREETMPS-at-eval-string change would be more invasive and have wider side effects, while not actually fixing this case.

So the right surgical fix is in `bless` + `MyVarCleanupStack.register` (Step C), not in eval-string handling.

---

## Out of scope

- `win32/seekdir.t -30`, `porting/checkcase.t -27` — environmental (Windows-specific filesystem; file-count varies per checkout). Document in the merge PR description.
- `op/do.t #70` — deferred (Step D); document as known limitation if Steps A+B+C land cleanly.

---

## Step D investigation notes (post-rebase, 2026-04-26)

### Reproducer

```perl
package p124248;
our $d = 0;
sub DESTROY { $d++ }
package main;
sub f { print "in f, d=$p124248::d (expected 1)\n"; }
f(do { 1; !!(my $x = bless [], 'p124248'); });
```

System Perl: `in f, d=1`. PerlOnJava: `in f, d=0` (DESTROY fires only after `f()` returns).

### Root cause analysis

In `EmitBlock.java` (line ~382), do-blocks are emitted with `flush=false`:

```java
boolean isDoBlock = node.getBooleanAnnotation("blockIsDoBlock");
EmitStatement.emitScopeExitNullStores(ctx, scopeIndex, !isSubBody && !isDoBlock);
```

The comment explains: do-block result may be on the JVM operand stack; a full
`MortalList.flush()` could destroy it before the caller captures it. This is
correct for cases like `do { my $x = ...; $x }` where the result IS the my-var.

But it also suppresses DESTROY for *truly transient* my-vars (like the
`my $x = bless []` inside `!!(...)` above) whose values do **not** escape
the do-block.

### Why naive flush=true breaks DBIC

A previous attempt to set `flush=true` for do-blocks broke
`DBIx::Class t/60core.t` with 14 fails. Pattern in DBIC code:

```perl
$self->{cursor} ||= do { my $x = $self->_create_cursor; $x };
```

Here `$x` IS the do-block's result. Flushing at scope exit would destroy
the cursor before `||=` stores it.

### Possible fix paths (NOT YET IMPLEMENTED)

1. **Result-saved flush.** At do-block exit:
   1. Increment refCount of the result on the JVM stack.
   2. Run `MortalList.flush()` — destroys true transients but the result
      is protected by its bumped refCount.
   3. Decrement refCount back, deferring it to the OUTER scope's MortalList.

   This is essentially what real Perl 5's SAVETMPS/FREETMPS+sv_2mortal does.
   Implementation: in `EmitBlock` after `materializeBlockResult`, emit
   `result.refCount++; flush(); MortalList.deferDecrement(result);`.

2. **Mark-bounded flush via pushMark/popAndFlush.** Only flush entries
   added during the do-block's own execution. Earlier entries (from outer
   expression context) are preserved. Still risks destroying the result
   if the result was added to MortalList by something inside the do-block.

3. **AST analysis.** Detect at compile time whether the do-block's
   syntactic result expression references any of the my-vars declared
   inside. If not, emit `flush=true`. If yes, emit `flush=false`.
   Most conservative; matches DBIC's `do { my $x = ...; $x }` pattern.

   Implementation hint: the do-block's result expression is the last
   statement. Walk it for any IdentifierNode that resolves to a my-var
   declared in the do-block's scope. If found, suppress flush.

### Recommendation

**Path 3 (AST analysis)** is the safest: it gives DBIC's "result IS my-var"
pattern the current behavior, and gives RT 124248's "result is independent"
pattern the FREETMPS behavior. Path 1 is most Perl-faithful but riskier.

### Acceptance criteria for Step D

- `op/do.t` test #70 (RT 124248) passes (69/73 → matches master, no -1
  regression).
- `./jcpan -t DBIx::Class`: 314/314 PASS, 0 Dubious — **must not regress**.
- `./jcpan -t Moo`: 71/71. `./jcpan -t Template`: 106/106.
- `make`: BUILD SUCCESSFUL.
- No new regressions in `compare_test_logs.pl` output.

### Next steps for Step D (when ready)

1. Implement Path 3: add AST visitor to detect "result references inner my-var".
2. In `EmitBlock`, when `isDoBlock` and the AST analysis says "no escape",
   pass `flush=true` to `emitScopeExitNullStores`.
3. Run gates after each commit; tag green checkpoints.
4. If still risky, fall back to Path 1 with extra DBIC stress-testing.
