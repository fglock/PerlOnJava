# Development Tools

This directory contains utility scripts and tools for PerlOnJava development.

## Test Management Tools

### perl_test_runner.pl
**Purpose:** Main test runner for PerlOnJava test suite.

On POSIX hosts each timed test runs in a runner-owned process group. Timeout
cleanup kills that complete group so nested `fresh_perl` JVMs cannot survive
after their direct test process exits.

**Usage:**
```bash
perl dev/tools/perl_test_runner.pl --jobs 10 --cpu-heavy-jobs 2 \
  --output /tmp/regex.json perl5_t/t/re
```

Normal fixtures use `--jobs`. Memory-sensitive regex fixtures run in a serial
lane, while `pat_psycho*` and `speed*` run afterward in a dedicated lane only
when `--cpu-heavy-jobs` is supplied. Direct runner calls that omit it retain the
weighted scheduler.

### compare_test_results.pl
**Purpose:** Compare test results between runs to identify regressions or improvements.

The baseline may be either runner JSON or a captured historical runner log.
Use `--fail-on-regression` for release gates and `--output` to retain a
machine-readable per-file comparison. Corpus gates can additionally use
`--fail-on-invalid --expected-files N` to reject missing files, execution
errors, timeouts, incomplete runs, and zero-TAP results:

```bash
perl dev/tools/compare_test_results.pl --fail-on-regression --fail-on-invalid \
  --expected-files 80 \
  --path-prefix perl5_t/t/re \
  --output /tmp/regex-comparison.json \
  ../PerlOnJava/logs/test_20260821_143000_1091.log /tmp/regex.json
```

For a broad latest-Perl map that intentionally includes inherited
platform/build-tree rows, use `--fail-on-new-invalid` with
`--fail-on-regression`. It still rejects missing files, expected-count drift,
and any invalid candidate row whose baseline row was valid or absent; inherited
invalid rows remain visible in the JSON and human report.

### check_thread_core_parity.pl
**Purpose:** Enforce the same-commit direct/thread contract for the Perl-core
regex wrappers. It consumes the JSON reports produced by
`make test-threads-core`, allows independently tracked direct regex gaps,
and fails when a wrapper loses TAP or adds failures, incompleteness, a timeout,
or an execution error.

This tool is normally invoked by the Make target rather than directly:

```bash
perl dev/tools/check_thread_core_parity.pl build/reports/threads/core core-jvm-virtual
```

### jcpan_bisect_module.pl
**Purpose:** Find the PR merge commit where a slow `./jcpan -t MODULE` target stopped passing.

**Usage:**
```bash
perl dev/tools/jcpan_bisect_module.pl --module DBIx::Class --good 9b36f7e57 --bad master --timeout 7200
perl dev/tools/jcpan_bisect_module.pl --module Moo --good 3fe76ed3b --bad master --timeout 3600
```

By default it creates a separate worktree under `/tmp`, runs `git bisect --first-parent`, builds each candidate with `make`, wraps each `jcpan` run in a hard timeout, and stores per-commit logs plus cached verdicts under `/tmp`. Use a first-parent integration ref such as `master` or `origin/master` as `--bad`; use `--all-commits` only when branch-internal commits are expected to build and pass tests.

## Analysis Tools

### analyze_missing_operators.pl
**Purpose:** Analyze which Perl operators are not yet implemented in PerlOnJava.

## Git Hooks

### install_git_hooks.sh
**Purpose:** Install git hooks for pre-commit checks.

**Usage:**
```bash
./dev/tools/install_git_hooks.sh
```

### pre_commit_check.sh
**Purpose:** Pre-commit hook that runs checks before allowing commits.

## Development Utilities

### run_with_timeout.sh
**Purpose:** Run commands with a timeout to prevent hanging tests.

**Usage:**
```bash
./dev/tools/run_with_timeout.sh <timeout_seconds> <command>
```

### safe_analysis_setup.sh
**Purpose:** Set up safe environment for analysis tasks.

### start_analysis.sh
**Purpose:** Start analysis of test results or code patterns.

## Source Inspection

### list_perl_prototypes.pl
**Purpose:** List Perl function prototypes.

## Scoped tools

- Regex implementation tooling: `dev/regex/tools/`
- Feature and module tools: `dev/modules/`
- Maintenance and historical migration tools: `dev/maintenance/`
- Reproducers and experimental snippets: `dev/sandbox/`
- Benchmarks: `dev/bench/`

## Adding New Tools

When adding new development tools:
1. Place only reusable, project-wide scripts in this directory; put
   feature-specific and one-off work under its owning scoped directory.
2. Make them executable: `chmod +x tool_name.sh`
3. Add documentation to this README
4. Reference from relevant documentation in `dev/prompts/`
5. Use clear, descriptive names
6. Include usage examples and error handling
