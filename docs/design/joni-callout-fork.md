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

- Joni is a maintained source fork in `third_party/joni`. Its production sources
  and upstream tests are dedicated source sets in the root Gradle project. This
  keeps the code boundary explicit without exposing the vendored engine as a
  separately resolved project dependency.
- The source retains the upstream `org.joni` packages to keep changes reviewable
  and suitable for upstream submission.
- The vendored project is the only Joni implementation on PerlOnJava's compile
  and runtime classpaths.
- JCodings remains an upstream binary dependency because PerlOnJava does not
  modify its encoding implementation.
- Every production regex is compiled by the vendored engine. No Java matcher or
  Java preprocessing adapter remains in the runtime path. The unreferenced
  `RegexBackendPolicy` parser may remain solely as disconnected scaffolding for
  immutable compatibility tests; its environment/property spellings do not
  select a production backend.

## Internal callout syntax

The structured Perl regex frontend emits `(?{=CALL:<id>})` for plain and
conditional callbacks and `(?{=DYNAMIC:<id>})` for dynamic subprograms only in
an engine-facing skeleton. `<id>` is a non-negative decimal index into the
callback table owned by that regex value. The fork parses these representations
into dedicated callout nodes; it never parses Perl source.

Runtime-interpolated pattern text must not be promoted to trusted skeleton
syntax. The PerlOnJava frontend remains responsible for preserving source
origin and rejecting injected internal tokens.

## Engine API

The fork exposes a runtime-neutral API in `org.joni`:

```java
interface CalloutHandler {
    CalloutResult execute(int calloutId, MatchView match);
    default DynamicPatternResult executeDynamic(int calloutId, MatchView match);
    void unwind(Object backtrackToken);
    default void complete(Object successfulToken) { unwind(successfulToken); }
    default void finish(boolean matched) {}
}

final class DynamicPatternResult {
    Regex getRegex();
    CalloutHandler getCalloutHandler();
    Object getBacktrackToken();
}

interface MatchView {
    int currentBytePosition();
    int captureCount();
    int captureBegin(int capture);
    int captureEnd(int capture);
    int lastClosedCapture();
    String controlMark();
}
```

The handler is installed on each `Matcher`, not on the compiled `Regex`, so a
shared compiled pattern can execute concurrently with runtime-local callback
state. `lastClosedCapture()` exposes Perl's capture-close order for `$^N`, which
cannot be derived from the highest-numbered defined capture. `controlMark()`
exposes the current backtracking-visible mark. Both values are provisional.

`CalloutResult` selects `CONTINUE` or `FAIL` and may carry an opaque unwind
token. The engine neither interprets that token nor depends on PerlOnJava
runtime classes.

`DynamicPatternResult` supplies a compiled nested program, its matcher-local
handler, and the token for the dynamic expression's provisional Perl state. The
default `executeDynamic` implementation fails fast, so handlers that only use
plain callouts remain source compatible.

`finish(matched)` is the invocation-level hook after all active tokens have
been completed or unwound. The default is empty because token cleanup is
already complete; PerlOnJava uses it to publish or restore final match state and
to close the matcher's dynamic-local level.

## Execution and unwind contract

1. The callout opcode invokes the handler with the callout ID and a read-only
   view of the current byte position and provisional capture boundaries.
2. A non-null token is stored in a matcher stack frame before execution
   continues or fails.
3. Backtracking across that frame calls `unwind(token)` exactly once.
4. Successful completion calls `complete(token)` for every still-active token in
   reverse execution order. Its default implementation delegates to `unwind`,
   preserving the cleanup-only contract for engine-neutral handlers.
5. Failed completion, interruption, timeout, and handler exceptions unwind every
   already-active token in reverse execution order. A handler that throws must
   clean up state created by that invocation itself because the matcher never
   receives its token.
6. A `FAIL` result enters normal matcher backtracking after installing the frame,
   so cleanup follows the same path as later-pattern failure.
7. A dynamic callout resolves its nested program only when execution reaches
   the opcode. The nested matcher yields one result at a time; an outer failure
   resumes the nested matcher at its next alternative before the outer engine
   backtracks past the dynamic frame.
8. Nested captures remain private to the nested matcher. Its endpoint advances
   the outer input position, while outer capture numbering and match variables
   remain unchanged.
9. Completing or abandoning a nested continuation propagates completion or
   unwind to all of its active callback tokens exactly once.
10. After token resolution, the host calls `finish(true)` for a selected match or
    `finish(false)` for failure. Nested continuations do the same when completed
    or abandoned.
11. Patterns without callout nodes allocate no handler state or callout frames.

Callback conditions use an internal conditional-callout opcode. `CONTINUE`
selects the yes branch and `FAIL` selects the no branch without treating the
condition itself as a failed match.

## Matcher-control boundaries

`(*ACCEPT)` is represented as a dedicated AST node and bytecode opcode; it is
not rewritten into an assertion or callback. At execution it accepts the
nearest matcher-program boundary:

- At the top level, it completes the whole match at the current input position
  and skips the remaining pattern.
- In a subpattern call, it completes that call and resumes the caller after the
  call site.
- In a positive lookahead, it completes the assertion, restores its zero-width
  input position, and resumes the enclosing program.
- In a dynamic `(??{...})` program, it completes the nested matcher while the
  enclosing matcher continues with its suffix.

Captures opened inside the accepted boundary close at the current input
position. Captures outside that boundary remain active. Backtracking and
dynamic-continuation frames abandoned by acceptance are completed exactly once.
Because suffixes after a control verb are not necessarily reachable, programs
containing matcher-control opcodes bypass Joni's ordinary mandatory-literal and
minimum-length optimizer unless that optimizer becomes control-flow aware.

## Structured Perl frontend

Literal callback bodies are represented as lexical `SubroutineNode` closures.
The parser emits a `regexTemplate` operation whose ordered parts contain normal
interpolation values and explicit `regexCallback` wrappers. Runtime template
construction assigns callback IDs and produces the engine-facing skeleton.

Optimistic callbacks use the same structured path. Standalone `(*{ code })`
callbacks execute as zero-width BLOCK callouts and publish their result through
`$^R`; `(?(*{ code })yes|no)` predicates execute as CONDITION callouts, select
their branch from the result's truth, and leave `$^R` unchanged. The matcher
publishes `$^N` from capture-close order rather than deriving it from `$+`, whose
meaning is the highest-numbered defined capture.

An interpolated coderef remains ordinary interpolation because only the parser
can create a callback wrapper. Runtime strings containing Perl eval groups never
become trusted callback skeletons. They retain the existing security checks and,
where compatibility mode permits unsupported eval groups, the existing
zero-width no-op behavior.

At execution, the bridge publishes provisional numbered captures and offsets,
runs the closure in scalar context, and updates `$^R` for plain callbacks only.
Each token checkpoints regex state, `$^R`, and the dynamic-local stack. Backtrack
unwind restores all three; successful completion restores dynamic scope while
retaining the last successful plain callback result. Dynamic locals created by
one callback remain active along the matcher's forward path, including later
callbacks, until that path is unwound or the match completes. The ordinary
closure epilogue therefore does not tear down a callback's local frame; the
matcher token owns it.

Callback closures are Perl pseudo blocks, not ordinary subroutine or loop
boundaries. An unhandled `last`, `next`, `redo`, or `goto` cannot target a loop
or label outside the callback. The bridge converts escaped control flow to the
corresponding pseudo-block diagnostic before it can reach surrounding Perl
frames. Recursive callback re-entry has an engine-owned depth ceiling independent
of the JVM's configured native stack size.

Every structured executable callback template executes in Joni, including a
callback whose body happens to return a constant: `(?{ 1 })` still has
match-time side effects and unwind semantics. Runtime-dependent dynamic-pattern
expressions execute only when their opcode is reached. Semantically safe
constant expressions may use the compile-time fold path; constants with
captures or top-level alternatives remain dynamic so they cannot change outer
grouping or capture numbers.

Literal match targets have one stable scalar identity per compiled call site so
`pos()` and `/g` survive repeated loop execution without allowing two identical
literal occurrences to share state.

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

## Source-policy and matcher boundary

The former `RegexPreprocessor` and Java matcher adapter are absent. The shipped
boundary is now:

- `StringSegmentParser`, `RuntimeRegexTemplate`, `RuntimeRegexSourceCompiler`,
  and `RuntimeRegex` own Perl source provenance, trusted callback templates,
  `use re 'eval'`, lexical policy, user-defined properties, source locations,
  diagnostics, and runtime match state.
- `JoniRegexPattern` converts that policy into a Joni `Syntax`, options, resolver
  hooks, and trusted internal tokens. It also converts between Perl character
  offsets and matcher byte offsets.
- The fork owns matcher-visible grammar and behavior. Native Joni parses and
  executes extended classes, `(?(DEFINE)...)`, named characters, subpattern
  calls, conditions, control verbs, callback/dynamic opcodes, folding, captures,
  backtracking, and optimizer selection.

Text normalization may express source policy, but it must not emulate behavior
that depends on capture close order, matcher regions, encoding, backtracking, or
callback execution. Semantically safe constant dynamic expressions may fold to
pattern text; embedded closures never become compile-time constants because
their execution and unwind are observable.

`RuntimeRegex` caches compiled Joni values per Perl runtime. The key includes
source/modifiers, lexical debug and strict state, trusted callout count, byte
provenance, and custom-charname translator identity. A handler remains
matcher-local. Joni owns compiled optimizer metadata, and disables ordinary
search optimization where control-flow or dynamic-option opcodes make its
assumptions unsafe.

Unicode programs use UTF-8 and explicit byte/character offset maps. Byte-mode
programs use ISO-8859-1 identity maps. The PerlOnJava `CharacterPropertyResolver`,
`NamedCharacterResolver`, and `WideScalarCodec` hooks supply current-Perl
property/name data and reversible signed/wide scalar encodings without giving
the fork access to runtime objects. Property-value wildcard expressions execute
through Joni's runtime-neutral `PerlPropertyValueMatcher`.

Generated tables follow the latest safely fast-forwarded upstream `perl5/`
checkout. The exact consumed revision and hashes are provenance for a generation,
not a permanent target SHA. Deterministic second-generation checks protect the
checked-in data while allowing the next refresh to advance upstream.

## Remaining integration gates

The canonical checklist is `dev/design/phase36-regex-parity.md`; do not promote
independently staged P3/P4/P5 work to integrated behavior. At this snapshot the
recorded residuals include:

- remaining native lexer/parser diagnostic families and exact source rendering;
- forward/reverse literal and character-class fold expansion;
- classification of the disconnected selector/fallback parser while keeping
  production Java routing absent; immutable compatibility tests are not a
  deletion target without explicit authority;
- one immutable integrated Unicode/regex acceptance artifact, followed by the
  complete corpus, performance, packaging, notice, and platform gates.

Unsupported syntax remains fatal unless the explicit development-only
`JPERL_UNIMPLEMENTED=warn` downgrade is requested. That downgrade is never
evidence of semantic support.

## Verification

- The complete imported Joni suite passes through the root build.
- Existing recursive-regex tests match stock Joni behavior.
- Focused tests cover provisional captures, repeated execution after
  backtracking, `FAIL`, nested frames, handler exceptions, interruption, and
  exact-once reverse-order unwind.
- Optimistic-callback tests cover standalone execution, conditional truth,
  `$^R`, exact `$^N` capture-close order, `$+`, and alternative reachability.
- The standalone JAR contains no `org/joni` or `org/jcodings` classes, contains
  both relocated trees, and includes all required notices.
- An embedding smoke test can load stock Joni and PerlOnJava together.
- Full `make` and the focused direct/thread regex matrix pass.
- The executable-callback and dynamic-pattern unit tests pass under standard
  Perl and both PerlOnJava execution backends.
- Dynamic-pattern tests cover delayed expression evaluation, returned strings
  and `qr//` values, private nested captures, outer-suffix backtracking, nested
  callback cleanup, and recursive `qr//` values.
- Matcher-control tests cover top-level, empty, subpattern-call, positive
  lookahead, and dynamic-program acceptance, including capture endpoints and
  skipped callbacks.
- `dev/tools/perl_test_runner.pl perl5_t/t/re/` is compared file-by-file with
  `../PerlOnJava/logs/test_20260815_080000_958.log`; no previously passing regex
  file may regress, and changed pass counts or blocked-test totals are reported.

## Stop conditions

- Do not approximate callbacks as post-match hooks.
- Do not enable a callback form if provisional state or unwind is incomplete.
- Do not implement dynamic patterns as atomic nested matches; their alternatives
  must participate in outer backtracking.
- Keep unsupported syntax fatal until its complete semantic gate passes.
