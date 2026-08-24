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

Current status: complete. PR #895 merged on 2026-08-10 after the required
Ubuntu and Windows CI jobs passed.

### Phase checklist

- [x] Phase 1: Shared `use bytes` substitution semantics (2026-08-06)
- [x] Phase 2: Bundled-provider resolution (2026-08-06)
- [x] Phase 3: Prerequisite phase preservation (2026-08-06)
- [x] Phase 4: Caller, eval, warning, and symbol-table parity (2026-08-06)
- [x] Phase 5: Control flow, lvalues, and introspection (2026-08-07)
- [x] Phase 6: Regex engine coverage (2026-08-07; executable callbacks deferred
  by design)
- [x] Phase 7: CPAN metadata and process services (2026-08-07)
- [x] Phase 8: Deterministic lifetime and remaining parity (2026-08-09)

### Next steps

1. Keep Object::InsideOut/Logger::Simple, ExtUtils::ParseXS, Regexp::Common's
   executable conditional, and Type::Tiny's
   executable `(?{...})`/`(??{...})` paths under explicit capability policy
   until the compiler/runtime callback interface is designed.
2. Treat that callback interface as a separate follow-up project; it is not a
   blocker for the completed workaround-elimination phases.

### Completed phase deliverables

- Final validation: PR #895 passed the `ubuntu-latest` and `windows-latest`
  Java CI jobs and merged as `cc559b7b1c66c9dc04a909db815fe948a2f05b82`
  on 2026-08-10.

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
  interpreter caller metadata rather than signal-handler preservation. A
  reduced `sub a { caller(0) }` reproduction showed the defect requires an
  interpreter subroutine called from `eval`. Caller selection now retains the
  named frame, and active-code mapping supplies the correct pristine argument
  snapshot to `@DB::args`; the focused regression and Carp::Assert's 12-test
  embedded suite pass on the interpreter.
- Phase 4 target-suite pass/blocker matrix: aliased 9/9, Carp::Assert 12/12,
  Exception::Class 86/86, Hook::LexWrap 57/57, and Sub::Quote 5,427/5,427
  pass. Hook::LexWrap was fixed by adding `sqrt` to the parser's
  `CORE::GLOBAL` override points; `core_global_sqrt_override.t` covers dynamic
  wrapper reassignment on standard Perl, JVM, and interpreter. Recursive
  package-stash entries now alias undefined nested stashes, normalize the
  `main::Pkg::` spelling, resolve aliased package prefixes transitively, and
  create post-alias scalars in the canonical stash while preserving scalars
  pinned before the alias. `stash_recursive_alias.t` passes on standard Perl,
  JVM, and interpreter, and Devel::Symdump's unchanged `t/recur.t` passes on
  both PerlOnJava backends. Symbol-table inventory now keeps `use vars`
  typeglob declarations from eagerly materializing aggregate slots, preserves
  strict-vars authorization separately, exposes lowercase standard handles and
  main's argument-array slot, distinguishes the special `$:` glob from a
  package stash, and creates `%!`, `%+`, and `%-` lazily. Devel::Symdump's
  unchanged full suite passes all 9 files and 29 assertions.
  `Devel::Peek::CvGV` now reconstructs canonical
  typeglobs from `Sub::Util::subname`; `devel_peek_cvgv.t` passes on standard
  Perl, JVM, and interpreter, and Test::MockObject advances to 231/232. Its
  sole remaining weak-reference lifetime failure is deferred to Phase 8.
  Test2::Plugin::NoWarnings' final blocker is resolved without patching
  IPC::Run3. Constant named handles embedded in two-argument duplication modes
  are now qualified in their compile-time package, so IPC::Run3 can save and
  restore its localized `STDOUT_SAVE` and `STDERR_SAVE` globs. The remaining
  `t/compile.t` mismatch was a diagnostic-order issue: strict-vars checking now
  preserves the undeclared scalar-dereference error even when a later syntax
  error aborts parsing. `zz_open_dup_package_handle.t` and
  `zz_strict_deref_compile_error.t` pass under standard Perl, JVM, and
  interpreter backends; the unchanged Test2::Plugin::NoWarnings suite passes
  all 5 files and 8 assertions.
- Phase 4 completion also narrows the virtual-eval frame exception to actual
  interpreter frames. JVM `$SIG{__DIE__}` handlers again see `(eval)` while the
  interpreter retains the named Carp caller frame. The existing
  `io_compress_regressions.t` and `carp_confess_named_frame.t` regressions pass,
  and the final full `make` gate passes 245 tests with 2 skipped.
- Phase 5's patched Graph baseline initially passed 86/88 files, with all
  eight failures in `t/59_dfs.t` and `t/62_bcc.t`: diagnostics named the caller `(eval)` where
  Graph expected `Graph::topological_sort`, `Graph::is_connected`, or
  `Graph::biconnectivity`. The defect was eval/interpreter frame alignment, not
  the anticipated nested map/grep return propagation.
- Formatted interpreter frames now carry explicit backend metadata, virtual
  eval frames are placed outside their contiguous interpreted calls, and a
  named active Perl subroutine takes precedence when it occupies a synthetic
  eval slot. Genuine outer eval callers remain `(eval)`.
- Regression: `src/test/resources/unit/zz_eval_defined_named_caller.t` passes
  its four assertions under standard Perl, JVM, and interpreter backends. The
  Graph 0.9735 suite with its existing source bundle passes all 88 files and
  9,256 assertions.
  The full `make` gate passes 245 tests with 2 skipped.
- The remaining Phase 5 target baselines also pass with their current patches:
  Class::Method::Modifiers 2.15 passes 29 files/131 assertions without a
  source patch; Module::Install 1.21 passes 35 files/546 assertions with its
  explicit-`authors` patch; Term::ANSIColor::Markup 0.06 passes 4 files/11
  assertions with its portable-accessor patch.
- A pristine Graph extraction exposed the still-active patch surface. Cached
  JVM method dispatch invoked resolved code directly and let a non-local
  RETURN from nested map callbacks escape past the owning method. Both cache
  hit and miss paths now consume that return at the normal method boundary
  while preserving raw LVALUE context checks. Regression:
  `src/test/resources/unit/zz_nested_map_return.t` passes 11 assertions under
  standard Perl, JVM, and interpreter backends; unpatched `t/16_edges.t`
  passes 48/48.
- The explicit-loop `get_ids_by_paths` rewrites were removed from the
  `AdjacencyMap.pm` and `AdjacencyMap-Light.pm` patches. Applying the reduced
  patch bundle to pristine Graph 0.9735 sources passes all 88 files and 9,260
  assertions. A fully unpatched run now reaches 82/88 files and identifies the
  remaining independent gaps: deterministic attribute dumping,
  `defined(&sub)`, SCC return shape, and Storable code references. The normal
  `jcpan -t Graph` path refreshes the installed reduced patch copies and passes
  all 88 files and 9,234 assertions.
- Assignment RHS parsing under `defined` now restores ampersand-call semantics:
  `defined(my $x = &sub)` calls the subroutine and tests its assigned result,
  while a direct `defined(&sub)` remains a CODE-slot probe. Regression:
  `src/test/resources/unit/zz_defined_ampersand_assignment.t` passes 4/4 under
  standard Perl, JVM, and interpreter backends.
- The final `AdjacencyMap-Light.pm.patch` hunk is retired. Pristine Graph tests
  `t/24_mixvertexed.t`, `t/48_get_vertex_count.t`, and `t/65_ref.t` pass
  56/56, 12/12, and 1,795/1,795 respectively. Bootstrap retirement removes
  the stale installed patch on upgrade. Pristine Graph with only the two
  remaining patch files passes all 88 files and 9,263 assertions.
- The final `jcpan -t Graph` smoke applies exactly two patches and passes all
  88 files and 9,268 assertions. A fresh `CPAN::Config` bootstrap load removes
  `Graph/AdjacencyMap-Light.pm.patch` from the installed patch cache.
- The same assignment-RHS parser correction makes Graph's upstream
  `strongly_connected_component_by_index` expression work without rewriting
  its return shape. Regression: `src/test/resources/unit/zz_ampersand_postfix_deref.t`
  passes 4/4 under standard Perl, JVM, and interpreter backends. The SCC hunk
  is removed from `Graph.pm.patch`; pristine Graph with the current two patch
  files passes all 88 files and 9,245 assertions. A source-first
  `jcpan -t Graph` smoke applies those two reduced patches to a fresh build,
  retains the upstream SCC expression, and passes all 88 files and 9,246
  assertions. The final full `make` gate passes 245 tests with 2 skipped.
- Data::Dumper's PerlOnJava scalar-state bridge now controls the complete safe
  decimal decision, so untouched short numeric strings remain quoted while
  integer and numified-string scalars remain numeric. Regression:
  `src/test/resources/unit/zz_data_dumper_short_numeric_flags.t` passes 5/5
  under standard Perl, JVM, and interpreter backends; the existing
  `data_dumper_numified.t` regression remains 7/7. Unpatched Graph
  `t/08_stringify.t` improves from eight failures to the four independent
  Set::Object lifetime failures, with every attribute-dumping mismatch gone.
  The recursive deterministic-dumper rewrite is removed from
  `AdjacencyMap.pm.patch`. The reduced two-patch CPAN build retains Graph's
  upstream `_dumper` and passes all 88 files and 9,249 assertions on a
  same-tree full rerun. A transient `t/89_connected_subgraphs.t` count mismatch
  (111 versus 112) passed 17/17 immediately afterward and remained green in
  that full rerun. The final full `make` gate passes.
- Storable's SX_CODE reader now honors a true `$Storable::Eval`, delegates to
  either its CODE callback or lexical Perl eval, preserves seen-table tags, and
  collapses the surrounding SX_REF around an already reference-level CODE
  value. False Eval retains PerlOnJava's established refusal diagnostic.
  Regression: `src/test/resources/unit/zz_storable_code_eval.t` passes 2/2
  under standard Perl, JVM, and interpreter backends. Unpatched Graph
  `t/67_copy.t` advances from a fatal `Can't retrieve code references` error
  after 47 assertions to all 56 assertions passing. The Storable guard is
  removed from `Graph.pm.patch`. Its final caller-attribution hunk is also
  obsolete: unpatched `t/06_new.t`, `t/59_dfs.t`, `t/63_scc.t`, and
  `t/83_bitmatrix.t` pass 169/169, 266/266, 56/56, and 29/29 respectively.
  `Graph.pm.patch` is deleted and registered for installed-cache retirement;
  Graph now has one remaining source patch. The source-first `jcpan -t Graph`
  smoke applies exactly that one patch, uses upstream Storable selection, and
  passes all 88 files and 9,243 assertions; bootstrap removes the installed
  `Graph.pm.patch`. The full `make` gate passes.
- JVM nested-block cleanup now drains deferred mortal decrements only above the
  current function mark. Previously, a full drain inside the second constructor
  call could destroy the first constructor result while it was still waiting in
  the caller's list-assignment RHS. Regression:
  `src/test/resources/unit/zz_list_assignment_destroy_lifetime.t` passes 3/3
  under standard Perl, JVM, and interpreter backends.
- Graph's final adjacency rewrite is retired. The upstream Set::Object-based
  rendering in `t/08_stringify.t` now keeps both returned sets alive, so
  `AdjacencyMap.pm.patch` and `Graph.yml` are deleted and registered for
  installed-cache retirement. A source-first, zero-patch `jcpan -t Graph`
  build passes all 88 files and 9,241 assertions, retains upstream
  `AdjacencyMap.pm`, and removes all stale installed Graph preference and patch
  files. Graph 0.9735 is now fully unpatched, and the final full `make` gate
  passes.
- Undefined named CODE references assigned to another typeglob now retain a
  live forward-CV alias. Independently cached source and target placeholders
  form an interpreter-only alias group and receive the later definition in
  place; adopting an interpreted definition delegates to its executable
  bytecode without changing ordinary JVM glob-replacement behavior. Regression:
  `src/test/resources/unit/zz_forward_code_glob_alias.t` passes 4/4 under
  standard Perl, JVM, and interpreter backends, while the existing saved-CV,
  overload, and Math::BigInt regressions remain green.
- Module::Install's explicit `authors` wrapper is retired. Unmodified upstream
  Module::Install 1.21 passes all 35 files and 546 assertions, and a
  source-first `jcpan -t Module::Install` run applies no patch, retains upstream
  `*authors = \&author`, removes the stale installed preference and patch, and
  passes the same 35-file/546-assertion suite. `Module-Install.yml` and
  `Module-Install-1.21/ExplicitAuthorsMethod.patch` are deleted and registered
  for installed-cache retirement. The final full `make` gate passes.
- The bundled Want compatibility layer now loads a Java call-context bridge
  lazily through XSLoader and answers `LVALUE`/`ASSIGN` from the existing
  runtime context stack. Class::Accessor::Lvalue 0.11's unchanged suite passes
  2 files/24 assertions on the JVM backend; the interpreter exercises the
  lvalue behavior successfully, with its two remaining failures limited to
  pre-existing anonymous-accessor `caller` attribution in diagnostics.
- Term::ANSIColor::Markup 0.06 no longer substitutes ordinary accessors for its
  upstream lvalue accessors. Its pristine suite passes 4 files/11 assertions
  on the JVM backend and all 11 assertions in direct interpreter runs using
  the current Test::Builder. A source-first `jcpan -t
  Term::ANSIColor::Markup` run installs the real pure-Perl
  Class::Accessor::Lvalue dependency, applies no patch, retains upstream
  lvalue assignments, and passes 4 files/11 assertions. The distropref and
  `PortableAccessors.patch` are deleted and registered for installed-cache
  retirement; a source bootstrap removes both stale artifacts.
- Phase 5's final Class::Method::Modifiers pass makes the shared lvalue/runtime
  policy authoritative at the remaining interpreter boundaries. Interpreted
  `:lvalue` code now preserves returned scalar aliases during RETURN assembly
  and call-result coercion, matching the JVM path. Eval-block caller frames
  keep the interpreted callee name while sourcing package, file, and line from
  the adjacent eval call site.
- Interpreter assignment through localized `our` arrays and hashes reloads
  the dynamic global container instead of mutating a stale symbol-table
  register. Hash-element assignment updates the existing scalar slot in place,
  so references such as `\$cache->{wrapped}` observe later CODE replacement.
  Sub::Util initialization now installs its documented
  `Sub::Name::_is_renamed` introspection alias.
- Regressions `zz_caller_exported_alias.t`, `zz_lvalue_wrapper_return.t`,
  `zz_local_our_aggregate_restore.t`,
  `zz_hash_element_assignment_identity.t`, and
  `zz_b_anonymous_cv_introspection.t` pass under standard Perl, JVM, and
  interpreter backends. The existing four-level eval caller regression remains
  green, and the full `make` gate passes.
- Unmodified Class::Method::Modifiers 2.15 passes 29 files/131 assertions on
  the JVM backend and in a source-first, zero-pref `jcpan -t` run. Isolated
  interpreter execution passes all non-TODO assertions in all 29 files; 27
  files exit cleanly, while `t/041-modify-parent.t` and `t/140-lvalue.t` reach
  only their upstream TODO failures before bundled Test::More's empty
  diagnostic stack panics. The ordinary interpreter Test::Harness path also
  reuses one runtime across files and exposes cross-file package/test-state
  contamination, so the isolated per-file matrix is the authoritative
  semantic result. Bootstrap retirement removes the stale
  `Class-Method-Modifiers.yml` ignore-failure preference.
- Phase 5 is complete: Graph 0.9735, Class::Method::Modifiers 2.15,
  Module::Install 1.21, and Term::ANSIColor::Markup 0.06 all pass their
  source-first JVM acceptance paths without Phase 5 preferences or source
  patches. The known interpreter Test::More TODO-diagnostic and in-process
  harness-isolation limitations remain visible; no failure is converted to a
  pass.
- Phase 6 inventory found three remaining active regex-related preferences:
  ExtUtils::ParseXS and Logger::Simple retain recursive-pattern capability
  policy, and XML::TreePP retained parser stack-safety plus byte/UTF-8 source
  adaptations. Type::Tiny's executable-callback test skip has been retired.
  String::Random and Regexp::Common have no source preference or patch; their
  names remain only in bootstrap retirement so upgrades remove stale signed
  copies.
- String::Random 0.32 is a completed zero-workaround baseline. Its unchanged
  Module::Build suite passes 9 files/202 assertions on JVM and interpreter
  backends. Two consecutive source-first `jcpan -t String::Random` runs pass
  the same suite, apply no preference, and leave `String-Random.yml` absent.
- Regexp::Common 2024080801 remains a later regex-engine target. Its pristine
  JVM suite runs 73 files/140,752 assertions but fails 11 programs and 2,633
  assertions across nested comments, IPv6, USA SSNs, balanced patterns,
  case-insensitive matching, palindromes, named subpatterns, and Netherlands
  and Spain postal codes. No failure is hidden by a preference.
- Type::Tiny 2.010001's complete pristine callback surface passes 3 files/15
  assertions under standard Perl and both PerlOnJava backends:
  `strmatch-allow-callbacks.t`, `strmatch-avoid-callbacks.t`, and
  `StrMatch-more.t`. `SkipRegexCallbackTests.patch`, its distropref, and its
  provider registration are retired. Bootstrap cleanup removes only stale
  PerlOnJava-owned copies of the preference and preserves user-owned policy.
- XML::TreePP 0.43 no longer needs its incremental parser rewrite. Pristine
  upstream `t/03_parsefile.t` and the 150 KB `t/51_RT_42441.t` fixture pass on
  JVM and interpreter backends; the complete pristine JVM suite reaches all 55
  files/1,073 assertions with failures confined to the independent
  byte/UTF-8 output tests. The patch now retains only the encoding adaptation.
  The reduced patch passes its five affected upstream files/136 assertions
  under standard Perl and passes every assertion in isolated JVM and
  interpreter runs. A source-first `jcpan -t XML::TreePP` in a fresh isolated
  `PERLONJAVA_HOME` bootstraps only the encoding patch, leaves the upstream
  global parser regex intact, and passes all 55 files/1,073 assertions. The
  final full `make` gate passes.
- Logger::Simple 2.0 isolates the Object::InsideOut dependency to a
  self-referential `(??{$BALANCED_PARENS})` pattern. Standard Perl passes its
  unchanged 4-file/8-assertion suite. JVM and interpreter backends pass the
  same suite only under the visible `JPERL_UNIMPLEMENTED=warn` fallback; an
  unmodified no-policy run fails 3 of 4 programs while compiling that pattern.
  This is executable dynamic-pattern evaluation, so it remains with the other
  deferred callback capabilities rather than being treated as declarative
  recursion.
- Logger-Simple.yml now sets warning mode through the cross-platform
  distropref `test.env` mapping instead of a Unix shell assignment. It was the
  only bundled preference missing the `PerlOnJava` ownership signature, which
  prevented bootstrap upgrades from replacing the old commandline. A narrowly
  matched migration upgrades that exact legacy file; regression
  `cpan_distroprefs_signature_migration.t` checks every active preference's
  signature and the migration under standard Perl, JVM, and interpreter. An
  isolated existing-home `jcpan -t Logger::Simple` run migrates the legacy
  file, applies warning mode through the normal CPAN test command, and passes
  all 4 files/8 assertions.
- ExtUtils::ParseXS 3.64 also uses executable `(??{$bal})` patterns throughout
  OutputMap and Node parsing, and three upstream run tests compile native XS
  fixtures. Its test-phase skip therefore remains an explicit combination of
  callback and native-compilation capability policy, not a declarative regex
  workaround ready for retirement.
- Legacy multidimensional hash access now has interpreter parity with the JVM
  backend: `$hash{a, b}` and `$hashref->{a, b}` join key expressions with the
  current `$;` value. Regression `zz_multidimensional_hash_key.t` passes 4/4
  under standard Perl, JVM, and interpreter. Regexp::Common now loads on the
  interpreter; its previously blocked Netherlands and Spain postal-code files
  completed targeted runs of 5,641 and 2,834 assertions respectively. This
  proves the multidimensional-key blocker is gone; later randomized complete
  matrices still expose a shared Spain postal-pattern mismatch.
- Regexp::Common's `RE_balanced` reduction is declarative recursion, not an
  executable callback. The generated pattern contains `(?-1)`; the current
  preprocessor incorrectly routes the leading minus through modifier parsing
  and emits `(?)`. Java's regex engine has no recursive-subpattern operation.
  A finite-depth expansion was rejected because it would silently make valid
  matching depend on nesting depth, contrary to Phase 6's stack-safe backend
  contract. This is the first required matcher-abstraction/backend increment.
- Scalar context is now applied consistently to named and dereferenced arrays
  in interpreter bytecode. Previously `@$ref` and `@{...}` emitted a
  `RuntimeArray` even in scalar arithmetic; postfix compound assignments such
  as `$tests += @$_ for values %groups` then failed with a `ClassCastException`
  or corrupted TAP plans. Regression
  `zz_postfix_foreach_compound_assignment.t` passes 3/3 under standard Perl,
  JVM, and interpreter backends.
- The scalar-array correction unblocks 14 previously crashing or incomplete
  Regexp::Common interpreter files (67,899/67,899 assertions), including the
  URI matrix, USA SSNs, HTML comments, decimal numbers, US ZIP codes, and the
  ZIP driver. The complete isolated per-file interpreter matrix now reaches
  all 73 files and 147,004 assertions: 67 files pass, five have assertion
  failures, and the executable conditional test errors explicitly. The shared
  assertion categories are nested comments, IPv6, case-insensitive matching,
  palindromes, and Spain postal codes. The equivalent JVM matrix reaches
  149,226 assertions and additionally exposes balanced and named/numbered
  subpattern failures; empty interpreter output for those three files is not
  counted as a pass gate.
- Phase 6 now has a backend-neutral `RegexMatcher` boundary for Perl-visible
  match state and a Joni 2.2.7 backend for declarative recursive subpatterns.
  Perl relative and absolute numbered calls and named calls are translated to
  Joni's stack-machine recursion syntax; captures are converted from UTF-8
  byte offsets to Perl/Java character offsets. Java `Pattern` remains the
  default backend for all non-recursive patterns, and executable regex
  callbacks remain outside this increment.
- Regression `zz_recursive_regex.t` passes 12/12 under standard Perl, JVM, and
  interpreter execution, covering nested and 500-level matches, rejection,
  captures after a wide character, global matching, global substitution, and
  stringified `qr//` flags. The full local `make` gate passes.
- Regexp::Common's unchanged `test_balanced.t`, `test_sub.t`, and
  `test_sub_named.t` now pass 127/127, 20/20, and 5/5 respectively on the JVM
  backend. Its complete unchanged JVM matrix still reaches 73 files and
  149,226 assertions, but failures initially fell from eight files to five:
  the three recursion-dependent failures were eliminated. Direct interpreter
  execution of those three upstream files still
  exits without TAP; the equivalent recursive semantics pass through the new
  cross-backend unit regression and are not misreported as upstream passes.
- Perl branch-reset groups now retain native logical capture numbering through
  a Java-to-Perl group map instead of exposing one group range per expanded
  alternative. Regression `zz_branch_reset_capture.t` passes 14/14 under
  standard Perl, JVM, and interpreter, including unequal alternatives,
  optional captures, list context, and distinct trailing-capture sizing for
  `@-` and `@+`. Regexp::Common's unchanged IPv6 suite consequently passes
  159/159 on both PerlOnJava backends. The complete JVM matrix remains 73 files
  and 149,226 assertions, with failures reduced again from five files to four:
  executable conditional comments, executable palindrome recursion,
  declarative nested comments, and Spain postal codes.
- Regexp::Common nested comments are also executable dynamic recursion: the
  module constructs `(??{$Regexp::Common::comment[...]})`. Spain's apparent
  postal-pattern failures were instead invalid randomized "pass" inputs caused
  by bytecode `redo` rechecking a `while` condition. Perl `redo` restarts the
  body without checking that condition. Regression `zz_redo_unless.t` passes
  5/5 under standard Perl, JVM, and interpreter; the unchanged Spain suite now
  passes 2,833/2,833 on both PerlOnJava backends.
- Phase 6 declarative coverage is complete. The final unchanged JVM matrix runs
  all 73 Regexp::Common files and 149,226 assertions; only three executable-code
  regex suites remain: conditional comments, palindrome recursion, and nested
  comment recursion. These use `(?{...})`, `(??{...})`, or both and remain
  visible under the callback-interface deferral required by the Phase 6 design.
- Phase 7's first metadata-only retirement removes
  `Test-Deep-JSON.yml`. Test::Deep::JSON 0.05's generated metadata declares
  `Exporter::Lite`, `JSON::MaybeXS`, and `Test::Deep`, so the Phase 3
  META/MYMETA reconciliation resolves the graph without an explicit
  distropref. Bootstrap migration removes stale PerlOnJava-signed copies while
  preserving a user-owned file with the same name; the standard-Perl migration
  regression passes 5/5. A fresh isolated `PERLONJAVA_HOME` source install
  applies no preference, installs all three dependencies, and passes the
  unchanged target suite (2 files/7 assertions). The full `make` gate passes.
- Phase 7 now has one bounded missing-module test retry. PerlOnJava captures
  and replays the first no-fork test command's complete output, accepts only
  canonical `Can't locate path/to/Module.pm in @INC` diagnostics, deduplicates
  their normalized module names, queues them as test prerequisites, and
  revisits that distribution once. A second failure and every noncanonical
  assertion failure remain ordinary failures. Regression
  `cpan_prerequisite_phases.t` passes 23/23 under standard Perl, JVM, and
  interpreter execution.
- `XML-Filter-GenericChunk.yml` is retired. Upstream META supplies
  `XML::LibXML` and `XML::SAX::Base`; an isolated no-policy first run exposed
  the two genuinely undeclared test-time modules, `XML::SAX::DocumentLocator`
  and `XML::NamespaceSupport`. The bounded retry installed XML::SAX and its
  namespace dependency, revisited the unchanged target exactly once, and
  passed all 3 files/20 assertions. Bootstrap migration removes stale signed
  copies of the former four-module preference.
- `HTTP-Response-Encoding.yml` and its Makefile metadata patch are retired.
  Upstream omits the test-only `LWP::UserAgent`; the bounded retry discovers
  that module, stages libwww-perl's source graph, and then passes the unchanged
  target suite (5 files/17 assertions). That clean graph also exposed missing
  core `AnyDBM_File`; PerlOnJava now bundles the standard selector at version
  1.01 and delegates to its existing SDBM implementation, preventing CPAN from
  attempting to install a complete Perl distribution for WWW::RobotRules.
- Phase 7 adds `PerlOnJava::Process`, an argv-safe process API backed by Java
  `ProcessBuilder`. It mirrors Perl `%ENV`, supports an explicit working
  directory, merges and captures stdout/stderr, enforces bounded deadlines,
  and terminates descendant processes before forcibly terminating a surviving
  root. A fork/exec implementation preserves the API when the regression runs
  under standard Perl. `zz_perlonjava_process.t` passes 8/8 under standard
  Perl, the JVM backend, and the interpreter backend.
- The bundled `CPAN::FindDependencies::MakeMaker` adapter now invokes
  `Makefile.PL` through that process API, replacing the distribution-specific
  Unix `system` patch. `CPAN-FindDependencies.yml` and
  `CPAN-FindDependencies-3.13/MakeMaker.pm.patch` are retired with stale-file
  migration coverage. The unchanged upstream focused `t/makefilepl.t` passes
  6/6, including output suppression and the spinning-Makefile.PL deadline;
  the full `make` gate passes.
- Phase 8 begins by preserving reachable strong aggregate owners before the
  weak-reference cleanup heuristic corrects suspected selective-refcount
  drift. Owner reachability follows scalar, aggregate, and backend-neutral
  closed-over-variable captures, while stale slots in restored localized
  caches no longer suppress cleanup. A block-lexical call log can therefore
  retain a logged object until its owning entry is deleted, without pinning
  the object afterward.
  `zz_block_lexical_call_log_lifetime.t` passes 2/2 under standard Perl, JVM,
  and interpreter execution. Test::MockObject's unchanged `t/bugs.t` advances
  from 17/18 to 18/18 on the JVM backend, and a source-first CPAN run passes
  the complete 12-file/232-assertion suite. Its interpreter method-table issue
  remains separate and is not claimed as an upstream interpreter pass.
- Test::Deep's right-hand expected structure previously retained two stale
  selective owners after its localized comparison caches were restored, so
  `t/memory.t` cleared the weak observer only after an explicit reachability
  sweep. The stricter reachable-owner check clears that observer at the Perl
  statement boundary. The unchanged focused test passes 2/2 on JVM and
  interpreter, and a source-first CPAN run passes all 42 files/1,268
  assertions.
- A `CORE::GLOBAL::caller` override with prototype `;$` now leaves binary
  concatenation outside an omitted optional argument. This fixes the standard
  Perl parse of `caller.'::'`, used by YAML::Mo after Sub::Uplevel installs its
  caller proxy. Regression `core_global_caller_concat.t` passes 2/2 under
  standard Perl, JVM, and interpreter execution; `Sub::Uplevel` followed by
  YAML now loads on the JVM backend. The full `make` gate passes. Interpreter
  YAML still has a separate pre-existing compile-time import issue at its
  first `has ... =>` declaration and is not folded into this parser fix.
- Eval-generated foreach and C-style loops now emit Perl's defined empty-string
  result in scalar context instead of leaving the bytecode result register
  absent (which enclosing blocks converted to undef). Parse::RecDescent uses
  that distinction to accept a successfully matched production whose final
  action statement is a loop. Regression `eval_foreach_return_value.t` passes
  2/2 under standard Perl, JVM, and interpreter execution, and the full
  `make` gate passes. SQL::Translator's unchanged SQLite-to-YAML round trip
  consequently advances from 1/2 to 2/2; its MySQL parser advances beyond the
  former failure after assertion 204 and completes all 347 assertions under a
  1,200-second bound.
- SQL::Translator's first complete unchanged source matrix runs all 75 files
  without a harness timeout: 60 pass, four fail assertions, seven error, and
  four are incomplete, for 1,382/1,427 passing assertions (96.8%). This is the
  Phase 8 baseline rather than a policy-retirement pass: it identifies 15
  non-passing files while confirming the core MySQL, SQLite, Oracle, Access,
  YAML, JSON, Storable, PostgreSQL-producer, and SQL-Server paths.
- `XML::LibXML::Node::getAttributes` is now implemented with its DOM behavior:
  it returns ordinary attribute nodes but excludes namespace declaration
  nodes, while the existing `attributes` API continues to return both. The new
  `xml_libxml_get_attributes.t` regression passes 2/2 on JVM and interpreter;
  the host Perl preflight skips because XML::LibXML is not installed there.
  Five previously non-passing unchanged SQL::Translator files now pass:
  `t/16xml-parser.t` (240/240), `t/43xml-to-db2.t` (1/1),
  `t/46xml-to-pg.t` (1/1), `t/48xml-to-sqlite.t` (2/2), and
  `t/64xml-to-mysql.t` (2/2).
- Quote-delimited token patterns whose repeated alternation cannot consume the
  closing delimiter now make that repetition possessive in the Java form.
  Backtracking iterations cannot change a valid result for this shape, and
  removing them prevents Java `Pattern` from consuming one native stack frame
  per character. Regression `regex_long_quoted_token.t` passes 5/5 under
  standard Perl, JVM, and interpreter, covering a 20,000-character capture,
  an escaped delimiter, and an unterminated failure. With the default stack
  and a 2 GB heap bound for local machine isolation, SQL::Translator's
  unchanged `t/08postgres-to-mysql.t` passes 1/1 and
  `t/14postgres-parser.t` passes 211/211.
- The earlier SQL producer/list-context cluster was downstream of the corrected
  XML attribute semantics, not a shared list-return defect. Unchanged
  `t/44-xml-to-db2-array.t` passes 1/1, both `t/51-xml-to-oracle*.t` files pass
  2/2, and `t/74-filename-arrayref.t` passes 2/2. Correcting the temporary
  XML::Writer source path from the distribution root to `blib/lib` also makes
  `t/60roundtrip.t` pass its XML, YAML, and SQLite sections through assertion
  25; completion is still resource-blocked during its MySQL section.
- JDBC-backed DBI handles now recognize SQL containing only line/block comments
  and semicolons before preparing it, returning DBI's true zero (`0E0`) instead
  of passing a finalized no-op statement to SQLite. Regression
  `dbi_do_comment.t` passes 3/3 under standard Perl, JVM, and interpreter.
  SQL::Translator's unchanged `sqlite-rename-field.t` advances from 15/16 to
  16/16 because its generated schema-conversion comment is now a successful
  no-op.
- The corrected authoritative SQL::Translator matrix uses absolute test paths
  (preserving the distribution-root cwd), XML::Writer's generated `blib/lib`,
  one worker, a 768 MB inherited heap bound, and a 1,800-second per-file hard
  timeout. All 75 unchanged files and 1,947/1,947 assertions pass, with zero
  failures, errors, timeouts, or incomplete files and eight upstream TODOs.
  The expensive gates complete within the bound: `t/02mysql-parser.t` passes
  347/347 in 1,755 seconds, `t/60roundtrip.t` passes 100/100 in 1,670 seconds,
  and the open3-based diff scripts pass 16/16 and 21/21.
- `SQL-Translator.yml` is retired now that its documented removal condition is
  met. It is removed from bundled preference installation, and bootstrap
  migration removes an old PerlOnJava-owned installed copy while preserving
  user-owned policy files. `cpan_distroprefs_signature_migration.t` passes 9/9
  under standard Perl, JVM, and interpreter execution.
- CPAN metadata and distribution tests now have independent JVM heap controls.
  `jcpan` preserves the caller-selected heap for its long-lived metadata graph,
  while defaulting MakeMaker test children to 768 MB and preserving non-heap
  options such as `-Xss`; `PERLONJAVA_TEST_JPERL_OPTS` remains an explicit
  override. Both the full and simplified MakeMaker generators propagate the
  child setting. `zzzz_makemaker_test_heap_override.t` passes 4/4 under
  standard Perl, JVM, and interpreter execution.
- A fresh isolated source-first `jcpan -t SQL::Translator` with a 2 GB CPAN
  parent and 768 MB test children installs all generated dependencies without
  manual source paths and without installing or applying
  `SQL-Translator.yml`. All 75 upstream files and 1,947/1,947 assertions pass
  in 3,706 seconds (`Result: PASS`, exit 0). This independently confirms the
  complete matrix through the normal CPAN dependency and MakeMaker paths.
- A live lexical weakened after its referent's registered owners have become
  unreachable now requests one root-based sweep when selective refcount still
  reports a positive residual count. This preserves the throttled fast path for
  ordinary weak refs while restoring immediate Perl semantics for DBIC's
  schema/parser lifetime pattern. Against the isolated normally installed
  SQL::Translator, DBIx::Class's unchanged `t/99dbic_sqlt_parser.t` passes
  179/179 (including `Schema not leaked`) and `t/86sqlt.t` passes 144/144; both
  automatic leak registries finish empty.
- CGI's Phase 8 follow-up is complete (2026-08-08). Runtime parity fixes make
  sparse list holes iterate as `undef`, distinguish leading-dot numeric
  literals from concatenation during prototype parsing, preserve magic-while
  assignment through single parentheses, return `EBADF` rather than throwing
  for a missing filehandle-duplication source, expose the bundled File::Temp
  wrapper through legacy FileHandle identity, and store `%ENV` values as
  byte-oriented scalars. The bundled HTML::Entities implementation now treats
  an explicit undefined unsafe set as the default and uses decimal numeric
  fallback entities. Eight standard-Perl-validated regressions pass under JVM
  and interpreter execution. CGI 4.72's complete unchanged source matrix passes
  all 64 files and 1,578/1,578 assertions with zero failures, errors, timeouts,
  or incomplete files (10 skips and four upstream TODOs). The full `make` gate
  passes; no CGI distribution preference or patch is required.
- LWP's Phase 8 follow-up is complete (2026-08-08). The bundled
  `HTTP::Cookies` implementation now adds request cookie headers with domain,
  path, secure, port, expiry, version, and pre-existing-header handling, and
  converts `Max-Age` to an absolute expiry when storing a cookie. The focused
  `http_cookies_request_header.t` regression passes 6/6 under standard Perl,
  JVM, and interpreter execution. System Perl and PerlOnJava both pass the
  unchanged `t/local/http.t` 136/136 localhost-server assertions. With the
  source graph's already-built `Encode::Locale` prerequisite restored to its
  isolated test path, libwww-perl 6.83's complete unchanged matrix passes all
  23 files and 330 assertions (`Result: PASS`; one unstable upstream NNTP file
  skips). The full `make` gate passes; no libwww-perl preference or patch is
  required.
- The Phase 8 64-bit-IV differential baseline is established (2026-08-08).
  Standard Perl reports 8-byte IV/UV values and supports 64-bit numeric NOT,
  shifts, `q`/`Q`, and long `sprintf`; PerlOnJava still deliberately advertises
  4-byte IV/UV values and keeps those downstream gates disabled. The first
  dependency-ordered migration step now stores signed Java `long` values as
  exact integer scalars, carries them through JVM and interpreter literals,
  constant folding, arithmetic, comparison, increment, and decrement, and
  retains Java `Integer` storage for ordinary values so established fast paths
  and unit-shard state remain stable. Regression
  `zzzz_iv64_scalar_exactness.t` passes 10/10 under standard Perl, JVM, and
  interpreter execution, covering 2^53, IV_MAX/IV_MIN, arithmetic,
  comparison, and increment. The differential baseline now matches standard
  Perl for all signed integer and arithmetic rows; bitwise width, `q`/`Q`,
  long `sprintf`, unsigned IV_MAX+1 values, and `Config.pm` remain explicit
  next steps. The full `make` gate passes.
- The signed/within-IV shift step is complete (2026-08-09). Shared runtime
  operators now use a 64-bit word for left/right shifts, reverse direction for
  negative shift counts at the 64-bit boundary, propagate the sign bit under
  `use integer`, and apply signed 64-bit integer NOT. Regression
  `zzzz_iv64_shift_semantics.t` passes 10/10 under standard Perl, JVM, and
  interpreter execution, including the signed high bit. The full `make` gate
  passes. Unchanged upstream `op/bop.t` runs 522 assertions and currently
  passes 494 on JVM and 493 on interpreter; its width-derived assertions still
  consult the intentionally unchanged `Config.pm` value of four bytes, while
  the cusp/negative-UV group remains blocked on representing UV_MAX exactly.
  Ordinary numeric NOT and unsigned results above IV_MAX therefore remain the
  next atomic step rather than being approximated with a signed Java `long`.
- The unsigned 64-bit scalar and bitwise step is complete (2026-08-09).
  Integer scalars above IV_MAX use an exact `BigInteger` representation while
  ordinary signed values retain the established `Integer`/`Long` fast paths.
  Arithmetic and numeric comparison preserve those unsigned values, and
  ordinary numeric NOT, AND, OR, XOR, and shifts now operate over a 64-bit UV
  word on both backends. Regression `zzzz_iv64_unsigned_bitwise.t` passes
  12/12 under standard Perl, JVM, and interpreter execution; the interpreter
  also exposes its pre-existing local numeric-warning suppression gap inside
  Test::Builder without changing the successful TAP result. The differential
  baseline now matches standard Perl for numeric NOT and unsigned shifts.
  Unchanged `op/bop.t` improves to 496/522 on JVM and 495/522 on interpreter;
  its remaining width-derived assertions continue to select 32-bit
  expectations from the intentionally unchanged `Config.pm`. The full `make`
  gate passes. The next migration gate is `q`/`Q` pack/unpack and long
  `sprintf`, followed by advertising the completed 64-bit runtime in Config.
- The `q`/`Q` pack/unpack gate is complete (2026-08-09). Decimal literals
  through UV_MAX now compile to exact integer scalars on JVM and interpreter
  backends, including when passed through ordinary Perl argument lists;
  larger literals retain NV promotion. Native, little-endian, and big-endian
  quad packing writes the low 64-bit word, while unpack reconstructs signed IV
  or exact unsigned UV values. Regression `zzzz_iv64_pack_quad.t` passes 16/16
  under standard Perl, JVM, and interpreter execution, covering IV_MIN,
  IV_MAX, UV_MAX, the unsigned high bit, repeats, endian modifiers, native
  width, and subroutine-argument preservation. Unchanged `op/pack.t` completes
  all 14,726 assertions and passes 14,670 on JVM and 14,668 on interpreter;
  all newly exercised `q`/`Q` round-trip assertions pass. The full `make` gate
  passes. Long `sprintf` modifiers are the next migration gate before the
  final 64-bit Config switch.
- The long-`sprintf` gate is complete (2026-08-09). Integer conversions now
  accept Perl's `ll`, `q`, and `L` quad modifiers, preserving signed IV and
  unsigned UV reinterpretation across decimal, octal, hexadecimal, and binary
  output. Decimal formatting also handles IV_MIN without overflowing while
  deriving its magnitude. Regression `zzzz_iv64_sprintf_long.t` passes 16/16
  under standard Perl, JVM, and interpreter execution, including UV_MAX,
  signed aliases, width, precision, and alternate forms. Unchanged
  `op/sprintf2.t` completes all 1,655 assertions and passes 1,638 on JVM and
  1,589 on interpreter; all 24 newly unlocked quad-format and warning
  assertions pass. The remaining failures are pre-existing overload, UTF-8,
  overflow-diagnostic, and high-precision `%g` gaps. The full `make` gate
  passes. The runtime gates are now complete; the next step is the final
  64-bit Config declaration switch and consumer verification.
- The 64-bit Config declaration and first consumer gate are complete
  (2026-08-09). `Config.pm` now truthfully reports 8-byte IV, UV, size, NV,
  quad, and long-long types, the native eight-byte byte order, 53 NV-preserved
  UV bits, and the absence of complete NV-to-UV preservation. Native `j`/`J`
  pack templates now use eight bytes. Decimal and non-decimal literals retain
  exact IV/UV values through UV_MAX and promote larger values to NV; arithmetic
  and bitwise shifts preserve the same boundary on both backends. Config
  regression `zzzz_iv64_config.t` passes 22/22 and unsigned regression
  `zzzz_iv64_unsigned_bitwise.t` passes 17/17 under standard Perl, JVM, and
  interpreter execution. The unchanged core matrices complete all assertions:
  `op/bop.t` passes 504/522 on JVM and 503/522 on interpreter,
  `op/pack.t` passes 14,670/14,726 and 14,668/14,726, and `op/sprintf2.t`
  passes 1,641/1,702 and 1,592/1,702 respectively. The Config-unlocked
  sprintf path now formats exact large integral doubles and accepts Perl's
  combined sign flags instead of aborting; remaining failures are known
  formatting and diagnostic parity gaps.
- Scalar::Type 1.0.1 is now a bundled pure-Perl/Java provider (2026-08-09),
  replacing its XS-only scalar introspection with the `_scalar_type` runtime
  primitive. Its unchanged API contract is covered by 18 passing module-test
  assertions and is documented in the bundled-module and feature matrices.
  With that dependency and exact over-UV literal promotion in place,
  Data::CompactReadonly's unchanged source-first suite passes all 11 files and
  342 assertions without a distribution preference or patch. Number::Phone
  also resolves and builds all 305 source files without a workaround. JFR
  attributed 3,263/3,288 samples from its generated 9.2 MB
  `Number::Phone::StubCountry::CN` load to repeated full-token scans in
  `ErrorMessageUtil.getLineNumberAccurate`. A lazily built physical-line index
  preserves random-access diagnostics while removing that quadratic parser
  path; the direct CN constructor improves from exceeding 60 seconds to 2.03
  seconds. Unchanged `example-phone-numbers.t` improves from exhausting a
  15-minute deadline after 1,804 assertions to passing 9,976/9,976 in 272
  seconds, and unchanged `libphonenumber.t` passes 917/917 in 13 seconds. The
  complete unchanged Number::Phone source suite passes all 40 files and
  13,609/13,609 assertions in 370 seconds (`Result: PASS`; four expected
  optional/slow tests skip). The full `make` gate passes; no Number::Phone
  distribution preference or patch is required.

## Progress Tracking

### Current Status: final rebase, live CPAN output repair, and local validation complete; CI pending

### Completed Phases

- [x] Rebase the workaround-elimination series onto current `origin/master`
  (2026-08-09).
  - Rebased all 53 original branch commits plus the post-rebase reconciliation
    onto `e22f32805` without dropping a commit. The final master advance was
    documentation-only and did not change the verified runtime or module set.
  - Reconciled the branch with master's regex byte-view, CPAN phase/prefs,
    lvalue/caller, integer-width, HTML entity, and Storable UV changes.
  - Added scalar-glob dereference compatibility to the bundled File::Temp
    wrapper; the standard-Perl-validated regression passes on both backends and
    CGI 4.72 again passes all 64 files and 1,603 assertions.
- [x] Verify master and branch CPAN fixes after the rebase (2026-08-09).
  - All 21 CPAN targets fixed on master pass their complete `jcpan -t` gates.
  - Twenty-five branch targets pass their complete gates, including Test::Deep
    (42 files, 1,268 assertions), Data::CompactReadonly (11 files, 342
    assertions), and Number::Phone (40 files, 13,609 assertions). Number::Phone
    requires the supported 4 GB child override after master's default test heap
    was capped at 768 MB; its unchanged suite is otherwise fully green.
  - The post-rebase SQL::Translator source-first gate exercised all 75 files
    and 1,947 assertions. Its only failure was the 16-assertion open3 diff
    script: master's immediate weak-wrapper sweep cleared an unrelated Moo
    weak `index -> table` owner link while a nested call frame was still being
    assembled. Wrappers without `DESTROY` now request only the targeted next-
    statement-boundary sweep, while the existing immediate global sweep is
    retained for `DESTROY` classes. The failed file then passes 16/16, and the
    master-sensitive Hash::AutoHash (32 files, 2,785 assertions),
    Data::FetchPath (4 files, 19 assertions), and Password::OWASP (4 files, 40
    assertions) gates remain green.
  - The final full `make` gate passes. The post-File::Temp bundled-module
    matrix also passes all 378 files.
  - Master advanced during the long verification window. After rebasing again,
    its newly landed module gates also pass: Crypt::Twofish2 (1 file, 83
    assertions), Text::Markdown (17 files, 64 assertions),
    Text::Markdown::Slidy (2 files, 5 assertions), Char::Latin7 (210 files,
    5,711 assertions), and Text::Fold (6 files, 38 assertions). The full
    `make`, SQL diff 16/16, and Hash::AutoHash 2,785/2,785 gates remain green
    on the final base.
- [x] Repair the reported post-rebase core regressions (2026-08-10).
  - Reconciled parser, numeric/string operators, pack/unpack, regex magic,
    control-flow, taint, symbol-table, and lvalue behavior across the 27
    reported core files. Against the saved `e22f32805` master baseline, the
    definitive matrix has no lower pass counts: 17 files are equal and 10 are
    improved.
  - Preserved ordinary `%!`, `%+`, and `%-` typeglob aliases without leaking
    magic hash slots through repeatedly localized introspection globs.
    `re/reg_namedcapture.t` passes 2/2, `op/runlevel.t` remains at master parity
    (14/24), and unmodified Devel::Symdump passes 9 files/29 assertions; its
    focused test also passes under standard Perl.
  - The full `make` gate and all 378 bundled-module files pass. A fresh sweep
    of all 26 master-side CPAN targets and all 25 branch targets passes after
    the Devel::Symdump follow-up, including Number::Phone's complete
    13,609-assertion suite with the documented 4 GB child override.
- [x] Rebase onto final `origin/master` and revalidate all affected modules
  (2026-08-10).
  - Rebased all 55 branch commits onto `2095f7b55` without dropping a commit,
    reconciling master's taint-mode and ICU regex work with the branch's
    byte-regex, exact 64-bit integer, vec, and lifecycle changes.
  - Against a freshly built `2095f7b55` worktree, the reported 27-file core
    matrix has no lower pass counts: 17 files are equal and 10 are improved.
  - The post-rebase CPAN sweep passes all 54 targets: the prior 51-target
    master/branch matrix plus newly landed Getopt::Param,
    DateTime::TimeZone::Tzfile, and Unicode::BiDiRule.
  - The sweep exposed a regression already present on the new master:
    compile-time `our` declarations did not materialize their package glob
    slots, so Specio rejected DateTime::Duration during its circular load.
    PerlOnJava now matches standard Perl by creating scalar, array, and hash
    slots while compiling `our`; the standard-Perl-validated regression passes
    6/6 on JVM and interpreter backends, and DateTime passes all 51 files and
    3,589 assertions.
  - The final full `make` gate passes. Devel::Symdump passes 9 files/29
    assertions, aliased passes 6 files/40 assertions, and the bundled-module
    matrix passes all 378 files.
- [x] Restore live `jcpan` test output while retaining missing-prerequisite
  recovery (2026-08-10).
  - The canonical missing-module retry introduced retry-aware test-output
    capture, but replayed the captured TAP only after the complete distribution
    suite exited. Long suites such as DBIx::Class therefore appeared silent
    even while their worker JVMs were making progress.
  - `PerlOnJava::Process` now has an opt-in live tee mode. Each merged child
    output chunk is flushed through the current Perl STDOUT immediately and is
    also retained byte-for-byte for the existing canonical `Can't locate`
    parser. CPAN still promotes discovered test prerequisites and retries no
    more than once per command; it no longer replays output at the end.
  - The process runner also resolves an omitted `cwd` from Perl's current
    logical directory. Java `ProcessBuilder` otherwise inherited the launch
    directory of the outer JVM, causing CPAN's `make test` to run the
    PerlOnJava repository Makefile instead of the distribution Makefile after
    CPAN had changed directory internally.
  - A timing regression has the child verify that its first marker reached the
    tee destination before the child exits. A CPAN-level regression verifies
    successful status, retained analysis output, and that live tee mode is
    requested. Both tests pass under standard Perl, JVM, and interpreter
    execution.
  - Clean Ubuntu CI also exposed that the focused HTTP::Cookies request-header
    regression had been loading HTTP::Request from the developer CPAN home.
    The Java unit harness now includes its existing `unit/lib` fixture tree,
    with a minimal test-only request/URI object; production module lookup and
    the unchanged regression remain untouched.

### Next Steps

1. Let CI exercise platform-specific gates and resolve any failures without
   restoring distribution-specific CPAN workarounds.

### Verification Notes

- CPAN::FindDependencies 3.13's documented focused gate previously passed 6/6.
  The current CPAN index supplies 3.14, whose expanded suite adds a hanging
  `cpandeps-diff` scenario and whose focused test no longer completes the same
  assertion path in the local source graph. The underlying bundled
  `PerlOnJava::Process` regressions remain green; do not report the 3.14
  plan-only direct exit as a successful 6/6 run.

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
- `dev/design/regex-implementation.md`
- `dev/design/shared_ast_transformer.md`
- `.agents/skills/port-cpan-module/SKILL.md`
- `.agents/skills/debug-perlonjava/SKILL.md`
