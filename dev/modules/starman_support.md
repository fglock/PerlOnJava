# Starman Support for PerlOnJava

## Status: Phase 4 Complete (2026-05-08)

- **Module**: `Starman`
- **Primary command**: `./jcpan -t Starman`
- **Scope**: Investigate and fix Starman `jcpan` test failures, or classify remaining failures as hard platform limitations with clear operator guidance.

## Background

Starman is a widely-used PSGI server stack. Getting `./jcpan -t Starman`
reliable improves compatibility for Plack applications and gives users a more
complete server choice matrix next to `Plack::Handler::Netty`.

This document tracks the phased work to make Starman testing reproducible and
actionable on PerlOnJava.

## Current Context

- `fork` remains unsupported in PerlOnJava, which can affect prefork server
  ecosystems (`Net::Server::*`, `Server::Starter`).
- Historical CPAN compatibility snapshots include entries for:
  - `Starman`
  - `Starman::Server`
  - `Net::Server::PreFork`
  - `Server::Starter`
- Starman outcomes may be influenced by broader process and socket behavior, not
  only by Starman-specific code.

## Plan

### Phase 1: Baseline and Failure Capture

1. Run a timeout-bounded baseline:
   - `timeout 1800 ./jcpan -t Starman > /tmp/starman_jcpan_baseline.log 2>&1`
2. Capture:
   - exit code
   - first fatal error
   - failing stage (configure/build/test)
   - failing test files/subtests (if any)
3. Add command and observed result in the Baseline Results section below.

### Phase 2: Dependency and Root-Cause Classification

1. Build a dependency failure map from baseline logs.
2. Classify each blocker:
   - platform limitation (`fork`/prefork assumptions)
   - harness/process execution behavior
   - runtime I/O/socket semantics
   - module compatibility bug/regression
3. Prioritize blockers that directly block Starman test progress.

### Phase 3: Targeted Fix Streams

1. **Harness/process stream**
   - Patch CPAN/TAP process paths when failures come from subprocess behavior.
2. **Runtime I/O stream**
   - Patch socket/read/select semantics if failure signatures point at network runtime behavior.
3. **Module compatibility stream**
   - Add minimal compatibility fixes for specific failing modules in Starman's dependency chain.
4. After each fix, rerun the smallest affected test first, then rerun
   `./jcpan -t Starman`.

### Phase 4: Full Verification and Documentation

1. Re-run full Starman test flow:
   - `timeout 1800 ./jcpan -t Starman > /tmp/starman_jcpan_final.log 2>&1`
2. Run full project regression check:
   - `make`
3. Record:
   - files changed
   - before/after results
   - unresolved limitations
   - recommended workaround path if needed

## Baseline Results

Command:

```bash
timeout 1800 ./jcpan -t Starman > /tmp/starman_jcpan_baseline.log 2>&1
```

Observed result:

- `HTTP::Parser::XS` dependency passed in pure-Perl fallback mode.
- `Starman` reached `Build test` but failed (`Result: FAIL`).
- First hard failure in `Starman::Server` load chain:
  - `get_addr_info is not a valid Socket macro nor defined by Net::Server::Proto`
- Many Starman tests were already skipped with:
  - `fork() not supported on this platform (Java/JVM)`

Failure summary from baseline:

- Files: 18
- Tests executed: 1
- Failed test programs: 4 (all parse failures from early compile abort)
- Primary failure class: module compatibility in `Net::Server::Proto` imports

## Dependency Chain Notes

Actual chain observed during fixes:

1. `Starman` test invokes `Starman::Server`.
2. `Starman::Server` depends on `Net::Server::PreFork` / `Net::Server`.
3. `Net::Server` imports helper symbols from `Net::Server::Proto`.
4. `Net::Server::Proto` import-time export validation rejected helper symbols
   missing from `@EXPORT_OK` (`get_addr_info`, then `ipv6_package`).
5. After `Net::Server::Proto` import was fixed, Starman then failed on
   `Global symbol "$CRLF" requires explicit package name`, caused by missing
   scalar CRLF exports in PerlOnJava's `Socket.pm` for `IO::Socket qw(:crlf)`.

Blocker classes:

- **Module compatibility bug**: `Net::Server::Proto` export list/invocation model
- **Runtime/module compatibility bug**: `Socket.pm` missing `$CR/$LF/$CRLF` scalar exports
- **Platform limitation**: expected `fork`-based tests skipped

## Exit Criteria

- `./jcpan -t Starman` passes end-to-end, or
- remaining failures are reduced to explicit, reproducible platform limits with
  actionable operator guidance and no untriaged blockers.

## Implementation Notes

### Fixes Applied

1. **Socket compatibility alias**
   - File: `src/main/perl/lib/Socket.pm`
   - Added `get_addr_info` alias to `getaddrinfo` to satisfy older
     `Net::Server::Proto` import behavior during early triage.

2. **Net::Server distropref + patch bootstrap**
   - File: `src/main/perl/lib/CPAN/Config.pm`
   - Added bundled distropref `Net-Server.yml` and patch bootstrap entry for:
     - `Net-Server-2.018/Proto.pm.patch`

3. **Net::Server::Proto export-list patch**
   - File: `src/main/perl/lib/CPAN/Config.pm` (inline patch payload in `_bootstrap_patches`)
   - Added missing helper symbols to `@EXPORT_OK`:
     - `get_addr_info`
     - `safe_name_info`
     - `parse_info`
     - `object`
     - `ipv6_package`

4. **Force-installed patched Net::Server**
   - Command: `./jcpan -fi Net::Server`
   - Reason: ensure patched `Net::Server::Proto.pm` is actually installed to
     `~/.perlonjava/lib/` even when upstream Net::Server test suite remains red.

5. **CRLF scalar export parity**
   - File: `src/main/perl/lib/Socket.pm`
   - Added scalar exports and values:
     - `$CR`, `$LF`, `$CRLF`
   - Added them to `@EXPORT` and `:crlf` export tag for
     `IO::Socket qw(:crlf)` compatibility.

### Final Verification

Command:

```bash
timeout 1800 ./jcpan -t Starman > /tmp/starman_jcpan_final6.log 2>&1
```

Result:

- `Result: PASS`
- `Files=18, Tests=2` (remaining Starman tests are skipped due to unsupported `fork`)
- Exit: `0`

Project regression verification:

```bash
timeout 5400 make > /tmp/make_starman_final.log 2>&1
```

- Exit: `0`

## Progress Tracking

### Current Status: Completed and passing

### Completed Phases

- [x] Phase 1: Baseline and failure capture (2026-05-08)
- [x] Phase 2: Dependency and root-cause classification (2026-05-08)
- [x] Phase 3: Targeted fix streams (2026-05-08)
- [x] Phase 4: Full verification and documentation (2026-05-08)

### Next Steps

1. If desired, reduce noisy `Net::Server::Proto` redefinition/prototype warnings.
2. Optionally add a dedicated regression test for `IO::Socket qw(:crlf)` scalar exports.
3. Keep `Net-Server.yml` distropref and patch synced if upstream Net::Server version changes.

### Open Questions

- Should we suppress known benign `Net::Server::Proto` redefinition warnings to
  reduce log noise in CPAN runs?
- Do we want a distropref for Starman itself to explicitly annotate expected
  `fork`-related skips in test output?

## Related Documents

- `dev/modules/plack_handler_netty.md`
- `dev/modules/dancer2_support.md`
- `dev/modules/mojo_ioloop.md`
- `dev/modules/template.md`
