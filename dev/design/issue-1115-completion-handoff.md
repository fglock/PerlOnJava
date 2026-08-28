# Issue #1115 Completion Handoff

## Current checkpoint

PR #1129 is open. `fix/issue-1115-mojolicious` is locally at `81cc2a3d0`, two
commits ahead of its remote, with the completed `UNIVERSAL` work, final
process-pipe ownership fix, permanent regressions, and focused evidence.
The validation branch
`wip/issue-1115-does-20260828-100139` contains the completed work plus two
process-pipe WIP snapshots. Its worktree has an uncommitted failed experiment;
the required patch/status backups were captured at
`/tmp/wip-unstaged-20260828-172026.patch`,
`/tmp/wip-staged-20260828-172026.patch`, and
`/tmp/wip-status-20260828-172026.txt`, but its linked-worktree Git metadata is
not writable from this session, so do not mutate or discard that checkout.

## Active diagnosis

The JVM truncation is fixed and committed. JVM and interpreter focused
runs both drain all 65,536 stderr bytes with empty stdout and child exit 0.
The fix materializes only anonymous-I/O aliases before JVM lexical cleanup,
preserves all non-I/O return identity, transfers the holder to the caller's
final return copy, and establishes ownership when `IPC::Open3` creates each
process handle. Stdout and stderr remain distinct `ProcessInputHandle`
instances and descriptor registrations.

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
- [x] Clean immutable full gate passes on `81cc2a3d0` (2026-08-28): all five
  unit shards, Joni, packaging, and shadow JAR; 9m19s, exit 0. A gate with the
  final `UNIVERSAL` fix also passes in 5m28s: `/tmp/make_issue1115_universal_fix.log`.
- [x] Remaining core UAT regressions have permanent system-Perl-validated
  coverage and pass on JVM/interpreter (2026-08-28).
  - `UNIVERSAL::DOES` reports `DOES` for an unblessed reference.
  - Undeclared class names inherit explicit `@UNIVERSAL::ISA` parents.
  - Core UAT: 4/4 files, 261/261 assertions, 100%.
- [ ] Complete framework acceptance, integration history, and final evidence.
- [x] The earlier uninterrupted gate on `204a9922d` completed in 14m44s with all peer
  shards and Joni complete, but shard 3's Gradle worker channel ended with
  `java.io.EOFException` and no test assertion (`/tmp/make_issue1115_204a9922d.log`).
  This was classified as a corrupt persisted Gradle result store and superseded
  by the two green gates above.

## Resume point

Commit the focused `UNIVERSAL` regression, runtime fix, and this handoff update
separately with Codex attribution. Then run framework acceptance sequentially:
Mojolicious, the 199-file supported Catalyst manifest, DBIx::Class, and the
task-board JVM/interpreter smoke test. Finish integration-history cleanup,
rerun `make` on the exact final commit, update evidence docs, push, and update
PR #1129 only when every required gate is green.
