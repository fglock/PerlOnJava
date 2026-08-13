# CPAN compiler and tooling compatibility suite

## Goal

Make `jcpan -t` work for Constructor::Sugar, Chandra::Markdown,
Test::Perl::Dist, XML::Writer::Simple, Games::Axmud, ISO::639, and their
dependencies by fixing reusable compiler, runtime, and CPAN tooling defects.
Distribution-specific preferences are a last resort. A target that also fails
under the local system Perl may be left unsupported with the failure recorded.

## Baseline (2026-08-13)

| Target | First actionable failure |
|---|---|
| Constructor::Sugar | `Constructor::SugarLibrary` generates a constructor for `My::Moose::Object`; invoking it reports that `new` is missing. |
| Chandra::Markdown | Load fails at the XS-only `Devel::Hook` dependency after traversing the call-parser dependency stack. |
| Test::Perl::Dist | Loading through `LWP::Online ':skip_all'` reports `You tried to plan twice`. |
| XML::Writer::Simple | 6/7 files pass; `XML::DT` calls `getChildnodes` on `XML::LibXML::Element`, where the compatibility alias is missing. |
| Games::Axmud | Compile test stops because XS-only `Glib` cannot load. |
| ISO::639 | Parser rejects `say STDERR "..."` in the installed module. |

All `jcpan` invocations are wrapped in `timeout` and their complete output is
captured under `/tmp/jcpan_*_baseline.log`.

## Progress Tracking

### Current Status: Implementation and regression validation complete

### Completed Phases

- [x] Initial reproduction and failure classification (2026-08-13)
  - Created a feature branch and captured all six requested root failures.
  - Identified initial shared parser, DOM compatibility, test-planning, object
    construction, and XS/tooling boundaries.
- [x] System Perl classification (2026-08-13)
  - Passing: Constructor::Sugar (Throwable-SugarFactory), Chandra::Markdown,
    XML::Writer::Simple, and ISO::639.
  - Ignored by the requested rule: Test::Perl::Dist fails with LWP::Online's
    duplicate plan and platform-specific Win32 coverage; Games::Axmud cannot
    build its native Gtk3/GObject dependency stack on this macOS host.
- [x] Compiler and runtime fixes (2026-08-13)
  - Added the 5.44 feature bundle and modern default `say` parsing without
    enabling syntax features that would change legacy Perl source semantics.
  - Corrected warning pragma propagation, multi-category FATAL modifiers, and
    runtime typeglob CODE redefinition warnings.
  - Added parser-owned, thread-local UNITCHECK queues plus BEGIN::Lift call
    parser handling, Devel::Hook accessors, and CallParser build exports.
  - Added `XML::LibXML::Element::getChildnodes` compatibility.
- [x] Portable Java module backends (2026-08-13)
  - Delegated Markdown rendering to commonmark-java with official GFM table,
    strikethrough, task-list, and autolink extensions.
  - Implemented Search::Trigram's indexed Dice-scoring API and safe Java ABI
    compatibility entry points; its distribution passes 94 tests.
  - Added reusable Object::Proto class/accessor construction and a headless
    Chandra::Element renderer for non-GUI consumers.
- [x] Requested target verification (2026-08-13)
  - Constructor::Sugar: 7 files / 95 tests pass.
  - Chandra::Markdown: 5 files / 83 tests pass.
  - XML::Writer::Simple: 7 files / 33 tests pass.
  - ISO::639: 16 tests pass.
  - Full `make`: passes after the completed implementation.
  - `make test-bundled-modules` was also run; it currently has 145 unrelated
    pre-existing harness/resource failures (principally relative `t/*.inc` and
    helper modules not copied into the test working directory). The requested
    CPAN target runs and the full unit build are unaffected.
- [x] Core-suite regression follow-up (2026-08-13)
  - Restricted runtime CODE-slot redefinition warnings so declaration
    materialization, Java-backed module methods, and same-value constant
    re-exports retain Perl's non-warning behavior.
  - Restored `re/pat.t` to 1098/1302, `op/gv.t` to 233/304, and `uni/gv.t`
    to 163/206; each failed-test set now matches master exactly.
  - Revalidated the new unit coverage with system Perl and completed a green
    full `make` build.

### Next Steps

1. Push the regression follow-up to PR #946.
2. Monitor Linux and Windows CI to completion.

### Open Questions

- Object::Proto's complete distribution covers advanced prototype chains,
  roles, modifiers, singleton, and introspection beyond Chandra::Markdown's
  dependency surface; those APIs are not claimed as complete here.
- Markdown::Simple's full distribution includes native ABI/arena diagnostics,
  granular extension-disable switches, and extensive highlighter/heading-ID
  behavior beyond Chandra::Markdown's supported rendering surface.

## Related References

- `docs/guides/module-porting.md`
- `dev/design/patch-and-cpan-prefs-layout.md`
- `.agents/skills/debug-perlonjava/SKILL.md`
- `.agents/skills/port-cpan-module/SKILL.md`
