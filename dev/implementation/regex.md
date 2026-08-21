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

`JPERL_REGEX_BACKEND` and `jperl.regex.backend` do not select a production
matcher. The production `RegexBackendPolicy` class has been removed; a
test-scope-only model preserves the immutable migration assertions and cannot
enter the standalone artifact. Likewise,
`JoniRegexPattern.compatibilityPatternDescription` is a display-only
normalization for legacy assertions and debug descriptions. The raw
`sourcePattern` is what Joni compiles and what source diagnostics describe.

This separation is the central maintenance rule:

- PerlOnJava owns source provenance, lexical policy, runtime state, and Perl
  callbacks.
- Joni owns matcher-visible grammar and behavior.
- Compatibility descriptions must never become matcher input.

## Responsibility map

| Responsibility | Production classes |
| --- | --- |
| Parse literal/interpolated regex source and capture executable closures | `StringSegmentParser`, `RegexLiteralAnalyzer` |
| Preserve trusted callback provenance while assembling interpolated patterns | `RuntimeRegexTemplate`, `RuntimeRegexCallback` |
| Compile runtime source admitted by `use re 'eval'` in its Perl lexical context | `RuntimeRegexSourceCompiler` |
| Cache compiled variants and implement Perl-visible match/substitution state | `RuntimeRegex`, `RegexFlags` |
| Adapt encoding, diagnostics, resolver hooks, callbacks, compiled facts, and Joni match results | `JoniRegexPattern` and its `JoniRegexMatcher` / `PerlCalloutHandler` nested classes |
| Resolve Perl Unicode properties and names with generated-table precedence | `UnicodeResolver`, `NamedCharacterExpansion`, `PerlUnicode*Data` |
| Parse and execute matcher semantics without Perl runtime dependencies | `org.joni.Regex`, `Parser`, `Analyser`, `ArrayCompiler`, `ByteCodeMachine` |
| Expose runtime-neutral host hooks | `CalloutHandler`, `MatchView`, `DynamicPatternResult`, `CharacterPropertyResolver`, `NamedCharacterResolver`, `PerlPropertyValueMatcher`, `WideScalarCodec` |

## From Perl source to a compiled pattern

`StringSegmentParser` distinguishes literal source, interpolation, and
parser-created executable blocks. It emits `regexCallback` nodes inside a
`regexTemplate`; JVM lowering and interpreter lowering preserve the same node
contract.

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

`RuntimeRegex.compileSynchronized()` performs the remaining Perl-side checks,
constructs `RegexFlags`, and creates `JoniRegexPattern` variants. Its cache key
includes source and modifiers, lexical debug and `re 'strict'` state, trusted
callout count, effective byte provenance, and custom-`charnames` translator
discriminator. The discriminator is the translator scalar's string
representation; the translator and its `NamedCharacterCache` are separately
retained for deferred recompilation. User-defined Unicode properties are
preloaded outside the compiler lock when Perl code may run. Deferred
source-compile placeholders retain their exact compilation context and are
replaced before matching.

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
positive inline Perl charset modifiers, optimizer metadata, the native
instruction listing, authoritative wide-class coverage, and immutable semantic
facts about the first compiled character-class program. Perl-compatible debug
labels such as `SANY`, `OPFAIL`, `REG_ANY`, `ANYOFR`, and `ANYOFHbbm` are rendered
only when those compiled facts prove the shape; otherwise debug output falls
back to Joni's native bytecode. Debug presentation never becomes matcher input.

## Matching and Perl state

A compiled `RuntimeRegex` can hold an ordinary Joni variant, a Unicode variant,
and a byte-pattern variant for byte-backed `/d` case-fold behavior.
`selectRecursivePattern()` chooses among them from the pattern and subject
provenance. Every match operation creates a matcher and, when needed, a
matcher-local callback handler; compiled patterns do not share provisional
callback state.

`RuntimeRegex` owns Perl-visible behavior around the matcher: scalar and list
context, `pos`, `/g`, `/c`, `\G`, substitutions, match variables, named and
numbered captures, preserve-match state, and last-successful-pattern reuse.
Joni reports byte offsets. The adapter maps them to Perl character offsets
before publishing `$&`, `$1`, `@-`, `@+`, and `pos`.

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
`CharacterPropertyResolver`. `NamedCharacterResolver` uses the compiled
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
`dev/tools/perl_unicode_data_generators.json`. It records the latest imported
upstream Perl checkout, Perl and Unicode versions, input hashes, generated
outputs, and output hashes. The current checked-in generation is Perl 5.45.2 at
the recorded commit and Unicode 17.0.0; these are provenance for this
generation, not a permanent version pin.
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

## Remaining source-policy boundary

The Java code in the regex package uses small Java regular expressions and
hand-written scanners for source provenance and diagnostics; none is a match
engine for a Perl pattern. `RuntimeRegex` still owns `\Q` interpolation,
unescaped-left-brace warnings, literal diagnostic markers, recognition of
runtime executable source, and discovery/preloading of user-property callbacks.
`RegexDiagnosticFormatter` and Joni's `WarnCallback` mapping retain Perl source
spelling, marker positions, categories, lexical masks, and fatality.

`CharacterPropertyResolver.Context` carries whether a property escape is
outside a class, in an ordinary class, or in Perl's experimental `(?[...])`
class. The PerlOnJava resolver rejects actual multi-code-point Name properties
and unresolved user properties only in the extended context; Joni attaches the
exact closing-brace source position. No host-side extended-property scanner
remains.

`requiresJoniBackend()`, `analyzePerlSyntax()`, and their helper scanners remain
in the main source file only as package-private compatibility surfaces for the
historical routing tests. Production has no caller for them, and they neither
select nor prepare a matcher. The old `\K`-inside-lookaround precheck, raw
control-verb spelling scan, and raw inline-charset scan have been removed from
production: Joni compilation and compiled metadata now own those decisions.

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
supported syntax works. The active acceptance checklist remains
[`phase36-regex-parity.md`](../design/phase36-regex-parity.md); this document
describes architecture rather than project status.

## Document lifecycle

| Document | Recommendation | Reason |
| --- | --- | --- |
| This document | Keep | Canonical end-to-end implementation and ownership map. |
| [`joni-callout-fork.md`](../../docs/design/joni-callout-fork.md) | Keep, narrowly scoped | Canonical runtime-neutral fork API, token lifecycle, continuations, and matcher control contract. |
| [`phase36-regex-parity.md`](../design/phase36-regex-parity.md) | Keep while active; summarize, then delete after final acceptance | Preserve its final evidence in the release/acceptance record first; its architecture and completed execution detail otherwise duplicate this document once migration closes. |
| [`perl-regex-library-rfc.md`](../design/perl-regex-library-rfc.md) | Keep | A clearly marked future product proposal, not current implementation documentation. |

No document is deleted as part of this reconciliation. The only later deletion
recommendation is the Phase 36 plan, after its final evidence is summarized in
the durable release/acceptance record; the two canonical documents and the
distinct future RFC remain.
