# Debug PerlOnJava Windows CI Failures

## ⚠️⚠️⚠️ CRITICAL: NEVER USE `git stash` ⚠️⚠️⚠️

**DANGER: Changes are SILENTLY LOST when using git stash/stash pop!**

- NEVER use `git stash` to temporarily revert changes
- INSTEAD: Commit to a WIP branch or use `git diff > backup.patch`
- This warning exists because completed work was lost during debugging

## Overview

This skill helps debug test failures that occur specifically in the Windows CI/CD environment but pass locally on macOS/Linux.

## When to Use

- Tests pass locally on macOS/Linux but fail on Windows CI
- Windows-specific path handling issues
- Shell command differences between platforms
- File I/O issues on Windows

## CI/CD Structure

### GitHub Actions Workflow

The CI runs on `windows-latest` using:
- Java 21 (Temurin)
- Gradle for build
- Maven for tests (`make ci` runs `mvn clean test`)

### Viewing CI Logs

```bash
# List recent CI runs
gh run list --branch <branch-name> --limit 5

# View failed test logs
gh run view <run-id> --log-failed

# Filter for specific errors
gh run view <run-id> --log-failed 2>&1 | grep -E "FAILURE|error|not ok"

# Get test count summary
gh run view <run-id> --log-failed 2>&1 | grep "Tests run:"
```

## Common Windows CI Issues

### 1. Cwd/getcwd Issues

**Symptom**: "Cannot chdir back to : 2" or "Undefined subroutine &Cwd::cwd called"

**Root Cause**: The Perl `Cwd.pm` uses shell backticks (`` `cd` ``) on Windows which doesn't work in PerlOnJava.

**Solution**: PerlOnJava provides `Internals::getcwd` which uses Java's `System.getProperty("user.dir")`. The Cwd.pm has been modified to use this when available.

**Key Files**:
- `src/main/perl/lib/Cwd.pm` - Perl module with platform-specific fallbacks
- `src/main/java/org/perlonjava/runtime/perlmodule/Internals.java` - Java implementation of getcwd

### 2. Temp File Creation Issues

**Symptom**: "Cannot open/create <filename>: open failed"

**Root Cause**: 
- Windows uses different path separators (`\` vs `/`)
- Temp directory permissions may differ
- File locking behavior differs on Windows

**Debugging**:
```bash
# Check temp path in error message
gh run view <run-id> --log-failed 2>&1 | grep "open failed"
```

### 3. $^O Detection

PerlOnJava sets `$^O` based on the Java `os.name` property:
- Windows: `MSWin32`
- macOS: `darwin`
- Linux: `linux`

**Key File**: `src/main/java/org/perlonjava/runtime/runtimetypes/SystemUtils.java`

### 4. Shell Command Differences

Windows CI may fail when Perl code uses:
- Backticks with Unix commands
- `system()` calls assuming Unix shell
- Path separators in shell commands

## Debugging Workflow

### Step 1: Identify the Failing Test

```bash
# Get list of failing tests
gh run view <run-id> --log-failed 2>&1 | grep "testUnitTests.*FAILURE"
```

### Step 2: Map Test Number to File

```bash
# List tests in order (tests are numbered alphabetically)
ls -1 src/test/resources/unit/*.t | sort | nl | grep "<number>"
```

### Step 3: Analyze the Error

```bash
# Get full context around error
gh run view <run-id> --log-failed 2>&1 | grep -A10 "unit\\<test>.t"
```

### Step 4: Check if Pre-existing

```bash
# Compare with master branch CI
gh run list --branch master --limit 3
gh run view <master-run-id> --log-failed
```

## Platform-Specific Code Patterns

### Checking for Windows in Perl

```perl
if ($^O eq 'MSWin32') {
    # Windows-specific code
}
```

### Checking for Windows in Java

```java
if (SystemUtils.osIsWindows()) {
    // Windows-specific code
}
```

### Safe Cross-Platform getcwd

```perl
# In Cwd.pm, use Internals::getcwd if available
if (eval { Internals::getcwd(); 1 }) {
    *getcwd = \&Internals::getcwd;
}
```

## Test File Locations

- Unit tests: `src/test/resources/unit/*.t`
- Perl5 test suite: `perl5_t/t/`
- Java tests: `src/test/java/org/perlonjava/`

## Related Files

- `.github/workflows/gradle.yml` - CI workflow definition
- `Makefile` - Build targets including `ci`
- `src/main/java/org/perlonjava/runtime/perlmodule/Cwd.java` - Java Cwd stub
- `src/main/perl/lib/Cwd.pm` - Perl Cwd implementation

## Troubleshooting Checklist

1. [ ] Is the failure Windows-specific? (Check if macOS/Linux CI passes)
2. [ ] Is it a new regression or pre-existing? (Compare with master)
3. [ ] Does it involve file paths or shell commands?
4. [ ] Does it use Cwd or directory operations?
5. [ ] Is `$^O` being checked correctly?
6. [ ] Are there any `defined &Subroutine` checks that might behave differently?

## Adding Debug Output

To debug CI issues, you can temporarily add print statements to Perl modules:

```perl
# Add to Cwd.pm to debug
warn "DEBUG: \$^O = $^O";
warn "DEBUG: Internals::getcwd available: " . (eval { Internals::getcwd(); 1 } ? "yes" : "no");
```

Then check CI logs:
```bash
gh run view <run-id> --log-failed 2>&1 | grep "DEBUG:"
```

Remember to remove debug output before final commit.
