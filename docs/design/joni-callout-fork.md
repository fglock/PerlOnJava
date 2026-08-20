# Vendored Joni callout engine

## Purpose and boundary

Perl executable regex constructs run while the matcher owns provisional
captures and its backtracking stack. A post-match hook cannot implement their
semantics. PerlOnJava therefore maintains a Joni source fork with runtime-neutral
callout and dynamic-subprogram APIs.

The fork is a matcher, not a Perl evaluator. It receives opaque integer IDs and
calls an interface; it never imports PerlOnJava runtime classes or parses Perl
callback bodies. The Perl frontend and runtime own source admission, closure
capture, `use re 'eval'`, diagnostics, and Perl-visible state.

All production regexes use this fork. There is no Java matcher, and the legacy
`RegexPreprocessor` is absent. Limited Java source-policy scanning and adapter
translation still prepare admitted source for native Joni. The historical
`RegexBackendPolicy` model exists only in test scope, while
`JoniRegexPattern.compatibilityPatternDescription` is a display compatibility
surface; neither can become alternate matcher input.

## Source and dependency model

Maintained sources live in `third_party/joni/src/org/joni` and retain upstream
`org.joni` packages so the fork remains reviewable. Gradle adds them to the main
source set and compiles imported fork tests from the separate `joniTest` source
set. JCodings remains an upstream binary dependency; the fork uses its encoding
and baseline fold primitives without depending on a modified JCodings API.

The fork adds parser nodes, bytecode, matcher stack entries, and the following
public hooks:

- `CalloutHandler`, `CalloutResult`, `CalloutAction`, and `MatchView`;
- `DynamicPatternResult` for host-supplied nested programs;
- `CharacterPropertyResolver` and its range result;
- `NamedCharacterResolver`;
- `WideScalarCodec`;
- `PerlPropertyValueMatcher` for runtime-neutral property-value wildcards.

Keep these APIs free of `org.perlonjava` types. Engine tests should be expressible
with only Joni and JCodings classes.

## Trusted engine syntax

The structured Perl frontend represents executable parts separately from text.
`RuntimeRegexTemplate` assigns each part an index in the regex value's callback
table and emits an engine-facing token:

```text
(?{=CALL:<id>})
(?{=DYNAMIC:<id>})
```

`<id>` is a non-negative decimal table index. A CALL token is also used as an
internal conditional predicate when it occurs in a regex condition.
`StringSegmentParser` is the producer of callback AST parts,
`RuntimeRegexTemplate` builds their callback table, and `trustedCalloutCount`
bounds the IDs that Joni accepts. `maskCallouts()` shields token-shaped text
while admitted runtime Perl source is reparsed.

The assembled pattern currently has no per-segment provenance marker. Raw
interpolated text that spells an in-range internal ID can therefore collide with
a parser-created callback entry. This is a known runtime-template boundary gap.
The fork deliberately remains unaware of Perl source provenance; the adapter
must close the gap rather than teaching Joni to execute source text.

## Runtime-neutral API

The central interfaces are:

```java
interface CalloutHandler {
    CalloutResult execute(int calloutId, MatchView match);
    default DynamicPatternResult executeDynamic(int calloutId, MatchView match);
    void unwind(Object backtrackToken);
    default void complete(Object successfulToken) { unwind(successfulToken); }
    default void finish(boolean matched) {}
}

interface MatchView {
    int currentBytePosition();
    int captureCount();
    int captureBegin(int capture);
    int captureEnd(int capture);
    int lastClosedCapture();
    String controlMark();
}

final class DynamicPatternResult {
    Regex getRegex();
    CalloutHandler getCalloutHandler();
    Object getBacktrackToken();
}
```

`CalloutResult` chooses `CONTINUE` or `FAIL` and may carry an opaque token.
`MatchView` is read-only and valid only during the call; capture and position
values are Joni byte offsets, and unset captures use `Region.REGION_NOTPOS`.
`lastClosedCapture()` represents capture-close order for Perl's `$^N`, which is
not equivalent to the highest-numbered active capture. `controlMark()` exposes
the current backtracking-visible mark.

The handler is installed on a `Matcher`, not on the compiled `Regex`. One
compiled program can therefore be shared while each operation keeps independent
callback state. Handlers that do not implement dynamic programs inherit a
fail-fast `executeDynamic` default.

## Token lifecycle

The matcher and handler share this exact contract:

1. The callout opcode invokes the handler with its ID and provisional view.
2. A returned non-null token is stored in a matcher stack entry before normal
   execution or failure continues.
3. Backtracking across that entry calls `unwind(token)` exactly once.
4. A token retained by the selected successful path receives
   `complete(token)` exactly once, in reverse execution order.
5. A `FAIL` result enters ordinary backtracking after its token is registered.
6. Active tokens are resolved before `finish(true)` or `finish(false)` closes
   the matcher invocation.
7. If a handler throws before returning a token, the handler must clean up state
   created by that invocation; Joni cannot register a token it never received.

`complete` defaults to `unwind`, so cleanup-only handlers remain valid. The Perl
bridge overrides completion because a successful callback retains different
state from an abandoned one. Patterns without callout nodes do not allocate
Perl callback handlers or callout stack frames.

For the top-level matcher, `JoniRegexMatcher` calls `finish` after Joni resolves
the token stack. Joni's nested dynamic continuation calls its own handler's
`finish` when that continuation completes or is abandoned.

## Dynamic subprograms

`executeDynamic` returns a compiled nested `Regex`, its matcher-local handler,
and the token for the dynamic expression's provisional state. Joni creates a
nested matcher only when execution reaches the opcode.

The nested matcher is a continuation, not an atomic boolean test. It yields one
candidate endpoint at a time; if the outer suffix fails, the outer matcher asks
the continuation for its next alternative before abandoning the dynamic frame.
Nested captures remain private, while the selected endpoint advances the outer
input. Completion or abandonment resolves every nested callback token and calls
the nested handler's `finish` exactly once.

This behavior is required for `(??{...})`: expression evaluation is delayed,
returned strings and `qr//` values participate in outer backtracking, and a
recursive returned regex uses the same continuation contract.

## Perl bridge

`JoniRegexPattern.PerlCalloutHandler` is the runtime implementation. Before
running a closure it publishes provisional numbered captures and offsets. A
token checkpoints Perl regex/capture state, the prior `$^R`, dynamic-local
level, result metadata, and, for dynamic evaluation, the prior capture view.
The callback's `pos` value is published and restored around invocation; it is
not stored in the token. Control marks are exposed through `MatchView`, not
copied into every token.

Plain callbacks run in scalar context and update `$^R`; conditional callbacks
select a branch without changing `$^R`. `$^N` comes from `lastClosedCapture`,
not from `$+`. Forward execution keeps callback locals visible to later
callbacks on the same path. `unwind` restores abandoned state; `complete`
restores dynamic scope while retaining the selected callback result; `finish`
publishes or restores the final operation state.

Callback closures are Perl pseudo blocks. Unhandled `last`, `next`, `redo`, or
`goto` cannot escape into an enclosing Perl loop or label. Callback exceptions
restore the bridge's dynamic level even though no Joni token was returned.
The PerlOnJava pseudo-block runtime limits recursive callback re-entry to 1,024
levels independently of JVM native stack size.

Every parser-created executable callback remains match-time observable. A body
that happens to return a constant is not converted into a non-executable
pattern. Only semantically safe constant dynamic expressions may use the
compile-time fold path; captures or top-level alternatives keep a dynamic
program boundary.

## Matcher control and optimization

Control verbs are native AST nodes and opcodes. `(*ACCEPT)` accepts the nearest
matcher-program boundary: the whole top-level program, a subpattern call, a
positive lookahead, or a dynamic nested program. It closes captures opened in
that boundary at the current position and completes abandoned callback and
continuation frames exactly once. `(*FAIL)`, `(*PRUNE)`, `(*SKIP)`, `(*THEN)`,
`(*COMMIT)`, and mark forms likewise participate in native backtracking.

Optimizer facts are derived from the Joni AST. Programs with control-verb state
or dynamic options bypass ordinary search optimization. Dynamic callout nodes
have unbounded maximum length, and Perl multi-fold nodes can suppress literal
and minimum-length facts when those facts would be unsafe. Do not restore an
optimizer shortcut without testing its control-flow, encoding, and fold
assumptions.

## Encoding and property hooks

Joni works in encoding byte offsets. Unicode programs use UTF-8; byte programs
use ISO-8859-1. PerlOnJava maps offsets at the adapter boundary. `WideScalarCodec`
lets the engine recognize PerlOnJava's reversible internal representation for
surrogate and above-Unicode scalar values without learning about Perl runtime
objects.

`CharacterPropertyResolver.Result` carries inclusive Unicode and wide ranges
plus the property's case-fold policy. The parser preserves that provenance
through ordinary and extended class algebra. `NamedCharacterResolver` supplies
scalar names and sequences. `PerlPropertyValueMatcher` compiles the value-side
regex used by Perl property wildcards with Joni semantics.

JCodings remains responsible for byte traversal and baseline folds. Generated
current-Perl tables and maintained Joni policy add Perl aliases, boundary rules,
simple and multi-code-point closure, byte provenance, and `/d`, `/u`, `/a`, and
`/aa` eligibility.

## Packaging and notices

Source packages stay `org.joni`, but the standalone JAR relocates Joni to
`org.perlonjava.internal.joni` and JCodings to
`org.perlonjava.internal.jcodings`. This prevents linkage collisions when an
embedding application also loads JRuby or stock Joni.

The JAR contains byte-identical copies of:

- `third_party/joni/LICENSE` as `META-INF/licenses/joni-LICENSE.txt`;
- `third_party/licenses/jcodings-LICENSE.txt`;
- `third_party/joni/PERLONJAVA-NOTICE.md` as
  `META-INF/licenses/joni-PERLONJAVA-NOTICE.md`.

The combined SBOM identifies vendored Joni 2.2.7, JCodings 1.0.64, their
licenses, and the Joni-to-JCodings dependency. Do not remove or rewrite upstream
copyright headers in maintained sources.

## Change verification

A fork change requires evidence at the boundary it modifies:

- `make test-joni` for direct lexer, parser, compiler, optimizer, matcher, and
  callout behavior;
- system-Perl validation followed by both PerlOnJava execution backends for
  changed Perl fixtures;
- focused callback tests for provisional captures, `$^R`, `$^N`, dynamic
  locals, exceptions, exact-once unwind/completion, and nested continuations;
- matcher-control tests for top-level, subpattern, lookahead, and dynamic
  program boundaries;
- `make` for the complete unit, fork, packaging, and notice gate;
- the Gradle `verifyJoniPackaging` task, run by `make`, which invokes
  `dev/tools/verify-joni-packaging.pl` with the standalone JAR and merged SBOM;
- the imported `perl5_t/t/re/` corpus compared file by file against its accepted
  baseline.

Never approximate callbacks as post-match hooks, dynamic patterns as atomic
nested matches, or control verbs as source rewrites. Unsupported syntax remains
fatal unless the explicit development-only `JPERL_UNIMPLEMENTED=warn` policy is
requested; that downgrade is not semantic support.
