# Issue #1115 Completion Handoff

## Current checkpoint

PR #1129 is open. `fix/issue-1115-mojolicious` contains
the completed `UNIVERSAL`, process-pipe, active-argument aggregate lifetime,
and compound-lvalue fixes plus permanent regressions and framework evidence.
Post-UAT regressions are fixed on `9b2377b6f`; its exact-commit local gate and
focused UAT are green. Push and hosted CI verification are the remaining
repository workflow steps before review/merge.
The validation branch
`wip/issue-1115-does-20260828-100139` contains the completed work plus two
process-pipe WIP snapshots. Its worktree has an uncommitted failed experiment;
the required patch/status backups were captured at
`/tmp/wip-unstaged-20260828-172026.patch`,
`/tmp/wip-staged-20260828-172026.patch`, and
`/tmp/wip-status-20260828-172026.txt`, but its linked-worktree Git metadata is
not writable from this session, so do not mutate or discard that checkout.

## Active diagnosis

The JVM truncation and TAP parallel-harness descriptor loss are fixed and
committed. JVM and interpreter focused
runs both drain all 65,536 stderr bytes with empty stdout and child exit 0.
The fix materializes only anonymous-I/O aliases before JVM lexical cleanup,
preserves all non-I/O return identity, transfers the holder to the caller's
final return copy, and establishes ownership when `IPC::Open3` creates each
process handle. Stdout and stderr remain distinct `ProcessInputHandle`
instances and descriptor registrations.

The DBIx::Class harness exposed two additional root behaviors. A signal that
interrupts a blocking poll-backed `select` now returns `-1` with `EINTR`, and
aggregate cleanup is deferred while an active pristine argument frame still
aliases the aggregate. Cleanup is reconsidered as frames unwind, preserving
TAP::Parser::Multiplexer's nested process handles without leaking sockets.

The final interpreter task-board smoke exposed Perl compound-assignment
evaluation order in Mojo::Template. Both backends now resolve and vivify a
compound lvalue before a mutating RHS. The system-Perl-validated 5-assertion
regression and every task-board route pass on JVM and interpreter.

Windows CI on `8dd73a939` exposed that `sysopen` incorrectly inherited the
platform `:crlf` layer when no lexical `use open` hint applied, and that the
non-POSIX `stat` fallback discarded exact modes applied by `sysopen`/`chmod`.
`sysopen` now stays raw by default while retaining lexical encoding hints, and
Windows mode emulation is fenced by file identity. Complete TAP is retained in
JUnit failures for actionable hosted-run diagnostics. The related focused
tests pass on system Perl, JVM, and interpreter; the local full gate passes in
4m28s (`/tmp/make_issue1115_windows_fix2.log`).

The branch-only `die_after_lexical_filehandle_scope.t` regression was
classified against exact parent `130d6fdb0` (parent passes 4/4) and fixed by
clearing stale readline diagnostic context before releasing a non-returned I/O
owner. The focused JVM test now passes 4/4.

The clean immutable full gate on `81cc2a3d0` passes all five unit shards,
Joni, packaging, and shadow-JAR generation in 9m19s after `make clean` removed
a corrupt persisted Gradle result store
(`/tmp/make_issue1115_81cc2a3d0_clean_gate.log`). With the final two shared
`UNIVERSAL` fixes applied, a second full gate passes in 5m28s
(`/tmp/make_issue1115_universal_fix.log`). The focused regression passes 2/2
on system Perl, JVM, and interpreter, and core UAT passes 4/4 files and
261/261 assertions (`/tmp/issue1115_universal_fix_core_uat.log`).

## Phase 1: preserve and normalize the validation state

1. Keep the patch/status backups named above. Do not use `git stash`,
   destructive checkout/restore/reset, or mutate a checkout while a build or
   test gate is running.
2. Inspect the failed validation diff and retain only the coherent
   `ProcessInputHandle` state machine, its 4 MiB cap/backpressure, and the
   permanent process-pipe regression.
3. Remove temporary `[JPERL_*_DEBUG]` diagnostics from `RuntimeIO` and
   `RuntimeScalar`.
4. Remove unsuccessful generic ownership experiments in `IPCOpen3`, the
   `RuntimeScalar` copy constructor, and `MortalList`, plus whitespace-only
   `IOOperator` changes.
5. If the linked worktree remains inaccessible, reproduce the final net diff
   on a new integration branch from the clean main checkout; never overwrite
   the preserved WIP checkout.

## Phase 2: implement JVM returned-aggregate ownership

1. Add an internal return-aware array-cleanup helper for JVM-generated
   subroutines. Before cleaning a lexical array, inspect the materialized
   `RuntimeList` return value for scalars referencing the same anonymous
   `RuntimeGlob`.
2. For every returned alias, acquire its durable I/O holder before releasing
   the source array element. If the returned value is the same scalar, retain
   its existing token rather than acquiring twice.
3. Release ordinary non-returned array elements normally so socket EOF and
   descriptor recycling remain deterministic.
4. Spill and pass non-void implicit subroutine results through JVM scope
   cleanup. Use the same helper for explicit returns. Do not alter interpreter
   behavior unless focused parity testing demonstrates an independent defect.
5. Keep stdout and stderr as separate `ProcessInputHandle` objects and
   separate descriptor registrations.

## Phase 3: permanent regression coverage

1. Make `process_pipe_postexit_drain.t` report captured byte counts before
   comparing content.
2. Cover the implicit list-return shape used by `IO::Select::handles()`, an
   exact 65,536-byte stderr payload, empty stdout, successful child exit, and
   complete draining after child exit.
3. Preserve `re_debug_thread_region.t` unchanged.
4. Keep `socket_scope_exit_eof.t` fully green to prove returned-handle
   preservation does not leak sockets.
5. Validate every new or changed Perl test with system Perl before relying on
   it for PerlOnJava behavior.

## Phase 4: focused validation

Capture complete output in `/tmp` for every command. Run system Perl, JVM, and
interpreter versions of `process_pipe_postexit_drain.t`; JVM and interpreter
versions of `re_debug_thread_region.t`; system Perl, JVM, and interpreter
returned-handle coverage; and JVM/interpreter versions of
`socket_scope_exit_eof.t`. Confirm no diagnostic markers remain. After each
test series, check for unexpected PerlOnJava JVMs and clean up only explicitly
identified leftovers.

## Phase 5: immutable full gate

Run the exact immutable integration commit with:

```text
nice -n 10 timeout 1200 make
```

All shards must pass. Classify any failure against the clean PR parent before
making further changes. Never edit the source checkout while this gate or its
children are running.

## Phase 6: integration history

1. Create `integrate/issue-1115-final` from `fix/issue-1115-mojolicious`.
2. Cherry-pick the contiguous completed alias/`UNIVERSAL` commits through
   `c84524f59` without rebasing the WIP snapshots.
3. Apply and commit the final process-pipe regression as a test-only commit.
4. Apply and commit the final runtime/compiler ownership fix separately.
5. Commit this handoff and related documentation separately.
6. Include Codex attribution in every AI-assisted commit.
7. Run `make` on the exact integration commit, then fast-forward
   `fix/issue-1115-mojolicious` to it, push without force, and verify PR #1129
   remains open with the expected files.

## Phase 7: core UAT

Run the bounded four-file core UAT through `perl_test_runner.pl`:

```text
perl5_t/t/mro/package_aliases.t
perl5_t/t/mro/package_aliases_utf8.t
perl5_t/t/mro/isa_aliases.t
perl5_t/t/op/universal.t
```

Every assertion must pass. If a `UNIVERSAL` case remains, add a focused,
system-Perl-validated regression, fix shared runtime behavior where possible,
verify both backends, and rerun `make`.

## Phase 8: framework acceptance and evidence

Run sequentially with full logs:

```text
nice -n 10 timeout 3600 ./jcpan -t Mojolicious
nice -n 10 timeout 3600 ./jcpan --jobs 8 -t DBIx::Class
```

Install `Catalyst::Runtime`, then run its complete supported manifest through
the bounded runner, excluding only real-fork-only `t/live_fork.t`; require all
199 supported files to pass. Run the Mojolicious task-board example on JVM and
interpreter backends as the final route-level smoke test.

After acceptance, update `dev/modules/mojo_ioloop.md` and the terse WIP section
of `docs/about/changelog.md` with fresh counts, elapsed times, commit SHA, and
log paths. Mark this handoff complete with only review/merge remaining. Update
PR #1129 through a body file containing exact gate, UAT, and framework results;
retain `Fixes #1115` and do not claim readiness until every required gate is
green.

## Acceptance criteria

- The 65,536-byte process-pipe test passes on system Perl, JVM, and
  interpreter, including post-exit draining, empty stdout, and exit status 0.
- `re_debug_thread_region.t` and `socket_scope_exit_eof.t` are fully green on
  both PerlOnJava backends.
- No diagnostic markers or unexpected JVM processes remain.
- The full `make` gate, four-file core UAT, Mojolicious, Catalyst supported
  suite, DBIx::Class suite, and task-board smoke test pass.
- Final history has separate test, runtime, and documentation commits with
  attribution; PR #1129 is open, lists the expected files, and retains
  `Fixes #1115`.

## Current progress

- [x] Complete handoff written to `dev/design/issue-1115-completion-handoff.md`.
- [x] WIP process-pipe/runtime/test changes committed as `47014007a`.
- [x] Remote `UNIVERSAL` fixes merged in `312adf4c4` and pushed to PR #1129.
- [x] Changed regression passes under system Perl.
- [x] JVM returned-aggregate ownership implemented (2026-08-28).
  - Anonymous-I/O aliases are narrowly materialized before implicit and
    explicit JVM return cleanup; non-I/O values retain existing semantics.
  - `IPC::Open3` process handles acquire a durable owner at creation.
  - Stale readline context is cleared before a final non-returned owner release.
- [x] Permanent process regression strengthened and system-Perl validated
  (2026-08-28): `/tmp/issue1115_process_system_perl_final.log`.
- [x] Focused process test passes JVM and interpreter with 65,536 bytes
  (2026-08-28): `/tmp/issue1115_process_jvm_ordered.log` and
  `/tmp/issue1115_process_interpreter_materialized.log`.
- [x] Focused lexical-filehandle test passes JVM 4/4 after parent
  classification: `/tmp/issue1115_die_exact_parent.log` and
  `/tmp/issue1115_die_jvm_ordered.log`.
- [x] Focused matrix complete on `204a9922d` (2026-08-28).
  - Process drain: JVM/interpreter 3/3, 65,536 stderr bytes, empty stdout.
  - Regex debug region: JVM/interpreter 4/4.
  - Returned lexical handle: system Perl/JVM/interpreter 4/4.
  - Socket scope/EOF: system Perl/JVM/interpreter 35/35.
  - Logs: `/tmp/issue1115_204a_*.log`; no PerlOnJava5 JVM survived.
- [x] Clean immutable full gate passes on merged head `9daa1be73`
  (2026-08-28): all five unit shards, Joni, packaging, and shadow JAR; 4m33s,
  exit 0. Log: `/tmp/make_issue1115_9daa1be73_merge.log`.
- [x] Remaining core UAT regressions have permanent system-Perl-validated
  coverage and pass on JVM/interpreter (2026-08-29).
  - `UNIVERSAL::DOES` reports `DOES` for an unblessed reference.
  - Undeclared class names inherit explicit `@UNIVERSAL::ISA` parents.
  - Core UAT on merged head `9daa1be73`: 4/4 files, 261/261 assertions,
100%. Log: `/tmp/issue1115_9daa1be73_core_uat.log`.

The 2026-08-29 UAT comparison exposed three real behavior regressions and two
test-corpus count differences. Commit `9b2377b6f` restores the real losses:
named subroutines ending in `defer` now supply `undef` to JVM implicit-return
cleanup, `PerlIO->import(...)` is a safe no-op instead of inheriting strict
`UNIVERSAL::import`, and repeated `$#array` lvalues remain writable on both
backends. The three focused tests pass system Perl and both backends. The exact
commit passes all unit shards, Joni, packaging, and shadow-JAR generation in
3m05s (`/tmp/issue1115_uat_regressions_9b2377b6f_make.log`). The five-file UAT
slice is restored to `defer.t` 25/33, `caller.t` 96/115, `splice.t` 33/34,
`perlio.t` 38/48, and `repeat.t` 49/50
(`/tmp/issue1115_uat_regressions_9b2377b6f_runner.log`). The 11 `caller.t` and
2 `splice.t` count differences versus the supplied baseline are absent tests
in the current imported corpus, not newly failing assertions.
- [x] Complete final documentation/integration publication and PR evidence.
- [x] Mojolicious 9.49 acceptance passes on merged head `9daa1be73`
  (2026-08-28): 109/109 files, 4,194 tests, 955s, exit 0.
  - Log: `/tmp/issue1115_9daa1be73_mojolicious.log`.
  - A prior sandbox-only run was invalid because local listen sockets were
    denied; all affected server tests pass in the authoritative run.
- [x] Catalyst::Runtime 5.90132 supported acceptance passes on merged head
  `9daa1be73` (2026-08-29): 199/199 files, 3,774/3,774 assertions, 19 skips,
  9 TODOs, 2,260 elapsed seconds,
  zero failures/timeouts/incomplete files, exit 0.
  - Excluded only the real-fork-only `t/live_fork.t` from the 200-file tarball.
  - Log: `/tmp/issue1115_9daa1be73_catalyst_199_correct.log`.
- [x] DBIx::Class 0.082844 acceptance passes on merged head `9daa1be73`
  (2026-08-29): 325/325 files, 43,020 tests, 1,759 wallclock seconds, exit 0.
  - Exact command: `nice -n 10 timeout 3600 ./jcpan --jobs 8 -t DBIx::Class`.
  - Log: `/tmp/issue1115_9daa1be73_dbix_class.log`.
- [x] TAP parallel-harness regressions fixed and covered (2026-08-28).
  - Interrupted blocking `select`: system Perl/JVM/interpreter 2/2.
  - Nested process handle in durable IO::Select aggregate: system Perl,
    JVM, and interpreter 3/3; socket EOF guard remains 35/35 on both backends.
  - Revalidated on merged head `9daa1be73`; logs:
    `/tmp/issue1115_merge_{system,jvm,interpreter}_*.log`.
- [x] Mojolicious task-board route smoke passes on JVM and interpreter on
  merged head `9daa1be73` (2026-08-29): readiness, rendered `/`, `/health`,
  `/api/tasks`, and the
  three-chunk `/activity` response all pass exact body checks.
  - Logs: `/tmp/issue1115_9daa1be73_task_board_jvm_*` and
    `/tmp/issue1115_9daa1be73_task_board_interpreter_*`.
- [x] Compound-assignment evaluation-order regression passes system Perl,
  JVM, and interpreter 5/5; full `make` passes in 4m53s on the final source
  change (`/tmp/make_issue1115_compound_lvalue_fix2.log`).
- [x] The earlier uninterrupted gate on `204a9922d` completed in 14m44s with all peer
  shards and Joni complete, but shard 3's Gradle worker channel ended with
  `java.io.EOFException` and no test assertion (`/tmp/make_issue1115_204a9922d.log`).
  This was classified as a corrupt persisted Gradle result store and superseded
  by the two green gates above.
- [x] Windows CI regressions from run `33220437496` classified and fixed locally
  (2026-08-29).
  - `sysopen` is raw by default on Windows but still honors lexical `use open`.
  - Exact modes set through `sysopen`/`chmod` survive Windows `stat` emulation.
  - System Perl: 4 focused files, 17/17 assertions.
  - JVM/interpreter: PerlIO layers 8/8, sysopen mode 4/4, threaded in-place
    editing 3/3, process drain 3/3, and socket lifetime 35/35.
  - Full local gate: all five shards, Joni, packaging, and shadow JAR in 4m28s;
    `/tmp/make_issue1115_windows_fix2.log`.
- [x] Windows fix published as `b1b0494cd`; hosted run `33223173108` passes
  Ubuntu in 30m57s and Windows in 30m36s (2026-08-29).
- [x] Post-UAT runtime regressions fixed as `9b2377b6f` (2026-08-29).
  - Three permanent tests pass system Perl and JVM/interpreter (5/5 each).
  - Exact-commit `make` passes in 3m05s; log:
    `/tmp/issue1115_uat_regressions_9b2377b6f_make.log`.
  - The five reported files retain every assertion present in the current
    corpus; the only baseline count differences are 13 absent imported tests.

## Resume point

Push `9b2377b6f` and this evidence update, wait for hosted CI, then verify PR
#1129 remains open and retains `Fixes #1115`. No implementation work remains;
after CI, review and merge according to normal repository policy. Real
fork/prefork follow-up remains tracked in #1144.
