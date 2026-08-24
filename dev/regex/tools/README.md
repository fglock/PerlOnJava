# Regex implementation tools

This directory contains tools, policies, and focused tests that were created
for the regex implementation project. General-purpose development utilities
remain in `dev/tools`.

## Corpus and acceptance

`generate_regex_test_ledger.pl` derives the current regex corpus from the
imported Perl tests and documented feature references. The acceptance,
comparison, CPAN, packaging, and performance scripts consume that ledger and
write their artifacts to caller-selected directories.

`run_regex_acceptance.pl` runs the bounded JVM and
interpreter corpus legs. Memory-sensitive fixtures use a serial lane;
`pat_psycho*` and `speed*` use the separately bounded CPU-heavy lane.

`collect_direct_thread.pl` projects the direct/thread
results and applies the exact entries in
`direct_thread_allowlist.json`.

## Archived project evidence

The final-envelope and execution-authority tools, together with their focused
fixtures, are archived historical one-off tooling from the regex implementation
project. This includes `assemble_acceptance_envelope.pl`,
`assemble_final_performance.pl`, `check_final_performance.pl`, and the associated
final-envelope, final-performance, and evidence-identity security tests.

They remain here so the implementation record can be reproduced and inspected.
Their historical envelope and authority fixture semantics are explicitly not
active release gates in the replacement plan. Current delivery uses the
functional, parity, bundled-module, warmed-performance, bounded-stress,
packaging, platform, code-cleanup, documentation, and UAT gates documented in
`dev/design/regex-implementation.md`.

## Joni packaging and Unicode data

`verify-joni-packaging.pl` verifies standalone Joni/JCodings ownership, notices,
SBOM identities, and dependency edges.

`generate_perl_unicode_data.pl` is the entry point for the checked-in
Perl-derived Unicode Java tables. Its manifest is
`perl_unicode_data_generators.json`; individual generators remain executable
for focused development.

Run the orchestrator from the repository root:

```bash
perl dev/regex/tools/generate_perl_unicode_data.pl --check
perl dev/regex/tools/generate_perl_unicode_data.pl --refresh
```

## Focused tests

The dedicated tool tests live in `dev/regex/tools/tests`. Run the focused test
for the tool being changed, for example:

```bash
timeout 600 prove dev/regex/tools/tests/generate_regex_test_ledger.t
```

Do not use a directory-wide `prove` run as a release gate: it includes the
archived historical envelope and authority fixtures described above. Use the
current replacement-plan gates for release acceptance.
