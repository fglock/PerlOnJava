---
name: debug-regex-engine
description: Diagnose and fix PerlOnJava regular-expression parser, compiler, Joni fork, matcher, Unicode, warning, callout, performance, and JVM/interpreter parity failures. Use for failing Perl regex unit/core/CPAN tests, `pat.t` or `pat_thr.t` gaps, incorrect captures or `/g` progression, source-policy and `qr//` interpolation bugs, Perl-vs-Joni diagnostic differences, Unicode/property mismatches, regex hangs, or regex performance regressions.
---

# Debug the PerlOnJava Regex Engine

Read `AGENTS.md` before touching the tree. Obey its dirty-tree snapshot, no-
stash, timeout, test-integrity, build, and attribution rules. Use this workflow
in addition to `debug-perlonjava`; use `profile-perlonjava` when JFR is needed.

## Establish ownership before editing

Reduce the failure, then place it at the narrowest correct boundary:

| Symptom | Primary ownership |
|---|---|
| Delimiters, interpolation, lexical hints, source bytes | frontend parser and regex template construction |
| `use re 'eval'`, executable-source provenance, warning masks, locations | PerlOnJava runtime source policy |
| Regex grammar, classes, quantifiers, verbs, recursion, folding | forked Joni parser/analyser |
| Backtracking, captures, zero-width progression, `\G`, callouts | forked Joni matcher/bytecode machine |
| Perl property names, above-Unicode domains, charnames | `UnicodeResolver` and generated Unicode data |
| Final Perl wording/category/fatality/location | host diagnostic boundary, unless Joni lacks the event |
| JVM-only or interpreter-only behavior | compiler/interpreter runtime parity, not Joni by default |

Keep source policy in PerlOnJava and matching semantics in Joni. Do not add
pattern rewriting, `java.util.regex` fallback, source-text special cases, or a
second host implementation of a native Joni capability.

## Build a trustworthy oracle

1. Extract one minimal Perl program from the failing assertion. Preserve the
   operation form, modifiers, scalar byte/Unicode provenance, `pos`, warning
   scope, overloads, and callback side effects that matter.
2. Run the reducer with system Perl first. A new tracked `.t` file must pass
   system Perl before it may define PerlOnJava behavior.
3. Run the same file on the JVM and interpreter backends. Capture every run to
   a file and wrap `jperl`/`prove` in `timeout`.
4. Add a nearby passing control and the reverse edge. A one-assertion test often
   hides cache, progression, warning-timing, or operand-order regressions.

Typical commands:

```bash
timeout 60 prove src/test/resources/unit/regex/reducer.t \
  > /tmp/reducer-system.log 2>&1
timeout 120 ./jperl src/test/resources/unit/regex/reducer.t \
  > /tmp/reducer-jvm.log 2>&1
timeout 120 ./jperl --interpreter src/test/resources/unit/regex/reducer.t \
  > /tmp/reducer-interpreter.log 2>&1
```

Never modify imported `perl5_t` fixtures. If current upstream Perl changed a
test, update the development checkout and run `dev/import-perl5/sync.pl`; fix
the product against the unpatched imported test.

## Make every discovered failure permanent coverage

Every externally observed failure must add or strengthen a tracked,
project-owned regression test before the fix is complete. This includes core
Perl and CPAN assertion failures, incorrect warning text/category/timing,
timeouts with a diagnosed algorithmic root, backend-only failures, and stale
backend/source-routing behavior. A passing distribution rerun, an untracked
reproducer in `/tmp`, or an existing broad test is integration evidence; it
does not replace a focused permanent regression test.

The permanent test must:

- reproduce the original failing operation and preserve the relevant source,
  scalar, warning, overload, callback, thread, and byte/Unicode provenance;
- pass unchanged on system Perl before product code is changed, except for a
  direct-Joni-only invariant that has no Perl-level representation;
- fail on the unfixed PerlOnJava parent for the expected reason, with the
  before-result retained in the delivery report;
- pass on JVM and interpreter after the fix, plus direct Joni when the change
  is inside the fork;
- include a nearby positive control and reverse/negative edge so a special-case
  implementation cannot satisfy only the reported input;
- remain independent of a downloaded CPAN build tree whenever the behavior can
  be expressed with core/project-owned fixtures.

Do not mark a plan item complete or deliver a commit until this test is tracked.
If a failure cannot yet be reduced into permanent coverage, keep the item open
and report the reduction blocker explicitly.

## Separate compile, search, and reporting failures

Trace the first divergent stage instead of patching the final symptom:

1. Source/template identity: raw and cooked text, byte/Unicode flag, modifiers,
   overload result, lexical hints.
2. Native compile: Joni options, AST, emitted bytecode, optimization metadata,
   callback/property descriptors.
3. Search: search start, region bounds, independent `\G` position, zero-length
   retry state, character-to-byte conversion, capture high-water state.
4. Publication: `pos`, `$&`, `$1..`, `%+`, `@{^CAPTURE}`, `(*MARK)`, warning and
   fatal diagnostic text/location.

For `/g` and `\G`, record the complete sequence of `($-[0], $+[0], pos)` until
failure. Search start, `\G`, and the previous empty-match position are distinct
state. Test byte and wide subjects because Joni offsets are bytes while Perl
publishes character offsets.

For warnings, test category disabled, enabled, fatal, and qr-construction versus
match-execution timing. Optimizer/start-class inspection may emit a distinct
event from the executed opcode. Never globally suppress a warning to make a
distribution quiet.

For overload and interpolation, record operand `ref`, order, reversed flag,
stringification count, and whether a compiled `Regexp` object retains identity.
Test match and substitution; bare, prefixed, suffixed, and chained forms can use
different template paths.

## Change Joni safely

Treat `third_party/joni` as a maintained fork:

- Preserve every upstream copyright and authorship notice.
- Add the repository's compatible notice header to new fork files.
- Prefer immutable compile metadata and general parser/matcher capabilities.
- Add direct Joni coverage for native behavior and host Perl coverage for the
  PerlOnJava integration boundary.
- Audit copy/clone/thread paths when adding regex, node, matcher, or resolver
  state. Clear matcher-local callbacks and caches at the existing lifecycle.
- Keep optimizer metadata conservative. A precheck may reject only when the
  required condition is mathematically necessary on every successful path.

When changing alternatives, quantifiers, lookarounds, recursion, or class set
operations, test both positive and negative forms plus nested and empty paths.
Metadata merged across alternation must represent every branch, not whichever
branch was visited last.

## Diagnose performance without weakening semantics

Create a scalable reducer with size/repeat environment variables. Measure a
small correctness case and a larger bounded case, then profile the larger case
with JFR through `profile-perlonjava`. Distinguish startup/compilation cost from
matcher cost.

Accept an optimization only when it:

- removes an algorithmic root rather than raising a timeout;
- preserves system-Perl results on success, failure, anchoring, captures, and
  callbacks;
- includes a direct native test for optimization metadata or search behavior;
- demonstrates warmed before/after evidence under comparable load.

Do not skip an expensive assertion, recognize its literal source, or convert a
required semantic test into a benchmark-only test.

## Validate from narrow to broad

Use the smallest gate that can disprove the patch, then expand:

1. System-Perl reducer.
2. JVM and interpreter reducer.
3. Existing adjacent regex unit tests and direct Joni tests.
4. Owning imported file on both backends; for thread-sensitive work also run its
   `_thr` peer and compare assertion maps.
5. Relevant CPAN distribution on every required mode, scanning warnings.
6. One warning-free `make` on the exact clean candidate head.
7. Complete acceptance ledger and CI only after related patches are integrated.

Always invoke builds through `make`. Do not repeatedly launch full builds while
the patch is changing. If `make` fails, compare the exact parent in a separate
clean worktree; never overwrite a dirty tree to perform the A/B check.

For core-suite counts, use system Perl to launch the runner:

```bash
perl dev/tools/perl_test_runner.pl --jobs 5 --timeout 300 \
  --output /tmp/regex-results.json perl5_t/t/re \
  > /tmp/regex-run.log 2>&1
```

Require complete nonzero TAP records. Compare by path against the checked-in or
designated immutable baseline; a timeout or `0/0` row is not a passing result.
Do not repeat a costly complete `pat.t` map until all focused failing clusters
on that candidate are closed.

## Report an integration-ready result

Provide all of the following:

- root cause and ownership boundary;
- commit SHA and changed files;
- permanent regression-test path plus system-Perl and unfixed-parent evidence;
- system/JVM/interpreter counts and log paths;
- direct/thread or CPAN evidence when applicable;
- warning scan and performance evidence when applicable;
- exact `make` result, or a parent-fenced failure with identical A/B evidence;
- `git diff --check`, clean status, and confirmation that no test process or
  development-only symlink was left behind.

Do not claim completion from a focused pass while an owned adjacent test is red.
Preserve earlier accepted commits; add a follow-up commit when review exposes a
new defect rather than rewriting delivered history.
