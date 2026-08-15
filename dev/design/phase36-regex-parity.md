# Phase 36: Complete Regex Parity

## Status

- **Project status:** Planned as a parallel project
- **Current stage:** Baseline refresh and architecture validation
- **Parent plan:** `dev/design/concurrency.md`
- **Detailed callback design:** `dev/design/executable-regex-callbacks.md`
- **Integration rule:** Direct regex semantics are implemented first. Thread
  wrappers receive no compatibility branches or test-specific behavior.

## Objective

Complete the shared Perl regex implementation exercised by direct core tests and
their `_thr.t` wrappers. The result must behave consistently on the JVM and
interpreter backends, preserve runtime ownership across ithread snapshots, and
retain the existing fast paths for patterns that do not need advanced features.

Phase 36 is intentionally independent from the remaining threads delivery work.
It should be developed in a separate checkout and integrated through focused
regex PRs. The concurrency project consumes its results through unchanged
thread wrappers and protects the Phase 44 release matrix.

## Completed Foundation

- Lexical `use/no re 'debug'` and `debugcolor` state is runtime-owned and works
  across thread snapshots.
- Recursive definitions used by `reg_email` compile on the recursive backend.
- DATA handles retain the source position and inheritance behavior needed by
  direct and threaded regex fixtures.
- Quoted `(?{` text inside `\Q...\E` is not parsed as executable code.
- Literal regex expressions assembled from strings, concatenation, and
  `quotemeta` are validated when the surrounding CV is compiled.
- `(*FAIL)` and `(*F)` have real always-failing zero-width behavior.
- Runtime-owned Unicode property caches and per-runtime-family property
  coordination are implemented.
- Phase 44 established timeout-free thread-wrapper execution and the supported
  regex anchors, including lexical debugging 6/6, user-property race 3/3, and
  `pat_psycho_thr.t` 17/17.

## Remaining Workstreams

### A. Executable regex callbacks

Implement match-time `(?{ BLOCK })`, callback conditions `(?(?{ BLOCK })yes|no)`,
optimistic evaluation `(*{ BLOCK })`, and dynamic patterns `(??{ EXPR })`.
Callbacks must run while the matcher owns its provisional captures and
backtracking stack. Post-match callbacks and construction-time execution are not
acceptable approximations.

The architecture, semantic matrix, Joni callout proposal, and callback-specific
risks live in `dev/design/executable-regex-callbacks.md`. Revalidate that
document's historical prerequisites and library-version assumptions before
implementation.

### B. Conditionals and control verbs

Complete non-callback conditionals and the ACCEPT, PRUNE, SKIP, THEN, and COMMIT
families. Define their capture, `pos`, alternation, and backtracking effects with
standard-Perl differential tests. Engine rewrites are allowed only when they
preserve those effects; otherwise route the pattern to a capable backend.

### C. Lookbehind, recursion, and nested programs

Close remaining variable-length lookbehind, recursive-call, nested-regex, and
capture-numbering gaps. Declarative recursion and executable dynamic patterns
must share consistent recursion limits and timeout behavior without relying on
unbounded Java stack recursion.

### D. Unicode properties

Finish direct Unicode property semantics before attributing wrapper failures to
threads. Cover built-in and user-defined properties, cache identity, warnings,
exceptions, recursion, concurrent unrelated names, and same-name coordination.
Preserve runtime-local results and snapshot policy.

### E. `qr//`, diagnostics, and match state

Complete regex object interpolation/stringification, `/g`, `/c`, `/o`,
substitution state, warning text, source locations, compile errors, byte/Unicode
targets, and nested match-state restoration. Internal callback identifiers must
never leak through stringification or diagnostics.

### F. Direct/thread parity and policy removal

After a direct test passes, run its unchanged thread wrapper on both backends.
Remove capability policies, CPAN patches, or skips only when the unchanged
source-first gate passes. A wrapper may configure resources and timeouts, but it
must not change expected regex behavior.

## Implementation Stages

### Stage 36.0 — Refresh the differential baseline

1. Record same-commit direct and thread-wrapper counts with the standard runner.
2. Separate direct language gaps from clone/runtime-ownership gaps.
3. Add standard-Perl-valid focused tests for every proposed semantic change.
4. Capture backend fallback and timeout behavior for each target.

**Exit criteria:** Every target failure is classified by feature and backend;
the baseline has no orphaned JVMs or unexplained timeout-only zero-TAP results.

### Stage 36.1 — Validate the callback engine seam

1. Refresh the Joni callout spike against the currently shipped matcher layer.
2. Prove a runtime-neutral callout can observe provisional captures, repeat after
   backtracking, and receive an exact unwind notification.
3. Decide whether to publish a namespaced fork or propose the generic callout API
   upstream.

**Exit criteria:** The spike demonstrates forward execution and backtracking
unwind without PerlOnJava dependencies inside the regex engine.

### Stage 36.2 — Structured frontend and callback templates

1. Preserve callback Perl ASTs instead of flattening them into marker strings.
2. Compile callback bodies as lexical `RuntimeCode` values on both backends.
3. Build per-regex callback tables with collision-proof internal skeletons.
4. Keep unsupported execution fatal until the matcher bridge is present.

**Exit criteria:** JVM and interpreter construct equivalent closure-bearing
templates, with correct lexical identity and snapshot ownership.

### Stage 36.3 — Plain callbacks and provisional match state

Implement `(?{ BLOCK })`, `$^R`, provisional numbered/named captures, `$^N`,
`@-`, `@+`, `$_`, and `pos`. Add an active match-state stack so callbacks may
run nested regexes without destroying the outer provisional state.

**Exit criteria:** The focused plain-callback matrix and relevant unchanged
Type::Tiny tests pass on system Perl, JVM, and interpreter.

### Stage 36.4 — Backtracking, dynamic scope, and callback conditions

Add matcher-owned dynamic-local checkpoints and exact-once unwind for success,
failure, alternatives, quantifiers, lookarounds, exceptions, interruption, and
timeout. Implement callback conditions only after this unwind model is proven.

**Exit criteria:** Applicable `rxcode.t`, `reg_eval_scope.t`, and
Regexp::Common callback-condition sections match standard Perl.

### Stage 36.5 — Dynamic patterns and recursive execution

Implement `(??{ EXPR })` as a nested matcher program whose alternatives
participate in outer backtracking. Specify returned string versus `qr//` values,
capture numbering, modifier inheritance, caching, recursion limits, and `/o`.

**Exit criteria:** The focused dynamic-pattern matrix, Object::InsideOut's
recursive pattern, and applicable `reg_eval.t`/`rxcode.t` sections pass.

### Stage 36.6 — Remaining declarative parity

Complete control verbs, conditionals, lookbehind, Unicode properties, regex
objects, state, and diagnostics. Prefer isolated feature slices with direct
oracles over broad changes to `RegexPreprocessor`.

**Exit criteria:** Direct target files complete their expected plans on both
backends, with no regression in ordinary Java-regex or declarative Joni paths.

### Stage 36.7 — Integration and release

1. Run all applicable direct and `_thr.t` companions.
2. Run `make` and the Phase 44 thread release matrix.
3. Run unchanged CPAN suites whose policies are being removed.
4. Update the feature matrix, changelog, and regex implementation documents.
5. Require green Ubuntu and Windows CI before merging each release slice.

**Exit criteria:** Target suites and wrappers have captured passing evidence;
removed policies are justified by unchanged-source results; Phase 44 anchors
remain green.

## Test Matrix

### Focused core targets

- `perl5_t/t/re/pat_re_eval.t`
- `perl5_t/t/re/rxcode.t`
- `perl5_t/t/re/reg_eval.t`
- `perl5_t/t/re/reg_eval_scope.t`
- `perl5_t/t/re/pat.t`
- `perl5_t/t/re/pat_advanced.t`
- `perl5_t/t/re/regexp_qr_embed.t`
- `perl5_t/t/re/regexp_unicode_prop.t`
- `perl5_t/t/re/speed.t`
- Every applicable `_thr.t` companion

### CPAN targets

- Type::Tiny callback tests without callback capability patches
- Regexp::Common callback and dynamic-pattern tests
- Object::InsideOut recursive-pattern tests
- Any distribution whose policy is removed by a Phase 36 slice

### Preservation gates

- Full `make`
- Phase 44 core thread-wrapper matrix without timeout
- Test2 default and opt-in stress
- Storable and Net::SSLeay thread gates
- DBI ownership tests and `timeout 3600 ./jcpan --jobs 8 -t DBIx::Class`
- Ubuntu and Windows CI

All new Perl unit tests must first pass under system Perl. Every `jperl`,
`jcpan`, and `prove` investigation must be hard-timeout-wrapped and captured to
a file. Resource-sensitive tests stay in the runner's exclusive lane, and a
timing delta is a regression only after a serialized same-commit reproduction.

## Parallel-Project Boundaries

- Work in a separate checkout and feature branch; do not share build artifacts
  or active test processes with the concurrency release branch.
- Keep callback-engine dependency changes separate from semantic frontend/runtime
  changes where practical, so the fork surface can be reviewed independently.
- Do not edit thread wrappers to manufacture parity. Fix the direct regex path,
  then use wrappers as snapshot/ownership acceptance tests.
- Rebase before each integration slice and rerun the focused direct/thread pair
  on the rebased commit.
- Update this file after each completed stage with dates, exact test evidence,
  blockers, and the next resumable action. The concurrency plan should contain
  only the cross-project status and Phase 44 preservation contract.

## Risks and Stop Conditions

- Stop if the matcher cannot expose provisional captures and unwind points; do
  not replace callbacks with post-match execution.
- Stop if dynamic patterns are atomic and cannot yield alternatives to outer
  backtracking.
- Keep unsupported syntax fatal if correct semantics are unavailable.
- Preserve separate cached matcher structure and per-value lexical callback
  tables to prevent closure identity leaks.
- Do not allocate callback state on ordinary-pattern fast paths.
- Treat timeout, interruption, nested match, and non-local control flow cleanup
  as correctness requirements, not later optimizations.

## Progress Tracking

### Current Status: Stage 36.0 ready

### Completed stages

- [ ] Stage 36.0: Refresh differential baseline
- [ ] Stage 36.1: Validate callback engine seam
- [ ] Stage 36.2: Structured frontend and callback templates
- [ ] Stage 36.3: Plain callbacks and provisional match state
- [ ] Stage 36.4: Backtracking, dynamic scope, and conditions
- [ ] Stage 36.5: Dynamic patterns and recursive execution
- [ ] Stage 36.6: Remaining declarative parity
- [ ] Stage 36.7: Integration and release

### Next steps

1. Refresh direct and wrapper counts on the Phase 36 checkout.
2. Create and validate the callback semantic matrix with system Perl.
3. Revalidate the Joni callout spike against the current matcher abstraction.
4. Record the fork/upstream decision before starting structured frontend work.

### Open blockers

- The current Java/Joni matcher paths do not expose Perl callouts during
  backtracking.
- Several callback-localization and dynamic-pattern capture rules still require
  standard-Perl differential evidence.
- The detailed callback design contains historical PR and library assumptions
  that must be refreshed during Stage 36.0.

## Related Documents and Skills

- `dev/design/executable-regex-callbacks.md` — detailed callback architecture
- `dev/design/concurrency.md` — parent threads plan and Phase 44 gates
- `dev/design/regex_jruby_joni.md` — Joni integration notes
- `dev/design/regex_parser_integration.md` — parser/AST strategy
- `dev/design/regex_preprocessing_fixes.md` — preprocessing gaps and baselines
- `dev/design/regex_alternatives.md` — backend alternatives
- `dev/implementation/regex.md` — earlier matcher architecture
- `.agents/skills/debug-perlonjava/SKILL.md` — differential debugging workflow
