# CPAN Release Acceptance Plan

## Goal and scope

Pass all in-scope tests selected by `make test-cpan-release-acceptance`, fixing
reusable runtime, bundled-module, and tooling behavior. Keep PR
[#1628](https://github.com/fglock/PerlOnJava/pull/1628) updated with validated
commits and accurate acceptance evidence.

The agreed exclusions are issues labeled `area:parser`, issue
[#1269](https://github.com/fglock/PerlOnJava/issues/1269), and tests that require
unsupported `fork`. Confirm each exclusion against its root cause. Catalyst
must run its normal upstream suite; a distribution-wide skip is unacceptable.
The Mojolicious `t/mojo/dom.t` OOM and runaway behavior remain in scope.

This is a production host. Use deterministic allocation, retained-byte, and
traversal counts when evaluating performance changes. Elapsed time is not
performance acceptance evidence; timeouts remain runaway guards.

## Current evidence (2026-10-04)

Acceptance run 10 used source commit `92742e438`. The full build and unit gate
passed, the installed launcher distribution was refreshed, and its JAR passed
the check for absence of `Catalyst-Runtime.yml`. The acceptance target exited
with status 2 and remains incomplete.

| Target | Recorded result | Remaining work |
| --- | --- | --- |
| PPR | Pass | Retain this baseline; rerun in final integration. |
| Template | Pass | Retain this baseline; rerun in final integration. |
| Excel::Writer::XLSX | Pass: 1,247 files, 5,138 tests | Retain this baseline; rerun in final integration. |
| Catalyst | A separate focused isolated run executed Catalyst-Runtime normally in an isolated CPAN home: 200 programs, 3,798 tests; two failures are the documented `area:parser` cases in `http_exceptions*.t`, and `live_fork.t` is the agreed fork exception | No in-scope failure remains. The shared default CPAN home was being rewritten by a separate tester in a sibling checkout, so acceptance runs must use a unique `PERLONJAVA_HOME`. |
| Mojolicious | Run 10 timed out after 5,400 seconds with repeated warnings at `t/mojo/dom.t` line 1489. After the runtime fix, unchanged upstream `t/mojo/dom.t` passed 132 top-level subtests and 1,418 assertions with no warning or OOM matches. | Run the full Mojolicious acceptance target in the final coherent gate. |
| Image::ExifTool | Run 10 reported `NOT TESTED`, 3/113 failed programs, and 2/595 failed subtests. Focused upstream `t/XMP.t` test 3 originally reproduced a write-and-reparse mismatch; the reduced test sequence now passes on system Perl, JVM, and interpreter. Focused `t/Writer.t` reproduces test 38's Sony metadata-copy mismatch, then reaches its 30-minute guard at test 54. | The in-scope XMP root cause is fixed and focused coverage passes. Retest the complete target in the final gate. The Sony transliteration range error and dependent Writer failures are the documented `area:parser` exclusion. |
| DateTime | 1/51 failed programs, 0/3,518 failed subtests; `t/10subtract.t` exited without a TAP plan | Confirm the relationship to excluded issue #1269. |
| DBIx::Class | 309/325 failed programs, 2/1,722 failed subtests | Classify the compilation error in `t/lib/DBICTest/RunMode.pm` that cascades through most files, then classify the remaining failed assertions. |

The reports, gate transcript, recreated Catalyst preference, and full tester
logs are saved under:

```text
build/reports/cpan-release-acceptance-20261004/run-10/
```

The compressed tester archive is approximately 478 MB; archive creation exited
successfully. The raw tester directory is
`/tmp/cpan_random_logs/20261004-033003-95136/`. Preserve these baseline artifacts
and use distinct paths for later candidates.

## Execution plan

### 1. Resolve Catalyst's active preference source — complete (2026-10-04)

- Run 10's preference was recreated by a concurrent CPAN tester in the sibling
  checkout `/Users/fglock/projects/PerlOnJava`; it shares the default CPAN home.
  The external process was left untouched. Subsequent CPAN work is isolated in
  a unique `PERLONJAVA_HOME`, with cached CPAN sources shared read-only by
  symlink.
- The refreshed launcher JAR contains no owned `Catalyst-Runtime.yml`; the
  existing configuration bootstrap removes the stale signed preference while
  preserving user-owned preferences. A fresh isolated home also starts with
  no Catalyst preference or cached skip state.
- Isolated normal Catalyst execution completed all 200 programs and 3,798
  assertions. The two `http_exceptions*.t` failures reproduce a Data::Dump
  regex syntax error already documented as `area:parser`; `live_fork.t` is the
  agreed fork-dependent exception. No distribution-wide skip was used.

### 2. Reduce and fix the Mojolicious loop — complete (2026-10-04)

- Use the saved warning location to isolate the smallest failing DOM operation.
  Compare the result with system Perl and identify the first state divergence.
- Initial reduction: `<ul><li><ol><li>F<li>G</ol><li>A</li></ul>`. System Perl
  parses it with no warnings. The PerlOnJava JVM backend emits repeated
  undefined-parent warnings in `Mojo::DOM::HTML::_start`; the interpreter
  backend parses this reduced case without warnings. Keeping parser nodes alive
  for the duration of parsing also prevents the JVM failure, so reachability is
  handling was implicated. A bounded `PJ_WEAKCLEAR_TRACE` run then confirmed
  that a live `RuntimeArray` was cleared from `RuntimeScalar.setLargeRefCounted`
  through `DestroyDispatch.callDestroy` before the warning loop began.
- When a nested call overwrites a temporary alias to an unblessed array/hash
  that still has weak back-references, defer cleanup to the statement-boundary
  targeted reachability sweep. Add a self-contained project regression for the
  nested optional-list-item parent chain.
- System Perl passes the regression 4/4. The unfixed JVM candidate failed 3/4;
  the interpreter passed 4/4. After the fix, both JVM and interpreter pass 4/4.
  The full `nice -n 19 make` gate passed.
- The unchanged upstream Mojolicious `t/mojo/dom.t` passes 132 top-level
  subtests and 1,418 assertions with exit 0. Its compressed full output is
  saved at
  `build/reports/cpan-release-acceptance-20261004/dom-focused-after-fix/dom-jvm.log.gz`;
  TAP integrity and the absence of warning/OOM matches were checked. This
  output count is deterministic evidence for the runaway-diagnostics fix; no
  elapsed-time performance claim is used.

### 3. Group other failures by root cause — in progress

- Build a failure matrix from the saved logs, recording the earliest cause,
  affected targets, focused reproducer, and applicable exclusion.
- Investigate DBIx::Class's shared compilation failure once before running its
  many dependent test programs. Independently classify its two failed subtests.
- Confirm DateTime's #1269 relationship and classify Image::ExifTool's earliest
  failure. Record excluded causes explicitly instead of treating their target
  failures as unexplained acceptance gaps.
- Image::ExifTool's Sony test fails with an invalid transliteration range;
  subsequent missing Sony tag-table messages are cascading module-load
  failures. Writer tests 37-38 explicitly copy `t/images/Sony.jpg` metadata;
  test 38 writes a 664-byte file where 2.2 kB is expected. Classify this with
  the Sony parser exclusion. The focused Writer file then timed out at test 54;
  its saved output contains no further `not ok` before the 30-minute guard.
- XMP test 3 is not explained by the Sony failure: system Perl passes both
  upstream files, while the post-DOM JAR reports `Processing TIFF-like data
  after unknown 36-byte header` when reparsing the image produced in memory.
  A standalone read of the saved image scalar parses its image tags, so the
  reproducer preserves the write-and-reparse sequence and binary-string state.
- The reduction identified the first divergence in ExifTool's inverse
  `ExifVersion` conversion: numeric `232` passes through the no-op
  `$val =~ tr/.//d` and becomes `0232`. System Perl leaves this result
  unflagged; PerlOnJava marks it UTF8. The resulting IFD0 output buffer is
  upgraded, causing the later TIFF-like header error. The project regression
  passed on system Perl and failed on the unfixed JVM parent at the numeric
  UTF8-flag assertion. The fix now gives mutating and `/r` transliteration a
  byte string for non-upgraded numeric inputs, preserves an existing UTF8
  string channel, and retains UTF8 for results containing wide characters.
- A nearby dual-valued scalar probe exposed the same flag distinction in
  `dualvar`: plain string channels must stay byte strings, while upgraded
  channels and version strings retain UTF8. `Scalar::Util::dualvar` and
  `utf8::is_utf8` now preserve/observe those channels. The 21-assertion project
  regression passes on system Perl and both PerlOnJava backends; adjacent
  transliteration suites pass on both backends.
- Unchanged upstream ExifTool `t/XMP.t` tests 1-3 now pass on system Perl, JVM,
  and interpreter, including the former test 3 write-and-reparse failure.
  Focused logs and the full `make` output are saved under
  `build/reports/cpan-release-acceptance-20261004/transliteration-fix/`.
- DateTime's only failed program, `t/10subtract.t`, has all 37 assertions pass
  but exits before its TAP plan due to the issue #1269 failure already tracked
  in the core-suite reduction plan. DBIx::Class's two failed subtests are both
  `use DBICTest` failures cascading from the literal conflict marker in
  `t/lib/DBICTest/RunMode.pm`; its other failed programs share that
  `area:parser` cause. No independent DBIx::Class assertion failure remains.
- Use narrow fork-specific exceptions when required. Upgrade bundled modules
  when a validated compatibility fix needs them.

### 4. Batch implementation and focused validation

- Group related source fixes before rebuilding. Run host-Perl oracle and
  tooling checks first, then use one built artifact for focused JVM and
  interpreter regressions and affected upstream files.
- Every externally observed in-scope failure needs permanent tracked
  regression coverage, including evidence of failure on the unfixed parent.
  Preserve existing upstream tests unchanged.
- Keep checkout source immutable while any build or test gate runs. Serialize
  JAR writers with readers, use `nice -n 19 make`, wrap investigative runtime
  commands in `timeout`, and capture complete output to distinct files.
- Repeat expensive passing gates only when a source change or unresolved
  concern warrants them. Poll long runs at wider intervals and report material
  milestones.

### 5. Validate one final coherent candidate

- Once focused blockers pass, bring the PR branch up to date before establishing
  the final immutable source barrier.
- Run the full build, required documentation checks, and one complete CPAN
  release acceptance gate. Verify Catalyst actually ran its tests and that
  Mojolicious completed without the loop or OOM.
- Save the final reports and logs under a new run directory. Check process
  cleanup and record each target's outcome and any agreed exclusion.
- A passing build alone does not complete release acceptance.

### 6. Maintain the PR and completion record

- Push validated commits to PR #1628, verify that it remains open and contains
  the expected changes after any rebase, and update its description with the
  final implementation and validation evidence.
- Keep significant behavior changes in the changelog's work-in-progress
  section. Update this document after each completed phase.
- Mark the goal complete only when every in-scope acceptance requirement has
  evidence and remaining failures map to agreed exclusions.

## Progress tracking

### Current status: Rebase complete; post-rebase validation and final acceptance pending (2026-10-04)

- [x] Preserve run 10's full logs and report snapshots (2026-10-04).
- [x] Validate the bounded-output fix through the full unit gate and an OOM-free
  Mojolicious timeout run (2026-10-04).
- [x] Refresh and check the installed launcher JAR (2026-10-04); Catalyst's
  remaining preference source is unresolved.
- [x] Resolve the shared-home Catalyst skip and validate normal execution in
  an isolated CPAN home; classify its three failed programs by the agreed
  parser/fork exclusions.
- [x] Fix premature clearing of live weak-parent arrays; validate system Perl,
  both PerlOnJava backends, full `make`, and unchanged upstream `dom.t`.
- [x] Reduce and fix the Image::ExifTool XMP mismatch to transliteration,
  `dualvar`, and UTF8-flag handling; validate the 21-assertion regression on
  system Perl and both PerlOnJava backends, adjacent suites on both backends,
  and upstream XMP tests 1-3 on all three runtimes.
- [x] Classify DateTime `t/10subtract.t` under #1269 and DBIx::Class's two
  assertion failures under the documented parser conflict-marker cascade.
- [x] Rebase the 51-commit feature branch onto its current remote branch and
  preserve the newer reachability implementation, deterministic coverage, and
  unique compatibility changelog entries.
- [x] Validate the rebased candidate with `nice -n 19 make` (exit 0) and rerun
  the transliteration, Unicode, dual-channel, and Mojolicious weak-parent
  regressions on JVM and interpreter (all pass). Logs are under
  `/tmp/cpan-acceptance-rebased-focused/`; the build log is
  `/tmp/cpan-acceptance-rebase-make.log`.
- [ ] Complete final acceptance and update PR #1628.

### Next steps

1. Run `nice -n 19 make check-links` for this progress update.
2. Run `nice -n 19 make test-cpan-release-acceptance` on one immutable
   candidate with a unique `PERLONJAVA_HOME`; retain complete logs and reports.
3. Fix any newly observed in-scope failures in a coherent batch, update this
   plan, and repeat the required gates until all targets pass or map to the
   agreed `fork`, `area:parser`, or #1269 exceptions.
4. Push validated commits to PR #1628, verify it remains open with the expected
   files, and update the PR description with final acceptance evidence.

### Open questions

- Does the final acceptance run reveal any additional in-scope target failures?

## Related documents

- [High-impact issue batch](high-impact-issues-20261002.md)
- [CPAN preferences and patch layout](patch-and-cpan-prefs-layout.md)

## Delivery split (2026-10-05)

The independent reachability/lifecycle WIP includes all its fixes and new tests.
PR #1628 now contains the remaining UAT fixes. Neither split is release-qualified.
See [the durable UAT handoff](cpan-uat-handoff-20261005.md) for accurate run 11
outcomes, setup gaps, confirmed reductions, and the next immutable gates.
The goal, exclusions and requirement to fix DOM OOM remain unchanged.
