# jcpan compiler and tooling compatibility batch (2026-08-17)

## Objective

Fix reusable compiler, runtime, and CPAN-tooling failures exposed by
`WordNet::SenseRelate::WordToSet`, `SPVM::Sys::Time::Timezone`,
`Data::OpenStruct::Deep`, `App::column::run`,
`Dist::Zilla::Plugin::Rinci::AddPrereqs`, `Oracle::Loader`, and their
dependencies. Targets that fail under system Perl may be excluded.

## Progress Tracking

### Current Status: implementation and local validation complete

### Completed Phases

- [x] Phase 1: initial reproduction and system-Perl gate (2026-08-17)
  - `WordNet::SenseRelate::WordToSet` fails under system Perl because its
    `WordNet::QueryData` prerequisite cannot be loaded, so that target is
    excluded unless an earlier independent tooling defect is found.
  - `Data::OpenStruct::Deep` exposed a bundled `Want` arity/prototype mismatch.
  - `App::column::run` exposed quadratic parsing of the 2.3 MB PERLANCAR
    `CHECKSUMS` file; a JVM thread dump located the repeated prefix scan in
    `StringParser.parseRawStringWithDelimiter`.
- [x] Phase 2: reusable compiler and runtime fixes (2026-08-17)
  - Indexed each parser's token source-line offsets once by token identity,
    preserving line numbers through token rewrites/removals while eliminating
    the repeated prefix scan.
  - Changed bundled `Want::want` from a one-scalar prototype to varargs and
    matched combined, negated, scalar, list, lvalue, and chained-object
    predicates against PerlOnJava's existing raw call contexts.
- [x] Phase 3: target and project validation (2026-08-17)
  - `Data::OpenStruct::Deep` passes 3 files / 11 tests on both system Perl and
    PerlOnJava.
  - `App::column::run` passes 5 files / 2 tests on PerlOnJava after its 2.3 MB
    author `CHECKSUMS` file is parsed without the former timeout.
  - `Dist::Zilla::Plugin::Rinci::AddPrereqs` passes 4 files / 1 test after its
    full dependency graph is built.
  - `WordNet::SenseRelate::WordToSet` is excluded because system Perl cannot
    load its `WordNet::QueryData` prerequisite.
  - `SPVM::Sys::Time::Timezone` is excluded because system Perl cannot build
    the SPVM and SPVM::Resource prerequisite chain.
  - `Oracle::Loader` is excluded because its unchanged distribution installs
    root `Loader.pm` while its test requires `Oracle/Loader.pm`; system Perl
    fails before running any assertions.
  - The upstream Want behavior regression was validated with system Perl, and
    the full project `make` passes all unit shards and packaging checks.

### Next Steps

1. Run final post-documentation validation and commit with attribution.
2. Publish the pull request and monitor Linux and Windows CI.

### Open Questions

- None. The three excluded targets have reproducible system-Perl failures.

## Related References

- `.agents/skills/debug-perlonjava/SKILL.md`
- `docs/guides/module-porting.md`
