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

## Creating a JFR recording

Use the JDK that built the candidate. For a bounded recording through the
project launcher, pass JVM options with `JPERL_OPTS` and keep a timeout around
the run:

```bash
JPERL_OPTS="-XX:StartFlightRecording=duration=60s,filename=/tmp/perlonjava.jfr" \
  timeout 90 ./jperl path/to/workload.pl
```

Inspect the recording with the JDK's `jfr` tool:

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
updating an issue. Use established repository labels and workflows.
