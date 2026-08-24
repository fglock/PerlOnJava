---
name: create-release
description: Create a PerlOnJava release, including the project-wide version bump, changelog promotion, validation, release PR, exact merged-commit tag, and GitHub release. Use for PerlOnJava version bumps, release preparation, release tags, or GitHub release publication.
---

# Create a PerlOnJava release

Follow `AGENTS.md`, especially its dirty-tree preflight, testing, branch, commit-attribution, and no-direct-push-to-master rules.

## Prepare

1. Fetch `origin` and create a clean `release/<version>` branch from current `origin/master` in a separate worktree.
2. Confirm the tag and GitHub release do not already exist.
3. Inspect the previous tag and GitHub release for naming, notes, and tag style.
4. Record the starting version from `src/main/java/org/perlonjava/core/Configuration.java.in`.

## Update the version

Run from the repository root:

```bash
perl Configure.pl -D version=<version>
```

Review every changed file. Search the entire tracked tree for both the old and new versions, including regex-escaped forms such as `5\\.44\\.0`. Update current product-version references, generated artifact names, launchers, packaging checks, examples, tests, and active documentation. Preserve references that are explicitly historical, such as prior changelog entries, upstream Perl history/delta documentation, incident records, and design discussions about older releases.

Do not commit the generated, ignored `Configuration.java`.

## Promote the changelog

In `docs/about/changelog.md`:

1. Leave a new, empty `## Work in progress` section at the top.
2. Promote the previous work-in-progress content to `## v<version>: <terse title>`.
3. Consolidate implementation history into short user-facing bullets. Keep important features, compatibility improvements, performance changes, and bug fixes; omit PR chronology, internal evidence mechanics, and superseded intermediate details.

Use the promoted changelog section as the source for GitHub release notes.

## Validate and integrate

1. Validate this skill when it changed:

   ```bash
   python3 /Users/fglock/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/create-release
   ```

2. Run `make`, capture its complete output, and stop if it fails.
3. Run `make test-bundled-modules`, capture its complete output, and require every bundled-module test to pass.
4. Commit with the required AI attribution, push the release branch, and open a PR using `--body-file`.
5. Monitor all required CI checks. Merge only after local validation and CI pass.

## Tag and publish

1. Fetch `origin/master` after the release PR merges.
2. Verify the release changes are present and identify the exact merged `origin/master` commit.
3. Create `v<version>` using the same annotated/lightweight convention as the preceding release, targeting that exact commit. Verify the local tag target before pushing it.
4. Push only the release tag, then verify the remote tag resolves to the intended commit.
5. Create the GitHub release from a notes file, matching the previous release's title and concise Markdown style. Mark it latest unless this is explicitly a prerelease.
6. Verify the published release URL, title, tag, release status, and target commit.

Stop rather than overwrite an existing tag/release, publish from an unmerged branch, tag an unexpected commit, or continue after a failed required check.
