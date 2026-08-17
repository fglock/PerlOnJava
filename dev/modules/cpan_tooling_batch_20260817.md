# CPAN compiler and tooling compatibility batch (2026-08-17)

## Goal

Make `jcpan -t` work for Cantella::Worker, Story::Interact::WWW,
Catmandu::PICAplus, Net::RabbitMQ::Channel, Parallel::Fork::BossWorker,
Pod::XPath, MetaStore, and their supported dependencies. Prefer reusable
compiler, runtime, and CPAN-tooling fixes over distribution preferences. A
target that also fails under the local system Perl may be left unsupported
with the failure recorded.

## Baseline

All `jcpan`, `prove`, and system-CPAN invocations use a hard timeout and write
their complete output under `/tmp`.

| Target | First actionable result |
|---|---|
| Cantella::Worker | Its prefork manager reaches unsupported process `fork` after portable assertions. |
| Story::Interact::WWW | XS-only Text::Markdown::Hoedown cannot load. |
| Catmandu::PICAplus | The dependency graph tries native XML tooling and previously failed through optional Sub::Util metadata. |
| Net::RabbitMQ::Channel | Class::Easy's generated nested module is absent because MakeMaker runs its `.PL` file before creating the target directory. |
| Parallel::Fork::BossWorker | Its sole fixed-plan test reaches unsupported process `fork` after three portable assertions. |
| Pod::XPath | Pod::XML produces malformed XML and two assertions fail. |
| MetaStore | The parser rejects indirect constructors whose class name ends in `::`; XML::Writer also assigns an aliased literal argument to itself. |

## Progress Tracking

### Current Status: implementation and local validation complete

### Completed Phases

- [x] Initial reproduction and system-Perl classification (2026-08-17)
  - Pod::XPath fails identically under system Perl and is excluded under the
    requested rule.
  - Net::RabbitMQ::Channel cannot build its Net::RabbitMQ prerequisite under
    system Perl and is excluded under the requested rule.
  - Catmandu::PICAplus's Catmandu-PICA distribution fails under system Perl:
    its importer aborts on encoded I/O and its fix test contains the same
    parser-invalid trailing comma seen on PerlOnJava. It is excluded under the
    requested rule after confirming the bundled XML::LibXML dependency path.
  - Text::Markdown::Hoedown passes its complete upstream system-Perl suite.
- [x] Reusable compiler/runtime/tooling fixes (2026-08-17)
  - Permit exact-identity assignment through a read-only literal alias while
    retaining errors for distinct equal-valued constants.
  - Parse indirect constructor calls after a trailing package separator.
  - Create parent directories before MakeMaker runs `.PL` generators for
    nested installation targets.
  - Complete fixed Test::Builder plans with explicit skips when an unsupported
    process `fork` is reached after portable assertions.
  - Restrict interpreter eval-exception scope cleanup to actual lexical
    registers so installed closures retain their captured variables.
  - Preserve Perl's runtime-regex treatment of unrecognized `\u` escapes and
    emit categorized warnings for characters an encoding layer cannot map.
- [x] Portable Java dependency backend (2026-08-17)
  - Added Text::Markdown::Hoedown over the already bundled commonmark-java
    libraries, including HTML, table-of-contents, and callback renderers.
- [x] Confirmed target results (2026-08-17)
  - MetaStore passes 4 files / 40 tests.
  - Class::Easy passes 6 files / 88 tests with strict dependency testing.
  - Story::Interact::WWW passes 3 files / 3 tests.
  - Cantella::Worker passes 2 files / 16 tests; fork-only assertions are
    represented as platform skips.
  - Parallel::Fork::BossWorker passes 1 file / 20 tests; fork-only assertions
    are represented as platform skips.
  - XML::Writer passes its strict dependency suite: 4 files / 274 tests.
- [x] Project validation (2026-08-17)
  - `make` passes all 500 unit tests (2 expected skips).
  - The Text::Markdown::Hoedown bundled suite passes in the JVM harness, and
    its unchanged upstream suite passes under system Perl.
  - The captured-lexical eval regression passes on both JVM and interpreter
    backends.
  - The full informational interpreter matrix completes with its existing
    baseline (13,714 / 13,865 assertions passing). The runtime, parser,
    fixed-plan fork, and captured-lexical regressions pass there; the
    MakeMaker integration test reaches the interpreter's existing unsupported
    `File::Find` local-operand path.

### Next Steps

1. Open the pull request.
2. Monitor Linux and Windows CI and address any platform-specific failures.

### Open Questions

- Process `fork` remains impossible inside a live JVM. Fixed-plan test files
  retain their portable prefix and report only their remaining assertions as
  platform skips; this does not claim process-fork functionality.

## Related References

- `docs/guides/module-porting.md`
- `.agents/skills/debug-perlonjava/SKILL.md`
- `.agents/skills/port-cpan-module/SKILL.md`
