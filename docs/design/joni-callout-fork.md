# Vendored Joni callout engine

Perl executable regex constructs must run while the matcher owns provisional
captures and its backtracking stack. PerlOnJava therefore vendors Joni 2.2.7
with a small runtime-neutral callout and matcher-control extension. The fork is
an engine component: Perl parsing, trust decisions, and runtime state remain
outside it.

## Scope and dependency boundary

The fork supplies:

- matcher-time plain and conditional callouts;
- nested dynamic programs with alternatives resumable by outer backtracking;
- provisional capture and control-mark views;
- opaque token completion and unwind notifications;
- Perl matcher-control behavior used by the routed feature set;
- a syntax-level character-property resolver with explicit fold policy.

It does not compile Perl source, inspect PerlOnJava runtime objects, decide
whether interpolated text is trusted, or own Perl diagnostics and lexical
policy. Those responsibilities stay in the PerlOnJava frontend and runtime.

Joni sources live in `third_party/joni` and build as a dedicated source set in
the root project. JCodings remains an upstream binary dependency.

## Internal callout representation

The structured Perl frontend emits callout tokens only in a trusted
engine-facing skeleton:

- `(?{=CALL:<id>})` for plain callbacks;
- the corresponding conditional form for callback conditions;
- `(?{=DYNAMIC:<id>})` for match-time dynamic programs.

`<id>` indexes the callback table owned by that regex value. The fork parses
these forms into dedicated nodes and bytecode; it never parses the callback's
Perl body. Runtime interpolation cannot create the parser-owned wrapper needed
to enter this path.

## Runtime-neutral API

The public fork surface is expressed only in Joni types and opaque objects:

```java
interface CalloutHandler {
    CalloutResult execute(int calloutId, MatchView match);
    default DynamicPatternResult executeDynamic(int calloutId, MatchView match);
    void unwind(Object token);
    default void complete(Object token);
    default void finish(boolean matched);
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

`CalloutResult` selects `CONTINUE` or `FAIL` and may carry one opaque token.
`DynamicPatternResult` carries a nested `Regex`, its matcher-local handler, and
the token belonging to evaluation of the dynamic expression. A handler is
installed on a `Matcher`, not a compiled `Regex`, so compiled patterns remain
shareable while callback state stays invocation-local.

Capture positions are byte offsets in the active Joni input buffer. Unset
captures use `Region.REGION_NOTPOS`. `MatchView` is valid only during the
callback; a handler must copy anything it retains. PerlOnJava converts offsets
to Perl character positions before publishing match variables.

## Execution and unwind contract

1. A callout opcode invokes the handler with its ID and a read-only view of the
   current matcher state.
2. A returned token is pushed on the matcher stack before execution continues
   or a requested failure enters normal backtracking.
3. Backtracking across that frame calls `unwind(token)` exactly once.
4. Successful completion calls `complete(token)` exactly once for each active
   token, in reverse execution order.
5. Failure, abandonment, interruption, timeout, and handler exceptions unwind
   all still-active tokens in reverse execution order.
6. `finish(matched)` closes one matcher invocation after its tokens have been
   resolved.
7. A conditional callout uses `CONTINUE` to select the yes branch and `FAIL` to
   select the no branch; selecting no is not itself a failed match.
8. Patterns with no callout nodes allocate no callout frames or handler state.

PerlOnJava uses each token to checkpoint provisional regex state, `$^R`, and
the dynamic-local stack. Ordinary callback side effects are not transactionally
rolled back; only state governed by Perl's dynamic scoping and match semantics
is restored at the matcher boundary.

## Dynamic programs

A dynamic opcode invokes `executeDynamic` only when the matcher reaches it.
The returned regex runs against the same byte buffer from the current position.
Its first result advances the outer matcher; if a later outer suffix fails, the
nested continuation yields its next result before the outer matcher discards
the dynamic frame.

Nested capture numbering remains private. The nested endpoint advances the
outer input position, while active callback tokens and control actions are
completed or unwound exactly once. Recursive and nested matchers use
engine-owned depth limits instead of depending on the JVM native stack limit.

## Matcher-control boundaries

`(*ACCEPT)` is an AST node and opcode, not a callback or assertion rewrite. It
accepts the nearest matcher-program boundary:

- at top level, the whole match;
- in a subpattern call, that call;
- in a positive lookahead, the zero-width assertion;
- in a dynamic program, the nested matcher while the outer suffix continues.

Captures opened within the accepted boundary close at the current position;
outer captures remain active. Abandoned dynamic continuations and callout
frames complete exactly once. Control-flow-bearing programs bypass optimizer
assumptions that require every suffix to remain reachable.

`(*PRUNE)`, `(*SKIP)`, `(*THEN)`, `(*COMMIT)`, and marks operate on the native
matcher stack. A dynamic program is an alternation boundary, so its control
actions cannot incorrectly enter an enclosing branch.

## Perl bridge behavior

Literal callback bodies are lexical Perl closures stored by
`RuntimeRegexTemplate`. At execution, the PerlOnJava bridge:

- publishes provisional numbered and named captures and offsets;
- derives `$^N` from capture-close order rather than from `$+`;
- runs plain and conditional closures in scalar context;
- updates `$^R` for plain callbacks but not callback conditions;
- preserves dynamic locals along the active forward path;
- restores callback-owned state when Joni unwinds the token;
- isolates nested regex match state from the caller.

Callback closures are Perl pseudo-blocks. Escaped `last`, `next`, `redo`, or
`goto` cannot target a surrounding loop or label. Literal match subjects retain
stable call-site scalar identity so `/g` and `pos()` state survive repeated
execution without being shared by separate literal occurrences.

## Unicode property resolver and folding

The syntax object accepts a `CharacterPropertyResolver`. PerlOnJava supplies
range sets generated from Perl 5.44's pinned Unicode 17.0.0 data and a boolean
that states whether `/i` case folding applies to that property.

General-category ranges are foldable. Block, Script, Script_Extensions,
combining class, bidi class, decomposition type, East Asian width, numeric
value, and joining group ranges are explicitly no-fold. Joni applies class
fold expansion only when the resolver says it is permitted. Under Perl `/aa`,
multi-character folds are disabled and non-ASCII-to-ASCII fold matches are
rejected.

Properties whose no-fold behavior cannot yet survive composition inside a
larger character class are deliberately deferred to PerlOnJava's frontend
translation. Property-value wildcards and composed-class coverage remain
migration boundaries; this resolver must not be described as complete support
for either.

## Preprocessing ownership

The ownership line is stable even while routing changes:

- PerlOnJava owns source trust, `use re 'eval'`, lexical warnings, diagnostics,
  user-defined properties, modifiers, capture mapping, and backend selection.
- Backend-neutral normalization may canonicalize syntax shared by both
  matchers.
- Joni owns semantics that depend on its backtracking stack, including
  callouts, recursion, control verbs, provisional captures, and native folding.
- The Java adapter owns rewrites needed only by `java.util.regex` while that
  compatibility path exists.

Moving all of `RegexPreprocessor` into the fork would violate this boundary.
Only matcher-semantic rules belong in focused Joni changes.

## Namespacing, licenses, and authorship

Vendored source stays in upstream `org.joni` packages so modifications remain
reviewable and suitable for upstream discussion. The shadow JAR relocates
Joni to `org.perlonjava.internal.joni` and JCodings to
`org.perlonjava.internal.jcodings`, preventing linkage conflicts with JRuby or
stock Joni on an embedding application's classpath.

The upstream MIT license and all copyright and authorship notices are retained.
The distribution adds a separate PerlOnJava modification notice; it does not
replace or narrow upstream attribution. The JAR packages the Joni and JCodings
license texts and modification notice under `META-INF/licenses`, and the SBOM
records vendored Joni 2.2.7 with its JCodings dependency.

## Current migration boundary

The architectural target is a sole Joni matcher, but the current runtime still
uses progressive routing. Ordinary and compatibility-sensitive patterns may
execute through `java.util.regex`; Joni is selected for admitted semantic
families or forced differential runs. Removing the Java path requires explicit
corpus and performance gates and completion of remaining wildcard,
composed-class, and lookbehind admission work.

See [Regex implementation](../../dev/implementation/regex.md) for the runtime
selector and [Executable Regex Callbacks](../../dev/design/executable-regex-callbacks.md)
for the broader Perl semantic test model.
