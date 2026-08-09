# Executable Regex Callbacks

## Status

- **Current phase:** Phase 0 — design and differential baseline
- **Started:** 2026-08-09
- **Implementation status:** Not started
- **Prerequisite:** PR #895 (`feature/cpan-workaround-cleanup`) or an equivalent
  backend-neutral `RegexMatcher` integration
- **Primary targets:** `(?{ ... })`, `(?(?{ ... })yes|no)`, and `(??{ ... })`

## Decision Summary

Executable regex callbacks are implementable, but not by adding more textual
rewrites to `RegexPreprocessor` and not by wrapping `java.util.regex.Matcher`.
The matcher must invoke Perl while it owns the current backtracking state.

The preferred implementation is:

1. Preserve source-level regex code blocks as compiled `RuntimeCode` closures.
2. Represent an executable regex as a pattern skeleton plus a callback table.
3. Route executable patterns to a minimally extended Joni backend.
4. Add generic callout instructions and backtracking notifications to a maintained
   Joni fork; keep that fork independent of PerlOnJava runtime classes.
5. Keep ordinary patterns on the existing Java backend and declarative recursive
   patterns on the unmodified Joni path introduced by PR #895.

Joni 2.2.7 has an explicit bytecode matcher and backtracking stack, which makes it
a useful base, but it has no in-match callout API. `WarnCallback` only reports
compile warnings, `Matcher.interrupt()` only cancels matching, and
`Regex.setUserObject()` stores opaque data without invoking it. The opaque user
object is still a suitable place for a generic callout table after extending the
engine.

## Motivation

PR #895 leaves explicit capability policies for executable regex paths. These
are high-value shared blockers rather than isolated test failures:

- Type::Tiny constructs regex closures such as `qr/x(?{$z})/` and side-effecting
  callbacks such as `qr/f(?{ ++$count })oo/`.
- Regexp::Common uses callback conditions for validation and `(??{ ... })` for
  dynamically selected patterns.
- Object::InsideOut uses a self-referential `(??{ ... })` balanced-parentheses
  pattern.
- Perl core regex suites exercise callback timing, provisional captures,
  backtracking, lexical scope, dynamic scope, and runtime pattern compilation.

A correct shared implementation can remove capability policies and improve
multiple core regex files. A partial preprocessor substitution would make these
modules appear to work while silently skipping user code, so unsupported cases
must remain fatal until their semantics are implemented.

## Goals

- Execute literal `(?{ BLOCK })` callbacks at the correct match position.
- Preserve lexical closures created by `qr//`, `m//`, and `s///` on both the JVM
  and interpreter backends.
- Make provisional captures and match variables visible inside callbacks.
- Restore dynamically localized state when backtracking crosses a callback.
- Support callback conditions `(?(?{ BLOCK })yes|no)`.
- Support `(??{ EXPR })` as a nested dynamic regex program, including recursion.
- Preserve Perl's distinction between source-literal executable blocks and
  executable source introduced by runtime interpolation under `use re 'eval'`.
- Retain the fast existing engine paths for regexes without executable code.
- Provide standard-Perl differential tests before enabling each semantic tier.

## Non-goals

- Replacing every regex with Joni.
- Rewriting Perl callbacks into Java expressions.
- Treating callbacks as post-match hooks.
- Making all callback side effects transactional.
- Silently treating unsupported callbacks as zero-width no-ops.
- Completing every unrelated Perl regex feature in the same project.
- JIT-compiling the callback-aware matcher in the first implementation.

## Current Architecture and Gaps

### Parser

`StringSegmentParser.parseRegexCodeBlock()` already parses literal callback bodies
as Perl AST nodes. It currently chooses among:

- constant folding and a synthetic empty named capture for `$^R`;
- construction-time execution of a very narrow increment/decrement form;
- `RegexMarkers.CODE_BLOCK` or `RegexMarkers.RECURSIVE_PATTERN` for unsupported
  blocks.

All three representations flatten or discard information needed by a real
matcher. In particular, a pattern string cannot retain a lexical closure.

### Runtime

`RuntimeRegex` currently compiles a string plus modifiers and caches by those
values. Match variables are populated from a completed match. Executable code
needs a per-regex callback table, provisional match state during execution, and a
cache identity that does not conflate distinct closures with identical source.

### Regex engines

`java.util.regex` does not expose its backtracking transitions. Splitting a
pattern around callbacks and matching each fragment atomically is incorrect when
a later fragment fails and an earlier quantifier or alternative must retry.

PR #895 introduces a backend-neutral `RegexMatcher` and a Joni backend for
declarative subpattern recursion. That is the required routing seam, but the Joni
2.2.7 parser, compiler, opcode set, and bytecode matcher still have no callout
node or instruction.

## Required Perl Semantics

The implementation must be driven by observed standard-Perl behavior. The
following statements are design requirements to verify in Phase 0, not a
substitute for differential tests.

### Callback timing

- `(?{ BLOCK })` is a zero-width assertion executed when the matcher reaches it.
- It can execute more than once because of quantifiers, alternatives, lookarounds,
  and backtracking.
- Its scalar result becomes `$^R`; its truth value does not decide whether a
  plain `(?{ ... })` succeeds.
- Exceptions and non-local control flow must follow Perl behavior rather than be
  converted into a match failure without evidence.

### Provisional match state

While a callback is running, these values must describe the current partial
match:

- `$_` and `pos($_)`;
- numbered and named captures;
- `$^N`;
- `$^R`;
- `@-`, `@+`, `%+`, and `%-` where applicable;
- the pre-match variable, `$&`, and `$'` where Perl makes them observable at
  that point.

The runtime therefore needs a stack of active match views. Updating only the
global "last successful match" after `find()` returns is insufficient and breaks
nested regexes invoked from callbacks.

### Backtracking and side effects

Do not roll back every side effect. Perl can leave ordinary assignments, I/O,
warnings, and invoked subroutine effects visible even when the regex later
backtracks over the callback.

State created through `local`, together with automatically localized regex state
such as `$^R`, must unwind at the corresponding backtracking boundary. The
matcher must therefore record a `DynamicVariableManager` local-level checkpoint
on entry and restore to it when the callout frame is discarded. This is a
dynamic-scope checkpoint, not a general mutation journal.

The exact treatment of tied variables, magic, `pos()`, and callbacks inside
lookarounds must be established with differential tests.

### Callback conditions

`(?(?{ BLOCK })yes|no)` executes the block in scalar context and selects a branch
from its truth value. It is distinct from plain `(?{ BLOCK })` and requires a
branching matcher instruction rather than a precomputed condition.

### Dynamic patterns

`(??{ EXPR })` executes `EXPR` at the current match position and treats its
result as a regex program. The nested program must participate in outer
backtracking. Running one nested match atomically is insufficient: if a later
outer suffix fails, the nested program may need to yield another alternative.

The implementation must distinguish returned regex objects from returned strings,
preserve applicable modifiers and capture behavior, support recursive results,
and prevent unbounded Java recursion. Cache behavior, `/o`, and empty or undefined
results require differential coverage.

### Compile-time safety

Literal code in a source regex is compiled as part of the surrounding Perl unit.
Code introduced through runtime interpolation is subject to Perl's `use re
'eval'` safety rule. Embedding an already compiled `qr//` containing callbacks
must not be confused with injecting new source text.

## Proposed Architecture

```text
Perl source
    |
    v
String/regex parser
    |-- literal/interpolation AST
    `-- callback BlockNode ASTs
            |
            v
JVM compiler or bytecode compiler
    |-- evaluates pattern interpolation at construction time
    `-- compiles each callback as a lexical RuntimeCode closure
            |
            v
RuntimeRegexTemplate
    |-- engine pattern skeleton
    |-- callback descriptors and RuntimeCode table
    |-- source-origin and lexical re-eval policy
    `-- modifiers / callsite identity
            |
            v
RuntimeRegex
    |-- Java engine: ordinary supported patterns
    |-- Joni engine: declarative recursion
    `-- callout-Joni engine: executable callbacks
            |
            v
RegexCalloutBridge
    |-- installs provisional match view
    |-- invokes RuntimeCode in scalar context
    |-- tracks dynamic-local checkpoints
    `-- restores nested match state on exit/backtrack
```

### Frontend representation

Add an explicit AST representation rather than inserting callback marker strings
into ordinary interpolation:

```java
final class RegexTemplateNode extends AbstractNode {
    List<RegexTemplatePart> parts;
}

sealed interface RegexTemplatePart {
    record Pattern(Node scalarExpression) implements RegexTemplatePart {}
    record Callback(BlockNode block, CallbackKind kind) implements RegexTemplatePart {}
}

enum CallbackKind {
    CODE, CONDITION, DYNAMIC_PATTERN
}
```

The final class names may differ, but the representation must keep Perl AST and
pattern text separate. Callback bodies must use the existing anonymous-code
closure analysis and emission paths rather than inventing another evaluator.
Their invocation semantics are block-like, however, not an ordinary named
subroutine call: they must not introduce a spurious caller frame or fresh `@_`,
and regex-controlled `local` frames may need to outlive the Java method call that
evaluates the block.

Both compiler backends must construct the same runtime abstraction:

```java
final class RuntimeRegexTemplate {
    RuntimeScalar patternSkeleton;
    List<RuntimeRegexCallback> callbacks;
    RegexSourceOrigin sourceOrigin;
}

final class RuntimeRegexCallback {
    int id;
    CallbackKind kind;
    RuntimeCode code;
}
```

The callback table belongs to each runtime regex value. Two closures produced
from the same source string must not share captured lexical cells accidentally.

### Internal pattern encoding

The engine-facing pattern may contain private callout tokens that refer to table
indices, but these tokens are an internal serialization of structured data, not
user-visible preprocessing fallbacks. The encoding must be collision-proof and
must never be accepted from untrusted interpolated pattern text as an already
compiled callback.

The extended Joni parser should create dedicated callout AST nodes from these
tokens. It must not attempt to parse Perl source code.

### Generic Joni extension

Maintain a small Joni fork with a runtime-neutral interface similar to:

```java
interface CalloutHandler {
    CalloutResult execute(int calloutId, MatchView view);
    void unwind(Object backtrackToken);
}

record CalloutResult(
        CalloutAction action,
        Object value,
        Object backtrackToken) {}

enum CalloutAction {
    CONTINUE, FAIL, SELECT_TRUE, SELECT_FALSE, ENTER_DYNAMIC_PATTERN
}
```

The concrete contract should use minimal allocation, but it needs to communicate:

- callback identity;
- current byte position and provisional capture boundaries;
- result/action;
- a token to unwind dynamic state when the matcher backtracks past the callout.

Add matcher instructions conceptually equivalent to:

- `CALLOUT id` for `(?{ ... })`;
- `CALLOUT_CONDITION id, false_target` for callback conditions;
- `CALLOUT_PATTERN id` for `(??{ ... })`;
- a callout stack-frame type whose pop/unwind path notifies the handler.

Keep all Perl-specific operations in `RegexCalloutBridge`. The Joni fork must not
depend on `RuntimeCode`, `RuntimeScalar`, or other PerlOnJava types. This keeps the
fork reviewable and makes an upstream generic callout proposal possible.

### Dynamic pattern execution

`CALLOUT_PATTERN` cannot delegate to a separate matcher that returns only its
first result. The preferred implementation compiles the returned pattern into a
Joni program and enters it as a nested regex call frame sharing the outer input,
current position, and required capture context.

Phase 5 must first determine which Joni structures can safely be shared and
which capture-number translations Perl requires. If nested programs cannot be
entered without invasive Joni changes, stop and revise the design rather than
shipping atomic nested matching.

### Runtime match-state bridge

Introduce an active match-state stack rather than mutating one static completed
matcher in place:

```java
final class ActiveRegexMatch {
    RuntimeRegex regex;
    RuntimeScalar target;
    RegexMatcher matcher;
    MatchView provisionalView;
    RuntimeScalar previousCodeResult;
}
```

`RegexCalloutBridge` pushes or updates this state before invoking Perl and restores
the previous active view afterward. Nested matches from callback code then get a
new stack entry and cannot destroy the outer provisional captures.

The existing `RegexState` and `DynamicVariableManager` are starting points, but
`RegexState` currently models completed global state. It will need either a
provisional-view extension or a separate callback match state.

### Dynamic-local unwind

Before executing a callback:

1. Record `DynamicVariableManager.getLocalLevel()`.
2. Install the callback's provisional match view and automatic regex locals.
3. Invoke its `RuntimeCode` body in scalar context through a regex-block entry
   point that preserves the surrounding `@_` and caller identity.
4. Transfer ownership of callback-created dynamic-local frames to the matcher
   instead of applying the ordinary subroutine-exit unwind.
5. Retain the resulting local-level token in the Joni callout stack frame.

On normal forward continuation, localized values remain active for the part of
the match governed by Perl semantics. When Joni backtracks across that frame,
the bridge calls `popToLocalLevel(savedLevel)` and restores the prior `$^R` and
provisional match view. At final match completion or failure, all remaining
callout frames must be unwound exactly once.

This interaction needs explicit handling for exceptions, regex timeouts, matcher
interruption, and nested callbacks. If the existing `RuntimeCode.apply()` cleanup
cannot transfer dynamic-scope ownership, add a narrow `applyRegexCallback()` (or
equivalent execution mode); do not bypass cleanup by mutating the dynamic stack
after it has already been restored.

### Caching

Split cacheable engine structure from closure-bearing runtime values:

- Declarative patterns may continue using the existing pattern/modifier cache.
- An executable regex wrapper is always per construction unless proven safe.
- A compiled callout skeleton may be cached by normalized skeleton, modifiers,
  encoding mode, and engine version, while its callback table remains per value.
- `/o` uses callsite identity and retains the first constructed callback closure
  according to standard-Perl behavior.
- Dynamic `(??{ ... })` subprogram caching must be specified by differential tests;
  do not infer it from ordinary pattern caching.

### Serialization and stringification

`qr//` stringification must expose Perl-compatible source text, never internal
callout tokens or callback IDs. Storable support must be investigated separately:
serializing a closure-bearing regex may require the same restrictions as
serializing `RuntimeCode` closures.

## Rejected Approaches

### More `RegexPreprocessor` substitutions

Textual substitutions cannot invoke code at the correct backtracking point and
cannot retain lexical closures. This includes replacing callbacks with empty
groups, synthetic captures, lookarounds, or post-match callbacks.

### Splitting around Java matchers

A wrapper cannot ask `java.util.regex.Matcher` for the next internal alternative
of an earlier fragment after a later fragment fails. Making delegated fragments
atomic changes Perl semantics.

### Executing callbacks while constructing the regex

This gets timing, repetition, captures, and backtracking wrong. The existing
narrow increment/decrement construction-time path should be removed once the
real callback path is available.

### Blanket transactional rollback

Rolling back arbitrary assignments and external side effects would not match
Perl. Only dynamically scoped and automatically localized match state should be
restored at backtracking boundaries.

### JNI to the system Perl regex engine

The system engine cannot directly execute PerlOnJava `RuntimeCode` closures or
share PerlOnJava lexical cells. A native bridge would also add platform and
deployment costs without removing the callback-runtime integration problem.

### Full custom regex VM as the first step

A custom VM remains a fallback if the Joni spike fails, but it duplicates a large
working matcher before the callback semantics have been validated. Extend the
explicit-stack backend already entering PerlOnJava through PR #895 first.

## Differential Test Plan

Create `src/test/resources/unit/regex_executable_callbacks.t`. As required for
unit tests, validate every addition with standard Perl before relying on it for
PerlOnJava behavior.

### Phase 0 semantic matrix

Cover at least:

1. Plain callback execution and scalar `$^R` results.
2. False callback results that do not fail plain `(?{ ... })`.
3. Package-variable and lexical-closure reads/writes.
4. Multiple execution under alternation, greedy/lazy quantifiers, and failure.
5. Ordinary side effects after backtracking.
6. `local` restoration after forward progress, backtracking, success, and failure.
7. `$1`, named captures, `$^N`, `$^R`, `@-`, `@+`, `$_`, and `pos()`.
8. Positive and negative lookarounds.
9. Nested regex matches invoked by callback code.
10. Callback exceptions and surrounding `eval`.
11. Callback conditions with true, false, and backtracked branches.
12. `(??{ ... })` returning strings, `qr//` values, empty values, and recursive
    patterns.
13. Literal callbacks versus interpolated executable source with and without
    `use re 'eval'`.
14. `qr//` closure lifetime after the creating lexical scope exits.
15. `/g`, `/c`, `/o`, substitution, and byte/Unicode target behavior.

Suggested baseline command:

```bash
prove src/test/resources/unit/regex_executable_callbacks.t \
  > /tmp/prove_regex_executable_callbacks_perl.txt 2>&1
printf 'EXIT: %s\n' "$?" \
  >> /tmp/prove_regex_executable_callbacks_perl.txt
```

After each implementation phase, run both PerlOnJava backends with `timeout` and
capture complete output in files.

### Perl core gates

Use `perl dev/tools/perl_test_runner.pl` for broad counts and focused `jperl`
runs only under `timeout`:

- `perl5_t/t/re/rxcode.t`
- `perl5_t/t/re/reg_eval.t`
- `perl5_t/t/re/reg_eval_scope.t`
- `perl5_t/t/re/pat_re_eval.t`
- callback-related sections of `pat.t`, `pat_advanced.t`, and `subst.t`

Passing the focused unit file is not sufficient to claim full callback support.
Record reached assertions and remaining categories for each core file.

### CPAN policy-removal gates

- Run the original Type::Tiny callback tests without
  `SkipRegexCallbackTests.patch` on both backends.
- Run the unchanged Regexp::Common suite and classify remaining non-callback
  failures separately.
- Run Object::InsideOut's unchanged self-recursive pattern and its relevant suite.
- Remove a distropref or patch only after its unchanged source suite passes or the
  remaining policy has a separately documented capability reason.

### Regression and performance gates

- Run full `make` after every implementation phase.
- Preserve Java-regex behavior and performance for non-executable patterns.
- Compare Joni recursive-pattern tests introduced by PR #895 before and after the
  fork.
- Add timeout/step-limit tests for callbacks that recursively start matches.
- Measure allocation and throughput for ordinary patterns to ensure callback
  state is not allocated on their fast path.

## Implementation Phases

### Phase 0: Differential semantics and Joni spike

- Add the standard-Perl semantic matrix.
- Prototype a generic no-op callout node, opcode, handler, and unwind notification
  in a temporary Joni fork.
- Prove that a callback can observe provisional captures and runs again after
  backtracking.
- Decide fork publication/versioning and upstreaming strategy.

**Exit criteria:** The differential output is captured; the Joni prototype can
enter and unwind a generic callback without PerlOnJava dependencies; no working
tree-only fork changes remain.

### Phase 1: Structured frontend and runtime template

- Add callback-aware regex AST/template nodes.
- Compile callback blocks as lexical `RuntimeCode` closures in both backends.
- Construct per-value callback tables and collision-proof engine skeletons.
- Preserve existing fatal behavior until the matcher execution path exists.

**Exit criteria:** JVM and interpreter construct equivalent templates; closure
identity and lifetime tests pass without executing callbacks during construction.

### Phase 2: Plain `(?{ ... })`

- Integrate the callout-enabled Joni dependency.
- Add `CALLOUT`, provisional match views, `$^R`, and nested match-state stacking.
- Support package and lexical variable access.
- Keep unsupported semantic combinations fatal rather than silently approximated.

**Exit criteria:** The Phase 0 plain-callback matrix passes on standard Perl, JVM,
and interpreter; relevant Type::Tiny callback tests pass unchanged.

### Phase 3: Backtracking and dynamic scope

- Add callout backtracking frames and exact-once unwind.
- Restore `local` and automatic regex state without reverting ordinary side
  effects.
- Cover lookarounds, quantifiers, failure, exceptions, interruption, and timeout.

**Exit criteria:** Backtracking/localization sections of `rxcode.t` and
`reg_eval_scope.t` match standard Perl on both backends.

### Phase 4: Callback conditions

- Parse and represent `(?(?{ ... })yes|no)`.
- Add conditional callout branching.
- Validate provisional `$^N` and captures used by Regexp::Common validators.

**Exit criteria:** Target Regexp::Common callback-condition cases and focused core
tests pass unchanged.

### Phase 5: `(??{ ... })` dynamic programs

- Add nested program compilation/execution inside the Joni matcher.
- Integrate nested alternatives with outer backtracking.
- Specify capture numbering, modifier inheritance, recursion limits, and caching.
- Support self-referential runtime regex values without Java stack recursion.

**Exit criteria:** Differential dynamic-pattern matrix, Object::InsideOut's
self-recursive pattern, and applicable `reg_eval.t`/`rxcode.t` sections pass.

### Phase 6: Runtime source, hardening, and policy removal

- Implement `use re 'eval'` checks for runtime-injected executable source.
- Finish `/g`, `/c`, `/o`, substitution, byte/Unicode, stringification, warning,
  timeout, and serialization behavior.
- Run complete core and CPAN gates.
- Remove obsolete markers, construction-time callback hacks, CPAN patches, and
  capability policies only when their gates pass.
- Update feature matrix and user documentation.

**Exit criteria:** Full `make` passes; unchanged target suites have recorded results;
removed policies are justified by passing source-first gates.

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Joni fork becomes large or hard to rebase | Keep the callout SPI generic and opcode patch minimal; stop after Phase 0 if nested integration requires broad unrelated changes. |
| Callback match state corrupts nested regexes | Use an explicit active-match stack and differential nested-match tests. |
| Dynamic locals leak after failure or timeout | Store local-level tokens in matcher frames and exercise every exit path. |
| Cache reuses the wrong lexical closure | Separate cached skeletons from per-value callback tables; include callsite identity for `/o`. |
| Internal callback IDs can be injected by user strings | Use structured source-origin metadata and collision-proof private tokens rejected from runtime text. |
| `(??{ ... })` is treated atomically | Require nested alternatives to participate in outer backtracking before enabling the feature. |
| Core suite gains apparent passes from no-op fallback | Keep unsupported callbacks fatal and remove no-op behavior only through explicit test gates. |
| Ordinary regexes regress | Preserve engine dispatch fast paths and run PR #895 plus full `make` regression gates. |

## Open Questions

1. Can the Joni callout extension be accepted upstream, or should PerlOnJava
   publish a namespaced fork from the start?
2. What is the minimal provisional-capture API that avoids allocating `Region`
   snapshots for every callback?
3. Should active regex state become thread-local immediately, or can it be layered
   over current static fields without compromising concurrent use?
4. How exactly does standard Perl scope callback-created `local` values during
   successful forward continuation and quantifier repetition?
5. Which captures created by a returned `(??{ ... })` pattern are visible to the
   outer pattern, and how should Joni group numbers be translated?
6. What are Perl's cache rules when `(??{ ... })` repeatedly returns equal strings
   or the same `qr//` object?
7. How should closure-bearing regex values interact with Storable?
8. Which non-local control-flow operations are legal from a regex callback, and
   how should both compiler backends propagate them?

Do not resolve these by intuition. Add standard-Perl differential cases and
record the observed output in this document as phases proceed.

## Progress Tracking

### Current Status: Phase 0 in progress

### Completed Phases

- [ ] Phase 0: Differential semantics and Joni spike
- [ ] Phase 1: Structured frontend and runtime template
- [ ] Phase 2: Plain `(?{ ... })`
- [ ] Phase 3: Backtracking and dynamic scope
- [ ] Phase 4: Callback conditions
- [ ] Phase 5: `(??{ ... })` dynamic programs
- [ ] Phase 6: Runtime source, hardening, and policy removal

### Work Completed

- 2026-08-09: Created this design after reviewing the current parser,
  `RegexPreprocessor`, `RuntimeRegex`, the Joni 2.2.7 API, PR #895's matcher
  abstraction, and the executable-regex CPAN policies.
- 2026-08-09: Selected a structured callback template plus generic Joni callout
  extension as the preferred architecture.
- 2026-08-09: Corrected the older blanket-side-effect-journaling proposal: Perl
  dynamic locals require backtracking unwind, but ordinary side effects must not
  all be reverted.

### Next Steps

1. Write and validate `regex_executable_callbacks.t` with standard Perl.
2. Record exact standard-Perl outputs for every open semantic question reachable
   without implementation.
3. Create a disposable Joni 2.2.7 callout spike and measure the patch surface.
4. Decide whether to upstream the generic callout API or publish a namespaced fork.
5. Begin Phase 1 only after PR #895's matcher abstraction is merged or rebased into
   the implementation branch.

### Blockers

- PR #895 is still the integration prerequisite for the planned engine routing.
- Joni 2.2.7 does not expose an in-match callback extension point; Phase 0 must
  validate the maintained-fork approach.
- Several detailed Perl semantics remain intentionally open pending differential
  tests.

## Related Documents and Skills

- `dev/design/regex_jruby_joni.md` — older broad Joni migration notes
- `dev/design/regex_parser_integration.md` — regex AST/parser strategy
- `dev/design/regex_preprocessing_fixes.md` — known preprocessing gaps and counts
- `dev/design/regex_alternatives.md` — engine alternatives
- `dev/implementation/regex.md` — earlier full-VM and hybrid architecture notes
- `dev/design/cpan-workaround-elimination.md` — compatibility-policy design
  introduced by PR #895 (not present on `master` until that PR is merged)
- `.agents/skills/debug-perlonjava/SKILL.md` — required debugging and differential
  workflow
