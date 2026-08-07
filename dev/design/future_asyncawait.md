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

### Phase 4: Syntax completeness and interoperability — completed 2026-08-07

- Add `CANCEL` blocks, lexical async subs, async methods, signatures,
  attributes, and forward declarations.
- Validate interactions with supported keyword features such as `try`,
  `defer`, and `class`.
- Decide which portions of the extension-builder ABI can be represented by a
  PerlOnJava-native hook API.

### Phase 5: Production lowering and ecosystem validation — completed 2026-08-07

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

### Current status: Complete and fully verified

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
- [x] Phase 4a: Syntax forms and cancellation blocks (2026-08-06)
  - Added `my async sub`, async class methods, signatures, attributes, and
    forward declarations by routing the async modifier through the existing
    subroutine and class parsers.
  - Added `:experimental(cancel)` hint handling and cancellation-only dynamic
    registrations. Executed `CANCEL` blocks run in reverse order with captured
    lexicals when a suspended outer Future is cancelled, and are discarded on
    normal completion or failure.
  - Verified runtime interaction with `defer`, class methods, and both backend
    frontends; full `make` passes.
  - Files: `FutureAsyncAwaitParser.java`, `StatementResolver.java`,
    `StatementParser.java`, `CancelBlock.java`, bytecode compiler/interpreter
    integration, `Future::AsyncAwait.pm`, and async parser/runtime tests.
- [x] Phase 4b: Resumable native try/catch/finally (2026-08-07)
  - Lowered `TryNode` onto the interpreter's existing eval-handler opcodes,
    including lexical catch-parameter binding and expression-result merging.
  - Kept native `try` inline inside async bodies so an `await` suspends the
    owning async frame instead of an internal ordinary-sub wrapper.
  - Verified awaited success and failure through `try`, `catch`, and `finally`
    on both frontend backends; full `make` passes.
  - Deferred extension-hook ABI selection to Phase 5 so it is based on concrete
    ecosystem requirements rather than speculative compatibility surface.
  - Files: `BytecodeCompiler.java`, `StatementParser.java`, and
    `FutureAsyncAwaitRuntimeTest.java`.
- [x] Phase 5a: Upstream harness baseline and core compatibility (2026-08-07)
  - Made the facade load `Future >= 0.49` when available and record Awaitable
    capability from `%INC`, avoiding a compile-order dependency on runtime
    typeglob aliases while retaining the targeted missing-Future diagnostic.
  - Ran async bodies in list context, independent of the caller context, while
    preserving scalar/list/void context at each `await` expression.
  - Added the upstream stack-balance diagnostic helper and verified the core
    upstream files `00use.t` through `21context-while.t`, plus destruction,
    failure, native try, and regression tests on the default frontend.
  - The full cached 0.71 baseline now reaches 208 subtests across 52 files;
    remaining failures are concentrated in restricted syntax diagnostics,
    synchronous signature errors, Future subclass selection, abandoned-Future
    warnings, and optional Awaitable-role packaging.
  - Added both-backend regression coverage and verified the full `make` suite.
  - Files: `Future::AsyncAwait.pm`, `FutureAsyncAwaitParser.java`, backend async
    call-context integration, and `FutureAsyncAwaitRuntimeTest.java`.
- [x] Phase 5b: Configured Future subclasses (2026-08-07)
  - Implemented the upstream `future_class => $class` import option with
    lexical hint scoping and per-async-sub metadata.
  - Constructed immediate success/failure and pending outer results with the
    configured subclass while preserving Awaitable cloning for the default
    unconfigured path.
  - Verified upstream `50future-subclass.t` (4/4), both frontend regression
    coverage, and a green full `make` rerun.
  - Files: `Future::AsyncAwait.pm`, async parser/subroutine metadata paths,
    `InterpretedCode.java`, `FutureAsyncAwaitRuntime.java`, and
    `FutureAsyncAwaitRuntimeTest.java`.
- [x] Phase 5c: Upstream placement, signature, and attribute parity (2026-08-07)
  - Rejected `await` in map/grep callback blocks, non-lexical foreach iterator
    bodies, and eval-string top level with upstream-compatible diagnostics,
    while retaining lexical foreach and nested async-sub support.
  - Moved async signature arity checks to the synchronous call boundary while
    keeping default expressions and parameter binding inside the Future-wrapped
    body.
  - Preserved `:lvalue` for attribute introspection and emitted the required
    compile-time warning that it has no effect on an async Future result.
  - Verified upstream placement tests (19 assertions), signature tests (9),
    and attribute tests (5) on both frontends; full `make` passes.
  - The complete default-frontend baseline now has one applicable failing file,
    `42unresolved.t`, for four missing abandoned-Future diagnostics. Remaining
    harness failures require unsupported `for_list`, optional extension modules,
    or separately packaged Awaitable role/helper files.
  - Files: parser context and eval-string integration, `SignatureParser.java`,
    async runtime metadata, and `FutureAsyncAwaitRuntimeTest.java`.
- [x] Phase 5d: Abandoned returning-Future ownership (2026-08-07)
  - Replaced the readiness callback's strong ownership of the returned Future
    with a weakened scalar probe, allowing suspended async frames to detect
    when their caller has discarded the result.
  - Added upstream-compatible suspended and failed-abandonment warnings and
    terminal cleanup of abandoned frames.
  - Added explicit ownership of the currently awaited Future while a frame is
    suspended, preventing nested async chains from being mistaken for
    abandonment before their outer owner is collected.
  - Verified upstream `42unresolved.t` (14/14) on both frontends and added a
    nested-chain regression; full `make` passes.
  - Files: `FutureAsyncAwaitRuntime.java`, `InterpreterSuspension.java`, and
    `FutureAsyncAwaitRuntimeTest.java`.
- [x] Phase 5e: Awaitable role packaging (2026-08-07)
  - Packaged the upstream-compatible `Future::AsyncAwait::Awaitable` Role::Tiny
    contract and `Test::Future::AsyncAwait::Awaitable` conformance helper.
  - Used Role::Tiny's direct role initialization/generator interface to avoid
    relying on unsupported caller-sensitive typeglob exports.
  - Verified the modules with system Perl syntax checks and upstream
    `51awaitable-role.t`/`52awaitable-test.t` (9 assertions) on both frontends;
    full `make` passes.
  - Files: `Future/AsyncAwait/Awaitable.pm` and
    `Test/Future/AsyncAwait/Awaitable.pm`.
- [x] Phase 5f: Ecosystem validation and aggregate refcount repair (2026-08-07)
  - Repaired weak scalar references to autovivified array and hash elements by
    preserving the aggregate's owner-aware element collection during
    autovivification. This restores cancellation callback revocation and
    compaction in `Future` without changing weak-reference behavior for
    initialized aggregates.
  - Added an eight-assertion aggregate-element regression validated first with
    system Perl and then with both PerlOnJava frontends.
  - Repaired the two baseline failures that initially masked final verification:
    interpreter list declarations now register every lexical and replace that
    registration when assignment replaces the scalar slot; readonly scalar
    literals remain owned by their installed subroutine on both frontends;
    interpolated bracketed `\Q`/`\E` preserve Perl's deferred regexp warnings;
    and the interpreter now applies lexical warning/hint state at runtime like
    the JVM backend.
  - Verified `Future` 0.52 completely (`56` files, `784` tests) and
    `IO::Async` 0.805 completely (`64` files, `665` tests).
  - `Net::Async::HTTP` reaches its own suite but 38 programs cannot import four
    platform-dependent `Socket` IPTOS constants. The host system Perl also
    lacks those constants, so this is a separate Socket portability boundary
    rather than an async-runtime failure.
  - PAGI validation reaches 263 assertions across 110 programs: 68 programs
    and 231 assertions pass. Remaining failures are downstream module-porting
    gaps involving Net::Async::HTTP/WebSocket dependencies, Unix sockets,
    inherited descriptors, process activation, and file/runtime behavior.
- [x] Phase 5g: Production-lowering and extension-ABI decision (2026-08-07)
  - Retained selective interpreter routing as the production async lowering.
    It shares the same callable boundary with JVM-compiled callers and passed
    the applicable upstream and ecosystem coverage without a second state
    machine implementation.
  - No native extension-builder ABI was added. The validated pure-Perl
    Awaitable role and Future protocol cover the working ecosystem; downstream
    failures occur at unrelated module and operating-system boundaries.
  - A native JVM state machine remains an optional profiling-driven
    optimization, not a correctness or compatibility requirement.

### Next steps

1. [Completed 2026-08-07] Track Socket IPTOS portability needed by
   Net::Async::HTTP as a separate module-porting task.
   - Added the four portable RFC 1349 type-of-service constants required by
     Net::Async::HTTP to PerlOnJava's bundled `Socket` implementation.
   - Re-ran Net::Async::HTTP 0.50: the suite now reaches all 41 test programs
     without IPTOS import errors. The run executes 27 assertions (23 pass) and
     leaves 30 programs failing at the separately tracked socketpair, bind,
     and socket-I/O boundaries.
   - Kept this as a post-completion ecosystem follow-up rather than part of the
     async runtime; files: `Socket.java`, `Socket.pm`, and
     `socket_iptos_constants.t`.
2. Track PAGI's Unix-socket, inherited-descriptor, process-activation, and
   remaining file/runtime gaps separately from async/await.
   - [Completed 2026-08-07] Added channel-native Unix stream socket creation,
     bind, listen, connect, accept, close, `getsockname`, and `getpeername`,
     including interpreter opcode parity and packed `sockaddr_un` handling.
   - [Completed 2026-08-07] Allowed restrictive masks such as `umask(0077)`;
     the existing FFM binding already supported them, but the operator rejected
     owner permission bits incorrectly.
   - The focused socket and umask regressions pass with system Perl and both
     PerlOnJava frontends; a full `make` also passes.
   - PAGI's `t/43-unix-socket.t` now passes its first 13 subtests and reaches
     live Unix-listener setup. Its next section depends on unsupported `fork`
     and hits the test's 180-second external timeout.
   - [Completed 2026-08-07] Routed `POSIX::dup` and `POSIX::close` through the
     virtual descriptor table for Java-backed handles while retaining native
     fallbacks for raw descriptors. Descriptor lookup now prefers the current
     live owner when recycled fd numbers have stale bookkeeping entries.
   - The systemd save/`dup2`/fdopen/restore sequence passes a ten-assertion
     regression with system Perl, both PerlOnJava frontends, and the in-process
     unit harness.
   - [Completed 2026-08-07] Preserved socket identity through parsimonious
     fdopen, duplicated-descriptor, and layered filehandle wrappers. Socket
     naming, accept, datagram routing, shutdown checks, and socket options now
     consistently reach the underlying socket.
   - Added a seven-assertion borrowed-listener regression validated first with
     system Perl and then with both PerlOnJava frontends; full `make` passes.
     `IO::Poll` now uses the same wrapper-aware socket lookup, preventing a
     borrowed listener from being treated as permanently readable and causing
     a second blocking `accept`. Files: `RuntimeIO.java`, `IOOperator.java`,
     `IOPoll.java`, and `socket_borrowed_name.t`.
   - PAGI's `t/49-systemd-activation.t` now passes both subtests: it identifies
     and marks the inherited TCP listener, cleans `LISTEN_FDS`/`LISTEN_PID`,
     serves a request through IO::Async, and returns the expected 200 response
     and body.
   - [Completed 2026-08-07] Routed the interpreter's buffered `read` operator
     through the same `Readline.read` runtime as the JVM backend instead of
     incorrectly applying `sysread` semantics. Scalar-backed filehandles now
     fill their destination buffer inside selectively interpreted async frames.
   - Added scalar-handle `read` coverage validated with system Perl and both
     PerlOnJava frontends, plus an async-frame regression; full `make` passes.
     PAGI's `t/42-file-response.t` now passes all 14 in-process subtests. Its
     three remaining large-file worker-pool cases require unsupported `fork`
     and remain outside scope. Files: `IOOperator.java`,
     `FutureAsyncAwaitRuntimeTest.java`, and `scalar_handle_read.t`.
   - [Completed 2026-08-07] Removed PAGI's WebSocket module-loading boundary.
     `Net::Async::WebSocket` 0.14 used the XS-only experimental `meta` module
     only to install generated frame-sending methods; a bundled CPAN
     distropref now replaces that use with portable typeglob assignments.
   - Tightened `base`'s in-memory-package fallback so a missing nested
     dependency cannot leave a partially compiled parent class accepted as a
     successful load. The focused failure test matches system Perl on both
     PerlOnJava frontends.
   - Preserved byte-string flags for string bitwise AND, OR, XOR, and
     complement. This keeps WebSocket masking byte-safe instead of UTF-8
     re-encoding binary payload octets such as `FF FE`; the new operator
     regression matches system Perl on both frontends.
   - PAGI's `t/04-websocket.t` now passes all seven scenarios over a live
     loopback listener: text and binary echo, message sequencing, close,
     WebSocket scope, subprotocol parsing, and 403 rejection. Full `make`
     passes. A cold `jcpan -t Net::Async::WebSocket` also applies the bundled
     distropref without attempting the removed `meta` dependency and passes
     the upstream suite (5 files, 22 tests). Files: `Base.java`,
     `BitwiseOperators.java`, the two focused unit tests, and the
     `Net-Async-WebSocket` CPAN distropref and patch.
   - Next: continue the remaining PAGI file/runtime gaps.
     Process-worker tests remain outside scope while PerlOnJava does not
     implement `fork`.
3. Revisit native JVM state-machine lowering only if profiling identifies
   selective interpreter routing as a material bottleneck.

### Open questions

- No async-runtime questions remain open. Selective interpreter routing is the
  production lowering, and no extension-builder hooks are currently required.

### Final verification

- Updated the obsolete Phase 1 parser test to verify that runtime compilation
  continues past the former implementation boundary. Its minimal compatible
  `Future` fixture is defined in-memory so clean builds do not depend on a
  developer module cache.
- `weaken_scalar_refs.t`, `regex_charclass.t`, and the new aggregate-element
  weak-reference regression pass on both frontends; the new test also passes
  with system Perl.
- A clean full `make` succeeds after compiling all runtime changes.
- Final ecosystem reruns pass: Future 0.52 (`56` files, `784` tests) and
  IO::Async 0.805 (`64` files, `665` tests), with only documented unsupported
  fork, thread, listener, and socketpair cases skipped.

## References

- `dev/design/shared_ast_transformer.md`
- `docs/guides/module-porting.md`
- Upstream `Future::AsyncAwait` 0.71 source and test suite
