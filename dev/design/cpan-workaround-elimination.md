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

Current phase: Phase 4, caller, eval, warning, and symbol-table parity.

### Phase checklist

- [x] Phase 1: Shared `use bytes` substitution semantics (2026-08-06)
- [x] Phase 2: Bundled-provider resolution (2026-08-06)
- [x] Phase 3: Prerequisite phase preservation (2026-08-06)
- [ ] Phase 4: Caller, eval, warning, and symbol-table parity
- [ ] Phase 5: Control flow, lvalues, and introspection
- [ ] Phase 6: Regex engine coverage
- [ ] Phase 7: CPAN metadata and process services
- [ ] Phase 8: Deterministic lifetime and remaining parity

### Next steps

1. Fix the interpreter call-frame mapping exposed by
   `src/test/resources/unit/carp_confess_named_frame.t`: any interpreter
   subroutine invoked from an `eval` currently reports the surrounding virtual
   eval frame from `caller(0)` instead of its own frame. Standard Perl and the
   JVM backend report `assert_like_carp_assert(0)` correctly. The unchanged
   Carp::Assert `embedded-Carp-Assert.t` suite reproduces the same single
   failure (12 assertions, 11 passing) under the interpreter.
2. After the interpreter frame is named correctly, rerun the complete
   aliased, Carp::Assert, Devel::Symdump, Exception::Class, Hook::LexWrap,
   Sub::Quote, Test::MockObject, and Test2::Plugin::NoWarnings suites before
   retiring their remaining preferences or patches.
3. Trace `$SIG{__DIE__}`, `$@`, and caller metadata across JVM code,
   interpreter code, and nested `eval`; keep the live LWP `t/local/http.t` and
   CGI HTML::Entities failures as Phase 8/runtime-parity follow-ups.

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
- Phase 3 preserves configure (`q`), build (`b`), test (`t`), runtime (`r`),
  and command-line (`c`) queue types. Runtime requirements inherit the
  non-runtime phase of the dependency surface that needs them.
- Test requirements are resolved at the explicit target's test boundary.
  Dependency distributions skip their own test surfaces by default while
  remaining available through CPAN's tested-build path; the
  `jcpan --strict-dependency-tests` mode opts into recursive dependency tests.
- Regression: `src/test/resources/unit/cpan_prerequisite_phases.t` validates
  metadata separation, queue inheritance, default policy, and strict mode
  under standard Perl and both PerlOnJava backends (19 assertions). It also
  loads an on-disk `META.json` fixture to cover MakeMaker's META 1.4 phase
  collapse.
- MakeMaker-generated `MYMETA.yml` can merge test requirements into its single
  `build_requires` bucket. CPAN now reconciles that configured metadata with
  the distribution's static `META.json`/`META.yml`, retaining configured
  versions while restoring test-only modules to the test phase.
- Retired 46 dependency-only skip and ignore-failure prefs. Bootstrap
  retirement removes stale signed copies while retaining capability and source
  patch policies. A clean bootstrap installs 44 active prefs and none of the
  retired files.
- Cached upstream metadata confirms representative phase distinctions:
  DateTime test-only `Test::Without::Module`, libwww-perl test-only
  `HTTP::Daemon`/`Test::RequiresInternet`, and CGI test-only
  `Test::NoWarnings` are absent from their runtime requirement sets.
- Authorized isolated live graphs completed the Phase 3 acceptance gate.
  Moose was satisfied by the bundled provider without a shadow install. CGI
  ran its requested 64-file/1491-test suite. DateTime and LWP initially exposed
  the MakeMaker phase collapse through dependency-only `Test::Deep` installs;
  after reconciliation, both passed that former blockage and entered their
  requested distributions' own test suites. A production-path check against
  the cached HTTP::CookieJar and File::Copy::Recursive metadata confirms their
  collapsed entries are now `test_requires`, not build or runtime requirements.
- A final clean DateTime run with `PERLONJAVA_HOME` rooted in its isolated
  temporary tree passed all 51 files and 3,589 tests. The other live target
  suites exposed unrelated runtime-parity work: CGI has HTML::Entities regex
  failures, and LWP fails `t/local/http.t` around HTTP::Cookies/server
  behavior. All processes were bounded by external timeouts; these failures do
  not weaken the Phase 3 prerequisite-phase acceptance result.
- Phase 4 investigation: the unmodified aliased `t/sigdie.t` suite passes 9/9
  under standard Perl, JVM, and interpreter backends, so its retired
  `$SIG{__DIE__}` preference is no longer needed. The new CPAN-independent
  `carp_confess_named_frame.t` regression confirms the remaining issue is
  interpreter caller-frame naming rather than signal-handler preservation.
  A reduced `sub a { caller(0) }` reproduction shows the defect requires an
  interpreter subroutine called from `eval`; direct calls and JVM execution
  retain the named frame.

### Open questions

- How should release tooling detect a newer CPAN version of a forbidden-shadow
  provider (for example Set::Object newer than 1.43) and prompt a bundled
  implementation/manifest review without allowing an automatic shadow install?
- Should provider entries remain module-granular, or should a distribution
  entry optionally enumerate bundled pure-Perl siblings such as
  HTML::HeadParser?
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
