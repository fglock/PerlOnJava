# CPAN tooling module batch (2026-08-17)

## Goal

Make `jcpan -t` work for these indexed modules and their portable dependency
graphs by fixing reusable compiler, runtime, and CPAN tooling defects:

- `AnyEvent::Gearman::Worker::RetryConnection`
- `WebService::Connpass`
- `Util::Medley::Exec::Cache`
- `Amazon::DynamoDB::SignatureV4`
- `App::CPANChangesUtils`
- `Convert::TBX::RNG`

Distributions that fail under the local system Perl are outside the acceptance
gate. Distribution preferences are a last resort.

## Baseline

All PerlOnJava runs use isolated `PERLONJAVA_HOME` directories, bounded
`timeout` wrappers, and complete logs under `/tmp`.

| Target | Initial result |
|---|---|
| AnyEvent::Gearman::Worker::RetryConnection | Its distribution requires an external `gearmand`, real `fork`, and the XS-only EV loop. System Perl also fails because `gearmand` is unavailable. |
| WebService::Connpass | Dependency and system-Perl qualification in progress. |
| Util::Medley::Exec::Cache | Dependency qualification in progress; shares the Kavorka/call-parser stack with Amazon::DynamoDB. |
| Amazon::DynamoDB::SignatureV4 | Dependency qualification in progress. Digest::HMAC's 14 RFC 2202 tests already pass through the existing Digest::SHA backend. |
| App::CPANChangesUtils | First run timed out while resolving its large Perinci/Data::Sah dependency graph; the isolated cache is retained for a warm rerun. |
| Convert::TBX::RNG | Generated `MYMETA.yml` is rejected, so CPAN misses most declared dependencies and the one-shot missing-module retry remains incomplete. |

## Progress Tracking

### Current Status: Tooling fixes in progress

### Completed Phases

- [x] Repository safety pre-flight and feature branch (2026-08-17)
  - Confirmed a clean tree and created `fix/cpan-tooling-modules-20260817`.
- [x] Initial target and system-Perl qualification (2026-08-17)
  - Classified the Gearman distribution under the requested system-Perl
    ignore rule.
  - Confirmed the existing Java-backed SHA implementation is sufficient for
    Digest::HMAC; no duplicate crypto backend is needed.
- [x] First reusable CPAN metadata defect isolated (2026-08-17)
  - Convert::TBX::RNG and TBX::XCS carry a trailing carriage return in their
    abstract.
  - PerlOnJava's simplified MakeMaker embedded that control character in a
    single-quoted YAML scalar, making `Parse::CPAN::Meta` reject the complete
    prerequisite document.
  - Added upstream-compatible abstract sanitization and a focused regression
    test; the new test passes under system Perl.

### Next Steps

1. Rebuild PerlOnJava and verify the metadata regression with the rebuilt JAR.
2. Complete warm target runs and classify the next actionable failures.
3. Re-run every supported target, then run the full `make` suite.
4. Commit, push, open the PR, and monitor all CI checks to completion.

### Open Questions

- Whether the remaining dependency-heavy targets reach portable target tests
  before their first cold-cache timeout; warm reruns retain all completed
  dependency builds.

## Related References

- `docs/guides/module-porting.md`
- `dev/design/patch-and-cpan-prefs-layout.md`
- `.agents/skills/debug-perlonjava/SKILL.md`
- `.agents/skills/port-cpan-module/SKILL.md`
