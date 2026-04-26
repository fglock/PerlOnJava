# Moose Support for PerlOnJava

## Overview

This document outlines the path to supporting Moose on PerlOnJava. Moose is
Perl's most popular object system, providing a rich meta-object protocol (MOP)
for defining classes, attributes, roles, and method modifiers.

## Current Status

Phase 1 (B-module subroutine introspection) is **complete**. The remaining
work is split between:

1. **Quick path** — ship a pure-Perl `Moose.pm` shim that delegates to Moo so
   simple consumers like `ANSI::Unicode` work today.
2. **Real path** — bundle a pure-Perl `Class::MOP` + `Moose` so existing
   Moose distributions install through `jcpan` without patching.

The single biggest blocker for the real path is **not** the missing C compiler.
Modern Moose (2.4000) has 13 `.xs` files plus `mop.c`; even with the compiler
check bypassed, `ExtUtils::MakeMaker` would still try to build them. We must
either replace `Moose.pm` outright or intercept `WriteMakefile` to drop the XS
declarations.

### Out of scope

- **`DESTROY` / `DEMOLISH` timing** and **`weaken` / `isweak`** semantics
  are being addressed on a separate branch (see
  `dev/architecture/weaken-destroy.md`). This plan assumes those primitives
  are available and does **not** track their implementation. Moose's
  `DEMOLISH` chain falls out of having `DESTROY` work correctly; nothing
  Moose-specific is needed here.
- Real JVM-level class generation (Byte Buddy / Javassist / additional ASM
  use beyond what PerlOnJava already does). `Class::MOP` operates on Perl
  stashes, not `java.lang.Class`, so no third-party bytecode library is
  required for correctness. The optional "make_immutable inlining"
  optimization can reuse the existing ASM infrastructure if/when pursued.

### Verified status (run on master, Apr 2026)

| Component | Status | Verification |
|-----------|--------|--------------|
| `B::CV->GV->NAME` | **Works** | `./jperl -e 'sub f{} use B; print B::svref_2object(\&f)->GV->NAME'` → `f` |
| `Sub::Identify::get_code_info` | **Works** | Returns `("main","f")` for `\&f` |
| Moo | **Works** | `use Moo; has ...; ->new(...)` (~96% of upstream test suite) |
| `Try::Tiny` | Works | `use Try::Tiny` succeeds |
| `Module::Runtime` | Works | `use Module::Runtime` succeeds |
| `Devel::GlobalDestruction` | Works | `use Devel::GlobalDestruction` succeeds |
| `Devel::StackTrace` | Works | `use Devel::StackTrace` succeeds |
| `Devel::OverloadInfo` | Works | `use Devel::OverloadInfo` succeeds |
| `Sub::Exporter` | Works | `use Sub::Exporter` succeeds |
| `Sub::Install` | Works | `use Sub::Install` succeeds |
| `Sub::Identify` | Works | `use Sub::Identify` succeeds |
| `Data::OptList` | Works | `use Data::OptList` succeeds |
| `Class::Load` | Works | `use Class::Load` succeeds |
| `Package::Stash` | Works | `use Package::Stash` succeeds |
| `Eval::Closure` | Works | `use Eval::Closure` succeeds |
| `Params::Util` | Works (no env var needed) | `_CLASS("Foo")` returns truthy |
| `B::Hooks::EndOfScope` | Works | `use B::Hooks::EndOfScope` succeeds |
| `Package::DeprecationManager` | Loads, requires `-deprecations => {...}` import args (normal upstream behavior) | — |
| `Class::MOP` | **Missing** | Not bundled |
| `Moose` | **Missing** | Not bundled; CPAN install fails (XS) |
| `ExtUtils::HasCompiler` | Returns false in practice | Returns undef early because `$Config{usedl}` is empty |

### Real blockers

| Blocker | Severity | Description |
|---------|----------|-------------|
| `Class::MOP` not bundled | **Critical** | Moose can't load; even simple `use Moose` fails |
| Moose's `Makefile.PL` builds 13 `.xs` files | **Critical** | Compiler-check bypass alone is insufficient; MM still tries to compile |
| `Moose.pm` not bundled | **Critical** | No alternative entry point on disk |
| MAGIC-based export tracking in `Moose::Exporter` | Low | Affects re-export warnings only |

---

## Why Phase 1 was the prerequisite

Moose uses `Class::MOP::get_code_info($coderef)` (and `Sub::Identify`'s
identical helper) to:

1. Decide whether a method belongs to a class or was imported.
2. Track method origins during role composition.
3. Tell defined subs from re-exported ones.
4. Build method maps and override tables.

PerlOnJava now stores `subName`/`packageName` on `RuntimeCode`
(`src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`), and
`B.pm`'s `B::CV`/`B::GV` accessors read them. This is the foundation on which
either path below can build.

---

## Quick path: pure-Perl Moose shim via Moo

**Goal**: get `use Moose;` working for the common subset (attributes,
inheritance, roles, method modifiers) by delegating to Moo.

### Deliverables

- `src/main/perl/lib/Moose.pm` — shim that translates `Moose` API into Moo
  calls.
- `src/main/perl/lib/Moose/Role.pm` — delegates to `Moo::Role`.
- `src/main/perl/lib/Moose/Util/TypeConstraints.pm` — minimal stub providing
  `subtype`, `enum`, `as`, `where`, `coerce`, `class_type`, `role_type`.
- `src/main/perl/lib/Moose/Object.pm` — base class with `new`, `BUILD`,
  `BUILDARGS`, `meta` (returning a stub metaclass).

Reference implementations to mine: `Mo::Moose`, `Any::Moose`, and
`MouseX::Types::Moose` (already present in `~/.perlonjava/lib/`).

### Acceptance criteria

```bash
./jcpan -t ANSI::Unicode      # currently FAIL → must PASS
./jcpan -t Test::Class::Moose # smoke test
```

### Limitations of this path

- No real metaclass introspection (`$class->meta->get_all_attributes` etc.).
- Type constraints are name-only; no `MooseX::Types`.
- `Moose::Exporter`-based modules that call deep MOP APIs won't work.

This is enough for the long tail of CPAN modules that just declare attributes
and method modifiers.

---

## Real path: bundle pure-Perl `Class::MOP` + `Moose`

### Phase A — `ExtUtils::HasCompiler` deterministic stub

The upstream module currently lives at `~/.perlonjava/lib/ExtUtils/HasCompiler.pm`
and returns false only because `$Config{usedl}` happens to be empty. Make this
explicit and authoritative:

```perl
# src/main/perl/lib/ExtUtils/HasCompiler.pm
package ExtUtils::HasCompiler;
our $VERSION = '0.025';
use strict; use warnings;
use base 'Exporter';
our @EXPORT_OK = qw/can_compile_loadable_object can_compile_static_library can_compile_extension/;
our %EXPORT_TAGS = (all => \@EXPORT_OK);

sub can_compile_loadable_object { 0 }
sub can_compile_static_library  { 0 }
sub can_compile_extension       { 0 }
1;
```

Verify with:

```bash
./jperl -MExtUtils::HasCompiler=can_compile_loadable_object \
        -e 'print can_compile_loadable_object(quiet=>1) ? "yes" : "no"'
# → no
```

**Important**: this alone does **not** unblock Moose 2.4000. Its generated
`Makefile.PL` contains:

```perl
OBJECT => "xs/Attribute\$(OBJ_EXT) ... mop\$(OBJ_EXT)",
XS => { "xs/Attribute.xs" => "xs/Attribute.c", ... },  # 13 .xs files
C  => [ ... ],
```

After the compiler-check bypass, `WriteMakefile` will still attempt to compile
those. We need Phase B too.

### Phase B — strip XS in `WriteMakefile` on PerlOnJava

Two sub-options:

**B1.** Patch `src/main/perl/lib/ExtUtils/MakeMaker.pm` (already PerlOnJava's
own copy) so it scrubs `OBJECT`, `XS`, `C`, `H`, `XSPROTOARG`, `XSOPT` from
the args before generating the Makefile. Gate behind a config flag so other
modules with optional XS keep working.

**B2.** Bundle our own `Moose.pm` (Phase D) so the upstream
`Moose-2.4000/Makefile.PL` never runs.

Preferred: **B1** — it's a one-time investment that helps every XS module
that ships pure-Perl fallbacks.

### Phase C — Java `Class::MOP` helpers

Create `src/main/java/org/perlonjava/runtime/perlmodule/ClassMOP.java`. Most
of `Class::MOP` is already pure Perl upstream and runs unmodified once the
helpers exist. We only need Java for the irreducible pieces:

| Function | Trivial after Phase 1? | Notes |
|----------|------------------------|-------|
| `get_code_info($cv)` | Yes | Read `RuntimeCode.packageName`/`subName` |
| `is_stub($cv)` | Yes | Check that the code body is empty |
| `_definition_context()` | Yes | Capture `caller(1)` |
| `_flag_as_reexport($glob)` | No | Needs MAGIC; defer to Phase E |
| `_export_is_flagged($glob)` | No | Needs MAGIC; defer to Phase E |
| `INSTALL_SIMPLE_READER` etc. | Optional | Pure-Perl version is fine; Java only for speed |

Skeleton:

```java
package org.perlonjava.runtime.perlmodule;

public class ClassMOP extends PerlModuleBase {
    public ClassMOP() { super("Class::MOP", false); }

    public static void initialize() {
        ClassMOP m = new ClassMOP();
        try { m.registerMethod("get_code_info", null); }
        catch (NoSuchMethodException e) { throw new RuntimeException(e); }
    }

    public static RuntimeList get_code_info(RuntimeArray args, int ctx) {
        RuntimeScalar cv = args.get(0);
        if (cv.type != RuntimeScalarType.CODE) return new RuntimeList();
        RuntimeCode code = (RuntimeCode) cv.value;
        RuntimeList r = new RuntimeList();
        r.add(new RuntimeScalar(code.packageName != null ? code.packageName : "main"));
        r.add(new RuntimeScalar(code.subName     != null ? code.subName     : "__ANON__"));
        return r;
    }
}
```

Wire it up in `org.perlonjava.runtime.perlmodule` initialization next to
`Mro`, `Internals`, etc.

### Phase D — bundle pure-Perl `Class::MOP` and `Moose`

Drop the upstream `.pm` tree (without the `xs/` and `mop.c`) into
`src/main/perl/lib/Class/MOP*` and `src/main/perl/lib/Moose*`. Add a small
`PERL_CLASSMOP_PP=1`-style wrapper that forces every `Class::MOP::*` module
to skip `Class::Load::XS`/XS-only branches.

### Phase E — export-flag magic (optional)

Lower priority; only affects `Moose::Exporter` re-export tracking. Implement
as a `WeakHashMap<GlobReference, ExportFlags>` on the Java side, exposed
through helper subs `Moose::Exporter::_set_flag`/`_get_flag`.

---

## Verification matrix

```bash
# Phase 1 sanity (already passes)
./jperl -e 'sub f{} use B; print B::svref_2object(\&f)->GV->NAME'   # → f

# After Quick path (shim)
./jcpan -t ANSI::Unicode                                            # → PASS
./jperl -MMoose -e 'package P { use Moose; has x => (is=>"rw") } P->new(x=>1)'

# Run upstream Moose's full test suite against the shim (no install)
./jcpan -t Moose                                                    # → see baseline below

# After Phase A (HasCompiler stub)
./jperl -MExtUtils::HasCompiler=can_compile_loadable_object \
        -e 'print can_compile_loadable_object(quiet=>1) ? "yes" : "no"'  # → no

# After Phase B (XS-strip in WriteMakefile)
./jcpan -t Moose                                                    # → install OK, more tests pass

# After Phase C+D (real Class::MOP / Moose)
./jperl -MClass::MOP -e 'print Class::MOP::get_code_info(\&Class::MOP::class_of)'
./jcpan -t Moose
./jcpan -t MooseX::Types
```

### Running upstream Moose's test suite against the shim

`./jcpan -t Moose` is wired up via a CPAN distroprefs entry shipped from
`src/main/perl/lib/CPAN/Config.pm` (auto-bootstrapped to
`~/.perlonjava/cpan/prefs/Moose.yml` on first run). It:

- ensures `Moo` is installed before testing — the shim delegates to Moo,
  so the `pl:` step calls a tiny Perl helper
  (`PerlOnJava::Distroprefs::Moose::bootstrap_pl_phase`) that does
  `require Moo` and falls back to `system $ENV{JCPAN_BIN}, "Moo"` if
  Moo isn't loadable;
- creates a stub `Makefile` so CPAN.pm's "no Makefile created" fallback
  path doesn't kick in (also done by the same helper);
- skips `make` and `install` (`PerlOnJava::Distroprefs::Moose::noop`,
  cross-platform replacement for POSIX `true`);
- runs `prove --exec jperl -r t/` against the unpacked tarball.

`jcpan` / `jcpan.bat` prepend the project directory to `PATH` so
shell-spawned subprocesses (CPAN's distroprefs commandlines, prove's
child processes) find `jperl` on both Unix and Windows. They also
export `JCPAN_BIN` for the helper to recursively call jcpan when Moo
needs installing.

This design avoids POSIX-only shell constructs — `||`, `;`, `touch`,
`/dev/null`, `$VAR` — that don't work in Windows `cmd.exe`. Each phase
is a single `jperl -MPerlOnJava::Distroprefs::Moose -e '...'` (or
`prove --exec jperl ...`) invocation, parsed identically by `bash`,
`sh`, `cmd.exe`, and PowerShell.

We deliberately avoid a CPAN `depends:` block — it would force CPAN to
resolve Moose's full upstream prereq tree (`Package::Stash::XS`,
`MooseX::NonMoose`, …), most of which is XS and unsatisfiable. The
helper installs only `Moo`, the real runtime dependency of the shim.

Because `prove --exec` invokes `jperl` per test file without adding
`lib/` or `blib/lib/` to `@INC`, the **bundled shim from the jar** wins
over the unpacked upstream `lib/Moose.pm`. So you can run the entire
upstream suite end-to-end and see honestly which tests pass, without
patching Moose's `Makefile.PL` or shipping a fragile diff.

The same recipe is the model for any future "test against shim, don't
install" scenario — define a distroprefs entry that overrides `pl` /
`make` / `install` with no-ops and `test` with a `prove --exec` line.

### Quick-path baseline (Moose 2.4000)

Snapshot from `./jcpan -t Moose` against the current shim:

| Metric | Value |
|---|---|
| Test files executed | 478 |
| Individual assertions executed | 616 |
| Fully passing files | ~29 |
| Partially passing files | ~44 |
| Compile/load fail (missing `Class::MOP::*`, `Moose::Meta::*`) | ~405 |
| Assertions ok | 370 |
| Assertions fail | 246 |

The 29 fully-passing files cover BUILDARGS / BUILD chains, immutable
round-trips, anonymous role creation, several Moo↔Moose bug regressions,
the cookbook recipes for basic attribute / inheritance / subtype use,
and the Type::Tiny integration test. The 44 partials include
high-value chunks such as `basics/import_unimport.t` (31/48),
`basics/wrapped_method_cxt_propagation.t` (6/7), and
`recipes/basics_point_attributesandsubclassing.t` (28/31).

Phases C/D (real `Class::MOP` and `Moose` ports) should move these
numbers; record the new totals here whenever they shift.

---

### Lock in progress as bundled-module tests

`src/test/resources/module/{Distribution}/` is reserved for **unmodified
upstream test files** of CPAN distributions we actually bundle. Use it
**only** when both apply:

1. The distribution itself is bundled (its `.pm` files live in
   `src/main/perl/lib/`, or the test directory ships its own `lib/`).
2. The tests being copied are the upstream tests for **that** distribution.

So:

- When we eventually bundle a pure-Perl `Moose` (Phase D), copy
  `Moose-2.4000/t/...` into `src/test/resources/module/Moose/t/`.
- Same for `Class::MOP`, `MooseX::Types`, etc., as each gets bundled.
- Do **not** snapshot tests for downstream consumers we don't bundle
  (e.g. ANSI::Unicode). Those stay as `./jcpan -t` smoke checks.
- Do **not** put shim-specific or PerlOnJava-specific tests under
  `module/`. Shim coverage belongs in `src/test/resources/unit/` if it's
  needed beyond the `jcpan -t` smoke.

Conventions for bundled-distribution snapshots (see existing layouts under
`src/test/resources/module/`, e.g. `Clone-PP/`, `Math-BigInt/`,
`XML-Parser/`):

- One directory per CPAN distribution (`Moose/`, `Class-MOP/`, …); use the
  dist name with `::` replaced by `-`.
- Mirror the upstream `t/` layout exactly. Don't edit the test files; if a
  test is genuinely incompatible, prefer fixing the runtime over editing the
  test (per AGENTS.md).
- Tests are picked up automatically by the Gradle `testModule` task —
  no JUnit wiring is needed.

Verify with:

```bash
make test-bundled-modules
```

This gives us a regression net: every newly-passing upstream Moose-ecosystem
test we vendor in becomes guarded against regressions, and `git log
src/test/resources/module/Moose*` becomes the historical record of progress.

For the **current PR (Quick path / shim only)** there are no bundled
upstream distributions yet, so nothing is snapshotted under `module/`.
The regression net for the shim is `make` plus the `./jcpan -t
ANSI::Unicode` smoke check.

---

## Dependency graph (verified)

```
Moose                              ← MISSING
└── Class::MOP                     ← MISSING (Phase C+D)
    ├── MRO::Compat                ← upstream copy works
    ├── Class::Load                ← works
    │   ├── Module::Runtime        ← works
    │   ├── Data::OptList          ← works
    │   │   ├── Params::Util       ← works (no env var)
    │   │   └── Sub::Install       ← works
    │   └── Try::Tiny              ← works
    ├── Devel::GlobalDestruction   ← works
    ├── Devel::OverloadInfo        ← works
    ├── Devel::StackTrace          ← works
    ├── Dist::CheckConflicts       ← works
    ├── Eval::Closure              ← works
    ├── Package::DeprecationManager← works (normal import-arg requirement)
    ├── Package::Stash             ← works
    ├── Sub::Exporter              ← works
    ├── Sub::Identify              ← works (Phase 1)
    ├── List::Util                 ← built-in
    ├── Scalar::Util               ← built-in
    └── B::Hooks::EndOfScope       ← works
```

The whole "needs investigation" / "needs PP flag" column from the previous
revision of this doc is gone — every `Class::MOP` runtime dependency that
isn't `Class::MOP` itself loads cleanly today.

---

## Progress Tracking

### Current Status

- **Phase 1 — DONE.** B-module subroutine name/stash introspection works.
- **Quick path — not started.** Highest leverage: ships `Moose.pm` shim, immediately unblocks ANSI::Unicode-class modules.
- **Phase A — not started.** Trivial; replace upstream `ExtUtils::HasCompiler` with deterministic stub.
- **Phase B — not started.** Strip XS keys in `WriteMakefile`.
- **Phase C — not started.** Java `Class::MOP::get_code_info` + helpers.
- **Phase D — not started.** Bundle pure-Perl `Class::MOP` and `Moose`.
- **Phase E — deferred.** Export-flag MAGIC.

### Completed

- [x] Phase 1: B-module subroutine name introspection
- [x] Verified working dependency tree (Apr 2026)

### Decision needed

Pick one to pursue first:

1. **Quick path (Moose-as-Moo shim).** ~1–2 days. Unblocks ANSI::Unicode and similar. Won't unblock anything that depends on real MOP introspection.
2. **Phases A → B → D.** ~1–2 weeks. Lets `jcpan -t Moose` actually run upstream Moose. Bigger payoff, bigger risk.
3. **Phase C standalone (Java helpers only).** Unblocks nothing on its own but is a prerequisite for path 2 and a strict superset of what the shim needs.

Recommendation: **(1) first to ship value quickly, then (3) → (2)** as the real fix.

### Open work items

- [ ] Decide path (above).
- [ ] If path 1: write `src/main/perl/lib/Moose.pm`, `Moose/Role.pm`, `Moose/Object.pm`, `Moose/Util/TypeConstraints.pm`.
- [ ] If path 2: write Phase A stub, Phase B MakeMaker patch, Phase C Java module, Phase D bundle.
- [ ] In either case: add `./jcpan -t ANSI::Unicode` to a smoke test list.
- [ ] Each time we **bundle** a Moose-ecosystem distribution (Moose itself, Class::MOP, MooseX::Types, …), snapshot its upstream `t/` under `src/test/resources/module/{Distribution}/t/` so `make test-bundled-modules` guards against regressions. Do not snapshot tests for non-bundled downstream consumers; those remain `./jcpan -t` smoke checks.

---

## Related Documents

- [xs_fallback.md](xs_fallback.md) — XS fallback mechanism
- [makemaker_perlonjava.md](makemaker_perlonjava.md) — MakeMaker implementation
- [cpan_client.md](cpan_client.md) — CPAN client support
- `.agents/skills/port-cpan-module/` — Module porting skill

## References

- [Moose Manual](https://metacpan.org/pod/Moose::Manual)
- [Class::MOP](https://metacpan.org/pod/Class::MOP)
- [Moo](https://metacpan.org/pod/Moo)
- [B module](https://perldoc.perl.org/B)
