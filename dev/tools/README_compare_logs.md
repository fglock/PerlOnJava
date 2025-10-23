# Test Log Comparison Tool

## Overview

`compare_test_logs.pl` is a tool to compare two test run logs and identify regressions and improvements. This helps track progress and quickly identify which test files are improving or regressing between runs.

## Quick Start

```bash
# Compare two test logs
./dev/tools/compare_test_logs.pl logs/test_20251022_101400 logs/test_20251022_154800

# Quick summary only
./dev/tools/compare_test_logs.pl --summary-only old.log new.log

# Show only significant regressions (>= 10 tests difference)
./dev/tools/compare_test_logs.pl --min-diff 10 --no-show-progress old.log new.log

# Show only large test files with changes
./dev/tools/compare_test_logs.pl --min-total 1000 old.log new.log
```

## Options

### Filtering Options

- `--min-diff N` - Only show files with difference >= N tests (default: 1)
- `--min-total N` - Only show files with >= N total tests (default: 0)

### Display Options

- `--show-progress` / `--no-show-progress` - Show/hide files with improvements (default: show)
- `--show-regressions` / `--no-show-regressions` - Show/hide files with regressions (default: show)
- `--show-unchanged` / `--no-show-unchanged` - Show/hide files with no change (default: hide)
- `--summary-only` - Only show summary statistics, skip detailed list

### Sorting Options

- `--sort-by FIELD` - Sort by: `name`, `diff`, `before`, `after` (default: `diff`)

## Output Format

### Summary Section

Shows overall statistics:
- Total tests and passing tests for both logs
- Net change in passing tests
- Number of files with regressions/progress/unchanged

Example:
```
Total tests in old log:  252220 tests,  240248 passing  (95.25%)
Total tests in new log:  251383 tests,  239302 passing  (95.19%)

Net change:                -946 passing tests  (-0.39%)

Files with regressions:    10 files  (-  1070 tests)
Files with progress:        6 files  (+   124 tests)
Files unchanged:          603 files
```

### Detailed Changes Section

Lists individual files with changes:
- ✓ = Improvement (more tests passing)
- ✗ = Regression (fewer tests passing)
- = = Unchanged

Example:
```
✗ op/stat_errors.t                                   575/638         0/638          -575
✓ comp/retainedlines.t                                 1/109        87/109           +86
✗ re/subst.t                                         159/281         0/0            -159
```

## Common Use Cases

### 1. Daily Progress Tracking

Compare today's run with yesterday's to see overall progress:
```bash
./dev/tools/compare_test_logs.pl logs/yesterday.log logs/today.log --summary-only
```

### 2. Identify Major Regressions

Find files that lost many tests (potential blockers):
```bash
./dev/tools/compare_test_logs.pl --min-diff 100 --no-show-progress \
    logs/before.log logs/after.log
```

### 3. Focus on Critical Test Files

Only track changes in large, important test files:
```bash
./dev/tools/compare_test_logs.pl --min-total 1000 \
    logs/before.log logs/after.log
```

### 4. Quick Change Detection

See which files changed at all:
```bash
./dev/tools/compare_test_logs.pl logs/before.log logs/after.log | \
    grep -E "^[✓✗]"
```

### 5. Export for Analysis

Save results to a file for later review:
```bash
./dev/tools/compare_test_logs.pl logs/before.log logs/after.log > changes.txt
```

## Integration with Workflows

### Pre-commit Check

Before committing changes, compare with baseline:
```bash
# Run tests
make test

# Compare with baseline
./dev/tools/compare_test_logs.pl logs/baseline.log out.json

# If regressions found, investigate before committing
```

### Continuous Integration

Track progress over time by comparing sequential runs:
```bash
#!/bin/bash
# Track daily progress
TODAY=$(date +%Y%m%d)
YESTERDAY=$(date -d yesterday +%Y%m%d)

./dev/tools/compare_test_logs.pl \
    logs/test_${YESTERDAY}.log \
    logs/test_${TODAY}.log \
    --summary-only
```

### Regression Bisection

Find which commit introduced a regression:
```bash
# At each bisection point, run tests and compare
git bisect start
git bisect bad HEAD
git bisect good <known-good-commit>

# At each step:
make test
./dev/tools/compare_test_logs.pl logs/good.log out.json --summary-only

# Mark as good/bad based on results
git bisect good/bad
```

## Tips

1. **Save important baselines**: Keep logs of major milestones to track long-term progress
2. **Use min-diff wisely**: Start with `--min-diff 1` to see all changes, then increase if too noisy
3. **Combine filters**: Use multiple options together for focused analysis
4. **Check the summary first**: Use `--summary-only` for a quick health check
5. **Sort strategically**: 
   - Use `--sort-by diff` (default) to see biggest changes first
   - Use `--sort-by name` for alphabetical listing
   - Use `--sort-by before/after` to focus on files with many tests

## Notes

- The script parses log files from `perl_test_runner.pl` output
- It expects lines in format: `[N/M] test_file.t ... symbol passed/total ok (time)`
- Files that appear in only one log are tracked separately
- The comparison is based on passing test counts, not total tests

