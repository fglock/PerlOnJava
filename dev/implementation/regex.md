# Regex implementation

## Scope

Production regex matching has one engine: the vendored Joni fork.
`RuntimeRegex.java` is the Perl runtime boundary and `JoniRegexPattern.java` is
the only production matcher adapter. Neither `JPERL_REGEX_BACKEND` nor
`jperl.regex.backend` selects a Java matcher. `RegexBackendPolicy.java` and its
focused tests form disconnected compatibility scaffolding with no production
call site; they do not describe a supported runtime mode and may remain until
explicit retirement authority exists.

PerlOnJava owns source and runtime policy: lexical hints and warnings,
interpolation provenance, `use re 'eval'`, captured Perl closures, custom
`charnames`, user-defined properties, diagnostics, match variables, `/g`, `/c`,
and substitution. Joni owns grammar and matcher semantics: parsing, calls and
recursion, conditions, captures, lookarounds, class algebra, folding, control
verbs, backtracking, optimizer selection, and match-time callout opcodes.

This is an as-implemented record. The ordered gates remain in
[`phase36-regex-parity.md`](../design/phase36-regex-parity.md); historical
investigation snapshots are retained in Git history, not as current routing
documentation.

## Frontend policy and compilation

`StringSegmentParser.java` preserves literal versus interpolated source and
creates explicit callback parts. `RuntimeRegexTemplate.java` assigns trusted
internal IDs only to parser-created callback wrappers; runtime strings cannot
manufacture executable callouts. `maskCallouts()` protects that boundary.

`RuntimeRegexSourceCompiler.java` compiles admitted runtime source with captured
package, lexical cells, warning state, filename, and line. Dynamic expressions
are evaluated only when their opcode is reached. Returned strings and `qr//`
values become resumable nested matcher programs, so their alternatives
participate in outer backtracking without exporting nested capture numbers.

User-defined properties that may execute Perl are preloaded before the
synchronized compiler region. The runtime-local cache key contains the pattern
and modifiers plus lexical debug mode, trusted callout count, effective byte
provenance, `re 'strict'`, and custom-charname translator identity. There is no
backend component. A custom translator can force a fresh literal-validation
compile before the runtime leg reuses it.

## Fork, cache, and optimizer boundary

Maintained Joni sources are in `third_party/joni` under `org.joni`; Gradle
builds them and their tests as dedicated source sets, then relocates Joni and
JCodings in the packaged artifact. The maintained API, packaging obligations,
and copyright requirements are in
[joni-callout-fork.md](../../docs/design/joni-callout-fork.md).

Each compiled `RuntimeRegex` owns ordinary and Unicode Joni variants and, where
byte-backed case-insensitive default semantics require it, a byte-pattern
variant. A matcher and its callout handler are created per operation, so shared
compiled patterns do not share runtime callback state. Weak input-encoding
caches reuse UTF-8/byte offset maps; user-property and named-character results
have separate policy-aware caches.

`JoniRegexPattern.translatePattern()` is the final adapter boundary. It carries
trusted internal tokens and remaining source-policy encodings, but it does not
move matcher behavior out of Joni. Native Joni parses Perl extended character
classes, `(?(DEFINE)...)`, named characters, calls, conditions, control verbs,
and dynamic callouts. Joni's `Analyser` owns optimizer information; it disables
ordinary mandatory-literal/minimum-length optimization for control-verb and
dynamic-option programs and suppresses unsafe Perl multi-fold candidates.

## Callouts, dynamics, and transactions

`JoniRegexPattern` installs a matcher-local `CalloutHandler`. Callback tokens
checkpoint provisional captures, `$^R`, `$^N`, control marks, match position,
and dynamic locals. Backtracking calls `unwind` once; a selected successful path
calls `complete` once; `finish` reconciles final matcher success or failure.
Callback exceptions restore dynamic scope in the bridge because Joni cannot
register a token for a call that threw. Evidence:
`unit/regex/native_callback_backtrack_transaction.t`,
`callback_exception_unwind.t`, and `callback_local_scope.t`.

Dynamic `(??{...})` expressions compile only when reached. Their nested matcher
offers alternatives resumably to the outer matcher, so outer failure can request
the next nested alternative before abandoning the dynamic frame. Nested captures
stay private. See `RuntimeRegexSourceCompiler.java`, `JoniRegexPattern.java`,
`unit/regex/dynamic_patterns.t`, `native_dynamic_pattern_contract.t`, and
`runtime_dynamic_control_boundaries.t`.

Control verbs are matcher control flow, not post-match rewrites. Joni owns the
unwind boundary for `(*ACCEPT)`, `(*FAIL)`, `(*PRUNE)`, `(*SKIP)`, `(*THEN)`,
`(*COMMIT)`, and mark forms. Gates: `accept_control_verb.t`,
`control_verb_paths.t`, and `named_accept_fail_control_verbs.t`.

## Unicode, names, and folding

`RuntimeRegex` selects compiled variants from pattern and subject provenance.
Unicode mode encodes source and subject as UTF-8 and maps Joni byte offsets back
to Java/Perl character offsets. Byte mode uses ISO-8859-1 with identity maps.
`PerlUtfString` and Joni's `WideScalarCodec` provide a reversible internal form
for signed/wide Perl scalar values that ordinary Unicode cannot encode.

`UnicodeResolver.java` supplies aliases, precedence, inclusive and wide ranges,
case-fold eligibility, user properties, and script-run policy through Joni's
`CharacterPropertyResolver`. Property-value wildcards compile with the
runtime-neutral `PerlPropertyValueMatcher` and execute with Joni search
semantics. Gates:
`unicode_property_value_wildcards.t`,
`joni_native_property_wildcard_lexer.t`, and
`invalid_unicode_property_diagnostics.t`.

Unicode data follows the current upstream `perl5/` checkout, not a historical
commit pin. `make perl5-update` safely fast-forwards that checkout;
`make perl5-sync` also replays the import manifest. Generators listed in
`dev/tools/perl_unicode_data_generators.json` derive checked-in Java tables and
provenance from the consumed checkout and require deterministic regeneration.
`dev/import-perl5/sync.pl` with `--only` and `Name.pl` generates the checkout's
missing `unicore/Name.pl`, reports its checksum, and imports it normally. A
recorded revision/checksum proves one generation's input; it does not constrain
the next refresh.

Named scalars and multi-code-point sequences use `NamedCharacterExpansion` and
`JoniRegexPattern.NamedCharacterCache`. Gates include
`charnames_runtime_lookup.t`, `regex/unicode_named_sequences.t`, and
`regex/joni_named_character_sequences.t`. Exact unknown-name and
restricted-class diagnostics remain separate concerns.

Fold and provenance evidence includes `casefold_provenance_modes.t`,
`casefold_property_class_closure.t`, and
`joni_ascii_strict_backreference_folds.t`. Completion status belongs to the
canonical plan, because independently staged P3/P4/P5 work may not yet be in the
documented integration commit.

## Diagnostics, gaps, and verification

Source-facing diagnostic gates include `frontend_regex_diagnostic_provenance.t`,
`regex_re_strict_left_brace_policy.t`, and
`joni_extended_class_range_diagnostic.t`.

At this snapshot, the canonical active-phase checklist still names remaining
native lexer/parser diagnostic families, forward/reverse literal and
character-class fold expansion, classification of disconnected selector/
fallback scaffolding, and the combined integrated acceptance run. Production
Java routing must remain absent; immutable compatibility tests and their parser
need not be removed without explicit authority. Some compilation failures
become a never-match pattern only under explicit `JPERL_UNIMPLEMENTED=warn`;
acceptance runs must not use that mechanism to mask supported syntax. Do not
mark locally staged P3/P4/P5 work complete from prose or a single backend run.

Run changed unit fixtures with system Perl first. Focused `jperl`/`prove` runs
must use `timeout` and save complete output. `make` is the repository gate: it
builds the fork, verifies packaging, and runs parallel unit shards. Packaging
must also pass `dev/tools/verify-joni-packaging.pl`, including relocated
namespaces and all three license/notice files. The feature matrix is a status
summary, not an independent acceptance test.
