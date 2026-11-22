# CPAN tooling fixes for Ceph, Tie, DBIx, and Time modules

## Goal

Fix reusable PerlOnJava compiler, runtime, and CPAN tooling defects exposed by:

- `Ceph::RadosGW::Admin`
- `Kwiki::Edit::AdvisoryLock`
- `Tie::Config`
- `DBIx::Class::Sims::REST`
- `Time::TAI::Now`

`File::LckPwdF` and `Finance::FXCM::Simple` are baseline-only controls because
their native distributions also fail under system Perl on this macOS host.

## Baseline

| Module | PerlOnJava | System Perl | Disposition |
|---|---|---|---|
| Ceph::RadosGW::Admin | nested `Test::Spec` context becomes `undef` | minimal nested spec passes | fix runtime ownership |
| Kwiki::Edit::AdvisoryLock | passes | not needed | regression smoke only |
| Tie::Config | iterator exception and missing persisted write | passes all 21 tests | fix hash iteration and tied cleanup |
| File::LckPwdF | missing XS implementation | fails to load `_lckpwdf` on macOS | ignore |
| DBIx::Class::Sims::REST | `map { { $_->get_columns } }` produces a scalar count instead of a hashref | focused expression produces a populated hashref | fix parser block/hash disambiguation |
| Finance::FXCM::Simple | missing XS implementation | cannot compile without ForexConnect SDK | ignore |
| Time::TAI::Now | `Time::UTC::Now` XS dependency cannot build | `Time::UTC::Now` compiles; 57/58 tests pass, with only `Time::Unix` absent | add Java XS replacement |

## Design

1. Preserve Perl reference identity and strong ownership when a reference is
   stored inside a pure-Perl tied container implementation such as
   `Tie::IxHash`. Weak back-references must remain defined while that storage
   path remains reachable.
2. Make plain-hash `each` iteration mutation-tolerant. Perl permits deleting
   the current key while iterating; Java fail-fast map iterators must not leak
   `ConcurrentModificationException` into Perl code.
3. Ensure tied handlers receive deterministic finalization at the same visible
   lifecycle boundary as Perl so `Tie::Config` writes dirty state.
4. Bundle a Java XS implementation of `Time::UTC::Now`. Use `java.time.Instant`
   as an explicitly unbounded-accuracy clock source: normal calls return time
   with an undefined accuracy bound, while `DEMAND_ACCURACY` dies as documented.
   Keep the upstream Perl module and XSLoader API unchanged.
5. Add small unit regressions validated with system Perl before relying on
   upstream distribution suites.
6. Resolve top-level `SUPER::method` using the package at the method token's
   source location. Dynamically requiring a module from inside another sub must
   not inherit that caller's current subroutine package.
7. Parse an ambiguous inner brace at the start of a `map`, `grep`, or `sort`
   block in term context, while retaining the existing statement indicators
   for genuine nested blocks.

## Progress Tracking

### Current Status: Implementation and verification complete; pull request pending

### Completed Phases

- [x] Phase 1: Baseline and system-Perl classification (2026-08-16)
  - Captured full logs for all requested `jcpan -t` runs.
  - Confirmed `Kwiki::Edit::AdvisoryLock` already passes.
  - Confirmed native-only `File::LckPwdF` and `Finance::FXCM::Simple` also fail
    with system Perl and are out of scope.
  - Reduced the Ceph failure to nested `Test::Spec` plus `Tie::IxHash` weak
    ownership.
- [x] Phase 2: Shared compiler and runtime repairs (2026-08-16)
  - Snapshot plain-hash `each` entries so deleting the current key does not
    expose Java's fail-fast iterator behavior.
  - Include pure-Perl tie handlers in ownership/reachability walks and release
    global tie handlers during final destruction.
  - Resolve source-level `SUPER::method` calls from their lexical package in
    both execution backends.
  - Treat an ambiguous inner brace at the start of a block-taking operator as
    an anonymous hash constructor, fixing DBIx result serialization.
- [x] Phase 3: `Time::UTC::Now` Java port (2026-08-16)
  - Added the upstream-compatible Perl API and Java XS replacement backed by
    `java.time.Instant`.
  - Kept accuracy deliberately unbounded: ordinary calls return `undef` for
    accuracy and `DEMAND_ACCURACY` calls fail.
- [x] Phase 4: Regression coverage (2026-08-16)
  - Added focused tests for mutable `each`, tied ownership and shutdown,
    dynamically required `SUPER`, and `map` anonymous hashes.
  - Added a bundled-module suite for all `Time::UTC::Now` representations and
    conversions.
  - Validated both new suites against system Perl before PerlOnJava.
- [x] Phase 5: Distribution and project verification (2026-08-16)
  - `Ceph::RadosGW::Admin`, `Kwiki::Edit::AdvisoryLock`, `Tie::Config`,
    `DBIx::Class::Sims::REST`, and `Time::TAI::Now` pass their requested
    `jcpan -t` suites.
  - The focused regression passes on both JVM and interpreter backends, the
    bundled `Time::UTC::Now` suite passes, and the complete `make` build passes.

### Next Steps

1. Open a pull request and monitor CI to completion.

### Open Questions

- None. The DBIx failure was an independent parser ambiguity, and
  `Time::UTC::Now::_try_all` exposes the single mechanism actually used by the
  Java replacement.

## Related References

- `.agents/skills/debug-perlonjava/SKILL.md`
- `.agents/skills/port-cpan-module/SKILL.md`
- `docs/guides/module-porting.md`
- `dev/design/patch-and-cpan-prefs-layout.md`
