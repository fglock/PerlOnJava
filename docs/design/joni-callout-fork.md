# Vendored Joni callout engine

This document is the canonical contract for the runtime-neutral Joni fork:
host hooks, token lifecycle, dynamic continuations, and matcher control. The
[regex implementation document](../../dev/implementation/regex.md) is
authoritative for Perl frontend/runtime ownership, Unicode generation,
packaging, source policy, and project verification.

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
`RegexPreprocessor` is absent. Trusted-token materialization prepares callback
IDs without rewriting matcher semantics. Extended-class property context and
source positions now cross the runtime-neutral resolver API, so no host-side
extended-property grammar scan remains. Historical routing fixtures now assert
immutable Joni parser metadata directly; the
`requiresJoniBackend()`/`analyzePerlSyntax()` scanners are gone.
`JoniRegexPattern.patternDescription()` exposes the materialized native source;
there is no compatibility-description translator or alternate matcher input.

This document follows the engine boundary in execution order:

```text
trusted token or property hook
        -> Joni parser and analyser
        -> bytecode plus immutable compiled facts
        -> matcher-local host services
        -> stack-driven completion or unwind
```

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

The API list describes integrated source, not work in another worktree. A new
hook belongs here only after its implementation commit lands and direct Joni
tests establish its runtime-neutral contract.

The fork also exposes immutable facts from the compiled program rather than
asking the host to rescan source spelling: control-verb presence, positive
inline Perl charset modifiers, optimizer information, native bytecode text,
authoritative wide-class coverage, and semantic character-class/debug facts.
The adapter may render Perl-compatible debug labels from proven facts, but the
labels are presentation only.

## Trusted engine syntax

The structured Perl frontend represents executable parts separately from text.
`RuntimeRegexTemplate` assigns each part an index in the regex value's callback
table and stores a private slot sentinel. Sentinel starts in interpolated text
are escaped, and embedded callback-bearing regexes have their slots validated
and renumbered. Only `materializeTrustedCallouts()` converts validated slots to
engine-facing syntax immediately before Joni compilation:

```text
(?{=CALL:<id>})
(?{=DYNAMIC:<id>})
```

`<id>` is a non-negative decimal table index. A CALL token is also used as an
internal conditional predicate when it occurs in a regex condition.
`StringSegmentParser` is the producer of callback AST parts,
`RuntimeRegexTemplate` builds their callback table, and `trustedCalloutCount`
bounds the IDs that Joni accepts. `maskCallouts()` shields trusted slots while
admitted runtime Perl source is reparsed. Literal or interpolated text spelling
`(?{=CALL:0})` has no slot provenance and therefore cannot address the callback
table. The fork remains deliberately unaware of Perl source provenance.

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

Control-state publication and byte-pattern promotion likewise consume compiled
`Regex` facts. Comments, quoted text, and escaped spellings therefore cannot
masquerade as verbs or inline charset modifiers. The prohibition on `\K`
inside lookaround is validated by Joni's analyser, not by a host precheck.

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

The generated-data contract is maintained by the PerlOnJava adapter rather than
the runtime-neutral hook API. Generated tables follow the latest imported
upstream Perl checkout; the current generation records Perl 5.45.2 and Unicode
17.0.0 with input/output hashes. See the canonical
[regex implementation document](../../dev/implementation/regex.md#encoding-and-unicode-ownership)
for precedence, generators, and regeneration gates.

`CharacterPropertyResolver.Context` distinguishes outside, ordinary-class, and
Perl extended-class escapes. This lets the adapter reject string-valued and
unresolved user-defined properties in the correct context while Joni preserves
the exact closing-brace source position; no source rescan is involved.

### Deferred properties

`CharacterPropertyResolver.Result.deferred(...)` marks a property for matcher-
time resolution. The parser stores an immutable `DeferredProperty` containing
its raw and display spelling, `Context`, lexical option bits, source position,
token negation, and enclosing-class role. `Regex` exposes those facts but never
stores a host runtime object or a resolved result.

The host installs a `CharacterPropertyResolver.DeferredResolver` through
`Matcher.setDeferredPropertyResolver()`. The character-class opcode resolves a
term only when reached and caches its `Result` per matcher, class, and term.
Nested dynamic matchers receive the same resolver. Static membership, multiple
deferred terms, local folding, token negation, and enclosing negation are
combined by the native class program. Optimizer analysis declines facts that
could skip a deferred term incorrectly. Patterns without deferred terms do not
allocate the cache or resolver service.

Resolver failures use `CharacterPropertyResolver.ResolutionException` with the
parser-owned source position. Unknown ordinary properties can remain harmless
on an unreachable path and can be retried after the host definition appears;
deferred set algebra in Perl extended classes remains a construction error.
`TestDeferredCharacterProperty`, `TestDeferredCharacterPropertyFacts`, and
`TestDeferredPropertyDebugRendering` are the direct fork contracts; the host
execution and runtime-isolation gates are listed in the implementation document.

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

A direct fork change requires `make test-joni` plus focused tests for the
modified parser/compiler/matcher contract. Callback work must cover provisional
captures, `$^R`, `$^N`, dynamic locals, exceptions, exact-once token resolution,
and nested continuations; matcher-control work must cover each program
boundary. Perl fixtures are validated first with system Perl and then on both
PerlOnJava execution backends. Packaging, full-build, generated-data, and
imported-corpus gates are maintained in the
[canonical verification section](../../dev/implementation/regex.md#verification)
rather than duplicated here.

Never approximate callbacks as post-match hooks, dynamic patterns as atomic
nested matches, or control verbs as source rewrites. Unsupported syntax remains
fatal unless the explicit development-only `JPERL_UNIMPLEMENTED=warn` policy is
requested; that downgrade is not semantic support.

Related documents:

- [Regex implementation](../../dev/implementation/regex.md)
- [Phase 36 acceptance plan](../../dev/design/phase36-regex-parity.md)
- [Standalone library RFC](../../dev/design/perl-regex-library-rfc.md) (proposal only)
