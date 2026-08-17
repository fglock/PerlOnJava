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

### Current Status: Phases 0, 2, and 4 complete; Phases 1 and 3 corpus gates active

The combined integration stack now includes immutable Joni offset-map reuse,
regex-set property preservation, bounded catastrophic-backtracking safeguards,
user-property diagnostics and provenance, and focused built-in Unicode alias
normalization. The focused property gates pass 39/39, 12/12, 8/8, and 16/16;
unchanged `regexp_unicode_prop.t` passes 1,110/1,110 on JVM and interpreter.
Draft PR #1016 preserves each validated prerequisite head and passed a
warning-free full `make` on its corrected integration history.

The lossless generated `TestProp.pl` fixture now exposes ten previously gated
`uniprops*.t` files. The exact-history JVM run executes all 290,912 planned
assertions with zero timeouts or incomplete files, at 175,768 passing and
115,144 failing. PR 958 recorded zero plan for all ten files, so this is new
coverage rather than a regression, but it reopens Phase 3's full-corpus exit
gate. The interpreter produces the exact same 175,768/290,912 split, with
identical per-file plans and no timeout or incomplete file. Semantic-key
validation found that all 123,411 generated records
in chunks 05–10 retain literal UTF-8 boundary markers in both subjects and
patterns. Their 122,620 apparent passing assertions are therefore vacuous,
not evidence of boundary or folding parity. Chunks 01–04 contain the genuine
property and hand-written boundary evidence. An exact upstream-stage reducer
shows that lexical `use bytes` fails to replace the upgraded marker regex in a
byte subject (system Perl 4/4, JVM/interpreter 2/4); that byte-mode substitution
gap must be fixed before chunks 05–10 can become an authoritative boundary gate.

Explicit `Is_*` property/value assignments now normalize before built-in
routing, including Perl's colon delimiter. The focused 12-assertion oracle
passes on system Perl, JVM, and interpreter. The generated corpus rises by
exactly 44,944 assertions to 220,712/290,912 on both execution backends, with
identical per-file counts and zero errors, timeouts, or incomplete files.

Joni now accepts Perl's top-level, scoped, combined, and negative inline `p`
syntax as matcher-neutral policy. PerlOnJava publishes that policy while
ordinary and substitution callbacks execute, without misclassifying escaped or
character-class text. The focused 15-assertion oracle passes on system Perl,
JVM, and interpreter, and unchanged `reg_pmod.t` reaches 88/88 on both
execution backends. Regex source scanning also consumes each `\c` operand
before interpolation, so `\c@` cannot be mistaken for `@-`; the focused
4-assertion oracle passes on all three runtimes and unchanged `subst.t` reaches
250/281 on JVM and interpreter.

The matcher adapter now carries Joni's search start and Perl `\G` position as
independent cursors, including Unicode offset conversion. The focused
12-assertion oracle passes on system Perl, JVM, and interpreter; unchanged
`subst.t` reaches 275/281 on both execution backends with tests 165-188
restored. The temporary Java backend retains its start-at-`pos` approximation.

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

The last completed forced-backend differential's forced-Java/JVM leg covers all
80 files at
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
  - [x] Separated Joni's search-start and `\G` cursors for ordinary matching
    and substitution, including Unicode subjects and code replacements. The
    focused oracle passes 12/12 and unchanged `subst.t` passes 275/281 on JVM
    and interpreter.
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
- [ ] Phase 3: Unicode and pattern syntax completion (focused gates complete;
  generated full-corpus remediation active)
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
  - [x] Matched user-property definition validation, deterministic recursion
    chains, callback-death wrapping, and direct package-name policy.
    The focused oracle passes 12/12 on system Perl, JVM, and interpreter;
    unchanged upstream coverage gains two assertions.
  - [x] Preserved deferred user-property package provenance through implicit
    Unicode-flag copies and later literal reuse. The focused oracle passes 8/8
    on system Perl, JVM, and interpreter; `regexp_unicode_prop.t` gains nine
    assertions to 1,065/1,110 on both execution backends.
  - [x] Accepted inline `p` directly in Joni while retaining match-variable
    policy in PerlOnJava, including provisional callback state. The focused
    oracle passes 15/15 and unchanged `reg_pmod.t` passes 88/88 on JVM and
    interpreter.
  - [x] Preserved `\c` control operands through regex source interpolation.
    The focused oracle passes 4/4 and unchanged `subst.t` gains test 154 on
    both execution backends.
  - [x] Completed the built-in Unicode aliases exercised by
    `regexp_unicode_prop.t` while preserving deferred user-property precedence.
    The focused alias oracle passes 16/16 on system Perl, JVM, and interpreter;
    unchanged `regexp_unicode_prop.t` passes 1,110/1,110 on both execution
    backends.
  - [x] Added a lossless, idempotent importer for Perl's generated TestProp
    corpus. The focused importer test passes 66/66, two real generations are
    byte-identical, system Perl executes 503,197 TAP, and JVM/interpreter both
    execute 290,912 TAP with exact semantic parity and no timeout.
  - [x] Classified all 115,144 failures newly exposed by the lossless generated
    `uniprops*.t` corpus, including the cross-cutting invalid boundary-harness
    evidence in chunks 05–10.
  - [x] Normalized explicit `Is_*` property/value assignments and the colon
    delimiter (PR #1019), gaining exactly 44,944 generated assertions on both
    execution backends without changing any plan.
  - [x] Rejected 40 invalid Perl inline option/group-name forms in forked Joni
    with exact JVM/interpreter `reg_mesg.t` parity, reducing residual Joni-only
    acceptance differences from 198 to 158 (`028602adc`).
  - [ ] Fix byte-mode substitution of upgraded marker regexes so chunks 05–10
    exercise real boundary subjects, then close the property and boundary
    failures with exact JVM/interpreter plan and semantic parity before marking
    Phase 3 complete.
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
  - [x] Decoded byte-backed eval source according to lexical `use utf8`,
    including pragmas activated inside the source, while preserving `no utf8`
    byte semantics and fatal malformed-UTF-8 diagnostics. The focused oracle
    passes 7/7 on system Perl, JVM, interpreter, and the direct JVM eval
    compiler.
  - [x] Kept Joni syntax/value exceptions fatal for ordinary user-source
    compilation while preserving executable-source validation deferral. The
    focused oracle passes 7/7 and unchanged forced-Joni `reg_mesg.t` gains 259
    raw passing assertions; 197 parser-acceptance differences remain classified.
- [ ] Phase 5: Remove the Java matching backend
  - [x] Retired the unreachable top-level `(*PRUNE)` text rewrite after native
    Joni control-verb gates passed under default and forced-Java policy
    (`5760874e4`; 316 preprocessor lines removed).
  - [x] Removed the disabled invalid-brace pass and its exclusive helpers
    (`4be6a48e3`; 208 preprocessor lines removed), retaining active Perl/Joni
    quantifier diagnostics as explicit focused hard/TODO gates.
- [ ] Phase 6: Integration and release

### Next Steps

1. Publish the integrated inline-option parser slice after combined-stack
   focused gates and warning-free `make`, then integrate the validated PRUNE
   and dead invalid-brace preprocessor retirement commits `5760874e4` and
   `4be6a48e3` as the next stacked slice.
2. Fix lexical `use bytes` substitution when an upgraded marker regex matches a
   byte subject, without patching generated fixtures. Regenerate the lossless
   corpus and prove that chunks 05–10 no longer match literal boundary markers
   before treating any boundary or folding result as evidence. Retain the now-
   completed eval byte-source UTF-8 oracle as an independent runtime regression
   gate; it is not the generated-corpus marker fix.
3. Implement the remaining property clusters in measured order: Block,
   Script/Script_Extensions, Numeric_Value, Joining_Group, General_Category,
   break-property values, and Age/In/Present_In. Preserve pinned Perl 5.44
   acceptance and rejection semantics rather than inheriting host ICU breadth.
   Close the independently reduced Joni syntax gaps for Python-style named
   groups, alpha assertion aliases, underscored numeric escapes, and braced
   octal parsing with focused standard-Perl gates.
4. Rerun the forced-Joni 80-file corpus on JVM and interpreter from the combined
   head. Save complete JSON and logs, publish the missing differential report,
   and compare every file with both the Phase 0 result and PR 958 under the
   no-regression gate.
5. Audit every `RegexPreprocessor` rule against the final ownership boundary.
   Move matcher semantics into Joni, retain only source-policy scanning, delete
   Java-only rewrites and compiled-pattern variants, and remove the temporary
   Java backend selector after the performance gate passes.
6. Reconcile `docs/reference/feature-matrix.md` with the final corpus: replace
   stale Unicode limitations, add any still-missing regex features, and link
   each limitation to a reducer or explicit optimizer/debug exclusion.
7. Run unchanged CPAN consumers, the direct/thread release matrix, packaging
   and license checks, and warning-free `make`; then rebase each focused PR and
   require green Ubuntu and Windows CI.

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
