# Unicode and Encode compatibility

## Current state — 2026-11-20

Feature source commit: `6a9fb373b`; Unicode table sync commit: `3b04b7bed`; PR #2133 is open.

This change groups five CPAN compatibility reports: custom `Encode::Encoding`
registration (#2124), `decode_utf8` on Unicode strings (#2121),
`Unicode::Normalize::getComposite` (#2104), `Encode::Byte` loading (#2079), and
Java-backed `URI::Fast` / `URI::Encode::XS` (#2117).

Implemented behavior:

- Pure-Perl codecs register their class object and aliases through
  `Encode::define_encoding`.
- `decode_utf8` rejects wide Perl strings before any Latin-1 conversion, leaving
  the source unchanged when called inside `eval`.
- `getComposite` is registered as a Java method and returns the NFC composite
  for a pair, or undef when the pair does not compose.
- `Encode::Byte` loads without XS and exposes the Java single-byte encodings.
- `URI::Fast` and `URI::Encode::XS` load Java providers. The unchanged
  URI-Fast 0.55 test files are included as bundled-module tests.

The URI implementation uses `java.net.URI` plus a Perl-facing adapter. It
preserves raw URI components, supports IRI percent encoding, query parameters,
path accessors, and RFC 3986 relative resolution. Provider types do not escape
the Perl API.

## Validation

- The four new project unit files pass under system Perl, JVM, and interpreter.
- `nice -n 19 make` passes on the Unicode table sync commit.
- `nice -n 19 make check-links` passes.
- `perl dev/import-perl5/verify_sync.pl` passes for all configured imports; both
  Unicode table rows pass filtered sync idempotence checks.
- All 19 bundled URI-Fast test files pass on JVM and interpreter.
- Encode-DoubleEncodedUTF8 0.05 passes 7 tests on JVM and interpreter.
- TV-ARIB-ProgramGenre 0.01 passes 21 tests on JVM and interpreter.
- Text-Unicode-Equivalents 0.05 passes all 26 tests under system Perl, JVM, and
  interpreter. Its use of `do "unicore/Decomposition.pl"` required bundling the
  generated Unicode 18.0 decomposition table at
  `src/main/perl/lib/unicore/Decomposition.pl`; the unchanged upstream test is
  now a bundled-module regression.

## Decisions and next step

`getComposite` follows the system Perl oracle: non-starter pairs such as
U+0308/U+0301 return undef. The separate decomposition table supplies those
legacy core records for consumers that read the private file directly. The
`unicore/UCD.pl` and `unicore/Decomposition.pl` are committed Unicode 18.0
tables from the adjacent `perl5` checkout, matching generated `unicore/Name.pl`.
The import manifest copies these source artifacts, and filtered `sync.pl`
idempotence checks confirm reproducible imports.

The five reported compatibility gaps and their regression suites are
implemented. PR #2133 is the active review and validation vehicle; the remaining
next step is to resolve CI and review feedback.

## Related material

- [Issue queue](issue-queue.md)
- [CPAN module porting skill](../../.agents/skills/port-cpan-module/SKILL.md)
