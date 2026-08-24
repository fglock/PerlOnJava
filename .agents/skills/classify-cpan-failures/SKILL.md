---
name: classify-cpan-failures
description: Classify PerlOnJava CPAN tester failures and decide whether they warrant a GitHub issue. Use for FAIL or REGRESS records from cpan_random_tester.pl, not for implementing an already-confirmed compiler bug.
---

# Classifying CPAN Tester Failures

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
- Run the focused upstream suite with system Perl and retain its full output.
  Bound any potentially hanging invocation with `timeout`.
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
  name-based guess.

## GitHub handling

Search for an existing issue before proposing a new one. Use an existing
`bug` label when appropriate; do not create a new label for an isolated CPAN
failure. Include the distribution/version, system-Perl result, PerlOnJava
result, environment prerequisites, and archived-log path in any issue.

Do not create the issue unless the user explicitly asks for that external
action.
