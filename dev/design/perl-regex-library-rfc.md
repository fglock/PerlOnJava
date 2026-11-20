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

The following Phase 1 decisions are now recorded; the validation items remain
release blockers for a standalone artifact:

- **Compatibility target:** track the current Perl blead checkout at `perl5/`,
  with each data refresh pinned to its exact commit. At this RFC snapshot that
  is Perl 5.45.5, commit
  `29974cc644cdca03cbdc4d603b6965b0427934bf`, with Unicode 18.0.0. The runtime
  release version remains 5.44.1; this does not make the whole runtime claim
  blead compatibility. Each independently sourced data family must retain its
  own version and provenance.
- Complete a source-by-source license and generated-data provenance audit for
  Joni, JCodings, ICU, aliases, named characters, and their generators.
- **Initial API recommendation:** immutable compiled data-only patterns and
  stateful matchers, with explicit Unicode `String` and byte-array inputs,
  offsets in the corresponding input units, and explicit Perl flags. Keep
  replacement and split, structured diagnostics, and concurrency guarantees
  outside the initial contract until their semantics and retention behavior
  are specified. Do not expose Joni classes. Perl code and host callbacks are
  excluded from the default artifact; any later callout tier needs a separate
  opt-in contract.
- Measure the extracted artifact's dependency closure and resource controls;
  the current runtime's backtracking behavior does not yet prove enforceable
  standalone matching limits or cancellation.
- Validate the dependency boundary with a clean external Java consumer and
  differential, concurrency, cancellation, and packaging tests.

No external consumer validation or market evidence is recorded yet, so the
audience and use cases remain hypotheses. Success for Phase 1 is a reviewed
compatibility target, API boundary, dependency/license/provenance inventory,
and an explicit extraction recommendation. Success for an implementation
release additionally requires a clean external Java consumer, measured
dependency closure, differential and concurrency coverage, and enforceable
resource/cancellation behavior. The existing PerlOnJava unit/core corpus is
useful engine evidence, but does not satisfy that standalone release gate.

## Extraction Boundary Audit — 2026-11-16

This is an initial source and packaging audit, not release approval:

| Area | Current evidence | Extraction implication |
| --- | --- | --- |
| Engine | `third_party/joni` is the PerlOnJava-maintained Joni 2.2.7 fork. The root build relocates `org.joni` and `org.jcodings` into private namespaces in the PerlOnJava uber-JAR and includes the Joni license and fork notice. | Preserve the fork namespace, source history, and notices. The existing uber-JAR is not a standalone regex artifact. |
| Encoding | The build resolves JCodings 1.0.64 as a separate dependency. The adapter has a Perl-specific wide-scalar codec and byte/Unicode conversion paths. | JCodings is part of the minimal engine closure; Perl byte and scalar conversion behavior needs a runtime-neutral replacement or a justified extracted module. |
| Property resolution | `UnicodeResolver` and generated data classes use ICU4J 78.3 (`UnicodeSet`, `UCharacter`, and `UProperty`). The root build has ICU4J on the application classpath. | Do not treat generated alias tables as a replacement for ICU. A standalone dependency boundary must decide which ICU-backed resolution behavior remains. |
| Perl integration | `JoniRegexPattern` directly imports `WarnDie`, `PerlUtfString`, named-character expansion types, and Perl runtime types. `RuntimeRegex` supplies dynamic patterns, callbacks, warning handling, captures, and alarm integration. | The adapter cannot move unchanged into a data-only library. Callback and Perl execution paths must be split from a smaller core boundary. |
| Unicode provenance | The generator manifest selects the current `perl5/` blead checkout, Perl 5.45.5 at commit `29974cc644cdca03cbdc4d603b6965b0427934bf`, and UCD 18.0.0. Some generated data has distinct provenance, including named sequences at UCD 17.0.0 and Bidi inputs named for UCD 17.0.0. The case-fold table also has an independently pinned Perl-source baseline, whose refresh is gated by an existing exact-provenance regression test. The application version remains 5.44.1. | Track blead for generated aliases and properties while preserving each data family's explicit source pin. “Perl-compatible” still requires family-by-family version/provenance documentation; the application runtime is not itself a blead release. |
| Licensing | The fork notice records upstream Joni MIT provenance; JCodings carries its own retained license file; PerlOnJava source is dual GPL/Artistic; generated Unicode sources retain Unicode notices. The generated SBOM records ICU4J's Unicode license URL. | These top-level records identify the audit scope but do not replace a file-by-file source, generator, and redistribution review. No relicensing conclusion is made. |
| Resource limits | Joni exposes interruptible matching and an internal match-stack cap; the Perl runtime wraps alarm matching in a worker executor and cancels its future on timeout. | These mechanisms are evidence for possible controls, not yet proof of a public API's cancellation, per-match limits, or worker termination contract. Standalone limits need adversarial tests at the library boundary. |

The first clean extraction experiment must therefore measure the closure of a
runtime-neutral adapter, generated property data, ICU4J, and JCodings without
including `RuntimeRegex` or the Perl compiler. Until that experiment exists,
artifact size, external-consumer usability, and effective public resource
controls remain unverified.

## Engine Gate Review — 2026-11-16

The engine prerequisites in the Release Prerequisites section have been
reviewed against the merged implementation work:

- **Production matcher and backend routing: satisfied.** The regex
  implementation design identifies Joni as the sole production matcher.
  Remaining `java.util.regex.Pattern` uses in `RuntimeRegex` parse Perl source
  metadata and diagnostics; user-pattern matching goes through
  `JoniRegexPattern` and `RegexMatcher`.
- **Migration cleanup and parity: satisfied by the recorded merged gates.**
  [PR #1095](https://github.com/fglock/PerlOnJava/pull/1095) records the full
  Perl comparison, JVM/interpreter regex corpus and stress results, warmed
  performance comparison, packaging and provenance checks, and Ubuntu/Windows
  CI. [PR #1101](https://github.com/fglock/PerlOnJava/pull/1101) records the
  final release `make`, bundled-module, platform, and bounded regex stress
  gates. Both PRs are merged.
- **Standalone packaging and API boundary: still open.** The existing public
  `RegexMatcher` interface is backend-neutral, but it lives in the runtime
  package and is created by `RuntimeRegex` with `RuntimeScalar` and Perl
  callback state. The underlying `JoniRegexPattern` directly imports Perl
  warning, scalar-conversion, named-character, and runtime types. This is a
  useful starting seam, not evidence that an external consumer can compile or
  match without the Perl runtime.

The next extraction experiment should preserve the completed matcher and
parity gates while separating a runtime-neutral compile/match context from the
Perl-specific services. The remaining design choices in this RFC and the
requested single-PR versus separate-repository scope must be settled before
the standalone artifact can be implemented and validated.

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
- `dev/architecture/regex.md`
- `docs/design/joni-callout-fork.md`
