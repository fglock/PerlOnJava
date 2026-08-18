# Regex implementation

PerlOnJava's regex subsystem has one long-term matcher target: the vendored
Joni engine. The current runtime is an intentional migration state, however,
and still uses progressive routing between `java.util.regex` and Joni. This
document describes that present state; it does not imply that Java removal or
all remaining Perl regex semantics are complete.

## Runtime shape

`RuntimeRegex` owns Perl-facing compilation, caching, modifiers, diagnostics,
match variables, substitution, `/g`, `pos()`, and runtime integration. It
compiles against the backend-neutral `RegexMatcher` interface and selects one
of two matcher implementations:

- `JavaRegexMatcher` is the compatibility path for patterns not yet admitted
  to Joni by the migration policy.
- `JoniRegexPattern` is the stack-based matcher path for constructs that need
  engine-visible backtracking, recursion, control verbs, or executable
  callbacks.

`RegexBackendPolicy` controls this temporary split. With no setting, `auto`,
or `java`, the progressive selector uses Java unless
`JoniRegexPattern.requiresJoniBackend()` identifies a Joni-only construct.
`JPERL_REGEX_BACKEND=joni` (or `jperl.regex.backend=joni`) forces Joni for
differential work. The `java` value does not override Joni-only safety routing.

The selector currently routes structured callouts and dynamic programs,
subpattern calls and recursion, `\K`, definitions and recursion conditions,
and the implemented matcher-control verbs to Joni. Ordinary lookbehind and
other compatibility-sensitive patterns may remain on Java until their Joni
admission gates are satisfied.

## Perl source-policy boundary

The matcher never parses or executes Perl source text. PerlOnJava's parser and
runtime own the trust boundary:

1. Literal executable regex blocks are compiled as lexical Perl closures.
2. `RuntimeRegexTemplate` stores ordered interpolation parts and explicit
   callback wrappers.
3. Template construction assigns callback IDs and emits engine-facing tokens
   such as `(?{=CALL:<id>})` and `(?{=DYNAMIC:<id>})`.
4. `JoniRegexPattern` translates those trusted tokens into native callout
   nodes and installs a matcher-local handler.

An interpolated string cannot manufacture a trusted callback token. Runtime
source remains subject to Perl's `use re 'eval'` policy and the existing
compatibility behavior for unsupported textual eval groups. This distinction
must remain in PerlOnJava even after Joni becomes the sole matcher.

## Match-time callbacks

The vendored Joni fork exposes only runtime-neutral callout types. It sees
numeric IDs, provisional byte offsets, capture boundaries, control marks, and
opaque cleanup tokens; it has no dependency on PerlOnJava runtime classes.

The PerlOnJava bridge publishes provisional captures and match variables,
executes the lexical closure in scalar context, and checkpoints dynamic local
state. Joni stores the opaque token on its own backtracking stack. Crossing the
frame unwinds the token exactly once; successful completion resolves remaining
tokens in reverse order. Dynamic `(??{ ... })` results run as nested Joni
matchers whose remaining alternatives stay available to outer backtracking.

This contract is why executable constructs cannot be implemented by splitting
a pattern around Java matchers or by invoking callbacks after a match.

See [joni-callout-fork.md](../../docs/design/joni-callout-fork.md) for the
engine API and unwind contract.

## Unicode and case folding

PerlOnJava owns Perl property spelling, aliases, user-defined properties, and
diagnostics in `UnicodeResolver`. The native Joni resolver receives generated
range tables backed by Perl 5.44's pinned Unicode 17.0.0 inputs rather than the
host JDK's Unicode version.

Each natively resolved property carries an explicit `caseFold` policy:

- general-category properties participate in `/i` folding;
- Block, Script, Script_Extensions, combining class, bidi class,
  decomposition type, East Asian width, numeric value, and joining group do
  not gain members merely because `/i` is active.

Joni applies folding only when that flag is true. Perl `/aa` also disables
multi-character folds and rejects non-ASCII-to-ASCII fold matches. Properties
that cannot yet preserve their policy inside a composed character class are
kept on the frontend translation path instead of being admitted with broader
semantics.

User-defined property subs and Perl-specific error behavior remain runtime
responsibilities. The engine callback is for pinned built-in range data, not a
general path from Joni into Perl code.

## Preprocessing ownership

`RegexPreprocessor` remains part of the Java compatibility path and the Perl
source-policy layer. Rules belong in one of four categories:

- Perl source policy: trust, lexical warnings, diagnostics, user properties,
  modifier rules, and capture mapping;
- backend-neutral syntax normalization: aliases accepted by both engines;
- Java adaptation: rewrites and stack-safety workarounds needed only by
  `java.util.regex`;
- matcher semantics: operations whose meaning depends on backtracking state
  and therefore belong in Joni.

New semantic behavior should not be added as a textual Java rewrite when the
matcher must observe it. Migration work should move only the last category
into focused Joni extensions, with differential Perl tests.

## Vendoring and packaging

Joni 2.2.7 is compiled from `third_party/joni`; JCodings remains an upstream
binary dependency. Sources retain their upstream `org.joni` packages and MIT
copyright/authorship notices so the fork stays reviewable. The standalone
shadow JAR relocates Joni and JCodings to
`org.perlonjava.internal.joni` and `org.perlonjava.internal.jcodings`, avoiding
classpath collisions with JRuby or applications using stock Joni.

The JAR includes the Joni and JCodings license texts, a PerlOnJava modification
notice, and SBOM entries for the vendored component and dependency. Packaging
verification is part of the build.

## Remaining boundaries

The following are migration boundaries, not shipped claims:

- Java remains the default compatibility path for patterns not selected for
  Joni; retiring it requires current corpus and performance evidence.
- Property-value wildcard coverage is not complete across all Perl property
  families.
- Native no-fold policy is not yet retained for every property embedded in a
  composed or extended character class; those cases continue through frontend
  translation.
- Some lookbehind combinations and other compatibility-sensitive constructs
  remain outside Joni admission.

Keep the selector and forced-backend controls until these boundaries have
explicit differential gates. Do not describe the target sole-matcher design as
the current runtime until the Java path and its preprocessing adaptations are
actually removed.

## Related documents

- [Vendored Joni Callout Engine](../../docs/design/joni-callout-fork.md)
- [Executable Regex Callbacks](../design/executable-regex-callbacks.md)
- [Phase 36 regex parity plan](../design/phase36-regex-parity.md)
- [Regex feature matrix](../../docs/reference/feature-matrix.md)
