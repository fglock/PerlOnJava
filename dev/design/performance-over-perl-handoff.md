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

The likely opportunity is avoiding transient scalar/list transport inside the
bit-packed recurrence. It is **not** safe to reuse destination array element
cells generally: standard Perl and PerlOnJava both preserve a reference to an
old `@a` element across `@a = @b`. Any transfer/rebind optimization therefore
needs a whole-body, lexical no-escape proof for both arrays, dead-source proof,
and a generic fallback. Do not implement a local shortcut based only on the
Life benchmark shape.

Current disassembly confirms that the final `$next[$i]` expression already
uses native-word operations. The material unlowered boundary is the three
preceding `$left`/`$cell`/`$right` array reads: each still performs generic
index arithmetic, allocates a lexical scalar, resolves its alias, and calls
`addToScalar`. Select only a block-local provenance lowering that can replace
that whole transport sequence while retaining the generic path for every
observable scope.

Before coding, write the proof obligations for aliases, references, closures,
`eval`, debugger visibility, exceptions, destructors, non-local control flow,
and reassignment. Add a permanent focused test and validate it with system
Perl first. Then validate both backends, full `make`, an exact-parent
comparison, and a complete portfolio.

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

Preserve dynamic templates/modifiers, package and warning state, `qr//`
identity, `/g` position, capture state, callbacks, and Joni find conditions.
The retained literal alternation fast path must remain excluded for
`FIND_LONGEST` and `FIND_NOT_EMPTY`.

### 3. String: reduce a representation/ownership boundary

String remains well below the 0.90x floor. Prior attribution reaches
`RuntimeArray.createReferenceWithTrackedElements`, scalar materialization, and
string/substr work. Start with one broadly applicable, semantics-proven
representation boundary. Avoid another typed leaf branch unless profiling shows
its selected fraction and fallback cost can clear a material budget.

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
- Moving a plain scalar's `pos`/`/g` bookkeeping into a runtime-tagged direct
  field preserved cross-runtime isolation and passed the full gate, but its
  seven-pair exact-parent screen was inconclusive (1.11451x, 0.90006--1.38006)
  and a same-host Perl screen was non-accepting (0.97648x,
  0.81880--1.16453). Keep the bounded per-runtime map; direct state changes
  its lifetime shape without a repeatable body-cost win.
- Skipping one-slot Joni `Region` allocation for capture-free matches passed
  the focused group-zero coverage and full gate, but its seven-pair exact-
  parent screen was likewise inconclusive (1.06593x, 0.89930--1.26343).
  Keep the uniform capture snapshot path; allocation reduction alone does not
  clear the selection threshold under realistic load.
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
- Plain string-plus-integer concat regressed: 0.85675x geometric mean against
  its exact parent. The retained typed paths are plain UTF-8 string plus plain
  UTF-8 string, byte-string plus byte-string, and byte-string plus integer.
- Directly storing a two-argument `substr` snapshot into its void-context
  scalar-assignment destination passed its focused JVM/interpreter coverage
  and full gate, but the high-load three-pair selection screen was 0.98830x
  against its exact parent. Keep the ordinary snapshot-and-store path.
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
