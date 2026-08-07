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

Current phase: Phase 6, regex engine coverage.

### Phase checklist

- [x] Phase 1: Shared `use bytes` substitution semantics (2026-08-06)
- [x] Phase 2: Bundled-provider resolution (2026-08-06)
- [x] Phase 3: Prerequisite phase preservation (2026-08-06)
- [x] Phase 4: Caller, eval, warning, and symbol-table parity (2026-08-06)
- [x] Phase 5: Control flow, lvalues, and introspection (2026-08-07)
- [ ] Phase 6: Regex engine coverage
- [ ] Phase 7: CPAN metadata and process services
- [ ] Phase 8: Deterministic lifetime and remaining parity

### Next steps

1. Reduce Regexp::Common's five remaining JVM failures: executable conditional
   comments, declarative nested comments, IPv6, palindrome, and Spain postal
   codes. Keep each defect separate from the completed recursion increment.
2. Re-run the complete isolated Regexp::Common matrix on the interpreter and
   JVM backends after each regex increment; direct execution of three upstream
   recursion files remains subject to the documented interpreter no-TAP
   condition, while equivalent unit coverage must pass on both backends.
3. Keep Object::InsideOut/Logger::Simple, ExtUtils::ParseXS, Regexp::Common's
   executable conditional, and Type::Tiny's
   executable `(?{...})`/`(??{...})` paths under explicit capability policy
   until the compiler/runtime callback interface is designed.
4. Keep Test::MockObject's weak-reference lifetime assertion, the live LWP
   `t/local/http.t` failure, and CGI HTML::Entities failures as documented
   Phase 8/runtime-parity follow-ups.

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
- Phase 6 inventory found four active regex-related preferences:
  ExtUtils::ParseXS and Logger::Simple retain recursive-pattern capability
  policy, Type::Tiny retains two executable-callback test skips, and
  XML::TreePP retained parser stack-safety plus byte/UTF-8 source adaptations.
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
- Type::Tiny 2.010001's pristine callback tests pass 2 files/6 assertions under
  standard Perl. Both PerlOnJava backends reject the non-constant `(?{...})`
  group before assertions, confirming that `SkipRegexCallbackTests.patch`
  represents the explicitly deferred executable-callback capability rather
  than a declarative regex or stack-safety defect.
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
  149,226 assertions, but failures fall from eight files to five: the three
  recursion-dependent failures are eliminated, leaving only executable
  conditional comments, nested comments, IPv6, palindrome, and Spain postal
  codes. Direct interpreter execution of those three upstream files still
  exits without TAP; the equivalent recursive semantics pass through the new
  cross-backend unit regression and are not misreported as upstream passes.

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
