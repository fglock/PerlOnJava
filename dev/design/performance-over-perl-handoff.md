# Performance over Perl handoff

## Current state

The acceptance target remains unmet: the default JVM portfolio must reach at
least 1.05x Perl for its geometric mean and closure/Life anchors, with every
workload at least 0.90x. The last authoritative portfolio remains far below
that target; JSON is the lowest workload.

The current branch contains validated reductions to call-boundary allocation,
ordinary `substr` indexing, interpreter literal allocation, and interpreter
simple-leaf regex-state setup. These are retained because they preserve Perl
semantics, but none is acceptance evidence on the loaded host.

## Latest attribution

A bounded JSON call-layer capture at `142be63b2` found that the common
shared-argument instance category spends about 30.98 microseconds and 28,977
bytes inclusively per call, while only about 3.74 microseconds and 3,384 bytes
are exclusive generic-boundary cost. The next candidate must therefore reduce
interpreter/body work, rather than another caller-frame micro-optimization.

This branch adds `BytecodeOpcodeDiagnostics`, an opt-in per-opcode counter.
It is disabled in ordinary runs and is enabled only with:

```text
-Dperlonjava.bytecodeOpcodeDiagnostics=true
-Dperlonjava.bytecodeOpcodeDiagnosticsOutput=/tmp/json-opcodes.json
```

The full `make` gate passed after adding it (5m27s). Its instrumentation cost
makes it attribution-only, not throughput evidence.

## Resume procedure

1. Run a bounded JSON workload with the two properties above, preserving its
   JSON report under `/tmp`.
2. Identify the top dispatch families and correlate them with JFR CPU and
   allocation stacks.
3. Choose a semantics-preserving structural target (for example hot-eval
   promotion or a specialized opcode sequence), add permanent Perl-oracle
   coverage, and validate both backends plus `make`.
4. Only after a material diagnostic reduction, run the full seven-pair,
   seven-workload non-JFR portfolio under a quiet host.

## References

- [Main performance design](performance-over-perl.md)
- [JFR profiling workflow](../..//.agents/skills/profile-perlonjava/SKILL.md)
- [Bytecode interpreter architecture](interpreter.md)
