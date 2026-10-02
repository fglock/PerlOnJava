# High-impact issue batch (October 2026)

## Goal

Fix five currently open high-impact issues outside the separately handled
`area:parser` work and the standard test-suite tracking issue #1269:

1. [#1597](https://github.com/fglock/PerlOnJava/issues/1597) — JVM backend
   hangs in MIME::Decoder's `x-gzip64` test.
2. [#1336](https://github.com/fglock/PerlOnJava/issues/1336) — Math::Decimal's
   pure-Perl suite still times out in `t/cmp_pp.t` after the earlier
   `t/add_pp.t` improvement.
3. [#1278](https://github.com/fglock/PerlOnJava/issues/1278) — keep bundled
   CPAN module versions and provider compatibility in sync through a checked,
   reviewable update workflow.
4. [#1177](https://github.com/fglock/PerlOnJava/issues/1177) — extend
   Object::Pad compatibility to `:accessor` fields.
5. [#1187](https://github.com/fglock/PerlOnJava/issues/1187) — complete
   directory-handle `chdir` support after the recent regular-filehandle fixes.

Issue #1611 was initially considered because its CPAN failure has broad
dependant reach. Its reproducer currently fails on the `eof ... || ...`
expression that overlaps parser issue #1599, so it is left to the separate
parser work. Issue #1470 also carries `area:parser` and is excluded. The
remaining open high-impact issues are not automatically in this batch; the
five above are selected for confirmed compatibility failures and measurable
downstream impact.

## Reviewed status (2026-10-02)

The goal remains all five issues. No issue is declared complete merely because
its basic reproducer or the broad unit suite passes. Work is grouped so the
runtime changes can be compiled and integration-tested together, while Perl-only
updater fixtures run without a JVM rebuild.

| Issue | Current evidence | Remaining acceptance work |
| --- | --- | --- |
| #1597 | Project-owned pipe regression passes system Perl and both backends. MIME-tools 5.519 `t/Decoder.t` passes all 8 assertions on JVM; interpreter evidence exists from the same candidate family. Explicit close removes the registered descriptor. | Re-run the unchanged upstream decoder test on both final backends and confirm no child process remains. |
| #1177 | Fourteen accessor assertions pass Perl 5.42 with an isolated Object::Pad 0.825 oracle and both PerlOnJava backends. The regression skips only when `Object/Pad.pm` itself is missing. Legacy `has` is limited to class-feature bodies. | Recheck the parser against ordinary Perl `has`, verify invalid combinations against upstream where supported, and obtain/run MooseX::LocalAttribute `t/objectpad.t` on both backends. |
| #1187 | The new directory-handle test and existing conditional bareword test pass on both backends with runtime-local cwd. The implementation uses the absolute path captured by `opendir`; it does not preserve directory identity after rename/unlink. | Re-run both tests on the final candidate and keep rename/unlink semantics documented as a separate limitation. Never call process-global `fchdir`. |
| #1336 | The selected Java unit shard passed with the direct-scalar ordering and target-walk counter test. It verifies a live scalar is checked before graph fallback and an early target visits one graph node. The earlier full `cmp_pp.t` attempt timed out at assertion 41,048; that is not a performance measurement. | Add deterministic work counts for scalar-root inspection and repeated/multi-target fallback queries. Retain only an algorithm with bounded work for both single-target and multi-target drains, then run the full upstream correctness suite on both backends. |
| #1278 | `dev/import-cpan/registry.json`, `sync.pl` and `make update-bundled-modules` are implemented. Default report and `CHECK=1` pass. The fixture suite passes 25 tests, including drift in Perl/Java/backing-library contracts, Object::Pad 0.800/0.805, a newer Compress::Raw::Zlib requirement, dependency overlays, checksum failure, source staging and traversal rejection. | Run the final offline tests and real registry check. The online MetaCPAN endpoint remains unexercised; rewritten providers stay manual-port only. |

The last unfiltered `nice -n 19 make` predates the current Java regression test.
The selected unit shard compiled the current Java candidate, but that is not the
final integration gate. Do not use elapsed build or test time as a performance
acceptance signal on this production host.

## Corrections to the approach

- Batch source changes before the next full build. Use deterministic work
  counters and focused correctness checks during investigation; run the
  unfiltered gate once for a coherent candidate.
- Removing the cached full-root query is an experiment, not an established fix.
  The deterministic tests currently check direct-scalar branch order and graph
  node visits for an early target only. Add counts for the live-scalar scan and
  repeated/multi-target paths before accepting the algorithm; do not infer
  improvement from wall-clock results collected on this production host.
- Directory-handle `chdir` resolves through the absolute path saved by
  `opendir`, while keeping virtual cwd isolated per Perl runtime. This meets the
  reported open-handle chdir case without changing process cwd, but does not
  preserve inode identity after rename or unlink. Treat that as follow-on work;
  do not claim native `fchdir` semantics for this implementation.
- The Object::Pad test now skips only when Object::Pad.pm is absent. Dependency,
  compilation, and import errors fail, and the focused oracle passed using the
  isolated upstream 0.825 build plus its local XS dependencies.
- #1114 specifies the common updater design. Its required shared component is
  implemented in `dev/import-cpan/`; the fixtures must also exercise each
  registered version contract, including Java and backing-library versions.
- Keep each candidate's logs under distinct names. Do not overwrite baseline
  or failure evidence with a later build's log.

## Execution plan

### 1. Preserve evidence and define acceptance before further changes

- [x] Let the interrupted build and its workers finish before editing the tree.
- [x] Review the actual code, captured failures and issue requirements.
- [x] Record current status and the corrected plan in this document.
- [x] Preserve focused test/build logs and record known failing-parent or
  incomplete-acceptance evidence before further algorithm experiments.
- [x] Record each issue's project-owned regression and current acceptance gap.
  Existing tests remain unchanged.

### 2. Complete the three compatibility fixes as one implementation batch

For **#1597**, retain the demonstrated process-pipe diagnosis. Check every
fileno registration and cleanup path touched by the fix: temporary aliases,
explicit close and anonymous handle disposal. Preserve the ready-handle
regression and add meaningful lifetime coverage if the existing tests leave a
gap. Require the unchanged MIME decoder test on JVM and interpreter, with gzip
actually exercised, and no leaked child processes or descriptor registrations.

For **#1177**, install the matching Object::Pad oracle into an isolated temporary
system-Perl prefix. A skipped oracle run is not behavioral validation. Compare
getter/setter return values, `undef` and false setters, named accessors, leading
underscore names, independent instances, inheritance and invalid declarations
against upstream behavior. Check whether the current reader/writer conflict
restriction matches that oracle. Scope legacy `has` recognition to the intended
Object::Pad import context; inspect its interaction with ordinary core classes.
Run the reported MooseX::LocalAttribute `t/objectpad.t` consumer on both backends.
Keep the advertised 0.66 compatibility version until broader support is proven.

For **#1187**, meet the reported contract: `chdir` accepts an open directory
handle and updates the current directory used by that Perl runtime. Keep cwd
changes runtime-local; do not call process-wide native `fchdir`. Preserve
Windows failure behavior, advertise the conditional capability only where this
contract works, and pass the existing conditional bareword test unchanged. The
stored-path implementation's rename/unlink identity limitation is recorded
above and is outside this issue's reported reproducer.

### 3. Implement #1278 through the shared CPAN workflow

Implement one registry and `dev/import-cpan/sync.pl`, following the ownership
boundary in [#1114](https://github.com/fglock/PerlOnJava/issues/1114). Perl-core
imports remain owned by `dev/import-perl5/`.

Deliver the workflow in these dependent steps:

1. Define registry records for distribution provenance, package compatibility,
   wrapper/backend version contracts, port mode, patches and test mappings.
   Start with the two compression providers and the partial Object::Pad port.
2. Implement an offline consistency check. Compare only fields explicitly
   sharing a contract; distinguish upstream, wrapper, Java API and underlying
   library versions. The current 2.224 compression state should pass, while
   deliberately drifted fixtures must fail.
3. Implement online stable-release discovery separately from consistency,
   with injectable local metadata/archive fixtures. Verify archive identity,
   checksums and safe extraction; stage source/API/test differences outside
   the checkout. Preserve rewritten implementations and require manual porting
   when API changes are not covered by a validated import recipe.
4. Implement dependency checks at the resolution point that knows the required
   version and effective bundled provider, including dependency `blib` overlays.
   Reject insufficient providers before cascading consumer failures; report the
   module, supported and required versions, and update/manual-porting action.
   Cover old compression providers and Object::Pad 0.66 versus requirements
   0.800/0.805. Downloaded distribution versions must not masquerade as the
   effective bundled version.
5. Expose `make update-bundled-modules` with report/dry-run default, `CHECK=1`,
   `UPDATE=1` and module selection. Delegate entry points to the same updater.
   For rewritten providers, stage a manual-porting plan and reject `--apply`;
   no automated copy into tracked files is supported. Any future apply path must
   require its own validated import recipe and isolated validation gate.
6. Add offline tooling tests for selection, drift, fixture provenance, rewritten
   wrapper preservation, effective-provider mismatches and failure atomicity.
   Use fake validation gates for updater unit tests; do not run real full builds
   for each failure fixture. Validate actual provider identity and test discovery
   in end-to-end acceptance.

The updater can be developed and checked with host Perl before rebuilding the
JVM. The issue is not complete with only a version checker, version-string bumps
or a document referring to #1114.

### 4. Resolve #1336 with deterministic algorithmic evidence

- Treat this host's elapsed-time measurements as unreliable. Use fixed graph
  fixtures, test-only visit counters, and branch-proof assertions to compare
  work directly. A test timeout is only a runaway guard and must not be used as
  a speed threshold.
- Use the existing JFR recording to identify candidate hot paths, then assert
  how many scalar roots are inspected and how many graph nodes are visited for
  single-target and multi-target drains. In particular, prove that a direct
  scalar avoids external-root and graph construction, and that target queries
  stop at an early match. If repeated target-specific walks exceed one cached
  graph traversal for a drain, retain a batch-aware fallback. Avoid new
  wall-clock performance claims on this host.
- Preserve strong/weak ownership, closure and container roots, invalidation,
  destruction timing and runtime/thread isolation. Add permanent project-owned
  coverage for the externally observed regression and affected lifetime
  behavior; existing lifecycle tests are required adjacent coverage.
- Run all 62,210 `t/cmp_pp.t` assertions on both backends as a correctness
  check, followed by the affected pure-Perl suite. Capture the completed
  assertion counts and outcomes; use a generous hard timeout only to stop a
  true hang, and draw no performance conclusion from whether the guard fires.

### 5. Validate and finish the coherent batch

1. Run host-Perl oracle checks and offline tooling tests first. Compile the
   combined runtime candidate once through a suitable filtered `make` invocation,
   then run focused backend and upstream acceptance checks on that artifact.
2. Resolve failures in a related implementation batch before another build.
   Preserve the source candidate while any build or reader is running; keep
   shared-JAR writers sequential with readers. Use timeouts and full log capture.
3. Once all five issues meet their focused criteria, run unfiltered
   `nice -n 19 make`, the required bundled/provider checks and documentation
   checks. Do not repeat passing expensive gates without a source change,
   failure or unresolved concern that requires them.
4. Review the final diff, remove diagnostic remnants, check process cleanup,
   and ensure changelog/feature claims match the demonstrated support. Update
   this document with exact results, remaining limitations and next steps.
5. Mark the goal complete only when all five issues have their acceptance
   evidence. A green broad suite cannot substitute for deterministic work-count
   evidence, a missing updater gate or incomplete directory-handle semantics.

## Next actions

1. Add deterministic scalar-scan and multi-target counts for #1336; revise the
   reachability strategy if repeated fallbacks grow beyond one traversal per
   drain. Keep the existing lifecycle semantics tests adjacent.
2. Extend `dev/import-cpan/t/sync.t` to prove Java XS-version and Commons
   Compress catalog drift fail, then run `make test-import-cpan` and
   `make update-bundled-modules CHECK=1`.
3. Gather and run the reported MooseX::LocalAttribute test, and rerun MIME-tools,
   directory-handle and Object::Pad tests on both backends using one compiled
   candidate. Capture logs with separate names and hard timeouts.
4. After acceptance gaps close, run the single final `nice -n 19 make`, provider
   checks and required Markdown link checks; review changelog claims and update
   this document with results and limitations.

## Progress Tracking

### Current status: implementation in progress (2026-10-02)

### Completed phases

- [x] Phase 1: issue scope and acceptance review (2026-10-02)
  - Excluded `area:parser`, #1269, and the user's separate #1269/#area:parser
    work as directed.
  - Recorded current evidence, explicit gaps, production-host timing limits,
    and a batched validation order.
- [x] Phase 3 implementation: offline CPAN registry and staging workflow
  (2026-10-02)
  - Added `dev/import-cpan/registry.json`, `sync.pl`, fixture tests, and
    `update-bundled-modules` / `test-import-cpan` Make targets.
  - Perl fixture suite: 25 tests pass; real registry `CHECK=1` passes.

### In progress

- Phase 2: fixes for #1597, #1177, #1187 are implemented and have focused
  evidence; final upstream and consumer acceptance runs remain.
- Phase 4: #1336 candidate and direct/early-target unit coverage exist. Scalar
  root scan and multi-target work counts remain open before acceptance.
- Phase 5: final combined runtime build and issue-specific acceptance gates
  remain.

### Next steps

See [Next actions](#next-actions) above; batch runtime edits before the one
final unfiltered build.

### Open questions

- Can the exact MooseX::LocalAttribute consumer distribution be obtained and
  run without relying on unavailable network access from the shell?
- Do deterministic single-target and multi-target counts justify the current
  #1336 fallback strategy, or should the drain retain one cached graph walk?

## Related guidance

- [Issue triage](../../docs/guides/issue-triage.md)
- [PerlOnJava debugging workflow](../../.agents/skills/debug-perlonjava/SKILL.md)
- [Batching and validation](../../.agents/skills/debug-perlonjava/references/testing-cadence.md)
- [Runtime profiling workflow](../../.agents/skills/profile-perlonjava/SKILL.md)
