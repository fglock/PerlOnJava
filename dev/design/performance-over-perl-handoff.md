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

## Required next sequence

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
5. **Only then revisit direct-leaf lowering if marker ownership is proven.**
   First demonstrate a selected generated JSON CV, retain the generic path,
   and prove selected/rejected behavior on standard Perl and both backends.
6. **Test the hot-eval hypothesis only after that profile.** `JPERL_EVAL_NO_INTERPRETER=1`
   previously moved the JSON diagnostic by only about 5%. Verify which hot CVs
   changed backend and whether they account for the remaining time. Do not
   build a promotion mechanism until this activation evidence supports it.
7. **Screen each structural candidate with an Amdahl budget.** Record the
   non-overlapping fraction it affects, its guard hit rate, fallback cost,
   expected residual cost, allocations, and required speedup. Reject a change
   that cannot close a meaningful portion of a scored workload's budget even
   if it reduces a frequent opcode.
8. **Implement only measured hot paths.** Candidate classes include repeated
   interpreter call sequences, dynamic regex scope setup, lexical cleanup, and
   JSON::PP-specific executed patterns. Preserve the generic slow path and add
   standard-Perl regression coverage before backend and full-suite validation.
9. **Measure parent and candidate from the same controlled source state.**
   Start with a paired diagnostic only to answer the candidate's cost question.
   Run the complete seven-pair portfolio only after it demonstrates a material
   reduction. Retain compact evidence in the main design and update this
   handoff with exact commit hashes and remaining budgets.

## References

- [Main performance design](performance-over-perl.md)
- [Bytecode interpreter architecture](interpreter.md)
- [Profiling skill](../../.agents/skills/profile-perlonjava/SKILL.md)
