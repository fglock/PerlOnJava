# Unicode and Encode compatibility

## Current state — 2026-11-20

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
- `nice -n 19 make` passes.
- `nice -n 19 make check-links` passes.
- All 19 bundled URI-Fast test files pass on JVM and interpreter.
- Encode-DoubleEncodedUTF8 0.05 passes 7 tests on JVM and interpreter.
- TV-ARIB-ProgramGenre 0.01 passes 21 tests on JVM and interpreter.
- Text-Unicode-Equivalents 0.05 passes on system Perl. Under PerlOnJava its
  `t/tests.t` reaches all 26 assertions but 15 fail because the distribution
  reads Perl's private `unicore/Decomposition.pl` file with `do`; PerlOnJava
  does not currently bundle that internal file. Adding the host Perl 5.34 UCD
  directory to `@INC` makes the same suite pass. This dependency is separate
  from `getComposite`'s documented behavior and remains to be triaged.

## Decisions and next step

`getComposite` follows the system Perl oracle: non-starter pairs such as
U+0308/U+0301 return undef. Do not special-case these pairs to accommodate a
consumer that separately reads Perl's private decomposition table.

Keep the remaining Unicode private-data limitation visible in issue #2104 and
track it separately if support for CPAN modules that `do` private core files is
wanted. The current work should close the direct API gap and the other four
reported behaviors.

## Related material

- [Issue queue](issue-queue.md)
- [CPAN module porting skill](../../.agents/skills/port-cpan-module/SKILL.md)
