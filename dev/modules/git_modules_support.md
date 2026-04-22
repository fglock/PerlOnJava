# Git CPAN Module Support on PerlOnJava

## Goal

Make Git-related CPAN modules (`Git::Wrapper`, `Git::Repository`, and
transitively `System::Command`) pass their test suites on PerlOnJava.

`Git::Raw` is out of scope — it's an XS wrapper around libgit2 and requires
a from-scratch JGit-backed port. See the summary at the end.

## Motivation

Users asked about Git CPAN module support. `./jcpan -t Git::Raw` fails because
Git::Raw is XS-only. `./jcpan -t Git::Wrapper` and `./jcpan -t Git::Repository`
install cleanly (both are pure Perl) but fail at runtime:

- **Git::Wrapper**: 5/57 subtests fail across 2 test files.
- **Git::Repository**: most tests silently skip with
  `fork() not supported on this platform (Java/JVM)` because its core dependency
  `System::Command` uses `fork + exec`.

## Investigation Results

### Root cause #1 — `local $tied_scalar = value` does not dispatch through STORE

This is the **primary bug** blocking `Git::Wrapper`. `File::chdir` exports a
tied scalar `$CWD` whose STORE calls `chdir`. `Git::Wrapper::RUN` uses
`local $CWD = $self->dir` to scope the cwd change around each `git` invocation.

Minimal reproduction:

```perl
package T;
sub TIESCALAR { bless [], shift }
sub FETCH { print "FETCH\n"; $_[0][0] }
sub STORE { print "STORE: $_[1]\n"; $_[0][0] = $_[1] }
package main;
our $x; tie $x, "T";
$x = "direct";             # STORE called — OK
{ local $x = "scoped"; }   # PerlOnJava: NO STORE, NO FETCH.
```

| | Real Perl | PerlOnJava |
|---|---|---|
| `local $x = value` (entering scope) | FETCH → STORE "" → STORE value | (silent; tie is bypassed) |
| Inside scope, reading `$x` | FETCH returns "scoped" | returns "scoped" from plain slot |
| Leaving scope | STORE original | (silent) |

**Consequence for Git::Wrapper**: every `local $CWD = $self->dir` is a no-op,
so every `git` subcommand runs in the process's original cwd (the build
directory, which contains 32 files). `ls_files` returns those 32 files, `add
.` stages them, and so on. This explains all 5 basic.t failures and the
2 path_class.t failures in one go.

**Location**: `src/main/java/org/perlonjava/runtime/runtimetypes/GlobalRuntimeScalar.java`
— `dynamicSaveState()` replaces the slot in `GlobalVariable.globalVariables`
with a fresh untied `GlobalRuntimeScalar`, dropping the `TieScalar` magic.
`dynamicRestoreState()` restores the original slot verbatim.

**Fix direction**:
1. In `dynamicSaveState()`, when the original slot's `value` is a `TieScalar`,
   call `tiedFetch()` to save the current FETCH result, then call `tiedStore()`
   with the empty string (matching real Perl's "clear first" behaviour).
2. Do **not** replace the slot — keep the same tied scalar throughout the
   localized scope so that assignments go through `tiedStore`.
3. In `dynamicRestoreState()`, call `tiedStore()` with the saved FETCH value
   to restore.
4. If instead we keep the "replace slot" approach, the new slot must be a
   freshly-constructed `TieScalar` bound to the same handler object so STORE
   still dispatches. This is trickier because `local` restore then has to
   re-install the original tied scalar.

Strategy (1)+(2)+(3) is closest to real Perl semantics and avoids copying
the tie magic. It's also consistent with how non-global tied scalars would
need to behave.

### Root cause #2 — `System::Command` can't spawn without `fork`

`System::Command::_spawn` is a hand-rolled `pipe + fork + exec` using
`Symbol::gensym`, `pipe`, `fork`, `setpgrp`, `fcntl(F_GETFD)`, and
`exec { $cmd[0] } @cmd`. PerlOnJava has no `fork`, so this path dies.
`Git::Repository` depends on it; its test suite detects the failure and
skips almost every test.

**Fix direction — two options, not mutually exclusive**:

**Option A (preferred): ship a patched `System/Command.pm`** in
`src/main/perl/lib/` that replaces `$_spawn` with a code path using
`IPC::Open3::open3` (already implemented on PerlOnJava via `ProcessBuilder`;
see `dev/modules/ipc_open3_fix.md`). We need:

- `pid`, `stdin`, `stdout`, `stderr` handles that behave like the fork
  versions. `open3` already gives us all four.
- `cwd`, `env` options — implement via `ProcessBuilder` directly? Current
  `IPC::Open3` does not expose cwd/env. We may need a thin Java helper, or
  wrap `open3` with a `chdir ... open3 ... chdir back` shim (env via
  `local %ENV = (%ENV, %override)` is fine).
- `setpgrp` is a no-op on JVM — OK.
- `interactive` mode uses `system`, which already works.
- `System::Command::Reaper` wraps the handles and `waitpid`s on close. Should
  still work since `open3` returns a real PID that `waitpid` understands
  (verified by Git::Wrapper's working test cases — it waitpids on an open3
  PID).

**Option B: upstream-compatible shim** — only monkey-patch `_spawn` without
changing the rest. Less invasive, easier to keep in sync with CPAN.

We'll prototype Option B first (smallest patch), fall back to Option A if
there are integration issues.

### Root cause #3 — `Git::Repository` calls uninitialized values

After System::Command failure, `Git::Repository->new` continues without a
`git_dir` and prints warnings like:

```
Use of uninitialized value in join or string at Git/Repository.pm line 99.
Use of uninitialized value in join or string at Git/Repository.pm line 102.
```

These disappear once System::Command works. Not a PerlOnJava bug — just
downstream fallout.

## Plan

### Phase 1 — Fix `local $tied_scalar = value` (unblocks Git::Wrapper)

1. Reproduce with a minimal unit test under `src/test/resources/unit/` that
   counts STORE/FETCH calls on a tied scalar inside a `local` scope. Ensure
   the test matches the sequence observed in system `perl`:
   `FETCH, STORE "", STORE value, FETCH (inside), STORE original_value`.
2. Modify `GlobalRuntimeScalar.dynamicSaveState/dynamicRestoreState` to
   detect `TieScalar` and route through `tiedFetch`/`tiedStore` instead of
   swapping the slot.
3. Verify the tied magic is preserved inside the scope (assignments still
   dispatch STORE) and restored on exit.
4. Re-run `./jcpan -t Git::Wrapper`. Expect basic.t and path_class.t to
   recover. Confirm with `make` (must stay green).

### Phase 2 — Patch `System::Command` (unblocks Git::Repository)

1. Copy `System/Command.pm` into `src/main/perl/lib/System/Command.pm` as a
   baseline.
2. Rewrite `$_spawn` (and the `MSWin32` special case) to route through
   `IPC::Open3::open3`, handling `cwd`, `env`, and `input` options.
3. Audit `System::Command::Reaper` for fork-specific assumptions. If `waitpid`
   on an open3 PID + `close` on the handles is sufficient, leave it alone.
4. Remove the `fork() not supported` skip guard in `System/Command.pm` (or
   leave untouched if the new path doesn't trigger it).
5. Re-run `./jcpan -t System::Command` first (simpler surface), then
   `./jcpan -t Git::Repository`.
6. Iterate on any remaining issues — e.g., `setpgrp`, `trace`, signal
   handling — but treat these as optional if the happy path passes.

### Phase 3 — Follow-up housekeeping

- Update `docs/FEATURE_MATRIX.md` (or equivalent) noting Git::Wrapper and
  Git::Repository as supported, Git::Raw as unsupported.
- Add a short note to `AGENTS.md` about the `local $tied = ...` fix so
  others know it now works.
- Consider opening issues for any residual `System::Command` options we
  don't bother supporting (e.g., `setpgrp`) so users know what's missing.

## Out of scope — `Git::Raw`

Git::Raw bundles libgit2 + zlib + pcre + http-parser source and requires a
C compiler. PerlOnJava would need a JGit-backed Perl module that
re-implements ~40 classes' worth of Git::Raw's API. Comparable in size to
the Crypt::OpenSSL Bouncy Castle port. Defer until asked.

## Progress Tracking

### Current Status: Phase 1 in progress

### Completed Phases
_(none yet)_

### Next Steps
1. Write unit test for `local $tied_scalar = value`.
2. Fix `GlobalRuntimeScalar.dynamicSaveState/dynamicRestoreState` for
   `TieScalar`.
3. Verify `./jcpan -t Git::Wrapper` reaches 57/57.

### Open Questions
- Does `IPC::Open3::open3` on PerlOnJava honour the parent's cwd at the
  moment `open3` is called? Quick test showed **yes**, it uses the Java
  process's current cwd. Good — that means `chdir + open3 + chdir back` is
  a viable path for `System::Command`'s `cwd` option.
- Do we need a `ProcessBuilder.directory()`/`environment()` helper exposed
  to Perl? Probably not if we can do `local %ENV` and manual chdir.

## Related Docs

- `dev/modules/ipc_open3_fix.md` — prior work on IPC::Open3 / IO::Select.
- `dev/modules/xs_fallback.md` — XS/C handling in MakeMaker.
