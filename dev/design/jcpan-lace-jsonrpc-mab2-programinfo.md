# CPAN compiler and tooling compatibility batch

## Goal

Fix reusable compiler, runtime, and CPAN-tooling defects exposed by
`Template::Lace`, `Sledge::Plugin::JSONRPC`, `Catmandu::Exporter::MAB2`, and
their dependency graphs. Avoid distribution preferences where a general fix
is possible. A target that also fails with the same installed distribution on
system Perl is outside this batch.

## Implementation

- Compiler and parser fixes preserve package barewords on the right side of
  `isa`, extended named characters inside regexes, exception objects in
  `try`/`catch`, and `return` control flow through a catch block.
- JVM temporary ownership now keeps array/hash references returned from
  conditional method expressions alive. `Storable::dclone` preserves weak
  references without leaving cloned containers owned by temporary roots.
- `POSIX` exports its standard math functions by default, matching system
  Perl and allowing `UUID::Tiny` to use `floor`.
- The CPAN version probe only arms an alarm for filesystem modules; bundled
  modules loaded from the application JAR no longer trigger an unnecessary
  probe timeout.
- A bundled `YAML::Syck` compatibility layer delegates YAML work to the
  existing `YAML::PP`/SnakeYAML implementation and JSON work to `JSON::PP`.
- `XML::LibXML::Reader` uses the existing JDK DOM implementation for file,
  string, and filehandle construction, element traversal, and node copying.
  The shared XML input adapter accepts UTF-8, UTF-16, and UTF-32 BOM byte
  strings as well as an already-decoded Unicode BOM.
- `Data::Util` replaces the native scalar-inspection entry points with Java
  runtime-type checks. In particular, inspecting a missing aliased array slot
  does not vivify it, matching the XS implementation used by system Perl.
  The distribution's upstream `Data::Util::PurePerl` supplies the rest of the
  public API.

No distribution preferences were added. Existing Java facilities are reused:
the JDK XML stack and the already bundled SnakeYAML implementation provide the
required functionality, following the same approach used by existing
BouncyCastle-backed module ports.

## Verification

- New behavioral tests are validated with system Perl before running on both
  PerlOnJava backends.
- Direct Catmandu regressions cover its regex, exception handling, XML/SRU,
  and `Data::Util` binder paths.
- The requested module suites and the complete PerlOnJava build are rerun
  after the final implementation changes.
- `Log::ProgramInfo` is excluded because its installed suite has the same
  uname/log-key failures under system Perl.

## Progress Tracking

### Current Status: Completed (2026-08-11)

### Completed Phases

- [x] Phase 1: Reproduce and classify failures (2026-08-11)
  - Compared requested failures and focused dependency tests with system Perl.
  - Classified `Log::ProgramInfo` as a matching system-Perl failure.
- [x] Phase 2: Compiler and runtime repairs (2026-08-11)
  - Fixed parser, regex, exception, ownership, and cloning behavior.
  - Files: compiler/parser/runtime Java sources and focused unit tests.
- [x] Phase 3: Reusable module and tooling support (2026-08-11)
  - Added `Data::Util`, `YAML::Syck`, XML reader support, POSIX exports, and
    the safe CPAN version-probe behavior.
  - Files: `src/main/perl/lib`, Java module bridges, and module tests.
- [x] Phase 4: Validation (2026-08-11)
  - `Template::Lace`: 2 files, 142 tests passed.
  - `Sledge::Plugin::JSONRPC`: 4 files passed (two optional POD suites skipped).
  - `Catmandu::Exporter::MAB2`: 10 files, 92 tests passed.
  - Full build: 360 unit scripts passed with two expected skips.
  - Focused bundled `Data::Util` module test passed.
  - The initial complete 384-file bundled-module run had three pre-existing
    `Math::BigInt` subclass failures; the exact test fails identically in an
    isolated `master` worktree.
  - `Log::ProgramInfo`: excluded after its sole test file failed identically
    under system Perl on the uname-derived nested log key.
- [x] Phase 5: Math::BigInt bundled-test follow-up (2026-08-11)
  - Made overloaded-mutator copy-on-write conditional on aggregate sharing,
    matching Perl's optimization that skips the copy constructor for an
    unshared object.
  - Preserved the existing copy-constructor behavior for aliased objects and
    conservatively retained it for reference kinds without reliable ownership
    counts.
  - `sub_mbf.t`, `sub_mbi.t`, `sub_mbr.t`, and `sub_mif.t` all pass.
  - The complete bundled-module rerun passes all 384 files with no skips or
    failures.
- [x] Phase 6: Regex regression follow-up (2026-08-12)
  - Rebased onto the latest `master` and reproduced all five reported regex
    count regressions after a clean full build.
  - Fixed named Unicode escapes inside character classes so preprocessing
    emits one Java `\x{...}` escape instead of a doubled escape whose syntax
    characters became unintended class members.
  - Added system-Perl-validated coverage for named punctuation, whitespace,
    and joiner characters in character classes.

### Next Steps

1. No remaining work for this compatibility batch.

### Open Questions

- None.

## Related documentation and skills

- [Module porting guide](../../docs/guides/module-porting.md)
- [Patch and CPAN preferences layout](patch-and-cpan-prefs-layout.md)
- Skills: `debug-perlonjava`, `port-cpan-module`
