# Perl `fork` Emulation (issue #1144)

**Plan status:** investigation complete; implementation is not started.

This document is the implementation plan for [issue #1144](https://github.com/fglock/PerlOnJava/issues/1144). The issue tracks status and links here for technical decisions. If the issue summary and this plan differ, this plan is authoritative; update both together when the accepted contract changes. Earlier issue comments are design discussion, not a separate implementation specification.

The compatibility reference is Perl's [`perlfork`](https://perldoc.perl.org/perlfork) documentation and, for behavior that documentation leaves implicit, the supported Perl implementation used as the oracle. Unix system Perl validates ordinary Perl-level behavior; Windows Perl must also be used in CI to validate pseudofork-specific behavior. A passing Unix `perl` run alone does not validate the Windows emulation contract.

## Goal and compatibility boundary

Implement `fork()` as a Perl-level pseudo-process on JVMs without OS `fork`. The child runs a cloned Perl interpreter on a Java thread and resumes at the same Perl operation as the parent. Aim for standard Perl behavior at the Perl API boundary, including process IDs, state isolation, `wait`/`waitpid`, exit, and documented handle inheritance.

This does not create an OS process. Pseudo-processes share the host JVM, address space, OS credentials, process limits, and failure domain. Unsupported native or Java resources must be identified and handled explicitly. Do not advertise true process isolation or report `d_fork`.

Perl's `open FH, "-|"` and `open FH, "|-"` forms are tracked separately: they are useful project compatibility extensions, but are not a prerequisite for standard Windows `fork` emulation. Keep the existing exec-intercept strategy and JVM-replay strategy until the general fork continuation is proven; then replace replay as a separate, testable change.

## Perl-visible contract

- Parent and child both resume immediately after the same `fork` operation. The parent receives a nonzero pseudo-process ID; the child receives `0`; failure returns `undef` and sets `$!`.
- The child gets an isolated Perl-level state snapshot. Writes to Perl globals, lexicals, closures, `%ENV`, and the virtual current directory do not change the parent. File and socket endpoints follow explicit inherited-handle rules; regular-file wrappers may share the underlying seek position as documented by Perl.
- `$$`, `getpid`, and `getppid` report the pseudo-process identity consistently. `wait`, `waitpid` (including `WNOHANG`), status in `$?`, `SIGCHLD`, and supported `kill` operations resolve pseudo-process IDs before OS process APIs.
- `exit` ends only the current pseudo-process, runs its normal Perl cleanup, and waits for its outstanding pseudo-children. Match Perl's exception for children signalled with `TERM` to avoid shutdown deadlocks. `POSIX::_exit` ends only the pseudo-process without Perl `END`/`DESTROY` cleanup; manager-owned Java resources still require safe release. Neither operation may halt the host JVM.
- `exec` in a pseudo-child launches an external process, waits for it, then runs the pseudo-child's Perl cleanup and exits with that process's status, matching Perl pseudofork behavior. It does not replace the JVM thread with an OS process. Existing top-level `exec` behavior is a separate contract and must not be described as standard pseudofork compatibility.
- Nested forks are part of the compatibility target. Parent/child ownership, waiting, and shutdown must work recursively before the feature is called generally compatible.
- `$Config{d_fork}` remains false/undefined. Set `$Config{d_pseudofork}` only when the advertised baseline is enabled. Keep library-specific capability checks separate: `HAVE_POSIX_FORK` must remain false, and any pseudo-fork opt-in must be explicit.

Match Windows Perl's behavior for `CLONE`/`CLONE_SKIP` and other thread-related hooks through oracle tests. Do not assume Unix fork rules or the current ithread snapshot policy are correct for this emulation. Any intentional difference must be documented and must not be presented as full compatibility.

## Architecture

### 1. Resumable continuation across the whole call chain

Java cannot clone a running Java stack. The existing `SuspendedInterpreterFrame` for `await` captures one interpreter frame; that alone cannot implement `fork` when nested Perl calls use synchronous Java recursion. The child must be able to return through every Perl caller after the fork point.

Every Perl program may reach `fork` through a loaded module, callback, or runtime-generated code, so there is no sound way to select a non-resumable execution path based on whether a program is expected to fork. All Perl calls must use the explicit heap-resident continuation protocol from the root entry, including main code, subs, callbacks, modules, evals, and lazily compiled code. The interpreter and JVM backend must both implement that protocol; neither may leave a Java caller frame holding Perl continuation state. If an unconverted runtime callback/resource boundary is reached, reject `fork` there with a precise error and keep `d_pseudofork` disabled; do not silently fall back to an unsafe local lowering.

Use the explicit heap-resident Perl call chain described in the continuation subplan. Prototype it before integrating runtime cloning. Preserve instruction positions, operands, lexical pads, call/eval frames, dynamic localization, exception/unwind state, context, regex state, and cleanup scopes. Parent continuation receives the pseudo PID and child continuation receives `0`. Class boundaries can change as evidence accumulates, but the implementation must not depend on cloning a Java stack.

See [Full-Call-Chain Continuations for Pseudofork](fork_continuation.md) for the separate implementation plan, including interpreter/runtime callback boundaries and memory/performance goals.

No fork may occur while another Perl execution is mutating the same runtime unless the implementation can establish a consistent snapshot. Initially, reject such calls deterministically rather than risk deadlock or a torn snapshot.

### 2. Fork-specific runtime snapshot

Add a `ForkSnapshotPolicy` alongside the current ithread snapshot policy. `PerlRuntime.snapshotClone()` is documented and implemented as an ithread snapshot; its fresh registry does not make its hook and resource semantics suitable for fork by itself.

The policy must:

- Clone Perl-owned mutable state and preserve aliases, cycles, closures, weak references, and the captured continuation.
- Share only compiled code and resources proven immutable and safe to share.
- Match the target Perl's `CLONE` and `CLONE_SKIP` behavior as established above; do not reuse ithread behavior by accident.
- Classify every external Java/native resource. Duplicate/adapt it where semantics are supported; otherwise reject or detach it with a clear error. There must be no generic shallow-copy fallback for unknown resources.
- Give each pseudo-process a runtime-local process identity, parent identity, lifecycle, exit status, cancellation state, and child table.

Use `RuntimeGraphCloner` and thread snapshot code as implementation material, not as the fork contract. The clone must preserve process-tree relationships while giving each child independent mutable Perl state.

### 3. Process identity and operations

Create one runtime-local `ProcessIdentity`/`PseudoProcessManager` service and route Perl-visible process operations through it before native or JVM process APIs. Audit at least `$$`, `getpid`, `getppid`, `wait`, `waitpid`, `kill`, `POSIX`, `File::Temp`, syslog, and other code that embeds or queries a host PID. Keep pseudo IDs distinguishable from OS children and respect special `waitpid`/`kill` values such as `-1`, `0`, and negative process-group forms.

Each pseudo-process manager owns its descendants. Completion records must preserve Perl wait-status encoding, be reaped once, and be bounded. `WNOHANG` and minimal `SIGCHLD` delivery at safe Perl execution boundaries are part of the advertised baseline, not optional post-advertisement features. Additional signal behavior and cooperative cancellation can follow later. Do not promise hard `SIGKILL`: Java cannot safely terminate arbitrary threads; document cooperative cancellation limits.

Normal `exit` and `_exit` need separate pseudo-child paths. `_exit` must bypass Perl global destruction while never calling `Runtime.halt` or otherwise terminating unrelated runtimes. Runtime shutdown must define what happens to unreaped descendants, and avoid indefinite waits for children blocked in I/O.

### 4. Resource inheritance and external commands

Use typed inheritance policies integrated with `RuntimeIO` and handle leases:

| Resource | Initial policy |
|---|---|
| Pipes and sockets | Duplicate Perl wrappers over reference-counted endpoints; close in one pseudo-process must not close the other's wrapper. Test EOF and close order. |
| Regular files | Duplicate logical wrappers where supported; document shared seek-position behavior when the underlying channel is shared. |
| Listening sockets | Support only defined accept patterns; test shared-accept behavior and shutdown. |
| Event loops, selectors, timers | Rebuild in the child against inherited handles; never share mutable reactor state. |
| DB handles, Java/native handles, locks, unknown resources | Explicitly adapt, detach, or reject; never shallow-copy silently. |

For pseudo-child `exec`, use `ProcessBuilder` or the existing process launcher, associate the real process with the pseudo-child record, wait for it, propagate its status, then terminate the pseudo-child. Audit stdin/stdout/stderr inheritance and failure cleanup. `open "-|"`/`"|-"` may later reuse the continuation and handle machinery, but Strategy A and B stay intact until that path has its own regression evidence.

## Implementation sequence

### Phase 0: semantic oracle and continuation spike

- Record behavior from the Perl `perlfork` reference and test on Unix Perl and Windows Perl. Cover IDs, `CLONE`/`CLONE_SKIP`, nested calls, nested forks, `exit`, `_exit`, `exec`, wait status, `%ENV`, virtual `chdir`, and inherited handles.
- Add system-Perl-validated project regressions before implementation; keep Windows-oracle cases in Windows CI.
- Prove that a nested interpreted call chain can fork, return through callers in both branches, preserve localization/exception cleanup, and run destructors/END blocks at the correct time.
- Decide and document the fork-capable whole-program execution mode. No runtime implementation or capability advertisement before this proof.

### Phase 1: core pseudo-processes

- Implement whole-call-chain resumability and `ForkSnapshotPolicy`.
- Implement process identity, pseudo-process tree, synthetic IDs, `fork`, `exit`, `_exit`, `exec`, `wait`, `waitpid`, status, and deterministic shutdown.
- Support nested forks and state isolation. Add typed rejection for unsupported resources and concurrent snapshot attempts.
- Keep `d_pseudofork` off. Phase 1 establishes core lifecycle and snapshot behavior but does not yet provide the full advertised baseline.

### Phase 2: signals and resource inheritance

- Add `WNOHANG`, minimal `SIGCHLD`, supported `kill` behavior, and cooperative cancellation. Only advertise `d_pseudofork` after these and every other baseline gate pass.
- Complete pipes, sockets, filehandle ownership, and loopback-listener behavior with close-order, EOF, failure, and leak tests.
- After the general fork path is proven, replace JVM-replay Strategy B for no-command pipe opens in a separate change; retain Strategy A unless evidence shows it can be safely consolidated.

### Phase 3: compatibility and ecosystem

- Run focused PerlOnJava regressions on JVM and interpreter paths, plus Windows Perl oracle comparisons in CI.
- Test Catalyst `t/live_fork.t` unchanged where its contract fits the supported surface.
- Do **not** require Mojolicious 9.49 subprocess tests to pass unchanged while reporting `$Config{d_pseudofork}`: Mojolicious explicitly rejects that capability. Treat the rejection as expected library policy; do not hide or falsify the Config value to bypass it. Any adapter or upstream capability refinement is a separate compatibility decision.
- Keep `HAVE_POSIX_FORK` false for IO::Async and similar consumers. Add a distinct pseudo-fork opt-in only if an integration explicitly implements its required pipes, lifecycle, cancellation, and command behavior.
- Run broader Mojolicious, Catalyst, and DBIx::Class suites to catch regressions in non-fork operation; classify each remaining failure by actual OS-process requirements.

### Phase 4: hardening

- Stress nested/repeated child creation, parent-first and child-first completion, cancellation, resource close order, and parent shutdown.
- Verify no host-JVM termination, zombie records, leaked handles, retained snapshots, or orphan threads.
- Measure fork-heavy performance and document the execution-mode cost. Consider JVM state-machine optimization only after semantics are stable.

## Acceptance gates

- Perl-level regressions pass on system Perl; pseudofork-specific semantics also match Windows Perl CI.
- Compiler-owned tests cover fork in expressions, conditionals, loops, nested subs, evals, callbacks, exceptions, localized state, and nested forks.
- Parent and child resume after the same fork point and return through their complete caller chains.
- `$$`, `getpid`, `getppid`, `wait`, `waitpid`, `$?`, `exit`, `_exit`, `exec`, and documented `kill` behavior use the correct pseudo-process record.
- State isolation, hooks, handles, `%ENV`, virtual current directory, cleanup, and unsupported-resource errors have focused permanent tests.
- Programs launched through the JVM entry point and the explicit interpreter entry point produce the same results. Both entry points always use the resumable call protocol, whether or not the program actually forks; do not claim a JVM-stack continuation.
- `$Config{d_pseudofork}` is advertised only when the stated baseline is true; `$Config{d_fork}` and OS-level capability checks remain truthful.
- Existing `open "-|"`/`"|-"` tests remain green; their support is tracked as a project extension with its own gate.

## Explicit limitations

- No OS-level memory, security, privilege, namespace, UID/GID, resource-limit, or crash isolation.
- Native libraries with process-global state may not be safe across pseudo-process threads.
- `SIGKILL` is not a reliable hard stop; cancellation is cooperative and may not interrupt blocking native/Java calls.
- Filesystem environment, working directory, and handles are virtualized only where PerlOnJava explicitly routes the operation through runtime state. Any unvirtualized API must be rejected or documented as shared host behavior.
- Pseudofork compatibility is not equivalent to true Unix fork. Capability variables and integration behavior must make that distinction visible.

## Related

- [Issue #1144](https://github.com/fglock/PerlOnJava/issues/1144) — status and discussion; this document is the implementation plan.
- [Perl `perlfork` reference](https://perldoc.perl.org/perlfork)
- [Roadmap: Objective 6 / Fork Emulation](../../docs/about/roadmap.md)
- [Concurrency design](concurrency.md)
- [Full-call-chain continuation subplan](fork_continuation.md)
- [Perl threads reference](../../docs/reference/threads.md)
