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
5. [#1187](https://github.com/fglock/PerlOnJava/issues/1187) — fix open-handle
   and invalid-descriptor semantics, including directory `chdir`, `chmod`,
   `read`, `binmode` and `closedir` behavior.

Issue [#1611](https://github.com/fglock/PerlOnJava/issues/1611) was initially
considered because its CPAN failure has broad dependant reach. Its reproducer
currently fails on the `eof ... || ...` expression that overlaps parser issue
[#1599](https://github.com/fglock/PerlOnJava/issues/1599), so it is left to the
separate parser work. Issue [#1470](https://github.com/fglock/PerlOnJava/issues/1470)
also carries `area:parser` and is excluded. The
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
| #1597 | The project-owned pipe regression passes system Perl and both backends. MIME-tools 5.519 `t/Decoder.t` completes on JVM and interpreter with the `x-gzip64` path exercised; the optional BinHex case is unavailable because `Convert::BinHex` is absent. Explicit close unregisters the descriptor. | None for the reported reproducer. |
| #1177 | Fourteen accessor assertions pass the isolated Object::Pad 0.825 oracle and both PerlOnJava backends. The regression skips only when `Object/Pad.pm` itself is missing. Legacy `has` is limited to class-feature bodies. MooseX::LocalAttribute 0.05 `t/objectpad.t` passes on both backends. | None for scalar accessors and the reported consumer. The advertised compatibility level remains 0.66; broader Object::Pad MOP support is outside this fix. |
| #1187 | The 16-assertion `filehandle_error_semantics_high_impact.t` passes system Perl, JVM and interpreter, covering handle `chdir`/`chmod`, closed-handle `binmode`/`chmod`, read from write-only `STDOUT`, and closed `closedir`. The separate directory-handle test and unchanged conditional bareword test pass on both backends with runtime-local cwd. A direct probe reports `Bad file descriptor` in both `$!` and `$^E` after closed `closedir`. Directory `chdir` uses the absolute path captured by `opendir`; it does not preserve directory identity after rename/unlink. | None for the reported cases. Rename/unlink identity and native `fchdir` semantics remain outside scope; never call process-global `fchdir`. |
| #1336 | Seven tracked reachability tests assert direct-scalar ordering, scalar snapshot reuse, target short-circuiting, multi-target graph snapshot reuse and invalidation, shared-graph visit counts, cycle preservation for direct and descendant cycles, and skipping live weak referents. All ten Math::Decimal 0.004 `*_pp.t` files pass on system Perl, JVM and interpreter: 107,058 system-Perl assertions (with one optional pod-coverage skip) and 107,059 assertions on each PerlOnJava backend. `cmp_pp.t` contributes 62,210 passing assertions per backend. | None for the reported pure-Perl suite. A graph that exceeds the 50,000-node cap conservatively retains weak referents for that quiet sweep; this avoids clearing uncertain cycles but may defer cleanup. Timing was not used as evidence. |
| #1278 | `dev/import-cpan/registry.json`, `sync.pl` and `make update-bundled-modules` are implemented. Default report, `CHECK=1`, and all 25 fixture tests pass; fixtures cover provider drift, Object::Pad 0.800/0.805, newer Compress::Raw::Zlib requirements, overlays, checksum errors, staging and traversal rejection. The bundled compression providers already advertise 2.224, and the `IO::Compress` 2.224 prerequisite test passes. | `jcpan -t IO::Compress` reaches the full 25,615-test suite, which has 211 failures in the same seven test programs on the candidate and its pre-merge base `a1e07480e`. Those existing compatibility failures are outside the provider updater changes. The live MetaCPAN endpoint was not exercised; rewritten providers remain manual-port only. |

The final unfiltered `timeout 3600 nice -n 19 make` passed after the reachability
changes, including the descendant-cycle regression. The production host denied
the requested niceness adjustment, but the command continued and passed. Do not
use elapsed build or test time as a performance acceptance signal on this host.

## Corrections to the approach

- Batch source changes before the next full build. Use deterministic work
  counters and focused correctness checks during investigation; run the
  unfiltered gate once for a coherent candidate.
- Keep the first root query target-specific, then reuse a direct scalar
  referent set and one full graph snapshot if further targets need fallback.
  Deterministic tests assert traversal/build counts and invalidation. Do not
  infer improvement from wall-clock results collected on this production host.
- Batch quiet-sweep cycle analysis into one strong-edge graph and SCC pass.
  Select weak referents that can reach any strong cycle, then protect their
  strong descendants. This retains the prior behavior for an acyclic weak
  target that leads to a cycle. If graph construction hits its 50,000-node
  bound, conservatively retain all weak referents for that sweep.
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
- Assert how many scalar roots are inspected and how many graph nodes are
  visited for single-target and multi-target drains. In particular, prove that
  a direct scalar avoids external-root and graph construction, target queries
  stop at an early match, and later non-direct targets reuse one root snapshot.
  Avoid new wall-clock performance claims on this host.
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

1. Keep PR [#1628](https://github.com/fglock/PerlOnJava/pull/1628) updated as
   review or CI feedback arrives. GitHub reports no checks for the branch yet.
2. Leave the live MetaCPAN endpoint and broader Object::Pad MOP compatibility
   as follow-up work; neither blocks the five reported fixes in this batch.

## Progress Tracking

### Current status: implementation and acceptance complete; PR #1628 open (2026-10-02)

### Completed phases

- [x] Phase 1: issue scope and acceptance review (2026-10-02)
  - Excluded `area:parser` issues and #1269, as directed.
  - Recorded current evidence, explicit gaps, production-host timing limits,
    and a batched validation order.
- [x] Phase 3 implementation: offline CPAN registry and staging workflow
  (2026-10-02)
  - Added `dev/import-cpan/registry.json`, `sync.pl`, fixture tests, and
    `update-bundled-modules` / `test-import-cpan` Make targets.
  - Perl fixture suite: 25 tests pass; real registry `CHECK=1` passes.
- [x] Phase 4 implementation: deterministic reachability fallback
  (2026-10-02)
  - Added first-query direct scalar probing, a per-drain strong-scalar snapshot
    for repeated queries, target-specific first graph walk, and one reusable
    full graph snapshot for later fallback targets.
  - Batched weak-referent cycle analysis into one graph build and strongly
    connected component pass. Added preservation coverage for an acyclic weak
    target that points to a strong cycle.
  - All seven `ReachabilityQueryCostTest` tests pass in the final unfiltered
    `make`; exact counters cover scalar inspection, graph builds, unique node
    visits, short-circuiting, snapshot reuse, invalidation and cycle handling.
- [x] Phase 2 acceptance: #1597, #1177 and #1187 (2026-10-02)
  - Focused regressions, MIME-tools 5.519 `t/Decoder.t`, and MooseX::LocalAttribute
    0.05 `t/objectpad.t` pass on both backends. The 16-assertion filehandle
    error semantics test also passes system Perl and both backends. A direct
    probe confirms both `$!` and `$^E` contain `Bad file descriptor` after
    closed `closedir`. Optional BinHex support is skipped because
    `Convert::BinHex` is not installed.
- [x] Phase 4 acceptance: #1336 correctness (2026-10-02)
  - All ten Math::Decimal 0.004 `*_pp.t` files complete on system Perl, JVM and
    interpreter. The JVM and interpreter each pass 107,059 assertions; system
    Perl passes 107,058 with its optional pod-coverage test skipped. This
    includes all 62,210 `t/cmp_pp.t` assertions on both PerlOnJava backends.
    A prior 30-minute guard ended an incomplete JVM attempt at assertion
    52,506; the final merged-tree run completed under its configured per-file
    and command guards. Neither timeout nor elapsed time is used as a
    performance claim.
- [x] Phase 5: combined validation (2026-10-02)
  - `timeout 3600 nice -n 19 make` passes. The updater fixture suite passes 25
    tests and the real registry `CHECK=1` is consistent. The full `IO::Compress`
    CPAN test failure set was reproduced on the pre-merge base, confirming
    those 211 failures are pre-existing. After merging current master, the
    final `make` and all ten Math::Decimal pure-Perl files pass on both
    PerlOnJava backends.

### In progress

- Await review and any checks or feedback on PR #1628. The live MetaCPAN
  endpoint remains an explicit follow-up.

### Next steps

Maintain PR #1628 through review and address any requested changes.

### Open questions

- None block the five fixes. Run a separate online integration check for the
  MetaCPAN endpoint when network access is available.

## Related guidance

- [Issue triage](../../docs/guides/issue-triage.md)
- [PerlOnJava debugging workflow](../../.agents/skills/debug-perlonjava/SKILL.md)
- [Batching and validation](../../.agents/skills/debug-perlonjava/references/testing-cadence.md)
- [Runtime profiling workflow](../../.agents/skills/profile-perlonjava/SKILL.md)
