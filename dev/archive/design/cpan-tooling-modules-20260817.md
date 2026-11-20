# CPAN tooling module batch (2026-08-17)

## Goal

Make `jcpan -t` work for the portable parts of these indexed modules and
their dependency graphs by fixing reusable compiler, runtime, and CPAN tooling
defects:

- `AnyEvent::Gearman::Worker::RetryConnection`
- `WebService::Connpass`
- `Util::Medley::Exec::Cache`
- `Amazon::DynamoDB::SignatureV4`
- `App::CPANChangesUtils`
- `Convert::TBX::RNG`

Distributions that fail under the local system Perl are outside the acceptance
gate. Distribution preferences are a last resort.

## Final Results

| Target | System-Perl qualification | PerlOnJava result |
|---|---|---|
| `AnyEvent::Gearman::Worker::RetryConnection` | Fails because its tests require an external `gearmand`, real `fork`, and EV. | Ignored under the requested gate. |
| `WebService::Connpass` | Offline tests pass, but the distribution's online HTTPS test fails in the local environment. | Ignored under the requested gate. |
| `Util::Medley::Exec::Cache` | Its own suite fails after dependency resolution, including cache/list/file failures and unavailable native `File::LibMagic`. | Ignored under the requested gate. Its dependency run nevertheless exposed and drove the shared `Sub::Util` and `Fcntl` fixes. |
| `Amazon::DynamoDB::SignatureV4` | Amazon-DynamoDB's offline test passes; live cloud tests skip. | Passes: 1 test passed, 10 live-service files skipped, exit 0. |
| `App::CPANChangesUtils` | The cold dependency qualification exceeded the bounded system-Perl window without reaching a target verdict. | Passes its complete 0.077 distribution test after resolving the full dependency graph, exit 0. |
| `Convert::TBX::RNG` | Its target tests fail on macOS because upstream TBX::Checker hardcodes the Windows `;` Java classpath separator. | Ignored under the requested gate. Its graph exposed and drove the MakeMaker metadata and XML external-DTD fixes. |

## Implemented Tooling and Runtime Fixes

- `ExtUtils::MakeMaker` removes metadata-forbidden control characters from
  `ABSTRACT` before emitting `MYMETA.yml` and `MYMETA.json`. This prevents a
  malformed abstract from hiding the distribution's prerequisite graph.
- `XML::LibXML` carries the input system identifier into JAXP and honors
  `load_ext_dtd`, while keeping external DTD loading disabled by default.
- Loading `List::Util` initializes the shared `Sub::Util` Java backend, matching
  the single shared XS library used by Scalar-List-Utils.
- `Fcntl` supplies portable zero-valued `O_BINARY` and `O_TEXT` constants on
  platforms where the flags have no effect.
- The provider manifest now marks `Scalar::Util`, `List::Util`, and `Sub::Util`
  as authoritative Java-backed modules from Scalar-List-Utils, preventing a
  downloaded XS copy from shadowing the working runtime implementations.
- Amazon-DynamoDB 0.25 receives a version-scoped patch that delays loading its
  Kavorka-based full API until construction. `SignatureV4` is plain Perl and
  does not use Kavorka; the eager load was the only reason its test process
  required Perl's live XS parser API. The patched top-level module retains the
  distribution's existing dynamic API-version selection and passes under both
  system Perl and PerlOnJava.
- The repository safety guide now records the build/test JAR replacement race
  found during this work and prohibits building a worktree while its `jperl`
  or `jcpan` process is active.

Digest::HMAC's RFC 2202 tests pass through the already bundled Digest::SHA
backend, so no duplicate cryptography implementation was added.

## Progress Tracking

### Current Status: Implementation complete; PR validation in progress

### Completed Phases

- [x] Repository safety pre-flight and feature branch (2026-08-17)
  - Confirmed a clean tree and created `fix/cpan-tooling-modules-20260817`.
- [x] System-Perl qualification (2026-08-17)
  - Applied the requested ignore gate to Gearman, Connpass, Util::Medley, and
    Convert::TBX::RNG using their actual target results.
- [x] Reusable compiler/tooling repairs (2026-08-17)
  - Fixed MakeMaker metadata, XML external-DTD plumbing, shared XS-provider
    bootstrap, portable Fcntl constants, and bundled-provider resolution.
- [x] Supported target verification (2026-08-17)
  - `App::CPANChangesUtils` 0.077: `Result: PASS`, exit 0.
  - `Amazon::DynamoDB` 0.25 for the requested signer: `Result: PASS`, exit 0.
- [x] Full local regression suite (2026-08-17)
  - `make`: `BUILD SUCCESSFUL`, all five unit shards and Joni passed.

### Next Steps

1. Push the final compatibility patch and documentation commit.
2. Mark PR #1004 ready and wait for Ubuntu and Windows CI to pass.

### Open Questions

- A general Java implementation of `Parse::Keyword` remains future work. It
  requires a live parser-extension API (lex cursor mutation and partial Perl
  expression compilation), not a simple XS function mapping. No supported
  target in this batch uses that API after removing Amazon's unrelated eager
  load.

## Related References

- `docs/guides/module-porting.md`
- `dev/design/patch-and-cpan-prefs-layout.md`
- `.agents/skills/debug-perlonjava/SKILL.md`
- `.agents/skills/port-cpan-module/SKILL.md`
