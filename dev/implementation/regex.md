# Regex implementation

This is the canonical end-to-end description of regex ownership in
PerlOnJava. The narrower
[`joni-callout-fork.md`](../../docs/design/joni-callout-fork.md) documents the
runtime-neutral fork API and matcher lifecycle; it intentionally does not
repeat frontend, Unicode-generation, packaging, or release-policy details.

## Runtime boundary

PerlOnJava has one production regex engine: the maintained Joni fork in
`third_party/joni`. `RuntimeRegex` is the Perl runtime boundary and
`JoniRegexPattern` is the only adapter that constructs a matcher. The JVM and
bytecode-interpreter execution backends call this same runtime code; neither has
its own regex implementation.

Some regex-package classes use `java.util.regex.Pattern` as a bounded text
scanner for source policy or diagnostics. Those calls never compile or match a
Perl pattern. “No Java matcher” means there is no production path from a Perl
regex operation to `java.util.regex.Matcher`; it does not prohibit ordinary
Java text utilities elsewhere in the runtime.

Legacy backend-selector settings have no production consumer.
`JoniRegexPattern.patternDescription()` returns the materialized native
`sourcePattern`; no compatibility translator or second matcher-like source
description remains.

This separation is the central maintenance rule:

- PerlOnJava owns source provenance, lexical policy, runtime state, and Perl
  callbacks.
- Joni owns matcher-visible grammar and behavior.
- Pattern descriptions and diagnostics preserve native source identity.

The execution path is:

```text
Perl source / interpolation
        -> RuntimeRegexTemplate (trusted closure slots)
        -> RuntimeRegex (lexical policy, variants, Perl-visible state)
        -> JoniRegexPattern (runtime-neutral adapters)
        -> org.joni.Regex / Matcher (parse, compile, optimize, execute)
        -> RuntimeRegex (publish captures, pos, replacement, and warnings)
```

## Responsibility map

| Responsibility | Production classes |
| --- | --- |
| Parse literal/interpolated regex source, apply lexical regex-constant handlers, and capture executable closures | `StringSegmentParser`, `ConstantOverloadParser`, `RegexLiteralAnalyzer` |
| Preserve trusted callback provenance while assembling interpolated patterns | `RuntimeRegexTemplate`, `RuntimeRegexCallback` |
| Compile runtime source admitted by `use re 'eval'` in its Perl lexical context | `RuntimeRegexSourceCompiler` |
| Cache compiled variants and implement Perl-visible match/substitution state | `RuntimeRegex`, `RegexFlags` |
| Adapt encoding, diagnostics, resolver hooks, callbacks, compiled facts, and Joni match results | `JoniRegexPattern` and its `JoniRegexMatcher` / `PerlCalloutHandler` nested classes |
| Resolve Perl Unicode properties and names with generated-table precedence | `UnicodeResolver`, `NamedCharacterExpansion`, `PerlUnicode*Data` |
| Parse and execute matcher semantics without Perl runtime dependencies | `org.joni.Regex`, `Parser`, `Analyser`, `ArrayCompiler`, `ByteCodeMachine` |
| Expose runtime-neutral host hooks | `CalloutHandler`, `MatchView`, `DynamicPatternResult`, `CharacterPropertyResolver`, `NamedCharacterResolver`, `LocaleResolver`, `PerlPropertyValueMatcher`, `WideScalarCodec` |

## From Perl source to a compiled pattern

`StringSegmentParser` distinguishes literal source, interpolation, and
parser-created executable blocks. It emits `regexCallback` nodes inside a
`regexTemplate`; JVM lowering and interpreter lowering preserve the same node
contract.

When lexical `overload::constant qr` is active, `ConstantOverloadParser` calls
the handler while parsing each constant regex segment, before later source can
change values observed by the handler. Its raw argument retains source octets,
its cooked argument follows ordinary literal byte/Unicode provenance, and the
handler result is retained as the segment value. Internal reparsing suppresses
the already-consumed hint so callback-bearing results are not overloaded a
second time. This is Perl source policy; matching the resulting program still
uses Joni exclusively.

`RuntimeRegexTemplate` assembles the runtime value. Only parser-created callback
wrappers allocate entries in the callback table. They are represented by
private slot sentinels, not by matcher syntax; sentinel starts in interpolated
text are doubled, so untrusted text cannot acquire callback provenance.
Embedding a callback-bearing `qr//` validates and renumbers its slots.
`maskCallouts()` hides trusted slots while runtime Perl source is parsed, and
`materializeTrustedCallouts()` turns only validated, in-range slots into Joni
tokens immediately before compilation. `trustedCalloutCount` supplies the
second bounds check. A lone interpolated `qr//` retains its compiled identity,
preserving its flags, callbacks, and overload behavior.

`RuntimeRegexSourceCompiler` handles executable regex source admitted by
`use re 'eval'`. It compiles with the construction site's package, lexical
cells, hints, warning bits, byte provenance, and operation flags, using a fresh
synthetic `(eval N)` filename at line 1. Dynamic source is therefore checked as
Perl source before it becomes a Joni program; it is not evaluated by the regex
engine.

The `re` module records lexical regex policy in `ScopedSymbolTable`.
`StringParser` combines those defaults with an operator's explicit modifiers
before `RegexFlags` is constructed. Supported state includes `strict`, `eval`,
`taint`, debug modes, and `/a`, `/aa`, `/d`, `/i`, `/l`, `/m`, `/n`, `/p`, `/s`,
`/u`, `/x`, and `/xx`. Explicit pattern charset modifiers take precedence over
lexical defaults; `no re '/flags'` selectively cancels state, and nested scopes
restore the exact `/x` versus `/xx` level. `re::is_regexp`,
`re::regexp_pattern`, and `re::optimization` expose compiled values and
Joni-selected facts without creating another matcher path.

`RuntimeRegex.compileSynchronized()` performs the remaining Perl-side checks,
constructs `RegexFlags`, and creates `JoniRegexPattern` variants. Its cache key
includes source and modifiers, lexical debug and `re 'strict'` state, trusted
callout count, effective byte provenance, and custom-`charnames` translator
discriminator. The translator and its `NamedCharacterCache` remain attached to
the compiled regex. User-defined property callbacks are represented by native
deferred Joni class terms as described below.

`JoniRegexPattern` supplies Joni `Syntax`, options, resolver hooks, warning
mapping, and trusted-token materialization. Production matcher input otherwise
retains the admitted source spelling. Native Joni parses and executes groups,
captures, calls and recursion, conditions, lookarounds, the full supported
control-verb family, quantifiers, ordinary and extended character classes,
named characters, properties, case folding, callbacks, and dynamic
subprograms. Joni's analyser, compiler, and matcher—not a Java spelling
scanner—own the corresponding behavior and diagnostics.

The compiled `org.joni.Regex` is also the authority for facts consumed by the
adapter. These include actual control-verb presence (including unnamed verbs),
positive inline Perl charset modifiers, optimizer metadata (including the
retained synthetic start class beside a floating exact), the native instruction
listing, authoritative wide-class coverage, and immutable semantic facts about
the first compiled character-class program. Perl-compatible debug labels such
as `SANY`, `OPFAIL`, `REG_ANY`, `ANYOFR`, and `ANYOFHbbm` are rendered only when
those compiled facts prove the shape; otherwise debug output falls back to
Joni's native bytecode. Debug presentation never becomes matcher input.

## Matching and Perl state

A compiled `RuntimeRegex` can hold an ordinary Joni variant, a Unicode variant,
and a byte-pattern variant for byte-backed `/d` case-fold behavior. A
locale-bearing `JoniRegexPattern` also retains its non-UTF-8 locale program;
matcher construction selects it from the current `LC_CTYPE` state and installs
matcher-local locale, deferred-property, and callback services as needed.
`selectRecursivePattern()` chooses among them from the pattern and subject
provenance. Every match operation creates a matcher and, when needed, a
matcher-local callback handler; compiled patterns do not share provisional
callback state.

`RuntimeRegex` owns Perl-visible behavior around the matcher: scalar and list
context, `pos`, `/g`, `/c`, `\G`, substitutions, match variables, named and
numbered captures, preserve-match state, and last-successful-pattern reuse.
Joni reports byte offsets. The adapter maps them to Perl character offsets
before publishing `$&`, `$1`, `@-`, `@+`, and `pos`.

`$^N` is published from Joni's last-closed-capture fact, not from the
highest-numbered active capture used by `$+`. `@{^CAPTURE}` is a dynamic,
read-only view of the numbered capture buffers: index zero is `$1`, negative
indexes follow ordinary Perl array rules, nonparticipating groups are `undef`,
and the whole-match slot used by `@-` and `@+` is not included. Published values
retain byte/Unicode and taint provenance. Failed matches preserve or clear state
according to the operation's Perl contract, including the `/p` variables
`${^PREMATCH}`, `${^MATCH}`, and `${^POSTMATCH}`.

Global matching implements Perl's empty-match rule explicitly: after returning
one zero-width match at an offset, the next attempt first asks for a consuming
match at that same offset with all further empty results suppressed. Only then
may it advance by one Perl character. Substitution uses the same semantic rule.

## Executable callbacks and dynamic patterns

The engine-facing skeleton uses `(?{=CALL:<id>})` and
`(?{=DYNAMIC:<id>})`. The fork parses these as dedicated nodes and opcodes; it
does not parse or execute Perl source.

`JoniRegexPattern.PerlCalloutHandler` publishes provisional capture state and
runs the captured Perl closure in scalar context. Each callback token
checkpoints Perl regex/capture state, the prior `$^R`, dynamic-local level,
result metadata, and, for dynamic evaluation, the prior capture view. Callback
position is published and restored around invocation rather than stored in the
token. Joni's matcher stack calls `unwind` when backtracking abandons the token
and `complete` when the selected path keeps it. The adapter finishes the
top-level invocation; nested Joni continuations finish their own handlers. The
bridge restores dynamic scope on both normal and exceptional paths.

A dynamic `(??{...})` expression is evaluated only when its opcode is reached.
The returned string or `qr//` becomes a nested Joni continuation. Its
alternatives remain resumable by the outer matcher, while its capture numbering
stays private. Control verbs operate at the current matcher-program boundary;
they are not post-match rewrites.

The runtime-neutral fork API and exact unwind contract are documented in
[`joni-callout-fork.md`](../../docs/design/joni-callout-fork.md).

### Script runs

Joni parses and executes `(*script_run:...)`, `(*sr:...)`,
`(*atomic_script_run:...)`, and `(*asr:...)` as native scoped programs. A normal
script-run validates its consumed span when the scope completes and can reactivate
that boundary when backtracking re-enters it; the atomic form also cuts internal
backtracking. `(*ACCEPT)` follows the nearest matcher-program boundary, including
Perl's distinction between accepting inside an uncaptured run and after a
captured run has completed.

The fork asks `CharacterPropertyResolver.isScriptRun()` to validate a span.
`UnicodeResolver.isPerlScriptRun()` implements Perl's Script_Extensions,
Japanese compatibility, Unknown, and decimal digit-set policy using generated
data. Grammar, scope, completion, unwind, and atomicity remain Joni matcher
semantics; the adapter supplies only the runtime-neutral predicate.

## Encoding and Unicode ownership

Unicode patterns and subjects use UTF-8 plus explicit character-to-byte and
byte-to-character maps. Byte programs use ISO-8859-1 and identity maps.
`PerlUtfString` together with Joni's `WideScalarCodec` provides a reversible
internal representation for surrogate and above-Unicode Perl scalar values.

Unicode behavior has three data owners:

- Checked-in `PerlUnicode*Data` classes and
  `third_party/joni/.../PerlUnicodeCaseFoldData` encode current-Perl property,
  alias, name, and fold semantics that must not drift with libraries. Joni's
  `WordBreakData`, `SentenceBreakData`, and `LineBreakData` separately encode
  boundary data.
- ICU supplies `UnicodeSet` operations and fallback Unicode APIs where Perl does
  not require a pinned override.
- JCodings supplies encodings and baseline code-point/fold primitives used by
  Joni. `RuntimeRegex`, `RegexFlags`, and `UnicodeResolver` choose Perl-specific
  `/d`, `/u`, `/a`, `/aa`, byte provenance, and property-fold policy; the
  maintained fork enforces those choices and adds pinned multi-code-point fold
  behavior.

`UnicodeResolver` implements Perl alias grammar and precedence: cached
user-defined properties are consulted before colliding built-ins, then pinned
Perl aliases and generated tables, followed by explicitly accepted ICU
property/value fallbacks. It also implements property-value wildcards,
script-run policy, inclusive and wide ranges, and per-property case-fold
eligibility through Joni's
`CharacterPropertyResolver`. Matcher-local `LocaleResolver` supplies
locale-sensitive class membership without storing Perl runtime state in a
compiled `Regex`. `NamedCharacterResolver` uses the compiled
`NamedCharacterCache`; `PerlPropertyValueMatcher` evaluates wildcard value
expressions without depending on PerlOnJava runtime classes.

Property-value wildcard delimiters follow current Perl's punctuation grammar:
ASCII punctuation other than `-`, `+`, `_`, and `{` may delimit the value
subpattern; `(`, `[`, and `<` use their paired closing character, and a
backslash-escaped opener requires the same escape before its closer. The same
parser owns Numeric_Value, Block, Name, Age, and the other enumerated property
families. The resolver also exposes Perl's internal
`utf8::_perl_surrogate` property as D800-DFFF in both Joni's encoding-domain
and wide-scalar ranges.

The generator registry is
`dev/tools/perl_unicode_data_generators.json`. It is the authority for the
latest imported upstream Perl checkout used by the checked-in generation: Perl
and Unicode versions, the source commit, input hashes, generator paths,
generated outputs, and output hashes. Its recorded commit and versions identify
one reproducible generation; they are provenance, not permanent pins or a reason
to reject a deliberate refresh from the latest `perl5/` checkout.
`dev/tools/generate_perl_unicode_data.pl --check` is the deterministic
regeneration gate for the registered property and fold tables. Scalar-name and
named-sequence tables currently have standalone generators,
`generate_perl_unicode_scalar_name_data.pl` and
`generate_perl_unicode_named_sequence_data.pl`; they are not registered in that
manifest and therefore require explicit regeneration and byte comparison. The
Joni word, sentence, and line boundary tables are produced by
`generate_joni_word_break_data.pl`, `generate_joni_boundary_data.pl`, and
`generate_joni_line_break_data.pl`. They are not entries in this manifest, so
refreshing them requires an explicit regeneration and byte comparison. The
policy is current-checkout provenance: a recorded commit and hashes identify one
generation, while deliberate `make perl5-update` and `make perl5-sync`
operations may advance the next one.

## Fork and distribution

The fork retains upstream `org.joni` packages in source. Gradle adds its
production sources to `main`; imported fork tests use the separate `joniTest`
source set. The standalone JAR relocates Joni to
`org.perlonjava.internal.joni` and JCodings to
`org.perlonjava.internal.jcodings`, preventing conflicts with embedding
applications.

The JAR carries the upstream Joni license, the JCodings license, and the
PerlOnJava fork notice under `META-INF/licenses`. Its SBOM records vendored Joni
and the Joni-to-JCodings dependency. `verifyJoniPackaging` and
`dev/tools/verify-joni-packaging.pl` enforce relocation, notice bytes, component
metadata, and dependency edges.

## Retained source-policy boundary

The Java code in the regex package uses small Java regular expressions and
hand-written scanners for source provenance and diagnostics; none is a match
engine for a Perl pattern. `RuntimeRegex` still owns `\Q` interpolation,
unescaped-left-brace warnings, literal diagnostic markers, recognition of
runtime executable source, and user-property callback provenance.
`RegexDiagnosticFormatter` and Joni's `WarnCallback` mapping retain Perl source
spelling, marker positions, categories, lexical masks, and fatality.

The former matcher-semantic `RegexPreprocessor`, Java matcher backend, and
backend selector are absent. Retained scanners enforce source admission,
security, provenance, and diagnostic presentation; they cannot select an engine
or emulate captures, encoding, backtracking, control flow, or matcher-visible
class algebra.

`CharacterPropertyResolver.Context` carries whether a property escape is
outside a class, in an ordinary class, or in Perl's experimental `(?[...])`
class. The PerlOnJava resolver rejects actual multi-code-point Name properties
and unresolved user properties only in the extended context; Joni attaches the
exact closing-brace source position. No host-side extended-property scanner
remains.

Joni compilation and compiled metadata own `\K`-inside-lookaround,
control-verb, inline-charset, and native-syntax decisions. Source-policy
scanners do not select an engine or approximate those matcher semantics.

## Deferred user properties

`CharacterPropertyResolver.Result.deferred(...)` tells the parser to retain an
unresolved property as a `DeferredProperty` in the immutable character-class
program. The retained fact includes raw and display spelling, parser context,
token-local options, source position, token negation, and enclosing-class
negation. Static class members and multiple deferred terms remain distinct;
Joni disables optimizer facts that would bypass required property execution.

`JoniRegexPattern` installs a `CharacterPropertyResolver.DeferredResolver` on
each `Matcher`. `ByteCodeMachine` asks for the ranges only when execution reaches
the class opcode, and `Matcher` caches the result by compiled class and term for
that invocation. Nested dynamic matchers inherit the resolver. The compiled
`Regex` therefore contains no Perl runtime object or resolved callback state,
and an unreachable alternative, skipped optional term, or optimizer rejection
does not invoke the callback.

The bridge captures the relevant Perl package and delegates reached terms to
`UnicodeResolver.resolveDeferredJoniProperty()`. It keeps sensitive and folded
results separate, maps rejection positions back to Perl source, and preserves
runtime/thread isolation. A forward declaration can be installed after the
regex was compiled and retried after an unknown-property failure. Unknown
properties in `(?[...])` remain construction errors because deferred set
algebra is not admitted there. Patterns without deferred terms install no
resolver.

`deferred_user_property_execution.t`,
`dynamic_user_property_cache_context.t`, and
`TestDeferredCharacterProperty` cover lazy execution, caching, negation and
union semantics, local `/i`, nested programs, package identity, failures, and
thread/runtime ownership. No full-domain stand-in or whole-pattern runtime
recompilation participates in matching.

## Verification

For a regex change, validate new or changed Perl fixtures with system Perl
first. Run focused PerlOnJava tests under both execution backends with a timeout
and captured output. Direct fork changes also require `make test-joni`.

The repository gates are:

- `make` for the maintained fork, packaging checks, and unit shards;
- `make check-links` when documentation links change and `lychee` is installed;
- `perl dev/tools/generate_perl_unicode_data.pl --check` for generated Unicode
  inputs and outputs;
- `perl dev/tools/perl_test_runner.pl perl5_t/t/re/` for the imported regex
  corpus, compared file by file rather than by aggregate totals alone.

`JPERL_UNIMPLEMENTED=warn` may deliberately turn unsupported compilation into a
warning and a never-match pattern. It is a diagnostic aid, never evidence that
supported syntax works. Remaining release boundaries are an immutable
full-corpus and CPAN ledger, platform and packaging evidence, and eventual
removal of the warn-mode acceptance harness.
The active acceptance checklist remains
[`phase36-regex-parity.md`](../design/phase36-regex-parity.md); this document
describes architecture rather than project status.

## Document lifecycle

### Canonical and active documents

| Document | Disposition | Scope |
| --- | --- | --- |
| This document | Keep | Canonical end-to-end implementation and ownership map. |
| [`joni-callout-fork.md`](../../docs/design/joni-callout-fork.md) | Keep | Canonical runtime-neutral fork API and matcher lifecycle; it links here instead of duplicating frontend, generated-data, packaging, or project-status detail. |
| [`phase36-regex-parity.md`](../design/phase36-regex-parity.md) | Keep while Phase 36 is active | Execution ledger and acceptance evidence, not an implementation reference. After acceptance, preserve its final evidence in a durable release record and retire the plan. |
| [`feature-matrix.md`](../../docs/reference/feature-matrix.md#regular-expressions) | Keep | User-facing capability summary. It should link to canonical architecture rather than reproduce internals. |
| [`perl-regex-library-rfc.md`](../design/perl-regex-library-rfc.md) | Keep | Explicitly future, standalone-library proposal; not current runtime documentation. |

### Noncanonical regex material

| Path | Disposition | Reason |
| --- | --- | --- |
| [`test_pass_rate_improvement_plan.md`](../design/test_pass_rate_improvement_plan.md) and [`sublanguage_parser_architecture.md`](../design/sublanguage_parser_architecture.md) | Keep with explicit historical/proposal scope | Their Java-matcher or preprocessor designs are not the current pipeline; link here for the implemented architecture. |
| Regex prompts, module incident notes, and presentations | Keep with their original purpose | They are investigation, incident, or presentation records. Refresh them before reuse; do not cite them as architecture. |

Searches may still find retired engine wording in prompts, module incident
notes, and presentations. Those files record the state or proposal relevant to
their own purpose; they are neither redundant copies nor current architecture.
Only this file and `joni-callout-fork.md` are normative implementation
references.
