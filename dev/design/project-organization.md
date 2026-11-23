# Project organization review and cleanup plan

Status: **Active** — 2026-11-20. Phases 1–3 implemented. AGENTS.md rewritten.

Goal: make the repository look and behave like a production-grade open-source
project. A first-time visitor should understand the layout in a minute, a
contributor should find one obvious place for each kind of file, and release
artifacts should be traceable. No runtime behavior changes are proposed; every
phase is a move, a deletion, a tooling addition, or a documentation rewrite.

## Current state

Measured on `master` at `2de296a36` (tracked files only unless noted).

| Area | Observation |
|---|---|
| Top level | 43 tracked entries; 8 Markdown files, 2 licence texts, 8 launcher scripts, 2 build systems, plus `MANIFEST`, `lib/`, `perljava.pod`, `scripts/` (one file) |
| `src/` | 6,848 files: 3,303 main, 3,545 test |
| `dev/` | 815 files in 27 subdirectories; 14 of them hold seven files or fewer |
| `dev/design/` | 151 documents in one flat directory, mixed naming (`UPPER_SNAKE`, `snake_case`, `kebab-case`); about 23 are dated batches, handoffs, or single-issue notes |
| `docs/` | 29 files, cleanly structured (`getting-started`, `guides`, `reference`, `about`, `faq`); `docs/design/` holds a single file |
| Unit tests | 1,981 `.t` files, most of them flat in `src/test/resources/unit/` |
| Bundled Perl | 2,624 files in `src/main/perl/lib/`, upstream CPAN code and project-owned code interleaved |
| Java | 346k lines; six hand-written files exceed 5,000 lines (`RuntimeCode` 11.2k, `BytecodeCompiler` 9.9k, `NetSSLeay` 9.4k, `RuntimeScalar` 6.7k, `RuntimeRegex` 5.4k, `XMLLibXML` 5.2k) |
| Build | `build.gradle` (971 lines) and `pom.xml` (522 lines) both carry version `5.44.1`; the Makefile has about 50 targets and no `help` |
| CI | Two workflows: the main build and a single-issue performance workflow |
| Community files | No issue or PR templates, `CODEOWNERS`, `CODE_OF_CONDUCT.md`, `dependabot.yml`, `.editorconfig`, or root `CHANGELOG.md` |
| Working tree | Tests run from the repository root leave ignored debris there (`store*`, `integer.*`, `*.tmp`, `*.dat`, `fake out!`, heap dumps); `.gitignore` has grown about 25 rules to hide it |

What already works well and should be kept: the `docs/` structure, the
`frontend` / `backend` / `runtime` / `app` Java package split, the single
`make` entry point, the SBOM targets, and `.agents/skills/`.

## Findings

1. **`dev/` has no organizing principle.** Stable architecture references,
   active plans, finished incident notes, LLM prompts, sandbox code, slides,
   tooling, and generated reports sit side by side. Several directories overlap:
   `architecture` / `implementation` / `custom_bytecode` / `regex` are all
   "how it works"; `sandbox` / `examples` / `repros` / `known-bugs` are all
   "scratch code"; `tools` / `scripts` / `maintenance` are all "scripts".
2. **`dev/design/` mixes living designs with history.** Completed handoffs and
   dated batch notes outnumber what a reader needs, and the README index cannot
   keep up. Superseded proposals (`documentation.md`, `implementation_status.md`,
   `new_repo.md`, `todo.md`) are still at the top level of the directory.
3. **The repository root is crowded.** `MANIFEST` and `lib/README.md` exist
   only because two imported tests expect them in the current directory.
   `MILESTONES.md` is a redirect stub. `perljava.pod`, `scripts/`, `Dockerfile`,
   and `Configure.pl` have no shared home.
4. **Tests pollute the checkout.** Upstream tests that write relative paths run
   with the repository root as their working directory. This is the root cause
   of the `.gitignore` sprawl and makes `git status` noise-prone for agents.
5. **Vendored and owned code are indistinguishable.** There is no manifest of
   upstream name, version, licence, and local patches for
   `src/main/perl/lib/`, `com/booking/sereal`, `com/google/zxing`, or
   `third_party/joni`. This blocks licence review and makes upgrades guesswork.
   (`dev/import-perl5/config.yaml` covers perl5-derived files only.)
6. **Two build systems.** Maven duplicates the dependency list and version, is
   not exercised by CI, and is reachable only through `test-maven-parallel`.
7. **The unit-test directory is flat.** Nearly 2,000 files with only a few
   topical subdirectories (`regex`, `refcount`, `overload`) makes ownership and
   targeted runs harder than necessary.
8. **Standard project-hygiene signals are missing.** Templates, code owners,
   dependency update automation, formatting configuration, and a release
   workflow are what outside users check first.
9. **Oversized classes.** A handful of files concentrate unrelated
   responsibilities; they dominate merge conflicts and review cost.
10. **Generated data lives in git history.** `dev/cpan-reports/*.dat` and the
    report Markdown total 1.3 MB and are rewritten on every tester run.

## Target layout

```
/                    README, LICENSE, CONTRIBUTING, SECURITY, AI_POLICY, AGENTS,
                     CHANGELOG, build files, launchers — nothing else
bin/                 jperl, jcpan, jprove, jperldoc (+ .bat); root keeps thin
                     shims only if compatibility requires them
docs/                user-facing documentation (unchanged structure)
dev/
  architecture/      how the system works today (stable, reviewed)
  design/            proposals and active plans only, one file or folder each
    archive/         completed, superseded, and incident documents
  modules/           CPAN porting notes
  tools/             every developer script, with tests
  bench/             benchmarks
  reports/           generated CPAN compatibility data
  import-perl5/      upstream sync tooling
  talks/             presentations
  scratch/           sandbox, examples, repros — explicitly unsupported
src/main/java/       project-owned Java
src/main/perl/lib/   project-owned Perl (PerlOnJava::*, patched core shims)
src/vendor/          unmodified upstream Perl and Java, with VENDORED.yaml
src/test/resources/unit/<area>/   tests grouped by subsystem
third_party/         forked upstream with local changes (joni)
packaging/           Dockerfile, deb, Windows installer, native launcher
```

## Plan

Phases are ordered by benefit per unit of risk. Each is one PR (or a short
series), keeps `make` green, and uses `git mv` so history follows the files.
Phases 1–3 are documentation and metadata only; later phases touch build
configuration and need the full gate.

### Phase 1 — Root and community files

- Add `.github/ISSUE_TEMPLATE/` (bug, CPAN module failure, feature),
  `pull_request_template.md`, `CODEOWNERS`, `dependabot.yml` (Gradle and
  Actions), `CODE_OF_CONDUCT.md`, and `.editorconfig`.
- Add a root `CHANGELOG.md` that links to `docs/about/changelog.md`, or move
  the changelog to the root; pick one canonical location.
- Delete `MILESTONES.md`; fold `QUICKSTART.md` into
  `docs/getting-started/` and link it from the README.
- Move `perljava.pod` to `docs/reference/`, `scripts/inject-git-info.ps1` to
  `dev/tools/`, and `Dockerfile` plus `native/` to `packaging/`.
- Move `docs/design/joni-callout-fork.md` to `dev/design/`.
- Add `make help` generated from target comments, and group Makefile targets
  under section headers.

Acceptance: root listing fits on one screen; GitHub's community-standards
checklist is complete; `make check-links` passes.

### Phase 2 — Consolidate `dev/`

| From | To |
|---|---|
| `implementation/`, `custom_bytecode/` (docs), stable parts of `regex/` | `architecture/` |
| `scripts/`, `maintenance/`, `jvm_profiler/` | `tools/` |
| `sandbox/`, `examples/`, `repros/`, `known-bugs/`, `design_spark/`, `diagnostic-traces/` | `scratch/` |
| `cpan-reports/` | `reports/cpan/` |
| `presentations/` | `talks/` |
| `prompts/` | `design/archive/` for analyses; delete obsolete prompts; live procedures become skills |
| `test-manifests/`, `test-corpora/`, `framework-testing/` | `tools/` or `src/test/`, depending on consumer |
| `patches/`, `unicode/` | unchanged, or under `import-perl5/` if only that tooling reads them |

Update every hard-coded path in the Makefile, `build.gradle`, skills, and
`AGENTS.md` in the same PR. The CPAN report paths are named in `AGENTS.md` and
the `publish-cpan-reports` skill; move them only while no tester holds the
report lock.

Acceptance: `dev/` has at most ten subdirectories, each with a README stating
its purpose and whether its contents are supported.

### Phase 3 — Triage `dev/design/`

- Adopt one naming rule: `kebab-case.md`, no dates or issue numbers in names
  of living documents.
- Give every document a front-matter status line: `Proposal`, `Active`,
  `Implemented`, `Superseded`, or `Historical`.
- Move `Implemented`, `Superseded`, and `Historical` documents — handoffs,
  dated batches, single-issue and single-PR notes — to `design/archive/`.
  Where an implemented design describes current behavior, promote the durable
  part to `dev/architecture/` first.
- Merge near-duplicates (the eleven `cpan-*` / `jcpan-*` tooling batches, the
  three `refcount_alignment_*` files, `docs.md` / `documentation.md`,
  `debugger.md` / `perl_debugger.md` / `jdwp_debugger.md`).
- Replace the prose README with a generated index table (title, status, last
  change) produced by a small script in `dev/tools/` and checked by
  `make check-links`.

Acceptance: fewer than forty documents at the top of `dev/design/`; the index
is generated, not hand-maintained.

### Phase 4 — Hermetic tests and a clean checkout

- Run every bundled-module and imported test with a per-test temporary
  working directory, in `ModuleTestExecutionTest`, `perl_test_runner.pl`, and
  the `jcpan` test path.
- Provide the fixtures `t/op/readdir.t` and `t/op/stat_errors.t` expect inside
  that directory, then delete root `MANIFEST` and `lib/README.md`.
- Send JVM heap dumps to `build/` via `-XX:HeapDumpPath` in the launchers.
- Remove the `.gitignore` rules that only existed to hide the debris, and add
  a CI step that fails when `git status --porcelain --ignored` shows new files
  outside `build/` after the test run.

Acceptance: a full `make` leaves the working tree byte-identical outside
`build/` and `.gradle/`.

### Phase 5 — Vendored code inventory

- Add `VENDORED.yaml`: path, upstream distribution, version, licence, and
  local-patch flag for every bundled CPAN distribution and Java library.
  Generate the first draft from `$VERSION` strings and the existing SBOM
  tooling.
- Separate unmodified upstream Perl into `src/vendor/perl/lib/` and keep
  `src/main/perl/lib/` for project-owned modules and patched shims. Both
  directories are packaged into the JAR at the same resource path, so runtime
  `@INC` behavior is unchanged.
- Move `com/booking/sereal` and `com/google/zxing` out of `src/main/java`
  into Maven Central dependencies where an equivalent release exists,
  otherwise into `third_party/` beside joni.
- Feed `VENDORED.yaml` into `make sbom` and add a `NOTICE` file.

Acceptance: every file in the shipped JAR is attributable to either this
project or a named upstream version, and the SBOM is derived from that data.

### Phase 6 — Synchronize the two build systems

Maven is a supported entry point (some users prefer Maven tooling). Both
`build.gradle` and `pom.xml` remain.

- Move the project version to `gradle.properties` as the single source and
  have the Maven POM read it or be generated from it.
- Split `build.gradle` into `gradle/*.gradle` script plugins (packaging,
  testing, SBOM, git-info) so the main file states only what the project is.
- Verify that `mvn test` and `./gradlew test` produce the same test count.

Acceptance: version appears in one file; the two build systems agree on
dependencies and test scope.

### Phase 7 — Group unit tests by subsystem

- Introduce `unit/<area>/` directories matching existing ones (`regex`,
  `refcount`, `overload`): for example `parser`, `io`, `tie`, `threads`,
  `sprintf-pack`, `modules/<Distribution>`, `cpan-regressions`.
- Move files only; contents stay untouched, consistent with the rule against
  modifying tests. Confirm the discovery glob in `PerlScriptExecutionTest`
  recurses, and that the test count before and after is identical.
- Record the area list in `src/test/resources/README.md` so new tests land in
  the right place.

Acceptance: no more than about fifty files directly in `unit/`; identical
pass count on system Perl and both backends.

### Phase 8 — CI, release, and quality gates

- Split the single workflow into named jobs: build and unit, threads,
  bundled modules, link check, SBOM. Reuse one built JAR as an artifact.
- Replace `issue-1863-performance.yml` with a general, manually triggered
  benchmark workflow.
- Add a tag-triggered release workflow: build, SBOM, checksums, GitHub
  release, Maven Central publication (see `maven-central-publishing.md`).
- Add Java formatting (Spotless) in check mode on changed files only, and a
  compiler-warnings budget that may not increase.

Acceptance: a `v*` tag produces a signed, reproducible release without manual
steps; every PR shows separately named checks.

### Phase 9 — Large-class decomposition (ongoing)

Not a single PR. Set a soft ceiling of about 3,000 lines for hand-written
classes and require any PR that grows a file past it to extract a cohesive
unit first. Initial candidates, each needing its own design note:
`RuntimeCode` (call dispatch, eval-string, caller stack), `BytecodeCompiler`
(per-node-family compilers, as `CompileAssignment` already is), `NetSSLeay`
and `XMLLibXML` (per-API-group classes), `IOOperator`. Move the generated
Unicode tables (`PerlUnicode*Data.java`) into binary resources loaded at
startup so they stop inflating source metrics and compile time.

### Phase 10 — Generated reports out of mainline history (optional)

Publish CPAN compatibility data to a dedicated `reports` branch or GitHub
Pages and keep only the summary Markdown on `master`. Defer until the tester
workflow and the publish skill can be changed together.

## Risks and constraints

- **Path churn breaks agents and skills.** Each move PR must update
  `AGENTS.md`, `.agents/skills/`, the Makefile, and `build.gradle`, and should
  run `grep -r` for the old path before merge.
- **Open PRs conflict with moves.** Schedule Phases 2, 3, and 7 when the open
  PR count is low, and announce them on the coordination issue.
- **JAR resource layout is a compatibility surface.** Phase 5 must be verified
  by diffing the file list of the shadow JAR before and after.
- **Windows CI** exercises the `.bat` launchers and path handling; Phases 1
  and 4 need both platforms green.
- **Tests are the source of truth.** Phase 7 moves files without editing them.

## Suggested order and effort

| Phase | Effort | Risk | Depends on |
|---|---|---|---|
| 1 Root and community files | Small | Low | — |
| 2 Consolidate `dev/` | Medium | Low | — |
| 3 Triage `dev/design/` | Medium | Low | 2 |
| 4 Hermetic tests | Medium | Medium | — |
| 5 Vendored inventory | Large | Medium | — |
| 6 Synchronize build systems | Medium | Medium | — |
| 7 Group unit tests | Medium | Low | 4 |
| 8 CI and release | Medium | Medium | 6 |
| 9 Class decomposition | Ongoing | High | — |
| 10 Reports off mainline | Small | Low | 2 |

Phases 1–4 deliver most of the visible improvement and can be finished
without touching runtime code.

## Resolved decisions

1. **Maven stays as a supported build.** Some users prefer Maven tooling.
   Phase 6 synchronizes the two instead of removing one. (2026-11-20)
2. **Launchers stay at the root for now.** Moving to `bin/` needs review of
   the Windows `.bat` launchers and the Debian installer. A future phase may
   still do this with root shims. (2026-11-20)
3. **`dev/prompts/` is archived.** Moved to `dev/archive/prompts/`. Skills
   replace the prompt workflow. (2026-11-20)
4. **Finished design docs archived in-tree** at `dev/archive/design/`.
   (2026-11-20)

## Related documents

- [Documentation improvement plan](docs.md)
- [Issue organization and agent coordination](issue-organization.md)
- [Patch and CPAN distropref layout](patch-and-cpan-prefs-layout.md)
- [Maven Central publishing](maven-central-publishing.md)
- [SBOM](sbom.md)
- [Planning index](issue-queue.md)
