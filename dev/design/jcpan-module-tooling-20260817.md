# jcpan module tooling follow-up (2026-08-17)

## Objective

Unblock compiler/runtime/tooling failures found while exercising:
`Labyrinth::Plugin::Review`, `Log::Minimal::Indent`, `LinAlg::Vector`,
`Text::TokenStream`, `Search::Typesense`, and `Astro::Constants`.

## Progress Tracking

### Current Status: implementation complete; PR/CI in progress

### Completed Phases

- [x] Phase 1: classify failures against the six distributions and their
  dependency closures.
  - System-Perl-only missing dependencies were not treated as runtime bugs.
  - Pure-Perl dependencies were installed through `jcpan --notest` for local
    validation.
- [x] Phase 2: shared parser and bundled-tooling fixes.
  - Fat-comma autoquoting now handles comparison keywords such as `eq =>` in
    direct calls, including list-context lookahead.
  - Constant-CV lookup and infix assignment validation preserve Perl's
    constant-item diagnostic.
  - Qualified scalar names now accept numeric package segments such as
    `$Astro::Constants::2019::VERSION`.
- [x] Phase 3: focused module validation and scope classification.
  - `Astro::Constants`: all 23 test files pass (136 assertions).
  - `Text::TokenStream`: non-regex lexer/token-stream coverage passes; the
    remaining `(*MARK:NAME)` dependency is assigned to the Joni migration.
  - `Labyrinth::Plugin::Review` and `Log::Minimal::Indent`: their remaining
    blockers are the deferred XS-backed `Session::Token` and `Guard` paths.
  - `LinAlg::Vector` is excluded by the system-Perl gate because its test
    cannot load Moose in the system Perl environment. Direct Moose constructor
    validation under PerlOnJava succeeds; the observed alias behavior belongs
    to the excluded dependency path.
  - `Search::Typesense` is excluded by the system-Perl gate because its test
    stack cannot load `Devel::StackTrace`. Its remaining cached-fixture
    assertion distinguishes JSON string `"125"` from numeric `125`.
  - Added regression coverage validated first with system Perl and then with
    both PerlOnJava backends.
  - Removed an over-broad identifier lookahead change after the clean-master
    baseline showed it regressed B::Deparse source offsets and XML module
    parsing; the minimized parser changes retain the target fixes.
- [x] Phase 4a: full local regression validation (2026-08-17).
  - A clean `origin/master` worktree was used to baseline four initial
    failures safely without altering the feature branch.
  - The minimized branch passes the required `make`, including all five unit
    shards, Joni tests, packaging verification, and the shadow JAR build.
- [ ] Phase 4b: publish the feature branch PR and verify CI checks.

### Open Questions / Handoffs

- `(*MARK:NAME)` remains with the parallel Joni regex migration. This branch
  deliberately does not duplicate that regex implementation.
- XS-backed dependencies, including `Guard` and `Session::Token`, are deferred
  from this compiler/tooling branch.
- No distribution preference overrides were added.

### Next Steps

1. Commit with attribution, push the branch, and open the PR.
2. Monitor and fix CI until all required checks pass.
