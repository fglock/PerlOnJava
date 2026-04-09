# PerlOnJava Agent Guidelines

## ⚠️⚠️⚠️ CRITICAL WARNING: NEVER USE `git stash` ⚠️⚠️⚠️

```
╔══════════════════════════════════════════════════════════════════════════════╗
║                                                                              ║
║   DANGER: DO NOT USE `git stash` DURING ACTIVE WORK!                        ║
║                                                                              ║
║   Changes can be SILENTLY LOST when using git stash/stash pop.              ║
║   This has caused loss of completed work during debugging sessions.         ║
║                                                                              ║
║   INSTEAD:                                                                   ║
║   - Commit your changes to a WIP branch before testing alternatives         ║
║   - Use `git diff > backup.patch` to save uncommitted changes               ║
║   - Never stash to "temporarily" revert - you WILL lose work                ║
║                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

---

## Project Rules

### Progress Tracking for Multi-Phase Work

When working on multi-phase projects (like the Shared AST Transformer), **always update the design document when completing a phase**:

1. **Mark the phase as completed** with date
2. **Document what was done** (files changed, key decisions)
3. **Update "Next Steps"** section so the user knows where to resume
4. **Note any blockers or open questions**

Example format at the end of a design doc:

```markdown
## Progress Tracking

### Current Status: Phase 2 in progress

### Completed Phases
- [x] Phase 1: Infrastructure (2024-03-09)
  - Created ASTAnnotation class
  - Added typed fields to AbstractNode
  - Files: AbstractNode.java, ASTAnnotation.java

### Next Steps
1. Implement VariableResolver visitor
2. Add closure capture detection
3. Run differential tests

### Open Questions
- Should we cache lvalue analysis results?
```

### Design Documents

- Design documents live in `dev/design/`
- Each major feature should have its own design doc
- Keep docs updated as implementation progresses
- Reference related docs and skills at the end

### Unimplemented Features

PerlOnJava does **not** implement the following Perl features:

| Feature | Impact |
|---------|--------|
| `weaken` / `isweak` | No weak reference tracking. `weaken()` is a no-op, `isweak()` always returns false (since nothing is ever weakened). JVM's tracing GC handles circular references natively. |
| `Scalar::Util::readonly` | Works for compile-time constants (`RuntimeScalarReadOnly` instances). Does not yet detect variables made readonly at runtime via `Internals::SvREADONLY` (those copy type/value into a plain `RuntimeScalar` without replacing the object). |
| `DESTROY` | Object destructors never called; DEMOLISH patterns and cleanup code won't run |
| `fork` | Process forking not available; use `perl` (not `jperl`) to run `perl_test_runner.pl` |
| `threads` | Perl threads not supported; use Java threading via inline Java if needed |

### Testing

**NEVER modify or delete existing tests.** Tests are the source of truth. If a test fails, fix the code, not the test. When in doubt, verify expected behavior with system Perl (`perl`, not `jperl`).

**ALWAYS use `make` commands. NEVER use raw mvn/gradlew commands.**

| Command | What it does |
|---------|--------------|
| `make` | Build + run all unit tests (use before committing) |
| `make dev` | Build only, skip tests (for quick iteration during debugging) |
| `make test-bundled-modules` | Run bundled CPAN module tests (XML::Parser, etc.) |

- For interpreter changes, test with both backends:
  ```bash
  ./jperl -e 'code'           # JVM backend
  ./jperl --int -e 'code'     # Interpreter
  ```

### Perl Test Runner

Use `dev/tools/perl_test_runner.pl` to run Perl test files and get pass/fail counts. **Run with `perl` (not `jperl`)** because it needs fork support.

```bash
# Run specific test files
perl dev/tools/perl_test_runner.pl perl5_t/t/re/regexp.t perl5_t/t/op/utfhash.t

# Run all tests in a directory
perl dev/tools/perl_test_runner.pl perl5_t/t/op/

# Common test directories
perl dev/tools/perl_test_runner.pl perl5_t/t/re/    # Regex tests
perl dev/tools/perl_test_runner.pl perl5_t/t/op/    # Operator tests
perl dev/tools/perl_test_runner.pl perl5_t/t/uni/   # Unicode tests
```

The runner:
- Executes tests in parallel (5 jobs by default)
- Has a 300s timeout per test
- Reports pass/fail counts in format: `passed/total`
- Saves results to `test_results_YYYYMMDD_HHMMSS.txt`
- Sets required environment variables automatically (see below)

#### Running Tests Directly (without perl_test_runner.pl)

If you run tests directly with `./jperl`, you may need to set these environment variables:

```bash
# For tests that use unimplemented features (re/pat.t, op/pack.t, etc.)
# Without this, unimplemented features cause fatal errors
export JPERL_UNIMPLEMENTED=warn

# For memory-intensive tests (re/pat.t, op/repeat.t, op/list.t)
# Increases JVM stack size to prevent StackOverflowError
export JPERL_OPTS="-Xss256m"

# Skip tests with 300KB+ strings that crash the JVM
export PERL_SKIP_BIG_MEM_TESTS=1

# Example: running re/pat.t directly
cd perl5_t/t
JPERL_UNIMPLEMENTED=warn JPERL_OPTS="-Xss256m" PERL_SKIP_BIG_MEM_TESTS=1 ../../jperl re/pat.t
```

The perl_test_runner.pl sets these automatically based on the test file being run.

### Git Workflow

**IMPORTANT: Never push directly to master. Always use feature branches and PRs.**

**IMPORTANT: Always run `make` and ensure it passes before pushing commits or updating PRs.** This runs all unit tests and catches regressions early.

1. **Create a feature branch** before making changes:
   ```bash
   git checkout -b feature/descriptive-name
   ```

2. **Make commits** on the feature branch with clear messages

3. **Verify tests pass** before pushing:
   ```bash
   make  # Must succeed before pushing
   ```

4. **Push the feature branch** and create a PR:
   ```bash
   git push origin feature/descriptive-name
   gh pr create --title "Title" --body "Description"
   ```

5. **Wait for review** before merging

6. **Use `GIT_EDITOR="true"` for non-interactive git operations** (e.g., `git commit --amend`, `git rebase`). This avoids hanging on an interactive editor:
   ```bash
   GIT_EDITOR="true" git commit --amend
   ```

### Commits

- Reference the design doc or issue in commit messages when relevant
- Use conventional commit format when possible
- **Always include `src/main/java/org/perlonjava/core/Configuration.java` in commits and PRs** - This file contains the git commit ID and date that help users identify the exact PerlOnJava version when reporting issues. Running `make` automatically updates this file via the `injectGitInfo` Gradle task. Always stage it before committing:
  ```bash
  make  # Updates Configuration.java with current commit ID
  git add src/main/java/org/perlonjava/core/Configuration.java
  ```
  **Why this matters:** Users can run `./jperl -v` to see the commit ID, making it easy to communicate exactly which version they're using when creating bug reports.

## Available Skills

See `.cognition/skills/` for specialized debugging and development skills:
- `debug-perlonjava` - General debugging
- `interpreter-parity` - JVM vs interpreter parity issues
- `debug-exiftool` - ExifTool test debugging
- `profile-perlonjava` - Performance profiling

## How to Check Regressions

When a unit test fails on a feature branch, always verify whether it also fails on master before trying to fix it:

```bash
# 1. Save your work
git diff > /tmp/my-changes.patch

# 2. Switch to master and do a clean build
git checkout master
make clean ; make

# 3. If the test passes on master, it's a regression you introduced — fix it
# 4. If the test also fails on master, it's pre-existing — don't waste time on it

# 5. Switch back to your branch
git checkout feature/your-branch
git apply /tmp/my-changes.patch
```

```bash
# Run specific test
cd perl5_t/t && ../../jperl <test>.t

# Count passing tests
../../jperl <test>.t 2>&1 | grep "^ok" | wc -l

# Check for interpreter fallback
JPERL_SHOW_FALLBACK=1 ../../jperl <test>.t 2>&1 | grep -i fallback
```
