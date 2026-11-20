# PerlOnJava Agent Guidelines

These rules apply to every AI agent working in this repository. Read the whole
file before changing the working tree. Human contributors should read
[CONTRIBUTING.md](CONTRIBUTING.md); this file adds the rules that keep
automated work safe, verifiable, and easy to review.

Contents:

1. [Professional standards](#professional-standards)
2. [Protecting the working tree](#protecting-the-working-tree)
3. [Running PerlOnJava processes](#running-perlonjava-processes)
4. [Repository layout](#repository-layout)
5. [Testing](#testing)
6. [Git workflow](#git-workflow)
7. [Commits](#commits)
8. [Coordination and documentation](#coordination-and-documentation)
9. [Implementation status](#implementation-status)
10. [Skills](#available-skills)

## Professional standards

Work the way a careful senior engineer would on a shared production codebase.

### Scope

- Do what was asked. Keep each branch and PR to one topic; propose unrelated
  improvements instead of folding them in.
- Fix the root cause in the lowest reusable layer. Do not special-case a
  module name, a test name, or an environment variable to make one case pass.
- Do not weaken a check to get a green result: no disabled tests, widened
  timeouts, skipped gates, or suppressed warnings without an explicit decision
  from the maintainer recorded in the PR.
- Prefer the smallest change that is correct. Match the surrounding code's
  style, naming, and structure; do not reformat code you are not changing.

### Evidence

- State only what you verified. Distinguish "ran and passed", "ran and
  failed", and "not run". Never describe work as complete, fixed, or passing
  without the command and result that show it.
- Report failures and skipped steps as plainly as successes. If a gate was not
  run, say so and say why.
- Standard Perl is the reference. When behavior is in doubt, check it with
  `perl` before changing PerlOnJava or a test.
- Verify a recalled file, function, flag, or issue state against the current
  tree or live GitHub state before acting on it.

### Communication

- Write for a reviewer who has no context: lead with the outcome, then the
  evidence, then what remains. Use complete sentences and plain technical
  language.
- Keep commit messages, PR descriptions, issue comments, code comments, and
  documentation factual. No emoji, exclamation marks, capitalized emphasis,
  marketing language, or narration of your own process.
- Code comments explain why the code is the way it is. Do not record
  debugging history, issue chronology, or who asked for a change; that belongs
  in the commit message.
- Ask before any action that is destructive, hard to reverse, or visible
  outside the repository (force-push, closing or merging a PR, deleting a
  branch, publishing a release, posting on an issue you do not hold a claim
  on). Approval for one such action does not extend to the next.

### Leaving the workspace clean

- Write scratch files, logs, and reproducers under `/tmp` or `build/`, never
  in the repository root or beside source files. Before finishing, confirm
  `git status` shows only intended changes.
- Remove what you created: temporary worktrees (`git worktree remove`),
  temporary branches, background processes, and test databases.
- Do not add new files or directories at the repository root, and do not add
  new top-level directories under `dev/`. See
  [Repository layout](#repository-layout).
- Do not commit generated output, credentials, local configuration, or large
  binary files.

## Protecting the working tree

Unstaged edits are not in git's object database. One wrong command destroys
them with no recovery. These rules are mandatory.

### Pre-flight for a dirty tree

If `git status` shows any modified or untracked file that you did not create
in this session, do this before anything else:

```bash
ts=$(date +%Y%m%d-%H%M%S)
git diff           > /tmp/wip-unstaged-$ts.patch
git diff --cached  > /tmp/wip-staged-$ts.patch
git status -s      > /tmp/wip-status-$ts.txt

git checkout -b wip/<topic>-$ts
git add -A && git commit -m "wip: snapshot before <action>"
```

Only after both the patches and the snapshot commit exist may you run a
command that touches the working tree (rebase, checkout, restore, reset,
clean, and similar).

When asked to open a PR from existing changes, the order is: create the
branch, commit a snapshot, then polish. Never reorder these steps.

**Exception: generated CPAN reports.** When the only pre-existing changes are
the generated report files below and the user asks to save or publish them, do
not create pre-flight patches or a WIP snapshot:

- `dev/cpan-reports/cpan-compatibility-pass.dat`
- `dev/cpan-reports/cpan-compatibility-fail.dat`
- `dev/cpan-reports/cpan-compatibility-skip.dat`
- `dev/cpan-reports/cpan-compatibility.md`

Use the [publish-cpan-reports skill](.agents/skills/publish-cpan-reports/SKILL.md)
to stage all four files while holding the tester's report lock. If any other
pre-existing path is dirty, the normal pre-flight applies. Never discard
report updates that appear after the locked snapshot.

### Commands that must not run on a dirty tree

| Command | Effect |
|---|---|
| `git checkout <path>`, `git checkout -- <path>` | Overwrites the working tree from the index |
| `git restore <path>` | Overwrites the working tree from the index |
| `git restore --staged <path>` | Safe only after a snapshot |
| `git reset --hard` | Discards all unstaged work |
| `git clean -fd` | Permanently deletes untracked files |
| `git stash`, `git stash pop` | Can silently lose changes; see below |

To drop one file's changes: complete the pre-flight, move the file aside with
`mv path/to/file /tmp/discarded-$ts`, and recreate it with
`git show HEAD:path > path` if needed.

### Never use `git stash`

Changes have been lost through `git stash` / `git stash pop` during active
work. Do not use it, even to revert temporarily. Instead:

- commit to a WIP branch before testing an alternative, or
- save uncommitted changes with `git diff > /tmp/backup.patch`.

### Rebase: `--ours` and `--theirs` are swapped

During `git rebase` the sides have the opposite meaning from `git merge`
(see git-rebase(1), "the sides are swapped"):

| Operation | `--ours` | `--theirs` |
|---|---|---|
| merge | The branch you are on (your work) | The branch being merged in |
| rebase | The upstream target, such as `master` | The commit being replayed (your work) |

Taking `--ours` during a rebase conflict selects the upstream version. The
replayed commit becomes empty and rebase drops it without an error.

To keep your branch's version of a file during a rebase conflict:

```bash
git checkout --theirs <file>
git add <file>
git rebase --continue
```

After every `--continue`, verify that your commits are still present:

```bash
git log --oneline <upstream>..HEAD
```

If the output is empty, recover from the reflog:

```bash
git reflog | head -20
git reset --hard <sha-of-your-commit-before-rebase>
```

If you are unsure which side is which, inspect both before choosing:

```bash
git show :2:<file> > /tmp/ours.txt     # "ours" side of the conflict
git show :3:<file> > /tmp/theirs.txt   # "theirs" side of the conflict
diff /tmp/ours.txt /tmp/theirs.txt
```

Force-pushing a branch to a commit equal to the base branch head makes GitHub
close the PR, and a later corrected push does not reopen it. After any
force-push, run `gh pr view <n> --json state,files` and confirm the PR is open
with the expected files; use `gh pr reopen <n>` if it was closed.

## Running PerlOnJava processes

### Always use `timeout`

Wrap every `jperl`, `jcpan`, and `prove` invocation in `timeout N`. Never use
bare `./jperl`, a background `./jperl ... &`, or `/usr/bin/time -p` alone
(which measures but does not kill) for anything that could hang.

```bash
timeout 60 ./jperl t/foo.t
timeout 60 ./jperl -Ilib -It/lib t/foo.t
timeout 3600 ./jcpan -t DBIx::Class     # long runs still get a limit
```

`./jperl` ends with `exec java`, so the JVM replaces the shell wrapper. When
the invoking shell exits, a hung JVM is reparented to PID 1 and keeps running
at full CPU with a multi-gigabyte heap. A few such orphans starve the host and
make unrelated tests miss their no-output deadlines, which then looks like a
test hang. Give each parallel worker its own `timeout`.

Before finishing, check for and remove leftover processes:

```bash
ps aux | awk '$3 > 20 {print $2, $3, $11, $12}'
pkill -9 -f "perlonjava-.*\.jar.*\.t\b"      # only if an unexpected JVM remains
```

Kill only processes you started. Another session's test run or CPAN tester may
be active on the same host.

### Build priority

Run repository `make` targets at the lowest priority so builds and gates yield
CPU on the shared host. Keep the `nice -n 19` prefix when adding a timeout or
redirecting output:

```bash
nice -n 19 make
```

### Python tooling

Homebrew Python is externally managed. Do not use `sudo pip` or
`--break-system-packages`. Use the repository virtual environment and invoke
tools that need it with `.venv/bin/python`:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install PyYAML
```

Agents may create and populate this environment when a tooling dependency is
missing.

## Repository layout

Put each file where its kind belongs. If nothing fits, ask instead of creating
a new location.

| Path | Contents |
|---|---|
| `src/main/java/` | Compiler, runtime, and Java implementations of Perl modules |
| `src/main/perl/lib/` | Bundled Perl modules |
| `src/test/resources/unit/` | Project-owned Perl unit tests |
| `src/test/resources/module/` | Bundled CPAN module test suites |
| `src/test/java/` | JUnit tests |
| `third_party/` | Forked upstream code with local changes |
| `docs/` | User documentation |
| `dev/architecture/` | How subsystems work today |
| `dev/design/` | Proposals and active plans; finished work moves to `dev/archive/` |
| `dev/modules/` | CPAN module porting notes |
| `dev/tools/` | Developer scripts, with tests in `dev/tools/tests/` |
| `dev/sandbox/` | Unsupported experiments and reproducers |
| `.agents/skills/` | Reusable agent procedures |

[dev/README.md](dev/README.md) describes every `dev/` directory.
`perl5/`, `perl5_t/`, `t/`, `build/`, and `target/` are local or generated and
are not tracked.

## Testing

### Required gates

**Always use `make`. Never invoke `mvn` or `gradlew` directly.**

| Command | Purpose |
|---|---|
| `make` | Build and run all unit tests. Required before every push. |
| `make test-bundled-modules` | Run bundled CPAN module tests |
| `make help` | List the other gates |

`make dev` is disabled because it built without running tests. If a no-test
build is genuinely required, run `./gradlew shadowJar installDist` and run
`make` before pushing.

Both `make` and `make test-bundled-modules` rebuild the development shadow
JAR. Treat each as a writer of that JAR: do not run either while `jperl`,
`jcpan`, or a test runner is using the same worktree's JAR. Start reader gates
only after the Make process and all of its workers have exited successfully.

**Documentation-only exception.** Changes limited to documentation, generated
CPAN report data, and non-executable skill instructions do not require `make`
or runtime tests. When any Markdown file changes, run `make check-links`,
which checks the root Markdown files, `docs/`, `dev/`, `.agents/`, and
`.github/`, and verifies the generated design index. For changed Markdown
outside those paths, also run `lychee --offline` on the changed files. If the
diff includes source code, executable scripts, tests, build configuration, or
other runtime-affecting files, the normal requirements apply.

### Rules

**Never modify or delete an existing test.** Tests are the source of truth. If
a test fails, fix the code. When in doubt, verify the expected behavior with
standard Perl (`perl`, not `jperl`).

**Never mutate a checkout while a build or test gate is running in it.** A
gate validates one immutable source commit. Wait for the process and all of
its children to finish before cherry-picking, rebasing, editing, or
regenerating that checkout, or integrate in a separate worktree. If a mutation
does occur, let the running processes finish without interruption, discard
the result even if it is green, and rerun from a clean commit.

**Every externally observed failure needs permanent regression coverage
before the fix is complete.** This covers failures found in imported Perl
tests, CPAN distributions, platform CI, warning scans, performance tests, or
one backend only. Add or strengthen a project-owned focused test that
reproduces the root behavior. Validate it on standard Perl first when the
behavior has a Perl-level representation, keep evidence that it fails on the
unfixed parent, and require success on both the JVM and interpreter backends
after the fix, plus direct engine coverage when an engine owns the behavior.
A passing broad suite or an untracked `/tmp` reproducer is integration
evidence, not a substitute.

**Validate new unit tests with standard Perl first.** Tests in
`src/test/resources/unit` encode standard Perl behavior, not
PerlOnJava-specific behavior. Run every new or changed unit test with `perl`
or `prove`, capture the output, and only then use it to drive a fix:

```bash
prove -r src/test/resources/unit > /tmp/prove_unit_perl.txt 2>&1; echo "EXIT: $?" >> /tmp/prove_unit_perl.txt
```

**Capture full test output in a file.** Terminal output is truncated. Redirect
and read the file:

```bash
prove src/test/resources/unit > /tmp/prove_output.txt 2>&1; echo "EXIT: $?" >> /tmp/prove_output.txt
timeout 120 ./jperl test.t > /tmp/test_output.txt 2>&1
perl dev/tools/perl_test_runner.pl perl5_t/t/op/ > /tmp/test_output.txt 2>&1
```

**Test both backends** when a change touches the interpreter or shared
runtime:

```bash
timeout 60 ./jperl -e 'code'                 # JVM backend
timeout 60 ./jperl --interpreter -e 'code'   # bytecode interpreter
```

### Perl test runner

`dev/tools/perl_test_runner.pl` runs Perl test files and reports pass/fail
counts. Run it with `perl`, not `jperl`: it needs `fork`.

```bash
perl dev/tools/perl_test_runner.pl perl5_t/t/re/regexp.t perl5_t/t/op/utfhash.t
perl dev/tools/perl_test_runner.pl perl5_t/t/op/     # operators
perl dev/tools/perl_test_runner.pl perl5_t/t/re/     # regex
perl dev/tools/perl_test_runner.pl perl5_t/t/uni/    # Unicode
```

The runner executes five jobs in parallel, applies a 300-second timeout per
test, reports `passed/total`, saves results to
`test_results_YYYYMMDD_HHMMSS.txt`, and sets the environment variables below
automatically.

When running a test directly, set them yourself where needed:

```bash
# Memory-intensive tests (re/pat.t, op/repeat.t, op/list.t) need a larger stack.
export JPERL_OPTS="-Xss256m"
# Skip tests that build strings of 300 KB or more.
export PERL_SKIP_BIG_MEM_TESTS=1

cd perl5_t/t
JPERL_OPTS="-Xss256m" PERL_SKIP_BIG_MEM_TESTS=1 timeout 600 ../../jperl re/pat.t
```

### Checking whether a failure is a regression

When a unit test fails on a feature branch, determine whether it also fails on
`master` before trying to fix it. Use a separate worktree so the feature
checkout is not disturbed:

```bash
git worktree add /tmp/poj-master-check master
cd /tmp/poj-master-check && nice -n 19 make
# ... compare, then clean up:
cd - && git worktree remove /tmp/poj-master-check
```

If the test passes on `master`, the branch introduced the regression: fix it.
If it also fails on `master`, it is pre-existing: report it and do not expand
the branch to fix it.

Useful commands:

```bash
cd perl5_t/t && timeout 600 ../../jperl <test>.t                         # run one test
timeout 600 ../../jperl <test>.t 2>&1 | grep -c "^ok"                    # count passes
JPERL_SHOW_FALLBACK=1 timeout 600 ../../jperl <test>.t 2>&1 | grep -i fallback   # interpreter fallback
```

## Git workflow

**Never push to `master`. Use a feature branch and a PR.**

**Except for the documentation-only case under [Testing](#testing), run
`make` and confirm it passes before pushing commits or updating a PR.**

1. Create a feature branch before making changes:
   `git checkout -b feature/descriptive-name`.
2. Commit on the branch with clear messages (see [Commits](#commits)).
3. Run `nice -n 19 make`. It must succeed before pushing.
4. Evaluate changelog impact before finalizing the PR. Runtime behavior,
   user-visible compatibility, major tooling or workflow changes, releases,
   and architectural changes normally warrant an entry in
   `docs/about/changelog.md`; routine generated-data refreshes and minor
   documentation corrections normally do not. Add the entry under
   `## Work in progress` in the same PR. Never add unreleased changes to a
   released version section. Match the changelog's terse bullet style; an
   entry is not a design history or an incident report.
5. Push the branch and open a PR. Fill in the PR template: summary,
   verification commands with outcomes, and the checklist.
6. Wait for review. Do not merge your own PR unless the maintainer asked you
   to.

### Shell quoting

Never place text containing backticks inside a double-quoted shell argument:
the shell runs it as a command substitution, which can execute an unintended
command or corrupt the text. Use single quotes for literal arguments, and
write PR bodies and commit messages to a file:

```bash
cat > /tmp/pr_body.md << 'EOF'
PR body with `backticks` and other markdown...
EOF
gh pr create --title "Title" --body-file /tmp/pr_body.md
```

### Non-interactive git

Set `GIT_EDITOR="true"` for operations that would open an editor:

```bash
GIT_EDITOR="true" git commit --amend
```

### GitHub transport fallback

If `git fetch` or `git push` repeatedly fails with HTTP/2 or HTTP/1.1
connection resets, or SSH times out, check whether `gh api` still works. If it
does, publish through the GitHub REST Git Database API instead of retrying the
Git transport:

1. Read the base ref with `gh api repos/OWNER/REPO/git/ref/heads/master` and
   use its commit SHA as the parent. Create a feature branch ref; never update
   `master`.
2. Create a blob for each added or changed file with
   `POST repos/OWNER/REPO/git/blobs`. Create a tree with
   `POST repos/OWNER/REPO/git/trees`, setting `base_tree` to the base commit's
   tree and listing every changed path (mode, type, blob SHA, or a null SHA
   for a deletion).
3. Create a commit with `POST repos/OWNER/REPO/git/commits` using the new tree
   and the base commit as parent. Create the feature ref with
   `POST repos/OWNER/REPO/git/refs` and `ref: refs/heads/BRANCH`.
4. Open a PR with `POST repos/OWNER/REPO/pulls`. Verify that it is open,
   targets the intended base and branch, and lists the expected files.

Use `gh api` for authentication; never put credentials in commands or files.
Preserve the local commit before switching to this fallback. When aligning a
local checkout to an API-created commit, use that exact SHA, verify the tree
and working-tree status afterwards, and complete the dirty-tree pre-flight
before any destructive local reset.

## Commits

- Use conventional commit format (`fix:`, `feat:`, `docs:`, `test:`,
  `refactor:`, `build:`, `ci:`) with an imperative subject of at most about 72
  characters. The body explains what changed and why, and names the issue or
  design document.
- One logical change per commit. Do not mix a refactor with a behavior change.
- Write the message to a file and commit with `-F` to avoid quoting problems:

  ```bash
  cat > /tmp/commit_msg.txt << 'ENDMSG'
  fix: description of the change

  Details about what was fixed and why.

  Generated with [TOOL_NAME](TOOL_DOCS_URL)

  Co-Authored-By: TOOL_NAME <TOOL_BOT_EMAIL>
  ENDMSG
  git commit -F /tmp/commit_msg.txt
  ```

- **Attribution.** AI-assisted commits must carry the attribution markers
  required by [AI_POLICY.md](AI_POLICY.md):

  ```
  Generated with [TOOL_NAME](TOOL_DOCS_URL)

  Co-Authored-By: TOOL_NAME <TOOL_BOT_EMAIL>
  ```

  Replace `TOOL_NAME` with the tool's name (for example Devin, Copilot,
  Claude), `TOOL_DOCS_URL` with a link to its documentation, and
  `TOOL_BOT_EMAIL` with the tool's GitHub bot address (for example
  `158243242+devin-ai-integration[bot]@users.noreply.github.com`).
- **Do not commit `src/main/java/org/perlonjava/core/Configuration.java`.** It
  is gitignored and generated at build time from `Configuration.java.in` by
  the `injectGitInfo` Gradle task, so `./jperl -v` still shows the real commit.
  To capture a specific build's version information for a release tag, use
  `git add -f`.

## Coordination and documentation

### Agent claims and concise checkpoints

Before implementing an issue, read its latest comments and linked PRs, then
use [the claim protocol](docs/guides/issue-triage.md#agent-work-claims).
Claims are temporary records of an agent session's work; GitHub assignees and
permanent subsystem owners are optional. Respect existing claims and
dependencies. A stale timestamp is not permission to take over or overwrite
work.

For long work, maintain one current checkpoint, normally 200–400 words. It
contains the issue and scope, agent session, branch and source commit or PR,
completed result, decisive evidence, blockers, and the immediate next action.
Refresh it after a material result, before a handoff or context compaction,
and before stopping. Resume from that checkpoint and the relevant changes
since it rather than rereading the whole history. Check live issue and PR
state before acting on a saved summary.

Keep conversation updates and issue comments brief. Replace your designated
current-state summary instead of appending repeated plans, transcripts,
command-by-command diaries, or unchanged status. Capture complete test output
in separate logs; summarize commands, source identity, outcomes, and evidence
links. Preserve required failure evidence, decisions, and unresolved findings;
the word range does not constrain necessary design documents or test logs.

Preserve append-only mailboxes and other agents' messages. Use a separate
current summary with a processed-message cursor; never truncate history to
save context. Follow
[mailbox coordination](.agents/skills/file-mailbox-coordination/SKILL.md) when
a file-coordinated pool is actually in use.

### Design documents

- Design documents live in `dev/design/`. Each major feature has its own
  document; follow the rules in [dev/design/README.md](dev/design/README.md)
  for naming, the `Status:` line, and archiving.
- When completing a phase, update the document with the date, delivered scope,
  source or merged commit, key decisions, evidence links, the next phase, and
  blockers. Keep one current-state section and a compact completed-phase
  table. Replace superseded next steps; keep durable decisions and acceptance
  evidence without accumulating an execution log.
- When the work is finished, move the description of current behavior to
  `dev/architecture/`, move the plan to `dev/archive/design/`, and regenerate
  the index with `perl dev/tools/gen_design_index.pl`.
- Do not create planning, analysis, or handoff documents that nobody asked
  for. A checkpoint belongs on the issue, not in a new file.
- The [planning index](dev/design/issue-queue.md) separates organizational
  work from project deliverables. Its dated shortlist supports task selection;
  current issue claims and PR state determine whether work is available.

### Maintaining these guidelines

Do not add chronological incident logs to `AGENTS.md` or to skills. When an
incident exposes a reusable lesson, integrate the prevention into the relevant
rule or design document; otherwise record it in the commit message. Skills
describe the current workflow, not its history.

## Implementation status

Check [docs/reference/feature-matrix.md](docs/reference/feature-matrix.md)
for the full list. The points below affect how agents test.

| Feature | Status |
|---|---|
| `weaken` / `isweak` | Implemented with selective reference counting on top of JVM garbage collection. See `dev/architecture/weaken-destroy.md`. |
| `DESTROY` | Implemented. Fires deterministically for tracked objects (blessed into a class with `DESTROY`). See `dev/architecture/weaken-destroy.md`. |
| `Scalar::Util::readonly` | Works for compile-time constants (`RuntimeScalarReadOnly`). Does not yet detect variables made read-only at runtime through `Internals::SvREADONLY`. |
| ithreads | Implemented on both backends, including `threads`, `threads::shared`, `Thread::Queue`, and `Thread::Semaphore`. See [docs/reference/threads.md](docs/reference/threads.md). |
| `fork` | Not implemented. Run `perl_test_runner.pl` with `perl`, not `jperl`. |

## Available skills

Procedures for specialized work are in `.agents/skills/`. Load the matching
skill before starting that kind of task.

| Skill | Use for |
|---|---|
| `debug-perlonjava` | Compiler and runtime test failures and regressions |
| `interpreter-parity` | Differences between the JVM and interpreter backends |
| `debug-regex-engine` | Regex parser, Joni fork, matcher, Unicode, and regex performance |
| `debug-windows-ci` | Failures that occur only on Windows CI |
| `debug-exiftool` | Image::ExifTool test failures |
| `debugger` | Perl debugger support (`-d`, stepping, breakpoints) |
| `profile-perlonjava` | Runtime profiling with Java Flight Recorder |
| `port-cpan-module` | Porting CPAN modules, replacing XS with Java |
| `port-native-module` | Porting modules that need native system calls |
| `classify-cpan-failures` | Triage of CPAN tester failures |
| `publish-cpan-reports` | Publishing the generated CPAN compatibility reports |
| `create-release` | Version bump, changelog promotion, and release |
| `file-mailbox-coordination` | Coordinating local agents through a file mailbox |
| `migrate-jna`, `fix-pat-sprintf` | Task-specific procedures retained for reference |
