# PerlOnJava Feature Audit Probes

Small, rerunnable probes for validating documented PerlOnJava compatibility
gaps against system Perl and both PerlOnJava execution backends.

These are audit probes, not the project’s conformance-test suite. They are
intentionally focused on one behavior and emit standard TAP through
`Test::More`, so the results can be captured or consumed by existing Perl test
tools.

## Running the probes

Run system Perl first to establish the expected behavior. A passing TAP run
under native Perl is required before treating the probe as an oracle:

```bash
probe=dev/tools/feature-audit/autoclose_scope.t
timeout 60 perl "$probe" > /tmp/feature-audit-perl.txt 2>&1
```

Then run both PerlOnJava backends:

```bash
timeout 60 ./jperl "$probe" > /tmp/feature-audit-jvm.txt 2>&1
timeout 60 ./jperl --interpreter "$probe" > /tmp/feature-audit-interpreter.txt 2>&1
```

Always use a timeout for `jperl`, `jcpan`, and `prove` probes. Capture full
output to files, compare the native result with both backends, and record the
Perl version and platform when behavior is version- or platform-dependent.

## Probes

| Probe | Coverage |
|---|---|
| `autoclose_scope.t` | Buffered write and lexical filehandle scope exit |
| `autoclose_fd.t` | Whether an fd can be reopened after the lexical handle exits |
| `remaining_semantics.t` | Focused checks for smartmatch, restricted hashes, overload, `caller`, `ops`, DBM, `fork`, `dump`, and multibyte file positioning |
| `regex_remaining.t` | Executable conditional expressions, grapheme clusters, and `Extended_Pictographic` |
| `caller_fields.t` | Full 11-field `caller()` tuple and subroutine metadata |
| `numeric_bigint.t` | BigInt precision behavior |
| `numeric_bignum.t` | BigNum arbitrary-precision division |
| `numeric_bigrat.t` | BigRat rational arithmetic |
| `unicode_strings.t` | Unicode case mapping for non-UTF-8 byte strings |
| `multibyte_io.t` | Representative encoded-handle `seek`, `tell`, and `truncate` behavior |

## Interpreting results

- `yes`/matching output across native Perl and both backends means the tested
  contract is supported.
- A backend-only mismatch is a backend regression or partial-support finding,
  not proof that the whole feature is missing.
- A JVM-specific difference caused by the execution model should be recorded
  as a JVM limitation.
- A probe result alone does not justify changing the feature matrix when the
  probe only checks syntax or module loading; add a semantic probe first.

New probes must pass under system Perl before their output is used as the
expected-behavior oracle. A failing TAP run under PerlOnJava is useful audit
evidence for a gap; it is not itself a regression test expectation. Store
captured logs outside the repository unless a small, stable result is needed
as permanent evidence in a design document.
