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
| MooseX-MethodAttributes 0.32 | runtime | 20/22 files pass; Catalyst-specific inherited modifier failure | blocking | reduce and fix at Moose/MOP/runtime layer |
| Catalyst-Runtime 5.90132 | runtime | downloaded, not successfully installed | blocking | resume after method attributes and isolation |
| Plack 1.0054 | runtime | dependency installation incomplete | blocking later | classify runtime versus test-only prerequisites |
| Class-C3-Adopt-NEXT 0.14 | runtime | functional tests mostly pass; warning differences remain | non-blocking until proven otherwise | defer |
| Encode-Locale 1.05 | transitive runtime | tied `%ENV` mutation tests fail | risk | verify whether Catalyst runtime path exercises mutation |
| POSIX-strftime-Compiler 0.46 | Plack logging | timezone and `POSIX::tzset` differences | non-blocking for initial dispatch | fix before logging acceptance gate |
| AnyDBM_File | optional/transitive | missing bundled core module makes CPAN suggest installing Perl | tooling defect | add/import core module or correct capability metadata |
| MooseX-Getopt 0.78 | runtime/development boundary | force-installed for discovery; help and trapped-exit tests fail | classify | determine which Catalyst runtime code requires it |
| Test-Trap 0.3.5 | test/development | force-installed for discovery; many failures | deferred | do not block runtime installation if only test-time |

When a new dependency appears, add it here only if it is blocking, forced,
incorrectly classified, or exposes a reusable PerlOnJava defect.

## Current Handoff State

Milestone 0 completed on 2026-08-04. In a fresh root, `Text-Glob-0.11`
downloaded with a verified checksum, passed both upstream files and all 74
tests, installed without force beneath `$PERLONJAVA_HOME/lib`, and loaded from
that exact path. The full `make` gate also passes. Milestone 1 is now active:
reduce the inherited attributed-method failure while retaining the
MooseX::MethodAttributes metaroles described below. Use the dependency table as
the baseline; use commit history and the PR for chronological progress.

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

### Milestone 1: Catalyst method attributes

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

## Current Blocker: MooseX-MethodAttributes

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

### Observed behavior

- `t/catalyst.t` aborts while compiling/loading the Catalyst-like subclass:

  ```text
  Moose::Exception::MethodNameNotFoundInInheritanceHierarchy=HASH(...)
  Compilation failed in require
  ```

- The failure occurs around an `after get_attribute => sub { ... }` modifier
  wrapping an inherited method carrying a custom `:Local` attribute.
- `t/catalyst_role.t` completes but reports one method-list count mismatch.
- Ordinary inherited Moose modifiers pass in a smaller probe. The reduction
  must preserve MooseX::MethodAttributes metaroles and attributed methods.

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

### Investigation order

1. Provision a standard-Perl Catalyst/Moose environment; the workstation's
   current system Perl does not have Moose installed.
2. Reduce the upstream package hierarchy while retaining:
   - an inherited attributed method;
   - `MooseX::MethodAttributes` inheritable metaroles;
   - an `after` modifier in the subclass.
3. Compare the class precedence list, `@ISA`, local method map, and
   `find_next_method_by_name` immediately before the failing modifier.
4. Determine whether the parent method is absent, stale in a method/MRO cache,
   or represented by the wrong metaclass.
5. Test both JVM and interpreter backends.
6. Add the reduced test under `src/test/resources/unit/` only after standard
   Perl validates it.
7. Run the full MooseX-MethodAttributes suite and `make`.

Do not add Catalyst names to generic cache invalidation or method lookup code.
The fix must be driven by ordinary Perl/Moose semantics.

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
