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
weighted scheduler. The Phase 36 regex acceptance producer always supplies the
option and defaults it to 2, permitting useful CPU parallelism without making
the heavyweight fixtures contend with `pat*`.

### generate_regex_test_ledger.pl
**Purpose:** Derive the current Phase 36 regex corpus without a hand-maintained
file list or pinned Perl revision.

The tool includes every current `perl5_t/t/re/*.t` file, scans `op/` and `uni/`
for regex-bearing tests, resolves test references from the feature matrix,
Phase 36 plan, and comparison policy, and records direct/thread pairs and unit
gates separately. It emits canonical JSON plus an optional one-path-per-line
runner list:

Documented nested test paths may name any file beneath the current
`perl5_t/t` root. Absolute paths, dot/dot-dot components, and symlinks that
escape that root remain unresolved so a reference document cannot extend the
acceptance corpus outside the imported checkout.

```bash
perl dev/tools/generate_regex_test_ledger.pl \
  --runner-list /tmp/phase36-regex-files.txt \
  --output /tmp/phase36-regex-ledger.json
```

Use the same list to compare only those exact identities against a broader
historical runner log:

```bash
perl dev/tools/compare_test_results.pl \
  --file-list /tmp/phase36-regex-files.txt \
  baseline.log candidate.json
```

The generated counts describe the current latest upstream checkout. Refresh
comparisons may record hashes for that run, but they must not encode a
historical Perl revision as the expected corpus.

### run_phase36_regex_acceptance.pl

**Purpose:** Compose the Phase 36 current-checkout ledgers, bounded JVM and
interpreter runner legs, fail-closed immutable-baseline comparisons, and exact
Joni packaging check into one immutable-artifact manifest. One complete-corpus
execution produces a strict semantic view derived from core regex,
direct/thread, thread-only, and documented unit gates that rejects every
invalid row, plus a broad view that rejects regressions or newly invalid rows
while retaining inherited platform/build-tree invalidity as classified evidence. The manifest
records the starting/final clean tracked-source state, current `perl5/` SHA as
provenance, and the bounded `jperl -v` injected SHA matched to that source,
along with list-form commands, exit statuses, counts, and SHA-256s.

The coordinator runs the real corpus only after creating the exact JAR/SBOM.
Workers use `--prepare-only` with injected fake tool paths to test the
composition without starting the corpus; the real invocation is:

```bash
perl dev/tools/run_phase36_regex_acceptance.pl \
  --baseline /absolute/authority-selected/baselines/pr1091.json \
  --artifact-dir /absolute/authority-selected/artifacts/phase36-acceptance \
  --jar /absolute/path/to/the/sealed/perlonjava-release.jar \
  --sbom /absolute/path/to/the/sealed/perlonjava-release-sbom.json \
  --jobs 10 --cpu-heavy-jobs 2
```

The release authority selects the absolute baseline and artifact paths and
passes the exact sealed JAR/SBOM produced for that candidate; do not infer a
JAR from a worktree-relative build path. The manifest retains both runner
budgets. `--cpu-heavy-jobs` defaults to 2 and is bounded to 1..3 for final
acceptance. The latest-Perl corpus file count is observed from the generated
ledger at execution time, not pinned in this command or documentation.

### verify-joni-packaging.pl

**Purpose:** Fail closed on the final standalone JAR/SBOM boundary. The
two-argument verifier checks relocated Joni/JCodings class ownership, exact
checked-in notice bytes, unique Joni/JCodings CycloneDX identities, and the
declared Joni-to-JCodings dependency edge. It also requires the structural
signature written by `merge-sbom.pl`: canonical PerlOnJava root metadata and a
nonempty, uniquely identified bundled-Perl component set. A dependency-only
CycloneDX `bom.json` is therefore rejected; pass the final merged `sbom.json`.
The verifier resolves notice sources relative to the repository, so it may run
from an artifact directory:

```bash
perl dev/tools/verify-joni-packaging.pl standalone.jar merged-sbom.json
```

For a system-Perl oracle of an imported core test, remember that `t/test.pl`
replaces `@INC` with the imported `../lib`. Preload host-core modules needed by
the test before that reset. For example, the reproducible `op/do.t` oracle is:

```bash
cd perl5_t/t
perl -Mstrict -MErrno -MPerlIO -MPerlIO::scalar op/do.t
```

This distinguishes a real TAP count from a zero-TAP host-module startup error;
it does not patch or otherwise modify the imported test.

### reorganize_tests.sh
**Purpose:** Reorganize test directory structure to separate PerlOnJava unit tests from standard Perl module tests.

**Usage:**
```bash
./dev/tools/reorganize_tests.sh
```

**What it does:**
- Moves all current tests to `src/test/resources/unit/`
- Creates empty directories: `lib/`, `ext/`, `dist/`, `cpan/`
- Preserves git history with `git mv`
- Verifies all tests are accounted for

**Documentation:** See `dev/prompts/test-directory-reorganization.md`

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
`make test-threads-core`, allows independently tracked direct Phase 36 gaps,
and fails when a wrapper loses TAP or adds failures, incompleteness, a timeout,
or an execution error.

This tool is normally invoked by the Make target rather than directly:

```bash
perl dev/tools/check_thread_core_parity.pl build/reports/threads/core core-jvm-virtual
```

### collect_phase36_direct_thread.pl

Consumes the acceptance producer manifest plus its exact ledger. It verifies the
retained JVM/interpreter runner JSON hashes before projecting ten pairs across
two backends and one ledger thread-only row across two backends. Supplemental
Make core artifacts (including `stclass_threads.t`) are named separately and
never counted into that projection.

The collector also hashes and parses every runner row's retained raw TAP. It
compares assertion presence and status as semantic evidence while recording
description-only differences separately. String runner statuses remain strings
and are counted under `details.status_counts`; malformed, zero-TAP, incomplete,
timed-out, or count-inconsistent rows fail closed.

Known shared direct/thread failures require an exact `--allowlist` JSON entry
for backend, direct path, thread path, and assertion number. Divergent statuses
cannot be allowlisted, and stale entries fail the collection. The Phase 36
allowlist is `dev/tools/phase36_direct_thread_allowlist.json`.

### jcpan_bisect_module.pl
**Purpose:** Find the PR merge commit where a slow `./jcpan -t MODULE` target stopped passing.

**Usage:**
```bash
perl dev/tools/jcpan_bisect_module.pl --module DBIx::Class --good 9b36f7e57 --bad master --timeout 7200
perl dev/tools/jcpan_bisect_module.pl --module Moo --good 3fe76ed3b --bad master --timeout 3600
```

By default it creates a separate worktree under `/tmp`, runs `git bisect --first-parent`, builds each candidate with `make`, wraps each `jcpan` run in a hard timeout, and stores per-commit logs plus cached verdicts under `/tmp`. Use a first-parent integration ref such as `master` or `origin/master` as `--bad`; use `--all-commits` only when branch-internal commits are expected to build and pass tests.

### tap_test_fixer.pl
**Purpose:** Fix TAP (Test Anything Protocol) test output formatting.

## Analysis Tools

### analyze_missing_operators.pl
**Purpose:** Analyze which Perl operators are not yet implemented in PerlOnJava.

### analyze_pack_failures.pl
**Purpose:** Analyze pack/unpack test failures to identify patterns.

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

## Parser Tools

### perl5_parser.pl
**Purpose:** Parse Perl 5 code for analysis.

### list_perl_prototypes.pl
**Purpose:** List Perl function prototypes.

### create_lexer_switch.pl
**Purpose:** Generate lexer switch statements.

## Code Generation/Templates

### Perl Unicode Java data

`generate_perl_unicode_data.pl` is the single development entry point for all
checked-in Perl-derived Unicode Java tables, including the runtime
`PerlUnicode*Data.java` families and Joni's `PerlUnicodeCaseFoldData.java`. Its
manifest records the selected current Perl commit as provenance, Unicode
version, unicore and Perl generator-source SHA-256 checksums, generator/output
mapping, and expected generated SHA-256 checksums. Each selected generator is
run twice and publication is rejected if the two byte streams differ.

The complete family uses the current default branch checked out at `perl5/`.
Explicit unicore and Perl roots can be supplied when the checkout lives
elsewhere:

```bash
# Regenerate every checked-in class and transactionally refresh current-source
# provenance and reproducibility hashes.
perl dev/tools/generate_perl_unicode_data.pl --refresh

# CI/review gate: fail if recorded provenance or a generated file is stale.
perl dev/tools/generate_perl_unicode_data.pl --check

# Inspect or update one family while developing a generator.
perl dev/tools/generate_perl_unicode_data.pl --list
perl dev/tools/generate_perl_unicode_data.pl --only block
perl dev/tools/generate_perl_unicode_data.pl \
  --unicode-root /path/to/perl5/lib/unicore \
  --perl-root /path/to/perl5 --check

# Regenerate or check only the default Unicode case-fold relation.
perl dev/tools/generate_perl_unicode_data.pl --only case-fold
perl dev/tools/generate_perl_unicode_data.pl --only case-fold --check
```

The case-fold table is data-only. It records default C+F full folds, C+S simple
equivalence closures, reverse multi-character folds, their components, and the
two excluded Turkic source records. Perl mode policy for `/d`, `/u`, `/a`,
`/aa`, byte/Unicode provenance, locale behavior, and ASCII-crossing eligibility
remains hand-written in the Joni integration and is deliberately not generated.

The shared parsing and provenance helpers live in
`lib/PerlOnJava/UnicodeGenerator.pm`; the complete inventory is
`perl_unicode_data_generators.json`. Individual generators remain directly
executable for focused development and emit Java source on standard output.
The orchestrator alone owns deterministic double generation, fail-closed
source/output checksum validation, stale-output checking, and atomic updates.

### automatic_operator_descriptor.java
**Purpose:** Template for automatic operator descriptors.

### Overload.java
**Purpose:** Template for operator overloading.

### UnaryOperatorBenchmark.java
**Purpose:** Benchmark template for unary operators.

### TTYCheck.java
**Purpose:** TTY checking utility.

### Other Java templates
- `cache_eviction_thread.java`
- `combine_set.java`
- `inline_grep.java`
- `lazy_list.java`
- `overloading_bit.java`

## Configuration

### _vimrc
**Purpose:** Vim configuration for PerlOnJava development.

## Adding New Tools

When adding new development tools:
1. Place scripts in this directory
2. Make them executable: `chmod +x tool_name.sh`
3. Add documentation to this README
4. Reference from relevant documentation in `dev/prompts/`
5. Use clear, descriptive names
6. Include usage examples and error handling
