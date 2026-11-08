---
name: profile-perlonjava
description: Profile and optimize PerlOnJava runtime performance with Java Flight Recorder. Use when investigating runtime bottlenecks, identifying CPU or allocation hotspots, measuring optimizations, or diagnosing performance regressions.
---

# Profile PerlOnJava

Read and follow [batching and validation guidance](../debug-perlonjava/references/testing-cadence.md).
Use this workflow for runtime performance investigations and optimizations.
The end-to-end benchmark protocol and worked guidance live in
[`dev/bench/README.md`](../../../dev/bench/README.md) and
[`dev/design/performance-over-perl.md`](../../../dev/design/performance-over-perl.md).

## Investigation workflow

1. **Define the question and metric.** Name the exact workload, expected
   behavior, comparison reference, and acceptance threshold before changing
   code. Separate compute-only work from I/O and end-to-end behavior. Keep the
   rendering-disabled Life benchmark distinct from terminal-output runs of
   `examples/life_bitpacked.pl`.
2. **Capture a reproducible baseline.** Record source and JAR hashes, JDK/JVM
   settings, Perl version/configuration and source revision, workload/input
   hashes, host load, requested/observed process priority, and commands. Use
   correctness checksums and equal operation counts. Compare the candidate to
   its exact parent for attribution and to pinned optimized blead for Perl
   parity; these answer different questions.
3. **Profile outside scored timing.** Use JFR CPU, allocation, GC, lock, thread,
   and code-cache events as appropriate. Inspect HotSpot compilation,
   inlining, uncommon-trap, and deoptimization evidence. A sample identifies a
   hypothesis; it does not establish that removing that work improves total
   throughput. Do not run a profiler during measured benchmark windows.
4. **Make one attributable optimization at a time.** State the fast-path
   preconditions, add focused coverage for them, and retain the general
   fallback. Validate Perl-level behavior with standard Perl and run the
   required JVM/interpreter checks. Keep unrelated hypotheses in separate
   candidate changes so regressions and speedups remain attributable.
5. **Measure and decide.** Use persistent workers, stabilized warmup,
   randomized paired order, enough independent pairs for the workload's
   acceptance contract, and paired confidence intervals. Keep raw samples,
   exclusions and reasons, checksums, and process logs. Retain a change only
   when the relevant interval meets the stated threshold; a single run, a
   point estimate, or a portfolio mean hiding a slow workload is insufficient.
6. **Batch expensive validation.** Finish related implementation and focused
   checks before one full `nice -n 19 timeout 3600 make` gate on the immutable
   candidate. Do not mutate a checkout or share its build artifact while a
   gate or benchmark is running. On the busy shared host, avoid repeated full
   builds after individual edits. Push only after the local candidate passes,
   then check hosted CI.

## Profiling with async-profiler

Async-profiler gives lower-overhead, allocation-unbiased CPU flat/collapsed
profiles that complement JFR.  The installed version is at
`/opt/homebrew/bin/asprof` (async-profiler 4.5 on this host).

### Finding the JVM PID

`./jperl` ends with `exec java …`, so the bash wrapper is **replaced** by
the JVM.  Wrap the run in `timeout` and find the PID via the timeout's child
tree — never the wrapper PID:

```bash
timeout 120 ./jperl dev/bench/benchmark_eval_string.pl > /tmp/bench.txt &
TIMEOUT_PID=$!
sleep 5          # let the JVM warm up
JAVA_PID=$(pgrep -P $TIMEOUT_PID java 2>/dev/null || \
           pgrep -f "perlonjava.*\.jar" 2>/dev/null | head -1)
echo "Java PID: $JAVA_PID"
```

On a quiet machine the benchmark finishes in ~20 s; shorten the warmup sleep
(≤5 s) and increase the profiling duration so the JVM is still alive when
`asprof` attaches.

### Capturing and converting a profile

```bash
# Capture a JFR profile (CPU only, 40 s)
/opt/homebrew/bin/asprof -d 40 -f /tmp/profile.jfr $JAVA_PID

# Convert to collapsed-stack format for analysis
java -cp /opt/homebrew/Cellar/async-profiler/4.5/libexec/jfr-converter.jar \
     one.convert.Main -o collapsed --cpu /tmp/profile.jfr /tmp/profile_collapsed.txt

# Total sample count
awk '{n+=$NF} END{print n}' /tmp/profile_collapsed.txt
```

### Analysing the collapsed output

Each line is `frame1;frame2;...;leaf N`.  Count leaf frames by class:

```python
from collections import defaultdict
counts = defaultdict(int)
with open('/tmp/profile_collapsed.txt') as f:
    for line in f:
        parts = line.rsplit(' ', 1)
        if len(parts) != 2: continue
        stack, cnt = parts
        try: cnt = int(cnt.strip())
        except: continue
        leaf = stack.split(';')[-1]
        cls = leaf.split('.')[-2].split('/')[-1].split('[')[0] if '.' in leaf else leaf[:30]
        counts[cls] += cnt
for k, v in sorted(counts.items(), key=lambda x: -x[1])[:30]:
    print(f'{v:5d}  {k}')
```

To show full call chains through a specific class:

```python
with open('/tmp/profile_collapsed.txt') as f:
    for line in f:
        parts = line.rsplit(' ', 1)
        if len(parts) != 2: continue
        stack, cnt = parts
        try: cnt = int(cnt.strip())
        except: continue
        frames = stack.split(';')
        for i, fr in enumerate(frames):
            if 'TargetClass' in fr:
                context = ';'.join(frames[max(0,i-2):i+3])
                print(f'{cnt:4d}  {context}')
                break
```

### Reading the profile

*   `[j]` = JIT-compiled frame, `[i]` = interpreter frame, `[0]` = C1 tier.
    A method appearing as a leaf with `[0]` or `[i]` is not yet hot-compiled
    and will be faster once the JIT promotes it.
*   GC frames (`G1ParScanThreadState`, `GenericTaskQueueSet`,
    `OopOopIterateBackwardsDispatch`) indicate **allocation pressure**: too many
    long-lived objects are being created.  Identify which allocations promote to
    the old generation (HashMap/TreeMap/ArrayList growth that never shrinks) and
    bound or clear them.
*   `ThreadLocal$ThreadLocalMap` samples indicate frequent per-thread state
    lookups; consider caching the result in a local variable if called in a hot
    loop.
*   `HashMap.getNode` samples are usually leaf-attributed to the calling class;
    use the call-chain analysis to identify which HashMap is hot and whether
    it can be replaced with an array, a bitmask, or direct fields.

### Creating a JFR recording with the JDK

```bash
JPERL_OPTS="-XX:StartFlightRecording=duration=60s,filename=/tmp/perlonjava.jfr" \
  timeout 90 ./jperl path/to/workload.pl
```

Inspect with:

```bash
jfr summary /tmp/perlonjava.jfr
jfr print --events jdk.ExecutionSample /tmp/perlonjava.jfr
```

For allocation or GC questions, select the relevant JFR events and preserve
the recording alongside the benchmark report. Profiles explain a measured
result; they do not replace paired end-to-end measurements.

## I/O workloads

Treat terminal output as its own workload. For `examples/life_bitpacked.pl`,
the default mode prints the grid while the acceptance `life` worker disables
rendering. Compare identical arguments and output bytes using the same sink.
Measure a real terminal or PTY separately from redirected output, verify byte
counts and hashes after timing, and report bytes per second and elapsed time.
Do not fold terminal latency into compute-only parity results.

## Reporting and issue updates

Link the source-matched report, JFR/profile artifacts, benchmark protocol, and
remaining work from the relevant issue. Read the
[issue triage guide](../../../docs/guides/issue-triage.md) when classifying or
updating an issue. Keep its agent claim/checkpoint concise and link complete
measurement artifacts; use established repository labels and workflows.
