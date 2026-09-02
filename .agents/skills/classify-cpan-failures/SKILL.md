---
name: classify-cpan-failures
description: Classify PerlOnJava CPAN tester failures and decide whether they warrant a GitHub issue. Use for FAIL or REGRESS records from cpan_random_tester.pl, not for implementing an already-confirmed compiler bug.
---

# Classifying CPAN Tester Failures

## Tooling prerequisite

The skill validator requires PyYAML. If validation reports that `yaml` is
missing, create or use the repository-local `.venv` and install PyYAML there;
do not install it into the system or Homebrew-managed Python environment:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install PyYAML
```

Run the validator with `.venv/bin/python` after installation.

Use the archived run log and its `FAILURES.tsv` or `REGRESSIONS.tsv` row as
the source of truth. Verify that the reported module is the module whose test
block actually failed; nested CPAN dependency output can otherwise make an
association ambiguous.

## Issue threshold

Create or recommend a PerlOnJava issue only when all of these hold:

1. The relevant upstream test suite can pass completely under system Perl in
   the same usable environment.
2. The PerlOnJava failure is reproducible and attributable to PerlOnJava,
   rather than a tester timeout, missing service, missing display, platform
   prerequisite, or unsupported native dependency.
3. For a distribution containing XS/native code, it is an important ecosystem
   dependency: establish this with concrete reverse-dependency evidence (for
   example, a widely used foundational module such as Moose). Do not open an
   issue merely because an XS distribution fails.

For the importance check, use `dev/tools/cpan_port_priorities.pl` rather than
estimating from the module name. For a single native dependency, run a bounded
targeted lookup such as:

```bash
timeout 300 perl dev/tools/cpan_port_priorities.pl \
  --targeted --module IPC::ShareLite --top 1 \
  --cache /tmp/perlonjava-cpan-port-priorities.json
```

Record the unique runtime dependant count, recent-dependant count, and sample
dependant distributions. Use the bulk mode for a ranked shortlist, and the
targeted mode to verify individual candidates. A low count supports deferring
an XS port; it does not by itself prove that a failure is environmental or
that a high-count module is easy to port.

If system Perl cannot pass all tests, do not create an issue. Record the
environmental or upstream reason instead.

Exception: when the failure is independently attributable to a PerlOnJava
internal bug, create or recommend an issue even if the full upstream suite
cannot run cleanly. First reduce it to a small, self-contained reproducer
that can become a project regression test when the bug is fixed.

A timeout alone is not evidence of a bug and must not trigger an issue. Treat
it as actionable only after tracing it to a PerlOnJava defect (for example, a
reproducible infinite loop); retain a bounded reproducer that demonstrates the
non-termination.

## Investigation

- Read the complete archived target log, especially configure output, the test
  harness summary, and timeout context.
- Check distribution metadata and source for XS/C code. Treat GUI/display,
  native-library, network-service, and OS-only test requirements as
  environment constraints until system Perl proves otherwise.
- During a `jcpan` test, inspect the target's
  `blib/.perlonjava-cpan-perl5lib` file. A dependency blib can shadow a
  bundled module even when the module is normally available from the JAR.
  Reproduce the same load order with that exact `PERL5LIB`; if system Perl
  succeeds and PerlOnJava fails, classify it as an internal CPAN-overlay bug.
- Run the focused upstream suite with system Perl and retain its full output.
  Bound any potentially hanging invocation with `timeout`.
- If system Perl does not have the target or any test dependency, install the
  exact distribution and all missing prerequisites automatically into an
  isolated temporary local library before comparing results (never install
  into the global Perl installation). A missing system module is a setup gap
  to resolve, not evidence that upstream tests cannot pass. Do not classify
  the result until the installation attempt has either completed or produced
  a documented, reproducible setup blocker.
- For isolated setup, use a temporary install base and a separate CPAN client
  configuration, including both Makefile.PL and Module::Build install
  arguments. Ensure the CPAN client itself writes its metadata, build trees,
  and temporary files under that isolated configuration; do not let it fall
  back to the user's shared CPAN home. Prefer the exact versions already
  selected by the archived PerlOnJava run when available, and preserve the
  install command and dependency log. Capture dependency-install output
  separately from test output, then run `prove` with the temporary library
  prepended to `PERL5LIB`. A failure or timeout before the target test command
  starts is a setup result, not a target-module failure; retry or repair the
  isolated installation when the failure is merely a missing prerequisite.
- If the suite passes under system Perl, reproduce with both PerlOnJava
  backends when the failure is compiler/runtime related.
- When an internal PerlOnJava cause is confirmed, keep the smallest reusable
  reproducer and record which backend(s) it affects. Do not infer an internal
  cause solely from `Unknown test outcome`.
- For timeouts, first rule out slow dependencies and environmental contention.
  Create an issue only when a bounded reproducer attributes the timeout to a
  PerlOnJava internal bug.
- For XS modules, count or otherwise document reverse dependencies before
  calling the module important. Prefer CPAN index/dependency metadata over a
  name-based guess; use `cpan_port_priorities.pl --targeted` for the module
  under review and retain the count and examples in the classification notes.

## GitHub handling

Search for an existing issue before proposing a new one. Use an existing
`bug` label when appropriate; do not create a new label for an isolated CPAN
failure. Include the distribution/version, system-Perl result, PerlOnJava
result, and environment prerequisites in any issue. GitHub issue bodies must
be durable outside the local checkout: do not include `/tmp` paths,
home-directory paths, local build paths, or references to uncommitted/local-
only files. Use the archived run identifier and stable test names instead;
summarize local source or design evidence directly in the issue.

When a related existing issue already covers the confirmed cause, recommend a
follow-up comment that records the newly classified distribution, stable
failing tests, and CPAN run identifier as post-fix verification coverage. Do
not add that comment unless the user explicitly authorizes the GitHub write,
and do not open a duplicate issue.

Do not create the issue unless the user explicitly asks for that external
action.

