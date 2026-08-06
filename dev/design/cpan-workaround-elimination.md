# Eliminating CPAN compatibility workarounds

## Purpose

PerlOnJava ships CPAN distroprefs and source patches to keep useful modules
installable while compiler, runtime, module-provider, and CPAN-tooling behavior
is incomplete. This document defines the architecture and delivery plan for
removing those workarounds.

The desired result is not an empty workaround directory. Narrow policies are
appropriate for genuinely unsupported platform capabilities, upstream release
defects, and intentionally partial Java replacements. The goal is to remove
workarounds that compensate for valid Perl behavior or generic installer
behavior that PerlOnJava should implement itself.

This is a handout and implementation contract. Development history belongs in
commit messages, not here.

## Scope

The cleanup covers:

- `src/main/perl/lib/PerlOnJava/CpanDistroprefs/`
- `src/main/perl/lib/PerlOnJava/CpanPatches/`
- compiler and runtime behavior exposed by the affected distributions;
- CPAN dependency resolution, build selection, testing, and installation;
- bundled Perl and Java-backed module discovery;
- bootstrap and retirement of generated preferences and patches.

It does not require implementing POSIX `fork`, Perl threads, arbitrary native
XS loading, or every API of a deliberately partial Java-backed module.

## Principles

1. Valid Perl source must be fixed at the compiler or runtime layer.
2. Generic distribution-install behavior must be fixed in CPAN tooling.
3. A bundled provider must satisfy dependency resolution without being
   shadowed by an upstream distribution.
4. Unsupported capabilities must fail or skip explicitly; they must not be
   reported as passing tests.
5. Upstream source defects should be fixed upstream when practical. A narrow
   versioned patch may remain until a corrected release is available.
6. Removing a workaround requires the unmodified upstream distribution to pass
   the applicable acceptance gate.
7. Every new Perl unit test must first pass with standard Perl and then pass
   with both PerlOnJava backends.
8. All `jperl`, `jcpan`, and `prove` runs must have a hard timeout and write
   complete output to a log file.

## Workaround classes

Every distropref and patch must have one owner class:

| Class | Correct owner | Removal condition |
|---|---|---|
| Valid Perl semantic mismatch | Compiler or runtime | Standard-Perl-validated unit plus unmodified upstream suite pass on both backends |
| Dependency phase or metadata handling | CPAN tooling | Clean isolated install resolves the graph without the pref or patch |
| Bundled or Java-backed provider | Provider manifest and resolver | Resolver accepts the bundled version and prevents accidental shadowing |
| Optional pure-Perl backend selection | Build policy | Generic backend selection chooses the supported implementation |
| Unsupported platform capability | Capability policy | Retain a narrow declarative rule unless the capability is implemented |
| Upstream release defect | Upstream or versioned patch | Remove when the resolver selects a corrected release |
| Partial replacement conformance | Module implementation | Unmodified applicable upstream tests pass against the replacement |

`PERLONJAVA_TEST_IGNORE_FAILURES` is transitional. It is not an acceptable
steady-state outcome because it marks an unknown set of failures successful.

## Target architecture

### Shared semantic layer

Context, lvalue identity, lexical hints, non-local control flow, source
locations, warning state, and string encoding flags should be determined once
and consumed consistently by the JVM and interpreter backends.

Compiler-specific handling is limited to emitting the shared operation. The
runtime operation owns Perl-visible mutation, capture state, diagnostics, and
return values.

### Bundled-provider manifest

The build produces a machine-readable manifest for modules supplied by the
distribution. Each entry contains:

```yaml
module: DBI
version: 1.647
provider: bundled-perl
shadow_policy: forbidden
test_strategy: bundled-provider
```

Supported provider values are:

- `bundled-perl`
- `java-xs`
- `compatibility-shim`
- `unsupported-native`

CPAN consults this manifest before scheduling a distribution. A dependency is
satisfied when a compatible bundled provider exists. An explicit conformance
test request may still fetch upstream source without installing it over the
provider.

### Phase-aware dependency graph

Configure, build, test, and runtime prerequisites remain distinct throughout
CPAN resolution. Test requirements are not promoted to build or runtime
requirements merely because the current queue cannot represent their phase.

The command mode determines the test policy:

- explicit target tests exercise the target distribution;
- dependency installation requires its runtime and build surface;
- strict dependency testing is an explicit mode;
- an unavailable test-only capability is reported as unavailable, not as a
  successful test run.

### Capability policy

Capabilities are named facts such as `fork`, `threads`, `native-xs`,
`local-network`, and `live-network`. Distribution policies refer to facts
rather than executable-name regexes or target-specific environment matching.

Capability policy may choose a pure-Perl backend, exclude an unsupported test
file, or declare a provider unavailable. It must not hide failures unrelated to
the named capability.

### Workaround manifest and retirement

Preferences and patches are indexed by one generated manifest rather than by
parallel handwritten lists in `CPAN::Config`. Each artifact records its source,
installed destination, checksum, owner class, and optional retirement marker.

Bootstrap updates or removes only files carrying the PerlOnJava ownership
signature. User-owned CPAN configuration is never overwritten or deleted.

## Implementation phases

### Phase 1: Shared `use bytes` substitution semantics

Implement destructive and non-destructive regex substitution against the
UTF-8 octets of a UTF-8 scalar while preserving the original lvalue.

Required behavior:

- matching and capture variables observe octets under lexical `use bytes`;
- destructive substitution writes the byte result through the original
  scalar, including tied or aggregate lvalues;
- `/r` leaves the original untouched and returns the byte-oriented result;
- substitution count and scalar/list/void context remain unchanged;
- code replacements receive byte-oriented captures;
- JVM and interpreter behavior is identical.

Exit criteria:

- a standard-Perl-validated non-TODO bytes substitution unit passes;
- the complete unmodified WWW::Form::UrlEncoded suite passes on both backends;
- `WWW-Form-UrlEncoded.yml` and its source patch are removed and retired;
- `make` passes.

### Phase 2: Bundled-provider resolution

Add the provider manifest and consult it during CPAN resolution and install.
Keep lazy Java module initialization isolated from compiler-global literal and
reference state.

Initial providers: DBI, Moose, CryptX/Crypt::PRNG, HTML::Parser,
XML::LibXML, Set::Object, and Package::Stash::XS.

Exit criteria:

- clean isolated dependency installs do not fetch or shadow compatible bundled
  providers;
- explicit provider conformance tests have a documented, non-installing path;
- lifecycle skip prefs for migrated providers are removed;
- `make` and provider smoke tests pass.

### Phase 3: Prerequisite phase preservation

Represent test requirements separately from build requirements in the CPAN
queue and expose strict dependency testing as an explicit command mode.

Exit criteria:

- clean representative DateTime, LWP, CGI, and Moose dependency graphs do not
  install unsupported test-only modules as runtime prerequisites;
- dependency-only skip and ignore-failure prefs made unnecessary by the phase
  model are removed;
- direct `jcpan -t` still exercises the requested distribution.

### Phase 4: Caller, eval, warning, and symbol-table parity

Use one Perl-visible call-frame model for JVM code, interpreted code, eval,
Java-backed calls, warning handlers, and exception handlers. Preserve `$@`,
`%^H`, source spans, glob slots, and signal handlers across nested calls and
cleanup.

Target distributions include aliased, Carp::Assert, Devel::Symdump,
Exception::Class, Hook::LexWrap, Sub::Quote, Test::MockObject, and
Test2::Plugin::NoWarnings.

### Phase 5: Control flow, lvalues, and introspection

Propagate explicit `return` through arbitrary nested map/grep blocks to the
owning Perl subroutine. Make shared lvalue analysis authoritative for both
backends and integrate lazy native `Want` support without eager global-state
changes.

Target distributions include Graph, Class::Method::Modifiers,
Module::Install, and Term::ANSIColor::Markup.

### Phase 6: Regex engine coverage

Adopt a stack-safe Perl-compatible regex backend for conditionals, atomic
groups, recursion, subroutine patterns, and large inputs. Treat executable
`(?{...})` and `(??{...})` callbacks as a separate final step because they
require Perl code execution during matching.

Target distributions include Regexp::Common, String::Random,
Object::InsideOut, Logger::Simple, ExtUtils::ParseXS, Type::Tiny, and
XML::TreePP.

### Phase 7: CPAN metadata and process services

Recover undeclared prerequisites from authoritative MYMETA data and one bounded
retry after a canonical missing-module test failure. Add an argv-safe Java
process service with output capture, deadlines, and process-tree termination.

This phase must improve subprocess use without claiming POSIX `fork` support.

### Phase 8: Deterministic lifetime and remaining parity

Complete weak-reference, readonly-clone, scope-exit, and destruction semantics
needed by Test::Deep and SQL::Translator. Evaluate 64-bit IV compatibility as
a separate runtime migration with pack, unpack, bitwise, and sprintf gates.

## Verification matrix

Each workaround-removal change must record these results in its commit message:

| Gate | Required |
|---|---|
| Reduced test under standard Perl | Yes |
| Reduced test under JVM backend | Yes |
| Reduced test under interpreter backend | Yes |
| Unmodified upstream suite | Yes, unless capability policy explicitly limits it |
| Clean isolated `PERLONJAVA_HOME` install | For CPAN tooling/provider changes |
| Full `make` | Before every pushed commit and PR update |
| Relevant CPAN distropref smoke | Before removing a pref or patch |
| Ubuntu and Windows CI | Before declaring the PR ready |

Workaround deletion and its replacement implementation belong in the same
commit. A removed generated artifact must also have a retirement entry so
upgrades do not leave stale files active in existing homes.

## Pull request completion criteria

The implementation PR is ready for review only when:

1. every completed phase satisfies its exit criteria;
2. the design status and next steps identify the precise resumption point;
3. no existing test was modified to weaken expected behavior;
4. the full local `make` gate passes;
5. the branch is pushed without force to `master`;
6. all required GitHub CI checks pass on Ubuntu and Windows;
7. the PR lists every removed pref and patch and every retained capability
   policy.

## Implementation status

Current phase: Phase 3, prerequisite phase preservation.

### Phase checklist

- [x] Phase 1: Shared `use bytes` substitution semantics (2026-08-06)
- [x] Phase 2: Bundled-provider resolution (2026-08-06)
- [ ] Phase 3: Prerequisite phase preservation
- [ ] Phase 4: Caller, eval, warning, and symbol-table parity
- [ ] Phase 5: Control flow, lvalues, and introspection
- [ ] Phase 6: Regex engine coverage
- [ ] Phase 7: CPAN metadata and process services
- [ ] Phase 8: Deterministic lifetime and remaining parity

### Next steps

1. Preserve runtime, build, test, and configure requirements as separate CPAN
   queue phases.
2. Add an explicit strict dependency-testing command mode.
3. Verify clean DateTime, LWP, CGI, and Moose dependency graphs do not install
   unsupported test-only modules as runtime prerequisites.
4. Retire dependency-only skip and ignore-failure prefs made unnecessary by
   phase-aware resolution.

### Completed phase deliverables

- Phase 1 implements bytes-mode replacement on the original scalar for JVM and
  interpreter execution, including `/r`, aggregate lvalues, tied scalars, and
  byte-oriented captures.
- Runtime and compiler files: `EmitRegex.java`, interpreter replacement opcode
  handling, `RuntimeRegex.java`, `StringOperators.java`, and
  `ScalarOperators.java`.
- Regression: `src/test/resources/unit/bytes_regex_substitution.t`.
- Retired artifacts: `WWW-Form-UrlEncoded.yml` and
  `WWW-Form-UrlEncoded-0.26/PP.pm.patch`.
- Phase 2 adds the schema-validated `PerlOnJava/providers.json` manifest and
  makes CPAN satisfy compatible requirements from provider versions while
  rejecting incompatible or forced shadow installs.
- Initial providers: DBI, Moose, Crypt::PRNG, HTML::Parser/HTML::Entities,
  XML::LibXML, Set::Object, and Package::Stash::XS. Clean-runtime gaps were
  closed with bundled HTML loader files, a Package::Stash::XS pure-Perl facade,
  and the XML::SAX exception surface required by XML::LibXML.
- Provider conformance uses the non-installing
  `jcpan --provider -t Module::Name` path.
- Retired lifecycle prefs: `DBI.yml`, `Moose.yml`, `CryptX.yml`,
  `HTML-Parser.yml`, `XML-LibXML.yml`, `Set-Object.yml`, and
  `Package-Stash-XS.yml`; retired patches: `DBI/DBI.pm.patch` and
  `DBI/PurePerl.pm.patch`.
- Verification: the provider regression passes under standard Perl and both
  PerlOnJava resolver backends; all providers load from a clean JVM runtime;
  a clean `jcpan DBI` refreshes metadata but fetches no DBI distribution and
  installs no user-local files; full `make` passes.

### Open questions

- How should release tooling detect a newer CPAN version of a forbidden-shadow
  provider (for example Set::Object newer than 1.43) and prompt a bundled
  implementation/manifest review without allowing an automatic shadow install?
- Should provider entries remain module-granular, or should a distribution
  entry optionally enumerate bundled pure-Perl siblings such as
  HTML::HeadParser?
- Which dependency-test policy should be the default for `jcpan install` once
  phases are preserved?
- Should executable regex callbacks use the future regex backend directly or
  a compiler-owned callback opcode interface?

## Related documents and skills

- `dev/design/patch-and-cpan-prefs-layout.md`
- `dev/design/cpan-runtime-parity-experiment.md`
- `dev/design/catalyst-support.md`
- `dev/design/regex_alternatives.md`
- `dev/design/shared_ast_transformer.md`
- `.agents/skills/port-cpan-module/SKILL.md`
- `.agents/skills/debug-perlonjava/SKILL.md`
