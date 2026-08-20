# Regex implementation

## Scope

Production regex matching uses the vendored Joni fork. `RuntimeRegex.java` is
the runtime boundary and `JoniRegexPattern.java` is its matcher adapter. This
is an as-implemented record; the ordered parity plan remains in
`dev/design/phase36-regex-parity.md`.

`RegexBackendPolicy.java` routes an unset value, `auto`, and `joni` to Joni.
`JPERL_REGEX_BACKEND=java` and `jperl.regex.backend=java` retain Java only for
differential diagnosis. Features unavailable in Java still force Joni, so the
Java route is neither default nor a general compatibility promise.

## Frontend policy and compilation

The frontend and `RuntimeRegex` own lexical `use re` policy, modifiers,
warnings, source locations, `use re 'eval'` admission, custom `charnames`, and
byte-versus-Unicode source provenance. `RuntimeRegexTemplate.java` builds
structured interpolation parts; only parser-created callback parts receive
trusted internal IDs. `maskCallouts()` prevents interpolated text from creating
engine callouts.

`RuntimeRegexSourceCompiler.java` compiles admitted runtime source with captured
lexical cells. User-defined Unicode properties are preloaded before the
synchronized compiler region (`RuntimeRegex.compile`). Cache keys include
backend, strict/debug state, callout count, byte provenance, and charname
translator identity. `RuntimeRegex` maintains ordinary, Unicode, and—where
needed—byte Joni variants, while converting matcher byte offsets to Perl
character positions, captures, `/g`, `/c`, and match variables.

## Fork and routing boundary

Maintained Joni sources are in `third_party/joni` under `org.joni`; Gradle
builds them and their tests as dedicated source sets, then relocates Joni and
JCodings in the packaged artifact. The maintained API, packaging obligations,
and copyright requirements are in
[joni-callout-fork.md](../../docs/design/joni-callout-fork.md).

Joni owns parsing and matcher semantics: recursion, calls, conditions, control
verbs, captures, backtracking, and match-time callbacks. The frontend may
normalize source policy, but must not emulate backtracking. Remaining Java-only
rewrites are temporary migration code; removing the selector requires semantic,
performance, packaging, and platform evidence—not prose.

## Callouts, dynamics, and transactions

`JoniRegexPattern` installs a matcher-local `CalloutHandler`. Callback tokens
checkpoint provisional captures, `$^R`, match position, and dynamic locals.
Backtracking unwinds a token once; successful completion closes dynamic scope
while retaining the successful plain callback result. Evidence:
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

`UnicodeResolver.java` maps Perl property policy to Joni. Property-value
wildcards execute in the Joni path, but the adapter still materializes selected
ranges because nested property grammar is not wholly native. Gates:
`unicode_property_value_wildcards.t`,
`joni_native_property_wildcard_lexer.t`, and
`invalid_unicode_property_diagnostics.t`.

Unicode imports follow the current latest upstream `perl5/` checkout.
`perl dev/import-perl5/sync.pl --only Name.pl` generates the checkout's missing
`perl5/lib/unicore/Name.pl` with a compatible host Perl, then performs the
ordinary manifest copy to `src/main/perl/lib/unicore/Name.pl`. It reports a
checksum for that refresh; two generations and source/target comparison prove
determinism without a fixed historical checksum. See
`dev/import-perl5/config.yaml` and `dev/import-perl5/sync.pl`.

Named scalars and multi-code-point sequences use `NamedCharacterExpansion` and
`JoniRegexPattern.NamedCharacterCache`. Gates include
`charnames_runtime_lookup.t`, `regex/unicode_named_sequences.t`, and
`regex/joni_named_character_sequences.t`. Exact unknown-name and
restricted-class diagnostics remain separate concerns.

Full folding, charset modes, property/class closure, reverse literal expansion,
and folded backreferences still have migration work. Evidence and gaps are in
`casefold_provenance_modes.t`, `casefold_property_class_closure.t`, and
`joni_ascii_strict_backreference_folds.t`.

## Diagnostics, gaps, and verification

Temporary bridges include property-range materialization, some extended-class
lowering, named-sequence transport, and non-executing `(?(DEFINE)...)` handling.
They must be removed only with native replacement and Perl parity evidence.
Source-facing diagnostic gates include `frontend_regex_diagnostic_provenance.t`,
`regex_re_strict_left_brace_policy.t`, and
`joni_extended_class_range_diagnostic.t`.

Active gaps are native nested property-value grammar, complete folding and
provenance ownership, remaining Java-only compatibility code, and exact Perl
diagnostic/source restoration after temporary encodings. Do not mark them
complete from a documentation statement or a single backend run.

Run changed unit fixtures with system Perl first. `make` is the repository gate:
it builds the fork, verifies packaging, and runs parallel unit shards. Before
retiring the diagnostic Java differential mode, require the documented Perl
corpus comparison,
forced-Joni JVM and interpreter legs, package relocation checks, performance
evidence, and platform CI. The feature matrix is a status summary, not an
independent acceptance test.
