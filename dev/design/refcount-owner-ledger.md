# Perl-Exact Reference Ownership and Closure-Pad Lifetime

Status: Active — 2026-11-26

**Delivery issue:** [#2043](https://github.com/fglock/PerlOnJava/issues/2043)
(origin [#1132](https://github.com/fglock/PerlOnJava/issues/1132), history in #1649).
**Target PR:** [#1938](https://github.com/fglock/PerlOnJava/pull/1938).
**Related:** [Weaken & DESTROY](../architecture/weaken-destroy.md),
[Refcount alignment plan](refcount_alignment_plan.md),
[Refcount alignment progress](refcount_alignment_progress.md).

## Objective

Align PerlOnJava's observable reference counts, weak-reference lifetime,
closure capture lifetime, cycles, and deterministic `DESTROY` with system
Perl, using explicit owner tokens instead of JVM reachability or
selective-refcount heuristics.

Issue #2043 adds one constraint: no lifecycle decision may depend on a memory
scan. Reference counts, weak-observer clearing, and destruction timing must
follow maintained owner-slot state. Do not add tree walks, snapshots, or
proxy-list sweeps to fix a failure; fix the owner transition or maintain an
index.

## Current state (2026-11-26)

| Item | Value |
|---|---|
| Local branch | `wip/issue2043-pr1938-20261125`, tracking `origin/wip/alias-cell-ownership-20261030-185849` (the PR head branch) |
| Committed HEAD | `7288fb4faf`, equal to the PR head, so a plain `git push` fast-forwards |
| PR #1938 | Open, hosted checks green on `7288fb4faf`, but **conflicting with `master`** (checked 2026-11-26 12:47) |
| Master | `origin/master` is `357bccf2c9`, 10 commits ahead of the branch. Conflicts: `docs/about/changelog.md`, `ModuleOperators.java`, `GlobalVariable.java` |
| Working tree | Uncommitted: 12 Java files, this document, and 6 new unit tests. Do not reset, stash, or discard. Backup: `/tmp/wip-unstaged-20261126-125432.patch` and `/tmp/wip-untracked-20261126-125432.tgz` |
| Development JAR | `target/perlonjava-5.44.1.jar`, written 12:36, built from the current working tree |
| Last full gate | `nice -n 19 make` on the working tree: exit 2 after 10m07, one failure (`array_last_index_anon_array_owner.t`). Log: `/tmp/issue2043-step3-owner-slot-step4-undefined-only-make.log` |

### Focused test matrix

Each cell is JVM / interpreter. "master" is the JAR built from `cfd34e3dd7`
(`/private/tmp/issue2043-master-scan-check`), "HEAD" the JAR built from
`2edf51f698` (`/private/tmp/issue2043-pre-scan-merge`), "WIP" the current
working tree. The master and HEAD columns are in `/tmp/issue2043-baseline/`;
the WIP column was rerun at 12:50 and is in `/tmp/issue2043-validate/`. The six
tests marked *new* are untracked files in `src/test/resources/unit/`; all six
pass system Perl (20 assertions, `/tmp/issue2043-added-tests-system-perl.log`).

| Test | master | HEAD | WIP | Required action |
|---|---|---|---|---|
| `op/inccode.t`, `op/inccode-tie.t` (imported) | 75 / 75 | fail 60 / fail 60 | fail 60 / fail 60 | Fix A. Branch regression. |
| `array_last_index_anon_array_owner.t` (*new*) | 0 of 3 / 0 of 3 | 2 of 3 / 2 of 3 | 0 of 3 / 0 of 3 | Fix B. The WIP made it worse. |
| `autoload_named_glob_alias_name.t` (*new*) | pass / fail | fail / fail | pass / fail | Fix C. Interpreter only. |
| `core_builtin_reference_alias.t` (*new*) | pass / 4 of 6 | pass / 4 of 6 | 6 of 6 / 6 of 6 | Done. |
| `eval_regex_code_hints_refcount.t` | pass / pass | pass / pass | pass / pass | Done. |
| `readonly_coercion_argument_lifetime.t` | pass / pass | pass / pass | 8 of 8 / 8 of 8 | Done. |
| `core_prototype_alias_origin.t` (*new*) | pass / fail | pass / fail | pass / pass | Done. |
| `lvalue_sub_explicit_return_readonly.t` (*new*) | pass / pass | 4 of 8 / 0 of 8 | 8 of 8 / 8 of 8 | Done. |
| `require_error_lists_all_searched_paths.t` (*new*) | fail / fail | fail / fail | pass / pass | Done. |
| `goto_tailcall_cleanup.t` | pass / pass | pass / pass | 13 of 13 / 13 of 13 | Done. |

### Open failures

**A. `op/inccode.t` assertion 60 (branch regression, imported test).** After
`do "foo"` through an `@INC` hook `sub { $die, $data }`, the CODE reference
`$die` reports 2 references where 1 is expected. Assertions 61–63 (`$data`,
and both values after `do "bar"`) pass, so the extra owner is specific to the
first CODE element of the hook's return list and does not accumulate. The
uncommitted `MortalList.pushMark()` / `flushAboveMark()` wrapper around
`invokeIncHook` in `ModuleOperators` does not change the result; the owner is
not a mortal queued above that mark.

**B. `\$#{[]}` after the anonymous array is freed (new test).** Perl returns
`undef` for `$$ref` and warns `Attempt to set length of freed array` on
assignment. At HEAD only the first assertion fails (`-1` instead of `undef`).
The working tree fails all three. The log history bisects this: the test was
still 2 of 3 at 11:46 (`/tmp/issue2043-array-release-trace.jvm.log`), after
the generation rewrite of `RuntimeArray.java` (last edited 11:04), and 0 of 3
at 11:57 (`/tmp/issue2043-finalfix-array_last_index_anon_array_owner.t.jvm.log`).
The regression is therefore in the later edits to
`RuntimeArraySizeLvalue.java`, not in the generation rewrite and not in the
12:35 `PerlOwnerSlot` hook, which was added afterwards and changed nothing.

**C. `autoload_named_glob_alias_name.t` on the interpreter (new test).**
`*alias = *target; alias();` with no `target` defined must set `$AUTOLOAD` to
`main::alias`. The JVM backend passes because `RuntimeCode` now prefers a
qualified call-site name over `referenceOriginFqn` at both
"Does AUTOLOAD exist?" sites. The interpreter still reports `main::target`.
The uncommitted block in `SlowOpcodeHandler` (after
`throwIfDirectCallUndefined`) that clones the CV and sets
`referenceOriginFqn` has no effect on this test. This fails on the master
interpreter too, so it is an interpreter gap rather than a ledger regression.

### Next steps

Read this whole section before starting. The order is chosen so that the
ten-minute full gate runs once.

#### Build and test loop

Three commands are used throughout. Never run two of them at the same time in
this checkout, and never edit files while one is running.

1. **Fast JAR build, about one minute, no tests.** Use this for every
   iteration. AGENTS.md permits it when a no-test build is required, provided
   `make` runs before the push.

   ```bash
   nice -n 19 ./gradlew shadowJar installDist > /tmp/issue2043-jar.log 2>&1; echo "EXIT: $?" >> /tmp/issue2043-jar.log
   tail -5 /tmp/issue2043-jar.log
   ```

   If it prints `BUILD FAILED`, read the compiler error in the log, fix it,
   and rebuild. Do not run tests against a JAR from a failed build: check
   that `ls -la target/perlonjava-5.44.1.jar` shows a time later than your
   last edit.

2. **Focused matrix, about two minutes, both backends.** Pass an output
   directory. It prints one line per cell; compare against the table above.

   ```bash
   OUT=/tmp/issue2043-iter; mkdir -p $OUT; U=src/test/resources/unit
   for t in array_last_index_anon_array_owner autoload_named_glob_alias_name \
            core_builtin_reference_alias core_prototype_alias_origin \
            eval_regex_code_hints_refcount goto_tailcall_cleanup \
            lvalue_sub_explicit_return_readonly readonly_coercion_argument_lifetime \
            require_error_lists_all_searched_paths; do
     timeout 120 ./jperl $U/$t.t               > $OUT/$t-jvm.log 2>&1
     timeout 120 ./jperl --interpreter $U/$t.t > $OUT/$t-interp.log 2>&1
     for m in jvm interp; do
       echo "$t $m ok=$(grep -c '^ok' $OUT/$t-$m.log) notok=$(grep -c '^not ok' $OUT/$t-$m.log)"
     done
   done
   for t in inccode inccode-tie; do
     (cd perl5_t/t && timeout 300 ../../jperl op/$t.t)               > $OUT/$t-jvm.log 2>&1
     (cd perl5_t/t && timeout 300 ../../jperl --interpreter op/$t.t) > $OUT/$t-interp.log 2>&1
     for m in jvm interp; do
       echo "$t $m ok=$(grep -c '^ok' $OUT/$t-$m.log) notok=$(grep -c '^not ok' $OUT/$t-$m.log)"
     done
   done
   ```

   While working on one fix, run only its test by hand instead of the whole
   loop; run the whole loop before moving to the next step.

3. **Full gate, about ten minutes.** Run it once, in step 5. Earlier full
   runs are wasted time: a unit failure outside the matrix is unlikely to be
   found faster by `make` than by the matrix, and any later edit invalidates
   the result.

Fixes A, B, and C touch different files. Develop them one at a time, but
when two candidate edits are ready together, compile them in one JAR build.

#### Step 1. Snapshot the working tree (5 minutes)

The uncommitted work exists nowhere else. Commit it locally before the merge.
Nothing is pushed in this step.

```bash
git status -s                       # expect the 13 modified and 6 untracked paths listed in git, nothing else
git add -A
git commit -m "wip: snapshot issue 2043 regression fixes before master merge"
git branch wip/issue2043-snapshot-20261126      # backup pointer; delete in step 7
SNAP=$(git rev-parse HEAD); echo $SNAP          # record this SHA; later steps use it
```

The commit is local and the PR is squash-merged, so a `wip:` subject is
acceptable here. If `git status` shows a path you do not recognize, stop and
ask; do not add it.

#### Step 2. Merge master and rebuild once (30–60 minutes)

The PR cannot merge until this is done, and fix A edits a conflicting file,
so merge first.

```bash
git fetch origin master
git merge origin/master             # expect three conflicts
```

Resolve each file, then `git add` it:

- `docs/about/changelog.md`: keep every bullet from both sides under
  `## Work in progress`. Do not move bullets into a released section.
- `src/main/java/org/perlonjava/runtime/runtimetypes/GlobalVariable.java`:
  the working tree did not change this file, so the conflict is between
  committed branch code and master. Compare the sides with
  `git show :2:<path> > /tmp/ours.java` (branch) and
  `git show :3:<path> > /tmp/theirs.java` (master). Keep the branch's
  owner-slot calls and add master's new logic around them.
- `src/main/java/org/perlonjava/runtime/operators/ModuleOperators.java`:
  master changed package and caller context for nested `require`
  (`3614619c27`, `4e22a6895b`, `6a501b1dd9`). Take master's version of the
  hook-invocation block and **drop the `MortalList.pushMark()` /
  `flushAboveMark()` wrapper**; it is proven ineffective (failure A) and is
  the main source of the conflict. Keep the other WIP change in this file:
  the removal of `syntheticBundledInc`, so that `incEntriesChecked` always
  records the entry. `require_error_lists_all_searched_paths.t` depends on it.

Finish with `GIT_EDITOR=true git commit`, confirm
`git log --oneline origin/master..HEAD | head -3` shows the merge and the
snapshot, then run the fast JAR build and the focused matrix with
`OUT=/tmp/issue2043-postmerge`.

Expected result: identical to the WIP column above.

If it is not what you expect:

- **The merge reports different conflicting files.** Master moved again.
  Resolve by the same rule: branch owner-slot code plus master's new logic.
  If a conflict is in a file under `runtimetypes/` that you cannot resolve
  with confidence, run `git merge --abort` (safe: the snapshot is committed)
  and ask the maintainer.
- **The JAR build fails after the merge.** The error is almost always a
  method that master renamed or a signature the branch changed. Fix the call
  site; do not revert master's commit.
- **A matrix cell that was passing now fails.** Find out whether master
  alone fails it before debugging: build a master JAR in a separate worktree
  (one minute, no tests) and run the one test there.

  ```bash
  git worktree add /tmp/issue2043-master-357b origin/master
  (cd /tmp/issue2043-master-357b && nice -n 19 ./gradlew shadowJar installDist > /tmp/issue2043-master-357b-jar.log 2>&1)
  timeout 120 /tmp/issue2043-master-357b/jperl src/test/resources/unit/<test>.t
  ```

  If master also fails, it is pre-existing: record it under Known limitations
  and continue. If master passes, the merge resolution is wrong; re-read the
  conflict hunks for that subsystem. Keep this worktree until step 5 and
  remove it in step 7.

#### Step 3. Fix B: restore the array test to 2 of 3, then reach 3 of 3

Do this first: it is the only failure the working tree itself introduced.

1. Restore the two warning assertions. Compare the working version of
   `RuntimeArraySizeLvalue.java` with the PR head:
   `git diff 7288fb4faf HEAD -- src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeArraySizeLvalue.java`.
   Three edits are candidates, in this order of likelihood:
   `argumentAliasContainer()` now returns `null` unless the parent has
   `refCount > 0` or a strong owner identity; the two
   `hasLivePerlValueOwner` overrides return `false`; the constructor no
   longer clears `arrayLengthLvalueOrphaned`. Revert one, rebuild the JAR,
   and run the test on JVM. Stop at the first revert that returns 2 of 3 and
   keep the other edits.
2. Remove the `UNTRACKED_REFERENCE` block added to `PerlOwnerSlot` (the
   `array.orphanArraySizeLvalues()` call near line 1430). It did not move the
   result, so it is not the owning transition.
3. Fix assertion 1. The anonymous array `[]` in `\$#{[]}` must have its
   size-proxy generation invalidated when the statement's temporary owner is
   released. Find which owner that is before editing: add a temporary
   `System.err.println` with `new Throwable().printStackTrace()` at the top
   of `RuntimeArray.orphanArraySizeLvalues()` and a second print where the
   anonymous-array owner slot is released (`PerlOwnerSlot`, the release
   method that contains `registeredInOwnerLedger = false`), printing `kind`
   and `System.identityHashCode(previous)`. Run
   `timeout 60 ./jperl -e 'my $r = \$#{[]}; print defined $$r ? "live\n" : "undef\n"'`.
   The release line whose identity matches the array tells you the slot
   kind. Call `orphanArraySizeLvalues()` from that transition, guarded by
   `!array.hasPerlStrongOwner()`. Remove the prints before the matrix run.

Done when the test is 3 of 3 on both backends and the imported `$#`
coverage is unchanged: record
`(cd perl5_t/t && timeout 300 ../../jperl op/array.t | grep -c '^ok')`
before the first edit of this step and require the same count afterwards.
The constraint from the Objective applies: the fix
is one call at one owner transition. Do not re-add a list of proxies and do
not walk arrays to find them.

If it does not work:

- **None of the three reverts restores 2 of 3.** Reverse the whole WIP
  change to that one file and confirm:
  `git diff 7288fb4faf $SNAP -- <path to RuntimeArraySizeLvalue.java> | git apply -R`.
  If the file no longer compiles against the generation fields in
  `RuntimeArray.java`, reverse `RuntimeArray.java` the same way. That returns
  to the PR-head proxy-list implementation, which is known to give 2 of 3.
- **The release print never shows the array.** The array has no owner slot
  at all; it is an untracked temporary whose reference count never rises.
  In that case the transition is the statement-end mortal flush. Look at how
  `MortalList.scopeExitCleanupArrayBody()` reaches `orphanArraySizeLvalues()`
  for lexical arrays (line 1306) and find the equivalent point for a
  temporary reference.
- **Assertion 1 is still failing after about two hours.** Stop. Report the
  slot kind you found and what you tried. The maintainer decides between
  more time and deferring: the test is 0 of 3 on master, so 2 of 3 is an
  improvement, but a new test may not be committed failing. Deferral means
  leaving this one test file out of the commit and opening a follow-up
  issue; that is the maintainer's decision, not yours.

#### Step 4. Fix A, then fix C

**Fix A, `@INC` hook return list.** Identify the owner before changing code.

```bash
cat > /tmp/issue2043-inc-trace.pl << 'EOF'
my $die = sub { die };
my $data = [];
unshift @INC, sub { $die, $data };
print "before: ", &Internals::SvREFCNT($die), "\n";
do "foo";
print "after:  ", &Internals::SvREFCNT($die), "\n";
print Internals::jperl_owner_trace($die), "\n";
EOF
PJ_REFCOUNT_TRACE=1 timeout 60 ./jperl /tmp/issue2043-inc-trace.pl > /tmp/issue2043-inc-trace.log 2>&1
```

`before` and `after` must be equal; today `after` is one higher. The trace
output lists the live owner slots on the CODE object with their kinds and
acquire sites. Run the same script on the master JAR
(`/private/tmp/issue2043-master-scan-check/jperl`) and compare: the slot
present on the branch and absent on master is the leak. Then find where that
slot kind is acquired on the path `ModuleOperators.invokeIncHook` →
`readIncHookSource`, and release it where the hook result stops being used
(after `readIncHookSource` returns or throws). The first return-list element
is the one treated as a possible source filter or generator, which is why
only `$die` leaks; start with the branch of `readIncHookSource` that
inspects element 0 as CODE.

Done when both files are 75 of 75 on both backends.

If it does not work:

- **The trace prints `NOT_REF` or no slot list.** Pass the reference itself
  (`$die`, not `\$die`) and confirm `PJ_REFCOUNT_TRACE=1` is in the
  environment of the `jperl` process.
- **Branch and master traces show the same slots.** The difference is then
  in how `Internals::SvREFCNT` counts, not in a leaked slot. Compare
  `Internals.svRefcount` between `origin/master` and HEAD for a slot kind
  that the branch counts and master did not. Do not re-add an
  `ARGUMENT_ALIAS` subtraction; that was tried and broke two other tests.
- **The fix makes 61–63 or another refcount test fail.** The release is in
  the wrong place and is dropping an owner that `$data` or a later `do`
  still needs. Undo it and release only the element-0 CODE owner.
- **No owning transition is found after about two hours.** Stop and report
  the two traces. This is a regression against master on an imported test,
  so it cannot be deferred without the maintainer's decision.

**Fix C, interpreter AUTOLOAD name.** Find out why the JVM fix does not
apply. In `RuntimeCode`, both "Does AUTOLOAD exist?" sites now use
`subroutineName` when it contains `::`. Print `subroutineName` and
`code.referenceOriginFqn` at both sites and run
`timeout 60 ./jperl --interpreter src/test/resources/unit/autoload_named_glob_alias_name.t`.

- If `subroutineName` is empty or unqualified on the interpreter, the fix
  is at the interpreter call opcode: pass the call-site name (the handler
  in `SlowOpcodeHandler` already has it in `name`) into the `apply` call the
  way the JVM backend does. Then delete the CV-cloning block from
  `SlowOpcodeHandler`; it is dead code.
- If `subroutineName` is correct but neither site is reached, the
  interpreter takes a third dispatch path; find it by printing a stack trace
  from the `AUTOLOAD` assignment and apply the same name preference there.

Done when the test passes on both backends and
`core_builtin_reference_alias.t` stays 6 of 6 on both.

If the interpreter needs a change to its call convention (a new opcode
operand, or a changed `apply` signature used by other opcodes), stop and
report that boundary. Either way, remove the `SlowOpcodeHandler` block if it
is still without effect: unproven code does not ship.

After fixes A, B, and C: one JAR build, then the whole matrix with
`OUT=/tmp/issue2043-final`. Every cell must pass. Run the six new tests and
any test you added with system Perl again
(`prove src/test/resources/unit/<file>.t > /tmp/issue2043-final/perl-<file>.log 2>&1`).

#### Step 5. Full gate, once (10–15 minutes)

Preconditions: `git status` shows only intended edits, no debug prints remain
(`git diff | grep -n 'System.err\|printStackTrace'` prints nothing new), and
nothing else is running in this checkout. Commit the fixes first so the gate
validates a commit, using the format in AGENTS.md, one logical fix per
commit.

```bash
git fetch origin master && git merge-tree --write-tree HEAD origin/master > /dev/null && echo "still mergeable"
nice -n 19 timeout 1800 make > /tmp/issue2043-final-make.log 2>&1; echo "EXIT: $?" >> /tmp/issue2043-final-make.log
tail -30 /tmp/issue2043-final-make.log
```

Do not touch the checkout until it exits. Expected: `BUILD SUCCESSFUL` and
`EXIT: 0`.

If it is not what you expect:

- **`still mergeable` is not printed.** Master gained a new conflict. Merge
  it now (as in step 2), rerun the JAR build and matrix, then start this step
  again. If master only advanced without conflict, do not merge; the gate is
  valid for the commit you push.
- **A unit test fails.** Do not rerun `make`. Run that one test with
  `./jperl` and `./jperl --interpreter`, then against
  `/tmp/issue2043-master-357b/jperl` (step 2) and the PR-head JAR
  `/private/tmp/issue2043-pre-scan-merge/jperl`. Fails on master: pre-existing,
  record it and ask whether the gate may proceed. Passes on master, fails on
  the PR-head JAR: an older branch regression; fix it with the JAR-build
  loop. Passes on both: caused by today's fixes; bisect by reverting them one
  at a time. After any code change, the full gate must run again from the
  start, so batch every fix before rerunning it.
- **The run is killed at 1800 seconds or a test fails only on timing.** Two
  other sessions load this host. Check
  `ps aux | awk '$3 > 20 {print $2, $3, $11, $12}'`; do not kill their
  processes. Wait for them to finish and rerun once. Do not raise a test
  timeout.

#### Step 6. Document and publish (20 minutes)

1. `docs/about/changelog.md`, under `## Work in progress`, one terse bullet
   each for the user-visible fixes in this batch: `require` errors list every
   searched `@INC` entry; an lvalue sub rejects a readonly value from an
   explicit `return`; CORE builtins keep their identity and prototype through
   glob aliases; `AUTOLOAD` reports the called alias name; `@INC` hook return
   values are released after loading; `\$#{[]}` is orphaned when the array is
   freed. Omit a bullet whose fix was deferred.
2. Replace this "Current state" section with the final matrix (all cells from
   `/tmp/issue2043-final/`), the gate result, and the remaining next step
   (maintainer review). Add one row to "Completed phases". Move any deferred
   item to "Known limitations" with its issue number.
3. Documentation-only commit, then the two checks:

   ```bash
   nice -n 19 make check-links > /tmp/issue2043-final-check-links.log 2>&1; echo "EXIT: $?" >> /tmp/issue2043-final-check-links.log
   git diff --check origin/wip/alias-cell-ownership-20261030-185849..HEAD
   git status -s            # must be empty
   ```

   These two edits change no runtime file, so the step 5 gate stays valid.
   If you change any Java, Perl, or test file after step 5, rerun step 5.
4. Push without force and verify:

   ```bash
   git push origin HEAD:wip/alias-cell-ownership-20261030-185849
   gh pr view 1938 --json state,headRefOid,mergeable,mergeStateStatus
   ```

   Expected: `OPEN`, `headRefOid` equal to `git rev-parse HEAD`, and
   `mergeable` `MERGEABLE`. Update the PR description's Validation section
   with the gate commit, duration, and log names. Watch hosted checks with
   `gh pr checks 1938`. Do not merge.

If it is not what you expect:

- **The push is rejected as non-fast-forward.** Someone pushed to the PR
  branch. Do not force. `git fetch`, inspect
  `git log HEAD..origin/wip/alias-cell-ownership-20261030-185849`, merge it,
  and return to step 5.
- **`mergeable` is `CONFLICTING`.** Master moved between the gate and the
  push. Merge master, rerun the JAR build, the matrix, and step 5.
- **A hosted check fails.** Read the failing job log with
  `gh run view --log-failed`. A Windows-only failure follows the
  `debug-windows-ci` skill. Do not push a speculative fix; reproduce or
  reason from the log first.

#### Step 7. Clean up

```bash
git worktree remove /tmp/issue2043-master-357b      # if created
git branch -D wip/issue2043-snapshot-20261126       # only after the push is verified
ps aux | awk '$3 > 20 {print $2, $3, $11, $12}'     # no JVM of yours remains
```

Keep the logs under `/tmp/issue2043-final/` and
`/tmp/issue2043-final-make.log`; the PR description refers to them. After the
maintainer merges: verify #2043 is resolved, move the design sections below
to `dev/architecture/`, archive this plan, and run
`perl dev/tools/gen_design_index.pl`.

## Completed phases

| Date | Delivered scope | Source and evidence |
|---|---|---|
| 2026-11-19 | Maintained owner-kind counts and narrow identity indexes; correct nested hash-slot `B::REFCNT` during `DESTROY` | Owner-ledger unit gate; DBIx `t/60core.t`, `t/101populate_rs.t` |
| 2026-11-21 | Interpreter foreach source owners retained through normal and `last` exits; overwritten temporary call-argument owners released | `4edc5faa51`, `eb8907f362`; system Perl, JVM, interpreter |
| 2026-11-21 | eval/die `DESTROY` LIFO order; weak-tracked scalar storage, raw untracked slots, and async foreach owner restoration | Fails on unfixed `aebe3b0742`; full `make` passed |
| 2026-11-21 | Ordinary aggregate cleanup retires direct outgoing slots; `RecursiveAggregateReleaseWalker` and `CleanupTargetProbe` removed from that path | `nested_untracked_aggregate_owner_release.t` on system Perl and both backends |
| 2026-11-22 | Scope-exit pre-scans removed; tied arrays use raw storage during slot retirement | Full `make` passed |
| 2026-11-22 | Runtime-only eval captures separated from semantic closure owners; JVM frame-exit owner release | All 12 DBIx `t/resultset/*.t` pass on JVM with LeakTracer clean |
| 2026-11-22 | Anonymous array call arguments stage an owner token; explicit `exit` unwinds lexical owners before `END` | `dbix_schema_init_shutdown_lifecycle.t`; DBIx `t/relationship/core.t`, `t/inflate/*.t` |
| 2026-11-22 | Package-array root marking skipped for already rooted aggregates | `11838413af`; `PackageArrayRootMarkingFastPathTest` |
| 2026-11-24 | `releaseDetachedUntrackedReferenceOwners` iterates the untracked-owner index instead of an owner-slot snapshot | `339bf5cd7a`; full `make` passed |
| 2026-11-24 | Eight-target CPAN acceptance on exact PR head | `ee827a2ad5`; see Acceptance evidence |
| 2026-11-26 | Reachability scans removed: eval-created CV templates own explicit slots, the global-destruction cleanup pre-scan is gone, and `ownerSlotCount` reads maintained per-kind counts | `9d22a6a6ce`; `OwnerSlotKindCountIndexTest` |
| 2026-11-26 | `undef %hash` selects destructor ordering from a maintained direct-value identity index | `RuntimeHashDestroyableValueIndexTest`, `undef_hash_owner_index.t` |
| 2026-11-26 | Master merged into the PR branch | `2edf51f698`; full `make` passed in 8m18; hosted checks green |

## Design

### Ownership model

```text
RuntimeCode --semantic capture--> RuntimeScalar pad cell --strong slot--> referent
```

- The declaring scope and semantic closure captures own the pad cell.
- A strong pad cell owns exactly one referent token; a weak pad cell owns none.
- Closures sharing one pad cell do not change the referent count.
- Compiler metadata and generated JVM object fields are not Perl roots and
  own neither the pad nor its referent.

### Invariants

1. A live pad cell owns at most one token for its current referent, and
   exactly one when the cell is strong and the referent is tracked.
2. `semanticCaptureCount` affects pad lifetime, not referent cardinality.
3. Metadata captures never affect Perl lifetime.
4. Reassignment releases the old token before acquiring the new one.
5. `weaken()` releases the pad token; captures do not recreate it.
6. The final pad owner releases the token exactly once.
7. Lifecycle decisions follow owner slots. Java reachability neither creates
   nor retires a Perl owner.
8. Strong Perl cycles stay alive until a strong edge is explicitly broken.
9. No root traversal, snapshot, or sweep changes reference counts, weak
   observers, or destruction timing.

### Runtime state

- `slotOwnsReferent` (currently `refCountOwned`): the scalar slot owns one
  strong referent token.
- `scopeOwnerAlive`: the declaring lexical scope still owns the pad cell.
- `semanticCaptureCount`: live Perl closures sharing the pad cell.
- `metadataCaptureCount`: conservative or diagnostic captures with no
  ownership effect.
- `CaptureBinding`: a `RuntimeCode` record pairing a pad cell with capture
  provenance; each semantic binding is released exactly once.

`scopeExited` is derived lifecycle state, not an ownership heuristic.

### State transitions

| Event | Pad ownership | Referent ownership |
|---|---|---|
| Strong lexical assignment | Scope owns pad | Pad owns one token |
| Semantic closure capture (first or additional) | Add pad owner | Unchanged |
| Metadata capture | Unchanged | Unchanged |
| Declaring scope exits | Remove scope owner | Token kept while semantic captures remain |
| One of several closures is released | Remove one pad owner | Unchanged |
| Final closure is released after scope exit | Pad becomes dead | Release one token |
| Captured scalar is reassigned | Pad remains live | Release old token; acquire one new token |
| Captured scalar is weakened | Pad remains live but weak | Release token |
| Captured scalar is unweakened | Pad remains live and strong | Acquire one token |

A borrowed scalar promoted into a semantic captured pad acquires one pad-slot
token. Further closures sharing that pad acquire none.

### Capture provenance

Both backends produce equivalent capture descriptors.

- **JVM:** attach semantic `CaptureBinding` records at compile time. Do not
  infer ownership from reflected generated fields.
- **Interpreter:** distinguish lexicals the closure references from
  conservative snapshots of visible registers. Preserve `eval STRING` access
  to lexicals without making unrelated registers permanent owners. Release
  bindings through `RuntimeCode.releaseCaptures()`, as the JVM backend does.
- **Threads:** rebuild slot ownership and semantic captures in an ithread
  clone from capture descriptors, not from the source scalar's transient
  `refCountOwned`.

### Owner ledger

Every owner-producing path names an owner kind: scalar or pad slot, aggregate
element, package global, glob or stash slot, CODE storage, tie wrapper, pad
constant, temporary call hold, explicit destruction rescue, or another
runtime-specific strong Perl edge. Debug validation asserts
`refCount == sum(authoritative owner tokens)`. Temporary aliases, generated
fields, and conservative captures produce no token.

A referent stays alive while any strong Perl slot remains, including edges in
strong cycles. Releasing the final slot performs cleanup through the ordinary
container and lifecycle paths. Temporary leases and suspended-frame owners
are explicit slots.

### Scan-free mechanisms

| Decision | Mechanism |
|---|---|
| Owner-kind presence and counts | Per-kind counts maintained on add, release, and reclassification |
| Final array and hash cleanup | Retire direct element owners; child containers cascade through their own slots |
| `undef %hash` destructor ordering | Direct-value identity index maintained by hash mutation, scalar replacement, and blessing; aliased cells and wrapped shared maps take progressive cleanup |
| Global destruction | Visit each nested aggregate once through the existing cycle-detection set; no cleanup-target pre-scan |
| Eval-created anonymous CVs | Explicit owner slots, released at eval completion or CV teardown |
| Package-array roots | Global table writes establish the root; reads of a rooted aggregate skip re-marking |
| Detached untracked owners | Iterate the untracked-owner index |
| `\$#array` proxies (`RuntimeArraySizeLvalue`) | Generation identity on the array; orphaning replaces the generation (uncommitted) |

## Acceptance evidence

**CPAN, exact PR head `ee827a2ad5` (2026-11-24).** Excel::Writer::XLSX
(1,263 files / 5,189 tests), PPR (1,255), Image::ExifTool with
File::RandomAccess (113 files / 600), DateTime (3,592), and Template Toolkit
(121 files / 3,180) pass. Three failures are classified outside this work:

| Target | Failure | Issue |
|---|---|---|
| DBIx::Class | 1 of 43,020: `t/storage/txn.t` caller source line (expected 451, got 448 and 447); reproduces on baseline `278c12be27` | [#2170](https://github.com/fglock/PerlOnJava/issues/2170) |
| Catalyst | 2 of 3,477 parser assertions; reproduces on master `1ee4ff572d` | [#2168](https://github.com/fglock/PerlOnJava/issues/2168) |
| Mojolicious | 1 of 3,735: `t/mojo/asset.t` cannot fork under host pressure; waived by the maintainer | [#2169](https://github.com/fglock/PerlOnJava/issues/2169) |

**DBIx::Class leak coverage.** `diamond_prefetch_cursor_leak.t`,
`diamond_prefetch_cursor_leak_permutations.t`, and
`diamond_prefetch_shutdown_leak.t` under `src/test/resources/unit/refcount/`
require `DBIC_CLASS_TEST_ROOT` and skip without it. They pass on system Perl
and both backends. Upstream `t/prefetch/diamond.t`, `t/60core.t`,
`t/52leaks.t`, and all 12 `t/resultset/*.t` pass on JVM with LeakTracer clean.

**Performance, `chart_axis28.t`, seven checksum-matched pairs at nice 19–20.**
Results are per workload and are not a portfolio-wide claim; JFR figures are
sampled weights.

| Change | Throughput ratio (95% interval) | Allocation |
|---|---|---|
| Direct-slot cleanup against the recursive walker | 0.970 [0.869, 1.075] and 0.993 [0.886, 1.128]: no reliable change | Unchanged (7.80 GB) |
| Package-array root fast path (`11838413af` against `2362185545`) | 11.92 [10.53, 13.68] | Not measured |
| Untracked-owner index (`339bf5cd7a` against `3e97440c60`) | 1.074 [1.061, 1.088] | 8.126 GB to 7.617 GB |

## Known limitations

- `tied_handler_value_cell_alias.t` fails only on the interpreter and
  reproduces on master `4a5c33753f`.
- Five DBIx `t/resultset/*.t` files exceed 600 seconds on the interpreter;
  unclassified. All-backend DBIx acceptance is not claimed.
- System Perl reports three `update_delete.t` SQL-status mismatches in the
  DBIx resultset sweep, unrelated to leak witnesses.
- A full `jcpan -t DBIx::Class` run does not finish within one hour.
- #2037 and #2034 are separate follow-up work.

## Regression matrix

New or changed Perl tests must pass system Perl before PerlOnJava output is
used to drive a fix.

**Pad and closure ownership**

- Zero, one, and two closures sharing one lexical retain one referent owner.
- Independent lexical copies produce independent referent owners.
- Scope exit preserves a referent until the final closure is released.
- Releasing one of two closures does not release the shared pad token.
- The final release clears weak observers and invokes `DESTROY` exactly once.
- Nested closures and returned closures share the correct pad cell.
- Argument aliases and borrowed captures are promoted exactly once.
- Self-capturing CODE and strong closure cycles match Perl.

**Mutation**

- Weakening a captured slot makes it non-owning; unweakening restores one owner.
- Reassignment releases the old referent and retains the new one.
- Repeated reassignment cannot release a stale referent through deferred work.
- Ordinary weak references clear when no strong owner exists.

**Capture provenance**

- JVM and interpreter closures report equivalent semantic captures.
- Interpreter register snapshots do not retain unrelated objects.
- `eval STRING` retains only the Perl-visible lexical environment for the
  correct code-object lifetime.
- Generated JVM objects and reflected fields do not become Perl roots.
- ithread clones reconstruct independent ownership.

**Compatibility guards to keep green**

- `unit/weak_localized_cache_lifetime.t`
- Closure-cycle, captured-scalar, weak-callback, Sub::Quote, and Sub::Defer
  tests under `unit/refcount/`
- Focused Moo and Sub::Quote tests, DBIx::Class leak-tracer and weak-registry
  tests
- Exact-refcount tests using `B::REFCNT` or `Test2::Tools::Refcount`
- Issue #1132: the Future reproducer prints `completed=1`,
  `Future::Utils::repeat` prints `ready=1 result=final`, and
  Net::Async::HTTP `t/05redir.t`, `t/30timeout.t`, and `t/32remove.t` pass on
  both backends

## Final gates

- Every new or changed Perl test passes system Perl first.
- The focused test fails on the unfixed parent, with the log kept.
- Focused tests pass on JVM and interpreter.
- `make` passes with complete captured output on the exact source commit.
- `make check-links` passes.
- No unexpected high-CPU PerlOnJava JVM remains.

## History

The [ownership rewrite handoff](perl-ownership-rewrite.md#current-handoff-2026-11-15)
and PR [#1204](https://github.com/fglock/PerlOnJava/pull/1204) (branch
`fix/issue-1132-closure-lifetime`) record the earlier issue #1132 delivery.
Both are superseded by the current state above.

## Open question

Can the remaining compiler-created aggregate temporaries be made explicit in
the owner ledger instead of relying on opcode-specific transfers?
