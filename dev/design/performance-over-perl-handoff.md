# Performance over Perl handoff

## Start here — authoritative handoff, audited 2026-09-11

**The performance objective is not achieved.** Resume from the latest retained
implementation commit on `wip/performance-preflight-20260909-133542`, not the
older checkpoints below. The source/JAR-matched full high-load baseline and
subsequent localized retained measurements are recorded below. Earlier sections
labelled historical preserve experiment evidence, not the current execution
order. The main design's acceptance contract remains authoritative, but its
chronological progress narrative is also behind the latest implementation.

The next useful deliverable is a **measured call-boundary cost model**, followed
by one independently reversible candidate. The reproducible current baseline
has been collected, but shows substantial deficits rather than parity.
Do not start by consuming the new topic-observation flag. Its implementation
does not yet establish the proof its name suggests. No missing user permission
or priority decision prevents ordinary implementation, profiling, or testing;
the unfinished work is engineering. Success is an experimental result, not a
promise that a particular optimization will reach parity.

### Define 1-to-1 without weakening the target

All ratios here mean **PerlOnJava operations/second divided by standard Perl
operations/second**. Parent/candidate comparisons are separately labelled.
Startup and warmup are excluded: this project does not promise equal CLI
startup latency or parity for all possible Perl programs.

The existing contract below permits an individual non-anchor workload at
0.90x. That is **not literal per-workload 1-to-1**. For this user's handoff,
target every scored workload's median ratio and 95% confidence-interval lower
bound at or above 1.00x, while retaining the existing 1.05x portfolio/anchor
requirements. If its interval crosses 1.00x, parity for that workload remains
unproven. The existing
analyzer's `acceptance.passed` alone cannot certify this stronger objective.
Before declaring completion, add permanent reporter coverage and an explicit
stronger parity gate, without relaxing the existing design gates. Keep the
distinction visible in the final report and reconcile the main design then.

### Current implementation and what is actually supported

| Checkpoint | State at handoff | Evidence limits / next decision |
| --- | --- | --- |
| `6b5cdec6c` fixed one/two-slot fresh lexical unpack | Retained, with LexAlias fallback coverage | Seven parent/candidate pairs: median 1.0495x; not all warmups stable. Do not restore broad unpack lowering. |
| Broad nonempty leaf-frame reuse | Rejected and reverted | Two ratios 0.9459x and 1.0099x; allocation savings did not justify retention. Revisit only with a materially different cost/ownership argument. |
| `5270476f9`, `805736a0f` native JSON eligibility probes | Retained hash/sparse-array existence-before-fetch changes | Hash comparison very noisy; sparse-array follow-up lacks isolated throughput comparison. Not proof of general JSON parity. |
| `c90f88f85` constant-CV early return | Retained | Two JSON parent/candidate ratios 1.1223x, 1.1653x; local selection evidence only. Audit all bypassed call-boundary obligations before widening. |
| Cached hash-exists booleans, documented in `061d128c6` | Rejected and reverted | Ratios 1.0151x, 0.9889x: essentially neutral. Do not repeat unchanged. |
| `cdafea338` generated-CV `doesNotObserveDynamicTopic` | Metadata producer/copying only; no optimization consumer found | Full `make` log reports success in 5m37s. No dedicated proof/selection tests; not a safe effect-analysis contract yet. |
| `2a83a47f3` small negative-literal lowering and `b6c2ef49f3` BMP substring scan | Retained localized string improvements | Seven-pair parent/candidate medians were 1.1274x and 1.0569x respectively. The subsequent loaded-host portfolio raised string to 0.5400x Perl, but is noisy paired evidence rather than an acceptance baseline. |
| `92d5ccf1a` and `bbbbb506d` empty named-capture state reuse | Rejected and reverted twice | Both remove a recurring empty `LinkedHashMap`; the first seven high-load pairs measured 1.0304x median / 1.0483x geometric mean, and the independent `Map.of()` repeat measured 0.9969x / 0.9990x. Neither clears the material-gain bar. |
| `280ae31d1` plain-unblessed concat shortcut | Rejected and removed by `358e319ce` | Seven checksum-matched high-load pairs: 0.9980x median, 1.0191x geometric mean. A large outlier tracked reduced parent CPU service, not a robust gain. Do not retry this leaf shortcut. |
| `fbbff23a0` zero-capture regex cursor pool | Rejected and removed | Seven checksum-matched high-load pairs: 0.9236x median, 0.9558x geometric mean. Pool publication overhead caused a material regression; do not retry this cursor design. |
| `3d36a80a0` native-integer comparison shortcut | Rejected and removed | Seven checksum-matched high-load pairs: 0.9845x median, 0.9798x geometric mean. Avoiding `BigInteger` allocation did not overcome the added type checks. |

The current source after the removal passed the full immutable gate in 4m54s:
`/tmp/make-string-fastpath-rejection-20260912.log` (exit 0). This remains
historical integration evidence, not a replacement for building the exact
checkout on the next machine. Resolve commit IDs with Git before use; if the
branch has advanced, record the new source baseline explicitly.

### Historical measurement debt

The latest available all-workload diagnostic is
`/tmp/performance_current_baseline/20260910T213011Z/portfolio.json`.
It records source `061d128c688b7faed488b113111f1fa119cba4f2`, a clean source
status, and JAR SHA-256
`3b9dd833283541937fb78ed089a0268d8905fd3319224c58454bdd1e0e61ed91`.
This is **not a measurement of `cdafea338`**. There is also an unresolved
source/JAR provenance risk: the hash-exists experiment was reverted in source
before this run, and a rebuild after that reversion has not been established.
A clean Git status plus an independently recorded JAR hash does not prove that
the JAR implements that source. Quarantine this run as triage evidence until
that correspondence is demonstrated; rebuilding and remeasuring is preferable.
The source/JAR-matched full baseline below resolves this as a current-baseline
provenance issue, while retaining this older artifact as triage-only history.

### Resumption build checkpoint (2026-09-11)

The clean committed handoff checkout was rebuilt and gated successfully before
any new benchmark reader was started:

| Field | Value |
| --- | --- |
| Source commit | `f7744a4e2bb0c4d086ae9159d6ff2993f2dfcca9` |
| Gate | `timeout 1800 make`; exit 0; 5m40s |
| Gate log | `/tmp/perf-handoff-make-20260911.log` |
| Launcher SHA-256 | `7f34a9ee9c0acbd3d37ce43a63699feef46e486f8831dfe6edadc2be3e1f4092` |
| Launcher-selected JAR | `target/perlonjava-5.44.1.jar` |
| JAR SHA-256 | `f2d60be188dc4eede53d91ffd1d0c98886c70a12132a31ecaef966226ecf530d` |
| Java | Temurin 24.0.2+12 |
| Reference Perl | 5.42.2, `darwin-thread-multi-2level` |

No throughput measurement accompanied this checkpoint. At observation, host
load averages were 24.65/49.16/41.20 with unrelated system, Zoom, and browser
CPU consumers. A two-pair diagnostic or baseline under that contention would
not by itself resolve the existing measurement debt. A later seven-pair
acceptance baseline must retain the fresh host state and its quality label;
the user has requested that current high-load measurements be collected rather
than deferred.

### High-load closure/method diagnostic (2026-09-11)

The host is intentionally used under realistic contention. A two-pair
alternating fresh-process diagnostic completed with matching semantic checksums
and stable warmup for every engine/workload run. It is protocol-inconclusive
because it has two pairs, not seven; it is selection evidence only.

| Field | Value |
| --- | --- |
| Source commit | `04ebbb7831b1b54a10f02bf697c3440efa8b5e8b` |
| Artifact | launcher `7f34a9ee9c0acbd3d37ce43a63699feef46e486f8831dfe6edadc2be3e1f4092`; JAR `f2d60be188dc4eede53d91ffd1d0c98886c70a12132a31ecaef966226ecf530d` |
| Command | `timeout 1800 perl dev/bench/run_performance_portfolio.pl --workload closure --workload method --pairs 2 --output-dir /tmp/perf-handoff-highload-triage-20260911` |
| Host state in artifact | load averages 15.67/31.42/35.45 |
| Closure median | 0.2456x Perl (pair ratios 0.2338x, 0.2573x) |
| Method median | 0.2224x Perl (pair ratios 0.2275x, 0.2172x) |

The analyzer correctly labels this report `inconclusive` and rejects
acceptance because the protocol is not compliant; its two-workload geometric
mean is 0.2337x Perl. This current, source/JAR-matched diagnostic confirms the
closure and method call boundary remain far from 1-to-1 even when each warmup
is stable under load. The closure's exact empty `$f->()` calls already reuse
the runtime-local empty `@_` array; therefore, a follow-up must target the
remaining common call-frame lifecycle or a separately attributed generated
body cost, with a conservative ownership/effect proof. Do not claim a speedup
against historical JSON or quiet-host measurements.

It used one pair, 15 warmup windows maximum and 15 measurement windows. These
are noncompliant settings; the analyzer requires at least two pairs even to
summarize input. Do not duplicate pairs to make it accept this file.

| Workload | Historical diagnostic ratio | Improvement needed to reach 1.00x from that ratio |
| --- | ---: | ---: |
| closure | 0.2261x | 4.42x (4.64x for the 1.05x anchor) |
| method | 0.2155x, unstable PerlOnJava warmup | 4.64x, tentative only |
| string | 0.3913x | 2.56x |
| life | 0.4880x | 2.05x (2.15x for the 1.05x anchor) |
| regex | 0.5359x | 1.87x |
| numeric | 1.2521x | Preserve and revalidate |
| json | 2.5306x | Preserve and revalidate |

These figures justify investigating closure/method first, not declaring JSON
finished or claiming a current speedup. Benchmark the bundled/native JSON path
fairly: record module versions, loaded paths, options, selected implementation,
and checksums for both engines. A fast canonical native path does not establish
the performance of arbitrary JSON::PP options or its fallback parser.

### Full high-load portfolio baseline (2026-09-11)

The requested default seven-pair, seven-workload portfolio completed under
realistic host contention. The analyzer labels it `protocol_compliant: true`,
`conclusive: true`, and measurement quality `stable`; semantic checksums and
warmup stabilization passed under the portfolio's validation. This is a valid
current baseline for the exact runtime source/JAR, but it **fails** both the
existing portfolio acceptance threshold and the stronger 1-to-1 objective.
High load is a documented measurement condition, not a claim that a quiet-host
acceptance run was performed.

| Field | Value |
| --- | --- |
| Measured source commit | `85833b1fcd2203890fda025b6fc9208a41e2a619` (clean) |
| Runtime build source | `f7744a4e2bb0c4d086ae9159d6ff2993f2dfcca9`; the intervening commits modify only this handoff document |
| Command | `timeout 14400 perl dev/bench/run_performance_portfolio.pl --output-dir /tmp/perf-handoff-highload-baseline-20260911` |
| Configuration | 7 pairs; 10–60 warmup windows; 15 × 1-second measured windows; 180-second per-reader timeout |
| Host state in artifact | Darwin arm64; load averages 9.46/19.42/28.40 |
| Engine artifact | launcher `7f34a9ee9c0acbd3d37ce43a63699feef46e486f8831dfe6edadc2be3e1f4092`; JAR `f2d60be188dc4eede53d91ffd1d0c98886c70a12132a31ecaef966226ecf530d` |
| Portfolio artifact | `/tmp/perf-handoff-highload-baseline-20260911/20260911T082733Z/portfolio.json` (`bb485bdd09da38a2fb22e0cc68c217b2ac8e851144f64a7bc5272168765cd9fa`) |
| Analyzer artifact | `analysis.md` (`ea6496ffc92fd71d4132f94071da95c470ab8393c7be8d6ae73a274ad8031fe8`) |
| Portfolio geometric mean | 0.5647x Perl, 95% CI 0.5456–0.5818; acceptance rejected because it is below 1.05x |

| Workload | Geometric mean ratio | Median ratio | 95% CI |
| --- | ---: | ---: | ---: |
| closure | 0.2256x | 0.2335x | 0.2166–0.2334x |
| method | 0.2158x | 0.2138x | 0.2015–0.2317x |
| numeric | 1.2184x | 1.2257x | 1.1940–1.2393x |
| string | 0.4300x | 0.4226x | 0.4210–0.4406x |
| regex | 0.5775x | 0.5782x | 0.5684–0.5870x |
| life | 0.5340x | 0.5379x | 0.5220–0.5450x |
| json | 2.2910x | 2.2782x | 2.2672–2.3175x |

Closure and method are the limiting workloads, both near 0.22x Perl with
non-overlapping confidence intervals far below 1.00x. Numeric and JSON are
already above the stronger 1.00x lower-bound target; do not trade their
correctness or performance for a closure-specific shortcut. The next phase is
to produce an exclusive steady-state CPU/bytes-per-operation budget for closure
and method separately, then select a general call-boundary reduction with a
conservative ownership/effect proof. In particular, the closure's zero-argument
calls already reuse the runtime-local empty `@_`; do not reattempt empty-array
reuse or consume `doesNotObserveDynamicTopic` as an effect proof.

### Closure/method call-boundary attribution (2026-09-11)

The next-step attribution run completed seven fresh pairs each for closure and
method with JFR plus call-layer diagnostics enabled. It is source-clean at
`5053300019276de44d7f386b1535c13ad8ac3f83`, protocol-compliant, conclusive,
and stable, but it is intentionally a two-workload profiling run and therefore
cannot pass the complete-portfolio acceptance check. Its timing ratios (closure
0.1652x, method 0.1874x) include JFR and diagnostic overhead and are **not**
compared to the non-JFR baseline.

| Field | Value |
| --- | --- |
| Command | `timeout 7200 perl dev/bench/run_performance_portfolio.pl --workload closure --workload method --jfr --call-layer-diagnostics --output-dir /tmp/perf-handoff-highload-attribution-20260911` |
| Host state in artifact | Darwin arm64; load averages 5.06/5.05/7.56 |
| Portfolio artifact | `20260911T091846Z/portfolio.json` (`7fc1f4eecf8f007fa5fed982d6affeb974a65e63408a9f7e03ee49bc9623512a`) |
| Analyzer artifact | `analysis.md` (`0fe2174e28333c267b3b99a08a0fe9547e8986bbe09f10d421542c922af63c8e`) |
| JFR summary, closure | 7 recordings; 270 GCs; 0.376 s aggregate / 5.45 ms longest pause; 29,976 allocation samples |
| JFR summary, method | 7 recordings; 378 GCs; 8.167 s aggregate / 302.5 ms longest pause; 51,608 allocation samples |

The call-layer counters are diagnostic-only and weighted here by their reported
operation counts. They measure the shared general lifecycle, not a
closure-specific lowering:

| Workload / common category | Operations | Inclusive ns/op | Exclusive ns/op | Inclusive B/op | Exclusive B/op |
| --- | ---: | ---: | ---: | ---: | ---: |
| closure / named-args instance apply | 446,562,522 | 1,023 | 410 | 532 | 269 |
| method / shared-args instance apply | 235,707,677 | 1,733 | 540 | 1,932 | 437 |
| method / named-args instance apply | 7,256,941 | 47,957 | 6,994 | 59,614 | 15,979 |

The low-count `shared-args-static-facade` category and the diagnostic-token
allocations are excluded from candidate selection: their large apparent costs
are startup/compiler-heavy or instrumentation-only. The JFR allocation samples
corroborate real transport pressure (`RuntimeScalar`, `RuntimeArray`, backing
arrays, and `RuntimeList`), but sample weight is not an exclusive allocation
budget.

Separate steady-state async-profiler CPU captures used a forced 60-second
warmup and a 60-second measurement workload, with a 35-second CPU attachment.
The closure capture contained 3,579 samples: `invokeWithCallFrame` was present
in 3,510 (98.1%) inclusive stacks, but only 84 (2.35%) exclusive samples;
`popArgs` accounted for 82 (2.29%) exclusive samples. The method capture
contained 5,879 samples: `invokeWithCallFrame` appeared in 3,347 (56.9%)
inclusive stacks, while direct exclusive samples were distributed across
`MortalList.deferDecrementIfTracked` (3.6%), `enterCall` (2.3%),
`materializeLiteralPad` (1.8%), `isCurrentArgumentAlias` (1.7%), and
`methodArgsWithSelf` (1.0%). The corresponding collapsed CPU artifacts are
`/tmp/perf-handoff-closure-async-cpu.collapsed`
(`8e10250a6484887d6a19bf2e07d9a359a8db3fbddf54545de752eb67f280877b`)
and `/tmp/perf-handoff-method-async-cpu.collapsed`
(`653fb15c515a659f40d64fbf7e8cf2013ff3c7c304ba4ae15653631d41f6b9b7`).

The follow-up HotSpot compilation/inlining captures used the same forced
60-second warmup/60-second workload shape, with
`-XX:+LogCompilation -XX:+PrintCompilation -XX:+PrintInlining`. Both completed
under their 180-second timeout. `invokeWithCallFrame` (370 bytecodes) and
`invokeCallable` reach C2 level 4 in both captures; the method capture also
reaches C2 level 4 for `methodArgsWithSelf` and `applyCachedMethod`. The shared
boundary is therefore not awaiting JIT promotion. Its large body still rejects
some general setup callees for inlining (`enterCall`, 250 bytecodes, and
`getWarningBitsForCode`, 128 bytecodes), but a forced-inlining tweak would not
by itself meet the measured 10% anchor gate. The raw compilation logs are
`/tmp/perf-handoff-closure-hotspot.xml` (32 MB) and
`/tmp/perf-handoff-method-hotspot.xml` (37 MB); the closure/method logs contain
80/57 process-wide deoptimization records respectively, so no individual
deoptimization is attributed to a candidate without a focused proof.

This completes the JFR/call-layer, async CPU/allocation-selection, and JIT
activation evidence for the current source, but it does **not** justify a
production change yet: the direct helpers are individually below the 10%
anchor CPU gate. Next derive a non-overlapping Amdahl budget and a conservative
ownership/effect proof for a structural frame reduction; retain the generic
path unless aliasing, caller, dynamic-warning, closure-lifetime, control-flow,
and lvalue ownership are all proven. If no qualifying common case remains,
record the rejection and move to the next independently attributed cost rather
than adding a closure-only shortcut.

The first independently checked method helper is rejected. The async capture's
3.6% `MortalList.deferDecrementIfTracked` exclusive CPU was reached through
`deferDecrementIfNotCaptured` while the workload creates a fresh blessed method
object. The sampled paths perform real selective-owner release and, in the
largest leaf stack, queue a deferred base release; they are not a redundant
inactive-lifecycle guard. Even a hypothetical complete removal has a maximum
method gain of about 1.037x, far short of the 4.6x gap. Do not weaken
`DESTROY`/weak-reference/refcount cleanup for this workload; continue with a
non-overlapping structural call-frame budget and an ownership proof.

### Retained: reuse string-concat blessing eligibility (2026-09-11)

The high-load string CPU capture identified `RuntimeScalarType.blessedId` as
936 of 3,509 exclusive async-profiler samples (26.7%), reached from the
warning-aware string-concatenation overload check. That path had already
obtained each resolved operand's effective blessing identity to decide binary
overload dispatch, then immediately repeated the same two queries solely to
decide whether stringification overload handling was needed. The new narrow
path reuses those two identities in `stringConcatWarnUninitialized`; tied
operands are still fetched first, overloaded operands still dispatch through
`OverloadContext`, and the general helper remains for all other callers.

`string_concat_bless_id_fastpath.t` passes standard Perl and both PerlOnJava
backends, covering ordinary values, string overload, and a tied scalar whose
`FETCH` must run exactly once. The candidate full immutable `make` gate passed
in 4m07s; the detached exact parent (`aa5d3eb3b`) passed in 3m51s. Seven
alternating fresh-process candidate/parent string pairs under host load
averages initially near 9.81/12.91/11.85 produced ratios of 1.1107x, 1.0579x,
1.1554x, 1.0638x, 1.0784x, 1.0882x, and 1.0688x (median 1.0784x); every
engine warmup stabilized. Raw evidence is
`/tmp/perf-string-parent-candidate-20260911.json`
(`eb5e148fe302d1021a80eadbb4fb7234d5f628c4ba3e9f5cb9eb27a0fea564a4`).
This is a localized A/B retention result, not portfolio acceptance: applied
to the current 0.4300x string baseline it projects only about 0.464x Perl.
Recollect the complete portfolio after integrating several independent
material improvements; do not overstate this as string parity.

A separate forced-60-second-warmup/60-second candidate capture confirms that
the remaining string-side blessing samples are no longer a reason to repeat
the same change: 429 of 3,503 samples (12.2%) came directly from the retained
two eligibility queries in `stringConcatWarnUninitialized`; the rest of the
aggregate `blessedId` samples are principally unary-minus overload checks.
The next visible costs are dynamically scoped warning/bytes-state lookup via
`PerlRuntime.current()`/`ThreadLocal.get` and ordinary string/substr work.
Do not elide warning or bytes lookup merely from static source appearance:
the runtime deliberately supports lexical-state changes through dynamic
compilation. The raw candidate profile is
`/tmp/perf-handoff-string-post-async-cpu.collapsed`
(`2c2a8ae1a4025ae859b786074e6a8bec037fa50b81a610b35777f05e4ba4f7da`).

### Retained: lower small negative integer literals (2026-09-11)

The same post-change string profile attributed 348 samples to generic
`MathOperators.unaryMinusWarnUnpropagated`, primarily for the constant `-24`
substring offset in the workload. A positive small integer literal is a raw
`NumberNode` only when the parser has not rewritten it through
`overload::constant`. The JVM emitter now lowers that narrow case directly to
the already-cached immutable negative integer literal, bypassing unnecessary
unary-overload eligibility and warning machinery. Non-integer, zero, large,
and `overload::constant`-rewritten operands retain the existing generic path.

The permanent `unary_minus_literal_fastpath.t` covers the workload-shaped
offset, an underscored literal, and value preservation. It passed standard Perl
and both PerlOnJava backends. The candidate's immutable full `make` gate passed
in 3m51s, while an independently built detached immediate parent at
`c5ef17a6d` passed in 4m10s. Seven alternating fresh-JVM string pairs under
load averages 6.45/7.37/8.96 all favored the candidate: 1.1852x, 1.1431x,
1.1258x, 1.1367x, 1.1274x, 1.1147x, and 1.1148x candidate/parent median
throughput (median 1.1274x; geometric mean 1.1352x). Each pair required the
same semantic checksum. Raw evidence is
`/tmp/perf-negative-literal-parent-candidate-20260911.json`
(`ee7c9d5651ddb4b98b6bca693339bcdd765f658565c21b77f680f8b30c34b889`).
This is a localized retention result, not a new portfolio measurement or a
claim of parity. The next profile should rerank the candidate string artifact
before selecting another independent cost; do not extrapolate the paired gain
to every workload.

### Retained: direct BMP substring-offset scan (2026-09-11)

The next high-load CPU capture ranked
`PerlUtfString.scanOffsetByPerlCodePoints` among the visible string-workload
leaves. Its former loop constructed a `PerlStep` for every ordinary UTF-16
code unit while locating `substr` offsets. The new scan advances directly over
code units below the surrogate range, which are each exactly one Perl logical
character. At the first surrogate or internal-marker lead it falls back to the
unchanged general decoder, preserving supplementary scalars, unpaired
surrogates, and product-codec markers.

`substr_bmp_offset_fastpath.t` passes standard Perl and both PerlOnJava
backends, covering the workload-shaped ASCII negative offset, BMP offsets, and
supplementary-character boundaries. The candidate immutable full `make` gate
passed in 4m07s. The exact immediate-parent source `2a83a47f3` had previously
passed its primary-checkout full gate in 3m51s. Its detached-worktree rebuild
produced the benchmark JAR but failed the path-sensitive existing `unit/cwd.t`;
that environmental failure is not used as integration evidence. Seven
checksum-matched alternating fresh-JVM pairs nevertheless compared the exact
parent and candidate artifacts under load averages 6.31/7.29/8.78 and all
favored the candidate: 1.0809x, 1.0381x, 1.0404x, 1.0569x, 1.0714x, 1.0394x,
and 1.1095x candidate/parent median throughput (median 1.0569x; geometric
mean 1.0621x). Raw evidence is
`/tmp/perf-substr-bmp-parent-candidate-20260911.json`
(`fbe1641849e4d6df1b9023043f1e4356424d316c820ca0abc2b339bb9b7a4d25`).
This remains localized string evidence rather than a portfolio claim. Profile
the rebuilt candidate before choosing another target; do not bypass the
general Unicode decoder outside this proven direct-BMP scan.

### Post-retained full portfolio under realistic load (2026-09-11)

After both retained string changes, the default seven-pair, seven-workload
portfolio completed successfully. Every process had a matching semantic
checksum, stabilized warmup, and remained inside its 180-second timeout. The
runner records the source as clean `b6c2ef49f3a24535b866c9ca7bc132d9e7586104`.
The selected JAR SHA-256 was
`accfb817d9543690c3da65a4b7f038598d0bfb012b701f4d22868af54423c057`.
Its embedded generated build metadata predates the source commit, so retain
the artifact hash and source record together; do not describe this as a fresh
source/JAR-provenance acceptance baseline.

The host deliberately remained under realistic contention (artifact load
averages 5.40/6.67/8.36). Consequently the portfolio marks itself
`protocol_compliant: true` but `conclusive: false`; analyzed with
`--allow-noisy-host`, its quality is `noisy-paired`. It is not authoritative
positive evidence, but it is a decisive negative result: its upper overall
95% bootstrap bound, 0.6032x Perl, remains far below parity.

| Field | Value |
| --- | --- |
| Command | `timeout 3600 perl dev/bench/run_performance_portfolio.pl --output-dir /tmp/perf-handoff-post-bmp-20260911` |
| Portfolio artifact | `/tmp/perf-handoff-post-bmp-20260911/20260911T111722Z/portfolio.json` |
| Analysis artifact | `/tmp/perf-handoff-post-bmp-20260911/20260911T111722Z/analysis.json` |
| Overall geometric mean | 0.5839x Perl, 95% CI 0.5713–0.6032 |
| Minimum workload median | method, 0.2170x Perl |

| Workload | Geometric mean ratio | Median ratio | 95% CI |
| --- | ---: | ---: | ---: |
| closure | 0.2305x | 0.2293x | 0.2251–0.2360x |
| method | 0.2175x | 0.2170x | 0.2143–0.2207x |
| numeric | 1.2270x | 1.2380x | 1.1991–1.2530x |
| string | 0.5400x | 0.5279x | 0.5145–0.5701x |
| regex | 0.5554x | 0.5566x | 0.5434–0.5675x |
| life | 0.5169x | 0.5124x | 0.5073–0.5294x |
| json | 2.4973x | 2.4949x | 2.4446–2.5482x |

The string result moves materially above the earlier loaded-host baseline's
0.4300x, consistent with the localized retained changes, but differences in
host state and evidence quality make that an observation rather than a
causal portfolio claim. Method and closure remain the largest deficits.
Return to the recorded call-boundary cost model; do not spend another cycle on
minor string leaves before selecting a structural, independently reversible
call-boundary reduction with an explicit ownership proof.

### Rejected: empty named-capture map reuse (2026-09-11)

A post-warmup 121-second JFR capture of the regex workload under load recorded
8,547 execution samples and 35,367 allocation samples. Filtering from sixty
seconds after recording start selected
`RuntimeRegex.updateLastNamedCaptureGroups`: a successful plain regex match
allocated a fresh empty `LinkedHashMap` even though `%+` and `%-` can only
observe an empty map. The narrow candidate replaced that empty state with
`Collections.emptyMap()` while leaving the named-capture construction path
unchanged. Its six-assertion `%+`/`%-` reset regression passed standard Perl,
JVM, and interpreter; the candidate full `make` gate passed in 5m12s.

The exact parent was `c1c820f70`; its detached-worktree build produced the
parent JAR but failed only the known path-sensitive `unit/cwd.t`, while the
same source had passed the primary-checkout full gate. Seven checksum-matched
fresh-JVM pairs used 10--60 warmup windows and 15 one-second measured windows
for each JAR. All warmups stabilized, but host load averaged 15.43/19.94/21.04
and the gain was not material: candidate/parent ratios were 1.0304x, 1.0862x,
1.2870x, 1.0119x, 0.9105x, 1.0714x, and 0.9781x (median 1.0304x; geometric
mean 1.0483x). The raw artifact is
`/tmp/perf-regex-empty-named-parent-candidate-20260911.json`
(`7086fef7faceb5e717f6eecd7aa4c36c6a08594125da5c844a07521371719fa1`).

Revert the candidate: a few percent on a noisy host, including two regressions,
does not meet the structural 10%-anchor selection gate or justify carrying a
micro-fast path. The next regex investigation should quantify the larger
steady-state `JoniRegexPattern.JoniRegexMatcher` wrapper allocation (5,610
filtered JFR samples) and its ownership constraints; do not alter matcher
pooling merely because that wrapper is frequent.

### Repeat rejection: immutable empty named-capture map (2026-09-12)

The fresh current regex JFR capture selected the same allocation site again:
8,402 sampled `JoniRegexPattern$JoniRegexMatcher` wrappers remained the larger
opportunity, while `updateLastNamedCaptureGroups` accounted for 1,730 sampled
empty-map allocations. A deliberately narrow repeat candidate (`bbbbb506d`)
reused `Map.of()` only after a successful match whose named-group metadata was
empty. It retained the named and provisional-capture paths and added a
five-assertion `%+`/`%-` empty-state and named-capture regression. The test
passed system Perl; the exact candidate full `make` gate passed in 4m21s.

The exact parent was `942bba904`; its isolated full `make` gate passed in
3m34s. Seven checksum-valid (`1024`) fresh-JVM pairs used the standard
10--60-second warmup window and fifteen one-second measured windows. The
parent portfolio recorded host load 8.83/11.79/10.98 and the candidate 6.04/
7.56/9.12. Candidate/parent ratios were 0.972896x, 0.992059x, 1.056220x,
0.998259x, 0.980033x, 0.998615x, and 0.996910x (median 0.996910x;
geometric mean 0.998980x). Raw portfolios are
`/tmp/perf-regex-empty-named-parent-20260912/20260912T024133Z/portfolio.json`
and
`/tmp/perf-regex-empty-named-candidate-20260912/20260912T024830Z/portfolio.json`.

Reject and do not repeat this empty-state allocation change again. The
measurements show no throughput benefit despite the allocation removal; resume
only with a materially different, ownership-proven reduction of matcher-wrapper
or regex-state lifecycle cost.

### Loaded-host Life allocation selection (2026-09-11)

The rebased PR head was profiled for Life with 60 one-second warmup windows
and 60 measured windows under the same realistic host contention. The
121-second recording at `/tmp/perf-life-post-rebase-20260911.jfr` completed
successfully (171 execution and 34,853 allocation samples); the post-warmup
portion contains 59 execution and 17,712 allocation samples. CPU sampling is
therefore directional only: `ThreadLocalMap.getEntry` has 21 samples and
`RuntimeScalar.getLong` has 10. Allocation selection is decisive: dynamic
integer results account for the leading sites, including 8,255 sampled
`RuntimeScalar` allocations from `RuntimeScalarCache.getScalarInt(long)` and
3,826 in the generated Life body. The full stacks identify numeric bitwise
results (`xor`, `and`, `or`, and shifts), plus range-topic scalars; a further
`Long` boxing sample comes from `RuntimeScalar(long)`.

These results are not evidence that widening the small-integer cache is safe:
Life's values are dynamic, often outside its range, and must remain writable.
Nor is a general temporary-scalar pool safe: operator results can escape via
assignment, arguments, references, control flow, or `DESTROY`. The next Life
candidate must instead establish a narrow non-escaping generated-expression
representation with an explicit fallback and standard-Perl ownership tests.
Do not claim a timing improvement from this JFR capture.

### Rejected: fused six-term integer addition chain (2026-09-11)

A post-warmup closure JFR selected `MathOperators.addWarnUnpropagated` as the
largest remaining body-local CPU site (465 samples), ahead of the generic call
boundary helpers. The candidate evaluated all six source operands in their
ordinary scalar contexts, then fused a left-associated six-term addition only
when every result was an untainted fixed-width integer; wide integers, strings,
taint, overload, and all other inputs replayed the ordinary left-associated
operator chain. Standard Perl, JVM, and interpreter regression coverage passed,
as did the candidate full `make` gate in 4m35s. A candidate JFR confirmed
activation: the former `addWarnUnpropagated` hotspot was absent after warmup.

The allocation/CPU removal was not a material throughput result. The exact
parent `c7ba4a470` passed a separate full gate in 4m21s. Eight alternating
fresh-JVM parent/candidate pairs used the same closure workload, 15 one-second
measurement windows, and 30 or 60 warmup windows. Excluding one parent and one
candidate run whose warmup did not stabilize, six checksum-matched pairs gave
1.0745x, 1.0020x, 1.0104x, 1.3285x, 1.0516x, and 1.0757x candidate/parent
median throughput (median 1.0631x; geometric mean 1.0854x). The 1.3285x
outlier coincided with visible late-window host contention; it cannot justify
retention. Revert the fused chain and its regression. Future closure work must
reduce a larger, independently proven call-boundary cost rather than a single
arithmetic expression leaf.

### Closure scalar-result ownership check (2026-09-11)

The return-list wrapper remained prominent in the post-fusion closure JFR, so
the exact opt-in scalar-result counters were run on the source-matched parent
JAR rather than treating sampled `RuntimeCode.returnList` frames as proof of a
leak. Across a stabilized ten-warmup/ten-window closure diagnostic they record
67,935,259 pool hits and exactly as many successful recycles, with 527,331
initial pool misses and 526,799 ordinary-list rejections (0.77% of 68,462,058
scalar extractions); there were no multi-element rejections. The raw report is
`/tmp/closure-scalar-result-diagnostics-20260911.json`.

Therefore a general result-wrapper pool or recycle widening is not the next
closure target: nearly all eligible wrappers already complete the intended
lifecycle. `returnList` still participates in required scalar/list, lvalue,
copy, and IO-owner boundary handling. A future direct scalar-return ABI needs
an explicit proof for those boundaries and must not be justified merely by this
sampled frame or by the pool-miss count.

### Rejected: generated-CV warning-bit cache (2026-09-11)

The call-boundary audit identified the per-call JVM CV warning-bit lookup as a
strictly semantic-preserving candidate only when cached by both the active
compilation state and generated implementation identity; that retains
reset/rebinding and lazy-replacement behavior while avoiding a method-handle
class-name plus registry lookup on a hot call. A focused repeated-callee
warning-scope regression passed standard Perl, JVM, and interpreter execution,
and the candidate's full `make` gate passed under the loaded host in 15m03s.

Its source-matched parent/candidate closure comparison does not meet the
retention bar. The first 45-second pair had matching checksum `9216` but an
unstable parent warmup, so its apparent 1.60x ratio is excluded. The longer
60-second warmup pair stabilized on both sides with the same checksum and
medians of 3,328,925.584 versus 3,399,103.167 operations/s: 1.0211x
candidate/parent. This is below the 10% anchor gate and is not retained.
The raw logs are `/tmp/perf-warning-bits-cache-{parent,candidate}-{1,2}-20260911.log`.
Future call-boundary work should select a larger independently attributed
structural cost rather than retrying the same registry lookup cache.

### Candidate: guarded direct leaf integer-addition closure call (2026-09-11)

The next closure experiment retains the generic `RuntimeCode.apply` path by
default, but marks only generated anonymous closures whose entire body is a
positive-integer addition tree over captured scalar lexicals. A zero-argument
scalar call then uses a direct helper only while every captured scalar remains
an exact, untainted, unblessed integer and the CV is not lvalue-capable or
aggregate-capturing. Every other call falls back to `apply`, including
overloaded/blessed operands and closures that observe `caller` or `@_`.
The permanent regression covers captured-value mutation, overloaded addition,
caller identity, and argument observability; it passed standard Perl, JVM, and
interpreter execution. The exact candidate commit `d7c5a8ea0` also passed a
fresh full `make` gate.

A source-matched parent/candidate closure comparison established one valid
stable pair with checksum `9216`: 3,343,412.272 versus 6,381,115.538
operations/s (1.9086x candidate/parent). Two shorter pairs were excluded for
unstable parent or candidate warmup, so this is promising selection evidence,
not a completed localized retention protocol. Call-layer diagnostics confirm
selection: generic anonymous-CV `apply` counts fall to the outer-window calls,
rather than one invocation for each of the inner 128 leaf calls.

The resulting exact-commit full high-load portfolio completed successfully at
`/tmp/perf-direct-leaf-portfolio-20260911/20260911T144715Z/portfolio.json`.
Its source status was clean at `d7c5a8ea0`, its JAR SHA-256 was
`e82600707d7f5ea76b0a56cc8ee7e8509839243eb0928842c397152707ac7fbc`, and
the host reported load averages 12.80/19.89/34.98. All 49 pairs had matching
semantic checksums and completed inside their 180-second limit. The host
contention correctly left the portfolio `protocol_compliant: true` but
`conclusive: false`; the analyzer labels it `inconclusive`, so it is not an
authoritative acceptance baseline. Its geometric mean was 0.6397x Perl (95%
CI 0.5332--0.6600), with workload medians: closure 0.4759x, method 0.2082x,
numeric 1.2653x, string 0.5292x, regex 0.5598x, Life 0.4880x, and JSON
2.4185x. This is a decisive negative high-load result for the overall goal,
not evidence to claim parity or general portfolio improvement.

Before retaining this candidate for the PR, collect additional source-matched
parent/candidate closure pairs with stable warmup, then use a quiet or less
contended host for an authoritative complete-portfolio comparison. Do not
weaken the guards or extend the AST contract merely to raise the microbenchmark;
the existing fallback is part of the semantic proof.

That follow-up ran seven alternating parent/candidate pairs with a fixed 60
one-second-window warmup and 15 measured windows
(`/tmp/perf-direct-leaf-7pairs-retry-20260911/`). All fourteen processes exited successfully and
every pair retained checksum `9216`, but all parent warmups and six candidate
warmups were unstable under the current host load. Their raw candidate/parent
median ratios were 2.1314x, 1.9962x, 1.9131x, 1.9284x, 2.1089x, 2.7734x, and
2.0499x, respectively. This consistent directional signal does not override
the warmup gate: there are still zero eligible pairs. Preserve the candidate
locally for a quieter rerun; do not push or describe it as retained performance
evidence from this loaded host.

### Method call-boundary selection refresh (2026-09-11)

A one-pair method JFR diagnostic at the clean direct-leaf candidate recorded
77 seconds at
`/tmp/perf-method-direct-leaf-profile-20260911/20260911T161210Z/method-pair-01.jfr`.
It has 315 execution and 14,975 allocation samples; timing from this
instrumented one-pair run is not a throughput comparison. Filtering to the
final post-warmup interval ranks `ThreadLocalMap.getEntry` first (15 samples),
then fresh `RuntimeScalar` refcount transport (6), blessing lookup (5), and
`MortalList`/dynamic-variable cleanup (4 each). Full stacks show the
ThreadLocal lookup serves signal delivery, warning-bit scope, current argument
alias checks, `pos`, localization and global-alias state. It is therefore not a
single cacheable operation and must not be bypassed with static generated-CV
metadata.

The same post-warmup stacks repeatedly cross `RuntimeCode.callCached`,
`applyCachedMethod`, and `invokeWithCallFrame` before fresh method-argument
assignment. Continue by deriving one non-overlapping, semantics-preserving
method frame/argument transport reduction with a generic fallback. Preserve
the cleanup mark, invocation hold, fresh aliased `@_`, caller/warning scope,
signal checks, debugger hooks, non-local return behavior, and `DESTROY`
ownership; no one sampled helper proves any of those can be removed.

An opt-in, fixed-60-window call-layer run gave the required Amdahl bound. Its
warmup was unstable and its rate is not timing evidence, but its checksum was
`4352` and the high-volume shared-argument anonymous-CV category recorded
72,113,991 calls: 127.7 ns setup versus 1,978.8 ns inclusive cost per call
(722.2 ns exclusive; 416.2 exclusive allocated bytes). Thus eliminating all
currently measured generic frame setup could recover under 7% of this path,
below the 10% anchor gate. Do not implement a one-argument method-frame
micro-fast-path merely because the emitter already passes a single
`RuntimeBase`; the frame's aliased `@_` remains required and the available
budget is too small. Select a body-level or broader transport cost instead.

Streaming post-warmup allocation attribution from the same 77-second method
recording identifies the broader transport candidate: 539 sampled allocations
weighing 2.32 GB originate in `RuntimeCode.methodArgsWithSelf`, plus 1,264
`RuntimeScalar` samples weighing 5.42 GB in the generated method body and 710
weighing 3.04 GB in range iteration. The allocation weights are selection
evidence, not exact byte accounting. A method frame cannot be globally pooled:
Perl requires fresh aliased `@_`, debugger/caller support retains a pristine
frame, and a callee can mutate, capture, return, or re-enter through it. The
only plausible frame-reuse experiment is an explicitly marked JVM method whose
sole `@_` access is immediate copying into fresh lexicals and whose remaining
body cannot observe, mutate, or retain the frame; it must acquire a nested
per-runtime frame, keep the full `RuntimeCode.apply` lifecycle, and fall back
for every unproven case. Establish that AST/effect contract and permanent
standard-Perl tests before implementing it.

### Candidate: nested reusable immediate-unpack method frame (2026-09-11)

The allocation evidence above now has one deliberately narrow implementation
candidate. The JVM emitter marks only a CV with exactly one syntactic `@_`
reference when its first statement is `my ($scalar, ...) = @_`; the target
lexicals must be non-empty, distinct scalar names. At cached Perl-method
dispatch, and only for a one-scalar actual argument with debugging disabled,
the runtime borrows a two-slot frame from an execution-runtime-local pool.
The frame remains an aliased `@_` frame and still goes through the normal
`RuntimeCode.apply` push/pop, caller, warning, signal, exception, control-flow
and cleanup lifecycle. Recursive calls cannot share a live frame: `popArgs`
returns it to the pool only after the active argument-frame depth is removed.

Every nonmatching method, multiple-argument call, debugger invocation, and
CV with another syntactic `@_` observation retains the ordinary fresh-frame
path. The marker is copied through CODE cloning/rebinding. The permanent
`reusable_method_argument_frame.t` regression proves standard-Perl behavior
for repeated calls, nested recursion, and an `$_[1]` mutation fallback; it
passes standard Perl and both PerlOnJava backends. The exact source candidate
also passed `make` under the requested high host load in 5m03s
(`/tmp/make-reusable-method-frame-4-20260911.log`). This is safety and build
evidence only: collect source/JAR-matched alternating method pairs before
claiming allocation reduction or retaining it as a performance result.

The first bounded 60-window/15-window high-load diagnostic is not eligible:
the candidate at `3487098c6` had matching checksum `4352` but an unstable
PerlOnJava warmup at load 41.38/60.54/47.70, measuring 1.137M operations/s;
the clean parent `c7ba4a470` later stabilized at load 20.82/41.08/41.91 and
measured 1.620M operations/s. Their unlike host states and failed candidate
warmup make the apparent 0.702x candidate/parent direction non-comparable.
Artifacts are `/tmp/perf-reusable-method-frame-{candidate,parent}-20260911/`.
Do not retain, revert, or push this candidate on this pair; repeat alternating
source/JAR-matched runs only when both warmups stabilize.

A separate exact-candidate JFR diagnostic completed for 76 seconds at
`/tmp/perf-reusable-method-frame-jfr-20260911/20260911T165924Z/method-pair-01.jfr`
(17,073 allocation and 123 execution samples). Its candidate warmup was also
unstable, so it is allocation-selection evidence only. Filtering the final
15-second measurement interval by recording timestamp finds 5,315 sampled
`RuntimeScalar` allocations in generated `anon583.apply` (the hot method),
3,798 in `PerlRangeIntegerIterator.next`, and only 6 `RuntimeArray`
allocations at `methodArgsWithSelf`. The sparse CPU samples lead with
`ThreadLocalMap.getEntry` (10), then lifecycle/identity helpers. This supports
the pool's narrow allocation effect but rules out further method-frame tuning
as the next material candidate: profile and prove a non-escaping generated
method-lexical representation, while retaining normal lexical allocation for
every body that can capture, reference, dynamically inspect, or re-enter it.

### Full loaded-host portfolio refresh (2026-09-11)

The exact clean candidate source `38355ffef1d957a694adc840ec85ab51223d8b1e`
completed the complete seven-workload, seven-alternating-pair portfolio at
`/tmp/perf-reusable-method-frame-full-portfolio-20260911/20260911T170458Z/portfolio.json`.
It used the source-matched JAR
`1136c0525ee82e192569d653614bfd4f746d531cef418aa4b34dc42f7786f988`, JDK
24.0.2, 10--60 warmup windows and 15 one-second measurement windows; its
captured Darwin arm64 host load was 4.82/12.45/24.88. The runner exited zero,
all warmups stabilized, semantic checks passed, and the analyzer labels the
result protocol-compliant, conclusive, and stable.

This authoritative current-baseline result does **not** meet the issue #1196
acceptance target: its geometric mean is 0.6436x standard Perl (95% CI
0.6286x--0.6572x), and the analyzer rejects it because it is below 1.05x.
The workload median ratios are closure 0.4775x, method 0.2151x, numeric
1.2165x, string 0.5211x, regex 0.5462x, Life 0.5080x, and JSON 2.5299x.
Numeric and JSON are above Perl, but every other scored workload is below the
0.90x floor. This is a full acceptance measurement of the current source, not
an exact-parent A/B experiment; it therefore cannot attribute the shortfall to
the nested method-frame candidate or alone decide whether to revert it. It
does establish that performance parity remains unachieved under a stable,
realistically loaded host. The next implementation selection remains the
generated hot-method `RuntimeScalar` churn identified by the post-warmup JFR,
with a non-escaping ownership proof and focused standard-Perl regressions
before any representation change.

### Direct immediate-argument binding proof boundary (2026-09-11)

The follow-up emitter audit rules out a generic lexical-cell pool. A `my`
declaration is emitted as `new RuntimeScalar`, then passed through
`RuntimeCode.resolveLexicalAlias`, which also installs the cell in the active
lexical frame. That frame is observable by lexical aliasing, debugger/eval
paths, and runtime regex source; `my` values also participate in scope-exit
cleanup. Replacing that cell after construction cannot meet the allocation
goal, while pooling it before construction would let a retained reference,
alias, or destructor observe a later invocation.

The only viable next lowering is therefore direct argument binding, emitted
*instead of* `new RuntimeScalar`, with all of the following proof gates:

1. The CV has one immediate scalar `my (...) = @_` unpack and no dynamic
   source, debugger, lexical alias, capture, reference-taking, reassignment,
   or control-flow observation of the selected lexicals.
2. The remaining body is statically callback-free, and runtime guards prove
   the actual values take only plain, non-tied, non-overloaded paths. A guard
   miss must emit the existing allocation and list-assignment path.
3. The direct cell must still be registered in the active lexical frame; this
   preserves the runtime's pad invariant even though the guard proves no
   ordinary observation for the selected execution.
4. Permanent standard-Perl tests must cover ordinary copy semantics,
   assignment/reference rejection, recursive re-entry, aliases, `DESTROY`,
   and debugger/eval fallbacks before a selected path can be retained.

The current method benchmark has an immediate `($self, $n)` unpack followed
by hash-element mutation. Its existing entries already avoid proxy allocation
and `+=` already mutates small integers in place. It is consequently a useful
validation shape for direct binding, but not a license to specialize the
benchmark: a static and runtime proof must describe a reusable class of
generated methods, not only `PortfolioMethod::add`.

### String-path allocation selection (2026-09-11)

A bounded one-pair JFR diagnostic selected the next non-method candidate at
`/tmp/perf-string-selection-jfr-20260911/20260911T175425Z/string-pair-01.jfr`.
The clean documentation-only source was `f245de355` and its source-matched
runtime JAR was
`1136c0525ee82e192569d653614bfd4f746d531cef418aa4b34dc42f7786f988`; the
Darwin arm64 host artifact records load 9.46/9.75/9.73. Both engine warmups
stabilized and the PerlOnJava checksum was `24`, but a JFR-instrumented single
pair is not portfolio-compliant throughput evidence (the analyzer correctly
rejects it for having fewer than two pairs).

The 26-second recording has 7,591 allocation and 1,270 execution samples.
Recurring generated-body samples identify `PerlUtfString.offsetByPerlCodePoints`
through `Operator.substr`, warning-aware `StringOperators` concatenation, and
`GlobalVariable.aliasForeachGlobalVariable` for the implicit integer-range
topic. This is selection evidence only: the recording includes startup and
must not be used to rank exact byte budgets or claim a timing gain. The string
workload's local string recurrence and rvalue-only `$_` use are a candidate for
a separate non-escaping proof; do not widen generic range-topic reuse or
string operations merely because this benchmark's operands are plain values.

### Next steps

1. Read repository `AGENTS.md`, the main design contract, and the profiling
   skill before performance work. Apply the mandatory patch plus WIP-commit
   preflight if any pre-existing edits are present. Never stash or discard
   them. Work on a feature branch; no direct master push.
2. Inventory active Java/build/test processes, their command lines, parents,
   worktrees, elapsed time and CPU usage. Age alone is not a reason to kill.
   Stop only identified obsolete task-owned processes; do not use broad
   Java kill patterns. Keep one heavy gate/benchmark active on the measurement
   host. Check long jobs about every 120 seconds, with bounded waits that allow
   progress updates. Wrap every `jperl`, `jcpan`, and `prove` invocation in a
   timeout and capture full logs.
3. Treat the stable full high-load portfolio at `38355ffef` as the current
   authoritative baseline: it decisively misses the portfolio target but does
   not isolate any one candidate. Rebuild and collect a new full portfolio
   after every runtime-source change; retain host state and quality labels
   rather than silently comparing unlike environments. The direct-leaf
   candidate's 1.9086x single stable parent/candidate pair is selection evidence
   only; first complete its localized pairing protocol.
4. Select and prove a non-escaping generated-method `RuntimeScalar` reduction,
   using the JFR allocation budget before changing representation. Preserve the
   generic path for every aliasing, capture, dynamic inspection, destructor,
   exception, control-flow, or re-entry case. Do not attribute this baseline's
   method deficit to the nested immediate-unpack frame or revert it without an
   exact-parent A/B experiment. The JIT gate is complete: do not spend the next
   iteration on a forced-inlining tweak. Follow the experiment gates below;
   update this summary after each decision.

Example commands from a clean, committed checkout (choose a fresh evidence
directory for each experiment; inspect every exit status before continuing):

```bash
timeout 1800 make > /tmp/perf-handoff-make.log 2>&1
timeout 1200 perl dev/bench/run_performance_portfolio.pl --workload closure --workload method --pairs 2 --output-dir /tmp/perf-handoff-triage > /tmp/perf-handoff-triage.log 2>&1
timeout 14400 perl dev/bench/run_performance_portfolio.pl --output-dir /tmp/perf-handoff-baseline > /tmp/perf-handoff-baseline.log 2>&1
```

The runner prints the timestamped `portfolio.json` path into the log. Pass
that exact path to `perl dev/bench/analyze_performance_portfolio.pl --input
PATH --output REPORT_PATH`, capturing stdout/stderr too. Defaults are seven
alternating fresh-process pairs per workload, 10–60 warmup windows and 15
one-second measurement windows. Subset/short runs are diagnostic, not acceptance.
No JFR, call counters, fallback tracing or JIT diagnostics in throughput runs.
Use separate immutable parent/candidate worktrees and their own built JARs for
A/B tests; alternate execution on the same host, not concurrent execution.

### High-risk next idea: topic reuse needs a real proof

`EmitSubroutine` currently derives `doesNotObserveDynamicTopic` from
`!requiresAllRuntimeLexicals()` and absence of `"$_"` in a variable-name set.
`RuntimeCode` stores it and copies it on clone/adoption. The audit found no
consumer. **Absence of an explicit variable reference is not proof of absence
of observable effects.** Do not use this flag to recycle range scalars or skip
dynamic scope setup without a new, tested conservative analysis.

The proof must account for implicit-topic builtins/default-subject regexes,
qualified `$main::_`, aliases/typeglobs, nested calls, recursion/re-entry,
`eval`, callbacks, ties/overloading, warning/die hooks and debugger behavior.
Unknown effects must reject the fast path. Primitive-looking arithmetic on a
captured scalar can invoke user overload code; a syntactically leaf closure
is not automatically effect-free. Validate metadata propagation, invalidation
on CV replacement and backend differences, not just initial emission.

First trace the **actual scored call site** through generated bytecode. The
closure workload builds `$f` by calling a factory that returns a captured
closure, then repeatedly executes `$f->()` inside `for (1..128)`. A same-scope
`my $f = sub {...}` recognizer alone will not select this case. Also distinguish
explicit empty-argument `$f->()` from bare `&$f`, which shares `@_`; do not
optimize the latter emitter and assume it covers the former.

Diagnostic guard-hit counters or bytecode evidence must demonstrate selection
on the scored workload and rejection of unsafe cases. If proving this needs
interprocedural effects or runtime CV/type identity guards, budget that cost
before implementing it. Keep ordinary range elements distinct when a callee
can retain `\$_` or mutate the topic. If the proof is too broad or guard hit
rate too low, leave topic reuse unchanged and choose another measured target.

### Experiment plan and decision gates

| Stage | Deliverable | Advance only when |
| --- | --- | --- |
| Attribute | Selected call-site bytecode; exclusive CPU ns/op, allocated bytes/op, GC/JIT state; guard hit/fallback counts | A measured opportunity explains at least 10% of an anchor or 5% of portfolio time, per the design |
| Prove | Explicit ownership/effect contract, generic fallback, permanent selected/rejected tests | Standard Perl oracle first; failures reproduced on the unfixed parent where applicable; JVM and interpreter pass |
| Implement | One focused reversible change, no benchmark-specific behavior | Full immutable `make` passes; generated code confirms intended path |
| Screen | Alternating exact-parent/candidate fresh-process pairs, raw windows and stable warmups | Material repeatable throughput benefit, not merely fewer sampled allocations |
| Integrate | Complete seven-workload protocol at an exact candidate commit | No regression floor breach, anchor/portfolio gates pass, stronger per-workload parity is reported |

Build the budget from non-overlapping costs: call target/context resolution,
argument transport, dynamic scope/cleanup, result transport, body arithmetic,
range iteration, and residual runtime/GC. `RuntimeCode.apply` being on a stack
does not mean all time below it is call overhead. For an affected fraction
`f` improved by factor `s`, maximum total gain is `1 / (1 - f + f/s)`;
even eliminating a 10% cost gives only 1.11x, not the roughly 4.6x closure
improvement suggested by the diagnostic. Report uncertainty rather than
inventing a precise fraction from inclusive samples.

Investigate state/thread-local lookup consolidation and argument/result
transport at the general call boundary first if exclusive attribution supports
them. Preserve bound-runtime switching, stack/cleanup markers, scalar/list/void
and lvalue contexts, tail calls, exceptions and dynamic regex state. Audit the
constant-CV early return against those obligations before widening it. If
generated-body arithmetic dominates, update the design's phase decision with
evidence before primitive specialization; preserve signed/unsigned IV, NV,
BigInt, coercion, magic and overload semantics. Then independently address
Life, string and regex deficits; a JSON surplus cannot satisfy their floors.

For call/frame/topic candidates, permanent counterexamples must cover retained
`@_` and `\$_`, mutation through aliases, LexAlias replacing a destination before
entry, recursion, exceptions/nonlocal control, caller context, ties, overload,
debugger and CV replacement. Existing tests are starting points, not permission
to change expected results. Add focused tests; never modify/delete an existing
test to accommodate an optimization. Reuse the relevant debugging/parity skill
when a failure is found, and prove whether it predates the change.

### Profiling corrections and evidence portability

The historical closure JFR was started at JVM startup, not after warmup. Its
reported 4,297 range-scalar events are sample counts, not 4,297 allocated
objects or a byte budget. Ranking all printed stack frames produces overlapping
inclusive counts, not exclusive CPU attribution. Recollect or filter by actual
measurement timestamps, exclude each thread's initial allocation sample when
appropriate, use event weights/counters, and normalize to completed operations.
Do not drop just one global first sample or compare counts from unequal work.
The runner's `--jfr` likewise starts at launch; window filtering is still needed.
Collect the design-required async-profiler and JIT/inlining/deoptimization
evidence in separate diagnostic runs before accepting an attribution report.

These files existed at audit time but **will not follow Git to another
computer**. Preserve a compact extracted report and a manifest in durable
project/PR evidence storage before removing raw recordings. Transfer needed
raw evidence securely, respecting the design's bounded-recording/cleanup rule;
if unavailable, mark it unavailable and rerun rather than reconstruct results.

| Local artifact | SHA-256 |
| --- | --- |
| `/tmp/performance_current_baseline/20260910T213011Z/portfolio.json` | `9e5fd1ce39d9e3bcf39867f6ef5f88af99f64798b832699f747b006c49300174` |
| `/tmp/closure_current_profile.jfr` | `e4bf290d7d53c61f66fcd8f235203c1abf8e1bfccd85c4ed7f4702705595e69f` |
| `/tmp/json_post_hash_rejection.jfr` | `60cbc88a24b6768dd0e9a70f50fd2a89876b9f76aa77594d2c7a9adbfe913bd2` |
| `/tmp/make_dynamic_topic_metadata.log` | `f7035ed9cedf90d3774b101f222f9b5f9dd65d327a6007b312d4175d22c71897` |

For each new experiment retain: hypothesis and expected budget; exact parent
and candidate source/JAR/launcher hashes; environment/module identities;
commands and exit codes; oracle/regression/full-gate logs; raw per-pair windows;
analyzer report; profile window boundaries and compact attribution; selection
evidence; decision and remaining gaps. Checksums establish file identity, not
that a measurement was valid. Machine changes require a new pinned baseline;
never compare absolute throughput across hosts as a candidate speedup.

### Navigation and completion checklist

- [Workloads](../bench/performance_workload.pl),
  [runner](../bench/run_performance_portfolio.pl),
  [acceptance analyzer](../bench/analyze_performance_portfolio.pl).
- [JVM subroutine emission](../../src/main/java/org/perlonjava/backend/jvm/EmitSubroutine.java),
  [call runtime](../../src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java),
  [variable collector](../../src/main/java/org/perlonjava/backend/bytecode/VariableCollectorVisitor.java),
  [range-topic escape analysis](../../src/main/java/org/perlonjava/frontend/analysis/RangeTopicEscapeAnalyzer.java).
- [Permanent unit tests](../../src/test/resources/unit/),
  [profiling workflow](../../.agents/skills/profile-perlonjava/SKILL.md).

Completion requires all of the following, not simply exhausting this plan:

- [ ] Exact committed candidate, full successful build/test gate and permanent
  semantic regression coverage on standard Perl and both PerlOnJava backends.
- [ ] Quiet-host, stable, default-protocol seven-workload evidence with matching
  checksums and trustworthy source/JAR provenance; uninstrumented timings.
- [ ] Existing analyzer acceptance passes, and the stronger per-workload 1-to-1
  gate establishes parity with reported uncertainty. No excluded slow workload.
- [ ] Required profiling/bytecode evidence explains the gain; diagnostics are
  off by default; guarded fallback and resource bounds remain intact.
- [ ] Durable raw/compact evidence manifest, updated main design and this
  handoff, changelog impact evaluated, feature-branch PR reviewed before merge.

If any box remains open, report the measured gap and the next discriminating
experiment. Do not report the objective complete or blocked merely because
another optimization is difficult.

## Objective and proof

The objective is the [main performance contract](performance-over-perl.md):
the default JVM backend must reach a portfolio geometric mean of at least
1.05x standard Perl, with a 95% confidence interval wholly above 1.00x; both
the closure and Life anchors must also reach 1.05x; every scored workload must
be at least 0.90x; and semantics must remain correct on both backends.

Do not treat a short benchmark, a JFR capture, an allocation reduction, or an
analyzer success alone as proof. The final report must contain all seven
workloads, seven alternating fresh-process pairs per workload, stable warmup,
the paired confidence intervals, source/JAR hashes, and the pinned Perl/JDK
and host identity. The acceptance reporter now enforces this contract at
`ff7dd7d85`: it rejects incomplete, duplicate, or unknown scored workload
sets, calculates a workload-balanced bootstrap portfolio interval, and rejects
portfolio or closure/Life confidence bounds that include 1.00x.

## Historical evidence and budget — superseded execution order

The sections below retain earlier checkpoints and their original local evidence.
Their references to "current", "next", and "last" are relative to those
checkpoints. Use the audited start section above for current priorities and
evidence qualifications; do not execute this history as a fresh task list.

### Earlier evidence audit and priorities (2026-09-10)

The 1x objective remains **unachieved and unverified**. The last implementation
commit is `164d8f19b`; the subsequent handoff checkpoint is `c5f65c888` on
`wip/performance-preflight-20260909-133542`. No nonempty method-frame reuse
implementation or static observability proof has been added. The previous
stop reflects unfinished engineering, not a demonstrated dependency on user
approval or external information. Continue with the validation and measurement
work below before selecting another optimization.

**Correction to earlier completion reports:** final build logs were inspected
for this handoff update. Focused test success had been mistaken for full-gate
success while the full builds had not yet produced terminal results.

| Change | Final evidence available locally | Conclusion |
| --- | --- | --- |
| `c336e736e`, direct RHS wrapper removal | `/tmp/make_direct_argument_unpack.log`: `BUILD SUCCESSFUL in 5m 6s`, `EXIT: 0` | Successful recorded build; verify source immutability before reusing as acceptance evidence. |
| `d8eb18613`, alias regression | Earlier `/tmp/make_fresh_lexical_argument_unpack_alias.log` failed, but a fresh isolated-parent `/tmp/make_performance_fixed_slots_parent.log` completed `BUILD SUCCESSFUL in 5m 12s`, `EXIT: 0`. | The earlier failure is not a repeatable regression at this revision. |
| `164d8f19b`, fixed lexical slots | Earlier `/tmp/make_direct_fresh_scalar_slots.log` failed; a later immutable current-source gate at documentation checkpoint `55f834fca` completed `/tmp/make_performance_current_validation.log`: `BUILD SUCCESSFUL in 5m 11s`, `EXIT: 0`. | The fixed-slot source is now integration-validated; the checkpoint adds documentation only. |

The alias-regression build reports failures in `unicode_surrogate_scalars.t`,
`unpack.t`, `text_csv.t`, `threads_end_block_ownership.t`,
`threads_shared_lexical_reassignment.t`, `zz_perlonjava_process.t`, and
`x_shebang_switch.t`, plus Java runtime/shared-storage tests with
`NoClassDefFoundError`. The fixed-slot build reports missing
`binary/in-progress-results-generic.bin` files for shards 0, 1, and 3.
These are concrete investigation targets. Their root causes and relationship
to the candidate are not established; do not label them pre-existing or
harmless host contention without comparison evidence. Local `/tmp` artifacts
are pointers for the next session, not durable CI records.

The repeated failures therefore do not establish a code regression. They remain
useful operational evidence: an incomplete Gradle shard result is not a test
result and must be rerun from an immutable checkout before classifying code.

The delayed allocation recording was also recomputed from
`/tmp/method_hot_profile_fixed_slots_2_alloc.txt`, excluding the first
`jdk.ObjectAllocationSample` for each event thread. The recording's initial
main-thread `RuntimeArray` sample alone carried 25 GB; after exclusion,
sampled `RuntimeArray` weight is 1,239.9 MB. The leading retained sampled
classes are `RuntimeScalar` (6,778 MB), `RuntimeScalarReadOnly` (4,957.3 MB),
`WeakReference` (3,692 MB), `Object[]` (3,014.1 MB; 2,918.1 MB on
`methodArgsWithSelf` stacks), and `RuntimeArrayElementList` (1,896 MB; 1,808
MB on those stacks). This corrects the prior `methodArgsWithSelf` ranking:
sampled weights are an allocation-selection signal, not measured totals, and
this recording lacks a completed-call counter for per-operation normalization.

A diagnostics-off, three-pair alternating fresh-JVM comparison then used the
validated parent JAR (`d8eb18613`, SHA-256
`532540c9b605037448050cfd396480d2d58b7db8b3e5a0feee85549e37a65598`) and
candidate JAR (`55f834fca`, source-equivalent to fixed-slot `164d8f19b`,
SHA-256 `09c6862b657de22399cc9ad2d82e3768f990e179ccc09a73fa4e39a50394285b`).
Each process used ten warmup and five one-second method windows; order was
parent/candidate, candidate/parent, parent/candidate. Per-pair median
throughput ratios were 1.0705x (1.50M to 1.61M ops/s), 1.2821x (1.21M to
1.56M), and 1.1118x (1.28M to 1.42M), respectively. Only the first pair had
both warmups stabilized. The median 1.1118x direction is encouraging but is
not retain/broaden evidence on this shared host; raw JSON is
`/tmp/fixed_slots_{parent,candidate}_pair{1,2,3}.json`.

The required quiet-host follow-up completed seven alternating fresh-JVM pairs
after the LexAlias guard repair. The parent was `d8eb18613` (JAR SHA-256
`532540c9b605037448050cfd396480d2d58b7db8b3e5a0feee85549e37a65598`); the
candidate was `6b5cdec6c` (JAR SHA-256
`8ff107b14307ea3988b820bbd481b07da987de5bbaa468d366ab0e4fc7456a7f`). Each
process used ten warmup and ten one-second method windows. Candidate/parent
median ratios were 1.1646, 1.0334, 1.0908, 1.0506, 1.0391, 1.0495, and
1.0111; all seven favor the candidate, with a median 1.0495x and mean
1.0627x. Both warmups stabilized in pairs 3, 4, 6, and 7. This is sufficient
selection evidence to retain the guarded fixed-slot lowering, but is not a
Perl-comparison or portfolio acceptance result. Raw records are
`/tmp/fixed_slots_quiet_{parent,candidate}_pair{1,2,3,4,5,6,7}.json`.

A fresh clean-host method JFR at `6b5cdec6c` warmed 25 seconds and recorded
30 seconds (`/tmp/method_hot_profile_guarded_slots.jfr`, 8,607 allocation and
38 GC samples). Excluding each thread's first allocation sample, the leading
sampled allocation stacks were generated `anon583.apply` (14,133.9 MB),
`PerlRangeIntegerIterator.next` (7,645.1 MB), and
`RuntimeCode.methodArgsWithSelf` (6,054.2 MB). The method workload's implicit
range topic can be observed by its called Perl method, so it cannot safely
reuse the range cell under the existing non-retention proof. The generated
method body remains the largest budget; do not claim its sampled weight as an
exact total or bypass its result/control-flow ABI without a narrow ownership
proof.

The fixed-slot safety audit found that `Devel::LexAlias` can replace a lexical
cell before invocation, invalidating the earlier assumption that emitted `my`
slots are necessarily plain and distinct from `@_`. The fixed-arity helpers now
check the destination class/tie state and every RHS identity before direct
stores; any exceptional destination falls back to
`setFromListDiscardResultFreshScalars`. The full gate for that repair,
`/tmp/make_fixed_slots_destination_guard.log`, passed in 5m09s. The existing
`devel_lexalias_padwalker.t` regression passed on JVM and interpreter (12/12
each). The new focused generated `my ($x) = @_` plus pre-call LexAlias/tied
destination regression `fresh_lexical_argument_unpack_lexalias.t` passes
standard Perl, JVM, and interpreter (3/3 each); its final full gate,
`/tmp/make_fixed_slots_lexalias_regression.log`, passed in 4m40s.

Immediate next actions, in order:

1. Verify active processes and their working directories. Let all gates and
   children in this checkout finish before edits, builds, or JAR readers.
   Use a separate worktree if a gate needs to run alongside development.
   A tool observation ending does not prove its child build exited: require
   process termination plus the log's final build result and exit code.
2. The immutable candidate and parent `make` gates have now passed. The
   fixed-slot helper restores destination-class/tie and identity-alias fallback
   guards, and permanent generated `my ($x) = @_` plus pre-call
   `Devel::LexAlias`/tied-destination coverage now proves the fallback on
   standard Perl and both backends. Retain these guards when evolving the
   lowering; a declaration alone is not proof of freshness under lexical
   rebinding.
3. The seven-pair quiet-host A/B result retains the guarded fixed-slot lowering
   (+4.95% median method throughput). Compare `c336e736e` against `ab58a1c59`
   under the same protocol. Keep the fixed-slot guard and test while measuring
   subsequent work.
4. Apply the first-sample exclusion rule to all earlier 80/95.8/82.4 GB
   attribution claims before using them to rank work. A zero sampled class
   does not prove zero allocations.
5. Select the next structural change from the corrected CPU/allocation budget.
   The latest method capture ranks generated method-body scalar churn first;
   range-topic reuse is rejected unless the body and every reachable call prove
   the topic unobservable. Do not revive generic nonempty frame pooling.
   Reusable nonempty frames are only a hypothesis. Static use of `@_` solely
   in unpacking does not exclude observation through overloaded/tied values,
   callbacks, signal/die/warn handlers, debugger or lexical introspection,
   shared-argument calls, tail calls, and nested dynamic code. Per-depth leases
   address overlapping invocations but not escaping frame identity or the
   `copiedFromArgumentFrame` tokens retained by scalar copies. Cover selected
   and rejected paths, retained references, recursion, exceptions, and
   DESTROY timing before enabling reuse. If the proof is too broad or the
   budget too small, choose another measured hotspot; frame pooling is not a
   prerequisite to the overall performance goal.
6. After a repeatable material gain and passing correctness gates, run the
   complete seven-workload/seven-pair acceptance protocol above. Update both
   the main design and this handoff with durable evidence and remaining gaps.

This update is documentation-only; it does not repair or revalidate the
runtime candidates. The priorities here supersede conflicting success and
allocation-dominance claims in the historical narrative below.

The authoritative baseline is decisively below target. Its JSON ratio was
0.0102x, which needs an 88.2x speedup merely to reach the 0.90x floor. The
other recorded gaps remain material: closure needs 6.59x to its 1.05x anchor,
Life 2.75x, method 5.41x, regex 4.81x, string 3.09x, and numeric 2.69x to
their stated thresholds. No individual reduction should be described as
progress toward acceptance unless its non-overlapping affected fraction and
measured speedup can materially move one of those budgets.

The recent one-pair JSON diagnostic is useful only for attribution. Its
shared-argument instance category took about 30.98 microseconds and 28,977
bytes per call inclusive. The reported 3.74 microseconds / 3,384 bytes
"exclusive" value is **not** generic call-frame cost: it includes all body
work except nested instrumented calls. It cannot justify deprioritizing call
boundary work without a direct setup/dispatch/return measurement.

## What the opcode capture says

`BytecodeOpcodeDiagnostics` is an opt-in counter. A bounded JSON run recorded
high counts for branches, byte-string loads, mortal flushes, list creation,
call-site hint/warning setup, aliases, regex matching and state snapshots,
lexical cleanup, hash/array access, and direct calls. These counts cover
startup, warmup, measurement windows, and every interpreter CV in the process.
They establish that interpreter work is substantial, but not which operation
owns elapsed time or allocation. Never optimize by count alone.

Use it with:

```text
-Dperlonjava.bytecodeOpcodeDiagnostics=true
-Dperlonjava.bytecodeOpcodeDiagnosticsOutput=/tmp/json-opcodes.json
```

The implementation is disabled in ordinary runs. It passed the full `make`
gate in 5m27s, and its instrumentation cost makes it unsuitable for timing.

Per-CV attribution landed with the current work: counters are thread-confined,
then merged by package/subroutine/source location at shutdown. A bounded JSON
capture on 2026-09-10 (two warmup windows and three measurement windows) found
15,795,675 total dispatches. `JSON::PP::_string` accounted for 12,860,000
(81.4%), `JSON::PP::string_to_json` for 2,092,740 (13.2%), and
`JSON::PP::PP_encode_json` for 475,894 (3.0%). The short capture did not reach
stable warmup and is not a performance result; it is enough to rule out broad
opcode-count speculation. The next JSON investigation must use JFR CPU and
allocation stacks for `_string` and `string_to_json`, then separate the cost
of their repeated interpreter dispatch, allocation, and scalar/string
operations before changing code.

### JVM-compilation blocker found and removed

A JFR-guided inspection found a compile barrier that had hidden the useful
JVM path: `JSON::PP::PP_encode_json` could not be emitted because the generated
class embedded the entire deparse source as one JVM UTF-8 constant. Large source
files exceed the class-file 65,535-byte constant limit, so this forced the
interpreter before any hot-path optimization could matter. The emitter now
registers only oversized deparse sources under the generated class name and
loads them when the code object is constructed; ordinary sources retain the
direct constant path. `LargeDeparseSourceCompilationTest` covers a 70 KB source
and verifies that the named subroutine is JVM compiled. A direct JSON encode
trace now confirms `PP_encode_json` compiles successfully.

This is enabling work, not a performance result: it removes a hard compile
barrier without changing the execution cost of code that was already compiled.
It must remain allocation-free on the ordinary source path and must not become
an unbounded registry (one entry per generated oversized source is expected for
the lifetime of a loaded generated class).

The next decode trace narrowed the remaining JSON bottleneck: `JSON::PP::_string`
then fell back with ASM frame merging's `dstFrame` null failure. A fresh per-CV
counter capture after the compile-barrier fix assigned 17,656,000 of 17,656,167
interpreter dispatches to `_string`. The repair found two linked emitter defects:
duplicate parser-label registration left a dangling ASM target, and dynamic
cleanup-level slots were pre-initialized as references but later used as ints.
The latter is now represented consistently as a boxed `Integer`; focused
standard-Perl, JVM, interpreter, and JVM-compilation tests cover both the
labeled outer-loop case and `JSON::PP::_string`. A direct decode trace now shows
`_string` compiling without either frame or verifier fallback.

A one-pair, three-warmup/five-window JFR diagnostic from that exact dirty source
state measured about 10,626 PerlOnJava operations/s versus 64,720 Perl
operations/s (about 0.164x). This is roughly three times the earlier
fallback-era diagnostic rate, but its warmup was unstable and the host load was
high; it is activation evidence only, not an acceptance or regression score.
The nine-second recording contains substantial module-load/compiler samples and
only 36 execution samples, so it must not select a steady-state micro-optimization.
The next profile must use a sufficiently warmed compiled JSON process, exclude
startup, and attribute CPU and allocation inside the now-JVM-compiled parser
before changing runtime code.

A clean-source follow-up at `baa325691c57cc7a68dba3f9209d2a96ed1cbd99` used
ten warmup and fifteen measurement windows. It still did **not** stabilize on a
host with load averages 14.14/15.71/23.14: median window throughput was 9,249
PerlOnJava operations/s versus 51,160 Perl operations/s (0.181x), with the
PerlOnJava windows spanning 7,203–10,384 operations/s. The 27-second JFR
recording has 79 execution samples, 7,609 allocation samples, and 49 young
GCs, so it remains attribution only rather than a controlled comparison.
Late samples include `RuntimeCode` call lifecycle/return copying,
`JoniRegexPattern` matcher creation and matching, and string/scalar helpers;
they did not by themselves isolate a single compiled-parser body cost. The
post-warmup, per-CV diagnostic below supplies a selection budget; it still
requires a quiet-host confirmation before any throughput claim.

### Post-warmup JSON attribution (2026-09-10)

A timeout-bounded dedicated process warmed the exact JSON operation for 25
seconds before `jcmd JFR.start` recorded the next 40 seconds. The recording is
not a throughput comparison on this contended host, but it excludes module
loading and initial compilation: it contains 98 execution samples, 11,738
allocation samples, and 56 young collections. The sampled CPU and allocation
stacks retain `RuntimeCode.invokeCallable`/`invokeWithCallFrame`, return
coercion, regex matcher construction, and scalar/list allocation.

The existing call-layer collector now has the opt-in
`-Dperlonjava.callLayerDiagnosticsByCode=true` mode; ordinary aggregate output
and all normal execution remain unchanged. A 12-second warm diagnostic then
identified the actual hot CVs. Per main operation, `JSON::PP::decode` took
about 146 microseconds and `PP_decode_json` 146 microseconds; `encode` took
about 68 microseconds. Decode called `_string` about five times, for about 57
microseconds inclusive (34 microseconds exclusive) and 127 KB inclusive
allocation; it called `_next_chr` about 59 times, at about 584 ns and 1,096 B
per call. `_white` is also frequent (about 28 calls at 1.30 microseconds each).
These nested inclusive figures overlap and cannot be added, but `_string`'s
exclusive time alone is roughly 23% of decode and qualifies it for a structural
experiment.

The attempted direct-leaf lowering was deliberately discarded before commit:
the generated JVM marker was not attached by the compilation path used for its
small regression source, so the candidate was inactive and its assertion could
not establish a sound lowering contract. Do not revive it by widening a marker
without first proving marker ownership on the actual generated JSON CV and
covering selected/rejected behavior on both backends.

Two small JFR-driven Joni cleanups have now been measured. First, the matcher
warning hook accepted a Joni-specific functional interface, which made the
runtime allocate a forwarding lambda from its already-owned `LongConsumer` for
every affected match. The Joni API now stores that `LongConsumer` directly; a
fresh bounded JSON allocation capture no longer reports the forwarding lambda.
Second, byte-mode input construction had allocated two identity `int[]` maps
per byte-string subject even though ISO-8859-1 Java-character, native-byte, and
Perl-character offsets are identical. It now uses a byte-mode sentinel and
direct offset conversion. A 5-second warmup/15-second JSON allocation capture
on 2026-09-10 exercised this path (68,691 operations); its
`buildByteInputEncoding` samples contain the encoded byte array and
`InputEncoding` wrapper but no identity-map allocation. The full `make` gate
passed in 4m02s. These are verified allocation removals, not material
throughput claims: `JoniRegexMatcher`, `SubjectInputEncodings`, and the encoded
byte array remain prominent and need an Amdahl budget before a cache or API
redesign.

That budget supported one bounded structural experiment. The Joni bytecode
engine resets its mutable search state at each public match/search entry, but
was being allocated afresh for every simple match. Each compiled pattern now
has a bounded, per-thread idle matcher pool. Only feature-free matches use it:
locale resolution, callbacks, control verbs, deferred properties, warning
callbacks, alarm interruption, and physical named captures retain the fresh
matcher path. Results are copied from a borrowed engine before it is released;
`JoniRegexPatternTest` proves a later pooled match cannot alter an earlier
wrapper's groups or offsets. The initial pool was keyed by the immutable encoded
subject, so it proved ownership safety but could help only repeated matches of
the same byte array. On the bounded 5-second warmup/15-second JSON allocation
protocol, that version completed 83,384 operations and had sampled
`ByteCodeMachine` allocation of about 24.6 KB/operation, down from about
31.7 KB/operation in the immediately preceding 68,691-operation capture
(roughly 22%).

The pool now rebinds a returned matcher to the next complete byte subject,
rather than retaining a subject-keyed engine. Joni's `Region` is matcher-owned
capture-result storage, not caller-owned bounds; reset clears it along with the
bytecode machine's interrupt, stack, search, and control state. The permanent
pooled-matcher regression uses two distinct subject arrays and proves that the
first wrapper retains its match snapshot after the matcher is rebound. A fresh
5-second warmup/15-second JFR capture on 2026-09-10 completed 42,800 operations
and attributed 129,991,400 sampled bytes to `ByteCodeMachine`, about 3.04
KB/operation. This is approximately 90% below the pre-pool 31.7 KB/op capture
and 88% below same-subject pooling's 24.6 KB/op. The full `make` gate passed in
7m47s. This is strong allocation evidence, not a throughput or acceptance
result: the capture remains host-contended, and CPU samples are still dominated
by `RuntimeCode` call-frame lifecycle plus `ThreadLocal` lookup.

The next JFR budget was regex input-encoding cache churn. The old global,
synchronized `WeakHashMap` made a new subject metadata record on each scalar
value change and retained an unbounded set of temporary scalar keys until GC.
In the 42,800-operation rebound-pool capture, Joni stacks attributed 93.8 MB
to `SubjectInputEncodings`, 54.5 MB to `WeakHashMap` entries, and 12.6 MB to
`InputEncoding`: about 3.76 KB/operation for this setup path. It now uses a
bounded per-thread, 512-slot direct identity cache whose mutable slot metadata
is reused on scalar mutation; collisions only rebuild an encoding and cannot
expose another scalar's offsets. Existing `JoniSubjectEncodingCacheTest`
coverage proves unchanged-scalar reuse, mutation invalidation, independent
equal-valued scalars, and byte/unicode separation. The full `make` gate passed
in 3m48s. A fresh 94,282-operation 5-second warmup/15-second JFR capture had
zero sampled `SubjectInputEncodings` and `WeakHashMap` allocation; its remaining
`InputEncoding` samples were 56.1 MB, about 595 B/operation. This is an
approximately 84% reduction for the measured input-cache setup path, but not a
throughput or acceptance result on the contended host.

One remaining pool guard was itself defeating pooling: all match sites supplied
the `non_unicode` warning callback, although ordinary programs cannot execute a
Unicode-property warning opcode. Joni now publishes a parser metadata fact for
such opcodes, and PerlOnJava supplies the callback only for that fact or a
deferred property (whose warning capability is resolved at match time). The
metadata regression uses a warning-capable resolver, and the existing
`regex_nonunicode_property_warning.t` continues to prove warning behavior.
On a fresh 25-second warmup/40-second JSON allocation capture on 2026-09-10
(246,515 operations), sampled `ByteCodeMachine` allocation fell from 9.53 GB
in the preceding comparable capture to zero; `JoniRegexMatcher` remained 6.39
GB because each match still needs its result wrapper. The clean full `make`
gate passed in 6m32s. This removes a dominant allocation source but is still
not a throughput or acceptance claim.

### Guarded native JSON::PP canonical path (2026-09-10)

The compiled JSON hot path still spent most of its time crossing Perl call
boundaries for recursive encoding and parsing. `JSON::PP` now optionally loads
a private Java helper through `XSLoader`; it is not a replacement for the
public JSON::PP implementation. Encode selects it only for `canonical` output
with ordinary JSON arrays/hashes/scalars and no formatting, byte/Unicode output
mode, callbacks, custom sorting/booleans, relaxed options, blessed-object
handling, or other observable extension. Decode similarly excludes callbacks,
custom booleans, relaxed/loose syntax, tags, and bignum handling. Every
excluded configuration continues through the pre-existing pure-Perl code.

The helper preserves canonical key ordering, standard escaping, numeric scalar
types, `JSON::PP::Boolean`, nesting limits, and circular-reference rejection.
`unit/json_pp_native_canonical.t` is standard-Perl validated and covers the
selected shape plus a non-canonical fallback; `unit/json_parse_compat.t`
continues to cover duplicate-key and depth/error compatibility. A clean
`make` gate passed in 6m06s after the implementation and regression test.

A one-pair diagnostic from the exact dirty source state used the versioned
runner's 10 warmup/15 measurement windows. It is explicitly
`protocol_compliant: false` (one pair) and the host was highly loaded, so it
is not acceptance evidence. Nevertheless both engines stabilized and the
median JSON throughput was 151,902 operations/s for PerlOnJava versus 67,650
for Perl (2.245x). This is a major workload-local improvement over the prior
rough 0.18x JSON diagnostic. It does **not** establish the portfolio goal,
the no-workload-below-0.90x floor, anchors, or confidence interval. Next
measure a quiet-host seven-pair JSON confirmation, then run the whole
portfolio before claiming progress toward the project target.

A subsequent one-pair all-workload diagnostic on the same highly loaded host
confirmed the prioritization without becoming acceptance evidence: closure was
0.240x (3.05M versus 12.73M ops/s), method dispatch was 0.217x (1.28M versus
5.88M), numeric was 1.195x (20.95M versus 17.53M), string was 0.427x (8.28M
versus 19.39M), regex was 0.559x (2.52M versus 4.51M), Life was 0.425x (1.77M
versus 4.17M), and JSON was 2.147x (121,933 versus 56,789). Method dispatch
is therefore the next largest scored deficit; use a warmed CPU/allocation
profile of that workload to select a call-boundary optimization. Do not use
the noisy one-pair ratios for an acceptance claim.

That selection profile is now available: a timeout-bounded method-only JVM
process warmed for 25 seconds, then recorded 40 measurement windows with a
68-second JFR profile. Warmup did not stabilize on the contended host, so the
recording is attribution only. Of 555 execution samples, the leading runtime
frames were `RuntimeCode.invokeCallable` (221), `invokeWithCallFrame` (180),
`RuntimeCode.apply` (89), `callCached` (50), `callCachedInner` (48), and
`applyCachedMethod` (39); `RuntimeScalar` assignment/refcount helpers and
`MortalList` cleanup are also prominent. Method lookup is not the selection
target. Any next experiment must reduce common call-frame work while retaining
caller, warning scope, `@_` aliasing, non-local return, DESTROY/refcount, and
exception cleanup semantics; a method-only shortcut that bypasses those
boundaries is not acceptable.

A bounded method-`@_` frame-pool experiment was deliberately discarded before
commit. Although `\@_` references can be detected by refcount state, the
ordinary method return boundary is not sufficient ownership proof: tail-call
and internal dispatch paths can still retain the frame. The candidate broke
`json_parse_compat.t`, tail-call behavior, and Mojolicious lifecycle tests.
Do not recycle arbitrary method argument arrays unless a future design proves
ownership across the entire tail-call and non-local-control-flow protocol.

The first safe follow-up is intentionally smaller: void-context simple scalar
declarations such as `my ($self, $n) = @_` now select a list-assignment path
that avoids allocating a snapshot `RuntimeScalar` for each ordinary RHS value.
It is selected only for fresh `my` scalar lists and dynamically falls back for
identity aliases, ties, special scalar classes, or any other list shape. The
direct store preserves the argument-frame
provenance that the former snapshot constructor recorded, so mortal/refcount
cleanup remains correct. `fresh_lexical_argument_unpack.t` passed standard
Perl, JVM and interpreter execution, and the full `make` gate. A one-pair
method diagnostic on a busy host was 1.12M PerlOnJava versus 5.38M Perl
ops/s (0.208x); it is not a before/after comparison or acceptance evidence.
Measure this exact commit against its parent on a quiet host and retain it only
if the allocation saving produces a material, repeatable method gain.

The subsequent call-layer diagnostic (one pair, 3 warmup / 5 measurement
windows, therefore selection-only) narrowed the remaining method cost further.
`shared-args-instance-apply` reported about 3,102 allocated bytes and 1,910 ns
inclusive per method call, but only about 870 bytes and 596 ns were exclusive
call-frame work. A current JFR allocation sample also attributes recurring
`RuntimeList` allocation to the generated outer method-call site, with
`methodArgsWithSelf` still visible as a smaller `RuntimeArray` source. Do not
revive frame pooling: its maximum isolated allocation budget is too small and
its ownership proof previously failed. Instead investigate a conservative
scalar-result call lowering that preserves the `RuntimeList` ABI and every
control-flow marker path, while avoiding wrappers only when the caller and
callee are statically proven scalar-only.

The first implementation of that conservative result handling is deliberately
inside the existing ABI: `RuntimeList.addToScalar` now returns a marked,
private one-scalar wrapper through `scalarAndRecycle`, matching the direct
scalar-call path. Ordinary lists are not cleared, pooled, or otherwise given
different identity semantics. This removes a missed recycle point for compound
assignments such as `$sum += $object->value`, without changing argument-frame
or generic call-frame ownership. The new
`scalar_sub_call_compound_assignment.t` regression passed standard Perl, JVM,
and interpreter execution; the clean full `make` gate passed in 5m36s. Its
one-pair method diagnostic was host-contended and declining (1.38M to 1.10M
PerlOnJava operations/s across five windows), so it is not a keep/revert or
throughput result. Compare this exact commit with its parent using alternating
fresh processes on a quiet host and retain it only if its measured allocation
reduction translates into a repeatable method-workload gain.

### Post-warmup method allocation selection (2026-09-10)

A controlled method process warmed for 28 seconds before `jcmd` started its
own 30-second profile recording (the process exited after 28 recorded seconds).
This eliminates startup and initial compilation from allocation selection. The
recording has 8,308 allocation samples and 97 young collections, but only 30
execution samples, so it is allocation evidence rather than a CPU profile.
JFR's sampled allocation weights estimate 95.8 GB of `RuntimeScalar`, 15.8 GB
of object arrays, 3.62 GB of `RuntimeList`, and 3.57 GB of `RuntimeArray`.
The leading scalar stack (about 91.5 GB) originates in the generated body of
the hot cached method, not generic dispatch. The next identified sources are
the integer range iterator (about 3.59 GB), `methodArgsWithSelf` (about 3.20
GB), and `RuntimeScalar.getList`/`RuntimeList.acquireScalarResult` at the
return boundary (about 2.93 GB). These sampled categories overlap only by
time, not by allocation site; they demonstrate that generic argument-frame
pooling cannot close the method gap and remains unsafe.

Do not infer that the marked result-list pool is active merely because a
scalar caller reaches `addToScalar`: the warmed capture still samples its
acquire site. Before another result-path change, add an opt-in exact
acquire/recycle counter (disabled in normal execution) and use it on this
process to establish which scalar-context lowering consumes the wrapper. A
future direct scalar return ABI would have to preserve list, lvalue, tail-call,
non-local-control-flow, rvalue-copy, and `DESTROY` boundaries; it is justified
only if that counter and a quiet-host paired run show that wrapper lifecycle is
a material residual after the generated method body's scalar allocation.

That counter now identified and closed a direct leak. Two generated scalar
conversion sites (`RuntimeCode.apply()` through `EmitVariable`, and method
dispatch through `Dereference`) had invoked `RuntimeList.scalar()` directly,
so they bypassed the existing private-wrapper recycle helper. They now call
`scalarAndRecycle`; ordinary lists and control-flow markers retain identical
`scalar()` behavior. On the same bounded method protocol, pool misses fell
from 16,524,781 to 226,985 and successful recycles rose from 250,455 to
14,939,916; scalar extractions rose from 500,972 to 15,166,430. This proves
the affected hot path, not just a sampled allocation estimate. The regression
passed standard Perl, JVM, and interpreter execution; a clean full `make` gate
passed in 5m07s. A diagnostics-off one-pair run remained host-contended and
unstable (about 1.22M PerlOnJava vs 5.20M Perl median operations/s), so it is
not a throughput claim. The next measurement must use alternating fresh
processes on a quiet host before quantifying the gain.

### Scalar-result lifecycle re-audit (2026-09-11)

The opt-in counters were rerun after the rebase on the current JVM method
workload with 20 forced warmup windows and 10 one-second measured windows.
The process completed with a stable warmup and matching checksum under the
loaded host (`/tmp/scalar-result-method-20260911.log`, exit 0). Its report
(`...method-20260911.json`) records 48,147,429 private-result acquisitions:
47,428,617 pool hits and exactly 47,428,617 recycles. The remaining 718,812
scalar extractions were ordinary lists; there were no multi-element private
results. Thus the private wrapper lifecycle balances for this workload after
the two known JVM conversion fixes. Do not add another recycle-site shortcut:
the remaining acquisition misses are accounted for by ordinary-list paths,
not an unreturned private wrapper. Resume selection from a distinct generated
method-body scalar operation or a representation change with a complete
ownership proof.

### Method call boundary: copy-cell proof and high-load remeasurement (2026-09-11)

`direct_argument_binding_guard.t` now fixes the semantic boundary for any
future `my (...) = @_` lowering.  It passes under standard Perl and both
PerlOnJava backends, and covers an ordinary immediate copy, later `$_[0]`
mutation, a retained lexical reference, recursive re-entry, `eval STRING`,
and object lifetime through `DESTROY`.  In particular, a lexical may not
borrow the argument scalar: the two are distinct cells even when their initial
values are the same.

The existing `reusableImmediateMethodArgs` metadata therefore remains only a
physical `@_`-frame cache.  It does not remove the fresh lexical cells emitted
for `$self` and `$n`, and it is not a proof that those cells can be pooled.  A
current JFR/call-layer selection capture attributes the hot named method path
to 32,813,870 `shared-args-instance-apply` operations at 1,756.67 ns/op
inclusive (1,610.67 ns/op body); generated bytecode inspection confirms fresh
`RuntimeScalar` construction followed by lexical-alias registration for both
arguments.  A generic cell pool is rejected: references, argument aliases,
dynamic source, debugger/lexical inspection, recursive activation, and
destructor timing require an explicit whole-body non-escape proof and a
runtime fallback, not merely immediate-unpack metadata.

The current source (`50ef79575`) was measured with the complete seven-pair
alternating portfolio protocol under real host contention.  All 14 processes
reported stable warmup.  The artifact
`/tmp/perf-method-highload-20260911/20260911T183640Z/portfolio.json` records
a 0.2194x PerlOnJava/Perl median method-throughput ratio (0.2349x mean;
0.2102x--0.3297x range).  The run began with 20 users and load averages
3.16/7.48/9.41; unrelated PerlOnJava jobs raised the observed one-minute load
to 17.25 during collection.  This is valuable load-conditioned selection
evidence, not a quiet-host acceptance claim.  Do not compare it directly to
the historical quiet-host candidate deltas.

### Rejected active-pad registration elision (2026-09-11)

A guarded experiment retained the fresh lexical cells and list-assignment
semantics but omitted their active-pad registration only for callback-free,
lexical-only immediate-unpack CVs with plain argument values.  The full
`make` gate passed.  It was rejected and removed after the three-pair
high-load selection artifact
`/tmp/perf-method-pad-elision-selection-20260911/20260911T185408Z/portfolio.json`
measured a 0.1959x median method ratio (0.1866x mean;
0.1489x--0.2148x range), below the preceding 0.2194x loaded-host reference.
All six processes stabilized, so this is sufficient negative selection
evidence despite host variance.  The active lexical-frame map is already
reused by depth; eliminating its registration did not remove the fresh scalar
allocation budget and must not be retained as a speculative escape-analysis
hint.

### Read-only direct-argument lexical lowering contract (2026-09-11)

The next generated-method candidate must lower before lexical-cell allocation,
not substitute a value after `NEW RuntimeScalar`: the latter preserves the
dominant allocation.  The JVM declaration emitter owns both the lexical JVM
slot and that allocation, while the existing fixed-arity unpack helper owns
the subsequent copy.  A correct fast branch may bind the slot to the current
`@_` element only when a whole-body analysis proves each selected lexical is a
scalar read, never an lvalue, reference, capture, argument to a user call,
dynamic-source input, or debugger/PadWalker target.  The normal branch must
remain the existing fresh-cell unpack.

Runtime entry guards must reject tied/proxy/readonly/magic arguments and any
active lexical-alias or debugger support.  Missing arguments need an inert
undef read value, while extra arguments retain the normal `@_` frame.  The
proof and tests must cover caller-side mutation, references, recursion,
`eval STRING`, `DESTROY`, tied values, and an explicitly rejected user-call
case.  This is a general compiler lowering criterion; do not recognize the
portfolio method body or its hash keys as a special case.

### Issue #1196 closure reproduction under host load (2026-09-11)

The issue's `dev/bench/benchmark_closure.pl` reproduction completed under 20
active users and load averages 13.02/18.72/18.42 at 163.51 iterations/s
(30.58 CPU seconds for 5,000 `timethis` iterations).  Its 31-second JFR
recording (`/tmp/closure-issue1196-highload-20260911.jfr`) has 2,165 execution
samples and 3,384 allocation samples.  Repeated stacks retain
`RuntimeCode.apply`, `coerceScalarCallResult`, return-boundary copying, and
the generated loop/closure bodies.  The existing direct integer-addition leaf
entry is present in sampled stacks, but it still invokes the generated body
and scalar-result coercion.  It is therefore not a complete zero-argument
closure ABI.  Treat this as host-contended selection evidence only; preserve
the issue's caller/context/warning/closure-lifetime fallback constraints when
designing a broader direct entry.

### Rejected direct-leaf return-coercion bypass (2026-09-11)

The existing integer-capture direct leaf entry was changed experimentally to
retain temporary-root release while bypassing scalar coercion and lvalue
detachment.  The complete `make` gate passed in 3m51s, but the same closure
reproduction regressed to 157.04 iterations/s (31.84 CPU seconds), compared
with the preceding loaded-host 163.51/s (30.58 CPU seconds).  The change was
removed.  Do not infer a gain from omitting a seemingly redundant return
boundary: it did not reduce the dominant generated-body/call cost and retains
ownership risk outside this narrow integer case.

### Direct fresh-lexical `@_` unpack lowering (2026-09-10)

The next narrow allocation repair removes the transient one-element
`RuntimeList` wrapper used only to carry `@_` into a void-context fresh lexical
declaration (`my ($x, ...) = @_`). The JVM emitter now recognizes exactly that
syntactic form and passes the existing argument `RuntimeArray` directly to
`RuntimeList.setFromArgumentArrayDiscardResultFreshScalars`. The runtime uses
the same dynamic guards as the existing fresh-scalar path: tied or non-plain
destination values, special RHS values, and identity aliases all fall back to
ordinary list assignment. This preserves `@_` aliasing and the generic list
ABI; it is not an argument-frame pool or a direct-return ABI.

The ordinary-value and aliasing regressions
`fresh_lexical_argument_unpack.t` and
`fresh_lexical_argument_unpack_alias.t` pass under standard Perl, the JVM
backend, and the interpreter in focused runs; the later isolated-parent full
`make` gate passed (see the evidence audit above). The latter
proves that changing `$_[0]` still updates the caller while the just-unpacked
lexical retains its prior value. A timeout-bounded post-warmup JFR attempt
captured only one second before the process exited, so it cannot support a
numerical allocation or throughput claim. On a quiet host, record a
sufficiently long post-warmup capture and compare alternating fresh method
processes with the parent before retaining or broadening this candidate. In
particular, distinguish the deliberately retained destination `RuntimeList`
from the eliminated RHS transport wrapper.

### Fixed-arity fresh lexical slots (2026-09-10)

The two most common method forms have one or two scalar lexical arguments.
For those same guarded void-context `my (...) = @_` declarations, the JVM now
creates the fresh lexical slots and passes them directly to fixed-arity runtime
helpers. This removes the destination `RuntimeList`, its `ArrayList`, and its
backing array on the ordinary path without introducing a varargs array. Tied
or special RHS values retain the generic list-assignment implementation. The
standard-Perl, JVM, and interpreter unpack/alias regressions passed focused
runs, and the later immutable candidate full `make` gate passed (see the
evidence audit above).

A delayed JFR recording (25-second warmup, 30-second recording) contains
6,685 allocation samples and 156 execution samples. Unlike the earlier method
capture, it has no sampled `RuntimeList` or `ArrayList` allocation in the hot
method body. This is useful allocation attribution, not a throughput result.
The first allocation sample attributes a 25 GB weight to a `RuntimeArray`
at `RuntimeCode.methodArgsWithSelf`; this requires boundary validation before
ranking the remaining sources. Do not pool arbitrary argument
frames: the prior ownership proof failed. Instead find a representation that
preserves `@_` aliases, retained frame references, tail calls, exceptions, and
non-local control flow before changing this boundary.

The existing `reusableEmptyArgs` implementation is a reference for a possible
experiment, not a safety proof for nonempty reuse: it is runtime-local and
uses static metadata with debugger fallback. The hot method's only
static `@_` occurrence is now the direct fresh-lexical unpack. Do not treat
that fact alone as sufficient: first extend metadata to distinguish this exact
lowered use from a later `@_` read, mutation, reference, `caller`/debugger
observation, nested dynamic source, or recursive re-entry. Any reusable
nonempty frame must be leased per active depth and returned only when that
proof holds; otherwise construct the current fresh `RuntimeArray`.

### Guarded RHS transport scope (2026-09-10)

The broad direct-`@_` RHS transport lowering was measured separately from the
fixed-slot lowering, using seven alternating fresh-process method pairs against
parent `ab58a1c59`.  Candidate `c336e736e` had a 0.9636x median ratio (0.9591x
mean; range 0.9086x--1.0102x).  It is therefore a repeatable negative result,
not a portfolio contribution: bypassing the generic RHS `RuntimeList` for all
fresh declaration arities must not be retained.

The current emitter consequently limits that direct transport to the
independently measured one- and two-slot declarations.  Three or more fresh
lexicals use the prior generic RHS list transport while retaining the existing
guards and fixed-slot lowering where applicable.  The new permanent
`fresh_lexical_argument_unpack_three.t` regression proves ordinary values,
missing values, and `@_` aliasing; it passed standard Perl, JVM, and
interpreter focused runs.  The immutable full `make` gate passed in 3m25s.

In contrast, the retained fixed-slot candidate `6b5cdec6c` was compared with
its parent in seven alternating pairs: median 1.0495x, mean 1.0627x, range
1.0111x--1.1646x.  This is evidence to retain the one/two-slot lowering, but
not evidence that the complete portfolio meets the 1.00x goal.

A delayed 30-second JFR capture of the current guarded path, excluding each
event thread's initial allocation sample from attribution, estimates 14.1 GB
in the generated hot method body, 7.65 GB in `PerlRangeIntegerIterator.next`,
and 6.05 GB in `RuntimeCode.methodArgsWithSelf`.  CPU sampling was too sparse
to rank.  Do not reuse the range iterator generically: an implicit `$_` in a
loop whose body calls a method can be observed or retained.  The next structural
selection target is generated-method scalar churn and its call ABI, with an
explicit non-overlapping budget and safety proof before any representation
change.

### Corrected JSON allocation ranking (2026-09-10)

A fresh delayed JSON JFR capture exposed an important sampling correction:
the apparent 39.6 GB constant-`RuntimeList` copy was the recording's first
allocation sample and must not be used to rank work.  Excluding each event
thread's initial sample, the leading allocation sites are instead generic
`RuntimeCode.apply` `RuntimeArray` construction (1,630 samples),
`RuntimeArray.get` proxy entries (1,235), `RuntimeCode.apply` `RuntimeList`
wrappers (527), and `RuntimeHash.get` proxy entries (516).  Native JSON
decoding remains CPU-hot in `JsonReader.readValue`/`readObject`, but its
`readString` builder and resulting string allocations are materially smaller
than those generic paths.

Two candidates were tested and discarded.  The unescaped-string scan merely
replaced builder allocation with `substring` string/byte-array allocation.
A scalar-context constant-CV shortcut passed its full gate but left the
dominant list-context copy and still allocated a scalar result wrapper.  Do
not revive either without a controlled parent comparison proving a net gain.
The next JSON structural candidate is a safe reduction of generic
argument-frame `RuntimeArray` construction or proxy-entry materialization;
it must retain `@_` aliasing, lvalue, exception, dynamic-scope, and
control-flow behavior.

A later guarded simple-leaf experiment extended the reusable empty frame to
nonempty calls only when the emitted CV neither referenced `@_` nor dynamic
source and was already proven by `CleanupNeededVisitor` to contain no nested
user calls. It passed the standard-Perl oracle, JVM/interpreter focused test,
and a clean full `make` gate. A warmed allocation capture reduced sampled
`RuntimeCode.apply` `RuntimeArray` construction from 1,630 to 482 events, but
two alternating fresh-process parent/candidate JSON pairs measured only
0.9459x and 1.0099x (about 0.978x mean). The shortcut was discarded. Do not
revive broad argument-frame elision based on allocation samples alone; require
a controlled throughput gain and prioritize proxy-entry materialization or a
more localized call ABI reduction instead.

### JSON native-path missing-option probes (2026-09-10)

The next proxy allocation target was the native JSON eligibility CVs. Their
ordinary configuration has several absent optional hash keys; direct rvalue
reads created `RuntimeHashProxyEntry` objects even though the guard only needs
to decide whether to fall back. The guards now use `exists` before reading an
optional value, preserving present false/undef values and the established
fallback decision while avoiding an absent-slot proxy. Standard Perl's native
canonical test passed, and the clean full `make` gate passed in 6m50s. In a
warmed JFR capture, `RuntimeHashProxyEntry` disappeared from the sampled top
allocation sites (it had previously been 285--516 samples); array proxy
entries remain. Two alternating fresh-process JSON pairs measured 1.4173x and
1.0086x candidate/parent median throughput (1.213x mean). The spread is not
acceptance-quality evidence, but it is a positive localized diagnostic result;
retain the guard and next profile the remaining array proxy entries.

The follow-up applied the same existence-before-fetch rule to sparse optional
indices in the `PROPS` array. A clean full `make` gate passed in 3m48s. A
15-second warmup/20-second JFR capture then removed
`RuntimeArrayProxyEntry` from the ranked allocation sites as well; the leading
remaining allocations are generic `RuntimeCode.apply` arrays/lists and backing
array growth. This is a verified allocation reduction, but it has not yet had
a separate controlled parent/candidate throughput comparison; do not count it
as acceptance evidence.

### Constant-CV call-frame removal (2026-09-10)

The next localized candidate removes an allocation that the generic direct-call
facade made before a constant CV could return: it built a fresh aliased `@_`
`RuntimeArray` even though `RuntimeCode.apply(RuntimeArray, ...)` immediately
returns `constantValue` without observing that frame. The native-array facade
now detects `constantValue` after normal call-target resolution and performs
the same lvalue legality check before returning the constant result. It does
not change argument evaluation, tied/readonly code-reference resolution, or
the instance constant-CV behavior.

The standard-Perl constant oracle passed (45 assertions); JVM and interpreter
`constant.t` each passed (43 assertions). The immutable candidate full `make`
gate passed in 3m58s, while the exact parent `805736a0f` passed its separate
immutable full gate in 3m45s. A fresh 15-second-warmup/20-second JFR capture
reduced sampled `RuntimeCode.apply` `RuntimeArray` construction from 803 to
17 events (the remaining `RuntimeList` result wrapper is expected). In two
alternating fresh-process JSON comparisons against that exact parent, stable
warmups produced candidate/parent median ratios of 1.1223x and 1.1653x
(1.1438x mean). This is a localized retention result, not portfolio acceptance
evidence; the next profile should rank the still-material `RuntimeList`
wrappers, `Arrays.copyOf`, `RuntimeHash.exists` scalar churn, and
`methodArgsWithSelf` frames without weakening `@_` aliasing or call-boundary
semantics.

### Rejected cached hash-exists booleans (2026-09-10)

Returning the existing immutable boolean cache instead of a fresh scalar from
ordinary `RuntimeHash.exists` was tested because JFR attributed 1,386 sampled
scalar allocations to that method on the guarded JSON path. It preserved the
separate tied/autovivifying paths, passed the standard-Perl hash-exists oracle,
the focused JVM/interpreter `exists_hashref_zero` test, and a clean full
`make` gate in 3m34s. A broader interpreter autovivification failure was
checked against the exact parent and is pre-existing.

The exact parent `c90f88f85` passed its own immutable full gate in 3m50s.
Two alternating fresh-process JSON comparisons produced only 1.0151x and
0.9889x candidate/parent median ratios (1.0020x mean), with stable warmups.
Discard the cache substitution: sampled allocation removal is not throughput
evidence here. Continue with a profile-selected operation that reduces a
whole transport or result representation, rather than a small scalar object
alone.

### Historical portfolio triage: closure and method calls (2026-09-11)

A one-pair diagnostic portfolio with 15 warmup and 15 measurement
windows suggested a shift away from JSON as the portfolio limiter: JSON measured
2.5306x Perl and numeric 1.2521x. The stable deficits were closure 0.2261x,
string 0.3913x, life 0.4880x, and regex 0.5359x; method measured 0.2155x but
its PerlOnJava warmup did not stabilize, so it is selection evidence only.
This is not acceptance evidence (one pair only and shortened warmup). The
source/JAR correspondence is also unresolved, as detailed in the audited
start section. Treat closure/call transport as a priority to verify, not an
authoritatively established current bottleneck.

A startup-inclusive JFR capture accompanying 15 warmup and 20 measurement
windows of the closure workload showed `RuntimeCode.apply`, call-frame
bookkeeping and runtime thread-local lookup in sampled stacks. It does not
establish their exclusive steady-state CPU fractions. The workload performs
128 zero-argument closure calls per batch, reported as 128 operations.
`PerlRangeIntegerIterator.next` led the reported allocation-event count (4,297
samples), from the implicit-topic `for (1..128)` loop; this is not a weighted
allocation budget. The existing reusable-topic lowering deliberately rejects that body
because it calls a closure: an arbitrary callee can observe or retain `$_`.
Do not widen the guard merely because this specific benchmark closure does not
read `$_`. The subsequent `cdafea338` metadata commit is not a sound proof of
non-observation: it checks variable references, not all implicit or transitive
effects. Follow the proof and activation gates in the audited start section
before considering any consumer or range-topic candidate.

### Guarded zero-argument closure ABI (2026-09-11)

Issue #1196's exact `benchmark_closure.pl` uses an explicit `return` around a
six-capture addition.  A direct scalar entry now recognizes that terminal
return/list shell, records the capture names in expression order, and uses a
cached resolved-cell vector on ordinary calls.  The vector is guarded by a
per-CV capture-rebinding epoch: `Internals.rebindCapturedVariable` advances
that epoch before its `Devel::LexAlias` or `PadWalker` caller changes a cell,
so the next direct call resolves the current `closedOverVariables` mapping.
Integer, untainted, unblessed, non-wide values use `Math.addExact`; overflow,
aliases, ties, objects, strings, taint, lvalue calls, and every non-matching
body retain the generic call boundary.

`direct_closure_integer_addition.t` passes standard Perl and both backends.
The first cached-cell implementation failed `devel_lexalias_padwalker.t`; the
epoch-authoritative correction passed the full `make` gate in 4m27s under load,
and the focused test passes on both backends. At 20 users and load averages
18.70/29.25/32.26, the pre-epoch issue reproduction ran at 520.31 calls/s;
contemporaneous standard Perl was 613.50 calls/s (0.848x). JFR
`/tmp/closure-alias-authority-20260911.jfr` samples the evaluator body (lines
6189--6193), not generic fallback line 6211.

Two subsequent alternating fresh-process pairs for the epoch candidate,
`/tmp/perf-issue1196-closure-capture-epoch-20260911/20260911T201043Z/portfolio.json`,
had stable warmups and matching checksums. Their medians were 13,025,427 and
13,119,953 PerlOnJava operations/s versus 14,601,253 and 14,765,850 standard
Perl operations/s: 0.8921x and 0.8885x. The preceding two-pair selection on
the same workload measured 0.7824x and 0.8030x; differing host load means this
is directional retention evidence, not a controlled parent/candidate proof.
It nevertheless confirms the cache removes a meaningful steady-state cost
without weakening rebinding semantics. It remains below the 1.05x anchor;
extend the shape only with a separately proven ABI.

### Issue #1196 Life confirmation under load (2026-09-11)

The documented 200x200, 10,000-generation no-display workload completed in
45.147 seconds (9.92 Mcells/s) with 20 users and load averages falling from
19.70/26.68/30.90 to 15.58/24.61/29.90.  JFR
`/tmp/life-issue1196-highload-20260911.jfr` has 1,508 execution samples.
It confirms that dynamic word values are not merely small-integer cache misses:
the hot stacks include `BigInteger.and` through `BitwiseOperators.unsignedResult`,
as well as `currentArgumentAliasFrame` and scalar copies while materializing
`next_generation_parallel(@_)`.  Do not expand scalar caching or borrow that
argument frame. The next Life design must establish a generated, non-escaping
unsigned-word expression representation and a direct argument ABI with explicit
fallback for aliases, references, mutation, control flow, and wide values.

### Native-representable unsigned bitwise results (2026-09-11)

The narrow representation repair keeps `BigInteger` only for upper-half UVs.
When a masked bitwise `BigInteger` result fits a signed native IV,
`BitwiseOperators.unsignedResult` now returns the ordinary native scalar
representation. This preserves Perl's numeric and string results while stopping
32-bit masks from propagating `BigInteger` through later Life expressions.
`bitwise_unsigned_native_result.t` passed standard Perl and both backends; the
full gate passed under load in 5m53s. A same-shape Life run completed in 38.605
seconds (11.60 Mcells/s), versus the preceding 45.147s (9.92 Mcells/s) loaded
baseline. Host conditions differ, so treat the 14.5% reduction as selection
evidence pending paired measurement, not final portfolio evidence.

Two alternating fresh-process pairs in
`/tmp/perf-issue1196-current-20260911/20260911T195858Z/portfolio.json`
provide that first paired selection: closure ratios were 0.7824x and 0.8030x,
while Life ratios were 0.5369x and 0.5184x. Warmups stabilized and semantic
checksums matched. Host load changed from 7.54/19.19/26.46 to
18.12/18.81/24.43 during the run, so retain the small sample as a directional
post-change baseline; it proves both anchors remain below the 1.05x target.

### Complete rebased issue #1196 portfolio (2026-09-11)

The exact rebased checkout completed the full acceptance protocol: seven
alternating fresh-process pairs for every scored workload, fifteen one-second
windows per process, stable warmups, and matching semantic checksums. The
source then passed its immutable full `make` gate in 3m53s. The artifact is
`/tmp/perf-issue1196-rebased-full-20260911/20260911T202130Z/portfolio.json`;
its report is
`/tmp/perf-issue1196-rebased-full-20260911-analysis.json`. It began with 20
users at load 6.95/11.65/15.54 and remained realistically contended (observed
one-minute load reached 27.52 during Life), yet every warmup stabilized. The
report therefore marks it authoritative and a decisive negative result.

The workload-median geometric mean is 0.7003x standard Perl (bootstrap 95% CI
0.6858--0.7291), far below the 1.05x objective. Closure is 0.8684x
(0.8646--0.9050), an improvement over the preceding two-pair cache selection
but still below its anchor; Life is 0.5093x (0.5032--0.5326). Method remains
the minimum at 0.2265x; string and regex are 0.5363x and 0.5060x;
numeric is 1.2045x and JSON 2.5212x. Retain the capture-epoch cache, but do
not claim parity or spend another iteration on its result-wrapper mechanics.
The next implementation target is the independently dominant method-call
boundary, with a guarded direct argument representation and explicit aliases,
recursion, dynamic-scope, lvalue, exception, and control-flow fallback proof.

### Rebased method allocation selection (2026-09-11)

A fresh bounded JFR recording of the current method workload is
`/tmp/method-current-rebased-20260911.jfr` (60 seconds, profile settings;
`/tmp/method-current-rebased-20260911.log`, exit 0). The workload reached a
stable warmup despite the loaded host. Its allocation events must not be read
as an exact byte ledger, but their structural attribution is decisive: 5,530
`RuntimeScalar` samples originate in generated `anon583.apply`, the benchmark
method's `$self->{x/y} += $n` body. Only 27 `RuntimeArray` samples originate
at `methodArgsWithSelf`; broad frame reuse is therefore still the wrong next
experiment. CPU sampling is sparse (18 samples) but independently retains
`invokeWithCallFrame`, `enterCalleeWarningScope`, `exitCall`, scalar result
coercion, and `RuntimeScalar` hash dereference on the active path.

The next candidate must be a generated-method, scalar-context lowering for a
plain unblessed hash receiver, literal key, native-integer compound update,
and immediate scalar use. It needs a generic fallback for ties, overload,
blessing, references, lvalue observation, aliases, mutation, warnings,
exceptions, dynamic callers, recursion, and non-local control flow. Do not
reuse the argument frame or replace general hash entry semantics merely because
this benchmark method is simple.

### Rejected: broad wide-UV bitwise word conversion (2026-09-11)

Life still sampled `BigInteger.and` after the retained narrow unsigned-result
repair. A candidate therefore performed `&`, `|`, and `^` directly on the low
64-bit Java words for every INTEGER operand, including upper-half UV
`BigInteger` values. A new standard-Perl oracle and both PerlOnJava backends
passed, and the immutable full `make` gate passed in 3m44. The candidate is
nevertheless rejected: two checksum-matched, stable alternating Life pairs in
`/tmp/perf-life-wide-word-20260911/20260911T212127Z/portfolio.json` measured
0.5006x and 0.4969x Perl, below the retained rebased portfolio's 0.5093x
Life median. Do not revive this broad conversion from allocation intuition;
the next Life candidate needs an expression-level, non-escaping proof and a
material paired gain.

### Source-matched regex matcher-lifecycle selection (2026-09-11)

The first regex JFR taken after rejecting the wide-UV candidate is invalid as
selection evidence: its development JAR still contained that candidate even
though the source had been restored. It was allowed to finish without mutating
the checkout, then the exact restored source passed a fresh immutable `make`
gate in 4m08s (commit `e6667430f`). The replacement, source-matched recording
is `/tmp/regex-source-matched-rebased-20260911.jfr`; its companion workload
log exited 0 with a stable warmup and checksum `1024` under the loaded host.

The 60-second profile contains 3,772 execution and 17,730 allocation samples.
The Joni engine is still a material cost (`ByteCodeMachine.executeSb`,
`Matcher.search`, and `JoniRegexMatcher.find`), but matcher lifecycle now has
an independent non-engine budget: `ThreadLocalMap.getEntry` is the leading
top frame (477 samples), and JFR attributes 6,127 sampled
`JoniRegexMatcher` wrapper allocations. The feature-free native matcher is
already pooled, so this is wrapper creation and pool lookup rather than a
reason to remove Joni pooling. Position publication (`RuntimePosLvalue`) and
warning checks are visible but much smaller.

Do not pool `JoniRegexMatcher` by simply rebinding it. A successful wrapper is
installed as `regexState.globalMatcher` for later capture and match-variable
queries; named captures can also read its underlying matcher. The next regex
candidate is therefore a post-success immutable capture snapshot for eligible
feature-free, unnamed-capture patterns, followed by a runtime-local recyclable
execution cursor. It requires explicit fallback for named/physical captures,
callbacks, control verbs, locale, deferred properties, alarms, `/g` retry,
`\\G`, and any observable saved-match state. Establish the oracle and guard
hit rate before implementation, and accept it only with checksum-matched
alternating pairs that materially improve the 0.5060x portfolio anchor.

### Rejected: runtime-owned Joni matcher-pool lookup (2026-09-12)

The first narrow implementation moved feature-free Joni matcher pools from a
per-pattern `ThreadLocal` to auxiliary state owned by the active
`RuntimeRegexState`; direct matching passed the already-resolved state down to
the Joni adapter. Low-level Java users that deliberately have no bound
`PerlRuntime` retained the previous per-pattern fallback pool. This preserved
runtime and ithread ownership rather than sharing mutable matchers across
threads. The candidate initially exposed that no-runtime boundary in Joni unit
tests, was corrected, and then passed its complete immutable `make` gate in
3m53s.

It is rejected on measurement, not correctness. A detached parent worktree at
`9c39ad5a6` and candidate `227174c33` both received complete gates, then seven
checksum-matched, fresh-process, alternating regex pairs ran under the loaded
host. The durable artifact is
`/private/tmp/perf-regex-parent-candidate-20260911.json`. Every pair returned
checksum `1024`; ratios were 0.9990, 1.1082, 1.0955, 1.0120, 0.9948, 0.9735,
and 1.0011x candidate/parent. The median is 1.0011x and geometric mean 1.0251x,
but the final two pairs did not stabilize their warmups, so the artifact is
explicitly non-conclusive. Even the stable subset does not establish a
material, order-robust gain sufficient to justify a new runtime cache and
embedding fallback. Revert this candidate; profile the remaining Joni engine
budget or a provably snapshot-safe cursor design instead.

### Regex cursor/snapshot ownership boundary (2026-09-12)

Source inspection refines the remaining regex design. `JoniRegexMatcher.find`
already returns its native Joni `Matcher` to the per-pattern, per-thread pool
in its `finally`; the allocation still visible in JFR is the Java
`JoniRegexMatcher` wrapper. It cannot simply be pooled because
`RuntimeRegex.match` and substitution publish it as
`RuntimeRegexState.globalMatcher`, and `$1`, `@-`, `%+`, `$^R`, `pos`, and
failed-match preservation can subsequently read it.

The safe split is therefore an execution cursor plus an immutable
`RegexMatcher` snapshot. On each successful match, the cursor must copy its
numbered capture strings and bounds, named-group map where eligible, visible
start/end, consumed start, last-closed capture, control state, pattern
description, and source input into the snapshot before publication. The local
cursor must remain live through a `/g` loop; only when the owning top-level
operation has finished may it return to a bounded runtime-local cursor pool.
That means snapshotting cannot be deferred until the next regex operation.

The first implementation must exclude named/physical captures and code-block
captures (`$^R`), callbacks, control verbs, deferred properties, locale,
alarms, `\\G` retry state, and all match paths that return a matcher for a
later operation. Its permanent oracle must prove capture/offset preservation
after a succeeding match, a following failed match, a pooled cursor rebind to
a distinct subject, scalar and list `/g`, and substitution. Only then collect
guard-hit diagnostics and measure against the current 0.521463x regex anchor.

### Rejected: zero-capture cursor snapshot pool (2026-09-12)

Commit `fbbff23a0` implemented the smallest version of that design: only
non-locale Joni patterns with no captures or named groups, callbacks, control
verbs, deferred properties, non-Unicode warning handler, or alarm support
could publish an immutable overall-match view and return their Java cursor to
one pattern/thread-local idle slot. The focused oracle passed unchanged on
system Perl and on both PerlOnJava backends; the candidate also passed the
full immutable `make` gate in 5m17s. The detached parent `e49982b8d` passed
its own full gate in 5m18s.

Seven fresh-process, alternating high-load regex pairs then used 15 fixed
warmup windows and 15 one-second measured windows per side. Every result
returned checksum `1024`. Candidate/parent median-throughput ratios were
0.9236, 0.9117, 1.1540, 0.9634, 0.9609, 0.8838, and 0.9162x. The pair median
was 0.9236x and the geometric mean was 0.9558x; the lone improvement was
unstable, while no stable pair improved. This is a material regression, so
the pool was removed. Its system-Perl-validated oracle is retained as permanent
coverage for zero-capture match-state publication. Do not revive the
zero-capture snapshot implementation: the allocation reduction loses to its
publication and pooling overhead under realistic load. Any later cursor design
needs a different non-overlapping cost argument and a broader lifecycle proof.

### Rejected: native-integer comparison shortcut (2026-09-12)

Commit `3d36a80a0` used `Long.compare` when both `INTEGER` payloads were
ordinary Java `Number` values, retaining the `BigInteger` path for wide
values. The new numeric comparison oracle passed on system Perl and on both
PerlOnJava backends, and the candidate full immutable `make` gate passed in
4m10s; its detached parent `8aeac037c` passed in 3m46s.

Seven fresh-process, alternating high-load numeric pairs used 15 fixed warmup
windows and 15 one-second measured windows per side. Every result returned
checksum `37478`. Candidate/parent median-throughput ratios were 0.9157,
0.9763, 1.0068, 1.0204, 0.9951, 0.9845, and 0.9636x. The pair median was
0.9845x and geometric mean 0.9798x; several parent warmups were unstable, but
the fully stable pairs also showed no material gain. The shortcut was removed,
while its system-Perl-validated numeric regression test remains permanent
coverage. Do not repeat this `Number` type-check path without a materially
different cost model.

### Method lexical-copy bytecode attribution (2026-09-12)

After restoring the rejected regex source, the immutable full `make` gate
passed in 3m48s, rebuilding the source-matched development JAR. A bounded,
filtered ASM trace of the current method workload is
`/tmp/method-anon583-asm-20260912.log`. It resolves the earlier allocation
profile's ambiguous generated-frame attribution: at the entry to generated
`anon583.apply`, the immediate `my ($self, $n) = @_` unpack emits exactly two
`new RuntimeScalar()` cells before `RuntimeCode.resolveLexicalAlias`. The
literal `x` and `y` keys already use occurrence-local `materializeLiteralPad`,
and `MathOperators.addAssign` updates the native-integer hash slots in place.

The next method candidate is consequently execution-local reusable *copy
cells*, not literal-key caching, arithmetic specialization, or direct alias
binding. It must retain ordinary copy semantics: later mutation through `@_`,
references to an unpacked lexical, recursive re-entry, string eval, dynamic
lexical access, destruction lifetime, and every callback/control-flow path
must fall back to fresh cells. The permanent
`direct_argument_binding_guard.t` already demonstrates why borrowing argument
cells directly is incorrect. Before implementation, define a whole-body
non-escape proof for a narrow generated method shape and add selected/rejected
coverage for the pooled-copy lifecycle; only then measure it against the
0.2265x method anchor.

### Rejected: guarded direct two-field method update (2026-09-12)

The next narrow candidate recognized only the exact body used by the method
workload: `my ($self, $n) = @_`, native-integer `x` and `y` compound updates,
and their returned sum. Its runtime entry rejected non-scalar context,
overflow, ties, `%{}` overload, shared/proxy/tainted values, missing slots,
and every non-ordinary integer before mutation. The permanent
`direct_method_hash_update_guard.t` passed standard Perl plus both PerlOnJava
backends, including tied-hash FETCH/STORE and overloaded hash-dereference
fallbacks. The candidate's complete gate passed in 3m32s; an ASM trace proved
the marker was emitted for the dynamic benchmark CV.

It is nevertheless rejected. The exact detached parent `cd20d4b77` and
candidate `3c466e202` both passed complete gates, then seven checksum-matched,
fresh-process, alternating method pairs ran under realistic host load with 60
one-second warmup windows and 15 measured windows per process. The append-only
pair artifact is `/private/tmp/perf-direct-method-parent-candidate-20260912-pairs.ndjson`;
its finalized summary is
`/private/tmp/perf-direct-method-parent-candidate-20260912.json`. All pairs
returned checksum `4352`. Candidate/parent ratios were 1.0183, 1.1007,
1.0304, 0.8844, 1.0277, 0.9627, and 1.0255x; pairs 2 and 3 had unstable
warmups. The all-pair median is 1.0255x and geometric mean 1.0051x, below the
10% retention bar and non-conclusive under the loaded host. The source was
restored and its final complete `make` gate passed in 3m46s. Do not revive this
direct method bypass: it adds a highly specialized semantic surface without a
material, order-robust reduction. Continue instead with reusable fresh copy
cells only after proving their complete escape and lifetime boundary.

The implementation boundary for that next candidate is now explicit. The
generated body must acquire a leased *fresh* scalar rather than allocate and
then replace one; alias substitution after `new RuntimeScalar()` cannot reduce
the measured allocation. Lease ownership belongs to the active
`RuntimeCode.invokeWithCallFrame` execution frame, whose `finally` covers
ordinary return, exceptions, and non-local control flow. Do not release from
generated return labels alone. Static eligibility must exclude all lexical
escape/dynamic-source paths, while runtime eligibility must reject an active
lexical alias, debugger mode, and every value shape that can invoke Perl code
(tie, overload, autovivification, shared/proxy, or non-native scalar). Recursion
requires one independent leased pair per active call depth. Build those
selected/rejected lifecycle tests before changing the lowering, then measure
the allocation reduction against the exact current parent under the same
alternating high-load protocol.

An implementation audit adds a further exclusion: normal JVM scope exit calls
`RuntimeScalar.scopeExitCleanup` and then nulls the local slot. That mutates
cell lifecycle state beyond its value (capture/scope-exit state, owned
references, IO and weak-reference bookkeeping). A shallow `RuntimeScalar[]`
pool is therefore not a valid first implementation: reusing a cell would need
an audited complete reset-and-release protocol, not merely `set(undef)`, and
would risk changing destruction timing. Do not add that pool until its reset
contract is independently specified and tested. Prefer a representation that
keeps the original ordinary lexical cells, or demonstrate a bounded
integer-only cell type whose lifecycle is provably empty on both acquisition
and release.

### Method source-matched allocation selection (2026-09-12)

A fresh 60-second source-matched JFR recording,
`/tmp/method-copy-cell-selection-20260912.jfr`, ran the method workload with a
stable 60-window warmup and checksum `4352` under realistic load. Its dominant
selected CV, `anon583` (the generated `add` body), accounts for 8,018 sampled
`RuntimeScalar` allocations; the enclosing workload CV `anon584` accounts for
2,602. The allocation counts are the extracted event counts in
`/tmp/method-copy-cell-selection-20260912-anon583-alloc-counts.txt` and
`/tmp/method-copy-cell-selection-20260912-anon584-alloc-counts.txt`.

The same CPU capture shows only sparse samples in
`isCurrentArgumentAlias`, `setFreshScalarsFromArgumentArray`, and deferred
decrement helpers. Do not redirect this candidate toward a general alias-check
micro-optimization. The next representation experiment may instead borrow the
already-aliased `@_` scalar only when the whole body and runtime values prove
that its independent lexical identity is unobservable. Generated scope cleanup
must skip such borrowed locals; if the runtime guard selects fresh fallback
cells, the active `invokeWithCallFrame` `finally` must clean those cells before
the call returns. This is a different ownership model from pooling and needs
focused selected/borrowed/fallback/recursion tests before implementation.

### Rejected: guarded immediate method-lexical borrowing (2026-09-12)

The resulting narrow experiment marked only the exact source-matched `add`
body, then borrowed the two argument scalars for `$self` and `$n` only when
the runtime frame had exactly two ordinary, unshared, untainted native values,
the receiver was a plain hash with plain native-integer `x` and `y` slots, and
there was no debugger or lexical-alias state. Every other call took fresh
cells. The active call frame owned the fallback cells and cleaned them in its
`finally`; generated scope cleanup excluded only locals known to be
call-frame-owned. The permanent direct-method guard continued to pass under
system Perl and both PerlOnJava backends, and the candidate's complete `make`
gate passed in 4m14s.

It is rejected on measurement. Exact parent `71d4a5cb9` and candidate
`69fe9a51a` were independently built, then measured in seven alternating,
fresh-process method pairs under the loaded host (60 one-second warmup windows
and 15 measured windows per process). All warmups stabilized and every run
returned checksum `4352`. Candidate/parent ratios were 0.9893, 0.9623,
0.9519, 0.9350, 1.0306, 0.9101, and 0.9480x: median 0.9519x and geometric
mean 0.9604x. The append-only pair artifact is
`/private/tmp/perf-borrow-fresh-method-parent-candidate-20260912-pairs.ndjson`;
the finalized summary is
`/private/tmp/perf-borrow-fresh-method-parent-candidate-20260912.json`.
The bookkeeping and conservative shape checks cost more than the eliminated
allocations. The source has been restored to the parent representation. Do not
revive argument-cell borrowing for this workload without an allocation profile
showing a materially cheaper ownership protocol and a fresh exact-parent
comparison.

### Closure result and range-topic selection (2026-09-12)

A source/JAR-matched, 76-second JFR plus call-layer diagnostic ran the current
closure workload at source `46b67d06f` and JAR SHA-256
`2106d5ed5caca96bb378703217a9d829f3fba3e32a88217598da5b2e22e9e5bd`.
The artifact is
`/tmp/perf-closure-current-jfr-20260912/20260912T002954Z/closure-pair-01.jfr`;
the paired portfolio and call-layer report are in that same directory. The
host recorded load averages 3.98/6.92/7.88. Both engines returned checksum
`9216`; PerlOnJava's forced warmup stabilized, while standard Perl's did not.
Accordingly its 0.7141x instrumented pair ratio is not throughput evidence.

The retained direct-leaf closure path is selected: its `new RuntimeScalar(sum)`
site in `RuntimeCode.applyDirectLeafIntegerAddition` appears in 2,591 sampled
`RuntimeScalar` allocation events. The generated outer closure's range
iterator appears in 9,398 of the 12,192 scalar allocation samples, and
`MathOperators.addAssign` boxing appears in 7,408 samples; these categories
overlap and must not be added into a byte estimate. CPU stacks also contain
the direct result-list acquire/recycle path and `invokeCallable`, but the
instrumented call-layer data is not exclusive enough to select a general
call-frame rewrite.

The next proof target is therefore the range topic, not another method-cell
pool: determine whether a generated `for (integer range)` body can establish
that its implicit topic is unobservable for the full dynamic call graph. The
existing `doesNotObserveDynamicTopic` metadata is explicitly insufficient.
Only a selected path that proves every invoked CV remains the guarded direct
leaf, with an ordinary iterator fallback before any rebinding, could reuse an
ephemeral topic cell. It must cover code-ref replacement, aliases, callbacks,
`eval`, caller/debugger inspection, overload/tie, recursion and exception
re-entry. If that proof cannot be made generic, leave range iteration alone
and instead measure a scalar-result transport candidate against its exact
parent.

### Rejected: guarded direct-leaf range-topic reuse (2026-09-12)

The first implementation recognized exactly one implicit-topic range body:
a simple lexical accumulator `+=` a zero-argument lexical direct call. At
iterator creation it called a runtime guard that required debugger and taint
mode off, exact ordinary code and accumulator scalar classes, a guarded
direct-leaf integer-addition CV, and an unwatched, unblessed native-integer
accumulator without live substr observers. It otherwise selected the ordinary
iterator. The permanent `for_loop_test.t` extension passed system Perl (35/35)
and both PerlOnJava backends (35/35), including overloaded accumulator and
captured-overload callbacks that retain `\\$_` and therefore require distinct
topic cells. The candidate full `make` gate passed in 3m44s; exact parent
`ea4a4b44a` passed separately in 4m01s.

It is rejected on measurement. Seven alternating fresh-process closure pairs
used forced 60-window warmups and 15 one-second measurement windows under the
loaded host. Candidate/parent ratios were 0.9892, 1.0401, 0.9999, 0.9750,
0.9917, 1.0147, and 0.9436x; pair 3 and pair 5 had unstable warmups. The
all-pair median is 0.9917x and geometric mean 0.9931x, below the material-gain
bar and non-conclusive under the stability protocol. The append-only evidence
is `/private/tmp/perf-direct-leaf-range-parent-candidate-20260912-pairs.ndjson`
and the final summary is
`/private/tmp/perf-direct-leaf-range-parent-candidate-20260912.json`.
The source has been restored to the parent representation. Do not revive this
guard unchanged: its runtime checks consume the allocation saving. A later
range-topic effort needs a broader, cheaper effect proof with a measured
non-overlapping CPU budget, not a closure-workload recognizer.

### Current string-concatenation selection (2026-09-12)

A source/JAR-matched 76-second JFR selection run of the current string
workload is `/tmp/perf-string-current-jfr-20260912/20260912T011520Z/`.
It recorded source `1404109e5b83a389e9125ccf809d2214d649e200`, JAR SHA-256
`6e80aac4138ddab65bab0659f5912ae14f137496ca63cd0c9264961e74055469`,
checksum `24`, stable warmups, and host load averages 7.16/10.21/8.80. Its
one-pair 0.5357x Perl throughput is profiling-selection evidence, not an A/B
claim. CPU samples select `StringOperators.stringConcatWarnUninitialized` as
the leading string-specific non-boundary cost. Allocation samples rooted there
include 7,687 `RuntimeScalar`, 2,097 `String`, 230 `byte[]`, and temporary
`RuntimeScalar[]` allocations. Those sample categories overlap; they are not a
byte ledger.

### Rejected: fixed-arity concat taint propagation (2026-09-12)

The selected allocation observation led to a deliberately narrow candidate:
replace the two-input varargs call to `propagateTaint` with a fixed-arity
helper, retaining the variadic helper for genuine multi-input callers. The
standard-Perl byte-string oracle passed (2/2), and the candidate's immutable
full `make` gate passed in 3m57s. The exact parent gate passed in 3m37s.

Seven fresh alternating string pairs compared parent source
`1404109e5b83a389e9125ccf809d2214d649e200` / JAR
`5e3b0851f6def78b8865edc027e12a79d3a8e3bba79fc09722e4b38f672268c9`
against candidate `f528a9ba6d574b90e32520831795caa170ba1a15` / JAR
`f787a148dd0fe82d116ab9c3698cabf2e7116f5c2f6b7a7af8732deb87e31f28`.
All checksums were `24` and every warmup stabilized. The candidate/parent
PerlOnJava ratios were 1.0376, 0.9857, 1.0210, 0.9947, 0.9898, 0.9395, and
0.9213; median 0.9898x and geometric mean 0.9835x. The candidate also ran at
lower recorded load (4.29/7.06/8.69 versus 8.67/11.01/10.28), so this is not
evidence of a gain hidden by greater contention. Raw portfolios are
`/tmp/perf-string-taint-parent-20260912/20260912T013119Z/portfolio.json` and
`/tmp/perf-string-taint-candidate-20260912/20260912T013756Z/portfolio.json`.
The source has been restored to the parent representation. Do not retry this
helper split alone: the allocation it avoids is below the material performance
threshold. Select the next string candidate from a source-matched CPU/allocation
budget that isolates a larger cost than generic taint propagation.

### Rejected: guarded ordinary string-concat fast path (2026-09-12)

The next candidate recognized only exact base `RuntimeScalar` byte-string,
string, and integer operands with no taint metadata and no active `bytes`
pragma. It returned before warning, tie, overload, and taint logic only when
those semantics were impossible; all other operands retained the existing
path. The strengthened byte-string/integer oracle passed on standard Perl
(4/4), and the candidate's full `make` gate passed in 3m34s. The exact parent
gate passed in 3m58s.

Seven fresh alternating string pairs compared parent source
`0d2b27db7581ce6d92f4ce5d3751a869ec2f53b5` / JAR
`d96388b9669a3acc273361ce82ac5786c82567f1f6fbbf90e2c87b0fce95fa95`
with candidate `648400dc7e0edf3088231dc0e0a9790688d94826` / JAR
`f167c908986c9c54e7f11efda0ff287e92bf13de43da9d41bf33e28fd5572fdf`.
All checksum values were `24` and every warmup stabilized. Candidate/parent
PerlOnJava ratios were 1.0226, 0.9945, 1.0135, 1.0527, 1.0112, 0.9908, and
0.9985; median 1.0112x and geometric mean 1.0118x. This is below the material
gain threshold, particularly because the candidate's recorded host load was
lower (4.98/7.60/9.27 versus 10.85/13.25/11.45). The raw portfolios are
`/tmp/perf-string-plain-parent-20260912/20260912T020222Z/portfolio.json` and
`/tmp/perf-string-plain-candidate-20260912/20260912T020855Z/portfolio.json`.
The source has been restored to the parent representation. Do not revive this
runtime guard unchanged: its checks erase most of the small dispatch saving.
The next string candidate must remove a larger expression-level temporary or
select a non-overlapping CPU cost from a fresh profile.

### Current method allocation refresh (2026-09-12)

The current source-equivalent JFR selection run is
`/tmp/perf-method-current-jfr-20260912/20260912T022133Z/`. It recorded source
`bb92383a962036b7d0feeed078a633a125b23558`, JAR SHA-256
`b93f3e0d3160505b866b51d318bbb862c84d7c7ea9421b9a2a1088f128ee80f7`,
checksum `4352`, and host load averages 6.47/9.76/9.24. The 76-second
recording has 18,349 allocation samples. Standard Perl's forced warmup
stabilized, but PerlOnJava's did not; its instrumented timing is therefore
not comparison evidence.

The allocation selection remains decisive: generated method body `anon583`
accounts for 7,213 sampled `RuntimeScalar` allocations, the outer method
workload's range iterator for 4,002, and `MortalList.queueDeferredBase` for
2,356 `WeakReference` samples. The latter follows real lifecycle ownership
and is not a safe cleanup micro-optimization. The method's reusable immediate
`@_` frame appears only as 32 sampled `RuntimeArray` allocations, so extending
that representation cannot close the method gap. Do not revive direct
argument-cell borrowing or the direct two-field bypass: both were measured and
rejected. The only justified next method experiment is a fresh, bounded,
integer-only lexical-cell representation with a whole-body non-escape proof,
per-depth ownership, and fallback coverage for aliases, recursion, callbacks,
dynamic source, lvalue observation, exceptions, and destruction lifecycle.

### Method lexical-cell reuse ownership contract (2026-09-12)

Source inspection fixes the boundary for that experiment. The existing
`reusableImmediateMethodArgs` optimization borrows only a two-element
`RuntimeArray` from `ExecutionRuntimeState`; `anon583.apply` still creates its
two `RuntimeScalar` lexical cells before calling `RuntimeCode.resolveLexicalAlias`.
The reusable cells therefore cannot live on a `RuntimeCode`: a recursive call
of the same CV needs distinct cells, and an active lexical frame exposes each
call's cells to debugger and dynamic-source machinery while that call is live.

If implemented, a candidate must attach a two-cell pad exclusively to the
already borrowed argument frame. `pushArgs` makes that frame current before
generated body execution and `popArgs` is the sole release boundary, so a
frame-local pad gives recursion a distinct allocation and makes reuse possible
only after both the argument and active-lexical frame have been removed. The
compiler must emit the borrowed cells only for one exact integer-only body
shape: immediate two-scalar `my ($self, $n) = @_`, no additional declarations,
closures, eval STRING, runtime regex source/callbacks, references to either
lexical, `local`, `state`, aliases, callbacks, exception/control-flow edges,
or later `@_` observation. Every other CV must keep the existing fresh-cell
path.

`RuntimeCode.resolveLexicalAlias` remains mandatory at each declaration. If a
LexAlias replacement is configured, the candidate must bypass the pooled cell
for that slot and keep the replacement as the active lexical binding; it may
not return a replacement cell to the pool. The permanent oracle must cover
normal copy isolation from `@_`, recursive re-entry, reference capture,
eval-STRING visibility, LexAlias/tied destination behavior, and object
destruction after `@_` releases its alias. Only after those fallback cases are
proved on system Perl and both backends should a frame-local implementation be
measured against the method workload's 0.2265x Perl anchor.

### Source-matched loaded-host method baseline (2026-09-12)

The current source-matched JAR was built from `dcbd70114`
(`b53f23cb74e021f6f85f537dab9736023da5d029a13a9a3f2bf04aef816d4976`);
its immutable full `make` gate passed in 3m39s. A seven-pair method portfolio
then completed under realistic host load 8.45/10.49/9.65. Every Perl and
PerlOnJava process returned checksum `4352`, and every warmup stabilized.
Median throughputs and candidate/Perl ratios were: 1.642993M/7.377522M
(0.222703x), 1.580538M/7.288576M (0.216851x), 1.562563M/7.232775M
(0.216039x), 1.595596M/7.321138M (0.217944x), 1.509404M/7.095302M
(0.212733x), 1.531341M/7.188146M (0.213037x), and
1.536747M/7.021425M (0.218865x). The median is 0.216851x and geometric mean
is 0.216858x. The durable raw artifact is
`/tmp/perf-method-baseline-20260912/20260912T030857Z/portfolio.json`.

This is the current method anchor for the frame-local lexical-cell experiment.
It confirms a large, stable deficit rather than a warmup artifact; a candidate
must make a material improvement while retaining the ownership contract above.

### Rejected: frame-local method lexical-cell reuse (2026-09-12)

Candidate `b034bc670` recognized only the exact four-statement method body in
the method workload: immediate `my ($self, $n) = @_`, two literal-key `x`/`y`
compound updates, and their returned sum. It borrowed two cells only from the
already execution-local reusable argument frame, cleared them with
`RuntimeScalar.undefine()` after the active lexical frame left scope, and kept
the generic path for every other body shape, debugger mode, and LexAlias
replacement. The permanent six-assertion oracle covered repeated calls,
tied-hash FETCH/STORE behavior, and overloaded hash dereference; it passed
system Perl, the JVM backend, and the interpreter. The candidate's source-
matched full `make` gate passed in 3m40s.

Seven fresh-JVM pairs compared parent `dcbd70114` with candidate `b034bc670`.
All candidate samples had checksum `4352` and stabilized warmups. Candidate/
parent ratios were 0.994300x, 1.050332x, 1.031990x, 0.993138x, 1.032211x,
1.088421x, and 1.015022x (median 1.031990x; geometric mean 1.028886x).
The parent recorded host load 8.45/10.49/9.65 and the candidate 9.06/12.89/
11.86, so this already-small result cannot justify a micro-optimization under
the structural 10% selection bar. Raw artifacts are
`/tmp/perf-method-baseline-20260912/20260912T030857Z/portfolio.json` and
`/tmp/perf-method-lexical-cells-candidate-20260912/20260912T033328Z/portfolio.json`.

Revert the candidate. Do not revive this exact frame-local cell strategy;
though its ownership proof is sound, it does not close enough of the 0.2169x
method gap. The next method selection must target a larger call-boundary or
per-iteration allocation source with an independently material Amdahl budget.

### Post-revert loaded-host allocation refresh (2026-09-12)

The restored source at `616a84485` received one fresh method JFR portfolio at
`/tmp/perf-method-post-revert-jfr-20260912/20260912T034550Z/portfolio.json`.
The 76-second recording has 19,197 allocation samples; both engines returned
checksum `4352`, and PerlOnJava stabilized its 60 one-second warmup windows.
Standard Perl did not stabilize under host load 9.94/12.57/11.62, so this is
allocation-selection evidence only, not a new throughput anchor.

The JFR confirms 9,579 sampled `RuntimeScalar` allocations in generated
`anon583.apply` (40.94 GB sampled weight), followed by 3,132 in the observable
`for 1 .. 64` iterator (13.35 GB). The latter cannot be generically reused:
the method body can observe or retain implicit `$_`. `registerActiveLexical`
accounts for 1,668 `HashMap.Node` samples (7.06 GB), but its active frame and
map are already recycled; each remaining node represents a live lexical
identity that DB eval, runtime regex source, PadWalker, or Devel::LexAlias may
observe. Do not elide that registration without an explicit whole-CV
non-observability proof and a new material Amdahl budget. The next viable
method work therefore remains a larger call-boundary representation change,
not iterator or registry pooling.

### Dense method CPU selection under load (2026-09-12)

The default JFR execution sampling was too sparse to rank the restored method
path, so a bounded 1 ms capture ran its 60-window warmup and 15-window method
workload at current source `0486edf89`. Its command was guarded by `timeout
180`; it returned checksum `4352` and wrote
`/tmp/perf-method-cpu-1ms-20260912.jfr` (18,048 allocation samples and 424
execution samples). Instrumentation made its warmup unstable, so this is CPU
selection evidence rather than throughput evidence.

Filtering to the final 20 seconds leaves 222 execution samples. The leading
exclusive sites are `ArrayList.removeLast` (40),
`MortalList.processDeferredEntriesFrom` (33),
`RuntimeBase.releaseTransientTraceOwner` (27),
`IdentityHashMap.get` (21), and `MortalList.flushAboveMark` (13). The same
tail has `MortalList.flushAboveMark` in 132 inclusive stacks, followed by
`RuntimeArray.setFromList` (127) and
`RuntimeBase.setFromListDiscardResult` (91). This explains why removing only
lexical allocation, active-pad registration, or a result wrapper did not
produce a material method gain: a copied `$self` can own a counted blessed
reference and scope exit must preserve deferred release, weak-reference, and
dynamic `DESTROY` behavior.

Do not elide scalar cleanup merely because the benchmark class currently has
no `DESTROY`; Perl can install lifecycle behavior dynamically and a callback
can expose it. Any next call-boundary candidate must instead establish an
independent, whole-invocation proof for a non-owning representation or an
explicit dynamic fallback. The fresh-unpack helper is not a sufficient Amdahl
target by itself.

### Rejected: disabled trace-owner monitor elision (2026-09-12)

Candidate `f98f11c4d` moved the immutable `PJ_REFCOUNT_TRACE` and per-referent
trace-disabled checks ahead of synchronization in transient-owner acquire and
release. The enabled path rechecked the flag inside the original monitor, so
diagnostic accounting remained serialized; the full `make` gate passed in
4m06s, and `owner_trace_snapshot.t` passed 3/3 with
`PJ_REFCOUNT_TRACE=1` and `PJ_REFCOUNT_TRACE_CLASS=OwnerTrace`.

The exact parent `c1899e87a` and candidate both completed stable,
protocol-compliant seven-pair method portfolios with checksum `4352` in every
process. Parent load was 10.88/13.14/11.12 and candidate load 6.50/7.51/8.96.
Candidate/parent PerlOnJava throughput ratios were 0.976573x, 1.009858x,
0.982069x, 1.016435x, 1.054494x, 1.029129x, and 1.013254x: median 1.013254x
and geometric mean 1.011386x. Artifacts are
`/tmp/perf-trace-owner-parent-20260912/20260912T041612Z/portfolio.json` and
`/tmp/perf-trace-owner-candidate-20260912/20260912T042250Z/portfolio.json`.

Revert the candidate. The monitor removal is semantically safe but cannot
close the material method gap, and the different host loads only strengthen
the decision not to retain this sub-threshold micro-optimization. Future work
must select a larger ownership or call representation change.

### Current loaded-host closure baseline (2026-09-12)

The current source at `0f13ab520` completed a fresh, closure-only,
protocol-compliant portfolio at
`/tmp/perf-closure-current-highload-20260912/20260912T035250Z/portfolio.json`.
All seven alternating fresh-process pairs returned checksum `9216` and every
Perl and PerlOnJava warmup stabilized under host load 4.91/7.08/9.24. The
source-matched JAR SHA-256 is
`6eca0720c54040b6841b49a6a96a1612a4e5184a7325412448b34f80c83cc79a`.

The closure ratio is now 0.902117x geometric mean (median 0.896934x; 95% CI
0.888523--0.917030), versus standard Perl. This is the first current stable
high-load closure baseline after the retained direct-leaf lowering, and it
supersedes earlier closure measurements whose warmups were unstable or whose
source predates later call-boundary work. It remains below the handoff's 1.00x
per-workload lower-bound requirement, so parity is not achieved. The result
does establish that the remaining gap is about 11%, making a broad
call-boundary representation improvement the next justified closure target;
do not infer a further benefit from rejected range-topic or scalar-cell
micro-optimizations.

### Rebased closure refresh under realistic load (2026-09-12)

After the careful rebase and source-matched full gate, commit `86b5032e6`
completed a fresh default seven-pair closure portfolio at
`/tmp/perf-closure-rebased-highload-20260912/20260912T050725Z/portfolio.json`.
All pairs completed with the expected checksum and stable warmups; the
repository analyzer classified the result `authoritative: true` and
`measurement_quality: stable` for this one workload. The closure geometric
mean and median were both 0.868894x Perl, with a paired bootstrap interval of
0.844121--0.893513x. Pair ratios were 0.868894x, 0.812168x, 0.930739x,
0.876171x, 0.855608x, 0.875806x, and 0.860662x.

This is a refreshed loaded-host closure measurement, not portfolio acceptance:
the analyzer correctly rejects a single-workload artifact as an incomplete
scored set. It is nevertheless material evidence that the current rebased
source remains below parity and that no retained micro-optimization has closed
the closure gap. The next candidate must target a broad call-boundary or
result-representation cost with a non-overlapping Amdahl budget, and it must
be compared to this exact source in alternating fresh processes.

### Scalar-result pool slot reuse under realistic load (2026-09-12)

The final 20 seconds of a 1 ms JFR CPU capture on the rebased source attributed
the largest closure cost to scalar-result transport: `ArrayList.add` (3,598
samples) followed by `RuntimeList.scalarAndRecycle`'s `ArrayList.clear` (292)
and pool `ArrayDeque.addFirst` (260). The pool's idle entries are private,
one-element lists, so the candidate preserves that slot while idle and replaces
it with `set(0, value)` at the next acquisition instead of clearing then adding
it. Lists that are no longer exactly one element still do not recycle.

The source-matched full `make` gate passed in 3m40s. A fresh default seven-pair
closure portfolio at
`/tmp/perf-closure-slot-reuse-highload-20260912/20260912T052521Z/portfolio.json`
was stable and authoritative for this workload: geometric mean 0.872110x,
median 0.877291x, and paired bootstrap interval 0.861442--0.882783x Perl.
That is a modest ~1.0% median gain from the preceding 0.868894x loaded-host
baseline, still well short of parity and still not whole-portfolio acceptance.
Retain this low-risk transport reduction; profile a broader call-boundary
representation next rather than expecting further pool micro-tuning to close
the remaining ~12% closure gap.

### Current method attribution and loaded-host refresh (2026-09-12)

The current pushed source was profiled with a 76-second 1 ms JFR recording at
`/tmp/perf-method-current-cpu-1ms-20260912.jfr`; the final measurement interval
kept the semantic checksum `4352`. CPU samples lead with `MortalList` deferred
owner processing, lexical-alias stack removal, and thread-local state. Matching
allocation samples identify the generated hot method body (`anon583.apply`,
1,728 samples), range iteration (1,099), and deferred tracked-owner queueing
(292). A bounded ASM dump at
`/tmp/perf-method-anon583-asm-20260912.log` confirms that each cached method
entry still allocates fresh `$self` and `$n` lexical cells before the existing
two-slot `@_` unpack lowering; the latter removes list transport but cannot
remove those copy cells.

The exact commit `a6cebfcba` completed a fresh seven-pair method portfolio at
`/tmp/perf-method-current-highload-20260912/20260912T053849Z/portfolio.json`.
Its median was 0.225718x Perl, geometric mean 0.220499x, and paired interval
0.202084--0.240159x. One engine warmup was unstable, so the analyzer correctly
marks this artifact protocol-inconclusive and non-authoritative; use it only
for target selection. The stable profile and generated bytecode support the
same next direction: derive a conservative static non-escape/effect contract
for immediate scalar unpack lexicals, then lower their allocation only behind
that contract and retain the ordinary fresh-cell path on every miss. Do not
pool cells or weaken mortal ownership merely to target this benchmark.

### Complete current-source loaded-host portfolio (2026-09-12)

The exact PR source `4a4a9ca08` completed the complete seven-workload,
seven-alternating-pair protocol at
`/tmp/perf-full-current-highload-20260912/20260912T055203Z/portfolio.json`.
The runner exited zero; every process preserved its semantic checksum and
warmup stabilization. The repository analyzer classifies the artifact
`authoritative: true`, `protocol_compliant: true`, and
`measurement_quality: stable`.

This is a decisive current baseline, not parity: the portfolio geometric mean
is 0.697486x Perl (bootstrap interval 0.627570--0.734469x), below the existing
1.05x acceptance target and the stronger per-workload 1.00x objective.
Workload medians are closure 0.873307x, method 0.218557x, numeric 1.168957x,
string 0.543285x, regex 0.521463x, Life 0.551230x, and JSON 2.304798x.
Method is unambiguously the floor (0.216271--0.228146x), while numeric and
JSON are above parity. Retain the measured closure slot-reuse improvement, but
do not mistake it for broad progress: the next implementation needs a
structural, ownership-proven reduction of the method call/body representation,
with generic fallback coverage; already rejected method-cell, direct-method,
trace-owner, and argument-frame micro-candidates must not be revived unchanged.

### Refreshed Life representation selection under load (2026-09-12)

A source/JAR-matched, one-pair diagnostic refreshed the Life allocation
evidence after the full portfolio: `timeout 600 perl
dev/bench/run_performance_portfolio.pl --workload life --pairs 1 --warmup-min
15 --warmup-max 15 --windows 30 --window-seconds 1 --jfr --jfr-max-size 64m
--output-dir /tmp/perf-life-current-jfr-20260912`. It exited successfully and
produced
`/tmp/perf-life-current-jfr-20260912/20260912T064330Z/portfolio.json` and
`life-pair-01.jfr`. Both engines stabilized, returned checksum `1243097892`,
and completed all 30 measurement windows. This is allocation-selection
evidence only, not a portfolio comparison.

The 76-second recording has 13,438 sampled allocations and 24 CPU samples.
Its dominant recurring allocation stack is native-word result construction:
`RuntimeScalarCache.getScalarInt(long)` through
`BitwiseOperators.unsignedResult(long)` for shift, `&`, `|`, and `^`; JFR also
records the accompanying `Long.valueOf` from `RuntimeScalar` construction.
The earlier wide-UV conversion rejection still applies: changing all UV
bitwise values to low-64-bit Java words regressed paired Life throughput.

The next Life candidate, if any, must instead prove a generic transient-result
ownership protocol: a bitwise result may be reused or transferred only when it
is compiler/runtime-proven not to be a lexical, lvalue, alias, tied/overloaded,
tainted, referenced, or container-observable scalar. A plain larger scalar
cache cannot help random word values, and an expression-shaped helper tied to
this benchmark's rule is out of scope. Establish permanent standard-Perl
coverage for both selected and rejected ownership cases before changing the
runtime; otherwise retain the current native-result representation.

### Rejected: transient bitwise-result cell reuse (2026-09-12)

The ownership protocol was implemented conservatively: only an untainted,
operator-created native-integer result could be overwritten by the next
numeric bitwise operation.  Lexicals, aliases, lvalues, tied and overloaded
values, referenced scalars, cached constants, and every fallback continued to
allocate normally.  `bitwise_transient_numeric_result.t` passed standard Perl,
the JVM backend, and the interpreter; the exact candidate also passed the
immutable full `make` gate under load in 3m41s.

It is rejected on measured throughput.  The source/JAR-matched seven-pair
Life protocol at
`/tmp/perf-life-transient-result-highload-20260912/20260912T065352Z/portfolio.json`
was stable and authoritative.  Its Life geometric mean was 0.498972x Perl,
median 0.501171x, and paired bootstrap interval 0.494890--0.502601x, with
pair ratios from 0.489492x to 0.503779x.  That is substantially below the
retained current full-portfolio Life median of 0.551230x.  The code and its
temporary regression test were removed with a non-destructive patch; do not
revive this result-cell mutation scheme without new evidence that explains
the regression.

### Correctness checkpoint: terminal list-global capture publication (2026-09-12)

While preparing the next regex measurement, a focused standard-Perl reducer
found that a list-context global match could return all captures correctly but
leave `@-` and `@+` describing only the final overall match after its terminal
failed cursor probe.  The failure is at the host Joni-adapter publication
boundary, not Joni matching: `RuntimeRegex` publishes the cursor after each
success, then invokes `find()` once more to establish exhaustion.  That final
failure was clearing the adapter's capture metadata behind the already-published
matcher.

`regex_cursor_snapshot_lifetime.t` is permanent project-owned coverage for
successive successful matches, a later failed match, and list-context `/g`.
It passes unchanged on system Perl and failed on the preceding PerlOnJava
source with `@-` = `(3)` and `@+` = `(5, undef, undef)` after `a1 b2`.
The corrected cursor preserves the previously published metadata only for its
terminal false probe; a new top-level failed match still preserves the prior
published state through the established runtime path.  The exact candidate
passed `timeout 1200 make` under the realistic host load in 6m43s (log
`/tmp/make-regex-global-cursor-state-v2-20260912.log`) and the focused test on
both backends.  This is correctness work, not a throughput claim; remeasure
the regex portfolio only after the committed source is the measured candidate.

That remeasurement is now complete for committed source `710c3d079`:
`/tmp/perf-regex-global-cursor-state-highload-20260912/20260912T072531Z/portfolio.json`
contains seven alternating fresh-process pairs collected with 20 active users
and load averages 12.60/52.54/48.24.  The analyzer report is authoritative,
protocol-compliant, and stable; it records a regex median of 0.495453x Perl,
geometric mean 0.498459x, and 95% paired interval 0.489005--0.509108x.  Its
single-workload scope correctly makes overall acceptance incomplete.  This
non-controlled, host-contended measurement neither attributes a regression to
the capture fix nor permits a throughput claim for it; it confirms that regex
remains a material parity deficit and that any next optimization needs a
separate parent/candidate protocol.

### Current method allocation selection refresh (2026-09-12)

A current-source, bounded JFR diagnostic completed successfully at
`/tmp/perf-method-current-jfr-highload-20260912/20260912T073342Z/` with one
pair, 15 fixed warmup windows, 30 one-second measurement windows, and a 64 MB
recording.  The source was the pushed `967814480` documentation checkpoint;
the selected JAR contains the identical runtime code from `710c3d079`.
The host had 20 active users and load averages 4.36/14.15/29.33.  Both engines
stabilized and retained method checksum `4352`; the one-pair/JFR run is
allocation selection evidence only, not a parity or candidate comparison.

Filtering the 47-second recording after its 15-second warmup leaves 5,358
`RuntimeScalar` allocation samples with 22.87 GB sampled weight.  The largest
inclusive paths cross `anon583.apply` (the generated `add` method),
`RuntimeCode.applyCachedMethod`, `invokeWithCallFrame`, and the outer range
body.  Execution sampling is intentionally sparse under contention, but it
again observes call lifecycle, argument-copy setup, active-lexical
registration, warning scope, and mortal cleanup.  This rules out treating a
method-frame pool, a ThreadLocal lookup shortcut, or range-iterator tuning as
a credible route from the current roughly 0.22x method ratio to parity.  The
next candidate remains a conservatively proven whole-body lowering that avoids
fresh argument-copy lexical cells only when their independent-cell semantics
cannot be observed; it must retain the ordinary cell path on every uncertain
body and be measured against a clean parent after focused semantic coverage.

### Direct immediate-argument-copy lowering under high load (2026-09-12)

Commits `45e0aefd9` and `516dde063` implement that JVM-only whole-body proof.
It recognizes an immediate `my ($x, ...) = @_` unpack only when the rest of
the body cannot observe independent lexical cells. The runtime tests the
entire frame atomically; missing, non-plain, debug, or LexAlias-exposed
arguments send every target through the existing fresh-cell path. The selected
branch avoids fresh cells and lexical-cleanup registration for borrowed cells.
The proof permits scalar reads, arithmetic, hash subscripts, and returns, but
rejects calls, references, dynamic source, loops, closures, and unknown AST.

`direct_argument_copy_lowering.t` and `direct_argument_binding_guard.t` pass
on system Perl and both PerlOnJava backends. `516dde063` passed `make` under
load in 7m14s (`/tmp/make-direct-argument-copy-hash-subscript-20260912.log`).
Its seven-pair method artifact is
`/tmp/perf-direct-argument-copy-hash-subscript-highload-20260912/20260912T083709Z/portfolio.json`:
median 0.228594x Perl, geometric mean 0.230222x, paired interval
0.209320--0.259276x. Checksums and warmup passed, but the 19-user host load
was 45.64/58.95/59.14, so this is protocol-compliant but inconclusive—not a
method or portfolio gain claim.

Selection instrumentation added after that run establishes that this candidate
does not activate in the standard loaded runtime. With the required global
LexAlias guard restored, a bounded method workload completed at host load
99.24/125.42/115.58 with checksum `4352`, 5,838,720 rejected frame checks, and
zero selected frames (`/tmp/direct-argument-copy-selection-restored-20260912.json`).
Removing the global guard made two existing permanent semantic tests fail:
`unit/overload/code_ref.t` and `unit/reusable_method_argument_frame.t`.
The restored implementation passed `make` in 7m17s
(`/tmp/make-direct-argument-copy-diagnostics-restored-20260912.log`) while
load peaked at 161.47. Therefore the whole-body lowering is not a viable
standard-runtime performance candidate; do not interpret its earlier ratios as
a gain or schedule parent/candidate comparison. Leave its conservative fallback
in place only until the implementation is removed or a narrower independently
proven observer model is designed.

### Rebased high-load method attribution triage (2026-09-12)

After the careful rebase onto `e7955af16`, the exact PR head `7ee98a988`
passed `make` in 7m33s. A bounded current-source/JAR JFR plus call-layer run
then completed under host load 45.21/68.90/80.28:
`/tmp/perf-rebased-method-attribution-20260912/20260912T094519Z/portfolio.json`.
It is deliberately **not** a throughput comparison or acceptance artifact (one
pair, three warmup windows, and `warmup_stabilized: false`), but it preserves
checksum `4352` and identifies the exact runtime JAR
`94ba6f6a5167361b9580a991b0ceb3ffdb9742142b9b06aebc326aed93e53ee9`.

The diagnostic reports 3,465,996 `shared-args-instance-apply` operations at
4,039 ns inclusive, 1,270 ns exclusive, and 1,673 bytes inclusive per
operation. Its JFR contains 966 allocation samples and seven GCs (111 ms total
pause), but the short recording includes startup/compiler activity and must not
be used to rank individual leaf helpers. It reconfirms that the next candidate
needs a general call-boundary ownership/effect proof; direct argument-copy
lowering remains rejected because its selection count is zero in the standard
runtime. Collect a longer steady-state profile before proposing a new
structural reduction.

### Plain-unblessed concat rejection (2026-09-12)

The rebased high-load string JFR capture at `f2b5dd924` repeatedly sampled
`RuntimeScalarType.blessedId` beneath warning-aware concatenation (181 matching
stack lines in
`/tmp/perf-rebased-string-steady-execution-20260912.txt`). Commit `280ae31d1`
temporarily added a narrow fast path after tied fetch and capture
materialization: when both resolved scalar types are at most `JAVAOBJECT`, it
skipped effective-blessing queries and no-op stringification. References,
readonly scalars, formats, proxies, and tied values retained the prior path.

`string_concat_bless_id_fastpath.t` passes on system Perl; the full project
gate passed in 7m16s
(`/tmp/make-string-plain-unblessed-fastpath-20260912.log`). The matching
candidate JFR run under high load completed with checksum `24` at
`/tmp/perf-string-plain-unblessed-candidate-jfr-20260912/20260912T100254Z/portfolio.json`;
matching `blessedId` stack lines fell from 181 to 2. Different host contention
made GC counts non-comparable (93 versus 129), so a clean alternating
comparison was required. That comparison used seven parent/candidate pairs,
15 one-second measurement windows per run, fixed 15-window warmup, and
checksum `24` in every run. Under the host's realistic high load, the median
pair ratio was 0.9980 (-0.20%) and the geometric mean was 1.0191 (+1.91%);
the apparent +16.89% result in one pair coincided with the parent receiving
only 0.845 CPU seconds per wall second. This is not a material or robust gain,
so the fast path was removed. The JFR reduction was real but did not translate
to useful end-to-end throughput; retain the existing overload-aware path and
do not revisit this leaf shortcut without a structural reduction.

That longer one-pair diagnostic completed at PR head `b65ab4924` under load
30.38/58.59/74.66:
`/tmp/perf-rebased-method-steady-jfr-20260912/20260912T094752Z/portfolio.json`.
It records 16,358,382 shared-frame calls at 4,001 ns inclusive, 1,200 ns
exclusive, and 1,676 bytes inclusive per call; its 15 warmup windows still did
not stabilize, so it remains selection evidence rather than a throughput
comparison. The 3,188 allocation samples and 30 GCs (983 ms total pause) show
the same shared path. Steady CPU samples repeatedly cross fresh argument-value
copying (`setFreshScalarsFromArgumentArray`), alias-frame checks,
`methodArgsWithSelf`, `enterCall`, and mortal cleanup. Each has real Perl
ownership/caller semantics or lacks a non-overlapping Amdahl budget. Reject
further unproven call-boundary leaf shortcuts; a future candidate must first
prove a general structural ownership/effect reduction.

## Historical workstream sequence — not the current task queue

Start with the audited first-work-session plan at the top of this document.
The list below retains the earlier broader workstream history and candidates;
several proposed comparisons were subsequently completed or rejected.

1. **Completed: enforce the acceptance reporter (`ff7dd7d85`).** The unit
   suite proves that incomplete portfolios and a closure interval crossing
   1.00x cannot pass.
2. **Completed: make `JSON::PP::_string` JVM-compilable.** The permanent
   labeled-loop and JSON tests prove standard Perl behavior, both backends, and
   the absence of `_string` interpreter fallback. The cleanup-level representation
   is reference-typed end-to-end so JVM frames cannot merge an uninitialized
   reference slot with an integer cleanup level.
3. **Completed for selection: attribute the newly compiled hot path.** The
   post-warmup JFR and per-CV call collector isolate `_string`, `_next_chr`,
   and `_white`; their host-contended timing remains diagnostic-only. Preserve
   the raw per-CV counts and collect a quiet-host confirmation before making
   a throughput claim. Do not optimize module loading, ASM compilation, or an
   individual sampled runtime helper without its non-overlapping Amdahl budget.
4. **Completed for allocation selection: rebind pooled Joni matchers and bound
   subject encoding caches.** The warning-hook forwarding lambda, byte-mode
   identity maps, a bounded feature-free Joni pool, and a per-thread bounded
   subject-input cache are in place; neither cache retains an unbounded subject
   set.
   The cross-subject snapshot, subject-cache mutation, and non-Unicode
   warning metadata regressions plus the
   full gate cover their safety. Next, use alternating fresh-process pairs on a
   quiet host to measure the non-overlapping throughput effect, then profile
   residual byte-array construction. Generic `RuntimeCode` call frames remain
   the next larger CPU budget; revisit direct-leaf lowering only under its
   explicit marker-ownership gate.
5. **Completed: measure fresh-lexical `@_` unpack lowering by scope.** The
   broad RHS transport removal regressed at 0.9636x median and was narrowed
   back to one/two slots.  The fixed-slot lowering gained 1.0495x median in
   seven pairs and remains; it is not portfolio acceptance evidence.
6. **Derive a whole-body eligibility proof before changing generated-method
   scalar representation.** The current method JFR and seven-pair loaded-host
   portfolio retain generated lexical setup as the leading selection target,
   but `direct_argument_binding_guard.t` rejects argument-cell borrowing.
   Identify a non-escaping static body shape, its runtime plain-value guards,
   and a fallback before considering stack-local or leased lexical cells.
   Prove lvalue, aliasing, destructor, exception, control-flow, recursion,
   debugger, and dynamic-source behavior; do not broaden the existing `@_`
   frame cache into a generic cell pool. The active-pad registration experiment
   is rejected; select a lowering that removes a scalar representation or a
   complete operation, rather than one that merely changes its observability.
7. **Reassess a `methodArgsWithSelf` reduction only after that selection.** Correct the initial-sample
   weighting before ranking this allocation source. Do not pool or reuse a frame
   until ownership is proven across retained `@_` references, tail calls,
   exception cleanup, and non-local control flow. Prefer a narrow method-call
   representation whose fallback preserves the current `RuntimeArray` ABI.
   The first candidate is a per-depth runtime-local frame only for CVs whose
   sole argument use is the recognized direct fresh unpack; add selected and
   rejected observer/recursion/alias coverage before implementing it.
8. **Measure the direct scalar-result recycle repair against its parent.**
   Use alternating fresh-process method pairs on a quiet host, with allocation
   attribution. Retain the generic `RuntimeList` path for list, lvalue, tail
   call, and non-local-control-flow cases; do not widen result recycling unless
   the next narrow guard is standard-Perl validated and proves ownership on
   both backends.
9. **Use the exact opt-in scalar-result counters to find any remaining bypass.**
   Attribute acquire, recycle, and rejected-recycle outcomes after warmup; a
   sampled JFR allocation site alone cannot establish that a caller fails to
   recycle. Keep the counters absent from normal timing runs.
10. **Only then revisit direct-leaf lowering if marker ownership is proven.**
   First demonstrate a selected generated JSON CV, retain the generic path,
   and prove selected/rejected behavior on standard Perl and both backends.
11. **Test the hot-eval hypothesis only after that profile.** `JPERL_EVAL_NO_INTERPRETER=1`
   previously moved the JSON diagnostic by only about 5%. Verify which hot CVs
   changed backend and whether they account for the remaining time. Do not
   build a promotion mechanism until this activation evidence supports it.
12. **Screen each structural candidate with an Amdahl budget.** Record the
   non-overlapping fraction it affects, its guard hit rate, fallback cost,
   expected residual cost, allocations, and required speedup. Reject a change
   that cannot close a meaningful portion of a scored workload's budget even
   if it reduces a frequent opcode.
13. **Implement only measured hot paths.** Candidate classes include repeated
   interpreter call sequences, dynamic regex scope setup, lexical cleanup, and
   JSON::PP-specific executed patterns. Preserve the generic slow path and add
   standard-Perl regression coverage before backend and full-suite validation.
14. **Measure parent and candidate from the same controlled source state.**
   Start with a paired diagnostic only to answer the candidate's cost question.
   Run the complete seven-pair portfolio only after it demonstrates a material
   reduction. Retain compact evidence in the main design and update this
   handoff with exact commit hashes and remaining budgets.

## References

- [Main performance design](performance-over-perl.md)
- [Bytecode interpreter architecture](interpreter.md)
- [Profiling skill](../../.agents/skills/profile-perlonjava/SKILL.md)
