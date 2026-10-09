# Package variable binding: compile-time glob references

Status: design (no implementation yet)
Related: issue #1669 (PadWalker `peek_our`), `Test::Expander` upstream suite

## Problem

PerlOnJava resolves a package variable by name on every access. Perl binds
compiled code to the glob (GV) it was compiled against, so the binding
survives later changes to the symbol table. The difference shows up as soon
as the stash entry is deleted and reinstalled.

Minimal reproduction (blead: `value: x`; PerlOnJava JVM and interpreter:
`value: undef`; strict-visibility check `1` on all three):

```perl
use strict; use warnings;
package Pkg;
our $CLASS = 'x';
package Other;
sub strict_eval($) { local *@; use strict 'vars'; local $SIG{__WARN__} = sub {}; eval shift; return $@ ? 0 : 1 }
package main;
{
    no strict 'refs';
    my $glob = *{"Pkg::CLASS"};
    delete $Pkg::{CLASS};
    my $newglob = \*{"Pkg::CLASS"};
    *$newglob = \$Pkg::CLASS;
}
print "value: ", (defined $Pkg::CLASS ? $Pkg::CLASS : 'undef'), "\n";
```

`Sub::Delete::delete_sub` does exactly this sequence, and `Test::Expander`
calls it on every import, so `$CLASS` is lost on the second import. This is
the cause of the 7 remaining subtest failures in `import.t` (121-126, 128).

## Current model

- Scalars live in the name-keyed map `GlobalVariable.globalVariables`.
- Compiled code asks for the container by name at each access:
  - JVM: `EmitVariable` emits `GlobalVariable.getGlobalVariable(name)` per access.
  - Interpreter: `BytecodeInterpreter` (LOAD/STORE global scalar, around lines
    1110-1132) and `InlineOpcodeHandler` (around lines 1595-1608) resolve by name.
- Stash deletion removes the map entry: `RuntimeStash` (around line 227) calls
  `GlobalVariable.globalVariables.remove(fullKey)`.

Consequence: after a stash delete, any name lookup returns a fresh, empty
container, including lookups from code compiled before the delete.

## Target model

Each package-variable access in compiled code holds a stable reference to the
glob it was compiled against (a `RuntimeGlob`), and reads the glob's current
scalar slot at each access:

- `delete $Pkg::{name}` unlinks the glob from the stash but does not clear its
  slots, so compiled code keeps seeing the value.
- A later lookup by name (new code, or the stash) yields the new glob.
- `*name = \$x` updates the slot of the glob that name currently maps to, as now.

This matches blead, where the op holds the GV.

## Scope

Name-based runtime lookups (about 313 `GlobalVariable.getGlobalVariable`
callers outside the emitters) can stay as they are. They model runtime
symbolic access (`${"Pkg::x"}`), which resolves the current glob by design.

Only the compiled-code access paths change:

- JVM emitters: `EmitVariable` and the other emitter sites that call
  `getGlobalVariable` with a constant name.
- Interpreter: the LOAD/STORE global scalar opcodes and `InlineOpcodeHandler`.
- Stash deletion (`RuntimeStash`): keep the glob's slots when the entry is removed.

## Risks

- Performance: one extra indirection per package-scalar access. The repo has no
  benchmark for this path yet; add a package-scalar-heavy script and time it
  before and after step 3.
- Constant-pool and class-size limits: holding a `RuntimeGlob` per constant
  name adds constants to generated classes. The 255-argument and 64 KB method
  limits apply; large modules must still fall back to the interpreter cleanly.
- `local`, glob aliasing (`*A = *B`), and format/IO globs share the same
  storage; each needs a regression test on both backends.

## Plan

1. **Centralize scalar storage (no behavior change).** Measured surface: the
   scalar map `GlobalVariable.globalVariables` is accessed directly in 123
   places across 18 files, 68 of them writes (put, remove, replace, compute).
   Route every access through accessor methods on `GlobalVariable`. Only after
   this can the map's values be replaced by stable per-name holders.
   Gate with `make`; expect zero behavior change.
2. **Holder infrastructure.** Add a stable per-name holder that owns the
   scalar slot, reached through the accessors. No behavior change yet.
3. **Interpreter.** Switch the LOAD/STORE global scalar opcodes and
   `InlineOpcodeHandler` to the holder. Gate with `make`.
4. **JVM.** Switch `EmitVariable` and the constant-name emitter sites. Gate
   with `make`, then the interpreter-parity suite.
5. **Stash delete.** Keep the glob's slots on `delete $Pkg::{name}`.
6. **Tests.** Add `package_variable_stash_delete.t` (the reproduction above),
   validated on blead first, and check that it fails on the unfixed parent.
7. **Consumer.** Rerun the `Test::Expander` upstream suite; expect `import.t`
   130/130.

## Open questions

- Should the holder be a `RuntimeGlob` directly, or a lighter reference type
  that only exposes the scalar slot?
- Which compiled-code paths besides global scalars also resolve by name
  (arrays and hashes via the same mechanism)? Those need the same change for
  the same reason, but are out of scope for the first milestone.

## Findings from the stash-delete trace (not yet a fix)

Traced `Sub::Delete::delete_sub('CLASS')` on the second import in
`t/Test/Expander/import.t`. The post-delete reinstall line is never reached on
the failing call. The call returns at the first branch:

    ref $stash->{$key} eq 'SCALAR' and delete $stash->{$key}, return;

That is, the stash entry is a bare scalar, not a glob. Blead creates this
form when a glob holds only a scalar slot. PerlOnJava keeps a glob.

Ruled out as the sole cause:
- Strict visibility of the package variable (`strict_eval`) matches on both.
- `Internals::SvREFCNT`: untracked scalars report 1 on PerlOnJava. Counting
  glob-slot aliases makes the aliased probe match blead (2), but the import
  failures are unchanged, so that change was reverted.
- A single `delete_sub` call: blead and PerlOnJava agree on the value.

Smaller repro for the same gap (`/tmp/stash_eval_probe.pl`, not in the repo):
after one `delete_sub('Q::CLASS')`, blead gives `direct=q eval=undef`. The
compiled read keeps the old glob, and a string `eval` of the name resolves
through the stash to a new, empty glob. PerlOnJava gives `direct=q eval=q`,
because both reads go through the one name map. This is the binding gap in
its simplest form, and it is the behavior milestones 2-5 must reproduce.


Open: how a scalar-only stash entry should behave after deletion, and why
blead's compiled `$CLASS` still reads the value after the entry is gone.


## Findings: strict vars visibility (landed)

Perl's `strict vars` accepts an unqualified package scalar only when it was
imported or is lexically declared with `our` in scope. PerlOnJava was more
permissive in two places, now fixed:

- `Variable.java` accepted any scalar whose container existed. It now requires
  the declared/imported flag.
- `OperatorParser` marked every `our` scalar as globally declared. It now only
  materializes the glob.
- Whole-glob assignment (`*A = *B`) now sets the imported flag for the scalar
  slot, as Perl does for imports (File::chdir's `*CWD` export depends on this).

Verified: `strict_vars_our_scope.t` passes 4/4 on the blead oracle and on both
backends; `make` passed.

## Remaining consumer gap (not yet explained)

`import.t` subtests 126 and 128 still fail, and the cause is not the strict
rule above. Tracing the `Sub::Delete` decisions shows the difference is the code
slot of `Test::Expander::CLASS` at the fourth import: system Perl has it, and
PerlOnJava does not, so `Sub::Delete` returns early on PerlOnJava. The cause is
not yet identified. Adding a probe such as `defined &Test::Expander::CLASS` to
the module changes the result, so the probe itself must be made non-invasive
before the trace can be trusted.


## Import rule for strict vars (landed)

Blead imports a package scalar (so `strict vars` accepts it) when a scalar reference
is assigned to its glob from a different package than the glob's own. An alias
assignment inside the glob's own package does not import the name. Verified on the
blead oracle (`/tmp/import_rule.pl` and `glob_alias_import_flag.t`).

PerlOnJava now applies the same rule in `RuntimeGlob`, both for scalar-reference
assignment and for whole-glob assignment, comparing the glob's package with the
running package. An earlier version that imported every alias-group member was
too permissive and is removed.

Tests: `glob_alias_import_flag.t` (4/4, blead and both backends),
`strict_vars_our_scope.t` (4/4). `make` passes. `import.t` remains at 128/130,
so the remaining Sub::Delete divergence is not the import rule.


## Findings: stash entry ref() (landed; consumer not re-measured)

Blead's `ref($Pkg::{name})` depends on the entry's package and kind. Measured on
the blead oracle (`perl5/perl`):

| Entry | Blead `ref` |
|---|---|
| defined sub in `main::` | `CODE` |
| prototyped constant in `main::` | `SCALAR` |
| forward declaration in `main::` | `""` |
| sub or prototyped constant in any other package | `""` |
| constant.pm proxy, any package | `SCALAR` |

Before this change, `RuntimeStashEntry.compactValueForRef()` returned `SCALAR` for
every prototyped constant CV. `Sub::Delete` then took its scalar branch and deleted
`Test::Expander::CLASS` on imports 2 and 3. The same method returned `CODE` for
forward declarations and for sub-only entries outside `main::`. Each rule is now
gated on the entry's package, and the constant.pm rule on
`GlobalVariable.hasGlobalPseudoConstant`.

Residual: a sub compiled in package `Other` but named `main::x` (`sub ::x` or
`sub main::x` inside `package Other`) reports `CODE`, where blead reports `""`.
Blead keys the rule on the compiling package; this code keys it on the entry name.
Matching blead needs the compiling package recorded on `RuntimeCode`.

Tests: `stash_entry_ref.t` passes 9/9 on blead and on both backends. `op/sub.t`
(65 ok, 1 pre-existing TODO) and `op/gv.t` (304/304) match blead. `make` passed
(361 suites, 3284 tests, no failures).

Consumer: see the next section.


## Consumer results after the stash-entry change (2026-10-09)

`t/Test/Expander/import.t` (Test::Expander 2.7.1, clean copy with the debug prints
removed). Dependencies are upstream CPAN releases installed under `/tmp/ser_deps`:
Sub::Delete 1.00003, Importer, Test2::Suite, Test2::Tools::Explain, Scalar::Readonly,
and Const::Fast.

| Build | JVM | Interpreter | System perl |
|---|---|---|---|
| Fixed (this change) | 130/130 | dies after subtest 112 | 130/130 |
| Pre-fix `compactValueForRef` (scratch jar in `/tmp/prefix`) | 128 ok; 126 and 128 fail | dies after subtest 112 | - |

The JVM A/B attributes the two subtests to this change: the same clean `import.t`
and the same dependencies fail exactly 126 and 128 without it.

The interpreter stop was a separate bug, and it is fixed in the next section. It also
happened on the pre-fix build and on the master jar built 2026-10-07, so it predates
this work. `caller` returned the wrong package for a sub compiled by eval STRING
under a `package` statement. `Test2::Util::HashBase` installs `NEGATE` into the
package that calls `import`, so the constant landed in the wrong package and
`Test2::Compare::Pattern` failed under `strict subs`.

Repro (`/tmp/ser_probe/caller_eval.pl`, not in the repo):

    package Lib;
    sub import { my $c = caller; print "into=$c\n" }
    package main;
    my $sub = eval qq{package Pat;\n#line 1 "gen"\nsub { Lib->import('a') }} or die $@;
    $sub->();

System perl and the JVM print `into=Pat`. The interpreter printed `into=Lib`.


## Interpreter caller() package for eval STRING frames (fixed 2026-10-09)

Root cause: when a sub starts executing, `BytecodeInterpreter.execute()` sets the
runtime `currentPackage` to the sub's own package, and nothing restores the caller's
package afterwards. `ExceptionFormatter` then read that single runtime value for any
outer frame whose source is an eval, so an eval-compiled caller reported the callee's
package. Eval top-level code calling into another package was affected the same way.

Fix: each interpreter frame records the package in effect when it is pushed, which is
the call-site package. The record lives in `ExecutionRuntimeState.interpreterCallerPackages`,
parallel to `interpreterFrames`, and is pushed in `InterpreterState.pushFrame` and
`pushEvalFrameForCurrentInterpreter`, and popped in `InterpreterState.pop`. For an
outer eval frame, `ExceptionFormatter` now uses the record of the next inner frame.
The innermost frame and non-eval frames keep their existing rules.

Tests: `interpreter_eval_caller_package.t` (5/5, system perl, blead, JVM, and interpreter;
it failed 4 of 5 on the interpreter before the fix). `op/caller.t` is 126/126 on blead,
JVM, and interpreter. `op/eval.t` has the same 4 `not ok` on blead, JVM, and interpreter
before and after the fix. `make` passed (361 suites, 3285 tests, no failures).

Consumer: `import.t` passes 130/130 on the JVM and on the interpreter, and 130/130 on
system perl.


## Progress Tracking

### Current Status: binding implemented for compiled code; stash-entry ref() matches blead; interpreter caller() fixed; consumer 130/130 on JVM, interpreter, and system perl

Implemented (2026-10-09):
- `ScalarPin` (runtimetypes): a compiled site's binding to a package scalar. It
  reads the live scalar while the name is linked, and keeps the frozen scalar after
  the name leaves its stash.
- Pins are shared per linked name (`GlobalVariable.pinScalar`) and detached by
  stash deletion (`RuntimeStash`, `GlobalVariable.detachScalarPin`).
- Interpreter: `InterpretedCode.scalarPin(nameIdx)`, used by the LOAD/STORE global
  scalar opcodes. Pins are created on first execution.
- JVM: `EmitVariable` loads package scalars through a static pin field declared
  per class and filled on first use. Falls back to the old lookup when no class
  writer is available.
- `EvalStringHandler`: an `our` scalar captured by an eval is resolved by name at
  eval time, so a stale container is not linked back into the stash.

Verified: `package_variable_stash_delete.t` passes 6/6 on the blead oracle and on
both PerlOnJava backends. `make` passed (`BUILD SUCCESSFUL`).

Consumer: `t/Test/Expander/import.t` passes 130/130 on the JVM and on the interpreter,
with the stash-entry ref and interpreter caller fixes in place. The whole upstream
suite (26 files, including `import.t`) passes on the JVM, the interpreter, and system
perl: 189 ok, 0 not ok, exit 0 for every file on each runner. Before those fixes it
was 128/130 on the JVM (126 and 128 failing), and 123 before the binding change.

Remaining divergence (not a binding issue): `Sub::Delete` decides whether to
reinstall a deleted scalar from `Internals::SvREFCNT` and the imported flag. On
PerlOnJava, a repeated `delete_sub` re-links the name in a probe where system Perl
does not, and does not re-link it in the consumer where system Perl does. Both
depend on the reference-count and imported-flag model, which PerlOnJava does not
match yet.

Known approximations:
- A pin is created on first execution, not at compile time. The two differ only
  when a name is deleted between compilation and first execution.
- Namespace-wide deletes (`delete $Pkg::{"Sub::"}`) do not detach pins yet.
- Arrays and hashes still resolve by name.

### Completed Phases
- [x] Binding for compiled scalar access (ScalarPin; JVM and interpreter) (2026-10-09)
  - Consumer import.t 128/130; remaining 126 and 128 come from Sub::Delete reinstall heuristics.
- [x] Milestone 1: centralize scalar storage (2026-10-09)
  - All 101 references outside `GlobalVariable.java` now go through `GlobalVariable.scalarSlots()`.
  - The 32 internal uses inside `GlobalVariable.java` still use the field directly.
  - Behavior unchanged: `make` passed.
- [x] Reproduce the stash-delete binding gap on blead and PerlOnJava (2026-10-09)
  - Repro in this document; strict-visibility check ruled out as the cause.

### Next Steps
1. Decide whether #1669 closes now. Its consumer criterion passes on both backends.
2. Commit the stash-entry fix, the interpreter caller fix, their tests, and the docs,
   and open a PR, after approval.
3. Decide whether to match blead's compiling-package rule for `main::` entries
   (see the residual above).
4. Milestone 2: holder infrastructure (decide RuntimeGlob vs lighter slot type first).
5. Milestone 3: interpreter opcodes switch to the holder.

### Open Questions
- See above.
