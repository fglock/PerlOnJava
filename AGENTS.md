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

### Generated CPAN report publication exception

When the only pre-existing changes are these generated report files and the
user asks to save or publish them, do not create pre-flight patches or a WIP
snapshot commit:

- `dev/cpan-reports/cpan-compatibility-pass.dat`
- `dev/cpan-reports/cpan-compatibility-fail.dat`
- `dev/cpan-reports/cpan-compatibility-skip.dat`
- `dev/cpan-reports/cpan-compatibility.md`

Use the [publish-cpan-reports skill](.agents/skills/publish-cpan-reports/SKILL.md)
to stage all four files while holding the tester's report lock. If any other
pre-existing path is dirty, the normal mandatory pre-flight still applies.
Never discard report updates that appear after the locked snapshot.

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
║   "ours" is the new base it is building on top of. From git-rebase(1):       ║
║     "the side reported as ours is the so-far rebased series, starting        ║
║      with <upstream>, and theirs is the working branch. In other words,      ║
║      the sides are swapped."                                                 ║
║     https://git-scm.com/docs/git-rebase  (search for "sides are swapped")    ║
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
║   GITHUB SIDE-EFFECT: if you ever force-push the branch to a SHA that        ║
║   equals the base branch's HEAD (which happens if rebase silently drops      ║
║   your commit), GitHub will auto-CLOSE the PR. A subsequent force-push       ║
║   back to the correct SHA does NOT auto-reopen it. You have to run:          ║
║                                                                              ║
║       gh pr reopen <number>                                                  ║
║                                                                              ║
║   So: after any force-push, check `gh pr view <n> --json state,files` to    ║
║   make sure the PR is still OPEN and shows the expected files.               ║
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

## ⚠️⚠️⚠️ ALWAYS WRAP `jperl`/`jcpan` IN `timeout` ⚠️⚠️⚠️

```
╔══════════════════════════════════════════════════════════════════════════════╗
║                                                                              ║
║   Investigative agents that launch PerlOnJava test runs MUST wrap every      ║
║   `jperl`/`jcpan`/`prove` invocation with `timeout N` — NEVER just           ║
║   `/usr/bin/time -p` (which only measures, never kills) and NEVER bare       ║
║   `./jperl …` for anything that could hang.                                  ║
║                                                                              ║
║       # WRONG — JVM survives forever if it hangs                             ║
║       /usr/bin/time -p ./jperl t/foo.t                                       ║
║       ./jperl t/foo.t &                                                      ║
║                                                                              ║
║       # RIGHT — JVM is hard-killed after 60 s                                ║
║       timeout 60 ./jperl t/foo.t                                             ║
║       timeout 60 ./jperl -Ilib -It/lib t/foo.t                               ║
║                                                                              ║
║   Why this matters:                                                          ║
║                                                                              ║
║   - `./jperl` ends with `exec java …`, so the bash wrapper is replaced       ║
║     by the JVM. When the agent's own bash exits, those JVMs get              ║
║     reparented to PID 1 and KEEP RUNNING at 100% CPU — there is no           ║
║     SIGHUP propagation and no JVM-side self-watchdog.                        ║
║   - On a 48 GB Mac the JVM defaults to ~12 GB heap. A handful of orphan      ║
║     JVMs at 100% CPU silently starves the whole machine, which then          ║
║     makes the NEXT `jcpan -t Module` run miss the 300 s no-output deadline   ║
║     in `TAP::Parser::Iterator::Process` — the symptom looks like "test       ║
║     X hangs" when it's really just CPU starvation from orphans.              ║
║   - `t/96_is_deteministic_value.t` and `t/76joins.t` SIGKILLs in PR #635     ║
║     CI runs were caused exactly by this: a previous agent left ~14 orphan    ║
║     JVMs at 100% CPU each, load avg climbed to 50, and the harness gave      ║
║     up on innocent tests after 5 minutes of no TAP output.                   ║
║                                                                              ║
║   If your run REALLY may exceed any sane wall clock (e.g. a full             ║
║   `jcpan -t DBIx::Class` is ~40 min), still wrap it: `timeout 3600 ...`.     ║
║   If you spawn parallel test workers, give each its own `timeout`.           ║
║                                                                              ║
║   When you finish an investigation, sanity-check your cleanup:               ║
║                                                                              ║
║       ps aux | awk '$3 > 20 {print $2, $3, $11, $12}'                        ║
║                                                                              ║
║   If any unexpected `java …perlonjava…` shows up, kill it:                   ║
║                                                                              ║
║       pkill -9 -f "perlonjava-.*\.jar.*\.t\b"                                ║
║                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

## Project Rules

### Maintaining these guidelines

Do not add chronological incident logs to `AGENTS.md` or to skills. When an
incident exposes a reusable lesson, integrate the prevention into the relevant
main-text rule or design document; otherwise record it in the commit message.
Skills must describe the current workflow, not its history.

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
| `weaken` / `isweak` | Implemented. Uses selective reference counting on top of JVM GC. See `dev/architecture/weaken-destroy.md` for details. |
| `DESTROY` | Implemented. Fires deterministically for tracked objects (blessed into a class with DESTROY). See `dev/architecture/weaken-destroy.md`. |
| `Scalar::Util::readonly` | Works for compile-time constants (`RuntimeScalarReadOnly` instances). Does not yet detect variables made readonly at runtime via `Internals::SvREADONLY` (those copy type/value into a plain `RuntimeScalar` without replacing the object). |

### Unimplemented Features

Perl ithreads are implemented on both execution backends, including the bundled
`threads`, `threads::shared`, `Thread::Queue`, and `Thread::Semaphore` modules.
See [docs/reference/threads.md](docs/reference/threads.md) for the supported
surface and remaining ecosystem-specific limitations.

PerlOnJava does **not** implement the following Perl features:

| Feature | Impact |
|---------|--------|
| `fork` | Process forking not available; use `perl` (not `jperl`) to run `perl_test_runner.pl` |

### Testing

**Documentation-only exception:** Changes limited to documentation, generated
CPAN report data, and non-executable agent skill instructions do not require
`make` or runtime tests. When any Markdown file changes, run `make check-links`.
For changed Markdown outside the paths covered by that target, also run
`lychee --offline` on the changed files directly. If the diff includes source
code, executable scripts, tests, build configuration, or other
runtime-affecting files, the normal test requirements apply.

**NEVER modify or delete existing tests.** Tests are the source of truth. If a test fails, fix the code, not the test. When in doubt, verify expected behavior with system Perl (`perl`, not `jperl`).

**NEVER mutate a checkout while a build or test gate is running in it.** A gate
validates one immutable source commit. Wait for the process and all children to
finish before cherry-picking, rebasing, editing, or regenerating that checkout,
or run integration in a separate worktree. If mutation occurs, let the exact
processes drain without interruption, discard the result even if green, and
rerun from a clean immutable barrier.

**EVERY externally observed failure requires permanent tracked regression coverage before the fix is complete.** This includes failures found in imported Perl tests, CPAN distributions, platform CI, warning scans, performance tests, or one PerlOnJava backend. Add or strengthen a project-owned focused test that reproduces the root behavior; validate it on system Perl first when the behavior has a Perl-level representation, retain evidence that it fails on the unfixed PerlOnJava parent, and require JVM/interpreter success after the fix (plus direct engine coverage when the engine owns the behavior). A passing broad suite or an untracked `/tmp` reproducer is integration evidence, not a substitute for the permanent test.

**ALWAYS validate new unit tests with standard Perl before relying on them.** Unit tests in `src/test/resources/unit` must encode standard Perl behavior, not PerlOnJava-specific behavior. For any new or changed unit test, run it with `perl` or `prove` first, capture the output, and only then use it to drive PerlOnJava fixes:
```bash
prove -r src/test/resources/unit > /tmp/prove_unit_perl.txt 2>&1; echo "EXIT: $?" >> /tmp/prove_unit_perl.txt
```

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

Both targets rebuild the development shadow JAR. Treat each as a shared-JAR
writer: do not run either beside `jperl`, `jcpan`, or a Perl test runner that
uses the same worktree's JAR. Reader gates may start only after the Make process
and all of its workers have exited successfully.

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
# For memory-intensive tests (re/pat.t, op/repeat.t, op/list.t)
# Increases JVM stack size to prevent StackOverflowError
export JPERL_OPTS="-Xss256m"

# Skip tests with 300KB+ strings that crash the JVM
export PERL_SKIP_BIG_MEM_TESTS=1

# Example: running re/pat.t directly
cd perl5_t/t
JPERL_OPTS="-Xss256m" PERL_SKIP_BIG_MEM_TESTS=1 ../../jperl re/pat.t
```

The perl_test_runner.pl sets these automatically based on the test file being run.

### Git Workflow

**IMPORTANT: Never push directly to master. Always use feature branches and PRs.**

**IMPORTANT: Except for the documentation-only case defined under Testing,
always run `make` and ensure it passes before pushing commits or updating
PRs.** This runs all unit tests and catches regressions early.

1. **Create a feature branch** before making changes:
   ```bash
   git checkout -b feature/descriptive-name
   ```

2. **Make commits** on the feature branch with clear messages

3. **Verify tests pass** before pushing:
   ```bash
   make  # Must succeed before pushing
   ```

4. **Evaluate changelog impact before finalizing the PR.** When a PR is about
   to finish, decide whether it contains a significant project change. Runtime
   behavior, user-visible compatibility, major tooling or workflow changes,
   releases, and architectural changes normally warrant an entry in
   `docs/about/changelog.md`; routine generated-data refreshes and minor
   documentation corrections normally do not. If the change is significant,
   add its entry under `## Work in progress` in the same PR before considering
   the work complete; never add unreleased changes directly to a released
   version section. Match the changelog's existing terse bullet style and
   level of detail; do not turn an entry into a design history or incident
   report.

5. **Push the feature branch** and create a PR:
   ```bash
   git push origin feature/descriptive-name
   gh pr create --title "Title" --body-file /tmp/pr_body.md
   ```
   **IMPORTANT: Never place backtick-containing text inside a double-quoted
   shell argument.** The shell treats backticks as command substitution; this
   can execute an unintended command in search patterns or silently corrupt a
   PR body. Use literal-safe single quotes for arguments. For PR text, always
   write the body to a temp file and use `--body-file`:
   ```bash
   cat > /tmp/pr_body.md << 'EOF'
   PR body with `backticks` and other markdown...
   EOF
   gh pr create --title "Title" --body-file /tmp/pr_body.md
   ```

6. **Wait for review** before merging

7. **Use `GIT_EDITOR="true"` for non-interactive git operations** (e.g., `git commit --amend`, `git rebase`). This avoids hanging on an interactive editor:
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
- **Do NOT commit `src/main/java/org/perlonjava/core/Configuration.java`** - This file is listed in `.gitignore` and is generated at build time from `Configuration.java.in`. The `injectGitInfo` Gradle task creates it automatically (and recreates it on a fresh clone if it is absent). Committing it used to cause constant rebase conflicts on the injected git hash / date / timestamp.
  - `./jperl -v` still shows the real commit ID because the build injects it into the gitignored file before compilation.
  - If you ever need to capture a specific build's version info in git (e.g. for a release tag), use `git add -f`.

## Available Skills

See `.agents/skills/` for specialized debugging and development skills:
- `debug-regex-engine` - Perl/Joni regex ownership, oracle, parity, performance,
  and acceptance workflow
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
