# UUID (`jcpan -t UUID`) support plan

## Problem statement

`./jcpan -t UUID` currently fails on PerlOnJava because the `UUID` CPAN distribution is XS/C-heavy and its build/test flow expects native compilation. PerlOnJava cannot compile that XS code, so the upstream `make` stage breaks before meaningful runtime validation.

PerlOnJava already has `Data::UUID` support in `src/main/java/org/perlonjava/runtime/perlmodule/DataUUID.java`, but the `UUID` module exposes a different API surface (procedural functions, additional versions, and import tags/options).

## Goals

- Make `./jcpan -t UUID` complete successfully on PerlOnJava.
- Provide a working bundled `UUID` implementation with compatible core APIs.
- Reuse existing `DataUUID` functionality where semantics overlap.
- Keep the implementation maintainable by centralizing shared UUID conversion logic.

## Non-goals (initial pass)

- Bit-for-bit parity for every edge case of persistence and platform-specific behavior in the first iteration.
- Reimplementing CPAN's full native RNG/state stack exactly as in C.

## Proposed approach

### 1) Bundle `UUID.pm` shim in PerlOnJava

Add a bundled `src/main/perl/lib/UUID.pm` so CPAN-installed `UUID.pm` is not required for normal runtime use and upstream XS bootstrap paths are avoided where appropriate.

Expected effect:
- `ExtUtils::MakeMaker` detects primary module as bundled and skips incompatible upstream test execution unless explicitly forced.
- `jcpan -t UUID` becomes resilient to XS-only build/test assumptions.

### 2) Add Java-backed `UUID` module entry point

Create `src/main/java/org/perlonjava/runtime/perlmodule/UUID.java` and expose core procedural API expected by the CPAN module:

- generation: `uuid`, `uuid0`, `uuid1`, `uuid3`, `uuid4`, `uuid5`, `uuid6`, `uuid7`
- binary operations: `generate*`, `parse`, `unparse*`, `compare`, `copy`, `clear`, `is_null`
- metadata helpers: `time`, `type`, `variant`, `version`

### 3) Reuse and factor shared logic from `DataUUID`

Extract or mirror the reusable internals from `DataUUID`:

- binary UUID <-> string/hex/base64 conversion helpers
- v3 (MD5 namespace) generation flow
- byte-level compare/parsing utilities

Add shared utility helper(s) if needed (for example, a `UUIDUtils` runtime helper) to avoid duplicated conversion code between `DataUUID` and `UUID`.

### 4) Compatibility and option handling

Implement import-time behavior that matters for common use:

- export tags (`:all`) and standard function exports
- safe handling strategy for options like `:mac=` and `:persist=`:
  - either support directly, or
  - accept but degrade gracefully with documented behavior, without hard crashes

### 5) Verification and regression checks

Minimum validation matrix:

- `timeout 600 ./jcpan -t UUID` (must pass)
- direct smoke checks:
  - `./jperl -e 'use UUID qw(uuid4); print uuid4(), "\n"'`
  - `./jperl -e 'use UUID qw(parse unparse); my ($b,$s)=("",""); $s=UUID::uuid4(); UUID::parse($s,$b); UUID::unparse($b,$s); print "$s\n";'`
- ensure existing `Data::UUID` behavior still works:
  - `./jperl -e 'use Data::UUID; my $u=Data::UUID->new; print $u->create_str, "\n"'`

Run with output redirected to files per project testing guidance.

## Risks and mitigations

- API mismatch between `UUID` and `Data::UUID`.
  - Mitigation: keep `UUID` as dedicated module surface, only share internal helpers.
- Hidden test expectations in `UUID` import flags/options.
  - Mitigation: prioritize behavior exercised by module consumers and `jcpan -t UUID`, then iterate.
- Duplicate logic drift between modules.
  - Mitigation: factor common code into shared helper class early.

## Implementation phases

1. **Phase 1: Bundled shim + skeleton Java module**
   - Add bundled `UUID.pm`
   - Add `UUID.java` with essential v4 parse/unparse/compare paths
2. **Phase 2: Complete generation and metadata APIs**
   - Implement v1/v3/v5/v6/v7 and helper introspection functions
3. **Phase 3: Compatibility polish**
   - Import options, edge cases, and parity cleanup
4. **Phase 4: Validation**
   - Re-run `jcpan -t UUID`, targeted smoke tests, and regression checks

## Progress Tracking

### Current Status: Planned (not started)

### Completed Phases

- [ ] Phase 1: Bundled shim + skeleton Java module
- [ ] Phase 2: Complete generation and metadata APIs
- [ ] Phase 3: Compatibility polish
- [ ] Phase 4: Validation

### Next Steps

1. Add bundled `UUID.pm` under `src/main/perl/lib/`.
2. Implement `UUID.java` with reusable conversion/generation helpers.
3. Refactor shared logic from `DataUUID.java` into common helper(s).
4. Run `timeout 600 ./jcpan -t UUID` and capture logs.

### Open Questions

- Should unsupported advanced options (`:persist`, specific MAC modes) be no-op or warning in initial rollout?
- Do we want strict parity for `UUID::time` semantics on non-time-based UUID variants in phase 1?
