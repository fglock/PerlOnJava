# Regex implementation

## Scope and target

PerlOnJava maintains a source fork of Joni as its Perl-compatible regex engine.
The target architecture has one production matcher: Joni owns parsing,
backtracking, captures, conditions, recursion, control verbs, Unicode matching,
case folding, and matcher-visible callbacks. PerlOnJava owns Perl source policy,
runtime integration, lexical warnings, diagnostics, and callback closures.

Migration is not complete. Automatic routing still uses Java for ordinary
patterns unless a Joni-only construct is present. Setting
`JPERL_REGEX_BACKEND=joni` or the `jperl.regex.backend=joni` system property
forces Joni and is the compatibility gate used by the unit corpus. The Java
matcher, selector, and Java-only rewrites are temporary migration scaffolding.

## Compilation and routing

`RuntimeRegex` compiles Perl regex values and keeps byte-string and Unicode
variants where subject provenance can affect semantics. `RegexBackendPolicy`
selects the temporary Java route or Joni. `JoniRegexPattern` converts only the
remaining frontend-owned representations, builds the PerlOnJava Joni syntax,
and compiles the vendored engine.

Patterns requiring matcher semantics unavailable in Java select Joni. These
include subpattern calls, recursion, Perl conditions, structured executable or
dynamic callbacks, control verbs, ASCII-strict folding, and native named
character sequences. Embedded closures select Joni even when their result is
constant because execution and unwind remain observable at match time.

The matcher exposes the same `RegexMatcher` interface to JVM-compiled and
interpreter execution. Match, substitution, `/g`, `/c`, `pos`, captures, and
match variables are coordinated by `RuntimeRegex`; backend-specific offsets are
converted to Perl character positions at that boundary.

## Vendored Joni fork

Production Joni sources live in `third_party/joni` under their original
`org.joni` namespace. The root build compiles the fork and its upstream tests as
dedicated source sets. Packaging relocates Joni to
`org.perlonjava.internal.joni` and JCodings to
`org.perlonjava.internal.jcodings`, avoiding classpath collisions without
rewriting the maintained source namespace.

The fork includes native support required by PerlOnJava for:

- absolute, relative, named, recursive, and whole-pattern subpattern calls;
- numbered, named, assertion, recursion, and callback conditions;
- `(*ACCEPT)`, `(*MARK:NAME)`, named `(*SKIP:NAME)`, `(*PRUNE)`, `(*COMMIT)`,
  and `(*THEN)` with matcher-owned unwind boundaries;
- runtime-neutral callouts and dynamic nested matchers;
- wide scalar ranges beyond Unicode and Perl property resolution;
- Perl-specific parser, diagnostic, and case-fold hooks.

Original Joni, JCodings, Unicode, and Perl copyright and authorship notices are
retained. The packaged JAR includes license texts and a modification notice;
the SBOM identifies the vendored components.

## Executable and dynamic callbacks

The Perl parser creates structured callback parts rather than embedding raw Perl
source in a regex string. Runtime assembly assigns callback IDs and emits
trusted engine tokens such as `(?{=CALL:<id>})` and
`(?{=DYNAMIC:<id>})`. Interpolated runtime text cannot create trusted tokens.

Joni invokes a matcher-local `CalloutHandler` with provisional captures and the
current byte position. Callback tokens checkpoint regex state, `$^R`, and
dynamic locals. Backtracking unwinds each token exactly once; successful
completion closes active scopes while retaining the successful callback result.
Dynamic `(??{ ... })` programs retain their alternatives as resumable nested
matcher continuations rather than atomic post-processing.

`$^N` follows capture-close order. `$+` remains the highest-numbered defined
capture. Nested matches save and restore outer match state, and callback control
flow cannot target loops or labels outside its pseudo block.

## Unicode properties and named characters

`UnicodeResolver` implements Perl property aliases and defaults from generated,
pinned Unicode 17 data. Generators and checked-in sources live under `dev/tools`
and `dev/unicode/17.0.0`; freshness tests require byte-for-byte regeneration.
ICU4J supplies general Unicode operations, while generated Perl tables define
the compatibility surface where Perl aliases, defaults, or versions differ.

Property-value wildcard expressions are compiled and executed by
`org.joni.PerlPropertyValueMatcher`; Java `Pattern` no longer evaluates them.
The resolver currently materializes the selected ranges through the adapter
because Joni's character-property lexer does not yet own nested regex syntax
inside `\p{...}`. Removing that materialization is an active migration step.

Generated named-sequence data resolves Perl's multi-code-point `\N{name}` values
before ordinary scalar Unicode names. Custom `charnames` translators and source
provenance remain frontend/runtime responsibilities. Temporary encoded sequence
tokens and their diagnostic restoration are still being removed.

## Case folding

Generated Perl tables contain default full mappings, simple-fold classes,
reverse multi-character sequences, and Turkic exclusions. The fork has a
runtime-neutral fold adapter and suppresses exact-search optimisation where a
full fold makes its literal bound unsafe.

Property/class closure, explicit `/d`, `/u`, `/a`, and `/aa` context, reverse
literal expansion, and backreference folding are not yet complete. Until those
paths carry Perl provenance natively, the adapter retains the corresponding
semantic protections.

## Preprocessing boundary

Preprocessing may preserve source policy or bridge a feature not yet represented
by Joni, but it must not emulate backtracking semantics. Current temporary work
includes Java syntax adaptation, range materialisation for property wildcards,
named-sequence encoding, extended-character-class lowering, and the
`(?(DEFINE)...)` rewrite. Each is deleted with the same change that proves its
native replacement against Perl.

The frontend continues to own trusted callback construction, `use re 'eval'`
security, lexical warning policy, user-defined Unicode properties, original
source locations, and Perl-facing diagnostics. Those are source/runtime policy,
not matcher behavior.

## Validation contract

New semantic fixtures are validated with system Perl before either PerlOnJava
backend. Focused changes run direct Joni tests plus forced-Joni JVM and
interpreter fixtures. Combined batches run warning-free `make`, packaging, and
generated-data freshness checks.

The release gate compares `dev/tools/perl_test_runner.pl` output file-by-file
with `../PerlOnJava/logs/test_20260815_080000_958.log`. Complete Unicode,
`reg_mesg.t`, `pat.t`, `pat_advanced.t`, four 80-file forced-Joni legs,
`pat_psycho*`, and `speed*` must finish without unexplained regressions,
timeouts, incomplete TAP, or backend disagreement.

## Remaining migration gates

- finish native extended character classes and `(?(DEFINE)...)`;
- complete property/class, literal, backreference, and provenance-aware folding;
- move nested property-value regex parsing into Joni;
- close runtime-source and exact diagnostic differences;
- remove Java matching, backend selection, and matcher-semantic preprocessing;
- remove obsolete imported-test patches and verify sync idempotence;
- pass performance, packaging, Linux, Windows, and complete CI gates.

The ordered execution plan and acceptance tracker are in
`dev/design/phase36-regex-parity.md`. The fork API and ownership contract are in
`docs/design/joni-callout-fork.md`.
