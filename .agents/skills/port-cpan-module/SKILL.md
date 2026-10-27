---
name: port-cpan-module
description: Port CPAN modules with minimal pure-Perl adaptations and Java replacements for XS/C; use native-module guidance for OS calls.
---

# Port a CPAN module

Follow [AGENTS.md](../../../AGENTS.md) for dirty-tree preservation, immutable
validation, timeouts and full logs. Keep one concise claim/checkpoint and record
only current decisions and evidence links in the design progress section.

Authoritative contributor guidance:

- [Module porting](../../../docs/guides/module-porting.md): naming, layout and
  port acceptance.
- [Patch layout](../../../dev/design/patch-and-cpan-prefs-layout.md): bundled
  CPAN distroprefs/tarball patches versus Perl 5 import patches.
- [Dual-backend modules](../../../dev/design/DUAL_BACKEND_CPAN_MODULES.md):
  Java XS replacement integration.

## Analyze before implementing

Pin the upstream release and obtain its Perl, XS/C, tests and support files.
Inspect MODULE/PACKAGE declarations, every native function, signatures/defaults,
stack/return conventions, algorithms, platform branches and edge cases. Identify
which functions require Java and retain original pure-Perl accessors, helpers,
overloads, exports and maps wherever possible. Preserve author/copyright/license
POD and identify the PerlOnJava port.

Check prerequisites, similar existing providers, `build.gradle` libraries and
`runtime/nativ/` before adding replacements. Pure-Perl modules may need no Java;
most XS replacements extend PerlModuleBase, while internal built-ins use a
different registration path. Use Java's existing libraries when behavior fits;
use [port-native-module](../port-native-module/SKILL.md) for OS/FFM calls and
Windows policies. Confirm current native infrastructure instead of assuming an
older JNR implementation remains authoritative.

## Implement the provider boundary

Use `src/main/java/org/perlonjava/runtime/perlmodule/ModuleName.java` and
`src/main/perl/lib/Module/Name.pm`. Map `Module::Name` to `ModuleName`, preserving
all-caps names such as DBI. Register methods in static `initialize()` and load
through the original wrapper's XSLoader hook. Standard Java method signatures
are `public static RuntimeList method(RuntimeArray args, int ctx)`.

Read [implementation-patterns.md](references/implementation-patterns.md) when
writing the provider: it retains the Java/Perl templates, XS conversion table,
return/reference/context examples, Java-library patterns, Time::Piece example
and troubleshooting tips. Inspect similar current providers before copying an
example; Perl scalar/list/void semantics and byte/Unicode distinctions remain
part of compatibility.

## Import and test without losing evidence

Copy upstream tests/support files into
`src/test/resources/module/Module-Name/t/` and required sibling fixture paths.
ModuleTestExecutionTest discovers `.t` files and runs from the module root;
`JPERL_TEST_FILTER=Module-Name` can focus iteration. Preserve upstream tests and
fixtures; do not modify/delete existing tests to make the port pass.

Validate the unchanged upstream behavior with system Perl using isolated missing
prerequisites. Add project-owned focused coverage for externally observed bugs,
validate it with Perl first, retain failing-parent evidence, then require JVM
and interpreter success. Exercise API edge cases found in XS comments.

Run sequentially with captured output, appropriate timeouts and no concurrent
shared-JAR readers/writers:

```bash
nice -n 19 make > /tmp/port-make.log 2>&1
JPERL_TEST_FILTER=Module-Name nice -n 19 make test-bundled-modules > /tmp/port-bundled.log 2>&1
timeout 3600 ./jcpan -t Module::Name > /tmp/port-upstream.log 2>&1
```

Require each gate's successful exit before starting a dependent gate. Bundled
module tests are separate from `make` unit coverage. Clear filters for required
full gates; `make dev` is disabled. Use `jcpan` for isolated install/test setup,
record its actual configured home and dependency load order, and verify the
bundled implementation separately from installed CPAN overlays.

A bundle must load its advertised dependencies without relying on an installed
copy. Prove this in an isolated empty CPAN home or separate worktree, checking
loaded paths; never delete the user's `.perlonjava` installation to hide an
overlay. A missing dependency is a bundling gap to fix, not a reason to change
the upstream test.

For core/dual-life files sourced from Perl 5, use `dev/import-perl5/sync.pl` and
its manifest instead of manual copying. Scope `--only` to a unique source/target
substring; use a full replay only intentionally. Set `protected: true` for files
needing PerlOnJava-specific changes and preserve optional patches in
`dev/import-perl5/patches/`, following the module-porting guide.

## Document delivery

Update bundled-modules and feature-matrix references, the Work in progress
changelog and notable README features when appropriate. Retain attribution,
record unsupported scope explicitly, and update the phase's current design
checkpoint with source, tests and next action rather than a chronological log.

## Issue coordination and reporting

Use the [triage guide](../../../docs/guides/issue-triage.md) for issue evidence,
agent claims, dependencies and concise checkpoints. Follow
[AGENTS.md](../../../AGENTS.md) for work preservation and validation.
