# Performance over Perl handoff

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

## Current evidence and budget

### Resume here: evidence audit and priorities (2026-09-10)

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

## Required next sequence

Start with the evidence audit's immediate actions above. The list below
retains the broader workstream history and longer-term candidates.

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
6. **Select a generated-method scalar-churn reduction before changing call
   frames.** The corrected post-warmup JFR makes generated method-body scalar
   allocation the leading residual budget. Identify a semantics-preserving
   scalar operation with a non-overlapping Amdahl budget; retain the generic
   path and prove lvalue, aliasing, destructor, exception, and control-flow
   behavior before measuring it.
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
