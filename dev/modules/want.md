# Want.pm Port Plan

## Overview

`Want` (Robin Houston, CPAN) is a Perl module that exposes much richer
calling-context introspection than the built-in `wantarray`. It is a hard
blocker for several CPAN modules in PerlOnJava — most visibly
`Class::Accessor::Lvalue` and therefore everything downstream of it
(e.g. `ActiveResource`).

This document scopes a port and proposes an incremental path.

## Why we need it

`Class::Accessor::Lvalue` (and its `::Fast` sibling) are accessor
generators that produce subroutines usable on either side of `=`:

```perl
$obj->name              # read
$obj->name = "Frank";   # assign
```

To do that they ask `Want` whether the call site is an lvalue, an
rvalue, or a readonly slot, and emit a clean `croak` for misuse:

```
'main' cannot alter the value of 'baz' on objects of class 'Foo'
```

Without `Want`, the whole chain breaks at `require` time:

```
Can't load loadable object for module Want: no Java XS implementation available
```

Other CPAN modules that depend on Want (incomplete list):

- `Class::Accessor::Lvalue`, `Class::Accessor::Lvalue::Fast`
- `Sub::Curry`
- Various accessor frameworks and DSLs that overload chained method
  calls (`->foo->bar` patterns)
- A long tail of small modules that use `want('LIST')` /
  `want('SCALAR')` for polymorphic returns

For the immediate goal of unblocking `ActiveResource`, only the
LVALUE / RVALUE / ASSIGN paths plus `rreturn` / `lnoreturn` matter.

## What Want actually does

Want is implemented as XS that walks Perl's op tree at the call site
to figure out exactly how the caller is using the return value.

### API surface (full)

| Call                         | Returns true when caller is doing            |
|------------------------------|----------------------------------------------|
| `want('VOID')`               | `foo();` (return value discarded)            |
| `want('SCALAR')`             | `$x = foo();`                                |
| `want('LIST')`               | `@a = foo();` `(...) = foo();`               |
| `want('BOOL')`               | `if (foo())` / `while (foo())` / `!foo()`    |
| `want('COUNT')`              | `scalar(@a = foo())` count context           |
| `want('HASH')`               | `%h = foo();` / `%{ foo() }`                 |
| `want('ARRAY')`              | `@a = foo();` / `@{ foo() }`                 |
| `want('CODE')`               | `&{ foo() }->(...)`                          |
| `want('GLOB')`               | `*{ foo() }`                                 |
| `want('REFSCALAR')`          | `${ foo() }`                                 |
| `want('OBJECT')`             | `foo()->bar(...)`                            |
| `want('OBJECT', 'IO::File')` | …and `bar` belongs to IO::File               |
| `want('CHAIN', N)`           | there are at least N chained method calls    |
| `want('LVALUE')`             | `foo() = ...` (call is on the LHS of `=`)    |
| `want('RVALUE')`             | call is being read, not assigned to          |
| `want('ASSIGN')`             | specifically the LHS of an assignment        |
| `want('COUNT', N)`           | repeated-context variant                     |

Helpers that use the introspection to control the return:

| Call                   | Effect                                                |
|------------------------|-------------------------------------------------------|
| `rreturn(@v)`          | return `@v` as an rvalue regardless of call site      |
| `lnoreturn`            | bail out of an lvalue call without storing anything   |
| `want_ref()`           | return the reftype the caller wants (HASH/ARRAY/...)  |
| `wantref()`            | similar, returns "HASH"/"ARRAY"/... or empty          |

### Why it's hard on PerlOnJava

Want's implementation pokes directly at C-level Perl internals:

- Walks the op tree from `PL_op` upward to find the nearest enclosing
  `OP_ENTERSUB`, `OP_AASSIGN`, `OP_RV2HV`, etc.
- Reads context flags (`G_VOID`, `G_SCALAR`, `G_ARRAY`) from the
  caller's stack frame.
- For `LVALUE`/`ASSIGN`, looks at whether the parent op is a left-hand
  side of `=`/`+=`/`||=`/etc. and whether the function call is in
  `OPf_MOD` modify-context.

PerlOnJava has no op tree at runtime — Perl source is compiled to JVM
bytecode, so there is nothing to walk. The information Want needs has
to be reconstructed from the JVM call frame and from compile-time
information that PerlOnJava chooses to thread through.

## Proposed approach

Three options, in increasing cost:

### Option A — Pure-Perl `Want` shim (MVP)

Ship a hand-written `lib/Want.pm` that implements only the subset
real users hit. Specifically:

- `want('LVALUE')` / `want('RVALUE')` / `want('ASSIGN')`
- `want('LIST')` / `want('SCALAR')` / `want('VOID')` (these can be
  built on top of `wantarray`)
- `rreturn` / `lnoreturn`
- `want('BOOL')` (if cheap)

The hard parts are LVALUE/ASSIGN detection. Two sub-options:

**A1. Hook through PerlOnJava core.** Add a small bit of state in the
runtime that tracks "the current sub call is on the LHS of an `=`"
and expose it to Perl-land via an `Internals::Want::*` helper. The
bytecode emitter for assignments already knows whether the RHS is a
sub call; we add a thread-local or call-frame-local "lvalue context"
flag set by the assignment op and read by `Want.pm`.

**A2. Compile-time pragma.** Use a source filter / AST rewriter so
that calls to known `Want`-using subs get tagged with a context hint.
More invasive, less general.

A1 is the cleaner direction.

**Coverage**: enough for `Class::Accessor::Lvalue::Fast`,
`Class::Accessor::Lvalue`, and most accessor-style users. Not enough
for Want's more exotic chain-walking or `OBJECT('Pkg')` queries.

**Estimated effort**: medium. Maybe ~300 lines split between Java
(the lvalue-context flag) and Perl (the Want shim itself).

### Option B — Java port of a curated subset

Same surface as Option A but implemented natively in Java for
performance and tighter integration. Adds an `XSLoader::load('Want', …)`
target that fronts a Java module.

Better long-term home; a bit more upfront work because we need to
plumb the lvalue/wantarray-extended info through `RuntimeContextType`
and friends.

### Option C — Full Want parity

Implement the entire Want API including OBJECT/CHAIN/REFSCALAR
variants. Requires PerlOnJava to either reconstruct an op-tree-like
structure at compile time or thread enough info through the runtime
to answer all of Want's questions.

Largest effort and the boundary is genuinely fuzzy — some of Want's
behaviour leaks Perl-internals semantics that don't have a clean
translation in a non-op-tree runtime.

## Recommendation

**Land Option A1** as the first step. It is the cheapest
"unblock-real-users" path:

1. Adds a small lvalue-context flag to PerlOnJava's call mechanism.
2. Ships a Pure-Perl `lib/Want.pm` covering LVALUE/RVALUE/ASSIGN/
   LIST/SCALAR/VOID/BOOL and `rreturn`/`lnoreturn`.
3. Targets `Class::Accessor::Lvalue` and `ActiveResource` as the
   acceptance tests.

If subsequent users need `OBJECT`/`CHAIN`/`HASH` introspection, treat
each one as a follow-up that grows the shim incrementally.

## Acceptance tests

The port is "done enough" when:

1. `Class::Accessor::Lvalue` test suite passes (or all failures are
   confined to features Want's full API would support but our shim
   doesn't, and these are documented).
2. `ActiveResource`'s own `t/base.t`, `t/connection.t`, `t/simple.t`
   load without dying at `require Class::Accessor::Lvalue::Fast`.
3. A new `src/test/resources/unit/want_basics.t` exercises:
   - `$x = foo()` — `want('LVALUE')` false, `want('RVALUE')` true
   - `foo() = 42` — `want('LVALUE')` true, `want('ASSIGN')` true
   - `@a = foo()` / `$x = foo()` / `foo()` — list/scalar/void
   - `rreturn(...)` short-circuits and returns scalar even from an
     `@a = foo()` call site
   - `lnoreturn` exits a sub used as `foo() = 42` cleanly

## Implementation checklist

### A1 (proposed)

- [ ] Add a per-call-frame "lvalue target" flag to PerlOnJava's
  call mechanism. Source of truth is the bytecode emitter for
  `OP_AASSIGN` / `OP_SASSIGN` when the LHS resolves to a sub call.
- [ ] Expose `Internals::Want::is_lvalue_call()` (and a couple of
  cousin helpers) from Java.
- [ ] Write `src/main/perl/lib/Want.pm` implementing the public
  API on top of `wantarray` + the new internals helper.
- [ ] Add `src/test/resources/unit/want_basics.t` (regression).
- [ ] Run `Class-Accessor-Lvalue-0.11` test suite under `jperl`,
  iterate until clean.
- [ ] Update `dev/modules/active_resource.md` to mark issue #3
  resolved and re-run the full `jcpan -t ActiveResource` chain.

### Out of scope (for now)

- `want('OBJECT', 'Pkg')` / `want('CHAIN', N)` — call-stack and
  method-resolution introspection; defer until a real user asks.
- `want_ref` / `wantref` — easy to add later but no current consumer.

## Risks / Open Questions

- **LVALUE detection precision**: PerlOnJava already has limited
  lvalue-sub support. We need to make sure the new context flag is
  set for *all* lvalue call sites the bytecode emitter generates,
  not just the obvious ones (e.g. assignment-via-modify ops like
  `+=`, `||=`, list-assign-into-sub).
- **Re-entrancy**: the flag must be associated with the specific
  call frame, not global state — recursion and nested calls must
  not see each other's lvalue context.
- **Interpreter parity**: PerlOnJava has both JVM-bytecode and
  interpreter backends. The lvalue-context flag must work
  identically on both. Good test target for the
  `interpreter-parity` skill.
- **Performance**: setting a flag on every sub call has a cost.
  Worth measuring on the existing benchmarks before/after.

## Progress Tracking

### Current Status: scoping / design

### Completed Steps
- [ ] Design doc reviewed
- [ ] A1 proof-of-concept on a feature branch
- [ ] Class::Accessor::Lvalue passes
- [ ] ActiveResource passes (sans network I/O)
- [ ] Want.pm shim merged

### Open Questions
- A1 vs A2 vs B: confirm A1 (runtime hook + Perl shim) is the right
  starting point.
- Naming: `Internals::Want::*` vs a private `B::*`-style module?

### Related Docs
- `dev/modules/active_resource.md` — the user-visible blocker that
  prompted this plan.
- `dev/architecture/` — PerlOnJava call-frame / lvalue documentation
  (TODO: link specific files once located).
