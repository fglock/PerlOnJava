# Performance over Perl handoff

Issue: [#1196](https://github.com/fglock/PerlOnJava/issues/1196)

## Resume here — 2026-09-14

The objective is **not achieved**. Continue from the current committed source,
after rebuilding it, and use the measured portfolio protocol rather than older
commit identifiers or historical benchmark narratives.

The retained improvements have brought closure, method, numeric, and JSON
above Perl in the latest high-load evidence. String, regex, and Life remain
materially below parity. The next deliverable is one conservative,
independently reversible body-cost reduction for one of those workloads.

Do not consume `doesNotObserveDynamicTopic` as an ownership/effect proof. It
only records analysis metadata; it does not prove that a lexical, topic, or
array cell cannot be observed through aliasing, a closure, `eval`, debugger
state, exceptions, destructors, or dynamic code.

### Alternate-quest checkpoint

It is safe to leave this optimization effort for an unrelated task and return
without recreating any completed measurement. The Joni stack-guard and
cursor-local matcher-pool eligibility-cache candidates are retired after
completed forward/reverse selection screens. The scalar-only aggregate-cleanup
candidate is also retired: it passed its system-Perl oracle, JVM/interpreter
test, and clean full gate, but its completed seven-pair Life screen at
`/tmp/perf-life-scalar-only-array-candidate-20260914/20260914T194709Z/portfolio.json`
was 0.62207x Perl (0.62172x pair geometric mean), below the 0.63085x current
anchor. Its proof and cleanup omission must not be reapplied or remeasured.
The captureless Joni last-closed-capture query elision is also retired: it
removed a real temporary traversal allocation but produced no material,
conclusive regex screen.
The current runtime therefore retains the accepted pooled-matcher configuration
elision, but none of those rejected variants. Inspect the current committed
branch head before resuming rather than applying or benchmarking a retired
candidate again.
PR #1295 is a separate open PR on
`perf/benchmark-authority` and must not be used as this work's source or
updated as part of the resume.

On return, first inspect active processes and each process's working directory;
do not mutate a checkout with a benchmark or test gate still active. Then read
this file's **Current measured position**, **Fresh attribution and selected
work**, and **Next steps** sections. Treat the listed JFRs, portfolio JSON, and
completed exact-parent screens as terminal evidence: do not restart them.
The first new action is to read the completed rejection checkpoints, retain
their evidence, and then choose a *different* broad source-level
ownership/representation boundary in Regex, String, or Life. Do not restart,
extend, or reapply any retired candidate.

## Acceptance target

Ratios are PerlOnJava operations/second divided by the pinned reference Perl.
Startup, parsing, bytecode generation, and warmup are excluded.

The project acceptance contract requires all of the following from a complete,
source/JAR-matched default portfolio:

- portfolio geometric mean at least 1.05x with 95% confidence interval wholly
  above 1.00x;
- closure and Life anchors each at least 1.05x with intervals wholly above
  1.00x;
- no scored workload below 0.90x; and
- preserved Perl semantics and JVM/interpreter parity.

For this handoff, also aim to establish a 1.00x median and lower confidence
bound for every scored workload. The existing acceptance reporter does not by
itself certify that stronger per-workload claim; add reporter coverage before
claiming it.

The default benchmark is `dev/bench/run_performance_portfolio.pl`: seven
alternating fresh-process pairs per workload, 10--60 one-second warmup windows,
and fifteen one-second measurement windows. Every run must record source/JAR,
host state, checksums, and analyzer output. A high-load run is valuable
selection evidence but cannot make a positive acceptance claim when the
analyzer labels it noisy or inconclusive.

This host intentionally runs a realistic production-like simulation. Its
ambient CPU load is therefore part of the profile context rather than a reason
to discard a completed recording. Record it in every artifact and use matched,
alternating fresh-process pairs for comparisons; retain the analyzer's
confidence and noise qualification when deciding whether an observed gain is
strong enough to keep.

## Current measured position

The current source/JAR-matched full high-load measurement is:

`/tmp/perf-current-full-highload-post-regex-20260914/20260914T024737Z/portfolio.json`

It completed checksums, protocol validation, and stable warmup, but remains
non-accepting: portfolio geometric mean 1.00563x (95% interval
0.94909--1.03423x), minimum 0.60042x. Its workload geometric means were:

| Workload | Ratio |
| --- | ---: |
| Closure | 1.15196x |
| Method | 1.10180x |
| Numeric | 1.16259x |
| String | 0.59316x |
| Regex | 0.76236x |
| Life | 0.63085x |
| JSON | 2.35292x |

Later scoped high-load checks confirm the same prioritization. Keep the
retained generic UTF-8 plain-string concat, byte-string concat and
byte-string/integer concat paths, capture-free literal alternation dispatch,
lazy scalar-match materialization of `$&`, and empty use-site warning-path
elision; none establishes portfolio parity.

## Fresh attribution and selected work

### 1. Life: establish a safe representation boundary first

The fresh Life recording is `/tmp/perf-life-current-body-20260914.jfr` with
its workload log at `/tmp/perf-life-current-body-20260914.log`. It completed
with checksum `1243097892` after stable warmup. Allocation samples repeatedly
reach `RuntimeArray.addToArray`, range iteration, arithmetic scalar creation,
and `RuntimeArray.setUnsignedWordElement`.

A later bounded production-load diagnostic at
`/tmp/perf-life-callframe-current-jfr-20260914/20260914T112308Z/life-pair-01.jfr`
ran for 77 seconds. Its CPU and allocation samples center on
`RuntimeCode.invokeCallable` and `invokeWithCallFrame`, followed by
`RuntimeArray.setFromList`, list/array copy paths, and range iteration. The
native-word expression is no longer the dominant selection target.

The latest independently bounded generated-body trace is
`/tmp/perf-life-current-body-refresh-20260914.jfr`, with its workload log at
`/tmp/perf-life-current-body-refresh-20260914.log`. It ran for 77 seconds,
returned the same checksum (`1243097892`), and is diagnostic only because
warmup did not stabilize on the intentional production-like host load. Its
381 execution samples and 21,936 allocation samples again put
`RuntimeCode.invokeCallable`, `invokeWithCallFrame`, scalar materialization,
array/list transport, and generated-body lexical activity ahead of a narrow
word-operation leaf. `RuntimeArray.setUnsignedWordElement` also reaches
`RuntimeScalar.set(long)` and consequently boxes non-`int` words; however,
that setter deliberately preserves the identity and ordinary mutation effects
of an observable array-element scalar. Do not optimize that setter in
isolation. Any replacement must be a general whole-representation boundary
with an explicit no-escape/no-observation proof and fallback.

The follow-up call-layer artifact
`/tmp/perf-life-call-layer-current-20260914/20260914T113412Z/portfolio.json`
completed with the same checksum on the intentional production-like host load
(36.27/38.61/32.01). It is one diagnostic pair, not acceptance evidence. Its
45,904 named-instance calls averaged 1.098 ms inclusive and 0.553 ms exclusive
with 2.210 MB / 1.105 MB inclusive/exclusive allocation; the measured dispatch
setup itself was only 0.562 microseconds. Thus a dispatch micro-optimization
cannot close Life, while a general lifecycle/result-representation reduction
could be material if—and only if—it preserves the full Perl call contract.

The likely opportunity is avoiding transient scalar/list transport inside the
bit-packed recurrence. It is **not** safe to reuse destination array element
cells generally: standard Perl and PerlOnJava both preserve a reference to an
old `@a` element across `@a = @b`. Any transfer/rebind optimization therefore
needs a whole-body, lexical no-escape proof for both arrays, dead-source proof,
and a generic fallback. Do not implement a local shortcut based only on the
Life benchmark shape.

The earlier disassembly attribution that named the three `$left`/`$cell`/
`$right` reads is now historical, not an active implementation direction. The
current source already lowers the final recurrence to native-word operations,
and the new call-frame recording puts generated-body scalar/list transport and
range/array work ahead of an isolated read lowering. Do not revive a
read-specific shortcut or a per-node eligibility probe: previous native
bitwise-tree and word-store variants were measured regressions. Select only a
whole representation boundary with a general proof and fallback.

Before coding, write the proof obligations for aliases, references, closures,
`eval`, debugger visibility, exceptions, destructors, non-local control flow,
and reassignment. Add a permanent focused test and validate it with system
Perl first. Then validate both backends, full `make`, an exact-parent
comparison, and a complete portfolio.

The existing inner closure already receives `reusableEmptyArgs` and
`requiresJvmClosureFrame = false` when its static body qualifies. The residual
`invokeWithCallFrame` samples are therefore not evidence that either existing
flag is missing. The call-layer collector further attributes only 0.39us of
Life setup per application versus about 1.043ms inclusive generated-body time;
do not revive a call-frame, warning-scope, or argument-unpack bypass without
new non-overlapping attribution. A future Life candidate must instead isolate
a materially larger scalar/list transport or representation boundary, with the
ordinary lifecycle retained for mutation, aliases, references, nested
closures, dynamic calls, callbacks, control-flow joins, debugger observation,
caller/warning scope, exceptions, and destructor timing.

The plain-array void-assignment-result candidate is retired. It preserved the
ordinary clear/store/refcount/mortal lifecycle and omitted only the result
array of a void `@array = LIST`, but its immutable `make` gate passed at
`/tmp/make-array-void-assignment-candidate-20260914.log` and the completed
seven-pair candidate and exact-parent screens were respectively
`/tmp/perf-life-array-void-assignment-candidate-20260914/20260914T185112Z/portfolio.json`
and
`/tmp/perf-life-array-void-assignment-parent-76c09d435-20260914/20260914T185846Z/portfolio.json`.
Both were protocol-compliant with stable warmup and matching checksums. The
candidate Life median was 0.62470x Perl versus the parent's 0.65851x; same-index
candidate/parent PerlOnJava median throughput had a 0.96696x geometric mean
(range 0.76280x--1.06735x). Do not retry plain-array void-assignment result
elision; it is too small and non-repeatable for the Life gap.

The subsequent compiler audit identifies the required larger boundary. The
existing `CleanupNeededVisitor` only suppresses lexical cleanup-stack
registration; `EmitStatement` still unconditionally calls
`scopeExitCleanupArray` because an arbitrary local array may contain a
caller-provided or call-returned blessed reference. A successor must be a
separate, conservative scalar-only aggregate dataflow proof: each selected
array is freshly allocated, cannot escape or be aliased, and receives elements
only from non-calling primitive expressions or other proven scalar-only arrays.
It must reject `@_`, dereferences, calls, `eval`, ties, references, closures,
dynamic scope, callbacks, and every control-flow join without identical facts.
Only that whole lifetime proof could safely omit the container walk; do not
weaken the existing generic cleanup gate.

The proof cannot stop at a generated closure boundary. `EmitSubroutine` carries
the declaration AST of every captured lexical in `SymbolEntry.ast()`, and the
child emitter rebuilds its symbol table from those entries. Analyze and tag an
outer declaration before closure creation, propagate that immutable fact only
through a captured lexical with the same declaration identity, and seed the
child proof from it. This is required for the measured Life kernel: its
private `@grid` begins as a copy of captured `@seed`, and the fresh per-round
`@next` is derived from `@grid`. A local-only `@next` proof would reject the
actual recurrence; treating any capture as scalar-only would be unsound. The
outer proof must therefore establish the seed's scalar-only producer (including
the `map` callback and its range input) before the child can inherit it.

Selection must be consumed at every lifecycle-emission site, not only ordinary
block fallthrough. The JVM currently emits array cleanup from normal
`EmitStatement` scope exit, conditional cleanup, `EmitControlFlow` subroutine
exit, and explicit `return`; eval exception cleanup separately records the
local slots that its catch handler visits. A selected slot must be excluded
consistently from all of those paths (while still being nulled and retaining
ordinary cleanup for every unproven slot). Otherwise an early return, non-local
exit, or `eval` unwind would either reintroduce the cost or create a divergent
lifecycle contract.

### 2. Regex: target matcher/dispatch body cost

The current regex JFR is `/tmp/perf-regex-current-body-20260914.jfr`; compact
CPU/allocation reports are `/tmp/perf-regex-current-body-20260914.cpu.txt` and
`/tmp/perf-regex-current-body-20260914.alloc.txt`. It completed with checksum
`1024`. CPU samples center on `RuntimeRegex.matchRegexDirect`, regex metadata,
literal-pad materialization, quoted-regex resolution, and Joni search/matcher
configuration. Allocate effort to a broad dispatch or temporary-representation
boundary with a non-overlapping Amdahl budget, not an individual bytecode leaf.

The scalar-match whole-text boundary is now lazy: it keeps the immutable
match-time input and offsets, then creates `$&` only if it is read. The
focused system-Perl/JVM/interpreter test covers failed-follow-up and replacement
visibility, and the exact PR gate passed. In the clean managed candidate and
reverse-parent screens it improved the regex median from 0.65171x to 0.72192x
despite higher candidate host load. Next, profile the remaining matcher and
dispatch body after this allocation is removed; do not special-case the
portfolio pattern or make list-context `/g` return values lazy.

Patterns without deferred use-site diagnostics now bypass dynamic warning-scope
resolution; patterns with diagnostics retain the complete warning path. This
raised the clean seven-pair regex screen from 0.68546x to 0.75043x Perl despite
higher candidate load. Profile only the residual generic matcher/dispatch
costs next, retaining dynamic templates, warning policy, and callback behavior.

The default-state pooled-matcher configuration elision in `1c9b71091` is a
conservative candidate: the existing pool admits a matcher only when locale,
callbacks, control verbs, physical named captures, deferred-property handling,
warning handling, and alarm interruption are all absent. It consequently skips
only redundant writes of those default values; every featureful lifecycle still
calls `configureMatcher`. Both candidate and exact-parent full gates passed.
Their completed seven-pair source-distinct regex screens are
`/tmp/perf-regex-default-config-candidate-20260914/20260914T121106Z/portfolio.json`
and
`/tmp/perf-regex-default-config-parent-20260914/20260914T122138Z/portfolio.json`.
The candidate's per-pair PerlOnJava/Perl median was 0.73300x versus the
parent's 0.66430x, but its host load was substantially higher
(51.69/77.39/78.27 versus 37.82/39.20/55.32). This is encouraging screening
evidence, not a selection or acceptance result: do not restart either completed
screen; require a separately scoped reverse-order exact-parent comparison before
retaining it.

The ASCII identity-offset-map candidate in `b9261990f` makes UTF-8 input
encoding use the same null identity maps already used for byte strings when
every input character is ASCII. It preserves explicit maps for non-ASCII input
and covers normal capture offsets, `/g`/`pos`, callout offsets, and a wide
character control case in `src/test/resources/unit/regex/ascii_identity_offsets.t`.
That test passed system Perl and both backends; both candidate and exact-parent
`make` gates also passed. Its completed source-distinct seven-pair screens are
`/tmp/perf-regex-ascii-identity-candidate-20260914/20260914T125210Z/portfolio.json`
and
`/tmp/perf-regex-ascii-identity-parent-20260914/20260914T130010Z/portfolio.json`.
The candidate median was 0.76951x (2.684M PerlOnJava/s, 3.360M Perl/s) versus
the parent's 0.86103x (2.518M, 3.084M). These sequential production-load
screens are not a controlled comparison: their loads were respectively
40.16/45.76/43.89 and 30.93/33.65/37.63, and the parent artifact was
non-conclusive. They nevertheless provide no selection-grade lift. Do not
restart either completed screen or retain this as the next regex lever; seek a
broader matcher/dispatch-body cost instead.

Preserve dynamic templates/modifiers, package and warning state, `qr//`
identity, `/g` position, capture state, callbacks, and Joni find conditions.
The retained literal alternation fast path must remain excluded for
`FIND_LONGEST` and `FIND_NOT_EMPTY`.

### Joni stack-guard selection checkpoint (2026-09-14)

Commit `b2767b54b77d1f13659f43210e0e646d347765af` conditionally elides the
paired `ThreadLocal` matcher-execution-depth guard around
`ByteCodeMachine.matchAt` only when the matcher has no runtime service that
can re-enter it. The ordinary callback-free path therefore avoids the guard;
callouts, deferred-property resolution, locale resolution, and non-Unicode
property warnings retain it. Direct Joni dynamic calls retain their independent
stack behavior. The existing reentrant-callout Joni coverage plus
`src/test/resources/unit/regex_joni_search_optimizer.t` passed system Perl,
JVM, and interpreter. `make test-joni`, the candidate full gate, and the exact
committed-source full gate also passed; their logs are
`/tmp/make-test-joni-stack-guard-20260914.log`,
`/tmp/make-joni-stack-guard-candidate-20260914.log`, and
`/tmp/make-joni-stack-guard-candidate-committed-20260914.log`. The exact
parent `9dbfd3081c413b2991184b14ba69905aa8b33c4e` is available, gated, and
idle at `/private/tmp/perf-joni-stack-parent-9dbfd3081`; its gate log is
`/tmp/make-joni-stack-guard-parent-9dbfd3081-20260914.log`.

The first completed, alternating-order seven-pair exact-parent screening
sequence is rooted at `/tmp/perf-joni-stack-guard-parent-candidate-20260914`;
its `screen.log` ends `EXIT: 0`. Every artifact has checksum `1024` and
stabilized warmup. Same-index candidate/parent PerlOnJava median-throughput
ratios were 0.7581x, 0.7993x, 1.1196x, 1.1212x, 1.3452x, 0.7723x, and
1.0861x: median 1.0861x, geometric mean 0.9784x, range 0.7581x--1.3452x.

Its new, independently named reverse-order screen is
`/tmp/perf-joni-stack-guard-reverse-9894eb116-20260914`; its `screen.log`
also ends `EXIT: 0`, every artifact has checksum `1024` and stabilized
warmup, and its candidate JAR is source-matched to `9894eb116`. Its ratios
were 0.9769x, 1.2705x, 1.3892x, 0.5784x, 0.9827x, 1.0837x, and 1.0888x:
median 1.0837x, geometric mean 1.0210x, range 0.5784x--1.3892x. Across both
screens, the fourteen-pair geometric mean is 0.9995x. The realistic
production-like load is useful context, but these reverse screens do not show
a material repeatable gain. The change is therefore retired by a normal
revert; do not restart either screen, claim a win, or retry this stack-guard
elision unchanged.

### Rejected static-callsite package-cache elision (2026-09-14)

The post-revert diagnostic pair at
`/tmp/perf-regex-post-stack-guard-revert-jfr-20260914/20260914T164856Z`
completed with checksum `1024`. After startup it still attributes broad body
cost to `RuntimeRegex.matchRegexDirect`, `matchRegex`, quoted-regex resolution,
Joni search, and call-frame transport. The `getQuotedRegexInPackage` cache-hit
candidate in `d82e1033d056194a03b1a14b3651e37e757cca10` checked the existing
runtime-local private callsite wrapper before temporarily switching the lexical
package; cache misses retained the complete compile and restore path. Its
permanent focused test
`src/test/resources/unit/regex_static_callsite_package_cache.t` passed system
Perl, JVM, and interpreter, and both the candidate and exact-commit full gates
passed at `/tmp/make-regex-static-callsite-package-cache-candidate-20260914.log`
and `/tmp/make-regex-static-callsite-package-cache-exact-d82e1033d-20260914.log`.

The completed seven-pair exact-parent screen is rooted at
`/tmp/perf-regex-static-callsite-package-cache-parent-candidate-20260914` and
its `screen.log` ends `EXIT: 0`. All artifacts have checksum `1024`; candidate
pair 4 did not stabilize warmup. Same-index candidate/parent medians were
1.0799x, 0.9178x, 1.0984x, 0.7076x (unstable), 1.1849x, 1.0150x, and
1.2424x: all-pair geometric mean 1.0203x. This one high-variance screen is not
selection-grade evidence, so the candidate is retired by a normal revert.

### Rejected cursor-local matcher-pool eligibility cache (2026-09-14)

Commit `9d32018530ab09233c16f7cd9fb764f25d334dfc` computed the existing
matcher-pool eligibility predicate once in `JoniRegexMatcher` construction,
rather than repeating the audit for each `/g` probe. The cached fact depended
only on cursor-final pattern metadata, flags, callbacks, property hooks,
interrupt mode, and control-verb/named-capture state; featureful cursors kept
the ordinary configuration and matcher lifecycle. The candidate full gate and
the exact-commit gate passed at
`/tmp/make-regex-matcher-eligibility-cache-candidate-20260914-retry.log` and
`/tmp/make-regex-matcher-eligibility-cache-exact-9d3201853-20260914.log`.
The separately built exact parent `76c09d435e3c5c13efcf72632cd444834c271eca`
also passed at
`/tmp/make-regex-matcher-eligibility-cache-parent-76c09d435-20260914.log` in
`/private/tmp/perf-regex-matcher-eligibility-parent-20260914`.

The initial candidate-then-parent seven-pair screens are
`/tmp/perf-regex-matcher-eligibility-cache-candidate-9d3201853-20260914/20260914T180418Z/portfolio.json`
and
`/tmp/perf-regex-matcher-eligibility-cache-parent-76c09d435-20260914/20260914T181108Z/portfolio.json`.
All warmups stabilized and checksums were `1024`; their candidate/parent
PerlOnJava median-throughput geometric mean was 1.0225x. The candidate host
load was substantially higher (16.03/35.08/44.37) than the parent's
6.39/13.98/30.07, so that result required a reverse screen. The independently
completed parent-then-candidate screens are
`/tmp/perf-regex-matcher-eligibility-cache-parent-reverse-76c09d435-20260914/20260914T181909Z/portfolio.json`
and
`/tmp/perf-regex-matcher-eligibility-cache-candidate-reverse-9d3201853-20260914/20260914T182539Z/portfolio.json`.
Their seven-pair geometric mean was only 1.0132x. Across all fourteen pairs,
the geometric mean is 1.0178x (range 0.9636x--1.0969x): a non-material,
high-variance result that cannot close the roughly 0.76x Regex gap. Retire the
candidate by normal revert; do not restart these completed screens or retry
this eligibility-cache hoist unchanged.

### Rejected captureless last-closed-capture query elision (2026-09-14)

The new JFR/call-layer diagnostic at
`/tmp/perf-regex-joni-body-current-20260914/20260914T195904Z` confirmed that a
capture-free pattern reaches `ByteCodeMachine.lastClosedCapture`, which creates
a temporary `ArrayDeque` even though no `$^N` group can be published. Candidate
`00edfb3a2` skipped that query only when `regex.numberOfCaptures() == 0`; its
system-Perl oracle, JVM/interpreter test, and two clean full gates passed.

The completed seven-pair candidate screen is
`/tmp/perf-regex-captureless-last-closed-candidate-20260914/20260914T201633Z/portfolio.json`.
It is protocol-compliant but inconclusive because candidate pair 1 did not
stabilize; its 0.76829x median and 0.78702x pair geometric mean at load
26.66/29.40/22.55 are not a material selection signal over the 0.76236x
anchor. The source was reverted without an exact-parent screen. Do not retry
this captureless state-query leaf; seek a broader Joni search/match boundary.

### 3. String: reduce a representation/ownership boundary

String remains well below the 0.90x floor. Prior attribution reaches
`RuntimeArray.createReferenceWithTrackedElements`, scalar materialization, and
string/substr work. Start with one broadly applicable, semantics-proven
representation boundary. Avoid another typed leaf branch unless profiling shows
its selected fraction and fallback cost can clear a material budget.

The generated recurrence `substr($s . ':' . $_, -24)` already takes the
fixed-arity two-argument `substr` emitter path, so it does not allocate the
general `RuntimeBase[]` varargs transport. Its ASCII literals are emitted as
`BYTE_STRING`, which already selects the retained byte-string and
byte-string/integer concat fast paths; its direct assignment also emits the
`substr` RHS in `SNAPSHOT` context, avoiding an unnecessary lvalue proxy. Its
remaining expression cost is therefore the two independently materialized
warning-aware concatenation scalars crossing into the generic snapshot path.
Do not add a source- or benchmark-shaped concat/substr shortcut. Any successor
must provide a general representation for an unobserved concat result while
preserving left-to-right evaluation, overload, ties, warnings, taint,
byte/UTF-8 provenance, aliases, and lvalue behavior.

### Mixed UTF-8/octet concat checkpoint (2026-09-14)

Commit `752ab95c917029dd20f6749f7fc3ff9abb5a2648` conservatively avoids the
generic concat result transport for an ordinary, untainted mixed
`STRING`/`BYTE_STRING` pair only when `use bytes` is inactive. It deliberately
falls back for special-variable proxies, taint, format taint, and byte-hint
semantics. The permanent coverage is
`src/test/resources/unit/string_concat_mixed_utf8_byte_fastpath.t`: it passed
the system-Perl oracle and both PerlOnJava backends. The exact committed
`make` gate passed at
`/tmp/make-string-mixed-utf8-byte-exact-752ab95c9-20260914.log`.

The completed candidate screen is
`/tmp/perf-string-mixed-utf8-byte-candidate-20260914/20260914T134827Z/portfolio.json`.
It is protocol-compliant and conclusive on the intentional production-like
host load (54.81/59.59/54.20), with a 0.59118x median (8.922M PerlOnJava/s,
15.282M Perl/s). Its one source-distinct parent screen is
`/tmp/perf-string-mixed-utf8-byte-parent-20260914/20260914T140142Z/portfolio.json`:
it is also protocol-compliant and conclusive, at 0.63247x (9.601M PerlOnJava/s,
14.528M Perl/s) on load 6.58/14.83/29.81. The non-identical ambient loads mean
these sequential screens are not a controlled positive comparison, but they
provide no material or repeatable lift. Do not restart either screen.

The exact parent is already gated and idle in detached worktree
`/private/tmp/perf-string-mixed-utf8-byte-parent-f072` at
`f072703c9`; its gate log is
`/tmp/make-string-mixed-utf8-byte-parent-exact-f072703c9-20260914.log`.
The guarded source fast path has therefore been removed. Its semantic test
remains: system Perl, both PerlOnJava backends, and the post-removal full gate
passed (`/tmp/make-string-mixed-utf8-byte-retired-20260914.log`). Do not retry
another mixed UTF-8/octet concat leaf; the remaining String work needs a
broader unobserved-result representation boundary.

### Fresh generic-transport attribution (2026-09-14)

`/tmp/perf-string-generic-transport-current-20260914.jfr` is a 77-second,
current-source JFR diagnostic (checksum `24`, 3,176 execution samples and
22,062 allocation samples). It is not throughput evidence: warmup did not
stabilize under the intentional production-like host load. After the first ten
seconds, stack-frame occurrence counts are `RuntimeScalar.toString` 1,287,
`stringConcatWarnUninitialized` 1,232, `RuntimeScalar.addToScalar` 541,
`RuntimeScalar.set` 532, and `Operator.substrImpl` 281; `blessedId` appears
only three times. Allocation samples repeatedly show `byteStringConcat` scalar
and Java-string allocations followed by a `substrSnapshot` scalar. Thus a new
candidate must address the complete ordinary unobserved concat-result and
snapshot transport, not another blessing/type leaf or a standalone assignment
dispatch tweak. It must still establish operand warnings, ties, overload,
taint, byte/UTF-8 provenance, and left-to-right evaluation before any deferred
or transferable representation is selected.

### Rejected deferred concat representation (2026-09-14)

A generic runtime candidate briefly represented ordinary concat results with
deferred character storage, allowing a read-only byte-string `substr` snapshot
to consume that storage without first materializing the complete intermediate
string. This was deliberately a runtime representation experiment, not a
compiler-shaped concat/`substr` lowering, and no benchmark was started.

The candidate is rejected on semantics before measurement. A tied-hash key
formed by an ordinary concat observed the deferred value through an existing
direct value boundary as `""` rather than its complete string. Repairing every
such escape boundary piecemeal would not establish the required ownership
contract, so the runtime changes were removed rather than widened. The
permanent focused coverage is
`src/test/resources/unit/deferred_concat_snapshot.t`; it covers ordinary,
Unicode, byte-string, tied-scalar, tied-hash-key, and assignment/alias
behavior. It passed system Perl, both PerlOnJava backends, and the
restored-source full gate at
`/tmp/make-deferred-concat-snapshot-hash-key-final-20260914.log` (5m52s).

Do not retry a globally deferred mutable/string-builder scalar representation
without a complete audit of every value-observation and escape boundary. A
future String candidate must prove its representation cannot reach a hash key,
reference, assignment, list/call transport, tied/magic operation, or external
runtime consumer before it stops carrying an ordinary `String` value.

### Static concat escape audit (2026-09-14)

The required source-level audit has now been performed for the rejected global
deferred-concat representation. `stringConcatWarnUninitialized` returns an
ordinary `RuntimeScalar` directly to generic consumers after its warning, tie,
overload, taint, and byte/UTF-8 work. `RuntimeScalar.set(RuntimeScalar)`
materializes a non-transferable growing representation at assignment, and
`RuntimeScalar.toString()` is a general Java/value-observation boundary used
by `substr`, hash keys, calls, and other runtime consumers. The tied-hash-key
failure is therefore a representative direct escape, not a missed local
`substr` materialization case.

No closed, general unobserved-result boundary was identified by that audit.
Do not begin another global deferred representation experiment or benchmark.
String remains below parity and needs a new ownership model, but the active
candidate-selection slot moves to the non-overlapping Joni search/match body
until such a String proof exists.

## Next steps

1. **String: retain the completed audit; do not implement yet.**
   The mixed UTF-8/octet leaf was screened and retired. Attribute the two
   materialized concat scalars and the `substr` snapshot in the current
   recurrence as one complete path. Define a conservative representation that
   is selected only after both operands' ordinary warning, tie, overload,
   taint, and byte/UTF-8 behavior has been established. It must fall back
   before a reference, lvalue, alias, observable warning, or dynamic operand
   could observe an intermediate scalar. Do not reintroduce the rejected fused
   concat/substr, assignment-snapshot, mixed UTF-8/octet, or globally deferred
   string-builder variants. The latter leaked through a tied-hash-key value
   boundary before benchmarking. The current audit found no such closed
   boundary, so no String benchmark or representation substitution is active.
2. **Regex: seek a different broad Joni body boundary.** The stack-guard
   elision has two completed reverse-order screens and is retired; preserve
   both artifact roots and do not restart or retry it. The retained matcher
   pool, literal-alternation path, lazy `$&`, and warning-path elision remain
   active. Do not retry capture-free `Region` removal, empty capture maps,
   captureless last-closed-capture query elision,
   cursor publication, direct literal search, another input-offset-map tweak,
   cursor-local matcher-pool eligibility caching,
   the completed default-state and ASCII-map screens, or stack-depth guard
   elision. A successor needs a non-overlapping CPU/allocation budget and its
   own general proof for dynamic patterns, callbacks, `/g`, `pos`, capture
   publication, and Joni find conditions.
3. **Life third: resume at the whole representation/ownership proof, not a
   new trace.** The current generated-body trace is complete; do not restart
   it. Its scalar/list, call-frame, and lexical transport attribution rules
   out a local `setUnsignedWordElement` boxing tweak or a plain void-assignment
   result elision. The attempted scalar-only aggregate cleanup proof passed
   semantics but regressed to 0.62207x and is retired; do not retry it. Seek a
   different whole-array/list or body-lifecycle representation boundary with
   an ordinary fallback. Its proof must cover
   aliases, references, closures, `eval`, debugger visibility, `caller`,
   warning scope, exceptions, callbacks, non-local control flow, reassignment,
   and destructor timing. Only after that proof exists should a focused
   system-Perl regression test and implementation be attempted.
   Implement it across closure boundaries: annotate a proven declaration in
   the enclosing block, inherit only that exact `SymbolEntry.ast()` fact while
   building an anonymous sub's symbol table, and require the source `map`
   producer plus callback and range to be scalar-only before accepting the
   captured seed. Do not claim that a captured array is safe merely because
   the child reads it. Thread the selected slots through normal scope exit,
   conditional cleanup, subroutine exit, explicit return, and eval exception
   cleanup before measuring; a fallthrough-only change is invalid.
   A later source audit also identified the concrete next subproblem: the
   existing `foreachPrimitiveIntegerIterator` only recycles a proven-safe
   implicit `$_` topic, while the Life kernel's `for my $i (0 .. $#grid)`
   takes the ordinary lexical-alias path and materializes a scalar per range
   element. Before changing it, extend the proof to the declared lexical's
   exact declaration identity and reject every reference, closure, dynamic
   call, `eval`, alias, `local`, callback, control-flow, or post-loop
   observation. Preserve the ordinary iterator and lexical restoration path
   as fallback. This is a general foreach representation boundary, not a
   Life-shaped index shortcut; no candidate has been implemented or measured.
4. **For any retained candidate, run the required evidence ladder.** Start
   with a system-Perl oracle and focused JVM/interpreter test, drain a clean
   immutable `make` gate, measure alternating exact-parent pairs on this
   production-like host, and only then run the complete source/JAR-matched
   portfolio. Keep the goal open until every scored workload has a 1.00x
   median and lower confidence bound and the stricter acceptance target above
   is met.

## Do not retry unchanged

- Empty named-capture map reuse, captureless Joni-region elimination, and
  zero-capture cursor pooling regressed despite allocation reductions.
- Joni parsed-program metadata bit-mask checks regressed: same-index
  candidate/parent geometric mean 0.95998x in the reverse-order full check.
- Retaining a native Joni matcher inside an already-published `/g` cursor
  regressed 0.88306x against its exact parent. Keep the existing per-probe
  matcher-pool lifecycle; changing only its publication point is not a viable
  regex lever.
- Literal-pad lock elision was non-repeatable across reversed high-load
  screens. Do not replace the synchronized hit path without controlled-host
  evidence of a material benefit.
- A 64-slot direct front cache for the per-runtime static-regex map was
  correct and isolated, but gained only 1.04785x in the candidate-first,
  parent-reverse high-load comparison. Keep the ordinary map until a broader
  cache boundary clears the 5% selection threshold.
- Eliding `ByteCodeMachine.matchAt`'s plain-matcher `ThreadLocal` execution
  depth guard retained all callback/deferred-property/locale/warning-service
  fallbacks and passed focused plus full gates, but two independently ordered
  seven-pair exact-parent screens combined to only 0.9995x geometric mean.
  Keep the ordinary guard; realistic-load variance does not establish a
  material repeatable benefit.
- Checking the runtime-local static regex callsite cache before the temporary
  lexical-package facade switch preserved focused user-property behavior, but
  its completed exact-parent screen had an unstable candidate warmup and only
  1.0203x all-pair geometric mean. Keep the ordinary package path; do not
  retry this cache-hit elision unchanged.
- Moving a plain scalar's `pos`/`/g` bookkeeping into a runtime-tagged direct
  field preserved cross-runtime isolation and passed the full gate, but its
  seven-pair exact-parent screen was inconclusive (1.11451x, 0.90006--1.38006)
  and a same-host Perl screen was non-accepting (0.97648x,
  0.81880--1.16453). Keep the bounded per-runtime map; direct state changes
  its lifetime shape without a repeatable body-cost win.
- Skipping one-slot Joni `Region` allocation for capture-free matches passed
  the focused group-zero coverage and full gate, but its earlier seven-pair
  exact-parent screen was inconclusive (1.06593x, 0.89930--1.26343). A
  duplicate 2026-09-14 selection screen at
  `/tmp/perf-regex-captureless-region-candidate-20260914/20260914T203622Z/portfolio.json`
  was stable and conclusive but still only 0.76324x median / 0.76327x pair
  geometric mean, versus the 0.76236x current anchor. Keep the uniform capture
  snapshot path; allocation reduction alone does not clear the selection
  threshold under realistic load. Do not rerun this candidate.
- Direct forward discovery for Joni's capture-free literal alternations
  preserved resumed `/g` bounds and passed the full gate, but three high-load
  candidate/parent probes were 1.04973x, 0.96537x, and 0.96986x. Keep Joni's
  generic candidate search; this dispatch shortcut is not repeatable.
- Caching constructor-fixed direct-global-cursor eligibility gained only
  1.04476x against its reverse parent. Keep the direct check; do not trade
  readability for a sub-threshold metadata-cache gain.
- Publishing a shared immutable empty named-capture map avoided a per-match
  allocation and preserved `%+`/`%-` clearing, but its high-load candidate and
  reverse-parent medians were only 0.77383x and 0.76465x respectively (1.01200x
  candidate/parent). Keep the ordinary publication path; the residual matcher
  gap needs a larger body boundary.
- Fusing the unsigned-word store's repeated eligibility probe gained only
  1.01798x against its Life reverse parent. Keep the clearer existing split;
  the remaining Life cost requires a broader representation boundary.
- The refreshed generated-body Life JFR confirms that `setUnsignedWordElement`
  boxes wide words through the ordinary observable scalar setter. This is
  attribution, not authorization for a setter-only fast path: retain scalar
  identity and mutation effects unless a whole representation proof selects a
  general fallback-backed boundary.
- Reusing a non-retaining `for my $i (integer range)` iterator cell was
  semantics-safe behind the existing conservative analyzer, but its stable
  candidate/parent screens were effectively tied (0.66734x vs. 0.66785x Life
  median; 1.01323x geometric-mean ratio). Keep the ordinary lexical range
  iterator; seek a larger body-cost boundary.
- A whole-subroutine-proven private integer-array slot transfer preserved the
  escaped-old-element fallback, but its complete seven-pair high-load
  candidate/parent screen was 0.98764x geometric mean. Keep ordinary list
  assignment; avoiding its temporary scalar copies did not repay the guarded
  container handoff.
- Plain-array void-assignment result elision passed its full gate, but its
  seven-pair exact-parent Life comparison was 0.96696x geometric mean
  (0.76280x--1.06735x). Keep the ordinary result path; this allocation alone
  cannot close the Life gap.
- Plain string-plus-integer concat regressed: 0.85675x geometric mean against
  its exact parent. The retained typed paths are plain UTF-8 string plus plain
  UTF-8 string, byte-string plus byte-string, and byte-string plus integer.
- The guarded plain mixed UTF-8/octet concat path was semantically correct but
  its conclusive candidate and source-distinct parent screens were 0.59118x
  and 0.63247x respectively under different production-like loads. It provides
  no selection-grade lift; keep the generic path and do not retry that leaf.
- Directly storing a two-argument `substr` snapshot into its void-context
  scalar-assignment destination passed its focused JVM/interpreter coverage
  and full gate, but the high-load three-pair selection screen was 0.98830x
  against its exact parent. Keep the ordinary snapshot-and-store path.
- A globally deferred concat string representation was semantically invalid:
  a tied-hash key observed its placeholder value before materialization. Keep
  ordinary `String` storage unless a candidate proves every escape and direct
  value-observation boundary, rather than adding local materialization fixes.
- Naive array-element reuse or ordinary `@a = @b` destination-cell reuse is
  semantically invalid when old elements are referenced.
- Broad call-frame/scalar pooling, ordinary matcher lifecycle removal, static
  regex package-cache bypass, and range-topic reuse lack the required ownership
  proof or were measured regressions.

Detailed rejected-experiment artifacts remain in commit history and their
recorded `/tmp` benchmark paths, not in this handoff.

## Required candidate workflow

1. Rebuild the exact committed source with `timeout 1800 make`; do not mutate
   the checkout until the gate and its children finish.
2. Profile a bounded representative workload and state the affected fraction,
   guards, fallback, expected saving, and semantic proof boundary.
3. Add or strengthen a permanent project-owned regression test. Run new Perl
   tests on system Perl before using them to drive PerlOnJava work.
4. Run JVM and interpreter coverage, then a clean immutable full `make` gate.
5. Measure candidate and exact parent with alternating fresh processes under
   the same protocol. Retain only a material, repeatable gain.
6. After a retained runtime change, run the full portfolio and update this
   document only with the current result and next decision.

## Operational safeguards

- Wrap every `jperl`, `jcpan`, or `prove` invocation in `timeout` and capture
  full output to a file.
- Treat `make` as a shared-JAR writer; never run it beside readers using the
  same worktree JAR and never edit that checkout while it runs.
- High host load is an intentional measurement condition. Record it; do not
  disguise it as quiet-host acceptance evidence.
- Keep this file forward-looking. Put raw logs, full pair tables, and rejected
  candidate chronology in the experiments document.

## References

- [Main performance design](performance-over-perl.md)
- [Profiling workflow](../../.agents/skills/profile-perlonjava/SKILL.md)
