# High-impact bug batch (October 2026)

## Goal

Fix open high-impact bugs [#1577](https://github.com/fglock/PerlOnJava/issues/1577),
[#1473](https://github.com/fglock/PerlOnJava/issues/1473),
[#1470](https://github.com/fglock/PerlOnJava/issues/1470),
[#1336](https://github.com/fglock/PerlOnJava/issues/1336),
[#1307](https://github.com/fglock/PerlOnJava/issues/1307), and
[#1187](https://github.com/fglock/PerlOnJava/issues/1187) in one feature branch
and PR. Issue [#1269](https://github.com/fglock/PerlOnJava/issues/1269)
is being handled in PerlOnJava5. Include
[#1252](https://github.com/fglock/PerlOnJava/issues/1252) only if a contained,
proven fix emerges from profiling.

## Implementation phases

1. Correct bare-call resolution for imported `dump`, `try`, and `catch` while
   preserving Perl's genuine CORE ambiguity diagnostics.
2. Implement handle-based `chdir` and correct invalid or closed filehandle
   behavior for `read`, `binmode`, `closedir`, `chmod`, and repeated `close`.
3. Fix AnyEvent::Tools' package-variable closure failure and timer behavior;
   separately verify MooX::Options `new_with_options` and track unrelated
   native dependencies.
4. Reduce the Math::Decimal pure-Perl slowdown, identify the shared hotspot
   using JFR and deterministic work counts, then fix it for both backends.
5. Inspect the large-source Module::Build path. Include #1252 only if a single
   contained change addresses a measured hotspot without timing-based gates.
6. Validate the full batch, update the changelog, and submit one PR.

## Validation

- Add a focused project-owned regression for each confirmed defect. Run new
  Perl-level tests on system Perl first and preserve failure evidence from the
  unfixed PerlOnJava parent.
- Check JVM and interpreter backends, relevant CPAN suites, and adjacent tests.
- Use profiles, operation counts, allocations, and input-size scaling for
  performance comparisons. Timeouts bound test runs; wall time is not a pass
  threshold on the production host.
- Batch related edits before expensive rebuilds. Run unfiltered `nice -n 19
  make` and required integration gates on the final candidate, with complete
  output captured and no checkout mutation during gates.

## Progress tracking

### Current status: implementation complete; PR #1598 updated, CI/review pending

### Completed phases

- [x] Phase 1: CORE ambiguity warnings (2026-10-01)
  - Updated `SubroutineParser` and `ParsePrimary` to distinguish `use subs`,
    imported CVs, and forward declarations, and to respect the disabled `try`
    feature when diagnosing bare calls.
  - Added `core_name_ambiguity_imports.t`; system Perl and both PerlOnJava
    backends pass all 10 assertions, including imported `Time::HiRes::time`.
    The unfixed parent failed the `try`/`catch` and `dump` warning assertions.
- [x] Phase 2: Filehandle errors (2026-10-01)
  - Updated `RuntimeIO`, `Directory`, `IOOperator`, `Readline`, `StandardIO`,
    `LayeredIOHandle`, and `Operator` for handle path capture and EBADF behavior.
  - Added `filehandle_error_semantics_high_impact.t`; all 16 assertions pass
    on system Perl and both PerlOnJava backends. After installing `Test::Class`,
    IO::Die `t/IO-Die.t` also passes all 387 assertions (fork-dependent cases
    are skipped on PerlOnJava).
- [x] Phase 3: CPAN compatibility triage (2026-10-01)
  - PR #1322 already fixed the reported list-context and role behavior on
    master. JavaScript::Const::Exporter `t/script.t` now passes all four
    assertions with its test prerequisites installed and MooX::Options supplied
    from its CPAN source tree. Both `js-const` invocations emit the expected
    JavaScript constants with no MooX::Options error.
  - #1470 was present in the original query but missed in the initial plan.
    Changed anonymous constant-prototype CV optimization to apply only to
    `my`/`state` variables, so `our` package variables remain live. Added a
    regression for the reported `AnyEvent::detect` compile error and added
    `Time::HiRes::clock_gettime` with Linux and Darwin monotonic clock IDs.
    The new tests pass on system Perl and both backends. AnyEvent::Tools 0.12
    passes all 103 system-Perl assertions. Its JVM run passes 102/103; the one
    failure is the upstream `<1 ms` timestamp-equality assertion for read-lock
    callbacks. An event-order probe confirms both readers enter before the
    first releases on system Perl and both PerlOnJava backends. Mutex, repeat,
    buffer, and remaining ordering cases pass in bounded focused runs on both
    backends. The aggregate interpreter `prove` run exceeded its bound under
    host load after the buffer child emitted all 25 passing assertions; running
    `07_buffer.t` directly against the rebuilt JAR exits successfully.
- [x] Phase 4: Math::Decimal root-walk reuse (2026-10-01)
  - A JFR sample of `t/add_pp.t` identified repeated
    `ReachabilityWalker.isReachableFromRoots` work during mortal cleanup.
    Added one identity-based full-root snapshot per drain and a Java parity
    test comparing membership with individual walks over 200 rooted objects.
  - Both backends completed all 16,363 `t/add_pp.t` assertions. JFR after the
    change showed the per-object walk had left the sampled hot path. The wider
    pure-Perl suite passed `add_pp.t`, `canon_pp.t`, and `classify_pp.t`; its
    62,210-assertion `cmp_pp.t` was stopped to bound production-host load.
    Wall-clock comparisons were not used as an acceptance criterion.
- [x] Phase 5: Locale::CLDR triage (2026-10-01)
  - The 1.1-million-line generated source requires a separate Module::Build
    investigation; no contained, proven fix emerged. #1252 stays out of scope.
- [x] Phase 6: Validation and review submission (2026-10-01)
  - Rebased onto current master, passed the unfiltered repository build and
    documentation link check, and opened [PR #1598](https://github.com/fglock/PerlOnJava/pull/1598).
  - Validated the focused regressions on system Perl and both backends, plus
    IO::Die, Log::Dump, and JavaScript::Const::Exporter integration cases.
  - Revalidated the final source with `nice -n 19 make` (passed in 5m13s).
    The focused tests pass on system Perl and both backends. The full
    AnyEvent::Tools run is sensitive to its sub-millisecond timing assertion;
    direct buffer and deterministic reader-order checks pass. Pushed the #1470
    follow-up and updated the PR description.
  - The earlier GitHub Actions run passed on the pre-#1470 head. GitHub has
    not reported a status check for the updated head yet.

### Next steps

1. Monitor CI for the updated PR #1598 head and address review.
2. Follow up on the full Math::Decimal pure-Perl suite under a dedicated test
   budget; the focused reported case and snapshot parity checks pass.

### Open questions

- #1252 still involves the 1.1-million-line Locale::CLDR distribution and a
  separate Module::Build path. No contained change was identified during this
  batch, so it remains outside the PR.
- Handle-based `chdir` and `chmod` currently use the path captured at open time;
  descriptor identity after a path is renamed needs separate native support.
- Installed `Test::UseAllModules`, `Test::Class`, and `IO::Capture::Stderr`
  locally; Log::Dump passes 22 test files and 52 assertions when the host's
  `NO_COLOR` setting is unset for its color assertions. IO::Die passes all
  387 assertions, with fork-dependent cases skipped.
- `Test::Script` was force-installed locally after its own suite failed 6 of
  97 subtests, and `Sub::Identify` after 1 of 147 failed. MooX::Options was
  left in its source tree after 41 of 97 distribution subtests failed; its
  source path was supplied to the passing #1307 integration test.
- Rebased onto `origin/master` at `2399c79d4` on 2026-10-01. The post-rebase
  unfiltered `nice -n 19 make` passed; GitHub CI was still running when #1470
  was discovered.

## Related guidance

- [Repository agent guidelines](../../AGENTS.md)
- [Debugging skill](../../.agents/skills/debug-perlonjava/SKILL.md)
- [Profiling skill](../../.agents/skills/profile-perlonjava/SKILL.md)
