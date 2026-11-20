# PerlOnJava development workspace

Developer resources that are not part of the shipped distribution. User
documentation lives in [`docs/`](../docs/README.md); contributor rules live in
[`CONTRIBUTING.md`](../CONTRIBUTING.md) and [`AGENTS.md`](../AGENTS.md).

## Where things go

| Directory | Holds | Status of contents |
|---|---|---|
| [architecture/](architecture/README.md) | How the system works today: subsystem references and per-feature implementation notes, including the [bytecode interpreter](architecture/custom_bytecode/README.md) | Maintained; update with the code |
| [design/](design/README.md) | Proposals and active plans. Finished, superseded, and incident documents move to [archive/](archive/README.md) | Each document states its status |
| [archive/](archive/README.md) | Finished and superseded design documents, and pre-skill prompt files | Historical; not maintained |
| [modules/](modules/README.md) | CPAN module porting notes, XS replacements, `jcpan` and MakeMaker behavior, [framework testing](modules/framework-testing/README.md) | Maintained per module |
| [regex/](regex/) | Regex engine workspace: oracle data, parity tooling, and plans | Maintained |
| [tools/](tools/README.md) | Developer scripts and their tests, [maintenance scripts](tools/maintenance/README.md), [JVM profiling guide](tools/jvm_profiler/README.md) | Maintained; tools used by `make` have tests in `tools/tests/` |
| [bench/](bench/README.md) | Benchmarks and the benchmark runner | Maintained |
| [import-perl5/](import-perl5/README.md) | Tooling and configuration for importing files from the upstream perl5 tree | Maintained |
| [unicode/](unicode/) | Unicode Character Database inputs for the table generators | Generated input; do not edit by hand |
| [patches/](patches/README.md) | Reference patches for third-party CPAN distributions | Maintained |
| [test-corpora/](test-corpora/) | Pinned third-party test files and checksum manifests used by `make` gates | Do not edit; replace only with a new pinned version |
| [cpan-reports/](cpan-reports/README.md) | Generated CPAN compatibility data | Generated; publish with the `publish-cpan-reports` skill |
| [presentations/](presentations/README.md) | Talks, workshop material, and blog drafts | Historical |
| [sandbox/](sandbox/README.md) | Experiments, examples, reproducers, known-bug scripts, and raw diagnostic traces | Unsupported; may be outdated or broken |

## Rules

1. Put a file in the directory whose description matches it. Do not create a
   new top-level directory under `dev/` for a single topic; use a subdirectory
   of the closest match.
2. A document that describes current behavior belongs in `architecture/`. A
   document that proposes or tracks a change belongs in `design/` and moves to
   `dev/archive/` when the work is finished or abandoned.
3. A reproducer that should keep passing is a unit test under
   `src/test/resources/unit/`, not a sandbox file.
4. Reusable agent procedures are skills under `.agents/skills/`, not prompt
   files.
5. Nothing in `sandbox/` or `dev/archive/` may be a dependency of the
   build or of a test gate.
