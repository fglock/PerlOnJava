# Full Perl Regex Semantic Parity

## Goal

Complete Perl 5.44 regular-expression semantics on both PerlOnJava execution
backends and converge on the vendored, namespaced Joni engine for all matching.
Java `Pattern` remains available only as a temporary differential backend while
the migration is being proved.

The acceptance target includes matching, captures, match state, callbacks,
dynamic patterns, errors, warning categories and locations, byte and Unicode
behavior, direct/thread parity, and unchanged-source CPAN consumers. Assertions
that inspect Perl's internal optimizer program or debug transcript are reported
separately from language-semantic failures.

The historical comparison point is:

```text
../PerlOnJava/logs/test_20260815_080000_958.log
```

The last completed 80-file differential recorded 51,002/94,829 passing
assertions, 729 more passing assertions and 58 more planned assertions than that
baseline, with no per-file pass-count regressions.

## Architecture

### Final engine boundary

- The vendored Joni fork is the sole production matcher.
- PerlOnJava owns Perl source policy: interpolation provenance, `use re 'eval'`,
  lexical warning and modifier state, executable callback closures, user-defined
  Unicode properties, source locations, and Perl diagnostics.
- Joni owns regex parsing and matcher semantics: captures, conditions,
  recursion, lookarounds, case folding, control verbs, backtracking-visible
  state, and byte/Unicode matching.
- The fork remains runtime-neutral. It receives internal callback IDs and a
  matcher-local handler API, never Perl source or PerlOnJava runtime objects.
- Upstream packages and notices stay unchanged in `third_party/joni`; standalone
  packaging relocates Joni and JCodings into `org.perlonjava.internal`.

### Migration controls

A temporary developer-only backend selector supports separate Java and Joni
corpus runs. It must never run both matchers for one operation because callbacks,
tied variables, `pos()`, and substitutions may have observable side effects.
Joni is the default matcher; explicit Java mode remains only for differential
measurement. The selector and Java matching fields are removed at the end of the
migration.

### Preprocessing boundary

Every current `RegexPreprocessor` rule is classified before it moves:

1. Perl source policy remains outside Joni.
2. Backend-neutral spelling normalization moves into a small frontend scanner.
3. Matcher semantics move into Joni's parser, compiler, and matcher.
4. Java-only syntax rewrites and stack workarounds are deleted with the Java
   matching backend.

Text rewriting must not emulate behavior that depends on backtracking, capture
close order, matcher regions, or encoding.

## Implementation Phases

### Phase 0 — Reproducible differential baseline

1. Run all 80 `perl5_t/t/re` files on JVM and interpreter backends from the same
   clean commit after confirming that no unrelated PerlOnJava builds are active.
2. Save complete output and JSON outside the source tree, compare every file
   against the PR 958 baseline, and reject any unexplained zero-TAP or timeout
   result.
3. Classify every failure as matcher semantics, source policy, diagnostics,
   shared non-regex behavior, or optimizer/debug transcript.
4. Give `pat_psycho*` and `speed*` a configurable two-worker CPU-heavy lane.
   Keep `pat*`, `pat_advanced*`, and memory-sensitive fixtures in a one-worker
   exclusive lane. Every child retains its own hard timeout and process group.

Exit criteria: the baseline is repeatable, direct/thread and JVM/interpreter
differences are visible, and the report identifies the next semantic slice.

### Phase 1 — Joni ordinary-pattern parity

1. Add the temporary forced-backend selector and route ordinary patterns through
   Joni by default in focused tests.
2. Compile separate byte and Unicode variants using the source and target scalar
   metadata; preserve raw byte offsets and convert UTF-8 offsets only at the Perl
   match-variable boundary.
3. Complete matcher regions, anchoring and transparent bounds, zero-width search
   progression, `\G`, `/g`, `/c`, `/o`, captures, duplicate names, branch reset,
   regex-object reuse, substitution, and nested match-state restoration.
4. Run the same corpus once with each forced backend. Do not hide unsupported
   Joni behavior by falling back within a match.

Exit criteria: every assertion previously passing on Java also passes on Joni,
with no direct/thread or JVM/interpreter regression.

### Phase 2 — Conditions and backtracking-visible state

1. Implement numbered and named capture conditions, assertion conditions,
   recursion conditions, `(DEFINE)`, and executable callback conditions in Joni.
2. Complete `(*MARK:name)`, named `(*SKIP:name)`, `$REGMARK`, `$REGERROR`, cut
   boundaries, recursion limits, and interactions with lookarounds, subpattern
   calls, dynamic programs, and callback unwind.
3. Preserve exact capture-close order, provisional match variables, dynamic
   locals, and callback side effects along the selected matcher path.

Exit criteria: focused standard-Perl oracles and applicable `pat_advanced.t`,
`rxcode.t`, `reg_eval_scope.t`, and callback sections agree on both backends.

### Phase 3 — Unicode and pattern syntax completion

1. Implement Perl property aliases, versioned `Age` forms, script extensions,
   `\N{name}`, extended classes, user-defined property recursion and errors, and
   byte-versus-Unicode warning behavior.
2. Complete remaining case-folding, grapheme, lookbehind, and invalid-pattern
   diagnostics in the Joni frontend and engine.
3. Derive property names and aliases from the bundled Perl 5.44 Unicode data so
   behavior does not depend on the host JDK Unicode version.

Exit criteria: semantic assertions in `regexp_unicode_prop.t`, `pat.t`, and
`pat_advanced.t` complete without `JPERL_UNIMPLEMENTED=warn` masking supported
syntax.

### Phase 4 — Runtime source and diagnostics

1. Preserve runtime-eval source names, package and lexical context, warning
   masks, line numbers, syntax errors, and Unicode/byte source identity.
2. Complete recursive and nested `(??{...})`, mixed literal/runtime executable
   source, tied and localized interpolation, regex-object stringification, and
   `/g`, `/c`, `/o` state across callback and exception boundaries.
3. Close the semantic assertions in `pat_re_eval.t`. Track shared non-regex
   `eval` failures separately, but fix them when they prevent regex source from
   executing with standard Perl behavior.

Exit criteria: all 555 `pat_re_eval.t` assertions execute and every semantic
assertion passes on both execution backends.

### Phase 5 — Remove the Java matching backend

1. Move every remaining matcher-semantic preprocessor rule into Joni.
2. Delete Java compiled-pattern variants, feature routing, Java-only rewrites,
   and the temporary backend selector.
3. Retain only the small Perl source-policy/frontend layer described above.
4. Remove stale parser and preprocessing plans or rewrite them to describe the
   final ownership boundary.

Exit criteria: Joni is the only production matcher and ordinary patterns do not
allocate callback state or callback frames.

### Phase 6 — Integration and release

1. Retire regex-test accommodations incrementally. Whenever a PerlOnJava fix
   makes a `dev/import-perl5/patches/pat.t.patch` hunk unnecessary, remove that
   hunk and rerun `perl dev/import-perl5/sync.pl --only perl5/t/re/pat.t` to
   restore the unchanged upstream assertions. Do not hand-edit the imported
   test to approximate upstream content.
2. Delete the `pat.t.patch` configuration entry and patch file once its final
   hunk is obsolete. Rerun the targeted sync twice and require the second run to
   produce no diff, proving that the checked-in test is the unpatched Perl 5.44
   source and the import is idempotent.
3. Run the complete direct and `_thr.t` regex matrix on JVM and interpreter
   backends and compare it file-by-file with both the Phase 0 result and PR 958.
4. Run unchanged Type::Tiny, Regexp::Common, Object::InsideOut, and every CPAN
   suite whose regex capability policy is removed.
5. Run warning-free `make`, Joni upstream tests, packaging and license checks,
   and the thread release matrix.
6. Rebase each focused delivery slice onto current master. Require green Ubuntu
   and Windows CI before merging and beginning the next slice.

Exit criteria: all semantic gates pass, no previously passing file regresses,
the regex corpus is reproduced from `dev/import-perl5/sync.pl` without a regex
test patch, and documentation reports optimizer/debug-only exclusions
explicitly.

### Upstream patch retirement queue

`pat.t.patch` is reduced in place as these gates close; the corresponding
upstream hunk is restored by the targeted importer before its result is counted:

| Upstream section | Gate before restoring the hunk |
|---|---|
| `(*ACCEPT)` capture-close cases | Exact success and capture values pass without converting fatal setup failures to warnings |
| `pos` inside `(?{...})` | Callback-visible `pos`, captures, and unwind behavior pass on JVM and interpreter |
| reference stringification diagnostics | Unqualified `diag` resolves in the original lexical/package context |
| `${^LAST_SUCCESSFUL_PATTERN}` | Dynamic empty-pattern reuse, copying, matching, and substitution pass |
| `(??{...})` code blocks interpolated from arrays | All original runtime-eval and side-effect assertions pass without an enclosing compatibility `eval` |

The queue is complete only when `config.yaml` no longer names `pat.t.patch`, the
patch file is gone, and two consecutive targeted syncs leave a clean tree.

## Test Contract

- Validate every new or changed Perl unit test with system `perl` or `prove`
  before running it with PerlOnJava.
- Run JVM and interpreter tests under `timeout`, capture complete output in
  files, and inspect the saved files rather than truncated terminal output.
- Use `perl dev/tools/perl_test_runner.pl`; the runner requires process `fork`
  and must not run under `jperl`.
- Run direct tests before thread wrappers. A wrapper must preserve the direct
  result and may change only resources and ownership context.
- Unsupported syntax remains fatal until its complete semantic gate passes.
- Do not alter existing Perl core tests to fit PerlOnJava behavior.
- Treat the import manifest and its patch files as temporary compatibility debt:
  remove each regex-test patch hunk as soon as its guarded behavior passes, then
  use a targeted `sync.pl` run to recover the exact upstream test source.
- `make` must pass without warning output before every push.

## Performance Gate

Before removing Java matching, run five warmed ordinary-pattern measurements on
each backend. Joni's median runtime must be within 25% of the Java baseline,
must introduce no new timeout, and must not materially increase steady-state
allocation. A failure blocks backend removal, not semantic fixes.

## Public Interfaces

No permanent public regex API or command-line option is added. The temporary
developer backend selector is removed in Phase 5. Existing Perl syntax,
variables, warning categories, and regex object behavior are the public
compatibility contract.

## Progress Tracking

### Current Status: Joni default; Phase 1 remediation and Phase 3 Unicode active

Invalid Unicode properties now remain fatal under
`JPERL_UNIMPLEMENTED=warn`, and only exact `Is`/`In` final-name prefixes enter
Perl's user-property callback path. A 39-assertion standard-Perl oracle passes
on JVM and interpreter; the unchanged `regexp_unicode_prop.t` runner improves
from 1,039/1,110 to 1,054/1,110, leaving built-in aliases and six
user-property-definition diagnostic assertions as separate work.

Executable callback source and literal trailing `/x` comments survive canonical
regex-object stringification on both execution backends. Recursive Joni call
frames now preserve the Perl-visible caller capture view for optimistic
callbacks and committed matches, including `$1`, `$^N`, and `$+`. Tied scalar
values returned by dynamic callbacks are materialized before callee regex state
teardown. Joni invalid-backreference errors use Perl's nonexistent-group
diagnostic. Reopened repeated groups expose their preceding closed capture to
dynamic callbacks without altering matching registers. Nested dynamic matcher
completion preserves the last successful block result in `$^R`, including a
runtime `qr` returned by an outer `(??{...})`. Executable-looking groups inside
double-quote case modifiers are deferred until after interpolation and obey
runtime `re 'eval'` permission. Foreach aliases refresh the active lexical-cell
registry on both execution backends, so runtime-compiled callbacks capture each
iteration's cell and retain it after scope exit. Executable runtime pattern
compilation uses independent `(eval N)` source identities for diagnostics.
Runtime source now inherits exact lexical warning masks and reports Unicode
parser names and undefined match operands at the original call site. Failed
callback branches preserve the preceding successful `$&`, `$1`, and related
match state. Dynamic regex-state restoration releases discarded temporary
callback patterns, so captured values stay alive through the enclosing scope
and are destroyed when that scope exits. Recursive callback unwind preserves
the failed nested `$^N` and `$+` state without clobbering numbered captures or
one-level failed callback state. The focused `pat_re_eval.t` gate executes all
555 assertions with 550 semantic assertions passing on both execution backends;
the remaining five inspect Perl's optimizer/debug transcript.

The forced-backend differential is running in the parallel PerlOnJava3
checkout. Its completed forced-Java/JVM leg covers all 80 files at
49,923/94,823 versus PR 958's 50,273/94,771. The apparent aggregate regression
is dominated by `pat{,_thr}.t` aborting after test 239 on a runtime eval-group
policy error; that source-policy slice is assigned independently. The completed
forced-Joni/JVM leg is 32,479/77,612, with ten bounded timeout files. Its
largest completed losses against forced Java are `reg_posixcc.t` (-508),
`reg_mesg.t` (-300), both `pat_advanced` variants (-240 each),
`alpha_assertions.t` (-89), and `regex_sets.t` (-84). The completed
forced-Java/interpreter leg covers all 80 files at 50,021/94,823 with no runner
timeouts, 98 more passing assertions than forced-Java/JVM, and an identical
plan. The completed forced-Joni/interpreter leg is 32,483/77,612 with the same
ten timeouts and planned count as Joni/JVM. The final same-binary report is
complete in `dev/design/phase36-regex-differential-20260817.md`; Phase 1's exit
criterion is not met because Joni loses Java-passing assertions and introduces
matcher-specific timeouts on both execution backends.

### Completed Phases

- [x] Phase 0: Reproducible differential baseline (2026-08-17)
  - Captured the 80-file regex differential with complete output and JSON.
  - Compared every file with the PR 958 baseline at
    `../PerlOnJava/logs/test_20260815_080000_958.log`.
  - Recorded 51,002/94,829 passing assertions, a net gain of 729 passing and
    58 planned assertions, with no per-file pass-count regressions.
  - Added separate parallel handling for CPU-heavy `pat_psycho*` and `speed*`
    tests while retaining per-child timeouts.
- [ ] Phase 1: Joni ordinary-pattern parity (implementation substantially
  complete; forced Java/Joni corpus comparison remains)
  - [x] Added the temporary backend selector and made Joni the default.
  - [x] Routed ordinary matching, substitution, and split through the selected
    backend without per-operation fallback.
  - [x] Completed the forced-Java/JVM 80-file leg and identified the
    `pat{,_thr}.t` test-239 source-policy abort as the leading regression.
  - [x] Completed the four-leg forced-backend matrix and published its
    classification. The exit criterion is explicitly not met; timeout and
    semantic remediation remain Phase 1 work.
  - [x] Reduced the forced-Joni zero-pass surface to seven shared causes:
    catastrophic backtracking, quadratic matcher reconstruction, absent
    generated Unicode fixtures, regex-set preprocessing, unsupported compiler
    introspection, regexp-object propagation, and three assertion-level
    environment/runtime failures.
  - [x] Moved immutable Joni UTF-8 input and offset maps out of the scalar
    `/g` hot loop. The focused million-match oracle completes in 1.07 seconds
    on JVM and 1.41 seconds on interpreter (PR #1008), with exact map and
    supplementary-character capture-boundary coverage.
- [x] Phase 2: Conditions and backtracking-visible state (2026-08-17)
  - [x] Implemented executable callback conditions, control verbs including
    `(*MARK:NAME)`, and callback-visible recursive capture state in Joni.
  - [x] Closed runtime callback capture ownership at final scope teardown.
  - [x] Closed failed-path `$^N` and `$+` restoration through recursive
    callback unwind.
  - [x] Added direct active-localization lookup for runtime control variables;
    dynamic `PRUNE`, `SKIP`, and `COMMIT` update package `$REGERROR` without
    mutating non-localized `$REGERROR`/`$REGMARK` variables on either backend.
  - [x] Propagated `PRUNE`, `SKIP`, `COMMIT`, and `THEN` cuts and search-control
    requests from nested `(??{...})` matcher programs. A 9-assertion
    standard-Perl oracle passes on JVM and interpreter, and `pat_advanced.t`
    test 891 now observes 3 callback executions instead of 9.
  - [x] Refreshed the package alias stored for a reused `our` symbol when a
    later declaration changes package. The focused package oracle passes on
    system Perl, JVM, and interpreter, and `pat_advanced.t` tests 922-933 pass
    on both execution backends without a regex-adapter workaround.
  - [x] Exposed the actual match subject as callback `$_`, the provisional
    callout offset through `pos`, and the in-progress match span through `$&`
    plus the pre-match and post-match variables. Callback-bearing substitution
    recompilation now preserves trusted callout markers. The 24-assertion
    upstream `pos inside (?{})` block
    passes on system Perl, JVM, and interpreter; `subst_amp.t` remains 13/13
    on both execution backends.
  - [x] Removed the obsolete nested `(*ACCEPT)` and callback-`pos` workarounds
    from `pat.t.patch` and resynchronized those original Perl 5.44 assertions.
  - [x] Verified reference stringification (5/5) and
    `${^LAST_SUCCESSFUL_PATTERN}` dynamic scope and reuse (25/25) on system
    Perl, JVM, and interpreter; removed both obsolete `pat.t.patch` wrappers
    and resynchronized the original assertions.
  - [x] Preserved callback-bearing compiled regexes through one- and multi-item
    array interpolation, including Perl's deferred dot-overload composition
    with surrounding dynamic callbacks. The focused oracle passes 28/28 on
    system Perl, JVM, and interpreter.
  - [x] Removed the final `pat.t.patch` hunk, deleted the patch and its importer
    configuration, and resynchronized the unmodified Perl 5.44 `pat.t`.
- [ ] Phase 3: Unicode and pattern syntax completion (in progress)
  - [x] Added Perl escape syntax, Unicode-property resolution, scoped ASCII
    folds, possessive intervals, and bounded lookbehind support to Joni.
  - [x] Converted public regex `pos` values between Perl logical-character
    offsets and Java matcher offsets for scalar `/g`, `\G`, fast scanners, and
    substitution callbacks. The 11-assertion supplementary-character oracle
    passes on system Perl, JVM, and interpreter.
  - [x] Restricted user-defined property dispatch to Perl's exact `Is`/`In`
    naming convention and made unknown-property diagnostics fatal even in
    compatibility warning mode. The focused oracle passes 39/39 on system
    Perl, JVM, and interpreter; `regexp_unicode_prop.t` gains 15 assertions.
  - [ ] Complete the remaining Unicode aliases and diagnostic parity inventory.
- [x] Phase 4: Runtime source and diagnostics (2026-08-17; semantic gate
  complete at 550/555)
  - [x] Preserved mixed executable-source provenance, nested dynamic callback
    values, foreach lexical cells, and independent `(eval N)` source names.
  - [x] Restored lexical warning masks, Unicode source diagnostics, undefined
    operand warnings, and prior successful match state across failed callbacks.
  - [x] Released callback captures when temporary match state is discarded and
    the final owning regex scope exits (test 307).
  - [x] Resolved failed-path `$^N`/`$+` tests 85-86 on JVM and interpreter.
  - [x] Classified tests 444-448 as optimizer/debug-transcript exclusions.
- [ ] Phase 5: Remove the Java matching backend
- [ ] Phase 6: Integration and release

### Next Steps

1. Remediate the seven reduced forced-Joni failure causes, starting with the
   catastrophic `regexp*` cluster and the now-profiled `pat_psycho*` matcher
   reconstruction path; keep per-child hard limits throughout.
2. Integrate the stacked Joni offset-map, regex-set property, and CEC timeout
   slices, then rerun the forced-Joni corpus from the new combined head.
3. Capture the clean-branch JVM and interpreter 80-file baselines and compare
   both to PR 958 with the regression exit gate.
4. Integrate the exact invalid-property dispatch/diagnostic slice, then finish
   PerlOnJava4's built-in Unicode-alias slice and the six residual
   user-property-definition diagnostic assertions against Perl 5.44 behavior.
5. Keep the warning-free whole-unit-suite gate green with Joni as the default;
   use explicit Java mode only to classify corpus regressions before Phase 5
   removes the legacy backend and selector.

### Open Questions and Blockers

- Exact optimizer/debug transcript assertions are not semantic release blockers;
  each exclusion still requires an explicit report entry.
- Resource-sensitive baselines must wait for unrelated Java builds to finish.
- The interpreter does not reliably expose the lexical package through
  `InterpreterState.currentPackage` while a regex executes. Localized
  `$REGMARK`/`$REGERROR` slots are therefore enumerated from active dynamic
  `GlobalRuntimeScalar` bindings rather than inferred from the current package
  or scanned from dormant globals.
- Shared parser or `eval` failures are fixed in focused slices when they block a
  regex semantic test, rather than being approximated inside the matcher.
- Starting a forced-Joni global match exactly on a supplementary character
  also requires PR #1008's high-surrogate offset-map correction. The public
  `pos` conversion is independently complete; add that exact-start assertion
  when #1008 integrates.

## Related Documents and Skills

- `docs/design/joni-callout-fork.md`
- `dev/design/executable-regex-callbacks.md`
- `dev/design/regex_parser_integration.md`
- `dev/design/regex_preprocessing_fixes.md`
- `.agents/skills/debug-perlonjava/SKILL.md`
