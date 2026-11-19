# Pseudo-Fork via Threads (issue #1144)

## Context

Threads (ithreads) are fully implemented in PerlOnJava3. The `snapshotClone()`
/ `snapshotCloneForThread()` infrastructure can clone an entire interpreter
state and run it on a JVM thread (virtual threads are the default carrier via
Java 24) — exactly the machinery needed for pseudo-fork.

The existing fork surface:

- `fork()` — returns `undef`/EAGAIN; test-aware skip only. No runtime isolation.
- `open FH, "-|"` / `open FH, "|-"` without command — two strategies in
  `IOOperator.java` + `ForkOpenState.java`:
  - **Strategy A (exec-intercept):** sets pending state; when `exec @cmd` is
    called, runs the command and throws `ForkOpenCompleteException` to unwind
    the "child" branch. Works for the common `open → if/else → exec` idiom.
    **Keep this.** It is fast and covers the most common pattern.
  - **Strategy B (JVM-replay):** spawns a child JVM with
    `PERLONJAVA_FORK_OPEN_REPLAY=N:PPID`; the child replays the script with
    muted stdout until it reaches the N-th no-command pipe-open.
    **Replace this** with the thread-based fork in Phase 2.

The goal is to implement bare `fork()` and replace Strategy B using the thread
snapshot engine: cleaner, faster, works for `-e` programs, and handles
arbitrary child-side Perl code.

---

## Semantic Contract (from issue #1144)

- `fork()` returns a positive synthetic PID in the parent and `0` in the child.
- Failure returns `undef` and sets `$!`.
- Parent and child **resume immediately after the same `fork` expression**.
- Mutable Perl state is isolated from the snapshot point (fork semantics, not
  ithread `CLONE` semantics — do not invoke `CLONE` hooks for pseudofork).
- `exit` and uncaught termination end only the pseudo-child, record status,
  notify the parent, and **never terminate the host JVM**.
- `wait`, `waitpid`, `WNOHANG`, `$?`, and minimal `SIGCHLD` work for
  pseudo-children.
- `$Config{d_fork}` remains false/undefined; `$Config{d_pseudofork}` is
  defined only when the implementation is enabled.
- Unsupported operations fail explicitly instead of silently sharing unsafe
  process-global state.
- Both JVM and interpreter backends behave identically.

---

## Architecture

### 1. Resumable Fork Continuation

Java cannot clone a running Java stack, so `fork()` must be a
**compiler/runtime control-flow boundary**, not an ordinary function call.

**JVM backend (Phase 1):** Lower scopes containing `fork` to the
**bytecode/interpreter execution path** at compile time. The interpreter can
snapshot its bytecode frame and resume both parent and child at the instruction
following `fork`. This is the pragmatic Phase 1 approach specified in issue
#1144 §1 — no native JVM continuation cloning is needed yet.

**Interpreter backend:** Snapshot the bytecode frame directly; resume parent
with the synthetic PID and child with `0` on a virtual thread.

A later optimization phase may add native JVM continuation lowering without
changing semantics.

### 2. Runtime State Cloning

Build on the existing snapshot infrastructure but use a **fork-specific
clone policy** distinct from the ithread clone policy:

- Use `PerlRuntime.snapshotClone()` / `snapshotCloneWithRoots()` — these
  create the child with its own **fresh `PerlThreadRegistry`** (process
  isolation), unlike `snapshotCloneForThread()` which enrolls the child in
  the parent's thread family.
- Copy: mutable globals, pads, symbol tables, module state, `%ENV`, signal
  handlers, `$!`, `$?`, random state, process metadata.
- Share (read-only): compiled code and safe immutable resources.
- **Do not invoke ithread `CLONE` hooks** unless Perl pseudofork compatibility
  requires it.
- Give the child: synthetic PID, parent PID, lifecycle state, exit status,
  and cancellation token managed by `PseudoProcessManager`.

### 3. External Resource Inheritance

| Resource type | Policy |
|---|---|
| Pipes and loopback sockets | Inherit duplicate Perl handle wrappers over shared, reference-counted endpoints; closing one side must not prematurely close the sibling |
| Listening sockets | Allow controlled parent/child accept patterns (required by live server tests); document shared-accept behavior |
| Regular files | Duplicate logical handle state where safe; document shared file-position behavior |
| DB handles, Java objects, native handles | Reject, detach, or mark unsupported via typed snapshot policies — no accidental shallow copy |
| Event-loop watchers, timers, selectors | Rebuild in child against inherited handles; never share a mutable reactor instance |

Integrates with the existing `RuntimeIO` / scope-exit ownership work.

### 4. Process Operations and Signals

Implement pseudo-process-aware paths that check `PseudoProcessManager` before
delegating to OS process APIs:

- `getpid` / `$$` and `getppid`
- `exit`, child exception termination, status encoding
- `wait` and `waitpid` including `WNOHANG`; `$?` updates
- `kill 0`, cooperative `TERM`/`INT`, pseudoprocess lookup
- `SIGCHLD` delivery at safe Perl execution boundaries

Java cannot force-kill arbitrary threads. `SIGKILL` is cooperative cancellation
unless the child is at a runtime safepoint — document this.

---

## What to Reuse

| Existing asset | Location | Role |
|---|---|---|
| `PerlRuntime.snapshotClone()` / `snapshotCloneWithRoots()` | `PerlRuntime.java:483,488` | Clone with fresh registry (process isolation) |
| `PerlThreadControlBlock` | `runtimetypes/PerlThreadControlBlock.java` | Thread lifecycle carrier; use the `EntryPoint` constructor |
| `PerlThreadRegistry` | `runtimetypes/PerlThreadRegistry.java` | ID allocation pattern, process-exit signaling |
| `ThreadInheritancePolicy` | `runtimetypes/ThreadInheritancePolicy.java` | File-handle fork semantics already defined |
| `PerlThreadExitException` | `runtimetypes/PerlThreadExitException.java` | Terminate child thread cleanly on `exit()` |
| `PerlRuntime.close()` | `runtimetypes/PerlRuntime.java` | Child-exit teardown: alarms, signals, I/O, global destruction |
| `ForkOpenCompleteException` | `runtime/ForkOpenCompleteException.java` | Keep for Strategy A pipe-open control flow |
| `ForkOpenState.PendingForkOpen` machinery | `runtime/ForkOpenState.java` | Keep the pending-state helpers for Strategy A |

---

## Changes Required

### New file: `PseudoProcessManager.java`

Owned by the parent `PerlRuntime`. Responsibilities:

- Allocate synthetic PIDs that do not collide with real OS PIDs.
- Map synthetic PID → `PerlThreadControlBlock`.
- Track parent/child relationships.
- Deliver wait status and `$?` updates.
- Cancel/reap abandoned children on parent runtime shutdown.
- `isForkedChild()` — returns true when `$$` ≠ parent's PID.

Pattern: reuse `PerlThreadRegistry.allocateId()` for the PID counter.

### `SystemOperator.fork()` — `operators/SystemOperator.java:1611`

Replace the EAGAIN/undef stub. For Phase 1 JVM backend, detect that the
calling scope has been lowered to the interpreter path (see Architecture §1)
and delegate to the interpreter fork implementation. For the interpreter
backend:

1. Snapshot the runtime with `snapshotClone()`.
2. Register the child in `PseudoProcessManager`.
3. Set `$$` in child to synthetic PID.
4. Launch child on a virtual thread via `PerlThreadControlBlock` (`EntryPoint`
   constructor).
5. Return synthetic PID to parent; child continues with `0`.

### `SystemOperator.exec()` — `operators/SystemOperator.java:1366`

Add a check at entry:

```java
if (PseudoProcessManager.isForkedChild()) {
    int exitCode = runExternalCommand(flattenedArgs);
    throw new PerlThreadExitException(exitCode);  // terminate child thread only
}
```

### `SystemOperator.wait()` / `waitpid()` — `operators/SystemOperator.java`

- Look up synthetic PID in `PseudoProcessManager`; if found, join the thread,
  collect completion, populate `$?`.
- If not found, fall through to `ProcessHandle.of(pid)` for real OS children.

### `IOOperator.open()` — `operators/IOOperator.java:879`

Phase 2: replace `RuntimeIO.openForkPipe()` (JVM-replay, Strategy B) with
thread-based pseudo-fork:

- `-|`: fork child thread, redirect child STDOUT to `PipedOutputStream`; give
  parent a `PipedInputStream`-backed filehandle.
- `|-`: fork child thread, redirect child STDIN from `PipedInputStream` fed
  by parent's `PipedOutputStream`-backed filehandle.

Strategy A (exec-intercept via `ForkOpenCompleteException`) remains in place
as the fast path for the `open → exec` idiom.

### `RuntimeIO.openForkPipe()` — `runtimetypes/RuntimeIO.java:1347`

Phase 2: replace JVM-replay body with thread-based piped fork.

### `ForkOpenState.java` — `runtime/ForkOpenState.java`

Phase 2 (after thread path proven): remove `currentInvocation()`,
`currentJavaCommand()`, `embeddedRuntimeClasspath()`,
`REPLAY_ENV`/`isReplayProcess()`/`isReplayChildOccurrence()`, and
`activateForkOpenChildStdout()` plumbing. Keep `PendingForkOpen` and
`setPending/getPending/clear/hasPending` for Strategy A.

### `Config.pm` / `d_pseudofork`

Set `$Config{d_pseudofork} = 'define'` when pseudofork is enabled;
`$Config{d_fork}` stays false.

### `AGENTS.md`

Remove `fork` from the Unimplemented Features table once Phase 1 is complete.

---

## Implementation Phases

### Phase 1: Compiler contract and lifecycle (issue §Phase 1)

- Add permanent system-Perl-validated fork regression tests **before** implementation.
- Add the resumable `fork` operation on the interpreter backend; lower
  fork-containing scopes to the interpreter path on the JVM backend.
- Clone pure Perl state via `snapshotClone()`.
- Implement `PseudoProcessManager`, synthetic PID, `exit`, `waitpid`, `$?`,
  and cleanup.
- Support simple no-I/O parent/child programs.
- Advertise `d_pseudofork`.

### Phase 2: Pipes, handles, and signals (issue §Phase 2)

- Replace Strategy B (JVM-replay) with thread-based piped fork.
- Add inherited pipe and loopback-socket ownership.
- Rebuild event-loop watchers in the child.
- Implement `WNOHANG`, minimal `SIGCHLD`, and cooperative `kill`.
- Stress tests: close order, parent-first/child-first exit, failures, orphan cleanup.

### Phase 3: Ecosystem acceptance (issue §Phase 3)

- Pass unchanged Mojolicious `t/mojo/subprocess.t` and related tests.
- Pass Catalyst::Runtime `t/live_fork.t` unchanged.
- Run Mojolicious, Catalyst::Runtime, DBIx::Class acceptance suites.

### Phase 4: Hardening (issue §Phase 4)

- Nested pseudofork policy.
- Additional signal/status parity.
- Performance and leak testing.
- Windows CI coverage.

---

## Limitations (document, do not hide)

1. **No OS-level process isolation**: no privilege, namespace, UID/GID, or
   memory-isolation guarantee. Document clearly.
2. **`SIGKILL` is cooperative**: Java cannot force-kill threads; `kill 9, $pid`
   cancels at the next runtime safepoint.
3. **`exec()` without prior fork terminates the JVM** (unchanged).
4. **JVM backend Phase 1** lowers fork-containing scopes to interpreter path —
   may affect performance of fork-heavy code until Phase 4 optimization.
5. **Working directory and `%ENV` changes** are runtime-local or explicitly
   unsupported (no OS process isolation).

---

## Verification

**Existing tests to keep passing** (`src/test/resources/unit/`):
- `fork_open_no_command.t`, `fork_open_bareword_write.t`,
  `fork_open_wait.t`, `fork_open_output_pump.t`
- `fork_operator_skip.t`, `fork_capability_truthful.t`,
  `fork_fixed_plan_skip.t`

**New project-owned regression tests** (validate on system `perl` first):
- `fork_basic.t` — parent receives PID, child receives `0`, both continue
  after the fork point; `waitpid` / `$?`
- `fork_child_code.t` — child runs arbitrary Perl code including global writes;
  parent sees isolation
- `fork_nested.t` — nested fork with correct ownership rules
- `fork_open_child_perl_code.t` — `open FH, "-|"` where child runs Perl code
  (no exec), replacing the JVM-replay-only scenario

**Ecosystem gate (Phase 3)**:
- Mojolicious `t/mojo/subprocess.t` passes unchanged
- Catalyst::Runtime `t/live_fork.t` passes unchanged

**Build gate**: `nice -n 19 make` passes after each phase.

---

## Related

- Issue: #1144
- Roadmap: `docs/about/roadmap.md` Objective 6 / Fork Emulation
- Related compatibility work: #1115
- Design: `dev/design/concurrency.md` (threads architecture)
- Reference: `docs/reference/threads.md` (update to cover pseudo-fork surface)
