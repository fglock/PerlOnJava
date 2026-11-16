# RFC: Embeddable Perl-Compatible Regex Library for the JVM

## Status

Feasibility investigation is active under issue #1592. No standalone public API,
repository, or artifact has been delivered. The current PerlOnJava engine remains
the only implementation source; a standalone release requires a separately
reviewed extraction and packaging gate.

## Feasibility Snapshot — 2026-11-16

The supported runtime currently targets Perl 5.44.1 and Java 24. The production
adapter, `JoniRegexPattern`, is package-private and directly depends on Perl
runtime types for warnings, string provenance, scalar conversion, named
characters, callbacks, dynamic patterns, and captures. The maintained engine
is under `third_party/joni`; generated Perl Unicode/property tables and their
resolver live under `src/main/java/org/perlonjava/runtime/regex`. These are
concrete extraction dependencies, not yet a standalone module boundary.

The initial extraction should use the standalone repository with PerlOnJava as
a pinned Git submodule, as described by issue #1592. The build must select an
explicit engine/core source boundary and declare its JCodings, ICU, and data
provenance dependencies. It must not compile the full Perl runtime or publish
fork internals as the Java API. Engine fixes remain in PerlOnJava until a later
review establishes a shared source-of-truth repository and upgrade gates for
both consumers.

The following Phase 1 questions remain release blockers:

- Pin the exact Perl compatibility and Unicode data versions together. The
  runtime version alone does not establish which Unicode data snapshot defines
  every regex property.
- Complete a source-by-source license and generated-data provenance audit for
  Joni, JCodings, ICU, aliases, named characters, and their generators.
- Decide public byte and Unicode input types, offset units, flag behavior,
  replacement/split scope, structured diagnostics, and concurrency contracts.
- Measure the extracted artifact's dependency closure and resource controls;
  the current runtime's backtracking behavior does not yet prove enforceable
  standalone matching limits or cancellation.
- Validate the dependency boundary with a clean external Java consumer and
  differential, concurrency, cancellation, and packaging tests.

The issue's proposed Java audience and use cases are hypotheses; no external
consumer validation or market evidence is recorded yet. The existing PerlOnJava
unit/core corpus is useful engine evidence, but does not satisfy the standalone
artifact release gate. Phase 1 remains in progress until the blockers above are
resolved and the extraction cost and maintenance obligation are accepted.

## Summary

After the full Joni migration is complete, PerlOnJava could publish its regex
engine as a standalone JVM library. The library would offer Perl-compatible
regular expressions through a small Java API, in the same broad product space
as PCRE, without requiring applications to run general Perl programs.

The public contract would be Perl regex semantics implemented by the forked
Joni engine and the minimum PerlOnJava compatibility runtime needed by it. It
must not expose the regex implementation's temporary Java-regex routing or transitional
preprocessing as permanent behavior.

## Motivation

Java applications currently have no lightweight way to request Perl regex
semantics when `java.util.regex` is insufficient. PerlOnJava's Joni fork is
gaining capabilities that are useful independently of the language runtime:

- Perl syntax and capture behavior
- Perl-compatible Unicode and byte-string semantics
- named groups, subroutine calls, recursion, and control verbs
- Perl-style diagnostics
- a differential corpus against system Perl

A standalone artifact would make that work reusable by JVM applications and
would give the regex implementation a narrow, testable public boundary.

## Proposed Product Boundary

The default artifact should compile and execute data-only patterns. A familiar
Java-facing API is preferable to exposing Joni internals:

```java
PerlPattern pattern = PerlPattern.compile("(?<word>\\w+)", PerlFlags.UNICODE);
PerlMatcher matcher = pattern.matcher(input);

if (matcher.find()) {
    String word = matcher.group("word");
}
```

The initial public surface should cover:

- immutable compiled patterns
- stateful matchers
- numeric and named captures
- `find`, anchored match, replacement, and split operations
- explicit Perl flags and byte-versus-Unicode input modes
- structured compile and match exceptions
- configurable resource limits

The API should document Perl semantics directly. Similarity to
`java.util.regex.Pattern` and `Matcher` is useful for discoverability, but it
must not imply Java-regex behavior where Perl differs.

## Executable Pattern Tiers

Executable constructs require a deliberately separate contract:

1. **Data-only engine**: Ordinary patterns, recursion, subroutine calls,
   conditionals, control verbs, and other constructs that do not execute host
   language code. This is the safe default artifact and API.
2. **Host-callout engine**: Callouts invoke explicitly registered Java
   callbacks through a constrained interface. Applications control the
   registry and policy.
3. **Perl-execution integration**: Constructs such as `(?{ ... })` and
   `(??{ ... })` execute Perl code in a PerlOnJava runtime context. This belongs
   in a separate opt-in integration artifact, not in the default library.

The data-only library must reject executable constructs unless the caller has
selected and configured the corresponding execution tier.

## Architecture

The standalone library should depend on a narrow regex-runtime module rather
than the complete PerlOnJava compiler and runtime. The preferred dependency
direction is:

```text
public Perl regex API
        |
minimal Perl regex compatibility layer
        |
PerlOnJava Joni fork
```

The compatibility layer may contain generated Unicode/property data,
byte/Unicode provenance, diagnostics, replacement semantics, and other logic
that is genuinely part of Perl regex behavior. General Perl parsing,
bytecode generation, global variables, and runtime operators should remain
outside the data-only artifact.

Joni classes are implementation details. Applications should not depend on
fork-specific packages or internal syntax nodes, so the fork can evolve
without breaking the public API.

## Compatibility Contract

Releases should identify a target Perl version and publish measured
compatibility rather than claim unqualified "Perl compatible" behavior. The
release evidence should include:

- the exact upstream Perl regex corpus revision
- selected, executed, passed, failed, and skipped test counts
- byte, Unicode, JVM, and interpreter dimensions where applicable
- known unsupported or intentionally different behavior
- results relative to the maintained PerlOnJava baseline

Compatibility changes should follow semantic versioning at the API level.
Corrections that make matching behavior agree with the declared Perl version
may still affect applications and must be called out in release notes.

## Packaging and Attribution

Publish the data-only API and Perl-execution integration as separate Maven
artifacts. Avoid split packages with upstream Joni and other Joni forks. The
fork should remain in the collision-resistant PerlOnJava namespace selected by
the Joni fork design.

All original Joni copyright, license, and authorship notices must be retained.
Generated data and derived sources must record their input source, applicable
license, generator, and reproducible generation command.

## Security and Resource Control

Regex matching can consume substantial CPU, memory, and stack even without
callbacks. The API should support match deadlines or operation budgets,
backtracking/stack limits where technically possible, and cancellation. It
must define whether compiled patterns and inputs retain caller-owned data.

Executable tiers require stronger isolation guidance. Callback and Perl-code
execution must be disabled by default, explicit at construction time, and
documented as execution of trusted code rather than ordinary regex matching.

## Release Prerequisites

Implementation should not begin until the regex implementation establishes all of the
following:

- ordinary constant patterns use Joni by default
- temporary Java backend routing is removed from the advertised path
- temporary PerlOnJava preprocessors have either moved into justified Joni
  internals or been removed
- the four-leg differential matrix has no unexplained regressions
- the final upstream Perl regex comparison meets the project parity gate
- fork packaging, licensing, attribution, and generated-data provenance are
  audited
- the internal regex API is separable without depending on general Perl
  execution

## Open Questions

- Which Perl release defines the first compatibility target?
- Should byte input use `byte[]`, a dedicated immutable value, or both?
- Should replacement templates be part of the first release?
- Which resource limits can the Joni fork enforce reliably?
- Is the host-callout tier useful enough to publish before the full
  Perl-execution integration?
- Should the artifact name emphasize Perl compatibility, PerlOnJava, or the
  Joni fork while avoiding confusion with PCRE?

## Relationship to Current Work

The regex implementation remains focused on completing and validating PerlOnJava's own full
Joni migration. This RFC is a possible follow-on productization step. It must
consume the completed implementation rather than introduce a second regex
behavior or stabilize transitional routing decisions.

Related documents:

- `dev/design/regex-implementation.md`
- `dev/implementation/regex.md`
- `docs/design/joni-callout-fork.md`
