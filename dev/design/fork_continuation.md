# Full-Call-Chain Continuations for Pseudofork

**Status:** proposed implementation subplan for [pseudofork issue #1144](https://github.com/fglock/PerlOnJava/issues/1144). This document addresses continuation capture/resume only; [the pseudofork plan](fork_open_emulation.md) remains the canonical feature contract.

## Problem

Pseudofork needs two executions to resume at one Perl operation: the parent returns a pseudo PID and the child returns `0`. Java cannot copy a live JVM stack. Starting another Java or virtual thread does not preserve the caller's local variables, `finally` blocks, or return path.

The current interpreter already represents one invocation with `SuspendedInterpreterFrame`, including its bytecode PC, registers, eval/control stacks, regex state, closure cleanup and dynamic-state suspension. However, `CALL_SUB` and related paths synchronously call `RuntimeCode.apply()`. Nested interpreted calls therefore create nested Java invocations. `InterpreterState` tracks frames for introspection, but it does not hold a resumable copy of each frame's registers and return destination. In addition, `RuntimeCode.apply()` manages Perl-visible invocation state in Java `try/finally` blocks. Capturing only the innermost frame or unwinding through those `finally` blocks loses required continuation state or runs cleanup too early.

Relevant implementation points:

- `src/main/java/org/perlonjava/backend/bytecode/SuspendedInterpreterFrame.java`
- `src/main/java/org/perlonjava/backend/bytecode/BytecodeInterpreter.java`, especially `CALL_SUB`, `CALL_METHOD`, and execution entry/exit
- `src/main/java/org/perlonjava/backend/bytecode/InterpreterState.java`
- `src/main/java/org/perlonjava/backend/bytecode/InterpretedCode.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`
- [Interpreter architecture](interpreter.md)

## Design decision

Use a **fork-capable interpreter with an explicit heap-resident Perl call chain**. Do not attempt to copy JVM frames, use virtual-thread internals as a clone mechanism, or lower only the lexical scope containing `fork`.

When pseudofork is enabled, every Perl call path that can be active at a fork point must be representable in this explicit call chain. This includes main code, subs, eval, modules and runtime-created code. Any Java runtime operation that can call Perl code synchronously must either become a resumable bridge operation or be rejected as a fork boundary until converted. The JVM launcher must select this mode for the complete Perl program; the ordinary JVM-compiled path remains available when pseudofork is disabled.

The initial implementation should extend the existing `SuspendedInterpreterFrame` model rather than create a second interpreter state format. The exact class boundaries can change after the prototype, but the following state must be explicit and cloneable.

## Continuation representation

### Perl call frame

Each active Perl invocation has one heap-owned frame containing:

- Shared immutable code metadata and bytecode.
- Current PC, register array, call context, argument state and subroutine identity.
- Caller return destination and any pending call result conversion.
- Eval catches, labeled/control-block stacks, localization depth, regex state, method invocant holds, closure ownership and scope cleanup batches.
- The invocation-wrapper state normally kept on the Java stack: args/call-context/active-code entries, warning and hint state, signature state, caller metadata, mortal/cleanup marks, and actions to run on return, exception, or cancellation.
- A tagged frame kind and program counter for a pending runtime callback or built-in bridge, when present.

The frame chain is a compact, per-execution stack owned by the runtime. It contains only active calls, not completed calls or a copy of the Java stack. Return from a child frame writes its result into the saved caller destination and resumes the caller dispatch loop.

### Calls and dispatch

In fork-capable mode, interpreted `CALL_SUB` and `CALL_METHOD` push a frame and transfer control to the shared dispatch loop instead of recursively invoking `BytecodeInterpreter.execute()` through `RuntimeCode.apply()`. Normal return, Perl exceptions, `die`/`eval`, tail calls, non-local control flow, and cleanup all unwind explicit frames with the same semantics as today.

Tail calls replace or reuse the current frame when legal; they must not grow the continuation stack. Existing Perl call-depth and stack-trace reporting should read the explicit chain. `caller()` and debugger data must retain the same source location and package behavior.

`RuntimeCode.apply()` remains the public synchronous API for callers outside an active fork-capable dispatch. Inside the dispatch, Perl-to-Perl calls must use the frame protocol. Audit every runtime-to-Perl callback path, including overload, tied variables, sort, signal handlers, regex callbacks, destructors, `CLONE` hooks, and dynamic `require`/`eval`. Convert these paths to resumable bridge frames where a fork may occur inside the callback. A bridge frame must save the Java operation's post-callback continuation as explicit state; merely wrapping the callback in another `RuntimeCode.apply()` is not sufficient.

### Fork boundary

The fork opcode executes only at a dispatch safepoint. At that point:

1. The active frame chain records post-opcode PCs and the caller's pending result destination. No Java frame may own Perl continuation state that is needed after the fork.
2. The parent keeps its current chain and resumes with the pseudo PID.
3. The runtime snapshot clones the child continuation and its mutable Perl state in one identity-preserving graph operation. This preserves aliases between frame registers, lexical captures and globals.
4. The child copy receives `0` in the fork result destination and resumes through the same dispatcher on its pseudo-process thread.
5. The snapshot policy shares immutable code and clones or rejects mutable/external resources according to the parent pseudofork plan.

Do not copy the parent's frame chain and then also re-run its fork path. Only the child needs a continuation copy. The parent continues on its existing state.

## Runtime-to-Perl bridge inventory

The explicit frame dispatcher solves interpreter opcode calls, but it does not automatically solve a Perl callback invoked from Java. Before advertising full-call-chain support, inventory `RuntimeCode.apply()` call sites reachable during normal Perl execution and classify each as:

1. **Root entry:** a Java/API caller starts Perl and can wait for the interpreter to finish.
2. **Interpreter call:** bytecode invokes Perl code and must push a Perl frame directly.
3. **Resumable callback:** a Java runtime operation invokes Perl and has an explicit continuation record for the operation after the callback returns.
4. **Non-forkable boundary:** execution cannot yet be suspended safely; `fork` must fail with a precise diagnostic if called below it.

The non-forkable category is transitional, not a compatibility claim. The fork capability stays disabled until all Perl-visible callback paths in the advertised surface are resumable. Reflection-based scanning can identify call sites for review, but static enumeration must be supplemented with tests because callback paths can be indirect.

## Performance and memory goals

### Keep the ordinary JVM path unchanged

When pseudofork is disabled, compiled execution keeps its existing JVM call path. Fork-capable dispatch is selected at the whole-program boundary, not by adding a branch or continuation allocation to every compiled opcode.

In fork-capable interpreter mode:

- Preserve the switch-based bytecode dispatch and keep PC/registers in local variables during the hot dispatch loop. Spill them to the heap frame at call, callback, suspension and fork safepoints; do not write the PC to the frame after every opcode.
- Allocate or acquire one frame/register array per active interpreted invocation, not one object per bytecode instruction. The current interpreter already allocates a register array and `SuspendedInterpreterFrame` per invocation; reuse that representation where safe.
- Keep the call chain proportional to active Perl call depth. Completed frames must release registers, callback state and captured values promptly.
- Do not copy frame state on ordinary calls. Copy the active child frame chain only when a fork actually occurs.

### Bound the fork copy

The correctness-first copy includes every active frame's register state and control metadata, plus the mutable runtime graph. Share bytecode, source maps and immutable constant metadata. Use one identity map for runtime roots and frame roots so aliases remain correct. Avoid cloning inactive interpreter frames, completed calls, cached code, or the parent's own continuation.

Initially, copy each active frame's full register array; register liveness is a correctness hazard and should not block the continuation prototype. Then measure the bytes copied per active frame. A later optimization may add compiler-generated live-register maps at safepoints, provided it preserves lexical cleanup, returned closures, aliased arguments and debugger visibility. Clear dead temporary references during ordinary frame cleanup to avoid retaining large objects while a parent waits.

No per-opcode allocation is acceptable. Establish baseline throughput and allocation for recursive interpreter workloads before implementation. The prototype must report dispatch throughput, allocations per Perl call, bytes retained per active frame, and child snapshot cost versus call depth. Set a numeric regression budget from those measurements before merging; do not claim “fast” based only on asymptotic reasoning.

## Implementation phases

### Phase 0: call-boundary inventory and prototype

- Enumerate interpreter call and runtime-to-Perl callback paths; identify which Java wrappers contain state that must resume after a callback.
- Build a minimal explicit-frame prototype for nested interpreted subroutine calls, return values, exceptions and `eval` catches.
- Demonstrate a fork point with two independently resumable copies that both return through several Perl callers.
- Benchmark current interpreter dispatch and frame allocation. Record baseline and a target budget before expanding the prototype.
- Do not clone `PerlRuntime`, change `fork`, or advertise `d_pseudofork` in this phase.

### Phase 1: explicit interpreter call chain

- Add the fork-capable execution session and heap frame chain.
- Convert `CALL_SUB`, `CALL_METHOD`, tail calls and eval entry/return to explicit frame transitions.
- Preserve Perl exception/unwind and cleanup behavior in frame return paths. Keep ordinary interpreter mode and JVM compilation unchanged when fork mode is off.
- Add parity regressions for recursion, context, `caller`, tail calls, exceptions, localization, closures and debugger/source locations.

### Phase 2: resumable host callbacks

- Convert each in-scope Java-to-Perl callback to a bridge frame with an explicit post-callback continuation.
- Cover overload/tie/sort/eval/regex/signal/cleanup hooks and runtime module callbacks; add a test for each distinct bridge type.
- Add a runtime guard that rejects fork at any unconverted boundary and verify the diagnostic points to the boundary.
- Do not enable the capability while a Perl-visible callback can strand a Java caller frame.

### Phase 3: fork snapshot and resume

- Extend the fork snapshot graph to include the active frame chain and all frame-owned runtime roots.
- Clone only child continuation state; share immutable code; preserve reference identity and closure ownership.
- Resume child and parent with their distinct `fork` return values and verify complete caller-chain behavior.
- Integrate process lifecycle only after snapshot consistency and continuation cleanup pass focused tests.

### Phase 4: memory and speed optimization

- Use measurements to tune frame layout and storage. Consider live-register maps only if active-frame copying is a material cost.
- Benchmark deep recursion, callback-heavy code, no-fork interpreter overhead, and repeated fork with parent waits.
- Verify completed frames and abandoned children release arrays, captures, callback state and runtime snapshots.

## Required evidence

- System Perl confirms expected Perl-level results; Windows Perl CI validates pseudofork-specific behavior.
- Nested ordinary and tail calls resume in both parent and child, including calls through loaded modules and runtime-generated eval code.
- Fork from each converted callback type resumes the callback, the Java operation, and every Perl caller exactly once.
- `die`/`eval`, `return`, `goto`, `last`/`next`/`redo`, localization, signatures, `caller`, regex state and cleanup behave correctly across a fork suspension.
- No Java `finally` cleanup runs at suspension time unless it is represented and intentionally resumed as part of the continuation.
- With fork mode off, compiled execution remains unchanged. Fork-capable mode has no per-opcode allocations; frame memory scales with live call depth and register state; child-copy work is measured independently from normal dispatch.
- Unsupported runtime callback boundaries fail before creating a child and leave the parent continuation and runtime state intact.

## Non-goals for this subplan

- Cloning JVM stack frames or relying on internal JVM continuation cloning.
- Making the ordinary JVM-compiled call stack resumable as part of the first implementation.
- Optimizing away all frame copies before a correct full-chain snapshot exists.
- Defining process IDs, signal policy, resource inheritance or ecosystem compatibility; those remain in [the parent pseudofork plan](fork_open_emulation.md).
