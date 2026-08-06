# Future::AsyncAwait on PerlOnJava

## Status

Phases 1 through 3 completed on 2026-08-06. PerlOnJava can parse lexical
`async sub`/`await` syntax and execute async subroutines through resumable
interpreter frames. Immediate Awaitables continue inline; pending Awaitables
suspend without blocking and resume from `AWAIT_ON_READY` callbacks. File-scope
await uses the Awaitable `AWAIT_WAIT` protocol.

## Motivation

`Future::AsyncAwait` is the remaining language-level dependency preventing
asynchronous frameworks such as PAGI::Server from running on PerlOnJava.
This is not a conventional XS port. The upstream extension registers parser
keywords, adds custom Perl optree operations, and captures the running Perl
frame when an awaited Future is pending.

PerlOnJava does not execute Perl 5 optrees, so emulating the upstream XS entry
points would make the module load without providing its semantics. The
implementation must instead add the syntax to PerlOnJava's frontend and lower
it to resumable PerlOnJava execution frames.

## Compatibility target

The primary target is the public Perl syntax and behavior:

- `async sub NAME { ... }` and anonymous `async sub { ... }`
- `await EXPR` inside an async sub and at file scope
- an async sub always returns a Future
- a pending awaited Future suspends without blocking the event-loop thread
- success, failure, cancellation, scalar/list context, lexical state, dynamic
  localization, regex state, loops, and exception scopes survive suspension

The C-level extension ABI in `Future/AsyncAwait/ExtensionBuilder.pm` is a
separate compatibility layer. It is not required by PAGI::Server and will be
considered after the language semantics work.

## Non-goals

- Translating `await $future` to `$future->get`. That blocks IO::Async and can
  deadlock the event loop.
- Reproducing Perl 5's internal `PL_keyword_plugin`, optree, pad, or context
  stack data structures.
- Claiming Future::AsyncAwait runtime support before pending Futures can
  suspend and resume correctly.

## Proposed architecture

```text
Future::AsyncAwait import
          |
          v
lexically scoped %^H capability
          |
          v
parser: async sub / await
          |
          v
annotated PerlOnJava AST
          |
          v
async bytecode lowering
          |
          v
resumable interpreter frame <---- Future readiness callback
          |
          v
outer Future completion / failure / cancellation
```

The bytecode interpreter is the first runtime target because it already uses
an explicit program counter and register array. Async subroutines can be
routed selectively through that backend even when the rest of a program uses
the JVM backend. A later JVM lowering can generate an equivalent state
machine for performance.

## Phases

### Phase 1: Frontend and capability boundary — completed 2026-08-06

- Add a PerlOnJava `Future::AsyncAwait` compatibility facade which sets a
  lexically scoped `%^H` capability instead of loading XS.
- Recognize named and anonymous `async sub` only while that capability is
  active.
- Recognize `await` inside an async sub or at file scope and retain its operand
  as an `OperatorNode("await", ...)` in the AST.
- Annotate async subroutine nodes and bodies for backend routing.
- Reject runtime compilation with an explicit message identifying resumable
  frames as the next required phase.
- Add parser/integration tests for loading, lexical syntax activation, AST
  shape, placement validation, and the backend boundary.

This phase deliberately provides no immediate-Future shortcut. Having one
code path behave synchronously while pending Futures fail would conceal bugs
and encourage code that deadlocks when real asynchronous I/O is introduced.

### Phase 2: Resumable interpreter frames — completed 2026-08-06

- Added `SuspendedInterpreterFrame`, which owns the program counter, register
  array, eval catch stacks, labeled-block stack, regex snapshots, cleanup
  batches, call context, and closure ownership across suspension.
- Added the `AWAIT` opcode. Completed Awaitables continue immediately;
  pending Awaitables return an internal suspension result containing the frame,
  destination register, and expression context.
- Resume callbacks are serialized through a trampoline and complete or fail a
  single cloned outer Awaitable.
- Async subroutine bodies are forced through the interpreter from both the JVM
  and interpreter frontends. The dependency guard retains a targeted error
  until an implementation of the Awaitable constructor methods is loaded.

### Phase 3: Future lifecycle and control-flow parity — completed 2026-08-06

- Implement cancellation propagation in both directions.
- Preserve scalar/list/void context and failure values.
- Cover return, die/eval, nested awaits, loops, closures, localized variables,
  regex captures, destruction, and abandoned pending Futures.
- Implement file-scope await using the Awaitable `AWAIT_WAIT` protocol.

### Phase 4: Syntax completeness and interoperability

- Add `CANCEL` blocks, lexical async subs, async methods, signatures,
  attributes, and forward declarations.
- Validate interactions with supported keyword features such as `try`,
  `defer`, and `class`.
- Decide which portions of the extension-builder ABI can be represented by a
  PerlOnJava-native hook API.

### Phase 5: JVM lowering and ecosystem validation

- Generate JVM state-machine classes for async subroutines or retain selective
  interpreter routing where it is faster and simpler.
- Pass the applicable upstream Future::AsyncAwait suite on both backends.
- Run Future, IO::Async, Net::Async::HTTP, and PAGI::Server integration suites,
  including cancellation, streaming, websocket, and backpressure tests.

## Correctness invariants

1. Awaiting a pending Future never blocks the event-loop thread.
2. Code after an await executes at most once.
3. The async sub's returned Future completes exactly once.
4. Lexicals and localized values remain alive while suspended and are released
   exactly once after completion, failure, cancellation, or abandonment.
5. Resumption restores the original Perl scalar/list/void context.
6. A failure from the awaited Future behaves like an exception at the await
   expression.
7. Syntax activation is lexical and does not turn ordinary `async()` calls or
   barewords into keywords outside the importing scope.

## Testing strategy

- Java frontend tests inspect the AST and compiler diagnostics for Phase 1.
  Perl `.t` tests begin when runtime behavior exists, and each new test must
  first be validated with standard Perl and upstream Future::AsyncAwait.
- Phase 2 adds focused immediate, pending, nested, and failure tests for both
  default and `--interpreter` execution.
- Phase 3 imports applicable upstream tests without weakening or deleting
  them.
- Each phase ends with a full `make` run. Ecosystem phases additionally run
  timeout-wrapped `jcpan -t` commands with full output captured to files.

## Risks and mitigations

- **Frame ownership leaks:** make suspension state a single owner with an
  idempotent terminal transition and explicit cleanup tests.
- **Reentrant callback completion:** callbacks now pass through a thread-local
  trampoline rather than recursively growing the Java stack.
- **Backend divergence:** define suspension at interpreter-bytecode level
  first and route JVM callers through the same RuntimeCode boundary.
- **Misleading partial support:** retain targeted diagnostics for missing
  Awaitable constructors until an implementation is loaded.

## Progress tracking

### Current status: Phase 4 in progress; current focus is syntax completeness

### Completed phases

- [x] Phase 1: Frontend and capability boundary (2026-08-06)
  - Added lexical import/unimport capability handling.
  - Added named and anonymous async-sub parsing and await AST construction.
  - Added placement checks and explicit backend diagnostics.
  - Files: `Future/AsyncAwait.pm`, `FutureAsyncAwaitParser.java`, parser and
    backend integration points, and `FutureAsyncAwaitParserTest.java`.
- [x] Phase 2: Resumable interpreter frames (2026-08-06)
  - Added heap-owned interpreter execution state and internal suspension
    results.
  - Added `AWAIT` compilation, disassembly, immediate execution, pending
    callback resumption, and callback trampolining.
  - Added Awaitable cloning/completion/failure integration and selective
    interpreter routing for async bodies from both frontends.
  - Added `FutureAsyncAwaitRuntimeTest.java` for immediate, pending, repeated
    await, lexical-state, and failure behavior on both backends.
  - Files: `SuspendedInterpreterFrame.java`, `InterpreterSuspension.java`,
    `FutureAsyncAwaitRuntime.java`, interpreter compiler/runtime integration,
    and `FutureAsyncAwaitRuntimeTest.java`.
- [x] Phase 3a: Cancellation and terminal ownership (2026-08-06)
  - Linked cancellation of the returned async Future to the currently awaited
    Future through `AWAIT_CHAIN_CANCEL`.
  - Prevented cancelled async frames from resuming and made completion/failure
    idempotent across duplicate or racing readiness callbacks.
  - Added runtime coverage for cancellation propagation and cancellation before
    awaited readiness, on both frontends.
  - Files: `FutureAsyncAwaitRuntime.java` and
    `FutureAsyncAwaitRuntimeTest.java`.
- [x] Phase 3b: Dynamic-state detachment foundation (2026-08-06)
  - Added suspend/resume hooks to the dynamic-state protocol and stored
    detached state snapshots on `SuspendedInterpreterFrame`.
  - Preserved and restored `local` scalar values across pending awaits,
    including global-scalar localization markers, while exposing the caller's
    original value during suspension.
  - Added both-backend regression coverage and verified the full `make` suite.
  - Files: `DynamicState.java`, `DynamicVariableManager.java`,
    `RuntimeScalar.java`, `GlobalRuntimeScalar.java`,
    `BytecodeInterpreter.java`, `SuspendedInterpreterFrame.java`, and
    `FutureAsyncAwaitRuntimeTest.java`.
- [x] Phase 3c: Array/hash dynamic-state preservation (2026-08-06)
  - Preserved localized array and hash contents while the async frame is
    suspended, including package-global localization markers.
  - Added both-backend coverage for caller isolation, resumed values, and
    post-completion restoration; full `make` passes.
  - Files: `RuntimeArray.java`, `RuntimeHash.java`,
    `GlobalRuntimeArray.java`, `GlobalRuntimeHash.java`, and
    `FutureAsyncAwaitRuntimeTest.java`.
- [x] Phase 3d: Defer and package-state suspension behavior (2026-08-06)
  - Detached `DeferBlock` registrations without executing them at the await
    boundary; registrations are restored before resume and execute at actual
    async-frame exit.
  - Reused the scalar dynamic-state snapshot path for the runtime package
    tracker and added async defer regression coverage on both backends.
  - Files: `DeferBlock.java` and `FutureAsyncAwaitRuntimeTest.java`.
- [x] Phase 3e: Abandoned-frame cancellation cleanup (2026-08-06)
  - Added an explicit cleanup path for cancelled suspended frames that never
    resume through their awaited Future.
  - Reattached detached dynamic state under the saved call context so deferred
    blocks execute and localization is restored exactly once.
  - Added cancellation/defer cleanup coverage and verified the full `make`
    suite.
  - Files: `FutureAsyncAwaitRuntime.java` and
    `FutureAsyncAwaitRuntimeTest.java`.
- [x] Phase 3f: Special-variable suspension state (2026-08-06)
  - Preserved hidden state for errno (`$!`), output autoflush (`$|`), and
    input-line tracking (`$.`) across await detachment and resume.
  - Added errno coverage and verified the full `make` suite on both backends.
  - Files: `ErrnoVariable.java`, `OutputAutoFlushVariable.java`,
    `ScalarSpecialVariable.java`, and `FutureAsyncAwaitRuntimeTest.java`.
- [x] Phase 3g: Tied state and cancelled-frame destruction (2026-08-06)
  - Preserved the active value behind tied package scalars through FETCH/STORE
    while retaining the original tie magic.
  - Added explicit abandonment cleanup for interpreter lexical registers and
    closure captures so cancelled suspended frames release owned values and
    fire `DESTROY` exactly once.
  - Added tied-scalar and cancellation-destruction coverage on both backends;
    full `make` passes.
  - Files: `GlobalRuntimeScalar.java`, `BytecodeInterpreter.java`,
    `FutureAsyncAwaitRuntime.java`, and `FutureAsyncAwaitRuntimeTest.java`.
- [x] Phase 3h: Control-flow and context parity (2026-08-06)
  - Made interpreter cleanup levels frame-relative so eval and loop scope
    bookkeeping can be safely rebased when a Future callback resumes under a
    different dynamic-stack depth.
  - Verified success and failure through `eval`, repeated awaits in loops,
    closure captures, regex capture restoration, and scalar/list/void await
    contexts on both frontends.
  - Added both-backend regression coverage and verified the full `make` suite.
  - Files: `BytecodeInterpreter.java` and
    `FutureAsyncAwaitRuntimeTest.java`.
- [x] Phase 3i: File-scope await (2026-08-06)
  - Lowered file-scope `await` to the Awaitable `AWAIT_WAIT` method while
    preserving scalar, list, and void expression context.
  - Kept async-sub `await` on the nonblocking resumable-frame path and made
    `AWAIT_WAIT` failures behave like ordinary exceptions catchable by `eval`.
  - Added default- and interpreter-frontend regression coverage and verified
    the full `make` suite.
  - Files: `FutureAsyncAwaitRuntime.java`, `BytecodeInterpreter.java`,
    `EmitOperatorNode.java`, `Future::AsyncAwait.pm`, and
    `FutureAsyncAwaitRuntimeTest.java`.

### Next steps

1. Import the applicable upstream Future::AsyncAwait lifecycle and
   control-flow tests.
2. Add Phase 4 syntax forms: lexical async subs, methods, signatures,
   attributes, forward declarations, and `CANCEL` blocks.
3. Validate `try`, `defer`, and `class` interactions.

### Open questions

- Should async subs permanently use the interpreter, or be promoted to JVM
  state machines after semantic parity is established?
- Which Future::AsyncAwait extension hooks are required by modules in the
  intended PAGI ecosystem?

## References

- `dev/design/shared_ast_transformer.md`
- `docs/guides/module-porting.md`
- Upstream `Future::AsyncAwait` 0.71 source and test suite
