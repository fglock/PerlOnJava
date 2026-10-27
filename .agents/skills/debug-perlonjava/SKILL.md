---
name: debug-perlonjava
description: Debug and fix PerlOnJava compiler/runtime test failures and regressions; use specialized regex, platform, or parity guidance when the cause is localized.
---

# Debug PerlOnJava

Read [AGENTS.md](../../../AGENTS.md) and the
[testing cadence](references/testing-cadence.md). Keep the current issue claim
and checkpoint concise. Preserve dirty work before changing branches; never
stash or discard it. Use isolated worktrees for baseline comparisons.

## Establish evidence

1. Identify exact source/JAR identity, test, input, expected result and failing
   output. Check current issue/PR state before reproducing an old report.
2. Reduce the behavior and run system Perl first. Capture complete logs and
   compare JVM and interpreter on the same input. Test newly written Perl-level
   regression coverage with Perl before relying on it.
3. Save master/unfixed-parent baselines once with source identity. A failure on
   the branch is not automatically a regression; do not repeatedly rebuild the
   same baseline or switch a checkout underneath a running gate.
4. Inspect shared parser/runtime behavior and each backend's lowering. Reuse
   established runtime operations rather than adding interpreter-only special
   cases. System Perl determines expected semantics.

## Locate the cause

Source lives in `src/main/java/org/perlonjava/`; unit tests in
`src/test/resources/unit/`, imported core tests in `perl5_t/t/`.

- For context, lvalues, closures, duplicated side effects or stale block results,
  read [diagnostics.md](references/diagnostics.md). It retains the source map,
  environment flags and focused compiler/runtime patterns.
- For parser ambiguity, compare `--parse` with `perl -MO=Deparse`.
- For lowering, compare `--disassemble` with and without `--interpreter`.
- For JVM/interpreter differences or eval/fallback paths, use
  [interpreter-parity](../interpreter-parity/SKILL.md).
- For regex ownership, Unicode or matcher behavior, use
  [debug-regex-engine](../debug-regex-engine/SKILL.md).
- For CPU/allocation/retention, use
  [profile-perlonjava](../profile-perlonjava/SKILL.md); resolve JDK tools
  explicitly (`JAVA_HOME/bin/jfr` may not be on PATH). Keep complete recordings
  and use focused temporary instrumentation only while needed.

Every investigative `jperl`, `jcpan` or `prove` invocation needs a timeout and
full output capture. Example, after building the intended source:

```bash
timeout 60 ./jperl repro.pl > /tmp/repro-jvm.log 2>&1
JPERL_INTERPRETER=1 timeout 60 ./jperl repro.pl > /tmp/repro-interpreter.log 2>&1
timeout 60 perl repro.pl > /tmp/repro-perl.log 2>&1
```

Use `perl dev/tools/perl_test_runner.pl` for core tests; it needs fork support.
It sets `PERL_SKIP_BIG_MEM_TESTS=1` and the large stack for `re/pat.t`,
`op/repeat.t` and `op/list.t`. For direct runs, reproduce those settings and run
from `perl5_t/t/` with an absolute launcher if needed. Ensure subprocess tests
can find `jperl` on PATH. Do not treat `JPERL_SHOW_FALLBACK` output as proof of
which backend executed every eval.

## Implement and validate

Complete the related code paths and permanent focused regression coverage as a
coherent batch, using early small checks to resolve uncertainty. Never modify
or delete existing tests to obtain a pass. Retain unfixed-parent failure evidence
and require both backends plus applicable engine/upstream gates.

Build with `nice -n 19 make`, capturing full output; `make dev` is disabled.
Do not rebuild a JAR while readers use it. Use the task's relevant integration
and bundled gates after the build exits. Temporary debug prints must be removed.
A compatibility/runtime fix needs a terse Work in progress changelog entry.

Use a feature branch and a draft PR for an issue fix once the safe initial
snapshot is committed. Write commit and PR text through files per AGENTS.md.
Before UAT/CI, integrate current master and rerun required gates on the final
immutable candidate. Only declare readiness from that evidence, and review CI
and user feedback when UAT is underway.

## Prepare the checkout for UAT

Build the checkout the user will actually run and confirm its intended source
commit, successful build log and launcher smoke result. A branch name alone
does not establish that the local JAR is current. If UAT fails before TAP,
inspect its `out.json` and `raw_output_path` before changing timeouts or calling
it a test regression. Keep the current checkpoint's result/evidence links short.

## Issue coordination and reporting

Use the [triage guide](../../../docs/guides/issue-triage.md) for issue evidence,
agent claims, dependencies and concise checkpoints. Follow
[AGENTS.md](../../../AGENTS.md) for work preservation and validation.
