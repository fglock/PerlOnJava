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

The dedicated tool tests live in `dev/regex/tools/tests`:

```bash
timeout 600 prove dev/regex/tools/tests
```
