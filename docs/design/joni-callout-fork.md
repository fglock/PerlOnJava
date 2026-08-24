# Vendored Joni callout engine

This document is the canonical contract for the runtime-neutral Joni fork:
host hooks, token lifecycle, dynamic continuations, and matcher control. The
[regex implementation document](../../dev/implementation/regex.md) is
authoritative for Perl frontend/runtime ownership, Unicode generation,
packaging, source policy, and project verification.

This is an implemented internal contract, not a declaration of complete Perl
regex or release parity. Changing acceptance status remains the responsibility
of the active Phase 36 plan after all pending implementation and platform gates
are integrated.

## Purpose and boundary

Perl executable regex constructs run while the matcher owns provisional
captures and its backtracking stack. A post-match hook cannot implement their
semantics. PerlOnJava therefore maintains a Joni source fork with runtime-neutral
callout and dynamic-subprogram APIs.

The fork is a matcher, not a Perl evaluator. It receives opaque integer IDs and
calls an interface; it never imports PerlOnJava runtime classes or parses Perl
callback bodies. The Perl frontend and runtime own source admission, closure
capture, `use re 'eval'`, diagnostics, and Perl-visible state.

All matcher execution enters this fork. Trusted-token materialization prepares
callback IDs without rewriting matcher semantics. Extended-class property
context and source positions cross the runtime-neutral resolver API, so no
host-side extended-property grammar scan is needed.
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
- `CharacterPropertyResolver` and its eager/deferred results;
- `NamedCharacterResolver`;
- `LocaleResolver` for matcher-local `/l` semantics;
- `WideScalarCodec`;
- `PerlPropertyValueMatcher` for runtime-neutral property-value wildcards.

Keep these APIs free of `org.perlonjava` types. Engine tests should be expressible
with only Joni and JCodings classes.

These `org.joni` hooks are public only at the maintained-source boundary; they
are not a separately versioned application API. An ABI change must update the
PerlOnJava adapter and direct fork tests together. The shaded distribution
is intended to relocate the packages and does not promise binary compatibility
for consumers that reach into them. Exact relocation is an artifact property
which must be verified for the release candidate, not inferred from source
package names.

The fork also exposes immutable facts from the compiled program rather than
asking the host to rescan source spelling: control-verb presence, positive
inline Perl charset modifiers, optimizer information, native bytecode text,
authoritative wide-class coverage, retained synthetic start-class provenance,
and semantic character-class/debug facts.
The adapter may render Perl-compatible debug labels from proven facts, but the
labels are presentation only.

## Maintenance boundary

| Change | Owner and required boundary |
| --- | --- |
| Perl interpolation, lexical hints, `use re 'eval'`, closure capture, warning policy, or source ownership | PerlOnJava frontend/runtime; pass only opaque IDs or runtime-neutral facts into Joni. |
| Matcher grammar, AST validation, bytecode, captures, recursion, backtracking, control verbs, class algebra, or optimization | Joni parser/analyser/compiler/matcher; do not emulate it with host source rewriting. |
| Callout, dynamic-continuation, locale, name, property, or wide-scalar hook shape | Runtime-neutral Joni interface plus matching adapter change and direct fork contract tests. |
| Perl wording, warning category/fatality, character-offset conversion, or published match variables | PerlOnJava adapter/runtime consuming Joni events and positions. |
| Relocation, dependency metadata, notices, or SBOM content | Gradle packaging and verification tooling; source package names remain unchanged. |

State follows the same boundary:

| State | Lifetime and owner |
| --- | --- |
| Compiled grammar, bytecode, and semantic/optimizer facts | Immutable `Regex`; shareable across wrappers, runtimes, and ithread clones. |
| Input bytes, capture region, backtracking stack, locale/property services, and installed callout handler | One `Matcher` invocation. |
| Opaque callback token | Host-owned snapshot registered on the matcher stack and resolved exactly once by `unwind` or `complete`. |
| Perl captures, `$^R`, dynamic locals, `pos`, and callback CODE pads | PerlOnJava runtime; provisionally published through `MatchView`, never stored in `Regex`. |

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
    default DynamicPatternResult executeDynamic(
        int calloutId, int effectiveOptions, MatchView match);
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
    CharacterPropertyResolver.DeferredResolver getDeferredPropertyResolver();
}
```

`CalloutResult` chooses `CONTINUE` or `FAIL` and may carry an opaque token.
`MatchView` is read-only and valid only during the call; capture and position
values are Joni byte offsets, and unset captures use `Region.REGION_NOTPOS`.
`lastClosedCapture()` represents capture-close order for Perl's `$^N`, which is
not equivalent to the highest-numbered active capture. `controlMark()` exposes
the current backtracking-visible mark. The two-argument dynamic overload is
the compatibility form. `OP_DYNAMIC_CALLOUT` supplies the option bits active at
the callout site to the three-argument form; the Perl adapter reconstructs the
nested `RegexFlags` from those bits, rather than inheriting a stale outer
modifier set.

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

The callout side-effect decision is made against the next semantic matcher
operation. Capture setup and repeat bookkeeping are zero-width wrappers, so a
callback immediately before `(...) {1,}` is classified by the wrapped consuming
operation, not by the first bookkeeping opcode. This preserves Perl's
same-position failure behavior without teaching the Perl bridge about Joni
repeat bytecode.

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
not from `$+`. The bridge derives read-only `@{^CAPTURE}` from the numbered
capture view, beginning with `$1` and excluding the whole-match slot; this is
Perl-visible adapter state, not state stored in Joni's immutable `Regex`.
Forward execution keeps callback locals visible to later callbacks on the same
path. `unwind` restores abandoned state; `complete` restores dynamic scope while
retaining the selected callback result; `finish` publishes or restores the final
operation state.

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

Script runs are native scoped programs, not host-side post-match checks. The
parser recognizes `(*script_run:...)`/`(*sr:...)` and their atomic forms,
emits a script-run opcode, and records compiled program metadata. Normal group
completion validates the consumed span through
`CharacterPropertyResolver.isScriptRun()`. Stack entries reactivate the
validation boundary when backtracking re-enters it; atomic script runs also cut
internal alternatives. `(*ACCEPT)` observes the nearest active boundary, which
preserves Perl's different outcomes when ACCEPT occurs inside an uncaptured run
or after a captured run has completed.

Optimizer facts are derived from the Joni AST. Programs with control-verb state
or dynamic options bypass ordinary search optimization. Dynamic callout nodes
have unbounded maximum length, and Perl multi-fold nodes can suppress literal
and minimum-length facts when those facts would be unsafe. When a
zero-lower-bound quantifier precedes a floating exact, Joni retains the
synthetic start-class map as a compiled presentation fact without using that
map to exclude the empty branch. Do not restore an optimizer shortcut without
testing its control-flow, encoding, and fold assumptions.

Control-state publication and byte-pattern promotion likewise consume compiled
`Regex` facts. Comments, quoted text, and escaped spellings therefore cannot
masquerade as verbs or inline charset modifiers. KEEP/lookaround validity is
decided by Joni's analyser, not by a host precheck. The current analyser rejects
direct `\K` in positive and negative lookahead and lookbehind, matching the
exact selected Perl v5.45.3 executable. The contrary POD sentence is a
documented upstream POD/executable divergence; rejection is the supported
current-Perl behavior, not an unimplemented matcher feature. The semantic
capability map records that narrow divergence in its evidence while retaining
the schema-v2-compatible primary status; ordinary `\K` rows remain part of the
implemented ordinary-atoms family.

## Encoding and property hooks

Joni works in encoding byte offsets. Unicode programs use UTF-8; byte programs
use ISO-8859-1. PerlOnJava maps offsets at the adapter boundary. `WideScalarCodec`
lets the engine recognize PerlOnJava's reversible internal representation for
surrogate and above-Unicode scalar values without learning about Perl runtime
objects.

`CharacterPropertyResolver.Result` carries inclusive Unicode and wide ranges
plus the property's case-fold policy, or an immutable marker for matcher-time
resolution. The parser preserves that provenance through ordinary and extended
class algebra. `LocaleResolver` supplies matcher-local locale class and fold
behavior. `NamedCharacterResolver` supplies scalar names and sequences.
`PerlPropertyValueMatcher` compiles the value-side regex used by Perl property
wildcards with Joni semantics.

JCodings remains responsible for byte traversal and baseline folds. Generated
current-Perl tables and maintained Joni policy add Perl aliases, boundary rules,
simple and multi-code-point closure, byte provenance, and `/d`, `/u`, `/a`, and
`/aa` eligibility.

The generated-data contract is maintained by the PerlOnJava adapter rather than
the runtime-neutral hook API. Generated tables follow the latest imported
upstream Perl checkout, and the generator manifest records the source commit,
versions, and input/output hashes as reproducible provenance rather than a
permanent pin. See the canonical
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

Source packages remain `org.joni` for reviewability. The standalone packaging
contract intends to relocate Joni and JCodings, carry the upstream licenses and
fork notice, and describe both components and their dependency in the combined
SBOM. Vendoring does not transfer authorship: upstream headers remain intact,
and fork-owned notices describe PerlOnJava modifications without replacing
upstream copyright or license terms.

The canonical paths, relocation targets, and fail-closed verifier contract are
documented in the
[implementation reference](../../dev/implementation/regex.md#fork-and-distribution).
They must be evaluated against the exact candidate JAR, notice bytes, and SBOM;
this design does not assert that a release identity has passed those gates.

## Change verification

A direct fork change requires `make test-joni` plus focused tests for the
modified parser/compiler/matcher contract. Callback work must cover provisional
captures, `$^R`, `$^N`, dynamic locals, exceptions, exact-once token resolution,
and nested continuations; matcher-control work must cover each program
boundary. Script-run work must cover native parser metadata, normal and atomic
unwind, backtracking, and nested ACCEPT behavior through `TestControlVerb`,
`TestRegexParsedProgramMetadata`, `regex_joni_native_script_run.t`, and
`script_run_accept_boundary.t`. Perl fixtures are validated first with system
Perl and then on both PerlOnJava execution backends. Packaging, full-build,
generated-data, and imported-corpus gates are maintained in the
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
