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
6. Rewrite `dev/implementation/regex.md` to describe the final as-implemented
   matcher architecture and ownership boundaries, and update
   `docs/design/joni-callout-fork.md` to match the shipped fork API, namespace,
   packaging, callback/unwind contract, and Unicode responsibilities. Review
   both documents for a clear reader path, consistent terminology, and removal
   of superseded proposals or predictions. Audit the remaining regex/Joni
   design documents: delete only content that is wholly redundant and retains
   no useful rationale; otherwise replace historical implementation plans with
   concise summaries that preserve decisions and point to the canonical
   implementation and fork documents. Preserve copyright and authorship
   notices in every retained or consolidated third-party description.
7. Rebase each focused delivery slice onto current master. Require green Ubuntu
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

The published integration stack is preserved through draft PR #1039, stacked
on draft PRs #1025–#1026 and review-ready PR #1024. It includes the completed callback/runtime slices,
lossless generated Unicode fixtures, explicit `Is_*` property/value
normalization, fatal Joni syntax diagnostics, native GCB semantics, and the
first 524 lines of retired Java-only preprocessor code. Every published slice
has a warning-free combined `make` checkpoint. PR #1027 adds native sentence
boundaries; PR #1028 adds independently validated alpha assertion aliases and
native word boundaries. PR #1029 integrates corrected global zero-width `/g`
progression and pinned Perl 5.44 Unicode 17.0 Age properties. PR #1030 adds
binary `ASCII_Hex_Digit` values, pinned General_Category sets, and exact native
line boundaries. PR #1031 adds pinned Canonical_Combining_Class sets and valid
empty-property rendering. PR #1032 integrates native numeric escapes through
U+10FFFF; PR #1033 adds pinned Bidi_Class sets; PR #1034 integrates native
vertical-whitespace escapes; PR #1035 adds Decomposition_Type and PR #1036
adds East_Asian_Width, PR #1037 adds Numeric_Value, and PR #1038 adds
Joining_Group, and PR #1039 adds Block. The current WIP integrates
Script/Script_Extensions; independently validated break-property values,
generic and specialized binary-property data, residual enumerated-property
families, and the first preprocessor dead-state deletion are ready for focused
integration.

Lexical `use bytes` now compiles non-ASCII substitution patterns with a
single-byte Joni encoding while preserving upgraded, byte-backed, and compiled
`qr//` source provenance. The focused oracle passes 12/12 on system Perl, JVM,
and interpreter, and the exact upstream marker-stage reducer improves from 2/4
to 4/4 on both execution backends. Generated chunks 05–10 consequently execute
239,843 genuine boundary assertions rather than matching literal UTF-8 marker
text. JVM and interpreter have exact per-file parity at 2,192/239,843 with every
plan complete, exit 0, and no child timeout at the pre-GCB baseline. The runner
classifies zero-pass files as `error`, but their recorded plans, actual counts,
and process exits are complete.

Native Joni GCB assertions now implement GB1–GB13 and GB999, including Indic
conjunct and emoji-ZWJ context, and `\X` consumes repeated GB9c conjuncts. The
focused 29-assertion oracle passes on system Perl, JVM, and interpreter.
Authoritative chunk 05 improves by 6,324 assertions from 2,192/14,953 to
8,516/14,976 identically on JVM and interpreter: its complete GCB/`\X` section
passes, leaving only the 6,460 sentence-boundary assertions in that chunk.

Native Joni sentence assertions now implement SB1–SB11 and SB998 with a
reproducibly generated Perl 5.44 Unicode 17.0 `Sentence_Break` table. The
23-assertion focused oracle passes on system Perl, JVM, and interpreter, direct
Joni coverage exercises the same engine path, and authoritative chunk 05 passes
14,976/14,976 identically on JVM and interpreter. This closes all 6,460
remaining sentence assertions without coupling the Joni fork to ICU or the
PerlOnJava runtime.

The most recent exact property chunks 01–04 remain 98,092/167,501 on both
execution backends. Native Joni word assertions implement WB1–WB16 and WB999
from repository-pinned Perl 5.44 Unicode 17.0 Word_Break and
Extended_Pictographic data. The 33-assertion focused oracle passes on system
Perl, JVM, and interpreter, direct Joni exercises the same path, and generated
chunk 10 passes 19,510/19,510 identically on both execution backends. Combined
with the complete boundary chunk 05 and unchanged chunks 06–09, current
generated evidence was 132,578/407,367 before the current property slice. A resource-contended
current-head refresh did not reproduce a complete exact JVM/interpreter pair,
so it does not replace that accepted baseline.

`Age` now uses exact introduction-version sets and `In`/`Present_In` use
cumulative sets generated from the repository-pinned Perl 5.44 Unicode 17.0
`DAge.txt`; `Unassigned`/`NA`, colon delimiters, wildcard-value spellings, and
Perl loose version aliases are covered without inheriting the host ICU Unicode
version. The focused oracle passes 14/14 on system Perl, JVM, and interpreter.
Stable chunk 01 validation improves from 30,194 to 30,705 passing assertions
identically on JVM and interpreter, with no numbered regression. This raises
current generated evidence by 511 to 133,089/407,367.

`General_Category`/`gc`/`Category` assignments now resolve all atomic and
aggregate values from repository-pinned Perl 5.44 Unicode 17.0 data. Short,
long, `Is_`, colon, wildcard, and loose value aliases are generated
reproducibly without the host ICU category table. The focused oracle passes
18/18 on system Perl, JVM, and interpreter; Age remains 14/14 and invalid
property diagnostics remain 39/39. Chunk 01 improves by another 606 assertions
to 31,311/41,843 identically on both execution backends with no numbered
regression, raising property-plus-completed-sentence/word evidence to
133,695/407,367 before line integration.

Native Joni line assertions now implement Unicode 17 UAX #14 from pinned Perl
5.44 Line_Break, General_Category, East_Asian_Width, and emoji data. The
84-assertion focused oracle passes on both execution backends and chunks 06–09
pass 205,380/205,380 each on JVM and interpreter. Protected sentence and word
chunks remain exact, making the complete generated boundary corpus
239,866/239,866 and current generated evidence 339,075/407,367.

`Canonical_Combining_Class`/`ccc` assignments now resolve every pinned Unicode
17 value and alias, including ordered `Not_Reordered` defaults for unassigned
code points and reserved valid values whose sets are empty. Empty properties
render as valid match-none/match-all classes rather than invalid `[]` syntax.
The focused oracle passes 24/24 on system Perl, JVM, and interpreter. Chunk 01
passes 33,516/41,843 identically on both execution backends: 2,195 CCC
assertions and 10 already-native line preamble assertions improve over the
31,311 baseline with zero numbered regressions. Current generated evidence is
341,280/407,367.

`Bidi_Class`/`bc` assignments now resolve all 23 values from a complete pinned
Unicode 17 partition. Ordered missing defaults, short/long and loose aliases,
directional controls, noncharacters, and unknown-value rejection are covered.
The focused oracle passes 99/99 on system Perl, JVM, and interpreter; the
combined Unicode/property/boundary smoke is 345/345 per backend. Chunk 01 gains
736 assertions to 34,252/41,843 identically on both execution backends with no
numbered regression, raising current generated evidence to 342,016/407,367.

Native Joni `\v` now matches Perl's seven vertical-whitespace code points and
`\V` matches their complement, both directly and inside character classes,
without changing non-Perl Joni syntax behavior. The focused oracle passes
92/92 on system Perl, JVM, and interpreter. Unchanged `reg_posixcc.t` improves
from 2,052/2,560 to 2,560/2,560 on both execution backends with zero numbered
regressions, closing its entire 508-assertion Joni gap.

`Decomposition_Type`/`dt` assignments now resolve all 18 atomic values plus
Perl's composite `Non_Canonical` value from a complete pinned Unicode 17
partition. Short/long and loose aliases, ordered `None` defaults, the exact
case-sensitive `Is` assignment prefix, and invalid-value rejection are covered.
The focused oracle passes 45/45 on system Perl, JVM, and interpreter. Chunk 01
gains 640 assertions to 34,892/41,843 identically on both execution backends
with zero numbered regressions, raising current generated evidence to
342,656/407,367.

`East_Asian_Width`/`ea` assignments now resolve all six values from a complete
pinned Unicode 17 partition, including the ordered CJK `Wide` and general
`Neutral` missing defaults. Short/long and loose aliases are covered, and
surrogate range endpoints render as explicit Joni hex escapes rather than
lossy literal surrogates. The focused oracle passes 31/31 on system Perl, JVM,
and interpreter; protected boundary smoke remains 169/169 per backend. Chunk
01 gains 216 assertions to 35,108/41,843 identically on both execution backends
with zero numbered regressions, raising current generated evidence to
342,872/407,367.

`Numeric_Value`/`nv` assignments now resolve all 144 exact rational values and
the `NaN` complement from pinned Perl 5.44 Unicode 17 data. Integer, decimal,
exponent, reduced-rational, loose, wildcard, and exact case-sensitive `Is`
forms follow Perl's generated keyword aliases and binary-NV canonicalization,
including four-significant-digit decimal spellings without heuristic tolerance.
The focused oracle passes 50/50
on system Perl, JVM, and interpreter; protected boundary smoke remains 169/169
per backend. Chunks 02–03 gain 13,976 assertions with zero numbered regressions
and exact JVM/interpreter success sets, raising current generated evidence to
356,848/407,367.

`Joining_Group`/`jg` assignments now resolve all 106 values from a complete
pinned Unicode 17 partition. Loose aliases, the ordered `No_Joining_Group`
default, the alternate `Hamza_On_Heh_Goal` wildcard name, canonical and
squeezed wildcard values, exact case-sensitive `Is` policy, and wildcard
diagnostics follow Perl 5.44. The focused oracle passes 49/49 on system Perl,
JVM, and interpreter; protected boundary smoke remains 169/169 per backend.
Chunks 01–04 gain 4,290 assertions with no pass-count regression and exact
JVM/interpreter success sets, raising current generated evidence to
361,138/407,367.

`Block`/`blk` assignments and `In...`/single-`Is...` shortcuts now resolve all
347 values, including `No_Block`, from a complete pinned Unicode 17 partition.
Official compact aliases, loose forms, `#...#` wildcards, Script and
General_Category/binary precedence, ordered gaps, noncharacters, and exact
compound `Is` policy follow Perl 5.44. The 36-assertion oracle passes standard
Perl; JVM and interpreter pass all 35 Block-specific assertions while retaining
one pre-existing TODO for unresolved deferred `In...` user-property timing.
The two focused precedence reducers pass 12/12 on all runtimes, protected
boundary smoke remains 169/169 per backend, and chunks 01–04 gain 8,324
assertions with zero numbered regressions and exact backend identity. Current
generated evidence is 369,462/407,367.

`Script`/`sc` and `Script_Extensions`/`scx` assignments now resolve all 176
values from pinned Unicode 17 partitions and Script_Extensions overrides.
Explicit `sc` retains strict Script semantics while Perl's bare Script-value
shortcuts use Script_Extensions; the composite `Katakana_Or_Hiragana`/`Hrkt`
pseudo-value is rejected from bare and exact assignments and excluded from
wildcard unions as required by Perl. Loose aliases, `Qaac`/`Qaai`,
wildcards, exact `Is` assignment policy, precedence over Block shortcuts, and
positive or complemented properties inside ordinary character classes are
covered. The 95-assertion oracle passes system Perl, JVM, and interpreter; the
focused precedence, class-negation, and bare-scx reducers pass 7/7, 8/8, and
10/10 respectively on all three runtimes. Protected boundary smoke remains
169/169 per backend. Chunks 01–04 gain 8,140 assertions with zero numbered
regressions and exact JVM/interpreter counts, raising current generated
evidence to 377,602/407,367.

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

The post-PR-#1028 plus `/g` combined forced-Joni refresh executes all 80 files
at 74,603/331,826 on JVM and 74,607/331,826 on interpreter. Four generated
property chunks time out after producing partial TAP and require the narrow
600-second rerun; chunks 05 and 10 are exact while chunks 06–09 expose only the
assigned line-boundary gap. Six regressions versus the preceding Joni result
reduced to two fatal roots. Binary `ASCII_Hex_Digit=True` routing is now closed:
the focused Perl boolean-value oracle passes 16/16 on system Perl, JVM, and
interpreter, and `pat.t` is restored from its zero-TAP abort to the independently
tracked test-239 runtime-eval gate. Native Joni numeric parsing now treats bare
high octal escapes as UTF-8 code points and accepts underscored braced hex and
octal escapes through U+10FFFF. The focused standard-Perl oracle has 14 ordinary
passes plus four explicitly classified TODOs on both execution backends;
`pat_rt_report{,_thr}` advances from 5 executed assertions to 73/72, and
`pat_advanced.t` reaches its later independent `Titlecase` property blocker.
Strict-regex source policy and Perl code points above U+10FFFF remain explicit
frontend/representation debt, so the forced-Java underscore compatibility pass
is retained for now.

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
  - [x] Added a provenance-aware single-byte Joni pattern/input path for
    non-ASCII substitutions under lexical `use bytes`. Upgraded, byte-backed,
    and compiled byte-backed patterns pass 12/12 on all runtimes, and the
    generated Unicode marker stage passes 4/4 on JVM and interpreter.
  - [x] Closed `/g` same-position retry and capture semantics after a zero-width
    first alternative (`0703725c8`, integrated as `402102446`). The focused
    oracle passes 23/23, the raw omniholder reducer improves from 7/10 to 10/10
    in all six Java/Joni × JVM/interpreter modes, and DBIx::Simple remains 69/69.
  - [x] Reran the combined forced-Joni 80-file corpus on JVM and interpreter,
    published the complete file-by-file comparison, and reduced its six actual
    regressions to two fatal roots with narrow owners and rerun gates.
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
  - [x] Integrated native Python-style named captures and backreferences plus
    removal of their frontend conversion (`afbe2bc34`, integrated as
    `cc489bee8`). The 20-case oracle passes on both execution backends with exact
    malformed/unknown diagnostics.
  - [x] Integrated native braced-octal parsing and missing-close/empty
    diagnostics plus fatal unterminated braced-hex diagnostics (`55433291a`,
    `913e2b583`) with exact JVM/interpreter `reg_mesg.t` parity.
  - [x] Integrated native bare high-octal and underscored braced hex/octal
    parsing through U+10FFFF (`f849c2ef9`, integrated as `eb907a10b`). The
    focused gate has 14 ordinary passes plus four classified TODOs on both
    backends and restores `pat_advanced`/`pat_rt_report` startup.
  - [x] Integrated native Joni alpha assertion aliases `pla`, `plb`, `nla`,
    `nlb`, and `atomic` (`a6255fbff`, integrated as `49d7d9648`). The focused
    25-case oracle passes on both execution backends and the generated alpha
    corpus gains 98 passing assertions per backend with zero regressions.
  - [x] Fixed byte-mode substitution of upgraded marker regexes so chunks 05–10
    exercise real boundary subjects with exact JVM/interpreter plans.
  - [x] Implemented native Joni GCB assertions for GB1–GB13 and GB999 and aligned
    `\X` with repeated GB9c Indic conjunct behavior. The focused oracle passes
    29/29 and generated chunk 05 reaches 8,516/14,976 on both execution backends.
  - [x] Implemented native Joni sentence assertions for SB1–SB11 and SB998 from
    a reproducible Perl 5.44 Unicode 17.0 table. The focused oracle passes 23/23
    and generated chunk 05 passes 14,976/14,976 on both execution backends.
  - [x] Implemented native Joni word assertions for WB1–WB16 and WB999 from
    reproducible Perl 5.44 Unicode 17.0 Word_Break and Extended_Pictographic
    tables. The focused oracle passes 33/33 and generated chunk 10 passes
    19,510/19,510 on both execution backends.
  - [x] Generated exact `Age` and cumulative `In`/`Present_In` sets from pinned
    Perl 5.44 Unicode 17.0 data, including loose version, wildcard, and
    unassigned aliases. The focused oracle passes 14/14 and chunk 01 gains 511
    assertions with no numbered regression.
  - [x] Routed Perl boolean values for the built-in `ASCII_Hex_Digit`/`AHex`
    property through the frontend set resolver. All eight true/false aliases
    pass 16/16 on system Perl, JVM, and interpreter, restoring `pat.t` startup.
  - [x] Generated pinned Unicode 17.0 General_Category atomic and aggregate
    sets with Perl property/value aliases. The focused oracle passes 18/18 and
    chunk 01 gains 606 assertions on both backends with zero regressions.
  - [x] Implemented native Joni line assertions from reproducible pinned Unicode
    17.0 data. The focused oracle passes 84/84 and chunks 06–09 pass
    205,380/205,380 on both execution backends while sentence/word stay exact.
  - [x] Generated pinned Unicode 17.0 Canonical_Combining_Class sets with loose
    aliases, ordered missing defaults, reserved empty values, and valid
    empty/full rendering. The focused oracle passes 24/24 and chunk 01 reaches
    33,516/41,843 on both backends with zero numbered regressions.
  - [x] Generated and integrated a complete pinned Unicode 17.0 Bidi_Class
    partition with loose aliases and ordered missing defaults. The focused
    oracle passes 99/99 and chunk 01 gains 736 assertions to 34,252/41,843 on
    both backends with zero numbered regressions.
  - [x] Generated and integrated a complete pinned Unicode 17.0
    Decomposition_Type partition, including Perl's composite `Non_Canonical`
    value and exact `Is` prefix policy. The focused oracle passes 45/45 and
    chunk 01 gains 640 assertions to 34,892/41,843 on both backends with zero
    numbered regressions.
  - [x] Generated and integrated a complete pinned Unicode 17.0
    East_Asian_Width partition with all ordered missing defaults and lossless
    surrogate-range rendering. The focused oracle passes 31/31 and chunk 01
    gains 216 assertions to 35,108/41,843 on both backends with zero numbered
    regressions.
  - [x] Generated and integrated pinned Perl 5.44 Unicode 17.0 Numeric_Value
    sets for all 144 rationals plus NaN, including exact rational reduction,
    generated decimal keyword aliases, loose forms, and wildcard policy. The
    focused oracle passes 50/50; chunks 02–03 gain 13,976 assertions with zero
    numbered regressions and exact backend identity.
  - [x] Generated and integrated a complete pinned Unicode 17.0 Joining_Group
    partition with loose aliases, ordered defaults, alternate wildcard names,
    and exact `Is`/wildcard rejection policy. The focused oracle passes 49/49;
    chunks 01–04 gain 4,290 assertions with exact backend identity.
  - [x] Generated and integrated a complete pinned Unicode 17.0 Block
    partition with official aliases, ordered `No_Block` gaps, wildcard policy,
    and Script/category/binary precedence. Chunks 01–04 gain 8,324 assertions
    with zero numbered regressions and exact backend identity.
  - [x] Generated and integrated complete pinned Unicode 17.0 Script and
    Script_Extensions sets, including Perl's bare-scx policy, strict explicit
    Script assignments, composite-value rejection and wildcard exclusion,
    aliases, precedence, and ordinary character-class complements. The focused oracle
    passes 95/95; chunks 01–04 gain 8,140 assertions with zero numbered
    regressions and exact backend counts.
  - [x] Integrated native Perl `\v`/`\V` dispatch inside and outside character
    classes (`1eff1db97`, integrated as `6328935cd`). The focused oracle passes
    92/92 and unchanged `reg_posixcc.t` passes 2,560/2,560 on both backends.
  - [ ] Close the remaining property failures before marking Phase 3 complete.
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
  - [x] Removed the Java-only terminated-whitespace possessification pass
    (`c5343aca2`; 80 preprocessor lines removed) after greedy backtracking and
    20,000-character gates passed default and forced-Java policy on both
    execution backends.
  - [x] Validated removal of the Java-only terminated lazy-negated-class
    possessification pass (`625ea97a2`; 252 preprocessor lines removed) with
    leftmost-capture and 20,000-character gates in all four backend modes.
  - [x] Retired the Java-only DBIx omniholder alternative reorder
    (`18e71a532`; 50 lines removed) after exact substitution and bundled
    DBIx::Simple gates passed. The independent raw `/g` 7/10 progression gap is
    now closed at 10/10 by the shared matcher-adapter fix above.
- [ ] Phase 6: Integration and release

### Next Steps

1. Land review-ready PR #1024 and draft PRs #1025–#1039. Publish the validated
   Script/Script_Extensions integration in a separate focused WIP PR,
   preserving the independently reviewed data commit.
2. Preserve the now-complete native Joni boundary corpus at 239,866/239,866:
   sentence chunk 05, line chunks 06–09, and word chunk 10 must remain exact on
   JVM and interpreter while property and parser work continues.
3. Integrate the independently generated break-property value slice, then the
   generic and specialized binary-property families and residual enumerated
   families currently advancing in parallel.
   Preserve pinned Perl 5.44
   acceptance and rejection semantics rather than inheriting host ICU breadth.
   Keep native `\v`/`\V` exact at 2,560/2,560 in `reg_posixcc.t`.
4. Rerun generated property chunks 01–04 on both backends with the classified
   600-second bound and retain complete TAP/JSON. After the two fatal roots and
   native line boundaries integrate, refresh the complete forced-Joni 80-file
   corpus and apply the no-regression gate against Phase 0 and PR 958.
5. Audit every `RegexPreprocessor` rule against the final ownership boundary.
   Move matcher semantics into Joni, retain only source-policy scanning, delete
   Java-only rewrites and compiled-pattern variants, and remove the temporary
   Java backend selector after the performance gate passes.
6. Reconcile `docs/reference/feature-matrix.md` with the final corpus; update
   `dev/implementation/regex.md` and `docs/design/joni-callout-fork.md` to the
   as-implemented architecture and review both for clarity and structure.
   Audit redundant regex/Joni documents, deleting only wholly redundant text
   and summarizing historically useful rationale with links to the canonical
   documents. Replace stale Unicode limitations, add any still-missing regex
   features, and link each limitation to a reducer or explicit optimizer/debug
   exclusion.
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
