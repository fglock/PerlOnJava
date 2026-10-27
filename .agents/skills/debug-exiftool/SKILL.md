---
name: debug-exiftool
description: Debug and fix Image::ExifTool test failures in PerlOnJava while preserving upstream tests and format fixtures.
---

# Debug Image::ExifTool

Follow [AGENTS.md](../../../AGENTS.md), the
[testing cadence](../debug-perlonjava/references/testing-cadence.md) and the
[general debugging workflow](../debug-perlonjava/SKILL.md). Keep detailed logs
outside the current checkpoint. Fix confirmed differences in PerlOnJava.

## Reproduce with the upstream harness

Pin the exact ExifTool release in use rather than assuming an old extracted
version or test count. Keep its source, tests, images and expected output intact.
Run from the distribution root: tests use `t/TestLib.pm`, `t/images/` and
`t/<TestName>_N.out`. Resolve the absolute launcher for the intended built source.

```bash
# cwd: the pinned upstream distribution root
# exif_launcher: absolute launcher for the validated checkout
timeout 300 perl -Ilib t/Writer.t > /tmp/exiftool-perl.log 2>&1
timeout 300 "$exif_launcher" -Ilib t/Writer.t > /tmp/exiftool-jvm.log 2>&1
JPERL_INTERPRETER=1 timeout 300 "$exif_launcher" -Ilib t/Writer.t > /tmp/exiftool-interpreter.log 2>&1
```

Capture exit status separately and read full logs. A timeout is not a pass or
proof of an infinite loop. Use bounded concurrency for a suite, one complete log
per test, rather than launching every test in the background. Never rebuild the
shared JAR during these runs.

## Trace the first difference

1. Map `not ok N` to the `.t` operation: read, write, binary comparison or tag
   extraction. `check()` compares extracted tags; `writeInfo()` invokes
   SetNewValue/WriteInfo; `binaryCompare()` verifies exact bytes.
2. Compare `t/<TestName>_N.failed` with the expected `.out`. Sequential edits
   can cascade; investigate the first difference before later assertions.
3. Reduce the Perl construct, compare system Perl, then both backends. Add
   permanent project coverage, validate it with Perl, retain failing-parent
   evidence, and leave upstream tests/fixtures unchanged.
4. Read [diagnostics.md](references/diagnostics.md) for the closure ordering and
   register invariants, source map, format-specific leads, and every retained
   compiler/runtime troubleshooting tip. Consult the relevant section rather
   than loading the full guide for each update.

## Fallback and instrumentation

ExifTool's large routines can reach the bytecode interpreter, including from
otherwise JVM-executed code. `JPERL_SHOW_FALLBACK=1` helps locate large-sub
fallback; use `JPERL_EVAL_TRACE=1` for eval paths. Compare disassembly and use the
[parity skill](../interpreter-parity/SKILL.md) when the paths differ.

For compiler sizing/frame failures use `JPERL_BYTECODE_SIZE_DEBUG`,
`JPERL_ASM_DEBUG` and optional `JPERL_ASM_DEBUG_CLASS`. Control-flow tracing uses
`JPERL_TRACE_CONTROLFLOW`; spill sizing uses `JPERL_SPILL_SLOTS`. For I/O/flush
issues use `JPERL_IO_DEBUG`/`JPERL_STDIO_DEBUG`, and for loading use
`JPERL_REQUIRE_DEBUG`. Other flags are in the general debugging reference.
Temporary STDERR instrumentation can inspect runtime registers after captured
values are copied, or pinpoint an upstream value; never commit upstream edits
or leftover debug prints. Preserve original upstream files before instrumenting.

## Validate and report

Batch the shared runtime/compiler fix, build through `nice -n 19 make`, then
run the focused ExifTool cases and relevant core coverage such as `op/lexsub.t`
when block/sub emission changes. Use `perl5_t/t/` with the Perl test runner for
core harness support; ensure subprocess tests can locate `jperl` on PATH.
Report the pinned release, exact source/JAR, first difference, regression tests,
both-backend results and remaining failures with log links in a short checkpoint.

## Issue coordination and reporting

Use the [triage guide](../../../docs/guides/issue-triage.md) for issue evidence,
agent claims, dependencies and concise checkpoints. Follow
[AGENTS.md](../../../AGENTS.md) for work preservation and validation.
