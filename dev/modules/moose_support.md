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

Snapshot history from `./jcpan -t Moose` against the current shim:

| Metric | Initial shim | After refcount/DESTROY (Apr 2026) | After Phase A + C-mini (Apr 2026) | After Phase 2 stubs (Apr 2026) |
|---|---|---|---|---|
| Test files executed | 478 | 478 | 478 | 478 |
| Individual assertions executed | 616 | 616 | 667 | **1419** |
| Fully passing files | ~29 | 35 | 36 | **56** |
| Partially passing files | ~44 | 94 | 98 | **184** |
| Compile/load fail (missing `Class::MOP::*`, `Moose::Meta::*`) | ~405 | ~349 | ~344 | **~238** |
| Assertions ok | 370 | 372 | 419 | **953** |
| Assertions fail | 246 | 244 | 248 | **466** |

The initial 29 fully-passing files covered BUILDARGS / BUILD chains,
immutable round-trips, anonymous role creation, several Moo↔Moose bug
regressions, the cookbook recipes for basic attribute / inheritance /
subtype use, and the Type::Tiny integration test. The 44 partials
included high-value chunks such as `basics/import_unimport.t` (31/48),
`basics/wrapped_method_cxt_propagation.t` (6/7), and
`recipes/basics_point_attributesandsubclassing.t` (28/31).

The refcount/DESTROY merge (PRs #565, #566, plus weaken/destroy work)
moved the structural picture meaningfully even though the assertion
total only nudged: ~56 files that previously failed at compile/load
time now run subtests. Most ended up partial rather than fully green
(partials roughly doubled, 44 → 94), but six more files are fully
passing (29 → 35). The shim's per-test infrastructure (BUILD chains,
DEMOLISH ordering, weak refs) is now solid; the remaining failures
are dominated by missing `Class::MOP::*` and `Moose::Meta::*`
introspection APIs.

**Phase A + Phase C-mini** (this PR) added two pieces:

- `ExtUtils::HasCompiler` deterministic stub
  (`src/main/perl/lib/ExtUtils/HasCompiler.pm`) — always reports "no
  compiler", instead of relying on `$Config{usedl}` happening to be
  empty.
- `Class::MOP` shim (`src/main/perl/lib/Class/MOP.pm`) — provides
  `class_of`, `get_metaclass_by_name`, `store_metaclass_by_name`,
  `remove_metaclass_by_name`, `does_metaclass_exist`,
  `get_all_metaclasses` (and friends), `get_code_info`,
  `is_class_loaded`, `load_class`, `load_first_existing_class`. Returns
  "no metaclass" everywhere, which is the correct answer under the
  Moose-as-Moo shim. The previous behavior was a hard "Undefined
  subroutine &Class::MOP::class_of called" the moment Moo's
  `_Utils::_load_module` hit a not-installed dependency on a class
  that already had `Moose.pm` loaded.

Net effect of Phase A + C-mini: **+51 individual assertions now
execute** (616 → 667), **+47 newly pass** (372 → 419), and one more
file goes fully green (35 → 36). The four extra failures are
upstream tests that previously bailed before reaching their assertion
phase and now reach it; none are real regressions.

**Phase 2 stubs** (a follow-up PR) added the next batch of
compile-time blockers and a bailout fix:

- `Moose.pm` / `Moose::Role` now `use Class::MOP ()` at top-level so
  Moo's runtime calls to `Class::MOP::class_of` (made whenever
  `$INC{"Moose.pm"}` is set) are always defined. This was the cause of
  ~50+ "Undefined subroutine &Class::MOP::class_of" runtime errors on
  the previous baseline.
- `metaclass.pm` stub — installs a no-op `meta` method on the caller.
- `Test::Moose.pm` — covers `meta_ok`, `does_ok`, `has_attribute_ok`,
  `with_immutable`. Falls back to `$class->can($attr)` when no real
  metaclass is available.
- `Moose::Util.pm` — covers `find_meta`, `is_role`, `does_role`,
  `apply_all_roles`, `english_list`, `throw_exception`, plus
  trait/metaclass alias passes-through.
- Skeleton stubs for `Class::MOP::Class`, `Class::MOP::Attribute`,
  `Moose::Meta::Class`, `Moose::Meta::TypeConstraint::Parameterized`,
  `Moose::Meta::Role::Application::RoleSummation`, and
  `Moose::Exporter` — enough surface that `require X` succeeds and
  `X->new(...)` returns something with the methods upstream tests
  inspect.
- Pre-populated standard type-constraint stubs in
  `Moose::Util::TypeConstraints` (`Any`, `Item`, `Defined`, `Bool`,
  `Str`, `Num`, `Int`, `ArrayRef`, `HashRef`, `Object`, …). Without
  these, `t/type_constraints/util_std_type_constraints.t` would
  `BAIL_OUT("No such type ...")` and prove would stop, losing every
  test file that followed alphabetically (≈7 files / 50+ assertions).

Net effect of Phase 2: **+752 individual assertions now execute**
(667 → 1419), **+534 newly pass** (419 → 953), **+20 fully-green
files** (36 → 56), and -106 files now compile that previously
errored out at compile time. The +218 newly failing assertions are
mostly tests that hadn't reached their assertion phase before (so
"failure" is the honest answer); they include real shortcomings of
the stub (e.g. `Test::Moose::has_attribute_ok` doesn't know about
inherited Moo attributes) which would only be fixed by Phase D
(real Class::MOP / Moose port).

Phases C-full / D (real `Class::MOP::Class` instances and a pure-Perl
`Moose` port) should move these numbers further; record the new
totals here whenever they shift.

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
- **Quick path — DONE.** `Moose.pm` shim ships, ANSI::Unicode-class modules unblocked.
- **Phase A — DONE.** `ExtUtils::HasCompiler` deterministic stub ships at `src/main/perl/lib/ExtUtils/HasCompiler.pm`.
- **Phase B — not started.** Strip XS keys in `WriteMakefile`. (Lower priority while we're not yet trying to install upstream Moose.)
- **Phase C-mini — DONE.** `Class::MOP` shim with `class_of` / `get_metaclass_by_name` / `get_code_info` / `is_class_loaded` and friends; ships at `src/main/perl/lib/Class/MOP.pm`.
- **Phase 2 stubs — DONE.** `metaclass.pm`, `Test::Moose.pm`, `Moose::Util.pm`, plus skeleton `Class::MOP::Class` / `Class::MOP::Attribute` / `Moose::Meta::Class` / `Moose::Meta::TypeConstraint::Parameterized` / `Moose::Meta::Role::Application::RoleSummation` / `Moose::Exporter`. Pre-populated standard type-constraint stubs to avoid `BAIL_OUT` in upstream test suite.
- **Phase C-full — not started.** Real `Class::MOP::Class` instances backed by Java helpers (`org.perlonjava.runtime.perlmodule.ClassMOP`).
- **Phase D — not started.** Bundle pure-Perl `Class::MOP::*` and `Moose::*` distributions.
- **Phase E — deferred.** Export-flag MAGIC.

### Completed

- [x] Phase 1: B-module subroutine name introspection
- [x] Verified working dependency tree (Apr 2026)
- [x] Quick path: `Moose.pm` / `Moose::Role` / `Moose::Object` / `Moose::Util::TypeConstraints` shims
- [x] Phase A: `ExtUtils::HasCompiler` deterministic stub
- [x] Phase C-mini: `Class::MOP` shim (no metaclass instances; just enough surface to keep Moo happy)
- [x] Phase 2 stubs: `metaclass.pm`, `Test::Moose.pm`, `Moose::Util.pm`, skeleton `Class::MOP::Class` / `Class::MOP::Attribute` / `Moose::Meta::Class` / `Moose::Exporter` / friends, and standard-type stubs in `Moose::Util::TypeConstraints` to suppress upstream `BAIL_OUT`.

### Lessons learned (post-Phase-2)

The two iterative shim PRs (#570, #572) turned the formal phase plan
above on its head: paths C-full / D were originally framed as the
"real fix", but in practice **incremental shim widening has paid out
much faster than a full pure-Perl port** would have. Concrete
takeaways:

1. **Compile-time stubs are the highest-leverage move.** Each round
   of "let `require X` succeed" cleared dozens of files at once
   (Phase 2 alone: 344 → 238 files that fail before any subtest).
2. **Pre-loading is as important as having the stub.** Once
   `Moose.pm` set `$INC{Moose.pm}`, Moo's runtime probes called
   `Class::MOP::class_of` from random call sites. Adding
   `use Class::MOP ()` at the top of `Moose.pm` / `Moose/Role.pm`
   killed ~50+ runtime errors that would otherwise have masked any
   shim widening.
3. **One BAIL_OUT can hide an arbitrary number of test files.**
   `t/type_constraints/util_std_type_constraints.t` calling
   `BAIL_OUT("No such type ...")` was costing us ~7 trailing files
   per run. Pre-populating standard type-constraint stubs cleanly
   contained that — but the lesson is general: any new failure mode
   that hits `BAIL_OUT` should be treated as a high-priority block.
4. **The Moose-as-Moo gap is mostly method surface, not metaclass
   semantics.** A large fraction of upstream tests just want
   `$meta->add_attribute`, `$meta->get_method`, `$meta->is_mutable`
   to exist and return a sensible-shaped value. They rarely care
   that the metaclass is "real". Ergo: enriching `Moose::_FakeMeta`
   is high-leverage and low-risk.
5. **Stub objects must `isa` the right things.** Upstream tests do
   `isa_ok($meta, 'Moose::Meta::Class')` and
   `isa_ok($attr, 'Moose::Meta::Attribute')`. Returning a plain
   blessed hashref isn't enough; the stub needs `@ISA` set to the
   real upstream class names so `isa` checks pass.

### Recommended next phases

In priority order. All are incremental shim widenings that follow the
same playbook as Phases A / C-mini / 2.

#### Phase 3 — Rich `Moose::_FakeMeta` and the next batch of stubs

Estimated payoff: similar to Phase 2 (+15–25 fully-green files,
+200–500 newly-passing assertions). Estimated effort: ~1 day.

3a. **Enrich `Moose::_FakeMeta`** so `isa_ok($meta, 'Moose::Meta::Class')`
    passes and the methods upstream tests reach for actually exist:

    | Method                  | Failure count in last run |
    |-------------------------|---------------------------|
    | `add_attribute`         | 24                        |
    | `get_attribute`         | 8                         |
    | `new_object`            | 4                         |
    | `is_mutable`            | 3                         |
    | `get_method`            | 3                         |
    | `meta`                  | 4                         |
    | (FakeMeta isa Class::MOP::Class) | 6                |
    | (FakeMeta isa Moose::Meta::Class) | 4               |

    Fix: add `our @ISA = ('Class::MOP::Class', 'Moose::Meta::Class');`
    to `Moose::_FakeMeta`, and implement the missing methods either
    as pass-throughs to the underlying Moo metaclass (via
    `Moo->_constructor_maker_for($class)->all_attribute_specs`) or as
    minimal "remember what `has` declared" tracking inside
    `Moose.pm`'s `import`.

3b. **Add the next batch of compile-time `.pm` stubs** for the most
    common "Can't locate" failures:

    | Stub                                      | Errors |
    |-------------------------------------------|--------|
    | `Moose::Meta::Attribute`                  | 8      |
    | `Moose::Meta::Role`                       | 6      |
    | `Moose::Meta::Role::Composite`            | 7      |
    | `Class::MOP::Method`                      | 7      |
    | `Class::MOP::Instance`                    | 4      |
    | `Moose::Util::MetaRole` (with `apply_metaroles` no-op) | 4 + 9 calls |
    | `Moose::Meta::TypeConstraint`             | 3      |
    | `Moose::Exception` (and the most-thrown subclasses) | 3 + many `throw_exception` calls |

    Each is the same shape as the existing skeleton stubs:
    `package X; require Y; our @ISA = (Y); sub new { bless {...} } 1;`.

3c. **Bless `Moose::Util::TypeConstraints::_Stub` into
    `Moose::Meta::TypeConstraint`** so `isa_ok($t,
    'Moose::Meta::TypeConstraint')` passes (5 errors today).

3d. **Add the missing methods on `Moose::Util::TypeConstraints`**:

    | Method                           | Errors |
    |----------------------------------|--------|
    | `export_type_constraints_as_functions` | 5  |
    | `find_or_parse_type_constraint`        | 3  |

3e. **`Moose::Meta::Role->create_anon_role`** as a no-op returning
    a `_FakeRole` (4 errors).

#### Phase 4 — Real attribute introspection on top of Moo

Estimated payoff: medium-high (+100–300 newly-passing assertions,
mostly under `t/basics/`, `t/attributes/`, `t/cmop/attribute*`).
Estimated effort: ~2 days.

By Phase 3, `$meta->add_attribute(name => ..., is => 'rw')` exists
but is a no-op. To make `$meta->get_attribute_list` / `$meta->get_attribute(...)`
return useful values, hook into Moo's actual attribute store:

```perl
sub get_attribute_list {
    my $self = shift;
    my $name = $self->name;
    require Moo::_Utils;
    return keys %{ Moo->_constructor_maker_for($name)->all_attribute_specs // {} };
}
```

Same trick for `get_attribute`, `find_attribute_by_name`, etc.; wrap
each Moo attribute spec in a `Class::MOP::Attribute` stub. This makes
`Test::Moose::has_attribute_ok` actually test what users mean.

#### Phase 5 — `Moose::Util::MetaRole` real apply

Estimated payoff: low-medium (most MooseX::* extensions need it; few
upstream Moose tests do). Estimated effort: ~1 day.

`Moose::Util::MetaRole::apply_metaroles` is what
`MooseX::*` extensions use to install custom metaclass roles. A real
implementation needs to compose roles into the metaclass at
install-time — under the shim, "compose into Moo metaclass" is a no-op
that just records the role list, which is enough for most consumers.

#### Phase 6 — `Moose::Exporter` proper sugar installation

Estimated payoff: medium (unlocks every "extends Moose with custom
sugar" module: `MooseX::SimpleConfig`, `MooseX::Getopt`, ...).
Estimated effort: ~2–3 days.

The current `Moose::Exporter` stub only forwards to `Moose->import`.
A more complete version would install the caller's `with_caller` /
`with_meta` / `as_is` exports onto consumers.

#### Phase B (deferred) — strip XS in `WriteMakefile`

Only relevant once we attempt to install upstream Moose. With
shim-based testing via `prove --exec jperl`, we don't need it. Keep
in the plan but don't pursue until either:
- A user actually wants `cpan -i Moose` to succeed end-to-end, or
- Phase D is in play.

#### Phase C-full / Phase D — real `Class::MOP` / `Moose` port

Status updated: previously labeled "the real fix"; now the **last
recourse** rather than the next move.

Why deferred:
- Phase 3 alone is expected to recover another ~15–25 fully-green
  files for ~1 day of work.
- Phase 4 hooks into Moo internals to give us real attribute
  introspection without bundling Moose at all.
- A full pure-Perl Moose port is hundreds of files and thousands of
  lines, and would still need ~all of the shim infrastructure
  (Class::MOP, B, etc.) to work.

Reconsider Phase D when **either** the iterative shim has plateaued
(Phases 3–6 stop adding ≥10 files per round) **or** a specific
high-value distribution (e.g. Catalyst, DBIx::Class roles, ...) needs
something the shim categorically cannot provide.

#### Phase E (deferred) — Export-flag MAGIC

Same status as before. Affects `Moose::Exporter` re-export tracking
only; lowest priority.

### Open work items

- [ ] Phase 3a: enrich `Moose::_FakeMeta` (target: `Moose::_FakeMeta isa
      Moose::Meta::Class`, plus `add_attribute` / `get_attribute` /
      `new_object` / `is_mutable` / `get_method`).
- [ ] Phase 3b: add next batch of compile-time `.pm` stubs
      (`Moose::Meta::Attribute`, `Moose::Meta::Role`,
      `Moose::Meta::Role::Composite`, `Class::MOP::Method`,
      `Class::MOP::Instance`, `Moose::Util::MetaRole`,
      `Moose::Meta::TypeConstraint`, `Moose::Exception`).
- [ ] Phase 3c: bless `_Stub` into `Moose::Meta::TypeConstraint`.
- [ ] Phase 3d: add `export_type_constraints_as_functions` and
      `find_or_parse_type_constraint` to `Moose::Util::TypeConstraints`.
- [ ] Phase 3e: add `Moose::Meta::Role->create_anon_role`.
- [ ] Phase 4: hook into Moo's attribute store from
      `Moose::_FakeMeta->get_attribute*` methods.
- [ ] Phase 5: real-ish `Moose::Util::MetaRole::apply_metaroles`.
- [ ] Phase 6: full `Moose::Exporter` sugar installation.
- [ ] Each time we **bundle** a Moose-ecosystem distribution (Moose
      itself, Class::MOP, MooseX::Types, …), snapshot its upstream
      `t/` under `src/test/resources/module/{Distribution}/t/` so
      `make test-bundled-modules` guards against regressions. Do not
      snapshot tests for non-bundled downstream consumers; those
      remain `./jcpan -t` smoke checks.
- [ ] Phase B (deferred): patch `ExtUtils::MakeMaker::WriteMakefile`
      to scrub `OBJECT` / `XS` / `C` keys when running on PerlOnJava.
      Only needed once we try to install upstream Moose.
- [ ] Phase C-full / Phase D (deferred): bundle pure-Perl
      `Class::MOP` / `Moose`. Reconsider only if the iterative shim
      plateaus.

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
