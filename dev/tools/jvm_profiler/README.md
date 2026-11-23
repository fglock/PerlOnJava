# JVM Profiler

Skill and reference guide for profiling PerlOnJava with JVM tooling.

## Purpose

This directory contains the profiling skill for PerlOnJava — a detailed guide
to measuring and diagnosing performance issues using JVM-native tools
(JFR, async-profiler, `-XX:+PrintCompilation`, etc.).

## Contents

| File | Description |
|------|-------------|
| [SKILL.pm](SKILL.pm) | Full profiling guide: timing, JFR recording, async-profiler, `-XX:+PrintCompilation`, and PerlOnJava-specific patterns |

## Quick Reference

```bash
# Time a run
time ./jperl script.pl

# JFR recording (requires JDK 11+)
JPERL_OPTS="-XX:StartFlightRecording=filename=/tmp/run.jfr,duration=30s" \
    timeout 60 ./jperl script.pl

# Print compilation activity
JPERL_OPTS="-XX:+PrintCompilation" timeout 60 ./jperl script.pl

# Show bytecode size of generated apply() methods
JPERL_BYTECODE_SIZE_DEBUG=1 timeout 60 ./jperl script.pl
```

## See Also

- `dev/bench/` — Benchmark scripts for measuring specific operations
- `dev/design/dbixclass-timeout-jit-analysis.md` — Example JFR-based root-cause analysis
- `dev/design/reduce-apply-bytecode.md` — Fixing the JIT failure identified via JFR
- `.agents/skills/profile-perlonjava/` — Agent skill that wraps this guide
