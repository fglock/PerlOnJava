# `goto &sub` parity handoff

## Objective

Complete Perl-compatible `goto &sub` behavior on the JVM and bytecode
interpreter backends. The acceptance target is every applicable assertion in
`perl5_t/t/op/goto-sub.t`, including argument-frame cleanup and absent typeglob
ARRAY-slot behavior.

## Current findings

The WIP implementation on PR #1205 established that these failures are one
tail-call lifecycle problem, rather than independent diagnostics and reference
counting defects:

- A marker currently validates a named coderef before the abandoning frame has
  cleaned up. A destructor can undefine that target between marker construction
  and dispatch, so lookup must occur after cleanup.
- Releasing or cloning the argument array at the handoff changes Perl's `@_`
  aliasing and destructor lifetime. The call must retain the same argument
  container, while releasing only temporaries owned by the retired frame.
- JVM and interpreter trampolines resolve and clean up markers differently;
  the interpreter can hang on the core test.
- Observing `*_{ARRAY}` must not create an absent ARRAY slot. The current
  glob-array APIs mix observation with auto-vivification.

## Design

### Tail-call marker and dispatch

- Extend the tail-call marker contract to preserve the target identity (named
  symbol identity when available, otherwise the original coderef), source file
  and line, argument container, and argument ownership metadata.
- Do not validate or freshly resolve a named target in the marker constructor.
  After the source frame's cleanup, the dispatcher performs one fresh lookup
  and definedness check. It reports `Goto undefined subroutine &Pkg::name at
  file line N` using the marker location.
- Preserve existing dynamic behavior for anonymous coderefs, glob and string
  references, overload, AUTOLOAD, and eval context. Undefined-target checking
  remains before the eval-scope error, as in Perl.
- Make `RuntimeCode` expose one marker-resolution/dispatch helper. Both the
  JVM trampoline and `BytecodeInterpreter` must call it; remove duplicated WIP
  `tailcall` special cases and direct resolver checks.

### Argument-frame ownership

- Pass the actual current/localized `@_` container through `goto &sub`; never
  replace it with a clone merely to control destruction timing.
- Transfer marker-owned temporary aliases explicitly and release only the
  retired frame's owned temporaries after the handoff. Borrowed aliases and
  localized `*_` arrays remain live for the target exactly as Perl requires.
- Keep the dispatch iterative for chained tail calls and guarantee a marker is
  consumed once, preventing recursive re-entry and the interpreter hang.

### Typeglob ARRAY slots

- Add or use a non-vivifying ARRAY-slot peek for glob-slot reads. It must
  return undef when no slot exists.
- Reserve `getGlobArray` / global-array creation for writes and true array
  dereferences that require auto-vivification.
- Apply this distinction to named, localized, and detached/anonymous globs,
  especially `undef *_` and `local *_` surrounding `goto &sub`.

## Regression coverage

Update or replace the current focused WIP regression with the precise core
shapes and stable project-owned assertions for:

- a destructor that undefines the `goto` target during source-frame cleanup,
  including the exact diagnostic location form;
- destructor ordering across repeated tail calls;
- reification of a missing `$_[0]` passed to `utf8::encode`;
- an absent global ARRAY slot after the dynamic `utf8::encode` case;
- absent ARRAY slots after `undef *_` and after `local *_` followed by a
  `goto`.

Retain coverage for chained tail calls and aliasing so this fix does not
regress existing `goto` argument behavior. Every new or modified Perl test
must pass under system Perl before it is accepted as a regression oracle, then
pass on JVM and interpreter backends. The interpreter test must be timeout
bounded and finish normally.

## Validation and handoff

1. Run focused tests on system Perl, JVM, and interpreter.
2. Run `perl5_t/t/op/goto-sub.t` on both backends; require all applicable
   assertions to pass with no timeout.
3. Run relevant existing `goto`, subroutine, and typeglob tests.
4. Add a terse `docs/about/changelog.md` entry under `## Work in progress`
   covering restored `goto &sub` cleanup, argument-frame, and typeglob-slot
   compatibility.
5. On the final immutable commit, run `make`, inspect its full log, update PR
   #1205, and monitor CI before asking for UAT.

## Progress Tracking

### Current Status: planned handoff (2026-09-01)

### Completed Phases

- [x] Investigation and scope definition (2026-09-01)
  - Identified stale named-target resolution, argument ownership, trampoline
    divergence, and ARRAY-slot vivification as the remaining failure classes.
  - Confirmed the target scope is full applicable `op/goto-sub.t` parity on
    both execution backends.

### Next Steps

1. Define and implement the shared marker-resolution and ownership contract.
2. Add the focused regressions and prove their expected behavior with system
   Perl.
3. Complete dual-backend core validation, full test gate, PR update, CI, and
   UAT handoff.

### Open Questions

- None. Preserve current Perl-compatible dynamic-call behavior while routing
  all tail-call markers through the shared dispatcher.

## Related work

- PR #1205 (current WIP vehicle)
- `perl5_t/t/op/goto-sub.t`
- `.agents/skills/debug-perlonjava/SKILL.md`
