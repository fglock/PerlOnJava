# Starman Support for PerlOnJava

## Status: Phase 0 Baseline Pending

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

Pending Phase 1 execution.

## Dependency Chain Notes

To be filled from baseline logs. Expected hotspots include:

- `Plack` integration modules
- `Net::Server`/`Net::Server::PreFork`
- `Server::Starter`
- process and TAP execution paths used by `jcpan -t`

## Exit Criteria

- `./jcpan -t Starman` passes end-to-end, or
- remaining failures are reduced to explicit, reproducible platform limits with
  actionable operator guidance and no untriaged blockers.

## Progress Tracking

### Current Status: Phase 0 in progress

### Completed Phases

- [ ] Phase 1: Baseline and failure capture
- [ ] Phase 2: Dependency and root-cause classification
- [ ] Phase 3: Targeted fix streams
- [ ] Phase 4: Full verification and documentation

### Next Steps

1. Capture baseline run log and first failure point.
2. Build blocker map and prioritize highest-leverage fix stream.
3. Implement targeted fixes and rerun Starman and `make`.

### Open Questions

- Does the current Starman failure (if any) come from Starman itself or from a
  transitive dependency test?
- Are remaining failures reducible without `fork`, or should they be documented
  as prefork limitations with Netty migration guidance?

## Related Documents

- `dev/modules/plack_handler_netty.md`
- `dev/modules/dancer2_support.md`
- `dev/modules/mojo_ioloop.md`
- `dev/modules/template.md`
