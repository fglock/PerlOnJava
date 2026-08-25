---
name: publish-cpan-reports
description: Publish the four generated PerlOnJava CPAN compatibility report files through a lock-consistent commit, GitHub PR, and merge. Use when asked to save, publish, or submit current dev/cpan-reports compatibility results; do not use for editing tester code or classifying failures.
---

# Publish CPAN reports

Publish one coherent snapshot of these generated files without stopping active
`cpan_random_tester.pl` processes:

- `dev/cpan-reports/cpan-compatibility-pass.dat`
- `dev/cpan-reports/cpan-compatibility-fail.dat`
- `dev/cpan-reports/cpan-compatibility-skip.dat`
- `dev/cpan-reports/cpan-compatibility.md`

## Safety boundary

The generated files are the only dirty-tree exception for this workflow. If
any other pre-existing path is modified or untracked, follow the normal dirty
tree pre-flight before proceeding. Stage only the four paths above; never use
`git add -A` for a report snapshot.

Do not stop report generators, clean the checkout, restore files, or discard
new report changes. Writers may update the worktree immediately after the
snapshot; those later changes belong to the next report publication.

## Locked snapshot

Create a report publication branch before staging. Derive the lock path using
the same `report_lock_name($project_root)` and `File::Spec->tmpdir` logic as
`dev/tools/cpan_random_tester.pl`; do not guess a fixed `/tmp` path. Acquire an
exclusive `flock` on that file, run `git add` for all four report paths while
holding the lock, and release it only after `git add` exits.

The tester holds this same lock while atomically replacing all four files, so
the Git index is a coherent transaction even if the worktree changes after the
lock is released. Commit from the index without restaging later updates.

Before committing, verify:

- the staged diff contains exactly the four report paths;
- the line counts of the three staged `.dat` files equal the pass, fail, skip,
  and total values in the staged Markdown summary;
- `make check-links` passes;
- `lychee --offline dev/cpan-reports/cpan-compatibility.md` passes because that
  generated Markdown file is outside the paths covered by `make check-links`.

Do not run `make` or runtime tests for this data-only publication.

## Publish

Commit with a report-refresh message, push the feature branch, and create a PR
whose body records the staged totals and that only link validation was run.
When the user asked to save or publish the reports, merge the report-only PR
after verifying that it is open and contains exactly the four expected files.
If the user asked only to open a PR, leave it open.

Run the GitHub PR creation, inspection, and merge commands from a neutral
temporary directory outside every Git worktree, and pass the repository
explicitly with `--repo`. In particular, never run `gh pr merge` from the
report checkout: with branch deletion enabled, the CLI may switch that checkout
to the base branch and fast-forward it. Using a neutral directory makes
`--delete-branch` a remote-only cleanup.

Use the repository's normal merge method and verify the merged PR from the same
neutral directory. After merging, do not fetch, switch branches, or otherwise
mutate the report checkout; active testers may already have produced the next
set of report updates there.
