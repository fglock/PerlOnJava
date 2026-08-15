# Vendored Joni Callout Engine

## Purpose

Perl executable regex constructs must run while the matcher owns provisional
captures and its backtracking stack. Stock Joni provides the required
stack-based matcher but does not expose match-time callbacks or unwind
notifications. PerlOnJava therefore vendors a minimally extended Joni engine.

This document defines the current architecture and acceptance contract. Change
history, imported revision details, and completed-work records belong in Git
commit messages, not here.

## Repository and dependency model

- Joni is a pinned source snapshot in `third_party/joni`. Its production sources
  and upstream tests are dedicated source sets in the root Gradle project. This
  keeps the code boundary explicit without exposing the vendored engine as a
  separately resolved project dependency.
- The source retains the upstream `org.joni` packages to keep changes reviewable
  and suitable for upstream submission.
- The vendored project is the only Joni implementation on PerlOnJava's compile
  and runtime classpaths.
- JCodings remains an upstream binary dependency because PerlOnJava does not
  modify its encoding implementation.
- Ordinary Java-regex patterns keep their existing fast path. Declarative
  recursive patterns and executable patterns use the vendored Joni engine.

## Internal callout syntax

The structured Perl regex frontend emits `(?{=CALL:<id>})` only in an
engine-facing skeleton. `<id>` is a non-negative decimal index into the callback
table owned by that regex value. The fork parses this representation into a
dedicated callout node; it never parses Perl source.

Runtime-interpolated pattern text must not be promoted to trusted skeleton
syntax. The PerlOnJava frontend remains responsible for preserving source
origin and rejecting injected internal tokens.

## Engine API

The fork exposes a runtime-neutral API in `org.joni`:

```java
interface CalloutHandler {
    CalloutResult execute(int calloutId, MatchView match);
    void unwind(Object backtrackToken);
}

interface MatchView {
    int currentBytePosition();
    int captureCount();
    int captureBegin(int capture);
    int captureEnd(int capture);
}
```

The handler is installed on each `Matcher`, not on the compiled `Regex`, so a
shared compiled pattern can execute concurrently with runtime-local callback
state.

`CalloutResult` selects `CONTINUE` or `FAIL` and may carry an opaque unwind
token. The engine neither interprets that token nor depends on PerlOnJava
runtime classes.

## Execution and unwind contract

1. The callout opcode invokes the handler with the callout ID and a read-only
   view of the current byte position and provisional capture boundaries.
2. A non-null token is stored in a matcher stack frame before execution
   continues or fails.
3. Backtracking across that frame calls `unwind(token)` exactly once.
4. Successful completion, failed completion, interruption, timeout, and handler
   exceptions unwind every still-active token in reverse execution order.
5. A `FAIL` result enters normal matcher backtracking after installing the frame,
   so cleanup follows the same path as later-pattern failure.
6. Patterns without callout nodes allocate no handler state or callout frames.

The first engine slice supports plain callouts. Conditional callbacks and
dynamic nested patterns extend the action set only after their Perl semantics
and outer-backtracking behavior are proven independently.

## Capture view

Capture offsets are byte offsets relative to the matcher's input buffer, matching
Joni's native representation. Capture zero describes the provisional overall
match from the current match start to the current instruction position. Unset
captures return `Region.REGION_NOTPOS`. The bridge converts offsets to Perl
character positions when publishing match variables.

The view is valid only during `execute`; handlers must copy any data they retain.
The engine must not allocate a `Region` snapshot for each callout.

## Packaging and namespace isolation

Source code remains in `org.joni`, but the standalone JAR relocates it to
`org.perlonjava.internal.joni`. JCodings is relocated to
`org.perlonjava.internal.jcodings` because Joni's API and implementation refer
to its classes. This lets an embedding application load PerlOnJava beside JRuby
or stock Joni without linkage collisions.

The standalone JAR contains the unmodified Joni and JCodings license texts and a
separate PerlOnJava modification notice under `META-INF/licenses`. The SBOM must
identify the vendored Joni component, its upstream version, MIT license, and
JCodings dependency.

## Implementation stages

1. Import and build the pinned upstream snapshot with its original tests through
   the dedicated root source sets.
2. Replace the external dependency and prove declarative regex parity.
3. Add the callout parser node, opcode, handler API, provisional match view, and
   forward execution.
4. Add matcher-owned callout frames and exact-once cleanup for every exit path.
5. Connect structured Perl callback templates without enabling unsupported
   callback forms.
6. Add conditional and nested-program actions only after focused differential
   tests establish their contracts.

## Verification

- The complete imported Joni suite passes through the root build.
- Existing recursive-regex tests match stock Joni behavior.
- Focused tests cover provisional captures, repeated execution after
  backtracking, `FAIL`, nested frames, handler exceptions, interruption, and
  exact-once reverse-order unwind.
- The standalone JAR contains no `org/joni` or `org/jcodings` classes, contains
  both relocated trees, and includes all required notices.
- An embedding smoke test can load stock Joni and PerlOnJava together.
- Full `make` and the focused direct/thread regex matrix pass.

## Stop conditions

- Do not approximate callbacks as post-match hooks.
- Do not enable a callback form if provisional state or unwind is incomplete.
- Do not implement dynamic patterns as atomic nested matches; their alternatives
  must participate in outer backtracking.
- Keep unsupported syntax fatal until its complete semantic gate passes.
