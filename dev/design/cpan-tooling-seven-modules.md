# CPAN Tooling Support for Seven Module Families

## Goal

Make the following distributions and their dependencies test through `jcpan`
by fixing reusable compiler, runtime, and build-tool behavior:

- Dancer::Plugin::DBIC
- Test::Simpler
- App::PhoneNumberUtils
- ExtUtils::InferConfig
- Plate
- SQL::Translator::Producer::GoogleBigQuery
- IO::AsyncX::Sendfile

Failures reproduced by system Perl are outside this work. Distribution-specific
preferences are avoided; narrow compatibility modules are used only where the
upstream distribution requires XS that cannot be built for PerlOnJava.

## Implementation

### Compiler and runtime semantics

- Preserve live lexical-pad names, declaration kinds, and aliases for
  PadWalker `peek_my` and `peek_our` on both execution backends.
- Localize individual array elements by replacing and later restoring the
  container slot, including read-only argument aliases.
- Scalarize one-element parenthesized ternary branches in scalar/lvalue
  contexts.
- Match Perl diagnostics for missing closing delimiters and strict-subs
  barewords.
- Avoid content-hashing large immutable strings in the UTF index cache; use a
  bounded per-thread identity cache for repeated operations on the same value.
- Cache registered-code names, use native-long fast paths for bitwise
  operators, and avoid quadratic active-frame scans in async-heavy workloads.
- Treat pristine arguments of active calls as reachability roots, following
  their callback captures so Future sequence objects cannot be destroyed while
  `get`/`await` is still executing.

### Process and build tooling

- Give PerlOnJava's IPC::Cmd backend separate concurrent stdout and stderr
  drains and preserve their distinct results.
- Let Module::Build skip XS compilation on PerlOnJava when no native compiler
  backend is available.
- Provide a bundled Module::Build::Tiny compatibility layer for the same XS
  omission and make `jcpan` prefer bundled tooling.
- Complete DBI's standard and interval constant surface required by downstream
  schema and SQL tooling.

### Java-backed native-module replacements

- Implement Plate's small XS surface in Java while preserving glob aliasing,
  aggregate-reference argument handling, constants, and declaration metadata.
- Implement Sys::Sendfile in Java using existing RuntimeIO and SocketIO
  facilities, including partial non-blocking writes. This keeps the async API
  portable without introducing another native dependency.
- Make IO::Poll report socket readiness alongside immediately-ready non-socket
  descriptors, and return a defined zero for zero-length socket `syswrite`.

## Validation

The focused regression test is `src/test/resources/unit/cpan_tooling_runtime.t`.
It is first run with system Perl, then included in the normal `make` suite.
Each requested distribution is also run through a bounded `jcpan -t` command;
network/socket tests are run with loopback access where required.

## Progress Tracking

### Current Status: complete (2026-08-16)

### Completed Phases

- [x] Reproduce and classify failures (2026-08-16)
  - Separated system-Perl failures from PerlOnJava compiler/tooling gaps.
- [x] Implement reusable compiler and runtime fixes (2026-08-16)
  - Added pad introspection, localization, ternary-context, diagnostics,
    process capture, and large-string cache fixes.
- [x] Implement missing portable native-module surfaces (2026-08-16)
  - Added Java Plate and Sys::Sendfile implementations using existing runtime
    facilities.
- [x] Add focused regression coverage (2026-08-16)
  - Added and validated `cpan_tooling_runtime.t` with system Perl.
- [x] Complete final validation (2026-08-16)
  - System Perl: `cpan_tooling_runtime.t` passed all 19 assertions.
  - Full repository `make`: passed (latest run: 4m 21s).
  - `Dancer::Plugin::DBIC`: 7 files, 23 tests passed.
  - `Test::Simpler`: 4 files, 11 tests passed.
  - `App::PhoneNumberUtils`: 5 files, 7 tests passed after its dependency
    cache was populated across two bounded runs.
  - `ExtUtils::InferConfig`: 4 files, 325 tests passed.
  - `Plate`: 7 files, 189 tests passed.
  - `SQL::Translator::Producer::GoogleBigQuery`: 5 files, 8 tests passed.
  - `IO::AsyncX::Sendfile`: 4 files, 177 tests passed, including parallel
    Unix-domain-socket transfers.

### Next Steps

1. Open the pull request.
2. Verify all CI checks.

### Open Questions

- None blocking this phase. Future work may share PadWalker's opt-in frame
  registry with the debugger after profiling both paths.

## Related Documentation

- `dev/modules/padwalker.md`
- `.agents/skills/debug-perlonjava/SKILL.md`
- `.agents/skills/port-cpan-module/SKILL.md`
