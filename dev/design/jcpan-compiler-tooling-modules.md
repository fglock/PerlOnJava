# jcpan compiler and tooling modules

## Goal

Fix reusable compiler and CPAN-tooling blockers exposed by Pegex::JSON,
Music::Factory, Bio::Data::Plasmid::CloningVector,
Catalyst::Engine::HTTP::POE::YieldCC, App::Chained, Queue, and their
dependencies. Prefer shared runtime/tooling fixes and existing Java libraries
over distribution preferences.

## Progress Tracking

### Current Status: PR #964 open; CI in progress

### Completed Phases

- [x] Phase 1: baseline and system-Perl classification (2026-08-15)
  - Captured bounded `jcpan -t` logs for all six requested modules.
  - Identified malformed Bio-MCPrimers packaging and Catalyst's omitted
    Restarter::Watcher as upstream distribution failures.
- [x] Phase 2: shared root-cause implementation (2026-08-15)
  - Restored YAML::PP's standard object `dump` API.
  - Serialized scalar references through their referents for boolean.pm parity.
  - Made gzip EOF status compatible with CPAN single-file extraction.
  - Extended generic missing-prerequisite discovery to TAP diagnostics.
  - Routed Object::Pad's core syntax to PerlOnJava's native class compiler.
- [x] Phase 3: cross-runtime regression validation (2026-08-15)
  - Validated YAML, gzip, native-class, and CPAN-tooling regressions with
    system Perl where applicable.
  - Passed the focused regressions on JVM and interpreter backends.
- [x] Phase 4: requested module verification (2026-08-15)
  - Pegex::JSON: 4 files, 21 assertions, PASS.
  - Music::Factory: 5 files, 20 assertions, PASS.
  - App::Chained: 2 files, 9 assertions, PASS after generic dependency retry.
  - Queue: single-file distribution built and tested successfully; upstream
    ships no test directory.
  - Bio::Data::Plasmid::CloningVector excluded because Bio-MCPrimers has no
    Makefile.PL and fails system-Perl configuration.
  - Catalyst::Engine::HTTP::POE::YieldCC excluded because its distribution
    requires but does not ship or declare Restarter::Watcher; system Perl
    reproduces the missing-module failure.
- [x] Phase 5: full verification (2026-08-15)
  - Full `make` passed all unit shards.
- [ ] Phase 6: pull request and CI
  - Opened [PR #964](https://github.com/fglock/PerlOnJava/pull/964).
  - CI checks are in progress.

### Next Steps

1. Monitor all PR #964 CI checks to completion.
2. Record the final CI result here.

### Open Questions

- Object::Pad-specific MOP and extension APIs remain outside the native class
  compatibility pragma; the requested Music::Factory surface uses core syntax.

## References

- Skills: `debug-perlonjava`, `port-cpan-module`
