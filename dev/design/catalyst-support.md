# Catalyst Support Handoff

## Objective

Run an unmodified Catalyst application on PerlOnJava through PSGI and
`Plack::Handler::Netty`.

Fix compatibility at the lowest reusable layer: CPAN tooling, compiler,
runtime, core modules, or general-purpose CPAN dependencies. Do not patch
Catalyst to hide PerlOnJava defects.

The compatibility target is currently:

| Distribution | Version |
|---|---:|
| Catalyst-Runtime | 5.90132 |
| MooseX-MethodAttributes | 0.32 |
| Plack | 1.0054 |
| HTTP-Body | 1.23 |
| Moose | 2.4000 |

Update this table deliberately when CPAN resolves a newer release. Do not let
an implicit version change invalidate an established baseline.

## Definition of Done

Catalyst runtime support is complete when all of the following hold:

1. A clean, isolated `jcpan install Catalyst::Runtime` succeeds without
   `-f`, skipped runtime prerequisites, or Catalyst-specific source patches.
2. `./jperl -MCatalyst -e 'print $Catalyst::VERSION'` succeeds.
3. An unmodified minimal Catalyst application starts as a PSGI application
   under `Plack::Handler::Netty`.
4. End-to-end requests verify:
   - `:Path`, `:Local`, `:Args`, `:CaptureArgs`, `:Chained`, and `:Private`
     action discovery and dispatch;
   - query and URL-encoded parameters;
   - multipart upload handling;
   - response status, headers, cookies, redirects, and UTF-8 response bodies;
   - exception-to-500 behavior and request logging.
5. The full PerlOnJava unit suite passes with `make`.
6. Every new Perl regression test was first validated with standard Perl.
7. Compiler/runtime changes shared by both backends are tested with the JVM
   backend and `--interpreter`.
8. The final PR documents unsupported deployment modes and optional Catalyst
   components.

Passing every test in every transitive distribution is desirable but is not a
release gate when a failure is confined to a test-only or unsupported feature.
Runtime prerequisites must install and function without force.

## Scope

### Required

- `Catalyst::Runtime` and its runtime dependency graph.
- Catalyst method/action attributes and Moose metaclass integration.
- PSGI operation through `Plack::Handler::Netty`.
- Single-process, non-forking operation.
- Request parsing through `HTTP::Body`.
- A reproducible clean-install test environment.

### Deferred

- `Catalyst::Devel`, application generators, and development-server reloaders.
- Prefork servers, daemonization, and worker management.
- Plugins outside the minimal acceptance application, including authentication,
  sessions, DBIx::Class models, and template views.
- Complete compatibility for test-only modules such as `Test::Trap`.
- Performance tuning beyond preventing obvious hangs or pathological fallback.

Deferred work must not be installed accidentally as part of the runtime gate.
If CPAN requests it, first determine whether prerequisite phase/type handling is
incorrect.

## Engineering Rules

1. Keep upstream Catalyst and its dependencies unchanged during diagnosis.
2. Reduce failures before changing implementation code.
3. Validate new Perl tests with system Perl before running them with `jperl`.
4. Prefer a general compiler/runtime/tooling fix over a bundled module change.
5. A bundled module fix is appropriate when PerlOnJava's implementation of
   that module violates its public Perl API.
6. Use Java replacements only for unavoidable XS/native functionality with no
   viable pure-Perl path.
7. Never use `git stash`, alter upstream tests, or run `jperl`, `jcpan`, or
   `prove` without `timeout`.
8. Capture complete command output in `/tmp`; record durable conclusions here
   or in the PR because `/tmp` logs are not handoff artifacts.
9. Keep commits narrow. Commit history and the PR are the progress ledger;
   this document describes current state, decisions, and future work.

## Reproducible Environment

### Current contamination warning

The workstation's `~/.perlonjava` is not a clean baseline. Dependency discovery
force-installed unchanged copies of `Test::Trap` and `MooseX::Getopt`, and many
other prerequisites were installed incrementally. Do not use the current
`~/.perlonjava` to claim that a clean Catalyst installation succeeds.

Do not delete, clean, restore, or replace `~/.perlonjava` in place. It may
contain unrelated user state.

### Isolated-home tooling

`PERLONJAVA_HOME` is implemented on the Catalyst support branch with these
semantics:

- default remains `~/.perlonjava`;
- library, CPAN metadata, sources, build directories, preferences, patches,
  scripts, and manpages all derive from the override;
- `jperl` automatically includes `$PERLONJAVA_HOME/lib` in `@INC`;
- `jcpan` installs only beneath the override;
- the override works on Unix and Windows launchers;
- tests can create a temporary isolated home without mutating user state.

Acceptance test:

```bash
isolated_root=$(mktemp -d /tmp/perlonjava-catalyst.XXXXXX)
PERLONJAVA_HOME="$isolated_root" timeout 1200 ./jcpan install Text::Glob \
  > /tmp/catalyst-isolated-cpan.log 2>&1
PERLONJAVA_HOME="$isolated_root" timeout 60 ./jperl -MText::Glob \
  -e 'print "$Text::Glob::VERSION\n$INC{q(Text/Glob.pm)}\n"' \
  >> /tmp/catalyst-isolated-cpan.log 2>&1
```

`Try::Tiny` is not a valid isolation probe because version 0.32 is bundled in
the JAR; CPAN reports it up to date without installing into the selected home.
`Text::Glob` is deliberately used because it is a small, pure-Perl,
non-bundled distribution whose `%INC` origin proves the install location.

`PerlOnJavaHomeIntegrationTest` runs the native launcher selected for the host
OS, loads `Config`, `CPAN::Config`, and `CPAN::HandleConfig`, and verifies that
runtime, core-probe, preferences, patches, and install paths remain under a
temporary override while the default user home stays untouched. The same test
therefore exercises `jperl` on Unix and `jperl.bat` on Windows CI.

## Dependency Status

| Component | Class | Current result | Gate | Next action |
|---|---|---|---|---|
| HTTP-Body 1.23 | runtime | 13/13 files, 250/250 assertions pass; installs normally | cleared | retain regression coverage |
| Moose 2.4000 | runtime | bundled; broad upstream/DBIx::Class coverage already exists | monitor | investigate only Catalyst-relevant failures |
| MooseX-MethodAttributes 0.32 | runtime | 22/22 files, 144/144 tests pass unchanged | cleared | retain package-generation regression coverage |
| Catalyst-Runtime 5.90132 | runtime | builds 56 files, but cannot install because required dependencies fail; aggregate suite reached the one-hour guard | blocking | clear direct runtime prerequisites before rerunning the suite |
| Plack 1.0054 | runtime | builds 71 files; install is blocked by six required distributions and its suite consequently cannot load request modules | blocking | clear `Stream::Buffered` and URL encoding first, then rerun Plack |
| Socket `NI_NAMEREQD` | core API | constant/export added; system Perl, JVM, interpreter, and full `make` pass; `Catalyst::Request` now advances to `Stream::Buffered` | cleared | retain regression coverage |
| Stream-Buffered 0.03 | Catalyst and Plack runtime | `t/print.t` and `t/subclass.t` lose printed values (4/18 assertions fail) | blocking | reduce tied/filehandle print behavior |
| WWW-Form-UrlEncoded 0.26 | Plack runtime | Unicode value is emitted literally instead of UTF-8 percent-encoded (2/199 assertions fail) | blocking | reduce byte/UTF-8 URI escaping |
| HTTP-Entity-Parser 0.25 | Plack runtime | cannot load because `Stream::Buffered` and `WWW::Form::UrlEncoded` did not install | downstream blocking | rerun after its two prerequisites clear |
| Filesys-Notify-Simple 0.14 | Plack runtime, reloader use | move/recreate tests emit duplicate plans and no assertions | classify | determine whether the non-forking runtime path works; reloader support is deferred |
| Test-TCP 2.22 | Plack runtime metadata | process/fork-oriented suite fails broadly and distribution does not install | classify | separate runtime helpers usable without `fork` from unsupported tests |
| Class-C3-Adopt-NEXT 0.14 | Catalyst runtime | 24/26 assertions pass; warning text/count differences prevent installation | blocking | reduce warning-category and formatting differences |
| Encode-Locale 1.05 | HTTP-Message runtime | tied `%ENV` byte-key/value mutation fails 3/28 assertions and prevents installation | blocking | reduce byte-string hash key/value behavior |
| POSIX-strftime-Compiler 0.46 | Plack logging runtime | timezone expectation and missing `POSIX::tzset` prevent installation | blocking | implement `tzset` and normalize timezone behavior |
| AnyDBM_File | optional/transitive | missing bundled core module makes CPAN suggest installing Perl | tooling defect | add/import core module or correct capability metadata |
| MooseX-Getopt 0.78 | Catalyst runtime | functional tests mostly pass, but missing failed `Test::Trap` prevents a clean install | blocking/tooling | preserve runtime installability without claiming fork/exit test support |
| CGI-Struct 1.21 | Catalyst runtime | tests shell out through `env perl5` and fail with permission denied | blocking/tooling | route subprocess Perl selection through the PerlOnJava executable |
| Text-SimpleTable 2.07 | Catalyst runtime | 11/12 assertions pass; one backend-selection assertion prevents installation | blocking | reduce optional visual-width selection |
| Test-Trap 0.3.5 | test/development | label/exit/fork tests fail; blocks MooseX-Getopt's test prerequisite | deferred capability, install blocker | fix prerequisite-phase handling or narrowly classify unsupported tests |

When a new dependency appears, add it here only if it is blocking, forced,
incorrectly classified, or exposes a reusable PerlOnJava defect.

## Current Handoff State

Milestones 0 and 1 completed on 2026-08-04. Milestone 2 is active. A clean
isolated install under `/tmp/perlonjava-catalyst-runtime.T2Kb69` enumerated the
full runtime graph, built Catalyst-Runtime 5.90132, and reached its aggregate
suite without force before the one-hour guard expired. The run proved that
Plack's six failed prerequisites and the additional Catalyst prerequisites in
the dependency table are real clean-install blockers. The direct Catalyst
compile blocker `Socket::NI_NAMEREQD` is fixed: its standard-Perl-validated
test passes on both PerlOnJava backends, `make` passes, and loading
`Catalyst::Request` now proceeds to the next missing runtime dependency,
`Stream::Buffered`. Next reduce `Stream::Buffered`, then the Unicode escaping
failure in `WWW::Form::UrlEncoded`, before rerunning Plack and Catalyst.

## Immediate Next Steps

Execute these in order; do not skip ahead by force-installing a failed
distribution:

1. Reduce `Stream-Buffered-0.03` failures in `t/print.t` and `t/subclass.t`.
   Validate the reduction with system Perl, add shared-runtime regression
   coverage, and verify both PerlOnJava backends.
2. Reduce the Unicode percent-encoding failure in
   `WWW-Form-UrlEncoded-0.26` (`foo=%E5&bar=☺` versus
   `foo=%E5&bar=%E2%98%BA`) and rerun its complete upstream suite unchanged.
3. In a new isolated `PERLONJAVA_HOME`, install those two distributions, then
   rerun `HTTP-Entity-Parser-0.25` and the complete `Plack-1.0054` install.
4. Classify the remaining Plack/Catalyst blockers in the dependency table.
   For fork, process, watcher, or reloader tests, prove that the required
   single-process runtime path works before adding any narrow test policy.
5. Rerun a fresh, unforced `jcpan install Catalyst::Runtime`, followed by the
   `-MCatalyst` load gate. Record exact versions and results in this document.
6. Run `make`, push the unified PR, and wait for Ubuntu and Windows CI to pass.
   Leave the PR unmerged so the user can run the isolated install and approve
   it explicitly.

Stream::Buffered 0.03 is now cleared: its unchanged upstream tests pass on
both backends after restoring `-s`/`-z` behavior for unlinked temporary files.
The full `make` gate passes. The next active blocker is Unicode escaping in
`WWW::Form::UrlEncoded`; Catalyst runtime completion remains follow-up work.

When a milestone is completed, update this paragraph to name the next active
milestone and record its acceptance result, without adding a work diary.

## Milestone Plan

### Milestone 0: Isolated CPAN state (completed 2026-08-04)

Deliverables:

- Implement and document `PERLONJAVA_HOME` or an equivalently named override.
- Add Unix and Windows launcher coverage.
- Add a test proving installation and loading happen entirely within an
  isolated temporary root.

Exit criteria:

- A known-small CPAN distribution installs and loads from an isolated root.
- The default `~/.perlonjava` remains untouched by the test.

Completion:

- Runtime `@INC`, `Config`, CPAN state and `MyConfig` discovery, MakeMaker
  install paths, core probes, scripts, and manpages derive from
  `PERLONJAVA_HOME`; the unset default remains `~/.perlonjava`.
- `PerlOnJavaHomeIntegrationTest` selects `jperl` or `jperl.bat` for the host
  OS and proves the temporary override does not write to the default home.
- Files: `GlobalContext.java`, `Config.pm`, `CPAN/Config.pm`,
  `CPAN/HandleConfig.pm`, both MakeMaker implementations, launcher integration
  tests, and the CPAN usage guide.
- Acceptance: `Text-Glob-0.11`, 2/2 files and 74/74 tests, installed and loaded
  from a fresh `/tmp/perlonjava-catalyst.*` root without force.
- Next step: Milestone 1's MooseX::MethodAttributes Catalyst reduction. No
  isolated-home blocker remains.

### Milestone 1: Catalyst method attributes (completed 2026-08-04)

Deliverables:

- Reduce the two MooseX-MethodAttributes Catalyst failures.
- Add standard-Perl-validated local regression tests.
- Fix inheritance, method-modifier, metaclass, MRO, cache invalidation, or code
  attribute behavior at the responsible reusable layer.

Exit criteria:

- Upstream `MooseX-MethodAttributes` `t/catalyst.t` and
  `t/catalyst_role.t` pass unchanged.
- The complete upstream MooseX-MethodAttributes suite has no regressions.
- Relevant local tests pass on both PerlOnJava backends.

Completion:

- Reduction showed the earliest failure was a local attributed method with a
  `before` modifier; inherited `after` lookup failed later for the same reason.
- PerlOnJava did not increment `mro::get_pkg_gen` for named sub definitions or
  redefinitions. Class::MOP therefore reused an empty local method map, and
  `namespace::autoclean` deleted real methods as if they were imports.
- `SubroutineParser` now increments the defining package's generation whenever
  it installs or replaces a named sub.
- `mro_pkg_gen_sub_definition.t` was validated unchanged with system Perl and
  passes with both PerlOnJava backends.
- Acceptance: `t/catalyst.t` 13/13, `t/catalyst_role.t` 21/21, full upstream
  suite 22/22 files and 144/144 tests, and the full `make` gate all pass.
- Next step: Milestone 2's clean isolated Catalyst-Runtime installation.

### Milestone 2: Clean Catalyst runtime installation

Deliverables:

- Install Catalyst-Runtime 5.90132 into a fresh isolated home.
- Classify every failed prerequisite as runtime, configure/build, test-only,
  optional, or unsupported.
- Correct CPAN prerequisite handling when optional/test modules block runtime
  installation.
- Fix remaining runtime dependency failures without force installs.

Exit criteria:

```bash
PERLONJAVA_HOME="$isolated_root" timeout 1200 ./jcpan install Catalyst::Runtime
PERLONJAVA_HOME="$isolated_root" timeout 60 ./jperl \
  -MCatalyst -e 'print $Catalyst::VERSION, "\n"'
```

Both commands exit zero, and the install log contains no forced distribution.

#### Milestone 2 progress update (2026-08-04)

- [x] Cleared `Stream::Buffered` 0.03 without changing its upstream source.
  `FileTestOperator` now uses the open channel for `-s` and `-z` when
  `IO::File->new_tmpfile` has an unlinked pathname; `IOHandle` and
  `CustomFileChannel` provide the reusable size operation.
- [x] Added `filetest_anonymous_tmpfile.t`, validated with system Perl, and
  passed it with both JVM and interpreter backends.
- [x] Passed unchanged `Stream::Buffered` `t/print.t` and `t/subclass.t` on
  both backends (18/18 assertions).
- [x] Full `make` passes after stale test workers were removed (14 tasks,
  1m43s).

Next active blocker: `WWW::Form::UrlEncoded` 0.26 Unicode percent encoding.

#### Stream::Buffered resolution

`Stream::Buffered::File::size` calls Perl's `-s` filehandle operation after
writing to an `IO::File->new_tmpfile` handle. PerlOnJava recognized only
pathname-backed channels, while `new_tmpfile` deliberately unlinks its path;
the result was `undef` for both `-s` and `-z`. The fix keeps the general file
test layer responsible for the behavior and obtains the size from the open
`FileChannel`, preserving upstream Stream::Buffered unchanged.

### Milestone 3: Minimal application boot and dispatch

Create a small upstream-compatible fixture application. First run its tests
with standard Perl in an environment containing Catalyst 5.90132, then run it
with PerlOnJava.

Required controller coverage:

```perl
sub index   :Path('/') :Args(0) { ... }
sub local   :Local                  { ... }
sub base    :Chained('/') :PathPart('api') :CaptureArgs(0) { ... }
sub item    :Chained('base') :PathPart('item') :Args(1)    { ... }
sub private :Private                { ... }
```

Exit criteria:

- Application setup completes without patching Catalyst.
- Every required action is discovered with the correct attributes.
- Direct dispatcher tests select the expected action and arguments.

### Milestone 4: PSGI and Netty end-to-end behavior

Deliverables:

- Expose the fixture application as PSGI.
- Run it with `Plack::Handler::Netty` using a hard timeout.
- Exercise it through an HTTP client with deterministic request fixtures.
- Verify request bodies, uploads, headers, cookies, redirects, UTF-8, and 500s.

Expected PSGI flags:

```perl
psgi.multithread  => 0
psgi.multiprocess => 0
psgi.run_once     => 0
```

Exit criteria:

- End-to-end tests pass without `fork` or Perl threads.
- Server termination is deterministic and leaves no orphaned JVM.

### Milestone 5: Hardening and documentation

Deliverables:

- Run `make` and the relevant bundled/upstream suites.
- Verify installation once more from a new isolated home.
- Document supported deployment, known limitations, and JDBC setup references.
- Decide whether Catalyst::Devel deserves a follow-up PR.

Exit criteria:

- All items in Definition of Done are satisfied.
- The PR contains exact test commands and results.

## Resolved Blocker: MooseX-MethodAttributes

### Reproduction source

Downloaded distribution:

```text
~/.perlonjava/cpan/build/MooseX-MethodAttributes-0.32-0/
```

The numeric build suffix is CPAN-generated and may differ on another machine.

Run the complete upstream suite:

```bash
cd ~/.perlonjava/cpan/build/MooseX-MethodAttributes-0.32-0
timeout 300 make test > /tmp/moosex-methodattributes.log 2>&1
```

Run the two Catalyst-focused files directly:

```bash
cd ~/.perlonjava/cpan/build/MooseX-MethodAttributes-0.32-0
timeout 60 /path/to/PerlOnJava4/jperl -Ilib -It/lib t/catalyst.t \
  > /tmp/moosex-methodattributes-catalyst.log 2>&1
timeout 60 /path/to/PerlOnJava4/jperl -Ilib -It/lib t/catalyst_role.t \
  > /tmp/moosex-methodattributes-role.log 2>&1
```

### Resolution

- Before the fix, `t/catalyst.t` aborted while loading the first Catalyst-like
  Moose class with:

  ```text
  Moose::Exception::MethodNameNotFoundInInheritanceHierarchy=HASH(...)
  Compilation failed in require
  ```

- The first failing operation was actually `before get_foo => sub { ... }` on
  a local attributed method. At scope end, Class::MOP saw a stale package
  generation and returned only its pre-sub-definition method map;
  `namespace::autoclean` then removed `get_foo`, `get_attribute`, and `other`.
- The same stale map caused the later inherited `after get_attribute` lookup
  failure and the role test's local-method count mismatch.
- Incrementing `mro::get_pkg_gen` in `SubroutineParser` at each named sub
  definition or redefinition restores ordinary Perl cache semantics without
  any Catalyst- or Moose-specific branch.
- The unchanged upstream suite now passes all 22 files and 144 tests.

### Relevant upstream files

```text
t/catalyst.t
t/catalyst_role.t
t/lib/CatalystLike/Controller.pm
t/lib/CatalystLike/Controller/Moose.pm
t/lib/CatalystLike/Controller/Moose/MethodModifiers.pm
lib/MooseX/MethodAttributes/Role/Meta/Class.pm
```

### Relevant PerlOnJava files

```text
src/main/perl/lib/Class/MOP/Class.pm
src/main/perl/lib/Class/MOP/Mixin/HasMethods.pm
src/main/perl/lib/Moose/Meta/Class.pm
src/main/java/org/perlonjava/runtime/mro/InheritanceResolver.java
src/main/java/org/perlonjava/runtime/perlmodule/Attributes.java
src/main/java/org/perlonjava/frontend/parser/SubroutineParser.java
src/main/java/org/perlonjava/backend/jvm/EmitSubroutine.java
src/main/java/org/perlonjava/backend/bytecode/OpcodeHandlerExtended.java
```

The fix deliberately contains no Catalyst- or Moose-specific cache or lookup
branch. The local regression uses only core `mro` behavior so system Perl can
validate the expected semantics without a separate Moose installation.

## Standard Command Set

All output-producing test commands write complete logs before summaries are
read.

```bash
# Full project gate
timeout 1200 make > /tmp/make-catalyst.log 2>&1

# Standard-Perl validation of a new unit test
timeout 60 prove src/test/resources/unit/catalyst_regression.t \
  > /tmp/catalyst-regression-perl.log 2>&1

# PerlOnJava backend comparison
timeout 60 ./jperl src/test/resources/unit/catalyst_regression.t \
  > /tmp/catalyst-regression-jvm.log 2>&1
timeout 60 ./jperl --interpreter src/test/resources/unit/catalyst_regression.t \
  > /tmp/catalyst-regression-interpreter.log 2>&1

# Dependency installation; never run bare jcpan
timeout 1200 ./jcpan install Catalyst::Runtime \
  > /tmp/catalyst-runtime-install.log 2>&1

# Cleanup audit
ps aux | awk '$3 > 20 {print $2, $3, $11, $12}'
```

Do not force-install modules in an acceptance run. A force install is allowed
only for dependency discovery, must use an isolated home, and must be recorded
in the dependency table.

## PR and Commit Strategy

Prefer one independently verified commit per reusable fix:

1. isolated CPAN-home tooling;
2. each compiler/runtime semantic fix with regression tests;
3. each bundled general-purpose module compatibility fix;
4. Catalyst fixture and PSGI integration;
5. final documentation.

The PR should summarize milestone results, exact versions, install/test
commands, forced-install discoveries that were eliminated, and remaining
deferred work. Avoid duplicating a chronological work diary in this document.

## Related Documentation

- [AGENTS.md](../../AGENTS.md) — mandatory safety, testing, and Git workflow.
- [Using CPAN Modules](../../docs/guides/using-cpan-modules.md) — `jcpan`
  behavior.
- [Module Porting](../../docs/guides/module-porting.md) — XS and Java
  replacement policy.
- [Bundled Modules](../../docs/reference/bundled-modules.md) — bundled Moose,
  DBI, and Plack handler.
- [Netty PSGI Example](../../examples/http_server_plack/README.md) — deployment
  model.
- [Moose Support](../modules/moose_support.md) — Moose/Class::MOP status and
  diagnostics.
