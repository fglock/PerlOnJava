# Diagnostic Traces

Raw diagnostic output captured from instrumented PerlOnJava builds during
debugging sessions.

## Purpose

When investigating a subtle runtime bug (e.g. reference-count under/overflow,
unexpected DESTROY ordering, memory leaks), developers enable compile-time
instrumentation flags that emit structured log lines at runtime. The resulting
output is saved here so it can be shared, compared across runs, and referenced
in write-up documents.

**These files are raw data — not analysed write-ups.** Analysed documents that
explain the root cause and propose fixes belong in `dev/design/` instead.

## File Naming Convention

```
<subsystem>-<symptom>[-<variant>].txt
```

Examples: `cmop-metaclass-refcount-underflow.txt`,
`cmop-metaclass-refcount-with-queue-sites.txt`

## Contents

| File | What was instrumented | Issue investigated |
|------|-----------------------|--------------------|
| `cmop-metaclass-refcount-underflow.txt` | Selective refcount tracing (`JPERL_REFCOUNT_DEBUG`) | Class::MOP::Class object refcount drops to 0 while still live |
| `cmop-metaclass-refcount-with-queue-sites.txt` | Refcount + queue-site annotations | Same issue with call-site detail added |
| `cmop-metaclass-with-owner-tracking.txt` | Refcount + owner-tracking | Same issue with owning-scalar annotations |

## How to Capture a Trace

Enable the relevant instrumentation flag(s) before running the test:

```bash
# Refcount tracing (example — actual flag names vary by subsystem)
JPERL_REFCOUNT_DEBUG=1 timeout 60 ./jperl -Ilib t/failing_test.t > /tmp/trace.txt 2>&1
cp /tmp/trace.txt dev/diagnostic-traces/subsystem-symptom.txt
```

Always use `timeout` to prevent orphaned JVM processes (see `AGENTS.md`).

## See Also

- `dev/design/` — Analysed write-ups and fix plans derived from these traces
- `dev/architecture/weaken-destroy.md` — Refcount / DESTROY architecture
- `AGENTS.md` — Warning about orphaned JVM processes when running tests
