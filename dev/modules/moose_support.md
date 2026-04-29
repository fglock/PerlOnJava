# Moose Support for PerlOnJava

## Overview

This document tracks the path to passing **477 / 478** Moose 2.4000 test
files (everything except `t/todo_tests/moose_and_threads.t`, which is
already TODO upstream — PerlOnJava does not implement `threads`).

Two complementary tracks:

1. **Phases 3 → 6** — incremental shim widening (Moose-as-Moo). Ships
   value to Moose-using CPAN modules without bundling upstream Moose.
2. **Phase D** — bundle the upstream pure-Perl `Moose` distribution
   plus `Class::MOP::PurePerl` (~500 lines that replace the 710 lines
   of XS / `mop.c`). This is the phase that gets us to 477 / 478.

### Out of scope

- `t/todo_tests/moose_and_threads.t` (uses Perl `threads`; already
  TODO upstream). Skipped via distroprefs in Phase D.
- `fork` semantics — zero Moose tests use `fork`.
- Real JVM-level class generation (Byte Buddy / Javassist / extra ASM).
  `Class::MOP` operates on Perl stashes, not `java.lang.Class`.

### Already covered in core PerlOnJava

(Listed because they were "blockers" in earlier revisions and no longer are.)

- `weaken` / `isweak` — cooperative refcount on top of JVM GC.
  See `dev/architecture/weaken-destroy.md`.
- `DESTROY` / `DEMOLISH` — fires deterministically for tracked
  blessed objects.
- `B` module subroutine name/stash introspection
  (`RuntimeCode.subName` / `packageName`, surfaced via
  `B::CV` / `B::GV`). This was the original Phase 1 prerequisite.

### Current dependency status

`Class::MOP` and `Moose` are the only missing pieces; all runtime
prereqs work today: `Try::Tiny`, `Module::Runtime`,
`Devel::GlobalDestruction`, `Devel::StackTrace`, `Devel::OverloadInfo`,
`Sub::Exporter`, `Sub::Install`, `Sub::Identify`, `Data::OptList`,
`Class::Load`, `Package::Stash`, `Eval::Closure`, `Params::Util`,
`B::Hooks::EndOfScope`, `Package::DeprecationManager`,
`Dist::CheckConflicts`, `MRO::Compat`. `ExtUtils::HasCompiler`
deterministically returns false (Phase A stub at
`src/main/perl/lib/ExtUtils/HasCompiler.pm`).

---

## Phase plan summary

### Done

- **Phase 1** — B-module subroutine introspection.
- **Quick path** — Moose-as-Moo shim (`Moose.pm`, `Moose::Role`,
  `Moose::Object`, `Moose::Util::TypeConstraints`).
- **Phase A** — `ExtUtils::HasCompiler` deterministic stub.
- **Phase C-mini** — `Class::MOP` shim (`class_of`, `get_metaclass_by_name`,
  `get_code_info`, `is_class_loaded`, …).
- **Phase 2 / Phase 3 stubs** — `metaclass.pm`, `Test::Moose`,
  `Moose::Util`, rich `Moose::_FakeMeta` (with `@ISA` = (`Class::MOP::Class`,
  `Moose::Meta::Class`) so `isa_ok` passes), per-class meta cache,
  attribute tracking via `has` wrapper, plus skeleton stubs for
  `Class::MOP::{Class,Attribute,Method,Method::Accessor,Instance,Package}`,
  `Moose::Meta::{Class,Method,Attribute,Role,Role::Composite,
  TypeConstraint,TypeConstraint::Enum,TypeConstraint::Parameterized,
  Role::Application::RoleSummation}`, `Moose::Util::MetaRole` (with
  `apply_metaroles` no-op), `Moose::Exception` (overloaded stringify
  + `throw`), `Moose::Exporter`. Pre-populated standard type-constraint
  stubs (`Any`, `Item`, `Defined`, `Bool`, `Str`, `Num`, `Int`, `ArrayRef`,
  `HashRef`, `Object`, …) so `t/type_constraints/util_std_type_constraints.t`
  doesn't `BAIL_OUT`. Added `find_or_parse_type_constraint` (handles
  `Maybe[Foo]`, `Foo|Bar`, `ArrayRef[Foo]`, `HashRef[Foo]`,
  `ScalarRef[Foo]`), `export_type_constraints_as_functions`,
  `Moose::Meta::Role->create_anon_role`. Pre-loading
  `use Class::MOP ()` in `Moose.pm` / `Moose/Role.pm` so Moo's runtime
  probes for `Class::MOP::class_of` always resolve.

### Not started

- **Phase 4** — hook `Moose::_FakeMeta->get_attribute*` into Moo's
  attribute store via `Moo->_constructor_maker_for($class)->all_attribute_specs`.
- **Phase 5** — `Moose::Util::MetaRole::apply_metaroles` real-ish apply.
- **Phase 6** — full `Moose::Exporter` sugar installation.
- **Phase D** — bundle upstream Moose + `Class::MOP::PurePerl`.
  See "Phase D plan" below.

### Deferred

- **Phase B** — strip `OBJECT`/`XS`/`C`/`H`/`XSPROTOARG`/`XSOPT` keys
  in `WriteMakefile`. Not on the Moose critical path; bundled Moose
  ships from the JAR.
- **Phase E** — export-flag MAGIC for `Moose::Exporter` re-export
  tracking. Affects warnings only.

---

## Lessons learned (do NOT repeat these mistakes)

### Core-runtime fixes that were attempted and reverted

1. **`*GLOB{SCALAR}` returning a fresh `\$value` reference** (commit
   `880bf65c7`, reverted in `3d02203dc`). Motivation: Class::Load::PP
   does `${ *{...}{SCALAR} }` and our impl returned a copy. The "fix"
   silently broke Path::Class (and DBIC by extension) because
   Path::Class's overload code does `*$sym = \&nil; $$sym = $arg{$_};`
   — assignments through the glob-deref expect to land on the
   package's actual SV slot, not a throwaway reference.
   **Lesson**: any change to typeglob slot semantics must be validated
   against the full DBIC suite, which exercises Path::Class heavily.
   The eventual real fix was different (see below: PerlOnJava's actual
   `*x{SCALAR}` bug returned the value instead of a SCALAR ref;
   `RuntimeGlob.java` line 554-565 now calls `createReference()` like
   the ARRAY/HASH/GLOB cases). Regression test in
   `src/test/resources/unit/typeglob.t`.

2. **Auto-sweep weaken / walker-gated destroy, version 1**
   (commits `ca3af1ad3` + `ecb5c6400`, reverted in `f8ef367e4` /
   `d3743a11c`). Motivation: Class::MOP bootstrap died because the
   metaclass was being destroyed mid-construction. The "fix" coupled
   destroy timing to the reachability walker's view of refcount. It
   passed targeted refcount unit tests but introduced regressions in
   DBIC's `t/52leaks.t` that the unit tests didn't catch.
   **Lesson on measurement**: running partial DBIC subsets and
   treating "fast-fail at compile time" as "no regression" is wrong.
   The correct gate is the full `./jcpan --jobs 1 -t DBIx::Class`
   (~24 min, 314 files / ~13858 assertions).

3. **"Skip destroy when weak refs exist" guard in `MortalList.flush()`**
   (D-W3 step W3, attempt 1). Broke 5+ existing weaken/destroy unit
   tests (`weaken_destroy.t`, `weaken_edge_cases.t`, `weaken_basic.t`,
   `destroy_anon_containers.t`, `weaken_via_sub.t` Case 5). Reverted.

4. **Same guard tightened to `blessId != 0 && hasWeakRefsTo`** (D-W3
   step W3, attempt 2). Still broke cycle-breaking-via-weaken tests
   because cycles of blessed objects rely on DESTROY firing when the
   last external strong ref disappears. With the guard, the cycle
   stays alive forever. Reverted.
   **Lesson**: there is no simple predicate at the destroy gate that
   distinguishes "transient refCount drift during heavy ref shuffling"
   from "genuine end-of-life with weak refs about to clear". The fix
   has to live in the **accounting**, not at the gate.

5. **Class-name-restricted walker gate** (PR #572, commit `0c90da3fe`,
   D-W2c). Restricted the walker gate to objects blessed into
   `Class::MOP*` / `Moose*` / `Moo*`. Made DBIC + Moose both pass, but
   violated the "language behaves the same regardless of which module
   you use" rule. Replaced by the universal walker (commit `2f5490771`,
   D-W5), then both replaced by D-W6 (see below).

6. **`globalOnly=true` walker gate** (commit `d769faceb`, D-W5
   intermediate). Skipped my-var seeding so only package-global
   reachability counted. Major Moose regression: anonymous metaclasses
   are held *strongly* only via my-vars and *weakly* in `our %METAS`
   — without my-var seeding the walker thought they were unreachable
   and DESTROY fired. `t/type_constraints/util_std_type_constraints.t`
   alone went from 0 to 556 failing assertions. Replaced.

7. **Universal walker gate** (commit `2f5490771`, D-W5 final).
   Removes the class-name list. Strictly improves Moose vs the
   heuristic but four DBIC patterns regress because the walker is
   "too correct":
   - `t/cdbi/04-lazy.t` test 11 (`_attribute_exists('opop')`) — a
     transient blessed object's DESTROY fires during DBIC's
     `_construct_results` row-build path and takes column data with it.
   - `t/storage/txn_scope_guard.t` test 18 — relies on Perl 5's exact
     refcount timing (`@DB::args` capture creates a second strong ref
     after the first DESTROY fires, triggering a second DESTROY +
     warning). The walker sees the capture and defers the first
     DESTROY, so only one fires.
   - `t/52leaks.t` — relies on self-referential cycles **leaking**
     (Perl 5 cannot collect cycles). Our walker collects them, so
     `$r` is undef when the test tries to register it.
   - `util_std_type_constraints.t` "no plan" tail.
   The walker gate is the wrong abstraction: it either over-protects
   (broken cycle break) or under-protects (broken DBIC bootstrap).
   **D-W6 below is the principled replacement.**

### Empirical comparison of the gate variants (D-W5)

| Discriminator | DBIC files / asserts failed | Moose files / asserts failed |
|---|---|---|
| Class-name heuristic (PR #572 baseline) | **0 / 0** ✅ | 82 / 137 |
| No gate at all | 7 / 2 ❌ | 77 / 134 |
| `isReachableFromRoots(target, globalOnly=true)` | 3 / 1 ❌ | 63 / 691 |
| Universal walker (default seeding) | 4 / 2 | **61 / 133** ✅ |

### Lessons learned about stub design (post-Phase-2)

1. **Compile-time stubs are the highest-leverage move.** Each round
   of "let `require X` succeed" cleared dozens of files at once.
2. **Pre-loading is as important as having the stub.** Once
   `Moose.pm` set `$INC{Moose.pm}`, Moo's runtime probes called
   `Class::MOP::class_of` from random call sites; without
   `use Class::MOP ()` at the top of `Moose.pm` / `Moose/Role.pm`
   ~50+ runtime errors mask any further shim widening.
3. **One `BAIL_OUT` can hide an arbitrary number of trailing test
   files** (alphabetical order). `util_std_type_constraints.t`'s
   `BAIL_OUT("No such type ...")` was costing ~7 trailing files per
   run. Pre-populating standard type-constraint stubs contained it.
4. **Stub objects must `isa` the right things.** `isa_ok($meta,
   'Moose::Meta::Class')` etc. need the stub's `@ISA` set to the
   real upstream class names; a plain blessed hashref isn't enough.
5. **Bless return values, don't return raw hashrefs.**
   `Moose::Util::TypeConstraints::_store` originally returned
   unblessed hashrefs and produced "Can't call method 'check' on
   unblessed reference" failures. Now blesses into `_Stub`
   (`@ISA` = `Moose::Meta::TypeConstraint`).
6. **The Moose-as-Moo gap is mostly method surface, not metaclass
   semantics.** Most upstream tests want `$meta->add_attribute`,
   `$meta->get_method`, `$meta->is_mutable` to exist and return
   sensible-shaped values; they rarely care that the metaclass is
   "real". Enriching `Moose::_FakeMeta` is high-leverage.

---

## Lock in progress as bundled-module tests

`src/test/resources/module/{Distribution}/` is reserved for
**unmodified upstream test files** of CPAN distributions we actually
bundle. Use it only when both apply:

1. The distribution itself is bundled (its `.pm` files live in
   `src/main/perl/lib/`, or the test directory ships its own `lib/`).
2. The tests being copied are the upstream tests for **that** distribution.

Conventions:
- One directory per CPAN distribution (`Moose/`, `Class-MOP/`, …);
  use the dist name with `::` replaced by `-`.
- Mirror upstream `t/` exactly. Don't edit the test files; if a test
  is incompatible, fix the runtime per AGENTS.md.
- Tests are picked up automatically by Gradle's `testModule` task.
- Verify with `make test-bundled-modules`.

For any "test against shim, don't install" scenario, define a CPAN
distroprefs entry that overrides `pl` / `make` / `install` with no-ops
and `test` with `prove --exec jperl ...`. The current Moose distropref
is shipped from `src/main/perl/lib/CPAN/Config.pm`
(auto-bootstrapped to `~/.perlonjava/cpan/prefs/Moose.yml`). It:
- ensures `Moo` is installed before testing (the shim delegates to
  Moo) via a Perl helper
  `PerlOnJava::Distroprefs::Moose::bootstrap_pl_phase`;
- creates a stub `Makefile` (CPAN.pm's "no Makefile created" fallback);
- skips `make` and `install` (`PerlOnJava::Distroprefs::Moose::noop`);
- runs `prove --exec jperl -r t/`.

`jcpan` / `jcpan.bat` prepend the project directory to `PATH` and
export `JCPAN_BIN` so subprocesses on Unix and Windows find `jperl`
and can recursively invoke jcpan. The design avoids POSIX-only shell
constructs (`||`, `;`, `touch`, `/dev/null`, `$VAR`) that don't work
in `cmd.exe`. Each phase is a single
`jperl -MPerlOnJava::Distroprefs::Moose -e '...'` invocation.

We deliberately avoid a CPAN `depends:` block — it would force CPAN
to resolve Moose's full upstream prereq tree
(`Package::Stash::XS`, `MooseX::NonMoose`, …), most of which is XS
and unsatisfiable.

Because `prove --exec` invokes `jperl` per test file without adding
`lib/` or `blib/lib/` to `@INC`, the **bundled shim from the jar**
wins over the unpacked upstream `lib/Moose.pm`.

---

## Phase D plan

### D1 — Bundle upstream `.pm` files

Drop `Moose-2.4000/lib/{Class/MOP*,Moose*,metaclass.pm,Test/Moose.pm,
oose.pm}` into `src/main/perl/lib/`, replacing the shim files. Also
snapshot upstream `t/` into `src/test/resources/module/Moose/t/` for
regression coverage (per AGENTS.md). Effort: ~½ day.

### D2 — Patch `Class::MOP.pm` to skip `XSLoader::load`

Upstream `Class::MOP.pm` line 31 has an unconditional
`XSLoader::load('Moose', $VERSION)`. PerlOnJava fails with
"Can't load shared library on this platform". Replace with:

```perl
if ($ENV{MOOSE_PUREPERL} || !$Config{usedl}) {
    require Class::MOP::PurePerl;
}
else {
    XSLoader::load('Moose', $VERSION);
}
```

PerlOnJava's `$Config{usedl}` is empty, so this routes to PurePerl.
This is the only modification to upstream Moose code. Document it
prominently so future sync-ups don't drop it. Effort: ~½ day.

### D3 — Implement `Class::MOP::PurePerl`

The XS provides accessor methods on a handful of mixin classes; none
of them do anything clever — all read/write hash slots on the
metaclass / attribute / method instances.

| .xs file              | Lines | What it provides |
|-----------------------|-------|------------------|
| `Attribute.xs`        | 9     | BOOT only |
| `AttributeCore.xs`    | 18    | mixin readers (name / accessor / reader / writer / predicate / clearer / builder / init_arg / initializer / definition_context / insertion_order) |
| `Class.xs`            | 12    | BOOT only |
| `Generated.xs`        | 9     | BOOT only |
| `HasAttributes.xs`    | 9     | `_attribute_map` reader |
| `HasMethods.xs`       | 89    | `_method_map`, tied method install |
| `Inlined.xs`          | 8     | BOOT only |
| `Instance.xs`         | 8     | BOOT only |
| `MOP.xs`              | 22    | `is_class_loaded`, `_inline_check_constraint`, … |
| `Method.xs`           | 23    | `body` / `name` / `package_name` accessors |
| `Moose.xs`            | 148   | `Moose::Util::throw_exception_class_callback`, init_meta hooks |
| `Package.xs`          | 8     | BOOT only |
| `ToInstance.xs`       | 63    | `Class::MOP::class_of` fast path |
| `mop.c`               | 284   | `mop_install_simple_accessor`, `mop_class_check`, `mop_check_package_cache_flag` |

Total Perl replacement: < 500 lines, mostly
`sub name { $_[0]->{name} }`-shaped one-liners. Lives in one new
file: `src/main/perl/lib/Class/MOP/PurePerl.pm`. It walks the mixin
packages and installs accessors plus the few non-accessor helpers.
Reference: upstream Moose pre-XS commit `bf38c2e9` shows exactly
which Perl was replaced. Effort: ~3 days.

### D4 — Verify prereqs still load

All Class::MOP runtime prereqs work today (see "Current dependency
status" above). Re-verify with the bundled (vs shim) `Class::MOP`.
Effort: ~½ day.

### D5 — Distroprefs threads-test exclusion

`prove --not` does not exist — known issue. Workaround: a small
`--exec` wrapper that returns `1..0 # SKIP threads not implemented`
for `t/todo_tests/moose_and_threads.t` and runs `jperl` for every
other file. ~10 lines of Perl. Effort: ~10 min.

### D6 — Snapshot tests

Per AGENTS.md, copy `Moose-2.4000/t/` (minus the threads file) into
`src/test/resources/module/Moose/t/` and add it to
`make test-bundled-modules`. Effort: ~½ day.

### Phase D total: ~5 days

**Outcome**: 477 / 478 fully-green files. Anything still failing
after Phase D is a real bug in PerlOnJava core (not in the Moose
port) and gets fixed in core.

### Previous Phase D attempt status

The previous Phase D attempt got as far as bundling the upstream
files in branch `feature/moose-phase-d` (since deleted) and worked
out the D2 XSLoader patch and a D3 skeleton. It was paused on the
walker-gate / refcount issues now superseded by D-W6.

---

## Phase D-W3: Sub::Util / sort BLOCK fixes (DONE)

Two bytecode/runtime bugs found while triaging the Moose plateau.

### D-W3a: `Sub::Util::subname` of anonymous subs

`B::svref_2object($code)->GV->STASH->NAME` (used by
`Class::MOP::get_code_info` and `Class::MOP::Mixin::HasMethods::
_code_is_mine`) returned `main` for any anon sub created in a
non-main package. Real Perl returns the compile-time package
(CvSTASH), recorded on `RuntimeCode.packageName`.

Fix:
- `Sub::Util::subname` returns `Pkg::__ANON__` when the sub has no
  name but a known package.
- Honors the `explicitlyRenamed` flag for `set_subname("", $code)`
  (returns empty string, not `__ANON__`).
- `B.pm`'s `_introspect` accepts `Pkg::__ANON__`, `Pkg::` and bare
  renames.

This unblocks immutable metaclass trait application (the
`Class::MOP::Class::Immutable::Trait::add_method` etc. were being
rejected by `_code_is_mine`).

### D-W3b: sort BLOCK comparator @_

`sort { $_[0]->($a, $b) } @list` (Moose's native Array trait `sort`
accessor) — real Perl exposes the surrounding sub's `@_` inside the
sort BLOCK; PerlOnJava was creating a fresh empty `@_`.

Fix: pass slot 1 (`@_`) to `ListOperators.sort` from the JVM
emitter (`EmitOperator.handleMapOperator`) and the interpreter
(`InlineOpcodeHandler.executeSort`); forward as the comparator's
args (unless the comparator has a `$$` prototype). SORT op
descriptor widened to include `RuntimeArray` (the outer @_).

---

## Phase D-W6: replicate Perl 5 destroy semantics exactly

### Why

The user's directive: *PerlOnJava should not "improve on" Perl 5's
DESTROY semantics; it should match them.* The two remaining DBIC
regressions in D-W5 are exactly the cases where PerlOnJava
"diverges by being more correct":

- **`52leaks.t`**: tests that self-referential cycles **leak**.
  Real Perl 5 cannot collect cycles; an object holding `$self->{me}
  = $self` lives forever even after the user drops their last
  external reference. PerlOnJava's walker currently *detects* the
  cycle and destroys it, which is technically nicer but breaks
  any code that relies on the leak as a feature (the entire
  intentional-leak diagnostic in DBIC's `t/52leaks.t`, plus any
  user code that uses the leak to keep a per-instance state alive
  through "I'll fix this later" cycle constructs).

- **`txn_scope_guard.t`**: tests that DESTROY fires *each* time
  refCount transitions to 0 — including the transient transition
  where a `Devel::StackTrace`-style `@DB::args` capture briefly
  holds the object after the user-visible `undef $g`. Real Perl 5
  fires DESTROY immediately at the first refCount=0, then again
  when the captures are dropped, and DBIC's TxnScopeGuard guards
  against the second one with a warning. PerlOnJava's walker sees
  the capture and defers the first DESTROY, so only one fires
  (no warning).

The right fix is to make cooperative refCount the **single source
of truth** for DESTROY firing, and use the walker only for explicit
`jperl_gc()` calls. Cycles will leak (matching Perl 5). DESTROY
will fire at every refCount=0 transition (matching Perl 5).

### What was wrong with the walker-gate approach

The walker gate exists because cooperative refCount is known to
drift — it transiently drops to 0 in MOP-style code (Moose,
Class::MOP, Moo, DBIx::Class::Schema bootstrap) where blessed
objects bounce through hash slots, closures, and method-modifier
wrappers. The walker absorbs the drift at the cost of incorrect
behaviour for the two patterns above.

The proper long-term fix (already noted in D-W2c "Why this is a
stopgap") is to **find and back-fill the missing refCount
increments at the source**, not paper over them with the walker.

### Concrete plan

#### Step 1 — instrument the refCount-drift sources

For each Moose-bootstrap pattern that currently relies on the
walker gate, identify *where* the cooperative refCount drift
happens. Candidates (from prior D-W investigations):

1. **Hash slot stores in MOP-style code** — e.g.
   `$METAS{$pkg} = $meta`. Does `RuntimeHash.put` always
   increment `$meta`'s refCount before discarding the previous
   slot value? Audit `RuntimeHash.put`, `RuntimeHash.put` via
   `set`, hash-slice stores (`@h{@keys} = @vals`), and the
   special-case paths in `RuntimeScalar.set`.
2. **Closure captures** — `RuntimeCode.capturedScalars`. When a
   blessed scalar is captured by a closure, the refCount must
   reflect that. Today some capture paths use `setLargeRefCounted`
   / `addToCapturedScalars` correctly but others may go through
   `MortalList` deferred-decrement before the capture is
   established.
3. **Argument promotion in method calls** — `@_` is built from
   the caller's arg list. Each scalar in `@_` should hold its
   own refCount on its referent. Audit `RuntimeArray.push`,
   `RuntimeArray.set` for the per-element refCount path.
4. **Glob assignment** — `*Foo::bar = sub {...}` stores a
   blessed CV. Audit `GlobalVariable.setGlobalCodeRef` and the
   typeglob-overlay path in `RuntimeGlob`.
5. **Stash / package-global writes** — `our %FOO = ...`,
   `${"Pkg::var"} = ...`. Audit `GlobalVariable.set*`.

For each source, write a tiny standalone reproducer that drops
the refCount to 0 transiently while a strong reference is still
held. Add it to `src/test/resources/unit/refcount/drift/`.

#### Step 2 — fix each drift source

For each reproducer, add the missing
increment/decrement so cooperative refCount stays consistent.
Verify each with `make` + the reproducer.

#### Step 3 — remove the walker gate

Once Steps 1 and 2 are complete, the walker gate at
`MortalList.flush` and `RuntimeScalar.set` can be removed
entirely:

```java
// Today:
} else if (base.blessId != 0
        && WeakRefRegistry.hasWeakRefsTo(base)
        && ReachabilityWalker.isReachableFromRoots(base)) {
    // defer
}

// After Step 3:
} else {
    base.refCount = Integer.MIN_VALUE;
    DestroyDispatch.callDestroy(base);
}
```

The walker continues to exist for explicit `jperl_gc()` calls
(opt-in cycle collection), but is **not** consulted on every
refCount→0 transition.

#### Step 4 — verify both Perl 5 semantics tests pass

- `52leaks.t`: the test populates `@circreffed` with cycles and
  then weakens them. With cooperative refCount alone, the cycle
  members keep each other alive; weaken doesn't drop a strong
  ref; the cycle leaks; `$r` stays defined; the test runs to
  completion.

- `txn_scope_guard.t` test 18: `undef $g` triggers DESTROY
  immediately (refCount→0). The handler captures @DB::args
  refs, raising the captured object's refCount back to ≥1 after
  DESTROY exits. `@arg_capture = ()` drops to 0 again, firing
  DESTROY a second time. DBIC's `_finalized` guard emits the
  expected warning. The test passes.

#### Step 5 — confirm Moose still works

Run `./jcpan -t Moose` and confirm pass rate is *at least* equal
to the current universal-walker number (61 failing files / 133
asserts). If any Moose tests regress, that points to a
not-yet-fixed drift source (back to Step 1 for that pattern).

### Acceptance criteria

- `MortalList.flush` and `RuntimeScalar.set` contain no walker
  call at all (gate is removed, not replaced with another
  heuristic).
- `52leaks.t` passes (cycles leak as expected).
- `txn_scope_guard.t` test 18 passes (double-DESTROY warning
  fires).
- `cdbi/04-lazy.t` passes (the SELECT result no longer loses
  `opop` because no transient destroy clobbers row construction).
- Moose: ≥396/478 (no regression vs current branch).
- DBIC: 0 / 0 PASS.
- All existing refcount unit tests still pass.

### Risk and rollback

This is a refactor of the cooperative refCount contract, not just
a gate change. Each Step 2 fix should be its own commit so any
regression can be bisected. The walker code itself is left in
place (only the gate call sites are simplified), so reintroducing
the gate as a safety net during debugging is a one-line revert.

### Empirical D-W6 progress (2026-04-29)

#### What removing the gate fixes (commit 230a672dd)

With the gate fully removed and cooperative refCount alone driving
DESTROY, the three DBIC "Perl 5 semantics" tests pass:

| Test | Before D-W6 | After D-W6 |
|---|---|---|
| `t/cdbi/04-lazy.t` test 11 | FAIL | **PASS** |
| `t/storage/txn_scope_guard.t` 18/18 | FAIL | **PASS** |
| `t/52leaks.t` 11/11 | bailed | **PASS** |

The cycle-leak reproducer in `dev/sandbox/refcount_drift.pl` also
confirms cycles leak naturally — cooperative refCount keeps cycle
members at refCount ≥ 1 with no walker, exactly as Perl 5 does.

#### What removing the gate breaks

Moose bootstrap fails: `use Class::MOP::Class` dies with

```
Can't locate object method "initialize" via package "Class::MOP::Class"
  at jar:PERL5LIB/Class/MOP/Mixin.pm line 12.
```

`PJ_DESTROY_TRACE=1 ./jperl -e 'use Class::MOP::Class'` shows ~15
non-blessed `RuntimeCode` objects being destroyed inside
`MortalList.flush` during `MiniTrait::apply`. Their stack traces
all run through `org.perlonjava.anonNNN.apply(.../Class/MOP/MiniTrait.pm:...)`
— i.e. the destroys fire *while* sub-installation is happening on
Class::MOP::Class. By the time the bootstrap reaches
`Class::MOP::Class->initialize(...)` the `initialize` slot is gone
from the package stash.

Confirmed drift source: **named-sub installation**. Patterns like:

```perl
package Class::MOP::Class;
sub initialize { ... }   # <-- the sub object is briefly the only ref to itself
                          #     before the package stash entry is created
```

cause the anonymous CV's cooperative refCount to transient-drop to 0,
and DESTROY fires on a CV that should still be live.

#### Hybrid attempt: only destroy blessed objects

Tried: keep the gate-removal but skip `callDestroy` for non-blessed
objects (since Perl 5 has no DESTROY semantics for non-blessed refs).

Result:
- Moose bootstrap: fixed (subs not destroyed).
- `cmop/numeric_defaults.t`: regressed (12 fails) — anonymous metaclasses
  use blessed objects whose refCount transient-drops to 0, and now they
  ARE destroyed.
- `t/52leaks.t`: regressed (4 fails) — `callDestroy` for non-blessed
  containers does cascade-decrement of contained blessed children;
  skipping it leaks DBIC rows transitively.

So "only destroy blessed objects" is not the right shape either.

#### Status: walker gate restored as safety net

Until the cooperative refCount audit is complete (Step 1 + Step 2 of
the plan above — multi-week effort), the universal walker gate from
PR #599 is restored. PerlOnJava continues to differ from Perl 5 on
the 4 documented DBIC tests, but Moose stays at ≥396/478 and DBIC
stays at 4 known regressions.

The new `PJ_DESTROY_TRACE=1` env-flag in
`DestroyDispatch.callDestroy` is left in place as a debugging aid
for the future audit.

#### Next concrete steps

The audit is structured as three independent sub-phases. Each
sub-phase ships:

1. **A standalone reproducer** in `src/test/resources/unit/refcount/drift/`
   that demonstrates the cooperative refCount drift WITHOUT relying on
   Moose, DBIC, or any large module — just plain Perl.
2. **A bisectable fix commit** that adds the missing increment(s) /
   decrement(s) at the source. The commit subject must follow
   `fix(refcount): drift in <area> — <one-liner>` so `git bisect` can
   target it.
3. **A `git revert`-able guarded removal** of one branch of the
   temporary walker gate condition (or a tightening of the gate) so we
   can prove the fix is sufficient and roll back cheaply if a Moose
   regression appears.
4. **Verification** against the hard regression gate in this doc
   (DBIC = 0/0, Moose ≥ 396/478, refcount unit tests, weaken_via_sub).

The three sub-phases are intentionally orderable; D-W6.1 is most
likely to fix the bulk of the bootstrap failure, the others harden
related paths.

### D-W6.1 — Sub-installation drift (highest ROI)

**Symptom.** `PJ_DESTROY_TRACE=1 ./jperl -e 'use Class::MOP::Class'`
shows ~15 non-blessed `RuntimeCode` objects being destroyed inside
`MortalList.flush` during `MiniTrait::apply`. By the time the
bootstrap calls `Class::MOP::Class->initialize(...)` the
`initialize` slot is gone from the package stash.

**Hypothesis.** `*Pkg::name = sub { ... }` (and the equivalent
`sub Pkg::name { ... }` named-sub form) creates an anonymous CV in a
temporary scalar, then assigns it into the package stash slot. The
temporary scalar's refCount drops to 0 BEFORE the stash slot
increments the CV's refCount, so the CV is briefly the only
referent of itself, and it gets pushed into the deferred-decrement
list.

**Audit targets.**

- `BytecodeCompiler.compileSubroutine` (or `compileNamedSub` if it
  exists) — the bytecode emitted between sub-creation and
  glob-assignment. Look for:
  - The `RuntimeScalar` holding the new CV: where does it get its
    refCount=1? Is `setLargeRefCounted` called on it?
  - The glob-assignment opcode: does the receiving glob slot
    INCREMENT the CV's refCount before the stack-temp is popped?
- `GlobalVariable.defineGlobalCodeRef` — the first time a sub is
  installed for a key, we create a fresh `RuntimeScalar` slot. Does
  that slot's `value` field hold a refCount on the CV?
- `RuntimeGlob.setSlot` (the `*foo = $coderef` path) — same
  question, plus: when overwriting an old slot value, does the
  decrement happen AFTER the increment of the new value? Off-by-one
  here causes refCount=0 transient.

**Reproducer skeleton** (`drift/sub_install.t`):

```perl
use strict;
use warnings;
use Test::More;

# Recreate Class::MOP's bootstrap pattern: install several subs
# into a brand-new package, observe whether any are destroyed
# before the package stash holds them.
my $destroyed_count = 0;
my $bind_destroy = sub {
    my $cv = shift;
    bless $cv, 'TmpCVProbe';   # bless so DESTROY fires observably
    no strict 'refs';
    *{"TmpCVProbe::DESTROY"} = sub { $destroyed_count++ };
    $cv;
};

# Pattern A: glob assignment with anonymous sub
my $sub_a = sub { 42 };
$bind_destroy->($sub_a);
{
    no strict 'refs';
    *{"TestPkg::method_a"} = $sub_a;
}
# After the glob assignment, $sub_a is held by both the stash AND
# this lexical. Drop $sub_a:
$sub_a = undef;
ok exists &TestPkg::method_a, 'glob-assigned sub still in stash';
is TestPkg->method_a, 42, 'glob-assigned sub still callable';

# Pattern B: named sub
package TestPkg;
sub method_b { 17 }
package main;
ok exists &TestPkg::method_b, 'named sub in stash';

# Pattern C: many subs in a loop (mimics MiniTrait::apply)
my @CVs;
for my $i (1..50) {
    my $cv = sub { $i };
    no strict 'refs';
    *{"TestPkg::loop_$i"} = $cv;
    push @CVs, \$cv;        # capture into an outer array
}
my $missing = 0;
for my $i (1..50) {
    no strict 'refs';
    $missing++ unless exists &{"TestPkg::loop_$i"};
}
is $missing, 0, 'all 50 loop-installed subs survive in stash';

is $destroyed_count, 0, 'no installed sub was prematurely destroyed';
done_testing;
```

**Fix shape.** When the glob slot accepts a new CV, do this in order:

1. `newCv.refCount++`
2. `oldSlotValue = slot.value;`
3. `slot.value = newCv;`
4. `oldSlotValue.refCount--; if (oldSlotValue.refCount == 0) destroy(oldSlotValue);`

The current code may be doing 4 before 1 (or relying on the caller's
temp-scalar to keep `newCv` alive across step 4). Fix is to make
step 1 unconditional inside the slot store.

**Acceptance.** `drift/sub_install.t` passes. After applying the
fix, removing the gate clause in `MortalList.flush` makes
`./jperl -e 'use Class::MOP::Class'` succeed (currently it fails).
At that point the gate clause for non-`hasWeakRefsTo` paths can be
deleted.

### D-W6.2 — Closure-capture drift

**Symptom.** Closures returned by `Class::MOP::Method::Generated::sub`
(and the equivalent in Moose's accessor inliner) sometimes lose
their captured `$self`/`$attr` references after the outer scope
exits. The walker masks this today.

**Hypothesis.** When a closure is created from a closure prototype
(see `RuntimeCode.cloneForClosure`), the captured scalars are
copied into the new code object's `capturedScalars` array. If the
copy doesn't increment the captured scalars' referents' refCounts,
the cooperative refCount stays at the original (now wrong) value.
When a captured RuntimeScalar's refCount hits 0 — possibly via a
deferred decrement from some unrelated scope — its referent dies
out from under the closure.

**Audit targets.**

- `RuntimeCode.cloneForClosure` — copies of `capturedScalars`
  must call `setLargeRefCounted` on each capture so its referent
  refCount tracks the closure's lifetime, not the prototype's.
- `EmitClosure` (bytecode side) — the per-closure-creation opcodes
  that snapshot the lexical environment. `addToCapturedScalars`
  must be the increment-aware path; any direct `add()` or array
  append bypassing it is a bug.
- `RuntimeCode.releaseCaptures` — when the closure itself dies, we
  decrement the captures. The pairing must be exact: every
  `addToCapturedScalars` needs a matching `releaseCaptures` entry.

**Reproducer skeleton** (`drift/closure_capture.t`):

```perl
use strict;
use warnings;
use Test::More;

my $destroy_count = 0;
package Probe;
sub new { bless { id => ++$Probe::N, alive => 1 }, shift }
sub DESTROY { $destroy_count++ }

package main;

# A closure that captures a blessed object. The outer scope should
# keep the object alive AS LONG AS the closure is reachable.
sub make_closure {
    my $obj = Probe->new;
    return sub { $obj->{id} };
}

my $c = make_closure();
# At this point, $obj has gone out of make_closure's scope, but
# the closure $c is the only strong holder of it. Refcount must
# be 1 (held by closure capture).
is $destroy_count, 0, 'object alive while closure is reachable';
ok defined eval { $c->() }, 'closure call still works';

undef $c;
# Now the closure is gone, so the captured object should die.
is $destroy_count, 1, 'object destroyed after closure released';

# Pattern B: closure prototype reused in a loop (Moose accessor
# inliner does this for every attribute).
my @closures;
my @ids;
for (1..10) {
    push @closures, make_closure();
}
my $alive = 0;
for my $cl (@closures) { $alive++ if defined eval { $cl->() } }
is $alive, 10, 'all 10 captured objects survive in their closures';

@closures = ();
is $destroy_count, 11, 'all captures released exactly once';

done_testing;
```

**Fix shape.** Inside `cloneForClosure`:

```java
RuntimeCode clone = new RuntimeCode(...);
clone.capturedScalars = new ArrayList<>(this.capturedScalars.size());
for (RuntimeScalar capture : this.capturedScalars) {
    addToCapturedScalars(clone, capture);   // bumps refCount
}
```

NOT a bare `clone.capturedScalars = new ArrayList<>(this.capturedScalars)`
which copies references but skips the refCount math.

**Acceptance.** `drift/closure_capture.t` passes. Then we can
remove a second branch of the gate condition (the
`hasWeakRefsTo`-on-closure path).

### D-W6.3 — `@_` argument-promotion drift

**Symptom.** Subroutines that pass blessed objects through `@_`,
shift them into a `my $self = shift`, then return — the blessed
object's refCount sometimes transient-drops to 0 inside the
callee, not at the caller's actual scope-exit. DBIC method chains
(`$rs->find(1)->update({...})`) are the canonical pattern.

**Hypothesis.** When `@_` is built at call time, each argument
scalar should hold a refCount on its referent. When `shift @_`
moves a scalar into a `my` slot, the `@_` element loses its slot
but the `my` slot picks up the refCount. If either side mishandles
the increment/decrement, refCount drifts.

**Audit targets.**

- `RuntimeCode.apply` (the call-site path that builds `@_`):
  every push into `RuntimeArray @_` must `setLargeRefCounted` the
  referent. Especially the `getList()` flattening path, which
  is known to be the source of several edge-case bugs.
- `RuntimeArray.shift` and `RuntimeArray.set`: the cooperative
  decrement-on-removal must happen.
- The named-arg path used by `method` keyword and Moose's
  inlined constructors.

**Reproducer skeleton** (`drift/at_underscore.t`):

```perl
use strict;
use warnings;
use Test::More;

my $destroy_count = 0;
package Probe;
sub new { bless { id => ++$Probe::N }, shift }
sub DESTROY { $destroy_count++ }

package main;

# Pattern A: object passed through @_ and shifted out
sub identity { my $x = shift; return $x }

{
    my $obj = Probe->new;
    my $back = identity($obj);
    is $destroy_count, 0, 'no destroy across @_ → my-var transfer';
    is $back->{id}, $obj->{id}, 'roundtrip object identity';
}
is $destroy_count, 1, 'destroyed once at outer scope exit';

# Pattern B: chained method call (DBIC-style)
$destroy_count = 0;
package Chain;
sub new { bless { inner => Probe->new }, shift }
sub get { return $_[0]->{inner} }
sub touch { return $_[0] }   # passes object through @_

package main;
{
    my $c = Chain->new;
    my $r = $c->touch->get;
    is $destroy_count, 0, 'no destroy in chained method call';
    is $r->{id}, $c->{inner}{id}, 'chain returns expected object';
}
is $destroy_count, 1, 'inner Probe destroyed once at outer scope exit';

# Pattern C: stress — many calls in a loop
$destroy_count = 0;
{
    my @objs = map { Probe->new } 1..100;
    my @out = map { identity($_) } @objs;
    is scalar @out, 100, 'all 100 round-tripped';
    is $destroy_count, 0, 'no destroy mid-loop';
}
is $destroy_count, 100, 'all 100 destroyed at outer scope exit';

done_testing;
```

**Fix shape.** Audit `RuntimeCode.apply`'s `@_` build path
(approximately):

```java
// Today (suspect): may push without setLargeRefCounted
for (RuntimeScalar arg : args) {
    underscore.elements.add(arg);
}

// After fix:
for (RuntimeScalar arg : args) {
    RuntimeScalar slot = new RuntimeScalar();
    slot.set(arg);                     // increments refCount of arg.value
    underscore.elements.add(slot);
}
```

Or, if the existing code already does this, find where the
*decrement* on `shift @_` skips the corresponding `set` path.

**Acceptance.** `drift/at_underscore.t` passes. The third (and
likely last) branch of the temporary gate condition can be
removed at this point.

### After all three sub-phases land

Once D-W6.1, D-W6.2, and D-W6.3 are all merged and verified:

1. Delete the `else if (... && hasWeakRefsTo && isReachableFromRoots)`
   branch in `MortalList.flush` and `RuntimeScalar.set`.
2. Confirm DBIC stays at 0/0 and Moose at ≥396/478.
3. Run the three "Perl 5 semantics" tests
   (`cdbi/04-lazy.t`, `txn_scope_guard.t`, `t/52leaks.t`) — all
   should pass.
4. Mark D-W6 as DONE and update this doc with the final landing
   commits.

The `ReachabilityWalker` BFS code stays in place, but is consulted
only by explicit `Internals::jperl_gc()` calls, matching real
Perl's "leak by default; explicit GC available" model.

### Sequencing and effort estimate

- **D-W6.1 (sub install):** highest probability of also fixing the
  `cdbi/04-lazy.t` failure on its own, because that failure is a
  transient destroy of a row-construction CV. Estimate: 2–4
  focused sessions.
- **D-W6.2 (closure capture):** likely to clear the
  `numeric_defaults.t` and `make_mutable.t` regressions that hit
  whenever the gate is loosened. Estimate: 2–3 sessions.
- **D-W6.3 (`@_`):** smallest blast radius; if D-W6.1 and D-W6.2
  go in cleanly, this may already be fine. Otherwise 1–2
  sessions.

Each session must end with the hard regression gate green, even
if that means reverting the in-progress drift fix.

---

## Hard regression gate (every refcount/destroy change)

Per the user's instruction: **"Failing weaken/DESTROY is not
accepted at all."** Every fix MUST be validated against:

```bash
./jcpan --jobs 1 -t DBIx::Class       # 0 failing assertions, ≤2 failed files
./jcpan --jobs 1 -t Moose              # ≥ 396 / 478 fully green
make                                    # full unit suite green
./jperl src/test/resources/unit/refcount/*.t   # all pass
./jperl src/test/resources/unit/weaken_via_sub.t  # 20/20
```

Parallel runs (`./jcpan -t …` without `--jobs 1`) OOM-crash on the
local box for several DBIC tests; that is environmental, not a
DESTROY regression. Always serialise the regression gate with
`--jobs 1`.

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
