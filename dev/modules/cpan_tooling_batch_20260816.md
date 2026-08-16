# CPAN compiler and tooling compatibility batch (2026-08-16)

## Goal

Make `jcpan -t` work for Thread::CriticalSection, Types::Namespace,
Catalyst::Plugin::Unicode, Search::Sitemap, Method::Signatures::PP,
Authen::SimplePam, Text::RecordParser, and their supported dependencies.
Prefer reusable compiler, runtime, and CPAN-tooling fixes over distribution
preferences. A target that also fails under the isolated local system Perl may
be left unsupported with the failure recorded.

## Baseline

All `jcpan` invocations use a hard timeout and write complete logs under
`/tmp/jcpan_*_baseline.log`.

| Target | PerlOnJava baseline |
|---|---|
| Thread::CriticalSection | Passes (4 files / 5 tests). |
| Types::Namespace | URI::NamespaceMap dependency aborts while compiling generated Moo accessors: `Can't modify non-lvalue subroutine call of &__ANON__ in scalar assignment`. |
| Catalyst::Plugin::Unicode | `IO::Scalar` rejects a blessed anonymous glob during `tie *$self` and cleanup. |
| Search::Sitemap | Compressed sitemap read fails; two DateTime coercion checks cross a one-second wall-clock boundary. |
| Method::Signatures::PP | PPR's generated grammar fails regex compilation after unsupported `\\g`/`\\X` handling and an invalid character class. |
| Authen::SimplePam | XS-only Authen::PAM installs Perl files without a Java implementation, so `Authen/PAM.pm` does not load. |
| Text::RecordParser | `IO::Scalar` rejects a blessed anonymous glob; 15 of 17 files otherwise pass. |

The isolated system-Perl reference root is
`/tmp/perlonjava-system-cpan-local`; its CPAN home is
`/tmp/perlonjava-system-cpan-home`. Text::RecordParser passes 17 files / 205
tests under Perl 5.42. Types::Namespace's containing URI::NamespaceMap
distribution passes 9 files / 70 tests. Catalyst::Plugin::Unicode and
Search::Sitemap also pass. Authen::SimplePam's one-test suite passes after its
Authen::PAM dependency is built; Authen::PAM's own interactive test requires a
real account password and was not exercised. Method::Signatures::PP fails its
own upstream suite under system Perl 5.42 and is excluded under the stated
system-Perl rule.

## Progress Tracking

### Current Status: Implementation and local validation complete

### Completed Phases

- [x] Feature branch and PerlOnJava baseline (2026-08-16)
  - Captured all seven requested targets sequentially to avoid CPAN cache races.
  - Confirmed Thread::CriticalSection already passes on current master.
- [x] System Perl classification (2026-08-16)
  - All actionable targets pass system Perl.
  - Excluded Method::Signatures::PP because its own upstream suite fails on
    system Perl 5.42.
- [x] Compiler/runtime/tooling fixes (2026-08-16)
  - Fixed compile-time rejection of assignment to known non-lvalue
    subroutines; URI::NamespaceMap now passes 9 files / 70 tests.
  - Added strict anonymous-glob-reference dereferencing and blessed underlying
    `GLOB` type parity for IO::Scalar; Catalyst::Plugin::Unicode passes 4 files
    / 13 tests and Text::RecordParser passes 17 files / 205 tests.
  - Added MakeMaker `CONFIGURE` callback execution and authoritative-root
    destination collision handling for generated modules such as Authen::PAM.
  - Routed XML::Parser stream reads through tied-handle `READ`, restored
    blessed-glob stream detection, and preserved the original tied object;
    Search::Sitemap's compressed-index test now passes.
  - Added an Authen::PAM Java XS boundary which exposes its constants and
    reports `PAM_SYSTEM_ERR` for native operations until FFM conversation
    callbacks can be implemented safely.
- [x] Requested-target and full-build validation (2026-08-16)
  - `make` passes all 486 unit tests (two skipped) and Joni packaging/tests.
  - Thread::CriticalSection passes 4 files / 5 tests.
  - URI::NamespaceMap (the distribution selected for `Types::Namespace`)
    passes 9 files / 70 tests.
  - Catalyst::Plugin::Unicode passes 4 files / 13 tests.
  - Authen::SimplePam passes its one-test suite after building the generated
    Authen::PAM Perl layer against the Java compatibility boundary.
  - Text::RecordParser passes 17 files / 205 tests.
  - Search::Sitemap's functional compressed-input regression is fixed and
    125/126 assertions pass. Its remaining `'now'` assertion captures the
    expected wall clock several seconds before evaluating the coercion and is
    therefore one second stale on PerlOnJava. JFR confirmed general compiler,
    DateTime setup, and reachability work between those points; disabling
    auto-GC alone does not remove the delay. Runtime clock semantics and the
    upstream test were intentionally left unchanged.
- [ ] PR and CI validation

### Next Steps

1. Open the PR and monitor CI to completion.
2. Follow up upstream on Search::Sitemap's speed-sensitive `'now'` assertion;
   it should compare within a tolerance or capture the clock at coercion time.
3. Implement native PAM conversations only after an FFM ownership design is
   available.

### Open Questions

- A future full Authen::PAM port needs an FFM upcall with libc-owned response
  buffers and carefully scoped Perl runtime ownership; the current boundary
  deliberately returns `PAM_SYSTEM_ERR` instead of risking unsafe callbacks or
  reporting false authentication success.
- Search::Sitemap passes under the faster system Perl run, but its test expects
  a later `'now'` conversion to equal a timestamp captured during fixture
  construction. PerlOnJava correctly returns the current epoch second, so the
  assertion fails when setup takes more than one second.

## Related References

- `docs/guides/module-porting.md`
- `.agents/skills/debug-perlonjava/SKILL.md`
- `.agents/skills/port-cpan-module/SKILL.md`
- `.agents/skills/port-native-module/SKILL.md`
- `.agents/skills/profile-perlonjava/SKILL.md`
