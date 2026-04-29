# PerlOnJava Agent Guidelines

> **Read this file before touching the working tree.**
> The two warning blocks below are not decorative — they document
> real incidents in which agents silently destroyed user work.
> If you skip them you will eventually be the next incident.

## ⚠️⚠️⚠️ MANDATORY PRE-FLIGHT FOR ANY DIRTY TREE ⚠️⚠️⚠️

```
╔══════════════════════════════════════════════════════════════════════════════╗
║                                                                              ║
║   IF `git status` SHOWS *ANY* MODIFIED OR UNTRACKED FILES YOU DID NOT        ║
║   CREATE IN THIS SESSION, RUN THIS BEFORE DOING ANYTHING ELSE:               ║
║                                                                              ║
║       ts=$(date +%Y%m%d-%H%M%S)                                              ║
║       git diff           > /tmp/wip-unstaged-$ts.patch                       ║
║       git diff --cached  > /tmp/wip-staged-$ts.patch                         ║
║       git status -s      > /tmp/wip-status-$ts.txt                           ║
║                                                                              ║
║   Then put the changes into a real commit on a WIP branch:                   ║
║                                                                              ║
║       git checkout -b wip/<topic>-$ts                                        ║
║       git add -A && git commit -m "wip: snapshot before <action>"            ║
║                                                                              ║
║   Only AFTER both backups exist may you run anything that touches the        ║
║   working tree (rebase, checkout, restore, reset, clean, stash, …).          ║
║                                                                              ║
║   The user's unstaged edits are NOT in git's object database. A single       ║
║   wrong `git checkout <path>` overwrites them with no possible recovery.     ║
║                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

## ⚠️⚠️⚠️ FORBIDDEN COMMANDS ON A DIRTY TREE ⚠️⚠️⚠️

```
╔══════════════════════════════════════════════════════════════════════════════╗
║                                                                              ║
║   The following commands SILENTLY DESTROY unstaged user work.                ║
║   Do NOT run them on a dirty tree, even "just to clean up":                  ║
║                                                                              ║
║     git checkout <path>          ← overwrites working tree from index        ║
║     git checkout -- <path>       ← same thing, no safer                      ║
║     git restore <path>           ← overwrites working tree from index        ║
║     git restore --staged <path>  ← only safe if you've snapshot-ed           ║
║     git reset --hard             ← nukes everything unstaged                 ║
║     git clean -fd                ← deletes untracked files permanently       ║
║     git stash / git stash pop    ← see warning below; can lose data          ║
║                                                                              ║
║   If you really need to drop a single file's changes:                        ║
║     1. Do the pre-flight backup above.                                       ║
║     2. `mv path/to/file /tmp/discarded-$ts` instead of `git checkout`.       ║
║     3. Re-create from HEAD with `git show HEAD:path > path` if needed.       ║
║                                                                              ║
║   When the user asks "open a PR with these changes", your FIRST action       ║
║   is `git checkout -b <branch> && git add -A && git commit -m wip`.          ║
║   Branch first, snapshot second, polish third. Never reorder these.          ║
║                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

## ⚠️⚠️⚠️ REBASE: `--ours` AND `--theirs` ARE REVERSED ⚠️⚠️⚠️

```
╔══════════════════════════════════════════════════════════════════════════════╗
║                                                                              ║
║   During `git rebase`, the meaning of --ours / --theirs is FLIPPED           ║
║   compared to `git merge`. This trips up agents and silently drops work.     ║
║                                                                              ║
║     During MERGE:                                                            ║
║       --ours   = the branch you are ON (your work)                           ║
║       --theirs = the branch being merged in                                  ║
║                                                                              ║
║     During REBASE:                                                           ║
║       --ours   = the UPSTREAM target (e.g. master) ← NOT your work!          ║
║       --theirs = the commit being replayed (your work)                       ║
║                                                                              ║
║   Why: rebase replays your commits onto upstream, so from rebase's POV       ║
║   "ours" is the new base it is building on top of.                           ║
║                                                                              ║
║   FAILURE MODE: running `git checkout --ours <file>` during a rebase         ║
║   conflict takes the upstream version, makes your replayed commit empty,     ║
║   and rebase silently DROPS the now-empty commit. Your work disappears       ║
║   from the branch with no error message.                                     ║
║                                                                              ║
║   SAFE PATTERN when you want to KEEP your branch's version of a file         ║
║   during a rebase conflict:                                                  ║
║                                                                              ║
║       git checkout --theirs <file>     ← takes YOUR work during rebase       ║
║       git add <file>                                                         ║
║       git rebase --continue                                                  ║
║                                                                              ║
║   ALWAYS verify after `--continue`:                                          ║
║                                                                              ║
║       git log --oneline <upstream>..HEAD                                     ║
║                                                                              ║
║   If the output is empty, your commit was dropped — recover from reflog:     ║
║                                                                              ║
║       git reflog | head -20                                                  ║
║       git reset --hard <sha-of-your-commit-before-rebase>                    ║
║                                                                              ║
║   If unsure which side is which, abort and inspect both versions first:      ║
║                                                                              ║
║       git show :2:<file> > /tmp/ours.txt    # "ours"   side of the conflict  ║
║       git show :3:<file> > /tmp/theirs.txt  # "theirs" side of the conflict  ║
║       diff /tmp/ours.txt /tmp/theirs.txt                                     ║
║                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

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

## Incident Log (do not delete — this is why the rules above exist)

| Date       | What was lost                                  | Root cause                                        |
|------------|------------------------------------------------|---------------------------------------------------|
| 2026-04-28 | ~600 cpan-tester module results (4736 → 4139)  | Agent ran `git checkout dev/cpan-reports/` on an unstaged refresh; concurrent `cpan_random_tester.pl` instances also race on `.dat` files (separate bug). |
| 2026-04-29 | cpan-reports refresh commit (briefly, on a feature branch — recovered from reflog) | Agent resolved a rebase conflict with `git checkout --ours` thinking it would keep the branch's version. During rebase, `--ours` means UPSTREAM, so the upstream files were taken, the replayed commit became empty, and rebase silently dropped it. Recovery: `git reset --hard <sha>` from `git reflog`, then re-rebase using `--theirs`. |

When you cause a new incident, append a row here in the same commit
that fixes it. Future agents need to see that these warnings are real.

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

### Partially Implemented Features

| Feature | Status |
|---------|--------|
| `weaken` / `isweak` | Implemented. Uses cooperative reference counting on top of JVM GC. See `dev/architecture/weaken-destroy.md` for details. |
| `DESTROY` | Implemented. Fires deterministically for tracked objects (blessed into a class with DESTROY). See `dev/architecture/weaken-destroy.md`. |
| `Scalar::Util::readonly` | Works for compile-time constants (`RuntimeScalarReadOnly` instances). Does not yet detect variables made readonly at runtime via `Internals::SvREADONLY` (those copy type/value into a plain `RuntimeScalar` without replacing the object). |

### Unimplemented Features

PerlOnJava does **not** implement the following Perl features:

| Feature | Impact |
|---------|--------|
| `fork` | Process forking not available; use `perl` (not `jperl`) to run `perl_test_runner.pl` |
| `threads` | Perl threads not supported; use Java threading via inline Java if needed |

### Testing

**NEVER modify or delete existing tests.** Tests are the source of truth. If a test fails, fix the code, not the test. When in doubt, verify expected behavior with system Perl (`perl`, not `jperl`).

**ALWAYS capture full test output to a file.** Test output can be very long and gets truncated in the terminal. Always redirect output to a file and read from there:
```bash
# For prove-based tests
prove src/test/resources/unit > /tmp/prove_output.txt 2>&1; echo "EXIT: $?" >> /tmp/prove_output.txt

# For jperl tests
./jperl test.t > /tmp/test_output.txt 2>&1

# For perl_test_runner.pl
perl dev/tools/perl_test_runner.pl perl5_t/t/op/ > /tmp/test_output.txt 2>&1

# Then read the results from the file
```

**ALWAYS use `make` commands. NEVER use raw mvn/gradlew commands.**

| Command | What it does |
|---------|--------------|
| `make` | Build + run all unit tests (always use this) |
| `make test-bundled-modules` | Run bundled CPAN module tests (XML::Parser, etc.) |

`make dev` has been disabled on purpose — it used to build without
running tests, which let regressions sneak into commits.  Always use
`make`; if you truly need a no-test build, invoke Gradle directly
(`./gradlew shadowJar installDist`).

- For interpreter changes, test with both backends:
  ```bash
  ./jperl -e 'code'           # JVM backend
  ./jperl --interpreter -e 'code'     # Interpreter
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
   gh pr create --title "Title" --body-file /tmp/pr_body.md
   ```
   **IMPORTANT: Never use `--body` with inline text containing backticks.** Bash
   interprets backticks as command substitution, silently corrupting the PR body.
   Always write the body to a temp file first and use `--body-file`:
   ```bash
   cat > /tmp/pr_body.md << 'EOF'
   PR body with `backticks` and other markdown...
   EOF
   gh pr create --title "Title" --body-file /tmp/pr_body.md
   ```

5. **Wait for review** before merging

6. **Use `GIT_EDITOR="true"` for non-interactive git operations** (e.g., `git commit --amend`, `git rebase`). This avoids hanging on an interactive editor:
   ```bash
   GIT_EDITOR="true" git commit --amend
   ```

### Commits

- Reference the design doc or issue in commit messages when relevant
- Use conventional commit format when possible
- **Write commit messages to a file** to avoid shell quoting issues (apostrophes, backticks, special characters). Use `git commit -F /tmp/commit_msg.txt` instead of `-m`:
  ```bash
  cat > /tmp/commit_msg.txt << 'ENDMSG'
  fix: description of the change

  Details about what was fixed and why.

  Generated with [TOOL_NAME](TOOL_DOCS_URL)

  Co-Authored-By: TOOL_NAME <TOOL_BOT_EMAIL>
  ENDMSG
  git commit -F /tmp/commit_msg.txt
  ```
- **Commit Attribution:** AI-assisted commits must include attribution markers in the commit message (see [AI_POLICY.md](AI_POLICY.md)):
  ```
  Generated with [TOOL_NAME](TOOL_DOCS_URL)

  Co-Authored-By: TOOL_NAME <TOOL_BOT_EMAIL>
  ```
  Replace `TOOL_NAME` with the AI tool's name (e.g. Devin, Copilot, Claude), `TOOL_DOCS_URL` with a link to its documentation, and `TOOL_BOT_EMAIL` with the tool's GitHub bot email address (e.g. `158243242+devin-ai-integration[bot]@users.noreply.github.com`).
- **Always include `src/main/java/org/perlonjava/core/Configuration.java` in commits and PRs** - This file contains the git commit ID and date that help users identify the exact PerlOnJava version when reporting issues. Running `make` automatically updates this file via the `injectGitInfo` Gradle task. Always stage it before committing:
  ```bash
  make  # Updates Configuration.java with current commit ID
  git add src/main/java/org/perlonjava/core/Configuration.java
  ```
  **Why this matters:** Users can run `./jperl -v` to see the commit ID, making it easy to communicate exactly which version they're using when creating bug reports.

## Available Skills

See `.agents/skills/` for specialized debugging and development skills:
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
