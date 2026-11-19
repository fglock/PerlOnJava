# Full-Call-Chain Continuations for Pseudofork

**Status:** proposed implementation subplan for [pseudofork issue #1144](https://github.com/fglock/PerlOnJava/issues/1144). This document addresses continuation capture/resume on both the interpreter and JVM backends; [the pseudofork plan](fork_open_emulation.md) remains the canonical feature contract.

## Problem

Pseudofork needs two executions to resume at one Perl operation: the parent returns a pseudo PID and the child returns `0`. Java cannot copy a live JVM stack. Starting another Java or virtual thread does not preserve the caller's local variables, `finally` blocks, or return path.

The interpreter already represents one invocation with `SuspendedInterpreterFrame`, including its bytecode PC, registers, eval/control stacks, regex state, closure cleanup and dynamic-state suspension. However, `CALL_SUB` and related paths synchronously call `RuntimeCode.apply()`, so nested calls still create nested Java invocations. `InterpreterState` tracks frames for introspection but does not hold a resumable copy of each frame's registers and return destination.

The JVM backend has the same fundamental limitation in a different form. `EmitterMethodCreator` and related emitters generate Java methods whose locals hold Perl registers and whose direct calls can bypass `RuntimeCode.apply()`. A fork in compiled code cannot resume by cloning those JVM frames either. The implementation therefore needs one heap-owned Perl call chain with backend-specific resumable code: interpreter dispatch frames for bytecode and generated JVM resume segments for compiled code. Perl-visible invocation state currently managed by `RuntimeCode.apply()` and Java `try/finally` blocks must be represented in frames so suspension does not run cleanup prematurely.

Relevant implementation points:

- `src/main/java/org/perlonjava/backend/bytecode/SuspendedInterpreterFrame.java`
- `src/main/java/org/perlonjava/backend/bytecode/BytecodeInterpreter.java`, especially `CALL_SUB`, `CALL_METHOD`, and execution entry/exit
- `src/main/java/org/perlonjava/backend/bytecode/InterpreterState.java`
- `src/main/java/org/perlonjava/backend/bytecode/InterpretedCode.java`
- `src/main/java/org/perlonjava/backend/jvm/EmitterMethodCreator.java`
- `src/main/java/org/perlonjava/backend/jvm/CompiledCode.java`
- `src/main/java/org/perlonjava/backend/jvm/EmitSubroutine.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`
- [Interpreter architecture](interpreter.md)

## Design decision

Use a **backend-neutral, fork-capable Perl call chain** whose active frames live on the heap. Support two execution engines within that model:

- The interpreter stores bytecode PC and registers in resumable interpreter frames and dispatches calls through an explicit loop.
- The JVM backend compiles all Perl code into resumable methods. Each generated method runs a segment of ordinary JVM bytecode, spills its Perl locals/registers and continuation PC into its heap frame at call, callback, suspension and fork safepoints, then returns a dispatcher action. Resume enters the generated method at the saved continuation point. The generated method must never leave a live Java caller frame owning Perl state across a Perl call or fork safepoint.

Do not attempt to copy JVM frames, use virtual-thread internals as a clone mechanism, or lower only the lexical scope containing `fork`. Any Perl program may reach `fork` through a loaded module, callback or runtime-generated code, so there is no sound “this program will not fork” execution mode. All Perl execution must use the shared resumable call protocol from its root entry across the complete program, including main code, subs, eval, modules, runtime-created code and callbacks. Backend selection may remain interpreter or JVM, but both must implement the same frame, return, unwind, snapshot and bridge contracts. Any Java runtime operation that can call Perl synchronously must either become resumable or be rejected as a fork boundary until converted. The pseudofork capability flag may gate availability of the `fork` operation, but it must not select a different non-resumable call path.

The existing `SuspendedInterpreterFrame` is a useful interpreter representation, not the universal continuation format. Define shared frame-chain and invocation-state contracts, then let backend-specific frame payloads hold interpreter registers or generated JVM locals. Share immutable code metadata across clones. The exact class boundaries can change after the prototype, but all state needed to resume and unwind must be explicit and cloneable.

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

The shared frame contract must describe backend, code object, continuation location, return destination, pending control transfer and invocation-wrapper state. Its backend payload holds interpreter bytecode state or generated JVM state, and is cloned through the same identity map as runtime roots.

### Calls and dispatch

Interpreted `CALL_SUB` and `CALL_METHOD` push a frame and transfer control to the shared dispatch loop instead of recursively invoking `BytecodeInterpreter.execute()` through `RuntimeCode.apply()`. This applies to every Perl execution, whether or not a fork actually occurs. Normal return, Perl exceptions, `die`/`eval`, tail calls, non-local control flow, and cleanup all unwind explicit frames with the same semantics as today.

Tail calls replace or reuse the current frame when legal; they must not grow the continuation stack. Existing Perl call-depth and stack-trace reporting should read the explicit chain. `caller()` and debugger data must retain the same source location and package behavior.

### JVM-generated resumable code

The JVM backend remains a supported backend; routing every JVM launch through the interpreter is not the target architecture. Make resumable execution the JVM backend's normal generated call protocol, not an optional compilation mode. In this protocol:

- Every Perl code object enters through the shared frame contract. Generated code may keep locals in JVM slots while running one segment, then spill the Perl registers/locals and continuation label to its heap frame before returning a dispatcher action. Do not retain a separate non-resumable Perl-to-Perl entry path.
- Segment boundaries include Perl-to-Perl calls, callbacks that can call Perl, fork/suspension safepoints, and transfers that cross a Perl frame. A generated method returns to the dispatcher before a child Perl frame is invoked; no Java caller frame may remain live across that call.
- Resume enters generated code at the saved continuation label and restores locals. Return values, pending call arguments, context conversion and unwind actions travel through explicit frame fields or typed dispatcher actions. Preserve generated JVM code between safepoints where practical; do not add a per-opcode heap write.
- Audit direct JVM-to-JVM calls, `RuntimeCode.apply()` calls, generated `apply()` entry points, and eval/runtime compilation. All Perl calls, including compiled-to-compiled calls, use the explicit call protocol. Runtime-created code follows the same protocol.
- Keep pure Java helpers and operations that cannot call Perl as direct calls within a generated segment. Route all roots, evals and modules through the same dispatcher; the pseudofork capability setting gates the operation, not the execution model.

The existing `goto`/`next`/exception and tail-call trampoline infrastructure is a control-transfer foundation, not by itself a continuation implementation. Reuse its typed transfer representation and iterative dispatch patterns where appropriate, extending them to cross saved Perl frames and resume after cloning. A `goto` within one Perl invocation can remain a JVM branch; non-local transfers update explicit frames and unwind metadata.

JVM methods have a 65,535-byte bytecode limit. Spill/restore and dispatch code can push large generated methods over that limit. Reuse or extend large-block refactoring, method-size fallback and trampoline extraction to split resumable code into bounded segments/helpers. Interpreter fallback is acceptable only if the affected code object's frame and active callers still participate in the same fork-capable call chain. Add diagnostics identifying the code object and generated method when splitting/fallback cannot stay within the limit.

`RuntimeCode.apply()` remains the public synchronous API, but a root call must enter and run the shared dispatcher; nested Perl calls must use the frame protocol. Audit every runtime-to-Perl callback path, including overload, tied variables, sort, signal handlers, regex callbacks, destructors, `CLONE` hooks, and dynamic `require`/`eval`. Convert these paths to resumable bridge frames where a fork may occur inside the callback. A bridge frame must save the Java operation's post-callback continuation as explicit state; merely wrapping the callback in another `RuntimeCode.apply()` is not sufficient.

### Fork boundary

The fork opcode executes only at a dispatch safepoint. At that point:

1. The active frame chain records post-opcode PCs and the caller's pending result destination. No Java frame may own Perl continuation state that is needed after the fork.
2. The parent keeps its current chain and resumes with the pseudo PID.
3. The runtime snapshot clones the child continuation and its mutable Perl state in one identity-preserving graph operation. This preserves aliases between frame registers, lexical captures and globals.
4. The child copy receives `0` in the fork result destination and resumes through the same dispatcher on its pseudo-process thread.
5. The snapshot policy shares immutable code and clones or rejects mutable/external resources according to the parent pseudofork plan.

Do not copy the parent's frame chain and then also re-run its fork path. Only the child needs a continuation copy. The parent continues on its existing state.

## Runtime-to-Perl bridge inventory

The frame dispatcher solves interpreter opcode and generated JVM calls, but it does not automatically solve a Perl callback invoked from Java. Before advertising full-call-chain support, inventory `RuntimeCode.apply()` call sites reachable during normal Perl execution and classify each as:

1. **Root entry:** a Java/API caller starts Perl and can wait for the active backend's dispatcher to finish.
2. **Perl call:** interpreter bytecode or generated JVM code invokes Perl and must push a Perl frame directly.
3. **Resumable callback:** a Java runtime operation invokes Perl and has an explicit continuation record for the operation after the callback returns.
4. **Non-forkable boundary:** execution cannot yet be suspended safely; `fork` must fail with a precise diagnostic if called below it.

The non-forkable category is transitional, not a compatibility claim. Include callbacks reached through DBI handles and driver objects, custom DBI methods, overload and tie hooks, as well as callbacks originating in core runtime code. Distinguish a continuation problem (Java operation cannot resume after callback) from a resource-snapshot problem (handle cannot be cloned/shared safely); both can independently make a fork boundary unsupported. The capability stays disabled until all Perl-visible callback paths in the advertised surface are resumable. Static enumeration must be supplemented with indirect-callback tests.

## Performance and memory goals

### Make the always-resumable path efficient

All programs execute Perl calls through the resumable protocol, because any program may fork. The design cannot promise zero overhead relative to the existing recursive JVM call path. It must keep overhead out of individual opcodes, avoid allocating dispatcher actions or writing frame fields per instruction, and measure the cost on programs that never call `fork`. Snapshot cloning and child-thread work occur only when a fork actually runs.

For interpreter execution:

- Preserve the switch-based bytecode dispatch and keep PC/registers in local variables during the hot dispatch loop. Spill them to the heap frame at call, callback, suspension and fork safepoints; do not write the PC to the frame after every opcode.
- Allocate or acquire one frame/register array per active interpreted invocation, not one object per bytecode instruction. The current interpreter already allocates a register array and `SuspendedInterpreterFrame` per invocation; reuse that representation where safe.
- Keep the call chain proportional to active Perl call depth. Completed frames must release registers, callback state and captured values promptly.
- Do not copy frame state on ordinary calls. Copy the active child frame chain only when a fork actually occurs.

For JVM execution, run generated JVM segments between safepoints and avoid a heap write per emitted opcode. Measure spill/restore code size, generated method count, segment dispatch overhead, allocations per Perl call and throughput against the current compiler baseline. Segment splitting must keep generated methods below the 65,535-byte classfile limit, including added trampoline and unwind code.

Treat Perl-call dispatch as the main steady-state risk: the resumable JVM path may replace a direct generated-to-generated call with a frame action, dispatcher loop and resume entry. Benchmark this separately from opcode execution with shallow and deep call chains, method calls, closures, tail calls and mixed compiled/interpreted calls. Compare warmed throughput and cold startup/first-call latency; generated resume methods must still reach stable JIT-compiled performance without excessive code-cache use or loss of useful inlining. If the central dispatcher dominates, optimize the call protocol or generated resume entry before considering per-opcode changes. Any batching or direct-call fast path must prove it leaves no unrepresented Java caller frame at a possible fork boundary.

### Bound the fork copy

The correctness-first copy includes every active frame's register state and control metadata, plus the mutable runtime graph. Share bytecode, generated classes/method metadata, source maps and immutable constant metadata. Use one identity map for runtime roots and frame roots so aliases remain correct. Avoid cloning inactive frames, completed calls, cached code, or the parent's own continuation.

Initially, copy each active frame's full register array; register liveness is a correctness hazard and should not block the continuation prototype. Then measure the bytes copied per active frame. A later optimization may add compiler-generated live-register maps at safepoints, provided it preserves lexical cleanup, returned closures, aliased arguments and debugger visibility. Clear dead temporary references during ordinary frame cleanup to avoid retaining large objects while a parent waits.

No per-opcode allocation is acceptable. Establish throughput and allocation baselines for recursive interpreter and JVM workloads before implementation. The prototypes must report dispatch throughput, allocations per Perl call, bytes retained per active frame, generated method sizes, and child snapshot cost versus call depth. Separate time/bytes spent copying the continuation from time/bytes spent cloning runtime roots; vary both call depth and runtime graph size so one does not hide the other. Include ordinary programs that never call `fork`, shallow forks, repeated forks, and a parent waiting while children retain snapshots. Capture allocation/GC and JIT/code-cache evidence outside scored runs. Set numeric regression budgets from these measurements before implementation expands; do not claim “fast” based only on asymptotic reasoning.

Use the project's [performance-over-Perl benchmark protocol](performance-over-perl.md) for repeatable warmed comparisons where its workloads apply. Add focused continuation benchmarks for Perl calls, recursion, callback bridges and fork snapshots; keep compiler/JVM startup and cold first-call latency as separately reported metrics. Record source/JAR identity, workload checksums, raw samples and host/JVM configuration. Averages from one run or instrumented profiler timings are not acceptance evidence.

## Implementation phases

### Phase 0: shared contract, call-boundary inventory and prototypes

- Define shared frame/dispatcher/control-transfer contracts and inventory interpreter calls, generated JVM direct calls, callbacks and existing trampolines.
- Build minimal interpreter and JVM-generated prototypes for nested Perl calls, return values, exceptions and `eval` catches. The JVM prototype must resume through generated caller and callee code without retaining a Java caller frame.
- Demonstrate a fork point with two independently resumable copies that return through several callers on both backends.
- Measure current interpreter/JVM baselines and the always-resumable path with no actual fork, per-call dispatcher overhead, generated method sizes, cold/warm behavior, code-cache impact and the 65,535-byte splitting strategy before expanding the prototypes. Set numeric no-fork overhead budgets here.
- Do not clone `PerlRuntime`, change `fork`, or advertise `d_pseudofork` in this phase.

### Phase 1: shared frame chain and interpreter backend

- Add the fork-capable execution session and heap frame chain.
- Convert `CALL_SUB`, `CALL_METHOD`, tail calls and eval entry/return to explicit frame transitions.
- Preserve Perl exception/unwind and cleanup behavior in frame return paths. Route all interpreter and JVM calls through the shared frame protocol; the capability flag may still keep `fork` unavailable until snapshot/process support is ready.
- Add parity regressions for recursion, context, `caller`, tail calls, exceptions, localization, closures and debugger/source locations.

### Phase 2: JVM resumable code generation

- Make generated resumable entry points the only Perl-to-Perl JVM call path and spill/restore at safepoints. Route all compiled-to-compiled calls through dispatcher actions.
- Preserve JVM trampoline behavior for tail calls and non-local control flow, extending it to save and transfer across explicit frames.
- Split large generated methods into bounded resumable segments/helpers. Test methods near and beyond the bytecode-size threshold and verify any fallback uses compatible frame semantics.
- Add JVM-specific parity coverage for recursion, context, `caller`, tail calls, exceptions, localization, closures, eval, debugger/source locations and mixed compiled/interpreted code.

### Phase 3: resumable host callbacks

- Convert each in-scope Java-to-Perl callback to a bridge frame with an explicit post-callback continuation.
- Cover overload/tie/sort/eval/regex/signal/cleanup hooks and runtime module callbacks; add a test for each distinct bridge type.
- Add a runtime guard that rejects fork at any unconverted boundary and verify the diagnostic points to the boundary.
- Do not enable the capability while a Perl-visible callback can strand a Java caller frame.

### Phase 4: fork snapshot and resume

- Extend the fork snapshot graph to include the active frame chain and all frame-owned runtime roots.
- Clone only child continuation state; share immutable code; preserve reference identity and closure ownership.
- Resume child and parent with their distinct `fork` return values and verify complete caller-chain behavior.
- Integrate process lifecycle only after snapshot consistency and continuation cleanup pass focused tests.

### Phase 5: memory and speed optimization

- Profile before changing frame layout. Consider compact/segmented register storage, reusable frame shells, or compiler-generated live-register maps only when measurements show frame allocation, retained references, or continuation copying is material. Do not pool frames or arrays unless ownership and clearing rules prevent a parent/child alias or stale-value retention.
- If runtime graph cloning dominates fork cost, optimize graph traversal and policy-approved immutable sharing independently of continuation-frame copying; never weaken alias preservation or resource policy for speed.
- Benchmark deep recursion, call-heavy and callback-heavy code, no-fork overhead on both backends, JVM segment dispatch/JIT behavior and generated method size, plus repeated fork with parent waits. Report throughput, latency, allocation, GC and snapshot cost separately.
- Verify completed frames and abandoned children release arrays, captures, callback state and runtime snapshots.

## Required evidence

- System Perl confirms expected Perl-level results; Windows Perl CI validates pseudofork-specific behavior.
- Nested ordinary and tail calls resume in both parent and child, including calls through loaded modules and runtime-generated eval code.
- Fork from each converted callback type resumes the callback, the Java operation, and every Perl caller exactly once.
- `die`/`eval`, `return`, `goto`, `last`/`next`/`redo`, localization, signatures, `caller`, regex state and cleanup behave correctly across a fork suspension.
- No Java `finally` cleanup runs at suspension time unless it is represented and intentionally resumed as part of the continuation.
- All programs use the resumable protocol through both interpreter and JVM-generated code, including mixed code objects; neither backend has per-opcode allocations. Frame memory scales with live call depth and register state; child-copy work is measured independently from normal dispatch.
- JVM resumable generation stays below the classfile method-size limit through bounded segments/helpers, and trampoline/non-local transfers preserve frame and unwind semantics after fork.
- No-fork performance meets the overhead budget set in Phase 0; reports separately expose call-dispatch cost, frame allocation/retention, runtime-graph clone cost, JVM warmup and code-cache impact.
- Unsupported runtime callback boundaries fail before creating a child and leave the parent continuation and runtime state intact.

## Non-goals for this subplan

- Maintaining a separate non-resumable JVM Perl-to-Perl call path that could leave untracked caller frames at a future fork.
- Cloning JVM stack frames or relying on internal JVM continuation cloning.
- Optimizing away all frame copies before a correct full-chain snapshot exists.
- Defining process IDs, signal policy, resource inheritance or ecosystem compatibility; those remain in [the parent pseudofork plan](fork_open_emulation.md).
