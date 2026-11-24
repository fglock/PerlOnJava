# Perl-Exact Reference Ownership and Closure-Pad Lifetime

**Status:** Design in progress  
**Issue:** [#1132](https://github.com/fglock/PerlOnJava/issues/1132)  
**Related architecture:**
[Weaken & DESTROY](../architecture/weaken-destroy.md),
[Refcount alignment plan](refcount_alignment_plan.md), and
[Refcount alignment progress](refcount_alignment_progress.md)

## Objective

Align PerlOnJava's observable reference counts, weak-reference lifetime, closure
capture lifetime, cycles, and deterministic `DESTROY` behavior with system Perl.
The implementation must distinguish real Perl ownership from JVM reachability
and compiler-generated capture metadata.

Issue #1132 established the owner-ledger work. Issue #2043 is the current
delivery for removing lifecycle decisions based on memory sweeps; #1649 is its
historical origin. The design replaces selective-refcount heuristics with
explicit, auditable owner tokens.

## Current Status (2026-11-24 — resumed)

**Delivery issue:** [#2043](https://github.com/PerlOnJava/issues/2043)

**Target PR:** [#1938](https://github.com/PerlOnJava/pull/1938)
**Goal:** deliver Perl-exact owner counts, weak-reference lifetime, and deterministic `DESTROY` behavior without memory-sweep lifecycle decisions. The current PR candidate is `da43c3bf97`, integrating #2171 (`fe8f9a3113`) into #1938. Stale Java tests for removed sweep, snapshot, and diagnostic internals were removed; `WatcherCleanupDoesNotTraverseLiveAggregateTest` adds behavior-level coverage for direct-slot watcher cleanup. `nice -n 19 timeout 1800 make` passes on this candidate in 7m56 (`/tmp/issue2043-restore-netssleay-make.log`). The hosted run for this head is queued.

The previous PR #1938 head was `ad436bee915957431eb68b911df05b6568e9d15e`; it integrates the separately reviewed security fix #2171 as merge commit `6ff4cad7af`. PR #2171 merged as `fe8f9a3113b81b952c667cf7952a9961cf5573d8` after its Ubuntu/Windows builds and CodeQL checks passed. The merged code bounds RSA key generation and documents the 1024-bit compatibility exception. A fresh CodeQL analysis on master at the merge commit still reports alert #3 (`java/insufficient-key-size`) at the NetSSLeay call; the refreshed #1938 CodeQL check also failed on the preceding candidate with this finding. The current #1938 head restores NetSSLeay exactly to #2171's merged version. Its hosted CI is queued. No #1938 review decision is recorded.

The exact-candidate eight-target CPAN acceptance run completed. PPR, DateTime, Image::ExifTool, Excel::Writer::XLSX, and Template passed. Catalyst failed 2/3,477 assertions across 10 programs, matching the recorded current-master failure. DBIx::Class failed 1/43,020 at the known `t/storage/txn.t` source-line mismatch. Mojolicious failed 1/3,735 because `t/mojo/asset.t` could not fork (`Resource temporarily unavailable`), matching the known host limitation. Full output: `/tmp/issue2043-339bf5cd7a-cpan-acceptance.log`; per-target logs: `/tmp/cpan_random_logs/20261124-014723-8770/`. The run regenerated CPAN reports; preserve those files uncommitted.

The three classified acceptance failures are tracked separately: [Catalyst's `role { ... }` parser ambiguity (#2168)](https://github.com/fglock/PerlOnJava/issues/2168), [Mojolicious host fork exhaustion (#2169)](https://github.com/fglock/PerlOnJava/issues/2169), and [DBIx transaction caller source locations (#2170)](https://github.com/fglock/PerlOnJava/issues/2170).

The first full-master build at `ae3f87c122` failed the new installer-lifetime regression because reconciliation had dropped the interpreter file-optree and JVM noncapturing-CODE owner registrations. Cherry-picking `3776bfa487` restored those paths; the focused test and full `make` now pass on `28240779ea`. Gate output: `/private/tmp/issue2043-fullmaster-template-fix-make.log`; focused logs: `/private/tmp/issue2043-fullmaster-template-fix-installer-{jvm,interpreter}.log`.

The eight-target CPAN acceptance rerun on source commit `316886c227` completed all target logs. DateTime passed 3,592 assertions; Image::ExifTool passed 113 files / 600 assertions; Template Toolkit passed 121 files / 3,180 assertions; PPR passed 1,255 assertions; and Excel::Writer::XLSX passed 1,247 files / 5,138 assertions. Mojolicious failed 37 assertions across 45 test programs because subprocesses could not fork under host resource pressure. Catalyst failed two assertions across ten test programs; the same `unit_core_plugin.t` and `unit_core_script_run_options.t` failures reproduce with the current-master JAR at `1ee4ff572d`. DBIx::Class had its single `t/storage/txn.t` source-line mismatch (expected 451, got 448 and 447), reproduced earlier on baseline `278c12be27`. Full output: `/private/tmp/issue2043-fullmaster-template-fix-cpan-acceptance.log`; per-target logs: `/tmp/cpan_random_logs/20261123-142534-68681/`. The run regenerated the three CPAN report files; keep them uncommitted.

### Checkpoint and diagnosis

The explicit-`exit` path previously ran END before active Perl frames retired their lexical owners. `WarnDie.exit()` now unwinds those registrations before flushing deferred releases and dispatching END. The new `dbix_schema_init_shutdown_lifecycle.t` passes on system Perl, JVM, and interpreter with DBICTest LeakTracer reporting no retained references. Full Make passes on the narrowed candidate; logs: `/private/tmp/issue2043-exit-unwind-final-make.log` and `/private/tmp/issue2043-schema-final-{perl-dbic,jvm,interpreter}.log`.

The eight-target acceptance run on the preceding committed candidate `f012dac872` completed with Excel::Writer::XLSX, PPR, Template Toolkit, Catalyst, File::RandomAccess, and DateTime passing. Mojolicious failed one assertion in `t/mojo/asset.t`; DBIx::Class failed LeakTracer checks in `t/inflate/core.t`, `t/inflate/datetime.t`, and `t/relationship/core.t`, plus a transaction error-line mismatch in `t/storage/txn.t`. A separate master worktree passed upstream `t/relationship/core.t` (82 tests, LeakTracer clean), confirming that the relationship leak was a candidate regression. Its focused case used `set_producers([$producer], { attribute => 2 })` followed by an undirected relationship list. The root cause was that anonymous array references passed as arguments lacked the staged owner token already used for anonymous hashes. `RuntimeCode.CallArguments` now stages that owner; the new temporary-array and DBIx shutdown regressions pass on system Perl, JVM, and interpreter. Upstream DBIx `t/relationship/core.t`, `t/inflate/core.t`, and `t/inflate/datetime.t` now pass on JVM with clean LeakTracer checks. `t/storage/txn.t` still has a PerlOnJava error-line mismatch at test 89.

The Mojolicious `t/mojo/asset.t` fork assertion fails on both the candidate and master JVMs with `Can't fork: Resource temporarily unavailable`; system Perl passes. This remains unclassified and is consistent with host process pressure.

The final eight-target acceptance run on `11838413af` (`nice -n 19 timeout 28860 make test-cpan-release-acceptance`) passed Excel::Writer::XLSX, PPR, Template Toolkit, Catalyst, File::RandomAccess, and DateTime. DBIx::Class reports one assertion failure in `t/storage/txn.t` (expected source line 451, got lines 448 and 447); its three shutdown LeakTracer failures are resolved. Mojolicious `t/mojo/asset.t` still fails its fork assertion with `Can't fork: Resource temporarily unavailable`, as it did on master; this remains covered by the user's prior waiver. The acceptance build phase passed in 8m17 and regenerated the launcher JAR with SHA-256 `d34af367d8fc07806c55de82d6ecbd512073753cddcdf6661570255d6c8ce626`. Full output is `/private/tmp/issue2043-package-root-cpan-acceptance.log`; per-target logs are under `/tmp/cpan_random_logs/20261122-230238-55924/`. The generated compatibility reports now contain this run's updates and must be preserved. PR #1938 remains open at `75dd7d542f1f86bff930beb1c5779be74fa27955`, is DIRTY against current master, and has no human review. The source fixes, focused regressions, full Make gate, and upstream DBIx relationship/inflate leak tests are verified; the remaining acceptance failures are the existing transaction-line mismatch and waived Mojolicious fork limitation.

### Permanent regression evidence

The DBIx tests are permanent project-owned Perl tests under `src/test/resources/unit/refcount`: `diamond_prefetch_cursor_leak.t`, `diamond_prefetch_cursor_leak_permutations.t`, and `diamond_prefetch_shutdown_leak.t`. They require `DBIC_CLASS_TEST_ROOT` and skip cleanly otherwise. Before the proxy fix, JVM and interpreter each failed four weak-witness checks (Schema, Storage, DBH, combined), while system Perl collected the witnesses. With the candidate, all three project tests pass on JVM and interpreter; the shutdown case passes 768 functional checks and the permutation case passes 772 assertions. Standard Perl passes all three as well, and DBICTest LeakTracer reports no retained references. Upstream DBIx `t/prefetch/diamond.t` passes 768 assertions, `t/60core.t` passes 125 assertions, and `t/52leaks.t` exits 0 on JVM. Moo's `t/accessor-weaken.t` (19 assertions) and Template Toolkit's `t/string.t` (95 assertions) pass on JVM and interpreter. The full `jcpan -t DBIx::Class` run reached storage tests and timed out after one hour (exit 124). Earlier candidate runs of all 12 `t/resultset/*.t` files found shutdown leaks in `plus_select.t` and `rowparser_internals.t`; a scoped reproduction isolated the JVM-only inflated-result lifetime issue to `Relationship::Base::catch`. The frame-exit fix described above resolves those failures: all 12 resultset files now pass on JVM, and LeakTracer reports no retained references in each. The system-Perl resultset sweep passes 11 files; its three `update_delete.t` SQL execution-status mismatches are unrelated to leak witnesses. The interpreter resultset sweep passes 7 files; five exceed 600 seconds and remain unclassified. Current and historical evidence is retained in `/private/tmp/issue2043-resultset-*` and `/private/tmp/issue2043-latest-dbix-*`. Devel::FindRef is unavailable because its distribution requires XS and cannot be installed by `jcpan`.

The exception-unwind regression passes on system Perl, JVM, and interpreter. It fails on the unfixed `aebe3b0742` source (the JVM reports `first` before `second`) and passes on the current candidate. The complete `nice -n 19 timeout 900 make` gate passed after adding it (all five unit shards, Joni, and home integration). A full Make run on the unfixed baseline failed 11 other JVM unit assertions across three shards; the focused regression result is separately captured in `/private/tmp/issue2043-unfixed-lifo-direct.log`.

`nested_untracked_aggregate_owner_release.t` adds six system-Perl-validated checks for nested array/hash release, a surviving shared owner, weak observers, and destructor rescue. It passes on JVM and interpreter. Ordinary final array/hash cleanup now retires direct element owners and lets final child-container release cascade through that container's own slots; it no longer allocates `RecursiveAggregateReleaseWalker` worksets or probes descendants with `CleanupTargetProbe`. Scope-exit's existing liveness guards and one-level eligibility checks remain, as does `CleanupTargetProbe` for global destruction. The package-array root-marking fast path and paired performance/allocation evidence are recorded under Current Work; the measurements cover the Excel workloads and do not claim a portfolio-wide effect.


The latest Excel::Writer::XLSX 1.15 run covered all 1,247 distribution test files against the rebuilt candidate: 1,246 passed and the optional `t/utility/xl_parse_date.t` was skipped because Date::Manip and Date::Calc are unavailable. The first `jcpan -t` batch reached its one-hour timeout without failures; a correctly scoped MakeMaker batch passed the remaining 498 files (4,276 assertions), and the optional test was run separately to record its skip. Logs: `/private/tmp/issue2043-excel-writer-xlsx-final-candidate.log`, `/private/tmp/issue2043-excel-writer-xlsx-final-resumed.log`, and `/private/tmp/issue2043-excel-writer-xlsx-final-optional.log`.

With the latest launcher JAR and the installed DBIx::Class 0.082844 dependency tree, all three focused `diamond_prefetch_*` issue tests pass on JVM and interpreter: `/private/tmp/issue2043-latest-diamond_prefetch_cursor_leak.t-{jvm,interp}.log`, `/private/tmp/issue2043-latest-diamond_prefetch_cursor_leak_permutations.t-{jvm,interp}.log`, and `/private/tmp/issue2043-latest-diamond_prefetch_shutdown_leak.t-{jvm,interp}.log`.

The `chart_axis28.t` workload was measured against an isolated before artifact that retains the recursive release walker and the same other current-source changes. Seven randomized persistent-worker pairs each passed the normalized XLSX checksum; all pairs passed the warmup stability limits with observed nice 19. Candidate/before throughput geometric mean is 0.970, with bootstrap 95% interval [0.869, 1.075], so this test does not show a reliable end-to-end gain. Diagnostic counters for 100 workbook writes show aggregate release walkers/worksets 9,298→0, release roots 26,537→0, recursive-probe worksets 3,888→1, and probe roots 14,574→184; owner-slot visits changed 6,349,994→6,343,314. Queue counters are also captured: transient RHS appends/drains are 13,294/13,294 on both artifacts, with 19,213 handoffs; frame queues allocate 6 and reuse 5,441 times; boundary queues allocate 0 with 14,241 marks; entry/boundary buffer growths are 14/13 on both. Separate JFR allocation samples total 7.804 GB before and 7.808 GB candidate; this sampled total is effectively unchanged and does not support an allocation-reduction claim for that change alone. Reports and raw profiles: `/private/tmp/issue2043-chart-axis-persistent-paired.json`, `/private/tmp/issue2043-chart-axis-before.jfr`, `/private/tmp/issue2043-chart-axis-candidate.jfr`, and the corresponding `*-diag-*.json` files. The baseline full Make gate failed only `ReachabilityQueryCostTest.repeatedContainerScopeCleanupDoesNotEnumerateNestedStashRoots`, which is the expected scan-count difference; all candidate shards pass.

The same paired workload was repeated on the latest source and launcher JAR (`f0a5437ed9a1db3ae11720b0831b4818ff99ba71c460062ea6e049ff0d1bb9f0`) with seven stable, checksum-matched pairs and worker niceness 20. Candidate/before throughput geometric mean is 0.993, with bootstrap 95% interval [0.886, 1.128]. This interval also includes no change, so the latest candidate has no demonstrated end-to-end speedup on this workload. The first rerun had no effective low-priority setting and was excluded. Valid report and logs: `/private/tmp/issue2043-chart-axis-persistent-paired-latest-nice.json`, `/private/tmp/issue2043-chart-axis-paired-latest-nice.log`; excluded run: `/private/tmp/issue2043-chart-axis-persistent-paired-latest.json`.

A broader persistent-worker profile wrote three checksum-identical workbooks, each with four 30-row data sheets, formulas, formatting, tables, conditional formatting, merged cells, and a chart. The same output hash was produced by both JARs. Elapsed time was 86.14s before and 87.06s on the candidate; this is a single diagnostic profile and does not establish a slowdown or speedup. Lifecycle counters changed from 2,447 aggregate walkers / 6,791 roots / 4,274 probe roots to 0 / 0 / 112, while owner-slot visits stayed nearly flat (3,781,284→3,779,397). JFR execution samples were dominated on both artifacts by `IdentityHashMap.containsKey` through `RuntimeScalar.markPrepopulatedPackageAggregateOwner` and `GlobalVariable.markPackageGlobalRoot`; allocation samples were led by `IdentityHashMap.init`. This identifies a separate package-root marking hotspot for further source-path and mutation audit; no change has been made from this profile alone. Artifacts: `/private/tmp/issue2043-excel-broad-profile-v2.json`, both `/private/tmp/issue2043-excel-broad-v2-{before,candidate}.jfr` recordings and their `*-diag-*.json` files. The initial 300-row worker exceeded its 120s timeout; the reduced workload was smoke-checked with system Perl and completed on both JARs.

The next narrow allocation change makes `WeakRefRegistry.releaseDetachedUntrackedReferenceOwners` iterate the existing untracked-owner index instead of allocating and filtering a snapshot of every owner slot. Full `nice -n 19 timeout 1800 make` passes on `339bf5cd7a` (`/tmp/issue2043-339bf5cd7a-make.log`). Seven randomized persistent-worker pairs on `chart_axis28.t` have stable warmups, matching workbook checksums, and observed nice 20; candidate/before throughput is 1.074 with bootstrap 95% interval [1.061, 1.088]. JFR allocation sample weights changed from 8.126 GB to 7.617 GB overall (about 6.3% lower), from 257.5 MB to 203.3 MB for `IdentityHashMap`, and from 22.3 MB to 1.4 MB on stacks beginning with `ownerSlotSnapshot`. These are sampled weights, not exact allocation totals. The paired report is `/private/tmp/issue2043-untracked-index-paired.json`; the exact JAR hashes and raw profiles are recorded there and in `/private/tmp/issue2043-untracked-index-{before,candidate}.jfr`.


### Completed milestones

| Date | Delivered scope | Evidence |
|---|---|---|
| 2026-11-19 | Cached hot owner-kind counts, narrow identity indexes, and reusable cleanup/probe worksets | Owner-ledger unit gate passed; earlier performance measurements remain inconclusive and are not a broad speedup claim. |
| 2026-11-19 | Correct nested hash-slot `B::REFCNT` reporting during `DESTROY` | System-Perl-validated regression; DBIx `t/60core.t` and `t/101populate_rs.t` passed. |
| 2026-11-21 | Retain interpreter foreach source owners through normal and `last` exits (`4edc5faa51`) | System Perl, JVM, interpreter, and focused DBIx diamond regression passed. |
| 2026-11-21 | Release overwritten temporary call-argument owners, including Test2 Amnesty facets (`eb8907f362`) | System Perl, JVM, and interpreter regressions passed; source build succeeded. |
| 2026-11-21 | Restore eval/die `DESTROY` LIFO ordering and the owner-trace snapshot prefix | System Perl, JVM, and interpreter checks pass; filtered refcount Make shard passes on the current candidate. These edits remain uncommitted and are not full acceptance. |
| 2026-11-21 | Repair weak-tracked scalar storage, raw untracked slots, legacy count adoption, and async foreach owner restoration | Full `nice -n 19 timeout 900 make` passed all five shards, Joni, and home integration on the tested working tree; focused lifecycle Perl tests passed on JVM and interpreter. |
| 2026-11-21 | Cascade ordinary aggregate cleanup through direct outgoing storage slots | `nice -n 19 timeout 900 make` passed all five shards, Joni, and home integration after restoring scope-exit eligibility guards; new nested untracked aggregate test passes on system Perl, JVM, and interpreter. No performance claim yet. |
| 2026-11-21 | Complete Excel::Writer::XLSX 1.15 test surface in two bounded batches | Across the original CPAN run and resumed `make test TEST_FILES=...`, all 1,247 discovered tests have results: 1,246 pass and one optional `xl_parse_date.t` skip for missing Date::Manip/Date::Calc. No test failures. Candidate JAR SHA-256: `59e46391b0836faa350ce71ad86e312d9b138565cd66f41f51bdcaeaa58313b0`. |
| 2026-11-22 | Measure direct-slot cleanup against its isolated recursive-walker baseline | Seven stable, checksum-matched pairs complete. The interval includes no change; lifecycle counters show walker/workset/probe reductions, while total sampled JFR allocation weight is unchanged. No speedup claim. |
| 2026-11-22 | Remove ordinary scope-exit pre-scans and preserve tied-array cleanup behavior | Full `make` passes in 7m13; new ownership, tied-array, and taint tests pass on the applicable backends. JAR SHA-256 is recorded below. |
| 2026-11-22 | Repeat paired `chart_axis28.t` measurement on the latest JAR | Seven stable, checksum-matched pairs at nice 20; throughput ratio 0.993, bootstrap 95% interval [0.886, 1.128]. No reliable end-to-end gain. |
| 2026-11-22 | Profile a broader multi-sheet Excel workload against before and latest JARs | Three matching workbooks per artifact; lifecycle walker/workset counts 2,447→0 and probe roots 4,274→112. Single-profile elapsed times are nearly equal; JFR points to repeated package-root marking as a separate hotspot. |
| 2026-11-22 | Separate runtime-only eval captures from semantic closure owners | New regression passes on system Perl, JVM, and interpreter; DBIx row-parser shutdown repro reports no leaks on both PerlOnJava backends. Full Make passes after the runtime change. |
| 2026-11-22 | Add focused coverage for DBIx inflated `+select` result shutdown | The regression first exposed four JVM DBIx Storage/Schema/DBH shutdown leaks. The JVM frame-exit fix resolves them; the project regression passes on system Perl and both backends with no LeakTracer-retained references. |
| 2026-11-22 | Run the full repository gate after adding both DBIx regressions | `nice -n 19 timeout 900 make` passes in 8m01 (`/private/tmp/issue2043-make-with-dbix-regression.log`). The optional external DBIx tests were run separately with `DBIC_CLASS_TEST_ROOT` set. |
| 2026-11-22 | Revalidate the resultset section after the JVM frame-exit fix | All 12 upstream resultset files pass on JVM with no retained references. System Perl passes 11; interpreter passes 7 and has five 600-second timeouts. The current full repository gate passes in 7m16. |

The latest full gate, `nice -n 19 timeout 1800 make`, passed in 8m33 on commit `11838413af` (`/private/tmp/issue2043-package-root-make.log`). It completed all five Java/Perl unit shards, Joni tests, home integration tests, and `PackageArrayRootMarkingFastPathTest`. That build's launcher JAR SHA-256 was `dada35bd36b0a0346604193d4ee924664e25f4fb886bf69f1981021854d37404`; the later acceptance build regenerated the same source commit's JAR as recorded above.

The current-master baseline `nice -n 19 timeout 900 make` passed at `506371c134` in 6m50 (`/private/tmp/issue2043-master-baseline-make.log`). The pre-edit candidate run at `aebe3b0742`, `JPERL_TEST_SHARD=0 nice -n 19 timeout 900 make`, reported 11 Java test failures in 5m52 (`/private/tmp/issue2043-pre-edit-shard0-make.log`). The ten failures in the later full candidate gate and the one extra pre-edit reachability-query failure are resolved on the current working tree. The latest full gate also completed the thread-shared queue clone test without reproducing its earlier `ConcurrentModificationException`.

Direct runs against the final rebuilt JAR passed `destroy_eval_die.t` (10/10), `issue_2043_nested_aggregate_destroy_lifo.t` (2/2), `list_assignment_hash_tail_releases_displaced_owner.t` (5/5), `owner_trace_snapshot.t` (3/3), and `issue_2043_interpreted_foreach_source_lifetime.t` (7/7) on JVM and interpreter. The full repository gate does not replace DBIx acceptance with that dependency available.

### Remaining acceptance work

| Area | Evidence | Next action |
|---|---|---|
| Excel::Writer::XLSX performance and allocation evidence | The package-array root scan comparison on `11838413af` showed 11.92× throughput on `chart_axis28.t` and 6.64× on package-array reads against exact parent `2362185545`. The latest seven-pair comparison of `339bf5cd7a` against `3e97440c60` shows 1.074× throughput [95% interval 1.061, 1.088], matching checksums and stable warmups at nice 20. JFR sampled allocation weight fell about 6.3% overall, with the `ownerSlotSnapshot` stack falling 93.6%. Reports: `/private/tmp/issue2043-package-root-excel-paired.json`, `/private/tmp/issue2043-package-array-paired.json`, and `/private/tmp/issue2043-untracked-index-paired.json`. | The allocation path is now directly targeted and measured; treat JFR as sampled evidence and avoid claiming a portfolio-wide speedup. |
| DBIx::Class acceptance | The exact `339bf5cd7a` eight-target run completed 325 DBIx test programs / 43,020 assertions with one failure: `t/storage/txn.t` expected source line 451, got 448 and 447. Baseline `278c12be27` reproduces it. The interpreter resultset timeouts and three system-Perl `update_delete.t` SQL-status mismatches are also classified against current master. | No owner-ledger-specific DBIx failure remains in the acceptance evidence; retain these baseline limitations and do not claim all-backend DBIx acceptance. |
| Catalyst acceptance | The exact `339bf5cd7a` acceptance run has the same two assertion failures across ten programs as the current-master reproduction at `1ee4ff572d`; the pre-merge candidate passed the target. | Treat as current-master baseline behavior; no owner-ledger fix is indicated. |
| Mojolicious acceptance | The exact `339bf5cd7a` run fails one assertion in `t/mojo/asset.t` with `Can't fork: Resource temporarily unavailable`; this matches the previously recorded host limitation. | The host fork limit is waived; retain the run log and do not interpret dependent missing assertions as product failures. |
| Focused compatibility retests | Moo weak accessor and Template Toolkit string tests pass on both backends. Standard Perl passes Moo; its Template Toolkit run could not load `Template::Stash::XS` because the temporary standard-Perl environment lacks that native build. | No focused retest remains; preserve the standard-Perl Template Toolkit environment limitation. |
| PR integration | PR #1938 is published at `da43c3bf97`, integrating #2171. Full Make passes; fresh hosted checks are queued. CodeQL still reports alert #3 on master at the merged #2171 commit. | Verify hosted checks and get maintainer disposition of the remaining CodeQL alert; then wait for human review. |

### Complete step-by-step delivery plan

1. **Resume from this checkpoint.** On 2026-11-24, issue #2043 and PR #1938 were reachable. The issue checkpoint is posted, PR #1938 is published at `6b8c43fe3265474e5ffcbf52da7266d3b994bf68`, and the three classified CPAN failures have separate issues (#2168–#2170). Keep the generated CPAN report changes uncommitted.
2. **Use TDD for every distinct failure.** Keep existing tests unchanged. Where coverage is missing, add a focused permanent test under `src/test/resources/unit` or the owning Java unit-test tree before production changes. For Perl tests, run system Perl first and save complete output. Prove the regression on the unfixed parent and preserve the failure log.
3. **Retain the lifecycle fixes.** The ten full-candidate shard-0 failures and the extra pre-edit reachability-query failure now pass; the latest full Make gate passes. Keep the owner-ledger and async foreach changes together because they address current ownership and resume boundaries.
4. **Reconfirm the proxy-owner fix.** The three diamond regressions and upstream `t/prefetch/diamond.t` and `t/60core.t` pass with the DBIx prerequisite available. Preserve the pre-fix/fixed logs. Confirm the optional DBIx dependency skip behavior remains clean.
5. **Complete DBIx acceptance.** Focused `t/prefetch/diamond.t`, `t/60core.t`, and `t/52leaks.t` previously passed. All 12 upstream `t/resultset/*.t` files now pass on JVM, including the earlier `plus_select` and row-parser shutdown leaks. Focused project regressions pass on system Perl and both backends. Investigate the five interpreter resultset timeouts and three system-Perl `update_delete.t` SQL-status mismatches before claiming cross-backend DBIx acceptance; then run the broader DBIx suite on the final candidate.
6. **Complete remaining compatibility gates.** Focused Moo and Template Toolkit checks pass on both backends. Rerun them with the final lifecycle candidate after fixing the JVM-only inflated-result leak. For product failures, add permanent tests first and validate expected Perl behavior; capture full outputs, exit status, command, and source identity.
7. **Review and document the final source.** Run `git diff --check`, review all runtime/support code, verify tests are in `src/`, and remove temporary diagnostics that are not needed. The Work in progress changelog now includes the eval exception, async foreach, and owner-slot lifecycle behavior. Run `make check-links` and offline link checking for changed Markdown.
8. **Commit and run immutable gates.** Commit the reviewed candidate before the final repository gate. Run `nice -n 19 timeout 900 make` on that exact source commit and focused acceptance gates. If any source changes follow, commit them and rerun affected tests plus `make`.
9. **Verify the published PR candidate.** After local gates pass, update #1938 to the candidate integrating merged #2171; inspect all hosted checks and current review state.
10. **Close delivery after review.** Record final PR checks and review evidence here. Wait for human review and maintainer merge; then verify issue #2043 is resolved and archive the design as directed by the design-document workflow.

Preflight backups of the dirty source state are `/tmp/wip-unstaged-20261121-144256.patch`, `/tmp/wip-staged-20261121-144256.patch`, `/tmp/wip-status-20261121-144256.txt`, and the later post-timeout snapshot `/tmp/wip-unstaged-20261121-154419.patch`, `/tmp/wip-staged-20261121-154419.patch`, `/tmp/wip-status-20261121-154419.txt`.

## Semantic Ownership Model

```text
RuntimeCode --semantic capture--> RuntimeScalar pad cell --strong slot--> referent
```

These edges have different meanings:

- The declaring scope and semantic closure captures own the pad cell.
- A strong pad cell owns exactly one referent token.
- A weak pad cell owns no referent token.
- Multiple closures can share one pad cell without changing the referent count.
- Conservative compiler metadata and generated JVM object fields are not Perl
  roots and own neither the pad nor its referent.

### Required invariants

1. A live pad cell owns at most one token for its current referent.
2. A strong, live pad cell owns exactly one token when its referent is tracked.
3. `semanticCaptureCount` affects pad lifetime, not referent cardinality.
4. Metadata captures never affect Perl lifetime.
5. Reassignment releases the old pad token before acquiring the new one.
6. `weaken()` releases the pad token and captures do not recreate it.
7. The final pad owner releases the token exactly once.
8. Lifecycle decisions follow owner slots directly; Java reachability does not create or retire Perl owners.
9. Java reachability alone cannot create a Perl owner.
10. Strong Perl cycles remain alive until a strong edge is explicitly broken.

## Runtime State

The scalar and closure state should make ownership explicit:

- `slotOwnsReferent`: the scalar slot owns one strong referent token. The
  existing `refCountOwned` field can initially implement this state, but its
  meaning must be narrowed and documented.
- `scopeOwnerAlive`: the declaring lexical scope still owns the pad cell.
- `semanticCaptureCount`: number of live Perl closures sharing the pad cell.
- `metadataCaptureCount`: conservative or diagnostic captures that do not
  affect Perl ownership.
- `CaptureBinding`: a `RuntimeCode` record pairing a pad cell with capture
  provenance. Each semantic binding is released exactly once.

`scopeExited` may remain during migration, but it must become derived lifecycle
state rather than an ownership heuristic.

## State Transitions

| Event | Pad ownership | Referent ownership |
|---|---|---|
| Strong lexical assignment | Scope owns pad | Pad owns one token |
| First semantic closure capture | Add pad owner | Unchanged |
| Additional closure capture | Add pad owner | Unchanged |
| Metadata capture | Unchanged | Unchanged |
| Declaring scope exits | Remove scope owner | Retain token while semantic captures remain |
| One of several closures is released | Remove one pad owner | Unchanged |
| Final closure is released after scope exit | Pad becomes dead | Release one token |
| Captured scalar is reassigned | Pad remains live | Release old token; acquire one new token |
| Captured scalar is weakened | Pad remains live but weak | Release token |
| Captured scalar is unweakened | Pad remains live and strong | Acquire one token |

A borrowed scalar promoted into a semantic captured pad acquires one pad-slot
token. Further closures sharing that pad do not acquire additional tokens.

## Exact Capture Provenance

Both execution backends must produce equivalent capture descriptors.

### JVM backend

- Attach semantic `CaptureBinding` records during compilation.
- Do not infer Perl ownership solely from reflected generated fields.
- Keep generated closure objects and compiler metadata opaque to ownership.

### Interpreter backend

- Distinguish lexicals referenced by the closure from conservative snapshots of
  all visible registers.
- Preserve `eval STRING` access to lexicals without making unrelated register
  metadata permanent owners.
- Release semantic bindings through the same `RuntimeCode.releaseCaptures()`
  path as the JVM backend.

### Threads

- Reconstruct slot ownership and semantic captures in an independent ithread
  snapshot from capture descriptors.
- Do not infer cloned ownership from the source scalar's transient
  `refCountOwned` value.

## Authoritative Owner Ledger

Tracked referents need an authoritative count separate from conservative JVM
reachability and historical selective-count drift. Every owner-producing path
must identify an owner kind:

- Scalar or pad slot
- Aggregate element
- Package global
- Glob or stash slot
- CODE storage
- Tie wrapper
- Pad constant
- Temporary call hold
- Explicit destruction rescue
- Other runtime-specific strong Perl edges

Debug validation should assert:

```text
refCount == sum(authoritative owner tokens)
```

Temporary aliases, generated fields, and conservative captures must not produce
authoritative tokens. During migration, legacy or uncertain holds must be
recorded separately so they cannot prevent weak cleanup indefinitely.

## Lifecycle Ownership and Diagnostic Queries

Weak-reference cleanup and destruction follow current owner-slot identities. A
referent remains alive while any Perl strong slot remains, including edges in
strong cycles. Releasing the final slot performs cleanup through the ordinary
container and lifecycle paths.

No root traversal, snapshot, or sweep may change reference counts, weak
observers, or destruction timing. The reachability walker and scalar registry
are removed; lifecycle code uses owner-slot identities for final-release
decisions. Temporary leases and suspended-frame owners remain explicit slots.

## Current Work

1. Ordinary final array/hash cleanup now retires direct outgoing slots; the
   recursive walker and one-level eligibility pre-scans are gone from that
   path. Existing liveness guards remain. Tied arrays use raw local storage
   access during slot retirement so cleanup does not call Perl `FETCHSIZE`.
   Global destruction still uses `CleanupTargetProbe`.
2. Skip repeated package-root marking when `getGlobalArray` reads an already
   rooted aggregate; global table writes establish the root and array mutation
   paths register later slots. The fast-path regression passes, and the
   seven-pair `chart_axis28.t` comparison against exact parent `2362185545`
   measures 11.92× throughput (95% interval [10.53, 13.68]) with matching
   normalized workbook checksums. The paired evidence is in
   `/private/tmp/issue2043-package-root-excel-paired.json`; the focused
   package-array workload and JFR samples are retained alongside it.
3. Exact-candidate release acceptance is complete. The three failures are
   classified as current-master or host-resource issues; the generated CPAN
   report files remain uncommitted.
4. The local PR reconciliation candidate is validated, but remote PR #1938 has
   not been updated. Recheck its live state and obtain explicit authorization
   before pushing the candidate.

## Regression Matrix

Every new or changed Perl test must pass system Perl before PerlOnJava is used
as an implementation oracle.

### Pad and closure ownership

- Zero, one, and two closures sharing one lexical retain one referent owner.
- Independent lexical copies produce independent referent owners.
- Scope exit preserves a referent until the final closure is released.
- Releasing one of two closures does not release the shared pad token.
- The final release clears weak observers and invokes `DESTROY` exactly once.
- Nested closures and returned closures share the correct pad cell.
- Argument aliases and borrowed captures are promoted exactly once.
- Self-capturing CODE and strong closure cycles match Perl behavior.

### Mutation

- Weakening a captured slot makes it non-owning.
- Unweakening a live captured slot restores one owner.
- Reassignment releases the old referent and retains the new referent.
- Repeated reassignment cannot release a stale referent through deferred work.
- Ordinary weak references clear when no strong owner exists.

### Capture provenance

- JVM and interpreter closures report equivalent semantic captures.
- Conservative interpreter register snapshots do not retain unrelated objects.
- `eval STRING` retains only the Perl-visible lexical environment for the
  correct code-object lifetime.
- Generated JVM objects and reflected fields do not become Perl roots.
- ithread clones reconstruct independent ownership.

### Existing compatibility guards

Preserve and run:

- `unit/weak_localized_cache_lifetime.t`
- Closure-cycle, captured-scalar, weak-callback, Sub::Quote, and Sub::Defer tests
  under `unit/refcount/`
- Focused Moo and Sub::Quote tests
- DBIx::Class leak-tracer and weak-registry tests
- Exact-refcount tests using `B::REFCNT` or `Test2::Tools::Refcount`

## Historical issue #1132 ecosystem acceptance

Run on JVM and interpreter backends with `timeout` and complete output logs:

1. The issue #1132 Future reproducer prints `completed=1` without a lost-sequence
   warning.
2. `Future::Utils::repeat` prints `ready=1 result=final`.
3. `Net::Async::HTTP` `t/05redir.t` completes without warning or timeout.
4. Full Future and Net::Async::HTTP distributions complete.
5. Cookie and Bzip2 failures remain separately classified.
6. Focused Moo, Sub::Quote, and DBIx::Class leak tests remain green.

## Final Gates

- Run every new or changed Perl test with system Perl first.
- Retain evidence that the focused issue test fails on the unfixed parent.
- Run focused tests on both PerlOnJava backends.
- Run `make` with complete captured output and zero failures.
- Run `make check-links` and offline `lychee` where required.
- Verify no unexpected high-CPU PerlOnJava JVM remains.
- Integrate current `master`, rerun focused and full gates on the exact commit,
  and only then mark the issue-fix PR ready.

## Progress Tracking

### Current checkpoint

The current status and evidence are maintained in the
[ownership rewrite handoff](perl-ownership-rewrite.md#current-handoff-2026-11-15).
The candidate has removed production reachability decisions and sweep
infrastructure. The DBIx and Test2 owner transitions have focused regressions;
the merged candidate passes the focused DBIx leak tests and direct survivor
checks. CI passes on the published candidate and PR #1938 is ready for human
review. The broader 37-program failure inventory is retained as historical
integration context. #2037 and #2034 remain separate post-merge follow-ups.

### Historical handoff: issue #1132 / PR #1204

This section records the earlier issue-linked PR
[#1204](https://github.com/PerlOnJava/pull/1204) handoff. Its branch was
`fix/issue-1132-closure-lifetime`; retained commits were `5b693177f`,
`1702edb70`, `4832540d6`, `9fcab6e81`, and `87b4fdee3`. These historical
branch instructions do not define the current continuation.

The Net::Async::HTTP 0.50 distribution is installed through `jcpan` in the
local CPAN cache. JVM and interpreter `t/30timeout.t` and `t/32remove.t` now
pass. Preserve both focused ownership regressions when evolving the ledger.

### Open Questions

- Can additional compiler-created aggregate temporaries be made explicit in
  the owner ledger, rather than relying on opcode-specific transfers?
