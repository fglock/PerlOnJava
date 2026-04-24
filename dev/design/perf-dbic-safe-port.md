# DBIC-Safe Perf Port Plan

## Goal
Apply performance optimizations from commits between `99509c6a0` (DBIC clean, slow)
and `1c79bbc7b` (fast, DBIC broken) onto a branch based on `99509c6a0`, without
breaking DBIx::Class.

## Baselines to establish (on branch, pre-cherry-pick)
- `./jperl examples/life_bitpacked.pl` → expect ~5.34 Mcells/s
- Fast DBIC indicator set (the 8 user-flagged tests); expect all PASS on 99509c6a0
- `./jcpan --jobs 8 -t Template` → **known broken on 99509c6a0** (passes on 1c79bbc7b)
  - Use as a secondary signal — some fixes between the two branches repair Template
- Full `./jcpan -t DBIx::Class` → PASS (already confirmed)

## Fast DBIC indicator set (user-provided)
```
t/96_is_deteministic_value.t   # Sub::Defer trampoline hang
t/resultset/as_subselect_rs.t  # Sub::Defer trampoline hang
t/search/select_chains.t       # Sub::Defer trampoline hang
t/storage/error.t              # Sub::Defer trampoline hang
t/storage/txn.t                # #89 nested failed txn_do
t/debug/pretty.t               # missing Parse::RecDescent dep (env issue)
t/52leaks.t                    # END-phase captureCount++ leak
t/zzzzzzz_perl_perf_bug.t      # cascade from Sub::Defer hang
```

Note: 4 of 8 failures share "Sub::Defer trampoline hang" root cause — they'll
almost certainly move together. `t/storage/txn.t` subtest 89 and `t/52leaks.t`
END-phase are distinct signals.

## Commits in scope (perf-labelled)

Low-risk (no obvious DBIC coupling):
  886e7498b  cache PerlRuntime.current() in local variables            (Tier 1)
  d070812cd  migrate ThreadLocal stacks to PerlRuntime instance fields (Tier 2)
  4a3b07287  batch push/pop caller state
  c33d7c828  batch RegexState save/restore
  b84ee499c  skip RegexState save/restore for regex-free subs
  fa8df8a2a  skip RegexState allocation when no regex has matched yet
  d4ddb7043  JVM-compile anonymous subs inside eval STRING
  f4d474a40  cache System.getenv() in hot paths as static final
  17527e8e7  cache warning bits + empty-args snapshot fast path
  660aa9e68  avoid autoboxing in RuntimeScalar(long) + bitwise fast paths
  1400475d3  avoid per-lookup CacheKey allocation in NameNormalizer

High-risk (touches Phase I GC/cleanup infra — primary DBIC suspects):
  2fb0bd129  gate ScalarRefRegistry.registerRef() on weakRefsExist
  a7165f711  gate MyVarCleanupStack.liveCounts on weakRefsExist
  ea7c66811  JPERL_CLASSIC gate / cumulative-tax investigation
  31caba56a  gate MyVarCleanupStack.unregister emission per-sub
  40e19e7a8  skip MyVarCleanupStack.register emission for simple subs

Related non-perf but perf-adjacent (may matter):
  4a1ad046b  fix(closure): track captureCount for named subs (mentioned in
             t/52leaks.t failure note!)
  1c79bbc7b  fix(B): B::NULL is terminal — return undef from all accessors

## Strategy
1. Branch `perf/dbic-safe-port` from 99509c6a0.
2. Cherry-pick low-risk commits as one batch. Verify:
   - make (unit tests)
   - life_bitpacked Mcells/s (log delta)
   - fast DBIC indicator set (all PASS)
   - (optionally `./jcpan --jobs 8 -t Template`)
   If anything breaks: bisect within batch to find offender; skip/fix.
3. Cherry-pick high-risk commits one at a time. Same verify per commit.
   If a commit breaks DBIC: read the commit, try to either
     (a) tighten the gate so it's DBIC-safe, or
     (b) skip & document why.
4. Final: full `./jcpan -t DBIx::Class` to confirm all 314 files PASS.
5. Open WIP PR.

## Non-goals
- No DBIC test edits.
- No push to master.
- No changes outside the perf commit set unless required to fix regressions
  from a cherry-pick.

## Tracking

See branch `perf/dbic-safe-port` and progress log appended below.

## Progress log

### Branch: `perf/dbic-safe-port` (from 99509c6a0)

| Step | Commit | On branch as | life_bitpacked Mcells/s | DBIC (8-test indicator set) | Unit tests |
|------|--------|--------------|-------------------------|-----------------------------|-----------|
| baseline | (none, @ 99509c6a0) | — | 5.6 | 8/8 PASS (full suite) | PASS |
| 1 | 2fb0bd129 ScalarRefRegistry.registerRef gate | e14adedee | 6.45 (+15%) | — | — |
| 2 | a7165f711 MyVarCleanupStack.liveCounts gate | 8edd81758 | 8.17 (+46%) | 8/8 PASS | — |
| 3 | 31caba56a MyVarCleanupStack.unregister emission gate | d256ede8f | 8.53 (+52%) | — | — |
| 4 | 40e19e7a8 Phase R register skip | 3450ed987 | **13.27 (+137%)** | **8/8 PASS** | **PASS** |

### Conflict resolutions
- `Configuration.java`: trivial — kept HEAD (build auto-regenerates).
- `EmitterMethodCreator.java` (step 3): accepted incoming block that adds
  `FORCE_CLEANUP` + env-caching constants (ASM_DEBUG, SPILL_SLOT_COUNT, etc.).
  These constants are defined but most call sites still use `System.getenv()`
  inline (those call-sites are migrated later by f4d474a40 which we haven't picked).
  No behavioural change; dead code at worst.
- `EmitVariable.java` (step 4): dropped `!MortalList.CLASSIC` from the
  Phase-R gate. The `CLASSIC` flag was introduced by ea7c66811 (measurement-
  only investigation commit we are explicitly not picking). Dropping it just
  removes a never-used off-path; Phase-R gate becomes purely
  `operator.equals("my") && cleanupNeeded`.

### Result
Target: 11.69 Mcells/s (from 1c79bbc7b). **Achieved: 13.27 Mcells/s.** Target
met with 4 cherry-picks, zero DBIC regressions in the user's 8-test indicator
set. Full DBIC suite run pending.

### Phase 2 candidates (optional additional wins)
- 17527e8e7 warning-bits cache + empty-args snapshot (reported +5–10%)
- fa8df8a2a RegexState.EMPTY singleton (1–3% typical, up to 17% on
  refcount-heavy benches)
- 660aa9e68 no-autobox RuntimeScalar(long) (0%, noise — maybe skip)
- 1400475d3 NameNormalizer CacheKey removal (unquantified)
- f4d474a40 System.getenv() caching (unquantified; would also remove the dead
  constants we accepted in step 3 by migrating their call sites)

Not on Phase 2 list:
- 4a1ad046b fix(closure) captureCount — user flagged as 52leaks.t regressor
- ea7c66811 JPERL_CLASSIC — measurement only
- ThreadLocal-consolidation chain (886e7498b etc.) — big on closure/method
  microbench but not life_bitpacked; would need whole chain picked together.


---

## Follow-up after PR #552 merges

### PerlOnJava quirk: user `sub do` in a package isn't callable via name

**Symptoms** (minimal repro):
```perl
package MyClass;
sub do { return "custom do" }
package main;
print defined(&MyClass::do) ? "YES\n" : "NO\n";  # NO (on PerlOnJava)
print \&MyClass::do, "\n";                        # CODE(0x...)  (ref returned!)
MyClass::do();                                    # dies: Undefined subroutine
```

Real Perl returns `YES`, `CODE(...)`, prints `"custom do"`.

**Impact hit during the DBIC rebase:** we wanted to override `sub do` in
`DBD::JDBC::db` to call `$sth->finish` and release JDBC locks (fixes
`t/storage/on_connect_do.t#8`). Glob-alias workaround
(`*DBD::JDBC::db::do = \&_do_impl`) reproduces the same quirk — the
symbol-table slot appears populated (`\&…do` returns a coderef,
`UNIVERSAL::can("do")` finds it) but user-code dispatch falls through
to "Undefined subroutine".

**Suspected cause:** our parser / code-generator special-cases the `do`
keyword (for `do FILE` / `do BLOCK` forms) at a level that precedes
normal method/package dispatch, so identifiers named `do` in a package
context are routed to the builtin parser instead of the user stash
slot. Likely similar to the constraints real Perl has on `INIT`,
`AUTOLOAD`, `DESTROY`, etc. — but we allow those.

**Fix candidates (not yet attempted):**
1. In the parser's `parsePackageMethodCall` / equivalent, check the
   package's stash for `do` *before* falling back to the builtin.
2. Let `sub do {...}` in a package context register normally, and
   re-route `$obj->do(...)` dispatch through that slot.
3. Provide an explicit escape like `$obj->${\"do"}(...)` that already
   works today — but that's a workaround, not a fix.

**Workarounds currently in use:**
- DBD::JDBC code uses `_do_impl` + glob alias; the glob alias happens
  to *not* fix the dispatch issue, so `sub do` for `$dbh->do(...)` is
  effectively unpatched. This leaves DBIC `t/storage/on_connect_do.t#8`
  failing with "database table is locked" on file-backed SQLite until
  this quirk is resolved.

---

## Next Steps (2026-04-24, post-DBI-revert)

Current state: `perf/dbic-safe-port` at `e8b0a7f4a`, 5 commits ahead of origin.

### Commits on branch (ahead of origin/master)
- `e8b0a7f4a` revert(DBI): roll back upstream DBI 1.647 + PurePerl
- `73bc6b4d8` fix(eval-string): preserve `our` aliases across inner `package` change
- `aa8287f1a` fix(stash): preserve CORE::GLOBAL::require across delete+restore round-trip
- `a1bace135` build: remove `make dev` target
- `17abda575` revert(scope-exit): drop bca73bd5's LIFO reverse
- Plus 4 perf phase commits and all non-DBI post-merge fixes inherited from earlier work

### Verified state at `e8b0a7f4a` (measurements)
- **`make`**: BUILD SUCCESSFUL (all unit-test shards green)
- **DBIx::Class full suite** (`./jcpan -t DBIx::Class`):
  `Files=314, Tests=13858, Result: PASS` — 0 Dubious, 0 "not ok" — ~24 min wallclock
- **Moo** (`./jcpan -t Moo`):
  `Files=71, Tests=841, Result: PASS` — 0 Dubious
- **Template** (`./jcpan -t Template`):
  `Files=106, Tests=2935, Result: PASS` — 0 Dubious
- **Perf**: ~11.8–12.1 Mcells/s (above the 11.69 Mcells/s target from `1c79bbc7b`)

### Ordered next steps

1. **Push `perf/dbic-safe-port` to origin and update PR #552 FIRST.**
   - This is IMPORTANT and intentionally step 1: it gives us a safe
     backup on GitHub BEFORE any further changes (cherry-picks, doc
     updates, rebases) that could regress the current known-PASS state.
   - Document the CURRENT commit (`e8b0a7f4a`) as the "PASS" reference
     point and record the measurements above in the PR body so
     reviewers can reproduce the result.
   - Reference this design doc and the revert commit message for the
     rationale on rolling back DBI 1.647.

2. **Cherry-pick the two generally-useful fixes on top of the reverted DBI.**
   These are post-merge commits that happen to be useful independently of
   the DBI 1.647 upgrade — they don't regress when applied on top of the
   reverted minimal DBI:
   - `07b961dd4` fix(DBI): tolerate setReadOnly() rejection on JDBC drivers
     that disallow it — useful for any JDBC driver that rejects readOnly.
   - `cdf400cbc` fix(DBD::SQLite): set `$dbh->{Driver}` back-reference
     after JDBC connect — fixes a symbol-table hole used by `DBI::_new_dbh`
     consumers.
   Verify each cherry-pick with `make`, then `./jcpan -t DBIx::Class` (or
   at least `dbic_fast_check.sh`) to confirm no regression from the
   already-PASSING state. Drop a commit if it doesn't apply cleanly or
   if it pulls in too much surrounding change — these are opportunistic,
   not required.

3. **Update `dev/architecture/weaken-destroy.md`** to reflect any behavior
   the recent refcount / scope-exit / stash fixes touched. Very important
   to keep this in sync — the file is the canonical description of our
   refcount-over-JVM-GC model and is referenced from AGENTS.md.
   Specifically:
   - The `bca73bd5` LIFO logic revert (scope exit cleanup ordering is back
     to relying on HashMap iteration order that empirically matches
     Perl 5's reverse-declaration LIFO).
   - The `aa8287f1a` stash fix for CORE::GLOBAL::require round-trip via
     `delete` + re-assign (detached-glob slot re-installation path).
   - The `73bc6b4d8` `our` alias inheritance into eval STRING via
     `ScopedSymbolTable.snapShot` + `BytecodeCompiler` parent-our-packages.

4. **Disable `module/Net-SSLeay/t/local/01_pod.t`.**
   - This is a pre-existing pod-coverage author test — false alarm,
     not a real bug. Add it to whichever skip list `make test-bundled-modules`
     uses (likely a `.gitignore`-style config under `src/test/resources/module/Net-SSLeay/`
     or the Gradle `testModule` task).

5. **Complete the secondary tests: `make test-bundled-modules`.**
   - Verify on the current branch tip, after skipping 01_pod.t.

6. **If step 5 still fails, add a follow-up phase to this plan:**
   - Fix `module/Text-CSV/t/55_combi.t` — this IS a real failure that
     could affect user programs. Worth a focused debug session. Do not
     leave it as a known-fail indefinitely.

7. **Review and refresh any outdated documentation** (AGENTS.md, design
   docs, README snippets) touched by the branch's scope. Examples:
   - DBI.pm now pre-merge minimal (~830 lines) — any doc referencing
     "DBI 1.647" should be updated to say we're on the pre-merge
     purpose-built DBI until a proper upgrade lands.
   - `make dev` removal note in AGENTS.md is already done.

8. **Push again** after steps 3-7 (incremental updates to the PR).

9. **Rebase with master.** Resolve conflicts as "ours" for the three
   DBI files (DBI.pm, DBI.java, DBI/PurePerl.pm — the last will stay
   deleted). Keep everything else from master. This re-validates that
   our branch can merge forward cleanly.

10. **Re-run the full battery after the rebase:**
    - `make` (unit tests)
    - `./jcpan -t Moo`
    - `./jcpan -t Template`
    - `./jcpan -t DBIx::Class` (full 314 files)
    - `make test-bundled-modules`

11. **Fix any regressions** introduced by the rebase and repeat step 10.

12. **Final push.**

13. **Hand off to user** for their final validation tests (whatever
    environment-specific checks they want to run: user's own scripts,
    larger integration tests, perf benchmarks on their machine, etc.).

### Followup (separate PR/issue, NOT in this PR's scope)

- **Proper DBI 1.647 migration**: bring back the upstream DBI with the
  PurePerl `connect`/`Active` bug fixed so DBIx::Class passes unmodified.
  Re-apply the generally-useful subset of the reverted fix commits
  (07b961dd4 setReadOnly, cdf400cbc SQLite Driver backref, ddfcd9771
  HandleError ordering) on top of a working 1.647 base.
