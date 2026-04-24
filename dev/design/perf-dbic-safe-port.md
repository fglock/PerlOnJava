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
   ~~These are post-merge commits that happen to be useful independently of
   the DBI 1.647 upgrade — they don't regress when applied on top of the
   reverted minimal DBI:~~
   - ~~`07b961dd4` fix(DBI): tolerate setReadOnly() rejection on JDBC drivers~~
     ~~that disallow it~~
   - ~~`cdf400cbc` fix(DBD::SQLite): set `$dbh->{Driver}` back-reference~~

   **STATUS (2026-04-24): SKIPPED — both commits turned out to be
   no-ops against the reverted baseline.** The pre-merge minimal
   `DBI.java` already guards `setReadOnly` in a try/catch (the
   current behavior at `e8b0a7f4a` matches `07b961dd4`'s intent),
   and the pre-merge `DBD/SQLite.pm` already sets
   `$dbh->{Driver}` correctly (the current behavior matches
   `cdf400cbc`'s intent). Both `git cherry-pick` attempts ended in
   either a conflict where "ours" was already equivalent, or an
   empty commit — no actual change to apply. Moving to step 3.

3. **Update `dev/architecture/weaken-destroy.md`** ✓ DONE (2026-04-24).
   Updated status header (DBIC 13858/13858, Template 2935/2935) and
   added a "2026-04-24 touch-ups on `perf/dbic-safe-port`" section
   documenting all three refcount/scope-exit/stash/alias fixes:
   - `17abda575` scope-exit LIFO revert
   - `aa8287f1a` CORE::GLOBAL::require delete+restore fix
   - `73bc6b4d8` `our` alias inheritance into eval STRING

4. **Disable `module/Net-SSLeay/t/local/01_pod.t`.** ✓ DONE (2026-04-24).
   Commit `761f1c9cf`: added `SKIPPED_MODULE_TESTS` set in
   `ModuleTestExecutionTest.java` with `module/Net-SSLeay/t/local/01_pod.t`
   as the first (and so far only) entry. Note: 01_pod.t already had
   `plan skip_all`, so it wasn't actually failing — the skip list
   codifies the intent and gives us a mechanism for future false-alarm
   entries.

5. **Complete the secondary tests: `make test-bundled-modules`.**
   ✓ DONE (2026-04-24). 2 failures remain:
   - `module/Net-SSLeay/t/local/33_x509_create_cert.t` (Crypt::OpenSSL::Bignum
     exponent returning `17` instead of `65537` — real bug)
   - `module/Text-CSV/t/55_combi.t` (subtest 26 content mismatch — real bug)
   Both added to the "Followup" section at the bottom of this doc.

6. **If step 5 still fails, add a follow-up phase to this plan:**
   ✓ DONE (commit `ddc869b6c`) — both real failures documented in the
   followup section with reproduction/fix-plan notes. Not in this PR's
   scope.

7. **Review and refresh any outdated documentation** (AGENTS.md, design
   docs, README snippets) touched by the branch's scope. ✓ DONE
   (commit `ddc869b6c`):
   - `dev/modules/dbi_test_parity.md` now has a top-of-file note saying
     the upstream-DBI-switch work is reverted on `perf/dbic-safe-port`.
   - AGENTS.md was already updated in commit `a1bace135` (remove
     `make dev` reference).

8. **Push again** after steps 3-7. ✓ DONE (2026-04-24, tip `ddc869b6c`
   pushed to origin).

9. **Rebase with master.** Resolve conflicts as "ours" for the three
   DBI files (DBI.pm, DBI.java, DBI/PurePerl.pm — the last will stay
   deleted). Keep everything else from master. This re-validates that
   our branch can merge forward cleanly.

10. **Re-run the full battery after the rebase:** ✓ DONE (2026-04-24).
    - `make` (unit tests) — BUILD SUCCESSFUL
    - `./jcpan -t Moo` — PASS (841/841)
    - `./jcpan -t Template` — PASS (2935/2935)
    - `./jcpan -t DBIx::Class` (full 314 files) — PASS (13858/13858, 0 Dubious)
      *after* narrowing master commit `7f3e0d12d`'s stash-alias
      canonicalisation. The broad version (in `bless`) caused ~29
      Dubious failures via "detached result source (source 'CD' is
      not associated with a schema)"; the narrow version (only in
      `isa`, with both directions handled) keeps DBIC green.
      See commit `e9bb4cb9c` for the fix.
    - `./jcpan -t JSON` — 1 Dubious (`t/13_limit.t`). This is identical
      before and after `e9bb4cb9c`, so it is *not* a regression from
      the alias fix. Tracked in the followup section as a separate
      bug to investigate.
    - `make test-bundled-modules` — 2 pre-existing failures already
      documented (Net-SSLeay `33_x509_create_cert.t`,
      Text-CSV `55_combi.t`). No new regressions from the merge.

11. **Fix any regressions** introduced by the rebase and repeat step 10.
    ✓ DONE (commit `e9bb4cb9c` is the fix for the single real merge
    regression — see step 10). JSON `t/13_limit.t` moved to followup.

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

- **Real bundled-module bugs surfaced by `make test-bundled-modules`.**
  Neither is a false alarm — both reflect real code paths that can
  affect user programs. They are intentionally NOT hidden in the skip
  list; track and fix in a dedicated follow-up phase/PR.

  1. `module/Net-SSLeay/t/local/33_x509_create_cert.t` — 139/141
     subtests pass. The 2 failures are:
     ```
     Failed test 'Crypt::OpenSSL::Bignum exponent once'
       at t/local/33_x509_create_cert.t line 42.
            got: '17'
       expected: '65537'
     Failed test 'Crypt::OpenSSL::Bignum exponent twice'
       at t/local/33_x509_create_cert.t line 47.
            got: '18'
       expected: '65537'
     ```
     The test pulls an RSA key's public exponent out via
     `Crypt::OpenSSL::Bignum` and calls `->to_hex` (or similar) on it.
     The canonical RSA public exponent is `65537` (`0x10001`).
     Getting `17` / `18` instead smells like a decimal-vs-hex
     stringification bug, or the Bignum is being truncated /
     interpreted as a small int somewhere. Likely a narrow fix in the
     Java-backed `Crypt::OpenSSL::Bignum` emulation (or whichever
     Perl module provides that interface in PerlOnJava).
     Fix plan: reproduce with a tiny test (`my $e = RSA key's e;
     print $e->to_hex;`); locate the stringification path; align
     with real OpenSSL behavior.

  2. `module/Text-CSV/t/55_combi.t` — subtest 26 fails (`not ok 26 -
     content`). The test generates large numbers of CSV input
     variations (hence the 16-second runtime) and is specifically
     designed to catch obscure combinatorial CSV edge cases. Failure
     mode to capture in the fix plan: narrow down which exact
     combination fails (quote/escape/separator permutation).
     This can affect user programs that parse CSV with unusual
     configurations.

  Acceptance: full `make test-bundled-modules` green (0 failures,
  no entries in the skip list beyond the current 01_pod.t false
  alarm).

- **JSON `t/13_limit.t` dubious on `jcpan -t JSON`.** Wstat 65280
  (exited 255), "Bad plan. You planned 11 tests but ran 1/2" — the
  test process dies partway. From the log context, the failure
  site is around `JSON::PP` line 849 / 1030 (repeated stack lines,
  likely an infinite recursion or limit-test that terminates the
  interpreter). Present both before and after the stash-alias fix
  (`e9bb4cb9c`), so it is NOT caused by the alias work. Likely a
  real PerlOnJava limit / recursion / stack bug exercised by JSON's
  deliberately-pathological `13_limit.t` inputs. Investigate and
  fix in a follow-up; for now JSON is 67/68 tests green with one
  dubious file.
