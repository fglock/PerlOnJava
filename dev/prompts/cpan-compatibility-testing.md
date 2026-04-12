# CPAN Compatibility Testing

## Goal

Maintain a continuously updated report of which CPAN modules pass all tests on PerlOnJava. This helps:
- Track compatibility progress over time
- Identify common failure patterns to prioritize fixes
- Give users confidence about which modules work
- Bisect regressions (PASS results record the git commit hash)

## Quick Start

```bash
# Build PerlOnJava first
make dev

# Test 10 random CPAN modules (uses jcpan -t by default)
perl dev/tools/cpan_random_tester.pl

# Test a larger batch (e.g., 50 modules)
perl dev/tools/cpan_random_tester.pl --count 50

# Install mode (deps stay installed for future runs)
perl dev/tools/cpan_random_tester.pl --install --count 20

# Regenerate the Markdown report from existing data
perl dev/tools/cpan_random_tester.pl --report-only
```

## How It Works

1. **Module selection**: Reads the full CPAN index (~43K distributions), picks N random modules that haven't passed yet. Previously-failed modules are re-eligible so they can be retried once their deps get installed.

2. **Testing**: Each target is run with `./jcpan -t <Module>` (default) which always runs tests even for already-installed modules. Use `--install` to install instead (deps stay for future runs, but already-installed modules are skipped).

3. **Dependency harvesting**: The script parses the **full** jcpan output and extracts results for **every** module that appears — not just the target, but all its dependencies too. One target often yields 5-20 additional data points.

4. **Smart updates**: When a module is re-tested:
   - FAIL → PASS: the record is **upgraded** (moved from fail.dat to pass.dat)
   - PASS stays PASS: date and commit are updated silently
   - PASS is never downgraded to FAIL (to protect against flaky tests)

5. **Git commit tracking**: Every PASS records the git commit hash. If a future run detects a PASS→FAIL regression, you can bisect between the known-good commit and HEAD.

6. **Crash-safe persistence**: Results are saved to `.dat` files after each target module, so partial runs are never lost.

7. **Report**: A Markdown report is generated at `dev/cpan-reports/cpan-compatibility.md`.

## Files

| File | Purpose |
|------|---------|
| `dev/tools/cpan_random_tester.pl` | Main test script (run with `perl`, not `jperl`) |
| `dev/cpan-reports/cpan-compatibility.md` | Human-readable report |
| `dev/cpan-reports/cpan-compatibility-pass.dat` | Pass list (TSV, includes git commit) |
| `dev/cpan-reports/cpan-compatibility-fail.dat` | Fail list (TSV) |
| `dev/cpan-reports/cpan-compatibility-skip.dat` | Skip list (TSV) |
| `/tmp/cpan_random_logs/` | Per-module test output logs |

## Workflow for Growing the Report

### Initial Seeding

Run a large batch to establish a baseline:

```bash
perl dev/tools/cpan_random_tester.pl --count 100 --seed 1
```

### Incremental Testing

Each run adds more modules. Failed modules are automatically retried in future runs (their deps may have been installed since):

```bash
# Each invocation picks new random targets + retries eligible failures
perl dev/tools/cpan_random_tester.pl --count 20
```

### After a PerlOnJava Improvement

Just run again. Modules that previously failed will be retried automatically. The script will show `UPGRADE` for any FAIL→PASS transitions:

```bash
make dev  # rebuild
perl dev/tools/cpan_random_tester.pl --count 50
```

### Updating the PR

After testing, commit the updated report and data files:

```bash
git add dev/cpan-reports/cpan-compatibility.md
git add dev/cpan-reports/cpan-compatibility-pass.dat
git add dev/cpan-reports/cpan-compatibility-fail.dat
git add dev/cpan-reports/cpan-compatibility-skip.dat
git commit -m "docs: update CPAN compatibility report"
```

## Analyzing Failures

The fail list is categorized by error type in the .md report. Common patterns:

- **Missing Dependencies**: Module needs another module that PerlOnJava can't install. Fixing the dep often unlocks many modules downstream.
- **Test Failures**: Module installs but some tests fail. Best candidates for targeted fixes (often close to passing).
- **Configure Failed**: `Makefile.PL` failed. Often due to XS detection or parser issues.
- **Timeout**: Module takes too long (>300s). May indicate infinite loops.
- **Stack/Memory**: JVM resource limits hit. Try `JPERL_OPTS="-Xss256m -Xmx2g"`.
- **Syntax Error**: PerlOnJava parser limitation. File a bug or check existing issues.

## Regression Bisecting

When a module transitions from PASS to FAIL:

1. Find the last-known-good commit in `cpan-compatibility-pass.dat` (8th column)
2. Bisect:
   ```bash
   git bisect start HEAD <good-commit>
   git bisect run sh -c './jcpan -t Some::Module 2>&1 | grep -q "Result: PASS"'
   ```

## Tips

- Run with `perl` (not `jperl`) since the script uses `fork` and backticks.
- The CPAN index must exist at `~/.cpan/sources/modules/02packages.details.txt.gz`. Run `./jcpan` once interactively if it's missing.
- Use `--seed N` for reproducible random selection.
- Logs for individual modules are in `/tmp/cpan_random_logs/<Module-Name>.log`.
- The existing `dev/tools/cpan_smoke_test.pl` tests a curated list of known modules. This script complements it by discovering new compatible modules from the full CPAN index.
