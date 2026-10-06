# CPAN UAT handoff (2026-10-05)

## Candidate scope

PR #1628 retains Object::Pad accessors (#1177), directory/handle errors (#1187),
bundled provider fixtures (#1278), transliteration/dualvar/version UTF-8 flags,
sort comparator helper tail calls, bounded captured subprocess diagnostics,
launcher refresh and bundled JAR pinning, and removal of the stale Catalyst
skip preference. All reachability and lifecycle changes, their fixes and new
regressions moved to an independent draft PR. In particular, #1597's pipe fix,
DOM parent-lifetime fix, socket ownership and #1336-related lifecycle work are
not included in this UAT candidate. Existing master tests remain intact.

The combined parent was d3f9ae5ac31b51810c0d3b83f302438e656a496a. Rebase onto
master c38ef8adf preserved newer eval/format context handling and sort comparator
frames. The split source must pass a new immutable build before publication.

## Acceptance run 11: exact qualification limits

The combined parent passed make and focused backend regressions. Its isolated
fresh-home acceptance attempt stopped at the overall eight-hour guard, exit 124.
This is not a successful release gate.

| Target | Result |
| --- | --- |
| Template | PASS: 121 files, 3,165 tests |
| PPR | PASS: 75 files, 1,253 tests |
| XLSX | PASS: 1,247 files, 5,115 tests |
| Mojolicious 9.49 | Retry: 109 files, 4,181 counted tests, 4,174 passed / 7 failed across five programs; dom.t passed |
| Catalyst-Runtime 5.90132 | Prerequisite installation exceeded 15,000-second guard before the suite; no distribution skip |
| DateTime 1.67 | Setup exceeded its guard before suite; cannot classify this as excluded #1269 |
| DBIx::Class | 309/325 programs failed; 2/1,722 assertions failed; parser cascade requires separate assertion classification |
| ExifTool 13.55 | Started, then interrupted by outer guard; no completed result |

Mojolicious final failures: asset.t subtest 20 requires unsupported fork;
json.t 25–26 computed numbers serialize as strings; websocket_lite_app.t 19
has mixed string/number output; proxy_app.t 1 and 3 have incorrect responses;
restful_lite_app.t 87 reports inactivity timeout. The initial missing gzip
prerequisite cascade was retried successfully and is not a separate runtime bug.

## Confirmed reductions and open work

Mixed arithmetic `1 + '0 but true'` and `23 + 'bar'` incorrectly set UTF-8 flags
on both PerlOnJava backends. The four-assertion reduction passes system Perl
and fails assertions 3–4 on both backends. Mojo::JSON's numeric predicate tests
this flag, explaining json.t 25–26. This is distinct from closed #1260/#1262.

A minimal Test::Mojo GET `/nothing` with Accept `image/png` passes system Perl
and JVM with the upstream default timeout. Interpreter instead exits 255 at
Mojo/Server/Daemon.pm:55 because app is undefined. A shortened five-second
probe produced a false JVM failure from cold template compilation; do not
reuse that artificial timeout as proof of a JVM defect. The full proxy/restful
failures still need reductions with default upstream settings.

## Resume sequence and completion criteria

1. Validate this exact split commit with captured `nice -n 19 make` output and
   `nice -n 19 make check-links`. Never mutate a checkout during a gate.
2. Review the linked bug tickets' inline reproductions and fix numeric flags
   and interpreter application ownership/dispatch with permanent regression
   tests. Verify Perl oracle, unfixed failure and JVM/interpreter success.
3. Reduce proxy/restful failures separately; classify DBIx assertions; complete
   DateTime and ExifTool rather than assuming setup or outer timeouts are excluded.
4. Review the independent lifecycle draft and its deterministic query counts.
   Its old combined success does not validate either new split candidate.
5. Batch fixes, build once at an immutable barrier, then run full acceptance
   in an isolated CPAN home and preserve logs. Only `area:parser`, #1269 and
   unsupported fork are authorized exclusions. Catalyst must run normally;
   DOM OOM remains an in-scope requirement in the independent lifecycle PR.
6. UAT approval does not establish release acceptance. Keep the goal open until
   all in-scope targets pass and all failures have explicit root-cause evidence.

## Related work

- [UAT PR #1628](https://github.com/fglock/PerlOnJava/pull/1628)
- [Acceptance plan](cpan-release-acceptance-plan.md)

## Investigation tickets

- [Reachability/lifecycle #1642](https://github.com/fglock/PerlOnJava/issues/1642)
- [Mixed arithmetic UTF-8 flags #1643](https://github.com/fglock/PerlOnJava/issues/1643)
- [Interpreter Test::Mojo application #1644](https://github.com/fglock/PerlOnJava/issues/1644)

## Independent UAT validation

Commit `1acfa1f80` passed full `nice -n 19 make` on 2026-10-05 (exit 0);
all unit shards and Joni packaging checks completed. `nice -n 19 make check-links`
also passed. This validates the split UAT code, not full release acceptance.

## Independent lifecycle delivery

- [Draft PR #1647](https://github.com/fglock/PerlOnJava/pull/1647)
- [Lifecycle handoff with full regression code](https://github.com/fglock/PerlOnJava/blob/wip/reachability-lifecycle-20261005/dev/design/reachability-lifecycle-handoff-20261005.md)
- [Qualification tracker #1646](https://github.com/fglock/PerlOnJava/issues/1646)

The independent lifecycle source `63b9463d5` passed full make, seven deterministic
Java tests and 22 Perl assertions on each of system Perl/JVM/interpreter. This
establishes split regression coverage; unresolved Catalyst cost and full release
acceptance remain open in #1642/#1646. The draft is not ready for merge.

## PR #1628 handoff update (2026-10-05)

The latest full Perl core run on PR #1628 is **not accepted**. Its complete log
was copied to the user-authorized sibling location
`../PerlOnJava/logs/test_20261005_140000_1628.log`; the baseline comparison is
saved at `build/reports/uat-20261005/compare_20261005_140000_1628.txt` in the
working checkout. It completed 575 files, but has three failed files and one
incomplete file. The report's 680,763 passing assertions do not make this run
green.

| File | Evidence | Handoff status |
| --- | --- | --- |
| `io/socket.t` | Full run failed bind test 4 and `setsockopt` test 25 while running in the restricted environment. A focused rerun with extended access passed 25/25. | Environment-dependent evidence; rerun in the authorized extended environment during final UAT. |
| `comp/proto.t` | Full and focused runs both emit tests 1–215, omit test 216 and the TAP plan, then exit 0. | Unresolved incomplete run. Find why the final test/plan is not reached and add a project regression if runtime behavior is responsible. |
| `comp/redef.t` | Assertion 18 fails: under `-w`, a prototype warning remains after `local $^W = 0`; the next constant-redefinition warning is expected. | Unresolved warning precedence bug. Keep `$^W` localization authoritative for `-w` while preserving explicitly lexical `use warnings`; cover both cases with project-owned tests. |
| `op/stat.t` | Assertion 94 fails for chained `-d -r _` after `stat(DIR)`. It is not one of the TTY-dependent assertions described by the comparison tool's known-flake note. | Unresolved file-test/stat-buffer behavior. Trace `_` state across the chained operators; do not classify this as a flake. |

The focused checks used to verify the first diagnosis are saved under
`/tmp/{warning-jvm,warning-interpreter,redef,proto,stat}-20261005.log`.
The trial edits to `IOOperator.fileno` and `WarnDie` did not fix the focused
core failures, and the proposed warning unit case contradicted `comp/redef.t`'s
`-w` oracle. Those trial edits were removed; do not cherry-pick them as fixes.

### Fix list for the next owner

1. **Prototype warning precedence:** reduce the difference between `-w` plus
   `local $^W = 0` and explicit lexical `use warnings` on system Perl and both
   PerlOnJava backends. Add a permanent project unit test for each behavior,
   validate both tests with system Perl first, then confirm failure on the
   unfixed parent and success on JVM/interpreter.
2. **Prototype test completion:** run `comp/proto.t` under system Perl and
   PerlOnJava with full captured output; identify why PerlOnJava exits before
   assertion 216/TAP plan. Add a minimal tracked regression if an implementation
   defect is confirmed. Keep the imported core test unchanged.
3. **Chained file tests on directory handles:** reduce `stat(DIR); -d -r _` and
   compare `$!`, the cached stat result, and each operator step against system
   Perl. Fix the owning file-test/stat-state code and add JVM/interpreter unit
   coverage; keep `op/stat.t` unchanged.
4. **Sockets:** retain the passing 25/25 extended-access focused run, then rerun
   the complete candidate's core suite with extended access so bind and socket
   options are meaningfully qualified.
5. Batch the confirmed code fixes, run the full build and focused JVM/interpreter
   gates once, then rerun the complete Perl core UAT and save a new log and
   baseline comparison. Update this handoff and PR #1628 with the resulting
   exact evidence. Until then, PR #1628 is not UAT accepted.

The PR's in-scope implementation list remains the one under Candidate scope
above: Object::Pad accessors, directory/handle errors, bundled provider
fixtures, transliteration/dualvar/version UTF-8 flags, sort comparator tail
calls, bounded captured subprocess diagnostics, launcher refresh, bundled JAR
pinning and the Catalyst preference fix. Reachability/lifecycle fixes remain
in independent draft #1647. These are delivered changes, not evidence that the
four rows above are resolved.
